package com.capsule.agentkey.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;



//限流检查类
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
//设置每个用户十秒内最多请求十次
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
        Long userId = getCurrentUserId();
        if (userId == null) {
            return true;
        }

        String key = "chat:" + userId;
        long now = System.currentTimeMillis();

        Window window = windows.compute(key, (k, v) -> {
            if (v == null || now - v.startTime > WINDOW_MS) {
                return new Window(now);
            }
            return v;
        });

        if (window.count.incrementAndGet() > MAX_REQUESTS) {
            writeError(response, 429, "请求过于频繁，请稍后再试");
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

    private void writeError(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
            String.format("{\"code\":%d,\"message\":\"%s\",\"data\":null,\"timestamp\":%d}", code, msg, System.currentTimeMillis())
        );
    }
}