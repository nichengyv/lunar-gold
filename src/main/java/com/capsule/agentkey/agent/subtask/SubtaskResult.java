package com.capsule.agentkey.agent.subtask;

/**
 * 单个子任务的执行结果（R7c）。
 *
 * <p>只有 {@link #SUCCEEDED} 代表子 Run 的验收契约已由证据核对通过；
 * 失败、取消与未启动都必须原样回传给父 Run，不能包装成成功。
 */
public record SubtaskResult(String subtaskId,
                            Long childRunId,
                            String status,
                            String reason,
                            String message) {

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SKIPPED = "SKIPPED";

    public boolean accepted() {
        return SUCCEEDED.equals(status);
    }
}
