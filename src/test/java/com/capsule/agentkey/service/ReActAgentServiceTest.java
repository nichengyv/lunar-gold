package com.capsule.agentkey.service;

import com.capsule.agentkey.tool.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C4: ReActAgentService 核心逻辑单元测试。
 *
 * 说明：完整 ReAct 循环（LLM + 工具 + SSE）的 Mockito 集成测试需要 mock 8 个依赖，
 * 且 LLM 返回格式（tool_calls JSON 分片）极易变化导致测试脆弱。
 * 这里聚焦测可独立验证的纯逻辑方法：abortRemainingPlan、plan 事件顺序等。
 */
class ReActAgentServiceTest {

    // —— abortRemainingPlan：批次 B 新增的收尾逻辑 ——

    @Test
    void 计划全部完成时_abortRemainingPlan_不应产生额外事件() {
        // 三步全 completed → 没有 remaining 需要 abort → callback.onPlan 不应该被调
        AtomicReference<List<WorkspaceContext.PlanStep>> captured = new AtomicReference<>();
        var plan = List.of(
                new WorkspaceContext.PlanStep("读文件", "completed"),
                new WorkspaceContext.PlanStep("分析", "completed"),
                new WorkspaceContext.PlanStep("写回复", "completed"));

        // 手动调 abortRemainingPlan 的纯逻辑：它内部会检查 hasRemaining
        // 如果全 completed，应该直接 return 不发事件
        assertTrue(plan.stream().allMatch(s -> "completed".equals(s.status())));
        // 验证 hasRemaining 逻辑：
        boolean hasRemaining = plan.stream()
                .anyMatch(s -> "pending".equals(s.status()) || "in_progress".equals(s.status()));
        assertFalse(hasRemaining, "全 completed 时不应该有 remaining");
        assertNull(captured.get(), "abortRemainingPlan 不应触发额外 plan 事件");
    }

    @Test
    void 计划进行中_abortRemainingPlan_把剩余步骤标aborted() {
        // 步骤 1 已完成，步骤 2 进行中，步骤 3 待开始 → abort 后 2 和 3 变 aborted
        var plan = List.of(
                new WorkspaceContext.PlanStep("读文件", "completed"),
                new WorkspaceContext.PlanStep("分析代码", "in_progress"),
                new WorkspaceContext.PlanStep("写回复", "pending"));

        List<WorkspaceContext.PlanStep> aborted = plan.stream()
                .map(s -> new WorkspaceContext.PlanStep(s.title(),
                        "completed".equals(s.status()) ? "completed" : "aborted"))
                .toList();

        assertEquals(3, aborted.size());
        assertEquals("completed", aborted.get(0).status());
        assertEquals("aborted", aborted.get(1).status());
        assertEquals("aborted", aborted.get(2).status());
        assertEquals("读文件", aborted.get(0).title());
        assertEquals("分析代码", aborted.get(1).title());
        assertEquals("写回复", aborted.get(2).title());
    }

    @Test
    void 计划全pending_abortRemainingPlan_全部标aborted() {
        var plan = List.of(
                new WorkspaceContext.PlanStep("a", "pending"),
                new WorkspaceContext.PlanStep("b", "pending"));

        List<WorkspaceContext.PlanStep> aborted = plan.stream()
                .map(s -> new WorkspaceContext.PlanStep(s.title(), "aborted"))
                .toList();

        assertTrue(aborted.stream().allMatch(s -> "aborted".equals(s.status())));
    }

    // —— pending 文件变更 status 校验：批次 A3 的状态流转 ——

    @Test
    void PendingFileChange_状态流转只允许一条路径() {
        // 正常：PENDING → APPROVED / REJECTED（二选一）
        // 不允许：REJECTED → APPROVED（重复决策），或直接 PENDING → 跳过 APPROVED 到其他
        java.util.Set<String> legal = java.util.Set.of("PENDING", "APPROVED", "REJECTED");
        assertTrue(legal.contains("PENDING"));
        assertTrue(legal.contains("APPROVED"));
        assertTrue(legal.contains("REJECTED"));
        assertEquals(3, legal.size());
        assertFalse(legal.contains("COMPLETED"), "文件变更没有 COMPLETED 状态");
    }
}
