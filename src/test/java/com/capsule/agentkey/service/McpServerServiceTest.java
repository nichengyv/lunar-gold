package com.capsule.agentkey.service;

import com.capsule.agentkey.dto.McpServerRequest;
import com.capsule.agentkey.dto.McpServerResponse;
import com.capsule.agentkey.entity.McpServer;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.mcp.McpServerRuntime;
import com.capsule.agentkey.repository.McpServerRepository;
import com.capsule.agentkey.util.AesUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpServerServiceTest {

    private McpServerRepository repository;
    private McpServerRuntime runtime;
    private AesUtil aesUtil;
    private McpServerService service;

    @BeforeEach
    void setUp() {
        repository = mock(McpServerRepository.class);
        runtime = mock(McpServerRuntime.class);
        aesUtil = mock(AesUtil.class);
        service = new McpServerService(repository, runtime, aesUtil, new ObjectMapper());
        when(runtime.status(any())).thenReturn(new McpServerRuntime.Status(false, 0, null));
        when(repository.save(any(McpServer.class))).thenAnswer(invocation -> {
            McpServer saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(1L);
            }
            return saved;
        });
    }

    @Test
    void 创建后立即尝试连接且只回显环境变量名() throws Exception {
        when(repository.existsByUserIdAndName(1L, "demo")).thenReturn(false);
        when(aesUtil.encrypt(anyString())).thenReturn("encrypted");
        when(aesUtil.decrypt("encrypted")).thenReturn("{\"TOKEN\":\"secret\"}");

        McpServerResponse response = service.create(1L, new McpServerRequest(
                "demo", "npx", List.of("-y", "server"), Map.of("TOKEN", "secret"), true));

        assertEquals("demo", response.name());
        assertEquals(List.of("TOKEN"), response.envKeys());
        assertTrue(response.enabled());
        ArgumentCaptor<McpServer> captor = ArgumentCaptor.forClass(McpServer.class);
        verify(repository).save(captor.capture());
        assertEquals("encrypted", captor.getValue().getEnvEncrypted(), "环境变量必须加密落库");
        verify(runtime).reload(captor.getValue());
    }

    @Test
    void 非法名称与保留名被拒绝() {
        assertThrows(BusinessException.class, () -> service.create(1L,
                new McpServerRequest("bad name!", "npx", List.of(), null, true)));
        assertThrows(BusinessException.class, () -> service.create(1L,
                new McpServerRequest("filesystem", "npx", List.of(), null, true)));
        assertThrows(BusinessException.class, () -> service.create(1L,
                new McpServerRequest("demo", " ", List.of(), null, true)));
    }

    @Test
    void 重名被拒绝() {
        when(repository.existsByUserIdAndName(1L, "demo")).thenReturn(true);

        assertThrows(BusinessException.class, () -> service.create(1L,
                new McpServerRequest("demo", "npx", List.of(), null, true)));
        verify(repository, never()).save(any());
    }

    @Test
    void 更新他人或不存在的数据返回404() {
        when(repository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.update(1L, 9L,
                new McpServerRequest("demo", "npx", List.of(), null, true)));
    }

    @Test
    void 关闭时不启动进程但保留配置() {
        McpServer existing = existing(2L, "demo", true);
        when(repository.findByIdAndUserId(2L, 1L)).thenReturn(Optional.of(existing));

        McpServerResponse response = service.setEnabled(1L, 2L, false);

        assertFalse(response.enabled());
        verify(runtime).reload(existing);
        assertFalse(existing.isEnabled());
    }

    @Test
    void 删除先断开再删库() {
        McpServer existing = existing(3L, "demo", true);
        when(repository.findByIdAndUserId(3L, 1L)).thenReturn(Optional.of(existing));

        service.delete(1L, 3L);

        verify(runtime).remove(3L, "demo");
        verify(repository).delete(existing);
    }

    @Test
    void 列表只返回自己的配置() {
        when(repository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(existing(4L, "demo", true)));

        List<McpServerResponse> servers = service.list(1L);

        assertEquals(1, servers.size());
        assertEquals("demo", servers.get(0).name());
    }

    private McpServer existing(Long id, String name, boolean enabled) {
        McpServer server = new McpServer();
        server.setId(id);
        server.setUserId(1L);
        server.setName(name);
        server.setCommand("npx");
        server.setArgsJson("[]");
        server.setEnabled(enabled);
        return server;
    }
}
