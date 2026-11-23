package com.capsule.agentkey.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP server 连接规格：统一描述 stdio / 远程（HTTP/SSE）连接参数。
 *
 * <p>0.1.4 阶段仅 stdio 落地；{@code url}/{@code headers} 为远程传输预留字段。
 * {@link StdioMcpClientFactory} 只接受 {@link TransportType#STDIO}，遇到远程类型抛
 * {@link UnsupportedOperationException}——明确未实现，避免静默失败。未来加
 * {@code HttpMcpClientFactory}/{@code SseMcpClientFactory} 时是新增实现类，不改现有代码。
 */
public record McpConnectionSpec(
        TransportType transportType,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers
) {
    public enum TransportType { STDIO, HTTP, SSE }

    public static McpConnectionSpec stdio(String command, List<String> args, Map<String, String> env) {
        return new McpConnectionSpec(TransportType.STDIO, command, args, env, null, null);
    }
}
