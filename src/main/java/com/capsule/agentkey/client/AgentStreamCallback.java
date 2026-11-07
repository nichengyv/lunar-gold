package com.capsule.agentkey.client;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 流式对话回调（第四十九批从 DeepSeekAgentClient.Callback 抽成中立接口）。
 * ReAct 编排只面向本接口编程，不绑定任何具体模型客户端实现，
 * 之后更换模型接入层时编排逻辑零改动。
 */
public interface AgentStreamCallback {

    void onChunk(String chunk);//收到一小块文字，反复触发

    /**
     * 流式返回中累积完成的工具调用（已把分片拼成完整 JSON）。
     * 用 default 实现，只关心文本的调用方无需实现。
     */
    default void onToolCalls(List<ObjectNode> toolCalls) {}

    void onComplete();//流式全部结束

    void onError(Throwable t);//网络、解析、业务异常
}
