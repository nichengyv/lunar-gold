-- V11：AgentRule 指令库表（第六十五批）
CREATE TABLE IF NOT EXISTS agent_rule (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  content     TEXT NOT NULL,
  scope       VARCHAR(16) NOT NULL,          -- GLOBAL / PROJECT / SESSION
  user_id     BIGINT,                         -- 非 builtin 的全局规则绑定用户
  project_id  BIGINT,                         -- PROJECT scope 绑定项目
  session_id  BIGINT,                         -- SESSION scope 绑定会话
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  builtin     BOOLEAN NOT NULL DEFAULT FALSE, -- 系统内置可关不可删
  created_at  TIMESTAMP,
  updated_at  TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ar_user_scope    ON agent_rule(user_id, scope);
CREATE INDEX IF NOT EXISTS idx_ar_project_scope  ON agent_rule(project_id, scope);
CREATE INDEX IF NOT EXISTS idx_ar_session_scope  ON agent_rule(session_id, scope);
CREATE INDEX IF NOT EXISTS idx_ar_scope_builtin  ON agent_rule(scope, builtin);
