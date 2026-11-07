package com.capsule.agentkey.mcp;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.tool.BaseTool;
import com.capsule.agentkey.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Function;

/**
 * 第五十一批（第五十九批改）：把外部 MCP server 的工具包装成项目内的 {@link BaseTool}。
 *
 * 第五十九批新增 fallback 支持：
 * - 等价工具（write_file/edit_file/read_file 等）标记 fallback=true，
 *   模型 prompt 里不展示（避免两套纠结），但 dispatch 层主工具失败时自动 try 它们
 * - 独有工具（create_directory/move_file/git read-only 等）fallback=false，
 *   模型正常看到和调用
 */
public class McpToolAdapter implements BaseTool {

    private final String name;
    private final String description;
    private final Map<String, Object> parametersSchema;
    private final Function<JsonNode, ToolResult> executor;
    private final boolean fallback;
    private final Capability requiredCapability;

    public McpToolAdapter(String name, String description, Map<String, Object> parametersSchema,
                          Function<JsonNode, ToolResult> executor, boolean fallback,
                          Capability requiredCapability) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
        this.executor = executor;
        this.fallback = fallback;
        this.requiredCapability = requiredCapability;
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public Map<String, Object> getParametersSchema() { return parametersSchema; }
    @Override public ToolResult execute(JsonNode arguments) { return executor.apply(arguments); }
    @Override public String source() { return "mcp"; }
    @Override public boolean isFallback() { return fallback; }
    @Override public Capability requiredCapability() { return requiredCapability; }
}
