package com.capsule.agentkey.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7c / P8-05：本地检索型记忆的确定性评分与预算。
 */
class RunHistoryRetrieverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private RunHistoryRetriever retriever() {
        return new RunHistoryRetriever(RunHistoryRetriever.Settings.defaults());
    }

    private RunHistoryRetriever.Source source(int index, String role, String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return new RunHistoryRetriever.Source(index, message);
    }

    @Test
    void 分词权重_标识符5_英文2_中文bigram1() {
        Map<String, Integer> tokens = RunHistoryRetriever.tokenize("读取 verifyOptions 配置 与 flag");

        assertEquals(5, tokens.get("verifyoptions"));
        assertEquals(5, tokens.get("flag"));
        assertEquals(1, tokens.get("配置"));
        assertTrue(tokens.values().stream().allMatch(weight -> weight <= 5));
    }

    @Test
    void 角色权重影响排序_user优先于tool() {
        RunHistoryRetriever lowThreshold = new RunHistoryRetriever(
                new RunHistoryRetriever.Settings(6, 1500, 6000, 1));
        List<RunHistoryRetriever.Hit> hits = lowThreshold.retrieve(List.of(
                source(1, "tool", "verifyOptions 的返回值为 true"),
                source(2, "user", "请确认 verifyOptions 的含义")), "verifyOptions");

        assertEquals(2, hits.size());
        assertEquals(2, hits.get(0).index());
        assertEquals("user", hits.get(0).role());
        assertEquals(1, hits.get(1).index());
        assertEquals("tool", hits.get(1).role());
        assertTrue(hits.get(0).score() > hits.get(1).score());
    }

    @Test
    void 同分时按原始序号升序() {
        List<RunHistoryRetriever.Hit> hits = retriever().retrieve(List.of(
                source(5, "user", "alpha five"),
                source(2, "user", "alpha two")), "alpha");

        assertEquals(2, hits.size());
        assertEquals(2, hits.get(0).index());
        assertEquals(5, hits.get(1).index());
    }

    @Test
    void 低于最低分不返回() {
        List<RunHistoryRetriever.Hit> hits = retriever().retrieve(List.of(
                source(1, "tool", "alpha")), "alpha");

        assertTrue(hits.isEmpty(), "tool 单条命中=5，低于默认最低分 6");
    }

    @Test
    void topK与总字符预算生效() {
        List<RunHistoryRetriever.Source> sources = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            sources.add(source(i, "user", "alpha 片段 " + i));
        }
        RunHistoryRetriever limited = new RunHistoryRetriever(
                new RunHistoryRetriever.Settings(3, 1500, 30, 6));

        List<RunHistoryRetriever.Hit> hits = limited.retrieve(sources, "alpha");

        assertTrue(hits.size() <= 3);
        int total = hits.stream().mapToInt(h -> h.content().length()).sum();
        assertTrue(total <= 30);
    }

    @Test
    void 单条片段超长被截断() {
        String longText = "alpha " + "x".repeat(5000);
        RunHistoryRetriever limited = new RunHistoryRetriever(
                new RunHistoryRetriever.Settings(6, 100, 6000, 6));

        List<RunHistoryRetriever.Hit> hits = limited.retrieve(
                List.of(source(1, "user", longText)), "alpha");

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).content().length() <= 101);
    }

    @Test
    void 相同内容只保留一条() {
        List<RunHistoryRetriever.Hit> hits = retriever().retrieve(List.of(
                source(1, "user", "alpha 内容"),
                source(2, "user", "alpha 内容")), "alpha");

        assertEquals(1, hits.size());
    }

    @Test
    void 空查询或无命中返回空列表() {
        assertTrue(retriever().retrieve(List.of(source(1, "user", "alpha")), " ").isEmpty());
        assertTrue(retriever().retrieve(List.of(), "alpha").isEmpty());
        assertTrue(retriever().retrieve(List.of(source(1, "user", "beta")), "alpha").isEmpty());
    }
}
