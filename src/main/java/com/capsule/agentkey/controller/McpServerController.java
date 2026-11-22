package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.dto.McpServerRequest;
import com.capsule.agentkey.dto.McpServerResponse;
import com.capsule.agentkey.service.McpServerService;
import com.capsule.agentkey.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * M1：用户自助 MCP server 管理（仅桌面端，云端没有 MCP 能力）。
 *
 * <p>环境变量只写不读：请求可提交 env，响应只返回 envKeys。
 */
@RestController
@RequestMapping("/api/mcp/servers")
@RequiredArgsConstructor
@Slf4j
public class McpServerController {

    private final McpServerService service;

    @GetMapping
    @RequiresCapability(Capability.MCP_READ)
    public List<McpServerResponse> list() {
        return service.list(CurrentUser.requireId());
    }

    @PostMapping
    @RequiresCapability(Capability.MCP_WRITE)
    public ResponseEntity<McpServerResponse> create(@RequestBody McpServerRequest request) {
        return ResponseEntity.ok(service.create(CurrentUser.requireId(), request));
    }

    @PutMapping("/{id}")
    @RequiresCapability(Capability.MCP_WRITE)
    public ResponseEntity<McpServerResponse> update(@PathVariable Long id,
                                                    @RequestBody McpServerRequest request) {
        return ResponseEntity.ok(service.update(CurrentUser.requireId(), id, request));
    }

    @PutMapping("/{id}/enabled")
    @RequiresCapability(Capability.MCP_WRITE)
    public ResponseEntity<McpServerResponse> setEnabled(@PathVariable Long id,
                                                        @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.setEnabled(CurrentUser.requireId(), id, enabled));
    }

    /** 重启进程并重新拉取工具列表 */
    @PostMapping("/{id}/reload")
    @RequiresCapability(Capability.MCP_WRITE)
    public ResponseEntity<McpServerResponse> reload(@PathVariable Long id) {
        return ResponseEntity.ok(service.reload(CurrentUser.requireId(), id));
    }

    @DeleteMapping("/{id}")
    @RequiresCapability(Capability.MCP_WRITE)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(CurrentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }
}
