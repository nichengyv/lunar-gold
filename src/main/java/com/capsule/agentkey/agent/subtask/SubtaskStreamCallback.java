package com.capsule.agentkey.agent.subtask;

import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunBudget;
import com.capsule.agentkey.diagnostics.DiagnosticsResult;
import com.capsule.agentkey.dto.ApprovalPreview;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.verify.VerifyResult;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子 Run 的流式回调（R7c）。
 *
 * <p>职责边界：子 Run 的模型 chunk、工具、计划、诊断、验证与用量只写入
 * <b>child runId</b> 的事件流；父 Run 只收到 {@link SubtaskUpdate} 轻量状态。
 *
 * <p>关键约束：绝不调用父回调的 {@code onComplete/onError}，也绝不保存父聊天消息、
 * 关闭父 SSE——子 Run 的终态由协调器根据验收证据决定。审批与用户询问携带
 * childRunId，前端据此把卡片归属到正确的子任务。
 */
@Slf4j
public final class SubtaskStreamCallback implements ReActAgentService.StreamCallback {

    private static final int SUMMARY_MAX_CHARS = 2000;

    private final Long parentRunId;
    private final Long childRunId;
    private final String subtaskId;
    private final AgentRunService runService;
    private final ReActAgentService.StreamCallback parentCallback;
    private final RunBudget budget;

    private final StringBuilder summary = new StringBuilder();
    private volatile ReActAgentService.Completion completion;

    public SubtaskStreamCallback(Long parentRunId, Long childRunId, String subtaskId,
                                 AgentRunService runService,
                                 ReActAgentService.StreamCallback parentCallback,
                                 RunBudget budget) {
        this.parentRunId = parentRunId;
        this.childRunId = childRunId;
        this.subtaskId = subtaskId;
        this.runService = runService;
        this.parentCallback = parentCallback;
        this.budget = budget;
    }

    /** 子 Run 最近一次完成判定；未被调用时为 null */
    public ReActAgentService.Completion completion() {
        return completion;
    }

    /** 子 Run 已生成正文的有限长度摘要，供父 Run 汇总 */
    public String summary() {
        return summary.toString();
    }

    @Override
    public void onThinking(String thought) {
        // 子 Run 的思考内容只属于子事件流；第一版不落库，避免事件表被长思考撑大
    }

    @Override
    public void onToolCall(String toolName, String arguments) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", toolName);
        payload.put("args", truncate(arguments, 500));
        appendEvent(AgentRunEvent.EventType.TOOL_CALL, payload);
    }

    @Override
    public void onToolResult(String toolName, ToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", toolName);
        payload.put("success", result.isSuccess());
        payload.put("content", truncate(result.getContent(), 500));
        payload.put("error", truncate(result.getError(), 300));
        payload.put("costMs", result.getCostMs());
        appendEvent(AgentRunEvent.EventType.TOOL_RESULT, payload);
    }

    @Override
    public void onPlan(List<PlanStep> steps) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("steps", steps == null ? List.of() : steps);
        appendEvent(AgentRunEvent.EventType.PLAN_UPDATED, payload);
    }

    @Override
    public void onUsage(long promptTokens, long completionTokens) {
        if (runService != null) {
            try {
                runService.recordUsage(childRunId, promptTokens, completionTokens);
            } catch (Exception e) {
                log.warn("记录子 Run 用量失败 [runId={}]: {}", childRunId, e.getMessage());
            }
        }
        if (budget != null) {
            budget.addTokens(promptTokens + completionTokens);
        }
    }

    @Override
    public void onChunk(String chunk) {
        int remaining = SUMMARY_MAX_CHARS - summary.length();
        if (remaining <= 0 || chunk == null || chunk.isEmpty()) {
            return;
        }
        summary.append(chunk, 0, Math.min(remaining, chunk.length()));
    }

    @Override
    public void onCompletion(ReActAgentService.Completion completion) {
        this.completion = completion;
    }

    @Override
    public void onDiagnostics(DiagnosticsResult diagnostics) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", diagnostics.available());
        payload.put("analyzedFiles", diagnostics.analyzedFiles());
        payload.put("totalCount", diagnostics.totalCount());
        appendEvent(AgentRunEvent.EventType.DIAGNOSTICS, payload);
    }

    @Override
    public void onVerify(VerifyResult verify) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", verify.status().name());
        payload.put("message", truncate(verify.message(), 500));
        payload.put("attempt", verify.attempt());
        payload.put("maxAttempts", verify.maxAttempts());
        appendEvent(AgentRunEvent.EventType.VERIFY, payload);
    }

    @Override
    public void onApprovalRequired(String approvalId, String tool, ApprovalPreview preview,
                                   String risk, long expiresAt) {
        if (runService != null) {
            try {
                runService.pauseForApproval(childRunId, approvalId, tool);
            } catch (Exception e) {
                log.warn("子 Run 进入等待审批失败 [runId={}]: {}", childRunId, e.getMessage());
            }
        }
        if (parentCallback != null) {
            parentCallback.onSubtask(SubtaskUpdate.approval(parentRunId, childRunId, subtaskId,
                    approvalId, tool, preview, risk, expiresAt));
        }
    }

    @Override
    public void onApprovalResolved(String approvalId, String decision, String source, String batchId) {
        if (runService != null) {
            try {
                runService.resumeAfterApproval(childRunId, decision, source, batchId);
            } catch (Exception e) {
                log.warn("子 Run 结束等待审批失败 [runId={}]: {}", childRunId, e.getMessage());
            }
        }
        if (parentCallback != null) {
            parentCallback.onSubtask(SubtaskUpdate.approvalResolved(parentRunId, childRunId,
                    subtaskId, approvalId, decision, source, batchId));
        }
    }

    @Override
    public void onUserQuestion(String questionId, String question, String header,
                               List<Map<String, Object>> options, boolean multiSelect) {
        if (runService != null) {
            try {
                runService.pauseForUserInput(childRunId, questionId, question, header, options, multiSelect);
            } catch (Exception e) {
                log.warn("子 Run 进入等待用户输入失败 [runId={}]: {}", childRunId, e.getMessage());
            }
        }
        if (parentCallback != null) {
            parentCallback.onSubtask(SubtaskUpdate.question(parentRunId, childRunId, subtaskId,
                    questionId, question, header, options, multiSelect));
        }
    }

    @Override
    public void onComplete() {
        // 子 Run 结束不关闭父 SSE，也不向父流发送 done
    }

    @Override
    public void onError(Throwable t) {
        // 异常由协调器按子 Run 终态处理，不能污染父 Run
    }

    private void appendEvent(AgentRunEvent.EventType type, Map<String, Object> payload) {
        if (runService == null || childRunId == null) {
            return;
        }
        try {
            runService.appendEvent(childRunId, type, payload);
        } catch (Exception e) {
            log.debug("写入子 Run 事件失败 [runId={}, type={}]: {}", childRunId, type, e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
