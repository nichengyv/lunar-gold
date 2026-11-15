# Lunar Gold 总架构文档（已完成）

> 更新日期：2026-11-14
> 状态：按当前代码事实整理，覆盖已落地模块与明确未完成项
> 关联：[架构设计取舍](../design.md)、[当前状态](../STATUS.md)、[更新规划](../UPDATE_PLAN.md)、[已完成工作](../COMPLETED_WORK.md)
> 用途：面试讲解、快速定位代码、后续开发前建立全局认知。本文只描述现状，不承诺未实现能力。

---

## 0. 一句话定位

Lunar Gold（金月）是**本地优先、可控、可验证的 AI 编程工作台**：

- **桌面客户端**：Electron 壳 + 内嵌后端 + 内嵌 PostgreSQL，本地读写项目、执行命令、审批与验证。
- **网页端**：账号、对话、知识库 RAG、下载页，不操作本地工作区。
- **Agent Runtime**：内部执行层，负责模型调用、工具分发、审批、状态与完成判定，**不是独立产品类型**。

技术栈：

| 层 | 技术 |
|---|---|
| 桌面壳 | Electron 43（`desktop/`） |
| 后端 | Spring Boot 4 / Java 21（`src/main/java/com/capsule/agentkey`） |
| 前端 | Vue 3 + TypeScript + Vite + Pinia（`frontend/`） |
| 数据 | PostgreSQL + pgvector（桌面端内嵌运行时） |
| 构建 | Maven、npm、electron-builder、NSIS |

---

## 1. 系统全景

```text
┌─ Electron 桌面壳 (desktop/) ──────────────────────────────────┐
│ main.js        单实例锁、窗口、启动编排、崩溃标记               │
│ sidecar.js     启动后端 jar、分配端口、健康检查、终止进程树     │
│ updater.js     检查/下载/校验/静默安装/失败回滚                 │
│ diagnostics.js 白名单诊断报告与 ZIP 导出                       │
└───────────────────────────┬───────────────────────────────────┘
                            │ HTTP + SSE（127.0.0.1 + 本机令牌）
┌───────────────────────────▼───────────────────────────────────┐
│ Spring Boot 后端 (src/main/java/com/capsule/agentkey)          │
│                                                               │
│ controller  14 个控制器：对话/文档/工作区/审批/设置/管理        │
│ service     业务编排：ReAct、会话、RAG、策略、运行、审批        │
│ tool        13 个内置工具 + ToolRegistry                       │
│ mcp         MCP 工具桥接、全局开关、健康熔断                    │
│ entity/repository  JPA 实体与数据访问                          │
│ config/filter/interceptor  安全、能力门控、部署模式             │
└──────────┬───────────────────────────────┬────────────────────┘
           │ JDBC                          │ HTTPS（用户 BYOK）
   PostgreSQL + pgvector                模型服务商
           ▲
┌──────────┴────────────────────────────────────────────────────┐
│ Vue 3 SPA (frontend/src)                                       │
│ views 7 个页面 / stores 5 个 Pinia 域 / api 手写 SSE 客户端     │
└───────────────────────────────────────────────────────────────┘
```

---

## 2. 模块索引

| 编号 | 模块 | 职责 | 关键入口 | 文档 |
|---|---|---|---|---|
| 01 | Agent Runtime | ReAct 循环、工具分发、完成判定、无进展检测 | `ReActAgentService` | [01-agent-runtime.md](01-agent-runtime.md) |
| 02 | 模型接入 | 客户端抽象、流式调用、超时重试熔断、token 计量 | `AgentClient`、`SpringAiAgentClient` | [02-model-gateway.md](02-model-gateway.md) |
| 03 | 工具与审批 | 内置工具、审批注册表、命令/文件沙箱、MCP | `ToolRegistry`、`ToolApprovalRegistry` | [03-tools-approval-sandbox.md](03-tools-approval-sandbox.md) |
| 04 | 上下文与 RAG | 文档入库、向量检索、上下文组装、提示词版本化 | `DocumentService`、`PromptCatalog` | [04-context-rag.md](04-context-rag.md) |
| 05 | API 与事件流 | 控制器、SSE 协议、断线续传、请求鉴权 | `AgentChatController` | [05-api-sse.md](05-api-sse.md) |
| 06 | 前端工作台 | 页面、状态管理、SSE 消费、任务恢复与展示 | `frontend/src` | [06-frontend.md](06-frontend.md) |
| 07 | 桌面端与部署 | 壳、侧车、内嵌数据库、打包、更新回滚 | `desktop/`、`application-desktop.yml` | [07-desktop-deployment.md](07-desktop-deployment.md) |
| 08 | 数据与持久化 | 迁移、实体关系、Run/Event/审批存储 | `db/migration`、`entity/` | [08-data-persistence.md](08-data-persistence.md) |
| 09 | 安全与沙箱 | 认证授权、跨租户隔离、文件/命令/MCP 约束 | `SecurityConfig`、`CommandService` | [09-security.md](09-security.md) |
| 10 | 质量与评测 | 单测、组件评测、Runtime 评测、基线与双门禁 | `src/test/.../eval`、`eval/` | [10-quality-evaluation.md](10-quality-evaluation.md) |

---

## 3. 一次任务的生命周期

以桌面端“修改一个文件”为例：

1. 前端 `ChatView` 发送 `POST /api/agent/chat/stream`，携带会话、项目、模型策略与 `clientRequestId`。
2. `AgentChatController` 校验会话归属、解析策略（`AgentPolicyResolver`），创建 `AgentRun` 并写 `RUNNING`。
3. `ReActAgentService.execute` 组装系统提示词（`PromptCatalog` 按 agent/coding 场景选择版本）、历史、RAG、项目指令、工作区树与权限说明。
4. 通过 `AgentClient` 流式请求模型；文本增量通过 SSE `content` 推送。
5. 模型返回 `tool_calls` 时，`ToolRegistry` 分发到 `ReadFileTool` / `WriteFileTool` 等：
   - P5-08：连续的只读调用（`BaseTool.readOnly()`）进入有界线程池并发执行，结果按原下标回放；写/命令、MCP、fallback 与需审批工具保持串行；
   - 写操作先经 `ToolApprovalRegistry` 请求用户审批，SSE 推送审批信息；用户决定写入数据库；
   - 工具执行结果回灌为 tool 消息，进入下一轮。
6. 循环停止条件：模型不再调用工具、达到 `maxToolRounds`、token 预算耗尽、时间预算耗尽（`agent.budget.max-seconds-per-run`，默认不限）、用户取消、或触发无进展检测。
7. 停止后计算 `Completion`：
   - 文件变了但没有诊断/verify 证据 → `UNVERIFIED`（前端显示“未验证”）；
   - 计划未完成、审批被拒、写入失败、轮次耗尽、无进展、取消 → `INCOMPLETE`；
   - 无文件修改且无未完成项 → `COMPLETED`。
8. `finalizePlan` 收口计划（未完成步骤标记 `aborted`），发送 `completion` 与 `done`，Run 落 `SUCCEEDED` 或 `CANCELLED`，并写 `resultJson` 与 token 统计。
9. 前端可从实时 SSE、历史消息 trace、`GET /api/agent/runs/{runId}/events` 三条路径恢复同一状态。

> 关键语义：`Run=SUCCEEDED` 只表示执行链正常收尾，不表示任务完成；完成与否看 `CompletionStatus`。

**P5-10-3 长任务可感知**：前端把实时事件折叠为五阶段时间线（理解→读取→修改→验证→汇总），未到达的中间阶段标「跳过」，当前阶段耗时每秒刷新；后端每轮通过 `usage` SSE 下发 `round/maxRounds/maxTokens/elapsedMs/maxElapsedMs`，前端展示预算并在任一维度 ≥80% 时警示。失败按可重试 / 需配置 / 需审批 / 需人工分级，分别给「重试这一轮」「去设置」「重新发起」「重新加载」。停止后保留内容并显示「已停止生成」，「继续」以固定续写提示发起新一轮；轮次不落库，恢复后显示「—」并用 run token 汇总兜底。

**P5-10-2 审批效率**：`ChatView` 维护当前选中审批，`J/K` 在输入态之外移动、到边界不循环，`A/D` 只作用于选中卡片；队列缩减时自动收口到相邻项。低风险批量仅覆盖 `risk=WRITE` 的 `write_file`/`edit_file`（含 MCP 同名工具），`POST /api/agent/approvals/batch` 在服务端校验用户、会话、PENDING 与白名单，任一不满足整批拒绝，只返回实际完成的 `resolvedIds`。V19 为 `tool_approval` 增加 `decision_source`（USER/BATCH/TIMEOUT/SESSION_STOP/SYSTEM；旧记录 NULL 显示「来源未知」），`approval_resolved` SSE 与 `APPROVAL_RESOLVED` Run Event 携带 `approvalId/decision/source/batchId`；`GET /api/agent/sessions/{sessionId}/approvals` 先校验会话归属再返回不含 `arguments` 的历史，展示在「追踪」页待审批区之后。

**P5-14 任务级交付**：`POST /api/agent/chat/stream` 创建 Run 前生成 `turnId` 并写入 `AgentRun.requestJson`；`ChangeSetService` 按 Session 聚合 Run/Event、`PendingFileChange`、审批与 Verify/Diagnostics，供 `GET /api/file-changes/changeset?sessionId=&runId=` 查询。验证状态只来自 VERIFY 事件或 completion reason，用户“接受”只改文件变更状态，不会把未验证改写为验证通过。`POST /api/file-changes/revert-session` 与 `/revert-run` 按逆序回滚 PENDING 变更，写盘前比对磁盘内容与 `newContent`，外部修改返回冲突；已接受变更保留审计。

**P5-11-2 体验可观测性**：前端 `utils/experienceDiagnostics.ts` 在浏览器本地有界保存脱敏异常、官方 Web Vitals、Run 与断线恢复样本，`main.ts` 注册 Vue/window 异常入口，`chat.ts` 在真实 runId 和恢复状态点采样。设置页 `ExperienceDiagnosticsPanel.vue` 提供开关、清除和手动 JSON 导出；没有自动上传或客户端遥测接收 API。后端 `AgentExperienceMetricsService` 通过 `GET /api/agent/metrics/experience` 按需聚合当前用户最近 100 Run/审批与进程内 userId 工具 Timer，首条 MODEL_CHUNK 时间在数据库内聚合；空样本返回 null。浏览器连接样本与后端审计窗口分开标注，不组合成全平台 SLO。

**P5-10-4 键盘与无障碍**：`Ctrl + /` 快捷键面板采用焦点陷阱（`utils/focusTrap.ts`），打开后焦点进入面板、Tab 不逃逸、关闭归还来源焦点；会话主体是原生按钮，Tab + Enter 即可切换，`aria-current` 标记当前会话。图标按钮补 `aria-label`，审批队列与卡片具备区域语义，审批到达/清空通过 `aria-live` 播报。`theme` store 的 `reduceMotion` 默认跟随系统并可在设置页手动开启，统一写入 `html[data-motion="reduce"]`，JS 平滑滚动同步降级。E2E 对 `/chat` 与 `/settings` 跑 axe，critical 为 0。

**P5-09 视觉体系收尾**：工具运行态由前端 `startedAt` 驱动每秒耗时，结束后回落到后端 `costMs`；密度档位只切换 `--density-*` token（紧凑 24px / 舒适 32px），由 `theme` store 持久化并在设置页提供入口。`.msg-actions` 增加 `:focus-within`，键盘可达；Markdown 表格统一边框与斑马纹，引用 chip 显示段落位置。768-1023 下工作台改为右侧浮层 + 遮罩，断点统一为 `< 768`；`ErrorState` 可选导出体验诊断 JSON。`e2e/responsive.spec.ts` 覆盖 960×640、900×700、767×700 与 1280×800 四档，断言无横向滚动且输入区可见。

---

## 4. 两种部署形态

| 维度 | cloud | desktop |
|---|---|---|
| 启动方式 | 常驻服务 | Electron 拉起 sidecar jar |
| 数据库 | 外部 PostgreSQL | 内嵌 PostgreSQL + pgvector |
| Redis | 使用 | 排除 |
| 账号 | 注册/登录、邮箱验证码 | 本地单用户、本机随机令牌 |
| 能力 | 对话、知识库、下载页 | 本地工作区、命令、Git、MCP、审批、文件审查 |
| 能力门控 | `CapabilityService` + `/api/meta` | 同一套代码，按 profile 切换能力集合 |
| 平台 | 服务端 | 当前仅 Windows |

代码入口：`config/DeploymentMode.java`、`service/CapabilityService.java`、`controller/MetaController.java`。

---

## 5. 关键设计决策

| 决策 | 理由 | 代价 / 边界 |
|---|---|---|
| 保留 ReAct，不重写为多智能体或状态图 | 现有循环可扩展；没有评测证据前，换架构只增加复杂度 | 复杂任务仍依赖提示词、计划与工具质量 |
| Run 执行状态与交付状态分离 | “跑完”不等于“做完”，必须能显示未验证/未完成 | 前端与契约需同时理解两套状态 |
| 文件修改必须有验证证据才算完成 | 防止模型口头宣称成功 | P5-05/P5-06 落地前，所有文件修改只能是 `UNVERIFIED` |
| 脚本化 Runtime 评测与组件评测分离 | 组件通过率不能冒充真实任务成功率 | 仍不等同真实模型能力，需额外抽样 |
| 所有写操作与命令走审批，且默认最小权限 | 本地项目不可信，用户必须可控 | 交互成本上升，需键盘化与批量优化 |
| 文件路径与命令双重沙箱 | 防止 `..`、符号链接逃逸与命令注入 | 跨平台沙箱尚未完成（仅 Windows Job Object） |
| 提示词外置并版本化 | 可回滚、可评测、可按场景区分 | 需要维护哈希与必备段落校验 |
| 单仓双形态、不做两个前端 | 复用同一套后端与页面，降低维护成本 | 能力差异必须靠门控而非分叉 |
| ChangeSet 作为聚合视图，不新增 Task/ChangeSet 表 | Session 已能表达任务边界，复用现有变更、Run/Event、审批与验证结果，避免重复领域模型 | 历史 Run 缺少 `turnId` 时无法按 Run 归属；命令外部副作用不可回滚 |
| 长会话按帧合并 + 段落缓存 + 窗口化渲染 | 500 条消息全量渲染会产生约 9000 个 DOM 节点；合帧限制 20 次/秒、只重算变化段落、超过 200 条只渲染可视窗口，实测首屏 108ms、DOM 216 节点、60 秒流式 0 长任务 | 虚拟化下跨窗口整段拖选不可用；估算高度会让滚动条长度轻微跳变 |
| 无障碍以 axe critical + 键盘路径作为门禁 | 自动扫描只能覆盖可检测规则，配合键盘 E2E 才能防止焦点陷阱、焦点恢复与可读名称回归 | 不等同完整 WCAG 审计；serious/moderate 违规记录但不阻断 |

---

## 6. 代码地图

```text
src/main/java/com/capsule/agentkey/
  controller/    HTTP 端点（对话、文档、工作区、审批、设置、管理、ChangeSet）
  service/       业务编排（ReAct、会话、RAG、策略、运行、审批、沙箱、ChangeSet、体验指标）
  tool/          内置工具与 ToolRegistry
  mcp/           MCP 桥接、开关、健康监控
  client/        模型客户端与韧性
  entity/ repository/   持久化
  config/ filter/ interceptor/ aspect/   安全、部署模式、能力门控
  dto/ exception/ util/
src/main/resources/
  prompts/       版本化提示词与 index.json
  db/migration/  Flyway V1~V18
frontend/src/
  views/ components/ stores/ api/ types/ router/ styles/ utils/
desktop/         Electron 主进程、侧车、更新、诊断
eval/            数据集、fixture、基线与报告
scripts/         评测、打包、合规脚本
```

---

## 7. 现状与边界

**已完成并可演示**：

- 桌面端启动、首启、安装/卸载、自动更新与失败回滚。
- 对话流式输出、工具调用、审批、文件修改与按轮撤回。
- 任务级 ChangeSet：跨 Run 聚合变更/审批/验证，支持继续修复、按 Run 或整体逆序撤回与外部修改冲突检测。
- Run 状态机、事件持久化、断线续传。
- 提示词版本化、组件评测、真实 ReAct 脚本化评测与双门禁。
- 完成控制（未验证/未完成）与无进展检测。
- 前端测试基线（P5-11-1）：Vitest 覆盖 SSE、chat store 与关键组件，Playwright 在浏览器层仿真后端覆盖发送、审批、停止、续传与交付撤回，CI 有独立 job。
- 体验可观测性（P5-11-2）：本地异常/Web Vitals/Run 恢复采样、设置页诊断面板和当前用户后端体验指标聚合；手动导出白名单 JSON，不自动上传。
- 长会话性能（P5-11-3）：流式按帧合并（≤20 次/秒）、Markdown 只重算变化段落、超过 200 条窗口化渲染；`@perf` 用例实测 500 条会话首屏 108ms、消息区 DOM 216 节点、60 秒流式 0 长任务。
- 键盘与无障碍（P5-10-4）：`Ctrl + /` 面板焦点陷阱与焦点恢复、会话项 Tab + Enter 切换、图标按钮可读名称、审批队列播报、手动「减少动效」；axe 扫描 `/chat` 与 `/settings` critical 为 0。
- 视觉体系收尾（P5-09）：工具运行态实时耗时、消息密度档位、表格斑马纹与引用位置、键盘可见操作条、窄屏工作台浮层；960×640 与三档宽度 E2E 断言无横向滚动、输入区可见。

**明确未完成（不要对外宣称）**：

| 项 | 现状 |
|---|---|
| P5-05 LSP 诊断 | 已完成：编辑后采集诊断并回灌；无验证配置时完成状态保持“未验证” |
| P5-06 Verify 回路 | 已完成：修改后自动运行项目验证命令，失败回环，未验证不伪装成功 |
| P5-04 代码索引 / repo map | 基础完成：repo map（目录 + 关键符号）已注入 prompt；可插拔 CodeIndexer、符号查询与内存增量缓存已落地；持久缓存与收益量化未做 |
| P5-07 混合检索 / rerank | 基础完成：向量 + pg_trgm 词法召回、RRF 融合、chunk 级引用已落地；模型 reranker 与真实语料量化未做 |
| P5-08 并行只读工具 | 已完成：连续只读批默认 3 并发，结果顺序不变；写/命令与 MCP 仍串行审批 |
| P5-14 任务级 ChangeSet | 基础完成：Session 级聚合、按 Run 过滤、继续修复、逆序整体/按 Run 撤回与冲突检测已落地；前端交付看板已接入；关键路径 E2E 已由 P5-11-1 覆盖 |
| P5-11 体验质量基础设施 | 已完成：P5-11-1 测试基线、P5-11-2 体验可观测性与 P5-11-3 长会话性能均已落地；当前 Vitest 88 项、Playwright 23 条通过（1 条 `@perf` 按需运行） |
| macOS/Linux 沙箱与安装包 | 暂缓，仅 Windows |
| 真实模型自动化评测 | 未接入，仅保留 BYOK 本地/定时抽样路径 |

---

## 8. 面试讲解建议

**3 分钟主线**：

1. 先讲产品问题：AI 编程工具“说得对”不等于“改得对”，所以做本地优先、可审批、可验证的工作台。
2. 讲执行闭环：ReAct → 工具 → 审批 → 文件副作用 → 完成判定。
3. 讲最硬的点：把“执行结束”和“任务完成”拆开，没有验证证据就是 `UNVERIFIED`。
4. 讲证据：15 条真实 Runtime 用例、独立基线、双门禁、CI。

**可能被追问的问题与回答方向**：

| 追问 | 回答要点 |
|---|---|
| 为什么不用 LangGraph/多智能体？ | 先有评测再换架构；当前瓶颈是上下文、诊断与验证，不是编排名词 |
| 如何防止模型假装完成？ | 完成由运行时根据计划、审批、写失败、文件变化与验证证据判定，模型只提供“候选完成” |
| 如何防止死循环？ | 轮次与 token 预算 + 重复调用/相同失败/无推进三类检测，阈值可配置 |
| 评测是不是自测自嗨？ | 组件评测与 Runtime 评测分离，后者驱动真实服务、工具与文件系统；并明确其不替代真实模型成功率 |
| 安全怎么保证？ | 路径 realpath 校验、命令白名单与危险子命令拦截、进程树回收、审批落库、跨租户校验 |
| 桌面端和网页端怎么复用？ | 同一后端与前端，按 `DeploymentMode` 与 `Capability` 门控，不分叉代码 |

**不要夸大**：当前没有 LSP 诊断、没有自动 verify、没有混合检索、没有多智能体；`task_success_rate=20%` 是刻意覆盖失败路径的脚本指标，不是产品真实成功率。

---

## 9. 阅读顺序

1. 本文（全局）
2. [01-agent-runtime.md](01-agent-runtime.md)（核心执行层）
3. [03-tools-approval-sandbox.md](03-tools-approval-sandbox.md)（工具、审批、沙箱）
4. [04-context-rag.md](04-context-rag.md)（上下文与知识库）
5. [05-api-sse.md](05-api-sse.md) 与 [06-frontend.md](06-frontend.md)（端到端链路）
6. [07-desktop-deployment.md](07-desktop-deployment.md) 与 [08-data-persistence.md](08-data-persistence.md)
7. [09-security.md](09-security.md) 与 [10-quality-evaluation.md](10-quality-evaluation.md)（工程底线）
