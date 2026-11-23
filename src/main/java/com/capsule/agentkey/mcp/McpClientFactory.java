package com.capsule.agentkey.mcp;

import io.modelcontextprotocol.client.McpSyncClient;

import java.util.List;
import java.util.Map;

/**
 * 创建用户自助 MCP server 的同步客户端。
 * 抽成接口便于测试替换与传输扩展（stdio / HTTP / SSE）。
 */
public interface McpClientFactory {

    /** 基于连接规格创建客户端；传输类型由 spec.transportType 决定。 */
    McpSyncClient create(McpConnectionSpec spec);

    /** 兼容旧调用：stdio 快捷方式，委托到 {@link #create(McpConnectionSpec)}。 */
    default McpSyncClient create(String command, List<String> args, Map<String, String> env) {
        return create(McpConnectionSpec.stdio(command, args, env));
    }
}
