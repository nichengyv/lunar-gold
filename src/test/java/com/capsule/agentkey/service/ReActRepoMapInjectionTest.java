package com.capsule.agentkey.service;

import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.diagnostics.DiagnosticsService;
import com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.eval.ScriptedAgentClient;
import com.capsule.agentkey.eval.ScriptedAgentClient.ScriptedTurn;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.service.index.CodeIndexService;
import com.capsule.agentkey.service.index.LightweightCodeIndexer;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.verify.VerifyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P5-04：repo map 与终端工具说明的注入相互独立。
 *
 * <p>终端规则不能依赖 repo map 是否存在；否则关闭 repo map 的 A/B 会连带丢失终端说明，
 * 收益也无法单独归因。</p>
 */
class ReActRepoMapInjectionTest {

    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;

    @TempDir
    Path workspace;

    @Test
    void repoMapDisabledStillInjectsTerminalNotes() throws Exception {
        String systemPrompt = run(properties(false));

        assertTrue(systemPrompt.contains("【终端命令】"), systemPrompt);
        assertFalse(systemPrompt.contains("【仓库结构（repo map）】"), systemPrompt);
    }

    @Test
    void repoMapEnabledInjectsBothSections() throws Exception {
        String systemPrompt = run(properties(true));

        assertTrue(systemPrompt.contains("【仓库结构（repo map）】"), systemPrompt);
        assertTrue(systemPrompt.contains("【终端命令】"), systemPrompt);
    }

    @Test
    void readOnlyPermissionSkipsTerminalNotes() throws Exception {
        String systemPrompt = run(properties(true), AgentPolicy.Permission.READ_ONLY);

        assertTrue(systemPrompt.contains("【仓库结构（repo map）】"), systemPrompt);
        assertFalse(systemPrompt.contains("【终端命令】"), systemPrompt);
    }

    private String run(AgentRepoMapProperties repoMapProperties) throws Exception {
        return run(repoMapProperties, AgentPolicy.Permission.AUTO);
    }

    private String run(AgentRepoMapProperties repoMapProperties,
                       AgentPolicy.Permission permission) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/main/java/OrderService.java"),
                "package com.demo;\npublic class OrderService {\n    public void cancel() {}\n}\n");

        ScriptedAgentClient client = new ScriptedAgentClient(List.of(ScriptedTurn.ofText("done")));
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setUserId(USER_ID);
        project.setWorkspacePath(workspace.toString());

        ProjectService projectService = mock(ProjectService.class);
        when(projectService.resolveProjectId(USER_ID, PROJECT_ID)).thenReturn(PROJECT_ID);
        when(projectService.getOwned(USER_ID, PROJECT_ID)).thenReturn(project);

        AgentRuleRepository ruleRepo = mock(AgentRuleRepository.class);
        when(ruleRepo.findEffectiveRules(anyLong(), anyLong(), anyLong())).thenReturn(List.of());
        com.capsule.agentkey.service.CapabilityService capabilities =
                mock(com.capsule.agentkey.service.CapabilityService.class);
        when(capabilities.has(any())).thenReturn(true);

        ToolRegistry tools = new ToolRegistry(mock(ApplicationContext.class),
                new SimpleMeterRegistry(), capabilities);
        DiagnosticsService diagnostics = new DiagnosticsService(
                new AgentDiagnosticsProperties(), List.of(new JavacDiagnosticsProvider()));
        ReActAgentService agent = new ReActAgentService(client, tools, mock(DocumentService.class),
                projectService, workspaceFileService(), mock(McpToolBridge.class), ruleRepo,
                new ToolApprovalRegistry(), new ScopeConstraintService(),
                new SessionPermissionTracker(), new PromptCatalog(new MockEnvironment()), diagnostics,
                new VerifyService(new AgentVerifyProperties(), mock(CommandService.class)),
                new RepoMapService(repoMapProperties,
                        new CodeIndexService(new LightweightCodeIndexer())));
        ReflectionTestUtils.setField(agent, "maxRepeatedToolCalls", 3);
        ReflectionTestUtils.setField(agent, "maxStagnantRounds", 4);

        AgentPolicy policy = new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, permission);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        agent.execute("test-key", policy, "hello", List.of(), USER_ID, PROJECT_ID, SESSION_ID,
                "turn-repo-map", false, "", "", "", "", new AtomicBoolean(false),
                new ReActAgentService.StreamCallback() {
                    @Override public void onThinking(String thought) {}
                    @Override public void onChunk(String chunk) {}
                    @Override public void onToolCall(String toolName, String arguments) {}
                    @Override public void onToolResult(String toolName,
                                                       com.capsule.agentkey.tool.ToolResult result) {}
                    @Override public void onComplete() {}
                    @Override public void onError(Throwable error) { failure.set(error); }
                });

        if (failure.get() != null) {
            throw new AssertionError("ReAct 执行失败", failure.get());
        }
        assertFalse(client.messageSnapshots().isEmpty(), "缺少模型消息快照");
        return client.messageSnapshots().get(0).get(0).path("content").asText();
    }

    private static AgentRepoMapProperties properties(boolean enabled) {
        AgentRepoMapProperties properties = new AgentRepoMapProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private static WorkspaceFileService workspaceFileService() {
        WorkspaceFileService service = new WorkspaceFileService();
        ReflectionTestUtils.setField(service, "maxReadBytes", 102400);
        ReflectionTestUtils.setField(service, "maxWriteBytes", 524288);
        ReflectionTestUtils.setField(service, "maxSearchResults", 50);
        ReflectionTestUtils.setField(service, "maxListDepth", 3);
        return service;
    }
}
