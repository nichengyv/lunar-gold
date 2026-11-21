package com.capsule.agentkey.eval.longsession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R7a/P8-04 统计门禁的离线单测；只验证预注册阈值，不产生任何真实调用。 */
class LongSessionStatsGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void wilson下限随样本量与成功率单调() {
        assertTrue(LongSessionStatsGate.wilsonLowerBound(10, 10) > 0.70,
                "10/10 的 95% 下限应高于 0.70");
        assertTrue(LongSessionStatsGate.wilsonLowerBound(0, 10) == 0.0,
                "0/10 的下限应为 0");
        assertTrue(LongSessionStatsGate.wilsonLowerBound(5, 10) < 0.50,
                "5/10 的下限应低于 0.50");
        assertTrue(LongSessionStatsGate.wilsonLowerBound(15, 20) > LongSessionStatsGate.wilsonLowerBound(8, 10),
                "相同成功率下样本越多下限越高");
        assertTrue(LongSessionStatsGate.wilsonLowerBound(0, 0) == 0.0,
                "空样本不得给出非零置信");
    }

    @Test
    void 每层十个全成功则通过() {
        List<ObjectNode> runs = new ArrayList<>();
        for (String level : List.of("L1", "L2", "L3")) {
            for (int i = 0; i < 10; i++) runs.add(run(level, true, "NONE", 1000, 100, true));
        }
        ObjectNode result = LongSessionStatsGate.evaluate(runs, null);
        assertTrue(result.path("pass").asBoolean(), "30 个全成功应通过：" + result.path("reasons"));
        assertTrue(result.path("byLevel").path("L1").path("pass").asBoolean());
        assertTrue(result.path("byLevel").path("L3").path("pass").asBoolean());
        assertTrue(result.path("byLevel").path("L1").path("successRateLowerBound95").asDouble() > 0.70);
        assertTrue(result.path("reasons").isEmpty());
    }

    @Test
    void 每层不足十个案例直接阻断() {
        List<ObjectNode> runs = new ArrayList<>();
        for (String level : List.of("L1", "L2")) {
            for (int i = 0; i < 10; i++) runs.add(run(level, true, "NONE", 1000, 100, true));
        }
        for (int i = 0; i < 9; i++) runs.add(run("L3", true, "NONE", 1000, 100, true));

        ObjectNode result = LongSessionStatsGate.evaluate(runs, null);
        assertFalse(result.path("pass").asBoolean(), "L3 只有 9 个 case 不得通过");
        assertTrue(result.path("reasons").toString().contains("cases=9"), result.path("reasons").toString());
    }

    @Test
    void provider失败率超过百分之五使测量无效() {
        List<ObjectNode> runs = allLevelRuns(10);
        runs.add(run("L1", false, "MODEL_OR_PROVIDER", 1000, 100, true));
        ObjectNode result = LongSessionStatsGate.evaluate(runs, null);
        assertFalse(result.path("pass").asBoolean());
        assertTrue(result.path("reasons").toString().contains("providerFailureRate"), result.path("reasons").toString());
    }

    @Test
    void L3记忆探针低于百分之九十五不通过() {
        List<ObjectNode> runs = new ArrayList<>();
        for (String level : List.of("L1", "L2")) {
            for (int i = 0; i < 10; i++) runs.add(run(level, true, "NONE", 1000, 100, true));
        }
        for (int i = 0; i < 10; i++) runs.add(run("L3", true, "NONE", 1000, 100, i < 9));
        ObjectNode result = LongSessionStatsGate.evaluate(runs, null);
        assertFalse(result.path("pass").asBoolean(), "L3 记忆探针 9/10 不得通过");
        assertTrue(result.path("reasons").toString().contains("memoryProbeRate"), result.path("reasons").toString());
    }

    @Test
    void 成功率下降与token耗时退化超过百分之十五阻断() {
        ObjectNode baseline = MAPPER.createObjectNode();
        ObjectNode byLevel = baseline.putObject("byLevel");
        ObjectNode l1 = byLevel.putObject("L1");
        l1.put("successRate", 0.9);
        l1.put("totalTokensMedian", 100);
        l1.put("wallMsMedian", 100);

        List<ObjectNode> runs = allLevelRuns(10);
        for (int i = 0; i < 10; i++) runs.set(i, run("L1", true, "NONE", 130, 130, true));
        ObjectNode result = LongSessionStatsGate.evaluate(runs, baseline);

        assertFalse(result.path("pass").asBoolean(), "成功率 1.0 不降但 token/耗时退化 30% 必须阻断");
        String reasons = result.path("reasons").toString();
        assertTrue(reasons.contains("totalTokensDegraded"), reasons);
        assertTrue(reasons.contains("wallMsDegraded"), reasons);
        assertTrue(result.path("byLevel").path("L1").path("degradation").path("totalTokens").path("ratio").asDouble() == 1.3);
    }

    @Test
    void 恰好百分之十五的退化不阻断() {
        ObjectNode baseline = MAPPER.createObjectNode();
        ObjectNode l1 = baseline.putObject("byLevel").putObject("L1");
        l1.put("successRate", 1.0);
        l1.put("totalTokensMedian", 100);
        l1.put("wallMsMedian", 100);

        List<ObjectNode> runs = allLevelRuns(10);
        for (int i = 0; i < 10; i++) runs.set(i, run("L1", true, "NONE", 115, 115, true));
        ObjectNode result = LongSessionStatsGate.evaluate(runs, baseline);

        assertTrue(result.path("pass").asBoolean(), "退化恰好 15% 不应阻断：" + result.path("reasons"));
        assertTrue(result.path("byLevel").path("L1").path("degradation").path("totalTokens").path("ratio").asDouble() == 1.15);
    }

    @Test
    void 成功率低于基线即使其它指标正常也阻断() {
        ObjectNode baseline = MAPPER.createObjectNode();
        baseline.putObject("byLevel").putObject("L2").put("successRate", 1.0);

        List<ObjectNode> runs = allLevelRuns(10);
        for (int i = 10; i < 20; i++) runs.set(i, run("L2", i < 18, i < 18 ? "NONE" : "STEP_OMISSION", 100, 100, true));
        ObjectNode result = LongSessionStatsGate.evaluate(runs, baseline);

        assertFalse(result.path("pass").asBoolean(), "成功率从 100% 降到 80% 不得通过");
        assertTrue(result.path("reasons").toString().contains("successRateDegraded"), result.path("reasons").toString());
    }

    private static List<ObjectNode> allLevelRuns(int perLevel) {
        List<ObjectNode> runs = new ArrayList<>();
        for (String level : List.of("L1", "L2", "L3")) {
            for (int i = 0; i < perLevel; i++) runs.add(run(level, true, "NONE", 1000, 100, true));
        }
        return runs;
    }

    private static ObjectNode run(String level, boolean success, String attribution,
                                  long tokens, long wallMs, boolean memoryProbe) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("level", level);
        node.put("success", success);
        node.put("failureAttribution", attribution);
        node.put("totalTokens", tokens);
        node.put("wallMs", wallMs);
        node.put("memoryProbePassed", memoryProbe);
        return node;
    }
}
