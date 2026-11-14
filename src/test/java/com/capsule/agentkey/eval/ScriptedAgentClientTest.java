package com.capsule.agentkey.eval;

import com.capsule.agentkey.client.AgentStreamCallback;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P5-02：ScriptedAgentClient 回放行为验证。
 */
class ScriptedAgentClientTest {

    @Test
    void 首轮返回工具调用_次轮返回文本() {
        var turns = List.of(
                ScriptedAgentClient.ScriptedTurn.ofTools(List.of(
                        new ScriptedAgentClient.ToolCallSpec("read_file", "{\"path\":\"pom.xml\"}"))),
                ScriptedAgentClient.ScriptedTurn.ofText("已读取 pom.xml")
        );
        var client = new ScriptedAgentClient(turns);

        // 第一轮：应回调 onToolCalls
        List<ObjectNode> captured = new ArrayList<>();
        AtomicBoolean completed1 = new AtomicBoolean(false);
        client.streamChat("k", "m", List.of(), List.of(), null, new AgentStreamCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCalls(List<ObjectNode> tc) { captured.addAll(tc); }
            @Override public void onComplete() { completed1.set(true); }
            @Override public void onError(Throwable t) { fail("不应有错误: " + t); }
        });
        assertEquals(1, captured.size());
        assertEquals("read_file", captured.get(0).path("function").path("name").asText());
        assertEquals("{\"path\":\"pom.xml\"}", captured.get(0).path("function").path("arguments").asText());
        assertTrue(completed1.get());

        // 第二轮：应回调 onChunk + onComplete
        AtomicReference<String> text = new AtomicReference<>("");
        AtomicBoolean completed2 = new AtomicBoolean(false);
        client.streamChat("k", "m", List.of(), List.of(), null, new AgentStreamCallback() {
            @Override public void onChunk(String c) { text.set(text.get() + c); }
            @Override public void onComplete() { completed2.set(true); }
            @Override public void onError(Throwable t) { fail("不应有错误"); }
        });
        assertEquals("已读取 pom.xml", text.get());
        assertTrue(completed2.get());
    }

    @Test
    void 超出脚本长度_返回空文本并收尾() {
        var client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofText("hi")));

        // 第一次：正常返回
        client.streamChat("k", "m", List.of(), List.of(), null, new AgentStreamCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onComplete() {}
            @Override public void onError(Throwable t) { fail(); }
        });

        // 第二次：脚本耗尽，应直接 onComplete 且无 chunk
        AtomicBoolean gotChunk = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        client.streamChat("k", "m", List.of(), List.of(), null, new AgentStreamCallback() {
            @Override public void onChunk(String c) { gotChunk.set(true); }
            @Override public void onComplete() { completed.set(true); }
            @Override public void onError(Throwable t) { fail(); }
        });
        assertFalse(gotChunk.get());
        assertTrue(completed.get());
    }

    @Test
    void callSync与callVision返回预设文本() {
        var client = new ScriptedAgentClient(List.of(), "sync-result", "vision-result");
        assertEquals("sync-result", client.callSync("k", "m", List.of()));
        assertEquals("vision-result", client.callVision("k", "m", "ins", List.of()));
    }
}
