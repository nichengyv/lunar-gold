# P5-14 任务级 ChangeSet 与交付 - Product Requirements Document

## Overview

- **Summary**：在现有 `PendingFileChange`、`AgentRun`、`AgentRunEvent`、审批记录与 Verify/Diagnostics 结果之上，增加任务级 ChangeSet 聚合视图。一个 Session 内的多次 Run 可统一查看文件变更、增删行、diff、来源 run/turn、审批、诊断与验证状态；支持继续修复、按 Run/整体撤回，并在撤回时检测外部修改冲突。
- **Purpose**：把「按轮产生了一些修改」升级为「一个任务可以统一审查、验证、继续修复和撤回」。接受只表示确认交付，不能把未验证改写为验证通过。
- **Target Users**：本地开发者、工作区任务使用者。

## Goals

1. 不新增 Task/ChangeSet 表：ChangeSet 作为现有实体的聚合视图，按 Session 聚合、按 Run 过滤。
2. 统一交付摘要：文件、增删行、diff、来源 run/turn、审批、诊断、验证状态；区分进行中、待审批、失败、已停止、未完成、未验证、验证通过、用户已接受。
3. 继续修复：从验证失败或诊断错误生成带失败上下文、未完成计划与原任务提示的继续执行输入，沿用同一 Session。
4. 接受与撤回：接受不改变验证状态；整体撤回按逆序回滚 Agent 变更；支持按 Run 撤回；已接受变更保留审计且不被隐式回滚。
5. 冲突检测：文件在 Agent 修改后被外部改动时拒绝覆盖，返回冲突明细。
6. 刷新恢复：交付视图完全由 DB 与 Run Event 重建，刷新后可恢复。

## Non-Goals

- 不新增数据库表或迁移；`turnId` 复用 `AgentRun.requestJson` 持久化映射。
- 不实现命令外部副作用的完整回滚；不承诺还原 Agent 之外进程产生的变化。
- 不实现任务实体（Task）拆分；同一 Session 即一个任务边界。
- 不实现 P5-11-1 前端 Vitest/Playwright 基建；该 E2E 仍归属 P5-11-1。
- 不重做 P5-05 诊断、P5-06 Verify、P5-13 完成控制。
- 不修改桌面打包、发布脚本与 CI。

## Background & Context

- `PendingFileChange` 已有 `sessionId`、`turnId`、`oldContent/newContent/diffText/additions/deletions/status(PENDING|REVERTED|ACCEPTED)`，但无 `runId`。
- `AgentRun` 有 `sessionId/projectId/state/requestJson/resultJson/startTime/endTime`；`AgentRunEvent` 已持久化 `COMPLETION`、`VERIFY`、`DIAGNOSTICS`、`APPROVAL_REQUESTED`、`APPROVAL_RESOLVED`，其中 `VERIFY/DIAGNOSTICS` payload 已带 `runId/turnId`。
- `AgentChatController` 当前在异步执行线程内生成 `turnId` 后通过 SSE 下发，未进入 `requestJson`。需提前生成并写入 `requestJson`，才能稳定建立 runId ↔ turnId 映射。
- `FileReviewController` 现有 `revert` / `revert-all` / `revert-turn` 直接覆盖写盘，无外部修改冲突检测。
- 现有前端 `PendingChangesCard.vue` 只展示项目 PENDING 变更，按文件操作；无任务状态、验证摘要、继续修复与整体撤回。
- 验收要求 P5-11-1 覆盖端到端关键路径；该基建尚未开始，本规格只交付可由 `vue-tsc` 与后端测试验证的功能与类型。

## Functional Requirements

### FR-1 持久化 Run ↔ Turn 映射（不新增迁移）

文件：`src/main/java/com/capsule/agentkey/controller/AgentChatController.java`

- 在 `agentRunService.create(...)` 之前生成 `final String turnId = "turn-" + UUID...`。
- `requestJson` 改为 `LinkedHashMap`，包含 `prompt`、`model`、`turnId`。
- 删除异步执行块内原有的 `turnId` 生成；`WorkspaceContext.setTurnId(turnId)` 与 SSE `turn_id` 继续使用同一值。

### FR-2 ChangeSet 查询接口

文件：`src/main/java/com/capsule/agentkey/controller/ChangeSetController.java`（新增）

- `@RestController @RequestMapping("/api/file-changes") @RequiresCapability(Capability.APPROVAL)`。
- `GET /changeset?sessionId={id}&runId={可选}`：
  - 通过 `ChatService.getOwnedSession(sessionId, userId)` 校验归属。
  - 返回 `ChangeSetView`（见 FR-3）。
- `POST /revert-session?sessionId={id}`：任务级整体撤回。
- `POST /revert-run?sessionId={id}&runId={id}`：按 Run 撤回。
- `getCurrentUserId()` 复用 SecurityContext 模式。
- 空结果返回 `ok=true` 与空 ChangeSet，不抛 404。

### FR-3 ChangeSetService 聚合模型

文件：`src/main/java/com/capsule/agentkey/service/ChangeSetService.java`（新增），注入：
`PendingFileChangeRepository`、`AgentRunRepository`、`AgentRunEventRepository`、`ChatMessageRepository`、`ProjectRepository`、`WorkspaceFileService`、`ObjectMapper`。

数据源：

1. `AgentRunRepository.findByUserIdAndSessionIdOrderByCreateTimeDescIdDesc(userId, sessionId)` 取任务内 Run；指定 `runId` 时只保留该条并要求属于该 Session。
2. `PendingFileChangeRepository.findBySessionIdOrderByCreateTimeDescIdDesc(sessionId)` 取全部状态变更。
3. 每个 Run：解析 `requestJson` 的 `prompt/turnId`；解析 `resultJson` 与最后一个 `COMPLETION` 事件得到 completion；取最后一个 `VERIFY`、`DIAGNOSTICS`；合并 `APPROVAL_REQUESTED/RESOLVED` 为审批项。
4. 变更按 `turnId` 归属 Run；无法匹配时 `runId=null` 并保留在变更列表。

`ChangeSetView` 字段：

```text
sessionId, projectId, runFilter
status, statusLabel, accepted
verification { status, label, message, runId, turnId, failureFingerprint, commands[] }
approvals { requested, resolved, items[{approvalId, tool, decision, runId, turnId}] }
totals { files, additions, deletions, pending, accepted, reverted }
runs [{ runId, turnId, state, prompt, createTime, endTime, completion, verification, diagnostics, approvals, files, additions, deletions }]
changes [{ id, runId, turnId, filePath, action, source, additions, deletions, diffText, status, createTime }]
continuePrompt
```

状态计算（`status`）：

1. 无 Run → `EMPTY`（暂无变更）。
2. 任一 Run 为 `QUEUED/RUNNING` → `RUNNING`；任一 `WAITING_APPROVAL` → `WAITING_APPROVAL`。
3. 否则取「最近一条具备 completion/verification/changes 的 Run」作为候选：
   - `CANCELLED` → `STOPPED`；
   - `FAILED` → `FAILED`；
   - completion `INCOMPLETE` → `INCOMPLETE`；
   - completion `UNVERIFIED` → `UNVERIFIED`；
   - 有文件变更：
     - verification `PASSED` 且不存在 PENDING 变更 → `ACCEPTED`（全部已接受）或 `VERIFIED`；
     - 其余 → `UNVERIFIED`；
   - 无文件变更且 completion `COMPLETED` → `COMPLETED`；
   - 兜底：有文件 `UNVERIFIED`，无文件 `COMPLETED`。

`statusLabel` 映射：进行中、待审批、已停止、失败、未完成、未验证、验证通过、用户已接受、已完成、暂无变更。

`verification` 计算：

- 优先取候选 Run 最后一个 `VERIFY` 事件的 `status` 与 `message/commands/failureFingerprint`。
- 无 VERIFY 时按 completion.reason 映射：`VERIFY_PASSED→PASSED`、`VERIFY_FAILED/DIAGNOSTICS_FAILED→FAILED`、`VERIFY_NOT_CONFIGURED→NOT_CONFIGURED`、`VERIFY_UNAVAILABLE/VERIFICATION_MISSING→UNAVAILABLE/MISSING`、`DIAGNOSTICS_PASSED→DIAGNOSTICS_PASSED`；其余 `MISSING`。
- `label`：验证通过/验证失败/未配置验证/验证不可用/编译诊断通过/未验证。
- **接受动作（PENDING→ACCEPTED）不得参与 verification 计算**；测试必须断言接受后仍为 `UNVERIFIED/NOT_CONFIGURED` 等原状态。

`accepted`：存在变更且 `pending=0 && accepted>0`。

`continuePrompt`：仅当 `status ∈ {FAILED, INCOMPLETE, UNVERIFIED, STOPPED}` 且存在失败/未验证依据时生成，内容固定包含：

1. 「继续修复上一个任务，沿用当前任务上下文，不要从头重做。」
2. 原任务：候选 Run `requestJson.prompt`。
3. 验证失败上下文：`verification.message`，失败命令 `name/command/exitCode/outputTail`（最多 3 条，单条输出截断 800 字符）。
4. 诊断错误：`diagnostics.diagnostics` 中 `severity=ERROR` 的文件、行号与消息（最多 10 条）。
5. 未完成计划：解析 Session 最近一条 ASSISTANT 消息 trace 内 `plan.steps`，筛选 `pending/in_progress`，取 `title/step/content`。
6. 「请先确认当前工作区状态，再修改并重新验证；验证未通过不得声称完成。」

无任何依据时 `continuePrompt=""`。

### FR-4 撤回与冲突检测

`ChangeSetService` 提供：

- `RevertResult revertSingle(Long id, Long userId)`：文件审查卡片单条撤回。
- `RevertResult revertTurn(Long sessionId, String turnId, Long userId)`：按轮次撤回。
- `RevertResult revertRun(Long sessionId, Long runId, Long userId)`：解析 Run 的 turnId 后按轮次撤回。
- `RevertResult revertSession(Long sessionId, Long userId)`：任务级整体撤回。
- `RevertResult revertProject(Long projectId, Long userId)`：兼容既有项目级批量撤回。

`RevertResult`：

```text
ok, count, skippedAccepted, conflicts[{id, filePath, reason}]
```

规则：

1. 只回滚 `status=PENDING` 的变更；`ACCEPTED/REVERTED` 计入 `skippedAccepted`/忽略，保留审计。
2. 排序：`createTime DESC, id DESC`，保证同一文件多次修改按发布时间逆序恢复。
3. 每个变更执行 `revertOne(PendingFileChange)`：
   - 解析项目工作区根，`workspaceFileService.resolveInWorkspace` 防越界。
   - `oldContent == null`（新建）：
     - 文件不存在 → 视为已移除，标 `REVERTED`；
     - 文件内容与 `newContent` 不一致 → 冲突「文件在 Agent 新建后被外部修改」；
     - 一致 → 删除文件并标 `REVERTED`。
   - `oldContent != null`（修改/删除）：
     - 文件不存在且 `action=delete` → 恢复 `oldContent`；
     - 文件不存在且 `action≠delete` → 冲突「文件在 Agent 修改后被外部删除」；
     - 内容与 `newContent` 不一致 → 冲突「文件在 Agent 修改后被外部改动」；
     - 一致 → 创建父目录并写回 `oldContent`，标 `REVERTED`。
   - 内容比较忽略 `\r\n` 与 `\n` 差异，避免 Windows 换行误报；写回使用 UTF-8。
4. 冲突项不修改文件、不修改状态，返回 `conflicts`；批量继续处理其余项。
5. 撤回文件 I/O 异常计入 `failed` 语义（`ok=false` 若全部失败），不抛出到控制器。

### FR-5 既有接口收敛

文件：`src/main/java/com/capsule/agentkey/controller/FileReviewController.java`

- 注入 `ChangeSetService`。
- `POST /{id}/revert` → `changeSetService.revertSingle(id, userId)`；保持原响应 `{ok,error}`，冲突时 `ok=false, error=冲突原因`。
- `POST /revert-all?projectId` → `changeSetService.revertProject(projectId, userId)`，返回 `{ok,count,failed,conflicts}`。
- `POST /revert-turn?sessionId&turnId` → `changeSetService.revertTurn(...)`。
- 删除本地 `revertOnDisk`，避免两套覆盖写盘逻辑。
- 列表与 accept/accept-all 行为不变；接受只改 `PendingFileChange.status`。

### FR-6 Repository 扩展

- `AgentRunRepository`：
  - `List<AgentRun> findByUserIdAndSessionIdOrderByCreateTimeDescIdDesc(Long userId, Long sessionId);`
- `PendingFileChangeRepository`：
  - `List<PendingFileChange> findBySessionIdOrderByCreateTimeDescIdDesc(Long sessionId);`
  - `List<PendingFileChange> findBySessionIdAndStatusOrderByCreateTimeDescIdDesc(Long sessionId, String status);`
- 保留既有方法，避免破坏现有调用。

### FR-7 前端类型与 API

- `frontend/src/types/api.ts`：
  - 新增 `ChangeSetStatus`、`ChangeSetVerification`、`ChangeSetApprovalItem`、`ChangeSetRun`、`ChangeSetChange`、`ChangeSetView`、`RevertResult`。
  - `PendingFileChange.status` 保持现有联合类型。
- `frontend/src/api/fileChanges.ts`：
  - `fetchChangeSet(sessionId, runId?)` → `GET /api/file-changes/changeset`
  - `revertSession(sessionId)` → `POST /api/file-changes/revert-session`
  - `revertRun(sessionId, runId)` → `POST /api/file-changes/revert-run`

### FR-8 前端 store

文件：`frontend/src/stores/chat.ts`

- state：`changeSet: ChangeSetView | null`、`changeSetLoading`、`changeSetRunFilter: number | null`。
- actions：
  - `fetchChangeSet()`：无 `currentSessionId` 时置空；有则带 `changeSetRunFilter` 请求，失败置空并保留 `pendingChanges` 原有降级。
  - `setChangeSetRunFilter(runId | null)`：设置后重新拉取。
  - `revertTask()`：`revertSession`，成功后提示计数与冲突数，刷新 `fetchChangeSet` 与 `fetchPendingChanges`。
  - `revertRunChanges(runId)`：`revertRun`，同上。
  - `continueFix()`：取 `changeSet.continuePrompt`，为空提示；否则调用现有 `sendMessage(prompt)`，沿用当前 Session。
  - `refreshChanges()`：串行刷新 `fetchPendingChanges` + `fetchChangeSet`。
- 触发点：
  - `selectSession` / `loadSessionMessages` 完成后；
  - `startNewSession` 时清空；
  - 现有生成结束刷新 `fetchPendingChanges` 的位置改为 `refreshChanges`；
  - `acceptChange/acceptAll/revertChange/revertTurn` 成功后刷新 ChangeSet。

### FR-9 前端交付看板

文件：`frontend/src/components/PendingChangesCard.vue`

- 保持现有折叠卡片形态与文件 diff 能力，升级为任务交付卡：
  - 头部：状态 chip（按 `changeSet.status` 配色，只用 `tokens.css` 变量）+「N 个文件改动」+ 增删行合计 + 验证/未验证摘要。
  - Run 过滤：有多个 Run 时显示「全部 / #runId」chip，点击 `setChangeSetRunFilter`；卡片数据随之过滤。
  - 验证摘要：显示 `verification.label` 与 message；失败时展开失败命令与 `outputTail`（只读）。
  - 审批摘要：`requested/resolved` 与未解析项提示；无审批时省略。
  - 文件列表：按 Run 分组（`changeSet.runs` 的 `turnId`），保留路径、+/-、diff 展开、编辑器打开、单条保留/撤销。
  - 每个 Run 组头提供「撤回此轮」；发起前二次确认。
  - 底部：「继续修复」（仅 `continuePrompt` 非空）、「整体撤回」（确认文案说明按逆序恢复且外部修改会冲突）、「全部保留」。
  - 未验证任务在头部与验证区明确显示「未验证」；接受后仍显示未验证，不出现「验证通过」。
- 兼容：`chat.changeSet == null` 时退回现有 `chat.pendingChanges` 列表展示，避免旧会话空白。

### FR-10 ChatView 接入

文件：`frontend/src/views/ChatView.vue`

- `PendingChangesCard` 现有渲染位置不变。
- `hasLocalWorkspace` 且已有 `currentSessionId` 时，onMounted 触发 `chat.fetchChangeSet()`；切换会话由 store 统一触发。

## Non-Functional Requirements

- **NFR-1 安全**：所有新接口校验 Session/Project 归属；路径仍经 `WorkspaceFileService.resolveInWorkspace`。
- **NFR-2 不覆盖用户内容**：任何外部修改都返回冲突，绝不静默覆盖。
- **NFR-3 有界**：单次 View 最多解析 200 条变更、50 条 Run；continuePrompt 输出截断；diff 继续由现有字段提供。
- **NFR-4 可回放**：视图只依赖 DB 与事件，刷新后一致；不依赖内存状态。
- **NFR-5 无新依赖**：后端只用 Jackson 与现有仓储；前端不新增 npm 包。
- **NFR-6 不阻塞**：解析单个 Run 的事件失败时降级为空摘要，不影响整个 ChangeSet。

## Constraints

- Java 21 / Spring Boot 4.1.2；Vue 3 + TypeScript。
- 不新增 Flyway 迁移。
- 不修改 `PendingFileChange` 表结构。
- 不破坏既有 `/api/file-changes` 列表、accept、revert 响应语义。
- 颜色只用 `tokens.css` 变量，不新增硬编码 hex。

## Assumptions

- 新 Run 的 `requestJson` 包含 `turnId`；历史 Run 缺失时允许 `runId=null`，按 turnId 无法归属的变更仍展示。
- 一个 Run 恰好对应一个 turnId。
- 验证与诊断结果以 Run Event 为准；消息 trace 缺失不影响任务视图。
- 用户接受变更即确认交付，但不等于验证通过。

## Acceptance Criteria

### AC-1 多 Run 统一视图
- **Given** 同一 Session 有两次 Run，均有文件变更与 VERIFY 事件
- **When** 请求 `GET /api/file-changes/changeset?sessionId=...`
- **Then** 返回 2 个 Run、合并文件与增删行、每个变更带 `runId/turnId`、`verification` 取最近候选 Run、状态正确。
- **Given** 指定 `runId`
- **Then** 只返回该 Run 与其变更，totals 与 status 基于过滤结果。

### AC-2 继续修复保留上下文
- **Given** 最近 Run 验证失败或诊断有 ERROR
- **When** 获取 ChangeSet
- **Then** `continuePrompt` 含原任务提示、失败命令/输出、未完成计划，且前端「继续修复」在**当前 Session** 调用 `sendMessage`，不新建会话。

### AC-3 整体撤回与按 Run 撤回
- **Given** 同一文件在 Run1/Run2 依次由 A→B→C，磁盘为 C
- **When** 整体撤回
- **Then** 按 Run2→Run1 逆序恢复为 A，两个变更状态均为 `REVERTED`；`count=2`。
- **Given** 只撤回 Run2
- **Then** 文件恢复为 B，Run1 变更保持原状态。

### AC-4 外部修改冲突
- **Given** 变更 `newContent=C`，磁盘被外部改为 D
- **When** 撤回
- **Then** 文件保持 D，变更仍为 `PENDING`，响应 `conflicts` 含该文件与原因。

### AC-5 接受不覆盖未验证
- **Given** 验证状态为 `NOT_CONFIGURED` 或 `UNAVAILABLE`
- **When** 调用 accept / accept-all
- **Then** ChangeSet `verification.status` 不变，任务状态仍为 `UNVERIFIED`，仅 `changes.status=ACCEPTED` 与 `accepted=true`。

### AC-6 已接受变更保留审计
- **Given** 一个变更已 `ACCEPTED`
- **When** 整体撤回
- **Then** 该文件不被回滚，`skippedAccepted` 计数增加，变更记录仍可查询。

### AC-7 刷新恢复
- **Given** 用户刷新页面
- **When** 重新加载 Session
- **Then** ChangeSet 由 DB 重建，展示相同的 Run、变更、验证与状态。

### AC-8 构建与回归
- 后端 `mvn -o -q compile`、`mvn -o -q test` 通过；`EvalGate,RuntimeEvalGate` 继续通过。
- 前端 `vue-tsc --noEmit` 通过。
- 既有文件审查、审批、Verify、Diagnostics 用例无回归。

## Verification Plan

1. `ChangeSetServiceTest`：
   - 两次 Run 聚合、按 runId 过滤、totals；
   - 未完成计划与失败命令进入 `continuePrompt`；
   - 接受后 verification 不变；
   - 整体撤回逆序（同文件 A→B→C 恢复 A）；
   - 按 Run 撤回只影响目标 turn；
   - 外部修改冲突不覆盖；
   - 已接受变更跳过并保留审计。
2. `mvn -o -q compile`、`mvn -o -q test`、`mvn -o -q test -Dtest=EvalGate,RuntimeEvalGate`。
3. 前端 `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`。
4. 同步 `docs/UPDATE_PLAN.md`、`docs/phases/phase5/plan.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`。

## Required Approval

实施前需用户确认本规格。确认后按 `tasks.md` 顺序执行；若实现中发现与现有契约冲突，先记录并调整规格，不静默扩大范围。
