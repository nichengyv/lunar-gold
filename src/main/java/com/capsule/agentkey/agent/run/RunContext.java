package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.diagnostics.DiagnosticsResult;
import com.capsule.agentkey.verify.VerifyResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 一次 Run 的权威状态（R1 建立，R2 扩充）。
 *
 * <p>重构前，plan、scope、诊断、验证、工具统计、runId、取消标记分散在
 * {@code WorkspaceContext} 的 ThreadLocal 里，跨 turn、断线重放、线程切换后无法还原；
 * P7-04c 的「回滚后计划丢失」正是这个原因。现在这些状态统一收敛到 RunContext：
 *
 * <ul>
 *   <li>工具线程通过 {@code WorkspaceContext} 读写同一份 RunContext（薄视图，不再存状态）；</li>
 *   <li>计划更新通过 {@link #updatePlan(List)} 写入，并同步触发持久化回调，
 *       落 {@code agent_run.plan_json} + {@code PLAN_UPDATED} 事件；</li>
 *   <li>持久化失败只降级为日志，不能让一次计划更新中断 ReAct 主循环。</li>
 * </ul>
 *
 * <p>线程安全：计划用 {@link AtomicReference} 保存不可变快照；planChanged 用
 * {@link AtomicBoolean} 保证 drain 语义；工具统计用并发 Map，供并行只读工具的子线程累计。
 */
@Slf4j
public final class RunContext {

    private volatile Long runId;
    private final Long userId;
    private final Long projectId;
    private volatile Long sessionId;
    private volatile String turnId;
    private final AtomicBoolean cancelled;

    /** 计划持久化回调；无 runId 或测试场景为 null，此时只保留内存快照 */
    private final Consumer<List<PlanStep>> planPersister;

    private final AtomicReference<List<PlanStep>> plan;
    private final AtomicBoolean planChanged = new AtomicBoolean(false);

    /** D1: 用户消息声明的文件范围约束；空列表 = 不限制 */
    private volatile List<String> scope = List.of();

    /** P5-05：最近一批变更文件的诊断结果 */
    private volatile DiagnosticsResult diagnostics;
    /** P5-05：最近一批诊断指纹，用于识别连续相同诊断 */
    private volatile String diagnosticsFingerprint = "";
    /** P5-05：诊断对应的文件版本（mtime:size） */
    private volatile String diagnosticsFileVersion = "";
    /** P5-06：最近一次项目验证结果 */
    private volatile VerifyResult verify;

    /**
     * R7c：文件变更版本与验证覆盖版本。
     *
     * <p>此前它们是 {@code AgentOrchestrator} 的局部变量，工具层看不到；
     * 契约步骤在「同一工具批里先写文件、再 update_plan」时可能误用旧验证结果。
     * 现在权威值收敛到 RunContext：每次 drain 到文件变更立即递增变更版本，
     * 验证完成后把已验证版本追平，供计划证据与最终收口共同读取。
     */
    private final java.util.concurrent.atomic.AtomicLong changeEpoch =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifiedEpoch =
            new java.util.concurrent.atomic.AtomicLong(-1);

    /**
     * P5-08：本轮会话级工具统计（tool → [calls, success, fail]）。
     * 并行只读工具在子线程 dispatch 时仍累计到同一份统计。
     */
    private final Map<String, int[]> toolStats = new ConcurrentHashMap<>();

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
    public void setRunId(Long runId) { this.runId = runId; }
    public Long userId() { return userId; }
    public Long projectId() { return projectId; }
    public Long sessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String turnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
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

    /** D1: 当前 scope 列表（文件范围约束），空列表 = 不限制 */
    public List<String> scope() { return scope; }

    /** D1: 设置 scope 列表；空/null = 不限制 */
    public void setScope(List<String> scope) {
        this.scope = scope == null ? List.of() : List.copyOf(scope);
    }

    public DiagnosticsResult diagnostics() { return diagnostics; }
    public void setDiagnostics(DiagnosticsResult diagnostics) { this.diagnostics = diagnostics; }

    public String diagnosticsFingerprint() { return diagnosticsFingerprint; }
    public void setDiagnosticsFingerprint(String fingerprint) {
        this.diagnosticsFingerprint = fingerprint == null ? "" : fingerprint;
    }

    public String diagnosticsFileVersion() { return diagnosticsFileVersion; }
    public void setDiagnosticsFileVersion(String version) {
        this.diagnosticsFileVersion = version == null ? "" : version;
    }

    public VerifyResult verify() { return verify; }
    public void setVerify(VerifyResult verify) { this.verify = verify; }

    /** 已发生的文件变更版本；从未变更时为 0 */
    public long changeEpoch() { return changeEpoch.get(); }

    /** 最近一次验证覆盖到的变更版本；从未验证时为 -1 */
    public long verifiedEpoch() { return verifiedEpoch.get(); }

    /** 有文件落地后立即递增变更版本，避免同一工具批里沿用旧验证 */
    public long bumpChangeEpoch() { return changeEpoch.incrementAndGet(); }

    /** 验证完成（无论通过/失败/不可用）后把已验证版本追平当前变更版本 */
    public void markVerified() { verifiedEpoch.set(changeEpoch.get()); }

    /** 续跑时从断点回写两个版本，保证不重复验证已覆盖的批次 */
    public void restoreEpochs(long change, long verified) {
        changeEpoch.set(Math.max(0, change));
        verifiedEpoch.set(verified);
    }

    /** requireVerify 契约的判定条件：已通过的验证必须覆盖最新一次文件变更 */
    public boolean verifyCoversLatestChange() {
        return verifiedEpoch.get() >= 0 && verifiedEpoch.get() >= changeEpoch.get();
    }

    /** P5-08：会话级工具统计，并行只读工具共享同一份 */
    public Map<String, int[]> toolStats() { return toolStats; }
}
