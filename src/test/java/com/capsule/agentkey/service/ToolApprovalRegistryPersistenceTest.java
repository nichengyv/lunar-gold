package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.ToolApproval;
import com.capsule.agentkey.repository.ToolApprovalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 审批落库与桌面重启恢复。 */
@ExtendWith(MockitoExtension.class)
class ToolApprovalRegistryPersistenceTest {

    @Mock ToolApprovalRepository repository;

    private ToolApproval saved(String state, long expiresAt) {
        ToolApproval approval = new ToolApproval();
        approval.setApprovalId("ap-persisted");
        approval.setUserId(1L);
        approval.setSessionId(10L);
        approval.setToolName("write_file");
        approval.setArguments("{\"path\":\"a.txt\"}");
        approval.setRisk("WRITE");
        approval.setState(state);
        approval.setExpiresAt(expiresAt);
        return approval;
    }

    @Test
    void 启动恢复未过期审批且保留原截止时间() {
        ToolApproval approval = saved("PENDING", System.currentTimeMillis() + 60_000);
        when(repository.findByState("PENDING")).thenReturn(List.of(approval));
        when(repository.findByStateAndRememberSessionTrue("APPROVED")).thenReturn(List.of());
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);
        registry.restorePersistedApprovals();

        when(repository.findByApprovalId("ap-persisted")).thenReturn(Optional.of(approval));
        assertTrue(registry.resolve("ap-persisted", 1L, "APPROVE", true));
        assertEquals("APPROVED", approval.getState());
        assertEquals("USER", approval.getDecisionSource());
        assertTrue(approval.isRememberSession());
        assertNotNull(approval.getDecisionTime());
        verify(repository, atLeastOnce()).save(approval);
    }

    @Test
    void 启动时过期审批直接拒绝并记录超时来源() {
        ToolApproval approval = saved("PENDING", System.currentTimeMillis() - 1);
        when(repository.findByState("PENDING")).thenReturn(List.of(approval));
        when(repository.findByStateAndRememberSessionTrue("APPROVED")).thenReturn(List.of());
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);
        registry.restorePersistedApprovals();

        assertFalse(registry.resolve("ap-persisted", 1L, "APPROVE", false));
        assertEquals("REJECTED", approval.getState());
        assertEquals("TIMEOUT", approval.getDecisionSource());
    }

    @Test
    void 启动恢复会话总是允许() {
        ToolApproval approval = saved("APPROVED", System.currentTimeMillis());
        approval.setRememberSession(true);
        when(repository.findByState("PENDING")).thenReturn(List.of());
        when(repository.findByStateAndRememberSessionTrue("APPROVED")).thenReturn(List.of(approval));
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);
        registry.restorePersistedApprovals();

        assertTrue(registry.isSessionAllowed(10L, "write_file"));
        assertFalse(registry.isSessionAllowed(11L, "write_file"));
    }

    @Test
    void 创建和取消均写数据库并记录会话停止来源() {
        when(repository.save(any(ToolApproval.class))).thenAnswer(inv -> inv.getArgument(0));
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);
        ToolApprovalRegistry.Pending pending = registry.create(1L, 10L, "write_file", "{}", "WRITE");
        ToolApproval persisted = saved("PENDING", pending.expiresAt());
        when(repository.findByApprovalId(pending.approvalId())).thenReturn(Optional.of(persisted));

        registry.cancelSession(10L);

        ToolApprovalRegistry.Resolution resolution = registry.await(pending, new AtomicBoolean(false));
        assertEquals(ToolApprovalRegistry.Decision.REJECT, resolution.decision());
        assertEquals("REJECTED", persisted.getState());
        assertEquals("SESSION_STOP", persisted.getDecisionSource());
        verify(repository, times(2)).save(any(ToolApproval.class));
    }

    @Test
    void 历史查询按会话和用户过滤未决项() {
        ToolApproval approved = saved("APPROVED", System.currentTimeMillis());
        approved.setDecisionSource("USER");
        when(repository.findBySessionIdAndUserIdAndStateNotOrderByCreateTimeDesc(10L, 1L, "PENDING"))
                .thenReturn(List.of(approved));
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);

        List<ToolApproval> history = registry.history(10L, 1L);

        assertEquals(1, history.size());
        assertEquals("USER", history.get(0).getDecisionSource());
        verify(repository).findBySessionIdAndUserIdAndStateNotOrderByCreateTimeDesc(10L, 1L, "PENDING");
    }
}
