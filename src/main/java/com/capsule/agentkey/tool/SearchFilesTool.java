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

/** 在工作区中搜索包含指定文本的文件（grep） */
@Component
@Slf4j
public class SearchFilesTool implements BaseTool {

    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;

    public SearchFilesTool(ProjectService projectService, WorkspaceFileService workspaceFileService) {
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
    }

    @Override
    public String getName() { return "search_files"; }

    @Override
    public Capability requiredCapability() { return Capability.LOCAL_WORKSPACE; }

    @Override
    public String getDescription() {
        return "在工作区所有文本文件中搜索包含指定内容的行（类似 grep）。" +
                "返回匹配的文件路径、行号和内容。" +
                "适合定位某个函数/类/配置项在哪个文件里。" +
                "可通过 glob 限定文件类型，如 '*.java'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> pattern = new HashMap<>();
        pattern.put("type", "string");
        pattern.put("description", "要搜索的文本内容（子串匹配，不是正则）");
        props.put("pattern", pattern);

        Map<String, Object> glob = new HashMap<>();
        glob.put("type", "string");
        glob.put("description", "可选，文件过滤 glob，如 '*.java'、'*.md'。留空搜索所有文本文件");
        props.put("glob", glob);

        schema.put("properties", props);
        schema.put("required", new String[]{"pattern"});
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            WorkspaceContext ctx = WorkspaceContext.get();
            if (ctx == null) return ToolResult.fail("工作区上下文丢失");
            Project p = projectService.getOwned(ctx.userId(), ctx.projectId());
            if (p.getWorkspacePath() == null) return ToolResult.fail("项目未绑定工作区");
            String pattern = arguments.path("pattern").asText();
            String glob = arguments.path("glob").asText("");
            List<WorkspaceFileService.SearchHit> hits =
                    workspaceFileService.searchInFiles(p.getWorkspacePath(), pattern, glob);
            if (hits.isEmpty()) {
                return ToolResult.ok("没有找到包含 '" + pattern + "' 的内容");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(hits.size()).append(" 处匹配：\n");
            for (WorkspaceFileService.SearchHit h : hits) {
                sb.append(h.file()).append(":").append(h.line()).append(": ")
                        .append(h.content()).append("\n");
            }
            return ToolResult.ok(sb.toString());
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage());
        }
    }
}
