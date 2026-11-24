package com.capsule.agentkey.mcp;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.dto.McpToolInfo;
import com.capsule.agentkey.entity.McpServer;
import com.capsule.agentkey.repository.McpServerRepository;
import com.capsule.agentkey.service.CapabilityService;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.util.AesUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M1：用户自助 MCP server 的运行时生命周期。
 *
 * <p>负责启动、重连、关闭进程，并把工具注册/注销到 {@link ToolRegistry}。
 * 用户删除或重载时先摘掉工具再关闭 client，避免模型拿到已断开的句柄。
 * 云端模式没有 MCP 能力，启动时直接跳过，绝不执行用户库里的命令。
 */
@Component
@Slf4j
public class McpServerRuntime {

    public record Status(boolean connected, int toolCount, String lastError) {
    }

    private record Managed(String name, McpSyncClient client, List<String> toolNames, String lastError) {
    }

    private final McpClientFactory clientFactory;
    private final McpToolBridge toolBridge;
    private final McpHealthMonitor healthMonitor;
    private final ToolRegistry toolRegistry;
    private final McpServerRepository repository;
    private final CapabilityService capabilityService;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;

    /** serverId → 已建立连接；只保存启用且尝试连接过的记录 */
    private final Map<Long, Managed> managed = new ConcurrentHashMap<>();

    public McpServerRuntime(McpClientFactory clientFactory,
                            McpToolBridge toolBridge,
                            McpHealthMonitor healthMonitor,
                            ToolRegistry toolRegistry,
                            McpServerRepository repository,
                            CapabilityService capabilityService,
                            AesUtil aesUtil,
                            ObjectMapper objectMapper) {
        this.clientFactory = clientFactory;
        this.toolBridge = toolBridge;
        this.healthMonitor = healthMonitor;
        this.toolRegistry = toolRegistry;
        this.repository = repository;
        this.capabilityService = capabilityService;
        this.aesUtil = aesUtil;
        this.objectMapper = objectMapper;
    }

    /** 桌面端启动后连接用户已启用的 server；失败只记录，不影响应用启动 */
    @EventListener(ApplicationReadyEvent.class)
    public void connectConfiguredServers() {
        if (!capabilityService.has(Capability.MCP_READ)) {
            return;
        }
        for (McpServer server : repository.findByEnabledTrueOrderByIdAsc()) {
            reload(server);
        }
    }

    /** 保存/启用后重建连接；关闭状态的 server 只断开不启动 */
    public synchronized void reload(McpServer server) {
        disconnectInternal(server.getId());
        if (!server.isEnabled()) {
            return;
        }
        healthMonitor.registerServer(server.getName());
        McpSyncClient client = null;
        try {
            Map<String, String> env = decryptEnv(server.getEnvEncrypted());
            List<String> args = parseArgs(server.getArgsJson());
            client = clientFactory.create(server.getCommand(), args, env);
            client.initialize();
            McpToolBridge.Registration registration =
                    toolBridge.registerManagedServer(client, server.getName());
            healthMonitor.attachClient(server.getName(), client);
            String error = registration.toolNames().isEmpty()
                    ? healthError(server.getName()) : null;
            managed.put(server.getId(),
                    new Managed(server.getName(), client, registration.toolNames(), error));
            log.info("用户 MCP server [{}] 已连接，注册 {} 个工具",
                    server.getName(), registration.toolNames().size());
        } catch (Exception e) {
            closeQuietly(client);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            healthMonitor.recordFailure(server.getName(), "连接失败: " + message);
            managed.put(server.getId(), new Managed(server.getName(), null, List.of(), message));
            log.warn("用户 MCP server [{}] 连接失败: {}", server.getName(), message);
        }
    }

    /** 删除 server：注销工具、关闭进程、移除健康记录 */
    public synchronized void remove(Long id, String name) {
        disconnectInternal(id);
        healthMonitor.unregisterServer(name);
    }

    public Status status(Long id) {
        Managed current = managed.get(id);
        if (current == null) {
            return new Status(false, 0, null);
        }
        return new Status(current.client() != null, current.toolNames().size(), current.lastError());
    }

    public boolean isConnected(Long id) {
        Managed current = managed.get(id);
        return current != null && current.client() != null;
    }

    /** 已注册工具的清单（远端原名 + 描述）；未连接或无工具时返回空列表 */
    public List<McpToolInfo> tools(Long id) {
        Managed current = managed.get(id);
        if (current == null || current.toolNames().isEmpty()) {
            return List.of();
        }
        return toolBridge.summarize(current.name(), current.toolNames());
    }

    @PreDestroy
    public synchronized void closeAll() {
        for (Long id : List.copyOf(managed.keySet())) {
            disconnectInternal(id);
        }
    }

    private void disconnectInternal(Long id) {
        Managed old = managed.remove(id);
        if (old == null) {
            return;
        }
        old.toolNames().forEach(toolRegistry::unregister);
        closeQuietly(old.client());
        healthMonitor.detachClient(old.name());
        log.info("用户 MCP server [{}] 已断开，注销 {} 个工具", old.name(), old.toolNames().size());
    }

    private List<String> parseArgs(String argsJson) throws Exception {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        List<String> args = objectMapper.readValue(argsJson, new TypeReference<List<String>>() {
        });
        return args == null ? List.of() : args;
    }

    private Map<String, String> decryptEnv(String encrypted) throws Exception {
        if (encrypted == null || encrypted.isBlank()) {
            return Map.of();
        }
        String json = aesUtil.decrypt(encrypted);
        Map<String, String> env = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
        });
        return env == null ? Map.of() : env;
    }

    private String healthError(String serverName) {
        Object error = healthMonitor.snapshot().getOrDefault(serverName, Map.of()).get("lastError");
        return error == null ? "未获取到工具" : String.valueOf(error);
    }

    private void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("关闭 MCP client 失败: {}", e.getMessage());
        }
    }
}
