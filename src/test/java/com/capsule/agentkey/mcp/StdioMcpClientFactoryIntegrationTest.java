package com.capsule.agentkey.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 传输冒烟：真实启动一个 stdio 子进程，完成握手、列工具与调用。
 * 没有 Node 时跳过，避免在无前端工具链的环境里误报。
 */
class StdioMcpClientFactoryIntegrationTest {

    @Test
    void 与真实stdio服务完成握手和工具调用() {
        Assumptions.assumeTrue(nodeAvailable(), "需要 Node.js 才能运行 stdio MCP 冒烟");

        Path script = Path.of("src/test/resources/mcp/test-mcp-server.js").toAbsolutePath();
        StdioMcpClientFactory factory = new StdioMcpClientFactory(
                Duration.ofSeconds(20), Duration.ofSeconds(20), "test-suite");

        try (McpSyncClient client = factory.create("node", List.of(script.toString()), Map.of())) {
            client.initialize();

            McpSchema.ListToolsResult tools = client.listTools();
            assertTrue(tools.tools().stream().anyMatch(tool -> "ping".equals(tool.name())),
                    "未拿到测试工具: " + tools.tools());

            McpSchema.CallToolResult result =
                    client.callTool(new McpSchema.CallToolRequest("ping", Map.of()));
            assertFalse(Boolean.TRUE.equals(result.isError()));
            assertTrue(result.content().toString().contains("pong"), result.content().toString());
        }
    }

    private boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
