package com.capsule.agentkey.acceptance;

import com.capsule.agentkey.controller.AgentChatController;
import com.capsule.agentkey.controller.support.ChatSseChannel;
import com.capsule.agentkey.controller.support.ChatStreamBridge;
import com.capsule.agentkey.dto.ApprovalPreview;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.AgentRunEvent.EventType;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.AgentPolicyResolver;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ApiKeyService;
import com.capsule.agentkey.service.ChatAttachmentService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.ContextSummaryService;
import com.capsule.agentkey.service.GenerationRegistry;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.PromptCatalog;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.service.SessionMemoryExtractionService;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.capsule.agentkey.service.ToolApprovalRegistry.Decision;
import com.capsule.agentkey.service.VisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6-06：真实 HTTP / Tomcat 上的 SSE 断流验收（补齐 checklist 偏差 1 / 2 / 3）。
 *
 * <p>与前一批的通道单测不同，这里启动真实 Tomcat + DispatcherServlet + 真实
 * {@link AgentChatController}、{@link ChatStreamBridge} 与 {@link ChatSseChannel}，
 * 用裸 Socket 读完首个事件后以 RST 断开（{@code SO_LINGER=0}），
 * 让服务端下一次写出必然失败，从而验证：
 * <ol>
 *   <li>断流只停止推送，Run 继续执行并成功落库，助手消息与事件都不丢；</li>
 *   <li>断流期间审批保持 PENDING，恢复后可继续批准并完成；</li>
 *   <li>断流后 /chat/stop 仍能显式终止 Run。</li>
 * </ol>
 * 模型层用 Mockito 替身，不触网；事件序号与用量走真实 {@link AgentRunService}。
 */
class RunDisconnectHttpAcceptanceTest {

    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 10L;
    private static final long SESSION_ID = 55L;

    /** @EnableWebMvc 配置在容器里由 Spring 实例化，控制器实例通过静态引用传入 */
    private static final AtomicReference<AgentChatController> CONTROLLER = new AtomicReference<>();

    @TempDir
    Path tempDir;

    private final Map<Long, AgentRun> runs = new ConcurrentHashMap<>();
    private final List<AgentRunEvent> savedEvents = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong runIdSeq = new AtomicLong(900);

    private Tomcat tomcat;
    private AnnotationConfigWebApplicationContext webContext;
    private ExecutorService agentExecutor;
    private int port;

    private ChatService chatService;
    private ReActAgentService agentService;
    private ToolApprovalRegistry approvals;

    @BeforeEach
    void setUp() throws Exception {
        runs.clear();
        savedEvents.clear();
        agentExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "p6-06-acceptance-agent");
            t.setDaemon(true);
            return t;
        });
        buildController();
        startServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        if (agentExecutor != null) {
            agentExecutor.shutdownNow();
        }
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
        if (webContext != null) {
            webContext.close();
        }
    }

    // ------------------------------------------------------------------
    // 1. 断流后 Run 继续并成功
    // ------------------------------------------------------------------

    @Test
    void 真实HTTP断流后Run继续完成且事件与回答完整() throws Exception {
        int chunks = 60;
        CountDownLatch finished = stubAgent((callback, cancel) -> {
            for (int i = 0; i < chunks; i++) {
                callback.onChunk("片段" + i + ";");
                Thread.sleep(40L);
            }
            callback.onUsage(7, 11);
            callback.onCompletion(new ReActAgentService.Completion(
                    ReActAgentService.CompletionStatus.COMPLETED, "completed", "完成", false));
            callback.onComplete();
        });

        String sse = streamAndAbort("event:content");
        assertTrue(sse.contains("text/event-stream"), "响应必须是 SSE：" + sse);

        assertTrue(finished.await(20, TimeUnit.SECONDS), "断流后 Run 仍在 20s 内没有跑完");
        AgentRun run = onlyRun();
        assertEquals(RunState.SUCCEEDED, run.getState(), "断流不能把 Run 置为取消或失败");
        assertNotNull(run.getResultJson());
        assertTrue(run.getResultJson().contains("\"clientDetached\":true"),
                "服务端应记录断流标记，实际: " + run.getResultJson());
        assertEquals(7, run.getPromptTokens(), "断流后用量仍应落库");
        assertEquals(11, run.getCompletionTokens());

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(chatService).saveMessage(eq(USER_ID), eq(SESSION_ID),
                eq(ChatMessage.MessageRole.ASSISTANT), content.capture(), isNull(), anyString());
        assertTrue(content.getValue().startsWith("片段0;"), "回答开头丢失");
        assertTrue(content.getValue().contains("片段" + (chunks - 1) + ";"), "回答结尾丢失");

        List<AgentRunEvent> events = eventsOf(run.getId());
        assertFalse(events.stream().anyMatch(e -> e.getEventType() == EventType.CANCELED),
                "断流不得产生 CANCELED 事件");
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == EventType.MODEL_CHUNK),
                "断流期间应继续写正文检查点");
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == EventType.COMPLETION),
                "断流后 completion 事件必须落库");
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1, events.get(i).getSeq(), "run_events.seq 必须连续");
        }
    }

    // ------------------------------------------------------------------
    // 1b. 心跳失败发现断流（断流后长时间没有任何业务写出）
    // ------------------------------------------------------------------

    @Test
    void 心跳失败只标记断流且Run继续完成() throws Exception {
        Logger channelLogger = (Logger) LoggerFactory.getLogger(ChatSseChannel.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        channelLogger.addAppender(appender);
        try {
            CountDownLatch finished = stubAgent((callback, cancel) -> {
                callback.onChunk("开始");
                // 断流后 17s 不再写事件：期间唯一可能的写出是 15s 周期的心跳
                Thread.sleep(17_000L);
                callback.onCompletion(new ReActAgentService.Completion(
                        ReActAgentService.CompletionStatus.COMPLETED, "completed", "完成", false));
                callback.onComplete();
            });

            streamAndAbort("event:content");

            assertTrue(finished.await(40, TimeUnit.SECONDS), "心跳失败后 Run 未完成");
            AgentRun run = onlyRun();
            assertEquals(RunState.SUCCEEDED, run.getState());
            assertTrue(run.getResultJson().contains("\"clientDetached\":true"),
                    "心跳失败应把连接标记为断流，实际: " + run.getResultJson());
            assertTrue(appender.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(message -> message.contains("心跳发送失败")),
                    "断流应由心跳失败发现，而不是业务事件写出失败：" + appender.list.stream()
                            .map(ILoggingEvent::getFormattedMessage).toList());
        } finally {
            channelLogger.detachAppender(appender);
        }
    }

    // ------------------------------------------------------------------
    // 2. 断流期间审批保持 PENDING，恢复后可批准
    // ------------------------------------------------------------------

    @Test
    void 断流期间审批保持PENDING且恢复后仍可批准完成() throws Exception {
        CompletableFuture<String> approvalId = new CompletableFuture<>();
        CompletableFuture<ToolApprovalRegistry.Pending> pendingRef = new CompletableFuture<>();
        CountDownLatch finished = stubAgent((callback, cancel) -> {
            callback.onChunk("准备写入");
            ToolApprovalRegistry.Pending pending = approvals.create(
                    USER_ID, SESSION_ID, "write_file", "{}", "WRITE");
            pendingRef.complete(pending);
            approvalId.complete(pending.approvalId());
            callback.onApprovalRequired(pending.approvalId(), "write_file",
                    new ApprovalPreview("write", "note.txt", null, null, 1, 0, true, null),
                    "WRITE", pending.expiresAt());
            // 等客户端完成 RST 后再写一次，让容器在此刻暴露断开；
            // 之后的审批等待与批准都发生在「服务端已认定断流」之后。
            Thread.sleep(300L);
            callback.onThinking("等待审批");
            ToolApprovalRegistry.Resolution resolution = approvals.await(pending, cancel);
            callback.onApprovalResolved(pending.approvalId(),
                    resolution.decision().name(), resolution.source().name(), null);
            if (resolution.decision() == Decision.APPROVE) {
                callback.onChunk("已批准继续");
                callback.onCompletion(new ReActAgentService.Completion(
                        ReActAgentService.CompletionStatus.COMPLETED, "completed", "完成", true));
            } else {
                callback.onCompletion(new ReActAgentService.Completion(
                        ReActAgentService.CompletionStatus.INCOMPLETE, "CANCELLED", "已停止", false));
            }
            callback.onComplete();
        });

        String sse = streamAndAbort("event:approval_required");
        assertTrue(sse.contains("approval_required"), "断流前应收到审批事件：" + sse);

        String id = approvalId.get(5, TimeUnit.SECONDS);
        ToolApprovalRegistry.Pending pending = pendingRef.get(5, TimeUnit.SECONDS);
        // 断流后多等一会：审批不能被当成断流而自动拒绝或超时
        Thread.sleep(1_500L);
        assertFalse(pending.future().isDone(), "断流期间审批必须保持 PENDING，等待用户决策");

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/agent/approvals/" + id))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"decision\":\"APPROVE\",\"remember\":\"NONE\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "恢复后批准应成功：" + response.body());

        assertTrue(finished.await(20, TimeUnit.SECONDS), "批准后 Run 未完成");
        AgentRun run = onlyRun();
        assertEquals(RunState.SUCCEEDED, run.getState());
        assertEquals(Decision.APPROVE, pending.future().getNow(null).decision());

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(chatService).saveMessage(eq(USER_ID), eq(SESSION_ID),
                eq(ChatMessage.MessageRole.ASSISTANT), content.capture(), isNull(), anyString());
        assertTrue(content.getValue().contains("已批准继续"), "批准后的后续输出应保存");
    }

    // ------------------------------------------------------------------
    // 2b. 断流期间拒绝审批，Run 继续收尾
    // ------------------------------------------------------------------

    @Test
    void 断流期间拒绝审批后Run继续收尾() throws Exception {
        CompletableFuture<String> approvalId = new CompletableFuture<>();
        CompletableFuture<ToolApprovalRegistry.Resolution> resolved = new CompletableFuture<>();
        CountDownLatch finished = stubAgent((callback, cancel) -> {
            callback.onChunk("准备写入");
            ToolApprovalRegistry.Pending pending = approvals.create(
                    USER_ID, SESSION_ID, "write_file", "{}", "WRITE");
            approvalId.complete(pending.approvalId());
            callback.onApprovalRequired(pending.approvalId(), "write_file",
                    new ApprovalPreview("write", "note.txt", null, null, 1, 0, true, null),
                    "WRITE", pending.expiresAt());
            Thread.sleep(300L);
            callback.onThinking("等待审批");
            ToolApprovalRegistry.Resolution resolution = approvals.await(pending, cancel);
            resolved.complete(resolution);
            callback.onApprovalResolved(pending.approvalId(),
                    resolution.decision().name(), resolution.source().name(), null);
            if (resolution.decision() == Decision.REJECT) {
                callback.onChunk("已拒绝写入");
            }
            callback.onCompletion(new ReActAgentService.Completion(
                    ReActAgentService.CompletionStatus.COMPLETED, "completed", "完成", true));
            callback.onComplete();
        });

        String sse = streamAndAbort("event:approval_required");
        assertTrue(sse.contains("approval_required"), "断流前应收到审批事件：" + sse);

        String id = approvalId.get(5, TimeUnit.SECONDS);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/agent/approvals/" + id))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"decision\":\"REJECT\",\"remember\":\"NONE\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "断流后拒绝应成功：" + response.body());

        ToolApprovalRegistry.Resolution resolution = resolved.get(10, TimeUnit.SECONDS);
        assertEquals(Decision.REJECT, resolution.decision());
        assertEquals(ToolApprovalRegistry.DecisionSource.USER, resolution.source());
        assertTrue(finished.await(20, TimeUnit.SECONDS), "拒绝后 Run 未收尾");
        assertEquals(RunState.SUCCEEDED, onlyRun().getState(), "拒绝不应把 Run 变成取消或失败");
    }

    // ------------------------------------------------------------------
    // 3. 断流后显式停止仍然生效
    // ------------------------------------------------------------------

    @Test
    void 断流后显式停止仍能终止Run() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch finished = stubAgent((callback, cancel) -> {
            for (int i = 0; i < 300; i++) {
                if (cancel.get()) {
                    cancelled.countDown();
                    break;
                }
                callback.onChunk("长任务" + i + ";");
                Thread.sleep(50L);
            }
            if (cancel.get()) {
                callback.onCompletion(new ReActAgentService.Completion(
                        ReActAgentService.CompletionStatus.INCOMPLETE, "CANCELLED", "用户停止", false));
            } else {
                callback.onCompletion(new ReActAgentService.Completion(
                        ReActAgentService.CompletionStatus.COMPLETED, "completed", "完成", false));
            }
            callback.onComplete();
        });

        streamAndAbort("event:content");

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/agent/chat/stop?sessionId=" + SESSION_ID))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "断流后停止接口应可用：" + response.body());

        assertTrue(cancelled.await(10, TimeUnit.SECONDS), "断流后停止标记没有传到执行线程");
        assertTrue(finished.await(20, TimeUnit.SECONDS), "停止后 Run 未结束");
        AgentRun run = onlyRun();
        assertEquals(RunState.CANCELLED, run.getState(), "只有显式停止才应产生取消终态");
        assertTrue(eventsOf(run.getId()).stream().anyMatch(e -> e.getEventType() == EventType.CANCELED),
                "取消终态应留下 CANCELED 事件");
    }

    // ------------------------------------------------------------------
    // 测试脚手架
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface AgentBehaviour {
        void run(ReActAgentService.StreamCallback callback, AtomicBoolean cancel) throws Exception;
    }

    /** 用替身模型替换 ReAct 循环；返回「执行线程收尾完成」信号 */
    private CountDownLatch stubAgent(AgentBehaviour behaviour) {
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            // P7-06：execute 带 runId 的 16 参重载——callback 在 15、cancel 在 14
            ReActAgentService.StreamCallback callback = invocation.getArgument(15);
            AtomicBoolean cancel = invocation.getArgument(14);
            try {
                behaviour.run(callback, cancel);
            } finally {
                finished.countDown();
            }
            return null;
        }).when(agentService).execute(anyString(), any(AgentPolicy.class), anyString(), anyList(),
                anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyBoolean(), anyString(), anyString(),
                anyString(), anyString(), any(AtomicBoolean.class),
                any(ReActAgentService.StreamCallback.class));
        return finished;
    }

    /**
     * 用裸 Socket 发起 SSE 请求，读到指定事件后以 RST 断开。
     * 用 {@code SO_LINGER=0} 是为了让内核立即发 RST，服务端下一次写出必然失败，
     * 从而真实触发 {@link com.capsule.agentkey.controller.support.ChatSseChannel} 的断流分支。
     */
    private String streamAndAbort(String marker) throws Exception {
        String body = "{\"prompt\":\"断流验收\",\"sessionId\":" + SESSION_ID + "}";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(20_000);
            String head = "POST /api/agent/chat/stream HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Accept: text/event-stream\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();

            String text = readUntil(socket.getInputStream(), marker);
            assertTrue(text.contains(marker), "20s 内未收到事件 " + marker + "：" + text);
            // 模拟客户端异常断开：不发 FIN，直接 RST
            socket.setSoLinger(true, 0);
            return text;
        }
    }

    private static String readUntil(InputStream in, String marker) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        while (buffer.size() < 64_000) {
            int n = in.read(chunk);
            if (n < 0) {
                break;
            }
            buffer.write(chunk, 0, n);
            if (buffer.toString(StandardCharsets.UTF_8).contains(marker)) {
                break;
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void buildController() {
        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        AgentRunEventRepository eventRepo = mock(AgentRunEventRepository.class);
        when(runRepo.save(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(runIdSeq.incrementAndGet());
            }
            runs.put(run.getId(), run);
            return run;
        });
        when(runRepo.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(runs.get(invocation.<Long>getArgument(0))));
        // R1：追加事件前会先锁 Run 行；mock 必须模拟“行存在”
        when(runRepo.lockForEventAppend(any())).thenAnswer(invocation ->
                Optional.ofNullable(runs.get(invocation.<Long>getArgument(0))));
        when(eventRepo.findMaxSeqByRunId(any())).thenAnswer(invocation -> {
            Long runId = invocation.getArgument(0);
            return eventsOf(runId).stream().map(AgentRunEvent::getSeq)
                    .max(Comparator.naturalOrder()).orElse(null);
        });
        when(eventRepo.save(any(AgentRunEvent.class))).thenAnswer(invocation -> {
            AgentRunEvent event = invocation.getArgument(0);
            savedEvents.add(event);
            return event;
        });
        when(eventRepo.findByRunIdOrderBySeqAsc(any())).thenAnswer(invocation ->
                eventsOf(invocation.<Long>getArgument(0)));

        chatService = mock(ChatService.class);
        when(chatService.getOwnedSession(eq(SESSION_ID), eq(USER_ID))).thenReturn(session());
        when(chatService.getHistory(eq(SESSION_ID), eq(USER_ID))).thenReturn(List.of());
        when(chatService.resolveReferencedSessionContext(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("");

        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        try {
            when(apiKeyService.getDecryptedKey(USER_ID, "DEEPSEEK")).thenReturn("test-key");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        ProjectService projectService = mock(ProjectService.class);
        when(projectService.resolveProjectId(USER_ID, null)).thenReturn(PROJECT_ID);
        when(projectService.getOwned(USER_ID, PROJECT_ID)).thenReturn(project());

        AgentPolicyResolver policyResolver = mock(AgentPolicyResolver.class);
        when(policyResolver.resolve(any(), any(), any())).thenReturn(policy());

        ContextSummaryService contextSummaryService = mock(ContextSummaryService.class);
        when(contextSummaryService.refresh(any(), any(), any(), any())).thenReturn("");

        agentService = mock(ReActAgentService.class);
        approvals = new ToolApprovalRegistry();

        AgentChatController controller = new AgentChatController(
                apiKeyService, agentService, chatService, mock(ChatAttachmentService.class),
                projectService, new GenerationRegistry(), mock(VisionService.class),
                contextSummaryService, policyResolver, approvals,
                mock(SessionMemoryExtractionService.class),
                new AgentRunService(runRepo, eventRepo, new ObjectMapper(), new com.capsule.agentkey.agent.run.RunEventStore(runRepo, eventRepo, new ObjectMapper())),
                new com.capsule.agentkey.tool.AskUserQuestionTool(),
                new PromptCatalog(new MockEnvironment()), agentExecutor);
        CONTROLLER.set(controller);
    }

    private void startServer() throws Exception {
        Tomcat server = new Tomcat();
        server.setBaseDir(Files.createDirectories(tempDir.resolve("tomcat")).toString());
        server.setPort(0);
        server.getConnector().setProperty("address", "127.0.0.1");
        Context context = server.addContext("",
                Files.createDirectories(tempDir.resolve("webapp")).toString());
        context.setParentClassLoader(getClass().getClassLoader());

        FilterDef auth = new FilterDef();
        auth.setFilterName("p606-auth");
        auth.setFilter(new FixedUserFilter());
        auth.setAsyncSupported("true");
        context.addFilterDef(auth);
        FilterMap authMapping = new FilterMap();
        authMapping.setFilterName("p606-auth");
        authMapping.addURLPattern("/*");
        context.addFilterMap(authMapping);

        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.register(TestWebConfig.class);
        Wrapper wrapper = Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(ctx));
        wrapper.setAsyncSupported(true);
        wrapper.setLoadOnStartup(1);
        context.addServletMappingDecoded("/*", "dispatcher");

        webContext = ctx;
        tomcat = server;
        server.start();
        port = server.getConnector().getLocalPort();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private AgentRun onlyRun() {
        assertEquals(1, runs.size(), "每次请求应只产生一个 Run");
        return runs.values().iterator().next();
    }

    private List<AgentRunEvent> eventsOf(Long runId) {
        synchronized (savedEvents) {
            return savedEvents.stream()
                    .filter(event -> Objects.equals(event.getRunId(), runId))
                    .sorted(Comparator.comparing(AgentRunEvent::getSeq))
                    .toList();
        }
    }

    private static ChatSession session() {
        ChatSession session = new ChatSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setProjectId(PROJECT_ID);
        return session;
    }

    private static Project project() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setUserId(USER_ID);
        return project;
    }

    private static AgentPolicy policy() {
        return new AgentPolicy("acceptance-model", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO);
    }

    /** 真实 SecurityContext 来源：把固定用户放进当前请求线程 */
    private static final class FixedUserFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
            try {
                chain.doFilter(request, response);
            } finally {
                if (request.getDispatcherType() != DispatcherType.ASYNC) {
                    SecurityContextHolder.clearContext();
                }
            }
        }
    }

    /** 最小 MVC 容器：只注册真实控制器，不引入数据库、Redis 与安全过滤链 */
    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    public static class TestWebConfig {
        @org.springframework.context.annotation.Bean
        public AgentChatController agentChatController() {
            AgentChatController controller = CONTROLLER.get();
            if (controller == null) {
                throw new IllegalStateException("测试控制器未初始化");
            }
            return controller;
        }
    }
}
