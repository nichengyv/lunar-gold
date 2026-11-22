package com.capsule.agentkey.agent.run;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 验收契约 JSON 解析（R7c）。
 *
 * <p>{@code update_plan} 与 {@code delegate_subtasks} 共用同一套契约格式与校验，
 * 避免主计划与子任务出现两套「验收」定义。格式非法时抛出带用户可读信息的
 * {@link IllegalArgumentException}，调用方转成工具失败结果。
 */
public final class PlanAcceptanceParser {

    /** 单个步骤/子任务的工件断言上限 */
    public static final int MAX_ARTIFACTS = 8;
    /** 单条工件断言的期望文本上限 */
    public static final int MAX_EXPECTED_TEXTS = 20;
    /** 单条期望文本长度上限 */
    public static final int MAX_EXPECTED_LENGTH = 200;

    private PlanAcceptanceParser() {
    }

    /**
     * 解析可选的验收契约。
     *
     * @param node   契约 JSON；null/缺失返回 null
     * @param prefix 错误信息前缀，例如「第 1 步」
     */
    public static PlanStep.Acceptance parse(JsonNode node, String prefix) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(prefix + " acceptance 必须是对象");
        }

        List<PlanStep.Artifact> artifacts = new ArrayList<>();
        JsonNode artifactsNode = node.get("artifacts");
        if (artifactsNode != null && !artifactsNode.isNull()) {
            if (!artifactsNode.isArray()) {
                throw new IllegalArgumentException(prefix + " acceptance.artifacts 必须是数组");
            }
            if (artifactsNode.size() > MAX_ARTIFACTS) {
                throw new IllegalArgumentException(prefix + " 工件断言过多：最多 " + MAX_ARTIFACTS + " 条");
            }
            for (int i = 0; i < artifactsNode.size(); i++) {
                artifacts.add(parseArtifact(artifactsNode.get(i), prefix, i));
            }
        }

        boolean requireVerify = node.path("requireVerify").asBoolean(false);
        if (artifacts.isEmpty() && !requireVerify) {
            throw new IllegalArgumentException(prefix
                    + " acceptance 至少要有一个 artifacts 断言或 requireVerify=true");
        }
        return new PlanStep.Acceptance(artifacts, requireVerify);
    }

    private static PlanStep.Artifact parseArtifact(JsonNode node, String prefix, int index) {
        String artifactPrefix = prefix + "第 " + (index + 1) + " 条工件断言";
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(artifactPrefix + "必须是对象");
        }
        String path = node.path("path").asText("").trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException(artifactPrefix + "缺少 path");
        }
        if (!PlanEvidenceService.isSafeRelativePath(path)) {
            throw new IllegalArgumentException(artifactPrefix + "的 path 必须是工作区内的安全相对路径：" + path);
        }
        JsonNode expectedNode = node.get("expectedContains");
        if (expectedNode == null || !expectedNode.isArray() || expectedNode.isEmpty()) {
            throw new IllegalArgumentException(artifactPrefix + "的 expectedContains 必须是非空数组");
        }
        if (expectedNode.size() > MAX_EXPECTED_TEXTS) {
            throw new IllegalArgumentException(artifactPrefix + "的 expectedContains 过多：最多 "
                    + MAX_EXPECTED_TEXTS + " 条");
        }
        List<String> expected = new ArrayList<>();
        for (JsonNode textNode : expectedNode) {
            String text = textNode.asText("").trim();
            if (text.isEmpty()) {
                throw new IllegalArgumentException(artifactPrefix + "的 expectedContains 不能包含空文本");
            }
            if (text.length() > MAX_EXPECTED_LENGTH) {
                throw new IllegalArgumentException(artifactPrefix + "的 expectedContains 单条不能超过 "
                        + MAX_EXPECTED_LENGTH + " 字");
            }
            expected.add(text);
        }
        return new PlanStep.Artifact(path, expected);
    }
}
