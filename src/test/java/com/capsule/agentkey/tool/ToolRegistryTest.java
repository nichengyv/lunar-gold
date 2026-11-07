package com.capsule.agentkey.tool;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.service.CapabilityService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ToolRegistryTest {

    @Test
    void 云端跳过本地命令和MCP工具但保留通用工具() {
        ToolRegistry registry = registry("cloud");

        assertFalse(registry.register(tool("read_file", Capability.LOCAL_WORKSPACE)));
        assertFalse(registry.register(tool("run_command", Capability.COMMAND_EXEC)));
        assertFalse(registry.register(tool("mcp_filesystem_read_file", Capability.MCP_READ)));
        assertFalse(registry.register(tool("mcp_filesystem_write_file", Capability.MCP_WRITE)));
        assertTrue(registry.register(tool("web_search", null)));

        assertFalse(registry.hasTool("read_file"));
        assertFalse(registry.hasTool("run_command"));
        assertFalse(registry.hasTool("mcp_filesystem_read_file"));
        assertFalse(registry.hasTool("mcp_filesystem_write_file"));
        assertTrue(registry.hasTool("web_search"));

        ToolResult result = registry.dispatch("read_file", "{}", null);
        assertFalse(result.isSuccess());
        assertEquals("未知工具: read_file", result.getError());
    }

    @Test
    void 桌面端注册本地命令和MCP工具() {
        ToolRegistry registry = registry("desktop");

        assertTrue(registry.register(tool("read_file", Capability.LOCAL_WORKSPACE)));
        assertTrue(registry.register(tool("run_command", Capability.COMMAND_EXEC)));
        assertTrue(registry.register(tool("mcp_filesystem_read_file", Capability.MCP_READ)));
        assertTrue(registry.register(tool("mcp_filesystem_write_file", Capability.MCP_WRITE)));
        assertTrue(registry.register(tool("web_search", null)));

        assertEquals(5, registry.getAllTools().size());
    }

    private ToolRegistry registry(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new ToolRegistry(mock(ApplicationContext.class), new SimpleMeterRegistry(),
                new CapabilityService(environment));
    }

    private BaseTool tool(String name, Capability requiredCapability) {
        return new BaseTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Map<String, Object> getParametersSchema() {
                return Map.of("type", "object");
            }

            @Override
            public ToolResult execute(JsonNode arguments) {
                return ToolResult.ok("ok");
            }

            @Override
            public Capability requiredCapability() {
                return requiredCapability;
            }
        };
    }
}
