//工具接口契约
package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 工具基类：所有 Agent 工具必须实现此接口
 */
public interface BaseTool {
    /**
     * 工具唯一标识名（与 LLM function name 对应）如 "get_current_weather"
     */
    String getName();

    /**
     * 工具描述（给 LLM 看的，让大模型判断用户意图，让它自己选用不用，用哪个）
     */
    String getDescription();

    /**
     * OpenAI/DeepSeek 格式的参数 Schema。
     * 告诉大模型调用这个工具要传什么参数，参数类型，哪些必填
     */
    Map<String, Object> getParametersSchema();

    /**
     * 执行工具
     * @param arguments 拿LLM 传来的参数 JSON（执行逻辑）
     *                  执行业务，由dispatch调度执行
     */
    ToolResult execute(JsonNode arguments);
}