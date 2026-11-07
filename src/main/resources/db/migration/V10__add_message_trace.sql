-- 助手消息的执行轨迹：思考内容、工具调用、资料来源、文件改动（JSON 文本）
-- 用于切换会话/刷新后还原步骤卡片，而不是只剩正文
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS trace TEXT;
