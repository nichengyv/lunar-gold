package com.capsule.agentkey.eval.rag;

import com.capsule.agentkey.config.PendingFileChangePrecheckCallback;
import com.capsule.agentkey.config.SpringAiEmbeddingModelConfig;
import com.capsule.agentkey.service.VectorStoreService;
import com.capsule.agentkey.service.VectorStoreService.SearchResult;
import com.capsule.agentkey.service.VectorStoreService.VectorRecord;
import com.capsule.agentkey.service.rag.RagCandidate;
import com.capsule.agentkey.service.rag.RankFusion;
import com.capsule.agentkey.service.rag.RrfRankFusion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P5-07-3：难例检索评测与模型 reranker 对照。
 *
 * <p>在真实内嵌 PostgreSQL + pgvector 上写入“基础语料 + 难例干扰切片”，
 * 用真实 embedding 复跑，只对比两种配置：hybrid（融合后截断 top-5）与
 * hybrid+rerank（融合后 20 条交给真实 reranker 取 top-5）。</p>
 *
 * <p>缺真实 embedding / rerank Key 时不运行；禁止用模拟向量结果替代结论。</p>
 */
@EnabledIfEnvironmentVariable(named = "DESKTOP_PG_RUNTIME", matches = ".+")
public class RagRerankEvalRunner {

    static final Path DATASET_DIR = Paths.get("eval", "rag-datasets");
    static final Path BASE_CHUNKS = DATASET_DIR.resolve("chunks.jsonl");
    static final Path HARD_CHUNKS = DATASET_DIR.resolve("hard-chunks.jsonl");
    static final Path HARD_QUERIES = DATASET_DIR.resolve("hard-queries.jsonl");
    static final Path REPORT_DIR = Paths.get("eval", "rag-reports");
    static final List<String> CONFIGS = List.of("hybrid", "hybrid+rerank");

    static final int CANDIDATE_K = 20;
    static final int TOP_K = 5;
    static final int LATENCY_REPEATS = 3;
    static final int MIN_HARD_QUERIES = 12;
    static final int MIN_DISTRACTOR_CHUNKS = 16;
    static final int MIN_NOISE_QUERIES = 2;
    static final int MIN_HARD_TARGETS_LOW_RANK = 80;
    static final float VECTOR_MIN_SCORE = 0.5f;
    static final float VECTOR_GATE_SCORE = 0.55f;
    static final float LEXICAL_MIN_SCORE = 0.3f;
    static final float LEXICAL_GATE_SCORE = 0.35f;
    static final int RRF_K = 60;

    static final double Q1_TOP1_GAIN_PP = 10d;
    static final double Q2_NDCG_GAIN_PP = 5d;
    static final double Q3_Q4_TOLERANCE_PP = 1d;
    static final double Q6_RERANK_P95_MS = 300d;
    static final double Q6_TOTAL_P95_MS = 1000d;
    static final double Q7_TOKEN_RATIO = 1.2d;
    static final double Q7_COST_PER_QUERY = 0.01d;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String PG_USER = "postgres";
    private static final String PG_DATABASE = "postgres";
    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;

    @Test
    void runEval() throws Exception {
        execute();
    }

    /** 跑完整评测并写出报告；门禁测试复用同一入口，保证口径一致。 */
    static Outcome execute() throws Exception {
        String embeddingKey = embeddingKey();
        String rerankKey = rerankKey();
        if (embeddingKey == null || rerankKey == null) {
            throw new IllegalStateException("前置缺口：需要真实 embedding 与 rerank Key。"
                    + " embedding=" + (embeddingKey == null ? "missing(RAG_EVAL_EMBEDDING_KEY/EMBEDDING_API_KEY)" : "ok")
                    + "，rerank=" + (rerankKey == null ? "missing(RAG_EVAL_RERANK_KEY)" : "ok"));
        }

        List<Chunk> baseChunks = loadChunkFile(BASE_CHUNKS);
        List<Chunk> hardChunks = loadChunkFile(HARD_CHUNKS);
        List<Chunk> chunks = mergeChunks(baseChunks, hardChunks);
        List<QueryCase> queries = loadQueries(chunks);
        ModelEmbedding embedding = new ModelEmbedding(embeddingKey);
        RerankClient reranker = new RerankClient(
                envOrDefault("RAG_EVAL_RERANK_URL", "https://api.siliconflow.cn/v1/rerank"),
                envOrDefault("RAG_EVAL_RERANK_MODEL", "BAAI/bge-reranker-v2-m3"),
                rerankKey,
                Integer.parseInt(envOrDefault("RAG_EVAL_RERANK_TIMEOUT_MS", "10000")));

        Path dataDir = Files.createTempDirectory("rag-rerank-eval-pg");
        EmbeddedPostgres postgres = null;
        SingleConnectionDataSource dataSource = null;
        try {
            postgres = startPostgres(dataDir);
            String jdbcUrl = postgres.getJdbcUrl(PG_USER, PG_DATABASE);
            Flyway.configure()
                    .dataSource(jdbcUrl, PG_USER, PG_USER)
                    .locations("classpath:db/migration")
                    .callbacks(new PendingFileChangePrecheckCallback())
                    .load()
                    .migrate();

            dataSource = new SingleConnectionDataSource(jdbcUrl, PG_USER, PG_USER, true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            // 语料规模较小：删除 HNSW 让排序退化为精确扫描，保证同一输入可复现
            jdbc.execute("DROP INDEX IF EXISTS idx_vector_record_embedding");
            jdbc.update("DELETE FROM vector_record WHERE user_id = ?", USER_ID);

            VectorStoreService store = new VectorStoreService(jdbc);
            Map<Long, Chunk> byChunkId = new HashMap<>();
            List<VectorRecord> records = new ArrayList<>();
            for (Chunk chunk : chunks) {
                records.add(new VectorRecord(chunk.chunkId(), chunk.documentId(), USER_ID,
                        chunk.content(), embedding.embed(chunk.content()), "knowledge", PROJECT_ID));
                byChunkId.put(chunk.chunkId(), chunk);
            }
            store.addVectors(records);

            RankFusion fusion = new RrfRankFusion(RRF_K);
            Map<String, Metrics> overall = new LinkedHashMap<>();
            CONFIGS.forEach(config -> overall.put(config, new Metrics()));
            List<Calibration> calibrations = new ArrayList<>();
            Map<String, Integer> noiseRejected = new HashMap<>();
            int noiseTotal = 0;

            for (QueryCase query : queries) {
                Evaluation evaluation = evaluateQuery(store, fusion, embedding, reranker, query, byChunkId);
                if (query.expectEmpty()) {
                    noiseTotal++;
                    for (String config : CONFIGS) {
                        if (evaluation.hits().get(config).isEmpty()) {
                            noiseRejected.merge(config, 1, Integer::sum);
                        }
                    }
                    continue;
                }
                calibrations.add(evaluation.calibration());
                for (String config : CONFIGS) {
                    overall.get(config).accumulate(query, evaluation.hits().get(config), byChunkId);
                    // hybrid 不含 rerank，延迟口径必须区分，否则基线的 total p95 会被 rerank 时间污染
                    overall.get(config).addLatencies(evaluation.retrievalNanos().get(config),
                            "hybrid+rerank".equals(config) ? evaluation.rerankNanos() : 0L);
                    if ("hybrid+rerank".equals(config)) {
                        overall.get(config).rerankTokens += evaluation.rerankTokens();
                        overall.get(config).rerankCalls += evaluation.rerankCalls();
                        overall.get(config).candidateTokens += evaluation.candidateTokens();
                    }
                }
            }

            ObjectNode report = buildReport(embedding, reranker, chunks.size(),
                    hardChunks.size(), queries.size(), calibrations,
                    overall, noiseTotal, noiseRejected);
            Path reportFile = writeReport(report);
            printSummary(report, reportFile);
            return new Outcome(report);
        } finally {
            if (dataSource != null) {
                dataSource.destroy();
            }
            if (postgres != null) {
                postgres.close();
            }
            deleteQuietly(dataDir);
        }
    }

    private static Evaluation evaluateQuery(VectorStoreService store, RankFusion fusion,
                                            ModelEmbedding embedding, RerankClient reranker,
                                            QueryCase query, Map<Long, Chunk> byChunkId) throws Exception {
        float[] queryVector = embedding.embedQuery(query.query());

        List<SearchResult> rawVector = List.of();
        List<SearchResult> rawLexical = List.of();
        List<Long> retrievalNanos = new ArrayList<>();
        List<RagCandidate> fused = List.of();
        boolean vectorGated = true;
        boolean lexicalGated = true;

        for (int repeat = 0; repeat < LATENCY_REPEATS; repeat++) {
            long start = System.nanoTime();
            rawVector = store.searchForProject(USER_ID, PROJECT_ID, queryVector, CANDIDATE_K, VECTOR_MIN_SCORE);
            rawLexical = store.searchLexicalForProject(USER_ID, PROJECT_ID, query.query(),
                    CANDIDATE_K, LEXICAL_MIN_SCORE);
            vectorGated = rawVector.isEmpty() || rawVector.get(0).score() < VECTOR_GATE_SCORE;
            lexicalGated = rawLexical.isEmpty() || rawLexical.get(0).score() < LEXICAL_GATE_SCORE;
            fused = (vectorGated && lexicalGated) ? List.of() : fusion.fuse(
                    vectorGated ? List.of() : toCandidates(rawVector, true),
                    lexicalGated ? List.of() : toCandidates(rawLexical, false),
                    CANDIDATE_K);
            retrievalNanos.add(System.nanoTime() - start);
        }

        List<Hit> hybridHits = fused.stream().limit(TOP_K).map(RagRerankEvalRunner::toHit).toList();
        List<Hit> rerankHits = List.of();
        long rerankNanos = 0L;
        int rerankTokens = 0;
        int rerankCalls = 0;
        long candidateTokens = fused.stream().mapToLong(candidate -> estimateTokens(candidate.content())).sum();

        if (!fused.isEmpty() && !query.expectEmpty()) {
            List<String> documents = fused.stream().map(RagCandidate::content).toList();
            long start = System.nanoTime();
            RerankClient.Result result = reranker.rerank(query.query(), documents, TOP_K);
            rerankNanos = System.nanoTime() - start;
            rerankCalls = 1;
            rerankTokens = result.tokens() == null ? 0 : result.tokens();
            List<Hit> ordered = new ArrayList<>(result.ranked().size());
            for (RerankClient.Ranked ranked : result.ranked()) {
                ordered.add(toHit(fused.get(ranked.index())));
            }
            rerankHits = ordered;
        } else if (query.expectEmpty()) {
            // noise 查询在门槛处判定，reranker 不参与，避免把付费调用算成收益或成本
            rerankHits = hybridHits;
        }

        Calibration calibration = new Calibration(
                query.id(),
                query.relevantChunkIds().isEmpty() ? null : query.relevantChunkIds().get(0),
                rankOf(rawVector, query.relevantChunkIds()),
                rankOf(rawLexical, query.relevantChunkIds()),
                rawVector.size(),
                rawLexical.size(),
                topScore(rawVector),
                targetScore(rawVector, query.relevantChunkIds()),
                topScore(rawLexical),
                targetScore(rawLexical, query.relevantChunkIds()),
                inPool(fused, query.relevantChunkIds()),
                fused.size(),
                rankOfHits(hybridHits, query.relevantChunkIds()),
                rankOfHits(rerankHits, query.relevantChunkIds()),
                vectorGated,
                lexicalGated);

        return new Evaluation(
                Map.of("hybrid", hybridHits, "hybrid+rerank", rerankHits),
                Map.of("hybrid", retrievalNanos, "hybrid+rerank", retrievalNanos),
                rerankNanos,
                rerankTokens,
                rerankCalls,
                candidateTokens,
                calibration);
    }

    private static int rankOf(List<SearchResult> hits, List<Long> relevant) {
        for (int i = 0; i < hits.size(); i++) {
            if (relevant.contains(hits.get(i).chunkId())) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int rankOfHits(List<Hit> hits, List<Long> relevant) {
        for (int i = 0; i < hits.size(); i++) {
            if (relevant.contains(hits.get(i).chunkId())) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean inPool(List<RagCandidate> fused, List<Long> relevant) {
        return fused.stream().anyMatch(candidate -> relevant.contains(candidate.chunkId()));
    }

    private static double topScore(List<SearchResult> hits) {
        return hits.isEmpty() ? 0d : round4(hits.get(0).score());
    }

    private static double targetScore(List<SearchResult> hits, List<Long> relevant) {
        for (SearchResult hit : hits) {
            if (relevant.contains(hit.chunkId())) {
                return round4(hit.score());
            }
        }
        return 0d;
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static List<RagCandidate> toCandidates(List<SearchResult> hits, boolean vector) {
        List<RagCandidate> candidates = new ArrayList<>(hits.size());
        for (SearchResult hit : hits) {
            candidates.add(vector
                    ? new RagCandidate(hit.chunkId(), hit.documentId(), hit.content(), hit.score(), 0f)
                    : new RagCandidate(hit.chunkId(), hit.documentId(), hit.content(), 0f, hit.score()));
        }
        return candidates;
    }

    private static Hit toHit(RagCandidate candidate) {
        return new Hit(candidate.chunkId(), candidate.documentId(), candidate.content());
    }

    private static ObjectNode buildReport(ModelEmbedding embedding, RerankClient reranker,
                                          int totalChunks, long hardChunks, int totalQueries,
                                          List<Calibration> calibrations,
                                          Map<String, Metrics> overall, int noiseTotal,
                                          Map<String, Integer> noiseRejected) {
        long hardQueries = calibrations.size();
        long lowRankTargets = calibrations.stream()
                .filter(c -> (c.vectorRank() == 0 || c.vectorRank() > TOP_K)
                        && (c.lexicalRank() == 0 || c.lexicalRank() > TOP_K))
                .count();
        double lowRankRatio = hardQueries == 0 ? 0d : round(lowRankTargets * 100d / hardQueries);
        long poolGaps = calibrations.stream().filter(c -> !c.inPool()).count();

        ObjectNode report = MAPPER.createObjectNode();
        report.put("version", 1);
        report.put("generated_at", LocalDateTime.now().toString());

        ObjectNode embeddingNode = report.putObject("embedding");
        embeddingNode.put("mode", "model");
        embeddingNode.put("model", envOrDefault("RAG_EVAL_EMBEDDING_MODEL", "BAAI/bge-m3"));
        embeddingNode.put("url", envOrDefault("RAG_EVAL_EMBEDDING_URL", "https://api.siliconflow.cn/v1/embeddings"));
        embeddingNode.put("query_tokens", embedding.queryTokens);
        embeddingNode.put("query_tokens_estimated", embedding.queryTokensEstimated);
        embeddingNode.put("index_tokens", embedding.indexTokens);

        ObjectNode rerankerNode = report.putObject("reranker");
        rerankerNode.put("model", envOrDefault("RAG_EVAL_RERANK_MODEL", "BAAI/bge-reranker-v2-m3"));
        rerankerNode.put("url", envOrDefault("RAG_EVAL_RERANK_URL", "https://api.siliconflow.cn/v1/rerank"));

        ObjectNode dataset = report.putObject("dataset");
        dataset.put("chunks", totalChunks);
        dataset.put("hard_chunks", hardChunks);
        dataset.put("queries", totalQueries);
        dataset.put("hard_queries", hardQueries);
        dataset.put("noise_queries", noiseTotal);
        dataset.put("top_k", TOP_K);
        dataset.put("candidate_k", CANDIDATE_K);
        dataset.put("vector_min_score", VECTOR_MIN_SCORE);
        dataset.put("vector_gate_score", VECTOR_GATE_SCORE);
        dataset.put("lexical_min_score", LEXICAL_MIN_SCORE);
        dataset.put("lexical_gate_score", LEXICAL_GATE_SCORE);
        dataset.put("rrf_k", RRF_K);
        dataset.put("latency_repeats", LATENCY_REPEATS);
        dataset.put("rerank_latency_scope", "single_call_per_query");
        dataset.put("context_tokens_note", "CJK 1 字约 1 token，非 CJK 4 字符约 1 token，估算值");

        ObjectNode configs = report.putObject("configs");
        overall.forEach((config, metrics) -> configs.set(config, metrics.toNode()));

        ObjectNode delta = report.putObject("delta").putObject("hybrid_plus_rerank_minus_hybrid");
        Metrics hybrid = overall.get("hybrid");
        Metrics rerank = overall.get("hybrid+rerank");
        delta.put("top1_hit_rate_pp", round(rerank.top1Pct() - hybrid.top1Pct()));
        delta.put("ndcg@5_pp", round(rerank.ndcgPct() - hybrid.ndcgPct()));
        delta.put("mrr@5_pp", round(rerank.mrrPct() - hybrid.mrrPct()));
        delta.put("recall@5_pp", round(rerank.recallPct() - hybrid.recallPct()));
        delta.put("context_tokens", round(rerank.averageContextTokens() - hybrid.averageContextTokens()));
        delta.put("retrieval_p95_ms", round(rerank.retrievalP95() - hybrid.retrievalP95()));
        delta.put("rerank_p95_ms", round(rerank.rerankP95()));
        delta.put("total_p95_ms", round(rerank.totalP95() - hybrid.totalP95()));

        ObjectNode calibration = report.putObject("calibration");
        calibration.put("hard_queries", hardQueries);
        calibration.put("target_both_rank_gt5", lowRankTargets);
        calibration.put("target_both_rank_gt5_pct", lowRankRatio);
        calibration.put("pool_gaps", poolGaps);
        var calibrationQueries = calibration.putArray("queries");
        calibrations.forEach(c -> {
            ObjectNode node = calibrationQueries.addObject();
            node.put("id", c.id());
            if (c.target() == null) {
                node.putNull("target");
            } else {
                node.put("target", c.target());
            }
            node.put("vector_rank", c.vectorRank());
            node.put("lexical_rank", c.lexicalRank());
            node.put("vector_candidates", c.vectorCandidates());
            node.put("lexical_candidates", c.lexicalCandidates());
            node.put("vector_top_score", c.vectorTopScore());
            node.put("target_vector_score", c.targetVectorScore());
            node.put("lexical_top_score", c.lexicalTopScore());
            node.put("target_lexical_score", c.targetLexicalScore());
            node.put("vector_gated", c.vectorGated());
            node.put("lexical_gated", c.lexicalGated());
            node.put("in_candidate_pool", c.inPool());
            node.put("pool_size", c.poolSize());
            node.put("hybrid_rank", c.hybridRank());
            node.put("rerank_rank", c.rerankRank());
        });

        ObjectNode noise = report.putObject("noise");
        noise.put("total", noiseTotal);
        ObjectNode rejected = noise.putObject("rejected");
        CONFIGS.forEach(config -> rejected.put(config, noiseRejected.getOrDefault(config, 0)));

        ObjectNode cost = report.putObject("cost");
        cost.put("embedding_query_tokens", embedding.queryTokens);
        cost.put("embedding_query_tokens_estimated", embedding.queryTokensEstimated);
        cost.put("rerank_tokens", rerank.rerankTokens);
        cost.put("rerank_calls", rerank.rerankCalls);
        cost.put("avg_rerank_tokens_per_query",
                round(hardQueries == 0 ? 0d : rerank.rerankTokens / (double) hardQueries));
        cost.put("avg_candidate_content_tokens",
                round(hardQueries == 0 ? 0d : rerank.candidateTokens / (double) hardQueries));
        Double embeddingPrice = doubleEnv("RAG_EVAL_PRICE_EMBEDDING_PER_MTOKENS");
        Double rerankPrice = doubleEnv("RAG_EVAL_PRICE_RERANK_PER_MTOKENS");
        if (embeddingPrice != null && rerankPrice != null && hardQueries > 0) {
            double total = (embedding.queryTokens * embeddingPrice + rerank.rerankTokens * rerankPrice) / 1_000_000d;
            cost.put("currency", "CNY");
            cost.put("avg_cost_per_query", round(total / hardQueries));
        } else {
            cost.putNull("currency");
            cost.putNull("avg_cost_per_query");
        }

        boolean d1 = hardQueries >= MIN_HARD_QUERIES && hardChunks >= MIN_DISTRACTOR_CHUNKS
                && noiseTotal >= MIN_NOISE_QUERIES;
        boolean d3 = lowRankRatio >= MIN_HARD_TARGETS_LOW_RANK;
        boolean d4 = poolGaps == 0;
        double top1Gain = round(rerank.top1Pct() - hybrid.top1Pct());
        double ndcgGain = round(rerank.ndcgPct() - hybrid.ndcgPct());
        double mrrDelta = round(rerank.mrrPct() - hybrid.mrrPct());
        double recallDelta = round(rerank.recallPct() - hybrid.recallPct());
        int rerankNoise = noiseRejected.getOrDefault("hybrid+rerank", 0);
        int hybridNoise = noiseRejected.getOrDefault("hybrid", 0);
        boolean q5 = rerankNoise == noiseTotal && rerankNoise >= hybridNoise;
        boolean rerankLatencyOk = rerank.rerankP95() <= Q6_RERANK_P95_MS;
        boolean totalLatencyOk = rerank.totalP95() <= Q6_TOTAL_P95_MS;
        boolean q6 = rerankLatencyOk && totalLatencyOk;
        double avgRerankTokens = hardQueries == 0 ? 0d : rerank.rerankTokens / (double) hardQueries;
        double avgCandidateTokens = hardQueries == 0 ? 0d : rerank.candidateTokens / (double) hardQueries;
        boolean tokenBudget = avgRerankTokens <= avgCandidateTokens * Q7_TOKEN_RATIO;
        boolean moneyBudget = true;
        if (rerankPrice != null && embeddingPrice != null && hardQueries > 0) {
            double avgCost = (embedding.queryTokens * embeddingPrice + rerank.rerankTokens * rerankPrice)
                    / 1_000_000d / hardQueries;
            moneyBudget = avgCost <= Q7_COST_PER_QUERY;
        }
        boolean q7 = tokenBudget && moneyBudget;

        ObjectNode decision = report.putObject("decision");
        var checks = decision.putArray("checks");
        checks.add(check("Q1", "top-1 命中率提升", ">= +" + Q1_TOP1_GAIN_PP + "pp", top1Gain,
                top1Gain >= Q1_TOP1_GAIN_PP));
        checks.add(check("Q2", "nDCG@5 提升", ">= +" + Q2_NDCG_GAIN_PP + "pp", ndcgGain,
                ndcgGain >= Q2_NDCG_GAIN_PP));
        checks.add(check("Q3", "MRR@5 不回退", ">= -" + Q3_Q4_TOLERANCE_PP + "pp", mrrDelta,
                mrrDelta >= -Q3_Q4_TOLERANCE_PP));
        checks.add(check("Q4", "Recall@5 不回退", ">= -" + Q3_Q4_TOLERANCE_PP + "pp", recallDelta,
                recallDelta >= -Q3_Q4_TOLERANCE_PP));
        ObjectNode q5Node = checks.addObject();
        q5Node.put("id", "Q5");
        q5Node.put("label", "noise 全部拒绝且不下降");
        q5Node.put("actual", rerankNoise + "/" + noiseTotal + "（hybrid " + hybridNoise + "/" + noiseTotal + "）");
        q5Node.put("passed", q5);
        checks.add(check("Q6", "rerank p95（ms）", "<= " + Q6_RERANK_P95_MS, rerank.rerankP95(), rerankLatencyOk));
        checks.add(check("Q6", "total p95（ms）", "<= " + Q6_TOTAL_P95_MS, rerank.totalP95(), totalLatencyOk));
        checks.add(check("Q7", "rerank tokens/查询", "<= " + Q7_TOKEN_RATIO + "×候选正文", avgRerankTokens,
                tokenBudget));
        if (rerankPrice != null && embeddingPrice != null && hardQueries > 0) {
            double avgCost = (embedding.queryTokens * embeddingPrice + rerank.rerankTokens * rerankPrice)
                    / 1_000_000d / hardQueries;
            checks.add(check("Q7", "单查询成本（CNY）", "<= " + Q7_COST_PER_QUERY, avgCost, moneyBudget));
        }
        checks.add(check("Q8", "数据有效性 D1", "hard>=" + MIN_HARD_QUERIES + " / 干扰>="
                + MIN_DISTRACTOR_CHUNKS + " / noise>=" + MIN_NOISE_QUERIES, hardQueries, d1));
        checks.add(check("D3", "目标两路排名>5 占比（%）", ">= " + MIN_HARD_TARGETS_LOW_RANK, lowRankRatio, d3));
        checks.add(check("D4", "目标进入候选池缺口数", "= 0", poolGaps, d4));

        boolean allChecks = d1 && d3 && d4 && top1Gain >= Q1_TOP1_GAIN_PP
                && ndcgGain >= Q2_NDCG_GAIN_PP
                && mrrDelta >= -Q3_Q4_TOLERANCE_PP && recallDelta >= -Q3_Q4_TOLERANCE_PP
                && q5 && q6 && q7;
        String conclusion;
        if (!d1 || !d3 || !d4) {
            conclusion = "数据集不合格，本次不判定";
        } else if (allChecks) {
            conclusion = "建议启用";
        } else {
            conclusion = "保持不启用";
        }
        decision.put("conclusion", conclusion);
        return report;
    }

    private static ObjectNode check(String id, String label, String expected, double actual, boolean passed) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("label", label);
        node.put("expected", expected);
        node.put("actual", round(actual));
        node.put("passed", passed);
        return node;
    }

    private static Path writeReport(ObjectNode report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path file = REPORT_DIR.resolve("rag-rerank-" + stamp + ".json");
        Files.writeString(file, MAPPER.writeValueAsString(report) + System.lineSeparator());
        return file;
    }

    private static void printSummary(ObjectNode report, Path file) {
        System.out.println();
        System.out.println("========== P5-07-3 难例检索与 reranker 对照 ==========");
        System.out.println("embedding=" + report.at("/embedding/model").asText()
                + "，reranker=" + report.at("/reranker/model").asText()
                + "，chunks=" + report.at("/dataset/chunks").asInt()
                + "，hard queries=" + report.at("/dataset/hard_queries").asInt());
        System.out.printf(Locale.ROOT, "%-14s %8s %8s %8s %8s %10s %10s %10s%n",
                "配置", "top1%", "Recall@5", "MRR@5", "nDCG@5", "检索p95", "rerankp95", "总p95");
        for (String config : CONFIGS) {
            JsonNode node = report.path("configs").path(config);
            System.out.printf(Locale.ROOT, "%-14s %8.2f %8.2f %8.2f %8.2f %10.2f %10.2f %10.2f%n",
                    config,
                    node.path("top1_hit_rate").asDouble(),
                    node.path("recall@5").asDouble(),
                    node.path("mrr@5").asDouble(),
                    node.path("ndcg@5").asDouble(),
                    node.path("retrieval_p95_ms").asDouble(),
                    node.path("rerank_p95_ms").asDouble(),
                    node.path("total_p95_ms").asDouble());
        }
        System.out.printf(Locale.ROOT, "校准：目标两路排名>5 占比 %.2f%%，候选池缺口 %d，noise %d 条拒绝 %d/%d%n",
                report.at("/calibration/target_both_rank_gt5_pct").asDouble(),
                report.at("/calibration/pool_gaps").asInt(),
                report.at("/noise/total").asInt(),
                report.at("/noise/rejected/hybrid+rerank").asInt(),
                report.at("/noise/total").asInt());
        System.out.println("成本：embedding query tokens=" + report.at("/cost/embedding_query_tokens").asInt()
                + "，rerank tokens=" + report.at("/cost/rerank_tokens").asInt()
                + "，rerank calls=" + report.at("/cost/rerank_calls").asInt());
        report.path("decision").path("checks").forEach(check ->
                System.out.printf(Locale.ROOT, "  %-3s %-24s 期望 %-24s 实际 %-10s %s%n",
                        check.path("id").asText(), check.path("label").asText(),
                        check.path("expected").asText(), check.path("actual").asText(),
                        check.path("passed").asBoolean() ? "PASS" : "FAIL"));
        System.out.println("结论：" + report.at("/decision/conclusion").asText());
        System.out.println("报告：" + file.toAbsolutePath());
        System.out.println("======================================================");
    }

    private static List<Chunk> mergeChunks(List<Chunk> base, List<Chunk> hard) {
        Set<Long> ids = new HashSet<>();
        List<Chunk> all = new ArrayList<>();
        for (Chunk chunk : base) {
            if (!ids.add(chunk.chunkId())) {
                throw new IllegalStateException("切片 chunkId 重复: " + chunk.chunkId());
            }
            all.add(chunk);
        }
        for (Chunk chunk : hard) {
            if (!ids.add(chunk.chunkId())) {
                throw new IllegalStateException("难例切片 chunkId 与基础语料重复: " + chunk.chunkId());
            }
            all.add(chunk);
        }
        return all;
    }

    private static List<Chunk> loadChunkFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("缺少语料文件 " + file);
        }
        List<Chunk> chunks = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            chunks.add(new Chunk(
                    node.path("chunkId").asLong(),
                    node.path("documentId").asLong(),
                    node.path("fileName").asText(),
                    node.path("chunkIndex").asInt(),
                    node.path("content").asText(),
                    strings(node.path("concepts"))));
        }
        return chunks;
    }

    private static List<QueryCase> loadQueries(List<Chunk> chunks) throws IOException {
        if (!Files.isRegularFile(HARD_QUERIES)) {
            throw new IllegalStateException("缺少难例查询文件 " + HARD_QUERIES);
        }
        Map<Long, Chunk> byChunkId = new HashMap<>();
        chunks.forEach(chunk -> byChunkId.put(chunk.chunkId(), chunk));
        List<QueryCase> queries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : Files.readAllLines(HARD_QUERIES)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            List<Long> relevant = new ArrayList<>();
            node.path("relevantChunkIds").forEach(id -> relevant.add(id.asLong()));
            QueryCase query = new QueryCase(
                    node.path("id").asText(),
                    node.path("query").asText(),
                    node.path("category").asText(),
                    strings(node.path("concepts")),
                    relevant,
                    node.path("expectEmpty").asBoolean());
            if (!ids.add(query.id())) {
                throw new IllegalStateException("查询 id 重复: " + query.id());
            }
            validateQuery(query, byChunkId);
            queries.add(query);
        }
        return queries;
    }

    private static void validateQuery(QueryCase query, Map<Long, Chunk> byChunkId) {
        for (Long chunkId : query.relevantChunkIds()) {
            if (!byChunkId.containsKey(chunkId)) {
                throw new IllegalStateException("查询 " + query.id() + " 引用了不存在的切片 " + chunkId);
            }
        }
        if (query.expectEmpty() == !query.relevantChunkIds().isEmpty()) {
            throw new IllegalStateException("查询 " + query.id() + " 的 expectEmpty 与标注不一致");
        }
        if ("noise".equals(query.category())) {
            return;
        }
        if (query.relevantChunkIds().size() != 1) {
            throw new IllegalStateException("难例查询 " + query.id() + " 必须有且仅有 1 个正确切片");
        }
        Set<String> shared = new HashSet<>(query.concepts());
        shared.retainAll(byChunkId.get(query.relevantChunkIds().get(0)).concepts());
        if (!shared.isEmpty()) {
            throw new IllegalStateException("难例查询 " + query.id() + " 与目标切片共享 concept "
                    + shared + "，无法暴露两路排名靠后");
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static String embeddingKey() {
        String key = env("RAG_EVAL_EMBEDDING_KEY");
        return key != null ? key : env("EMBEDDING_API_KEY");
    }

    private static String rerankKey() {
        return env("RAG_EVAL_RERANK_KEY");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = env(name);
        return value == null ? fallback : value;
    }

    private static Double doubleEnv(String name) {
        String value = env(name);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("环境变量 " + name + " 不是合法数字: " + value);
        }
    }

    private static EmbeddedPostgres startPostgres(Path dataDir) throws IOException {
        String raw = System.getenv("DESKTOP_PG_RUNTIME");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("未设置 DESKTOP_PG_RUNTIME，无法启动含 pgvector 的内嵌 PostgreSQL");
        }
        Path runtime = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(runtime)) {
            throw new IllegalStateException("DESKTOP_PG_RUNTIME 指向的文件不存在: " + runtime);
        }
        return EmbeddedPostgres.builder()
                .setDataDirectory(dataDir.toFile())
                .setCleanDataDirectory(true)
                .setServerConfig("listen_addresses", "127.0.0.1")
                .setPgBinaryResolver((system, architecture) -> Files.newInputStream(runtime))
                .start();
    }

    private static void deleteQuietly(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响评测结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static int estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 4d);
    }

    static final class ModelEmbedding {

        private final EmbeddingModel model;
        int indexTokens;
        int queryTokens;
        boolean queryTokensEstimated;

        ModelEmbedding(String apiKey) {
            this.model = new SpringAiEmbeddingModelConfig(
                    envOrDefault("RAG_EVAL_EMBEDDING_URL", "https://api.siliconflow.cn/v1/embeddings"),
                    envOrDefault("RAG_EVAL_EMBEDDING_MODEL", "BAAI/bge-m3"),
                    ObservationRegistry.NOOP).create(apiKey);
        }

        float[] embed(String text) throws IOException {
            return embedInternal(text, false);
        }

        float[] embedQuery(String text) throws IOException {
            return embedInternal(text, true);
        }

        private float[] embedInternal(String text, boolean query) throws IOException {
            EmbeddingResponse response = model.embedForResponse(List.of(text));
            if (response.getResults().isEmpty()) {
                throw new IOException("embedding 返回为空");
            }
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            Integer total = usage == null ? null : usage.getTotalTokens();
            if (total == null) {
                int estimated = estimateTokens(text);
                if (query) {
                    queryTokens += estimated;
                    queryTokensEstimated = true;
                } else {
                    indexTokens += estimated;
                }
            } else if (query) {
                queryTokens += total;
            } else {
                indexTokens += total;
            }
            return response.getResults().get(0).getOutput();
        }
    }

    record Outcome(ObjectNode report) {
    }

    private record Chunk(long chunkId, long documentId, String fileName, int chunkIndex,
                         String content, List<String> concepts) {
    }

    private record QueryCase(String id, String query, String category, List<String> concepts,
                             List<Long> relevantChunkIds, boolean expectEmpty) {
    }

    private record Hit(Long chunkId, Long documentId, String content) {
    }

    private record Calibration(String id, Long target, int vectorRank, int lexicalRank,
                               int vectorCandidates, int lexicalCandidates,
                               double vectorTopScore, double targetVectorScore,
                               double lexicalTopScore, double targetLexicalScore,
                               boolean inPool, int poolSize, int hybridRank, int rerankRank,
                               boolean vectorGated, boolean lexicalGated) {
    }

    private record Evaluation(Map<String, List<Hit>> hits,
                              Map<String, List<Long>> retrievalNanos,
                              long rerankNanos,
                              int rerankTokens,
                              int rerankCalls,
                              long candidateTokens,
                              Calibration calibration) {
    }

    static final class Metrics {

        private int queries;
        private int top1Hits;
        private int docHits;
        private double recall5;
        private double mrr5;
        private double ndcg5;
        private long contextTokens;
        private long rerankTokens;
        private int rerankCalls;
        private long candidateTokens;
        private final List<Long> retrievalNanos = new ArrayList<>();
        private final List<Long> rerankNanos = new ArrayList<>();
        private final List<Long> totalNanos = new ArrayList<>();

        void accumulate(QueryCase query, List<Hit> hits, Map<Long, Chunk> byChunkId) {
            queries++;
            Set<Long> relevant = new HashSet<>(query.relevantChunkIds());
            Set<Long> relevantDocuments = relevant.stream()
                    .map(chunkId -> byChunkId.get(chunkId).documentId())
                    .collect(java.util.stream.Collectors.toSet());
            int found5 = 0;
            int firstRank = 0;
            double dcg = 0d;
            for (int i = 0; i < hits.size() && i < TOP_K; i++) {
                Hit hit = hits.get(i);
                if (relevant.contains(hit.chunkId())) {
                    found5++;
                    if (firstRank == 0) {
                        firstRank = i + 1;
                    }
                    dcg += 1d / (Math.log(i + 2) / Math.log(2));
                }
                contextTokens += estimateTokens(hit.content());
            }
            if (firstRank == 1) {
                top1Hits++;
            }
            if (!relevant.isEmpty()) {
                recall5 += found5 / (double) relevant.size();
                double ideal = 0d;
                for (int i = 1; i <= Math.min(relevant.size(), TOP_K); i++) {
                    ideal += 1d / (Math.log(i + 1) / Math.log(2));
                }
                ndcg5 += ideal == 0d ? 0d : dcg / ideal;
                mrr5 += firstRank > 0 ? 1d / firstRank : 0d;
            }
            if (hits.stream().anyMatch(hit -> relevantDocuments.contains(hit.documentId()))) {
                docHits++;
            }
        }

        void addLatencies(List<Long> retrieval, long rerank) {
            retrievalNanos.addAll(retrieval);
            if (rerank > 0) {
                rerankNanos.add(rerank);
                retrieval.stream().findFirst().ifPresent(first -> totalNanos.add(first + rerank));
            } else {
                totalNanos.addAll(retrieval);
            }
        }

        double top1Pct() {
            return queries == 0 ? 0d : round(top1Hits * 100d / queries);
        }

        double recallPct() {
            return queries == 0 ? 0d : round(recall5 * 100d / queries);
        }

        double mrrPct() {
            return queries == 0 ? 0d : round(mrr5 * 100d / queries);
        }

        double ndcgPct() {
            return queries == 0 ? 0d : round(ndcg5 * 100d / queries);
        }

        double docHitRatePct() {
            return queries == 0 ? 0d : round(docHits * 100d / queries);
        }

        double averageContextTokens() {
            return queries == 0 ? 0d : round(contextTokens / (double) queries);
        }

        double retrievalP95() {
            return p95(retrievalNanos);
        }

        double rerankP95() {
            return p95(rerankNanos);
        }

        double totalP95() {
            return p95(totalNanos);
        }

        private static double p95(List<Long> values) {
            if (values.isEmpty()) {
                return 0d;
            }
            List<Long> sorted = new ArrayList<>(values);
            sorted.sort(Comparator.naturalOrder());
            int index = (int) Math.ceil(0.95d * sorted.size()) - 1;
            return round(sorted.get(Math.max(0, index)) / 1_000_000d);
        }

        ObjectNode toNode() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("queries", queries);
            node.put("top1_hit_rate", top1Pct());
            node.put("recall@5", recallPct());
            node.put("mrr@5", mrrPct());
            node.put("ndcg@5", ndcgPct());
            node.put("doc_hit_rate", docHitRatePct());
            node.put("context_tokens", averageContextTokens());
            node.put("retrieval_p95_ms", retrievalP95());
            node.put("rerank_p95_ms", rerankP95());
            node.put("total_p95_ms", totalP95());
            node.put("rerank_tokens", rerankTokens);
            node.put("rerank_calls", rerankCalls);
            return node;
        }
    }
}
