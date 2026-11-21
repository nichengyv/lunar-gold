package com.capsule.agentkey.agent.stream;

import com.capsule.agentkey.client.AgentClient;
import com.capsule.agentkey.client.AgentStreamCallback;
import com.capsule.agentkey.exception.TokenBudgetExceededException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R4：流式调用聚合契约。
 *
 * <p>锁定「chunk/usage 原样转发、tool_calls 聚合、错误与超预算在流结束后抛出」，
 * 保证把两处重复的 streamChat 收进 StreamPublisher 后行为不变。
 */
class StreamPublisherTest {

    private final StreamPublisher publisher = new StreamPublisher();

    @Test
    void 转发分片与用量并聚合工具调用() {
        List<String> chunks = new ArrayList<>();
        List<long[]> usages = new ArrayList<>();
        ObjectNode call = toolCall("read_file", "{\"path\":\"a.txt\"}");

        AgentClient client = client(cb -> {
            cb.onChunk("he");
            cb.onChunk("llo");
            cb.onUsage(10, 5);
            cb.onToolCalls(List.of(call));
            cb.onUsage(3, 2);
        });

        StreamPublisher.Turn turn = publisher.stream(client, "key", "model", List.of(), List.of(), null,
                chunks::add, (p, c) -> usages.add(new long[]{p, c}),
                new AtomicLong(), new AtomicLong(), 0);

        assertEquals("hello", turn.text());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("read_file", turn.toolCalls().get(0).path("function").path("name").asText());
        assertEquals(List.of("he", "llo"), chunks);
        assertEquals(2, usages.size());
        assertEquals(13, turn.promptTokens());
        assertEquals(7, turn.completionTokens());
    }

    @Test
    void 超过token预算时抛出专用异常() {
        AgentClient client = client(cb -> cb.onUsage(100, 50));

        TokenBudgetExceededException error = assertThrows(TokenBudgetExceededException.class,
                () -> publisher.stream(client, "key", "model", List.of(), List.of(), null,
                        chunk -> { }, (p, c) -> { },
                        new AtomicLong(), new AtomicLong(), 10));

        assertEquals(150, error.getUsedTokens());
        assertEquals(10, error.getLimitTokens());
    }

    @Test
    void 流式错误在结束后以运行时异常抛出() {
        AgentClient client = client(cb -> cb.onError(new IllegalStateException("boom")));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> publisher.stream(client, "key", "model", List.of(), List.of(), null,
                        chunk -> { }, (p, c) -> { },
                        new AtomicLong(), new AtomicLong(), 0));

        assertEquals("boom", error.getCause().getMessage());
    }

    private AgentClient client(java.util.function.Consumer<AgentStreamCallback> script) {
        return new AgentClient() {
            @Override
            public StreamResult streamChat(String apiKey, String model, List<ObjectNode> messages,
                                           List<org.springframework.ai.tool.ToolCallback> toolCallbacks,
                                           String memorySummary, AgentStreamCallback callback) {
                script.accept(callback);
                return new StreamResult(null, true);
            }

            @Override
            public String callSync(String apiKey, String model, List<ObjectNode> messages) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String callVision(String apiKey, String model, String instruction,
                                     List<String> imageDataUrls) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private ObjectNode toolCall(String name, String arguments) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ObjectNode function = node.putObject("function");
        function.put("name", name);
        function.put("arguments", arguments);
        return node;
    }
}
