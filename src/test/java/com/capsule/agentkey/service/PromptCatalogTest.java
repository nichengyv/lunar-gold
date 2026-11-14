package com.capsule.agentkey.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5-01：提示词目录的加载、校验、版本切换与失败回滚。
 */
class PromptCatalogTest {

    private static final String INDEX = "prompts-test/index.json";

    @Test
    void loadsDefaultVersionAndScenarios() {
        PromptCatalog catalog = new PromptCatalog(new MockEnvironment(), INDEX);

        assertEquals("agent-system@v1", catalog.describe().get("agent"));
        assertEquals("tool-mcp@v1", catalog.describe().get("tool-mcp"));
        assertTrue(catalog.text("agent").contains("测试用提示词 v1"));
    }

    @Test
    void loadsCodingScenarioFromProductionIndex() {
        PromptCatalog catalog = new PromptCatalog(new MockEnvironment());

        assertEquals("coding-system@v1", catalog.describe().get("coding"));
        assertTrue(catalog.text("coding").contains("【完成与验证】"));
        assertTrue(catalog.text("coding").contains("未验证"));
    }

    @Test
    void configSwitchesVersion() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("agent.prompt.scenario.agent.version", "v2");

        PromptCatalog catalog = new PromptCatalog(env, INDEX);

        assertEquals("agent-system@v2", catalog.describe().get("agent"));
        assertTrue(catalog.text("agent").contains("测试用提示词 v2"));
    }

    @Test
    void unknownVersionFailsFast() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("agent.prompt.scenario.agent.version", "v9");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(env, INDEX));
        assertTrue(error.getMessage().contains("未知提示词版本"));
    }

    @Test
    void hashMismatchIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(new MockEnvironment(), "prompts-test/bad-index.json"));
        assertTrue(error.getMessage().contains("哈希不匹配"));
    }

    @Test
    void failedReloadKeepsPreviousSnapshot() {
        PromptCatalog catalog = new PromptCatalog(new MockEnvironment(), INDEX);
        String before = catalog.text("agent");

        // 用坏索引模拟「重载时文件被改坏」：抛异常，但旧内容仍在（回滚）
        assertThrows(IllegalStateException.class, () -> catalog.reloadFrom("prompts-test/bad-index.json"));

        assertEquals("agent-system@v1", catalog.describe().get("agent"));
        assertEquals(before, catalog.text("agent"));
    }
}
