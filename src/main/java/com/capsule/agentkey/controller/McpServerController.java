package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.McpServerRequest;
import com.capsule.agentkey.dto.McpServerResponse;
import com.capsule.agentkey.dto.McpToolInfo;
import com.capsule.agentkey.service.McpServerService;
import com.capsule.agentkey.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * M1：用户自助 MCP server 管理（仅桌面端，云端没有 MCP 能力）。
 *
 * <p>所有返回统一用 {@link ApiResponse} 包装，与前端 axios unwrap 解包契约一致。
 * 环境变量只写不读：请求可提交 env，响应只返回 envKeys。
 */
@RestController
@RequestMapping("/api/mcp/servers")
@RequiredArgsConstructor
@Slf4j
public class McpServerController {

    private final McpServerService service;

    @GetMapping
    @RequiresCapability(Capability.MCP_READ)
    public ApiResponse<List<McpServerResponse>> list() {
        return ApiResponse.success(service.list(CurrentUser.requireId()));
    }

    @PostMapping
    @RequiresCapability(Capability.MCP_WRITE)
    public ApiResponse<McpServerResponse> create(@RequestBody McpServerRequest request) {
        return ApiResponse.success(service.create(CurrentUser.requireId(), request));
    }

    @PutMapping("/{id}")
    @RequiresCapability(Capability.MCP_WRITE)
    public ApiResponse<McpServerResponse> update(@PathVariable Long id,
                                                 @RequestBody McpServerRequest request) {
        return ApiResponse.success(service.update(CurrentUser.requireId(), id, request));
    }

    @PutMapping("/{id}/enabled")
    @RequiresCapability(Capability.MCP_WRITE)
    public ApiResponse<McpServerResponse> setEnabled(@PathVariable Long id,
                                                     @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        return ApiResponse.success(service.setEnabled(CurrentUser.requireId(), id, enabled));
    }

    /** 重启进程并重新拉取工具列表 */
    @PostMapping("/{id}/reload")
    @RequiresCapability(Capability.MCP_WRITE)
    public ApiResponse<McpServerResponse> reload(@PathVariable Long id) {
        return ApiResponse.success(service.reload(CurrentUser.requireId(), id));
    }

    /** 工具清单预览：已注册工具的远端原名与描述 */
    @GetMapping("/{id}/tools")
    @RequiresCapability(Capability.MCP_READ)
    public ApiResponse<List<McpToolInfo>> tools(@PathVariable Long id) {
        return ApiResponse.success(service.tools(CurrentUser.requireId(), id));
    }

    @DeleteMapping("/{id}")
    @RequiresCapability(Capability.MCP_WRITE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(CurrentUser.requireId(), id);
        return ApiResponse.success(null);
    }
}
