package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.service.FolderPickerService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统文件夹选择器接口。
 *
 * <p>与工作区文件操作分开，是因为它不属于任何项目（新建项目时还没项目 id），
 * 路由基路径是 {@code /api/workspace}，而不是 {@code /api/projects/{id}/workspace}。
 */
@RestController
@RequestMapping("/api/workspace")
@RequiresCapability(Capability.LOCAL_WORKSPACE)
public class WorkspacePickerController {

    private final FolderPickerService folderPickerService;

    public WorkspacePickerController(FolderPickerService folderPickerService) {
        this.folderPickerService = folderPickerService;
    }

    /**
     * 弹出操作系统原生的文件夹选择窗口（与"上传附件"同一个资源管理器），返回选中的绝对路径。
     * 用户取消时 data 为 null。请求会一直挂起直到窗口关闭，前端调用时不要设超时。
     */
    @PostMapping("/pick-folder")
    public ApiResponse<String> pickFolder() {
        return ApiResponse.success(folderPickerService.pickDirectory());
    }
}
