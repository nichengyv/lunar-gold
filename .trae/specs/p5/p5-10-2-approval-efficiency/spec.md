# P5-10-2 审批效率剩余项 — 规格说明

> 日期：2026-11-14
> 状态：已完成（2026-11-14）
> 对应规划：`docs/phases/phase5/plan.md` P5-10-2（J/K 切换、低风险批量、审批历史）
> 已存在基础：A/D 快捷键、首张卡片快捷键门控、倒计时、详情折叠、审批持久化、追踪面板待审批入口

## 1. 目标

补齐当前审批队列的三项效率能力：

1. 多个待审批之间可用 `J` / `K` 移动，当前项清晰可见，`A` / `D` 只作用于当前项。
2. 当前审批队列中的多个低风险写入可一次允许；命令、删除、移动及破坏性操作仍必须逐个确认。
3. 会话内可回看已处理审批的工具、决策和决策来源；刷新或重开页面后仍可追溯，批量操作有独立审计标识。

## 2. 非目标

- 不重做已完成的 A/D、倒计时、diff 折叠、风险摘要和追踪面板待审批定位。
- 不提供批量拒绝，不让批量操作隐式开启「本会话总是允许」。
- 不批量允许 `run_command`、任何删除/移动、递归删除或其他 `EXECUTE` / `DESTRUCTIVE` 操作。
- 不新增独立历史页面或跨会话历史；不返回审批原始参数 JSON。
- 不引入新的审批状态机，不改变工具执行和 ReAct 循环结构。

## 3. 现状与缺口

| 能力 | 已确认现状 | 缺口 |
|---|---|---|
| 队列与快捷键 | `ChatView` 按当前会话过滤未处理项；`ApprovalCard` 各自监听 keydown，`shortcutActive` 只放行第一张 | 无当前项模型、J/K 移动和选中态 |
| 批量 | 单条 `POST /api/agent/approvals/{approvalId}`； registry 逐项校验归属并完成 future | 无批量 API、低风险服务端判定和批次审计 |
| 决策记录 | `tool_approval` 已有工具、参数、风险、状态、决策时间；内存等待超时/停止会自动拒绝 | 无 `decisionSource`，无法区分手动、批量、超时和会话停止 |
| 运行审计 | `APPROVAL_RESOLVED` 事件只写 `decision`；`ChangeSetService` 已尝试读取 `approvalId` | resolved 事件缺 `approvalId/source/batchId`，审批关联不完整 |
| 历史展示 | 「追踪」面板已有会话级数据加载与待审批区 | 无历史查询 API 和已处理审批列表 |

## 4. 设计决策

### 4.1 J/K 当前项与纯键盘操作

在 `ChatView.vue` 维护当前会话审批队列的 `selectedApprovalId`：

- 队列首次出现时选中第一项；`J` 选择下一项、`K` 选择上一项，到边界后停止，不循环跳转。
- 输入框、textarea、select、contenteditable 正在输入，或按了 Ctrl/Meta/Alt 时，不处理 J/K。
- 当前项移除后，优先选中同一位置的后一项；没有后一项则选前一项。切换会话时按新会话队列重新收口。
- 当前卡片显示明确选中边框，并在队列内部 `scrollIntoView({ block: 'nearest' })`；点击卡片也同步选中。
- 现有卡片级 A/D 保留，但 `shortcutActive` 改为「approvalId 等于 selectedApprovalId」，不再固定第一项。
- 队列提示更新为「J/K 切换 · A 允许 / D 拒绝」；不额外引入一套全局 A/D 监听。

### 4.2 低风险批量允许

低风险采用保守白名单，前端只负责展示，后端是最终安全边界：

- 仅 `risk=WRITE` 且工具为 `write_file` / `edit_file`，或对应 MCP `_write_file` / `_edit_file`。
- `run_command`、`delete_file`、任何删除/移动/建目录工具及 `DESTRUCTIVE` 均不进入批量。
- 当前队列至少有 2 个低风险项时，队列头显示「允许全部低风险（N）」；混合队列中的高危项仍保留逐个操作。
- 「同轮」按当前会话的活动审批队列界定，不新增 runId 字段。请求同时携带 `sessionId` 和明确的 `approvalIds`，服务端校验所有 ID 属于当前用户、该会话且仍为 PENDING。

新增接口：

```http
POST /api/agent/approvals/batch
Content-Type: application/json

{
  "sessionId": 10,
  "approvalIds": ["ap-1", "ap-2"]
}
```

响应包含 `batchId` 与实际完成的 `resolvedIds`。规则：

1. 只允许 APPROVE；不提供 decision=REJECT 或 remember=SESSION。
2. 预检发现高危、跨会话或越权 ID 时整批拒绝，不批准其中任何一项。
3. 预检后个别项因并发已失效时，只返回实际完成的 ID；前端只把这些项标为已批准。
4. 每次批量生成一个 `batchId`；每条审批落 `decisionSource=BATCH`，对应 Run 事件携带同一 `batchId`，可还原一次批量操作。
5. 批量接口与普通审批一样受 `Capability.APPROVAL` 门控。

### 4.3 决策来源持久化与运行事件

`tool_approval` 新增可空 `decision_source`，使用 V19 Flyway 迁移，不新建表。来源固定为：

| 来源 | 触发 | 历史展示 |
|---|---|---|
| `USER` | 单条审批接口批准/拒绝（含「本会话总是允许」） | 本人 |
| `BATCH` | 低风险批量接口批准 | 本人 · 批量 |
| `TIMEOUT` | 等待超过 expiresAt；启动恢复时发现 PENDING 已过期 | 超时自动拒绝 |
| `SESSION_STOP` | 停止会话、客户端取消/断开导致审批终止 | 会话停止 |
| `SYSTEM` | 线程中断或等待异常等系统兜底拒绝 | 系统中止 |
| `NULL` | V19 之前已经处理的历史记录 | 早期记录（来源未知） |

实现上将等待结果由单独 Decision 扩展为包含 `decision/source/batchId` 的内部 resolution，保证 `await` 返回后能把真实来源继续传给回调，而不是由 ReAct 或前端猜测。

- 所有自动拒绝路径仍保持 REJECTED，不改变现有安全语义。
- 决策持久化保持幂等；手动、超时和停止发生竞争时只有获胜结果生效。
- `approval_resolved` SSE 与持久化 `APPROVAL_RESOLVED` Run Event 均扩展为 `approvalId/decision/source/batchId?`，旧字段保留。
- `AgentRunService.resumeAfterApproval` 同步接收上述审计字段；`ChangeSetService` 现有 approvalId 关联逻辑继续兼容旧事件。

### 4.4 会话内审批历史

新增：

```http
GET /api/agent/sessions/{sessionId}/approvals
```

- 控制器先用 `chatService.getOwnedSession` 校验会话归属，再按 `sessionId + userId` 查询已处理记录，按创建时间倒序返回。
- 返回字段仅含 `approvalId/tool/risk/state/decisionSource/decisionTime/createTime/rememberSession`，不返回原始 `arguments`。
- 历史以 `tool_approval` 为权威来源，不依赖会被自动 dismiss 的前端卡片，也不要求 Run 仍在运行。

历史放在现有工作台「追踪」页、待审批区之后，不新增 tab：

- 每行显示工具名、已批准/已拒绝、来源标签和决策时间；「本会话总是允许」保留可辨识标记。
- 进入追踪页或切换会话时加载；追踪页打开期间收到 approval_resolved 后刷新。
- 无历史时显示简洁空态；历史接口失败不掩盖已有工具统计。
- 前端 store 增加轻量 revision 通知历史刷新，不把整份历史复制进每条消息。

### 4.5 提交失败与选中收口

- 现有 `respondApproval` 在 API 失败后仍乐观标记 resolved 的行为一并修正：失败时保留待审批并提示，不伪造本地决策。
- 单条成功先按 USER 乐观更新，服务端事件到达后以真实 source 为准；批量只更新响应中的 resolvedIds。
- 队列缩小时按 4.1 规则移动选中项，避免 A/D 落到不可见或已处理审批上。

## 5. 涉及文件

### 后端

- `entity/ToolApproval.java`：新增 `decisionSource`。
- `db/migration/V19__add_tool_approval_decision_source.sql`：新增可空列及会话历史查询索引。
- `repository/ToolApprovalRepository.java`：会话+用户+已处理状态的倒序查询。
- `service/ToolApprovalRegistry.java`：resolution/source、批量预检与决策、超时/停止来源、历史查询。
- `service/ReActAgentService.java`：把完整审批 resolution 传给回调，执行语义不变。
- `service/AgentRunService.java`：APPROVAL_RESOLVED 审计载荷补齐 approvalId/source/batchId。
- `controller/AgentChatController.java`：批量与历史接口，SSE 审计字段。
- `dto/ApprovalBatchRequest.java`、`dto/ApprovalHistoryItem.java`：批量请求和历史响应类型。

### 前端

- `types/chat.ts` / `types/api.ts`：决策来源、历史项、批量响应及 resolved 扩展类型。
- `api/agent.ts`：批量允许和历史查询。
- `api/sse.ts`：approval_resolved 的 source/batchId 解析。
- `stores/chat.ts`：批量 action、来源事件、审批 revision、失败时不伪造 resolved。
- `views/ChatView.vue`：selectedApprovalId、J/K、选中收口、批量按钮与提示。
- `components/ApprovalCard.vue`：当前项样式和点击选中支持，保留现有卡片操作。
- `components/WorkbenchDock.vue`：追踪页审批历史及刷新。

## 6. 验收标准

1. 两个及以上待审批时，J/K 移动清晰可见；输入框内 J/K 正常输入；A/D 只处理当前项，可纯键盘完成一次允许和一次拒绝。
2. 当前项处理或队列切换后，选中项始终有效且滚动到可见位置。
3. 两个低风险写/编辑审批可一次允许；混合队列中的命令、删除、移动、高危项不被批量批准；伪造批量请求也被后端拒绝。
4. 批量中的每条记录均持久化 `BATCH`，Run 事件含相同 batchId、approvalId、decision 和 source。
5. 手动、批量、超时、会话停止分别落正确来源；重启后过期 PENDING 落 TIMEOUT，旧数据不伪造来源。
6. 追踪页能回看本会话已处理审批的工具、决策、来源和时间；切会话不串数据，刷新后仍可查。
7. 审批提交失败不乐观标记成功；现有 A/D、倒计时、会话级总是允许、ChangeSet 和断线恢复不回归。

## 7. 验证方式

- 后端聚焦测试：registry 的手动/批量/超时/停止来源、低风险和归属校验、历史查询；Run Event 审计字段；控制器批量/历史能力门控和归属。
- 前端 Vitest：低风险判定、批量响应更新、失败不 resolved、SSE source 解析、当前卡片快捷键与选中样式、历史来源展示。
- Playwright：扩展 `approval.spec.ts`，覆盖 J/K+A/D、混合队列只允许低风险、历史来源回看。
- 全量回归：`mvn -o test`、前端 `npx vitest run`、`npm run build`、Playwright 全量并重复运行。
- 完成后同步 `docs/UPDATE_PLAN.md`、`docs/phases/phase5/plan.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`，勾选 tasks/checklist 并记录实际测试数。

## 8. 风险与取舍

- 低风险白名单刻意窄于所有 WRITE：建目录和移动虽非 EXECUTE，也继续逐项确认，避免把“可写”误等同为“可批量”。
- 当前没有 approval.runId；本批以 sessionId + 明确 ID 集合界定当前队列，不为了展示分组扩大数据库和 ReAct 接口改造。
- 历史使用数据库而非仅 Run Event，保证重启和旧 run 仍可查；Run Event 用于同轮审计与 ChangeSet 对账，两者相互补充。
- 历史不返回原始 arguments，保持当前审批卡片“不暴露裸参数 JSON”的边界。
