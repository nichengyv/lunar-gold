-- 项目级自定义指令（Instructions）：每个项目一段可选的背景/口吻/要求，
-- 回答前拼进 system prompt，让助手按项目场景调整行为。
-- TEXT 可空，旧项目/新项目都不需要回填。
ALTER TABLE project ADD COLUMN IF NOT EXISTS instruction TEXT;

COMMENT ON COLUMN project.instruction IS '项目级自定义指令（如背景、口吻、领域约束），可选';
