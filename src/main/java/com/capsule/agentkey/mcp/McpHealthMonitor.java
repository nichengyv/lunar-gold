package com.capsule.agentkey.mcp;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第五十二批：MCP Server 健康监控 + 熔断。
 *
 * 为什么需要：MCP server 是外部进程（npx 拉起的 Node.js），随时可能挂掉（拉包失败、
 * 进程崩溃、网络超时）。之前 McpToolBridge 只有 try/catch 跳过单个 server，没有主动探测
 * 也没有熔断——server 持续失败时模型每次调它都要等 22 秒超时，把整个对话拖死。
 *
 * 做什么：
 * - 维护每个 server 的状态（UP / DOWN / UNKNOWN）和连续失败计数
 * - 定时 ping（每 30 秒），失败则标记 DOWN，成功则恢复 UP
 * - 业务调用时先查状态：DOWN 的 server 直接返回"server 不可用"，不浪费超时等待
 * - DOWN 超过冷却期（5 分钟）→ 放行一次探测调用，成功则回 UP
 * - 暴露 Prometheus 指标 mcp_server_up{name=...}（0=DOWN, 1=UP, -1=UNKNOWN）
 *
 * 这是一个轻量实现——没有引入 Resilience4j 之类的重型熔断库，
 * 只用 AtomicInteger + ConcurrentHashMap + 定时任务，对我们的场景够用。
 */
@Component
@Slf4j
public class McpHealthMonitor {

    public enum ServerStatus {
        /** 正常：最近一次探测或调用成功 */
        UP,
        /** 熔断：连续失败次数超过阈值，短期内不再尝试调用 */
        DOWN,
        /** 未初始化：还没注册过这个 server */
        UNKNOWN
    }

    /** 单个 server 的健康记录 */
    public static class ServerHealth {
        public final String serverName;
        public volatile ServerStatus status;
        public final AtomicInteger consecutiveFailures;
        public final AtomicInteger consecutiveSuccesses;
        public volatile Instant lastCheckAt;
        public volatile Instant lastSuccessAt;
        public volatile Instant lastFailureAt;
        public volatile String lastError;

        public ServerHealth(String serverName, ServerStatus status, int failures, int successes,
                            Instant lastCheckAt, Instant lastSuccessAt, Instant lastFailureAt, String lastError) {
            this.serverName = serverName;
            this.status = status;
            this.consecutiveFailures = new AtomicInteger(failures);
            this.consecutiveSuccesses = new AtomicInteger(successes);
            this.lastCheckAt = lastCheckAt;
            this.lastSuccessAt = lastSuccessAt;
            this.lastFailureAt = lastFailureAt;
            this.lastError = lastError;
        }
    }

    private final ObjectProvider<List<McpSyncClient>> clientsProvider;
    private final MeterRegistry meterRegistry;

    /** 连续 N 次失败后标记为 DOWN */
    private final int failureThreshold;
    /** DOWN 状态冷却期（毫秒）——超过这个时间后放行一次探测 */
    private final long cooldownMs;
    /** 定时探测间隔（毫秒） */
    private final long probeIntervalMs;

    /** serverName → ServerHealth */
    private final Map<String, ServerHealth> healthMap = new ConcurrentHashMap<>();

    /**
     * application.yml 里配置的 MCP server 名 baseline。
     * McpToolBridge 因为 npx 超时或 Spring AI initialized=false 导致没注册成功时，
     * 前端至少能看到这些 server 以 UNKNOWN 状态展示（诊断"有配置但没跑起来" vs "真没配置"）。
     */
    private final Set<String> configuredServers = ConcurrentHashMap.newKeySet();

    public McpHealthMonitor(
            ObjectProvider<List<McpSyncClient>> clientsProvider,
            MeterRegistry meterRegistry,
            @Value("${agent.mcp.health.failure-threshold:3}") int failureThreshold,
            @Value("${agent.mcp.health.cooldown-ms:300000}") long cooldownMs,
            @Value("${agent.mcp.health.probe-interval-ms:30000}") long probeIntervalMs) {
        this.clientsProvider = clientsProvider;
        this.meterRegistry = meterRegistry;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.probeIntervalMs = probeIntervalMs;
    }

    /**
     * 应用就绪后：
     * 1. 用 Spring Boot Binder 从 Environment 读 application.yml 里的 stdio connections map（key 就是 server 名）
     *    —— @Value 不能把 YAML 嵌套 Map 直接绑到构造函数参数（Spring 解析成空字符串，String→Map 转换失败）
     * 2. 把配置的 server 以 UNKNOWN 状态补进 healthMap（兜底：有配置但 McpToolBridge 还没 register 成功时，前端仍能看到）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        try {
            Map<String, Object> configured = Binder.get(env)
                    .bind("spring.ai.mcp.client.stdio.connections",
                            Bindable.mapOf(String.class, Object.class))
                    .orElseGet(java.util.Collections::emptyMap);
            if (!configured.isEmpty()) {
                configuredServers.addAll(configured.keySet());
                log.info("MCP 健康监控: 从配置加载了 {} 个 server 名 — {}", configuredServers.size(), configuredServers);
            }
        } catch (Exception e) {
            log.warn("MCP 健康监控: 无法从配置读取 stdio connections — {}", e.getMessage());
        }
        for (String name : configuredServers) {
            healthMap.computeIfAbsent(name, k -> {
                ServerHealth h = new ServerHealth(k, ServerStatus.UNKNOWN, 0, 0,
                        Instant.now(), null, null, "配置了但还没初始化");
                registerGauge(k, h);
                return h;
            });
        }
    }

    /**
     * McpToolBridge 注册 server 时调：把 server 纳入监控，初始状态 UNKNOWN。
     */
    public void registerServer(String serverName) {
        healthMap.computeIfAbsent(serverName, k -> {
            ServerHealth h = new ServerHealth(
                    serverName,
                    ServerStatus.UNKNOWN,
                    0, 0,
                    Instant.now(), null, null, null);
            registerGauge(serverName, h);
            log.info("MCP 健康监控: server [{}] 已注册", serverName);
            return h;
        });
    }

    /**
     * 业务调用 McpToolBridge.invoke() 之前调：查状态。
     *
     * @return null 表示可调用（UP 或 UNKNOWN）；非 null 表示熔断中、不可调用
     */
    public String checkBeforeInvoke(String serverName) {
        ServerHealth h = healthMap.get(serverName);
        if (h == null) {
            return null; // 没注册过的 server 放行（可能是监控初始化晚于业务调用）
        }
        if (h.status == ServerStatus.DOWN) {
            Instant now = Instant.now();
            long sinceDownMs = java.time.Duration.between(h.lastFailureAt, now).toMillis();
            if (sinceDownMs < cooldownMs) {
                // 还在冷却期，拒绝
                return String.format(
                        "MCP server [%s] 已熔断（连续失败 %d 次，%d 秒内不再尝试）。" +
                                "请在设置 → MCP Servers 中检查连接，或等待冷却期过后自动恢复。",
                        serverName, h.consecutiveFailures.get(), (cooldownMs - sinceDownMs) / 1000);
            } else {
                // 冷却期过了，放行一次探测（把状态改回 UNKNOWN，等这次调用的结果来决定 UP/DOWN）
                log.info("MCP 健康监控: server [{}] 冷却期已过，放行一次探测调用", serverName);
                h.status = ServerStatus.UNKNOWN;
            }
        }
        return null; // UP 或 UNKNOWN 都放行
    }

    /**
     * McpToolBridge.invoke() 调用成功后调：更新健康状态。
     */
    public void recordSuccess(String serverName) {
        ServerHealth h = healthMap.get(serverName);
        if (h == null) return;
        h.consecutiveFailures.set(0);
        h.consecutiveSuccesses.incrementAndGet();
        h.status = ServerStatus.UP;
        h.lastSuccessAt = Instant.now();
        h.lastError = null;
    }

    /**
     * McpToolBridge.invoke() 调用失败后调：更新健康状态。
     */
    public void recordFailure(String serverName, String error) {
        ServerHealth h = healthMap.get(serverName);
        if (h == null) return;
        int f = h.consecutiveFailures.incrementAndGet();
        h.consecutiveSuccesses.set(0);
        h.lastFailureAt = Instant.now();
        h.lastError = error;
        h.status = (f >= failureThreshold) ? ServerStatus.DOWN : ServerStatus.UP;
        if (f == failureThreshold) {
            log.warn("MCP 健康监控: server [{}] 连续失败 {} 次，标记为 DOWN。最后错误: {}",
                    serverName, f, error);
        }
    }

    /**
     * 定时探测：每 probeIntervalMs 秒 ping 所有已注册的 server。
     * 用 listTools() 而不是 ping()——ping() 只测管道通不通，listTools() 才能测出 server 进程
     * 是否真的在正常响应。
     */
    @Scheduled(fixedDelayString = "${agent.mcp.health.probe-interval-ms:30000}")
    public void probeAllServers() {
        List<McpSyncClient> clients = clientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) return;
        for (McpSyncClient client : clients) {
            String serverName = resolveServerName(client);
            ServerHealth h = healthMap.get(serverName);
            if (h == null) continue;
            h.lastCheckAt = Instant.now();
            try {
                if (!client.isInitialized()) continue; // 还没启动完成，跳过
                client.listTools(); // 轻量探测
                if (h.status != ServerStatus.UP) {
                    log.info("MCP 健康监控: server [{}] 探测成功，状态恢复为 UP", serverName);
                }
                recordSuccess(serverName);
            } catch (Exception e) {
                // 探测失败：只有 UP 状态才计入失败（DOWN 状态已经熔断了，不用重复计数）
                if (h.status == ServerStatus.UP) {
                    log.warn("MCP 健康监控: server [{}] 定时探测失败: {}", serverName, e.getMessage());
                    recordFailure(serverName, e.getMessage());
                }
            }
        }
    }

    /** 返回所有 server 的健康快照（给 status 接口用） */
    public Map<String, Map<String, Object>> snapshot() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ServerHealth> e : healthMap.entrySet()) {
            ServerHealth h = e.getValue();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("status", h.status.name());
            one.put("consecutiveFailures", h.consecutiveFailures.get());
            one.put("lastCheckAt", h.lastCheckAt);
            one.put("lastSuccessAt", h.lastSuccessAt);
            one.put("lastFailureAt", h.lastFailureAt);
            one.put("lastError", h.lastError);
            result.put(e.getKey(), one);
        }
        return result;
    }

    // ---------- 内部 ----------

    private String resolveServerName(McpSyncClient client) {
        String name = client.getClientInfo() == null ? null : client.getClientInfo().name();
        if (name == null) return "unknown";
        // Spring AI 拼接格式："agent-gateway-mcp - filesystem"
        if (name.contains(" - ")) {
            return name.substring(name.indexOf(" - ") + 3).trim();
        }
        int idx = name.indexOf('-');
        return idx > 0 ? name.substring(idx + 1).trim() : name;
    }

    private void registerGauge(String serverName, ServerHealth h) {
        // 让 Prometheus 能看到：1=UP, 0=DOWN, -1=UNKNOWN
        Gauge.builder("mcp.server.up", h, value -> switch (value.status) {
            case UP -> 1.0;
            case DOWN -> 0.0;
            case UNKNOWN -> -1.0;
        }).tag("name", serverName).description("MCP server 健康状态：1=UP 0=DOWN -1=UNKNOWN")
                .register(meterRegistry);
    }
}
