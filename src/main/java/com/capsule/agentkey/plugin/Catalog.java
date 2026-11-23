package com.capsule.agentkey.plugin;

import java.util.List;

/**
 * 插件目录：catalog 顶层结构，对应 marketplace.json。
 */
public record Catalog(
        String version,
        List<PluginManifest> plugins
) {
}
