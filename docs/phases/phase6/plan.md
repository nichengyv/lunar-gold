﻿﻿﻿# Phase 6 更新规划：多入口与本地 API 契约

> 更新日期：2026-11-17
> 状态：**已收口（2026-11-17）**；P6-01～P6-07 全部完成，出口条件见 §4
> 主计划见 [UPDATE_PLAN.md](../../UPDATE_PLAN.md)；Phase 5 收口总结见 [.trae/specs/p5/README.md](../../../.trae/specs/p5/README.md)；已完成工作见 [COMPLETED_WORK.md](../../COMPLETED_WORK.md)。
> 本文只描述目标、边界、批次与验收，不以日历工期作为承诺。

**收口摘要（2026-11-17）**：公开 API 子集与客户端凭证、CLI、VS Code 薄插件、审批入口审计、Run 断流解耦与交互收敛均已交付；真机复核覆盖 Web 工作台 7 条审批、VS Code 客户端审批 1 条（`clientId=FeBIz_31mXyW`）与 CLI 端到端；P6-04 评估结论为「保留现有布局、不新增中间工作区」。收尾修复了 CLI `parseTokenClientId` 对以下划线开头 `client_id` 的误判。

---

## 1. 与主计划的关系

- **产品形态不变**：Lunar Gold 仍是本地优先、可控、可验证的 AI 编程工作台；Phase 6 不新增"Runtime 产品"，也不把智能体内核外置成独立服务。
- **Phase 6 的唯一命题**：让**同一个智能体能力和同一份任务数据**能被第二个入口调用，而不是只有 Web 工作台。
- **不 fork VS Code**：IDE 侧只做薄插件（发起任务、看流式、审变更、做审批），不复制工作台、不接管编辑器、不做语言服务器。
- **顺序不可颠倒**：本地 API 契约与客户端身份是 CLI 与 IDE 插件的前置；没有稳定契约就没有第二个入口。
- **Phase 5 成果直接复用**：`turnId` / ChangeSet / 审批 / 诊断 / Verify / SSE 续传都已具备，Phase 6 只补"对外契约 + 客户端身份"。

## 2. 现状盘点（2026-11-16 核实）

| 项 | 现状 | 事实位置 |
|---|---|---|
| 接口版本 | 全部 `/api/**`，无版本前缀、无公开/内部划分 | 各 `controller/*.java` |
| 鉴权 | 云端 JWT、桌面端单一本地令牌，二选一；本地令牌校验通过即赋同一个本地用户 | `config/SecurityConfig.java`、`filter/LocalTokenFilter.java` |
| 客户端身份 | **没有**。CLI/插件若接本地 API，只能拿用户那枚本地令牌，无法区分来源、无法按客户端撤销 | 同上 |
| 权限范围 | 无 scope；本地令牌即全权 | 同上 |
| 流式 | SSE 已实现，`GET /api/agent/runs/{runId}/events?sinceSeq=` 且支持 `Last-Event-ID` | `AgentChatController` |
| 契约 | springdoc 已接入，`SPRINGDOC_ENABLED=false` 可整体关闭；无契约快照测试 | `SecurityConfig` 白名单、`pom.xml` |
| CLI | 不存在 | 全仓未命中 |
| IDE 插件 | 不存在 | 全仓未命中 |
| 审批来源 | `decision_source` 已有 USER / BATCH / TIMEOUT / SESSION_STOP / SYSTEM | V19 迁移、`ToolApprovalRegistry` |
| 审计 | Run Event + `turnId` 已覆盖；外部入口尚无独立标识 | `AgentRun.requestJson`、`ChangeSetService` |

## 3. 批次与验收

### P6-01 本地 API 契约与客户端身份（前置，必须先做）

> Spec：[spec.md](../../../.trae/specs/p6/p6-01-local-api-contract/spec.md) ｜ [tasks.md](../../../.trae/specs/p6/p6-01-local-api-contract/tasks.md) ｜ [checklist.md](../../../.trae/specs/p6/p6-01-local-api-contract/checklist.md)

**目标**：为"非 Web 客户端"划出一个有身份、有范围、可冻结的接口子集，且不复制第二套 controller。

设计决策（2026-11-16 评审，`[已定]` 项已冻结，详见 `## 3.A P6-01 决策备忘`）：

1. **不新增 `/api/v1/**`，只维护"公开子集清单"`[已定]`**。复用现有 controller，新增一份显式清单作为对外契约；清单外的接口对客户端凭证一律 403。
   理由：复制一套 controller 会带来两套测试、两套文档、两处改 bug，体积与维护成本都是净增；现有路径已由 Web 前端（75+ 单测、23 条 E2E）覆盖，无需要求外部客户端接受另一套路径。本项目三入口同仓库、桌面端整体升级，不存在「后端升级但客户端未升级」的窗口期，URL 版本号收益接近零。
   代价：URL 上没有版本号。若将来出现破坏性变更，再引入 `/api/v2` 或 `X-Api-Version` 协商；契约防线落在「子集清单 + OpenAPI 快照 + 鉴权矩阵测试」三件事上，而非版本号。
3. **网络边界校验**：仅监听回环地址；对非回环来源、缺失/伪造 `Origin` 与 `Host` 的请求直接拒绝并记日志（防 DNS rebinding 与本机端口被网页滥用）。
4. **客户端凭证取代共享令牌`[已定]`**。新增 `api_client` 表（`client_id`、名称、secret 哈希、scopes、创建/撤销时间、最后使用时间），走 `Authorization: Bearer <client_token>`；仍**仅允许回环地址**，与桌面端边界一致。理由：现有单令牌无法按客户端撤销，也无法审计"谁调用的"。
5. **Scope 最小集`[已定]`**：`chat:write`、`run:read`、`changes:read`、`changes:write`、`approval:write`、`project:read`、`client:admin`。越权一律 403，缺凭证 401，与现有 `ErrorCode` 信封保持一致。
6. **SSE 复用现有协议**，客户端必须支持 `Last-Event-ID` / `sinceSeq` 续传；连接断开不重放已消费事件。
7. **契约冻结**：以 OpenAPI 为准，只导出公开子集，新增契约快照测试（字段增删必须显式更新快照），生成 TS 客户端供 CLI/插件使用。
8. 响应补 `requestId`，日志与 Run Event 可关联。

### 3.A P6-01 决策备忘（2026-11-16）

**版本策略（已定）**：不加 `/api/v1`。防线是子集清单 + OpenAPI 快照 + 鉴权矩阵；破坏性变更出现时再上 `/api/v2` 或 `X-Api-Version` 协商，不提前铺。

**客户端凭证（已定）**：`api_client` 表 + `Authorization: Bearer <client_token>` + scope；仅回环；可单个撤销。token 建议 `lgc_` 前缀 + 32 字节随机，库内只存 SHA-256（每次请求都校验，必须快），撤销靠删行或置撤销时间。

**公开子集范围（已定）**：三组，约十来个端点。

| 组 | 端点 | 建议 scope |
|---|---|---|
| 任务闭环 | 创建会话、发任务、Run 事件流（`sinceSeq`/`Last-Event-ID` 续传）、变更集、审批、撤回 | `chat:write`、`run:read`、`changes:read`、`changes:write`、`approval:write` |
| 只读上下文 | 项目列表、会话列表、会话消息历史 | `project:read`、`run:read` |
| 客户端自管理 | 列出本机客户端、撤销、健康与版本探测 | `client:admin`（列出/撤销仅作用于自身；跨客户端管理仍需本地用户令牌） |

**不开放**：`documents`、`workspace` 读写、`settings`、`api-key`、`mcp`、`prompt-admin`。

**清单落点（待 spec 确认）**：倾向注解 `@PublicApi` + 启动自检（清单声明但控制器不存在则启动失败），避免配置文件漏配变成运行时 403。

非目标：公网可访问 API、OAuth、多租户、GraphQL、Webhook。

验收：

- 401 / 403 / scope 越权矩阵测试齐全；
- 撤销客户端后立即失效；
- 同一 Run 无论来自 Web 还是客户端凭证，都写入相同 `turnId` 与事件序列；
- 公开子集清单外的接口对客户端凭证返回 403，且有测试；
- 契约快照纳入 CI；
- 未授权来源（非回环、伪造 Origin/Host）请求被拒并记日志；
- 现有 Web 接口与前端测试零回归。

### P6-02 CLI

> 状态：**已完成**（2026-11-16）。Spec：[spec.md](../../../.trae/specs/p6/p6-02-cli/spec.md) ｜ [tasks.md](../../../.trae/specs/p6/p6-02-cli/tasks.md) ｜ [checklist.md](../../../.trae/specs/p6/p6-02-cli/checklist.md)
>
> 交付：`cli/`（零依赖、Node 24 type-stripping、无构建步骤，8 条命令 + `--json` NDJSON）、`cli/test/**`（71 条单测；2026-11-17 收尾修复后 72 条）、`scripts/cli-e2e.ps1`、`cli/README.md`。
>
> 实测退出码矩阵：`run` 正常无变更 0 / 有变更 6 / `error` 5 / 停在审批 4 / 断流续看重放不重复；`watch` 0·5·6（401 不重试→3）；`changes` 0；`revert` 0（非 TTY 无 `--yes`→2）；`approve` 0（失效→4）；`client create/show/revoke` 0（撤销后同令牌→3）；`doctor` 0（契约版本不匹配→1）；网络/5xx→1；未知命令/缺参→2。
>
> 真机 e2e：`scripts/cli-e2e.ps1` 在 desktop profile（端口 18082，独立数据目录与工作区副本）跑通「client create → doctor → run（exit 6，生成 note.txt）→ changes → revert（文件还原）→ client show/revoke」；后端 `mvn -o test` 仍为 331/0/0/15。
>
> 收尾修复（2026-11-17）：`parseTokenClientId` 原先按首个 `_` 切分，`client_id` 以下划线开头时返回 `null`，导致 `doctor` 误报、`client show/revoke` 丢失 id。现与后端 `ApiClientService` 对齐为固定 12 字符解析，并校验第 13 位分隔符与 secret 非空；新增 1 条单测覆盖下划线开头/结尾、secret 含下划线与非法分隔符。`cd cli && npm test` 72 条通过；`scripts/cli-e2e.ps1 -KeepData` 真机复跑通过（run exit 6、revert 删除 note.txt、client show/revoke 成功）。

**目标**：终端里能完整跑一次"发起 → 看流式 → 审变更 → 审批 → 撤回"。

设计决策：

- 技术选型：**Node + TypeScript**，复用前端已有的 SSE 解析与 OpenAPI 生成的类型，不引入 GraalVM 或第二个 Java 构建。
- 首版命令：`run`、`watch`（流式查看）、`changes`、`approve|deny`、`revert`、`client`（列出/撤销本机客户端）。
- 输出：默认人读文本，`--json` 输出稳定结构供脚本消费；退出码区分成功 / 未验证 / 未完成 / 需审批 / 认证失败。
- 不做交互式 TUI、不做离线模式、不内置模型 Key（沿用后端已存 Key）。

验收：脚本化端到端（起后端 → CLI 在 fixture 工作区跑任务 → 断言文件变更、审批、撤回、退出码）；`--json` 结构有稳定性测试；缺凭证时给出可执行的修复提示。

### P6-03 IDE 薄插件（先 VS Code）

> 状态：**已实施，本机 vsix 核心闭环验收通过**（2026-11-17）。Spec：[spec.md](../../../.trae/specs/p6/p6-03-ide-plugin/spec.md) ｜ [tasks.md](../../../.trae/specs/p6/p6-03-ide-plugin/tasks.md) ｜ [checklist.md](../../../.trae/specs/p6/p6-03-ide-plugin/checklist.md)
>
> 交付：`ide/vscode/`（TypeScript + esbuild 单文件 bundle，运行时零依赖），令牌只存 SecretStorage；变更用 VS Code 原生 diff（基线来自 `ChangeView.diffText`，不可靠时降级 patch 预览）；纯逻辑与 CLI 共用 SSE fixture 与续传去重策略；不做 LSP / 补全 / 多任务并发 / 远程适配，首版 vsix 本机手动安装。
>
> 实测：VS Code 1.143.1 安装 `lunargold-vscode-0.1.0.vsix` 后，连接、流式任务、批准 / 拒绝 / 本会话记住、原生 diff、按 Run / 会话撤回、停止和工作区错位提示均通过；扩展 43 条单测、CLI 71 条单测、后端 331/0/0/15 最终回归通过。证据与首轮环境超时记录见 checklist。
>
> 保留偏差：双待审批的「全部批准」成功路径未真机复现；远程窗口无真机环境。原「断流即取消」偏差已由 P6-06 修复：扩展重载后 Run 继续执行，可恢复事件、终态与待审批。
>
> 后续项（不阻断 P6-03 收口，尚未立项）：
> 1. **（已由 P6-06 完成）** 后端区分「客户端断流」与「用户显式取消」：断流只停止推送，Run 继续执行；详见下文 P6-06。
> 2. **「全部批准」可达性确认**：后端在单个 Run 内串行处理写工具，扩展又限制单活跃 Run，双待审批卡在正常流程中可能不可达。先确认是否存在真实可达路径（如上一 Run 遗留待审批 + 重载恢复）；若确认不可达，按「不过度设计」原则评估移除该按钮与批量调用分支，避免长期保留未验证代码。

**目标**：在编辑器里发起任务、查看流式输出、审阅并接受/撤回变更，不复制工作台。

设计决策：

- 只做三块：状态栏/命令入口、变更预览（调用 `changes` 接口 + 编辑器 diff）、审批面板（Webview 最小实现）。
- 通过本地 API + 客户端凭证；插件不直接读写用户 Key 数据库。
- 不接管编辑器补全、不做语言服务器、不复制 Chat 页面；复杂操作引导回 Web 工作台。
- 打包 vsix，签名与用户手动安装。

验收：插件可发起任务并收到流式；变更可在编辑器 diff 中查看；审批与撤回可用；断网重连能续传；插件未授权时给出明确引导。

### P6-04 工作区布局再评估（评估，不默认实施）

> 状态：**已完成，结论为「保留」**（2026-11-17）；未改任何布局代码。
>
> 结论：**保留现有「左侧栏 + 中间对话 + 右侧 WorkbenchDock」三栏结构，不新增中间工作区。** 工作区文件、变更、计划、追踪、监控与编辑器已经在右侧 Dock 内按 tab 收敛，再建一个中间工作区会与 `WorkbenchDock` 重复，并挤占主阅读列；本轮不调整，细节优化留到有真实反馈时另立 spec。
>
> 依据（代码事实）：
> 1. 右 Dock 已覆盖 6 类内容：变更 / 资料 / 计划 / 追踪 / 监控 / 文件；资源管理器点文件直接 `chat.openFile` 进入文件 tab，支持多文件 tab、脏状态与关闭确认（[WorkbenchDock.vue](../../../frontend/src/components/WorkbenchDock.vue)、[chat.ts](../../../frontend/src/stores/chat.ts)）。
> 2. 宽度可拖拽并持久化，300px～`min(70% 视口, 容器-420px)`；对话区保底 `MIN_CHAT_WIDTH=420`，并提供全屏模式（[ChatView.vue](../../../frontend/src/views/ChatView.vue)）。
> 3. <1024px 工作台自动改为浮层 + 遮罩，不挤压正文；<768px 主侧栏改为抽屉；≥1024px 侧栏可折叠为 56px 图标轨（[AppLayout.vue](../../../frontend/src/layouts/AppLayout.vue)）。
>
> 场景对比：
> | 场景 | 现有路径 | 是否需要一个新中间区 |
> |---|---|---|
> | 只对话 / 长回答阅读 | 收起 Dock，正文独占中间列 | 否 |
> | 审变更 / 看计划 / 追踪 | 右 Dock 对应 tab | 否，已有 |
> | 编辑工作区文件 | 资源管理器点文件 → 右 Dock 文件 tab / 全屏 | 否，已有 |
> | 900px 左右小屏 | Dock 浮层 + 遮罩，点击空白收起 | 否，已有 |
> | 深入编辑器 / diff | VS Code 插件原生 diff，或 Dock 全屏 | 否，入口已分工 |
>
> 实测证据（2026-11-17，Playwright + 系统 Edge）：`responsive.spec.ts` 4 条通过——960×640 无横向滚动且输入区可见；900×700 侧栏图标轨 + Dock 浮层不挤压正文；767×700 侧栏抽屉默认收起；1280×800 侧栏展开、三栏无横向滚动。900×700 截图见 `frontend/test-results/responsive-900x700-dock.png`（测试产物，不入库）。
>
> 后续可选（不立项、不改代码）：Dock 默认宽度按屏幕分档、tab 顺序/快捷键、文件 tab 溢出策略；仅在真实使用反馈证明有收益时再立 spec。

**目标**：判断中间工作区与三栏布局是否需要调整，避免与 `WorkbenchDock` 重复建设。

产出：一份评估结论（保留 / 调整 / 延后），附截图与使用场景对比；**不默认改代码**。若结论是"调整"，另立 spec。

### P6-05 多入口一致性与审计

> 状态：**审批来源与审批入口审计已实施，多入口真机复核已闭环**（2026-11-17）。Spec：[spec.md](../../../.trae/specs/p6/p6-05-multi-entry-audit/spec.md) ｜ [tasks.md](../../../.trae/specs/p6/p6-05-multi-entry-audit/tasks.md) ｜ [checklist.md](../../../.trae/specs/p6/p6-05-multi-entry-audit/checklist.md)
>
> 交付：`DecisionSource` 增加 `CLIENT`，单条审批按 `CurrentClient.clientIdOrNull()` 区分 Web（`USER`）与 CLI / VS Code（`CLIENT`）；批量保持 `BATCH`；`V21` 增加 `tool_approval.client_id` 记录审批入口，审批历史接口返回可空 `clientId`，前端来源标签支持「客户端」。发起入口 `agent_run.request_json.clientId` 与事件 `approval_resolved.clientId` 语义不变。
>
> 实测：后端 367 tests / 0 failures / 0 errors / 15 skipped；前端 88 tests / 12 files 通过。新增用例覆盖 Web 单条、客户端单条（批准 / 拒绝）、批量带 `client_id`、HTTP 层的 `USER` / `CLIENT` 分流；`V21` 在嵌入式 PostgreSQL 16.11 上完成 V1~V21 全量迁移并断言 `tool_approval.client_id` 结构。
>
> 真机复核（2026-11-17）：Web 工作台 7 条审批来源为 `WEB`；VS Code 以 `CLIENT=FeBIz_31mXyW` 审批 1 条（批准率 100%，决策 8600ms）；CLI 由 `scripts/cli-e2e.ps1` 落 `CLIENT`。`GET /api/agent/metrics/experience` 的 `$r.data.approval.byEntry` 在 Web 工作台与「设置 → 应用信息 → 高级诊断」均可见分组表。
>
> 已知偏差：体验指标未按入口维度拆分已由 [P6-05b](#p6-05b-审批体验指标按入口拆分p6-05-偏差-1-补齐) 补齐；历史审批记录不回填 `client_id`（保持 `UNKNOWN`）。

**目标**：三个入口看到的是同一份任务事实，且能追溯来源。

设计决策：

- `decision_source` 增加 `CLIENT`（CLI/插件审批）；审批记录记录审批请求自身的 `client_id`，Run Event 的 `client_id` 仍表示发起入口。
- 体验指标按入口维度可区分（不新增远程遥测，沿用 P5-11-2 的本地/聚合口径）——已由 P6-05b 补齐。
- 反向要求：任何入口都不得绕过审批、不得绕过 Verify/Diagnostics 结果直接标记"已完成"。

验收：同一任务分别从 Web 与 CLI 执行，ChangeSet 结构一致、`turnId` 可追溯、审批来源正确；越权路径有测试。

### P6-05b 审批体验指标按入口拆分（P6-05 偏差 1 补齐）

> 状态：**已实施**（2026-11-17）。Spec：[spec.md](../../../.trae/specs/p6/p6-05b-entry-metrics/spec.md) ｜ [tasks.md](../../../.trae/specs/p6/p6-05b-entry-metrics/tasks.md) ｜ [checklist.md](../../../.trae/specs/p6/p6-05b-entry-metrics/checklist.md)
>
> 交付：`GET /api/agent/metrics/experience` 的 `approval` 新增 `byEntry`，按 `tool_approval.client_id` 与 `decision_source` 分为 `WEB` / `CLIENT`（按 `client_id` 分组）/ `AUTO` / `UNKNOWN`，输出审批量、批准数、拒绝数、批准率与平均决策耗时。自动来源（`TIMEOUT` / `SESSION_STOP` / `SYSTEM`）单独成组且批准率为 `null`，不污染人为批准率；`decision_source = NULL` 的历史记录归 `UNKNOWN`，不猜测为 `WEB`。既有 `averageWaitMs` / `sampleCount` 口径不变，无迁移、无索引、无新端点，CLI / VS Code 零改动。
>
> 实测：后端 368 tests / 0 failures / 0 errors / 15 skipped；前端 89 tests / 12 files 通过，`vue-tsc --noEmit` 通过；CLI 71 / 扩展 43 不受影响。

**目标**：让 P5-11-2 的审批体验指标能回答「哪个入口批得最多、批准率如何、决策快不快」，而不新增遥测与数据库变更。

设计决策：

- 入口判定互斥且有序：自动来源 → `AUTO`；`client_id` 非空 → `CLIENT`；`USER` / `BATCH` → `WEB`；其余归 `UNKNOWN`。
- 复用最近 100 条已决策审批窗口在内存分组，分组 `totalCount` 之和等于 `sampleCount`；不加聚合 SQL 与索引。
- 响应只做增量扩展；前端在设置页「体验诊断」的 Agent 体验卡片内展示分组表。
- 非目标：不按入口拆分 Run / token / 工具指标，不新增采集开关或上传通道。

验收：五种分组与空样本均有单测；自动拒绝不进入批准率分母；前端分组表与 `vue-tsc` 通过；后端、前端、CLI、扩展零回归。

### P6-06 Run 生命周期与客户端断流解耦（P6-03 后续）

> 状态：**已实施，核心语义、HTTP 断流验收与回归通过**（2026-11-17）。Spec：[spec.md](../../../.trae/specs/p6/p6-06-run-lifetime/spec.md) ｜ [tasks.md](../../../.trae/specs/p6/p6-06-run-lifetime/tasks.md) ｜ [checklist.md](../../../.trae/specs/p6/p6-06-run-lifetime/checklist.md)
>
> 交付：拆分 `cancelRequested`（显式停止）与 `clientDetached`（SSE 断开）；断流只停止推送，Run 继续执行且事件先落库后发送；审批跨断流保持 `PENDING`；`GenerationRegistry` 支持同会话多 Run；CLI Ctrl-C 改为显式调用 `/chat/stop`；VS Code 扩展代码不变。
>
> 实测：后端 351 tests / 0 failures / 0 errors / 15 skipped（新增 20 条，其中 5 条为真实 Tomcat + HTTP 断流验收）；CLI 71 条、扩展 43 条通过。`RunDisconnectIntegrationTest` 用真实 ReAct 循环验证断流后完成并保存回答；`RunDisconnectHttpAcceptanceTest` 用 RST 断开验证断流继续、心跳失败发现断流、审批跨断流保持 `PENDING`、批准 / 拒绝后继续、`seq` 连续、usage 落库与断流后仍可停止。
>
> 已知偏差：`onError` / `onTimeout` 无法由真实客户端触发（emitter 超时为 0，Tomcat 把 RST 表现为发送异常），改为直接调用注册回调验证；重连后的审批列表接口未单独断言；执行异常终态与长跑兜底未单独断言；Web / CLI / VS Code 未做本轮真机复核；后端进程重启仍不续跑（非本批次目标）；仓库无覆盖率门禁。

**目标**：客户端断流不再终止任务，只有显式停止、预算耗尽或异常才结束 Run。

设计决策：

- 断流与取消是两个信号：ReAct、工具与审批只看显式停止，SSE 写出只看连接状态。
- 语义事件先落库后发送；断流后发送为 no-op，事件链保持连续。
- 终态只由业务结果决定，断流不再产生 `CANCELLED` 或丢弃半截回答。
- 不新增端点、事件类型、配置项与数据库迁移；无客户端时沿用现有预算与审批超时兜底。

验收：断流后 Run 继续并成功落库；显式停止仍立即中止；断流期间审批可恢复处理；后端、CLI、扩展与前端零回归。

### P6-07 交互收敛（穿插批次）

> 状态：**已实施，前端单测、类型检查与真机复核闭环**（2026-11-17）。Spec：[spec.md](../../../.trae/specs/p6/p6-07-ui-refine/spec.md) ｜ [tasks.md](../../../.trae/specs/p6/p6-07-ui-refine/tasks.md) ｜ [checklist.md](../../../.trae/specs/p6/p6-07-ui-refine/checklist.md)
>
> 交付：审批卡片去掉风险 chip 与秒级倒计时、`本会话总是允许` 收进更多菜单、过期不再自行隐藏；回答气泡在「重新生成」右侧显示本轮 token（悬停看输入 / 输出），复制 / 重新生成常显；「体验诊断」从设置一级 tab 移入「应用信息 → 高级诊断」，验收指标默认隐藏、需显式勾选。
>
> 依据：行业对照中五家均无风险等级标签，只有 Claude Code 在 Code Mode 下才有超时倒计时；用量指标放在 `/usage`、`/status`、状态栏或回答悬停；诊断均为按需入口（`/doctor`、Agent Debug Logs、Network Diagnostics），无常驻体验指标页。
>
> 实测：前端 92 tests / 12 files 通过，`vue-tsc` 无报错；真机复核确认新审批卡片、气泡 token 与「应用信息 → 高级诊断」路径均生效；`e2e/diagnostics.spec.ts` 1 条通过（2026-11-17），覆盖分组表、异常白名单脱敏、导出、停用与清除。
>
> 已知偏差：历史回答不回显 token（消息历史接口未返回 usage，需后端回填批次）；P6-05b spec 中「设置页体验诊断」的位置描述以本批次为准。

## 4. Phase 6 出口条件（2026-11-17 全部满足）

1. **公开子集契约冻结**，且有契约快照与鉴权矩阵测试。
2. **CLI 能独立完成一次完整任务闭环**，退出码语义稳定。
3. **VS Code 插件能发起任务、审阅变更、审批与撤回。**
4. **三入口任务数据一致，来源可审计。**
5. **现有 Web 工作台与 Phase 5 全部评测门禁零回归。**

### 4.1 出口检查记录（2026-11-17）

| 检查项 | 结果 | 证据 |
|---|---|---|
| CLI 令牌解析收尾修复 | 通过 | `parseTokenClientId` 改为固定 12 字符；CLI 单测 72/72；`scripts/cli-e2e.ps1 -KeepData` 真机通过（run exit 6、note.txt 生成、revert 删除、client show/revoke 成功） |
| 前端 diagnostics E2E | 通过 | `e2e/diagnostics.spec.ts` 1 条通过：分组表、异常脱敏、导出、停用、清除 |
| P6-04 工作区布局评估 | 保留，不改代码 | 三栏结构与 `WorkbenchDock` 分工成立；`responsive.spec.ts` 4 条通过（960/900/767/1280） |
| 回归基线 | 通过 | 后端 368 tests / 0 fail / 15 skipped；前端 92 passed / 12 files + `vue-tsc`；CLI 72；扩展 43 |
| 真机多入口 | 通过 | Web 工作台 7 条审批；VS Code `CLIENT=FeBIz_31mXyW` 1 条（100%、8600ms）；CLI e2e 落 `CLIENT` |

环境偏差（不修改仓库配置）：本机未安装 Playwright Chromium，且 Vite 6 默认只监听 `::1`，Playwright 的 `webServer` 轮询 `127.0.0.1` 会 120s 超时。本次 E2E 通过「已启动 Vite（localhost:5174）+ `E2E_BASE_URL`」并设置 `E2E_CHANNEL=msedge` 运行；CI 或新机器需先 `npx playwright install chromium`，或显式统一监听地址。

## 5. 依赖与风险

| 风险 | 影响 | 应对 |
|---|---|---|
| 公开子集与内部接口共用 controller | 误把内部接口暴露给客户端 | 清单显式声明 + 清单外 403 + 鉴权矩阵测试 |
| 客户端凭证被滥用 | 本地越权 | 仅回环 + scope 最小集 + 可撤销 + 审计留痕 |
| CLI 与插件各写一套 SSE 解析 | 协议分叉 | 统一由 OpenAPI 生成类型，SSE 解析抽成共享包 |
| 插件范围蔓延成"第二工作台" | 与 Phase 5 定位冲突 | 明确三块功能上限，复杂操作引导回 Web |
| 打包与签名成本 | 发布阻塞 | 先做本机手动安装，签名与自动更新后置 |

## 6. 与 Phase 5 遗留项的关系

Phase 5 的体验打磨项（三态覆盖、长回答折叠、三档宽度、断网实测、axe、500 会话压力）**不阻塞 Phase 6**，可作为 P6 期间的穿插批次；索引升级候选（`UPDATE_PLAN.md` §5.2）继续**暂缓**，等触发条件。

## 7. 实施顺序

`P6-01` → `P6-02` / `P6-03`（可并行，但都必须等 01 的契约冻结）→ `P6-05`（随 02/03 同步）→ `P6-04`（独立评估，可随时插入）→ `P6-07`（交互收敛，穿插）。

每个批次实施前在 `.trae/specs/p6/<task-id>/` 下补 `spec.md` / `tasks.md` / `checklist.md`，经确认后再改代码。
