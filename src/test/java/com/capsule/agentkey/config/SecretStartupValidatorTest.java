package com.capsule.agentkey.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretStartupValidatorTest {

    private static final String JWT = "01234567890123456789012345678901";
    private static final String AES = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Environment CLOUD = new MockEnvironment();

    @Test
    void 合法密钥通过校验() {
        assertDoesNotThrow(() -> new SecretStartupValidator(JWT, AES, CLOUD).validate());
    }

    @Test
    void 缺失Jwt密钥拒绝启动() {
        assertThrows(IllegalStateException.class,
                () -> new SecretStartupValidator("", AES, CLOUD).validate());
    }

    @Test
    void 弱Jwt密钥拒绝启动() {
        assertThrows(IllegalStateException.class,
                () -> new SecretStartupValidator("too-short", AES, CLOUD).validate());
    }

    @Test
    void 非法Aes密钥拒绝启动() {
        assertThrows(IllegalStateException.class,
                () -> new SecretStartupValidator(JWT, "not-base64", CLOUD).validate());
        assertThrows(IllegalStateException.class,
                () -> new SecretStartupValidator(JWT,
                        Base64.getEncoder().encodeToString(new byte[15]), CLOUD).validate());
    }

    @Test
    void 桌面端不要求Jwt密钥但仍要求Aes密钥() {
        MockEnvironment desktop = new MockEnvironment();
        desktop.setActiveProfiles("desktop");
        assertDoesNotThrow(() -> new SecretStartupValidator("", AES, desktop).validate());
        assertThrows(IllegalStateException.class,
                () -> new SecretStartupValidator("", "", desktop).validate());
    }
}
