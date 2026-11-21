package com.capsule.agentkey.agent.tool;

import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.SessionPermissionTracker;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * R3：串行执行链路与只读并行批的契约。
 *
 * <p>重点保证三件事：审批发生在执行之前且拒绝后绝不执行；
 * 无需审批时直接走 {@link ApprovalGate#dispatch}（scope 唯一出口）；
 * 只读批里每个调用同样经过 gate，不存在绕过 scope 的旁路。
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    @Mock ToolRegistry registry;
    @Mock ToolApprovalRegistry approvals;
    @Mock ApprovalGate gate;
    @Mock ToolExecutor.ApprovalUi ui;

    private final SessionPermissionTracker permissionTracker = new SessionPermissionTracker();
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ToolExecutor(registry, approvals, gate, permissionTracker, 3);
    }

    @Test
    void 需要审批时先询问再执行() {
        ToolApprovalRegistry.Pending pending = pending("write_file");
        when(gate.assess("write_file", "{}", AgentPolicy.Permission.MANUAL, 1L))
                .thenReturn(new ApprovalGate.Risk("WRITE", true));
        when(approvals.create(1L, 1L, "write_file", "{}", "WRITE")).thenReturn(pending);
        when(approvals.await(eq(pending), any()))
                .thenReturn(ToolApprovalRegistry.Resolution.of(
                        ToolApprovalRegistry.Decision.APPROVE, ToolApprovalRegistry.DecisionSource.USER));
        when(gate.dispatch("write_file", "{}", AgentPolicy.Permission.MANUAL))
                .thenReturn(ToolResult.ok("done"));

        ToolExecutor.Outcome outcome = executor.execute("write_file", "{}", 1L, 1L,
                AgentPolicy.Permission.MANUAL, new AtomicBoolean(), ui);

        assertTrue(outcome.result().isSuccess());
        assertEquals("WRITE", outcome.risk());
        assertFalse(outcome.approvalRejected());

        InOrder order = inOrder(ui, gate);
        order.verify(ui).onApprovalRequired(pending, "WRITE");
        order.verify(ui).onApprovalResolved(pending,
                ToolApprovalRegistry.Resolution.of(
                        ToolApprovalRegistry.Decision.APPROVE, ToolApprovalRegistry.DecisionSource.USER));
        order.verify(gate).dispatch("write_file", "{}", AgentPolicy.Permission.MANUAL);
    }

    @Test
    void 用户拒绝后不执行工具() {
        ToolApprovalRegistry.Pending pending = pending("run_command");
        when(gate.assess(eq("run_command"), anyString(), any(), anyLong()))
                .thenReturn(new ApprovalGate.Risk("EXECUTE", true));
        when(approvals.create(anyLong(), anyLong(), eq("run_command"), anyString(), eq("EXECUTE")))
                .thenReturn(pending);
        when(approvals.await(eq(pending), any()))
                .thenReturn(ToolApprovalRegistry.Resolution.of(
                        ToolApprovalRegistry.Decision.REJECT, ToolApprovalRegistry.DecisionSource.USER));
        when(gate.rejection("run_command", "EXECUTE")).thenReturn(ToolResult.fail("已拒绝"));

        ToolExecutor.Outcome outcome = executor.execute("run_command", "{\"command\":\"rm -rf /\"}",
                1L, 1L, AgentPolicy.Permission.MANUAL, new AtomicBoolean(), ui);

        assertFalse(outcome.result().isSuccess());
        assertTrue(outcome.approvalRejected());
        verify(gate, never()).dispatch(anyString(), anyString(), any());
    }

    @Test
    void 无需审批时直接交给gate执行() {
        when(gate.assess(eq("read_file"), anyString(), any(), anyLong()))
                .thenReturn(ApprovalGate.Risk.none());
        when(gate.dispatch(eq("read_file"), anyString(), any()))
                .thenReturn(ToolResult.ok("content"));

        ToolExecutor.Outcome outcome = executor.execute("read_file", "{}", 1L, 1L,
                AgentPolicy.Permission.MANUAL, new AtomicBoolean(), ui);

        assertTrue(outcome.result().isSuccess());
        assertNull(outcome.risk());
        verify(approvals, never()).create(anyLong(), anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(ui);
    }

    @Test
    void 只读批中每个调用都经过gate执行并按下标对齐() {
        when(registry.isReadOnly("read_file")).thenReturn(true);
        when(gate.dispatch(eq("read_file"), anyString(), any()))
                .thenAnswer(inv -> ToolResult.ok("content:" + inv.getArgument(1)));

        List<ObjectNode> calls = List.of(
                call("read_file", "{\"path\":\"a.txt\"}"),
                call("read_file", "{\"path\":\"b.txt\"}"));

        Map<Integer, ToolResult> results = executor.executeReadOnlyBatch(
                calls, 0, 1L, AgentPolicy.defaults("test"), new AtomicBoolean());

        assertEquals(2, results.size());
        assertEquals("content:{\"path\":\"a.txt\"}", results.get(0).getContent());
        assertEquals("content:{\"path\":\"b.txt\"}", results.get(1).getContent());
        verify(gate, times(2)).dispatch(eq("read_file"), anyString(), any());
    }

    @Test
    void 单个只读调用不进入并行批() {
        when(registry.isReadOnly("read_file")).thenReturn(true);

        Map<Integer, ToolResult> results = executor.executeReadOnlyBatch(
                List.of(call("read_file", "{}")), 0, 1L,
                AgentPolicy.defaults("test"), new AtomicBoolean());

        assertTrue(results.isEmpty());
        verifyNoInteractions(gate);
    }

    @Test
    void 写入询问排除在并行批之外() {
        when(registry.isReadOnly("write_file")).thenReturn(true);
        when(registry.isReadOnly("read_file")).thenReturn(true);

        assertFalse(executor.isParallelizable(call("ask_user_question", "{}")));
        assertFalse(executor.isParallelizable(call("write_file", "{}")));

        when(registry.hasFallback("read_file")).thenReturn(true);
        assertFalse(executor.isParallelizable(call("read_file", "{}")));
    }

    private ToolApprovalRegistry.Pending pending(String tool) {
        return new ToolApprovalRegistry.Pending("ap-1", 1L, 1L, tool, "{}", "WRITE",
                System.currentTimeMillis() + 60_000, new CompletableFuture<>());
    }

    private ObjectNode call(String name, String arguments) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ObjectNode function = node.putObject("function");
        function.put("name", name);
        function.put("arguments", arguments);
        return node;
    }
}
