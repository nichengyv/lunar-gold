# P5-11-2 体验可观测性 — 规格说明

> 日期：2026-11-14
> 状态：已完成（2026-11-14）
> 对应规划：`docs/phases/phase5/plan.md` P5-11-2
> 已存在基础：P3-06 桌面崩溃诊断、工具 `MeterRegistry`、Run/Event、审批决策时间、Run token 汇总、设置页 tab 与 Vitest/Playwright

## 1. 目标

在不远程上传、不复制对话与工作区内容的前提下，让慢、断和前端异常可以在本地诊断页定位：

1. 捕获 Vue 渲染异常、`window.onerror` 与 `unhandledrejection`，按白名单和脱敏规则保存到当前浏览器。
2. 采集 LCP / INP / CLS，记录所属聊天会话与采样时间，在本地查看当前值。
3. 汇总首字时延 p95、流中断率、断线恢复成功率、工具失败率、审批平均等待和每轮 token。
4. 提供本地采集开关；关闭后不再新增浏览器诊断记录，已有记录可查看、清除或由用户手动导出。

## 2. 非目标

- 不接入 Sentry 等远程服务，不自动上传，不新增服务端日志收集接口。
- 不采集提示词、回答、工具参数、文件内容、API Key、token、完整 URL 查询串或未经脱敏的堆栈。
- 不新增数据库表或 Flyway 迁移，不改变 ReAct、SSE、审批和断线恢复语义。
- 不把后端运行指标伪装成实时全量监控；无样本时显示「—」和样本数，不显示伪造的 0。
- 不在本项实施虚拟滚动、Markdown 增量渲染等 P5-11-3 性能改造。

## 3. 现状与缺口

| 能力 | 已确认现状 | 缺口 |
|---|---|---|
| 崩溃诊断 | `desktop/diagnostics.js` 覆盖 Electron/Java/PG，白名单报告与手动 ZIP 导出 | 不包含正常页面中的 Vue/浏览器异常和体验指标 |
| 前端异常 | `main.ts` 直接挂载应用，无全局异常或 Promise 拒绝采集 | 无本地记录、开关和查看入口 |
| Web Vitals | 无 `web-vitals` 或 PerformanceObserver 采集 | LCP/INP/CLS 无当前值 |
| Agent 指标 | `/api/agent/metrics` 仅聚合工具 Timer；Run 有开始时间与 token，Event 有 MODEL_CHUNK 时间，审批有创建/决策时间 | 无首字 p95、审批等待和每轮 token 聚合 |
| 断线恢复 | `recoverRun` 已区分 reconnecting/recovered/interrupted，并重放 Run Event | 无本地尝试、成功、失败与中断率统计 |
| 设置页 | 已有 tab 导航、卡片、健康状态 | 无体验诊断页 |

## 4. 设计决策

### 4.1 浏览器本地诊断与隐私边界

新增 `frontend/src/utils/experienceDiagnostics.ts`，统一负责开关、白名单记录、脱敏、容量限制和聚合。使用版本化 localStorage 键，损坏 JSON 或存储写满时静默降级，不影响聊天。

记录只包含：

- 异常：`id/timestamp/kind/message/route/sessionId`；`kind` 为 `vue/error/unhandledrejection`。
- Web Vitals：`name/value/timestamp/route/sessionId`，`name` 仅允许 LCP/INP/CLS。
- Run 标记：`runId/sessionId/startedAt`。
- 恢复记录：`runId/sessionId/startedAt/endedAt/durationMs/pollAttempts/outcome`，落库 `outcome` 仅允许 recovered/failed；被新恢复取代的临时记录按 discarded 处理且不写存储。

message 上限 2000 字符，沿用 P3-06 的通用密钥、Bearer/token、Windows/POSIX 绝对路径脱敏思路，只保存清洗后的单行摘要，不保存原始 Error、reason 对象和 stack。route 只取 pathname，不带 query/hash。

容量采用有界环形裁剪：异常 50 条、Web Vitals 180 条、Run 100 条、恢复 100 条。数据只存在当前浏览器，不同账号共用浏览器时不展示对话内容，但诊断页在账号切换后清空本地体验记录，避免跨账号保留 run/session 标识。

### 4.2 开关、Web Vitals 与异常接入

- 新增官方 `web-vitals` 运行时依赖，使用 `onLCP/onINP/onCLS`，避免自行近似 INP；库只返回指标，不配置任何上报端点。
- `main.ts` 在挂载前初始化诊断：注册 `app.config.errorHandler`、`window.error`、`window.unhandledrejection`，启用时启动 Web Vitals。
- 本地采集默认开启，因为数据不上传且有界保存；设置页可关闭。关闭后移除 window 事件监听；`web-vitals` 没有统一注销接口，其后续回调由开关拦截，新异常、Run、恢复和 Web Vitals 均不再写入。
- 关闭不自动删除已有记录，页面明确提示「已停止新采集」；提供「清除记录」。
- 支持用户点击「导出诊断 JSON」，内容仅为本地白名单记录和当前后端指标快照；不自动保存、不上传，不纳入 P3-06 崩溃 ZIP。

### 4.3 断线恢复指标

在现有 chat store 的两个真实状态点记录，不新增网络请求：

- `onRunId` 记录本地 Run 总数；同一 runId 去重。
- `recoverRun` 进入时记录一次恢复尝试并计时；每次同 run 的恢复都增加尝试次数，流中断按发生恢复的 runId 去重。
- 收到任意 Run 终态并完成事件重放后记录 `recovered`；网络重试耗尽记录 `failed`；用户切换会话/新恢复使 token 失效时不写结果，避免把正常导航算失败。
- Run 本身 FAILED/CANCELLED 但事件已成功补齐时，恢复传输可算成功，另保留 Run 终态，不把模型/工具失败误算为网络恢复失败。

展示：

- 流中断率 = 发生过恢复的本地 run 数 / 本地 run 总数。
- 断线恢复成功率 = recovered 恢复记录 / 已有终态的恢复记录。
- 同时展示恢复尝试数与平均恢复耗时；无本地样本显示「—」。该指标是当前浏览器样本，不外推为全平台可用性。

### 4.4 后端 Agent 体验聚合

新增 `AgentExperienceMetricsService` 与：

```http
GET /api/agent/metrics/experience
```

仅使用当前认证用户数据，无需 projectId，不新增能力门控。返回最近最多 100 个 Run 的窗口指标和进程内当前用户工具 Timer：

| 指标 | 计算 | 无样本 |
|---|---|---|
| 首字时延 p95 | Run.startTime 到该 Run 第一条 MODEL_CHUNK.createTime；最近 100 Run，nearest-rank p95 | `p95Ms=null` |
| 工具失败率 | 当前用户 `agent.tool.execution` Timer 中非 success 次数 / 总次数；进程累计 | `failureRate=null` |
| 审批平均等待 | 当前用户最近 100 条已决策审批，decisionTime-createTime；包含手动、批量和自动决策 | `averageWaitMs=null` |
| 每轮 token | 最近 100 Run 中有用量记录的平均 totalTokens，同时给总 token 和样本数 | `averageTotalTokens=null` |

Repository 只增加归属过滤、限量和首条 MODEL_CHUNK 时间聚合查询，不把所有模型 chunk 拉进内存。响应包含 `generatedAt/windowRuns` 以及各指标样本数；工具指标明确标记为进程累计，避免与最近 100 Run 窗口混淆。

首字时延、工具、审批和 token 是对既有运行审计数据的按需只读聚合，不受浏览器采集开关控制；开关关闭时诊断页不主动刷新该接口，用户手动刷新仍可查看已有审计聚合。

### 4.5 本地诊断页

新增 `ExperienceDiagnosticsPanel.vue`，在设置页增加「体验诊断」tab，桌面端和网页端均可查看：

1. **采集状态**：开关、隐私说明、最后更新时间、清除与导出按钮。
2. **页面体验**：LCP ms、INP ms、CLS，显示当前会话/全部会话筛选、最新值和样本数。
3. **连接体验**：流中断率、恢复成功率、恢复次数和平均恢复耗时。
4. **Agent 体验**：首字 p95、工具失败率、审批平均等待、每轮平均 token；显示后端样本窗口与加载失败重试，不让接口失败掩盖本地异常。
5. **最近异常**：时间、类型、所属会话和脱敏摘要，最多展示 50 条；空态明确。

## 5. 涉及文件

### 后端

- `service/AgentExperienceMetricsService.java`：限量聚合、p95/平均值/失败率。
- `repository/AgentRunRepository.java`：当前用户最近 100 Run。
- `repository/AgentRunEventRepository.java`：Run 集合的首条 MODEL_CHUNK 时间聚合。
- `repository/ToolApprovalRepository.java`：当前用户最近 100 条已决策审批。
- `controller/AgentChatController.java`：`GET /metrics/experience`，放在现有 `/metrics` 旁并保持路径精确匹配。
- 测试：新增 `AgentExperienceMetricsServiceTest`，覆盖窗口、公式、空样本和跨用户隔离。

### 前端

- `utils/experienceDiagnostics.ts`：本地采集、脱敏、开关、聚合和导出快照。
- `main.ts`：Vue/window/Promise 异常与 Web Vitals 初始化。
- `stores/chat.ts`、`stores/auth.ts`：Run/恢复采样及账号切换清理。
- `api/agent.ts`、`types/api.ts`：体验指标类型与查询。
- `components/ExperienceDiagnosticsPanel.vue`：本地诊断展示和操作。
- `views/SettingsView.vue`：新增 tab，复用现有卡片与设置样式。
- `package.json` / `package-lock.json`：新增 `web-vitals`。
- 测试：`experienceDiagnostics.test.ts`、诊断面板测试和 `e2e/diagnostics.spec.ts`；API fixture 增加体验指标响应。

## 6. 验收标准

1. 制造一次 Vue/window 异常和一次 unhandled rejection，设置页能看到时间、类型和脱敏摘要；敏感路径、token、对话正文和原始 stack 不落 localStorage/导出文件。
2. LCP/INP/CLS 有真实浏览器样本时显示当前值和样本数；不支持的浏览器显示「—」，不阻塞应用。
3. 首字 p95、流中断率、恢复成功率、工具失败率、审批平均等待、每轮 token 均有当前值或明确无样本态，公式与所属窗口正确。
4. 关闭采集后，新异常、Web Vitals、Run 和恢复记录均不增加；重新开启后恢复采集，已有记录只有点击清除才删除。
5. 跨用户后端数据不可见；本地账号切换会清除 run/session 标识；后端无数据、接口失败和 localStorage 损坏均不导致白屏。
6. 导出只在用户点击后发生，内容为白名单 JSON；应用不存在自动上传体验数据的网络请求。
7. 既有聊天、审批、断线恢复、设置页和 ChangeSet 行为不回归。

## 7. 验证方式

- 后端聚焦测试：最近 Run 窗口、首条 chunk p95、工具失败率、审批平均等待、token 平均、空样本与跨用户隔离。
- 前端 Vitest：开关启停、异常脱敏、容量裁剪、损坏存储、Run 去重、恢复公式、Web Vitals 聚合和面板失败/空态。
- Playwright：模拟后端体验指标，制造浏览器异常，验证本地展示、关闭后不再新增、清除与导出；不断言 CI 必然产生固定 LCP/INP 数值。
- 全量回归：`mvn -o test`、前端 `npx vitest run`、`npm run build`、Playwright 全量并重复运行。
- 完成后同步 `docs/UPDATE_PLAN.md`、`docs/phases/phase5/plan.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`，勾选 tasks/checklist 并记录实际测试数。

## 8. 风险与取舍

- 官方 `web-vitals` 增加一个小型运行时依赖，换取 INP 等指标的标准算法；不引入埋点 SDK。
- localStorage 容量有限，因此有界裁剪且写失败静默降级；本项不是长期时序数据库。
- 浏览器恢复指标只代表本机样本，后端 Run/Timer 指标代表当前用户；页面分别标注窗口，避免拼成虚假的全局 SLO。
- 后端不新增客户端遥测接收 API，减少隐私、滥用与多用户偏好持久化复杂度。

## 9. 实施结果与偏差（2026-11-14）

- 已按本规格完成 T1～T6，设置页「体验诊断」、后端只读聚合、本地异常/Web Vitals/Run 恢复采样、开关、清除与手动 JSON 导出均已落地；未创建 Git commit。
- 实测：后端全量 298 项，0 失败、0 错误、12 跳过；前端 Vitest 7 文件 57/57；生产构建通过（1847 个模块）；系统 Edge 下 Playwright 全量 14/14，完整套件重复三轮 42/42。
- 指标口径保持分离：本地流中断/恢复为当前浏览器有界样本；首字/审批/token 为当前用户最近 100 Run 或审批窗口；工具失败率为当前用户进程累计 Timer。无样本均返回或展示空态，不伪造成 0。
- 实现偏差仅为细节澄清：`web-vitals` 无统一注销接口，关闭后通过开关拦截后续回调而非注销库内部观察器；流中断率只统计仍存在于本地 Run 窗口内的恢复记录，避免窗口容量不同造成比例超过 100%。
- 未覆盖项不变：E2E 使用浏览器层 API 仿真，不代替真实后端/真实模型验收；不断言固定 Web Vitals 数值；未新增整页亮暗截图档案；长会话性能改造留待 P5-11-3。
