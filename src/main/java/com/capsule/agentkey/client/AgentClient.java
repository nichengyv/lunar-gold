package com.capsule.agentkey.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * P5-02：模型客户端中立接口。
 * <p>
 * ReAct 编排、摘要、视觉等上层服务只面向本接口编程，不绑定 Spring AI 具体实现。
 * 评测时可注入 {@code ScriptedAgentClient} 回放固定工具调用，真实链路仍用
 * {@code SpringAiAgentClient}，二者可互换。
 */
public interface AgentClient {

    /** 流式调用结果：成功时 error 为 null；sentChunk 标记是否已输出过内容（用于决定是否重试） */
    record StreamResult(Throwable error, boolean sentChunk) {}

    /**
     * 流式对话。文本按 delta 回调 onChunk，工具调用累积完成后回调 onToolCalls，
     * 结束回调 onComplete，异常回调 onError。
     */
    StreamResult streamChat(String apiKey, String model, List<ObjectNode> messages,
                            List<ToolCallback> toolCallbacks, String memorySummary,
                            AgentStreamCallback callback);

    /** 非流式文本调用，供摘要等辅助任务使用；失败直接抛出。 */
    String callSync(String apiKey, String model, List<ObjectNode> messages);

    /** 多模态转写，图片以 data URL 传入；失败直接抛出。 */
    String callVision(String apiKey, String model, String instruction, List<String> imageDataUrls);
}
