package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.ProjectSummary;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.FolderPickerService;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.WorkspaceFileService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工作区文件系统接口：
 * 1. 绑定/解绑工作区磁盘目录
 * 2. 弹系统文件夹选择窗口（新建/设置工作区时选目录）
 * 3. 工作区内文件的列/读/写/搜
 */
@RestController
@RequestMapping("/api")
@RequiresCapability(Capability.LOCAL_WORKSPACE)
public class WorkspaceController {

    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;
    private final FolderPickerService folderPickerService;

    public WorkspaceController(ProjectService projectService,
                               WorkspaceFileService workspaceFileService,
                               FolderPickerService folderPickerService) {
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
        this.folderPickerService = folderPickerService;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new BusinessException("用户未认证");
    }

    /** 取当前项目的工作区根路径（未绑定时抛异常，前端据此引导绑定） */
    private String requireWorkspaceRoot(Long projectId) {
        Long userId = getCurrentUserId();
        Project p = projectService.getOwned(userId, projectId);
        if (p.getWorkspacePath() == null || p.getWorkspacePath().isBlank()) {
            throw new BusinessException("该项目尚未绑定工作区，请先选择文件夹");
        }
        return p.getWorkspacePath();
    }

    // ==================== 工作区绑定 ====================

    /** 绑定工作区文件夹 */
    @PostMapping("/projects/{id}/workspace/set-path")
    public ApiResponse<ProjectSummary> setWorkspacePath(@PathVariable Long id,
                                                         @RequestBody PathRequest request) {
        Project p = projectService.setWorkspacePath(getCurrentUserId(), id,
                request == null ? null : request.path());
        return ApiResponse.success(ProjectSummary.from(p));
    }

    // ==================== 系统文件夹选择器 ====================

    /**
     * 弹出操作系统原生的文件夹选择窗口（与"上传附件"同一个资源管理器），返回选中的绝对路径。
     * 用户取消时 data 为 null。请求会一直挂起直到窗口关闭，前端调用时不要设超时。
     */
    @PostMapping("/workspace/pick-folder")
    public ApiResponse<String> pickFolder() {
        return ApiResponse.success(folderPickerService.pickDirectory());
    }

    // ==================== 工作区文件操作 ====================

    /** 列出工作区目录 */
    @GetMapping("/projects/{id}/workspace/files")
    public ApiResponse<List<WorkspaceFileService.FileNode>> listFiles(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "") String path) {
        String root = requireWorkspaceRoot(id);
        return ApiResponse.success(workspaceFileService.listDir(root, path));
    }

    /** 读取工作区文件 */
    @GetMapping("/projects/{id}/workspace/file")
    public ApiResponse<String> readFile(@PathVariable Long id,
                                        @RequestParam String path) {
        String root = requireWorkspaceRoot(id);
        return ApiResponse.success(workspaceFileService.readFile(root, path));
    }

    /** 按名字搜索工作区文件（前端 @ 引用选择器用），空关键字返回空列表 */
    @GetMapping("/projects/{id}/workspace/search-files")
    public ApiResponse<List<WorkspaceFileService.FileNode>> searchFiles(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "") String q) {
        String root = requireWorkspaceRoot(id);
        return ApiResponse.success(workspaceFileService.findFilesByName(root, q, 15));
    }

    /** 写入/创建工作区文件 */
    @PostMapping("/projects/{id}/workspace/file")
    public ApiResponse<Void> writeFile(@PathVariable Long id,
                                       @RequestBody WriteFileRequest request) {
        String root = requireWorkspaceRoot(id);
        workspaceFileService.writeFile(root, request.path(), request.content());
        return ApiResponse.success(null);
    }

    /** 精确编辑（替换第一处匹配） */
    @PatchMapping("/projects/{id}/workspace/file")
    public ApiResponse<String> editFile(@PathVariable Long id,
                                        @RequestBody EditFileRequest request) {
        String root = requireWorkspaceRoot(id);
        return ApiResponse.success(workspaceFileService.editFile(
                root, request.path(), request.oldText(), request.newText()));
    }

    /** 新建空文件或空目录（资源管理器右键新建用） */
    @PostMapping("/projects/{id}/workspace/create")
    public ApiResponse<Void> createEntry(@PathVariable Long id,
                                         @RequestBody CreateEntryRequest request) {
        String root = requireWorkspaceRoot(id);
        workspaceFileService.createEntry(root, request.path(), Boolean.TRUE.equals(request.directory()));
        return ApiResponse.success(null);
    }

    /** 重命名/移动文件或目录 */
    @PostMapping("/projects/{id}/workspace/rename")
    public ApiResponse<Void> renameEntry(@PathVariable Long id,
                                         @RequestBody RenameEntryRequest request) {
        String root = requireWorkspaceRoot(id);
        workspaceFileService.renameEntry(root, request.from(), request.to());
        return ApiResponse.success(null);
    }

    /** 删除文件或目录（目录递归删除，根目录不可删） */
    @DeleteMapping("/projects/{id}/workspace/entry")
    public ApiResponse<Void> deleteEntry(@PathVariable Long id,
                                         @RequestParam String path) {
        String root = requireWorkspaceRoot(id);
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
