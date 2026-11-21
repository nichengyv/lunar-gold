package com.capsule.agentkey.eval.longsession;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R7b live 验证：真实模型下「断点续跑」完成率的预注册门禁。
 *
 * <p>阈值在编码前固定，任何调整都必须单独提交并说明理由：
 * <ul>
 *   <li>每层至少 {@link #MIN_CASES_PER_LEVEL} 个「已注入中断并完成续跑」的配对 case</li>
 *   <li>续跑成功率 Wilson 95% 置信下限 ≥ {@link #MIN_SUCCESS_RATE_LOWER_BOUND}</li>
 *   <li>续跑成功率相对同一批直接执行下降 ≤ {@link #MAX_SUCCESS_RATE_DROP}（绝对百分点）</li>
 *   <li>续跑臂 provider 失败率 ≤ {@link #MAX_PROVIDER_FAILURE_RATE}</li>
 *   <li>续跑 token 中位数 / 直接执行 ≤ {@link #MAX_TOKEN_OVERHEAD_RATIO}</li>
 *   <li>续跑耗时中位数 / 直接执行 ≤ {@link #MAX_WALL_OVERHEAD_RATIO}</li>
 * </ul>
 *
 * <p>真实的进程重启会带来额外的启动开销，而评测在同一 JVM 内复现状态迁移，
 * 因此耗时阈值放宽到 2 倍，只用于发现「续跑重复执行整轮工具」这类结构性问题；
 * token 阈值更严格，因为崩溃发生在模型调用前，理论上不应重放已落地的轮次。
 *
 * <p>未能注入中断（例如某轮没有任何工具调用、没有断点）的 case 不计入成功率，
 * 单独记录为 {@code notApplicable}，避免把「没测到」算成「通过了」。
 */
final class LongSessionResumeGate {

    static final int MIN_CASES_PER_LEVEL = LongSessionDataset.MIN_CASES_PER_LEVEL;
    static final double CONFIDENCE_Z = 1.959963984540054;
    static final double MIN_SUCCESS_RATE_LOWER_BOUND = 0.50;
    static final double MAX_SUCCESS_RATE_DROP = 0.10;
    static final double MAX_PROVIDER_FAILURE_RATE = 0.05;
    static final double MAX_TOKEN_OVERHEAD_RATIO = 1.50;
    static final double MAX_WALL_OVERHEAD_RATIO = 2.00;

    private LongSessionResumeGate() {
    }

    /**
     * @param directRuns 直接执行臂，字段至少含 level/id/repeat/success/failureAttribution/totalTokens/wallMs
     * @param resumeRuns 续跑臂，额外含 resumeInjected；同 level/id/repeat 与直接执行配对
     */
    static ObjectNode evaluate(List<ObjectNode> directRuns, List<ObjectNode> resumeRuns) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("thresholds", thresholds());
        root.put("interruptPoint", "first_checkpoint_then_next_model_call");
        root.put("notApplicableDefinition", "resumeInjected=false：该 case 在首个断点前已结束，未测到续跑");

        ObjectNode byLevel = root.putObject("byLevel");
        List<String> reasons = new ArrayList<>();
        boolean allLevelsPass = true;
        int totalCases = 0;
        int totalResumeSuccesses = 0;
        int totalDirectSuccesses = 0;

        for (String level : List.of("L1", "L2", "L3")) {
            Map<String, ObjectNode> directByKey = new LinkedHashMap<>();
            for (ObjectNode run : directRuns) {
                if (level.equals(run.path("level").asText())) {
                    directByKey.put(key(run), run);
                }
            }

            int attempts = 0;
            int notApplicable = 0;
            int unmatched = 0;
            int cases = 0;
            int resumeSuccesses = 0;
            int directSuccesses = 0;
            int providerFailures = 0;
            List<Long> resumeTokens = new ArrayList<>();
            List<Long> directTokens = new ArrayList<>();
            List<Long> resumeWall = new ArrayList<>();
            List<Long> directWall = new ArrayList<>();

            for (ObjectNode resumed : resumeRuns) {
                if (!level.equals(resumed.path("level").asText())) {
                    continue;
                }
                attempts++;
                ObjectNode direct = directByKey.get(key(resumed));
                if (direct == null) {
                    unmatched++;
                    continue;
                }
                if (!resumed.path("resumeInjected").asBoolean()) {
                    notApplicable++;
                    continue;
                }
                cases++;
                if (resumed.path("success").asBoolean()) resumeSuccesses++;
                if (direct.path("success").asBoolean()) directSuccesses++;
                if ("MODEL_OR_PROVIDER".equals(resumed.path("failureAttribution").asText())) {
                    providerFailures++;
                }
                resumeTokens.add(resumed.path("totalTokens").asLong());
                directTokens.add(direct.path("totalTokens").asLong());
                resumeWall.add(resumed.path("wallMs").asLong());
                directWall.add(direct.path("wallMs").asLong());
            }

            double resumeRate = rate(resumeSuccesses, cases);
            double directRate = rate(directSuccesses, cases);
            double drop = round(Math.max(0, directRate - resumeRate));
            double lowerBound = wilsonLowerBound(resumeSuccesses, cases);
            double providerRate = rate(providerFailures, cases);
            long resumeTokenMedian = median(resumeTokens);
            long directTokenMedian = median(directTokens);
            long resumeWallMedian = median(resumeWall);
            long directWallMedian = median(directWall);
            double tokenRatio = ratio(resumeTokenMedian, directTokenMedian);
            double wallRatio = ratio(resumeWallMedian, directWallMedian);

            ObjectNode node = byLevel.putObject(level);
            node.put("attempts", attempts);
            node.put("cases", cases);
            node.put("notApplicable", notApplicable);
            node.put("unmatched", unmatched);
            node.put("resumeSuccesses", resumeSuccesses);
            node.put("directSuccesses", directSuccesses);
            node.put("resumeSuccessRate", resumeRate);
            node.put("directSuccessRate", directRate);
            node.put("successRateDrop", drop);
            node.put("resumeSuccessRateLowerBound95", round(lowerBound));
            node.put("providerFailures", providerFailures);
            node.put("providerFailureRate", providerRate);
            node.put("resumeTokensMedian", resumeTokenMedian);
            node.put("directTokensMedian", directTokenMedian);
            node.put("tokenRatio", tokenRatio);
            node.put("resumeWallMsMedian", resumeWallMedian);
            node.put("directWallMsMedian", directWallMedian);
            node.put("wallRatio", wallRatio);

            ArrayNode levelReasons = node.putArray("reasons");
            if (cases < MIN_CASES_PER_LEVEL) {
                levelReasons.add("cases=" + cases + ", required=" + MIN_CASES_PER_LEVEL);
            }
            if (providerRate > MAX_PROVIDER_FAILURE_RATE) {
                levelReasons.add("providerFailureRate=" + providerRate + ", max=" + MAX_PROVIDER_FAILURE_RATE);
            }
            if (lowerBound < MIN_SUCCESS_RATE_LOWER_BOUND) {
                levelReasons.add("resumeSuccessRateLowerBound95=" + round(lowerBound)
                        + ", required=" + MIN_SUCCESS_RATE_LOWER_BOUND);
            }
            if (drop > MAX_SUCCESS_RATE_DROP) {
                levelReasons.add("successRateDrop=" + drop + ", max=" + MAX_SUCCESS_RATE_DROP);
            }
            if (tokenRatio > MAX_TOKEN_OVERHEAD_RATIO) {
                levelReasons.add("tokenRatio=" + tokenRatio + ", max=" + MAX_TOKEN_OVERHEAD_RATIO);
            }
            if (wallRatio > MAX_WALL_OVERHEAD_RATIO) {
                levelReasons.add("wallRatio=" + wallRatio + ", max=" + MAX_WALL_OVERHEAD_RATIO);
            }

            boolean pass = levelReasons.isEmpty();
            node.put("pass", pass);
            if (!pass) {
                allLevelsPass = false;
                for (int i = 0; i < levelReasons.size(); i++) {
                    reasons.add(level + ": " + levelReasons.get(i).asText());
                }
            }
            totalCases += cases;
            totalResumeSuccesses += resumeSuccesses;
            totalDirectSuccesses += directSuccesses;
        }

        ObjectNode overall = root.putObject("overall");
        overall.put("cases", totalCases);
        overall.put("resumeSuccesses", totalResumeSuccesses);
        overall.put("directSuccesses", totalDirectSuccesses);
        overall.put("resumeSuccessRate", rate(totalResumeSuccesses, totalCases));
        overall.put("directSuccessRate", rate(totalDirectSuccesses, totalCases));
        overall.put("resumeSuccessRateLowerBound95", round(wilsonLowerBound(totalResumeSuccesses, totalCases)));
        overall.put("pass", allLevelsPass);
        ArrayNode reasonNodes = root.putArray("reasons");
        reasons.forEach(reasonNodes::add);
        root.put("pass", allLevelsPass);
        return root;
    }

    private static ObjectNode thresholds() {
        ObjectNode thresholds = JsonNodeFactory.instance.objectNode();
        thresholds.put("minCasesPerLevel", MIN_CASES_PER_LEVEL);
        thresholds.put("confidenceLevel", 0.95);
        thresholds.put("minSuccessRateLowerBound95", MIN_SUCCESS_RATE_LOWER_BOUND);
        thresholds.put("maxSuccessRateDrop", MAX_SUCCESS_RATE_DROP);
        thresholds.put("maxProviderFailureRate", MAX_PROVIDER_FAILURE_RATE);
        thresholds.put("maxTokenOverheadRatio", MAX_TOKEN_OVERHEAD_RATIO);
        thresholds.put("maxWallOverheadRatio", MAX_WALL_OVERHEAD_RATIO);
        return thresholds;
    }

    private static String key(ObjectNode run) {
        return run.path("id").asText() + "|" + run.path("repeat").asInt();
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

    private static double ratio(long current, long baseline) {
        return baseline <= 0 ? 0 : round((double) current / baseline);
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
