//工具接口契约
package com.capsule.agentkey.tool;

import com.capsule.agentkey.config.Capability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * 工具基类：所有 Agent 工具必须实现此接口
 */
public interface BaseTool {

    ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
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

    /** 返回 null 表示所有部署模式均可注册。 */
    default Capability requiredCapability() {
        return null;
    }

    /**
     * 第五十九批：工具来源。builtin = 项目内置（受工作区沙箱约束），
     * mcp = 外部 MCP server 提供（访问范围由 server 自身配置决定）。
     * 权限过滤与指标标签按来源区分。
     */
    default String source() {
        return "builtin";
    }

    /**
     * 第五十九批：是否是"备选"工具——给 dispatch 层 fallback 用的，
     * 模型 prompt 里不展示（避免看到两套等价工具纠结）。
     * McpToolBridge 注册的 MCP 工具全部标记为 fallback=true，
     * 但 dispatch 层会先跑主工具，失败再自动 try fallback。
     */
    default boolean isFallback() {
        return false;
    }

    /**
     * 第四十五批：把工具自带的 schema 转成 Spring AI 的工具定义。
     * schema 仍是 OpenAI/DeepSeek 的 JSON Schema，两种引擎共用同一份定义，
     * 避免出现「legacy 能调、spring-ai 调不了」的漂移。
     */
    default ToolDefinition toToolDefinition() {
        String inputSchema;
        try {
            inputSchema = SCHEMA_MAPPER.writeValueAsString(getParametersSchema());
        } catch (Exception e) {
            inputSchema = "{\"type\":\"object\",\"properties\":{}}";
        }
        return ToolDefinition.builder()
                .name(getName())
                .description(getDescription())
                .inputSchema(inputSchema)
                .build();
    }

    /**
     * 第四十五批：Spring AI 引擎下的工具回调。
     * 注意：只用于「下发定义」，执行权始终在 ToolRegistry.dispatch（权限过滤 + 沙箱 + 截断）。
     * Spring AI 的内部工具执行已显式关闭，这里的 call 只作兜底，绝不绕过权限。
     */
    default ToolCallback toToolCallback() {
        ToolDefinition definition = toToolDefinition();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "错误: 工具执行必须经由 ToolRegistry.dispatch，Spring AI 内部执行已禁用";
            }
        };
    }
}