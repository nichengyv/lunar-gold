package com.capsule.agentkey.service;

import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.eval.ScriptedAgentClient;
import com.capsule.agentkey.eval.ScriptedAgentClient.ScriptedTurn;
import com.capsule.agentkey.eval.ScriptedAgentClient.ToolCallSpec;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.tool.FileChange;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.WriteFileTool;
import com.capsule.agentkey.verify.VerifyResult;
import com.capsule.agentkey.verify.VerifyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5-06：真实 ReAct 循环中的项目验证闭环——失败回灌、修复通过、重试预算与状态区分。
 */
class ReActVerifyIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;

    @TempDir
    Path workspace;

    @Test
    void failureIsFedBackAndRepairCanPass() throws Exception {
        writeVerifyConfig();
        CommandService commandService = mock(CommandService.class);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(failure("Foo.java:12: cannot find symbol"), success());

        RunResult run = run(commandService, 4, List.of(
                ScriptedTurn.ofTools(List.of(new ToolCallSpec("write_file",
                        writeArgs("src/main/java/Ok.java", "public class Ok {}")))),
                ScriptedTurn.ofTools(List.of(new ToolCallSpec("write_file",
                        writeArgs("src/main/java/Ok.java", "public class Ok { } // fixed")))),
                ScriptedTurn.ofText("done")));

        assertEquals(ReActAgentService.CompletionStatus.COMPLETED, run.completion().status());
        assertEquals("VERIFY_PASSED", run.completion().reason());
        assertEquals(List.of(VerifyResult.Status.FAILED, VerifyResult.Status.PASSED),
                run.verifyEvents().stream().map(VerifyResult::status).toList());
        assertTrue(run.snapshots().get(1).stream()
                .anyMatch(m -> m.path("content").asText("").contains("【验证结果】")
                        && m.path("content").asText("").contains("cannot find symbol")));
        verify(commandService, times(2)).run(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void retryExhaustionStopsWithoutInfiniteLoop() throws Exception {
        writeVerifyConfig();
        CommandService commandService = mock(CommandService.class);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(failure("build still broken"));

        RunResult run = run(commandService, 10, List.of(
                writeTurn("public class Ok {} // 1"),
                writeTurn("public class Ok {} // 2"),
                writeTurn("public class Ok {} // 3"),
                ScriptedTurn.ofText("done")));

        assertEquals(ReActAgentService.CompletionStatus.INCOMPLETE, run.completion().status());
        assertEquals("VERIFY_RETRY_EXHAUSTED", run.completion().reason());
        verify(commandService, times(3)).run(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void notConfiguredStaysUnverifiedWithoutRunningCommands() throws Exception {
        CommandService commandService = mock(CommandService.class);

        RunResult run = run(commandService, 3, List.of(
                writeTurn("public class Ok {}"),
                ScriptedTurn.ofText("done")));

        assertEquals(ReActAgentService.CompletionStatus.UNVERIFIED, run.completion().status());
        assertEquals("VERIFY_NOT_CONFIGURED", run.completion().reason());
        assertEquals(List.of(VerifyResult.Status.NOT_CONFIGURED),
                run.verifyEvents().stream().map(VerifyResult::status).toList());
        verify(commandService, never()).run(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void unavailableEnvironmentIsDistinctFromCommandFailure() throws Exception {
        writeVerifyConfig();
        CommandService commandService = mock(CommandService.class);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new BusinessException("找不到可执行程序：mvn"));

        RunResult run = run(commandService, 3, List.of(
                writeTurn("public class Ok {}"),
                ScriptedTurn.ofText("done")));

        assertEquals(ReActAgentService.CompletionStatus.UNVERIFIED, run.completion().status());
        assertEquals("VERIFY_UNAVAILABLE", run.completion().reason());
        assertEquals(List.of(VerifyResult.Status.UNAVAILABLE),
                run.verifyEvents().stream().map(VerifyResult::status).toList());
    }

    @Test
    void repeatedIdenticalFailureStopsThroughNoProgress() throws Exception {
        writeVerifyConfig();
        CommandService commandService = mock(CommandService.class);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(failure("same failure"));

        RunResult run = run(commandService, 2, List.of(
                writeTurn("public class Ok {} // 1"),
                writeTurn("public class Ok {} // 2"),
                ScriptedTurn.ofText("done")));

        assertNotNull(run.noProgress());
        assertEquals("REPEATED_VERIFY_FAILURE", run.noProgress().reason());
        assertEquals(ReActAgentService.CompletionStatus.INCOMPLETE, run.completion().status());
    }

    private RunResult run(CommandService commandService, int maxRepeats, List<ScriptedTurn> turns) {
        try {
            ScriptedAgentClient client = new ScriptedAgentClient(turns);
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
            PendingFileChangeRepository pendingRepo = mock(PendingFileChangeRepository.class);
            when(pendingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            tools.register(new WriteFileTool(projectService, fileService, pendingRepo));

            AgentVerifyProperties properties = new AgentVerifyProperties();
            VerifyService verifyService = new VerifyService(properties, commandService);
            // 本测试只关注验证回路，关闭真实 javac 诊断以缩短耗时、避免与 P5-05 测试争抢编译资源
            com.capsule.agentkey.config.AgentDiagnosticsProperties diagnosticsProperties =
                    new com.capsule.agentkey.config.AgentDiagnosticsProperties();
            diagnosticsProperties.setProvider("none");
            ToolApprovalRegistry approvals = new ToolApprovalRegistry();
            ReActAgentService agent = new ReActAgentService(client, tools, mock(DocumentService.class),
                    projectService, fileService, mock(McpToolBridge.class), ruleRepo,
                    approvals, new ScopeConstraintService(),
                    new SessionPermissionTracker(), new PromptCatalog(new MockEnvironment()),
                    new com.capsule.agentkey.diagnostics.DiagnosticsService(
                            diagnosticsProperties,
                            List.of(new com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider())),
                    verifyService,
                    new RepoMapService(new com.capsule.agentkey.config.AgentRepoMapProperties(),
                            new com.capsule.agentkey.service.index.CodeIndexService(
                                    new com.capsule.agentkey.service.index.LightweightCodeIndexer())));
            ReflectionTestUtils.setField(agent, "maxRepeatedToolCalls", maxRepeats);
            ReflectionTestUtils.setField(agent, "maxStagnantRounds", 10);

            AgentPolicy policy = new AgentPolicy("scripted",
                    AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO);
            AtomicReference<ReActAgentService.Completion> completion = new AtomicReference<>();
            AtomicReference<ReActAgentService.NoProgress> noProgress = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<VerifyResult> verifyEvents = new ArrayList<>();
            List<FileChange> fileChanges = new ArrayList<>();
            AtomicBoolean cancelled = new AtomicBoolean(false);

            agent.execute("test-key", policy, "write it", List.of(), USER_ID, PROJECT_ID, SESSION_ID,
                    "turn-verify", false, "", "", "", "", cancelled,
                    new ReActAgentService.StreamCallback() {
                        @Override public void onThinking(String thought) {}
                        @Override public void onChunk(String chunk) {}
                        @Override public void onToolCall(String toolName, String arguments) {}
                        @Override public void onToolResult(String toolName, com.capsule.agentkey.tool.ToolResult result) {}
                        @Override public void onFileChange(FileChange change) { fileChanges.add(change); }
                        @Override public void onVerify(VerifyResult result) { verifyEvents.add(result); }
                        @Override public void onApprovalRequired(String approvalId, String tool,
                                com.capsule.agentkey.dto.ApprovalPreview preview,
                                String risk, long expiresAt) {
                            approvals.resolve(approvalId, USER_ID, "APPROVE", false, null);
                        }
                        @Override public void onNoProgress(ReActAgentService.NoProgress event) {
                            noProgress.set(event);
                        }
                        @Override public void onCompletion(ReActAgentService.Completion value) {
                            completion.set(value);
                        }
                        @Override public void onComplete() {}
                        @Override public void onError(Throwable t) { failure.set(t); }
                    });

            if (failure.get() != null) {
                throw new AssertionError("ReAct 执行失败", failure.get());
            }
            return new RunResult(completion.get(), noProgress.get(), verifyEvents,
                    client.messageSnapshots());
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("ReAct 执行失败", e);
        }
    }

    private void writeVerifyConfig() throws Exception {
        Path dir = workspace.resolve(".lunargold");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("verify.json"), """
                {"commands":[{"name":"compile","command":"mvn -q compile","required":true}]}
                """);
    }

    private static ScriptedTurn writeTurn(String content) {
        return ScriptedTurn.ofTools(List.of(new ToolCallSpec("write_file",
                writeArgs("src/main/java/Ok.java", content))));
    }

    private static String writeArgs(String path, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("path", path);
        node.put("content", content);
        return node.toString();
    }

    private static CommandService.CommandResult success() {
        return new CommandService.CommandResult(0, "BUILD SUCCESS", false, false, false, 15);
    }

    private static CommandService.CommandResult failure(String output) {
        return new CommandService.CommandResult(1, output, false, false, false, 15);
    }

    private static WorkspaceFileService workspaceFileService() {
        WorkspaceFileService service = new WorkspaceFileService();
        ReflectionTestUtils.setField(service, "maxReadBytes", 102400);
        ReflectionTestUtils.setField(service, "maxWriteBytes", 524288);
        ReflectionTestUtils.setField(service, "maxSearchResults", 50);
        ReflectionTestUtils.setField(service, "maxListDepth", 3);
        return service;
    }

    private record RunResult(ReActAgentService.Completion completion,
                             ReActAgentService.NoProgress noProgress,
                             List<VerifyResult> verifyEvents,
                             List<List<ObjectNode>> snapshots) {
    }
}
