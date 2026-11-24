package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.entity.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * R8a：把「上一轮已停止/失败」显式写进模型上下文。
 *
 * <p>停止只改 {@code agent_run.state} 时，模型看到的仍是一个未完成任务；
 * 这里补一条 system 段，明确告知不要自动续跑。只作用于本轮模型输入，
 * 不落库、不在前端展示。
 */
public final class TaskBoundaryNote {

    public static final String CONTENT = "【上一轮状态】上一轮任务已被用户停止或执行失败，任务未完成。"
            + "不要自动继续上一轮任务；只回答用户最新的消息。";

    private TaskBoundaryNote() {
    }

    /**
     * @param previousState 会话最近一条终态 Run 的状态名；null 或已成功时原样返回
     */
    public static List<ChatMessage> appendIfUnfinished(List<ChatMessage> history,
                                                       Long userId,
                                                       Long sessionId,
                                                       String previousState) {
        if (history == null) {
            return null;
        }
        if (!"CANCELLED".equals(previousState) && !"FAILED".equals(previousState)) {
            return history;
        }
        ChatMessage note = new ChatMessage();
        note.setUserId(userId);
        note.setSessionId(sessionId);
        note.setRole(ChatMessage.MessageRole.SYSTEM);
        note.setContent(CONTENT);
        List<ChatMessage> merged = new ArrayList<>(history);
        merged.add(note);
        return merged;
    }
}
