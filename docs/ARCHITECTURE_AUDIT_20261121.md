# 金月（agent-gateway-v3）架构审计报告

> **时点快照**：本报告写于 2026-11-21，用于驱动 R0～R7 分阶段重构；其中 P0/P1 问题已由 R1～R6 修复，R7 仍在进行。当前实现以 [architecture/README.md](architecture/README.md) 为准，未完成项以 [UPDATE_PLAN.md](UPDATE_PLAN.md) 第 14 节为准。

> 审计日期：2026-11-21
> 审计范围：后端 Agent 核心、工具层与运行时状态、持久化、前端、测试/评测/构建
> 方法：5 路只读代码审计（文件级 + 行号级证据），不做修改
> 结论用途：支撑一次分阶段大重构，替代“逐点打补丁”的演进方式

---

## 0. 结论摘要

金月当前**功能完整、测试面较广，但核心结构已经到临界点**：

- `ReActAgentService` **1498 行**，同时承担循环控制、策略、上下文组装、工具调度、停滞检测、计划收口、流式推送、持久化 8 类职责。
- `WorkspaceContext` 以 ThreadLocal 承载 **13 个字段**，把传输参数、领域状态、UI 推送标记、统计混在一起。
- **跨 turn 没有权威状态源**：plan 只在 ThreadLocal，这是 P7-04c 回滚的根因，也是 P7-07/Phase 8 续跑的直接障碍。
- `agent_run_event.seq` 采用“先查 max 再 +1”，**并发写同一 run 会撞唯一约束**，事件溯源地基不牢。
- 审批与 scope 存在**绕过点**（只读批、`ask_user_question`）。
- 前端 `useChatStore` 过大，断线恢复只覆盖部分事件类型；长列表虚拟滚动已有但恢复链路不完整。
- 测试约 400 项，但 **ReAct 主循环缺少可重复的 scripted 长会话回归**；评测 n=3，统计置信度不足。

**判断**：继续在现有结构上加 P7-07/08/09 会扩大债务。建议先完成“状态模型 + 接缝抽取”的地基重构（R1/R2），再把 P7 三项作为接缝上的实现落地，最后做前端与 Phase 8。

---

## 1. 系统全景

### 1.1 后端分层

```text
controller/            HTTP + SSE 入口
  └─ service/          Agent 编排、运行状态机、策略、记忆、评测
       ├─ ReActAgentService        主循环（god class）
       ├─ AgentPolicyResolver      策略归一化
       ├─ AgentRunService          运行状态机 + 事件持久化
       ├─ GenerationRegistry       取消标记登记
       └─ SessionMemory*           摘要 / 事实抽取
  └─ client/           Spring AI 封装（streamChat / fallback / 重试）
  └─ tool/             BaseTool + ToolRegistry + WorkspaceContext + 各工具
  └─ mcp/              MCP 桥接
  └─ entity/repository  JPA + Flyway(V1~V21)
```

### 1.2 运行时主链路

```text
POST /api/agent/chat/stream
  → Controller
  → ReActAgentService.execute
      → WorkspaceContext.set(userId, projectId, cancelled)
      → buildMessages(system + rules + RAG + file + memory summary)
      → loop:
          SpringAiAgentClient.streamChat
          → 模型 chunk / tool_calls
          → 只读批并发 or 单工具 dispatch
          → ToolRegistry.dispatch → BaseTool.execute
          → 审批 / scope 检查
          → 追加 tool result，检测停滞
      → finalizePlan
  → SSE events（chunk / tool / plan / user_question / completion）
  → AgentRunService 状态迁移 + agent_run_event 追加
```

### 1.3 前端链路

```text
ChatView.vue
  → useChatStore (Pinia)
      → api/sse.ts streamChat (fetch + ReadableStream 手写 SSE)
      → applyRunEvents 折叠历史事件
      → recoverRun 轮询 getRunEvents 断线续传
  → AskUserCard / 计划条 / 审批队列 / 文件变更
```

---

## 2. 跨层核心问题（按优先级）

### P0（必须在重构第一阶段解决）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| P0-1 | `ReActAgentService` god class，8 类职责耦合 | `service/ReActAgentService.java`（1498 行） | 任何改动都高风险，无法并行开发 |
| P0-2 | `WorkspaceContext` ThreadLocal 状态混杂 | `tool/WorkspaceContext.java`（187 行，13 字段） | 隐式共享、跨 turn 泄漏、测试困难 |
| P0-3 | 跨 turn 无权威状态源（plan/scope/progress） | `WorkspaceContext.java:33-43`；P7-04c 回滚记录 | 计划丢失、续跑不可行、P7-07 无法干净落地 |
| P0-4 | `agent_run_event.seq` 先查后写，非原子 | `service/AgentRunService.java:422-430` | 并发写撞唯一约束，事件溯源不可信 |
| P0-5 | 审批 / scope 存在绕过点 | `ReActAgentService.java:629-673`；`ScopeConstraintService.java:36-39` | 权限语义不闭合，安全与一致性风险 |

### P1（第二阶段）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| P1-1 | `useChatStore` 过大，恢复只覆盖部分事件 | `frontend/src/stores/chat.ts:302-377`、`705-740` | 前端状态难维护，断线后 UI 不一致 |
| P1-2 | 核心循环缺 scripted 长会话回归 | `src/test/java/.../LongSessionLiveRunner.java` | 重构缺少可重复安全网 |
| P1-3 | 评测 n=3，统计脆弱 | `docs/COMPLETED_WORK.md:588-610` | 无法证明“修好了” |
| P1-4 | 错误归因分散（errorCode 各写各的） | `ReActAgentService.java:919-924` | 失败难定位、指标不可比 |

### P2（第三阶段 / 持续）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| P2-1 | JSON 存 TEXT，无结构约束 | `AgentRun.java:57-71`、`AgentRunEvent.java:38-40` | 演进与查询受限 |
| P2-2 | `recoverAfterRestart` 直接标 FAILED | `AgentRunService.java:82-101` | 无法断点续跑 |
| P2-3 | 事件表无归档策略 | `V16__add_agent_run.sql:46-57` | 长会话数据膨胀 |

---

## 3. 分层详审

### 3.1 后端 Agent 核心

**职责分布（当前）**

| 职责 | 位置 | 问题 |
|---|---|---|
| 循环控制 | `execute` 主循环（约 L323-529） | 与流式、工具、停滞、预算混在一起 |
| 策略解析 | `AgentPolicyResolver` | 较清晰，可保留 |
| 上下文组装 | `buildMessages`（约 L1221+） | 与 system prompt、RAG、memory 强耦合 |
| 工具调度 | 主循环内 `streamedToolCalls` 分支（约 L599-673） | 与审批、scope、并发批处理混杂 |
| 停滞检测 | 5 处 stop 分支（约 L548/L617/L747/L776/L845） | 分散、文案内联、无恢复策略 |
| 计划收口 | `finalizePlan`（约 L1198-1219） | 只写 ThreadLocal，不持久化 |
| 流式推送 | `StreamCallback` 全链路透传 | 推送与业务逻辑耦合 |
| 持久化 | 直接调用 `AgentRunService` / repository | 领域层依赖基础设施 |

**最纠缠的 3 处**
1. `execute` 主循环：流式 + 工具 + 验证 + 停滞 + 预算 + callback。
2. `WorkspaceContext`：既是传输通道，又是领域状态仓库。
3. Spring AI 客户端与工具循环的边界：`streamChat` 内部处理 memory/重试，ReAct 又处理工具/审批，职责交叉。

**目标接缝**
- `AgentOrchestrator`：只管循环推进与终止条件。
- `ToolExecutor`：权限 → scope → 审批 → 执行 → 指标 → 文件变更。
- `LoopGuard`：停滞检测 + 渐进式恢复（P7-08）。
- `ContextAssembler`：system + rules + RAG + memory + plan 快照（P7-07）。
- `StreamPublisher`：SSE 事件出口，可替换/可测试。
- `RunStateService`：状态机 + 事件写入，事务与并发序内聚。

### 3.2 工具层与运行时状态

**关键事实**
- 只读批并发只在 `ToolRegistry.isReadOnly()` 且非 fallback、非 MCP 时生效；MCP/fallback 即使只读也串行。
- `WorkspaceContext.child()` 共享 `scope/toolStats/cancelled`，文件变更缓冲各自独立。
- 工具统计在有上下文时走共享 map，否则退化为线程局部。

**坏味道**

| 问题 | 证据 | 严重度 |
|---|---|---|
| `WorkspaceContext` 职责混杂 | `WorkspaceContext.java:20-50` | 高 |
| 工具契约不统一（返回/异常/校验各写各的） | `ToolRegistry.java:194-224`；`WriteFileTool.java:68-77` | 中 |
| 只读批绕过审批与 `dispatchWithScopeCheck` | `ReActAgentService.java:629-673` | 高 |
| scope 只覆盖文件类工具，`run_command` 不拦截 | `ScopeConstraintService.java:36-39` | 中 |
| 文件变更依赖 ThreadLocal，上下文丢失即丢变更 | `WorkspaceContext.java:106-124` | 中 |

**目标接口**
- `ToolContract`：统一 `name/description/schema/execute` 与错误语义。
- `ToolContext`：只含执行所需参数、工作区、权限、scope、取消。
- `ToolExecutor`：唯一执行入口，审批与 scope 不可绕过。
- `ApprovalGate`：`create/await/resolve`。
- `FileChangeService`：变更收集、去重、落库、推送。
- `SessionPermissionTracker`：会话权限单一来源。

### 3.3 持久化与数据模型

**关键事实**
- `AgentRun` 是运行聚合根，`AgentRunEvent` 是事件流（`runId + seq` 唯一）。
- `seq` 由应用层 `findMaxSeqByRunId + 1` 生成，并发不安全。
- 重启恢复把所有非终态直接标 `FAILED`，不做重放。
- 多个 JSON 字段用 `TEXT` 存储，无结构约束。

**坏味道**

| 问题 | 证据 | 严重度 |
|---|---|---|
| `seq` 并发唯一性风险 | `AgentRunService.java:422-430` | 高 |
| 状态迁移与事件写入的原子性依赖单事务，但 seq 非原子 | `AgentRunService.java:395-430` | 高 |
| 重启恢复直接 FAILED | `AgentRunService.java:82-101` | 中 |
| `TEXT` 存 JSON 无约束 | `AgentRun.java:57-71`、`AgentRunEvent.java:38-40` | 中 |
| 事件表无归档策略 | `V16__add_agent_run.sql:46-57` | 中 |

**目标**
- `seq` 改为数据库序列或 `INSERT ... SELECT COALESCE(MAX(seq),0)+1` 原子写，且加唯一约束兜底。
- `AgentRun` 保持状态唯一来源；新增 `plan_json` 作为**投影/快照**，事件流为唯一事实源。
- 明确“可重放事件”与“仅日志事件”的边界，为 Phase 8 续跑做准备。

### 3.4 前端

**关键事实**
- `api/sse.ts` 手写 SSE 解析，事件分发覆盖 `user_question/file_change/completion/diagnostics/verify/usage/done/error`。
- `stores/chat.ts` 同时管消息、计划、流状态、恢复、文件变更、审批、决策询问。
- `recoverRun()` 只恢复正文/工具/用户问题，`file_change`、`plan`、审批状态主要依赖实时流。

**坏味道**

| 问题 | 证据 | 严重度 |
|---|---|---|
| `useChatStore` 状态过多 | `stores/chat.ts:302-377` | 高 |
| `applyRunEvents` 职责过宽 | `stores/chat.ts:68-127` | 高 |
| 恢复链路覆盖不全 | `stores/chat.ts:705-740` | 中 |
| `ChatView` 承载过多 UI 状态 | `views/ChatView.vue:7-123` | 中 |
| SSE 解析与分发耦合在一个文件 | `api/sse.ts:143-269` | 中 |

**目标**
- `SSEService`：协议解析 + 重连 + 事件归一化。
- `useChatStream`：事件 → 状态域。
- store 拆分：`messages` / `stream` / `recovery` / `questions` / `approvals` / `fileChanges`。
- `useRecovery`：独立恢复生命周期与 `recoveryToken`。

### 3.5 测试 / 评测 / 构建

**关键事实**
- 后端约 400 项测试，覆盖工具、审批、scope、恢复、计划、repo map、诊断、验证。
- `LongSessionLiveRunner` 使用真实模型、真实 `ReActAgentService`、真实工具与状态机。
- 评测 L1/L2/L3 各 ×3，成功需同时满足工件与 coverage。
- CI：后端 `verify` + EvalGate/RuntimeEvalGate；前端 tsc/build/Vitest/Playwright。

**风险**

| 问题 | 证据 | 严重度 |
|---|---|---|
| 核心循环缺可重复 scripted 回归 | `LongSessionLiveRunner.java:114-159` | 高 |
| `WorkspaceContext` 跨 turn 状态无测试兜底 | `docs/COMPLETED_WORK.md:623-641` | 高 |
| 评测 n=3 | `docs/COMPLETED_WORK.md:588-610` | 中 |
| 真实模型评测依赖 API Key/网络 | `LongSessionLiveRunner.java:93-129` | 中 |
| CI 长会话门禁可能受超时影响 | `.github/workflows/ci.yml:14-40` | 低 |

**目标**
- 新增 `ScriptedAgentLoopTest`：用固定模型响应脚本驱动主循环，覆盖 plan/停滞/审批/取消/续跑。
- 评测扩到 n≥10，支持录制回放（recorded responses）降成本。
- CI 必过：后端 verify + 前端 build/Vitest；真实模型评测作为夜间/可选门禁。

---

## 4. 目标架构

### 4.1 后端包结构（目标）

```text
agent/
  orchestration/   AgentOrchestrator, LoopGuard, Budget, StopReason
  context/         ContextAssembler, PlanSnapshotProvider, MemoryPort
  tool/            ToolContract, ToolContext, ToolExecutor, ApprovalGate, FileChangeService
  stream/          StreamPublisher, SseEventMapper
  run/             RunStateService, RunContext, RunEventStore
tool/              各具体工具（只实现 ToolContract）
mcp/               MCP 适配器
```

**依赖方向**：`controller → agent.orchestration → agent.context/tool/stream/run → tool/mcp`。基础设施不反向依赖领域。

### 4.2 状态归属矩阵

| 状态 | 生命周期 | 归属 | 持久化 |
|---|---|---|---|
| userId/projectId/cancelled | 单次调用 | `ToolContext` / 调用参数 | 否 |
| runId/sessionId/turnId | 单次 run | `RunContext` | run 表 |
| plan/scope | 跨 turn | `RunContext` | `plan_json` + `PLAN_UPDATED` 事件 |
| diagnostics/verify | 跨 tool 批次 | `RunContext` | 事件（可选快照） |
| 停滞计数 | 单 turn | `LoopGuard` | 否（必要时事件） |
| 文件变更 | 单 turn | `FileChangeService` | `pending_file_change` |
| 审批 | 跨线程 | `ApprovalGate` | `tool_approval` |
| SSE 连接 | 单连接 | `StreamPublisher` | 否 |

### 4.3 数据模型调整

- `agent_run` 增加 `plan_json TEXT`（快照投影，非事实源）。
- `agent_run_event.seq` 改为数据库原子生成（序列或原子 INSERT）。
- 事件类型补齐：`PLAN_UPDATED`、`NUDGE`、`COMPACTION`、`CHECKPOINT`。
- 为 Phase 8 预留 `checkpoint_seq` / `resume_from_seq`（可先不加列，用事件表达）。

### 4.4 前端目标分层

```text
api/          sse.ts（协议） + agent.ts（REST）
services/     SSEService（重连/归一化）
stores/       messages / stream / recovery / questions / approvals / fileChanges
composables/  useChatStream / useRecovery / useVirtualList
views/        ChatView（只负责布局与挂载）
```

---

## 5. 重构路线图

> 原则：每个批次**行为保持**或**可独立回滚**；每批先补测试再动代码；不一次性大爆炸。

| 批次 | 内容 | 出口（必须同时满足） | 回滚 |
|---|---|---|---|
| **R0 安全网** | `ScriptedAgentLoopTest`；`RunContext` 契约测试；seq 并发测试 | 覆盖 plan/停滞/审批/取消/恢复；全量测试绿 | 只增测试，无风险 |
| **R1 状态地基** | `RunContext` + `RunEventStore`；seq 原子化；plan 持久化 | L2 回注 A/B 达标；并发写无冲突 | 保留旧路径开关 |
| **R2 Context 瘦身** | `WorkspaceContext` 只留传输；领域状态迁 `RunContext` | 全量测试绿；跨 turn 计划可见 | 兼容访问器 |
| **R3 工具接缝** | `ToolExecutor` + `ApprovalGate` + `FileChangeService` | 审批/scope 无绕过；工具测试全绿 | 双执行器开关 |
| **R4 编排接缝** | `AgentOrchestrator` + `LoopGuard` + `StreamPublisher` | 主循环行数下降；L1 停滞恢复达标 | 保留旧 execute |
| **R5 上下文接缝** | `ContextAssembler` + `ContextCondenser`；落地 P7-07/09 | L2/L3 A/B 达标；token 峰值下降 | 压缩可关 |
| **R6 前端拆分** | SSEService + store 拆分 + 恢复补全 | 前端测试绿；断线恢复覆盖 plan/file_change | 逐 store 迁移 |
| **R7 长任务能力** | Phase 8：续跑、完成强校验、大任务分解、评测扩容 | L1~L8 验收标准达标 | 按项开关 |

**与既有规划的关系**
- P7-07 并入 R1（持久化）+ R5（注入）。
- P7-08 并入 R4（LoopGuard）。
- P7-09 并入 R5（ContextCondenser）。
- Phase 8（P8-01~05）并入 R7。
- 不再单独按 P7-07/08/09 排期，避免双重追踪。

---

## 6. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| 重构导致 ReAct 行为漂移 | 高 | R0 scripted 回归 + 每批 A/B |
| ThreadLocal 迁移遗漏 | 高 | 兼容访问器 + 全量测试 + 逐字段迁移 |
| seq 原子化改动影响事件重放 | 中 | 先加唯一约束与并发测试，再切生成方式 |
| 前端 store 拆分引入状态不同步 | 中 | 逐域迁移，保留 facade |
| 评测扩容成本上升 | 中 | 录制回放 + 夜间任务 |
| 一次改太多无法归因 | 高 | 严格按 R0→R7 顺序，每批独立 commit/PR |

---

## 7. 验收标准（与 Phase 8 对齐）

重构完成后，除 L1~L8 长任务标准外，架构层需满足：

1. `ReActAgentService`（或 `AgentOrchestrator`）单类不超过 600 行，职责单一。
2. `WorkspaceContext` 不超过 80 行，只含传输参数。
3. 所有工具调用必经 `ToolExecutor`，审批/scope 无绕过路径（有测试证明）。
4. `agent_run_event.seq` 并发测试通过（N 线程写同一 run 无冲突）。
5. 前端单 store 不超过 400 行；断线恢复覆盖 plan / file_change / approval / user_question。
6. 核心链路有 scripted 回归，不依赖真实模型即可验证行为。
7. 全量后端测试 + 前端 Vitest/Playwright 绿。
