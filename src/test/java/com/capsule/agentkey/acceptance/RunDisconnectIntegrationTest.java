package com.capsule.agentkey.acceptance;

import com.capsule.agentkey.controller.support.ChatSseChannel;
import com.capsule.agentkey.controller.support.ChatStreamBridge;
import com.capsule.agentkey.diagnostics.DiagnosticsService;
import com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.eval.ScriptedAgentClient;
import com.capsule.agentkey.eval.ScriptedAgentClient.ScriptedTurn;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.CapabilityService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.DocumentService;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.PromptCatalog;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.service.RepoMapService;
import com.capsule.agentkey.service.ScopeConstraintService;
import com.capsule.agentkey.service.SessionMemoryExtractionService;
import com.capsule.agentkey.service.SessionPermissionTracker;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.capsule.agentkey.service.WorkspaceFileService;
import com.capsule.agentkey.service.index.CodeIndexService;
import com.capsule.agentkey.service.index.LightweightCodeIndexer;
import com.capsule.agentkey.tool.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6-06：SSE 断流后，真实 ReAct 循环仍应执行完成并落库，而不是按取消处理。
 */
class RunDisconnectIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;
    private static final long RUN_ID = 99L;

    @TempDir
    Path workspace;

    @Test
    void SSE断流后Run继续执行并保存完整回答() {
        ChatSseChannel channel = ChatSseChannel.open(USER_ID);
        channel.detached().set(true);

        ChatService chatService = mock(ChatService.class);
        SessionMemoryExtractionService memoryService = mock(SessionMemoryExtractionService.class);
        AgentRunService runService = mock(AgentRunService.class);
        AgentPolicy policy = new AgentPolicy("scripted",
                AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO);

        ChatStreamBridge bridge = new ChatStreamBridge(channel, RUN_ID, "turn-1", SESSION_ID, USER_ID,
                policy, "test-key", chatService, memoryService, runService, MAPPER, 100_000L, 60L);
        bridge.beginTurn();

        ReActAgentService agent = agent(new ScriptedAgentClient(
                List.of(ScriptedTurn.ofText("断流后继续生成的回答"))));
        agent.execute("test-key", policy, "写点东西", List.of(), USER_ID, PROJECT_ID, SESSION_ID,
                "turn-1", false, "", "", "", "", channel.cancelFlag(), bridge);

        assertFalse(channel.isCancelRequested(), "断流不能把 Run 置为取消");
        verify(chatService).saveMessage(eq(USER_ID), eq(SESSION_ID), eq(ChatMessage.MessageRole.ASSISTANT),
                eq("断流后继续生成的回答"), isNull(), anyString());
        verify(runService).appendEvent(eq(RUN_ID), eq(AgentRunEvent.EventType.COMPLETION), anyMap());
        verify(runService).succeedIfActive(eq(RUN_ID), contains("\"clientDetached\":true"));
        verify(runService, never()).cancelIfActive(anyLong(), anyString());
    }

    private ReActAgentService agent(ScriptedAgentClient client) {
        WorkspaceFileService fileService = workspaceFileService();
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setUserId(USER_ID);
        project.setWorkspacePath(workspace.toString());

        ProjectService projectService = mock(ProjectService.class);
        when(projectService.resolveProjectId(USER_ID, PROJECT_ID)).thenReturn(PROJECT_ID);
        when(projectService.getOwned(USER_ID, PROJECT_ID)).thenReturn(project);

        AgentRuleRepository ruleRepo = mock(AgentRuleRepository.class);
        when(ruleRepo.findEffectiveRules(anyLong(), anyLong(), anyLong())).thenReturn(List.of());
        CapabilityService capabilities = mock(CapabilityService.class);
        when(capabilities.has(any())).thenReturn(true);

        ToolRegistry tools = new ToolRegistry(mock(ApplicationContext.class),
                new SimpleMeterRegistry(), capabilities);
        ToolApprovalRegistry approvals = new ToolApprovalRegistry();
        DiagnosticsService diagnostics = new DiagnosticsService(
                new com.capsule.agentkey.config.AgentDiagnosticsProperties(),
                List.of(new JavacDiagnosticsProvider()));

        ReActAgentService agent = new ReActAgentService(client, tools, mock(DocumentService.class),
                projectService, fileService, mock(McpToolBridge.class), ruleRepo, approvals,
                new ScopeConstraintService(), new SessionPermissionTracker(),
                new PromptCatalog(new MockEnvironment()), diagnostics,
                new com.capsule.agentkey.verify.VerifyService(
                        new com.capsule.agentkey.config.AgentVerifyProperties(),
                        mock(com.capsule.agentkey.service.CommandService.class)),
                new RepoMapService(new com.capsule.agentkey.config.AgentRepoMapProperties(),
                        new CodeIndexService(new LightweightCodeIndexer())));
        ReflectionTestUtils.setField(agent, "maxRepeatedToolCalls", 3);
        ReflectionTestUtils.setField(agent, "maxStagnantRounds", 4);
        return agent;
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
