package com.capsule.agentkey.tool;

import com.capsule.agentkey.service.ReActAgentService.StreamCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * P7-06：ask_user_question —— 模型可调用的"停下来问用户" tool。
 *
 * <p>用法示例：模型在执行中发现需要用户选择方向或补充信息时调用：</p>
 * <pre>
 * ask_user_question({
 *   "questions": [{
 *     "id": "scope_choice",
 *     "header": "改动范围",
 *     "question": "这次改接口还是加 wrapper？",
 *     "options": [
 *       { "label": "改接口定义 (Recommended)", "description": "同步更新所有调用方" },
 *       { "label": "加 wrapper",              "description": "不侵入现有接口" }
 *     ],
 *     "multi_select": false
 *   }]
 * })
 * </pre>
 *
 * <p>tool 调用会：</p>
 * <ol>
 *   <li>通过 {@link StreamCallback#onUserQuestion} 推 SSE 事件，前端弹选项卡</li>
 *   <li>挂起 ReAct 循环，等待用户回答</li>
 *   <li>用户通过 {@code POST /api/agent/runs/{runId}/answer} 回答后恢复</li>
 * </ol>
 *
 * <p>设计约束（和 DeepSeek Harness 同方向）：</p>
 * <ul>
 *   <li>不支持子 agent 调用——只由 root agent 使用</li>
 *   <li>等待上限 {@value #MAX_WAIT_SECONDS} 秒（超时返回错误让模型自行决策），等待期间按 500ms 分片响应取消</li>
 *   <li>一次只能问一个 question（简化实现）</li>
 * </ul>
 */
@Component
public final class AskUserQuestionTool {

    /** 最大等待时长 15 分钟——超过后返回超时错误，让模型自己决定下一步 */
    private static final long MAX_WAIT_SECONDS = 15 * 60L;

    /** 分片等待粒度：每 500ms 检查一次取消标记，保证 /chat/stop 能被及时响应 */
    private static final long WAIT_SLICE_MS = 500L;

    private final Map<Long, Pending> pendingByRunId = new ConcurrentHashMap<>();

    /**
     * 执行 ask_user_question tool。
     *
     * @param runId           当前 Run id（用于 Controller 回传回答）
     * @param questionId      稳定 id，用户回答时回传
     * @param question        问题文本
     * @param header          可选小标题
     * @param options         可选选项列表 [{"label": "...", "description": "..."}]
     * @param multiSelect     是否允许多选
     * @param callback        用于推 SSE 事件
     * @param cancelRequested 外部取消信号（Run 停止时触发）
     * @return 用户回答的 JSON：{ "answers": [{ "id": "...", "selected": [...], "custom": "..." }] }
     */
    public String ask(Long runId, String questionId, String question, String header,
                      List<Map<String, Object>> options, boolean multiSelect,
                      StreamCallback callback, java.util.function.BooleanSupplier cancelRequested) {
        // 1. 注册 pending question
        CompletableFuture<UserAnswer> future = new CompletableFuture<>();
        pendingByRunId.put(runId, new Pending(questionId, future));

        // 2. 推 SSE 事件（触发 WAITING_USER_INPUT 状态迁移 + event:user_question）
        callback.onUserQuestion(questionId, question, header,
                options == null ? List.of() : List.copyOf(options), multiSelect);

        try {
            // 3. 等待用户回答：分片等待，保证 /chat/stop 置位取消标记后 500ms 内就能退出，
            //    而不是被阻塞到 15 分钟超时。总上限仍是 MAX_WAIT_SECONDS。
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_WAIT_SECONDS);
            UserAnswer answer;
            while (true) {
                if (cancelRequested.getAsBoolean()) {
                    pendingByRunId.remove(runId);
                    return "{ \"error\": \"CANCELLED\", "
                            + "\"message\": \"Run 已被用户停止，放弃本次等待\" }";
                }
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    pendingByRunId.remove(runId);
                    return "{ \"error\": \"USER_INPUT_TIMEOUT\", "
                            + "\"message\": \"等待用户输入超过 " + MAX_WAIT_SECONDS
                            + " 秒，请改用不需要用户确认的稳妥方案继续\" }";
                }
                try {
                    answer = future.get(Math.min(WAIT_SLICE_MS, remainingMs), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException sliceTimeout) {
                    // 继续下一片，检查取消标记与总超时
                }
            }

            // 4. 正常返回——按 DHS 格式返回：{ "answers": [{ "id": "...", "selected": [...], "custom": "..." }] }
            StringBuilder sb = new StringBuilder();
            sb.append("{ \"answers\": [{ ");
            sb.append("\"id\": \"").append(escapeJson(answer.questionId)).append("\", ");
            sb.append("\"selected\": [");
            if (answer.selected != null) {
                for (int i = 0; i < answer.selected.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"").append(escapeJson(answer.selected.get(i))).append("\"");
                }
            }
            sb.append("]");
            if (answer.custom != null && !answer.custom.isBlank()) {
                sb.append(", \"custom\": \"").append(escapeJson(answer.custom)).append("\"");
            }
            sb.append(" }] }");
            return sb.toString();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{ \"error\": \"INTERRUPTED\", \"message\": \"等待用户输入时线程被中断\" }";
        } catch (java.util.concurrent.CancellationException e) {
            return "{ \"error\": \"CANCELLED\", \"message\": \"Run 已被取消，放弃本次等待\" }";
        } catch (Exception e) {
            return "{ \"error\": \"INTERNAL_ERROR\", \"message\": \"" + escapeJson(e.getMessage()) + "\" }";
        } finally {
            pendingByRunId.remove(runId);
        }
    }

    /**
     * Controller 端点调用：用户回答了某个 Run 的等待问题。
     */
    public boolean resolve(Long runId, UserAnswer answer) {
        Pending pending = pendingByRunId.get(runId);
        if (pending == null || !pending.questionId.equals(answer.questionId)) {
            return false;
        }
        pending.future.complete(answer);
        return true;
    }

    /** Run 被停止时清理 pending。 */
    public void cleanup(Long runId) {
        Pending p = pendingByRunId.remove(runId);
        if (p != null) {
            p.future.cancel(true);
        }
    }

    // ---------------------------------------------------------------
    // 内部数据结构
    // ---------------------------------------------------------------

    public record UserAnswer(String questionId, List<String> selected, String custom) {}

    private record Pending(String questionId, CompletableFuture<UserAnswer> future) {}

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
