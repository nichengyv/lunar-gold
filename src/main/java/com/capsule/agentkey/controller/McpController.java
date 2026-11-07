package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.mcp.McpGlobalSwitch;
import com.capsule.agentkey.mcp.McpHealthMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * 第五十二批：MCP 健康状态接口。
 * 第六十七批扩展：加全局开关接口。
 *
 * GET /api/mcp/status —— 所有 MCP server 健康快照（已存在）
 * PUT /api/mcp/enabled —— 全局开关（逻辑层面：关掉后 MCP 工具不暴露给模型）
 *
 * 设计决策：
 * - 全局开关只做逻辑层面（Spring AI MCP stdio 没有运行时关闭 API）
 * - McpToolBridge.invoke() 在调用 MCP server 前会检查这个开关
 * - 关掉后模型看不到 MCP 工具——已注册的工具仍然存在，但 invoke 直接拒绝
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@Slf4j
public class McpController {

    private final McpHealthMonitor healthMonitor;
    private final McpGlobalSwitch globalSwitch;
    private final ObjectProvider<List<McpSyncClient>> clientsProvider;

    @GetMapping("/status")
    @RequiresCapability(Capability.MCP_READ)
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<McpSyncClient> clients = clientsProvider.getIfAvailable();
        // 全局开关关闭时覆盖 enabled 字段——前端设置页会正确显示"已禁用"
        boolean clientPresent = clients != null && !clients.isEmpty();
        result.put("enabled", globalSwitch.isEnabled() && clientPresent);
        result.put("servers", healthMonitor.snapshot());
        result.put("timestamp", java.time.Instant.now());
        return result;
    }

    /** 第六十七批：全局开关 */
    @PutMapping("/enabled")
    @RequiresCapability(Capability.MCP_WRITE)
    public ResponseEntity<Map<String, Object>> setEnabled(@RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) return ResponseEntity.badRequest().build();
        globalSwitch.setEnabled(enabled);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", globalSwitch.isEnabled());
        return ResponseEntity.ok(resp);
    }
}
