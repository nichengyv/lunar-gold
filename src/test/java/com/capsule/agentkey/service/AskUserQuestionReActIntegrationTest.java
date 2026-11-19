package com.capsule.agentkey.service;

import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.eval.ScriptedAgentClient;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.tool.AskUserQuestionSchemaTool;
import com.capsule.agentkey.tool.AskUserQuestionTool;
import com.capsule.agentkey.tool.BaseTool;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.capsule.agentkey.verify.VerifyService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P7-06：ask_user_question 在真实 ReAct 循环里的端到端行为。
 *
 * <p>用 {@link ScriptedAgentClient} 替代真实模型，驱动完整的
 * 「模型发起工具调用 → ReAct 拦截 → 挂起等待 → 用户回答 → 答案进入上下文 → 循环继续」链路，
 * 不依赖外部 API Key。</p>
 */
class AskUserQuestionReActIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;
    private static final long RUN_ID = 77L;

    private static final String ASK_ARGS = """
            {"questions":[{
              "id":"scope",
              "header":"改动范围",
              "question":"改接口还是加 wrapper？",
              "options":[
                {"label":"改接口 (Recommended)","description":"同步更新所有调用方"},
                {"label":"加 wrapper","description":"不侵入现有接口"}
              ],
              "multi_select":false
            }]}""";

    @AfterEach
    void clearContext() {
        WorkspaceContext.clear();
    }

    @Test
    void 模型发起询问_用户回答后循环继续_答案进入工具结果() throws Exception {
        Harness harness = new Harness(List.of(
                ScriptedAgentClient.ScriptedTurn.ofTools(List.of(
                        new ScriptedAgentClient.ToolCallSpec("ask_user_question", ASK_ARGS))),
                ScriptedAgentClient.ScriptedTurn.ofText("按改接口方案继续。")));

        AtomicReference<String> capturedQuestion = new AtomicReference<>();
        AtomicReference<String> toolResultContent = new AtomicReference<>();
        AtomicReference<String> finalText = new AtomicReference<>("");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ReActAgentService.StreamCallback callback = new RecordingCallback() {
            @Override public void onUserQuestion(String questionId, String question, String header,
                                                 List<Map<String, Object>> options, boolean multiSelect) {
                capturedQuestion.set(question);
                assertEquals("scope", questionId);
                assertEquals("改动范围", header);
                assertEquals(2, options.size());
                assertFalse(multiSelect);
                // 模拟用户在前端选项卡上点了「改接口 (Recommended)」
                boolean resolved = harness.askTool.resolve(RUN_ID,
                        new AskUserQuestionTool.UserAnswer(questionId, List.of("改接口 (Recommended)"), null));
                assertTrue(resolved, "挂起中的问题必须能被 resolve");
            }

            @Override public void onToolResult(String toolName, ToolResult result) {
                if ("ask_user_question".equals(toolName)) {
                    assertTrue(result.isSuccess(), "用户已回答，工具结果应成功: " + result.getContent());
                    toolResultContent.set(result.getContent());
                }
            }

            @Override public void onChunk(String chunk) {
                finalText.set(finalText.get() + chunk);
            }
        };

        ReActAgentService.Completion completion = harness.execute(cancelled, callback);

        assertNotNull(capturedQuestion.get(), "模型调用 ask_user_question 必须触发 onUserQuestion");
        assertEquals("改接口还是加 wrapper？", capturedQuestion.get());
        assertNotNull(toolResultContent.get());
        assertTrue(toolResultContent.get().contains("\"answers\""), toolResultContent.get());
        assertTrue(toolResultContent.get().contains("改接口 (Recommended)"), toolResultContent.get());
        assertTrue(toolResultContent.get().contains("\"id\": \"scope\""), toolResultContent.get());
        assertEquals("按改接口方案继续。", finalText.get(), "回答后循环应继续到第二轮文本输出");
        assertEquals(ReActAgentService.CompletionStatus.COMPLETED, completion.status());
        assertNull(WorkspaceContext.get(), "execute 结束后必须清理 ThreadLocal");
    }

    @Test
    void 询问与只读工具同轮_不进入并行批_先问后读() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        BaseTool readTool = new BaseTool() {
            @Override public String getName() { return "read_a"; }
            @Override public String getDescription() { return "read_a"; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of("type", "object"); }
            @Override public boolean readOnly() { return true; }
            @Override public ToolResult execute(JsonNode arguments) {
                order.add("read_a");
                return ToolResult.ok("A");
            }
        };
        Harness harness = new Harness(List.of(
                ScriptedAgentClient.ScriptedTurn.ofTools(List.of(
                        new ScriptedAgentClient.ToolCallSpec("ask_user_question", ASK_ARGS),
                        new ScriptedAgentClient.ToolCallSpec("read_a", "{}"))),
                ScriptedAgentClient.ScriptedTurn.ofText("完成。")), readTool);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        ReActAgentService.StreamCallback callback = new RecordingCallback() {
            @Override public void onUserQuestion(String questionId, String question, String header,
                                                 List<Map<String, Object>> options, boolean multiSelect) {
                order.add("question");
                harness.askTool.resolve(RUN_ID,
                        new AskUserQuestionTool.UserAnswer(questionId, List.of("加 wrapper"), null));
            }
        };

        ReActAgentService.Completion completion = harness.execute(cancelled, callback);

        assertEquals(List.of("question", "read_a"), order,
                "ask_user_question 不得进入只读并行批——后续只读工具必须等用户回答后才执行");
        assertEquals(ReActAgentService.CompletionStatus.COMPLETED, completion.status());
    }

    @Test
    void 等待期间用户停止_分片轮询及时退出() throws Exception {
        Harness harness = new Harness(List.of(
                ScriptedAgentClient.ScriptedTurn.ofTools(List.of(
                        new ScriptedAgentClient.ToolCallSpec("ask_user_question", ASK_ARGS))),
                ScriptedAgentClient.ScriptedTurn.ofText("不应走到这里。")));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<ToolResult> askResult = new AtomicReference<>();
        ReActAgentService.StreamCallback callback = new RecordingCallback() {
            @Override public void onUserQuestion(String questionId, String question, String header,
                                                 List<Map<String, Object>> options, boolean multiSelect) {
                // 用户不回答，直接点「停止」
                cancelled.set(true);
            }

            @Override public void onToolResult(String toolName, ToolResult result) {
                if ("ask_user_question".equals(toolName)) {
                    askResult.set(result);
                }
            }
        };

        long started = System.nanoTime();
        ReActAgentService.Completion completion = harness.execute(cancelled, callback);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertNotNull(askResult.get());
        assertFalse(askResult.get().isSuccess(), "取消后询问应以失败结果返回给模型");
        assertTrue(elapsedMs < 5_000, "停止后应在分片轮询内快速退出，实际耗时 " + elapsedMs + "ms");
        assertEquals(ReActAgentService.CompletionStatus.INCOMPLETE, completion.status());
        assertEquals("CANCELLED", completion.reason());
        assertNull(WorkspaceContext.get());
    }

    @Test
    void 无Run上下文时不向模型暴露该工具() {
        Harness harness = new Harness(List.of());
        try {
            WorkspaceContext.clear();
            List<String> withoutRun = harness.registry.buildRankedToolCallbacks("询问测试",
                            AgentPolicy.Permission.AUTO).stream()
                    .map(cb -> cb.getToolDefinition().name()).toList();
            assertFalse(withoutRun.contains("ask_user_question"),
                    "非流式/无 Run 上下文时暴露该工具会让模型发起永远无法回答的询问");

            WorkspaceContext.set(USER_ID, PROJECT_ID);
            WorkspaceContext.get().setRunId(RUN_ID);
            List<String> withRun = harness.registry.buildRankedToolCallbacks("询问测试",
                            AgentPolicy.Permission.AUTO).stream()
                    .map(cb -> cb.getToolDefinition().name()).toList();
            assertTrue(withRun.contains("ask_user_question"), "有 Run 上下文的流式执行必须暴露该工具");
        } finally {
            WorkspaceContext.clear();
        }
    }

    @Test
    void 工具schema的required必须是数组_否则真实provider直接400() throws Exception {
        // 回归：options.items.required 曾被误写成 {"type":"array","const":[...]}，
        // 脚本模型不会校验 schema，只有真实 DeepSeek 调用才暴露
        JsonNode schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                new AskUserQuestionSchemaTool().toToolDefinition().inputSchema());
        assertTrue(schema.path("required").isArray(), schema.toPrettyString());
        JsonNode questionItems = schema.at("/properties/questions/items");
        assertTrue(questionItems.path("required").isArray(), schema.toPrettyString());
        JsonNode optionRequired = questionItems.at("/properties/options/items/required");
        assertTrue(optionRequired.isArray(), schema.toPrettyString());
        assertEquals("label", optionRequired.get(0).asText());
    }

    // ========== 测试驱动 ==========

    /** 与 ReadOnlyToolBatchTest 相同的真实 ReActAgentService 装配，额外注入 AskUserQuestionTool */
    private static final class Harness {
        final AskUserQuestionTool askTool = new AskUserQuestionTool();
        final ToolRegistry registry;
        private final ReActAgentService agent;

        Harness(List<ScriptedAgentClient.ScriptedTurn> turns, BaseTool... extraTools) {
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
            registry = new ToolRegistry(mock(ApplicationContext.class), new SimpleMeterRegistry(), capabilities);
            registry.register(new AskUserQuestionSchemaTool());
            for (BaseTool tool : extraTools) {
                registry.register(tool);
            }

            ScriptedAgentClient client = new ScriptedAgentClient(turns);
            agent = new ReActAgentService(client, registry, mock(DocumentService.class),
                    projectService, mock(WorkspaceFileService.class), mock(McpToolBridge.class), ruleRepo,
                    new ToolApprovalRegistry(), new ScopeConstraintService(), new SessionPermissionTracker(),
                    new PromptCatalog(new MockEnvironment()),
                    new com.capsule.agentkey.diagnostics.DiagnosticsService(
                            new AgentDiagnosticsProperties(), List.of(new JavacDiagnosticsProvider())),
                    new VerifyService(new AgentVerifyProperties(), mock(CommandService.class)),
                    new RepoMapService(new AgentRepoMapProperties(),
                            new com.capsule.agentkey.service.index.CodeIndexService(
                                    new com.capsule.agentkey.service.index.LightweightCodeIndexer())));
            ReflectionTestUtils.setField(agent, "askUserQuestionTool", askTool);
        }

        ReActAgentService.Completion execute(AtomicBoolean cancelled, ReActAgentService.StreamCallback callback)
                throws Exception {
            AtomicReference<ReActAgentService.Completion> completion = new AtomicReference<>();
            ReActAgentService.StreamCallback wrapping = new ReActAgentService.StreamCallback() {
                @Override public void onThinking(String thought) { callback.onThinking(thought); }
                @Override public void onToolCall(String toolName, String arguments) {
                    callback.onToolCall(toolName, arguments);
                }
                @Override public void onToolResult(String toolName, ToolResult result) {
                    callback.onToolResult(toolName, result);
                }
                @Override public void onUserQuestion(String questionId, String question, String header,
                                                     List<Map<String, Object>> options, boolean multiSelect) {
                    callback.onUserQuestion(questionId, question, header, options, multiSelect);
                }
                @Override public void onChunk(String chunk) { callback.onChunk(chunk); }
                @Override public void onCompletion(ReActAgentService.Completion value) {
                    completion.set(value);
                    callback.onCompletion(value);
                }
                @Override public void onComplete() { callback.onComplete(); }
                @Override public void onError(Throwable t) { callback.onError(t); }
            };
            try {
                agent.execute("test-key",
                        new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                        "需要用户决策的测试", List.of(), USER_ID, PROJECT_ID, RUN_ID, SESSION_ID,
                        "turn-ask", false, "", "", "", "", cancelled, wrapping);
            } finally {
                agent.shutdownReadOnlyToolPool();
            }
            return completion.get();
        }
    }

    /** 默认空实现，按需覆写；onError 直接抛出避免静默吞掉真实故障 */
    private abstract static class RecordingCallback implements ReActAgentService.StreamCallback {
        @Override public void onThinking(String thought) {}
        @Override public void onToolCall(String toolName, String arguments) {}
        @Override public void onToolResult(String toolName, ToolResult result) {}
        @Override public void onChunk(String chunk) {}
        @Override public void onComplete() {}
        @Override public void onError(Throwable t) {
            throw new IllegalStateException("ReAct 循环不应报错", t);
        }
    }
}
