package com.capsule.agentkey.service;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.DeploymentMode;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityServiceTest {

    @Test
    void 未指定profile时默认云端能力() {
        CapabilityService service = new CapabilityService(new MockEnvironment());

        assertEquals(DeploymentMode.CLOUD, service.mode());
        assertEquals(EnumSet.of(Capability.CHAT, Capability.KNOWLEDGE, Capability.DOWNLOAD),
                service.enabled());
        assertFalse(service.has(Capability.LOCAL_WORKSPACE));
    }

    @Test
    void cloudProfile仅开放对话知识库和下载() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("cloud");
        CapabilityService service = new CapabilityService(environment);

        assertEquals(DeploymentMode.CLOUD, service.mode());
        assertTrue(service.has(Capability.CHAT));
        assertTrue(service.has(Capability.KNOWLEDGE));
        assertTrue(service.has(Capability.DOWNLOAD));
        assertFalse(service.has(Capability.COMMAND_EXEC));
        assertFalse(service.has(Capability.GIT));
        assertFalse(service.has(Capability.MCP_WRITE));
        assertFalse(service.has(Capability.LSP));
        assertFalse(service.has(Capability.APPROVAL));
    }

    @Test
    void desktopProfile开放本地能力但不提供桌面下载() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("desktop");
        CapabilityService service = new CapabilityService(environment);

        EnumSet<Capability> expected = EnumSet.allOf(Capability.class);
        expected.remove(Capability.DOWNLOAD);
        assertEquals(DeploymentMode.DESKTOP, service.mode());
        assertEquals(expected, service.enabled());
        assertTrue(service.has(Capability.LOCAL_WORKSPACE));
        assertTrue(service.has(Capability.COMMAND_EXEC));
        assertTrue(service.has(Capability.MCP_READ));
        assertTrue(service.has(Capability.MCP_WRITE));
        assertFalse(service.has(Capability.DOWNLOAD));
    }

    @Test
    void require仅拒绝当前模式未启用的能力() {
        CapabilityService service = new CapabilityService(new MockEnvironment());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.require(Capability.LOCAL_WORKSPACE));
        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        assertDoesNotThrow(() -> service.require(Capability.CHAT));
    }
}
