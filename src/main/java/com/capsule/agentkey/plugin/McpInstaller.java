package com.capsule.agentkey.plugin;

import com.capsule.agentkey.dto.McpServerRequest;
import com.capsule.agentkey.dto.McpServerResponse;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.service.McpServerService;
import com.capsule.agentkey.service.RuntimeDetector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 类型插件的安装器：把 catalog 里的 {@link PluginManifest} + 用户凭证组装成
 * {@code mcp_server} 记录并启动。非 Spring Bean，由 {@link PluginInstallerFactory}
 * 针对每个 manifest 创建实例。
 */
public class McpInstaller implements InstallablePlugin<McpServerResponse> {

    private final PluginManifest manifest;
    private final McpServerService mcpServerService;
    private final RuntimeDetector runtimeDetector;

    public McpInstaller(PluginManifest manifest, McpServerService mcpServerService,
                        RuntimeDetector runtimeDetector) {
        this.manifest = manifest;
        this.mcpServerService = mcpServerService;
        this.runtimeDetector = runtimeDetector;
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
        runtimeDetector.requireAll(manifest.runtime());
        validateCredentials(userInput);

        // args 占位符替换：manifest.args 里的 "${KEY}" 用用户输入替换（逗号分隔展开多值）
        List<String> args = resolveArgs(manifest.args(), userInput);
        Set<String> argKeys = collectArgKeys(manifest.args());

        // env = 预置模板 + 用户凭证（被 args 占位符消费的字段不进 env，避免重复注入）
        Map<String, String> env = new LinkedHashMap<>();
        if (manifest.envTemplate() != null) {
            env.putAll(manifest.envTemplate());
        }
        if (userInput != null) {
            userInput.forEach((key, value) -> {
                if (!argKeys.contains(key)) {
                    env.put(key, value);
                }
            });
        }

        // name 用 manifest.id 作为工具前缀；重名/重复安装由 service 层校验拦截
        McpServerRequest request = new McpServerRequest(
                manifest.id(), manifest.command(), args, env, true);
        return mcpServerService.createWithPlugin(userId, request, manifest.id(), manifest.version());
    }

    private static final Pattern ARG_PLACEHOLDER = Pattern.compile("^\\$\\{([^}]+)\\}$");

    /** 把 args 中的 "${KEY}" 占位符替换为用户输入；逗号分隔的值展开成多个参数 */
    private List<String> resolveArgs(List<String> args, Map<String, String> userInput) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>(args.size());
        for (String arg : args) {
            Matcher matcher = ARG_PLACEHOLDER.matcher(arg);
            if (matcher.matches()) {
                String key = matcher.group(1);
                String value = userInput == null ? null : userInput.get(key);
                if (value == null || value.isBlank()) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少必填参数: " + key);
                }
                for (String part : value.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        resolved.add(trimmed);
                    }
                }
            } else {
                resolved.add(arg);
            }
        }
        return resolved;
    }

    private Set<String> collectArgKeys(List<String> args) {
        if (args == null) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (String arg : args) {
            Matcher matcher = ARG_PLACEHOLDER.matcher(arg);
            if (matcher.matches()) {
                keys.add(matcher.group(1));
            }
        }
        return keys;
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
