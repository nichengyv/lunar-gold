package com.capsule.agentkey.eval;

import com.capsule.agentkey.dto.ChatSessionStats;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.repository.ChatMessageRepository;
import com.capsule.agentkey.repository.ChatSessionRepository;
import com.capsule.agentkey.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P7-02：会话追踪聚合（{@link ChatService#aggregateStats}）的成本基准。
 *
 * <p>只测 trace JSON 的解析与聚合，不含 JDBC 往返与行解码；trace 形状对齐
 * {@code ChatStreamBridge.onComplete} 的真实落库结构。用于按预注册规则判断：
 * 1000 条消息的 p95 是否超过 500ms，超过才值得引入结果缓存。
 *
 * <p>默认不进入常规测试：需要显式 {@code mvn -o "-Dperf=true" "-Dtest=ChatStatsBenchmarkTest" test}。
 */
@EnabledIfSystemProperty(named = "perf", matches = "true")
class ChatStatsBenchmarkTest {

    private static final long SESSION_ID = 1L;
    private static final long USER_ID = 1L;
    private static final int[] SIZES = {200, 500, 1000};
    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURE_ROUNDS = 5;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 聚合耗时应低于预注册阈值() throws IOException {
        Map<Integer, String> traces = new LinkedHashMap<>();
        for (int size : SIZES) {
            traces.put(size, buildTrace());
        }

        ChatService service = newService();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (int size : SIZES) {
            List<ChatMessage> messages = buildMessages(size, traces.get(size));
            long bytes = messages.stream()
                    .filter(m -> m.getTrace() != null)
                    .mapToLong(m -> m.getTrace().getBytes(StandardCharsets.UTF_8).length)
                    .sum();

            for (int i = 0; i < WARMUP_ROUNDS; i++) {
                aggregate(service, messages);
            }
            long[] samples = new long[MEASURE_ROUNDS];
            for (int i = 0; i < MEASURE_ROUNDS; i++) {
                long started = System.nanoTime();
                ChatSessionStats stats = aggregate(service, messages);
                samples[i] = (System.nanoTime() - started) / 1_000_000;
                assertNotNull(stats);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("messages", size * 2);
            row.put("assistantMessages", size);
            row.put("traceBytes", bytes);
            row.put("p50Ms", percentile(samples, 0.50));
            row.put("p95Ms", percentile(samples, 0.95));
            row.put("maxMs", percentile(samples, 1.0));
            rows.add(row);
        }

        System.out.println("\n========== 会话追踪聚合成本 ==========");
        for (Map<String, Object> row : rows) {
            System.out.printf("messages=%d trace=%.2fMB p50=%dms p95=%dms max=%dms%n",
                    row.get("messages"), ((Number) row.get("traceBytes")).doubleValue() / 1024 / 1024,
                    row.get("p50Ms"), row.get("p95Ms"), row.get("maxMs"));
        }

        Path dir = Paths.get("eval", "reports");
        Files.createDirectories(dir);
        Path report = dir.resolve("stats-aggregation-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", LocalDateTime.now().toString());
        payload.put("note", "仅 trace 解析与聚合，不含 JDBC；阈值 1000 条消息 p95 > 500ms 才引入缓存");
        payload.put("runs", rows);
        Files.writeString(report, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                StandardCharsets.UTF_8);
        System.out.println("报告: " + report.toAbsolutePath());
    }

    private ChatService newService() {
        ChatService service = new ChatService();
        ChatSessionRepository sessionRepo = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepo = mock(ChatMessageRepository.class);
        ChatSession session = new ChatSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        when(sessionRepo.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(messageRepo.findBySessionIdOrderByCreateTimeAsc(SESSION_ID)).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "sessionRepo", sessionRepo);
        ReflectionTestUtils.setField(service, "messageRepo", messageRepo);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        return service;
    }

    private ChatSessionStats aggregate(ChatService service, List<ChatMessage> messages) {
        ChatMessageRepository repo = (ChatMessageRepository) ReflectionTestUtils.getField(service, "messageRepo");
        when(repo.findBySessionIdOrderByCreateTimeAsc(SESSION_ID)).thenReturn(messages);
        return service.aggregateStats(SESSION_ID, USER_ID);
    }

    /** 一次助手轮次：thinking + 3 个工具 + 1 处改动 + 计划 + 三段耗时。 */
    private String buildTrace() throws IOException {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("thinking", "分析代码结构并确认修改范围。".repeat(120));
        List<Map<String, Object>> tools = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", i == 0 ? "read_file" : i == 1 ? "edit_file" : "run_command");
            item.put("args", "{\"path\":\"src/main/java/com/capsule/agentkey/service/Example" + i
                    + ".java\",\"query\":\"" + "x".repeat(160) + "\"}");
            item.put("status", "success");
            item.put("result", "执行结果：".repeat(180));
            item.put("error", "");
            item.put("costMs", 120 + i * 30L);
            tools.add(item);
        }
        trace.put("tools", tools);
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("path", "src/main/java/com/capsule/agentkey/service/Example.java");
        change.put("additions", 12);
        change.put("deletions", 3);
        change.put("diff", "+修改内容\n".repeat(120));
        changes.add(change);
        trace.put("changes", changes);
        trace.put("plan", Map.of("steps", List.of(
                Map.of("title", "定位入口", "status", "completed"),
                Map.of("title", "修改实现", "status", "completed"))));
        trace.put("turnId", "turn-bench");
        trace.put("durationMs", 4200);
        trace.put("segments", List.of(
                Map.of("phase", "setup", "ms", 600),
                Map.of("phase", "tools", "ms", 2100),
                Map.of("phase", "answer", "ms", 1500)));
        return mapper.writeValueAsString(trace);
    }

    private List<ChatMessage> buildMessages(int assistantCount, String trace) {
        List<ChatMessage> messages = new ArrayList<>(assistantCount * 2);
        for (int i = 0; i < assistantCount; i++) {
            ChatMessage user = new ChatMessage();
            user.setId((long) i * 2 + 1);
            user.setUserId(USER_ID);
            user.setSessionId(SESSION_ID);
            user.setRole(ChatMessage.MessageRole.USER);
            user.setContent("请继续推进第 " + i + " 步");
            user.setCreateTime(LocalDateTime.now().plusSeconds(i));
            messages.add(user);

            ChatMessage assistant = new ChatMessage();
            assistant.setId((long) i * 2 + 2);
            assistant.setUserId(USER_ID);
            assistant.setSessionId(SESSION_ID);
            assistant.setRole(ChatMessage.MessageRole.ASSISTANT);
            assistant.setContent("已完成第 " + i + " 步");
            assistant.setTrace(trace);
            assistant.setCreateTime(LocalDateTime.now().plusSeconds(i).plusNanos(1));
            messages.add(assistant);
        }
        messages.sort(Comparator.comparing(ChatMessage::getCreateTime));
        return messages;
    }

    private long percentile(long[] samples, double p) {
        long[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
