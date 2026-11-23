package com.capsule.agentkey.plugin;

import com.capsule.agentkey.dto.McpServerRequest;
import com.capsule.agentkey.dto.McpServerResponse;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.service.McpServerService;
import com.capsule.agentkey.service.RuntimeDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * T4 验收：McpInstaller 的凭证校验、env 组装、pluginId/version 透传。
 */
class McpInstallerTest {

    private McpServerService mockService;
    private RuntimeDetector mockDetector;
    private PluginManifest github;

    @BeforeEach
    void setUp() {
        mockService = mock(McpServerService.class);
        mockDetector = mock(RuntimeDetector.class);
        github = new PluginManifest(
                "github", "GitHub", "管理 issue/PR", "github", "开发协作",
                PluginType.MCP, "stdio", "npx.cmd",
                List.of("-y", "@modelcontextprotocol/server-github"),
                Map.of("NODE_ENV", "production"),
                List.of("node>=18"), 18,
                List.of("读取用户的 GitHub 仓库与 Issue"),
                List.of(new CredentialField("GITHUB_TOKEN", "GitHub Token",
                        "访问 GitHub 的 Personal Access Token", true, true, "ghp_")),
                "https://github.com/modelcontextprotocol/servers", "1.0.0");
    }

    @Test
    void installWithValidCredentialsPersistsWithPluginFields() {
        when(mockService.createWithPlugin(any(), any(), eq("github"), eq("1.0.0")))
                .thenReturn(new McpServerResponse(7L, "github", "npx.cmd", List.of(),
                        List.of("GITHUB_TOKEN", "NODE_ENV"), true, true, 18, null,
                        "github", "1.0.0"));

        McpInstaller installer = new McpInstaller(github, mockService, mockDetector);
        McpServerResponse resp = installer.install(1L, Map.of("GITHUB_TOKEN", "ghp_test"));

        assertEquals(7L, resp.id());
        ArgumentCaptor<McpServerRequest> captor = ArgumentCaptor.forClass(McpServerRequest.class);
        verify(mockService).createWithPlugin(eq(1L), captor.capture(), eq("github"), eq("1.0.0"));

        McpServerRequest req = captor.getValue();
        assertEquals("github", req.name());
        assertEquals("npx.cmd", req.command());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-github"), req.args());
        // env = 模板预置 + 用户凭证
        assertEquals("production", req.env().get("NODE_ENV"));
        assertEquals("ghp_test", req.env().get("GITHUB_TOKEN"));
        assertTrue(req.enabled());
    }

    @Test
    void installMissingRequiredCredentialThrows() {
        McpInstaller installer = new McpInstaller(github, mockService, mockDetector);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> installer.install(1L, Map.of()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verify(mockService, never()).createWithPlugin(any(), any(), any(), any());
    }

    @Test
    void installWithUnknownCredentialFieldThrows() {
        McpInstaller installer = new McpInstaller(github, mockService, mockDetector);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> installer.install(1L, Map.of("GITHUB_TOKEN", "ghp_test", "EVIL_KEY", "x")));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("EVIL_KEY"));
        verify(mockService, never()).createWithPlugin(any(), any(), any(), any());
    }

    @Test
    void getConfigSchemaAndManifest() {
        McpInstaller installer = new McpInstaller(github, mockService, mockDetector);
        assertSame(github, installer.getManifest());
        assertEquals(1, installer.getConfigSchema().size());
        assertEquals("GITHUB_TOKEN", installer.getConfigSchema().get(0).name());
    }

    @Test
    void uninstallDelegatesToService() {
        McpInstaller installer = new McpInstaller(github, mockService, mockDetector);
        installer.uninstall(1L, 7L);
        verify(mockService).delete(1L, 7L);
    }
}
