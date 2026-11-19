package com.capsule.agentkey.eval.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5-07-3 门禁：只校验数据有效性与报告结构，不对“reranker 必须提升”做硬断言。
 *
 * <p>真实模型付费且有波动，收益判定写入报告 decision 字段，由 spec 4.5 的预写阈值裁决；
 * 门禁只防止数据集退化、报告缺字段与 hybrid 基线下滑。</p>
 */
@EnabledIfEnvironmentVariable(named = "DESKTOP_PG_RUNTIME", matches = ".+")
class RagRerankEvalGateTest {

    private static final Path BASELINE = Paths.get("eval", "baselines", "rag-rerank-baseline.json");
    private static final double RECALL_TOLERANCE_PP = 2d;
    private static final double P95_TOLERANCE_RATIO = 0.2d;
    private static final double P95_TOLERANCE_MS = 25d;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void rerankEvalMeetsDataValidityAndReportContract() throws Exception {
        Assumptions.assumeTrue(embeddingKey() != null && rerankKey() != null,
                "缺少真实 embedding / rerank Key，跳过 P5-07-3 门禁");

        RagRerankEvalRunner.Outcome outcome = RagRerankEvalRunner.execute();
        ObjectNode report = outcome.report();

        assertTrue(report.at("/dataset/hard_queries").asInt() >= 12, "难例查询不足 12 条");
        assertTrue(report.at("/dataset/hard_chunks").asInt() >= 16, "难例干扰切片不足 16 个");
        assertTrue(report.at("/dataset/noise_queries").asInt() >= 2, "noise 查询不足 2 条");
        assertTrue(report.at("/calibration/target_both_rank_gt5_pct").asDouble() >= 80d,
                "目标切片两路排名 >5 的占比不足 80%，数据集不合格");
        assertEquals(0, report.at("/calibration/pool_gaps").asInt(),
                "存在目标切片未进入候选池的查询，需按 spec D4 处理");

        for (String config : RagRerankEvalRunner.CONFIGS) {
            ObjectNode node = (ObjectNode) report.at("/configs/" + config);
            assertTrue(node.path("queries").asInt() >= 12, config + " 查询数不足");
            assertTrue(node.has("top1_hit_rate"), config + " 缺少 top1_hit_rate");
            assertTrue(node.has("mrr@5"), config + " 缺少 mrr@5");
            assertTrue(node.has("ndcg@5"), config + " 缺少 ndcg@5");
            assertTrue(node.has("recall@5"), config + " 缺少 recall@5");
            assertTrue(node.has("retrieval_p95_ms"), config + " 缺少 retrieval_p95_ms");
            assertTrue(node.has("rerank_p95_ms"), config + " 缺少 rerank_p95_ms");
            assertTrue(node.has("total_p95_ms"), config + " 缺少 total_p95_ms");
        }
        assertTrue(report.has("noise"), "缺少 noise 节点");
        assertTrue(report.has("cost"), "缺少 cost 节点");
        assertTrue(report.at("/decision/checks").isArray(), "缺少 decision.checks");
        assertTrue(report.at("/decision/conclusion").asText().matches("建议启用|保持不启用|数据集不合格，本次不判定"),
                "decision.conclusion 不在允许的三选一范围内");

        assertEmbeddingModeIsReal(report);
        if (Boolean.getBoolean("eval.updateBaseline")) {
            writeBaseline(report);
            return;
        }
        assertHybridNotRegressed(report);
    }

    private static void assertEmbeddingModeIsReal(ObjectNode report) {
        assertEquals("model", report.at("/embedding/mode").asText(), "评测必须使用真实 embedding");
        assertTrue(report.at("/embedding/query_tokens_estimated").asBoolean() == false
                        || report.at("/embedding/query_tokens").asInt() > 0,
                "embedding token 缺失");
        assertNotNull(report.at("/reranker/model").asText(), "缺少 reranker 模型名");
    }

    private static void assertHybridNotRegressed(ObjectNode report) throws IOException {
        if (!Files.isRegularFile(BASELINE)) {
            return;
        }
        var baseline = MAPPER.readTree(BASELINE.toFile());
        String currentModel = report.at("/embedding/model").asText();
        String baselineModel = baseline.path("embedding_model").asText();
        if (!currentModel.equals(baselineModel)) {
            System.out.println("[P5-07-3] embedding 模型变化，跳过基线对比: "
                    + baselineModel + " -> " + currentModel);
            return;
        }
        var current = report.at("/configs/hybrid");
        double recallDelta = current.path("recall@5").asDouble() - baseline.at("/configs/hybrid/recall@5").asDouble();
        assertTrue(recallDelta >= -RECALL_TOLERANCE_PP,
                "hybrid Recall@5 回退 " + recallDelta + "pp，超过容差");
        double baselineP95 = baseline.at("/configs/hybrid/retrieval_p95_ms").asDouble();
        double allowed = baselineP95 * (1d + P95_TOLERANCE_RATIO) + P95_TOLERANCE_MS;
        assertTrue(current.path("retrieval_p95_ms").asDouble() <= allowed,
                "hybrid 检索 p95 超出基线容差: " + current.path("retrieval_p95_ms").asDouble()
                        + "ms > " + allowed + "ms");
    }

    private static void writeBaseline(ObjectNode report) throws IOException {
        ObjectNode baseline = MAPPER.createObjectNode();
        baseline.put("version", 1);
        baseline.put("embedding_model", report.at("/embedding/model").asText());
        baseline.put("reranker_model", report.at("/reranker/model").asText());
        baseline.put("hard_queries", report.at("/dataset/hard_queries").asInt());
        baseline.set("configs", report.get("configs"));
        baseline.set("calibration", report.get("calibration"));
        baseline.set("cost", report.get("cost"));
        baseline.set("decision", report.get("decision"));
        Files.createDirectories(BASELINE.getParent());
        Files.writeString(BASELINE, MAPPER.writeValueAsString(baseline) + System.lineSeparator());
        System.out.println("[P5-07-3] 已写入基线 " + BASELINE.toAbsolutePath());
    }

    private static String embeddingKey() {
        String key = System.getenv("RAG_EVAL_EMBEDDING_KEY");
        return key == null || key.isBlank() ? blankToNull(System.getenv("EMBEDDING_API_KEY")) : key;
    }

    private static String rerankKey() {
        return blankToNull(System.getenv("RAG_EVAL_RERANK_KEY"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
