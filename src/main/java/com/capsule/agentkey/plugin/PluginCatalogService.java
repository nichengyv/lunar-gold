package com.capsule.agentkey.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * 插件目录读取服务：从 classpath 的 {@code marketplace.json} 加载 curated 插件清单。
 *
 * <p>阶段 1 内嵌随包（零网络依赖）；阶段 2 换成远程 URL + 缓存，此处接口不变，
 * 前端始终调 {@code /api/plugins/catalog} 拿数据，不内嵌副本。
 */
@Service
public class PluginCatalogService {

    private final Catalog catalog;

    public PluginCatalogService(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("marketplace.json").getInputStream()) {
            this.catalog = objectMapper.readValue(in, Catalog.class);
        } catch (Exception e) {
            throw new IllegalStateException("加载内嵌插件目录 marketplace.json 失败", e);
        }
    }

    public Catalog catalog() {
        return catalog;
    }

    public List<PluginManifest> list() {
        return catalog.plugins() == null ? List.of() : catalog.plugins();
    }

    public Optional<PluginManifest> getById(String pluginId) {
        if (pluginId == null) {
            return Optional.empty();
        }
        return list().stream().filter(p -> pluginId.equals(p.id())).findFirst();
    }
}
