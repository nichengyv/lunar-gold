package com.capsule.agentkey.eval.longsession;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * R7a/P8-04 统计门禁：先预注册阈值，再由真实运行数据判定，不用点估计冒充结论。
 *
 * <p>阈值在编码前固定，任何调整都必须单独提交并说明理由：
 * <ul>
 *   <li>每层至少 {@link #MIN_CASES_PER_LEVEL} 个 case</li>
 *   <li>每层成功率 Wilson 95% 置信下限 ≥ {@link #MIN_SUCCESS_RATE_LOWER_BOUND}</li>
 *   <li>L3 记忆探针通过率 ≥ {@link #MIN_MEMORY_PROBE_RATE}</li>
 *   <li>provider 失败率 ≤ {@link #MAX_PROVIDER_FAILURE_RATE}（超过即测量无效）</li>
 *   <li>相对基线：成功率不下降，token/耗时中位数退化 < {@link #MAX_MEDIAN_DEGRADATION}</li>
 * </ul>
 */
final class LongSessionStatsGate {

    static final int MIN_CASES_PER_LEVEL = LongSessionDataset.MIN_CASES_PER_LEVEL;
    static final double CONFIDENCE_Z = 1.959963984540054;
    /** 与 L8「统计置信度」对齐：n=20 时约需 14 次以上成功才能达到下限 */
    static final double MIN_SUCCESS_RATE_LOWER_BOUND = 0.50;
    static final double MIN_MEMORY_PROBE_RATE = 0.95;
    static final double MAX_PROVIDER_FAILURE_RATE = 0.05;
    static final double MAX_MEDIAN_DEGRADATION = 1.15;

    private LongSessionStatsGate() {
    }

    /**
     * @param runs     每次真实运行的指标节点，至少包含 level/success/failureAttribution/totalTokens/wallMs
     * @param baseline 上一版 n≥10 基线报告节点，可为 null；格式与报告 byLevel 一致
     */
    static ObjectNode evaluate(List<ObjectNode> runs, ObjectNode baseline) {
        ObjectNode root = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        root.set("thresholds", thresholds());
        root.put("baselineStatus", baseline == null ? "missing" : "present");

        ObjectNode byLevel = root.putObject("byLevel");
        List<String> reasons = new ArrayList<>();
        boolean allLevelsPass = true;
        int totalCases = 0;
        int totalSuccesses = 0;

        for (String level : List.of("L1", "L2", "L3")) {
            List<ObjectNode> selected = runs.stream()
                    .filter(run -> level.equals(run.path("level").asText()))
                    .toList();
            ObjectNode node = byLevel.putObject(level);
            int cases = selected.size();
            int successes = (int) selected.stream().filter(run -> run.path("success").asBoolean()).count();
            int providerFailures = (int) selected.stream()
                    .filter(run -> "MODEL_OR_PROVIDER".equals(run.path("failureAttribution").asText()))
                    .count();
            double successRate = rate(successes, cases);
            double lowerBound = wilsonLowerBound(successes, cases);
            double providerRate = rate(providerFailures, cases);
            double memoryProbeRate = rate((int) selected.stream()
                    .filter(run -> run.path("memoryProbePassed").asBoolean()).count(), cases);

            node.put("cases", cases);
            node.put("successes", successes);
            node.put("successRate", successRate);
            node.put("successRateLowerBound95", round(lowerBound));
            node.put("providerFailures", providerFailures);
            node.put("providerFailureRate", providerRate);
            node.put("memoryProbeRate", memoryProbeRate);
            node.put("totalTokensMedian", median(selected.stream()
                    .map(run -> run.path("totalTokens").asLong()).toList()));
            node.put("wallMsMedian", median(selected.stream()
                    .map(run -> run.path("wallMs").asLong()).toList()));

            ArrayNode levelReasons = node.putArray("reasons");
            if (cases < MIN_CASES_PER_LEVEL) {
                levelReasons.add("cases=" + cases + ", required=" + MIN_CASES_PER_LEVEL);
            }
            if (providerRate > MAX_PROVIDER_FAILURE_RATE) {
                levelReasons.add("providerFailureRate=" + providerRate + ", max=" + MAX_PROVIDER_FAILURE_RATE);
            }
            if (lowerBound < MIN_SUCCESS_RATE_LOWER_BOUND) {
                levelReasons.add("successRateLowerBound95=" + round(lowerBound)
                        + ", required=" + MIN_SUCCESS_RATE_LOWER_BOUND);
            }
            if ("L3".equals(level) && memoryProbeRate < MIN_MEMORY_PROBE_RATE) {
                levelReasons.add("memoryProbeRate=" + memoryProbeRate + ", required=" + MIN_MEMORY_PROBE_RATE);
            }
            attachBaseline(baseline, level, node, successRate, levelReasons);

            boolean pass = levelReasons.isEmpty();
            node.put("pass", pass);
            if (!pass) {
                allLevelsPass = false;
                for (int i = 0; i < levelReasons.size(); i++) {
                    reasons.add(level + ": " + levelReasons.get(i).asText());
                }
            }
            totalCases += cases;
            totalSuccesses += successes;
        }

        ObjectNode overall = root.putObject("overall");
        overall.put("cases", totalCases);
        overall.put("successes", totalSuccesses);
        overall.put("successRate", rate(totalSuccesses, totalCases));
        overall.put("successRateLowerBound95", round(wilsonLowerBound(totalSuccesses, totalCases)));
        overall.put("pass", allLevelsPass);
        ArrayNode reasonNodes = root.putArray("reasons");
        reasons.forEach(reasonNodes::add);
        root.put("pass", allLevelsPass);
        return root;
    }

    private static void attachBaseline(ObjectNode baseline, String level, ObjectNode node,
                                       double successRate, ArrayNode levelReasons) {
        if (baseline == null) return;
        com.fasterxml.jackson.databind.JsonNode base = baseline.path("byLevel").path(level);
        if (base.isMissingNode() || base.isEmpty()) {
            node.put("baselineStatus", "level_missing");
            return;
        }
        double baseSuccessRate = base.path("successRate").asDouble(-1);
        if (baseSuccessRate >= 0 && successRate < baseSuccessRate) {
            levelReasons.add("successRateDegraded=" + successRate + ", baseline=" + baseSuccessRate);
        }
        node.put("successRateDegraded", baseSuccessRate >= 0 && successRate < baseSuccessRate);
        putDegradation(node, "totalTokens", medianOf(node, "totalTokensMedian"), base.path("totalTokensMedian").asLong(0));
        putDegradation(node, "wallMs", medianOf(node, "wallMsMedian"), base.path("wallMsMedian").asLong(0));
        node.path("degradation").fields().forEachRemaining(entry -> {
            double ratio = entry.getValue().path("ratio").asDouble(1.0);
            if (ratio > MAX_MEDIAN_DEGRADATION) {
                levelReasons.add(entry.getKey() + "Degraded=" + ratio + ", max=" + MAX_MEDIAN_DEGRADATION);
            }
        });
    }

    private static long medianOf(ObjectNode node, String field) {
        return node.path(field).asLong(0);
    }

    private static void putDegradation(ObjectNode node, String name, long current, long baseline) {
        if (baseline <= 0) return;
        ObjectNode degradation = node.has("degradation") ? (ObjectNode) node.get("degradation") : node.putObject("degradation");
        ObjectNode entry = degradation.putObject(name);
        double ratio = round((double) current / baseline);
        entry.put("currentMedian", current);
        entry.put("baselineMedian", baseline);
        entry.put("ratio", ratio);
    }

    private static ObjectNode thresholds() {
        ObjectNode thresholds = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        thresholds.put("minCasesPerLevel", MIN_CASES_PER_LEVEL);
        thresholds.put("confidenceLevel", 0.95);
        thresholds.put("minSuccessRateLowerBound95", MIN_SUCCESS_RATE_LOWER_BOUND);
        thresholds.put("minMemoryProbeRate", MIN_MEMORY_PROBE_RATE);
        thresholds.put("maxProviderFailureRate", MAX_PROVIDER_FAILURE_RATE);
        thresholds.put("maxMedianDegradation", MAX_MEDIAN_DEGRADATION);
        return thresholds;
    }

    /** Wilson score 区间下限；n=0 时返回 0，避免用 0/0 冒充高置信。 */
    static double wilsonLowerBound(int successes, int trials) {
        if (trials <= 0) return 0;
        double p = (double) successes / trials;
        double z2 = CONFIDENCE_Z * CONFIDENCE_Z;
        double denominator = 1 + z2 / trials;
        double center = p + z2 / (2.0 * trials);
        double margin = CONFIDENCE_Z * Math.sqrt(p * (1 - p) / trials + z2 / (4.0 * trials * trials));
        return Math.max(0, (center - margin) / denominator);
    }

    private static double rate(int value, int total) {
        return total == 0 ? 0 : round(value * 10000.0 / total) / 10000.0;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }
}
