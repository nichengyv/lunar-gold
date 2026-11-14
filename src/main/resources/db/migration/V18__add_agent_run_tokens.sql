-- P4-07：上下文与成本——按 run 累计 token 用量
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS prompt_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS completion_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS total_tokens BIGINT NOT NULL DEFAULT 0;

-- 会话维度的用量汇总查询
CREATE INDEX IF NOT EXISTS idx_agent_run_session_user
    ON agent_run (session_id, user_id);
