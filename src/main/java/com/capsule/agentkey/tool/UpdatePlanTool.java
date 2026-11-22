package com.capsule.agentkey.tool;

import com.capsule.agentkey.agent.run.PlanEvidenceService;
import com.capsule.agentkey.agent.run.PlanStep;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 第六十八批 A1：任务计划工具。
 *
 * 模型处理多步任务时先提交步骤清单，执行中再逐步更新每个步骤的状态；
 * 计划只写内存（WorkspaceContext），由 ReAct 循环推给前端，不碰文件系统，
 * 因此三档工作权限都可见。
 *
 * <p>R7c：步骤可携带可选的稳定 {@code id} 与确定性验收契约 {@code acceptance}。
 * 没有契约的旧步骤完全保持原行为；带契约的步骤额外要求：
 * <ul>
 *   <li>id 稳定、唯一、格式受限；</li>
 *   <li>验收契约声明后不可改写或删除；</li>
 *   <li>首次提交不能直接 completed，已完成步骤不能重新打开；</li>
 *   <li>每次进入 completed 都同步核对真实工件与验证覆盖版本，不满足则整次更新失败，
 *       保留上一份有效计划快照。</li>
 * </ul>
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
    /** 契约步骤 id 长度与字符集上限 */
    private static final int MAX_ID_LENGTH = 40;
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private final PlanEvidenceService planEvidenceService;

    /** 兼容手动装配（评测/单测）：没有工作区来源，契约步骤不会通过证据核对 */
    public UpdatePlanTool() {
        this(PlanEvidenceService.withoutWorkspace());
    }

    @Autowired
    public UpdatePlanTool(PlanEvidenceService planEvidenceService) {
        this.planEvidenceService = planEvidenceService == null
                ? PlanEvidenceService.withoutWorkspace() : planEvidenceService;
    }

    @Override
    public String getName() { return "update_plan"; }

    @Override
    public String getDescription() {
        return "提交或更新本轮任务的步骤计划。处理需要 3 步及以上的任务时，先调用本工具列出步骤，"
                + "再开始执行；每完成一步，再次调用本工具把该步状态改为 completed，并把下一步改为 in_progress。"
                + "单步问答或闲聊不需要调用。同一时间最多只能有一个步骤为 in_progress。"
                + "如需按真实文件证据验收某一步，可为该步提供稳定 id 与 acceptance；"
                + "带 acceptance 的步骤必须先经过 in_progress，且 completed 时系统会核对工件内容与验证结果。";
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

        Map<String, Object> id = new HashMap<>();
        id.put("type", "string");
        id.put("description", "可选的稳定步骤 id（字母/数字/下划线/短横线，最长 40）；带 acceptance 时必填，后续更新必须复用同一 id");
        stepProps.put("id", id);

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
        requireVerify.put("description", "是否要求最新一次验证为 PASSED 且覆盖最新文件变更");
        acceptanceProps.put("requireVerify", requireVerify);
        acceptance.put("properties", acceptanceProps);
        acceptance.put("description", "可选验收契约；至少包含一个工件断言或 requireVerify=true");
        stepProps.put("acceptance", acceptance);

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

        WorkspaceContext ctx = WorkspaceContext.get();
        if (ctx == null) {
            return ToolResult.fail("工作区上下文丢失，无法记录计划");
        }

        List<PlanStep> previous = ctx.plan();
        Map<String, PlanStep> previousById = new HashMap<>();
        for (PlanStep step : previous) {
            if (step.id() != null) {
                previousById.put(step.id(), step);
            }
        }

        List<PlanStep> steps = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
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

            String id = node.path("id").asText("").trim();
            if (id.isEmpty()) {
                id = null;
            }
            PlanStep.Acceptance acceptance;
            try {
                acceptance = parseAcceptance(node.get("acceptance"), i);
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }

            if (id != null) {
                if (id.length() > MAX_ID_LENGTH) {
                    return ToolResult.fail("第 " + (i + 1) + " 步 id 超过 " + MAX_ID_LENGTH + " 个字符");
                }
                if (!ID_PATTERN.matcher(id).matches()) {
                    return ToolResult.fail("第 " + (i + 1) + " 步 id 只允许字母、数字、下划线和短横线：" + id);
                }
                if (!seenIds.add(id)) {
                    return ToolResult.fail("步骤 id 重复：" + id + "，每个步骤必须使用唯一且稳定的 id");
                }
            }
            if (acceptance != null && id == null) {
                return ToolResult.fail("第 " + (i + 1) + " 步声明了验收契约，必须同时提供稳定的 id");
            }

            ToolResult contractError = validateContractTransition(id, status, acceptance, previousById);
            if (contractError != null) {
                return contractError;
            }
            steps.add(new PlanStep(title, status, id, acceptance));
        }
        if (inProgress > 1) {
            return ToolResult.fail("同一时间最多只能有一个进行中的步骤，当前有 " + inProgress + " 个");
        }

        // 已完成契约步骤不允许整步删除，否则等于绕过审计
        for (PlanStep old : previous) {
            if (old.id() != null && old.hasContract() && "completed".equals(old.status())
                    && !seenIds.contains(old.id())) {
                return ToolResult.fail("已完成步骤 " + old.id() + " 不能从计划中移除");
            }
        }

        // 每次提交 completed 都重新核对真实证据；不满足时整次更新失败，保留上一份有效快照
        List<PlanEvidenceService.Issue> issues =
                planEvidenceService.checkCompleted(ctx.runContext(), steps);
        if (!issues.isEmpty()) {
            return ToolResult.fail("计划完成证据不满足：" + describeIssues(issues));
        }

        ctx.setPlan(steps);

        int done = (int) steps.stream().filter(s -> "completed".equals(s.status())).count();
        log.info("任务计划已更新：{} 步，已完成 {} 步", steps.size(), done);
        return ToolResult.ok("计划已更新：共 " + steps.size() + " 步，已完成 " + done + " 步");
    }

    /** 契约步骤的状态迁移与契约不可变性校验；无问题时返回 null */
    private ToolResult validateContractTransition(String id, String status,
                                                  PlanStep.Acceptance acceptance,
                                                  Map<String, PlanStep> previousById) {
        if (id == null) {
            return null;
        }
        PlanStep old = previousById.get(id);
        if (old == null) {
            if (acceptance != null && "completed".equals(status)) {
                return ToolResult.fail("步骤 " + id + " 首次提交不能直接为 completed，须先经过 in_progress");
            }
            return null;
        }
        if (old.hasContract()) {
            if (!old.acceptance().equals(acceptance)) {
                return ToolResult.fail("步骤 " + id + " 的验收契约不可修改或删除");
            }
            if ("completed".equals(old.status()) && !"completed".equals(status)) {
                return ToolResult.fail("已完成步骤 " + id + " 不能重新打开，以免绕过验收审计");
            }
        } else if (acceptance != null) {
            return ToolResult.fail("步骤 " + id + " 已存在且未声明验收契约，不能事后追加");
        }
        return null;
    }

    /** 解析可选的验收契约；格式非法时抛出带用户可读信息的 IllegalArgumentException */
    private PlanStep.Acceptance parseAcceptance(JsonNode node, int index) {
        return com.capsule.agentkey.agent.run.PlanAcceptanceParser.parse(node,
                "第 " + (index + 1) + " 步");
    }

    private String describeIssues(List<PlanEvidenceService.Issue> issues) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, issues.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("；");
            }
            PlanEvidenceService.Issue issue = issues.get(i);
            if (issue.stepId() != null) {
                sb.append('[').append(issue.stepId()).append("] ");
            }
            sb.append(issue.message());
        }
        if (issues.size() > limit) {
            sb.append("；另有 ").append(issues.size() - limit).append(" 条证据问题");
        }
        return sb.toString();
    }
}
