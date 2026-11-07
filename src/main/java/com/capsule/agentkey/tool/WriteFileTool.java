package com.capsule.agentkey.tool;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.entity.PendingFileChange;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.WorkspaceFileService;
import com.capsule.agentkey.util.SimpleDiff;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** 写入/创建工作区文件（覆盖） */
@Component
@Slf4j
public class WriteFileTool implements BaseTool {

    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;
    private final PendingFileChangeRepository pfcRepo;

    public WriteFileTool(ProjectService projectService, WorkspaceFileService workspaceFileService,
                         PendingFileChangeRepository pfcRepo) {
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
        this.pfcRepo = pfcRepo;
    }

    @Override
    public String getName() { return "write_file"; }

    @Override
    public Capability requiredCapability() { return Capability.LOCAL_WORKSPACE; }

    @Override
    public String getDescription() {
        return "创建新文件或完全覆盖已有文件的内容。" +
                "如果只需要修改文件的一部分，请使用 edit_file。" +
                "父目录不存在时会自动创建。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "相对于工作区根的文件路径");
        props.put("path", path);
        Map<String, Object> content = new HashMap<>();
        content.put("type", "string");
        content.put("description", "文件的完整文本内容");
        props.put("content", content);
        schema.put("properties", props);
        schema.put("required", new String[]{"path", "content"});
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            WorkspaceContext ctx = WorkspaceContext.get();
            if (ctx == null) return ToolResult.fail("工作区上下文丢失");
            Project p = projectService.getOwned(ctx.userId(), ctx.projectId());
            if (p.getWorkspacePath() == null) return ToolResult.fail("项目未绑定工作区");
            String path = arguments.path("path").asText();
            String content = arguments.path("content").asText("");

            //写前先拿到旧内容，用于生成 diff；新文件按「从空内容创建」处理
            Path root = Paths.get(p.getWorkspacePath());
            Path file = workspaceFileService.resolveInWorkspace(root, path);
            String relative = root.relativize(file).toString().replace('\\', '/');
            boolean existed = Files.isRegularFile(file);
            String oldContent = existed ? readOldQuietly(file) : "";

            workspaceFileService.writeFile(p.getWorkspacePath(), path, content);

            SimpleDiff.Result diff = SimpleDiff.compute(oldContent == null ? "" : oldContent, content, 400);
            String diffText = oldContent == null ? "（原文件过大或不可读，未生成逐行对比）" : diff.text();
            //覆盖写入但内容没变时不产生变更记录，避免抽屉里出现空变更
            if (!existed || !diffText.isEmpty()) {
                WorkspaceContext.recordFileChange(new FileChange(
                        relative, existed ? "modify" : "create", "write_file",
                        diff.additions(), diff.deletions(), diffText));

                // 审查流落库：同时存 oldContent/newContent 支持撤销
                try {
                    WorkspaceContext ctxAfter = WorkspaceContext.get();
                    if (ctxAfter != null) {
                        PendingFileChange pfc = new PendingFileChange();
                        pfc.setProjectId(ctxAfter.projectId());
                        pfc.setSessionId(ctxAfter.sessionId());
                        pfc.setTurnId(ctxAfter.turnId());
                        pfc.setUserId(ctxAfter.userId());
                        pfc.setFilePath(relative);
                        pfc.setAction(existed ? "modify" : "create");
                        pfc.setSource("write_file");
                        pfc.setOldContent(existed ? oldContent : null);
                        pfc.setNewContent(content);
                        pfc.setDiffText(diffText);
                        pfc.setAdditions(diff.additions());
                        pfc.setDeletions(diff.deletions());
                        pfcRepo.save(pfc);
                    }
                } catch (Exception ex) {
                    // 审查流落库失败不影响写文件主流程
                    log.warn("PendingFileChange 落库失败: {}", ex.getMessage());
                }
            }
            return ToolResult.ok("已写入文件：" + path);
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage());
        }
    }

    /** 读旧内容生成 diff；文件不存在/不可读/过大时返回 null，避免因为展示 diff 影响主流程 */
    private String readOldQuietly(Path file) {
        try {
            if (Files.size(file) > 512 * 1024) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("读取旧文件内容失败，跳过 diff: {}", file);
            return null;
        }
    }
}
