-- V12：UserSetting 用户全局默认对话参数（第六十七批设置中心）
CREATE TABLE IF NOT EXISTS user_setting (
  id                  BIGSERIAL PRIMARY KEY,
  user_id             BIGINT NOT NULL UNIQUE,
  default_model       VARCHAR(64) DEFAULT 'deepseek-chat',
  default_reasoning   VARCHAR(16) DEFAULT 'STANDARD',
  default_permission  VARCHAR(16) DEFAULT 'EXECUTE',
  created_at          TIMESTAMP,
  updated_at          TIMESTAMP
);
