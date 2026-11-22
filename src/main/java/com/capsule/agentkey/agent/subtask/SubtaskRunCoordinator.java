package com.capsule.agentkey.agent.subtask;

import com.capsule.agentkey.agent.run.PlanEvidenceService;
import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunBudget;
import com.capsule.agentkey.agent.run.RunCheckpoint;
import com.capsule.agentkey.agent.run.RunContext;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ReActAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 串行子智能体协调器（R7c / P8-03）。
 *
 * <p>一次委派内的子任务严格按声明顺序执行：前一个子任务验收通过后才启动下一个；
 * 任一子任务失败、取消或证据不满足时立即回传，父 Run 不得把失败包装成成功。
 *
 * <p>隔离边界：
 * <ul>
 *   <li>每个子任务一个独立 child Run（{@code parent_run_id + delegation_id} 唯一）；</li>
 *   <li>子 Run 的 plan / checkpoint / state / usage / event 都写 child runId；</li>
 *   <li>父 Run 只通过 {@link SubtaskUpdate} 看到子任务状态；</li>
 *   <li>父子共享 {@link RunBudget}，子 Run 不能重置父预算。</li>
 * </ul>
 */
@Slf4j
public final class SubtaskRunCoordinator {

    /** 子 Run 的实际执行入口；由 ReActAgentService 提供，测试可替换为脚本化 runner。 */
    @FunctionalInterface
    public interface Runner {
        Execution run(Request request);
    }

    /** 一次子任务执行的输入。 */
    public record Request(String apiKey,
                          AgentPolicy policy,
                          SubtaskSpec spec,
                          Long parentRunId,
                          Long childRunId,
                          Long userId,
                          Long projectId,
                          Long sessionId,
                          String workspacePath,
                          AtomicBoolean cancelRequested,
                          ReActAgentService.StreamCallback parentCallback,
                          SubtaskStreamCallback childCallback,
                          RunBudget budget,
                          RunCheckpoint resumeFrom) {
    }

    /** 子任务执行结果：RunContext 用于父侧再次核对证据，error 表示执行异常。 */
    public record Execution(RunContext runContext, String summary, Throwable error) {
    }

    /** 每个子任务终结后保存父委派断点的回调。 */
    @FunctionalInterface
    public interface ProgressSink {
        void onProgress(List<SubtaskResult> results, int nextIndex);
    }

    private static final int MAX_DELEGATION_ID_LENGTH = 80;
    private static final int RESULT_SUMMARY_MAX_CHARS = 600;

    private final AgentRunService runService;
    private final PlanEvidenceService evidenceService;
    private final Runner runner;
    private final int maxSubtasks;
    private final ObjectMapper mapper = new ObjectMapper();

    public SubtaskRunCoordinator(AgentRunService runService,
                                 PlanEvidenceService evidenceService,
                                 Runner runner,
                                 int maxSubtasks) {
        this.runService = runService;
        this.evidenceService = evidenceService == null
                ? PlanEvidenceService.withoutWorkspace() : evidenceService;
        this.runner = runner;
        this.maxSubtasks = Math.max(1, maxSubtasks);
    }

    /**
     * 串行执行一次委派。
     *
     * @param priorResults 续跑时已经完成的子任务结果（按声明顺序前缀）
     * @param startIndex   从第几个子任务继续（0 表示全新委派）
     * @return 与 specs 前缀一致的结果列表；失败时长度可能小于 specs
     */
    public List<SubtaskResult> delegate(String apiKey, AgentPolicy policy, Long parentRunId,
                                        Long userId, Long projectId, Long sessionId,
                                        String workspacePath, String delegationId,
                                        List<SubtaskSpec> specs, List<SubtaskResult> priorResults,
                                        int startIndex, AtomicBoolean cancelRequested,
                                        ReActAgentService.StreamCallback parentCallback,
                                        RunBudget budget, ProgressSink progressSink) {
        List<SubtaskResult> results = new ArrayList<>();
        if (priorResults != null) {
            int keep = Math.min(Math.max(0, startIndex), priorResults.size());
            results.addAll(priorResults.subList(0, keep));
        }
        if (specs == null || specs.isEmpty()) {
            return results;
        }
        int from = Math.min(Math.max(0, startIndex), specs.size());
        if (specs.size() > maxSubtasks) {
            results.add(new SubtaskResult(specs.get(0).id(), null, SubtaskResult.FAILED,
                    "TOO_MANY_SUBTASKS", "单次委派最多 " + maxSubtasks + " 个子任务"));
            return results;
        }
        for (int i = from; i < specs.size(); i++) {
            SubtaskSpec spec = specs.get(i);
            if (cancelRequested != null && cancelRequested.get()) {
                results.add(new SubtaskResult(spec.id(), null, SubtaskResult.CANCELLED,
                        "PARENT_CANCELLED", "父 Run 已取消，未启动该子任务"));
                notifyProgress(progressSink, results, i + 1);
                break;
            }
            if (budget != null && budget.timeExceeded()) {
                results.add(new SubtaskResult(spec.id(), null, SubtaskResult.FAILED,
                        "TIME_BUDGET_EXCEEDED", "父子共享时间预算已耗尽，未启动该子任务"));
                notifyProgress(progressSink, results, i + 1);
                break;
            }
            SubtaskResult result = executeOne(apiKey, policy, parentRunId, userId, projectId,
                    sessionId, workspacePath, delegationId, spec, cancelRequested,
                    parentCallback, budget);
            results.add(result);
            notifyProgress(progressSink, results, i + 1);
            if (!result.accepted()) {
                break;
            }
        }
        // 子用量在子 Run 到达终态后逐条汇总；已成功的子 Run 复用时不再重复累计
        return results;
    }

    private SubtaskResult executeOne(String apiKey, AgentPolicy policy, Long parentRunId,
                                     Long userId, Long projectId, Long sessionId,
                                     String workspacePath, String delegationId, SubtaskSpec spec,
                                     AtomicBoolean cancelRequested,
                                     ReActAgentService.StreamCallback parentCallback,
                                     RunBudget budget) {
        AgentRun child;
        String childDelegationId = childDelegationId(delegationId, spec.id());
        try {
            child = runService.createSubtask(parentRunId, userId, sessionId, projectId,
                    childDelegationId, requestJson(spec)).run();
        } catch (Exception e) {
            log.warn("创建子 Run 失败 [parentRunId={}, subtask={}]: {}",
                    parentRunId, spec.id(), e.getMessage());
            return new SubtaskResult(spec.id(), null, SubtaskResult.FAILED,
                    "SUBTASK_CREATE_FAILED", "创建子任务失败：" + e.getMessage());
        }
        Long childRunId = child.getId();

        RunCheckpoint resumeFrom = null;
        try {
            switch (child.getState()) {
                case SUCCEEDED -> {
                    return new SubtaskResult(spec.id(), childRunId, SubtaskResult.SUCCEEDED,
                            "REUSED", "子任务此前已验收通过，直接复用结果");
                }
                case CANCELLED -> {
                    return new SubtaskResult(spec.id(), childRunId, SubtaskResult.CANCELLED,
                            "PREVIOUSLY_CANCELLED", "子任务此前已取消");
                }
                case FAILED -> {
                    if (!AgentRunService.isResumable(child)) {
                        return new SubtaskResult(spec.id(), childRunId, SubtaskResult.FAILED,
                                "PREVIOUS_FAILURE", "子任务此前已失败：" + child.getErrorMessage());
                    }
                    resumeFrom = runService.loadCheckpoint(childRunId).orElse(null);
                    if (resumeFrom == null
                            || resumeFrom.getMessages() == null
                            || resumeFrom.getMessages().isEmpty()) {
                        return new SubtaskResult(spec.id(), childRunId, SubtaskResult.FAILED,
                                "SUBTASK_NO_CHECKPOINT", "子任务缺少可续跑断点，不能猜测重放");
                    }
                    runService.resumeInterrupted(childRunId);
                }
                case QUEUED -> runService.start(childRunId);
                default -> {
                    // RUNNING / WAITING_*：正常情况下进程重启后已统一标为 FAILED，不应出现
                    runService.failIfActive(childRunId, "SUBTASK_STATE_CONFLICT",
                            "子任务处于不可恢复状态：" + child.getState());
                    return new SubtaskResult(spec.id(), childRunId, SubtaskResult.FAILED,
                            "SUBTASK_STATE_CONFLICT", "子任务处于不可恢复状态：" + child.getState());
                }
            }
        } catch (Exception e) {
            failQuietly(childRunId, "SUBTASK_START_FAILED", e.getMessage());
            return new SubtaskResult(spec.id(), childRunId, SubtaskResult.FAILED,
                    "SUBTASK_START_FAILED", "启动子任务失败：" + e.getMessage());
        }

        if (parentCallback != null) {
            parentCallback.onSubtask(SubtaskUpdate.status(parentRunId, childRunId, spec.id(),
                    "RUNNING", "子任务开始执行：" + spec.objective()));
        }

        SubtaskStreamCallback childCallback = new SubtaskStreamCallback(
                parentRunId, childRunId, spec.id(), runService, parentCallback, budget);
        Execution execution;
        try {
            execution = runner.run(new Request(apiKey, policy, spec, parentRunId, childRunId,
                    userId, projectId, sessionId, workspacePath, cancelRequested,
                    parentCallback, childCallback, budget, resumeFrom));
        } catch (Exception e) {
            log.warn("子任务执行异常 [childRunId={}]: {}", childRunId, e.getMessage(), e);
            failQuietly(childRunId, "SUBTASK_EXECUTION_FAILED", e.getMessage());
            recordChildUsage(parentRunId, childRunId);
            notifyStatus(parentCallback, parentRunId, childRunId, spec.id(), "FAILED",
                    "子任务执行异常：" + e.getMessage());
            return new SubtaskResult(spec.id(), childRunId, SubtaskResult.FAILED,
                    "SUBTASK_EXECUTION_FAILED", "子任务执行异常：" + e.getMessage());
        }

        if (cancelRequested != null && cancelRequested.get()) {
            cancelQuietly(childRunId, "parent cancelled");
            recordChildUsage(parentRunId, childRunId);
            notifyStatus(parentCallback, parentRunId, childRunId, spec.id(), "CANCELLED",
                    "父 Run 已取消，子任务中止");
            return new SubtaskResult(spec.id(), childRunId, SubtaskResult.CANCELLED,
                    "PARENT_CANCELLED", "父 Run 已取消，子任务中止");
        }
        if (execution != null && execution.error() != null) {
            failQuietly(childRunId, "SUBTASK_EXECUTION_FAILED", execution.error().getMessage());
            recordChildUsage(parentRunId, childRunId);
            notifyStatus(parentCallback, parentRunId, childRunId, spec.id(), "FAILED",
                    "子任务执行异常：" + execution.error().getMessage());
            return new SubtaskResult(spec.id(), childRunId, SubtaskResult.FAILED,
                    "SUBTASK_EXECUTION_FAILED", "子任务执行异常：" + execution.error().getMessage());
        }

        List<PlanEvidenceService.Issue> issues = checkEvidence(execution, spec);
        if (!issues.isEmpty()) {
            String detail = describeIssues(issues);
            failQuietly(childRunId, "SUBTASK_EVIDENCE_MISSING", detail);
            recordChildUsage(parentRunId, childRunId);
            notifyStatus(parentCallback, parentRunId, childRunId, spec.id(), "FAILED",
                    "子任务验收未通过：" + detail);
            return new SubtaskResult(spec.id(), childRunId, SubtaskResult.FAILED,
                    "SUBTASK_EVIDENCE_MISSING", "子任务验收未通过：" + detail);
        }

        String summary = execution == null ? "" : execution.summary();
        try {
            runService.succeedIfActive(childRunId, resultJson(spec, summary));
        } catch (Exception e) {
            log.warn("标记子 Run 成功失败 [childRunId={}]: {}", childRunId, e.getMessage());
        }
        recordChildUsage(parentRunId, childRunId);
        notifyStatus(parentCallback, parentRunId, childRunId, spec.id(), "SUCCEEDED",
                "子任务验收通过");
        return new SubtaskResult(spec.id(), childRunId, SubtaskResult.SUCCEEDED,
                "ACCEPTED", summary == null || summary.isBlank() ? "子任务验收通过" : summary);
    }

    private List<PlanEvidenceService.Issue> checkEvidence(Execution execution, SubtaskSpec spec) {
        if (execution == null || execution.runContext() == null) {
            return List.of(new PlanEvidenceService.Issue(spec.id(),
                    PlanEvidenceService.ISSUE_MISSING, "子任务未产生可核对的执行上下文"));
        }
        PlanStep completed = new PlanStep(spec.objective(), "completed", spec.id(), spec.acceptance());
        return evidenceService.checkCompleted(execution.runContext(), List.of(completed));
    }

    private void notifyStatus(ReActAgentService.StreamCallback parentCallback, Long parentRunId,
                              Long childRunId, String subtaskId, String status, String message) {
        if (parentCallback != null) {
            parentCallback.onSubtask(SubtaskUpdate.status(parentRunId, childRunId, subtaskId,
                    status, message));
        }
    }

    private void notifyProgress(ProgressSink sink, List<SubtaskResult> results, int nextIndex) {
        if (sink == null) {
            return;
        }
        try {
            sink.onProgress(List.copyOf(results), nextIndex);
        } catch (Exception e) {
            log.debug("保存子任务进度失败: {}", e.getMessage());
        }
    }

    private void failQuietly(Long runId, String code, String message) {
        try {
            runService.failIfActive(runId, code, message);
        } catch (Exception e) {
            log.warn("标记子 Run 失败失败 [runId={}]: {}", runId, e.getMessage());
        }
    }

    /** 子 Run 到达终态后把它的用量汇总进父 Run；复用的已完成子 Run 不再重复累计 */
    private void recordChildUsage(Long parentRunId, Long childRunId) {
        try {
            runService.addSubtaskUsageToParent(parentRunId, childRunId);
        } catch (Exception e) {
            log.warn("汇总子 Run 用量失败 [parentRunId={}, childRunId={}]: {}",
                    parentRunId, childRunId, e.getMessage());
        }
    }

    private void cancelQuietly(Long runId, String reason) {
        try {
            runService.cancelIfActive(runId, reason);
        } catch (Exception e) {
            log.warn("取消子 Run 失败 [runId={}]: {}", runId, e.getMessage());
        }
    }

    private String requestJson(SubtaskSpec spec) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subtaskId", spec.id());
        payload.put("objective", spec.objective());
        payload.put("acceptance", spec.acceptance());
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"subtaskId\":\"" + spec.id() + "\"}";
        }
    }

    private String resultJson(SubtaskSpec spec, String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subtaskId", spec.id());
        payload.put("status", SubtaskResult.SUCCEEDED);
        payload.put("summary", truncate(summary));
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static String describeIssues(List<PlanEvidenceService.Issue> issues) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(2, issues.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(issues.get(i).message());
        }
        if (issues.size() > limit) {
            sb.append("；另有 ").append(issues.size() - limit).append(" 条证据问题");
        }
        return sb.toString();
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= RESULT_SUMMARY_MAX_CHARS) {
            return text;
        }
        return text.substring(0, RESULT_SUMMARY_MAX_CHARS) + "…";
    }

    static String childDelegationId(String delegationId, String subtaskId) {
        String value = (delegationId == null ? "delegation" : delegationId) + ":" + subtaskId;
        if (value.length() > MAX_DELEGATION_ID_LENGTH) {
            value = value.substring(0, MAX_DELEGATION_ID_LENGTH);
        }
        return value;
    }
}
