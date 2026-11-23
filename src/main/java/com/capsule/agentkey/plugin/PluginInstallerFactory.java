package com.capsule.agentkey.plugin;

import org.springframework.stereotype.Component;

import com.capsule.agentkey.service.McpServerService;

import java.util.Optional;

/**
 * 根据 {@link PluginManifest} 创建对应的 {@link InstallablePlugin} 实例。
 * 0.1.4 仅 {@link PluginType#MCP} 一种实现；未来新增类型在此扩展分支，不改调用方。
 */
@Component
public class PluginInstallerFactory {

    private final PluginCatalogService catalogService;
    private final McpServerService mcpServerService;

    public PluginInstallerFactory(PluginCatalogService catalogService,
                                  McpServerService mcpServerService) {
        this.catalogService = catalogService;
        this.mcpServerService = mcpServerService;
    }

    public InstallablePlugin create(PluginManifest manifest) {
        PluginType type = manifest.typeOrDefault();
        if (type != PluginType.MCP) {
            throw new IllegalArgumentException("暂不支持安装插件类型: " + type);
        }
        return new McpInstaller(manifest, mcpServerService);
    }

    public Optional<InstallablePlugin> forId(String pluginId) {
        return catalogService.getById(pluginId).map(this::create);
    }
}
