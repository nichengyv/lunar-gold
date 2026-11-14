-- P5-10-2：审批决策来源与会话内历史查询。
-- USER / BATCH / TIMEOUT / SESSION_STOP / SYSTEM；V19 之前的旧记录保持 NULL，展示为来源未知。
ALTER TABLE tool_approval ADD COLUMN IF NOT EXISTS decision_source VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_tool_approval_session_history
    ON tool_approval (session_id, user_id, state, create_time);
