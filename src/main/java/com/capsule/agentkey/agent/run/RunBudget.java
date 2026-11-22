package com.capsule.agentkey.agent.run;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 父子 Run 共享的预算（R7c）。
 *
 * <p>子智能体不能通过新建 Run 重置父 Run 的 token / 时间预算：同一次委派里，
 * 父与所有子 Run 累计到同一个实例。各 Run 仍然分别记录自己的 usage，
 * 便于审计与父 Run 收尾时的用量汇总。
 */
public final class RunBudget {

    private final long maxTokens;
    private final long maxSeconds;
    private final long startedAtMillis = System.currentTimeMillis();
    private final AtomicLong usedTokens = new AtomicLong();

    /**
     * @param maxTokens  总 token 预算；<=0 表示回退到调用方的本地限制
     * @param maxSeconds 总时长预算（秒）；<=0 表示不限时
     */
    public RunBudget(long maxTokens, long maxSeconds) {
        this.maxTokens = maxTokens;
        this.maxSeconds = maxSeconds;
    }

    public void addTokens(long tokens) {
        if (tokens > 0) {
            usedTokens.addAndGet(tokens);
        }
    }

    public long usedTokens() {
        return usedTokens.get();
    }

    /**
     * 剩余 token 预算。
     *
     * <p>本实例没有总预算时回退到 {@code fallbackMax}；两者都不限制时返回 0
     * （{@code StreamPublisher} 把 <=0 视为不限）。
     */
    public long remainingTokens(long fallbackMax) {
        long max = maxTokens > 0 ? maxTokens : fallbackMax;
        if (max <= 0) {
            return 0;
        }
        return Math.max(1, max - usedTokens.get());
    }

    public boolean timeExceeded() {
        return maxSeconds > 0
                && System.currentTimeMillis() - startedAtMillis >= maxSeconds * 1000L;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAtMillis;
    }
}
