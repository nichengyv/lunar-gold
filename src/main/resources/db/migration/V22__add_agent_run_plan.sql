-- ============================================================
-- V22__add_agent_run_plan.sql
--
-- R1：把任务计划从进程内 ThreadLocal 提升为 Run 的持久化快照。
-- 说明：
--   1) plan_json 存 List<PlanStep> 的 JSON 数组（title + status）。
--   2) update_plan / finalizePlan 更新内存快照时同步写这一列，并追加
--      PLAN_UPDATED 事件；断线重放与续跑都以这里为准。
--   3) 只加列，不改历史数据，不触碰 V1~V21。
-- ============================================================

ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS plan_json TEXT;
