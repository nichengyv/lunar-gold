# P6-02 CLI - 技术任务清单

> 约定：每个任务完成后跑验收命令，结果贴进 PR；跨任务的共享契约（SSE 事件名、退出码、NDJSON 字段）一旦改动必须同步改 spec。
> 运行方式：`node cli/src/main.ts`（Node ≥ 24，type-stripping，无构建步骤）。测试：`node --test "cli/test/**/*.test.ts"`。

## T1 脚手架与配置解析

- 新增 `cli/package.json`（`"type": "module"`、`bin.lg`、`scripts.test`，**无 dependencies**）、`cli/tsconfig.json`（`strict`、`noEmit`）、`cli/README.md`（命令表、退出码表、令牌引导）。
- 新增 `cli/src/config.ts`：按 spec FR-5 的优先级解析 `baseUrl` / `token` / 超时 / 配置文件路径；支持 `--base-url`、`LG_BASE_URL`、`--token`、`LG_TOKEN`。
- 新增 `cli/src/cli.ts`：用 `node:util` 的 `parseArgs` 做子命令分发，未知命令给用法提示。
- 约束：禁止 `enum` / 参数属性 / `namespace`（type-stripping 不支持）；类型用 `type` / `interface` + `as const` 对象。
- 完成判据：`node cli/src/main.ts --help` 列出 8 条命令；`node cli/src/main.ts bogus` 退出码 2；配置优先级有单测。

## T2 HTTP 客户端与信封/错误映射

- 新增 `cli/src/http.ts`：`fetch` 封装，注入 `Authorization: Bearer`，解析 `ApiResponse` 信封，超时用 `AbortSignal.timeout`。
- 错误映射：401 → 退出码 3；403 且 message 含「缺少 scope」→ 3 并附 `lg client create --scopes ...` 提示；403 其他 → 3；5xx/网络 → 1；422/400 业务错误 → 对应命令的语义码（审批 4、撤回 1）。
- 完成判据：每个状态码分支有单测（注入假 `fetch`）；错误输出不含堆栈（非 `--verbose`）。

## T3 SSE 解析器与 fixture

- 新增 `cli/src/sse.ts`：从 `frontend/src/api/sse.ts` 移植，保留 `fetch + ReadableStream`、CRLF、多行 `data:`、注释帧（心跳）、流尾残余事件、`[DONE]` 终止。
- fixture 放 `cli/test/fixtures/sse/*.txt`：`basic`、`crlf`、`multiline-data`、`heartbeat`、`tail-without-blank-line`、`done`；前端同步用同一组文件（`frontend/src/api/__fixtures__` 软链或复制，二选一并写进 README）。
- 完成判据：`node --test` 覆盖 6 个 fixture；断言事件名与 `data` 原样、注释帧不产生事件。

## T4 `lg run` 流式主流程

- 新增 `cli/src/commands/run.ts`：`POST /api/agent/chat/stream`，请求体字段对齐 `ChatRequest`（`prompt` / `sessionId` / `projectId` / `attachmentIds` / `model` / `reasoning` / `permission` / `useRag` / `clientRequestId`；首版只用 `prompt` / `sessionId` / `projectId`）。
- 事件渲染：`session_id` / `run_id` / `turn_id` 各打印一次；`content` 增量写 stdout；`tool_call` / `tool_result` 单行摘要；`approval_required` 交给 T5；`error` 退出 5；`done` 退出判定交给 T6。
- `--json`：输出 NDJSON，`type` ∈ `session` / `run` / `content` / `tool` / `approval` / `resume` / `result`。
- 完成判据：假 SSE 服务（`node:http` 起本地 stub）下三种场景测试：正常 `done`、`error`、中途断流；断流触发 T6 续传。

## T5 审批命令与自动批准

- 新增 `cli/src/commands/approve.ts`：`POST /api/agent/approvals/{approvalId}`，body `{decision: "APPROVE"|"REJECT", remember: "NONE"|"SESSION"}`；`--deny` → `REJECT`，`--remember` → `SESSION`。
- `run` 内 `approval_required`：人读打印工具名/风险/`preview`/过期时间 + 可复制命令；`--auto-approve low` 且 `risk` 为低风险时自动 `APPROVE`（`remember: NONE`），其余退出 4。
- 完成判据：审批成功退出 0；审批失效（后端返回业务错误）退出 4 且 message 原样；`--auto-approve` 只对低风险生效（两种风险各一条测试）。

## T6 `lg watch` 续传

- 新增 `cli/src/commands/watch.ts`：`GET /api/agent/runs/{runId}/events?sinceSeq=`，按 `seq` 单调去重后渲染；断线重试最多 3 次，退避 1s/2s/4s，重试前打印 `[resume from seq=N]`。
- 复用 T4 的渲染器，不复制事件处理逻辑（抽 `cli/src/render.ts`）。
- 完成判据：stub 服务先返回 seq 1..3 后断流，再返回 seq 4..5 + `done`，断言 stdout 无重复事件、退出 0；`sinceSeq` 参数值正确。

## T7 变更集与撤回

- 新增 `cli/src/commands/changes.ts`：`GET /api/file-changes/changeset?sessionId=&runId=`。
- 新增 `cli/src/commands/revert.ts`：`POST /api/file-changes/revert-run?sessionId=&runId=` 或 `revert-session?sessionId=`，**查询参数而非 body**；无 `--yes` 且非 TTY → 退出 2 并提示。
- 完成判据：stub 断言请求 URL 带上查询参数、无 body；`--yes` 缺失时非 TTY 退出 2；成功时打印 `RevertResult` 摘要。

## T8 客户端与健康检查

- 新增 `cli/src/commands/client.ts`：`create`（本地令牌 → `POST /api/clients`，落盘 0600）、`show`（`GET /api/clients/self`，不回显明文）、`revoke`（`DELETE /api/clients/self`）。
- 新增 `cli/src/commands/doctor.ts`：`GET /api/health`，比对 `apiContractVersion === 1`。
- 完成判据：无本地令牌时提示可执行命令而非堆栈；`client revoke` 后同令牌下一次请求退出 3；`doctor` 在版本不匹配时退出 1。

## T9 端到端脚本

- 新增 `scripts/cli-e2e.ps1`：起 desktop 后端（复用 P6-01 自检的环境变量写法，端口用 `18082`）→ 在 `cli/test/fixtures/workspace` 副本里跑 `run` → 断言变更与 `changes` 输出 → `revert` 后文件还原 → `client show`/`revoke` 收尾。
- 脚本失败必须打印后端日志尾部与 CLI stderr，便于 CI 定位。
- 完成判据：脚本在真机绿；重复跑不依赖残留状态（每次自建 workspace 副本与 deck 端口的 DB 由后端自管）。

## T10 文档与收口

- `cli/README.md` 补：令牌引导、8 条命令示例、退出码表、`--json` 字段说明、已知限制（首版不跑 Verify、不做客户端列表）。
- 在 `docs/phases/phase6/plan.md` 的 P6-02 行标注完成（含实测退出码矩阵）。
- 完成判据：README 的命令示例逐条可复制执行；`mvn -o test` 与前端测试零回归。

## 依赖顺序

T1 → T2 → T3 →（T4、T7 可并行）→ T5/T6 → T8 → T9 → T10。

## 不做

- 不修改任何 `src/main/java`；不新增公开端点。
- 不引入 npm 依赖或构建步骤；不改 `frontend/` 构建配置。
- 不实现 `documents` / `workspace` / `settings` / `api-key` / `mcp` 子命令。
