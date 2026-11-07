package com.capsule.agentkey.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第六十八批 A2：工具审批登记表的决策、归属校验与会话级记忆。
 */
class ToolApprovalRegistryTest {

    private final ToolApprovalRegistry registry = new ToolApprovalRegistry();

    private ToolApprovalRegistry.Pending create() {
        return registry.create(1L, 10L, "write_file", "{\"path\":\"a.txt\"}", "WRITE");
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
    void 批准后等待线程立即拿到结果() {
        ToolApprovalRegistry.Pending record = create();
        assertTrue(registry.resolve(record.approvalId(), 1L, "APPROVE", false));

        assertEquals(ToolApprovalRegistry.Decision.APPROVE,
                registry.await(record, new AtomicBoolean(false)));
        assertFalse(registry.isSessionAllowed(10L, "write_file"), "未选择记住时不应写入会话记忆");
    }

    @Test
    void 拒绝后等待线程拿到拒绝() {
        ToolApprovalRegistry.Pending record = create();
        assertTrue(registry.resolve(record.approvalId(), 1L, "REJECT", false));

        assertEquals(ToolApprovalRegistry.Decision.REJECT,
                registry.await(record, new AtomicBoolean(false)));
    }

    @Test
    void 记住后本会话内不再询问() {
        ToolApprovalRegistry.Pending record = create();
        assertTrue(registry.resolve(record.approvalId(), 1L, "APPROVE", true));

        assertEquals(ToolApprovalRegistry.Decision.APPROVE,
                registry.await(record, new AtomicBoolean(false)));
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
    void 停止生成会拒绝该会话所有待审批() {
        ToolApprovalRegistry.Pending record = create();
        registry.cancelSession(10L);

        assertEquals(ToolApprovalRegistry.Decision.REJECT,
                registry.await(record, new AtomicBoolean(false)));
        assertFalse(registry.resolve(record.approvalId(), 1L, "APPROVE", false), "已取消的记录不能再提交");
    }
}
