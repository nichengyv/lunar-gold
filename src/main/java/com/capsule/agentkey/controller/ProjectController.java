package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.PublicApi;
import com.capsule.agentkey.config.PublicApiCatalog;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.ProjectSummary;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.util.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目（工作区）接口：列表、新建、删除。默认项目在首次访问时自动创建。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    @PublicApi(scope = "project:read", group = PublicApiCatalog.GROUP_CONTEXT)
    public ApiResponse<List<ProjectSummary>> list() {
        return ApiResponse.success(projectService.listByUserId(CurrentUser.requireId())
                .stream().map(ProjectSummary::from).toList());
    }

    @PostMapping
    public ApiResponse<ProjectSummary> create(@RequestBody CreateProjectRequest request) {
        Project project = projectService.create(
                CurrentUser.requireId(),
                request == null ? null : request.name(),
                request == null ? null : request.description());
        return ApiResponse.success(ProjectSummary.from(project));
    }

    public record CreateProjectRequest(String name, String description) {
    }

    /** 删除工作区：会话、资料、向量一并删除；磁盘上的代码文件夹不受影响 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        projectService.deleteOwned(CurrentUser.requireId(), id);
        return ApiResponse.success(null);
    }

    /** 更新项目自定义指令：空字符串 = 清空 */
    @PatchMapping("/{id}/instruction")
    public ApiResponse<ProjectSummary> updateInstruction(@PathVariable Long id,
                                                          @RequestBody InstructionRequest request) {
        Project project = projectService.updateInstruction(CurrentUser.requireId(), id,
                request == null ? null : request.instruction());
        return ApiResponse.success(ProjectSummary.from(project));
    }

    public record InstructionRequest(String instruction) {
    }

    /** 设置项目默认工作权限：新会话未显式选择时继承该值；传空字符串 = 取消设置 */
    @PatchMapping("/{id}/default-permission")
    public ApiResponse<ProjectSummary> updateDefaultPermission(@PathVariable Long id,
                                                              @RequestBody DefaultPermissionRequest request) {
        Project project = projectService.updateDefaultPermission(CurrentUser.requireId(), id,
                request == null ? null : request.permission());
        return ApiResponse.success(ProjectSummary.from(project));
    }

    public record DefaultPermissionRequest(String permission) {
    }
}
