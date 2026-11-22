package com.capsule.agentkey.service;

import com.capsule.agentkey.agent.run.PlanEvidenceService;
import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunContext;
import com.capsule.agentkey.service.ReActAgentService.CompletionStatus;
import com.capsule.agentkey.service.ReActAgentService.StreamCallback;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.capsule.agentkey.verify.VerifyResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7c：Run 终态判定对计划完成证据的最终复查。
 */
class RunTerminationTest {

    @TempDir
    Path workspace;

    private RunTermination termination;
    private RunContext runContext;

    @BeforeEach
    void setUp() {
        termination = new RunTermination(PlanEvidenceService.withLocator(
                (userId, projectId) -> workspace));
        runContext = RunContext.create(1L, 2L, 3L, 4L, "turn-1", new java.util.concurrent.atomic.AtomicBoolean(false),
                List.of(), null);
        WorkspaceContext.set(runContext);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    private PlanStep contractStep(String id, String status, String path, String expected) {
        return new PlanStep("实现", status, id,
                new PlanStep.Acceptance(
                        List.of(new PlanStep.Artifact(path, List.of(expected))), false));
    }

    private StreamCallback capturePlan(List<List<PlanStep>> captured) {
        return new StreamCallback() {
            @Override public void onThinking(String thought) {}
            @Override public void onToolCall(String toolName, String arguments) {}
            @Override public void onToolResult(String toolName, ToolResult result) {}
            @Override public void onChunk(String chunk) {}
            @Override public void onPlan(List<PlanStep> steps) { captured.add(steps); }
            @Override public void onComplete() {}
            @Override public void onError(Throwable t) {}
        };
    }

    @Test
    void 工件缺失时候选完成返回证据缺失() {
        WorkspaceContext.get().setPlan(List.of(
                contractStep("s1", "completed", "a.txt", "done")));

        var completion = termination.candidateCompletion(false, false, false);

        assertEquals(CompletionStatus.INCOMPLETE, completion.status());
        assertEquals("PLAN_EVIDENCE_MISSING", completion.reason());
    }

    @Test
    void 工件文本被删除后候选完成不再通过() throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "done");
        WorkspaceContext.get().setPlan(List.of(contractStep("s1", "completed", "a.txt", "done")));
        assertEquals(CompletionStatus.COMPLETED, termination.candidateCompletion(false, false, false).status());

        Files.writeString(file, "changed");
        var completion = termination.candidateCompletion(false, false, false);

        assertEquals(CompletionStatus.INCOMPLETE, completion.status());
        assertEquals("PLAN_EVIDENCE_MISSING", completion.reason());
    }

    @Test
    void 验证后又有变更时候选完成返回证据过期() {
        runContext.setVerify(new VerifyResult(VerifyResult.Status.PASSED, "verify.json", true,
                1, 3, List.of(), "", "通过"));
        runContext.markVerified();
        runContext.bumpChangeEpoch();
        WorkspaceContext.get().setPlan(List.of(new PlanStep("验证", "completed", "s1",
                new PlanStep.Acceptance(List.of(), true))));

        var completion = termination.candidateCompletion(false, false, false);

        assertEquals(CompletionStatus.INCOMPLETE, completion.status());
        assertEquals("PLAN_EVIDENCE_STALE", completion.reason());
    }

    @Test
    void 收口时把证据不满足的完成步骤降级为aborted() {
        WorkspaceContext.get().setPlan(List.of(
                contractStep("s1", "completed", "missing.txt", "x"),
                new PlanStep("旧步骤", "completed")));
        List<List<PlanStep>> pushed = new ArrayList<>();

        termination.finalizePlan(capturePlan(pushed));

        assertEquals(1, pushed.size());
        assertEquals("aborted", pushed.get(0).get(0).status());
        assertEquals("completed", pushed.get(0).get(1).status());
        assertEquals("s1", pushed.get(0).get(0).id(), "降级不能丢失契约字段");
        assertTrue(pushed.get(0).get(0).hasContract());
    }

    @Test
    void 旧计划全部完成时不产生收口事件() {
        WorkspaceContext.get().setPlan(List.of(
                new PlanStep("a", "completed"),
                new PlanStep("b", "completed")));
        List<List<PlanStep>> pushed = new ArrayList<>();

        termination.finalizePlan(capturePlan(pushed));

        assertTrue(pushed.isEmpty());
    }

    @Test
    void 没有契约的旧计划行为不变() {
        WorkspaceContext.get().setPlan(List.of(new PlanStep("a", "completed")));

        var completion = termination.candidateCompletion(false, false, false);

        assertEquals(CompletionStatus.COMPLETED, completion.status());
        assertEquals("NO_VERIFICATION_REQUIRED", completion.reason());
        assertFalse(completion.filesChanged());
    }

    @Test
    void 存在未验收子任务时父Run不得完成() {
        AgentRunService runService = org.mockito.Mockito.mock(AgentRunService.class);
        com.capsule.agentkey.entity.AgentRun failed = new com.capsule.agentkey.entity.AgentRun();
        failed.setId(9L);
        failed.setState(com.capsule.agentkey.entity.AgentRun.RunState.FAILED);
        org.mockito.Mockito.when(runService.findSubtasks(1L)).thenReturn(List.of(failed));
        RunTermination withSubtasks = new RunTermination(PlanEvidenceService.withLocator(
                (userId, projectId) -> workspace), runService);

        var completion = withSubtasks.candidateCompletion(false, false, false);

        assertEquals(CompletionStatus.INCOMPLETE, completion.status());
        assertEquals("SUBTASK_NOT_ACCEPTED", completion.reason());
    }

    @Test
    void 子任务全部验收成功时父Run可以完成() {
        AgentRunService runService = org.mockito.Mockito.mock(AgentRunService.class);
        com.capsule.agentkey.entity.AgentRun ok = new com.capsule.agentkey.entity.AgentRun();
        ok.setId(10L);
        ok.setState(com.capsule.agentkey.entity.AgentRun.RunState.SUCCEEDED);
        org.mockito.Mockito.when(runService.findSubtasks(1L)).thenReturn(List.of(ok));
        RunTermination withSubtasks = new RunTermination(PlanEvidenceService.withLocator(
                (userId, projectId) -> workspace), runService);

        var completion = withSubtasks.candidateCompletion(false, false, false);

        assertEquals(CompletionStatus.COMPLETED, completion.status());
    }
}
