package com.capsule.agentkey.agent.run;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 任务计划中的一个步骤：标题 + 状态。
 *
 * <p>状态取值：{@code pending} 待开始 / {@code in_progress} 进行中 /
 * {@code completed} 已完成 / {@code aborted} 已中止。
 *
 * <p>R1 起计划归属于 {@link RunContext}，不再是工具线程 ThreadLocal 的临时快照，
 * 因此跨 turn、断线重放和重启后都能从 {@code agent_run.plan_json} 恢复。
 *
 * <p>R7c：步骤可以有可选的稳定 {@code id} 和确定性验收契约 {@code acceptance}。
 * 旧的两字段计划（title/status）行为完全不变；{@code id}/{@code acceptance} 为空时
 * 序列化结果与旧格式一致（NON_NULL），保证历史计划 JSON 可继续读取。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanStep(String title, String status, String id, Acceptance acceptance) {

    /** 兼容旧调用与旧 JSON：没有 id、没有验收契约 */
    public PlanStep(String title, String status) {
        this(title, status, null, null);
    }

    public PlanStep {
        id = id == null || id.isBlank() ? null : id;
        if (acceptance != null && acceptance.isEmpty()) {
            // 空契约等价于没有契约，避免「声明了 acceptance 却什么都不校验」
            acceptance = null;
        }
    }

    public boolean hasContract() {
        return acceptance != null;
    }

    /**
     * 确定性验收契约：只描述可机器核对的事实，不涉及自然语言语义。
     *
     * <p>每一步至少包含一个工件断言或 {@code requireVerify=true}；校验发生在
     * {@code update_plan} 提交 completed 时与 Run 最终收口时，不引入 LLM 裁判。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Acceptance(List<Artifact> artifacts, boolean requireVerify) {

        public Acceptance {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isEmpty() {
            return artifacts.isEmpty() && !requireVerify;
        }
    }

    /**
     * 工件断言：相对工作区根的安全路径，以及文件中必须出现的文本片段。
     *
     * <p>路径与文本在提交计划时做静态校验，读取时再做真实路径（符号链接）校验；
     * 任何读取失败、文件不存在或越界都按「证据不满足」处理，不会降级为通过。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Artifact(String path, List<String> expectedContains) {

        public Artifact {
            expectedContains = expectedContains == null ? List.of() : List.copyOf(expectedContains);
        }
    }
}
