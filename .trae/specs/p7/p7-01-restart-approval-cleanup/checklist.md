# P7-01 验收检查点

## 实现
- [x] 重启清理覆盖 `WAITING_APPROVAL`，统一 `FAILED + PROCESS_RESTARTED` 并写 `STATE_CHANGED`。
- [x] 启动残留 `PENDING` 不再入内存：未过期 `REJECTED/SYSTEM`，过期 `REJECTED/TIMEOUT`。
- [x] 会话级「总是允许」恢复保持可用；无害化移除不再使用的 `toPending`。
- [x] `resolve()` 对残留审批返回 `false`，不会出现「批准成功但无人执行」。

## 测试
- [x] 定向：`AgentRunServiceTest` 9、`ToolApprovalRegistryTest` 15、`ToolApprovalRegistryPersistenceTest` 9，共 33 条通过。
- [x] 验收（设置 `DESKTOP_PG_RUNTIME`）：`RestartRecoveryAndMultiInstanceAcceptanceTest` 5 条通过。
- [x] 全量：357 条，0 失败、0 错误；桌面运行时就绪时跳过 4 条，未就绪时跳过 15 条。

## 文档
- [x] `STATUS.md` 两条已知边界更新（重启收口、MCP 冷却可配）。
- [x] `COMPLETED_WORK.md` 新增第 10 节，原「完成档案使用规则」顺延为第 11 节。
- [x] `UPDATE_PLAN.md` 第 0 节新增 P7 进度、Phase 4 剩余风险与「运行与审批状态」同步。
