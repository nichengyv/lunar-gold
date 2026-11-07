package com.capsule.agentkey.controller;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口。
 *
 * /api/health —— 设置页"关于"tab 用。返回运行时长 + JVM 启动时间 + 当前版本。
 * 设计：用 EventListener 记应用启动时刻，避免 System.currentTimeMillis() 在容器里不准。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static volatile Instant startedAt = Instant.now();

    @EventListener(ApplicationStartedEvent.class)
    public void onStarted(ApplicationStartedEvent event) {
        startedAt = Instant.now();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "UP");
        // TODO: 从 pom.xml 读取版本（打包时 spring-boot-maven-plugin 会写入 MANIFEST.MF）
        resp.put("version", "1.0.0-SNAPSHOT");
        // JVM 级运行时长（System 精度比我们自己记的更底层）
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        resp.put("uptimeMs", uptimeMs);
        resp.put("startedAt", startedAt.toString());
        resp.put("timestamp", Instant.now().toString());
        return resp;
    }
}
