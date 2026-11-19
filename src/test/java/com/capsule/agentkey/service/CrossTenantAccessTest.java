package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * P4-08 安全回归：跨用户（跨租户）越权。
 *
 * 只验证真正的拦截点——会话归属、运行归属、审批归属，
 * 路径逃逸与命令注入分别由 WorkspaceFileServiceTest / CommandServiceTest 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class CrossTenantAccessTest {

    @Mock ChatSessionRepository sessionRepo;
    @Mock AgentRunRepository runRepo;
    @Mock AgentRunEventRepository eventRepo;

    @Test
    void 读取他人会话被拒绝() throws Exception {
        ChatSession session = new ChatSession();
        session.setId(10L);
        session.setUserId(1L);
        when(sessionRepo.findById(10L)).thenReturn(Optional.of(session));

        ChatService chatService = new ChatService();
        set(chatService, "sessionRepo", sessionRepo);

        BusinessException denied = assertThrows(BusinessException.class,
                () -> chatService.getOwnedSession(10L, 2L));
        assertTrue(denied.getMessage().contains("无权"), "越权应给出无权限提示");
        // 同一会话对本人仍然可读，避免过度拦截
        assertEquals(10L, chatService.getOwnedSession(10L, 1L).getId());
    }

    @Test
    void 他人的运行与事件流不可见() {
        AgentRun run = new AgentRun();
        run.setId(77L);
        run.setUserId(1L);
        when(runRepo.findById(77L)).thenReturn(Optional.of(run));

        AgentRunService runService = new AgentRunService(runRepo, eventRepo, new ObjectMapper());

        // SSE 续传接口先做 findByIdAndUserId，查不到直接 400，不会走到读取事件表
        assertTrue(runService.findByIdAndUserId(77L, 2L).isEmpty());
        assertEquals(77L, runService.findByIdAndUserId(77L, 1L).orElseThrow().getId());
    }

    @Test
    void 他人的待审批不能被代为决策() {
        ToolApprovalRegistry registry = new ToolApprovalRegistry();
        ToolApprovalRegistry.Pending pending =
                registry.create(1L, 10L, "write_file", "{\"path\":\"a.txt\"}", "WRITE");

        assertFalse(registry.resolve(pending.approvalId(), 2L, "APPROVE", false, "cli-abc1234567"),
                "非归属用户的审批提交必须被拒绝");
        assertFalse(pending.future().isDone(), "被拒绝的提交不能唤醒等待中的 ReAct 线程");

        assertTrue(registry.resolve(pending.approvalId(), 1L, "APPROVE", false, null),
                "归属用户仍可正常审批");
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
