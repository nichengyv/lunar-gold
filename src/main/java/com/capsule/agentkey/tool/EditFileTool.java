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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** 精确编辑：把文件中第一处出现的 oldText 替换为 newText */
@Component
@Slf4j
public class EditFileTool implements BaseTool {

    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;
    private final PendingFileChangeRepository pfcRepo;

    public EditFileTool(ProjectService projectService, WorkspaceFileService workspaceFileService,
                        PendingFileChangeRepository pfcRepo) {
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
        this.pfcRepo = pfcRepo;
    }

    @Override
    public String getName() { return "edit_file"; }

    @Override
    public Capability requiredCapability() { return Capability.LOCAL_WORKSPACE; }

    @Override
    public String getDescription() {
        return "精确替换文件中的一段文本。" +
                "只会替换第一处匹配的 oldText。" +
                "如果原文中找不到 oldText，会返回失败——此时请先用 read_file 查看实际内容。" +
                "适合做小范围修改；大范围改动请用 write_file 整体覆盖。";
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

        Map<String, Object> oldText = new HashMap<>();
        oldText.put("type", "string");
        oldText.put("description", "要被替换的原文片段，必须与文件中的内容完全一致");
        props.put("oldText", oldText);

        Map<String, Object> newText = new HashMap<>();
        newText.put("type", "string");
        newText.put("description", "替换后的新文本");
        props.put("newText", newText);

        schema.put("properties", props);
        schema.put("required", new String[]{"path", "oldText", "newText"});
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
            String oldText = arguments.path("oldText").asText();
            String newText = arguments.path("newText").asText("");

            //编辑前后各读一次内容，用于向前端展示这次改了哪几行
            Path root = Paths.get(p.getWorkspacePath());
            Path file = workspaceFileService.resolveInWorkspace(root, path);
            String relative = root.relativize(file).toString().replace('\\', '/');
            String before = workspaceFileService.readFile(p.getWorkspacePath(), path);

            String result = workspaceFileService.editFile(p.getWorkspacePath(), path, oldText, newText);

            String after = workspaceFileService.readFile(p.getWorkspacePath(), path);
            SimpleDiff.Result diff = SimpleDiff.compute(before, after, 400);
            if (!diff.text().isEmpty()) {
                WorkspaceContext.recordFileChange(new FileChange(
                        relative, "modify", "edit_file",
                        diff.additions(), diff.deletions(), diff.text()));

                // 审查流落库
                try {
                    WorkspaceContext ctxAfter = WorkspaceContext.get();
                    if (ctxAfter != null) {
                        PendingFileChange pfc = new PendingFileChange();
                        pfc.setProjectId(ctxAfter.projectId());
                        pfc.setSessionId(ctxAfter.sessionId());
                        pfc.setTurnId(ctxAfter.turnId());
                        pfc.setUserId(ctxAfter.userId());
                        pfc.setFilePath(relative);
                        pfc.setAction("modify");
                        pfc.setSource("edit_file");
                        pfc.setOldContent(before);
                        pfc.setNewContent(after);
                        pfc.setDiffText(diff.text());
                        pfc.setAdditions(diff.additions());
                        pfc.setDeletions(diff.deletions());
                        pfcRepo.save(pfc);
                    }
                } catch (Exception ex) {
                    log.warn("PendingFileChange 落库失败: {}", ex.getMessage());
                }
            }
            return ToolResult.ok(result + "：" + path);
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage());
        }
    }
}
