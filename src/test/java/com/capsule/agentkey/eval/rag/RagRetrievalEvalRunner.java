package com.capsule.agentkey.eval.rag;

import com.capsule.agentkey.config.PendingFileChangePrecheckCallback;
import com.capsule.agentkey.config.SpringAiEmbeddingModelConfig;
import com.capsule.agentkey.service.EmbeddingService;
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

import static java.util.stream.Collectors.toSet;

/**
 * P5-07-2：离线检索质量评测。
 *
 * <p>用带标注的语料对比三种配置：纯向量、纯词法、RRF 混合。
 * 词法走真实 pg_trgm；向量默认由 concept 生成的确定性向量模拟语义召回，
 * 只回答“融合是否优于单路”，不冒充 embedding 模型本身的评测。</p>
 *
 * <p>依赖 scripts/build-pg-runtime.ps1 产出的运行时，由 DESKTOP_PG_RUNTIME 指定；
 * 未设置时整类跳过，普通 mvn test 不要求本机具备运行时。</p>
 */
@EnabledIfEnvironmentVariable(named = "DESKTOP_PG_RUNTIME", matches = ".+")
public class RagRetrievalEvalRunner {

    static final Path DATASET_DIR = Paths.get("eval", "rag-datasets");
    static final Path REPORT_DIR = Paths.get("eval", "rag-reports");
    static final List<String> CONFIGS = List.of("vector", "lexical", "hybrid");

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String PG_USER = "postgres";
    private static final String PG_DATABASE = "postgres";
    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final int TOP_K = 5;
    private static final int LATENCY_REPEATS = 3;
    private static final float VECTOR_MIN_SCORE = 0.5f;
    private static final float VECTOR_GATE_SCORE = 0.55f;
    private static final float LEXICAL_MIN_SCORE = 0.3f;
    private static final float LEXICAL_GATE_SCORE = 0.35f;
    private static final int RRF_K = 60;

    @Test
    void runEval() throws Exception {
        execute();
    }

    /** 跑完整评测并写出报告；门禁测试复用同一入口，保证口径一致。 */
    static Outcome execute() throws Exception {
        List<Chunk> chunks = loadChunks();
        List<QueryCase> queries = loadQueries(chunks);
        ProviderSelection selection = createProvider();

        Path dataDir = Files.createTempDirectory("rag-eval-pg");
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
            // 只有 24 条切片：去掉 HNSW 索引让排序退化为精确扫描，保证两次运行结果一致
            jdbc.execute("DROP INDEX IF EXISTS idx_vector_record_embedding");
            jdbc.update("DELETE FROM vector_record WHERE user_id = ?", USER_ID);

            VectorStoreService store = new VectorStoreService(jdbc);
            List<VectorRecord> records = new ArrayList<>();
            Map<Long, Chunk> byChunkId = new HashMap<>();
            for (Chunk chunk : chunks) {
                records.add(new VectorRecord(chunk.chunkId(), chunk.documentId(), USER_ID,
                        chunk.content(), selection.provider().embed(chunk.content(), chunk.concepts()),
                        "knowledge", PROJECT_ID));
                byChunkId.put(chunk.chunkId(), chunk);
            }
            store.addVectors(records);

            RankFusion rankFusion = new RrfRankFusion(RRF_K);
            Map<String, Metrics> overall = new LinkedHashMap<>();
            CONFIGS.forEach(config -> overall.put(config, new Metrics()));
            Map<String, Map<String, Metrics>> byCategory = new LinkedHashMap<>();
            Map<String, Integer> noiseRejected = new HashMap<>();
            int noiseTotal = 0;

            for (QueryCase query : queries) {
                QueryEvaluation evaluation = evaluateQuery(store, rankFusion, selection.provider(), query);
                if (query.expectEmpty()) {
                    noiseTotal++;
                    for (String config : CONFIGS) {
                        if (evaluation.hits().get(config).isEmpty()) {
                            noiseRejected.merge(config, 1, Integer::sum);
                        }
                    }
                    continue;
                }
                for (String config : CONFIGS) {
                    overall.get(config).accumulate(query, evaluation.hits().get(config), byChunkId);
                    byCategory.computeIfAbsent(query.category(), key -> {
                        Map<String, Metrics> metrics = new LinkedHashMap<>();
                        CONFIGS.forEach(name -> metrics.put(name, new Metrics()));
                        return metrics;
                    }).get(config).accumulate(query, evaluation.hits().get(config), byChunkId);
                }
                for (String config : CONFIGS) {
                    overall.get(config).addLatencies(evaluation.latencies().get(config));
                    byCategory.get(query.category()).get(config).addLatencies(evaluation.latencies().get(config));
                }
            }

            ObjectNode report = buildReport(selection.mode(), chunks, queries, overall, byCategory,
                    noiseTotal, noiseRejected);
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

    private static QueryEvaluation evaluateQuery(VectorStoreService store, RankFusion rankFusion,
                                                 EmbeddingProvider provider, QueryCase query) throws IOException {
        float[] queryVector = provider.embed(query.query(), query.concepts());
        List<Hit> vectorHits = List.of();
        List<Hit> lexicalHits = List.of();
        List<Hit> hybridHits = List.of();
        List<Long> vectorLatency = new ArrayList<>();
        List<Long> lexicalLatency = new ArrayList<>();
        List<Long> hybridLatency = new ArrayList<>();

        for (int repeat = 0; repeat < LATENCY_REPEATS; repeat++) {
            long start = System.nanoTime();
            List<SearchResult> rawVector = store.searchForProject(USER_ID, PROJECT_ID, queryVector,
                    TOP_K, VECTOR_MIN_SCORE);
            long afterVector = System.nanoTime();
            List<SearchResult> rawLexical = store.searchLexicalForProject(USER_ID, PROJECT_ID, query.query(),
                    TOP_K, LEXICAL_MIN_SCORE);
            long afterLexical = System.nanoTime();

            boolean vectorGated = rawVector.isEmpty() || rawVector.get(0).score() < VECTOR_GATE_SCORE;
            boolean lexicalGated = rawLexical.isEmpty() || rawLexical.get(0).score() < LEXICAL_GATE_SCORE;

            List<Hit> singleVector = vectorGated ? List.of() : toHits(rawVector);
            List<Hit> singleLexical = lexicalGated ? List.of() : toHits(rawLexical);
            List<Hit> hybrid;
            if (vectorGated && lexicalGated) {
                hybrid = List.of();
            } else {
                List<RagCandidate> fused = rankFusion.fuse(
                        vectorGated ? List.of() : toCandidates(rawVector, true),
                        lexicalGated ? List.of() : toCandidates(rawLexical, false),
                        TOP_K);
                hybrid = fused.stream()
                        .map(candidate -> new Hit(candidate.chunkId(), candidate.documentId(), candidate.content()))
                        .toList();
            }
            long afterFusion = System.nanoTime();

            if (repeat == 0) {
                vectorHits = singleVector;
                lexicalHits = singleLexical;
                hybridHits = hybrid;
            }
            vectorLatency.add(afterVector - start);
            lexicalLatency.add(afterLexical - afterVector);
            hybridLatency.add(afterFusion - start);
        }
        return new QueryEvaluation(
                Map.of("vector", vectorHits, "lexical", lexicalHits, "hybrid", hybridHits),
                Map.of("vector", vectorLatency, "lexical", lexicalLatency, "hybrid", hybridLatency));
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

    private static List<Hit> toHits(List<SearchResult> hits) {
        List<Hit> result = new ArrayList<>(hits.size());
        for (SearchResult hit : hits) {
            result.add(new Hit(hit.chunkId(), hit.documentId(), hit.content()));
        }
        return result;
    }

    private static ObjectNode buildReport(String embeddingMode, List<Chunk> chunks, List<QueryCase> queries,
                                          Map<String, Metrics> overall,
                                          Map<String, Map<String, Metrics>> byCategory,
                                          int noiseTotal, Map<String, Integer> noiseRejected) {
        ObjectNode report = MAPPER.createObjectNode();
        report.put("version", 1);
        report.put("embedding", embeddingMode);
        report.put("generated_at", LocalDateTime.now().toString());

        ObjectNode dataset = report.putObject("dataset");
        dataset.put("chunks", chunks.size());
        dataset.put("queries", queries.size());
        dataset.put("top_k", TOP_K);
        dataset.put("vector_min_score", VECTOR_MIN_SCORE);
        dataset.put("vector_gate_score", VECTOR_GATE_SCORE);
        dataset.put("lexical_min_score", LEXICAL_MIN_SCORE);
        dataset.put("lexical_gate_score", LEXICAL_GATE_SCORE);
        dataset.put("rrf_k", RRF_K);
        dataset.put("latency_scope", "retrieval_only_excluding_embedding");
        dataset.put("latency_repeats", LATENCY_REPEATS);
        dataset.put("context_tokens_note", "CJK 1 字约 1 token，非 CJK 4 字符约 1 token，估算值");

        ObjectNode configs = report.putObject("configs");
        overall.forEach((config, metrics) -> configs.set(config, metrics.toNode()));
        ObjectNode categories = report.putObject("categories");
        byCategory.forEach((category, metricsByConfig) -> {
            ObjectNode categoryNode = categories.putObject(category);
            metricsByConfig.forEach((config, metrics) -> categoryNode.set(config, metrics.toNode()));
        });

        ObjectNode noise = report.putObject("noise");
        noise.put("total", noiseTotal);
        ObjectNode rejected = noise.putObject("rejected");
        CONFIGS.forEach(config -> rejected.put(config, noiseRejected.getOrDefault(config, 0)));

        ObjectNode delta = report.putObject("delta").putObject("hybrid_minus_vector");
        delta.put("recall@5_pp", round(overall.get("hybrid").recall5Pct() - overall.get("vector").recall5Pct()));
        delta.put("doc_hit_rate_pp",
                round(overall.get("hybrid").docHitRatePct() - overall.get("vector").docHitRatePct()));
        JsonNode exact = categories.path("exact");
        delta.put("exact_recall@5_pp",
                round(exact.path("hybrid").path("recall@5").asDouble() - exact.path("vector").path("recall@5").asDouble()));
        delta.put("context_tokens",
                round(overall.get("hybrid").averageContextTokens() - overall.get("vector").averageContextTokens()));
        delta.put("p95_latency_ms",
                round(overall.get("hybrid").p95LatencyMs() - overall.get("vector").p95LatencyMs()));
        return report;
    }

    private static Path writeReport(ObjectNode report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path file = REPORT_DIR.resolve("rag-" + stamp + ".json");
        Files.writeString(file, MAPPER.writeValueAsString(report) + System.lineSeparator());
        return file;
    }

    private static void printSummary(ObjectNode report, Path file) {
        System.out.println();
        System.out.println("========== P5-07-2 检索质量评测（embedding=" + report.path("embedding").asText() + "） ==========");
        System.out.printf(Locale.ROOT, "%-8s %10s %10s %8s %8s %10s %12s %10s%n",
                "配置", "Recall@3", "Recall@5", "MRR@5", "nDCG@5", "文档命中%", "上下文token", "p95(ms)");
        for (String config : CONFIGS) {
            JsonNode node = report.path("configs").path(config);
            System.out.printf(Locale.ROOT, "%-8s %10.2f %10.2f %8.2f %8.2f %10.2f %12.2f %10.2f%n",
                    config,
                    node.path("recall@3").asDouble(),
                    node.path("recall@5").asDouble(),
                    node.path("mrr@5").asDouble(),
                    node.path("ndcg@5").asDouble(),
                    node.path("doc_hit_rate").asDouble(),
                    node.path("context_tokens").asDouble(),
                    node.path("p95_latency_ms").asDouble());
        }
        JsonNode delta = report.path("delta").path("hybrid_minus_vector");
        System.out.printf(Locale.ROOT,
                "hybrid - vector：Recall@5 %+.2fpp，文档命中 %+.2fpp，exact 类 Recall@5 %+.2fpp，上下文 token %+.2f，p95 %+.2fms%n",
                delta.path("recall@5_pp").asDouble(),
                delta.path("doc_hit_rate_pp").asDouble(),
                delta.path("exact_recall@5_pp").asDouble(),
                delta.path("context_tokens").asDouble(),
                delta.path("p95_latency_ms").asDouble());
        JsonNode noise = report.path("noise");
        System.out.printf(Locale.ROOT, "noise 查询 %d 条：vector 拒绝 %d，lexical 拒绝 %d，hybrid 拒绝 %d%n",
                noise.path("total").asInt(),
                noise.path("rejected").path("vector").asInt(),
                noise.path("rejected").path("lexical").asInt(),
                noise.path("rejected").path("hybrid").asInt());
        System.out.println("报告：" + file.toAbsolutePath());
        System.out.println("==============================================================================");
    }

    private static ProviderSelection createProvider() {
        String apiKey = System.getenv("RAG_EVAL_EMBEDDING_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return new ProviderSelection(new SimulatedEmbedding(), "simulated");
        }
        String apiUrl = envOrDefault("RAG_EVAL_EMBEDDING_URL", "https://api.siliconflow.cn/v1/embeddings");
        String model = envOrDefault("RAG_EVAL_EMBEDDING_MODEL", "BAAI/bge-m3");
        EmbeddingService service = new EmbeddingService(
                new SpringAiEmbeddingModelConfig(apiUrl, model, ObservationRegistry.NOOP));
        return new ProviderSelection((text, concepts) -> service.embedSingle(apiKey, text), "model");
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
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

    private static List<Chunk> loadChunks() throws IOException {
        Path file = DATASET_DIR.resolve("chunks.jsonl");
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
        Path file = DATASET_DIR.resolve("queries.jsonl");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("缺少查询文件 " + file);
        }
        Map<Long, Chunk> byChunkId = new HashMap<>();
        chunks.forEach(chunk -> byChunkId.put(chunk.chunkId(), chunk));
        List<QueryCase> queries = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
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
        if (!"exact".equals(query.category())) {
            return;
        }
        for (Long chunkId : query.relevantChunkIds()) {
            Set<String> shared = new HashSet<>(query.concepts());
            shared.retainAll(byChunkId.get(chunkId).concepts());
            if (!shared.isEmpty()) {
                throw new IllegalStateException("exact 查询 " + query.id() + " 与目标切片共享 concept "
                        + shared + "，无法暴露向量漏召回");
            }
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
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

    record Outcome(ObjectNode report) {
    }

    private record ProviderSelection(EmbeddingProvider provider, String mode) {
    }

    private record Chunk(long chunkId, long documentId, String fileName, int chunkIndex,
                         String content, List<String> concepts) {
    }

    private record QueryCase(String id, String query, String category, List<String> concepts,
                             List<Long> relevantChunkIds, boolean expectEmpty) {
    }

    private record Hit(Long chunkId, Long documentId, String content) {
    }

    private record QueryEvaluation(Map<String, List<Hit>> hits, Map<String, List<Long>> latencies) {
    }

    private static final class Metrics {

        private int queries;
        private double recall3;
        private double recall5;
        private double mrr5;
        private double ndcg5;
        private int docHits;
        private long contextTokens;
        private final List<Long> latenciesNanos = new ArrayList<>();

        void accumulate(QueryCase query, List<Hit> hits, Map<Long, Chunk> byChunkId) {
            queries++;
            Set<Long> relevant = new HashSet<>(query.relevantChunkIds());
            Set<Long> relevantDocuments = relevant.stream()
                    .map(chunkId -> byChunkId.get(chunkId).documentId())
                    .collect(toSet());
            int found3 = 0;
            int found5 = 0;
            int firstRank = 0;
            double dcg = 0d;
            for (int i = 0; i < hits.size() && i < TOP_K; i++) {
                Hit hit = hits.get(i);
                if (relevant.contains(hit.chunkId())) {
                    if (i < 3) {
                        found3++;
                    }
                    found5++;
                    if (firstRank == 0) {
                        firstRank = i + 1;
                    }
                    dcg += 1d / (Math.log(i + 2) / Math.log(2));
                }
                contextTokens += estimateTokens(hit.content());
            }
            if (!relevant.isEmpty()) {
                recall3 += found3 / (double) relevant.size();
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

        void addLatencies(List<Long> latencies) {
            latenciesNanos.addAll(latencies);
        }

        double recall5Pct() {
            return queries == 0 ? 0d : round(recall5 / queries * 100d);
        }

        double docHitRatePct() {
            return queries == 0 ? 0d : round(docHits / (double) queries * 100d);
        }

        double averageContextTokens() {
            return queries == 0 ? 0d : round(contextTokens / (double) queries);
        }

        double p95LatencyMs() {
            if (latenciesNanos.isEmpty()) {
                return 0d;
            }
            List<Long> sorted = new ArrayList<>(latenciesNanos);
            sorted.sort(Comparator.naturalOrder());
            int index = (int) Math.ceil(0.95d * sorted.size()) - 1;
            return round(sorted.get(Math.max(0, index)) / 1_000_000d);
        }

        ObjectNode toNode() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("queries", queries);
            node.put("recall@3", queries == 0 ? 0d : round(recall3 / queries * 100d));
            node.put("recall@5", recall5Pct());
            node.put("mrr@5", queries == 0 ? 0d : round(mrr5 / queries * 100d));
            node.put("ndcg@5", queries == 0 ? 0d : round(ndcg5 / queries * 100d));
            node.put("doc_hit_rate", docHitRatePct());
            node.put("context_tokens", averageContextTokens());
            node.put("p95_latency_ms", p95LatencyMs());
            return node;
        }
    }
}
