package com.capsule.agentkey.service;

import com.capsule.agentkey.agent.run.LoopGuard;
import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.service.ReActAgentService.Completion;
import com.capsule.agentkey.service.ReActAgentService.CompletionStatus;
import com.capsule.agentkey.service.ReActAgentService.NoProgress;
import com.capsule.agentkey.service.ReActAgentService.StreamCallback;
import com.capsule.agentkey.tool.WorkspaceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Run 终态判定与计划收口（R4b）。
 *
 * <p>主循环只决定「何时结束」，本类决定「以什么状态结束」：候选完成判定、
 * 无进展/预算停止载荷、未完成计划标记 aborted。抽出的目的是让
 * {@link AgentOrchestrator} 只保留循环控制，同时保持所有停止原因与载荷格式不变。
 */
@Slf4j
class RunTermination {

    /** 候选完成判定：优先看未完成计划与失败信号，再用诊断/验证作为证据 */
    Completion candidateCompletion(boolean workspaceChanged, boolean unresolvedWriteFailure,
                                   boolean unresolvedApprovalRejection) {
        if (hasUnfinishedPlan()) {
            return new Completion(CompletionStatus.INCOMPLETE, "PLAN_INCOMPLETE",
                    "模型已停止，但计划仍有未完成步骤。", workspaceChanged);
        }
        if (unresolvedApprovalRejection) {
            return new Completion(CompletionStatus.INCOMPLETE, "APPROVAL_REJECTED",
                    "所需操作未获批准，任务尚未完成。", workspaceChanged);
        }
        if (unresolvedWriteFailure) {
            return new Completion(CompletionStatus.INCOMPLETE, "WRITE_TOOL_FAILED",
                    "最后一次文件修改未成功，任务尚未完成。", workspaceChanged);
        }
        if (workspaceChanged) {
            WorkspaceContext ctx = WorkspaceContext.get();
            com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics =
                    ctx == null ? null : ctx.diagnostics();
            if (diagnostics != null && diagnostics.available() && diagnostics.hasErrors()) {
                long errors = diagnostics.diagnostics().stream()
                        .filter(d -> d.severity()
                                == com.capsule.agentkey.diagnostics.Diagnostic.Severity.ERROR)
                        .count();
                return new Completion(CompletionStatus.INCOMPLETE, "DIAGNOSTICS_FAILED",
                        "变更文件仍有 " + errors + " 个编译错误，任务尚未完成。", true);
            }

            //P5-06：项目验证优先于编译诊断作为完成证据；未配置/不可用不得包装成成功
            com.capsule.agentkey.verify.VerifyResult verify = ctx == null ? null : ctx.verify();
            if (verify != null) {
                switch (verify.status()) {
                    case FAILED -> {
                        return new Completion(CompletionStatus.INCOMPLETE, "VERIFY_FAILED",
                                "验证失败：" + verify.message() + "（第 " + verify.attempt() + '/'
                                        + verify.maxAttempts() + " 次），任务尚未完成。", true);
                    }
                    case PASSED -> {
                        return new Completion(CompletionStatus.COMPLETED, "VERIFY_PASSED",
                                "验证命令全部通过。", true);
                    }
                    case NOT_CONFIGURED -> {
                        return new Completion(CompletionStatus.UNVERIFIED, "VERIFY_NOT_CONFIGURED",
                                "文件已修改，但项目未配置验证命令；可在 "
                                        + verify.configPath()
                                        + " 配置验证，或人工审查并接受未验证变更。", true);
                    }
                    case UNAVAILABLE -> {
                        return new Completion(CompletionStatus.UNVERIFIED, "VERIFY_UNAVAILABLE",
                                "文件已修改，但验证无法执行：" + verify.message()
                                        + "；可修复环境后重试，或人工审查并接受未验证变更。", true);
                    }
                }
            }

            //没有验证结果时的兜底：保留 P5-05 的干净编译诊断作为证据
            if (diagnostics != null && diagnostics.available() && diagnostics.analyzedFiles() > 0) {
                return new Completion(CompletionStatus.COMPLETED, "DIAGNOSTICS_PASSED",
                        "变更文件已通过编译诊断。", true);
            }
            return new Completion(CompletionStatus.UNVERIFIED, "VERIFICATION_MISSING",
                    "文件已修改，但尚未运行诊断或验证。", true);
        }
        return new Completion(CompletionStatus.COMPLETED, "NO_VERIFICATION_REQUIRED",
                "本轮执行已结束，无需文件验证。", false);
    }

    boolean hasUnfinishedPlan() {
        WorkspaceContext ctx = WorkspaceContext.get();
        return ctx != null && ctx.plan().stream()
                .anyMatch(s -> "pending".equals(s.status()) || "in_progress".equals(s.status()));
    }

    /** LoopGuard 停止信号转换成既有的 NoProgress 载荷，保持 SSE/落库格式不变 */
    NoProgress toNoProgress(LoopGuard.Signal signal) {
        return new NoProgress(signal.reason(), signal.toolName(), signal.count(),
                signal.stagnantRounds(), signal.message());
    }

    /** P5-06：预算耗尽时保留计划实况并如实给出 INCOMPLETE */
    void stopWithCompletion(StreamCallback callback, boolean workspaceChanged,
                            String reason, String message) {
        log.warn("ReAct 预算停止: reason={}", reason);
        finalizePlan(callback);
        callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, reason, message, workspaceChanged));
        callback.onComplete();
    }

    void stopForNoProgress(StreamCallback callback, boolean workspaceChanged, NoProgress event) {
        log.warn("ReAct 无进展停止: reason={}, tool={}, repeats={}, stagnantRounds={}",
                event.reason(), event.toolName(), event.repeatCount(), event.stagnantRounds());
        callback.onNoProgress(event);
        finalizePlan(callback);
        callback.onCompletion(new Completion(
                CompletionStatus.INCOMPLETE, event.reason(), event.message(), workspaceChanged));
        callback.onComplete();
    }

    /**
     * 一轮结束时收口计划状态。
     *
     * <p>只有模型通过 update_plan 显式提交的 completed 才保留为完成；自然结束、断开、异常、
     * 轮次耗尽或无进展停止时，pending/in_progress 一律记为 aborted，不能替模型宣称步骤已完成。
     */
    void finalizePlan(StreamCallback callback) {
        try {
            WorkspaceContext ctx = WorkspaceContext.get();
            if (ctx == null || ctx.plan() == null) return;
            var plan = ctx.plan();
            boolean hasRemaining = plan.stream()
                    .anyMatch(s -> "pending".equals(s.status()) || "in_progress".equals(s.status()));
            if (!hasRemaining) return;

            var finalized = plan.stream()
                    .map(s -> "completed".equals(s.status())
                            ? s : new PlanStep(s.title(), "aborted"))
                    .toList();
            ctx.setPlan(finalized);
            callback.onPlan(finalized);
            log.info("本轮结束，计划已收口（未完成 {} 步）",
                    finalized.stream().filter(s -> !"completed".equals(s.status())).count());
        } catch (Exception e) {
            // 收尾动作失败不应该抛——主流程已经或即将结束
            log.debug("finalizePlan 静默失败（可忽略）: {}", e.getMessage());
        }
    }
}
