# P7-01 任务清单

> 详情与验收标准见 [spec.md](spec.md)；验收结果见 [checklist.md](checklist.md)。

## 实现

- [x] T1 `AgentRunService.recoverAfterRestart()`：查询状态加入 `WAITING_APPROVAL`，统一 `FAILED + PROCESS_RESTARTED` 并写 `STATE_CHANGED`；更新注释。
- [x] T2 `ToolApprovalRegistry.restorePersistedApprovals()`：未过期 `PENDING` 落 `REJECTED/SYSTEM` 且不入内存 `pending`；已过期保持 `TIMEOUT`；保留会话级「总是允许」恢复；日志按类汇总。
- [x] T3 改写 `ToolApprovalRegistryPersistenceTest` 中「未过期审批恢复可批准」用例为新语义。
- [x] T4 扩展 `RestartRecoveryAndMultiInstanceAcceptanceTest`：`WAITING_APPROVAL` Run 断言；未过期审批改为系统拒绝；双实例用例改为「都落系统拒绝」。
- [x] T5 在 `AgentRunServiceTest` 新增 `WAITING_APPROVAL` 重启终止用例。

## 验证

- [x] T6 定向测试：`AgentRunServiceTest`、`ToolApprovalRegistryTest`、`ToolApprovalRegistryPersistenceTest` 共 33 条全绿。
- [x] T7 全量 `mvn -o test`：357 条，0 失败、0 错误；未设置桌面运行时跳过 15 条。
- [x] T8 设置 `DESKTOP_PG_RUNTIME` 复跑全量：357 条，0 失败、0 错误，跳过降至 4 条；重启验收 5 条全通过。

## 文档

- [x] T9 更新 `docs/STATUS.md` 两条「已知边界」。
- [x] T10 `docs/COMPLETED_WORK.md` 追加第 10 节 P7-01 记录。
- [x] T11 `docs/UPDATE_PLAN.md` 第 0 节 / Phase 4 剩余风险同步。
