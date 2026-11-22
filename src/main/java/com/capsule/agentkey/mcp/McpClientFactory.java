package com.capsule.agentkey.mcp;

import io.modelcontextprotocol.client.McpSyncClient;

import java.util.List;
import java.util.Map;

/**
 * M1：创建用户自助 MCP server 的同步客户端。
 * 抽成接口便于测试替换，生产实现走 stdio 传输。
 */
public interface McpClientFactory {

    McpSyncClient create(String command, List<String> args, Map<String, String> env);
}
