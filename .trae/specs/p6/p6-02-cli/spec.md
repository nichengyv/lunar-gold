# P6-02 CLI - Product Requirements Document

## Overview

- **Summary**：新增一个 Node + TypeScript 命令行工具 `lg`，通过 P6-01 冻结的公开子集（`Authorization: Bearer lgc_...`）在终端里完成「发起任务 → 看流式 → 审变更 → 审批 → 撤回」。
- **Purpose**：让同一份智能体能力在第二个入口可用，不必打开 Web 工作台；同时用真实调用方验证 P6-01 契约是否够用。
- **Target Users**：本地开发者、脚本与 CI 场景；后续 P6-03 VS Code 插件复用同一套调用方式。

## Goals

1. 一条命令跑完一次任务，终端里能看到 `content` / `tool_call` / `tool_result` / 审批 / `done` 的流式过程。
2. 断线可续：用 `runId + sinceSeq`（对应后端 `GET /api/agent/runs/{runId}/events`）接着看，不重放已消费事件。
3. 变更可审可撤：`changes` 打印 ChangeSet，`revert` 按 run 或 session 撤回。
4. 审批可执行：读到 `approval_required` 时给出可复制的 `approve` 命令，非交互场景退出码可判定。
5. `--json` 输出稳定结构，供脚本消费；退出码语义固定，有测试。
6. 首次运行能从本地令牌换取客户端令牌，之后只保存客户端令牌。

## Non-Goals

- 不做交互式 TUI、不做命令补全、不做插件宿主。
- 不内置模型 Key，不读写用户 Key 数据库（沿用后端已存 Key）。
- 不做离线模式、不做本地模型推理、不做公网访问。
- 不新增后端端点；所有调用只落在 P6-01 的 15 个公开端点上。
- 不做 OpenAPI → TS 自动生成（P6-01 明确未做 schema 级快照，见 `p6-01/spec.md` FR-9）。
- 不做 `documents` / `workspace` / `settings` / `api-key` / `mcp` 相关能力。

## Background & Context

| 项 | 事实 | 位置 |
|---|---|---|
| 公开子集 | 15 个端点已冻结，清单外对客户端令牌 403 | `config/PublicApiCatalog.java`、`src/test/resources/contract/public-api.json` |
| 令牌 | `lgc_<client_id>_<secret>`，库内 SHA-256，撤销即时生效 | `service/ApiClientService.java` |
| scope | `chat:write` / `run:read` / `changes:read` / `changes:write` / `approval:write` / `project:read` / `client:admin` | `security/ApiScope.java` |
| 流式 | `POST /api/agent/chat/stream` 返回 SSE；事件名含 `session_id` / `run_id` / `turn_id` / `thinking` / `content` / `tool_call` / `tool_result` / `approval_required` / `approval_resolved` / `file_change` / `usage` / `done` / `error`；心跳为注释帧 | `controller/support/ChatSseChannel.java`、`ChatStreamBridge.java` |
| 续传 | `GET /api/agent/runs/{runId}/events?sinceSeq=`，`sinceSeq` 为空从头取，否则取 `seq > sinceSeq` | `AgentChatController.java`、`AgentRunService.java` |
| SSE 解析参考 | 已有 `fetch + ReadableStream` 手写解析，支持 CRLF、多行 `data:`、流尾残余事件 | `frontend/src/api/sse.ts` |
| 审批 | `POST /api/agent/approvals/{approvalId}`，body `{decision: APPROVE|REJECT, remember: NONE|SESSION}`；批量 `POST /api/agent/approvals/batch`，body `{sessionId, approvalIds}`（只允许 APPROVE） | `AgentChatController.java:419-456` |
| 撤回 | `POST /api/file-changes/revert-run?sessionId=&runId=`、`POST /api/file-changes/revert-session?sessionId=`（**查询参数**，非 JSON body） | `ChangeSetController.java:46-57` |
| 变更集 | `GET /api/file-changes/changeset?sessionId=&runId=` | `ChangeSetController.java:36-44` |
| 停止 | `POST /api/agent/chat/stop?sessionId=` | `AgentChatController.java:401` |
| 工程现状 | 仓库根无 `package.json`，无 workspace；前端为 Vue3 + Vite + vitest；无 OpenAPI 生成脚本 | 根目录、`frontend/package.json` |

关键推论：

- 后端已有 SSE 事件名与前端解析器，CLI 不应另造协议；但当前前端解析器写死在 `frontend/src/api/sse.ts`，直接跨包引用会把 CLI 构建绑到 Vite 工程上。
- 审批发生在 Run 进入 `WAITING_APPROVAL` 期间，SSE 连接保持；因此「CLI 进程退出」不等于「任务结束」，`watch` 必须能独立续看。
- 撤回接口用查询参数，CLI 若按 JSON body 调用会 400/参数缺失，必须在实现时对齐。

## 设计决策

1. **独立包 `cli/`，零运行时依赖**。本机 Node v24.21 已启用 TS type-stripping（`process.features.typescript === "strip"`），因此直接 `node cli/src/main.ts` 运行，**不引入 npm 依赖、不需要 `npm install`、没有构建步骤**，与仓库 `mvn -o` 的离线风格一致。代价：只能用可擦除语法（`interface` / `type` / `const` 合法，`enum`、参数属性、`namespace` 禁止）。理由：不碰前端构建链，避免为了共享一个解析器引入 workspace 与路径别名。
2. **SSE 解析器移植而非跨包引用**：以 `frontend/src/api/sse.ts` 为参考在 `cli/src/sse.ts` 重写，两端各用自己的运行器（frontend 用 vitest、cli 用 `node --test`），但**共用同一组 fixture 文件**（CRLF、多行 `data:`、心跳注释、流尾残余、`[DONE]`）。若将来抽共享包，迁移点只有这一处。理由：fixture 对齐即可防协议分叉，成本低于现在就拆 workspace。
3. **类型手写、不对齐生成器**：CLI 的 `cli/src/types.ts` 手写请求/响应类型，字段以后端 DTO 为准，并用 `src/test/resources/contract/public-api.json` 做「只用公开子集」的静态校验（CLI 侧维护一份端点→命令映射，测试断言两者一致）。
4. **令牌引导**：`lg client create` 用**本地用户令牌**（桌面端 `~/.lunargold/desktop/local-token`，或 `LG_LOCAL_TOKEN`、`LG_TOKEN`）调 `POST /api/clients` 换取 `lgc_` 令牌，落到 `~/.lunargold/cli/config.json`（POSIX 下 0600）。之后所有命令只用客户端令牌。
5. **不做客户端列表**：P6-01 只开放 `/api/clients/self`，`lg client show|revoke` 仅作用于自身；跨客户端管理引导回 Web。
6. **审批默认不自动通过**：`run` 遇到 `approval_required` 打印审批摘要与可复制命令并退出（码 4）。另提供 `run --auto-approve low`（仅 APPROVE 低风险，复用单条审批接口）供脚本使用，默认关闭。
7. **默认人读、`--json` 机读**：人读走 stdout（进度/正文），诊断走 stderr；`--json` 时 stdout 只输出一行一个 JSON 对象（NDJSON），字段固定并有快照测试。

## Functional Requirements

### FR-1 命令集

| 命令 | 作用 | 端点 | 必需 scope |
|---|---|---|---|
| `lg run <prompt>` | 发起任务并跟随流 | `POST /api/agent/chat/stream` | `chat:write` |
| `lg watch <runId>` | 续看已有 Run | `GET /api/agent/runs/{runId}/events` | `run:read` |
| `lg changes --session <id> [--run <id>]` | 打印变更集 | `GET /api/file-changes/changeset` | `changes:read` |
| `lg approve <approvalId> [--deny] [--remember]` | 提交审批 | `POST /api/agent/approvals/{approvalId}` | `approval:write` |
| `lg revert --session <id> [--run <id>] [--yes]` | 撤回变更 | `POST /api/file-changes/revert-run` / `revert-session` | `changes:write` |
| `lg stop --session <id>` | 停止当前生成 | `POST /api/agent/chat/stop` | `chat:write` |
| `lg client create --name <n> [--scopes ...]` | 签发客户端令牌 | `POST /api/clients`（本地令牌） | — |
| `lg client show` / `lg client revoke` | 查看/撤销自身 | `GET|DELETE /api/clients/self` | `client:admin` |
| `lg doctor` | 探测兼容性 | `GET /api/health` | 仅需有效令牌 |

全局参数：`--base-url`（默认 `http://127.0.0.1:8080`，可用 `LG_BASE_URL`）、`--token`（可用 `LG_TOKEN`）、`--json`、`--quiet`、`--timeout <s>`。

### FR-2 流式与续传

- `run` 解析 SSE：`data:` 为纯值的 `session_id` / `run_id` / `turn_id` 单独记录并在人读模式打印一次；`content` 增量打印正文；`tool_call` / `tool_result` 打印一行摘要；`approval_required` 打印审批卡片摘要；`done` 结束；`error` 以失败退出。
- 连接中断（网络错误 / 非 `done` 结束）时，按 `runId + 最后消费的 seq` 调 `GET /api/agent/runs/{runId}/events?sinceSeq=` 续看，指数退避重试，默认最多 3 次；重试前打印 `[resume from seq=N]` 到 stderr（`--json` 时为 `{"type":"resume","sinceSeq":N}`）。
- 已消费事件不重复输出：以 `seq` 单调去重。
- 后端事件里的 `seq` 是续传唯一依据；不依赖 `Last-Event-ID`（CLI 不是浏览器）。

### FR-3 变更与撤回

- `changes` 人读输出：每个文件一行（状态、路径、增删行数），末尾汇总；`--json` 输出后端 `ChangeSetView` 原样结构到 `.data`。
- `revert` 必须显式 `--session`；带 `--run` 时按 run 撤回，否则按 session；不带 `--yes` 时打印将撤回的文件数与目标，并要求交互确认（非 TTY 时直接拒绝并提示加 `--yes`）。
- 撤回成功打印后端 `RevertResult` 摘要；失败（如任务未结束）原样透出后端 message。

### FR-4 审批

- `approval_required` 的 `data.approvalId` 是唯一入口；人读输出工具名、风险、`preview` 摘要、过期时间，并打印：
  `lg approve <id>` / `lg approve <id> --deny`。
- `--remember` 映射 `remember: SESSION`，否则 `NONE`；`--deny` 映射 `decision: REJECT`。
- `run --auto-approve low`：收到 `approval_required` 且 `risk` 为低风险时自动 `APPROVE`（`remember: NONE`），其他风险仍按退出码 4 退出。
- 审批接口返回 400/业务错误（已失效、不属于当前用户）时，退出码 4，message 原样打印。

### FR-5 配置与令牌

- 读取顺序：`--token` > `LG_TOKEN` > `~/.lunargold/cli/config.json`。
- `client create` 的本地令牌来源：`--local-token` > `LG_LOCAL_TOKEN` > `~/.lunargold/desktop/local-token`；都没有时提示「桌面端未启动或未生成令牌，先运行 scripts/dev-desktop.ps1」。
- 令牌文件写入后 chmod 0600（Windows 跳过）；`client show` 不回显明文令牌。
- 配置优先级：`--base-url` > `LG_BASE_URL` > 配置文件 > 默认值。

### FR-6 输出与退出码

退出码（`--json` 时同时写入 `{"type":"result","exitCode":N,...}`）：

| 码 | 含义 | 触发条件 |
|---|---|---|
| 0 | 成功 | 收到 `done`，且本次没有文件变更 |
| 1 | 内部/网络错误 | 无法连接、5xx、未知响应结构、重试耗尽 |
| 2 | 用法错误 | 参数缺失/冲突、非 TTY 未加 `--yes` |
| 3 | 认证/授权失败 | 401（令牌无效/已撤销）或 403（scope 不足/接口未开放） |
| 4 | 需要人工审批 | 流停在 `approval_required` 且未自动批准，或审批提交失败 |
| 5 | 任务未完成 | 收到 `error`、被 `stop`、流结束但无 `done` |
| 6 | 变更已落盘但未验证 | `done` 后 `changes` 显示存在文件变更（CLI 首版不触发 Verify） |

- 403 且 message 含 `缺少 scope` 时，追加一行修复提示：`lg client create --name cli --scopes <当前+缺失>`。
- `--json`：stdout 仅 NDJSON，每行一个对象，`type` ∈ `session` / `run` / `content` / `tool` / `approval` / `resume` / `result`；字段名固定，有快照测试。
- 人读模式：正文与进度到 stdout，诊断/重试到 stderr；`--quiet` 只保留结果与错误。

### FR-7 `doctor`

- 调 `GET /api/health`，断言 `status == "UP"` 且 `apiContractVersion` 等于 CLI 编译期常量（同为 `1`）；不匹配时提示「CLI 与后端契约版本不一致，请升级 CLI 或后端」并退出 1。
- 同时检查令牌可解出 `client_id`、配置文件可写。

## 影响面（明确不动）

- 后端代码零改动；不新增端点、不改 scope 清单。
- `frontend/` 构建链与依赖零改动（不引入 workspace、不改 `vite.config.ts`）。
- 不修改 `PublicApiCatalog` 与契约快照；若 CLI 需要新端点，走 P6-01 的「注解 + 清单 + 快照」流程另开批次。

## 验收

1. 脚本化端到端：起桌面端后端 → 在 fixture 工作区 `lg run` 完成一次任务 → 断言文件变更、`changes` 输出、审批、`revert` 后文件还原、退出码。
2. `lg watch` 能在 `run` 进程退出后接着看，且不重复输出已消费事件（用 `sinceSeq` 断言）。
3. `--json` 每种 `type` 都有稳定性测试（快照），字段增删必须显式改快照。
4. 401 / 403 / 审批未处理 / 任务失败 / 有变更未验证 五类退出码各有一条测试。
5. 缺令牌时给出可执行的修复提示（含具体命令），不打印堆栈。
6. 前端 `npm run build` 与现有测试零回归；后端 `mvn -o test` 331/0/0/15 不变。

## 风险

| 风险 | 应对 |
|---|---|
| SSE 解析前后端分叉 | 两端共用同一组 fixture 单测；解析器集中在一个文件 |
| CLI 进程退出导致用户以为任务结束 | 退出码 4 与 `watch` 提示必须明确「任务仍在等待审批」 |
| 撤回误伤 | 必须显式 `--session`；无 `--yes` 时需 TTY 确认 |
| `revert-*` 用查询参数，按 JSON body 调会失败 | 端到端测试覆盖四个写接口的调用形态 |
| 手写类型与后端 DTO 漂移 | CLI 侧维护端点→命令映射，测试与 `public-api.json` 比对；字段级漂移由 e2e 兜底 |
| 首版把「未验证」做成噪音（几乎每次退出 6） | 在帮助与 README 中写明语义；后续接入 Verify 端点再收敛 |
