package com.capsule.agentkey.interceptor;

import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.util.ResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;



//限流检查类
//每分钟最多 10 次；登录用户按 userId 计数，未登录（/api/auth/**）按客户端 IP 计数
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final int MAX_REQUESTS = 10;
    private static final long WINDOW_MS = 60_000;

    private static class Window {
        final long startTime;
        final AtomicInteger count = new AtomicInteger(0);
        Window(long startTime) { this.startTime = startTime; }
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitInterceptor() {
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::cleanExpired, 5, 5, TimeUnit.MINUTES);
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().startTime > WINDOW_MS);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //CORS 预检请求不占限流配额
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Long userId = getCurrentUserId();
        String key;
        if (userId != null) {
            key = "chat:" + userId;
        } else {
            //A7：未登录接口（/api/auth/**）按客户端 IP 限流，否则无凭据可依、限流会整体失效
            key = "ip:" + request.getRemoteAddr();
        }

        long now = System.currentTimeMillis();

        Window window = windows.compute(key, (k, v) -> {
            if (v == null || now - v.startTime > WINDOW_MS) {
                return new Window(now);
            }
            return v;
        });

        if (window.count.incrementAndGet() > MAX_REQUESTS) {
            //429 Too Many Requests，状态码与文案都来自 ErrorCode，此处不再手写 JSON
            ResponseWriter.writeError(response, ErrorCode.RATE_LIMITED, null);
            return false;
        }
        return true;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        return null;
    }
}