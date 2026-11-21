package com.capsule.agentkey.agent.tool;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.ScopeConstraintService;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R3：审批与 scope 判定的唯一入口契约。
 *
 * <p>这些断言保证「哪些调用必须审批」不再散落在主循环分支里：
 * 只读不需要、写按档位、破坏性在自动档也要、MCP/fallback 强制、
 * 会话已允许时跳过；scope 校验失败时绝不 dispatch。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalGateTest {

    @Mock ToolApprovalRegistry approvals;
    @Mock ToolRegistry registry;
    @Mock ScopeConstraintService scope;

    private ApprovalGate gate;

    @BeforeEach
    void setUp() {
        gate = new ApprovalGate(approvals, registry, scope);
    }

    @Test
    void 只读工具永远不需要审批() {
        ApprovalGate.Risk risk = gate.assess("read_file", "{\"path\":\"a.txt\"}",
                AgentPolicy.Permission.MANUAL, 1L);

        assertNull(risk.level());
        assertFalse(risk.requiresApproval());
        assertFalse(risk.isWriteLike());
    }

    @Test
    void 手动档写文件需要审批_完全权限不需要() {
        String args = "{\"path\":\"a.txt\",\"content\":\"x\"}";

        assertTrue(gate.assess("write_file", args, AgentPolicy.Permission.MANUAL, 1L)
                .requiresApproval());
        assertFalse(gate.assess("write_file", args, AgentPolicy.Permission.FULL, 1L)
                .requiresApproval());
    }

    @Test
    void 自动档下递归删除仍需要审批() {
        ApprovalGate.Risk risk = gate.assess("delete_file",
                "{\"path\":\"tmp\",\"recursive\":true}", AgentPolicy.Permission.AUTO, 1L);

        assertEquals("DESTRUCTIVE", risk.level());
        assertTrue(risk.requiresApproval());
        assertTrue(risk.isWriteLike());
    }

    @Test
    void 普通删除在自动档不需要审批() {
        ApprovalGate.Risk risk = gate.assess("delete_file",
                "{\"path\":\"a.txt\"}", AgentPolicy.Permission.AUTO, 1L);

        assertEquals("WRITE", risk.level());
        assertFalse(risk.requiresApproval());
    }

    @Test
    void MCP写操作即使在完全权限下也强制审批() {
        ApprovalGate.Risk risk = gate.assess("mcp_filesystem_write_file",
                "{\"path\":\"a.txt\"}", AgentPolicy.Permission.FULL, 1L);

        assertEquals("WRITE", risk.level());
        assertTrue(risk.requiresApproval());
    }

    @Test
    void 内置写工具的MCP_fallback同样强制审批() {
        when(registry.hasFallback("write_file")).thenReturn(true);

        assertTrue(gate.assess("write_file", "{\"path\":\"a.txt\"}",
                AgentPolicy.Permission.FULL, 1L).requiresApproval());
    }

    @Test
    void 会话已允许该工具时跳过审批() {
        when(approvals.isSessionAllowed(9L, "write_file")).thenReturn(true);

        assertFalse(gate.assess("write_file", "{\"path\":\"a.txt\"}",
                AgentPolicy.Permission.MANUAL, 9L).requiresApproval());
    }

    @Test
    void scope校验失败时不执行工具只返回错误() {
        doThrow(new BusinessException("路径不在本轮范围内: secrets/"))
                .when(scope).checkToolCall(anyString(), any(), any());

        ToolResult result = gate.dispatch("write_file", "{\"path\":\"secrets/a.txt\"}",
                AgentPolicy.Permission.FULL);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("secrets/"));
        verify(registry, never()).dispatch(anyString(), anyString(), any());
    }

    @Test
    void scope通过后交给注册表执行() {
        when(registry.dispatch("read_file", "{\"path\":\"a.txt\"}", AgentPolicy.Permission.AUTO))
                .thenReturn(ToolResult.ok("content"));

        ToolResult result = gate.dispatch("read_file", "{\"path\":\"a.txt\"}",
                AgentPolicy.Permission.AUTO);

        assertTrue(result.isSuccess());
        assertEquals("content", result.getContent());
        verify(scope).checkToolCall(eq("read_file"), any(), isNull());
    }

    @Test
    void 非法JSON参数跳过scope解析仍交给工具自己报错() {
        when(registry.dispatch("read_file", "not-json", AgentPolicy.Permission.AUTO))
                .thenReturn(ToolResult.fail("参数不合法"));

        ToolResult result = gate.dispatch("read_file", "not-json", AgentPolicy.Permission.AUTO);

        assertFalse(result.isSuccess());
        verify(scope).checkToolCall(eq("read_file"), isNull(), isNull());
    }

    @Test
    void 拒绝文案按操作类型区分() {
        assertTrue(gate.rejection("run_command", "EXECUTE").getError().contains("执行命令"));
        assertTrue(gate.rejection("delete_file", "DESTRUCTIVE").getError().contains("递归删除目录"));
        assertTrue(gate.rejection("write_file", "WRITE").getError().contains("写入文件"));
    }
}
