package com.capsule.agentkey.mcp;

import com.capsule.agentkey.entity.McpServer;
import com.capsule.agentkey.repository.McpServerRepository;
import com.capsule.agentkey.service.CapabilityService;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.util.AesUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpServerRuntimeTest {

    private McpClientFactory clientFactory;
    private McpToolBridge bridge;
    private McpHealthMonitor healthMonitor;
    private ToolRegistry toolRegistry;
    private AesUtil aesUtil;
    private McpServerRuntime runtime;
    private McpSyncClient client;

    @BeforeEach
    void setUp() {
        clientFactory = mock(McpClientFactory.class);
        bridge = mock(McpToolBridge.class);
        healthMonitor = mock(McpHealthMonitor.class);
        toolRegistry = mock(ToolRegistry.class);
        aesUtil = mock(AesUtil.class);
        client = mock(McpSyncClient.class);
        runtime = new McpServerRuntime(clientFactory, bridge, healthMonitor, toolRegistry,
                mock(McpServerRepository.class), mock(CapabilityService.class), aesUtil,
                new ObjectMapper());
    }

    @Test
    void 启用后连接并登记工具数量() {
        McpServer server = server(7L, "demo", true);
        when(clientFactory.create(eq("npx"), anyList(), anyMap())).thenReturn(client);
        when(bridge.registerManagedServer(client, "demo"))
                .thenReturn(new McpToolBridge.Registration(List.of("mcp_demo_read"), 0));

        runtime.reload(server);

        McpServerRuntime.Status status = runtime.status(7L);
        assertTrue(status.connected());
        assertEquals(1, status.toolCount());
        verify(client).initialize();
        verify(healthMonitor).attachClient("demo", client);
    }

    @Test
    void 关闭状态只断开不启动进程() {
        McpServer server = server(8L, "disabled", false);

        runtime.reload(server);

        verify(clientFactory, never()).create(anyString(), anyList(), anyMap());
        assertFalse(runtime.status(8L).connected());
    }

    @Test
    void 启动失败记录错误且不标记已连接() {
        McpServer server = server(9L, "broken", true);
        when(clientFactory.create(eq("npx"), anyList(), anyMap()))
                .thenThrow(new RuntimeException("spawn failed"));

        runtime.reload(server);

        McpServerRuntime.Status status = runtime.status(9L);
        assertFalse(status.connected());
        assertEquals(0, status.toolCount());
        assertTrue(status.lastError().contains("spawn failed"), status.lastError());
        verify(healthMonitor).recordFailure(eq("broken"), org.mockito.ArgumentMatchers.contains("spawn failed"));
    }

    @Test
    void 删除时注销工具并关闭进程() {
        McpServer server = server(10L, "demo", true);
        when(clientFactory.create(eq("npx"), anyList(), anyMap())).thenReturn(client);
        when(bridge.registerManagedServer(client, "demo"))
                .thenReturn(new McpToolBridge.Registration(List.of("mcp_demo_read", "mcp_demo_write"), 0));
        runtime.reload(server);

        runtime.remove(10L, "demo");

        verify(toolRegistry).unregister("mcp_demo_read");
        verify(toolRegistry).unregister("mcp_demo_write");
        verify(client).close();
        verify(healthMonitor).unregisterServer("demo");
    }

    @Test
    void 环境变量解密后传给子进程() throws Exception {
        McpServer server = server(11L, "env-demo", true);
        server.setEnvEncrypted("encrypted");
        when(aesUtil.decrypt("encrypted")).thenReturn("{\"TOKEN\":\"secret\"}");
        when(clientFactory.create(eq("npx"), anyList(), anyMap())).thenReturn(client);

        runtime.reload(server);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(clientFactory).create(eq("npx"), anyList(), envCaptor.capture());
        assertEquals("secret", envCaptor.getValue().get("TOKEN"));
    }

    private McpServer server(Long id, String name, boolean enabled) {
        McpServer server = new McpServer();
        server.setId(id);
        server.setName(name);
        server.setCommand("npx");
        server.setArgsJson("[\"-y\",\"@modelcontextprotocol/server-filesystem\"]");
        server.setEnabled(enabled);
        return server;
    }
}
