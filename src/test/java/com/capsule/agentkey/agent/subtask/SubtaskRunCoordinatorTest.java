package com.capsule.agentkey.agent.subtask;

import com.capsule.agentkey.agent.run.PlanEvidenceService;
import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunContext;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ReActAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R7c：串行子智能体协调器——顺序、失败快速回传、复用、取消与用量汇总。
 */
class SubtaskRunCoordinatorTest {

    private AgentRunService runService;
    private PlanEvidenceService evidenceService;
    private final List<String> executed = new ArrayList<>();
    private SubtaskRunCoordinator coordinator;

    @BeforeEach
    void setUp() {
        runService = Mockito.mock(AgentRunService.class);
        evidenceService = Mockito.mock(PlanEvidenceService.class);
        when(evidenceService.checkCompleted(any(), anyList())).thenReturn(List.of());
        when(runService.createSubtask(anyLong(), any(), any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> new AgentRunService.CreateResult(
                        child(100L + executed.size(), RunState.QUEUED), true));
    }

    private AgentRun child(long id, RunState state) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setState(state);
        return run;
    }

    private SubtaskSpec spec(String id) {
        return new SubtaskSpec(id, "目标-" + id,
                new PlanStep.Acceptance(
                        List.of(new PlanStep.Artifact(id + ".txt", List.of("ok"))), false));
    }

    private void build(SubtaskRunCoordinator.Runner runner, int max) {
        coordinator = new SubtaskRunCoordinator(runService, evidenceService, runner, max);
    }

    private SubtaskRunCoordinator.Runner recordingRunner() {
        return request -> {
            executed.add(request.spec().id());
            RunContext context = RunContext.create(request.childRunId(), request.userId(),
                    request.projectId(), request.sessionId(), null,
                    new AtomicBoolean(false), List.of(), null);
            return new SubtaskRunCoordinator.Execution(context, "摘要-" + request.spec().id(), null);
        };
    }

    private List<SubtaskResult> delegate(List<SubtaskSpec> specs, AtomicBoolean cancel) {
        return coordinator.delegate("key", new com.capsule.agentkey.service.AgentPolicy(
                        "model", com.capsule.agentkey.service.AgentPolicy.Reasoning.STANDARD,
                        com.capsule.agentkey.service.AgentPolicy.Permission.AUTO),
                1L, 2L, 3L, 4L, null, "del-1", specs, List.of(), 0, cancel,
                new ReActAgentService.StreamCallback() {
                    @Override public void onThinking(String thought) {}
                    @Override public void onToolCall(String toolName, String arguments) {}
                    @Override public void onToolResult(String toolName,
                                                       com.capsule.agentkey.tool.ToolResult result) {}
                    @Override public void onChunk(String chunk) {}
                    @Override public void onComplete() {}
                    @Override public void onError(Throwable t) {}
                }, null, null);
    }

    @Test
    void 按声明顺序串行执行全部子任务() {
        build(recordingRunner(), 4);

        List<SubtaskResult> results = delegate(List.of(spec("s1"), spec("s2"), spec("s3")),
                new AtomicBoolean(false));

        assertEquals(List.of("s1", "s2", "s3"), executed);
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(SubtaskResult::accepted));
    }

    @Test
    void 子任务失败后不再启动后续子任务() {
        when(evidenceService.checkCompleted(any(), anyList()))
                .thenReturn(List.of())
                .thenReturn(List.of(new PlanEvidenceService.Issue("s2",
                        PlanEvidenceService.ISSUE_MISSING, "缺少工件")));
        build(recordingRunner(), 4);

        List<SubtaskResult> results = delegate(List.of(spec("s1"), spec("s2"), spec("s3")),
                new AtomicBoolean(false));

        assertEquals(List.of("s1", "s2"), executed);
        assertEquals(2, results.size());
        assertEquals(SubtaskResult.FAILED, results.get(1).status());
        assertEquals("SUBTASK_EVIDENCE_MISSING", results.get(1).reason());
    }

    @Test
    void 单次超过四个子任务直接失败且不执行() {
        build(recordingRunner(), 4);

        List<SubtaskResult> results = delegate(
                List.of(spec("s1"), spec("s2"), spec("s3"), spec("s4"), spec("s5")),
                new AtomicBoolean(false));

        assertTrue(executed.isEmpty());
        assertEquals(1, results.size());
        assertEquals("TOO_MANY_SUBTASKS", results.get(0).reason());
    }

    @Test
    void 取消后不再启动剩余子任务() {
        AtomicBoolean cancel = new AtomicBoolean(false);
        build(request -> {
            executed.add(request.spec().id());
            cancel.set(true);
            RunContext context = RunContext.create(request.childRunId(), request.userId(),
                    request.projectId(), request.sessionId(), null,
                    new AtomicBoolean(false), List.of(), null);
            return new SubtaskRunCoordinator.Execution(context, "ok", null);
        }, 4);

        List<SubtaskResult> results = delegate(List.of(spec("s1"), spec("s2")), cancel);

        assertEquals(List.of("s1"), executed);
        assertEquals(1, results.size());
        assertEquals(SubtaskResult.CANCELLED, results.get(0).status());
    }

    @Test
    void 已成功的子Run复用时不再重复执行() {
        when(runService.createSubtask(anyLong(), any(), any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> new AgentRunService.CreateResult(child(88L, RunState.SUCCEEDED), false));
        build(recordingRunner(), 4);

        List<SubtaskResult> results = delegate(List.of(spec("s1")), new AtomicBoolean(false));

        assertTrue(executed.isEmpty());
        assertEquals(SubtaskResult.SUCCEEDED, results.get(0).status());
        assertEquals("REUSED", results.get(0).reason());
    }

    @Test
    void 子Run终态后用量汇总进父Run() {
        build(recordingRunner(), 4);

        delegate(List.of(spec("s1")), new AtomicBoolean(false));

        verify(runService).addSubtaskUsageToParent(1L, 100L);
    }

    @Test
    void 执行异常时子Run标记失败且不继续() {
        build(request -> {
            executed.add(request.spec().id());
            throw new IllegalStateException("模型不可用");
        }, 4);

        List<SubtaskResult> results = delegate(List.of(spec("s1"), spec("s2")),
                new AtomicBoolean(false));

        assertEquals(List.of("s1"), executed);
        assertEquals("SUBTASK_EXECUTION_FAILED", results.get(0).reason());
        verify(runService).failIfActive(anyLong(), anyString(), anyString());
    }

    @Test
    void 空规格不会创建子Run() {
        build(recordingRunner(), 4);

        List<SubtaskResult> results = delegate(Collections.emptyList(), new AtomicBoolean(false));

        assertTrue(results.isEmpty());
        verify(runService, never()).createSubtask(anyLong(), any(), any(), any(), anyString(),
                anyString());
    }
}
