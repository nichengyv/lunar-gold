package com.capsule.agentkey.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5-03：把脚本化评测与入库基线对比，质量或成本回退时让 Maven/CI 失败。
 *
 * <p>正常检查：{@code mvn test -Dtest=EvalGate}
 * <p>确认变更后更新基线：{@code mvn test -Dtest=EvalGate -Deval.updateBaseline=true}
 */
public class EvalGate {

    static final Path BASELINE_PATH = Paths.get("eval", "baselines", "baseline.json");
    static final double PASS_RATE_DROP_PP = 2.0;
    static final double ROUNDS_INCREASE_PCT = 10.0;
    static final double TOKEN_INCREASE_PCT = 10.0;
    static final double P95_INCREASE_PCT = 20.0;
    static final double P95_ABSOLUTE_SLACK_MS = 25.0;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    public void qualityAndCostMustNotRegress() throws IOException {
        EvalRunner.RunResult run = EvalRunner.execute();
        ObjectNode current = run.report();

        if (Boolean.getBoolean("eval.updateBaseline")) {
            ObjectNode baseline = baselineFrom(current);
            Files.createDirectories(BASELINE_PATH.getParent());
            Files.writeString(BASELINE_PATH, MAPPER.writeValueAsString(baseline) + System.lineSeparator());
            System.out.println("评测基线已更新: " + BASELINE_PATH);
            return;
        }

        assertTrue(Files.exists(BASELINE_PATH),
                "缺少评测基线 " + BASELINE_PATH + "；请先运行 -Deval.updateBaseline=true");
        ObjectNode baseline = (ObjectNode) MAPPER.readTree(Files.readString(BASELINE_PATH));
        List<String> failures = compare(baseline, current);
        assertTrue(failures.isEmpty(), () -> "评测门禁发现回退：\n - " + String.join("\n - ", failures)
                + "\n若变更属于预期改进，请审查报告后使用 -Deval.updateBaseline=true 更新基线。");
        System.out.println("评测门禁通过：质量、轮次、token 与 p95 时延均未超出基线容忍度。");
    }

    static ObjectNode baselineFrom(ObjectNode report) {
        ObjectNode baseline = report.deepCopy();
        baseline.remove("timestamp");
        baseline.put("version", 1);
        ObjectNode thresholds = MAPPER.createObjectNode();
        thresholds.put("pass_rate_drop_pp", PASS_RATE_DROP_PP);
        thresholds.put("rounds_increase_pct", ROUNDS_INCREASE_PCT);
        thresholds.put("token_increase_pct", TOKEN_INCREASE_PCT);
        thresholds.put("p95_increase_pct", P95_INCREASE_PCT);
        thresholds.put("p95_absolute_slack_ms", P95_ABSOLUTE_SLACK_MS);
        baseline.set("thresholds", thresholds);

        ArrayNode cases = MAPPER.createArrayNode();
        for (JsonNode c : baseline.path("cases")) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("id", c.path("id").asText());
            item.put("category", c.path("category").asText());
            item.put("status", c.path("status").asText());
            cases.add(item);
        }
        baseline.set("cases", cases);
        return baseline;
    }

    static List<String> compare(ObjectNode baseline, ObjectNode current) {
        List<String> failures = new ArrayList<>();
        compareCaseSetAndStatus(baseline, current, failures);

        double baselineRate = rate(baseline.path("passed").asInt(), baseline.path("total").asInt());
        double currentRate = rate(current.path("passed").asInt(), current.path("total").asInt());
        checkPassRate("TOTAL", baselineRate, currentRate, failures);
        checkLatency("TOTAL", baseline.path("p95_latency_ms").asDouble(),
                current.path("p95_latency_ms").asDouble(), failures);

        Map<String, JsonNode> baselineCategories = byName(baseline.path("categories"));
        Map<String, JsonNode> currentCategories = byName(current.path("categories"));
        for (String category : baselineCategories.keySet()) {
            JsonNode before = baselineCategories.get(category);
            JsonNode after = currentCategories.get(category);
            if (after == null) {
                failures.add(category + ": 类别在当前评测中缺失");
                continue;
            }
            compareCategory(category, before, after, failures);
        }
        for (String category : currentCategories.keySet()) {
            if (!baselineCategories.containsKey(category)) {
                failures.add(category + ": 新增类别尚未纳入基线");
            }
        }
        return failures;
    }

    private static void compareCaseSetAndStatus(ObjectNode baseline, ObjectNode current, List<String> failures) {
        Map<String, JsonNode> before = byId(baseline.path("cases"));
        Map<String, JsonNode> after = byId(current.path("cases"));
        for (var entry : before.entrySet()) {
            JsonNode currentCase = after.get(entry.getKey());
            if (currentCase == null) {
                failures.add(entry.getKey() + ": 基线用例在当前评测中缺失");
            } else if ("pass".equals(entry.getValue().path("status").asText())
                    && !"pass".equals(currentCase.path("status").asText())) {
                failures.add(entry.getKey() + " [" + entry.getValue().path("category").asText()
                        + "]: 用例由 pass 回退为 " + currentCase.path("status").asText());
            }
        }
        for (String id : after.keySet()) {
            if (!before.containsKey(id)) failures.add(id + ": 新增用例尚未纳入基线");
        }
    }

    private static void compareCategory(String category, JsonNode before, JsonNode after, List<String> failures) {
        checkPassRate(category, before.path("pass_rate").asDouble(), after.path("pass_rate").asDouble(), failures);
        checkIncrease(category, "avg_rounds", before.path("avg_rounds").asDouble(),
                after.path("avg_rounds").asDouble(), ROUNDS_INCREASE_PCT, 0.05, failures);
        checkIncrease(category, "avg_tokens", before.path("avg_tokens").asDouble(),
                after.path("avg_tokens").asDouble(), TOKEN_INCREASE_PCT, 1.0, failures);
        checkLatency(category, before.path("p95_latency_ms").asDouble(),
                after.path("p95_latency_ms").asDouble(), failures);
    }

    private static void checkPassRate(String category, double before, double after, List<String> failures) {
        if (after < before - PASS_RATE_DROP_PP) {
            failures.add(String.format(Locale.ROOT, "%s pass_rate: %.2f%% -> %.2f%%（下降超过 %.1fpp）",
                    category, before, after, PASS_RATE_DROP_PP));
        }
    }

    private static void checkIncrease(String category, String metric, double before, double after,
                                      double tolerancePct, double absoluteSlack, List<String> failures) {
        double allowed = before * (1 + tolerancePct / 100.0) + absoluteSlack;
        if (after > allowed) {
            failures.add(String.format(Locale.ROOT, "%s %s: %.2f -> %.2f（允许上限 %.2f，增幅阈值 %.0f%%）",
                    category, metric, before, after, allowed, tolerancePct));
        }
    }

    private static void checkLatency(String category, double before, double after, List<String> failures) {
        double allowed = before * (1 + P95_INCREASE_PCT / 100.0) + P95_ABSOLUTE_SLACK_MS;
        if (after > allowed) {
            failures.add(String.format(Locale.ROOT, "%s p95_latency_ms: %.0fms -> %.0fms（允许上限 %.0fms，增幅阈值 %.0f%%）",
                    category, before, after, allowed, P95_INCREASE_PCT));
        }
    }

    private static double rate(int passed, int total) {
        return total == 0 ? 0 : passed * 100.0 / total;
    }

    private static Map<String, JsonNode> byName(JsonNode categories) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        categories.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static Map<String, JsonNode> byId(JsonNode cases) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode c : cases) result.put(c.path("id").asText(), c);
        return result;
    }
}
