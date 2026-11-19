package com.capsule.agentkey.service;

import com.capsule.agentkey.client.ProviderCircuitBreaker;
import com.capsule.agentkey.client.SpringAiAgentClient;
import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.config.SpringAiChatModelConfig;
import com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.tool.AskUserQuestionSchemaTool;
import com.capsule.agentkey.tool.AskUserQuestionTool;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.verify.VerifyService;
import com.capsule.agentkey.tool.WorkspaceContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P7-06 真实模型冒烟（默认跳过，不消耗费用）：
 * 设置环境变量 LUNARGOLD_LIVE_API_KEY 后，用真实 DeepSeek 账号验证
 * 「模型真的发起 ask_user_question → 用户回答 → 循环继续输出」。
 *
 * <p>模型默认 deepseek-flash，可用 LUNARGOLD_LIVE_MODEL 覆盖。</p>
 *
 * <pre>
 *   $env:LUNARGOLD_LIVE_API_KEY="sk-..."
 *   mvn -o test "-Dtest=AskUserQuestionLiveSmokeTest"
 * </pre>
 */
class AskUserQuestionLiveSmokeTest {

    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;
    private static final long RUN_ID = 88L;

    @AfterEach
    void clearContext() {
        WorkspaceContext.clear();
    }

    @Test
    void 真实模型发起询问并在用户回答后继续() throws Exception {
        String apiKey = System.getenv("LUNARGOLD_LIVE_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未设置 LUNARGOLD_LIVE_API_KEY，跳过真实模型冒烟（避免默认产生费用）");
        String model = System.getenv().getOrDefault("LUNARGOLD_LIVE_MODEL", "deepseek-flash");
        String baseUrl = System.getenv().getOrDefault("LUNARGOLD_LIVE_BASE_URL", "https://api.deepseek.com");

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
        registry.register(new AskUserQuestionSchemaTool());

        SpringAiAgentClient client = new SpringAiAgentClient(
                new SpringAiChatModelConfig(baseUrl, "/v1/chat/completions",
                        "", 1, 0L, 60L, 60L, ObservationRegistry.NOOP),
                new ProviderCircuitBreaker(5, 30_000L));
        ReActAgentService agent = new ReActAgentService(client, registry, mock(DocumentService.class),
                projectService, mock(WorkspaceFileService.class), mock(McpToolBridge.class), ruleRepo,
                new ToolApprovalRegistry(), new ScopeConstraintService(), new SessionPermissionTracker(),
                new PromptCatalog(new MockEnvironment()),
                new com.capsule.agentkey.diagnostics.DiagnosticsService(
                        new AgentDiagnosticsProperties(), List.of(new JavacDiagnosticsProvider())),
                new VerifyService(new AgentVerifyProperties(), mock(CommandService.class)),
                new RepoMapService(new AgentRepoMapProperties(),
                        new com.capsule.agentkey.service.index.CodeIndexService(
                                new com.capsule.agentkey.service.index.LightweightCodeIndexer())));
        AskUserQuestionTool askTool = new AskUserQuestionTool();
        ReflectionTestUtils.setField(agent, "askUserQuestionTool", askTool);

        AtomicBoolean asked = new AtomicBoolean(false);
        AtomicReference<String> finalText = new AtomicReference<>("");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ReActAgentService.Completion> completion = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        ReActAgentService.StreamCallback callback = new ReActAgentService.StreamCallback() {
            @Override public void onThinking(String thought) {}
            @Override public void onToolCall(String toolName, String arguments) {}
            @Override public void onToolResult(String toolName, ToolResult result) {}
            @Override public void onUserQuestion(String questionId, String question, String header,
                                                 List<Map<String, Object>> options, boolean multiSelect) {
                asked.set(true);
                askTool.resolve(RUN_ID, new AskUserQuestionTool.UserAnswer(
                        questionId, List.of(), "加 wrapper"));
            }
            @Override public void onChunk(String chunk) {
                finalText.set(finalText.get() + chunk);
            }
            @Override public void onCompletion(ReActAgentService.Completion value) {
                completion.set(value);
            }
            @Override public void onComplete() {}
            @Override public void onError(Throwable t) {
                failure.set(t);
            }
        };

        try {
            agent.execute(apiKey,
                    new AgentPolicy(model, AgentPolicy.Reasoning.STANDARD, AgentPolicy.Permission.AUTO),
                    "请调用 ask_user_question 工具问我一个问题：question 填「改接口还是加 wrapper？」，"
                            + "id 填 scope，options 给「改接口」和「加 wrapper」两项。"
                            + "拿到我的回答后，用一句话复述我的选择。",
                    List.of(), USER_ID, PROJECT_ID, RUN_ID, SESSION_ID, "live-smoke",
                    false, "", "", "", "", cancelled, callback);
        } finally {
            agent.shutdownReadOnlyToolPool();
        }

        assertNull(failure.get(), "真实模型调用不应报错: " + failure.get());
        assertTrue(asked.get(), "真实模型未发起 ask_user_question——检查 schema 是否暴露/提示词是否生效");
        assertNotNull(completion.get());
        assertFalse(finalText.get().isBlank(), "用户回答后模型应继续输出文本");
        System.out.println("[LIVE SMOKE] model=" + model + " completion=" + completion.get()
                + " finalText=" + finalText.get());
    }
}
