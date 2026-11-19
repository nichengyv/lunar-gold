# P6-05 多入口一致性与审计 - 验收清单

> 状态：**审批来源与审批入口审计已实施并通过单测 / 接口级 / 全量回归**（2026-11-17）。未勾选项见文末「偏差记录」。
>
> 通过基线：同一用户的同一条审批，Web 入口落 `USER` + `client_id = NULL`，CLI / VS Code 入口落 `CLIENT` + 对应 `client_id`；批量入口一律 `BATCH` 并以 `client_id` 区分；自动来源不带入口。

## 批次边界

- [x] 只新增 `decision_source = CLIENT` 与 `tool_approval.client_id`，未改动审批策略、scope、批量白名单；证据：审批归属校验与 `isBatchEligible` 未改动，完整套件通过。
- [x] 未新增公开端点；`PublicApiContractTest` 在完整套件中通过，路径与 scope 未变。
- [x] 未新增事件类型；`approval_resolved` 的 `clientId` 仍由 `AgentRunService` 从 Run 的 `requestJson` 读取，语义不变。
- [x] CLI 与 VS Code 零代码改动；证据：`npm test` 分别为 71 / 43 条通过（仅验证未回归）。
- [x] 未回填历史记录；`V21` 只加可空列，`create()` 与自动来源均不写 `client_id`。

## 来源判定

- [x] 单条审批且请求带客户端身份 → `DecisionSource.CLIENT`；证据：`ToolApprovalRegistryTest.客户端令牌审批记录客户端来源`（批准与拒绝各一条）。
- [x] 单条审批且为 Web 本地令牌 → `DecisionSource.USER`；证据：`ToolApprovalRegistryTest` 既有本人审批用例 + `HttpCrossTenantAcceptanceTest.审批来源按入口记录为USER或CLIENT`。
- [x] 批量审批 → `DecisionSource.BATCH`，与入口无关；证据：`ToolApprovalRegistryPersistenceTest.批量审批保留批量来源并记录客户端身份`。
- [x] 来源只取自 `CurrentClient.clientIdOrNull()`；证据：`AgentChatController` 两个审批端点均以该值传参，未读请求体或 Header。
- [x] 审批的 `userId` / `sessionId` 归属校验不变；证据：`CrossTenantAccessTest` 用客户端身份提交他人审批仍被拒，`ToolApprovalRegistryTest` 归属用例通过。

## 落库

- [x] `V21__add_tool_approval_client_id.sql` 只加可空 `client_id VARCHAR(32)`；证据：迁移文件；`FlywayV18MigrationAcceptanceTest` 期望更新为 V21 / 21 条并断言列（真实 PostgreSQL 未执行，见偏差 1）。
- [x] `ToolApproval.clientId` 与迁移列一致（`length = 32`）。
- [x] `create()` 写 `PENDING` 时 `client_id` 为 `NULL`；证据：实体字段无赋值，`客户端令牌审批写入口并记录客户端来源` 中决策前为 `NULL`。
- [x] 单条客户端审批写入审批请求的 `client_id`；证据：`客户端令牌审批写入口并记录客户端来源` 断言 `CLIENT` 与 `cli-abc1234567`。
- [x] 批量审批为每条记录写入 `client_id`；证据：`批量审批保留批量来源并记录客户端身份` 两条均断言 `vscode-abc12345`。
- [x] `TIMEOUT` / `SESSION_STOP` / `SYSTEM` / 重启过期路径 `client_id` 为 `NULL`；证据：`等待超时写审批表并记录超时来源`、`ToolApprovalRegistryTest.显式停止标记按会话停止记录来源`，调用点显式传 `null`。
- [x] `decision_source` / `decision_time` / `remember_session` 既有写入行为不变；证据：上述用例与既有审批用例全部通过。

## 接口与前端

- [x] `POST /api/agent/approvals/{approvalId}` 请求体、响应、scope 不变；证据：`HttpCrossTenantAcceptanceTest` 既有审批用例通过。
- [x] `POST /api/agent/approvals/batch` 请求体、响应、scope 不变；证据：同一套件批量用例通过。
- [x] `GET /api/agent/sessions/{sessionId}/approvals` 新增可空 `clientId`，其余字段不变且仍不返回 `arguments`；证据：`ApprovalHistoryItem` 仅新增字段，`PublicApiContractTest` 通过。
- [x] 前端 `ApprovalDecisionSource` 含 `'CLIENT'`，`approvalSourceLabel` 返回「客户端」；证据：`types/chat.ts`、`WorkbenchDock.vue`。
- [x] 未知 / `null` 来源仍显示「来源未知」；证据：`default` 分支未改动，前端 88 条测试通过。

## 测试

- [x] 单测：Web 单条 → `USER` + `null`；证据：`ToolApprovalRegistryPersistenceTest.断流期间拒绝写审批表并返回本人来源` 断言 `USER` 与 `null`。
- [x] 单测：客户端单条批准与拒绝 → `CLIENT` + `client_id`；证据：`客户端令牌审批写入口并记录客户端来源`、`ToolApprovalRegistryTest.客户端令牌审批记录客户端来源`。
- [x] 单测：批量（有 / 无 `clientId`）→ `BATCH` + 对应值；证据：新增批量用例（带 `client_id`）与既有批量用例（无 `clientId`）。
- [x] 单测：超时、会话停止、系统兜底 → 来源不变、`client_id = null`；证据：`等待超时写审批表并记录超时来源`、`显式停止标记按会话停止记录来源`。
- [x] 接口测试：两种身份分别批准同一条审批；证据：`HttpCrossTenantAcceptanceTest.审批来源按入口记录为USER或CLIENT`（JWT 链路 `USER`，`ClientTokenFilter` 链路 `CLIENT`）。
- [x] `FlywayV18MigrationAcceptanceTest` 已在真实 PostgreSQL 执行；证据：设置 `DESKTOP_PG_RUNTIME` 后嵌入式 PostgreSQL 16.11 启动，`Successfully applied 21 migrations ... now at version v21`，并断言 `tool_approval.client_id` 可空。

## 回归

- [x] 后端 `mvn -o test`：**367 tests / 0 failures / 0 errors / 15 skipped**；证据：surefire 汇总，新增 4 条用例均已执行（XML 中可见用例名）。
- [x] 前端 `npm test`：**88 passed / 12 files**；`npx vue-tsc --noEmit` 通过。
- [x] CLI `npm test`：**71 passed**；扩展 `npm test`：**43 passed**。
- [x] `docs/phases/phase6/plan.md` 标注 P6-05 交付与偏差；`docs/UPDATE_PLAN.md` 迁移表补 V20（api_client）/ V21。

## 不通过判定

出现以下任一情况，本批次不得标记完成：

- 用 `agent_run.request_json.clientId` 冒充审批入口。
- 允许客户端在请求体里自报来源或 `client_id`。
- 为了让测试通过而放宽归属校验或批量白名单。
- 改了审批端点路径、scope 或事件类型集合。
- 历史记录被猜测式回填，导致来源失真。

## 偏差记录

| 偏差 | 影响 | 原因 | 后续 |
|---|---|---|---|
| 1. 体验指标未按入口维度拆分 | ~~只能从审批表 `client_id` 自行聚合~~ 已消除 | 需要先定义按 `client_id` 的指标口径，且会改 P5-11-2 响应结构 | 已由 [P6-05b](../p6-05b-entry-metrics/spec.md) 补齐：`approval.byEntry` 按入口输出审批量 / 批准率 / 平均决策耗时 |
| 2. ~~Web / CLI / VS Code 未做真机复核~~ **已消除**（2026-11-17） | 已由真机证据替代单测推断 | 三入口各跑一次真实的需审批工具调用 | Web：工作台批准后 `approval.byEntry` 出现 `WEB`（7 条、85.7%）；CLI：`scripts/cli-e2e.ps1` 落 `CLIENT` + 客户端 id；VS Code：扩展内批准后出现 `CLIENT / FeBIz_31mXyW`（1 条、100.0%、8,600 ms） |
| 3. 历史记录不回填 `client_id` | V21 之前的审批无法判定入口 | 旧数据没有可信的入口信息，猜测会失真 | 按时间边界区分统计口径 |
