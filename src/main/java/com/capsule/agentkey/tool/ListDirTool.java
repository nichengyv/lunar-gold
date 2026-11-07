package com.capsule.agentkey.tool;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.WorkspaceFileService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 列出工作区目录内容。path 为相对路径，空表示工作区根目录 */
@Component
@Slf4j
public class ListDirTool implements BaseTool {

    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;

    public ListDirTool(ProjectService projectService, WorkspaceFileService workspaceFileService) {
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
    }

    @Override
    public String getName() { return "list_dir"; }

    @Override
    public Capability requiredCapability() { return Capability.LOCAL_WORKSPACE; }

    @Override
    public String getDescription() {
        return "列出工作区中指定目录的文件和子文件夹。" +
                "首次进入项目时应先调用此工具（path 留空）了解项目结构。" +
                "返回内容包含每项的名称、是否为目录、大小。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "相对于工作区根的目录路径，留空表示根目录。例如 'src/main/java'");
        props.put("path", path);
        schema.put("properties", props);
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            WorkspaceContext ctx = WorkspaceContext.get();
            if (ctx == null) return ToolResult.fail("工作区上下文丢失");
            Project p = projectService.getOwned(ctx.userId(), ctx.projectId());
            if (p.getWorkspacePath() == null) return ToolResult.fail("项目未绑定工作区");
            String relPath = arguments.path("path").asText("");
            List<WorkspaceFileService.FileNode> nodes = workspaceFileService.listDir(p.getWorkspacePath(), relPath);
            StringBuilder sb = new StringBuilder();
            sb.append(relPath.isEmpty() ? "工作区根目录" : relPath).append(":\n");
            for (WorkspaceFileService.FileNode n : nodes) {
                String type = n.isDirectory() ? "[目录]" : "[文件]";
                String size = n.isDirectory() ? "" : " (" + n.size() + " B)";
                sb.append("  ").append(type).append(" ").append(n.name()).append(size).append("\n");
            }
            if (nodes.isEmpty()) sb.append("  (空目录)\n");
            return ToolResult.ok(sb.toString());
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage());
        }
    }
}
