# P7-01 重启后未完成 Run 与未决审批收口 - Product Requirements Document

> 状态：**待批准**。范围已与用户确认（2026-11-19：稳定性边界收口，先做本项）。

## Overview

- **Summary**：后端启动时把 `WAITING_APPROVAL` 的 Run 一并终止为 `FAILED`（`PROCESS_RESTARTED`），并把重启后残留的 `PENDING` 审批统一落为 `REJECTED`（未过期 → `SYSTEM`，已过期 → 沿用 `TIMEOUT`），不再把它们恢复成「可批准但无人等待」的内存记录。
- **Purpose**：当前 `recoverAfterRestart` 只处理 `QUEUED/RUNNING`；`WAITING_APPROVAL` 的 Run 重启后仍被 `/api/agent/runs/running` 当作进行中返回，且 `restorePersistedApprovals` 会把未过期的 `PENDING` 装回内存，用户点「批准」能成功落库却没有任何线程继续执行——Run 永久卡在等待审批，这是可直接复现的状态陷阱。
- **Target Users**：桌面端与 Web 端在审批卡等待期间重启后端的使用者。

## 已核实的事实（避免重复调查）

| 事实 | 位置 |
|---|---|
| 启动恢复只覆盖 `QUEUED/RUNNING` → `FAILED` + `PROCESS_RESTARTED` + `STATE_CHANGED` 事件 | `service/AgentRunService.java:77-92` |
| 状态机允许 `WAITING_APPROVAL → FAILED` | `service/AgentRunService.java:59-62` |
| 未过期 `PENDING` 会被装回内存 `pending`，`resolve()` 直接完成一个无人等待的 future | `service/ToolApprovalRegistry.java:83-104`、`:177-211` |
| 已过期 `PENDING` 启动时落 `REJECTED/TIMEOUT` | `service/ToolApprovalRegistry.java:88-93` |
| `/runs/running` 查询 `RUNNING + WAITING_APPROVAL` | `controller/AgentChatController.java:380-394`、`service/AgentRunService.java:114-118` |
| 前端 `pendingApprovals` 只来自活动 SSE 事件，不从库中拉 PENDING | `frontend/src/stores/chat.ts`、`frontend/src/components/WorkbenchDock.vue:503-506` |
| MCP 熔断冷却**已可配置**（`MCP_COOLDOWN_MS`，默认 300000） | `application.yml:201-208`、`mcp/McpHealthMonitor.java:100-111` |
| `DecisionSource.SYSTEM` 前端已有文案「系统中止」 | `frontend/src/components/WorkbenchDock.vue:509-516` |

## Goals

1. 重启后不存在「进行中但已无执行线程」的 Run：`QUEUED`、`RUNNING`、`WAITING_APPROVAL` 全部进入终态。
2. 重启后不存在「可批准但无效果」的审批：残留 `PENDING` 一律落终态，决策来源可区分（未过期 `SYSTEM`，已过期 `TIMEOUT`）。
3. 行为对 Web / CLI / VS Code 一致，不新增接口、事件类型或数据库迁移。
4. 修正与新语义冲突的既有测试，并补充 `WAITING_APPROVAL` 用例。
5. 同步修正文档中两条已过时/缺失的边界说明。

## Non-Goals

- 不做重启后续跑（不恢复 ReAct 线程、不重放模型调用、不重新发起工具）。
- 不做多实例审批互斥或跨实例唤醒。
- 不改前端交互、不改 `/runs/running` 协议、不改审批接口。
- 不在本批次做 `aggregateStats` 性能优化与桌面 SSE E2E 补测（后续项）。

## 设计决策

1. **`WAITING_APPROVAL` 与 `RUNNING` 同等对待**：进程重启后执行线程已消失，等待审批的 Run 与执行中的 Run 一样不可恢复，统一 `FAILED` + `errorCode=PROCESS_RESTARTED`、`errorMessage=应用重启，原执行线程已终止`，并写 `STATE_CHANGED` 事件（payload 带 `targetState` 与 `errorCode`），保证按 `sinceSeq` 重放的客户端能看到终态。
2. **不恢复孤儿审批**：启动时 `PENDING` 的审批按定义都是孤儿（新进程的 `pending` map 为空，没有线程在 `await`）。未过期 → `REJECTED` + `DecisionSource.SYSTEM`；已过期 → 沿用 `REJECTED` + `DecisionSource.TIMEOUT`。两类都不写入内存 `pending`，因此后续 `resolve()` 返回 `false`（记录已失效），不会产生假成功。
3. **保留「总是允许」恢复**：`APPROVED + rememberSession=true` 的会话级白名单逻辑不变。
4. **不改事件词汇与状态机**：复用现有 `STATE_CHANGED` 与 `DecisionSource`，不新增枚举值。
5. **前端零改动**：`runs/running` 不再返回这些 Run 后，会话状态点自然显示最近一次终态；`WorkbenchDock` 的审批历史对 `SYSTEM` 已有文案。

## Functional Requirements

### FR-1 启动清理未完成 Run

- `AgentRunService.recoverAfterRestart()` 查询状态集合改为 `QUEUED, RUNNING, WAITING_APPROVAL`。
- 每条记录：`state=FAILED`、`errorCode=PROCESS_RESTARTED`、`errorMessage=应用重启，原执行线程已终止`、`endTime=now`，并 `appendInternal(runId, STATE_CHANGED, {targetState: FAILED, errorCode: PROCESS_RESTARTED})`。
- 日志汇总数量（沿用现有 `log.warn`）。
- 更新方法注释，说明三种状态都不可恢复。

### FR-2 启动清理未决审批

- `ToolApprovalRegistry.restorePersistedApprovals()`：
  - 遍历 `PENDING`：已过期 → `markDecision(saved, "REJECTED", false, TIMEOUT, null)`（保持现状）；未过期 → `markDecision(saved, "REJECTED", false, SYSTEM, null)`，不放入 `pending`。
  - 日志分别汇总两类数量，并说明「进程重启，无人等待审批」。
  - `PENDING` 循环结束后不再新增 `pending` 条目；保留 `APPROVED + rememberSession` 白名单恢复。

### FR-3 测试

- `ToolApprovalRegistryPersistenceTest`：
  - 改写 `启动恢复未过期审批且保留原截止时间`：断言启动后记录为 `REJECTED`、`decisionSource=SYSTEM`、`decisionTime` 非空，且 `resolve(...)` 返回 `false`。
  - `启动时过期审批直接拒绝并记录超时来源` 保持并通过。
- `RestartRecoveryAndMultiInstanceAcceptanceTest`（需 `DESKTOP_PG_RUNTIME`）：
  - `重启后遗留RUNNING与QUEUED变FAILED并写PROCESS_RESTARTED事件` 增加 `WAITING_APPROVAL` 种子与断言。
  - `未过期审批重启可恢复并落库决策` 改为 `未过期审批重启即被系统拒绝`：断言 `REJECTED`、`SYSTEM`，且 `resolve` 返回 `false`。
- `AgentRunServiceTest`：新增一个纯 mock 用例，覆盖 `WAITING_APPROVAL → FAILED` 与 `STATE_CHANGED` 事件写入（不依赖内嵌 PG）。

### FR-4 文档

- `docs/STATUS.md`「已知边界」：
  - 「审批状态存进程内存 | 重启丢正在等的卡 | 120s 超时自动拒绝兜底」改为「重启时未完成 Run 统一标 FAILED（PROCESS_RESTARTED），残留 PENDING 审批标 REJECTED（未过期 SYSTEM / 过期 TIMEOUT）」。
  - 「MCP 熔断冷却 5 分钟硬编码」改为「冷却默认 300s，可用 `MCP_COOLDOWN_MS` 调整；熔断期间不调用该 server」。
- `docs/COMPLETED_WORK.md` 追加 P7-01 小节（实现、测试结论、边界）。
- `docs/UPDATE_PLAN.md` 第 0 节与 Phase 4 剩余风险中标注「桌面重启恢复」中审批残局已收口，剩余仅跨实例互斥。

## 影响面与兼容性

| 文件 | 改动性质 |
|---|---|
| `src/main/java/com/capsule/agentkey/service/AgentRunService.java` | 状态集合 + 注释 |
| `src/main/java/com/capsule/agentkey/service/ToolApprovalRegistry.java` | 启动恢复分支 |
| `src/test/java/com/capsule/agentkey/service/ToolApprovalRegistryPersistenceTest.java` | 改写 1 个用例 |
| `src/test/java/com/capsule/agentkey/acceptance/RestartRecoveryAndMultiInstanceAcceptanceTest.java` | 扩展 1 个、改写 1 个用例 |
| `src/test/java/com/capsule/agentkey/service/AgentRunServiceTest.java` | 新增 1 个用例 |
| `docs/STATUS.md`、`docs/COMPLETED_WORK.md`、`docs/UPDATE_PLAN.md` | 文档同步 |

- 无接口、无数据库 schema、无事件类型变化；旧库数据在启动时被一次性收口。
- 唯一可见行为变化：重启后不再出现「等待审批」的僵尸 Run，也不再接受对残留审批的批准。

## 验证计划

1. `mvn -o -q "-Dtest=AgentRunServiceTest,ToolApprovalRegistryTest,ToolApprovalRegistryPersistenceTest" test`
2. `mvn -o -q test` 全量，要求 0 失败、0 错误；桌面 PG 验收测试在未设置 `DESKTOP_PG_RUNTIME` 时跳过需如实记录。
3. 人工核对：设置 `DESKTOP_PG_RUNTIME` 时运行 `RestartRecoveryAndMultiInstanceAcceptanceTest`。
4. 若改动触及前端无（本批次不改前端），不触发 E2E。
