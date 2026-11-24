package com.capsule.agentkey.dto;

/** MCP 工具清单预览项：远端工具原名 + 描述（不含内部前缀）。 */
public record McpToolInfo(String name, String description) {
}
