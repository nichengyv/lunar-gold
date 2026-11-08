package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮箱验证码：注册与找回密码共用同一套发放/校验规则。
 *
 * 规则：
 * 1. 6 位数字码，有效期 10 分钟；
 * 2. 同一邮箱同一场景 60 秒内不可重复发送，每日上限 10 次；
 * 3. 连续输错 5 次立即作废，需重新获取；
 * 4. 校验成功后立即消费，不能重复使用。
 *
 * 存储优先走 Redis（多实例共享，键带 TTL 自动过期）；
 * Redis 不可用时降级为进程内存储并打 WARN——验证码是可用性功能，
 * 不应因缓存故障导致全站无法注册，代价是降级期间多实例计数不精确。
 */
@Slf4j
@Service
public class EmailCodeService {

    /** 验证码场景：注册 / 重置密码，两个场景的验证码互不通用 */
    public enum Scene {
        REGISTER, RESET
    }

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);
    static final int DAILY_LIMIT = 10;
    static final int MAX_VERIFY_FAILURES = 5;

    private static final String CODE_KEY_PREFIX = "auth:email-code:code:";
    private static final String COOLDOWN_KEY_PREFIX = "auth:email-code:cooldown:";
    private static final String DAILY_KEY_PREFIX = "auth:email-code:daily:";
    private static final String FAIL_KEY_PREFIX = "auth:email-code:fail:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final MailSender mailSender;
    private final Clock clock;

    // ---------------- 降级路径（Redis 不可用时） ----------------
    private final Map<String, MemoryCode> memoryCodes = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> memoryCooldowns = new ConcurrentHashMap<>();
    private final Map<String, MemoryDaily> memoryDailies = new ConcurrentHashMap<>();

    private record MemoryCode(String code, LocalDateTime expiresAt, int failures) {}

    private record MemoryDaily(LocalDate date, int count) {}

    /**
     * 生产构造器；桌面端排除了 Redis 自动配置，redis 为 null 时全部走进程内存储。
     * 必须显式 @Autowired：本类还有一个带 Clock 的包内构造器，Spring 不会自动挑多构造器中的一个。
     */
    @Autowired
    public EmailCodeService(@Nullable StringRedisTemplate redis, MailSender mailSender) {
        this(redis, mailSender, Clock.systemUTC());
    }

    /** 测试用构造器：注入可控时钟，便于覆盖 60 秒冷却与每日上限 */
    EmailCodeService(StringRedisTemplate redis, MailSender mailSender, Clock clock) {
        this.redis = redis;
        this.mailSender = mailSender;
        this.clock = clock;
    }

    /**
     * 生成并投递验证码。
     *
     * @param email   已规范化的邮箱（小写、去空格）
     * @param scene   场景
     * @param deliver 是否真正发信。找回密码时邮箱未注册会传 false：
     *                仍然生成与限频，但不再向陌生地址发信，且调用方响应保持一致，不泄露账号是否存在
     */
    public void sendCode(String email, Scene scene, boolean deliver) {
        String identity = identity(scene, email);
        acquireSendSlot(identity, email);

        String code = generateCode();
        storeCode(identity, code);

        if (deliver) {
            mailSender.sendVerificationCode(email, sceneLabel(scene), code, CODE_TTL);
        } else {
            log.info("验证码已生成但不投递（邮箱未注册）[scene={}]", scene);
        }
    }

    /**
     * 校验并消费验证码。失败抛 BusinessException，成功返回后该码立即失效。
     */
    public void verify(String email, Scene scene, String code) {
        String identity = identity(scene, email);
        String expected = getCode(identity);
        if (expected == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "验证码已过期，请重新获取");
        }
        if (!expected.equals(code == null ? null : code.trim())) {
            int failures = recordFailure(identity);
            if (failures >= MAX_VERIFY_FAILURES) {
                clearCode(identity);
                throw new BusinessException(ErrorCode.PARAM_INVALID, "验证码错误次数过多，请重新获取");
            }
            throw new BusinessException(ErrorCode.PARAM_INVALID, "验证码不正确，请重新输入");
        }
        // 成功即消费，防止同一验证码被重复用于注册/改密
        clearCode(identity);
    }

    // ---------------- 限频 ----------------

    private void acquireSendSlot(String identity, String email) {
        if (redis == null) {
            acquireSendSlotInMemory(identity, email);
            return;
        }
        try {
            if (Boolean.TRUE.equals(redis.hasKey(COOLDOWN_KEY_PREFIX + identity))) {
                throw cooldownException();
            }
            String dailyKey = dailyKey(email);
            String used = redis.opsForValue().get(dailyKey);
            if (used != null && Integer.parseInt(used) >= DAILY_LIMIT) {
                throw dailyLimitException();
            }
            // 先置冷却再计数：即使后续步骤失败，也不会让同一邮箱被瞬间连发
            redis.opsForValue().set(COOLDOWN_KEY_PREFIX + identity, "1", SEND_COOLDOWN);
            Long count = redis.opsForValue().increment(dailyKey);
            if (count != null && count == 1L) {
                redis.expire(dailyKey, Duration.ofDays(1));
            }
            return;
        } catch (BusinessException e) {
            // 业务异常是限频结论，不能被下面的兜底 catch 吞掉
            throw e;
        } catch (Exception e) {
            log.warn("Redis 验证码限频不可用，降级为进程内限频: {}", e.getMessage());
        }
        acquireSendSlotInMemory(identity, email);
    }

    private void acquireSendSlotInMemory(String identity, String email) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime until = memoryCooldowns.get(identity);
        if (until != null) {
            if (until.isAfter(now)) {
                throw cooldownException();
            }
            memoryCooldowns.remove(identity);
        }
        LocalDate today = LocalDate.now(clock);
        MemoryDaily daily = memoryDailies.get(email);
        if (daily == null || !daily.date().equals(today)) {
            daily = new MemoryDaily(today, 0);
        }
        if (daily.count() >= DAILY_LIMIT) {
            throw dailyLimitException();
        }
        memoryDailies.put(email, new MemoryDaily(today, daily.count() + 1));
        memoryCooldowns.put(identity, now.plus(SEND_COOLDOWN));
    }

    private String dailyKey(String email) {
        return DAILY_KEY_PREFIX + LocalDate.now(clock) + ":" + email;
    }

    private BusinessException cooldownException() {
        return new BusinessException(ErrorCode.RATE_LIMITED, "验证码发送过于频繁，请 60 秒后再试");
    }

    private BusinessException dailyLimitException() {
        return new BusinessException(ErrorCode.RATE_LIMITED, "今日验证码发送次数已达上限，请明天再试");
    }

    // ---------------- 存储 ----------------

    private void storeCode(String identity, String code) {
        if (redis != null) {
            try {
                redis.opsForValue().set(CODE_KEY_PREFIX + identity, code, CODE_TTL);
                redis.delete(FAIL_KEY_PREFIX + identity);
                return;
            } catch (Exception e) {
                log.warn("Redis 验证码存储不可用，降级为进程内存储: {}", e.getMessage());
            }
        }
        memoryCodes.put(identity, new MemoryCode(code, LocalDateTime.now(clock).plus(CODE_TTL), 0));
    }

    private String getCode(String identity) {
        if (redis != null) {
            try {
                return redis.opsForValue().get(CODE_KEY_PREFIX + identity);
            } catch (Exception e) {
                log.warn("Redis 验证码读取不可用，降级为进程内读取: {}", e.getMessage());
            }
        }
        MemoryCode entry = memoryCodes.get(identity);
        if (entry == null) {
            return null;
        }
        if (!entry.expiresAt().isAfter(LocalDateTime.now(clock))) {
            memoryCodes.remove(identity);
            return null;
        }
        return entry.code();
    }

    private int recordFailure(String identity) {
        if (redis != null) {
            try {
                Long count = redis.opsForValue().increment(FAIL_KEY_PREFIX + identity);
                if (count == null) {
                    throw new IllegalStateException("Redis INCR 返回 null");
                }
                if (count == 1L) {
                    redis.expire(FAIL_KEY_PREFIX + identity, CODE_TTL);
                }
                return count.intValue();
            } catch (Exception e) {
                log.warn("Redis 验证码失败计数不可用，降级为进程内计数: {}", e.getMessage());
            }
        }
        MemoryCode updated = memoryCodes.compute(identity, (k, prev) -> {
            if (prev == null || !prev.expiresAt().isAfter(LocalDateTime.now(clock))) {
                return null;
            }
            return new MemoryCode(prev.code(), prev.expiresAt(), prev.failures() + 1);
        });
        return updated == null ? 1 : updated.failures();
    }

    private void clearCode(String identity) {
        if (redis != null) {
            try {
                redis.delete(CODE_KEY_PREFIX + identity);
                redis.delete(FAIL_KEY_PREFIX + identity);
            } catch (Exception e) {
                log.warn("Redis 验证码清理失败: {}", e.getMessage());
            }
        }
        memoryCodes.remove(identity);
    }

    // ---------------- 工具 ----------------

    private String identity(Scene scene, String email) {
        return scene.name() + ":" + email;
    }

    private String sceneLabel(Scene scene) {
        return scene == Scene.REGISTER ? "注册" : "重置密码";
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
