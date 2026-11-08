package com.capsule.agentkey.service;

import com.capsule.agentkey.interceptor.RateLimitInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 桌面端排除了 Redis 自动配置，以下组件会拿到 null 的 StringRedisTemplate。
 * 这里锁定「无 Redis 时仍然可用」的契约，避免以后把注入改回必填导致桌面端起不来。
 */
class NoRedisDegradationTest {

    @Test
    void 登录失败锁定在无Redis时走进程内计数() {
        LoginAttemptService service = new LoginAttemptService(null);

        for (int i = 0; i < 5; i++) {
            service.onFailure("alice");
        }

        assertTrue(service.isLocked("alice"), "连续 5 次失败后应锁定");
        service.onSuccess("alice");
        assertFalse(service.isLocked("alice"), "登录成功后应清除锁定");
    }

    @Test
    void 会话白名单在无Redis时放行且撤销不报错() {
        TokenSessionService service = new TokenSessionService(null);

        assertTrue(service.isActive("any-jti"), "没有白名单可查时应放行，避免全站 401");
        assertDoesNotThrow(() -> {
            service.register(1L, "jti-1", 60_000L);
            service.revoke(1L, "jti-1");
            service.revokeAll(1L);
        });
    }

    @Test
    void 验证码在无Redis时走进程内存储() {
        MailSender mailSender = mock(MailSender.class);
        EmailCodeService service = new EmailCodeService(null, mailSender, Clock.systemUTC());

        service.sendCode("alice@example.com", EmailCodeService.Scene.REGISTER, true);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendVerificationCode(
                eq("alice@example.com"), eq("注册"), code.capture(), any());

        assertDoesNotThrow(() ->
                service.verify("alice@example.com", EmailCodeService.Scene.REGISTER, code.getValue()));
    }

    @Test
    void 限流拦截器在无Redis时使用进程内计数() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(null, 1);
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/agent/chat");
        request.setRemoteAddr("127.0.0.1");

        assertTrue(interceptor.preHandle(request,
                new org.springframework.mock.web.MockHttpServletResponse(), new Object()));
        // 超过每分钟上限后，进程内计数同样要拦截
        assertFalse(interceptor.preHandle(request,
                new org.springframework.mock.web.MockHttpServletResponse(), new Object()));
    }
}
