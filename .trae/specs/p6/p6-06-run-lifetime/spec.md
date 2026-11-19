# P6-06 Run 生命周期与客户端断流解耦 - Product Requirements Document

> 状态：**已实施**（2026-11-17）。核心语义、单测、ReAct 集成、真实 HTTP 断流验收与全量回归通过；剩余偏差见 checklist 的偏差记录。

## Overview

- **Summary**：把「客户端 SSE 断流」从「取消 Run」中拆出来。断流后后端继续执行当前 Run、继续落库事件与审批；只有用户显式停止、预算耗尽或异常才终止 Run。
- **Purpose**：当前实现把断流与取消共用一个标记，导致 VS Code 窗口重载、CLI 中断、Web 刷新都会让后端把正在执行的任务标成取消并丢弃半截回答。P6-03 验收时已确认这一偏差；本批次修复根因，使 `runId + sinceSeq` 续传真正具备「补齐事件 + 拿到终态」的意义。
- **Target Users**：以 VS Code 扩展、CLI、Web 工作台发起长任务的本地用户；网络抖动或窗口重载不应损失已完成的工作。

## Goals

1. **断流不终止**：SSE 连接断开后 Run 继续执行，直到正常完成、异常、显式停止或预算耗尽。
2. **显式停止仍立即生效**：`POST /api/agent/chat/stop` 仍是唯一的用户级取消信号，保持现有中止 ReAct、杀子进程、拒绝待审批的行为。
3. **断流期间事件完整**：工具调用、工具结果、正文检查点、完成事件都必须落库，不能因为 SSE 写失败而跳过。
4. **审批跨断流保持待处理**：断流不自动拒绝审批；恢复连接后仍可批准 / 拒绝，超时规则不变。
5. **无人连接时仍是有界的**：沿用现有时间预算、token 预算、审批超时与工具超时，不引入无限期执行的 Run。
6. **多入口语义一致**：Web、CLI、VS Code 对「断流」与「停止」得到同一结果；重连只补事件，不重复发起 Run。

## Non-Goals

- 不做断流后自动把 SSE 重新附着到已有 Run（不新增订阅式推送）；客户端仍用 `GET /api/agent/runs/{runId}/events?sinceSeq=` 补齐。
- 不新增端点、不改 `PublicApiCatalog` / `public-api.json` / scope / 数据库 schema。
- 不做多客户端广播或共享观看；一个 Run 仍只有一个原始 SSE 通道，其他入口靠事件轮询。
- 不保证后端进程重启后继续执行进行中的 Run：执行线程在进程内，重启语义与现状一致，本批次不扩大。
- 不做跨实例协调、任务队列、断点续跑模型推理。
- 不做自动批准、自动重试、自动重新提交任务。
- 不重做前端交互：仅在不一致处修正状态映射或文案，不新增页面与控件。

## Background & Context

| 项 | 事实 | 位置 |
|---|---|---|
| 断连标记 | `ChatSseChannel.clientDisconnected` 在 SSE 写出失败、心跳失败、`onError`、`onTimeout` 时置位 | `controller/support/ChatSseChannel.java:36-61` |
| 标记复用 | 同一 `AtomicBoolean` 既登记为停止标记，又作为 ReAct 的 `cancelled` 入参 | `controller/AgentChatController.java:258`、`:326-336` |
| 推理中止 | ReAct 每轮开始与工具批次后检查 `cancelled`，命中即按 `CANCELLED` 收尾 | `service/ReActAgentService.java:436-446`、`:753-762` |
| 审批等待 | `await(record, cancelled)` 在标记置位时按 `SESSION_STOP` 拒绝 | `service/ToolApprovalRegistry.java:286-325` |
| 断流收尾 | 断流时丢弃未完成回复、标 `CANCELLED`、补最后一个正文检查点 | `controller/support/ChatStreamBridge.java:452-466`、`:550-561` |
| 事件落库 | 多数事件在 `channel.send(...)` 之后才 `appendRunEvent(...)`；send 抛异常会跳过落库 | `ChatStreamBridge.java:180-185`、`:216-223`、`:353-354`、`:427-428` |
| 异步执行 | Run 在 `agentStreamExecutor` 上执行，Controller 已返回，具备「请求结束但 Run 继续」的基础 | `AgentChatController.java:266` |
| 续传接口 | `events?sinceSeq=` 返回增量事件与 Run 终态；`runs/running` 供刷新后找回 runId | `AgentChatController.java:347-394` |
| 停止接口 | 按 `sessionId` 置位并拒绝该会话待审批 | `AgentChatController.java:401-413` |
| 预算与超时 | `maxSecondsPerRun` / `maxTokensPerRun`；审批 `TIMEOUT_MS = 120s` | `AgentChatController`、`ToolApprovalRegistry.java:61` |
| 多入口 | CLI 有独立 `stop` 命令；扩展停止时先调 stop 再中止本地流；Web 刷新后走 `runs/running` | `cli/src/commands/stop.ts`、`ide/vscode/src/vscode/taskController.ts`、`frontend/src/api/sse.ts` |

关键推论：

- **一个标记承担两种语义**，所以只要 SSE 断一次，任务就不可逆地进入取消。这是偏差的根因，不是扩展侧的展示问题。
- **现有「断线可续传」只在客户端短暂断线且 Run 尚未继续推进时成立**；一旦写作事件发生在断流期间，`appendRunEvent` 会被 `send` 异常跳过，事件链出现缺口。
- **执行线程独立于请求线程**，因此「断流后继续跑」不需要新的调度器，只需要把取消信号与连接状态拆开，并修正收尾逻辑。
- **已有预算与审批超时可以兜底无人值守**；不引入新配置项也能保证 Run 有界结束。

## 设计决策

1. **拆成两个信号**：`cancelRequested`（显式停止，唯一能中止 ReAct 与工具的信号）与 `clientDetached`（SSE 通道断开，仅影响事件发送）。ReAct 循环、工具执行、审批等待只读 `cancelRequested`；SSE 写出只读 `clientDetached`。
2. **持久化先于发送**：所有需要重放的语义事件先 `appendRunEvent`，再尝试 `channel.send`；`clientDetached` 置位后 `send` 变为静默 no-op，不再抛异常。这样断流期间的事件链保持连续，`sinceSeq` 能完整重建。
3. **终态只由业务结果决定**：正常完成 → 保存完整助手消息并标成功；异常 → 失败；显式停止 → `CANCELLED`（reason 表示用户停止）。`clientDetached` 不参与终态判定，也不再触发「丢弃半截回复」。
4. **审批等待只看显式停止**：断流期间待审批保持 `PENDING`；到 `expiresAt` 仍按 `TIMEOUT` 拒绝，显式停止按 `SESSION_STOP` 拒绝。恢复连接后 `approvals` 列表可直接继续处理。
5. **停止按会话取消该会话所有活跃 Run**：断流会让 Run 在后台继续，若同一会话又发起新 Run，旧的「一会话一标记」会被覆盖，导致旧 Run 无法停止。`GenerationRegistry` 改为会话下保存多个 Run 控制句柄，`/chat/stop` 取消全部；不改变接口签名。
6. **不新增事件类型**：断流只在日志与 Run 结果中留痕（`clientDetached`），避免扩大事件词汇、影响 Web / CLI / 扩展的未知事件处理；真正的取消仍写现有 `CANCELED` 事件。
7. **不新增配置项**：生命周期兜底沿用 `max-seconds-per-run`、`max-tokens-per-run`、审批 120s 与工具自身超时；命中后走现有收尾路径。
8. **不改请求/续传协议**：`POST /chat/stream` 仍是「发起一个 Run」；重连不得再次 POST，只能读取事件。扩展与 CLI 的现有恢复逻辑不变。
9. **主动中断必须显式停止**：CLI / 扩展 / Web 在用户主动中断时调用 stop；本地流 abort 或窗口重载不等于 stop。若 CLI 当前 Ctrl-C 未调用 stop，本批次只补这一处调用，不改命令输出与退出码体系。

## 语义矩阵

| 场景 | Run 是否继续 | 审批 | 事件与消息 | 终态 |
|---|---|---|---|---|
| 用户点停止 / `/chat/stop` | 否，立即中止 | 待审批按 `SESSION_STOP` 拒绝 | 保留已产生事件；写 `CANCELED` | `CANCELLED` |
| SSE 断流（关窗口 / 刷新 / 网络断） | 是 | 保持 `PENDING`，不拒绝 | 继续落库；不发送，不丢弃回复 | 由后续结果决定 |
| 断流后审批超时 | 是（工具按拒绝继续） | `TIMEOUT` 拒绝 | 继续落库 | 由后续结果决定 |
| 断流后正常完成 | — | — | 保存完整助手消息与轨迹 | 成功 |
| 断流后异常 | — | — | 记录失败事件与错误码 | 失败 |
| 时间 / token 预算耗尽 | 否 | 待审批按现有超时处理 | 写既有预算/终止事件 | 沿用现有映射 |
| 后端进程重启 | 否（执行线程消失） | 待审批随进程丢失 | 沿用现有重启恢复语义 | 沿用现状，不在本批次扩大 |

## Functional Requirements

### FR-1 断流处理

- SSE 写出失败、心跳失败、`onError` 只置 `clientDetached`，不置 `cancelRequested`。
- 断流后关闭 emitter 与心跳，释放连接资源；后续业务事件只落库，不再尝试写出。
- 断流不得触发：标取消、拒绝审批、丢弃助手消息、中止工具执行。

### FR-2 显式停止

- `POST /api/agent/chat/stop?sessionId=` 设置该会话全部活跃 Run 的 `cancelRequested`，并拒绝其待审批。
- ReAct 在轮次边界与工具批次后检查该信号；`run_command` 等长耗时工具沿用现有 200ms 内杀子进程的机制。
- 停止的终态、reason 与审计事件保持现状，供 P6-05 的来源统计使用。

### FR-3 事件完整性与续传

- 每个语义事件先落库后发送；断流期间 `run_events.seq` 必须连续，不得因发送失败缺项。
- 正文仍按现有 800ms / 2 万字检查点落库；完成时保存完整助手消息与轨迹。
- 重连使用 `events?sinceSeq=` 补齐，不重复执行工具、不重复写入文件变更。
- 若重连时 Run 已结束，直接返回终态与完整事件；助手消息可通过现有消息接口读取。

### FR-4 审批

- 断流期间待审批卡片不再推送，但记录仍为 `PENDING`，`GET /api/agent/sessions/{sessionId}/approvals` 可查。
- 批准 / 拒绝 / 批量审批逻辑不变；审批来源与批量 `batchId` 保持现状。
- 断流不改变 120s 审批超时；超时拒绝后 Run 按现有工具失败路径继续或结束。

### FR-5 生命周期与资源

- 不新增「无客户端超时」配置；`maxSecondsPerRun`、`maxTokensPerRun` 与工具超时是唯一兜底。
- `GenerationRegistry` 支持同一会话多个活跃 Run；`/chat/stop` 全部取消，Run 收尾时按句柄精确注销，避免误删新 Run。
- 断流不得造成心跳线程、emitter 或审批记录泄漏；收尾路径必须释放。

### FR-6 可观测性

- 断流、重连、显式停止分别记录带 `runId` / `sessionId` 的日志，便于区分。
- Run 结果中标记是否发生过断流（内部字段），不新增公开事件类型。
- 终态原因不得把断流写成 `CANCELLED` 或用户停止。

### FR-7 多入口一致性

- Web：刷新后通过 `runs/running` + `events` 恢复展示；断流不再导致任务消失或标记取消。
- CLI：`run` 的本地流中断不等于停止；用户 Ctrl-C 若为主动中断，必须显式调用 stop。
- VS Code：窗口重载后按 `runId + sinceSeq` 恢复；不会因为扩展宿主重启而取消后台 Run。

### FR-8 兼容性

- 公开 API 契约、响应结构、scope 不变；旧客户端无需修改。
- 事件类型集合不变；已有事件顺序除「先落库」外保持可观察顺序一致。
- 不新增数据库迁移；`run_events`、`agent_run` 结构不变。

## 影响面

| 范围 | 影响 |
|---|---|
| 后端主代码 | `ChatSseChannel`、`AgentChatController`、`ChatStreamBridge`、`ReActAgentService`（信号来源）、`ToolApprovalRegistry`、`GenerationRegistry` |
| 后端测试 | 新增断流 / 停止 / 审批 / 事件完整性单测与 HTTP 级集成测试；现有 331 条必须保持全绿 |
| CLI | 原则上零改动；仅核查并必要时补「Ctrl-C 主动中断调用 stop」 |
| VS Code 扩展 | 零代码改动；重载恢复路径按本批次语义重新验收 |
| 前端 | 不新增 UI；核查「断流即结束」的本地假设，必要时只修正状态映射或文案 |
| 契约与数据 | 不改 `public-api.json`、`PublicApiCatalog`、scope、数据库 schema |

## 验收

1. **单测**：`cancelRequested` 与 `clientDetached` 行为隔离；断流时审批不被拒绝；断流收尾保存消息且不标取消；`GenerationRegistry` 多 Run 停止与精确注销；先落库后发送的顺序有断言。
2. **集成（HTTP / SSE）**：发起任务后主动关闭 SSE 连接，断言 Run 继续、事件 seq 连续、最终成功、助手消息完整；对同一 Run 调 stop，断言 `CANCELLED` 且后续不再产生工具事件。已由 `RunDisconnectHttpAcceptanceTest` 在真实 Tomcat 上以 RST 断开覆盖。
3. **审批跨断流**：断流期间产生待审批，审批保持 `PENDING`；批准后工具继续执行并完成；另测超时与显式停止两种拒绝来源。跨断流部分已由同一 HTTP 用例覆盖。
4. **多入口**：Web 刷新、VS Code 窗口重载、CLI 中断三场景分别验证「不误取消 / 可继续 / 可显式停止」。
5. **零回归**：后端 `mvn -o test` **351 tests / 0 failures / 0 errors / 15 skipped**；CLI 71 条、扩展 43 条不变；`frontend/` 现有测试通过（生产构建受本机 esbuild 权限影响，见 checklist）。覆盖率无既有门禁，按 checklist 的分支清单验收。

## 风险

| 风险 | 应对 |
|---|---|
| 无人连接时继续消耗 token / 调用工具 | 沿用时间与 token 预算、工具超时；日志记录断流，便于排查异常长跑 |
| 断流后用户以为任务已结束，实际仍在跑 | 重连 / 刷新可恢复看到运行中；停止入口始终可用；文档写明断流不等于停止 |
| 同一会话堆积多个后台 Run | 停止按会话取消全部；发起新任务前仍提示先停止或完成当前任务（入口侧约束不变） |
| 事件顺序调整影响重放或前端渲染 | 先落库不改变事件语义顺序；新增顺序断言与事件快照测试 |
| 前端存在「SSE 结束即任务结束」的本地假设 | 实现前先核查 `frontend/src/api/sse.ts` 与聊天 store；只做最小状态映射修正，不新增 UI |
| 后端重启仍会丢失执行中的 Run | 明确不扩大范围；文档与验收都不宣称重启可续跑 |
| 审批长期无人处理导致任务停在等待 | 保持 120s 超时拒绝语义，行为与现状一致，不新增自动批准 |
