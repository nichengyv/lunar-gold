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
import java.util.ArrayList;
import java.util.HashMap;
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
        List<String> args = spec.args() == null ? List.of() : spec.args();
        Map<String, String> env = new HashMap<>(spec.env() == null ? Map.of() : spec.env());
        String base = McpNodeRuntime.baseName(spec.command());
        // 隔离 npm 缓存，避免与用户全局缓存互相锁
        if (("npx".equals(base) || "npm".equals(base)) && !env.containsKey("NPM_CONFIG_CACHE")) {
            String cache = McpNodeRuntime.npmCacheDir();
            if (cache != null) {
                env.put("NPM_CONFIG_CACHE", cache);
            }
        }
        String command = null;
        // 内置 Node 优先：npx/npm 走 node <cli.js>，不依赖系统 PATH 与 .cmd 包装
        if (McpNodeRuntime.home() != null) {
            if ("npx".equals(base) || "npm".equals(base)) {
                var cli = McpNodeRuntime.cli(base);
                var node = McpNodeRuntime.executable();
                if (cli != null && node != null) {
                    command = node.toString();
                    List<String> merged = new ArrayList<>(args.size() + 1);
                    merged.add(cli.toString());
                    merged.addAll(args);
                    args = merged;
                }
            } else if ("node".equals(base)) {
                var node = McpNodeRuntime.executable();
                if (node != null) {
                    command = node.toString();
                }
            }
        }
        if (command == null) {
            command = normalizeCommand(spec.command());
        }
        ServerParameters parameters = ServerParameters.builder(command)
                .args(args)
                .env(env)
                .build();
        StdioClientTransport transport = new StdioClientTransport(
                parameters, new JacksonMcpJsonMapper(jsonMapper));
        return McpClient.sync(transport)
                .requestTimeout(requestTimeout)
                .initializationTimeout(initializationTimeout)
                .clientInfo(new McpSchema.Implementation(clientName + "-mcp", "lunar-gold"))
                .build();
    }

    /**
     * Windows 下 npm/npx/pnpm/yarn 实际是 .cmd 包装脚本，ProcessBuilder 不会自动补扩展名，
     * 直接执行 "npx" 会失败。这里按平台补齐，让从官方文档复制的配置（command: "npx"）开箱可用。
     * 非 Windows 或已带路径/扩展名的命令原样返回。
     */
    static String normalizeCommand(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.startsWith("windows")) {
            return command;
        }
        String lower = command.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("/") || lower.contains("\\") || lower.contains(".")) {
            return command;
        }
        return switch (lower) {
            case "npx", "npm", "pnpm", "yarn" -> command + ".cmd";
            default -> command;
        };
    }
}
