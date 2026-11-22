package com.capsule.agentkey.service;

import com.capsule.agentkey.agent.run.PlanEvidenceService;
import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunCheckpoint;
import com.capsule.agentkey.agent.run.RunContext;
import com.capsule.agentkey.agent.subtask.SubtaskResult;
import com.capsule.agentkey.agent.subtask.SubtaskRunCoordinator;
import com.capsule.agentkey.agent.subtask.SubtaskSpec;
import com.capsule.agentkey.agent.tool.ToolExecutor;
import com.capsule.agentkey.agent.run.FileChangeService;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.eval.ScriptedAgentClient;
import com.capsule.agentkey.tool.AskUserQuestionTool;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.verify.VerifyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R7c：父 Run 从 pendingDelegation 续跑。
 *
 * <p>模拟「父 Run 在第二个子任务之前进程重启」：已验收的子任务不重放，
 * 只从下一未完成下标继续，委派 tool 响应只补一次，然后回到正常循环，
 * 由 RunTermination 读取子 Run 状态再次收口。
 */
class AgentOrchestratorSubtaskResumeTest {

    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;
    private static final long PARENT_RUN_ID = 555L;

    @TempDir
    Path workspace;

    @Test
    void 断点续跑只执行剩余子任务并补齐委派tool响应() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "ok", StandardCharsets.UTF_8);

        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofText("子任务已全部完成")));
        AgentRunService runService = mock(AgentRunService.class);
        AgentRun child = new AgentRun();
        child.setId(901L);
        child.setState(AgentRun.RunState.QUEUED);
        when(runService.createSubtask(eq(PARENT_RUN_ID), eq(USER_ID), eq(SESSION_ID),
                eq(PROJECT_ID), eq("del-1:sub-b"), anyString()))
                .thenReturn(new AgentRunService.CreateResult(child, true));
        when(runService.findSubtasks(PARENT_RUN_ID)).thenReturn(List.of(
                childRun(900L), childRun(901L)));

        List<String> executed = new ArrayList<>();
        PlanEvidenceService evidence = PlanEvidenceService.withLocator(
                (userId, projectId) -> workspace);
        SubtaskRunCoordinator coordinator = new SubtaskRunCoordinator(runService, evidence,
                request -> {
                    executed.add(request.spec().id());
                    RunContext context = RunContext.create(request.childRunId(), USER_ID,
                            PROJECT_ID, SESSION_ID, null, new AtomicBoolean(false),
                            List.of(), null);
                    return new SubtaskRunCoordinator.Execution(context, "子任务B完成", null);
                }, 4);

        PlanStep.Acceptance acceptance = new PlanStep.Acceptance(
                List.of(new PlanStep.Artifact("a.txt", List.of("ok"))), false);
        List<SubtaskSpec> specs = List.of(
                new SubtaskSpec("sub-a", "完成A", acceptance),
                new SubtaskSpec("sub-b", "完成B", acceptance));
        List<SubtaskResult> done = List.of(new SubtaskResult("sub-a", 900L,
                SubtaskResult.SUCCEEDED, "ACCEPTED", "子任务A完成"));

        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> messages = new ArrayList<>();
        messages.add(mapper.createObjectNode().put("role", "user").put("content", "执行委派"));
        messages.add(mapper.createObjectNode().put("role", "assistant").putNull("content"));

        RunCheckpoint resume = new RunCheckpoint();
        resume.setRound(2);
        resume.setMessages(new ArrayList<>(messages));
        resume.setPendingDelegation(new RunCheckpoint.PendingDelegation(
                "del-1", specs, 1, new ArrayList<>(done), "call-1"));

        List<RunCheckpoint> checkpoints = new ArrayList<>();
        Capture capture = new Capture();
        AgentOrchestrator.Invocation invocation = new AgentOrchestrator.Invocation(
                "test-key",
                new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                messages, List.of(), "", workspace.toString(), USER_ID, PROJECT_ID, SESSION_ID,
                new AtomicBoolean(false), capture, null, null, PARENT_RUN_ID, resume,
                checkpoints::add, coordinator, null);

        AgentOrchestrator orchestrator = new AgentOrchestrator(client,
                mock(ToolExecutor.class), mock(FileChangeService.class),
                mock(VerifyService.class), mock(AskUserQuestionTool.class), runService,
                (content, limit) -> content,
                new AgentOrchestrator.Limits(3, 4, 0, 0), evidence);

        orchestrator.run(invocation);

        assertEquals(List.of("sub-b"), executed, "已验收的子任务不得重放，只执行剩余下标");
        assertEquals(1, client.modelCalls(), "续跑后只发起一次正常推理");

        List<ObjectNode> sent = client.messageSnapshots().get(0);
        ObjectNode toolResponse = sent.stream()
                .filter(m -> "tool".equals(m.path("role").asText()))
                .findFirst().orElse(null);
        assertNotNull(toolResponse, "必须把委派结果作为 tool 响应补进权威消息");
        assertEquals("call-1", toolResponse.path("tool_call_id").asText(),
                "tool 响应必须复用原 toolCallId");
        assertTrue(toolResponse.path("content").asText().contains("sub-b"),
                "tool 响应必须包含恢复的子任务结果");
        assertFalse(toolResponse.path("content").asText().contains("失败"),
                "全部子任务通过时不能出现失败摘要");

        assertNotNull(capture.completion, "续跑完成后必须给出终态");
        assertEquals(ReActAgentService.CompletionStatus.COMPLETED, capture.completion.status(),
                "全部子 Run SUCCEEDED 时父 Run 才允许 COMPLETED");

        assertFalse(checkpoints.isEmpty(), "每个子任务终结后都要保存父委派断点");
        RunCheckpoint last = checkpoints.get(checkpoints.size() - 1);
        assertEquals(2, last.getRound());
        assertNotNull(last.getPendingDelegation());
        assertEquals(2, last.getPendingDelegation().getNextIndex(),
                "进度断点必须推进到下一个未完成下标");
    }

    @Test
    void 全部子任务已验收时只补委派tool响应且不重放() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "ok", StandardCharsets.UTF_8);

        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofText("汇总完成")));
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.findSubtasks(PARENT_RUN_ID)).thenReturn(List.of(
                childRun(910L), childRun(911L)));

        List<String> executed = new ArrayList<>();
        PlanEvidenceService evidence = PlanEvidenceService.withLocator(
                (userId, projectId) -> workspace);
        SubtaskRunCoordinator coordinator = new SubtaskRunCoordinator(runService, evidence,
                request -> {
                    executed.add(request.spec().id());
                    RunContext context = RunContext.create(request.childRunId(), USER_ID,
                            PROJECT_ID, SESSION_ID, null, new AtomicBoolean(false),
                            List.of(), null);
                    return new SubtaskRunCoordinator.Execution(context, "不应执行", null);
                }, 4);

        PlanStep.Acceptance acceptance = new PlanStep.Acceptance(
                List.of(new PlanStep.Artifact("a.txt", List.of("ok"))), false);
        List<SubtaskSpec> specs = List.of(
                new SubtaskSpec("sub-a", "完成A", acceptance),
                new SubtaskSpec("sub-b", "完成B", acceptance));
        List<SubtaskResult> done = List.of(
                new SubtaskResult("sub-a", 910L, SubtaskResult.SUCCEEDED, "ACCEPTED", "A完成"),
                new SubtaskResult("sub-b", 911L, SubtaskResult.SUCCEEDED, "ACCEPTED", "B完成"));

        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> messages = new ArrayList<>();
        messages.add(mapper.createObjectNode().put("role", "user").put("content", "执行委派"));
        messages.add(mapper.createObjectNode().put("role", "assistant").putNull("content"));

        RunCheckpoint resume = new RunCheckpoint();
        resume.setRound(2);
        resume.setMessages(new ArrayList<>(messages));
        resume.setPendingDelegation(new RunCheckpoint.PendingDelegation(
                "del-1", specs, 2, new ArrayList<>(done), "call-1"));

        List<RunCheckpoint> checkpoints = new ArrayList<>();
        Capture capture = new Capture();
        AgentOrchestrator.Invocation invocation = new AgentOrchestrator.Invocation(
                "test-key",
                new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                messages, List.of(), "", workspace.toString(), USER_ID, PROJECT_ID, SESSION_ID,
                new AtomicBoolean(false), capture, null, null, PARENT_RUN_ID, resume,
                checkpoints::add, coordinator, null);

        AgentOrchestrator orchestrator = new AgentOrchestrator(client,
                mock(ToolExecutor.class), mock(FileChangeService.class),
                mock(VerifyService.class), mock(AskUserQuestionTool.class), runService,
                (content, limit) -> content,
                new AgentOrchestrator.Limits(3, 4, 0, 0), evidence);

        orchestrator.run(invocation);

        assertTrue(executed.isEmpty(), "全部子任务已验收时不得重放");
        assertEquals(1, client.modelCalls(), "续跑后只发起一次正常推理");

        ObjectNode toolResponse = client.messageSnapshots().get(0).stream()
                .filter(m -> "tool".equals(m.path("role").asText()))
                .findFirst().orElse(null);
        assertNotNull(toolResponse, "必须补齐委派 tool 响应");
        assertEquals("call-1", toolResponse.path("tool_call_id").asText());
        assertTrue(toolResponse.path("content").asText().contains("sub-a"));
        assertTrue(toolResponse.path("content").asText().contains("sub-b"));

        assertNotNull(capture.completion);
        assertEquals(ReActAgentService.CompletionStatus.COMPLETED, capture.completion.status(),
                "全部子 Run SUCCEEDED 时父 Run 才允许 COMPLETED");
    }

    private static AgentRun childRun(long id) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setState(AgentRun.RunState.SUCCEEDED);
        return run;
    }

    /** 捕获终态与计划的最小回调 */
    private static final class Capture implements ReActAgentService.StreamCallback {
        ReActAgentService.Completion completion;

        @Override public void onThinking(String thought) {}
        @Override public void onToolCall(String toolName, String arguments) {}
        @Override public void onToolResult(String toolName, ToolResult result) {}
        @Override public void onChunk(String chunk) {}
        @Override public void onPlan(List<PlanStep> steps) {}
        @Override public void onCompletion(ReActAgentService.Completion completion) {
            this.completion = completion;
        }
        @Override public void onNoProgress(ReActAgentService.NoProgress noProgress) {}
        @Override public void onComplete() {}
        @Override public void onError(Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
