-- ============================================================
-- V24__add_agent_run_subtask.sql
--
-- R7c（P8-03 / L7）：串行子智能体。
-- 说明：
--   1) 子 Run 通过 parent_run_id 指向父 Run；delegation_id 是父 Run 内稳定的
--      子任务标识，与 parent_run_id 一起构成唯一约束，保证重试/续跑不会重复建 Run。
--   2) 顶层历史、最近会话 Run、运行中列表与用量查询都只统计 parent_run_id IS NULL；
--      子 Run 的用量在父 Run 收尾时按 delegation 汇总，避免双计。
--   3) 只加列与索引，不改历史数据，不触碰 V1~V23。
-- ============================================================

ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS parent_run_id BIGINT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS delegation_id VARCHAR(80);

CREATE INDEX IF NOT EXISTS idx_agent_run_parent ON agent_run (parent_run_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_run_subtask
    ON agent_run (parent_run_id, delegation_id);
