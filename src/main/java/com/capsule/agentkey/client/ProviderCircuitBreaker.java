package com.capsule.agentkey.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型 Provider 级熔断器（P4-06）。
 *
 * <p>按 provider（当前即 {@code agent.spring-ai.base-url}）隔离：
 * 一个 Provider 连续失败到阈值后打开熔断，在窗口期内直接快速失败，
 * 不再把请求打到明显故障的上游；窗口期结束后放行一个探测请求，
 * 探测成功则恢复，失败则重新打开。
 *
 * <p>按 Provider 而不是按模型隔离，是因为同一 base-url 下的模型共享
 * 同一个上游服务与同一把密钥，上游故障时逐个模型重试只会放大压力。
 */
@Component
public class ProviderCircuitBreaker {

    private final int failureThreshold;
    private final long openMillis;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public ProviderCircuitBreaker(
            @Value("${agent.spring-ai.circuit.failure-threshold:5}") int failureThreshold,
            @Value("${agent.spring-ai.circuit.open-millis:30000}") long openMillis) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(0L, openMillis);
    }

    /** 是否允许放行一次请求；半开状态下只放行一个探测请求 */
    public boolean allowRequest(String provider) {
        State state = stateOf(provider);
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.openUntil == 0L) {
                return true;
            }
            if (now < state.openUntil) {
                return false;
            }
            if (state.probeInFlight) {
                return false;
            }
            state.probeInFlight = true;
            return true;
        }
    }

    /** 只读判断，不改变半开探测状态 */
    public boolean isOpen(String provider) {
        State state = stateOf(provider);
        synchronized (state) {
            return state.openUntil != 0L && System.currentTimeMillis() < state.openUntil;
        }
    }

    public void recordSuccess(String provider) {
        State state = stateOf(provider);
        synchronized (state) {
            state.failures = 0;
            state.openUntil = 0L;
            state.probeInFlight = false;
        }
    }

    public void recordFailure(String provider) {
        State state = stateOf(provider);
        synchronized (state) {
            state.probeInFlight = false;
            state.failures++;
            if (state.failures >= failureThreshold) {
                state.openUntil = System.currentTimeMillis() + openMillis;
            }
        }
    }

    private State stateOf(String provider) {
        return states.computeIfAbsent(provider == null ? "" : provider, k -> new State());
    }

    private static final class State {
        private int failures;
        private long openUntil;
        private boolean probeInFlight;
    }
}
