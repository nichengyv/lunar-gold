package com.capsule.agentkey.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * MCP 全局开关（逻辑层面）。
 *
 * Spring AI MCP stdio 连接启动后没有官方 API 动态关闭——
 * 所以开关只控制「工具是否暴露给模型」：关掉后已注册的 MCP 工具仍然存在，
 * 但 McpToolBridge.invoke() 直接拒绝，ToolRegistry 返回失败给模型。
 *
 * 真正让模型看不到 MCP 工具需要重启（McpToolBridge.run() 在启动时注册）。
 */
@Component
@Slf4j
public class McpGlobalSwitch {

    @Value("${spring.ai.mcp.client.enabled:false}")
    private boolean configuredEnabled;

    /** 默认关闭，启动时读取统一的 Spring AI MCP 配置 */
    private volatile boolean enabled;

    /** McpController / McpToolBridge 都能读 */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("MCP 全局开关已 {}", enabled ? "启用" : "禁用");
    }

    /** 与 spring.ai.mcp.client.enabled 共用一份配置，避免客户端和逻辑开关状态相反。 */
    @EventListener(ApplicationStartedEvent.class)
    public void init() {
        this.enabled = configuredEnabled;
        log.info("MCP 全局开关初始化：{}", enabled ? "启用" : "禁用");
    }
}
