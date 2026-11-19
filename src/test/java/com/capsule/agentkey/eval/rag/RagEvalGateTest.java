package com.capsule.agentkey.eval.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** P5-07-2：检索质量门禁，证明混合检索相对单路向量没有回退，并且确实救回精确词查询。 */
@EnabledIfEnvironmentVariable(named = "DESKTOP_PG_RUNTIME", matches = ".+")
public class RagEvalGateTest {

    static final Path BASELINE_PATH = Paths.get("eval", "baselines", "rag-baseline.json");
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final double RATE_DROP_PP = 2.0;
    private static final double P95_INCREASE_RATIO = 0.20;
    private static final double P95_ABSOLUTE_SLACK_MS = 25.0;

    @Test
    public void hybridRetrievalMustBeatVectorOnExactQueries() throws Exception {
        RagRetrievalEvalRunner.Outcome outcome = RagRetrievalEvalRunner.execute();
        ObjectNode current = outcome.report();

        List<String> failures = new ArrayList<>();
        compareHybridAgainstVector(current, failures);
        compareNoiseRejection(current, failures);

        if (Boolean.getBoolean("eval.updateBaseline")) {
            assertTrue(failures.isEmpty(), () -> "核心检索断言未通过，禁止写入 RAG 基线：\n - "
                    + String.join("\n - ", failures));
            Files.createDirectories(BASELINE_PATH.getParent());
            ObjectNode baseline = current.deepCopy();
            baseline.remove("generated_at");
            baseline.remove("delta");
            Files.writeString(BASELINE_PATH, MAPPER.writeValueAsString(baseline) + System.lineSeparator());
            System.out.println("RAG 评测基线已更新: " + BASELINE_PATH);
            return;
        }

        compareBaseline(current, failures);
        assertTrue(failures.isEmpty(), () -> "RAG 检索门禁发现回退：\n - "
                + String.join("\n - ", failures)
                + "\n若变更属于预期改进，请审查报告后使用 -Deval.updateBaseline=true 更新基线。");
        System.out.println("RAG 检索门禁通过：混合检索未低于纯向量，精确词查询被救回，noise 全部拒绝。");
    }

    private static void compareHybridAgainstVector(ObjectNode current, List<String> failures) {
        double vectorRecall5 = current.at("/configs/vector/recall@5").asDouble();
        double hybridRecall5 = current.at("/configs/hybrid/recall@5").asDouble();
        if (hybridRecall5 < vectorRecall5 - 1e-9) {
            failures.add(String.format(Locale.ROOT, "整体 Recall@5: hybrid %.2f%% 低于 vector %.2f%%",
                    hybridRecall5, vectorRecall5));
        }

        double vectorExact = current.at("/categories/exact/vector/recall@5").asDouble();
        double hybridExact = current.at("/categories/exact/hybrid/recall@5").asDouble();
        if (hybridExact <= vectorExact) {
            failures.add(String.format(Locale.ROOT,
                    "exact 类 Recall@5: hybrid %.2f%% 未高于 vector %.2f%%，精确词查询没有被救回",
                    hybridExact, vectorExact));
        }
    }

    private static void compareNoiseRejection(ObjectNode current, List<String> failures) {
        int total = current.at("/noise/total").asInt();
        for (String config : RagRetrievalEvalRunner.CONFIGS) {
            int rejected = current.at("/noise/rejected/" + config).asInt();
            if (rejected < total) {
                failures.add(String.format(Locale.ROOT, "noise 拒绝数 %s: %d/%d，存在无关查询被当成命中",
                        config, rejected, total));
            }
        }
    }

    private static void compareBaseline(ObjectNode current, List<String> failures) throws IOException {
        if (!Files.exists(BASELINE_PATH)) {
            failures.add("缺少 RAG 评测基线 " + BASELINE_PATH + "；请先运行 -Deval.updateBaseline=true");
            return;
        }
        ObjectNode baseline = (ObjectNode) MAPPER.readTree(Files.readString(BASELINE_PATH));
        String baselineMode = baseline.path("embedding").asText();
        String currentMode = current.path("embedding").asText();
        if (!baselineMode.equals(currentMode)) {
            System.out.println("基线 embedding 模式为 " + baselineMode + "，本次为 " + currentMode
                    + "，跳过基线对比（模式不同不可直接比较）。");
            return;
        }
        for (String config : RagRetrievalEvalRunner.CONFIGS) {
            compareRate(baseline, current, "/configs/" + config, config, failures);
            compareLatency(baseline, current, "/configs/" + config, config, failures);
        }
        compareRate(baseline, current, "/categories/exact/vector", "exact/vector", failures);
        compareRate(baseline, current, "/categories/exact/hybrid", "exact/hybrid", failures);
    }

    private static void compareRate(ObjectNode baseline, ObjectNode current, String prefix,
                                    String label, List<String> failures) {
        double beforeRecall = baseline.at(prefix + "/recall@5").asDouble();
        double afterRecall = current.at(prefix + "/recall@5").asDouble();
        if (afterRecall < beforeRecall - RATE_DROP_PP) {
            failures.add(String.format(Locale.ROOT, "%s Recall@5: %.2f%% -> %.2f%%（下降超过 %.1fpp）",
                    label, beforeRecall, afterRecall, RATE_DROP_PP));
        }
        double beforeHit = baseline.at(prefix + "/doc_hit_rate").asDouble();
        double afterHit = current.at(prefix + "/doc_hit_rate").asDouble();
        if (afterHit < beforeHit - RATE_DROP_PP) {
            failures.add(String.format(Locale.ROOT, "%s 文档命中率: %.2f%% -> %.2f%%（下降超过 %.1fpp）",
                    label, beforeHit, afterHit, RATE_DROP_PP));
        }
    }

    private static void compareLatency(ObjectNode baseline, ObjectNode current, String prefix,
                                       String label, List<String> failures) {
        double before = baseline.at(prefix + "/p95_latency_ms").asDouble();
        double after = current.at(prefix + "/p95_latency_ms").asDouble();
        double limit = before * (1d + P95_INCREASE_RATIO) + P95_ABSOLUTE_SLACK_MS;
        if (after > limit) {
            failures.add(String.format(Locale.ROOT, "%s p95: %.2fms -> %.2fms（超过上限 %.2fms）",
                    label, before, after, limit));
        }
    }
}
