package com.capsule.agentkey.service;

import com.capsule.agentkey.diagnostics.DiagnosticsResult;
import com.capsule.agentkey.diagnostics.DiagnosticsService;
import com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.eval.ScriptedAgentClient;
import com.capsule.agentkey.eval.ScriptedAgentClient.ScriptedTurn;
import com.capsule.agentkey.eval.ScriptedAgentClient.ToolCallSpec;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.tool.FileChange;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.WriteFileTool;
import com.capsule.agentkey.tool.WorkspaceContext;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P5-05：真实 ReAct 循环中的诊断闭环——诊断回灌、完成证据与重复诊断停止。
 */
class ReActDiagnosticsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;

    @TempDir
    Path workspace;

    @Test
    void brokenJavaDiagnosticsAreInjectedAndBlockCompletion() {
        RunResult run = run(List.of(
                ScriptedTurn.ofTools(List.of(new ToolCallSpec("write_file",
                        writeArgs("src/main/java/Bad.java",
                                "public class Bad { void x() { int a = ; } }")))),
                ScriptedTurn.ofText("done")));

        assertEquals(ReActAgentService.CompletionStatus.INCOMPLETE, run.completion().status());
        assertEquals("DIAGNOSTICS_FAILED", run.completion().reason());
        assertTrue(run.diagnostics().stream().anyMatch(DiagnosticsResult::hasErrors));
        assertTrue(run.snapshots().size() >= 2);
        assertTrue(run.snapshots().get(1).stream()
                .anyMatch(m -> m.path("content").asText("").contains("【变更文件诊断】")));
    }

    @Test
    void cleanJavaDiagnosticsWithoutVerifyConfigStayUnverified() {
        RunResult run = run(List.of(
                ScriptedTurn.ofTools(List.of(new ToolCallSpec("write_file",
                        writeArgs("src/main/java/Ok.java", "public class Ok {}")))),
                ScriptedTurn.ofText("done")));

        // P5-06：编译诊断通过只代表诊断证据；没有项目验证命令时必须显示未验证
        assertEquals(ReActAgentService.CompletionStatus.UNVERIFIED, run.completion().status());
        assertEquals("VERIFY_NOT_CONFIGURED", run.completion().reason());
        assertTrue(run.diagnostics().stream().anyMatch(d -> d.available() && !d.hasErrors()));
    }

    @Test
    void repeatedIdenticalDiagnosticsTriggerNoProgressStop() {
        RunResult run = run(List.of(
                ScriptedTurn.ofTools(List.of(new ToolCallSpec("write_file", writeArgs(
                        "src/main/java/Bad.java",
                        "public class Bad { void x() { int a = ; } } // 1")))),
                ScriptedTurn.ofTools(List.of(new ToolCallSpec("write_file", writeArgs(
                        "src/main/java/Bad.java",
                        "public class Bad { void x() { int a = ; } } // 2")))),
                ScriptedTurn.ofTools(List.of(new ToolCallSpec("write_file", writeArgs(
                        "src/main/java/Bad.java",
                        "public class Bad { void x() { int a = ; } } // 3")))),
                ScriptedTurn.ofText("done")));

        assertNotNull(run.noProgress());
        assertEquals("REPEATED_DIAGNOSTICS", run.noProgress().reason());
        assertEquals(ReActAgentService.CompletionStatus.INCOMPLETE, run.completion().status());
    }

    @Test
    void nonJavaChangeStaysUnverified() {
        RunResult run = run(List.of(
                ScriptedTurn.ofTools(List.of(new ToolCallSpec("write_file",
                        writeArgs("script.py", "print('hi')")))),
                ScriptedTurn.ofText("done")));

        assertEquals(ReActAgentService.CompletionStatus.UNVERIFIED, run.completion().status());
        assertEquals("VERIFY_NOT_CONFIGURED", run.completion().reason());
    }

    private RunResult run(List<ScriptedTurn> turns) {
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
                            mock(CommandService.class)),
                    new RepoMapService(new com.capsule.agentkey.config.AgentRepoMapProperties(),
                            new com.capsule.agentkey.service.index.CodeIndexService(
                                    new com.capsule.agentkey.service.index.LightweightCodeIndexer())));
            ReflectionTestUtils.setField(agent, "maxRepeatedToolCalls", 3);
            ReflectionTestUtils.setField(agent, "maxStagnantRounds", 4);

            AgentPolicy policy = new AgentPolicy("scripted",
                    AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO);
            AtomicReference<ReActAgentService.Completion> completion = new AtomicReference<>();
            AtomicReference<ReActAgentService.NoProgress> noProgress = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<DiagnosticsResult> diagnosticsEvents = new ArrayList<>();
            List<FileChange> fileChanges = new ArrayList<>();
            AtomicBoolean cancelled = new AtomicBoolean(false);

            agent.execute("test-key", policy, "write it", List.of(), USER_ID, PROJECT_ID, SESSION_ID,
                    "turn-diagnostics", false, "", "", "", "", cancelled,
                    new ReActAgentService.StreamCallback() {
                        @Override public void onThinking(String thought) {}
                        @Override public void onChunk(String chunk) {}
                        @Override public void onToolCall(String toolName, String arguments) {}
                        @Override public void onToolResult(String toolName, com.capsule.agentkey.tool.ToolResult result) {}
                        @Override public void onFileChange(FileChange change) { fileChanges.add(change); }
                        @Override public void onDiagnostics(DiagnosticsResult result) {
                            diagnosticsEvents.add(result);
                        }
                        @Override public void onApprovalRequired(String approvalId, String tool,
                                com.capsule.agentkey.dto.ApprovalPreview preview,
                                String risk, long expiresAt) {
                            approvals.resolve(approvalId, USER_ID, "APPROVE", false);
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
            return new RunResult(completion.get(), noProgress.get(), diagnosticsEvents,
                    client.messageSnapshots(), fileChanges);
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("ReAct 执行失败", e);
        }
    }

    private static String writeArgs(String path, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("path", path);
        node.put("content", content);
        return node.toString();
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
                             List<DiagnosticsResult> diagnostics,
                             List<List<ObjectNode>> snapshots,
                             List<FileChange> fileChanges) {
    }
}
