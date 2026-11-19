-- P6-05：记录审批入口的客户端身份。
-- 仅当审批请求由客户端令牌（CLI / VS Code）发起时写入；Web 本地令牌与自动来源保持 NULL。
-- 与 agent_run.request_json.clientId（发起入口）语义不同，互不覆盖；V21 之前的旧记录不回填。
ALTER TABLE tool_approval ADD COLUMN IF NOT EXISTS client_id VARCHAR(32);
