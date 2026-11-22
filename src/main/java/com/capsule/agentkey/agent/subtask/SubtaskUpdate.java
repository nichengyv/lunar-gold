package com.capsule.agentkey.agent.subtask;

import java.util.List;
import java.util.Map;

/**
 * 子智能体状态更新（R7c）。
 *
 * <p>父 Run 的 SSE 只转发这一种轻量状态，不把子 Run 的原始 chunk/done/error 混进父流；
 * 子 Run 自己的完整事件仍写入 {@code agent_run_event}。审批与用户询问携带 childRunId，
 * 前端据此把卡片归属到正确的子任务，并保证父 Run 不被误置为等待审批/等待输入。
 */
public record SubtaskUpdate(String type,
                            Long parentRunId,
                            Long childRunId,
                            String subtaskId,
                            String status,
                            String message,
                            String approvalId,
                            String tool,
                            String questionId,
                            String question,
                            String header,
                            List<Map<String, Object>> options,
                            boolean multiSelect,
                            Object preview,
                            String risk,
                            long expiresAt,
                            String decision,
                            String source,
                            String batchId) {

    public static final String TYPE_STATUS = "status";
    public static final String TYPE_APPROVAL = "approval";
    public static final String TYPE_APPROVAL_RESOLVED = "approval_resolved";
    public static final String TYPE_QUESTION = "question";

    public static SubtaskUpdate status(Long parentRunId, Long childRunId, String subtaskId,
                                       String status, String message) {
        return new SubtaskUpdate(TYPE_STATUS, parentRunId, childRunId, subtaskId, status,
                message, null, null, null, null, null, null, false, null, null, 0L,
                null, null, null);
    }

    public static SubtaskUpdate approval(Long parentRunId, Long childRunId, String subtaskId,
                                         String approvalId, String tool, Object preview,
                                         String risk, long expiresAt) {
        return new SubtaskUpdate(TYPE_APPROVAL, parentRunId, childRunId, subtaskId, "WAITING_APPROVAL",
                "子任务需要审批：" + tool, approvalId, tool, null, null, null, null, false,
                preview, risk, expiresAt, null, null, null);
    }

    public static SubtaskUpdate approvalResolved(Long parentRunId, Long childRunId, String subtaskId,
                                                 String approvalId, String decision, String source,
                                                 String batchId) {
        return new SubtaskUpdate(TYPE_APPROVAL_RESOLVED, parentRunId, childRunId, subtaskId,
                "RUNNING", "子任务审批已处理：" + decision, approvalId, null, null, null, null,
                null, false, null, null, 0L, decision, source, batchId);
    }

    public static SubtaskUpdate question(Long parentRunId, Long childRunId, String subtaskId,
                                         String questionId, String question, String header,
                                         List<Map<String, Object>> options, boolean multiSelect) {
        return new SubtaskUpdate(TYPE_QUESTION, parentRunId, childRunId, subtaskId, "WAITING_USER_INPUT",
                "子任务需要用户回答", null, null, questionId, question, header, options, multiSelect,
                null, null, 0L, null, null, null);
    }
}
