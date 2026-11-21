package com.capsule.agentkey.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5 / P7-09：三层压缩的行为契约。
 *
 * <p>重点验证三件事：旧读取只留最近一次、滑窗不产生孤儿 tool 响应、
 * 摘要只生成一次且可复用。压缩只作用于副本，原始消息列表必须保持原样。
 */
class ContextCondenserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // 第一层：语义裁剪
    // ------------------------------------------------------------------

    @Test
    void 同一文件的多次读取只保留最近一次() {
        List<ObjectNode> messages = new ArrayList<>(List.of(
                system(),
                assistantCall("c1", "read_file", "{\"path\":\"a.txt\"}"),
                toolMessage("c1", "A".repeat(300)),
                assistantCall("c2", "read_file", "{\"path\":\"a.txt\"}"),
                toolMessage("c2", "B".repeat(300)),
                assistantCall("c3", "write_file", "{\"path\":\"a.txt\"}"),
                toolMessage("c3", "W".repeat(300)),
                user("继续")));
        ContextCondenser condenser = new ContextCondenser(
                new ContextCondenser.Settings(100_000, 100_000, 6), null);

        List<ObjectNode> result = condenser.condense(messages);

        assertTrue(result.get(2).path("content").asText().contains("历史读取窗口已折叠"));
        assertTrue(result.get(4).path("content").asText().startsWith("BBB"), "最近一次读取必须保留原文");
        assertTrue(result.get(6).path("content").asText().startsWith("WWW"), "写操作结果不参与折叠");
        assertEquals("A".repeat(300), messages.get(2).path("content").asText(),
                "压缩不能修改主循环持有的权威消息");
    }

    @Test
    void 只出现一次或过短的读取结果不折叠() {
        List<ObjectNode> messages = new ArrayList<>(List.of(
                system(),
                assistantCall("c1", "read_file", "{\"path\":\"a.txt\"}"),
                toolMessage("c1", "小的结果"),
                assistantCall("c2", "search_files", "{\"pattern\":\"foo\"}"),
                toolMessage("c2", "S".repeat(500)),
                user("继续")));
        ContextCondenser condenser = new ContextCondenser(
                new ContextCondenser.Settings(100_000, 100_000, 6), null);

        List<ObjectNode> result = condenser.condense(messages);

        assertEquals("小的结果", result.get(2).path("content").asText());
        assertEquals("S".repeat(500), result.get(4).path("content").asText());
    }

    // ------------------------------------------------------------------
    // 第二层：LLM 摘要
    // ------------------------------------------------------------------

    @Test
    void 达到阈值时生成摘要并保留最后的消息() {
        AtomicInteger calls = new AtomicInteger();
        ContextCondenser condenser = new ContextCondenser(
                new ContextCondenser.Settings(2_000, 500, 2),
                transcript -> {
                    calls.incrementAndGet();
                    return "SUMMARY-X";
                });
        List<ObjectNode> messages = summaryFixture();

        List<ObjectNode> result = condenser.condense(messages);

        assertEquals(1, calls.get());
        assertEquals(4, result.size());
        String summary = result.get(1).path("content").asText();
        assertTrue(summary.contains("较早上下文摘要"));
        assertTrue(summary.contains("SUMMARY-X"));
        assertEquals("最后问题", result.get(result.size() - 1).path("content").asText());
        assertTrue(condenser.hasCachedSummary());
    }

    @Test
    void 同一段历史不会重复摘要() {
        AtomicInteger calls = new AtomicInteger();
        ContextCondenser condenser = new ContextCondenser(
                new ContextCondenser.Settings(2_000, 500, 2),
                transcript -> {
                    calls.incrementAndGet();
                    return "SUMMARY-X";
                });
        List<ObjectNode> base = summaryFixture();
        condenser.condense(base);

        List<ObjectNode> grown = new ArrayList<>(base);
        grown.add(user("新增一轮"));
        List<ObjectNode> result = condenser.condense(grown);

        assertEquals(1, calls.get(), "新增内容很少时必须复用缓存摘要");
        assertTrue(result.get(1).path("content").asText().contains("SUMMARY-X"));
        assertTrue(result.get(result.size() - 1).path("content").asText().contains("新增一轮"));
    }

    @Test
    void 摘要异常时降级为滑窗且不抛错() {
        ContextCondenser condenser = new ContextCondenser(
                new ContextCondenser.Settings(300, 200, 2),
                transcript -> {
                    throw new IllegalStateException("model down");
                });
        List<ObjectNode> messages = new ArrayList<>();
        messages.add(system());
        for (int i = 0; i < 5; i++) {
            messages.add(user("历史" + i + ":" + "x".repeat(200)));
        }

        List<ObjectNode> result = condenser.condense(messages);

        assertTrue(result.size() < messages.size(), "摘要失败后仍必须通过滑窗把上下文压下去");
        assertEquals("system", result.get(0).path("role").asText());
    }

    // ------------------------------------------------------------------
    // 第三层：滑窗与孤儿 tool 清理
    // ------------------------------------------------------------------

    @Test
    void 滑窗保留首条system并淘汰最早消息() {
        List<ObjectNode> messages = new ArrayList<>(List.of(
                system(),
                user("旧问题:" + "o".repeat(500)),
                assistantCall("c1", "read_file", "{\"path\":\"a.txt\"}"),
                toolMessage("c1", "R".repeat(500)),
                user("新问题")));
        ContextCondenser condenser = new ContextCondenser(
                new ContextCondenser.Settings(200, 200, 2), null);

        List<ObjectNode> result = condenser.condense(messages);

        assertEquals("system", result.get(0).path("role").asText());
        assertEquals("新问题", result.get(result.size() - 1).path("content").asText());
        assertTrue(result.size() < messages.size());
        assertNoOrphanToolMessages(result);
    }

    @Test
    void 淘汰assistant时连带清理孤儿tool响应() {
        List<ObjectNode> messages = new ArrayList<>(List.of(
                system(),
                toolMessage("ghost", "G".repeat(500)),
                user("新问题")));
        ContextCondenser condenser = new ContextCondenser(
                new ContextCondenser.Settings(100, 100, 2), null);

        List<ObjectNode> result = condenser.condense(messages);

        assertEquals(2, result.size());
        assertNoOrphanToolMessages(result);
        assertEquals("user", result.get(1).path("role").asText());
    }

    @Test
    void 压缩参数非法时阈值被钳到滑窗上限() {
        ContextCondenser.Settings settings = new ContextCondenser.Settings(1_000, 5_000, 0);

        assertEquals(1_000, settings.summarizeAtChars());
        assertEquals(6, settings.keepRecentMessages());
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    /** system + 4 条 300 字符旧消息 + 最后问题；keepRecent=2 时中间 3 条会被摘要 */
    private List<ObjectNode> summaryFixture() {
        List<ObjectNode> messages = new ArrayList<>();
        messages.add(system());
        for (int i = 0; i < 4; i++) {
            messages.add(user("旧消息" + i + ":" + "x".repeat(300)));
        }
        messages.add(user("最后问题"));
        return messages;
    }

    private void assertNoOrphanToolMessages(List<ObjectNode> messages) {
        List<String> knownIds = new ArrayList<>();
        for (ObjectNode message : messages) {
            if (!"assistant".equals(message.path("role").asText())) {
                continue;
            }
            var calls = message.get("tool_calls");
            if (calls instanceof ArrayNode array) {
                array.forEach(call -> knownIds.add(call.path("id").asText("")));
            }
        }
        for (ObjectNode message : messages) {
            if ("tool".equals(message.path("role").asText())) {
                assertTrue(knownIds.contains(message.path("tool_call_id").asText("")),
                        "tool 响应必须能找到对应的 assistant(tool_calls)");
            }
        }
        assertFalse(messages.isEmpty());
    }

    private ObjectNode system() {
        return message("system", "你是助手");
    }

    private ObjectNode user(String content) {
        return message("user", content);
    }

    private ObjectNode toolMessage(String id, String content) {
        ObjectNode message = message("tool", content);
        message.put("tool_call_id", id);
        return message;
    }

    private ObjectNode assistantCall(String id, String name, String arguments) {
        ObjectNode message = message("assistant", "");
        ObjectNode call = mapper.createObjectNode();
        call.put("id", id);
        call.put("type", "function");
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name);
        function.put("arguments", arguments);
        call.set("function", function);
        ArrayNode calls = mapper.createArrayNode();
        calls.add(call);
        message.set("tool_calls", calls);
        return message;
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
