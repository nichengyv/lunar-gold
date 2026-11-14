package com.capsule.agentkey.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第六十八批 A2：工具审批登记表的决策、归属校验与会话级记忆。
 * P5-10-2：补充决策来源（手动 / 超时 / 会话停止 / 系统兜底）。
 */
class ToolApprovalRegistryTest {

    private final ToolApprovalRegistry registry = new ToolApprovalRegistry();

    private ToolApprovalRegistry.Pending create() {
        return registry.create(1L, 10L, "write_file", "{\"path\":\"a.txt\"}", "WRITE");
    }

    private ToolApprovalRegistry.Pending pending(long expiresAt) {
        return new ToolApprovalRegistry.Pending("ap-manual", 1L, 10L, "write_file", "{}", "WRITE",
                expiresAt, new CompletableFuture<>());
    }

    @Test
    void 风险等级只覆盖写文件与执行命令() {
        assertEquals("WRITE", ToolApprovalRegistry.riskOf("write_file"));
        assertEquals("WRITE", ToolApprovalRegistry.riskOf("edit_file"));
        assertEquals("EXECUTE", ToolApprovalRegistry.riskOf("run_command"));
        assertEquals("WRITE", ToolApprovalRegistry.riskOf("mcp_filesystem_write_file"));
        assertEquals("WRITE", ToolApprovalRegistry.riskOf("mcp_filesystem_create_directory"));
        assertEquals("DESTRUCTIVE", ToolApprovalRegistry.riskOf("mcp_filesystem_delete_file"));
        assertEquals(null, ToolApprovalRegistry.riskOf("mcp_filesystem_read_text_file"));
        assertEquals(null, ToolApprovalRegistry.riskOf("read_file"));
    }

    @Test
    void 批准后等待线程立即拿到结果且来源为本人() {
        ToolApprovalRegistry.Pending record = create();
        assertTrue(registry.resolve(record.approvalId(), 1L, "APPROVE", false));

        ToolApprovalRegistry.Resolution resolution = registry.await(record, new AtomicBoolean(false));
        assertEquals(ToolApprovalRegistry.Decision.APPROVE, resolution.decision());
        assertEquals(ToolApprovalRegistry.DecisionSource.USER, resolution.source());
        assertNull(resolution.batchId(), "手动审批不属于任何批次");
        assertFalse(registry.isSessionAllowed(10L, "write_file"), "未选择记住时不应写入会话记忆");
    }

    @Test
    void 拒绝后等待线程拿到拒绝且来源为本人() {
        ToolApprovalRegistry.Pending record = create();
        assertTrue(registry.resolve(record.approvalId(), 1L, "REJECT", false));

        ToolApprovalRegistry.Resolution resolution = registry.await(record, new AtomicBoolean(false));
        assertEquals(ToolApprovalRegistry.Decision.REJECT, resolution.decision());
        assertEquals(ToolApprovalRegistry.DecisionSource.USER, resolution.source());
    }

    @Test
    void 记住后本会话内不再询问() {
        ToolApprovalRegistry.Pending record = create();
        assertTrue(registry.resolve(record.approvalId(), 1L, "APPROVE", true));

        assertEquals(ToolApprovalRegistry.Decision.APPROVE,
                registry.await(record, new AtomicBoolean(false)).decision());
        assertTrue(registry.isSessionAllowed(10L, "write_file"));
        assertFalse(registry.isSessionAllowed(11L, "write_file"), "其他会话不应共享记忆");
        assertFalse(registry.isSessionAllowed(10L, "run_command"), "只记住被批准的那个工具");
    }

    @Test
    void 其他用户不能提交审批() {
        ToolApprovalRegistry.Pending record = create();
        assertFalse(registry.resolve(record.approvalId(), 2L, "APPROVE", false));
        assertTrue(registry.resolve(record.approvalId(), 1L, "REJECT", false));
    }

    @Test
    void 停止生成会拒绝该会话所有待审批并记录来源() {
        ToolApprovalRegistry.Pending record = create();
        registry.cancelSession(10L);

        ToolApprovalRegistry.Resolution resolution = registry.await(record, new AtomicBoolean(false));
        assertEquals(ToolApprovalRegistry.Decision.REJECT, resolution.decision());
        assertEquals(ToolApprovalRegistry.DecisionSource.SESSION_STOP, resolution.source());
        assertFalse(registry.resolve(record.approvalId(), 1L, "APPROVE", false), "已取消的记录不能再提交");
    }

    @Test
    void 客户端断开按会话停止记录来源() {
        ToolApprovalRegistry.Pending record = pending(System.currentTimeMillis() + 60_000);

        ToolApprovalRegistry.Resolution resolution = registry.await(record, new AtomicBoolean(true));
        assertEquals(ToolApprovalRegistry.Decision.REJECT, resolution.decision());
        assertEquals(ToolApprovalRegistry.DecisionSource.SESSION_STOP, resolution.source());
    }

    @Test
    void 等待超过截止时间按超时记录来源() {
        ToolApprovalRegistry.Pending record = pending(System.currentTimeMillis() - 1);

        ToolApprovalRegistry.Resolution resolution = registry.await(record, new AtomicBoolean(false));
        assertEquals(ToolApprovalRegistry.Decision.REJECT, resolution.decision());
        assertEquals(ToolApprovalRegistry.DecisionSource.TIMEOUT, resolution.source());
    }

    @Test
    void 线程中断按系统兜底记录来源() {
        ToolApprovalRegistry.Pending record = pending(System.currentTimeMillis() + 60_000);
        Thread.currentThread().interrupt();
        try {
            ToolApprovalRegistry.Resolution resolution = registry.await(record, new AtomicBoolean(false));
            assertEquals(ToolApprovalRegistry.Decision.REJECT, resolution.decision());
            assertEquals(ToolApprovalRegistry.DecisionSource.SYSTEM, resolution.source());
        } finally {
            // await 会重新置位中断标记，清掉避免影响后续用例
            Thread.interrupted();
        }
    }

    @Test
    void 批量白名单只放行写和编辑文件() {
        assertTrue(ToolApprovalRegistry.isBatchEligible("write_file", "WRITE"));
        assertTrue(ToolApprovalRegistry.isBatchEligible("edit_file", "WRITE"));
        assertTrue(ToolApprovalRegistry.isBatchEligible("mcp_filesystem_write_file", "WRITE"));
        assertTrue(ToolApprovalRegistry.isBatchEligible("mcp_filesystem_edit_file", "WRITE"));
        assertFalse(ToolApprovalRegistry.isBatchEligible("run_command", "EXECUTE"));
        assertFalse(ToolApprovalRegistry.isBatchEligible("delete_file", "WRITE"));
        assertFalse(ToolApprovalRegistry.isBatchEligible("mcp_filesystem_create_directory", "WRITE"));
        assertFalse(ToolApprovalRegistry.isBatchEligible("mcp_filesystem_move_file", "WRITE"));
        assertFalse(ToolApprovalRegistry.isBatchEligible("mcp_filesystem_delete_file", "DESTRUCTIVE"));
    }

    @Test
    void 批量批准共享同一批次并回传来源() {
        ToolApprovalRegistry.Pending first = registry.create(1L, 10L, "write_file", "{}", "WRITE");
        ToolApprovalRegistry.Pending second = registry.create(1L, 10L, "edit_file", "{}", "WRITE");

        ToolApprovalRegistry.BatchResult result =
                registry.resolveBatch(1L, 10L, java.util.List.of(first.approvalId(), second.approvalId()));

        assertTrue(result.accepted());
        assertEquals(2, result.resolvedIds().size());
        assertNotNull(result.batchId());
        ToolApprovalRegistry.Resolution firstResult = registry.await(first, new AtomicBoolean(false));
        assertEquals(ToolApprovalRegistry.Decision.APPROVE, firstResult.decision());
        assertEquals(ToolApprovalRegistry.DecisionSource.BATCH, firstResult.source());
        assertEquals(result.batchId(), firstResult.batchId());
        assertFalse(registry.isSessionAllowed(10L, "write_file"), "批量允许不应写会话记忆");
    }

    @Test
    void 混入高危操作时整批拒绝且不批准任何一项() {
        ToolApprovalRegistry.Pending low = registry.create(1L, 10L, "write_file", "{}", "WRITE");
        ToolApprovalRegistry.Pending high = registry.create(1L, 10L, "run_command", "{}", "EXECUTE");

        ToolApprovalRegistry.BatchResult result =
                registry.resolveBatch(1L, 10L, java.util.List.of(low.approvalId(), high.approvalId()));

        assertFalse(result.accepted());
        assertTrue(registry.resolve(low.approvalId(), 1L, "APPROVE", false), "低风险项应保持待审批");
        assertTrue(registry.resolve(high.approvalId(), 1L, "REJECT", false), "高危项应保持待审批");
    }

    @Test
    void 跨用户或跨会话的批量请求被拒绝() {
        ToolApprovalRegistry.Pending record = registry.create(1L, 10L, "write_file", "{}", "WRITE");

        assertFalse(registry.resolveBatch(2L, 10L, java.util.List.of(record.approvalId())).accepted());
        assertFalse(registry.resolveBatch(1L, 11L, java.util.List.of(record.approvalId())).accepted());
        assertTrue(registry.resolveBatch(1L, 10L, java.util.List.of(record.approvalId())).accepted());
    }

    @Test
    void 预检后并发失效的项只返回实际批准结果() {
        ToolApprovalRegistry.Pending first = registry.create(1L, 10L, "write_file", "{}", "WRITE");
        ToolApprovalRegistry.Pending second = registry.create(1L, 10L, "edit_file", "{}", "WRITE");
        // 预检前第二项已被单条操作抢先完成
        assertTrue(registry.resolve(second.approvalId(), 1L, "APPROVE", false));

        ToolApprovalRegistry.BatchResult result =
                registry.resolveBatch(1L, 10L, java.util.List.of(first.approvalId(), second.approvalId()));

        assertTrue(result.accepted());
        assertEquals(java.util.List.of(first.approvalId()), result.resolvedIds());
    }
}
