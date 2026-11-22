package com.capsule.agentkey.dto;

import java.util.List;
import java.util.Map;

/**
 * 创建/更新用户 MCP server。
 *
 * <p>{@code env} 为 null 表示「不修改环境变量」，空 Map 表示清空；接口从不回显变量值。
 */
public record McpServerRequest(
        String name,
        String command,
        List<String> args,
        Map<String, String> env,
        Boolean enabled
) {
}
