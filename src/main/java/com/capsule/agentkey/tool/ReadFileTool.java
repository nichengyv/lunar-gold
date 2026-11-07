package com.capsule.agentkey.tool;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.WorkspaceFileService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** 读取工作区文件内容（文本），大文件会被截断 */
@Component
@Slf4j
public class ReadFileTool implements BaseTool {

    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;

    public ReadFileTool(ProjectService projectService, WorkspaceFileService workspaceFileService) {
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
    }

    @Override
    public String getName() { return "read_file"; }

    @Override
    public Capability requiredCapability() { return Capability.LOCAL_WORKSPACE; }

    @Override
    public String getDescription() {
        return "读取工作区中某个文件的完整内容。" +
                "只能读取文本文件（代码、配置、文档等），不支持图片/二进制。" +
                "文件过大时会被截断，此时请用 search_files 定位具体内容。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "相对于工作区根的文件路径，如 'src/main/java/com/example/App.java'");
        props.put("path", path);
        schema.put("properties", props);
        schema.put("required", new String[]{"path"});
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
            String content = workspaceFileService.readFile(p.getWorkspacePath(), path);
            return ToolResult.ok(content);
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage());
        }
    }
}
