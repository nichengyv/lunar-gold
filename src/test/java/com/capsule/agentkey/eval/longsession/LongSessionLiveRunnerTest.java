package com.capsule.agentkey.eval.longsession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7-02 报告与归因的离线测试；不构造模型客户端，不触发外部调用。 */
class LongSessionLiveRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void provider与上下文错误分开归因() throws IOException {
        LongSessionDataset.Spec spec = spec("L1");
        ObjectNode provider = baseMetrics();
        provider.putArray("errors").add("HTTP 429 rate limit");
        assertEquals("MODEL_OR_PROVIDER", LongSessionLiveRunner.attribution(spec, provider));

        ObjectNode context = baseMetrics();
        context.putArray("errors").add("maximum context length exceeded");
        assertEquals("CONTEXT_TRUNCATION", LongSessionLiveRunner.attribution(spec, context));
    }

    @Test
    void 无进展和最大轮次优先于工件缺失() throws IOException {
        LongSessionDataset.Spec spec = spec("L1");
        ObjectNode noProgress = baseMetrics();
        noProgress.put("noProgress", 1);
        noProgress.putArray("missingArtifacts").add("Policy01.java");
        assertEquals("NO_PROGRESS", LongSessionLiveRunner.attribution(spec, noProgress));

        ObjectNode maxRounds = baseMetrics();
        maxRounds.put("maxRounds", 1);
        assertEquals("PLAN_DRIFT", LongSessionLiveRunner.attribution(spec, maxRounds));
    }

    @Test
    void 重复调用审批scope与工具恢复有可复核归因() throws IOException {
        LongSessionDataset.Spec l1 = spec("L1");
        ObjectNode repetition = baseMetrics();
        repetition.put("repeatedCalls", 3);
        repetition.putArray("missingArtifacts").add("Policy01.java");
        assertEquals("HISTORY_REPETITION", LongSessionLiveRunner.attribution(l1, repetition));

        ObjectNode recovery = baseMetrics();
        recovery.put("toolFailures", 1);
        recovery.put("recoveredTools", 0);
        recovery.putArray("coverageFailures").add("recoveredTools=0, required=1");
        assertEquals("TOOL_RECOVERY", LongSessionLiveRunner.attribution(l1, recovery));

        LongSessionDataset.Spec l2 = spec("L2");
        ObjectNode scope = baseMetrics();
        scope.putArray("coverageFailures").add("scopeRejections=0, required=1");
        assertEquals("APPROVAL_OR_SCOPE", LongSessionLiveRunner.attribution(l2, scope));
    }

    @Test
    void 完整三次取样才允许触发架构结论() {
        List<LongSessionLiveRunner.RunOutcome> outcomes = new ArrayList<>();
        for (int repeat = 1; repeat <= 3; repeat++) {
            for (String level : List.of("L1", "L2", "L3")) {
                outcomes.add(outcome(level, repeat, true, "NONE"));
            }
        }
        ObjectNode report = LongSessionLiveRunner.buildReport("deepseek-flash", "https://api.deepseek.com",
                3, Set.of("L1", "L2", "L3"), false, 300, outcomes);
        assertTrue(report.path("decision").path("eligible").asBoolean());
        assertFalse(report.path("decision").path("rule2PlanFirst").asBoolean());
        assertFalse(report.path("decision").path("rule3ContextMemory").asBoolean());
        assertEquals(9, report.path("runs").size());
        assertFalse(report.has("apiKey"));
    }

    @Test
    void 计划类失败过半触发规则二但L1冒烟不触发() {
        List<LongSessionLiveRunner.RunOutcome> outcomes = new ArrayList<>();
        for (int repeat = 1; repeat <= 3; repeat++) {
            for (String level : List.of("L1", "L2", "L3")) {
                boolean failed = repeat <= 2 && !"L3".equals(level);
                outcomes.add(outcome(level, repeat, !failed, failed ? "NO_PROGRESS" : "NONE"));
            }
        }
        ObjectNode report = LongSessionLiveRunner.buildReport("deepseek-flash", "https://api.deepseek.com",
                3, Set.of("L1", "L2", "L3"), false, 300, outcomes);
        assertTrue(report.path("decision").path("eligible").asBoolean());
        assertTrue(report.path("decision").path("rule2PlanFirst").asBoolean());

        ObjectNode smoke = LongSessionLiveRunner.buildReport("deepseek-flash", "https://api.deepseek.com",
                1, Set.of("L1"), false, 300, List.of(outcome("L1", 1, false, "NO_PROGRESS")));
        assertFalse(smoke.path("decision").path("eligible").asBoolean());
        assertFalse(smoke.path("decision").path("rule2PlanFirst").asBoolean());
    }

    // ---------- P7-04 记忆摘要升级回归 ----------

    @Test
    void L3摘要必须包含记忆锚点() throws IOException {
        LongSessionDataset.Spec l3 = spec("L3");
        LongSessionLiveRunner.Metrics metrics = new LongSessionLiveRunner.Metrics();
        metrics.toolCalls = 156;
        metrics.fileChanges = 45;
        List<String> ranges = List.of("Unit 1-5", "Unit 6-10", "Unit 11-15", "Unit 16-20",
                "Unit 21-25", "Unit 26-30", "Unit 31-35", "Unit 36-40", "Unit 41-45");

        String summary = LongSessionLiveRunner.checkpointSummary(l3, metrics, 9, ranges);

        // 锚点必须出现（L3 数据集强制有）
        assertTrue(summary.contains("记忆锚点：P7-MEMORY-ANCHOR-7319"),
                "摘要必须包含 L3 锚点，实际：" + summary);
        // 已完成区间必须累积
        assertTrue(summary.contains("Unit 1-5"), "必须包含首段区间");
        assertTrue(summary.contains("Unit 41-45"), "必须包含末段区间");
        // 进度必须保留
        assertTrue(summary.contains("9/12 turns"), "必须保留 turn 数进度");
        assertTrue(summary.contains("工具调用 156 次"), "必须保留工具调用计数");
        // 约束必须保留
        assertTrue(summary.contains("不要重复已完成文件"), "必须保留防重复约束");
    }

    @Test
    void 非L3数据集无anchor时降级输出进度和区间() throws IOException {
        LongSessionDataset.Spec l1 = spec("L1");
        LongSessionLiveRunner.Metrics metrics = new LongSessionLiveRunner.Metrics();
        metrics.toolCalls = 20;
        metrics.fileChanges = 13;
        List<String> ranges = List.of("Rule 01-04", "Rule 05-08", "Rule 09-13");

        String summary = LongSessionLiveRunner.checkpointSummary(l1, metrics, 3, ranges);

        // 不应出现"记忆锚点"段落（L1 没有 memory 配置）
        assertFalse(summary.contains("记忆锚点："),
                "无 anchor 的数据集不应输出锚点段落，实际：" + summary);
        // 但进度 + 区间 + 约束仍应保留
        assertTrue(summary.contains("3/" + l1.turns().size() + " turns"));
        assertTrue(summary.contains("已完成文件区间：Rule 01-04"));
        assertTrue(summary.contains("不要重复已完成文件"));
    }

    @Test
    void 空区间时不输出区间段() throws IOException {
        LongSessionDataset.Spec l2 = spec("L2");
        LongSessionLiveRunner.Metrics metrics = new LongSessionLiveRunner.Metrics();
        metrics.toolCalls = 5;
        metrics.fileChanges = 0;

        String summary = LongSessionLiveRunner.checkpointSummary(l2, metrics, 1, new ArrayList<>());

        assertFalse(summary.contains("已完成文件区间："),
                "空区间不应输出区间段，实际：" + summary);
        assertTrue(summary.contains("进度：1/" + l2.turns().size()));
    }

    @Test
    void 摘要字符数在合理范围内() throws IOException {
        LongSessionDataset.Spec l3 = spec("L3");
        LongSessionLiveRunner.Metrics metrics = new LongSessionLiveRunner.Metrics();
        metrics.toolCalls = 156;
        metrics.fileChanges = 45;
        List<String> ranges = List.of("Unit 1-5", "Unit 6-10", "Unit 11-15", "Unit 16-20",
                "Unit 21-25", "Unit 26-30", "Unit 31-35", "Unit 36-40", "Unit 41-45");

        String summary = LongSessionLiveRunner.checkpointSummary(l3, metrics, 9, ranges);
        int len = summary.length();
        assertTrue(len >= 150, "摘要应至少 150 字符，实际 " + len);
        assertTrue(len <= 600, "摘要应不超过 600 字符（上限 500 + 少量换行），实际 " + len);
    }

    private static LongSessionDataset.Spec spec(String level) throws IOException {
        return LongSessionDataset.loadAll().stream().filter(s -> level.equals(s.level())).findFirst().orElseThrow();
    }

    private static ObjectNode baseMetrics() {
        ObjectNode data = MAPPER.createObjectNode();
        data.putArray("errors");
        data.put("noProgress", 0);
        data.put("maxRounds", 0);
        data.put("repeatedCalls", 0);
        data.put("toolFailures", 0);
        data.put("recoveredTools", 1);
        data.put("planCompletionRate", 1.0);
        data.putArray("missingArtifacts");
        data.putArray("coverageFailures");
        return data;
    }

    private static LongSessionLiveRunner.RunOutcome outcome(String level, int repeat,
                                                             boolean success, String attribution) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("id", level.toLowerCase() + "-case");
        data.put("level", level);
        data.put("repeat", repeat);
        data.put("modelCalls", 20);
        data.put("totalTokens", 100);
        data.put("wallMs", 1000);
        data.put("success", success);
        data.put("failureAttribution", attribution);
        return new LongSessionLiveRunner.RunOutcome(data, success, attribution);
    }
}
