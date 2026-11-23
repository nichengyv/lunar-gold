package com.capsule.agentkey.plugin;

import java.util.List;
import java.util.Map;

/**
 * 插件元数据（来自 catalog）。字段与 {@code marketplace.json} 一一对齐。
 *
 * <p>{@code type} 可空，空值视为 {@link PluginType#MCP}（首批全部为 MCP）。
 * {@code transport} 为字符串（stdio / http / sse），由 Installer 解释。
 */
public record PluginManifest(
        String id,
        String name,
        String description,
        String icon,
        String category,
        PluginType type,
        String transport,
        String command,
        List<String> args,
        Map<String, String> envTemplate,
        List<String> runtime,
        Integer toolCount,
        List<String> requiredPermissions,
        List<CredentialField> credentials,
        String homepageUrl,
        String version
) {
    /** type 为空时回退 MCP */
    public PluginType typeOrDefault() {
        return type == null ? PluginType.MCP : type;
    }
}
