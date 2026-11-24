-- R8a：任务边界进入上下文
-- stop_reason 记录 Run 被停止的原因（USER_STOP / CANCELLED），供新一轮判断"上一轮已作废"
ALTER TABLE agent_run ADD COLUMN stop_reason VARCHAR(32);

-- 被停止轮次的助手消息打标，回放历史时标注为"该轮已由用户中断"，而不是当成失败
ALTER TABLE chat_message ADD COLUMN cancelled BOOLEAN NOT NULL DEFAULT FALSE;
