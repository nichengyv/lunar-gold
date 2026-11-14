package com.capsule.agentkey.client;

import com.capsule.agentkey.config.SpringAiChatModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P4-06：模型调用韧性的纯单元测试，不发起真实网络请求。 */
class SpringAiAgentClientResilienceTest {

    private static SpringAiChatModelConfig config(String fallbacks, int maxAttempts, long backoffMs) {
        return new SpringAiChatModelConfig(
                "https://example.invalid",
                "/v1/chat/completions",
                fallbacks,
                maxAttempts,
                backoffMs,
                5L,
                5L,
                ObservationRegistry.create());
    }

    @Test
    void 瞬时错误可重试_配置类错误不重试() {
        assertTrue(SpringAiAgentClient.isRetryable(new TimeoutException("idle timeout")));
        assertTrue(SpringAiAgentClient.isRetryable(new IOException("Connection reset")));
        assertTrue(SpringAiAgentClient.isRetryable(new ConnectException("connect failed")));
        assertTrue(SpringAiAgentClient.isRetryable(
                new IllegalStateException("429 Too Many Requests")));
        assertTrue(SpringAiAgentClient.isRetryable(
                new RuntimeException("503 Service Unavailable")));

        assertFalse(SpringAiAgentClient.isRetryable(new IllegalStateException("401 Unauthorized")));
        assertFalse(SpringAiAgentClient.isRetryable(
                new IllegalArgumentException("400 Bad Request: model not found")));
        assertFalse(SpringAiAgentClient.isRetryable(null));
    }

    @Test
    void 嵌套原因里的限流错误也能识别() {
        Throwable nested = new RuntimeException("request failed",
                new IllegalStateException("upstream returned 430"));
        assertFalse(SpringAiAgentClient.isRetryable(nested),
                "430 不在白名单，不能误判为限流");

        Throwable rateLimited = new RuntimeException("request failed",
                new IllegalStateException("upstream returned 429"));
        assertTrue(SpringAiAgentClient.isRetryable(rateLimited));
    }

    @Test
    void fallback模型默认为空_显式配置后才启用() {
        assertTrue(config("", 3, 0).fallbackModelsFor("deepseek-chat").isEmpty());

        List<String> configured = config("deepseek-chat, deepseek-reasoner ,", 3, 0)
                .fallbackModelsFor("deepseek-chat");
        assertEquals(List.of("deepseek-reasoner"), configured);
    }

    @Test
    void 熔断打开时快速失败且只回调一次错误() {
        SpringAiChatModelConfig config = config("", 3, 0);
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(1, 60_000L);
        breaker.recordFailure(config.providerId());

        SpringAiAgentClient client = new SpringAiAgentClient(config, breaker);
        RecordingCallback callback = new RecordingCallback();

        SpringAiAgentClient.StreamResult result = client.streamChat(
                "sk-test", "deepseek-chat", List.of(userMessage()),
                List.of(), null, callback);

        assertNotNull(result.error());
        assertTrue(result.error().getMessage().contains("熔断"));
        assertFalse(result.sentChunk());
        assertEquals(1, callback.errors.get(), "上层只应收到一次 onError");
        assertEquals(0, callback.chunks.get());
    }

    private ObjectNode userMessage() {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("role", "user");
        node.put("content", "hi");
        return node;
    }

    private static final class RecordingCallback implements AgentStreamCallback {
        private final AtomicInteger chunks = new AtomicInteger();
        private final AtomicInteger errors = new AtomicInteger();
        private final List<String> received = new ArrayList<>();

        @Override
        public void onChunk(String chunk) {
            chunks.incrementAndGet();
            received.add(chunk);
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable t) {
            errors.incrementAndGet();
        }
    }
}
