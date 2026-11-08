package com.capsule.agentkey.service.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTokenServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void 首次启动生成令牌并落盘() throws Exception {
        LocalTokenService service = new LocalTokenService(tempDir.toString(), "", false);

        assertNotNull(service.token());
        assertTrue(service.token().length() >= 40, "32 字节 Base64URL 编码后应不短于 40 个字符");
        Path tokenFile = tempDir.resolve(LocalTokenService.TOKEN_FILE_NAME);
        assertTrue(Files.isRegularFile(tokenFile));
        assertEquals(service.token(), Files.readString(tokenFile).trim());
    }

    @Test
    void 相同目录重复启动复用已有令牌() {
        LocalTokenService first = new LocalTokenService(tempDir.toString(), "", false);
        LocalTokenService second = new LocalTokenService(tempDir.toString(), "", false);

        assertEquals(first.token(), second.token());
    }

    @Test
    void 校验区分正确与错误令牌() {
        LocalTokenService service = new LocalTokenService(tempDir.toString(), "", false);

        assertTrue(service.matches(service.token()));
        assertTrue(service.matches("  " + service.token() + " "));
        assertFalse(service.matches("wrong-token"));
        assertFalse(service.matches(""));
        assertFalse(service.matches(null));
    }

    @Test
    void 配置的固定开发令牌同样被接受() {
        LocalTokenService service = new LocalTokenService(tempDir.toString(), "dev-token-123", false);

        assertTrue(service.matches("dev-token-123"));
        assertTrue(service.matches(service.token()));
        assertFalse(service.matches("other-token"));
    }
}
