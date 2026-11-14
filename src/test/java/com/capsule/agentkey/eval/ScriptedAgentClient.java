package com.capsule.agentkey.eval;

import com.capsule.agentkey.client.AgentClient;
import com.capsule.agentkey.client.AgentStreamCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P5-02：脚本化模型客户端，评测时注入 ReAct 循环回放固定工具调用。
 * <p>
 * 不依赖任何外部模型 API，结果完全确定。每次 {@link #streamChat} 调用按顺序取脚本中的下一个
 * {@link ScriptedTurn}：有 {@code toolCalls} 则回调 {@code onToolCalls}，有 {@code text} 则回调
 * {@code onChunk}+{@code onComplete}；脚本耗尽后直接 {@code onComplete}（空文本，让 ReAct 收尾）。
 */
public class ScriptedAgentClient implements AgentClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一轮脚本：要么调工具，要么出最终文本 */
    public record ScriptedTurn(List<ToolCallSpec> toolCalls, String text) {
        public static ScriptedTurn ofTools(List<ToolCallSpec> toolCalls) {
            return new ScriptedTurn(toolCalls, null);
        }
        public static ScriptedTurn ofText(String text) {
            return new ScriptedTurn(List.of(), text);
        }
    }

    /** 单个工具调用规格 */
    public record ToolCallSpec(String name, String arguments) {}

    private final List<ScriptedTurn> turns;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final AtomicInteger toolRounds = new AtomicInteger(0);
    private final AtomicInteger attemptedToolCalls = new AtomicInteger(0);
    private final AtomicInteger promptTokens = new AtomicInteger(0);
    private final AtomicInteger completionTokens = new AtomicInteger(0);
    private final List<List<ObjectNode>> messageSnapshots = new ArrayList<>();
    private final String syncText;
    private final String visionText;

    public ScriptedAgentClient(List<ScriptedTurn> turns) {
        this(turns, "", "");
    }

    public ScriptedAgentClient(List<ScriptedTurn> turns, String syncText, String visionText) {
        this.turns = new ArrayList<>(turns);
        this.syncText = syncText;
        this.visionText = visionText;
    }

    @Override
    public StreamResult streamChat(String apiKey, String model, List<ObjectNode> messages,
                                   List<ToolCallback> toolCallbacks, String memorySummary,
                                   AgentStreamCallback callback) {
        messageSnapshots.add(messages.stream().map(ObjectNode::deepCopy).toList());
        int idx = cursor.getAndIncrement();
        if (idx >= turns.size()) {
            // 脚本耗尽：返回空文本，ReAct 会判定无工具调用而收尾
            reportUsage(messages, 0, callback);
            callback.onComplete();
            return new StreamResult(null, false);
        }
        ScriptedTurn turn = turns.get(idx);
        if (turn.toolCalls() != null && !turn.toolCalls().isEmpty()) {
            toolRounds.incrementAndGet();
            attemptedToolCalls.addAndGet(turn.toolCalls().size());
            List<ObjectNode> nodes = new ArrayList<>();
            for (int i = 0; i < turn.toolCalls().size(); i++) {
                ToolCallSpec spec = turn.toolCalls().get(i);
                ObjectNode node = MAPPER.createObjectNode();
                node.put("id", "call_" + idx + "_" + i);
                ObjectNode function = MAPPER.createObjectNode();
                function.put("name", spec.name());
                function.put("arguments", spec.arguments() == null ? "{}" : spec.arguments());
                node.set("function", function);
                nodes.add(node);
            }
            callback.onToolCalls(nodes);
            reportUsage(messages, turn.toolCalls().size(), callback);
            callback.onComplete();
            return new StreamResult(null, false);
        }
        if (turn.text() != null) {
            callback.onChunk(turn.text());
        }
        reportUsage(messages, turn.text() == null ? 0 : turn.text().length(), callback);
        callback.onComplete();
        return new StreamResult(null, turn.text() != null);
    }

    private void reportUsage(List<ObjectNode> messages, int outputTokens, AgentStreamCallback callback) {
        int input = Math.max(1, messages.size());
        promptTokens.addAndGet(input);
        completionTokens.addAndGet(outputTokens);
        callback.onUsage(input, outputTokens);
    }

    /** 每次真实 ReAct 模型调用前的完整消息快照，含 assistant tool_calls 与 tool 结果回灌。 */
    public List<List<ObjectNode>> messageSnapshots() {
        return List.copyOf(messageSnapshots);
    }

    public int modelCalls() { return cursor.get(); }
    public int toolRounds() { return toolRounds.get(); }
    public int attemptedToolCalls() { return attemptedToolCalls.get(); }
    public int promptTokens() { return promptTokens.get(); }
    public int completionTokens() { return completionTokens.get(); }
    public int totalTokens() { return promptTokens() + completionTokens(); }

    @Override
    public String callSync(String apiKey, String model, List<ObjectNode> messages) {
        return syncText;
    }

    @Override
    public String callVision(String apiKey, String model, String instruction, List<String> imageDataUrls) {
        return visionText;
    }
}
