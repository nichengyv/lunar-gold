package com.capsule.agentkey.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPolicyTest {

    @Test
    void 默认策略使用标准推理与手动审批() {
        AgentPolicy policy = AgentPolicy.defaults("deepseek-chat");

        assertEquals("deepseek-chat", policy.model());
        assertEquals(AgentPolicy.Reasoning.STANDARD, policy.reasoning());
        assertEquals(AgentPolicy.Permission.MANUAL, policy.permission());
        assertEquals(AgentPolicy.Permission.MANUAL, AgentPolicy.Permission.from("EXECUTE"));
        assertEquals(AgentPolicy.Permission.MANUAL, AgentPolicy.Permission.from("WRITE"));
        assertEquals(AgentPolicy.Permission.MANUAL, AgentPolicy.Permission.from("unknown"));
    }

    @Test
    void 推理档位解析与历史预算保持一致() {
        assertEquals(AgentPolicy.Reasoning.FAST, AgentPolicy.Reasoning.from("fast"));
        assertEquals(AgentPolicy.Reasoning.DEEP, AgentPolicy.Reasoning.from(" DEEP "));
        assertEquals(AgentPolicy.Reasoning.STANDARD, AgentPolicy.Reasoning.from(null));
        assertEquals(12, AgentPolicy.Reasoning.STANDARD.historyCount(false));
        assertEquals(4, AgentPolicy.Reasoning.STANDARD.historyCount(true));
    }

    @Test
    void 手动审批拦截所有已声明风险但不拦截普通工具() {
        AgentPolicy.Permission permission = AgentPolicy.Permission.MANUAL;

        assertTrue(permission.requiresApproval("WRITE", null));
        assertTrue(permission.requiresApproval("EXECUTE", "mvn test"));
        assertTrue(permission.requiresApproval("DESTRUCTIVE", null));
        assertFalse(permission.requiresApproval(null, null));
    }

    @Test
    void 自动审批只放行普通写入与普通命令() {
        AgentPolicy.Permission permission = AgentPolicy.Permission.AUTO;

        assertFalse(permission.requiresApproval("WRITE", null));
        assertFalse(permission.requiresApproval("EXECUTE", "mvn test"));
        assertTrue(permission.requiresApproval("EXECUTE", "git reset --hard HEAD~1"));
        assertTrue(permission.requiresApproval("DESTRUCTIVE", null));
    }

    @Test
    void 完全权限与旧只读档不触发审批但能力边界不同() {
        assertFalse(AgentPolicy.Permission.FULL.requiresApproval("DESTRUCTIVE", null));
        assertTrue(AgentPolicy.Permission.FULL.canWrite());
        assertTrue(AgentPolicy.Permission.FULL.canExecute());

        assertFalse(AgentPolicy.Permission.READ_ONLY.requiresApproval("WRITE", null));
        assertFalse(AgentPolicy.Permission.READ_ONLY.canWrite());
        assertFalse(AgentPolicy.Permission.READ_ONLY.canExecute());
    }
}
