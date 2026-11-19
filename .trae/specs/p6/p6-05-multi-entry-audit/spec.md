# P6-05 多入口一致性与审计 - Product Requirements Document

> 状态：**草稿，待确认**（2026-11-17）。范围只覆盖「审批来源可区分 + 审批入口可追溯」；确认后再补 `tasks.md` / `checklist.md`，之后才改代码。

## Overview

- **Summary**：把「审批是谁从哪个入口做的」变成服务端判定并落库的事实。`tool_approval.decision_source` 增加 `CLIENT`（客户端令牌入口，即 CLI / VS Code 扩展），新增 `tool_approval.client_id` 记录审批请求自身的客户端身份；Web 本地令牌审批仍为 `USER`，批量审批仍为 `BATCH`，自动来源不变。
- **Purpose**：现在同一用户从 Web、CLI、VS Code 批准同一条审批，落库结果完全一样（`USER` 或 `BATCH`），事后无法回答「这次放行是哪个入口、哪个客户端做的」。P6-01 只记录了**发起 Run** 的 `clientId`，没有记录**审批**入口；两者语义不同，不能互相替代。
- **Target Users**：需要在 Web 工作台追溯「CLI / 插件是否代我批准了高危操作」的本地用户；排查审批越权或误放行的维护者。

## Goals

1. **入口可区分**：单条审批能区分 Web（本地令牌）与客户端令牌（CLI / VS Code），不需要客户端改代码或新增请求字段。
2. **审批入口可追溯**：审批表记录审批请求自身的 `client_id`；历史查询接口可读到该字段。
3. **来源判定只在服务端**：来源由当前请求的认证身份推断，不接受客户端自报；任何入口都不能伪造来源。
4. **语义不混用**：`agent_run.request_json.clientId`（发起入口）与 `tool_approval.client_id`（审批入口）是两件事，前者不改、不覆盖。
5. **零授权变化**：审批的 scope、归属校验、批量白名单、超时与停止语义全部不变；本批次只增加记录，不改变谁能批准什么。

## Non-Goals

- **不做历史数据回填**：V21 之前的审批记录无法可靠推断审批入口，`client_id` 保持 `NULL`，`decision_source` 保持原值。
- **不新增事件类型或事件字段**：`approval_resolved` 现有 `clientId` 仍表示**发起入口**（P6-01 已实现），不再解释成审批入口；审批入口以审批表为权威。
- **不按入口维度拆分体验指标**：`plan.md` 中的这一项需要先定义按 `client_id` 聚合的指标口径，会改动 P5-11-2 的响应结构；本批次只把 `tool_approval.client_id` 落成可用维度，指标维度另立批次。
- **不改审批策略**：不新增自动批准、不调整批量资格白名单、不新增 scope、不改 `PublicApiCatalog` / `public-api.json`。
- **不改 CLI / VS Code 代码**：两个入口已经用客户端令牌调用现有端点，来源由服务端推断即可。
- **不做 Web 新面板**：仅在既有审批历史里增加一个来源标签值，不新增页面与交互。

## Background & Context

| 项 | 事实 | 位置 |
|---|---|---|
| 审批表结构 | `tool_approval` 有 `decision_source VARCHAR(20)`，无 `client_id` | `entity/ToolApproval.java`、`db/migration/V19__add_tool_approval_decision_source.sql` |
| 来源枚举 | `DecisionSource = { USER, BATCH, TIMEOUT, SESSION_STOP, SYSTEM }` | `service/ToolApprovalRegistry.java:40` |
| 单条审批 | `resolve(approvalId, userId, decision, remember)` 固定写 `USER` | `service/ToolApprovalRegistry.java:173-206` |
| 批量审批 | `resolveBatch(userId, sessionId, ids)` 固定写 `BATCH` | `service/ToolApprovalRegistry.java:232-273` |
| 落库 | `persistDecision` / `markDecision` 只写状态、时间与来源 | `service/ToolApprovalRegistry.java:353-368` |
| 审批端点 | 两个端点只取 `CurrentUser.requireId()`，没有读取请求的客户端身份 | `controller/AgentChatController.java:419-456` |
| 审批历史 | 返回 `ApprovalHistoryItem`，不含 `clientId` | `controller/AgentChatController.java:459-476`、`dto/ApprovalHistoryItem.java` |
| 客户端身份读取 | `CurrentClient.clientIdOrNull()` 在客户端令牌请求时返回 `client_id`，Web 本地令牌 / JWT 返回 `null` | `security/CurrentClient.java:17-25` |
| 客户端身份载体 | `ClientPrincipal(userId, clientId, name, scopes, clientIp)` 挂在 `Authentication.details` | `security/ClientPrincipal.java` |
| 发起入口审计 | Run 创建时把 `clientId` 写入 `requestJson`；`approval_resolved` 事件据此带 `clientId` | `controller/AgentChatController.java:177-181`、`service/AgentRunService.java:203-227` |
| 客户端令牌 | `api_client.client_id VARCHAR(32)`，CLI / 扩展用 `Authorization: Bearer lgc_...` | `db/migration/V20__add_api_client.sql`、`filter/ClientTokenFilter.java` |
| 各入口审批调用 | CLI `lg approve`、VS Code 审批面板、Web 审批卡片都调同两个端点 | `cli/src/commands/approve.ts:21-26`、`ide/vscode/src/vscode/taskController.ts:340-345`、`:367-371`、`frontend/src/api/agent.ts:116-131` |
| 前端来源类型 | `ApprovalDecisionSource` 是闭合联合类型，不含 `CLIENT` | `frontend/src/types/chat.ts:110`、`WorkbenchDock.vue:589-598` |
| 迁移版本 | 最新为 `V20__add_api_client.sql`，V21 未占用 | `src/main/resources/db/migration` |

关键推论：

- **发起入口 ≠ 审批入口**。VS Code 发起的 Run 完全可能在 Web 上批准；用 `requestJson.clientId` 当审批来源会得出错误结论。审批入口必须在**审批请求**上取身份。
- **来源可以在服务端唯一确定**。两个审批端点都经过 `ClientTokenFilter`，`CurrentClient.clientIdOrNull()` 非空即客户端令牌；因此不需要改请求契约，也不需要信任任何自报字段。
- **`CLIENT` 只用于单条手动审批**。批量的语义是「一次批量放行」，保留 `BATCH`；入口由 `client_id` 区分，避免把「批量」和「来自哪个客户端」压进一个字段。
- **改动是纯增量**：新增可空列、新增枚举值、新增可空响应字段；旧记录、旧客户端、旧事件均不受影响。

## 设计决策

1. **服务端推断入口**：`respondApproval` / `respondApprovalBatch` 读取 `CurrentClient.clientIdOrNull()`，作为 `clientId` 传入注册表；不新增请求字段，不改 CLI / 扩展。
2. **来源映射**：
   - 单条审批且 `clientId != null` → `DecisionSource.CLIENT`；
   - 单条审批且 `clientId == null` → `DecisionSource.USER`（现状）；
   - 批量审批 → `DecisionSource.BATCH`（无论来自哪个入口）；
   - `TIMEOUT` / `SESSION_STOP` / `SYSTEM` / 重启恢复过期 → 不变，`client_id` 为 `NULL`。
3. **`client_id` 只在决策时写**：`create()` 不写 `client_id`；审批由谁做出的，就记录谁的客户端身份。重启恢复的 `PENDING` 记录在恢复时仍为 `NULL`，直到有人决策。
4. **新增 V21 迁移**：`ALTER TABLE tool_approval ADD COLUMN IF NOT EXISTS client_id VARCHAR(32);`，与 `api_client.client_id` 同宽、可空、不加索引（现有「会话历史」索引已覆盖读取路径，按客户端聚合另立批次时再加索引）。
5. **签名显式传参**：`resolve` / `resolveBatch` / `persistDecision` / `markDecision` 增加 `String clientId` 参数；不提供旧签名重载，直接修改调用点（主代码 2 处 + 测试若干），避免留下两套入口。
6. **`Resolution` 不改结构**：`source` 已随结果回传，`CLIENT` 自然出现在 `approval_resolved` 事件的 `source` 字段；事件里的 `clientId` 仍由 Run 的 `requestJson` 决定（发起入口），语义不变。
7. **历史接口补可空字段**：`ApprovalHistoryItem` 增加 `String clientId`；路径、scope、其余字段不变，属于向后兼容的响应扩展。
8. **前端只认新枚举值**：`ApprovalDecisionSource` 增加 `'CLIENT'`，标签显示「客户端」，未知值仍回退「来源未知」；历史项类型补 `clientId?: string | null`，但本批次不展示具体 ID。

## 语义矩阵

| 场景 | `decision_source` | `tool_approval.client_id` | 说明 |
|---|---|---|---|
| Web 手动批准 / 拒绝 | `USER` | `NULL` | 本地令牌，无客户端身份 |
| CLI `lg approve` / `lg approve --deny` | `CLIENT` | CLI 的 `client_id` | 新增来源值 |
| VS Code 审批面板 | `CLIENT` | 扩展的 `client_id` | 新增来源值 |
| CLI / 扩展批量允许 | `BATCH` | 对应 `client_id` | 保留批量语义 |
| Web 批量允许 | `BATCH` | `NULL` | 与现状一致，仅多一列空值 |
| 审批超时 | `TIMEOUT` | `NULL` | 非人为入口 |
| `/chat/stop` 或会话停止 | `SESSION_STOP` | `NULL` | 非人为入口 |
| 等待中断 / 系统兜底 | `SYSTEM` | `NULL` | 非人为入口 |
| 重启恢复时已过期 | `TIMEOUT` | `NULL` | 与现状一致 |
| V21 之前的历史记录 | 原值（可能是 `USER` / `BATCH` / `NULL`） | `NULL` | 不回填 |

## Functional Requirements

### FR-1 来源判定

- 两个审批端点在处理请求时读取 `CurrentClient.clientIdOrNull()`，并把它作为本次决策的审批入口身份。
- 单条审批：`clientId` 非空记 `CLIENT`，为空记 `USER`；批量审批一律记 `BATCH`。
- 判定不得依赖请求体字段、Header 透传值或客户端自报；`clientId` 只能来自认证结果。
- 原有的归属校验（`userId` 匹配、`sessionId` 匹配、批量白名单）与失败返回保持不变。

### FR-2 持久化

- 新增 `tool_approval.client_id`（`VARCHAR(32)`，可空）；`ToolApproval` 增加 `clientId` 字段。
- 决策落库时同时写 `decision_source`、`decision_time`、`remember_session` 与 `client_id`。
- `create()` 创建的 `PENDING` 记录 `client_id` 为 `NULL`；`TIMEOUT` / `SESSION_STOP` / `SYSTEM` 路径显式写 `NULL`。
- 审批表仍是决策的唯一权威来源；不新增审计表。

### FR-3 接口与展示

- `POST /api/agent/approvals/{approvalId}` 与 `POST /api/agent/approvals/batch` 的路径、请求体、响应体、scope 全部不变。
- `GET /api/agent/sessions/{sessionId}/approvals` 的每一项新增 `clientId`（可空）；不返回原始 `arguments` 的边界不变。
- `DecisionSource` 增加 `CLIENT`；`approval_resolved` 事件的 `source` 可能取到 `CLIENT`，事件 `type` 集合不变。
- 前端来源标签支持 `CLIENT`（文案建议「客户端」）；旧记录 `NULL` 仍显示「来源未知」。

### FR-4 多入口一致性

- Web、CLI、VS Code 走同一端点、同一归属校验、同一批量资格；差异只体现在 `decision_source` 与 `client_id`。
- 任何入口都不能通过构造请求把来源改成 `USER` 或 `CLIENT`；来源完全由服务端认证结果决定。
- 审批仍然不能被跳过：本批次不新增任何「免审批」路径，`Capability.APPROVAL` 与 `approval:write` scope 校验不变。
- 不改变 Verify / Diagnostics 的既有约束；本批次不触碰「未通过 Verify 仍标记完成」的判定。

### FR-5 兼容与迁移

- V21 只加可空列，不重写历史记录；旧库升级后所有历史记录 `client_id = NULL`。
- `ApprovalHistoryItem` 新增字段向后兼容：旧前端忽略未知字段即可正常工作。
- 事件消费者只需容忍 `source` 出现新值 `CLIENT`；未知值处理逻辑保持不变。
- 迁移验收测试更新为「最新版本 V21、共 21 条迁移」，并断言 `tool_approval.client_id` 列存在、可空、`character varying`。

### FR-6 测试与审计证据

- 单测覆盖：Web 审批 → `USER` + `NULL`；客户端审批 → `CLIENT` + `client_id`；批量（有 / 无 `clientId`）→ `BATCH` + 对应值；超时 / 会话停止 / 系统兜底 → 来源不变且 `client_id = NULL`。
- 接口测试覆盖：带 `ClientPrincipal` 的请求批准后，历史接口返回 `CLIENT` 与 `client_id`；本地用户令牌请求批准后返回 `USER` 与 `null`。
- 回归：后端全量 `mvn -o test` 保持 0 failures（当前基线 351 条）。

## 影响面

| 范围 | 影响 |
|---|---|
| 后端主代码 | `service/ToolApprovalRegistry`（枚举、签名、落库）、`controller/AgentChatController`（读取 `CurrentClient` 并传参）、`entity/ToolApproval`、`dto/ApprovalHistoryItem` |
| 数据库 | 新增 `V21__add_tool_approval_client_id.sql`（仅加可空列） |
| 后端测试 | `ToolApprovalRegistryTest` / `ToolApprovalRegistryPersistenceTest` / `CrossTenantAccessTest` 调用点改签名；新增来源与 `client_id` 断言；`FlywayV18MigrationAcceptanceTest` 期望 V21 |
| 前端 | `types/chat.ts` 增加 `'CLIENT'`；`WorkbenchDock.vue` 增加标签分支；不改布局与交互 |
| CLI / VS Code | 零代码改动；仅验收审批后的来源落库 |
| 契约 | 路径、scope、请求体不变；仅响应新增可空字段，不需改 `public-api.json` |

## 验收

1. **单测矩阵**：四种入口组合（Web 单条、客户端单条、Web 批量、客户端批量）分别得到预期的 `decision_source` 与 `client_id`；自动来源三项 `client_id` 均为 `NULL`。
2. **接口级验证**：`MockMvc` 下分别以 `ClientPrincipal` 与本地用户身份批准同一条审批，历史接口分别返回 `CLIENT + client_id` 与 `USER + null`；批量接口返回 `BATCH` 且历史项带上审批入口 `client_id`。
3. **迁移**：`V21` 在空库全量迁移后可读；`FlywayV18MigrationAcceptanceTest` 更新为 21/21 并通过（本机未设置 `DESKTOP_PG_RUNTIME` 时按既有规则跳过）。
4. **兼容**：旧记录 `client_id` 为 `NULL` 时历史接口与前端标签均不报错；未知来源值仍显示「来源未知」。
5. **零回归**：后端 `mvn -o test` 0 failures；前端 `npm test` 通过；CLI / 扩展测试不受影响。
6. **审计闭环**：给定一条 CLI 批准的记录，能同时从「审批历史接口的 `decision_source=CLIENT` + `client_id`」与「审批表对应列」得到一致结论；Web 批准同一任务得到 `USER` + `NULL`。

## 风险

| 风险 | 应对 |
|---|---|
| 把发起入口和审批入口混为一谈 | 明确两个 `clientId` 的语义边界；审批表只写审批请求身份，事件 `clientId` 不改语义 |
| 新增枚举值导致前端或事件消费者按未知值降级 | `CLIENT` 属于既有 `DecisionSource` 扩展，前端补标签；其余消费者沿用未知值兜底 |
| 历史记录无法回填导致统计口径断层 | 文档与验收明确「V21 前无审批入口」；需要入口维度统计时按时间边界区分 |
| 签名变更波及测试较多 | 一次性改全部调用点，不留旧重载；用编译错误驱动定位，避免遗漏 |
| 迁移在真实 PostgreSQL 上未验证 | 复用既有 Flyway 验收测试；本地未开启嵌入式 PG 时，在 checklist 标注为环境限制 |
| 客户端令牌能批准带来越权担忧 | 本批次不改授权；`Capability.APPROVAL` / `approval:write` / 归属校验 / 批量白名单全部保持，来源记录只增强可追溯性 |
