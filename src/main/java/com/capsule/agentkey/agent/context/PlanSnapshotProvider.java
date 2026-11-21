package com.capsule.agentkey.agent.context;

import com.capsule.agentkey.agent.run.PlanStep;

import java.util.List;

/**
 * 计划快照的读取入口（R5 / P7-07）。
 *
 * <p>注入点每轮调用一次，必须从单一事实源（{@code agent_run.plan_json} 或 RunContext 快照）
 * 重新读取，不能缓存上一轮的列表——P7-04c 的问题正是把 ThreadLocal 里的陈旧对象当成了事实源。
 * 实现方读取失败时应自行降级为空列表，而不是让装配过程抛异常中断主循环。
 */
@FunctionalInterface
public interface PlanSnapshotProvider {

    /** 返回最新计划快照；没有计划时返回空列表，不能返回 null */
    List<PlanStep> latestPlan();
}
