package com.capsule.agentkey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * JWT 会话白名单：让签发出的 token 可主动撤销。
 *
 * 背景：纯无状态 JWT 一旦签发只能等过期，用户登出 / 改密码 / 手机丢失
 * 都无法让旧 token 立即失效。这里把每次登录签发的 jti 写入 Redis 白名单，
 * 过滤器校验时除验签外还要确认 jti 仍在白名单，撤销 = 删 key。
 *
 * 键结构：
 *   jwt:session:{jti}              → userId       （TTL = JWT 过期时间，到期自动清理）
 *   jwt:user:{userId}:sessions     → Set{jti}     （用于"撤销该用户所有 token"）
 *
 * 【降级策略】Redis 不可用时所有读操作默认放行（打 WARN），写操作静默失败：
 * 可用性优先——Redis 短暂故障不应导致全站 401。代价是降级期间撤销不生效。
 */
@Slf4j
@Service
public class TokenSessionService {

    private static final String SESSION_KEY_PREFIX = "jwt:session:";
    private static final String USER_SESSIONS_PREFIX = "jwt:user:";
    private static final String USER_SESSIONS_SUFFIX = ":sessions";

    private final StringRedisTemplate redis;

    public TokenSessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 登录成功后登记会话：jti → userId，同时挂到用户的会话集合上 */
    public void register(Long userId, String jti, long ttlMillis) {
        if (userId == null || jti == null) return;
        try {
            redis.opsForValue().set(SESSION_KEY_PREFIX + jti, String.valueOf(userId),
                    Duration.ofMillis(ttlMillis));
            redis.opsForSet().add(userSessionsKey(userId), jti);
        } catch (Exception e) {
            log.warn("Redis 登记 JWT 会话失败（撤销功能暂不可用）: {}", e.getMessage());
        }
    }

    /** 过滤器校验：jti 仍在白名单则有效。Redis 故障时放行（可用性优先）。 */
    public boolean isActive(String jti) {
        if (jti == null) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey(SESSION_KEY_PREFIX + jti));
        } catch (Exception e) {
            log.warn("Redis 查询 JWT 会话失败，降级放行: {}", e.getMessage());
            return true;
        }
    }

    /** 登出：仅撤销当前 token */
    public void revoke(Long userId, String jti) {
        if (jti == null) return;
        try {
            redis.delete(SESSION_KEY_PREFIX + jti);
            if (userId != null) {
                redis.opsForSet().remove(userSessionsKey(userId), jti);
            }
        } catch (Exception e) {
            log.warn("Redis 撤销 JWT 会话失败: {}", e.getMessage());
        }
    }

    /** 改密码 / 账号被封 / 手机丢失：撤销该用户所有 token */
    public void revokeAll(Long userId) {
        if (userId == null) return;
        try {
            String setKey = userSessionsKey(userId);
            Set<String> jtis = redis.opsForSet().members(setKey);
            if (jtis != null && !jtis.isEmpty()) {
                redis.delete(jtis.stream().map(j -> SESSION_KEY_PREFIX + j).toList());
            }
            redis.delete(setKey);
            log.info("已撤销用户全部 JWT 会话 [userId={}, count={}]", userId, jtis == null ? 0 : jtis.size());
        } catch (Exception e) {
            log.warn("Redis 批量撤销 JWT 会话失败: {}", e.getMessage());
        }
    }

    private String userSessionsKey(Long userId) {
        return USER_SESSIONS_PREFIX + userId + USER_SESSIONS_SUFFIX;
    }
}
