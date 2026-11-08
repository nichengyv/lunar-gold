package com.capsule.agentkey.interceptor;

import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.util.ResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限流拦截器（每分钟 N 次）。
 * 计数键：登录用户按 userId，未登录（/api/auth/**）按客户端 IP。
 *
 * 【C1 多实例支持】计数存 Redis（INCR + 窗口过期），多实例共享同一份配额；
 * 这解决了原内存版的核心缺陷——两个实例各自算 10 次，实际可放行 20 次。
 *
 * 【降级策略】Redis 不可用时退回进程内计数（保留原有 Window 实现）并打 WARN 日志。
 * 选择降级而不是直接拒绝：限流是保护性措施而非业务前提，Redis 挂掉不应让整个服务不可用；
 * 代价是降级期间多实例配额不精确，属可接受损失。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String KEY_PREFIX = "ratelimit:";
    private static final long WINDOW_MS = 60_000;

    private final StringRedisTemplate redis;
    private final int maxRequests;

    private static class Window {
        final long startTime;
        final AtomicInteger count = new AtomicInteger(0);
        Window(long startTime) { this.startTime = startTime; }
    }

    /** Redis 故障时的进程内降级计数 */
    private final ConcurrentHashMap<String, Window> fallbackWindows = new ConcurrentHashMap<>();

    /** @param redis 桌面端排除了 Redis 自动配置，此时为 null，直接走进程内计数 */
    public RateLimitInterceptor(@Nullable StringRedisTemplate redis,
                                @Value("${app.rate-limit.chat-requests-per-minute:10}") int maxRequests) {
        this.redis = redis;
        this.maxRequests = maxRequests;
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::cleanExpired, 5, 5, TimeUnit.MINUTES);
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        fallbackWindows.entrySet().removeIf(e -> now - e.getValue().startTime > WINDOW_MS);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //CORS 预检请求不占限流配额
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Long userId = getCurrentUserId();
        //登录用户按 userId，未登录接口按客户端 IP——未登录时没有凭据，只能用 IP 作为限流身份
        String identity = (userId != null) ? "user:" + userId : "ip:" + request.getRemoteAddr();
        String redisKey = KEY_PREFIX + identity;

        Boolean allowed = tryConsumeInRedis(redisKey);
        if (allowed == null) {
            //Redis 不可用 → 降级到进程内计数
            allowed = tryConsumeInMemory(redisKey);
        }

        if (!allowed) {
            //429 Too Many Requests，状态码与文案都来自 ErrorCode，此处不再手写 JSON
            ResponseWriter.writeError(response, ErrorCode.RATE_LIMITED, null);
            return false;
        }
        return true;
    }

    /**
     * Redis 计数：INCR 后首次设置窗口过期，实现固定窗口限流。
     *
     * @return true=放行 false=超限 null=Redis 不可用（交由调用方降级）
     */
    private Boolean tryConsumeInRedis(String key) {
        if (redis == null) {
            return null;
        }
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return null;
            }
            //首次计数时设置过期，保证窗口自动滑动、不会永久残留
            if (count == 1L) {
                redis.expire(key, Duration.ofMillis(WINDOW_MS));
            }
            return count <= maxRequests;
        } catch (Exception e) {
            //连接拒绝/超时/认证失败都在这里，降级不中断请求
            log.warn("Redis 限流不可用，降级为进程内计数: {}", e.getMessage());
            return null;
        }
    }

    /** 进程内固定窗口计数（降级路径，语义与 Redis 版本一致） */
    private boolean tryConsumeInMemory(String key) {
        long now = System.currentTimeMillis();
        Window window = fallbackWindows.compute(key, (k, v) -> {
            if (v == null || now - v.startTime > WINDOW_MS) {
                return new Window(now);
            }
            return v;
        });
        return window.count.incrementAndGet() <= maxRequests;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        return null;
    }
}
