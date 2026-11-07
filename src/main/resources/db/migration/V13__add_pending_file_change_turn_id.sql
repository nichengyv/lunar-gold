-- V13：待审文件变更增加轮次 id，支持按轮次整体撤回（第六十八批 A3）
ALTER TABLE pending_file_change ADD COLUMN IF NOT EXISTS turn_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_pfc_turn ON pending_file_change (session_id, turn_id);
