package com.capsule.agentkey.agent.subtask;

import com.capsule.agentkey.agent.run.PlanAcceptanceParser;
import com.capsule.agentkey.agent.run.PlanStep;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 子任务规格（R7c）。
 *
 * <p>与计划步骤共用同一套验收契约：每个子任务必须有稳定 id、明确目标与至少一条
 * 工件/验证断言；纯文本任务不能通过委派伪装成已验证交付。
 */
public record SubtaskSpec(String id, String objective, PlanStep.Acceptance acceptance) {

    public static final String TOOL_NAME = "delegate_subtasks";
    /** 第一版单次委派硬上限；配置只能收紧不能突破 */
    public static final int MAX_SUBTASKS_PER_DELEGATION = 4;
    public static final int MAX_ID_LENGTH = 40;
    public static final int MAX_OBJECTIVE_LENGTH = 200;
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * 解析 {@code delegate_subtasks} 的参数。
     *
     * @param arguments  {"subtasks":[{"id","objective","acceptance"}]}
     * @param maxSubtasks 单次委派的子任务上限
     * @throws IllegalArgumentException 格式非法、id 重复或缺少验收契约
     */
    public static List<SubtaskSpec> parse(JsonNode arguments, int maxSubtasks) {
        JsonNode subtasks = arguments == null ? null : arguments.path("subtasks");
        if (subtasks == null || !subtasks.isArray() || subtasks.isEmpty()) {
            throw new IllegalArgumentException("subtasks 必须是非空数组");
        }
        if (subtasks.size() > maxSubtasks) {
            throw new IllegalArgumentException("子任务过多：最多 " + maxSubtasks + " 个，当前 "
                    + subtasks.size() + " 个");
        }
        List<SubtaskSpec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < subtasks.size(); i++) {
            JsonNode node = subtasks.get(i);
            String prefix = "第 " + (i + 1) + " 个子任务";
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(prefix + "必须是对象");
            }
            String id = node.path("id").asText("").trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException(prefix + "缺少稳定的 id");
            }
            if (id.length() > MAX_ID_LENGTH || !ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException(prefix + "的 id 只允许字母、数字、下划线和短横线，最长 "
                        + MAX_ID_LENGTH + " 个字符");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("子任务 id 重复：" + id);
            }
            String objective = node.path("objective").asText("").trim();
            if (objective.isEmpty()) {
                throw new IllegalArgumentException(prefix + "缺少 objective");
            }
            if (objective.length() > MAX_OBJECTIVE_LENGTH) {
                throw new IllegalArgumentException(prefix + "的 objective 超过 " + MAX_OBJECTIVE_LENGTH + " 字");
            }
            PlanStep.Acceptance acceptance = PlanAcceptanceParser.parse(
                    node.get("acceptance"), prefix);
            if (acceptance == null) {
                throw new IllegalArgumentException(prefix
                        + "必须提供 acceptance：子任务不能只凭自然语言结论验收");
            }
            specs.add(new SubtaskSpec(id, objective, acceptance));
        }
        return specs;
    }

    /** 渲染给子智能体的验收说明 */
    public String describeAcceptance() {
        StringBuilder sb = new StringBuilder();
        for (PlanStep.Artifact artifact : acceptance.artifacts()) {
            sb.append("- 文件 ").append(artifact.path()).append(" 必须包含：")
                    .append(String.join(" | ", artifact.expectedContains())).append('\n');
        }
        if (acceptance.requireVerify()) {
            sb.append("- 最新一次项目验证必须 PASSED，且覆盖最新文件变更\n");
        }
        return sb.toString();
    }
}
