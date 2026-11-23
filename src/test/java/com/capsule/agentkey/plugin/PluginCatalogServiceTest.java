package com.capsule.agentkey.plugin;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T3 验收：内嵌 marketplace.json 能被正确加载与查询。
 */
class PluginCatalogServiceTest {

    @Test
    void loadsEmbeddedCatalogAndGithubManifest() {
        PluginCatalogService svc = new PluginCatalogService(new ObjectMapper());

        assertEquals("1", svc.catalog().version());
        assertFalse(svc.list().isEmpty(), "catalog 至少应有一个插件");

        PluginManifest github = svc.getById("github").orElseThrow(() ->
                new AssertionError("应能查到 id=github 的插件"));
        assertEquals("GitHub", github.name());
        assertEquals("stdio", github.transport());
        assertEquals("npx.cmd", github.command());
        assertEquals(PluginType.MCP, github.typeOrDefault());
        assertNotNull(github.args());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-github"), github.args());
        assertNotNull(github.credentials());
        assertEquals(1, github.credentials().size());
        CredentialField token = github.credentials().get(0);
        assertEquals("GITHUB_TOKEN", token.name());
        assertTrue(token.required());
        assertTrue(token.secret());
    }

    @Test
    void getByIdReturnsEmptyForUnknown() {
        PluginCatalogService svc = new PluginCatalogService(new ObjectMapper());
        assertTrue(svc.getById("nonexistent").isEmpty());
        assertTrue(svc.getById(null).isEmpty());
    }

    @Test
    void listDoesNotReturnNull() {
        PluginCatalogService svc = new PluginCatalogService(new ObjectMapper());
        assertNotNull(svc.list());
    }
}
