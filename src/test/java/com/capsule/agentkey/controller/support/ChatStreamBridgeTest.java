package com.capsule.agentkey.controller.support;

import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.service.SessionMemoryExtractionService;
import com.capsule.agentkey.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6-06：断流只停止推送，不得丢弃回答、改变终态或跳过事件落库。
 */
class ChatStreamBridgeTest {

    private final ChatService chatService = mock(ChatService.class);
    private final SessionMemoryExtractionService memoryService = mock(SessionMemoryExtractionService.class);
    private final AgentRunService agentRunService = mock(AgentRunService.class);

    private ChatStreamBridge newBridge(ChatSseChannel channel) {
        return new ChatStreamBridge(channel, 99L, "turn-1", 10L, 1L, mock(AgentPolicy.class),
                "test-key", chatService, memoryService, agentRunService, new ObjectMapper(), 100_000L, 600L);
    }

    @Test
    void 断流后完成仍保存回答并标记成功() throws Exception {
        ChatSseChannel channel = ChatSseChannel.open(1L);
        channel.detached().set(true);
        try {
            ChatStreamBridge bridge = newBridge(channel);
            bridge.beginTurn();
            bridge.onChunk("断流后仍在生成的回答");
            bridge.onCompletion(new ReActAgentService.Completion(
                    ReActAgentService.CompletionStatus.COMPLETED, "NO_VERIFICATION_REQUIRED", "完成", false));
            bridge.onComplete();

            verify(chatService).saveMessage(eq(1L), eq(10L), eq(ChatMessage.MessageRole.ASSISTANT),
                    eq("断流后仍在生成的回答"), isNull(), anyString());
            verify(agentRunService).succeedIfActive(eq(99L), contains("\"clientDetached\":true"));
            verify(agentRunService, never()).cancelIfActive(anyLong(), anyString());
            verify(agentRunService).appendEvent(eq(99L), eq(AgentRunEvent.EventType.COMPLETION), anyMap());
        } finally {
            channel.emitter().complete();
        }
    }

    @Test
    void 显式停止仍标记取消且保留已生成内容() throws Exception {
        ChatSseChannel channel = ChatSseChannel.open(1L);
        try {
            ChatStreamBridge bridge = newBridge(channel);
            bridge.beginTurn();
            bridge.onChunk("停止前的回答");
            bridge.onCompletion(new ReActAgentService.Completion(
                    ReActAgentService.CompletionStatus.INCOMPLETE, "CANCELLED", "本轮执行已取消", false));
            bridge.onComplete();

            verify(chatService).saveMessage(eq(1L), eq(10L), eq(ChatMessage.MessageRole.ASSISTANT),
                    eq("停止前的回答"), isNull(), anyString());
            verify(agentRunService).cancelIfActive(99L, "agent cancelled");
            verify(agentRunService, never()).succeedIfActive(anyLong(), any());
        } finally {
            channel.emitter().complete();
        }
    }

    @Test
    void 断流后工具事件仍落库() {
        ChatSseChannel channel = ChatSseChannel.open(1L);
        channel.detached().set(true);
        try {
            ChatStreamBridge bridge = newBridge(channel);
            bridge.onToolCall("write_file", "{\"path\":\"a.txt\"}");
            bridge.onToolResult("write_file", ToolResult.ok("ok", 12L));

            verify(agentRunService).appendEvent(eq(99L), eq(AgentRunEvent.EventType.TOOL_CALL), anyMap());
            verify(agentRunService).appendEvent(eq(99L), eq(AgentRunEvent.EventType.TOOL_RESULT), anyMap());
        } finally {
            channel.emitter().complete();
        }
    }

    @Test
    void 断流后审批结果仍推动Run恢复() {
        ChatSseChannel channel = ChatSseChannel.open(1L);
        channel.detached().set(true);
        try {
            ChatStreamBridge bridge = newBridge(channel);

            assertDoesNotThrow(() -> bridge.onApprovalResolved("ap-1", "REJECT", "USER", null));

            verify(agentRunService).resumeAfterApproval(99L, "REJECT", "USER", null);
        } finally {
            channel.emitter().complete();
        }
    }
}
