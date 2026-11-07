package com.capsule.agentkey.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretStartupValidatorTest {

    private static final String JWT = "01234567890123456789012345678901";
    private static final String AES = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void 合法密钥通过校验() {
        assertDoesNotThrow(() -> new SecretStartupValidator(JWT, AES).validate());
    }

    @Test
    void 缺失Jwt密钥拒绝启动() {
        assertThrows(IllegalStateException.class,
                () -> new SecretStartupValidator("", AES).validate());
    }

    @Test
    void 弱Jwt密钥拒绝启动() {
        assertThrows(IllegalStateException.class,
                () -> new SecretStartupValidator("too-short", AES).validate());
    }

    @Test
    void 非法Aes密钥拒绝启动() {
        assertThrows(IllegalStateException.class,
                () -> new SecretStartupValidator(JWT, "not-base64").validate());
        assertThrows(IllegalStateException.class,
                () -> new SecretStartupValidator(JWT,
                        Base64.getEncoder().encodeToString(new byte[15])).validate());
    }
}
