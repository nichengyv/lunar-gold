package com.capsule.agentkey.config;

import io.modelcontextprotocol.client.McpClient;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 第五十一批：MCP 客户端调优。
 *
 * Spring AI 的自动配置只把 request-timeout 设到 SyncSpec，
 * initialization-timeout 仍是 MCP SDK 默认的 20 秒；而 stdio server 首次启动
 * 可能要先 npx 拉包，20 秒很容易超时（实测冷启动约 22 秒）。
 * 这里通过官方扩展点把初始化超时单独放大，不动 Spring AI 的自动配置。
 */
@Configuration
public class McpClientConfig {

    @Bean
    public McpClientCustomizer<McpClient.SyncSpec> mcpInitializationTimeoutCustomizer(
            @Value("${agent.mcp.client.initialization-timeout:120s}") Duration initializationTimeout) {
        return (name, spec) -> spec.initializationTimeout(initializationTimeout);
    }
}
