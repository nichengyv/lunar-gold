# P6-05 多入口一致性与审计 - 技术任务清单

> 状态：**已完成**（2026-11-17）。spec 已确认范围；代码、测试与文档均已落地，验收证据见 `checklist.md`。

> 约定：只做「审批来源 + 审批入口」的增量记录；不改 `PublicApiCatalog` / `public-api.json` / scope / 审批策略。
> 语义基线：`agent_run.request_json.clientId` = 发起入口（P6-01，不改）；`tool_approval.client_id` = 审批入口（本批次新增）。两者不得互相覆盖。

## T1 服务端判定审批入口

- `AgentChatController.respondApproval`：取 `CurrentClient.clientIdOrNull()`，作为 `clientId` 传给 `toolApprovalRegistry.resolve(...)`。
- `AgentChatController.respondApprovalBatch`：同样取 `CurrentClient.clientIdOrNull()`，传给 `resolveBatch(...)`。
- 不新增请求字段、不读 Header、不接受客户端自报来源；来源完全由认证结果决定。
- 完成判据：Controller 中两个审批端点都能把当前请求的客户端身份下传；Web 本地令牌请求得到 `null`。

## T2 注册表来源与落库

- `ToolApprovalRegistry.DecisionSource` 增加 `CLIENT`。
- `resolve(String approvalId, Long userId, String decision, boolean rememberSession, String clientId)`：`clientId != null` 时来源 `CLIENT`，否则 `USER`；结果与来源仍通过 `Resolution` 返回。
- `resolveBatch(Long userId, Long sessionId, List<String> approvalIds, String clientId)`：来源保持 `BATCH`，`clientId` 仅用于落库。
- `persistDecision` / `markDecision` 增加 `clientId` 参数并写入；`TIMEOUT` / `SESSION_STOP` / `SYSTEM` 及 `restorePersistedApprovals` 的过期拒绝路径传 `null`。
- 不保留旧签名重载；直接修改主代码与测试调用点。
- 完成判据：编译通过；单测证明四种入口组合的来源与 `client_id` 符合语义矩阵。

## T3 数据模型与迁移

- 新增 `db/migration/V21__add_tool_approval_client_id.sql`：`ALTER TABLE tool_approval ADD COLUMN IF NOT EXISTS client_id VARCHAR(32);`。
- `ToolApproval` 增加 `clientId`（无 `nullable=false`、长度 32、注释说明为审批入口）。
- `create()` 不设置 `client_id`；只记录审批者身份。
- 完成判据：实体与迁移列一致；历史记录升级后为 `NULL`。

## T4 历史接口与前端标签

- `ApprovalHistoryItem` 增加 `String clientId`；Controller 映射 `saved.getClientId()`。
- 路径、scope、其余字段、`arguments` 不返回的边界保持不变。
- 前端 `types/chat.ts`：`ApprovalDecisionSource` 增加 `'CLIENT'`，`ApprovalHistoryItem` 增加 `clientId?: string | null`。
- `WorkbenchDock.vue` 的 `approvalSourceLabel` 增加 `CLIENT` → 「客户端」；`default` 仍为「来源未知」。
- 完成判据：前端类型检查与现有测试通过；旧记录（`null`）展示不回归。

## T5 测试与迁移验收

- 单测（`ToolApprovalRegistryTest` / `ToolApprovalRegistryPersistenceTest` / `CrossTenantAccessTest`）更新签名，并新增：
  - Web 单条 → `USER` + `client_id = null`；
  - 客户端单条（批准 / 拒绝）→ `CLIENT` + `client_id`；
  - 批量有 / 无 `clientId` → `BATCH` + 对应值；
  - 超时、会话停止、系统兜底 → 来源不变且 `client_id = null`。
- 接口测试：以 `ClientPrincipal` 与本地用户身份分别批准同一条审批，历史接口分别返回 `CLIENT + clientId` 与 `USER + null`。
- `FlywayV18MigrationAcceptanceTest` 期望改为 V21 / 21 条，并断言 `tool_approval.client_id` 为可空 `character varying`。
- 完成判据：新增用例全绿；后端全量 `mvn -o test` 0 failures。

## T6 文档与零回归收口

- `docs/phases/phase6/plan.md`：P6-05 标注状态与落地范围，说明体验指标维度延后。
- `docs/UPDATE_PLAN.md`：迁移版本表补 V21。
- 回填 `checklist.md` 的证据（测试名、命令输出、偏差）。
- 零回归：后端、前端 `npm test`、CLI 与扩展测试不受影响。
- 完成判据：checklist 全勾或逐条交代偏差；契约测试无 diff。

## 依赖顺序

T3 → T2 → T1 → T4 → T5 → T6。

## 不做

- 不回填历史审批的 `client_id`。
- 不新增事件类型或事件字段；`approval_resolved.clientId` 语义不变。
- 不按入口拆分体验指标（另立批次）。
- 不新增 scope、不改审批白名单、不做自动批准。
- 不改 CLI / VS Code 代码。
