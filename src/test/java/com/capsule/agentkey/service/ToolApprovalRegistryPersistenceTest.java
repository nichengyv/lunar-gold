package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.ToolApproval;
import com.capsule.agentkey.repository.ToolApprovalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
    void 启动时未过期审批也按进程重启拒绝且不可再批准() {
        ToolApproval approval = saved("PENDING", System.currentTimeMillis() + 60_000);
        when(repository.findByState("PENDING")).thenReturn(List.of(approval));
        when(repository.findByStateAndRememberSessionTrue("APPROVED")).thenReturn(List.of());
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);
        registry.restorePersistedApprovals();

        assertEquals("REJECTED", approval.getState(), "重启后没有等待线程，残留审批必须落终态");
        assertEquals("SYSTEM", approval.getDecisionSource());
        assertFalse(approval.isRememberSession());
        assertNotNull(approval.getDecisionTime());
        assertFalse(registry.resolve("ap-persisted", 1L, "APPROVE", true, null),
                "残留审批不应再被批准，避免出现批准成功但无人继续执行的假动作");
        assertEquals("REJECTED", approval.getState());
        verify(repository, atLeastOnce()).save(approval);
    }

    @Test
    void 启动时过期审批直接拒绝并记录超时来源() {
        ToolApproval approval = saved("PENDING", System.currentTimeMillis() - 1);
        when(repository.findByState("PENDING")).thenReturn(List.of(approval));
        when(repository.findByStateAndRememberSessionTrue("APPROVED")).thenReturn(List.of());
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);
        registry.restorePersistedApprovals();

        assertFalse(registry.resolve("ap-persisted", 1L, "APPROVE", false, null));
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
    void 断流期间拒绝写审批表并返回本人来源() {
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);
        ToolApprovalRegistry.Pending pending = registry.create(1L, 10L, "write_file", "{}", "WRITE");
        ToolApproval persisted = saved("PENDING", pending.expiresAt());
        persisted.setApprovalId(pending.approvalId());
        when(repository.findByApprovalId(pending.approvalId())).thenReturn(Optional.of(persisted));

        // 断流后没有任何 SSE 连接参与：拒绝只按用户决策与审批表落库
        assertTrue(registry.resolve(pending.approvalId(), 1L, "REJECT", false, null));
        ToolApprovalRegistry.Resolution resolution = registry.await(pending, new AtomicBoolean(false));

        assertEquals(ToolApprovalRegistry.Decision.REJECT, resolution.decision());
        assertEquals(ToolApprovalRegistry.DecisionSource.USER, resolution.source());
        assertEquals("REJECTED", persisted.getState());
        assertEquals("USER", persisted.getDecisionSource());
        assertNull(persisted.getClientId());
        assertNotNull(persisted.getDecisionTime());
        verify(repository, times(2)).save(any(ToolApproval.class));
    }

    @Test
    void 客户端令牌审批写入口并记录客户端来源() {
        when(repository.save(any(ToolApproval.class))).thenAnswer(inv -> inv.getArgument(0));
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);
        ToolApprovalRegistry.Pending pending = registry.create(1L, 10L, "write_file", "{}", "WRITE");
        ToolApproval persisted = saved("PENDING", pending.expiresAt());
        persisted.setApprovalId(pending.approvalId());
        when(repository.findByApprovalId(pending.approvalId())).thenReturn(Optional.of(persisted));

        assertTrue(registry.resolve(pending.approvalId(), 1L, "APPROVE", false, "cli-abc1234567"));
        ToolApprovalRegistry.Resolution resolution = registry.await(pending, new AtomicBoolean(false));

        assertEquals(ToolApprovalRegistry.DecisionSource.CLIENT, resolution.source());
        assertEquals("CLIENT", persisted.getDecisionSource());
        assertEquals("cli-abc1234567", persisted.getClientId());
    }

    @Test
    void 批量审批保留批量来源并记录客户端身份() {
        when(repository.save(any(ToolApproval.class))).thenAnswer(inv -> inv.getArgument(0));
        ToolApprovalRegistry registry = new ToolApprovalRegistry(repository);
        ToolApprovalRegistry.Pending first = registry.create(1L, 10L, "write_file", "{}", "WRITE");
        ToolApprovalRegistry.Pending second = registry.create(1L, 10L, "edit_file", "{}", "WRITE");
        ToolApproval firstSaved = saved("PENDING", first.expiresAt());
        firstSaved.setApprovalId(first.approvalId());
        ToolApproval secondSaved = saved("PENDING", second.expiresAt());
        secondSaved.setApprovalId(second.approvalId());
        when(repository.findByApprovalId(first.approvalId())).thenReturn(Optional.of(firstSaved));
        when(repository.findByApprovalId(second.approvalId())).thenReturn(Optional.of(secondSaved));

        ToolApprovalRegistry.BatchResult result = registry.resolveBatch(1L, 10L,
                List.of(first.approvalId(), second.approvalId()), "vscode-abc12345");

        assertTrue(result.accepted());
        assertEquals("BATCH", firstSaved.getDecisionSource());
        assertEquals("vscode-abc12345", firstSaved.getClientId());
        assertEquals("BATCH", secondSaved.getDecisionSource());
        assertEquals("vscode-abc12345", secondSaved.getClientId());
    }

    @Test
    void 等待超时写审批表并记录超时来源() {
        ToolApprovalRegistry.Pending record = new ToolApprovalRegistry.Pending(
                "ap-timeout", 1L, 10L, "write_file", "{}", "WRITE",
                System.currentTimeMillis() - 1, new CompletableFuture<>());
        ToolApproval persisted = saved("PENDING", record.expiresAt());
        persisted.setApprovalId("ap-timeout");
        when(repository.findByApprovalId("ap-timeout")).thenReturn(Optional.of(persisted));

        ToolApprovalRegistry.Resolution resolution =
                new ToolApprovalRegistry(repository).await(record, new AtomicBoolean(false));

        assertEquals(ToolApprovalRegistry.Decision.REJECT, resolution.decision());
        assertEquals(ToolApprovalRegistry.DecisionSource.TIMEOUT, resolution.source());
        assertEquals("REJECTED", persisted.getState());
        assertEquals("TIMEOUT", persisted.getDecisionSource());
        verify(repository).save(persisted);
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
