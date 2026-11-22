package com.capsule.agentkey.dto;

import java.util.List;

/**
 * 用户 MCP server 的展示模型：环境变量只返回名称，连接状态来自运行时。
 */
public record McpServerResponse(
        Long id,
        String name,
        String command,
        List<String> args,
        List<String> envKeys,
        boolean enabled,
        boolean connected,
        int toolCount,
        String lastError
) {
}
