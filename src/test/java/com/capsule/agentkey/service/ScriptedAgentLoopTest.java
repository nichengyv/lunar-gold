package com.capsule.agentkey.service;

import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunEventStore;
import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.eval.ScriptedAgentClient;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.tool.BaseTool;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.UpdatePlanTool;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.capsule.agentkey.verify.VerifyService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R0：脚本化模型响应驱动的 ReAct 主循环回归。
 *
 * <p>重构前只有纯逻辑单测和「工具调用协议」测试，主循环的收尾（计划的 aborted 标记、
 * 无进展安全停止、取消、线程上下文清理）没有端到端断言，任何重构都可能悄悄改掉行为。
 * 这里用 {@link ScriptedAgentClient} 回放固定响应，把主循环当成黑盒验证：
 * 输入脚本 → 输出回调序列 → 可观察终态。
 */
class ScriptedAgentLoopTest {

    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;

    // ------------------------------------------------------------------
    // 计划：update_plan 写入 RunContext，收尾时未完成步骤标 aborted
    // ------------------------------------------------------------------

    @Test
    void update_plan写入运行上下文并在收尾时把未完成步骤标aborted() {
        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofTools(List.of(
                        new ScriptedAgentClient.ToolCallSpec("update_plan",
                                "{\"steps\":["
                                        + "{\"title\":\"读取项目\",\"status\":\"completed\"},"
                                        + "{\"title\":\"修改接口\",\"status\":\"in_progress\"}]}"))),
                ScriptedAgentClient.ScriptedTurn.ofText("计划已更新")));
        Capture capture = new Capture();

        run(client, capture, new UpdatePlanTool());

        assertFalse(capture.plans.isEmpty(), "update_plan 之后必须推送计划");
        List<PlanStep> first = capture.plans.get(0);
        assertEquals(2, first.size());
        assertEquals("读取项目", first.get(0).title());
        assertEquals("in_progress", first.get(1).status());

        List<PlanStep> last = capture.plans.get(capture.plans.size() - 1);
        assertEquals("completed", last.get(0).status(), "已完成步骤不能被收尾改写");
        assertEquals("aborted", last.get(1).status(), "模型未完成且已停止的步骤必须标记 aborted");

        assertNotNull(capture.completion, "收尾必须给出交付状态");
        assertEquals(ReActAgentService.CompletionStatus.INCOMPLETE, capture.completion.status());
        assertEquals("PLAN_INCOMPLETE", capture.completion.reason());
    }

    // ------------------------------------------------------------------
    // 无进展：连续相同工具调用先 nudge，超过自救窗口后停止（P7-08）
    // ------------------------------------------------------------------

    @Test
    void 连续相同工具调用先nudge_超过自救窗口后安全停止() {
        BaseTool readTool = tool("read_file", ToolResult.ok("same content"));
        List<ScriptedAgentClient.ScriptedTurn> turns = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            turns.add(ScriptedAgentClient.ScriptedTurn.ofTools(List.of(
                    new ScriptedAgentClient.ToolCallSpec("read_file", "{\"path\":\"a.txt\"}"))));
        }
        ScriptedAgentClient client = new ScriptedAgentClient(turns);
        Capture capture = new Capture();

        run(client, capture, readTool);

        assertNotNull(capture.noProgress, "超过一轮自救窗口后必须触发无进展停止");
        assertEquals("REPEATED_TOOL_CALL", capture.noProgress.reason());
        assertTrue(client.messageSnapshots().stream()
                        .flatMap(List::stream)
                        .anyMatch(m -> "user".equals(m.path("role").asText())
                                && m.path("content").asText().contains("【重要】")),
                "停止前必须先向模型注入 CRITICAL nudge");
        assertNotNull(capture.completion);
        assertEquals(ReActAgentService.CompletionStatus.INCOMPLETE, capture.completion.status());
    }

    // ------------------------------------------------------------------
    // 取消与清理：显式停止后不再调用模型，ThreadLocal 必须清空
    // ------------------------------------------------------------------

    @Test
    void 用户停止标记置位时直接收尾且清理线程上下文() {
        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofText("不应被调用")));
        Capture capture = new Capture();
        AtomicBoolean cancelled = new AtomicBoolean(true);

        run(client, capture, cancelled);

        assertEquals(0, client.modelCalls(), "取消后不应再请求模型");
        assertNotNull(capture.completion);
        assertEquals("CANCELLED", capture.completion.reason());
        assertNull(WorkspaceContext.get(), "主循环结束后必须清理工具线程上下文");
    }

    // ------------------------------------------------------------------
    // 续跑：从 Run 的持久化快照恢复计划，并注入模型上下文
    // ------------------------------------------------------------------

    @Test
    void 续跑时从持久化快照恢复计划并以user消息注入上下文() {
        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofText("继续执行")));
        Capture capture = new Capture();
        ReActAgentService agent = newAgent(client, capture, List.of());

        AgentRunService runService = mock(AgentRunService.class);
        when(runService.loadPlan(77L)).thenReturn(List.of(
                new PlanStep("读取项目", "completed"),
                new PlanStep("修改接口", "in_progress")));
        RunEventStore eventStore = mock(RunEventStore.class);
        ReflectionTestUtils.setField(agent, "agentRunService", runService);
        ReflectionTestUtils.setField(agent, "runEventStore", eventStore);

        try {
            agent.execute("test-key",
                    new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                    "继续", List.of(), USER_ID, PROJECT_ID, 77L, SESSION_ID,
                    "turn-resume", false, "", "", "", "", new AtomicBoolean(false), capture);
        } finally {
            agent.shutdownReadOnlyToolPool();
        }

        assertFalse(client.messageSnapshots().isEmpty());
        boolean injected = client.messageSnapshots().get(0).stream()
                .anyMatch(m -> "user".equals(m.path("role").asText())
                        && m.path("content").asText().contains("本轮任务计划")
                        && m.path("content").asText().contains("修改接口"));
        assertTrue(injected, "持久化计划必须作为 user 消息进入模型上下文");

        verify(eventStore).savePlan(eq(77L), any());
        assertNotNull(capture.completion);
        assertEquals("PLAN_INCOMPLETE", capture.completion.reason());
    }

    @Test
    void 开启软注入后每轮从持久化快照重读计划() {
        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofTools(List.of(
                        new ScriptedAgentClient.ToolCallSpec("update_plan",
                                "{\"steps\":[{\"title\":\"读取项目\",\"status\":\"in_progress\"}]}"))),
                ScriptedAgentClient.ScriptedTurn.ofText("完成")));
        Capture capture = new Capture();
        ReActAgentService agent = newAgent(client, capture, List.of(new UpdatePlanTool()));

        AgentRunService runService = mock(AgentRunService.class);
        when(runService.loadPlan(88L)).thenReturn(
                List.of(new PlanStep("读取项目", "in_progress")),
                List.of(new PlanStep("读取项目", "completed"),
                        new PlanStep("修改接口", "in_progress")));
        ReflectionTestUtils.setField(agent, "agentRunService", runService);
        ReflectionTestUtils.setField(agent, "runEventStore", mock(RunEventStore.class));
        // R5：默认关闭；本用例显式打开，验证“每轮重读 + 软注入”确实生效
        ReflectionTestUtils.setField(agent, "planInjectionEnabled", true);

        try {
            agent.execute("test-key",
                    new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                    "执行脚本", List.of(), USER_ID, PROJECT_ID, 88L, SESSION_ID,
                    "turn-inject", false, "", "", "", "", new AtomicBoolean(false), capture);
        } finally {
            agent.shutdownReadOnlyToolPool();
        }

        List<List<com.fasterxml.jackson.databind.node.ObjectNode>> snapshots = client.messageSnapshots();
        assertTrue(snapshots.size() >= 2, "至少两轮模型调用");
        long firstPlanCount = snapshots.get(0).stream()
                .filter(m -> m.path("content").asText().contains("Current plan")).count();
        assertEquals(1, firstPlanCount, "每轮只注入一条计划，不能与初始快照重复");
        assertTrue(snapshots.get(0).stream()
                .anyMatch(m -> m.path("content").asText().contains("[in_progress]")));
        assertTrue(snapshots.get(1).stream()
                        .anyMatch(m -> m.path("content").asText().contains("[completed]")
                                && m.path("content").asText().contains("修改接口")),
                "第二轮必须读到 update_plan 之后的新快照");
        verify(runService, org.mockito.Mockito.atLeast(2)).loadPlan(88L);
    }

    // ------------------------------------------------------------------
    // R7b：断点写入与续跑
    // ------------------------------------------------------------------

    @Test
    void 每轮工具批落地后写入可续跑断点() {
        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofTools(List.of(
                        new ScriptedAgentClient.ToolCallSpec("read_file", "{\"path\":\"a.txt\"}"))),
                ScriptedAgentClient.ScriptedTurn.ofText("完成")));
        Capture capture = new Capture();
        ReActAgentService agent = newAgent(client, capture,
                List.of(tool("read_file", ToolResult.ok("file content"))));

        AgentRunService runService = mock(AgentRunService.class);
        ReflectionTestUtils.setField(agent, "agentRunService", runService);
        List<com.capsule.agentkey.agent.run.RunCheckpoint> checkpoints = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            checkpoints.add(inv.getArgument(1));
            return null;
        }).when(runService).saveCheckpoint(eq(555L), any());

        try {
            agent.execute("test-key",
                    new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                    "修复登录", List.of(), USER_ID, PROJECT_ID, 555L, SESSION_ID,
                    "turn-checkpoint", false, "", "", "", "", new AtomicBoolean(false), capture);
        } finally {
            agent.shutdownReadOnlyToolPool();
        }

        assertEquals(1, checkpoints.size(), "一个完成的工具批对应一份断点");
        com.capsule.agentkey.agent.run.RunCheckpoint checkpoint = checkpoints.get(0);
        assertEquals(1, checkpoint.getRound());
        assertTrue(checkpoint.getMessages().stream()
                        .anyMatch(m -> "assistant".equals(m.path("role").asText()) && m.has("tool_calls")),
                "断点必须包含 assistant(tool_calls)");
        assertTrue(checkpoint.getMessages().stream()
                        .anyMatch(m -> "tool".equals(m.path("role").asText())),
                "断点必须包含工具结果，续跑才不会重放工具");
        assertTrue(checkpoint.getPromptTokens() > 0, "断点必须带上已累计 token");
    }

    @Test
    void 从断点续跑直接使用快照消息且不重建历史() {
        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofText("已继续")));
        Capture capture = new Capture();
        ReActAgentService agent = newAgent(client, capture, List.of());
        AgentRunService runService = mock(AgentRunService.class);
        ReflectionTestUtils.setField(agent, "agentRunService", runService);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode user =
                mapper.createObjectNode().put("role", "user").put("content", "原始任务：修复登录");
        com.fasterxml.jackson.databind.node.ObjectNode assistant =
                mapper.createObjectNode().put("role", "assistant").putNull("content");
        com.fasterxml.jackson.databind.node.ObjectNode toolMsg =
                mapper.createObjectNode().put("role", "tool").put("content", "file content");
        com.capsule.agentkey.agent.run.RunCheckpoint checkpoint =
                new com.capsule.agentkey.agent.run.RunCheckpoint();
        checkpoint.setRound(1);
        checkpoint.setPromptTokens(50);
        checkpoint.setCompletionTokens(10);
        checkpoint.setMessages(List.of(user, assistant, toolMsg));

        try {
            agent.resume("test-key",
                    new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                    556L, USER_ID, PROJECT_ID, SESSION_ID, "turn-resume-loop",
                    "原始任务：修复登录", checkpoint, new AtomicBoolean(false), capture);
        } finally {
            agent.shutdownReadOnlyToolPool();
        }

        assertEquals(1, client.modelCalls());
        List<com.fasterxml.jackson.databind.node.ObjectNode> sent = client.messageSnapshots().get(0);
        assertTrue(sent.stream().anyMatch(m -> "原始任务：修复登录".equals(m.path("content").asText())),
                "续跑必须直接使用断点里的用户消息");
        assertTrue(sent.stream().anyMatch(m -> "tool".equals(m.path("role").asText())),
                "续跑必须保留断点里的工具结果");
        assertEquals(3, sent.size(), "不得重新拼历史消息或重复注入上下文");
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private void run(ScriptedAgentClient client, Capture capture, BaseTool... tools) {
        run(client, capture, tools, new AtomicBoolean(false));
    }

    private void run(ScriptedAgentClient client, Capture capture, AtomicBoolean cancelled) {
        run(client, capture, new BaseTool[0], cancelled);
    }

    private void run(ScriptedAgentClient client, Capture capture, BaseTool[] tools, AtomicBoolean cancelled) {
        ReActAgentService agent = newAgent(client, capture, List.of(tools));
        try {
            agent.execute("test-key",
                    new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                    "执行脚本", List.of(), USER_ID, PROJECT_ID, SESSION_ID,
                    "turn-loop", false, "", "", "", "", cancelled, capture);
        } finally {
            agent.shutdownReadOnlyToolPool();
        }
    }

    private ReActAgentService newAgent(ScriptedAgentClient client, Capture capture, List<BaseTool> tools) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setUserId(USER_ID);
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.resolveProjectId(USER_ID, PROJECT_ID)).thenReturn(PROJECT_ID);
        when(projectService.getOwned(USER_ID, PROJECT_ID)).thenReturn(project);

        AgentRuleRepository ruleRepo = mock(AgentRuleRepository.class);
        when(ruleRepo.findEffectiveRules(USER_ID, PROJECT_ID, SESSION_ID)).thenReturn(List.of());
        CapabilityService capabilities = mock(CapabilityService.class);
        when(capabilities.has(any())).thenReturn(true);
        ToolRegistry registry = new ToolRegistry(mock(ApplicationContext.class),
                new SimpleMeterRegistry(), capabilities);
        tools.forEach(registry::register);

        return new ReActAgentService(client, registry, mock(DocumentService.class),
                projectService, mock(WorkspaceFileService.class), mock(com.capsule.agentkey.mcp.McpToolBridge.class),
                ruleRepo, new ToolApprovalRegistry(), new ScopeConstraintService(),
                new SessionPermissionTracker(), new PromptCatalog(new MockEnvironment()),
                mock(com.capsule.agentkey.diagnostics.DiagnosticsService.class),
                new VerifyService(new AgentVerifyProperties(), mock(CommandService.class)),
                new RepoMapService(new AgentRepoMapProperties(),
                        new com.capsule.agentkey.service.index.CodeIndexService(
                                new com.capsule.agentkey.service.index.LightweightCodeIndexer())));
    }

    private static BaseTool tool(String name, ToolResult result) {
        return new BaseTool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of("type", "object"); }
            @Override public ToolResult execute(JsonNode arguments) { return result; }
        };
    }

    /** 捕获主循环对外推送的终态，避免每个用例重复写匿名类 */
    private static final class Capture implements ReActAgentService.StreamCallback {
        final List<List<PlanStep>> plans = new ArrayList<>();
        ReActAgentService.Completion completion;
        ReActAgentService.NoProgress noProgress;

        @Override public void onThinking(String thought) {}
        @Override public void onToolCall(String toolName, String arguments) {}
        @Override public void onToolResult(String toolName, ToolResult result) {}
        @Override public void onChunk(String chunk) {}
        @Override public void onPlan(List<PlanStep> steps) { plans.add(List.copyOf(steps)); }
        @Override public void onCompletion(ReActAgentService.Completion completion) {
            this.completion = completion;
        }
        @Override public void onNoProgress(ReActAgentService.NoProgress noProgress) {
            this.noProgress = noProgress;
        }
        @Override public void onComplete() {}
        @Override public void onError(Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
