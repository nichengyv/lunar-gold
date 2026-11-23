package com.capsule.agentkey.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * M1：以 stdio 方式启动用户配置的 MCP server。
 *
 * <p>命令由 {@link ProcessBuilder} 直接执行，不经过 shell，因此参数里的
 * {@code ;}、{@code &&} 不会被解释成额外命令。初始化超时与 request 超时可通过配置调整。
 */
@Component
public class StdioMcpClientFactory implements McpClientFactory {

    private final JsonMapper jsonMapper = JsonMapper.shared();
    private final Duration requestTimeout;
    private final Duration initializationTimeout;
    private final String clientName;

    public StdioMcpClientFactory(
            @Value("${agent.mcp.user-servers.request-timeout:60s}") Duration requestTimeout,
            @Value("${agent.mcp.user-servers.initialization-timeout:120s}") Duration initializationTimeout,
            @Value("${spring.application.name:lunar-gold}") String clientName) {
        this.requestTimeout = requestTimeout;
        this.initializationTimeout = initializationTimeout;
        this.clientName = clientName;
    }

    @Override
    public McpSyncClient create(McpConnectionSpec spec) {
        if (spec.transportType() != McpConnectionSpec.TransportType.STDIO) {
            throw new UnsupportedOperationException(
                    "StdioMcpClientFactory 仅支持 STDIO，不支持 " + spec.transportType()
                            + "；远程传输（HTTP/SSE）尚未实现");
        }
        ServerParameters parameters = ServerParameters.builder(spec.command())
                .args(spec.args() == null ? List.of() : spec.args())
                .env(spec.env() == null ? Map.of() : spec.env())
                .build();
        StdioClientTransport transport = new StdioClientTransport(
                parameters, new JacksonMcpJsonMapper(jsonMapper));
        return McpClient.sync(transport)
                .requestTimeout(requestTimeout)
                .initializationTimeout(initializationTimeout)
                .clientInfo(new McpSchema.Implementation(clientName + "-mcp", "lunar-gold"))
                .build();
    }
}
