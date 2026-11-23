package com.capsule.agentkey.controller;

import java.util.List;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.McpServerResponse;
import com.capsule.agentkey.dto.PluginInstallRequest;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.plugin.Catalog;
import com.capsule.agentkey.plugin.InstallablePlugin;
import com.capsule.agentkey.plugin.PluginInstallerFactory;
import com.capsule.agentkey.service.RuntimeDetector;
import com.capsule.agentkey.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 0.1.4 插件中心：市场目录查询 + 一键安装。
 *
 * <p>与 {@link McpServerController} 同属桌面端内部接口，不纳入 {@code @PublicApi} 公开子集
 * （AI 客户端不应自行安装插件）；云端无 MCP 能力时由 {@code @RequiresCapability} 拦截禁用。
 * 校验异常由 {@code GlobalExceptionHandler} 统一转 400。
 */
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
@Slf4j
public class PluginController {

    private final PluginInstallerFactory installerFactory;
    private final com.capsule.agentkey.plugin.PluginCatalogService catalogService;
    private final RuntimeDetector runtimeDetector;

    @GetMapping("/catalog")
    @RequiresCapability(Capability.MCP_READ)
    public ApiResponse<Catalog> catalog() {
        return ApiResponse.success(catalogService.catalog());
    }

    /**
     * 运行时预检：前端打开安装弹窗时调用，报告每条 runtime 要求是否满足，
     * 不抛异常、不拦截；真正的安装拦截仍在 install 时由后端兜底。
     */
    @GetMapping("/runtime-check")
    @RequiresCapability(Capability.MCP_READ)
    public ApiResponse<List<RuntimeDetector.RuntimeCheckResult>> runtimeCheck(
            @RequestParam(name = "requirements", required = false) List<String> requirements) {
        return ApiResponse.success(runtimeDetector.checkAll(requirements));
    }

    @PostMapping("/install")
    @RequiresCapability(Capability.MCP_WRITE)
    public ApiResponse<McpServerResponse> install(@RequestBody PluginInstallRequest request) {
        if (request == null || request.pluginId() == null || request.pluginId().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "pluginId 不能为空");
        }
        InstallablePlugin<?> installer = installerFactory.forId(request.pluginId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID,
                        "未知插件: " + request.pluginId()));
        Object result = installer.install(CurrentUser.requireId(), request.credentials());
        if (result instanceof McpServerResponse response) {
            return ApiResponse.success(response);
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "插件安装结果类型不支持");
    }
}
