package com.capsule.agentkey.agent.tool;

import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.SessionPermissionTracker;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具执行链路（R3）。
 *
 * <p>串行路径与只读并行批都从这里走，区别只在「能不能并行」：
 * <ul>
 *   <li>串行：{@link #execute} 内完成「最新权限 → 风险判定 → 审批（如需要）→ scope → dispatch」；</li>
 *   <li>并行：{@link #executeReadOnlyBatch} 只接受 {@link #isParallelizable} 的调用，
 *       子线程里仍走 {@link ApprovalGate#dispatch}，不存在绕过 scope 的旁路。</li>
 * </ul>
 *
 * <p>审批 UI 通过 {@link ApprovalUi} 回调解耦：本类不依赖 SSE / 回调实现，
 * 也方便单测直接断言「先审批后执行」的顺序。
 */
@Slf4j
public class ToolExecutor {

    /** 审批交互回调；实现方负责构建预览、推送 SSE、落事件 */
    public interface ApprovalUi {
        void onApprovalRequired(ToolApprovalRegistry.Pending pending, String risk);

        void onApprovalResolved(ToolApprovalRegistry.Pending pending,
                                ToolApprovalRegistry.Resolution resolution);
    }

    /**
     * 一次串行执行的结果。
     *
     * @param result           工具结果（拒绝时为 fail）
     * @param risk             风险等级，null 表示无需审批
     * @param approvalRejected 是否因用户拒绝而未执行
     */
    public record Outcome(ToolResult result, String risk, boolean approvalRejected) {
    }

    private final ToolRegistry registry;
    private final ToolApprovalRegistry approvals;
    private final ApprovalGate gate;
    private final SessionPermissionTracker permissionTracker;
    private final int maxConcurrency;

    /** 只读批执行池：daemon 线程，按需初始化；关闭时中断未完成任务 */
    private volatile ExecutorService readOnlyPool;

    public ToolExecutor(ToolRegistry registry, ToolApprovalRegistry approvals, ApprovalGate gate,
                        SessionPermissionTracker permissionTracker, int maxConcurrency) {
        this.registry = registry;
        this.approvals = approvals;
        this.gate = gate;
        this.permissionTracker = permissionTracker;
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    /**
     * 串行执行一次工具调用。
     *
     * <p>每次调用都重读权限，用户在运行中切换档位能立即生效。
     * 拒绝时不执行工具，返回 approvalRejected=true，由调用方标记本轮未完成。
     */
    public Outcome execute(String toolName, String arguments, Long userId, Long sessionId,
                           AgentPolicy.Permission policyPermission, AtomicBoolean cancelRequested,
                           ApprovalUi ui) {
        AgentPolicy.Permission permission = permissionTracker.get(sessionId, policyPermission);
        ApprovalGate.Risk risk = gate.assess(toolName, arguments, permission, sessionId);
        if (risk.requiresApproval()) {
            ToolApprovalRegistry.Pending pending =
                    approvals.create(userId, sessionId, toolName, arguments, risk.level());
            ui.onApprovalRequired(pending, risk.level());
            ToolApprovalRegistry.Resolution decision = approvals.await(pending, cancelRequested);
            ui.onApprovalResolved(pending, decision);
            if (decision.decision() == ToolApprovalRegistry.Decision.REJECT) {
                log.info("用户拒绝工具调用: {}", toolName);
                return new Outcome(gate.rejection(toolName, risk.level()), risk.level(), true);
            }
        }
        return new Outcome(gate.dispatch(toolName, arguments, permission), risk.level(), false);
    }

    /**
     * P5-08：从 start 开始的连续只读调用是否可进入并行批。
     * 只并列「连续」的只读调用：中间出现写/命令必须断开，避免后置读取跑到写入之前；
     * 需要审批（含 MCP/fallback）的调用一律走串行路径。
     */
    public boolean isParallelizable(ObjectNode toolCall) {
        String toolName = toolCall.path("function").path("name").asText();
        // P7-06：ask_user_question 虽然 readOnly，但必须串行走挂起/恢复拦截点；
        // 若混入只读并行批，同轮后续工具会在用户回答之前被预执行，破坏"先问再做"的语义
        if ("ask_user_question".equals(toolName)) return false;
        if (toolName.isBlank() || !registry.isReadOnly(toolName)) return false;
        if (registry.hasFallback(toolName)) return false;
        String arguments = toolCall.path("function").path("arguments").asText();
        return ToolApprovalRegistry.riskOf(toolName, arguments) == null;
    }

    /**
     * P5-08：并发执行从 start 开始的连续只读批，返回下标对齐的结果。
     * 批规模小于 2 时返回空 Map（单次调用没有并行收益，仍走串行路径）。
     * 结果只回放不合并事件：事件与 tool 消息仍由主循环按原下标顺序发出。
     */
    public Map<Integer, ToolResult> executeReadOnlyBatch(List<ObjectNode> toolCalls, int start,
                                                         Long sessionId, AgentPolicy policy,
                                                         AtomicBoolean cancelRequested) {
        int end = start;
        while (end < toolCalls.size() && isParallelizable(toolCalls.get(end))) {
            end++;
        }
        int size = end - start;
        if (size < 2) {
            return Map.of();
        }

        AgentPolicy.Permission permission = permissionTracker.get(sessionId, policy.permission());
        WorkspaceContext parent = WorkspaceContext.get();
        List<Future<ToolResult>> futures = new ArrayList<>(size);
        for (int i = start; i < end; i++) {
            ObjectNode call = toolCalls.get(i);
            String toolName = call.path("function").path("name").asText();
            String arguments = call.path("function").path("arguments").asText();
            futures.add(pool().submit(() -> {
                if (cancelRequested != null && cancelRequested.get()) {
                    return ToolResult.fail("本轮执行已取消，未执行只读工具 " + toolName);
                }
                if (parent == null) {
                    return gate.dispatch(toolName, arguments, permission);
                }
                // 工作区工具依赖 ThreadLocal 上下文，子线程必须带入派生上下文
                return WorkspaceContext.callIn(parent.child(),
                        () -> gate.dispatch(toolName, arguments, permission));
            }));
        }
        log.info("P5-08 只读批并发执行: {} 个调用，并发上限 {}", size, maxConcurrency);

        Map<Integer, ToolResult> results = new HashMap<>();
        for (int i = 0; i < size; i++) {
            if (cancelRequested != null && cancelRequested.get()) {
                // 停止生成：取消尚未完成的任务，剩余调用标记为未执行
                for (int j = i; j < size; j++) {
                    futures.get(j).cancel(true);
                    results.put(start + j, ToolResult.fail("本轮执行已取消，未执行只读工具"));
                }
                break;
            }
            try {
                results.put(start + i, futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.put(start + i, ToolResult.fail("只读工具执行被中断"));
            } catch (java.util.concurrent.ExecutionException e) {
                results.put(start + i, ToolResult.fail("只读工具执行异常: " + e.getCause()));
            }
        }
        return results;
    }

    /** 只读批线程池，按配置上限懒初始化；daemon 线程不阻止进程退出 */
    private ExecutorService pool() {
        ExecutorService pool = readOnlyPool;
        if (pool == null) {
            synchronized (this) {
                pool = readOnlyPool;
                if (pool == null) {
                    AtomicInteger seq = new AtomicInteger();
                    pool = Executors.newFixedThreadPool(maxConcurrency, r -> {
                        Thread t = new Thread(r, "readonly-tool-" + seq.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                    readOnlyPool = pool;
                }
            }
        }
        return pool;
    }

    /** 关闭只读批线程池，中断未完成任务 */
    public void shutdown() {
        ExecutorService pool = readOnlyPool;
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
