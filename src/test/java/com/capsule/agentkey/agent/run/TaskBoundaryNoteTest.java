package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.entity.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** R8a：任务边界提示只在上一次 Run 已停止/失败时注入。 */
class TaskBoundaryNoteTest {

    private List<ChatMessage> history() {
        ChatMessage user = new ChatMessage();
        user.setRole(ChatMessage.MessageRole.USER);
        user.setContent("把项目跑起来");
        return new ArrayList<>(List.of(user));
    }

    @Test
    void 上一轮被停止时追加system提示() {
        List<ChatMessage> merged = TaskBoundaryNote.appendIfUnfinished(history(), 1L, 2L, "CANCELLED");

        assertEquals(2, merged.size());
        ChatMessage note = merged.get(1);
        assertEquals(ChatMessage.MessageRole.SYSTEM, note.getRole());
        assertTrue(note.getContent().contains("不要自动继续上一轮任务"));
        assertEquals(1L, note.getUserId());
        assertEquals(2L, note.getSessionId());
    }

    @Test
    void 上一轮失败时追加system提示() {
        List<ChatMessage> merged = TaskBoundaryNote.appendIfUnfinished(history(), 1L, 2L, "FAILED");
        assertEquals(2, merged.size());
    }

    @Test
    void 上一轮成功或没有上一轮时原样返回() {
        List<ChatMessage> original = history();
        assertSame(original, TaskBoundaryNote.appendIfUnfinished(original, 1L, 2L, "SUCCEEDED"));
        assertSame(original, TaskBoundaryNote.appendIfUnfinished(original, 1L, 2L, null));
    }

    @Test
    void 历史为空时保持null() {
        assertNull(TaskBoundaryNote.appendIfUnfinished(null, 1L, 2L, "CANCELLED"));
    }
}
