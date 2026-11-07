-- 长上下文滚动摘要：会话历史超过预算时，把较早的对话压缩成一段摘要持久化，
-- 后续每轮把摘要注入 system prompt，既保住上下文连贯性，又不把老消息全部塞进模型。
-- context_summary：压缩后的要点摘要（纯文本，可空）
-- summary_until_id：已压缩到的最后一条 chat_message.id，作为增量压缩游标，避免重复压缩同一段历史
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS context_summary TEXT;
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS summary_until_id BIGINT;

COMMENT ON COLUMN chat_session.context_summary IS '较早对话的滚动压缩摘要，注入 system prompt';
COMMENT ON COLUMN chat_session.summary_until_id IS '已压缩到的最后一条 chat_message.id（增量游标）';
