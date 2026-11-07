package com.capsule.agentkey.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作区工具的请求上下文（ThreadLocal）。
 * ReActAgentService 在执行 Agent 前设置 userId + projectId，
 * 工作区工具（list_dir / read_file / run_command 等）从中取出，避免改动 dispatch 签名。
 */
public class WorkspaceContext {

    /** 任务计划中的一个步骤：标题 + 状态（pending / in_progress / completed / aborted） */
    public record PlanStep(String title, String status) {
    }

    private static final ThreadLocal<WorkspaceContext> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final Long projectId;
    /** 当前会话 id（可为空，MCP 工具或后台任务无会话时） */
    private Long sessionId;
    /** 本轮执行 id：同一次 assistant 的多次文件改动共享，用于「撤回本轮」 */
    private String turnId;
    /** 客户端停止/断连标记：长耗时工具（如终端命令）运行中轮询它，置位即中止 */
    private final AtomicBoolean cancelled;
    /** 本次生成中工具对工作区文件产生的变更，供上层逐个推送给前端 */
    private final List<FileChange> fileChanges = new ArrayList<>();
    /** 本轮任务计划快照：update_plan 工具写入，ReAct 循环在工具执行后推送给前端 */
    private List<PlanStep> plan = List.of();
    /** 计划是否有更新（推送后清空，避免同一份计划重复下发） */
    private boolean planChanged;
    /** D1: 用户消息里声明的文件范围约束——scope 列表非空时，文件类工具只能在这些路径下操作；空 = 不限制 */
    private List<String> scope = List.of();

    public WorkspaceContext(Long userId, Long projectId, AtomicBoolean cancelled) {
        this.userId = userId;
        this.projectId = projectId;
        this.cancelled = cancelled;
    }

    public Long userId() { return userId; }
    public Long projectId() { return projectId; }
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
        return plan;
    }

    /** 更新计划快照并标记为「待推送」 */
    public void setPlan(List<PlanStep> steps) {
        this.plan = List.copyOf(steps);
        this.planChanged = true;
    }

    /** 有无未推送的计划变化；返回 true 时同时清空标记 */
    public boolean drainPlanChanged() {
        if (!planChanged) return false;
        planChanged = false;
        return true;
    }

    /** D1: 当前 scope 列表（文件范围约束），空列表 = 不限制 */
    public List<String> scope() { return scope; }

    /** D1: 设置 scope 列表；空/null = 不限制 */
    public void setScope(List<String> scope) {
        this.scope = scope == null ? List.of() : List.copyOf(scope);
    }


    public static void set(Long userId, Long projectId) {
        set(userId, projectId, null);
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
