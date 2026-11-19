# P6-05b 审批体验指标按入口拆分 - 任务拆解

> 依据：[spec.md](./spec.md)。顺序：后端聚合 → 后端测试 → 前端类型与展示 → 前端测试 → 文档回填与回归。
> 状态：**已全部完成**（2026-11-17）。

## T1 后端分组聚合

- [x] `AgentExperienceMetricsService` 增加入口判定（`AUTO` → `CLIENT` → `WEB` → `UNKNOWN`），优先级见 spec。
- [x] `ApprovalMetrics` 增加 `List<ApprovalEntryMetrics> byEntry`；新增 record `ApprovalEntryMetrics(entry, clientId, totalCount, approvedCount, rejectedCount, approvalRate, averageDecisionMs)`。
- [x] 复用 `findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc`，内存分组；`AUTO` 的 `approvalRate` 恒为 `null`；分组按 `totalCount desc, entry asc, clientId asc` 排序。
- [x] 既有 `averageWaitMs` / `sampleCount` 公式不变；无样本时 `byEntry` 为空列表。

## T2 后端测试

- [x] `AgentExperienceMetricsServiceTest` 既有用例适配新 record（构造与断言）；空样本用例补 `byEntry` 为空数组断言。
- [x] 新增分组用例 `groupsApprovalMetricsByEntry`：Web 单条 + 批量合并；两个不同 `client_id` 分别成组；`TIMEOUT` 归 `AUTO` 且批准率为 `null`；`decision_source = NULL` 归 `UNKNOWN`。
- [x] 断言：`totalCount` 之和等于 `sampleCount`、批准率分母排除自动来源、平均耗时按组独立、排序稳定、空样本为空数组。

## T3 前端类型与展示

- [x] `frontend/src/types/api.ts`：`AgentExperienceMetrics.approval` 增加 `byEntry` 与 `AgentApprovalEntryMetrics` 类型。
- [x] `ExperienceDiagnosticsPanel.vue`：Agent 体验卡片内增加「审批入口」小表（入口 / 审批量 / 批准率 / 平均耗时），无数据时空态「暂无已决策审批」。
- [x] 入口文案：`WEB` → 工作台，`CLIENT` → `客户端 · <clientId>`，`AUTO` → 自动，`UNKNOWN` → 来源未知，未知值原样回退。

## T4 前端测试

- [x] `ExperienceDiagnosticsPanel.test.ts`：fixture 补 `byEntry`；新增「按入口拆分审批指标，自动来源不显示批准率」用例。
- [x] `e2e/diagnostics.spec.ts`、`e2e/fixtures/api.ts`：体验指标响应补 `byEntry`，e2e 断言「工作台」与「客户端 · vscode-abc12345」。

## T5 文档与回归

- [x] `docs/phases/phase6/plan.md`：新增 P6-05b 段落，P6-05 偏差 1 标注已补齐。
- [x] `.trae/specs/p6/p6-05-multi-entry-audit/checklist.md`：偏差 1 标注由 P6-05b 补齐。
- [x] 回归：`mvn -o test`（368 / 0 / 0 / 15）、前端 `npm test` 89 通过 + `npx vue-tsc --noEmit` 通过、CLI 71 / 扩展 43 通过。
