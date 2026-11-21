package com.capsule.agentkey.tool;

import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作区工具的请求上下文（ThreadLocal）。
 * ReActAgentService 在执行 Agent 前设置 userId + projectId，
 * 工作区工具（list_dir / read_file / run_command 等）从中取出，避免改动 dispatch 签名。
 *
 * <p>R1：这里只保留「工具线程视图」。run 级别的权威状态（runId / plan / 取消标记）
 * 收敛到 {@link RunContext}，本类通过 {@link #runContext()} 访问同一份实例。
 * 未注入 RunContext 的测试/旧调用仍走本地字段，保证可回滚。
 */
public class WorkspaceContext {

    private static final ThreadLocal<WorkspaceContext> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final Long projectId;
    /** R1：Run 级权威状态；构造时可空（测试路径） */
    private final RunContext runContext;
    /** P7-06：当前 run id——ask_user_question 挂起/恢复的 key */
    private Long runId;
    /** 当前会话 id（可为空，MCP 工具或后台任务无会话时） */
    private Long sessionId;
    /** 本轮执行 id：同一次 assistant 的多次文件改动共享，用于「撤回本轮」 */
    private String turnId;
    /** 用户显式停止标记：长耗时工具（如终端命令）运行中轮询它，置位即中止；断流不会置位 */
    private final AtomicBoolean cancelled;
    /** 本次生成中工具对工作区文件产生的变更，供上层逐个推送给前端 */
    private final List<FileChange> fileChanges = new ArrayList<>();
    /** 未注入 RunContext 时的本地计划快照（迁移适配层） */
    private List<PlanStep> localPlan = List.of();
    /** 未注入 RunContext 时的本地「计划已更新」标记 */
    private boolean localPlanChanged;
    /** D1: 用户消息里声明的文件范围约束——scope 列表非空时，文件类工具只能在这些路径下操作；空 = 不限制 */
    private List<String> scope = List.of();
    /** P5-05：最近一批变更文件的诊断结果，供完成判定与前端验证摘要复用 */
    private com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics;
    /** P5-05：最近一批诊断指纹，用于识别连续相同诊断 */
    private String diagnosticsFingerprint = "";
    /** P5-05：诊断对应的文件版本（mtime:size），用于关联诊断与具体内容 */
    private String diagnosticsFileVersion = "";
    /** P5-06：最近一次项目验证结果，供完成判定与前端验证摘要复用 */
    private com.capsule.agentkey.verify.VerifyResult verify;
    /**
     * P5-08：本轮会话级工具统计（tool → [calls, success, fail]）。
     * 放在上下文而非线程 ThreadLocal，并行只读工具在子线程 dispatch 时仍能累计到同一份统计。
     */
    private final java.util.Map<String, int[]> toolStats;

    public WorkspaceContext(Long userId, Long projectId, AtomicBoolean cancelled) {
        this.userId = userId;
        this.projectId = projectId;
        this.cancelled = cancelled;
        this.runContext = null;
        this.toolStats = new java.util.concurrent.ConcurrentHashMap<>();
    }

    /** R1：以 RunContext 为权威状态创建工具视图 */
    public WorkspaceContext(RunContext runContext) {
        this.userId = runContext.userId();
        this.projectId = runContext.projectId();
        this.cancelled = runContext.cancelled();
        this.runContext = runContext;
        this.runId = runContext.runId();
        this.sessionId = runContext.sessionId();
        this.turnId = runContext.turnId();
        this.toolStats = new java.util.concurrent.ConcurrentHashMap<>();
    }

    /** P5-08：并行只读工具的子上下文——共享取消标记、scope 与统计，文件变更缓冲各自独立 */
    private WorkspaceContext(WorkspaceContext parent) {
        this.userId = parent.userId;
        this.projectId = parent.projectId;
        this.cancelled = parent.cancelled;
        this.runContext = parent.runContext;
        this.runId = parent.runId;
        this.sessionId = parent.sessionId;
        this.turnId = parent.turnId;
        this.scope = parent.scope;
        this.toolStats = parent.toolStats;
    }

    /** P5-08：派生一个可安全交给工作线程的上下文；无父上下文时返回 null */
    public WorkspaceContext child() {
        return new WorkspaceContext(this);
    }

    /** P5-08：在指定上下文中执行任务，结束后恢复线程原有上下文（线程池复用必须清理） */
    public static <T> T callIn(WorkspaceContext context, java.util.function.Supplier<T> action) {
        WorkspaceContext previous = HOLDER.get();
        HOLDER.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /** P5-08：会话级工具统计；无上下文时调用方自行降级 */
    public java.util.Map<String, int[]> toolStats() {
        return toolStats;
    }

    public Long userId() { return userId; }
    public Long projectId() { return projectId; }
    /** R1：Run 级权威状态；测试/旧调用可能为 null */
    public RunContext runContext() { return runContext; }
    public Long runId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long sessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String turnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public AtomicBoolean cancelled() { return cancelled; }

    public List<FileChange> fileChanges() { return fileChanges; }

    /** 工具执行成功后登记一次文件变更；无上下文（如单元测试）时静默忽略 */
    public static void recordFileChange(FileChange change) {
        WorkspaceContext ctx = HOLDER.get();
        if (ctx != null && change != null) {
            ctx.fileChanges.add(change);
        }
    }

    /** 取走已登记的变更并清空（按工具调用批次推送，避免内存里越积越多） */
    public static List<FileChange> drainFileChanges() {
        WorkspaceContext ctx = HOLDER.get();
        if (ctx == null || ctx.fileChanges.isEmpty()) {
            return List.of();
        }
        List<FileChange> copy = new ArrayList<>(ctx.fileChanges);
        ctx.fileChanges.clear();
        return copy;
    }

    /** 当前计划快照；没有上下文或还没提交过时返回空列表 */
    public List<PlanStep> plan() {
        return runContext != null ? runContext.plan() : localPlan;
    }

    /** 更新计划快照并标记为「待推送」；注入 RunContext 时同步触发持久化 */
    public void setPlan(List<PlanStep> steps) {
        if (runContext != null) {
            runContext.updatePlan(steps);
            return;
        }
        this.localPlan = List.copyOf(steps);
        this.localPlanChanged = true;
    }

    /** 有无未推送的计划变化；返回 true 时同时清空标记 */
    public boolean drainPlanChanged() {
        if (runContext != null) {
            return runContext.drainPlanChanged();
        }
        if (!localPlanChanged) return false;
        localPlanChanged = false;
        return true;
    }

    /** D1: 当前 scope 列表（文件范围约束），空列表 = 不限制 */
    public List<String> scope() { return scope; }

    /** D1: 设置 scope 列表；空/null = 不限制 */
    public void setScope(List<String> scope) {
        this.scope = scope == null ? List.of() : List.copyOf(scope);
    }


    public com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics() { return diagnostics; }
    public void setDiagnostics(com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics) {
        this.diagnostics = diagnostics;
    }
    public String diagnosticsFingerprint() { return diagnosticsFingerprint; }
    public void setDiagnosticsFingerprint(String fingerprint) {
        this.diagnosticsFingerprint = fingerprint == null ? "" : fingerprint;
    }
    public String diagnosticsFileVersion() { return diagnosticsFileVersion; }
    public void setDiagnosticsFileVersion(String version) {
        this.diagnosticsFileVersion = version == null ? "" : version;
    }

    public com.capsule.agentkey.verify.VerifyResult verify() { return verify; }
    public void setVerify(com.capsule.agentkey.verify.VerifyResult verify) {
        this.verify = verify;
    }

    public static void set(Long userId, Long projectId) {
        set(userId, projectId, null);
    }

    /** R1：以 RunContext 为权威状态绑定当前线程（runId / plan / 取消标记同源） */
    public static void set(RunContext runContext) {
        HOLDER.set(new WorkspaceContext(runContext));
    }

    public static void set(Long userId, Long projectId, AtomicBoolean cancelled) {
        HOLDER.set(new WorkspaceContext(userId, projectId, cancelled));
    }

    public static WorkspaceContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
