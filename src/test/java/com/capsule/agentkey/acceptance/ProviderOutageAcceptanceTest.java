package com.capsule.agentkey.acceptance;

import com.capsule.agentkey.client.AgentStreamCallback;
import com.capsule.agentkey.client.ProviderCircuitBreaker;
import com.capsule.agentkey.client.SpringAiAgentClient;
import com.capsule.agentkey.config.SpringAiChatModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C：Phase 4 出境验收——模型故障真实 HTTP 演练。
 *
 * <p>用本机 stub HTTP server 让真实的 Spring AI / WebClient 链路收到 500、429、
 * 连接拒绝、流式空闲超时与首片后卡死，验证退避重试、已输出不重试、
 * 熔断快速失败与 onError 只回调一次。
 */
class ProviderOutageAcceptanceTest {

    private final AtomicInteger requests = new AtomicInteger();

    @Test
    void 上游500按退避重试后失败且只回调一次错误() throws IOException {
        HttpServer server = jsonServer(500, "{\"error\":{\"message\":\"upstream boom\"}}");
        try {
            int port = server.getAddress().getPort();
            SpringAiAgentClient client = client(port, 3, 50L, 5L);
            RecordingCallback callback = new RecordingCallback();

            long start = System.nanoTime();
            SpringAiAgentClient.StreamResult result = stream(client, callback);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertNotNull(result.error(), "持续 500 最终应失败");
            assertTrue(requests.get() >= 3,
                    "应真实发起多次请求；实际 " + requests.get()
                            + " 次（Spring AI SDK 自身也带内部重试，会放大外层尝试）");
            assertEquals(1, callback.errors.get(), "onError 只允许回调一次");
            assertEquals(0, callback.chunks.get());
            assertTrue(elapsedMs >= 120, "应发生指数退避等待，实际 " + elapsedMs + "ms");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 四二九限流可重试() throws IOException {
        HttpServer server = jsonServer(429, "{\"error\":{\"message\":\"rate limit\"}}");
        try {
            SpringAiAgentClient client = client(server.getAddress().getPort(), 2, 10L, 5L);
            RecordingCallback callback = new RecordingCallback();

            SpringAiAgentClient.StreamResult result = stream(client, callback);

            assertNotNull(result.error());
            assertTrue(requests.get() >= 2,
                    "429 属于瞬时错误，应发生重试；实际 " + requests.get() + " 次");
            assertEquals(1, callback.errors.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 连接拒绝判为可重试() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        SpringAiAgentClient client = client(port, 2, 150L, 5L);
        RecordingCallback callback = new RecordingCallback();

        long start = System.nanoTime();
        SpringAiAgentClient.StreamResult result = stream(client, callback);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(result.error(), "断网/端口不可达应失败");
        assertEquals(1, callback.errors.get());
        assertTrue(elapsedMs >= 120, "连接失败应进入退避重试，实际 " + elapsedMs + "ms");
    }

    @Test
    void 流式空闲超时触发失败() throws IOException {
        HttpServer server = streamingServer(null, 3_000L);
        try {
            SpringAiAgentClient client = client(server.getAddress().getPort(), 2, 0L, 1L);
            RecordingCallback callback = new RecordingCallback();

            long start = System.nanoTime();
            SpringAiAgentClient.StreamResult result = stream(client, callback);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertNotNull(result.error(), "长时间无分片应触发空闲超时");
            assertTrue(requests.get() >= 2,
                    "空闲超时属于可重试，应发生重试；实际 " + requests.get() + " 次");
            assertEquals(1, callback.errors.get());
            assertTrue(elapsedMs < 20_000, "不应等到服务端 3 秒后才返回，实际 " + elapsedMs + "ms");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 已输出分片后空闲超时不重试() throws IOException {
        String firstChunk = "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},"
                + "\"finish_reason\":null}]}";
        HttpServer server = streamingServer(firstChunk, 3_000L);
        try {
            SpringAiAgentClient client = client(server.getAddress().getPort(), 3, 0L, 1L);
            RecordingCallback callback = new RecordingCallback();

            SpringAiAgentClient.StreamResult result = stream(client, callback);

            assertNotNull(result.error(), "首片后卡死最终应失败");
            assertTrue(result.sentChunk(), "应记录已经输出过分片");
            assertEquals(1, requests.get(), "已输出内容绝不重试");
            assertEquals(1, callback.chunks.get());
            assertEquals(1, callback.errors.get(), "只回调一次 onError");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 熔断打开后快速失败且不再发请求() throws IOException {
        HttpServer server = jsonServer(500, "{\"error\":{\"message\":\"down\"}}");
        try {
            SpringAiChatModelConfig config = config(server.getAddress().getPort(), 1, 0L, 5L);
            ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(2, 60_000L);
            SpringAiAgentClient client = new SpringAiAgentClient(config, breaker);

            RecordingCallback first = new RecordingCallback();
            assertNotNull(stream(client, first).error());
            RecordingCallback second = new RecordingCallback();
            assertNotNull(stream(client, second).error());
            assertTrue(breaker.isOpen(config.providerId()), "连续失败 2 次后熔断应打开");
            int requestsBeforeOpen = requests.get();
            assertTrue(requestsBeforeOpen >= 2, "熔断前应真实发起过请求");

            RecordingCallback third = new RecordingCallback();
            SpringAiAgentClient.StreamResult fastFail = stream(client, third);

            assertNotNull(fastFail.error());
            assertTrue(fastFail.error().getMessage().contains("熔断"),
                    "熔断后应快速失败并给出提示，实际: " + fastFail.error().getMessage());
            assertEquals(requestsBeforeOpen, requests.get(), "熔断窗口内不得再发起 HTTP 请求");
            assertEquals(1, third.errors.get());
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------

    private SpringAiAgentClient client(int port, int maxAttempts, long backoffMs, long idleSeconds) {
        return new SpringAiAgentClient(config(port, maxAttempts, backoffMs, idleSeconds),
                new ProviderCircuitBreaker(10, 30_000L));
    }

    private static SpringAiChatModelConfig config(int port, int maxAttempts, long backoffMs, long idleSeconds) {
        return new SpringAiChatModelConfig(
                "http://127.0.0.1:" + port,
                "/v1/chat/completions",
                "",
                maxAttempts,
                backoffMs,
                idleSeconds,
                5L,
                ObservationRegistry.create());
    }

    private static SpringAiAgentClient.StreamResult stream(SpringAiAgentClient client,
                                                           RecordingCallback callback) {
        return client.streamChat("sk-acceptance", "acceptance-model", messages(), List.<ToolCallback>of(),
                null, callback);
    }

    private static List<ObjectNode> messages() {
        ObjectNode message = new ObjectMapper().createObjectNode();
        message.put("role", "user");
        message.put("content", "hello");
        return List.of(message);
    }

    private HttpServer jsonServer(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    /** 先回 200 + text/event-stream；firstChunk 非空时先发一片，然后停住等待客户端超时。 */
    private HttpServer streamingServer(String firstChunk, long stallMs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                if (firstChunk != null) {
                    out.write(("data: " + firstChunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                Thread.sleep(stallMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignoredIo) {
                // 客户端空闲超时后会主动断开
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static final class RecordingCallback implements AgentStreamCallback {

        private final AtomicInteger chunks = new AtomicInteger();
        private final AtomicInteger errors = new AtomicInteger();
        private final AtomicReference<Throwable> lastError = new AtomicReference<>();

        @Override
        public void onChunk(String chunk) {
            chunks.incrementAndGet();
        }

        @Override
        public void onComplete() {
            // 验收只关心分片与错误
        }

        @Override
        public void onError(Throwable t) {
            errors.incrementAndGet();
            lastError.set(t);
        }
    }
}
