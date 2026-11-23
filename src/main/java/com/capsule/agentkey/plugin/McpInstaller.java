package com.capsule.agentkey.plugin;

import com.capsule.agentkey.dto.McpServerRequest;
import com.capsule.agentkey.dto.McpServerResponse;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.service.McpServerService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 类型插件的安装器：把 catalog 里的 {@link PluginManifest} + 用户凭证组装成
 * {@code mcp_server} 记录并启动。非 Spring Bean，由 {@link PluginInstallerFactory}
 * 针对每个 manifest 创建实例。
 */
public class McpInstaller implements InstallablePlugin<McpServerResponse> {

    private final PluginManifest manifest;
    private final McpServerService mcpServerService;

    public McpInstaller(PluginManifest manifest, McpServerService mcpServerService) {
        this.manifest = manifest;
        this.mcpServerService = mcpServerService;
    }

    @Override
    public PluginManifest getManifest() {
        return manifest;
    }

    @Override
    public List<CredentialField> getConfigSchema() {
        return manifest.credentials() == null ? List.of() : manifest.credentials();
    }

    @Override
    public McpServerResponse install(Long userId, Map<String, String> userInput) {
        validateCredentials(userInput);

        // env = manifest 预置模板 + 用户填写的凭证（用户值覆盖模板同名 key）
        Map<String, String> env = new LinkedHashMap<>();
        if (manifest.envTemplate() != null) {
            env.putAll(manifest.envTemplate());
        }
        if (userInput != null) {
            env.putAll(userInput);
        }

        // name 用 manifest.id 作为工具前缀；重名/重复安装由 service 层校验拦截
        McpServerRequest request = new McpServerRequest(
                manifest.id(), manifest.command(), manifest.args(), env, true);
        return mcpServerService.createWithPlugin(userId, request, manifest.id(), manifest.version());
    }

    @Override
    public void uninstall(Long userId, Long instanceId) {
        mcpServerService.delete(userId, instanceId);
    }

    private void validateCredentials(Map<String, String> userInput) {
        List<CredentialField> fields = getConfigSchema();
        // 1. 必填校验
        for (CredentialField f : fields) {
            if (f.required()) {
                String value = userInput == null ? null : userInput.get(f.name());
                if (value == null || value.isBlank()) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "缺少必填凭证: " + (f.label() == null ? f.name() : f.label()));
                }
            }
        }
        // 2. 禁止未定义字段（防止注入 catalog 之外的环境变量）
        if (userInput != null) {
            for (String key : userInput.keySet()) {
                boolean defined = fields.stream().anyMatch(f -> key.equals(f.name()));
                if (!defined) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "未知凭证字段: " + key);
                }
            }
        }
    }
}
