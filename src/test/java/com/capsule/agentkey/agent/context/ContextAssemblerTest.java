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
