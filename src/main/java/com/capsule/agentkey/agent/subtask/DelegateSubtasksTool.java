package com.capsule.agentkey.agent.subtask;

import com.capsule.agentkey.tool.BaseTool;
import com.capsule.agentkey.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code delegate_subtasks} 的合成工具定义（R7c）。
 *
 * <p>本工具<b>不注册进 ToolRegistry</b>：它没有普通工具的执行语义，
 * 由 {@code AgentOrchestrator} 在工具循环最前面拦截，交给
 * {@link SubtaskRunCoordinator} 串行创建并执行子 Run。
 *
 * <p>每个子任务必须带稳定 id、明确目标与验收契约（工件断言或验证要求），
 * 避免把没有证据的自然语言结论伪装成已完成交付。
 */
public class DelegateSubtasksTool implements BaseTool {

    @Override
    public String getName() {
        return SubtaskSpec.TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "把一个大任务拆成最多 4 个串行子任务，每个子任务在独立子 Run 中执行并由真实文件证据验收。"
                + "本工具必须是本轮唯一的工具调用；前一个子任务验收通过后才会启动下一个。"
                + "每个子任务都必须提供稳定 id、明确 objective 与 acceptance（工件断言或要求验证通过）；"
                + "没有验收契约的子任务不会被接受。第一版不支持子任务再委派。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> subtask = new HashMap<>();
        subtask.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> id = new HashMap<>();
        id.put("type", "string");
        id.put("description", "稳定唯一的子任务 id：字母/数字/下划线/短横线，最长 40");
        props.put("id", id);

        Map<String, Object> objective = new HashMap<>();
        objective.put("type", "string");
        objective.put("description", "子任务目标，最长 200 字，需能独立执行");
        props.put("objective", objective);

        Map<String, Object> acceptance = new HashMap<>();
        acceptance.put("type", "object");
        Map<String, Object> acceptanceProps = new HashMap<>();
        Map<String, Object> artifacts = new HashMap<>();
        artifacts.put("type", "array");
        artifacts.put("description", "工件断言：工作区内的安全相对路径 + 文件必须包含的文本片段");
        Map<String, Object> artifact = new HashMap<>();
        artifact.put("type", "object");
        Map<String, Object> artifactProps = new HashMap<>();
        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "相对工作区根的文件路径，禁止绝对路径与 ..");
        artifactProps.put("path", path);
        Map<String, Object> expectedContains = new HashMap<>();
        expectedContains.put("type", "array");
        expectedContains.put("items", Map.of("type", "string"));
        expectedContains.put("description", "该文件中必须出现的文本片段，至少一条");
        artifactProps.put("expectedContains", expectedContains);
        artifact.put("properties", artifactProps);
        artifact.put("required", List.of("path", "expectedContains"));
        artifacts.put("items", artifact);
        acceptanceProps.put("artifacts", artifacts);
        Map<String, Object> requireVerify = new HashMap<>();
        requireVerify.put("type", "boolean");
        requireVerify.put("description", "是否要求子 Run 的最新验证为 PASSED 且覆盖最新文件变更");
        acceptanceProps.put("requireVerify", requireVerify);
        acceptance.put("properties", acceptanceProps);
        acceptance.put("description", "必填验收契约；至少包含一个工件断言或 requireVerify=true");
        props.put("acceptance", acceptance);

        subtask.put("properties", props);
        subtask.put("required", List.of("id", "objective", "acceptance"));

        Map<String, Object> subtasks = new HashMap<>();
        subtasks.put("type", "array");
        subtasks.put("description", "按执行顺序排列的子任务，1-4 个");
        subtasks.put("items", subtask);

        Map<String, Object> schemaProps = new HashMap<>();
        schemaProps.put("subtasks", subtasks);
        schema.put("properties", schemaProps);
        schema.put("required", List.of("subtasks"));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        return ToolResult.fail("delegate_subtasks 只能由父 Run 主循环拦截执行");
    }
}
