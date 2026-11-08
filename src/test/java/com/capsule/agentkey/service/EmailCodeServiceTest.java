package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * EmailCodeService 单元测试。
 * Redis 用「所有调用都抛异常」的 mock 代替，从而稳定走进程内降级路径，
 * 不依赖本机是否启动 Redis；Redis 主路径的键结构由实现代码固定，测试不重复验证。
 */
class EmailCodeServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private MailSender mailSender;
    private EmailCodeService service;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, invocation -> {
            throw new IllegalStateException("Redis 不可用");
        });
        mailSender = mock(MailSender.class);
        service = new EmailCodeService(redis, mailSender, clock);
    }

    @Test
    void 验证码校验成功后立即失效() {
        String code = sendAndCaptureCode("alice@example.com", EmailCodeService.Scene.REGISTER);
        assertTrue(code.matches("\\d{6}"), "验证码应为 6 位数字");

        service.verify("alice@example.com", EmailCodeService.Scene.REGISTER, code);

        BusinessException again = assertThrows(BusinessException.class,
                () -> service.verify("alice@example.com", EmailCodeService.Scene.REGISTER, code));
        assertEquals("验证码已过期，请重新获取", again.getMessage());
    }

    @Test
    void 同一邮箱同场景六十秒内不可重复发送() {
        service.sendCode("alice@example.com", EmailCodeService.Scene.REGISTER, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("alice@example.com", EmailCodeService.Scene.REGISTER, true));
        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());

        // 超过冷却时间后允许再次发送
        clock.advance(Duration.ofSeconds(61));
        service.sendCode("alice@example.com", EmailCodeService.Scene.REGISTER, true);
    }

    @Test
    void 注册与重置密码的验证码互不通用() {
        String code = sendAndCaptureCode("alice@example.com", EmailCodeService.Scene.REGISTER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify("alice@example.com", EmailCodeService.Scene.RESET, code));
        assertEquals("验证码已过期，请重新获取", ex.getMessage());
    }

    @Test
    void 连续输错五次后验证码作废() {
        String code = sendAndCaptureCode("alice@example.com", EmailCodeService.Scene.REGISTER);

        for (int i = 0; i < EmailCodeService.MAX_VERIFY_FAILURES - 1; i++) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.verify("alice@example.com", EmailCodeService.Scene.REGISTER, "000000"));
            assertEquals("验证码不正确，请重新输入", ex.getMessage());
        }
        BusinessException last = assertThrows(BusinessException.class,
                () -> service.verify("alice@example.com", EmailCodeService.Scene.REGISTER, "000000"));
        assertEquals("验证码错误次数过多，请重新获取", last.getMessage());

        // 作废后即使验证码本身正确也不再可用
        BusinessException afterInvalid = assertThrows(BusinessException.class,
                () -> service.verify("alice@example.com", EmailCodeService.Scene.REGISTER, code));
        assertEquals("验证码已过期，请重新获取", afterInvalid.getMessage());
    }

    @Test
    void 每日发送次数达到上限后限流() {
        for (int i = 0; i < EmailCodeService.DAILY_LIMIT; i++) {
            service.sendCode("alice@example.com", EmailCodeService.Scene.REGISTER, true);
            clock.advance(Duration.ofSeconds(61));
        }

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("alice@example.com", EmailCodeService.Scene.REGISTER, true));
        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        assertEquals("今日验证码发送次数已达上限，请明天再试", ex.getMessage());
    }

    @Test
    void 邮箱未注册时不投递邮件但仍返回成功() {
        service.sendCode("nobody@example.com", EmailCodeService.Scene.RESET, false);

        verify(mailSender, never()).sendVerificationCode(anyString(), anyString(), anyString(), any());
    }

    private String sendAndCaptureCode(String email, EmailCodeService.Scene scene) {
        service.sendCode(email, scene, true);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendVerificationCode(eq(email), anyString(), codeCaptor.capture(), any());
        return codeCaptor.getValue();
    }

    /** 可推进的时钟：覆盖 60 秒冷却与每日计数重置这类时间相关逻辑 */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
