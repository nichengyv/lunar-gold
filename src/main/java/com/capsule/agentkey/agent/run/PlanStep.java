package com.capsule.agentkey.agent.run;

/**
 * 任务计划中的一个步骤：标题 + 状态。
 *
 * <p>状态取值：{@code pending} 待开始 / {@code in_progress} 进行中 /
 * {@code completed} 已完成 / {@code aborted} 已中止。
 *
 * <p>R1 起计划归属于 {@link RunContext}，不再是工具线程 ThreadLocal 的临时快照，
 * 因此跨 turn、断线重放和重启后都能从 {@code agent_run.plan_json} 恢复。
 */
public record PlanStep(String title, String status) {
}
