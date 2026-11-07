-- V14：补建 pending_file_change 表
-- 历史：V13 是 ALTER TABLE + 加 turn_id 列，但表本身从未在 Flyway 里建过（dev 靠 ddl-auto=update 自动建的）
-- 影响：prod 全新部署时 V13 会因表不存在而炸
-- 策略：CREATE TABLE IF NOT EXISTS，dev 环境已经有表就跳过，prod 全新部署时在 V13 之后自动补上
--   注：如果 prod 跑 V13（ALTER TABLE）时因为表不存在先炸了，需要先手动执行本脚本的 CREATE TABLE 段，再 flyway migrate 继续

CREATE TABLE IF NOT EXISTS pending_file_change (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    session_id      BIGINT,
    turn_id         VARCHAR(64),
    user_id         BIGINT NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    action          VARCHAR(20) NOT NULL,           -- WRITE / EDIT / DELETE
    source          VARCHAR(60) NOT NULL,           -- 哪个工具产生的
    old_content     TEXT,
    new_content     TEXT,
    diff_text       TEXT,
    additions       INTEGER,
    deletions       INTEGER,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / APPROVED / REJECTED
    create_time     TIMESTAMP,
    update_time     TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pfc_project ON pending_file_change (project_id);
CREATE INDEX IF NOT EXISTS idx_pfc_session ON pending_file_change (session_id);
CREATE INDEX IF NOT EXISTS idx_pfc_turn ON pending_file_change (session_id, turn_id);
CREATE INDEX IF NOT EXISTS idx_pfc_status ON pending_file_change (status);
