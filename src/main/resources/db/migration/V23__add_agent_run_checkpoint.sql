-- ============================================================
-- V23__add_agent_run_checkpoint.sql
--
-- R7b（P8-01）：Run 级断点续跑。
-- 说明：
--   1) checkpoint_json 存最近一个「工具批已落地、下一轮模型调用前」的完整模型消息快照，
--      以及轮次、预算与停滞相关计数；只保留最新一份，不追加历史副本。
--   2) 事件流仍追加轻量 CHECKPOINT 事件（round / 消息条数 / 字节数），
--      作为断点审计与 seq 边界；checkpoint_json 只是可重建的投影，不是新的事实源。
--   3) 重启恢复仍把残局标为 FAILED / PROCESS_RESTARTED；只有带 checkpoint 的 Run
--      才允许用户显式调用 resume 接口续跑。
--   4) 只加列，不改历史数据，不触碰 V1~V22。
-- ============================================================

ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS checkpoint_json TEXT;
