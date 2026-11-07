package com.capsule.agentkey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定（A7 防爆破）。
 * 同一用户名连续失败 MAX_ATTEMPTS 次后锁定 LOCK_DURATION，期间即使密码正确也拒绝登录。
 *
 * 【C1 多实例支持】计数与锁标记存 Redis（失败计数带 15 分钟 TTL），多实例共享，
 * 避免攻击者轮流打不同实例来绕过"5 次锁定"。
 *
 * 【降级策略】Redis 不可用时退回进程内计数并打 WARN：锁定是安全增强项，
 * Redis 挂掉不应导致正常用户无法登录；降级期间多实例锁定不精确，属可接受损失。
 */
@Slf4j
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final String FAIL_KEY_PREFIX = "login:fail:";
    private static final String LOCK_KEY_PREFIX = "login:lock:";

    private final StringRedisTemplate redis;

    //count：已失败次数；lockUntil：非 null 表示锁定截止时间（降级路径使用）
    private record Attempt(int count, LocalDateTime lockUntil) {}

    private final Map<String, Attempt> fallbackAttempts = new ConcurrentHashMap<>();

    public LoginAttemptService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 登录失败时调用，累计失败次数，达到阈值则启动锁定 */
    public void onFailure(String username) {
        if (username == null) return;
        try {
            String failKey = FAIL_KEY_PREFIX + username;
            Long count = redis.opsForValue().increment(failKey);
            if (count == null) {
                throw new IllegalStateException("Redis INCR 返回 null");
            }
            //首次失败设置 TTL：失败计数只统计"最近 15 分钟内的连续失败"，过期自动清零
            if (count == 1L) {
                redis.expire(failKey, LOCK_DURATION);
            }
            if (count >= MAX_ATTEMPTS) {
                //写入锁标记，TTL 到期自动解锁，无需定时任务清理
                redis.opsForValue().set(LOCK_KEY_PREFIX + username, "1", LOCK_DURATION);
                log.warn("登录失败次数达到上限，已锁定账号 [username={}]", username);
            }
        } catch (Exception e) {
            log.warn("Redis 登录计数不可用，降级为进程内计数: {}", e.getMessage());
            onFailureInMemory(username);
        }
    }

    /** 登录成功时调用，清除该用户的失败记录与锁标记 */
    public void onSuccess(String username) {
        if (username == null) return;
        try {
            redis.delete(FAIL_KEY_PREFIX + username);
            redis.delete(LOCK_KEY_PREFIX + username);
        } catch (Exception e) {
            log.warn("清理 Redis 登录计数失败: {}", e.getMessage());
        }
        fallbackAttempts.remove(username);
    }

    /** 锁定期间返回 true，此时应直接拒绝登录而不校验密码 */
    public boolean isLocked(String username) {
        if (username == null) return false;
        try {
            Boolean locked = redis.hasKey(LOCK_KEY_PREFIX + username);
            return Boolean.TRUE.equals(locked);
        } catch (Exception e) {
            log.warn("Redis 登录状态查询不可用，降级为进程内判断: {}", e.getMessage());
            return isLockedInMemory(username);
        }
    }

    // ---------------- 降级路径（Redis 不可用时） ----------------

    private void onFailureInMemory(String username) {
        fallbackAttempts.compute(username, (k, prev) -> {
            //首次失败，或上一次锁定已过期 → 重新计数
            if (prev == null || (prev.lockUntil() != null && prev.lockUntil().isBefore(LocalDateTime.now()))) {
                return new Attempt(1, null);
            }
            int next = prev.count() + 1;
            LocalDateTime locked = next >= MAX_ATTEMPTS ? LocalDateTime.now().plus(LOCK_DURATION) : null;
            return new Attempt(next, locked);
        });
    }

    private boolean isLockedInMemory(String username) {
        Attempt a = fallbackAttempts.get(username);
        return a != null && a.lockUntil() != null && a.lockUntil().isAfter(LocalDateTime.now());
    }
}
