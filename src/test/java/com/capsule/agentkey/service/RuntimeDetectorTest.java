package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T6 验收：运行时检测的解析、版本比较、require 校验。
 * 测试环境需安装 node（桌面端开发机/CI 均有）。
 */
class RuntimeDetectorTest {

    private final RuntimeDetector detector = new RuntimeDetector();

    @Test
    void compareSemverOrdersVersions() {
        assertTrue(RuntimeDetector.compareSemver("v20.10.0", "18") > 0);
        assertEquals(0, RuntimeDetector.compareSemver("18.0.0", "18"));
        assertTrue(RuntimeDetector.compareSemver("16", "18") < 0);
        assertEquals(0, RuntimeDetector.compareSemver("v18.5.0", "18.5"));
    }

    @Test
    void parseExtractsNameOpVersion() {
        RuntimeDetector.RuntimeRequirement r = detector.parse("node>=18");
        assertEquals("node", r.name());
        assertEquals(">=", r.op());
        assertEquals("18", r.version());

        RuntimeDetector.RuntimeRequirement r2 = detector.parse("python");
        assertEquals("python", r2.name());
        assertEquals("", r2.op());
    }

    @Test
    void detectUnknownRuntimeReturnsNull() {
        assertNull(detector.detect("definitely_not_a_runtime_xyz"));
    }

    @Test
    void detectNodeReturnsVersion() {
        String version = detector.detect("node");
        assertNotNull(version, "测试环境应安装 node");
        assertTrue(version.contains("."), "node 版本号应含小数点: " + version);
    }

    @Test
    void requireSatisfiedDoesNotThrow() {
        assertDoesNotThrow(() -> detector.require("node>=1"));
    }

    @Test
    void requireUnsatisfiedThrowsWithHelpfulMessage() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> detector.require("node>=99"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("node"));
        assertTrue(ex.getMessage().contains("99"));
    }

    @Test
    void requireMissingRuntimeThrows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> detector.require("definitely_not_a_runtime_xyz>=1"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("缺少运行时"));
    }
}
