package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.ProjectSummary;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.WorkspaceFileService;
import com.capsule.agentkey.util.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目工作区接口：
 * 1. 绑定/解绑工作区磁盘目录
 * 2. 工作区内文件的列/读/写/搜
 *
 * <p>所有路径都以项目 id 为前缀，因此基路径直接收敛到
 * {@code /api/projects/{projectId}/workspace}，不再占用 {@code /api} 这个公共前缀。
 * 系统文件夹选择窗口与项目无关，见 {@link WorkspacePickerController}。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/workspace")
@RequiresCapability(Capability.LOCAL_WORKSPACE)
public class WorkspaceController {

    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;

    public WorkspaceController(ProjectService projectService,
                               WorkspaceFileService workspaceFileService) {
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
    }

    /** 取当前项目的工作区根路径（未绑定时抛异常，前端据此引导绑定） */
    private String requireWorkspaceRoot(Long projectId) {
        Long userId = CurrentUser.requireId();
        Project p = projectService.getOwned(userId, projectId);
        if (p.getWorkspacePath() == null || p.getWorkspacePath().isBlank()) {
            throw new BusinessException("该项目尚未绑定工作区，请先选择文件夹");
        }
        return p.getWorkspacePath();
    }

    // ==================== 工作区绑定 ====================

    /** 绑定工作区文件夹 */
    @PostMapping("/set-path")
    public ApiResponse<ProjectSummary> setWorkspacePath(@PathVariable Long projectId,
                                                        @RequestBody PathRequest request) {
        Project p = projectService.setWorkspacePath(CurrentUser.requireId(), projectId,
                request == null ? null : request.path());
        return ApiResponse.success(ProjectSummary.from(p));
    }

    // ==================== 工作区文件操作 ====================

    /** 列出工作区目录 */
    @GetMapping("/files")
    public ApiResponse<List<WorkspaceFileService.FileNode>> listFiles(
            @PathVariable Long projectId,
            @RequestParam(required = false, defaultValue = "") String path) {
        String root = requireWorkspaceRoot(projectId);
        return ApiResponse.success(workspaceFileService.listDir(root, path));
    }

    /** 读取工作区文件 */
    @GetMapping("/file")
    public ApiResponse<String> readFile(@PathVariable Long projectId,
                                        @RequestParam String path) {
        String root = requireWorkspaceRoot(projectId);
        return ApiResponse.success(workspaceFileService.readFile(root, path));
    }

    /** 按名字搜索工作区文件（前端 @ 引用选择器用），空关键字返回空列表 */
    @GetMapping("/search-files")
    public ApiResponse<List<WorkspaceFileService.FileNode>> searchFiles(
            @PathVariable Long projectId,
            @RequestParam(required = false, defaultValue = "") String q) {
        String root = requireWorkspaceRoot(projectId);
        return ApiResponse.success(workspaceFileService.findFilesByName(root, q, 15));
    }

    /** 写入/创建工作区文件 */
    @PostMapping("/file")
    public ApiResponse<Void> writeFile(@PathVariable Long projectId,
                                       @RequestBody WriteFileRequest request) {
        String root = requireWorkspaceRoot(projectId);
        workspaceFileService.writeFile(root, request.path(), request.content());
        return ApiResponse.success(null);
    }

    /** 精确编辑（替换第一处匹配） */
    @PatchMapping("/file")
    public ApiResponse<String> editFile(@PathVariable Long projectId,
                                        @RequestBody EditFileRequest request) {
        String root = requireWorkspaceRoot(projectId);
        return ApiResponse.success(workspaceFileService.editFile(
                root, request.path(), request.oldText(), request.newText()));
    }

    /** 新建空文件或空目录（资源管理器右键新建用） */
    @PostMapping("/create")
    public ApiResponse<Void> createEntry(@PathVariable Long projectId,
                                         @RequestBody CreateEntryRequest request) {
        String root = requireWorkspaceRoot(projectId);
        workspaceFileService.createEntry(root, request.path(), Boolean.TRUE.equals(request.directory()));
        return ApiResponse.success(null);
    }

    /** 重命名/移动文件或目录 */
    @PostMapping("/rename")
    public ApiResponse<Void> renameEntry(@PathVariable Long projectId,
                                         @RequestBody RenameEntryRequest request) {
        String root = requireWorkspaceRoot(projectId);
        workspaceFileService.renameEntry(root, request.from(), request.to());
        return ApiResponse.success(null);
    }

    /** 删除文件或目录（目录递归删除，根目录不可删） */
    @DeleteMapping("/entry")
    public ApiResponse<Void> deleteEntry(@PathVariable Long projectId,
                                         @RequestParam String path) {
        String root = requireWorkspaceRoot(projectId);
        workspaceFileService.deleteEntry(root, path);
        return ApiResponse.success(null);
    }

    // ==================== 请求 DTO ====================

    public record PathRequest(String path) {}
    public record WriteFileRequest(String path, String content) {}
    public record EditFileRequest(String path, String oldText, String newText) {}
    public record CreateEntryRequest(String path, Boolean directory) {}
    public record RenameEntryRequest(String from, String to) {}
}
