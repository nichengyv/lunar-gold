package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第六十八批 A1：任务计划工具。
 *
 * 模型处理多步任务时先提交步骤清单，执行中再逐步更新每个步骤的状态；
 * 计划只写内存（WorkspaceContext），由 ReAct 循环推给前端，不碰文件系统，
 * 因此三档工作权限都可见。
 */
@Component
@Slf4j
public class UpdatePlanTool implements BaseTool {

    /** 合法状态：待开始 / 进行中 / 已完成 / 已中止（Agent 放弃后自动标记后续步骤） */
    private static final Set<String> VALID_STATUS = Set.of("pending", "in_progress", "completed", "aborted");
    /** 步骤数量上限：避免模型把计划写成一份超长清单 */
    private static final int MAX_STEPS = 20;
    /** 单步标题长度上限（字符数） */
    private static final int MAX_TITLE_LENGTH = 80;

    @Override
    public String getName() { return "update_plan"; }

    @Override
    public String getDescription() {
        return "提交或更新本轮任务的步骤计划。处理需要 3 步及以上的任务时，先调用本工具列出步骤，"
                + "再开始执行；每完成一步，再次调用本工具把该步状态改为 completed，并把下一步改为 in_progress。"
                + "单步问答或闲聊不需要调用。同一时间最多只能有一个步骤为 in_progress。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> step = new HashMap<>();
        step.put("type", "object");
        Map<String, Object> stepProps = new HashMap<>();

        Map<String, Object> title = new HashMap<>();
        title.put("type", "string");
        title.put("description", "步骤标题，简洁描述这一步要做什么，不超过 80 字");
        stepProps.put("title", title);

        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("enum", List.of("pending", "in_progress", "completed", "aborted"));
        status.put("description", "步骤状态：pending 待开始 / in_progress 进行中 / completed 已完成 / aborted 已中止（Agent 放弃后自动标记后续步骤）");
        stepProps.put("status", status);

        step.put("properties", stepProps);
        step.put("required", List.of("title", "status"));

        Map<String, Object> steps = new HashMap<>();
        steps.put("type", "array");
        steps.put("description", "任务步骤清单，按执行顺序排列，1-20 步");
        steps.put("items", step);

        Map<String, Object> props = new HashMap<>();
        props.put("steps", steps);
        schema.put("properties", props);
        schema.put("required", List.of("steps"));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        JsonNode stepsNode = arguments.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            return ToolResult.fail("steps 必须是非空数组，包含 1-20 个步骤");
        }
        if (stepsNode.size() > MAX_STEPS) {
            return ToolResult.fail("步骤过多：最多 " + MAX_STEPS + " 步，当前 " + stepsNode.size() + " 步");
        }

        List<WorkspaceContext.PlanStep> steps = new ArrayList<>();
        int inProgress = 0;
        for (int i = 0; i < stepsNode.size(); i++) {
            JsonNode node = stepsNode.get(i);
            String title = node.path("title").asText("").trim();
            if (title.isEmpty()) {
                return ToolResult.fail("第 " + (i + 1) + " 步缺少标题");
            }
            if (title.length() > MAX_TITLE_LENGTH) {
                return ToolResult.fail("第 " + (i + 1) + " 步标题超过 " + MAX_TITLE_LENGTH + " 字");
            }
            String status = node.path("status").asText("").trim();
            if (!VALID_STATUS.contains(status)) {
                return ToolResult.fail("第 " + (i + 1) + " 步状态非法：" + status
                        + "，只能是 pending / in_progress / completed / aborted");
            }
            if ("in_progress".equals(status)) {
                inProgress++;
            }
            steps.add(new WorkspaceContext.PlanStep(title, status));
        }
        if (inProgress > 1) {
            return ToolResult.fail("同一时间最多只能有一个进行中的步骤，当前有 " + inProgress + " 个");
        }

        WorkspaceContext ctx = WorkspaceContext.get();
        if (ctx == null) {
            return ToolResult.fail("工作区上下文丢失，无法记录计划");
        }
        ctx.setPlan(steps);

        int done = (int) steps.stream().filter(s -> "completed".equals(s.status())).count();
        log.info("任务计划已更新：{} 步，已完成 {} 步", steps.size(), done);
        return ToolResult.ok("计划已更新：共 " + steps.size() + " 步，已完成 " + done + " 步");
    }
}
