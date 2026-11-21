package com.capsule.agentkey.tool;

import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作区工具的请求上下文（ThreadLocal）。
 *
 * <p>R2 起本类只是 {@link RunContext} 的线程视图：runId / sessionId / turnId / scope /
 * 计划 / 诊断 / 验证 / 工具统计全部由 RunContext 持有，这里只保留一个
 * 「文件变更缓冲区」用于工具线程到 SSE 层的批量传递。
 *
 * <p>没有 RunContext 的调用（单测、MCP 或后台任务）会得到一个合成 RunContext，
 * 因此不存在「有状态 / 无状态」两套分支，行为与旧实现一致。
 */
public class WorkspaceContext {

    private static final ThreadLocal<WorkspaceContext> HOLDER = new ThreadLocal<>();

    /** Run 级权威状态；必定非空（无外部上下文时使用合成实例） */
    private final RunContext runContext;

    /** 本次生成中工具对工作区文件产生的变更，供上层逐个推送给前端 */
    private final List<FileChange> fileChanges = new ArrayList<>();

    public WorkspaceContext(Long userId, Long projectId, AtomicBoolean cancelled) {
        this(RunContext.create(null, userId, projectId, null, null, cancelled, List.of(), null));
    }

    /** 以 RunContext 为权威状态创建工具视图 */
    public WorkspaceContext(RunContext runContext) {
        this.runContext = runContext;
    }

    /** P5-08：并行只读工具的子上下文——共享 RunContext，文件变更缓冲各自独立 */
    private WorkspaceContext(WorkspaceContext parent) {
        this.runContext = parent.runContext;
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

    /** Run 级权威状态；永远非空 */
    public RunContext runContext() {
        return runContext;
    }

    public Long userId() { return runContext.userId(); }
    public Long projectId() { return runContext.projectId(); }
    public Long runId() { return runContext.runId(); }
    public void setRunId(Long runId) { runContext.setRunId(runId); }
    public Long sessionId() { return runContext.sessionId(); }
    public void setSessionId(Long sessionId) { runContext.setSessionId(sessionId); }
    public String turnId() { return runContext.turnId(); }
    public void setTurnId(String turnId) { runContext.setTurnId(turnId); }
    public AtomicBoolean cancelled() { return runContext.cancelled(); }

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
        return runContext.plan();
    }

    /** 更新计划快照并标记为「待推送」，同步触发持久化 */
    public void setPlan(List<PlanStep> steps) {
        runContext.updatePlan(steps);
    }

    /** 有无未推送的计划变化；返回 true 时同时清空标记 */
    public boolean drainPlanChanged() {
        return runContext.drainPlanChanged();
    }

    /** D1: 当前 scope 列表（文件范围约束），空列表 = 不限制 */
    public List<String> scope() { return runContext.scope(); }

    /** D1: 设置 scope 列表；空/null = 不限制 */
    public void setScope(List<String> scope) { runContext.setScope(scope); }

    public com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics() {
        return runContext.diagnostics();
    }
    public void setDiagnostics(com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics) {
        runContext.setDiagnostics(diagnostics);
    }
    public String diagnosticsFingerprint() { return runContext.diagnosticsFingerprint(); }
    public void setDiagnosticsFingerprint(String fingerprint) {
        runContext.setDiagnosticsFingerprint(fingerprint);
    }
    public String diagnosticsFileVersion() { return runContext.diagnosticsFileVersion(); }
    public void setDiagnosticsFileVersion(String version) {
        runContext.setDiagnosticsFileVersion(version);
    }

    public com.capsule.agentkey.verify.VerifyResult verify() { return runContext.verify(); }
    public void setVerify(com.capsule.agentkey.verify.VerifyResult verify) {
        runContext.setVerify(verify);
    }

    /** P5-08：会话级工具统计；无上下文时调用方自行降级 */
    public java.util.Map<String, int[]> toolStats() {
        return runContext.toolStats();
    }

    public static void set(Long userId, Long projectId) {
        set(userId, projectId, null);
    }

    /** 以 RunContext 为权威状态绑定当前线程（runId / plan / scope / 取消标记同源） */
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
