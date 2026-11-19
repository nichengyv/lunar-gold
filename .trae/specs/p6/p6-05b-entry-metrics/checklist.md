# P6-05b 审批体验指标按入口拆分 - 验收清单

> 状态：**已实施并通过单测、前端测试与全量回归**（2026-11-17）。
>
> 通过基线：`approval.byEntry` 按 `tool_approval.client_id` 与 `decision_source` 分组，自动来源不进入批准率分母，既有 `averageWaitMs` / `sampleCount` 口径不变。

## 批次边界

- [x] 未新增接口、迁移、索引、事件、配置项；证据：改动仅 `AgentExperienceMetricsService`、`types/api.ts`、`ExperienceDiagnosticsPanel.vue` 与测试。
- [x] 未改审批策略、scope、批量白名单、归属校验；证据：`ToolApprovalRegistry` 与 `AgentChatController` 未改动，完整套件通过。
- [x] 未按入口拆分 Run / token / 工具指标（非目标）。
- [x] 未回填历史数据；`decision_source = NULL` 归 `UNKNOWN`。

## 分组口径

- [x] `TIMEOUT` / `SESSION_STOP` / `SYSTEM` → `AUTO`，判定优先于 `client_id`；证据：`AUTO_SOURCES` 常量 + `entryOf` 首条分支。
- [x] `client_id != null` → `CLIENT`，按 `client_id` 分组；证据：`groupsApprovalMetricsByEntry` 中 `cli-abc1234567` 合并为 2 条、`vscode-abc12345` 独立成组。
- [x] `USER` / `BATCH` 且 `client_id == null` → `WEB`；证据：同一用例 Web 组 `totalCount = 3`。
- [x] `decision_source == null` → `UNKNOWN`；证据：同一用例 `UNKNOWN` 组批准率 1.0。
- [x] 分组 `totalCount` 之和等于 `approval.sampleCount`；证据：用例断言 `sum = 8 = sampleCount`。

## 指标公式

- [x] `approvalRate = approvedCount / (approvedCount + rejectedCount)`，分母为 0 或 `AUTO` 时为 `null`；证据：Web 2/3、CLI 0.5、AUTO `null`。
- [x] `averageDecisionMs` 按组独立计算，无有效样本为 `null`；证据：Web 2000 ms、CLI 1000 ms、AUTO 120000 ms。
- [x] 既有 `averageWaitMs`（含自动来源）与 `sampleCount` 取值不变；证据：用例断言 `16187.5 / 8`。
- [x] 排序为 `totalCount desc, entry asc, clientId asc`；证据：断言顺序 `WEB, CLIENT(cli), AUTO, CLIENT(vscode), UNKNOWN`。

## 接口与前端

- [x] `GET /api/agent/metrics/experience` 路径、鉴权、信封不变；仅响应新增 `approval.byEntry`。
- [x] 前端类型 `AgentExperienceMetrics.approval.byEntry` 与后端字段一致；`npx vue-tsc --noEmit` 通过。
- [x] 诊断页 Agent 体验卡片展示分组表；`CLIENT` 显示 `客户端 · <clientId>`；`AUTO` 批准率显示「—」；证据：新增组件用例与 e2e 断言。
- [x] 无数据时显示「暂无已决策审批」，不显示伪造的 0。

## 测试

- [x] 单测覆盖 `WEB` / 两个不同 `CLIENT` / `AUTO` / `UNKNOWN` 与空样本；证据：`groupsApprovalMetricsByEntry`、`returnsNullForEmptySamples`。
- [x] 单测断言批准率分母排除自动来源、分组求和等于 `sampleCount`。
- [x] 前端单测断言分组行渲染（客户端名、100.0%、自动行「—」与 120,000 ms）。
- [x] e2e fixture 与 `diagnostics.spec.ts` 的响应补 `byEntry`，诊断用例断言「工作台」与「客户端 · vscode-abc12345」。

## 回归与文档

- [x] 后端 `mvn -o test`：**368 tests / 0 failures / 0 errors / 15 skipped**。
- [x] 前端 `npm test`：**89 passed / 12 files**；`npx vue-tsc --noEmit` 通过。
- [x] CLI `npm test`：71 passed；扩展 `npm test`：43 passed（零改动验证）。
- [x] `docs/phases/phase6/plan.md` 新增 P6-05b 段落，P6-05 偏差 1 标注已补齐。

## 偏差记录

| 偏差 | 影响 | 原因 | 后续 |
|---|---|---|---|
| 未做真机验证（Web / CLI / VS Code 各批一次后看分组表） | 分组数据来自单测与 fixture，缺少真机截图 | 需要运行实例、模型 Key 与可触发审批的工具调用 | 与 P6-05 偏差 2 一并做真机复核 |
| 客户端只展示 `client_id`，不展示名称 | 撤销后难以对应到人类可读名称 | 避免额外 join `api_client` | 若需要名称，另立批次 |

## 不通过判定

- 用 `agent_run.request_json.clientId` 或其他自报字段推断审批入口。
- 自动拒绝被计入 `WEB` / `CLIENT` 的批准率分母。
- 历史 `NULL` 来源被推断为 `WEB`。
- 为凑分组而新增数据库列、索引或迁移。
- 改变既有 `averageWaitMs` / `sampleCount` 的取值口径。
