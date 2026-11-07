-- 会话级运行参数：模型 / 推理档位 / 工作权限。
-- 存会话上而不是请求上，用户切回旧会话时仍是当时选择的配置。
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS model VARCHAR(60);
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS reasoning VARCHAR(20);
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS permission VARCHAR(20);

COMMENT ON COLUMN chat_session.model IS '会话使用的对话模型（须在 deepseek.allowed-models 内）';
COMMENT ON COLUMN chat_session.reasoning IS '推理档位：FAST / STANDARD / DEEP';
COMMENT ON COLUMN chat_session.permission IS '工作权限：READ_ONLY / WRITE / EXECUTE';

-- 项目级默认工作权限：新会话未显式指定时继承；为空按 EXECUTE 处理
ALTER TABLE project ADD COLUMN IF NOT EXISTS default_permission VARCHAR(20);

COMMENT ON COLUMN project.default_permission IS '项目默认工作权限：READ_ONLY / WRITE / EXECUTE，为空按 EXECUTE';
