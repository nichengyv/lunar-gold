package com.capsule.agentkey.agent.run;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 一次 Run 的权威内存状态（R1）。
 *
 * <p>重构前，plan、runId、turnId、取消标记分散在 {@code WorkspaceContext} 的 ThreadLocal 里，
 * 跨 turn、断线重放、线程切换后无法还原；P7-04c 的「回滚后计划丢失」正是这个原因。
 * 现在这些状态统一收敛到 RunContext：
 *
 * <ul>
 *   <li>工具线程通过 {@code WorkspaceContext} 读取同一份 RunContext（迁移适配层，R2 再瘦身）；</li>
 *   <li>计划更新通过 {@link #updatePlan(List)} 写入，并同步触发持久化回调，
 *       落 {@code agent_run.plan_json} + {@code PLAN_UPDATED} 事件；</li>
 *   <li>持久化失败只降级为日志，不能让一次计划更新中断 ReAct 主循环。</li>
 * </ul>
 *
 * <p>线程安全：plan 用 {@link AtomicReference} 保存不可变快照，planChanged 用
 * {@link AtomicBoolean} 保证 drain 语义（读一次即清空），与原来的 ThreadLocal 行为一致。
 */
@Slf4j
public final class RunContext {

    private final Long runId;
    private final Long userId;
    private final Long projectId;
    private final Long sessionId;
    private final String turnId;
    private final AtomicBoolean cancelled;

    /** 计划持久化回调；无 runId 或测试场景为 null，此时只保留内存快照 */
    private final Consumer<List<PlanStep>> planPersister;

    private final AtomicReference<List<PlanStep>> plan;
    private final AtomicBoolean planChanged = new AtomicBoolean(false);

    private RunContext(Long runId, Long userId, Long projectId, Long sessionId, String turnId,
                       AtomicBoolean cancelled, List<PlanStep> initialPlan,
                       Consumer<List<PlanStep>> planPersister) {
        this.runId = runId;
        this.userId = userId;
        this.projectId = projectId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.cancelled = cancelled;
        this.planPersister = planPersister;
        this.plan = new AtomicReference<>(
                initialPlan == null ? List.of() : List.copyOf(initialPlan));
    }

    /**
     * 创建一次 Run 的上下文。
     *
     * @param initialPlan 已持久化的计划快照（续跑/重放时恢复）；新 Run 传空
     * @param planPersister 计划变更后的落库回调，可为 null
     */
    public static RunContext create(Long runId, Long userId, Long projectId, Long sessionId,
                                    String turnId, AtomicBoolean cancelled,
                                    List<PlanStep> initialPlan,
                                    Consumer<List<PlanStep>> planPersister) {
        return new RunContext(runId, userId, projectId, sessionId, turnId, cancelled,
                initialPlan, planPersister);
    }

    public Long runId() { return runId; }
    public Long userId() { return userId; }
    public Long projectId() { return projectId; }
    public Long sessionId() { return sessionId; }
    public String turnId() { return turnId; }
    public AtomicBoolean cancelled() { return cancelled; }

    /** 当前计划快照；从未提交过时返回空列表 */
    public List<PlanStep> plan() {
        return plan.get();
    }

    public boolean hasPlan() {
        return !plan.get().isEmpty();
    }

    /**
     * 更新计划快照并标记「待推送」，随后触发持久化。
     * 回调异常在这里吞掉并记日志：计划落库失败不应影响工具调用结果。
     */
    public void updatePlan(List<PlanStep> steps) {
        List<PlanStep> snapshot = steps == null ? List.of() : List.copyOf(steps);
        plan.set(snapshot);
        planChanged.set(true);
        if (planPersister == null) {
            return;
        }
        try {
            planPersister.accept(snapshot);
        } catch (Exception e) {
            log.warn("计划持久化失败（内存快照已更新，不中断主循环）: {}", e.getMessage());
        }
    }

    /** 有无未推送的计划变化；返回 true 时同时清空标记 */
    public boolean drainPlanChanged() {
        return planChanged.getAndSet(false);
    }
}
