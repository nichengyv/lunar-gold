package com.capsule.agentkey.exception;

/**
 * P4-07：单次运行消耗的 token 超过预算上限。
 *
 * <p>抛出后由 {@code AgentChatController} 映射为
 * {@code agent_run.error_code = TOKEN_BUDGET_EXCEEDED}，
 * 与模型自身故障区分开，便于前端提示用户「调高预算或精简任务」。
 */
public class TokenBudgetExceededException extends RuntimeException {

    private final long usedTokens;
    private final long limitTokens;

    public TokenBudgetExceededException(long usedTokens, long limitTokens) {
        super("本轮已消耗 " + usedTokens + " tokens，超过预算上限 " + limitTokens
                + "。请精简任务或调高 agent.budget.max-tokens-per-run。");
        this.usedTokens = usedTokens;
        this.limitTokens = limitTokens;
    }

    public long getUsedTokens() {
        return usedTokens;
    }

    public long getLimitTokens() {
        return limitTokens;
    }
}
