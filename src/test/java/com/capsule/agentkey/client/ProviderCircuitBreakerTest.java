package com.capsule.agentkey.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P4-06：Provider 级熔断器的状态流转测试，不涉及网络。 */
class ProviderCircuitBreakerTest {

    @Test
    void 连续失败达到阈值后熔断并在窗口期拒绝请求() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(2, 60_000L);

        assertTrue(breaker.allowRequest("provider-a"));
        breaker.recordFailure("provider-a");
        assertFalse(breaker.isOpen("provider-a"), "一次失败不应熔断");

        breaker.recordFailure("provider-a");
        assertTrue(breaker.isOpen("provider-a"));
        assertFalse(breaker.allowRequest("provider-a"));
    }

    @Test
    void 不同Provider互不影响() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(1, 60_000L);

        breaker.recordFailure("provider-a");
        assertTrue(breaker.isOpen("provider-a"));
        assertFalse(breaker.isOpen("provider-b"));
        assertTrue(breaker.allowRequest("provider-b"));
    }

    @Test
    void 成功一次后失败计数清零() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(2, 60_000L);

        breaker.recordFailure("p");
        breaker.recordSuccess("p");
        breaker.recordFailure("p");

        assertFalse(breaker.isOpen("p"), "中间成功过就不算连续失败");
    }

    @Test
    void 窗口期结束后只放行一个探测请求() throws InterruptedException {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(1, 40L);
        breaker.recordFailure("p");
        assertFalse(breaker.allowRequest("p"));

        Thread.sleep(80L);

        assertTrue(breaker.allowRequest("p"), "窗口结束后应放行探测");
        assertFalse(breaker.allowRequest("p"), "半开状态只允许一个探测");

        breaker.recordSuccess("p");
        assertTrue(breaker.allowRequest("p"));
        assertFalse(breaker.isOpen("p"));
    }

    @Test
    void 探测失败会重新打开熔断() throws InterruptedException {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(1, 40L);
        breaker.recordFailure("p");
        Thread.sleep(80L);

        assertTrue(breaker.allowRequest("p"));
        breaker.recordFailure("p");

        assertTrue(breaker.isOpen("p"));
        assertFalse(breaker.allowRequest("p"));
    }
}
