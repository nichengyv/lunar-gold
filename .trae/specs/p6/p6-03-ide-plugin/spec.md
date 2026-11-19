# P6-03 IDE 薄插件（先 VS Code）- Product Requirements Document

## Overview

- **Summary**：新增一个 VS Code 扩展（落点 `ide/vscode/`），通过 P6-01 冻结的公开子集（`Authorization: Bearer lgc_...`，仅回环）在编辑器里完成「发起任务 → 看流式 → 审变更 → 审批 → 撤回」。
- **Purpose**：让用户不离开编辑器就能驱动同一个智能体；与 Web 工作台、CLI 共享同一份 Run / ChangeSet / 审批事实，不制造第二份任务数据。
- **Target Users**：以编辑器为主工作台的本地开发者；脚本与自动化场景仍走 P6-02 的 CLI。

## Goals

1. 编辑器内可发起一次任务：命令面板 / 状态栏入口，选项目、输入 prompt，看到流式过程。
2. 变更可审：任务产生文件变更时，资源管理器旁有变更列表，点击用 **VS Code 原生 diff** 查看，不接受就不算完成。
3. 审批可执行：危险工具在侧边栏弹出审批卡片，批准 / 拒绝 / 本会话记住；插件重载后仍能找回待审批。
4. 可停止、可撤回：停止当前生成；按 run 或 session 撤回变更并还原磁盘文件。
5. 断线可续：与 CLI 同一套 `runId + sinceSeq` 语义，重连不重复渲染已消费事件；插件重载后可恢复查看。
6. 令牌安全：客户端令牌只存 VS Code **SecretStorage**，不落明文配置文件；可一键撤销自身客户端。

## Non-Goals

- 不接管编辑器补全、不做语言服务器（LSP）、不做内联聊天 / 代码操作（CodeAction）。
- 不复制 Web 工作台的 Chat 页面：无会话管理后台、无附件上传、无 RAG / 模型 / 推理档位开关（这些引导回 Web）。
- 不直接读写用户模型 Key 数据库；不内置任何 Key。
- 不做远程开发场景（SSH / WSL / Dev Container）的适配：扩展声明 `extensionKind: ui`，只在本地 UI 侧运行；检测到远程窗口时提示首版不支持。
- 不做多任务并发：同一时刻一个活跃 Run，发起新任务前必须先停止或完成当前任务。
- 不做 VS Code 之外 IDE 的插件；不做自动更新、签名与市场发布（首版本机 vsix 手动安装）。
- 不新增后端端点、不改 `PublicApiCatalog` 与契约快照；不改 `frontend/` 与 `cli/` 的任何文件。

## Background & Context

| 项 | 事实 | 位置 |
|---|---|---|
| 公开子集 | 15 个端点已冻结，清单外对客户端令牌 403 | `src/test/resources/contract/public-api.json` |
| 令牌 | `lgc_<client_id>_<secret>`，库内 SHA-256，撤销即时生效 | `service/ApiClientService.java` |
| scope | `chat:write` / `run:read` / `changes:read` / `changes:write` / `approval:write` / `project:read` / `client:admin` | `security/ApiScope.java` |
| 发起任务 | `POST /api/agent/chat/stream`；不传 `sessionId` 由后端建会话，SSE 里返回 `session_id` / `run_id` / `turn_id` | `AgentChatController` |
| 续传 | `GET /api/agent/runs/{runId}/events?sinceSeq=`，取 `seq > sinceSeq`；SSE 帧本身无 `seq` | `AgentRunService` |
| 变更集 | `GET /api/file-changes/changeset?sessionId=&runId=`；`ChangeView` 含 `filePath` / `action` / `additions` / `deletions` / `diffText` / `status` | `ChangeSetService.java:803` |
| 撤回 | `POST /api/file-changes/revert-run?sessionId=&runId=`、`revert-session?sessionId=`（**查询参数**，无 body） | `ChangeSetController.java:46-57` |
| 审批 | `POST /api/agent/approvals/{approvalId}`，body `{decision, remember}`；待审批列表 `GET /api/agent/sessions/{sessionId}/approvals`；批量 `POST /api/agent/approvals/batch`（只允许 APPROVE） | `AgentChatController` |
| 项目 / 会话 | `GET /api/projects`（project:read）、`GET /api/agent/sessions`（project:read）、`GET /api/agent/sessions/{sessionId}/messages`（run:read） | `public-api.json` |
| 健康 | `GET /api/health`，需有效令牌，`apiContractVersion == 1` | `HealthController` |
| 换发客户端令牌 | `POST /api/clients`（**不在公开子集**，需本地用户令牌 `~/.lunargold/desktop/local-token`） | `ApiClientController` |
| 已有同源实现 | CLI 已有零依赖 HTTP / SSE / 续传 / 审批 / 撤回语义与 6 个 SSE fixture | `cli/src/**`、`cli/test/fixtures/sse/` |
| 工程现状 | 仓库无 workspace；`cli/` 为 Node 24 type-stripping 零构建；`frontend/` 为 Vue3 + Vite | 根目录 |

关键推论：

- **扩展不能像 CLI 一样零构建**：VS Code 扩展宿主不执行 `.ts`，必须打包成 JS。因此构建只发生在插件包内部（esbuild bundle），不外溢到仓库其他部分。
- **diff 的基线不能靠猜**：`ChangeView.diffText` 是否完整 unified diff（而非截断预览）决定左侧文档如何构造，必须在 T2 先核实；核实前不承诺「一定能还原 before」。
- **VS Code 打开的文件夹 ≠ 项目工作区**时，磁盘 diff 与撤回都会指向另一份文件，必须在发起任务前显式校验并提示。
- **插件进程退出 ≠ 任务结束**：与 CLI 一样，审批等待期间后端任务仍在运行，重载后必须能按 `sessionId + runId` 恢复。

## 设计决策

1. **独立目录 `ide/vscode/`，TypeScript + esbuild 单文件 bundle**。运行时零 npm 依赖（HTTP 用扩展宿主自带的全局 `fetch`，VS Code ≥ 1.95 / Node ≥ 20）；`devDependencies` 仅构建与类型（`esbuild`、`typescript`、`@types/vscode`、`@vscode/vsce`）。产物 `dist/extension.js`，`vsce package` 出 vsix。
2. **代码分两层**：`src/shared/`（纯逻辑：SSE 解析、事件归约、续传去重、端点映射、diff 重建）只用可擦除 TS 语法，直接用 `node --test` 跑 `.ts` 单测，与 CLI 同一套测试方式；`src/extension.ts` 及 `src/vscode/`（依赖 `vscode` 模块的宿主层）不进单测，靠人工验收。
3. **SSE 与 CLI 同源、共用 fixture**：以 `cli/src/sse.ts` 为参考实现 `src/shared/sse.ts`，单测直接读 `cli/test/fixtures/sse/*.txt`（只读引用，不复制、不改 CLI）。将来抽共享包时迁移点仍是这一处（与 P6-02 README 记录一致）。
4. **令牌只进 SecretStorage**：`context.secrets.store('lunargold.clientToken', ...)`；`baseUrl` 走设置 `lunargold.baseUrl`（默认 `http://127.0.0.1:8080`）。不提供把令牌写入 settings.json 的能力。
5. **连接引导一条命令**：`Lunar Gold: 连接本机后端` 依次做：health 探测 → 已有 `lgc_` 令牌则校验 → 否则读本地用户令牌（`~/.lunargold/desktop/local-token`，可用 `lunargold.localTokenPath` 覆盖）调 `POST /api/clients` 换发 `vscode-<hostname>` 客户端（7 个 scope 全量）→ 存 SecretStorage。也支持手动粘贴 `lgc_` 令牌（密码输入框）。后端未启动时提示先运行 `scripts/dev-desktop.ps1`，不弹堆栈。
6. **工作区一致性前置校验**：选中项目的 `workspacePath` 与 VS Code 当前文件夹不一致时，弹出选择「打开该文件夹 / 仍要继续 / 取消」；继续则在面板持续显示「变更落在其他目录」的提示。理由：diff 与撤回都作用于磁盘，静默错位比不能用更糟。
7. **diff 用原生 `vscode.diff`，左侧来自 ChangeSet**：T2 已核实 `diffText` 来自 `SimpleDiff.compute(old, new, 400)`，是「单个 `@@` 块 + `- ` / `+ ` 行」的精简差异，不含上下文行；每一侧最多展示 400 行，超出会追加「省略 N 行」标记，原文件不可读时也可能只是说明文字，因此**不能假定为完整 unified diff**。仅在无省略标记、块位置与当前磁盘内容匹配时，用 `diffText` + 当前磁盘内容重建 before，放入 `lunargold-before:` 虚拟文档，右侧为磁盘文件；若存在省略、格式不符或文件已被再次修改，降级为「patch 预览（虚拟文档）+ 打开当前文件」，不伪造逐行对比。新增文件 before 为空文档，删除文件右侧为空文档。
8. **面板只做三块**：侧边栏 Webview（流式 + 审批卡片 + 操作按钮）、变更 TreeView、状态栏项。会话历史只提供「继续上次会话」（读 `workspaceState` + `GET /api/agent/sessions`），不做完整历史管理。
9. **恢复与续传复用 CLI 语义**：`workspaceState` 持久化 `{projectId, sessionId, runId, lastSeq}`；断线重试最多 3 次、退避 1s/2s/4s；`sinceSeq` 只取 `seq > lastSeq`；SSE 断流后的事件回放按 CLI 同一套去重策略（正文按累计长度取后缀，工具/审批/完成按指纹计数）。
10. **撤销即断连**：`Lunar Gold: 断开并撤销客户端` 调 `DELETE /api/clients/self` 并清除 SecretStorage 与本地任务状态；之后任何请求应得到 401/403 并引导重新连接。

## Functional Requirements

### FR-1 命令与入口

| 命令 ID | 标题 | 说明 | 端点 |
|---|---|---|---|
| `lunargold.connect` | 连接本机后端 | 见决策 5 | `GET /api/health`、`POST /api/clients`（本地令牌） |
| `lunargold.newTask` | 发起任务 | 选项目 → 输入 prompt → 提交 | `POST /api/agent/chat/stream` |
| `lunargold.openPanel` | 打开 Lunar Gold 面板 | 聚焦侧边栏 Webview | — |
| `lunargold.stop` | 停止当前生成 | 需确认 | `POST /api/agent/chat/stop?sessionId=` |
| `lunargold.refreshChanges` | 刷新变更列表 | | `GET /api/file-changes/changeset` |
| `lunargold.revertRun` | 撤回本次 Run 的变更 | modal 确认 | `POST /api/file-changes/revert-run` |
| `lunargold.revertSession` | 撤回本会话全部变更 | modal 确认 | `POST /api/file-changes/revert-session` |
| `lunargold.disconnect` | 断开并撤销客户端 | modal 确认 | `DELETE /api/clients/self` |

入口：命令面板、状态栏项（发起 / 停止 / 打开面板）、侧边栏视图容器。所有命令在未连接时先引导 `connect`。

### FR-2 连接与令牌

- 连接成功的判定：`GET /api/health` 返回 `status == "UP"` 且 `apiContractVersion == 1`；版本不匹配提示「插件与后端契约版本不一致，请升级插件或后端」。
- 401：提示令牌无效或已撤销，提供「重新连接」；403 且 message 含「缺少 scope」：提示重新走 `connect` 换发全量 scope 客户端。
- 令牌只允许两个来源：SecretStorage、用户手动粘贴；本地用户令牌仅用于换发，用后即弃，不存储。

### FR-3 发起任务与流式

- 项目选择来自 `GET /api/projects`（QuickPick，显示名称与工作区路径）；会话选择为「新会话」或既有会话（`GET /api/agent/sessions`）。
- 请求体首版只用 `prompt` / `sessionId` / `projectId`，与 CLI 一致。
- 面板渲染：`session_id` / `run_id` / `turn_id` 各显示一次；`content` 追加正文；`tool_call` / `tool_result` 单行摘要；`file_change` 刷新变更 TreeView；`approval_required` 出卡片；`done` / `error` 置终态并刷新变更。
- 状态栏：空闲 / 连接中 / 运行中（含 runId）/ 等待审批 / 失败，点击打开面板。

### FR-4 续传与恢复

- SSE 非正常结束（无 `done`）时按 `runId + lastSeq` 轮询 `events?sinceSeq=`，重试 3 次退避 1/2/4s；面板显示「已断线，正在重连（seq=N）」。
- 扩展重载后：若 `workspaceState` 有未终结 run，自动拉 `events` 续看；若停在等待审批，直接拉 `GET /api/agent/sessions/{sessionId}/approvals` 显示待审批卡片。
- 已消费事件不重复渲染（同 CLI 去重策略）。

### FR-5 变更与 diff

- TreeView 分组按 run 展示文件：路径、`action`、增删行数、状态（PENDING / ACCEPTED / REVERTED）。
- 点击文件执行 `vscode.diff`（决策 7）；新增 / 删除文件分别用空文档 / 仅左侧处理。
- 文件被外部再修改时不覆盖用户内容：diff 只读展示，撤回前重新拉 changeset 并以后端 `RevertResult` 冲突信息为准。
- 「接受」不做显式动作：与 Web 一致，变更落盘即事实，接受语义由撤回 / 保留表达（首版不接 Verify 端点，面板显示「未验证」标记）。

### FR-6 审批

- 卡片字段：工具名、风险（`WRITE` / `EXECUTE` / `DESTRUCTIVE`）、`preview`（路径 / 命令 / 增删行 / 说明）、过期时间。
- 操作：批准、拒绝、本会话记住（`remember: SESSION`，默认 `NONE`）；多个待审批时提供「全部批准」（`POST /api/agent/approvals/batch`）。
- 提交失败（已失效 / 不属于当前用户）原样透出后端 message，并刷新待审批列表。
- 插件不提供任何「自动批准」开关（与 CLI 的 `--auto-approve low` 不同：GUI 场景默认人来决定）。

### FR-7 撤回与停止

- 撤回前 modal 确认：列出将撤回的文件数与目标（本次 Run / 整个会话）；成功后刷新 TreeView 与打开的 diff，磁盘文件还原。
- 任务未结束时撤回，原样透出后端拒绝信息。
- 停止只作用于当前 `sessionId`；停止后面板保留已渲染内容并标记「已停止」。

### FR-8 设置与打包

- 设置仅两项：`lunargold.baseUrl`、`lunargold.localTokenPath`（可选，调试用）。
- `extensionKind: ["ui"]`；`engines.vscode` 与全局 `fetch` 可用性对齐（≥ 1.95）。
- `npm run package`（`vsce package`）产出 vsix；README 写明 `code --install-extension` 手动安装与卸载步骤。

## 影响面（明确不动）

- 后端 `src/main/java` 零改动；不新增端点、不改 scope、不改 `public-api.json`。
- `frontend/` 零改动；`cli/` 零改动（fixture 只读引用）。
- 不引入根级 `package.json` / workspace；构建与依赖全部限制在 `ide/vscode/` 内。
- 若发现公开子集不够用（例如 diff 需要完整 before 内容），**先回本 spec 讨论，再走 P6-01 的契约变更流程另开批次**，不在本批次顺手加端点。

## 验收

1. 纯逻辑单测全绿：`node --test "ide/vscode/test/**/*.test.ts"`；SSE 6 个 fixture 与 CLI 同源断言。
2. 端点静态校验：插件维护「端点 → 功能」映射，测试断言与 `public-api.json` 一致，唯一 local 例外为 `POST /api/clients`。
3. 手动验收（Extension Development Host + `scripts/cli-e2e.ps1` 同款后端环境）：连接 → 发起任务 → 流式 → 审批批准/拒绝 → diff 查看 → 撤回还原 → 停止 → 断网续传 → 撤销后失效，逐步过 checklist。
4. 打包 vsix 可在本机安装并重复第 3 条核心路径。
5. 零回归：后端 `mvn -o test` 331/0/0/15；CLI 71 条单测不变；`frontend/` 无 diff。

## 风险

| 风险 | 应对 |
|---|---|
| `diffText` 为截断预览，无法重建 before | T2 先核实；降级为 patch 预览 + 当前文件，不伪造 diff；确需完整 before 时另开契约批次 |
| 插件与 CLI 的 SSE / 续传逻辑分叉 | 共用同一组 fixture 与去重策略；解析与归约集中在 `src/shared/` |
| 工作区错位导致用户看错文件 | 发起前强制校验并提示；面板常驻错位提示 |
| 用户以为关闭面板即任务结束 | 终态与「等待审批」在状态栏常驻；重载自动恢复 |
| 远程窗口下回环与磁盘语义不成立 | `extensionKind: ui` + 远程窗口明确提示不支持 |
| 引入构建链后体积与维护成本上升 | 依赖只进 `devDependencies`，bundle 单文件；不改根工程 |
