package com.capsule.agentkey.agent.context;

import com.capsule.agentkey.agent.run.PlanStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5 / P7-07：计划软注入的行为契约。
 *
 * <p>核心不是“注入了一段文字”，而是“每轮都从单一事实源重读”。
 * 这里用递增的 provider 证明第二轮看到的是新快照，而不是第一轮的缓存。
 */
class ContextAssemblerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 开关关闭时不读取计划也不改变消息() {
        AtomicInteger calls = new AtomicInteger();
        ContextAssembler assembler = new ContextAssembler(false, () -> {
            calls.incrementAndGet();
            return List.of(new PlanStep("读取项目", "pending"));
        }, null);
        List<ObjectNode> base = List.of(system("你是助手"), user("开始"));

        List<ObjectNode> result = assembler.assemble(base);

        assertEquals(2, result.size());
        assertEquals("开始", result.get(1).path("content").asText());
        assertEquals(0, calls.get(), "关闭时不应访问计划存储");
    }

    @Test
    void 开启时每轮读取最新快照并插在system之后() {
        AtomicInteger calls = new AtomicInteger();
        List<List<PlanStep>> snapshots = List.of(
                List.of(new PlanStep("读取项目", "completed"), new PlanStep("修改接口", "in_progress")),
                List.of(new PlanStep("读取项目", "completed"), new PlanStep("修改接口", "completed")));
        ContextAssembler assembler = new ContextAssembler(true,
                () -> snapshots.get(Math.min(calls.getAndIncrement(), snapshots.size() - 1)), null);
        List<ObjectNode> base = List.of(system("你是助手"), user("继续"));

        List<ObjectNode> first = assembler.assemble(base);
        List<ObjectNode> second = assembler.assemble(base);

        assertEquals(3, first.size());
        assertEquals("system", first.get(0).path("role").asText());
        assertEquals("user", first.get(1).path("role").asText());
        String firstPlan = first.get(1).path("content").asText();
        assertTrue(firstPlan.contains("Current plan"), "必须包含约定的 Current plan 标识");
        assertTrue(firstPlan.contains("修改接口"));
        assertTrue(firstPlan.contains("[in_progress]"));

        String secondPlan = second.get(1).path("content").asText();
        assertTrue(secondPlan.contains("[completed]"), "第二轮必须看到更新的持久化快照");
        assertFalse(secondPlan.contains("[in_progress]"));
        assertEquals(2, calls.get(), "每轮都必须重新读取计划");
        assertEquals(2, base.size(), "权威消息列表不能被注入修改");
    }

    @Test
    void 计划读取失败时跳过注入而不中断本轮() {
        ContextAssembler assembler = new ContextAssembler(true, () -> {
            throw new IllegalStateException("db down");
        }, null);
        List<ObjectNode> base = List.of(system("你是助手"), user("继续"));

        List<ObjectNode> result = assembler.assemble(base);

        assertEquals(2, result.size());
    }

    @Test
    void 空计划不注入占位消息() {
        ContextAssembler assembler = new ContextAssembler(true, List::of, null);

        List<ObjectNode> result = assembler.assemble(List.of(system("你是助手"), user("继续")));

        assertEquals(2, result.size());
    }

    @Test
    void 没有system消息时计划插到首位() {
        ContextAssembler assembler = new ContextAssembler(true,
                () -> List.of(new PlanStep("读取项目", "pending")), null);

        List<ObjectNode> result = assembler.assemble(List.of(user("继续")));

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).path("role").asText());
        assertTrue(result.get(0).path("content").asText().contains("Current plan"));
        assertEquals("继续", result.get(1).path("content").asText());
    }

    // —— R7c / P8-05：压缩后本地检索型记忆 ——

    private RunHistoryRetriever retriever() {
        return new RunHistoryRetriever(RunHistoryRetriever.Settings.defaults());
    }

    /** 只保留末尾 1 条、窗口 20 字符，强制滑窗淘汰较早消息 */
    private ContextCondenser evictingCondenser() {
        return new ContextCondenser(new ContextCondenser.Settings(20, 20, 1), null);
    }

    @Test
    void 压缩省略后可召回旧消息并注入合成user记忆() {
        ContextAssembler assembler = new ContextAssembler(false, null,
                evictingCondenser(), retriever());
        List<ObjectNode> base = List.of(
                system("你是助手"),
                user("请记住 apiKey alpha-secret 这个配置"),
                user("现在继续实现 alpha 功能"));

        List<ObjectNode> result = assembler.assemble(base);

        assertEquals("system", result.get(0).path("role").asText());
        assertEquals("user", result.get(1).path("role").asText());
        String memory = result.get(1).path("content").asText();
        assertTrue(memory.contains("【历史检索记忆】"), memory);
        assertTrue(memory.contains("apiKey"), memory);
        assertTrue(memory.startsWith("【历史检索记忆】"));
        assertEquals(3, base.size(), "权威消息列表不能被修改");
    }

    @Test
    void 没有省略消息时不注入检索记忆() {
        ContextAssembler assembler = new ContextAssembler(false, null,
                new ContextCondenser(new ContextCondenser.Settings(10000, 10000, 6), null),
                retriever());
        List<ObjectNode> base = List.of(system("你是助手"), user("alpha 任务"));

        List<ObjectNode> result = assembler.assemble(base);

        assertEquals(2, result.size());
        assertEquals("alpha 任务", result.get(1).path("content").asText());
    }

    @Test
    void 检索无命中时不注入占位消息() {
        RunHistoryRetriever noHit = new RunHistoryRetriever(
                new RunHistoryRetriever.Settings(6, 1500, 6000, Integer.MAX_VALUE));
        ContextAssembler assembler = new ContextAssembler(false, null,
                evictingCondenser(), noHit);
        List<ObjectNode> base = List.of(
                system("你是助手"),
                user("请记住 apiKey alpha-secret 这个配置"),
                user("现在继续实现 alpha 功能"));

        List<ObjectNode> result = assembler.assemble(base);

        assertEquals(2, result.size());
    }

    @Test
    void 检索开关关闭时压缩后不注入记忆() {
        ContextAssembler assembler = new ContextAssembler(false, null, evictingCondenser());
        List<ObjectNode> base = List.of(
                system("你是助手"),
                user("请记住 apiKey alpha-secret 这个配置"),
                user("现在继续实现 alpha 功能"));

        List<ObjectNode> result = assembler.assemble(base);

        assertFalse(result.stream().anyMatch(m -> m.path("content").asText("")
                .contains("【历史检索记忆】")));
    }

    @Test
    void 检索记忆注入不破坏assistant与tool的配对顺序() {
        ContextAssembler assembler = new ContextAssembler(false, null,
                new ContextCondenser(new ContextCondenser.Settings(60, 60, 3), null),
                retriever());
        ObjectNode assistant = mapper.createObjectNode();
        assistant.put("role", "assistant");
        assistant.put("content", "调用工具");
        ObjectNode call = assistant.putArray("tool_calls").addObject();
        call.put("id", "call-1");
        call.put("type", "function");
        call.set("function", mapper.createObjectNode()
                .put("name", "read_file").put("arguments", "{\"path\":\"a.txt\"}"));
        ObjectNode tool = mapper.createObjectNode();
        tool.put("role", "tool");
        tool.put("tool_call_id", "call-1");
        tool.put("content", "alpha 文件内容");
        List<ObjectNode> base = List.of(
                system("你是助手"),
                user("alpha 旧任务需要记住 verifyOptions"),
                assistant,
                tool,
                user("alpha 继续"));
        List<String> before = base.stream().map(ObjectNode::toString).toList();

        List<ObjectNode> result = assembler.assemble(base);

        assertEquals(before, base.stream().map(ObjectNode::toString).toList(),
                "权威消息不得被修改");
        assertEquals(5, result.size());
        assertEquals("user", result.get(1).path("role").asText());
        assertTrue(result.get(1).path("content").asText().startsWith("【历史检索记忆】"));
        assertEquals("assistant", result.get(2).path("role").asText());
        assertEquals("call-1", result.get(2).path("tool_calls").get(0).path("id").asText());
        assertEquals("tool", result.get(3).path("role").asText());
        assertEquals("call-1", result.get(3).path("tool_call_id").asText(),
                "tool 响应必须紧跟在对应的 assistant 调用之后");
        assertEquals("alpha 继续", result.get(4).path("content").asText());
    }

    private ObjectNode system(String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "system");
        message.put("content", content);
        return message;
    }

    private ObjectNode user(String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", content);
        return message;
    }
}
