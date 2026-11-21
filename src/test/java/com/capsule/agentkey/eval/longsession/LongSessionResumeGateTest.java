package com.capsule.agentkey.eval.longsession;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7b live 续跑门禁的离线验证：只校验阈值判定逻辑，不代表任何真实模型结果。
 * 真实完成率必须由 {@link LongSessionLiveRunner} 在设置 Key 后产出。
 */
class LongSessionResumeGateTest {

    @Test
    void 十对成功且开销可控时通过() {
        List<ObjectNode> direct = new ArrayList<>();
        List<ObjectNode> resume = new ArrayList<>();
        for (int i = 0; i < LongSessionResumeGate.MIN_CASES_PER_LEVEL; i++) {
            direct.add(run("L1", "case-" + i, true, 1000, 10_000));
            resume.add(resumeRun("L1", "case-" + i, true, 1000, 10_000));
        }

        ObjectNode gate = LongSessionResumeGate.evaluate(direct, resume);

        ObjectNode level = (ObjectNode) gate.path("byLevel").path("L1");
        assertEquals(10, level.path("cases").asInt());
        assertEquals(0, level.path("notApplicable").asInt());
        assertEquals(1.0, level.path("resumeSuccessRate").asDouble(), 0.0001);
        assertEquals(1.0, level.path("tokenRatio").asDouble(), 0.0001);
        assertTrue(level.path("pass").asBoolean(), gate.path("reasons").toString());
        // 根节点要求 L1/L2/L3 都达到最小样本；这里只验证单层判定
    }

    @Test
    void 续跑成功率下降超过十个百分点是失败() {
        List<ObjectNode> direct = new ArrayList<>();
        List<ObjectNode> resume = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            direct.add(run("L2", "case-" + i, true, 1000, 10_000));
            // 前 8 个成功、后 2 个失败：80% vs 100%，下降 20pp
            resume.add(resumeRun("L2", "case-" + i, i < 8, 1000, 10_000));
        }

        ObjectNode gate = LongSessionResumeGate.evaluate(direct, resume);

        ObjectNode level = (ObjectNode) gate.path("byLevel").path("L2");
        assertEquals(0.2, level.path("successRateDrop").asDouble(), 0.0001);
        assertFalse(level.path("pass").asBoolean());
        assertTrue(level.path("reasons").toString().contains("successRateDrop"));
    }

    @Test
    void 未注入中断的case不计入成功率且不足十个时失败() {
        List<ObjectNode> direct = new ArrayList<>();
        List<ObjectNode> resume = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            direct.add(run("L1", "case-" + i, true, 1000, 10_000));
            ObjectNode node = resumeRun("L1", "case-" + i, true, 1000, 10_000);
            if (i == 9) node.put("resumeInjected", false);
            resume.add(node);
        }

        ObjectNode gate = LongSessionResumeGate.evaluate(direct, resume);

        ObjectNode level = (ObjectNode) gate.path("byLevel").path("L1");
        assertEquals(9, level.path("cases").asInt());
        assertEquals(1, level.path("notApplicable").asInt());
        assertEquals(1.0, level.path("resumeSuccessRate").asDouble(), 0.0001);
        assertFalse(level.path("pass").asBoolean());
        assertTrue(level.path("reasons").toString().contains("cases=9"));
    }

    @Test
    void provider失败率与token膨胀各自触发失败() {
        List<ObjectNode> direct = new ArrayList<>();
        List<ObjectNode> resume = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            direct.add(run("L3", "case-" + i, true, 1000, 10_000));
            ObjectNode node = resumeRun("L3", "case-" + i, true, 1000, 20_000);
            if (i == 0) {
                node.put("success", false);
                node.put("failureAttribution", "MODEL_OR_PROVIDER");
            }
            resume.add(node);
        }

        ObjectNode gate = LongSessionResumeGate.evaluate(direct, resume);

        ObjectNode level = (ObjectNode) gate.path("byLevel").path("L3");
        assertEquals(0.1, level.path("providerFailureRate").asDouble(), 0.0001);
        assertTrue(level.path("tokenRatio").asDouble() > LongSessionResumeGate.MAX_TOKEN_OVERHEAD_RATIO);
        assertFalse(level.path("pass").asBoolean());
        String reasons = level.path("reasons").toString();
        assertTrue(reasons.contains("providerFailureRate"), reasons);
        assertTrue(reasons.contains("tokenRatio"), reasons);
    }

    @Test
    void 缺少直接执行配对时记为unmatched而不是通过() {
        ObjectNode gate = LongSessionResumeGate.evaluate(
                List.of(),
                List.of(resumeRun("L1", "only-resume", true, 1000, 10_000)));

        ObjectNode level = (ObjectNode) gate.path("byLevel").path("L1");
        assertEquals(1, level.path("attempts").asInt());
        assertEquals(0, level.path("cases").asInt());
        assertEquals(1, level.path("unmatched").asInt());
        assertFalse(gate.path("pass").asBoolean());
    }

    private static ObjectNode run(String level, String id, boolean success, long wallMs, long tokens) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", id);
        node.put("level", level);
        node.put("repeat", 1);
        node.put("success", success);
        node.put("failureAttribution", success ? "NONE" : "STEP_OMISSION");
        node.put("totalTokens", tokens);
        node.put("wallMs", wallMs);
        return node;
    }

    private static ObjectNode resumeRun(String level, String id, boolean success, long wallMs, long tokens) {
        ObjectNode node = run(level, id, success, wallMs, tokens);
        node.put("resumeInjected", true);
        node.put("resumeTurn", 1);
        node.put("crashRound", 1);
        return node;
    }
}
