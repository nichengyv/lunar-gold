# lg —— Lunar Gold 本地命令行

Node + TypeScript 写的第二入口：在终端里完成「发起任务 → 看流式 → 审变更 → 审批 → 撤回」。
只调用 P6-01 冻结的公开子集（`src/test/resources/contract/public-api.json`），不新增后端端点。

## 运行环境

- Node **≥ 24**（依赖内置 TS type-stripping，直接跑 `.ts`，**没有构建步骤**）。
- 无需 `npm install`：`cli/package.json` 没有任何 dependencies。
- 只能用可擦除 TS 语法：`interface` / `type` / `as const` 可以，`enum` / 参数属性 / `namespace` 禁止。

```powershell
node cli/src/main.ts --help
# 或在 cli 目录下
node src/main.ts --help
```

## 令牌引导（首次使用）

1. 启动桌面端（会生成 `~/.lunargold/desktop/local-token`）：

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\dev-desktop.ps1
   ```

2. 用本地令牌换客户端令牌（`lgc_<client_id>_<secret>`），写入 `~/.lunargold/cli/config.json`：

   ```powershell
   node cli/src/main.ts client create --name cli
   ```

3. 自检：

   ```powershell
   node cli/src/main.ts doctor
   ```

   令牌来源优先级：`--token` > `LG_TOKEN` > 配置文件；
   地址优先级：`--base-url` > `LG_BASE_URL` > 配置文件 > `http://127.0.0.1:8080`。
   `client create` 的本地令牌来源：`--local-token` > `LG_LOCAL_TOKEN` > `~/.lunargold/desktop/local-token`。

> 令牌只在签发时出现一次，配置文件不会回显明文；`client revoke` 会同时清掉本地配置里的令牌。

## 命令与示例

| 命令 | 作用 | 端点 |
|---|---|---|
| `run <prompt>` | 发起任务并跟随 SSE | `POST /api/agent/chat/stream` |
| `watch <runId>` | 续看已有 Run（按 `seq` 增量） | `GET /api/agent/runs/{runId}/events` |
| `changes --session <id> [--run <id>]` | 打印变更集 | `GET /api/file-changes/changeset` |
| `approve <approvalId> [--deny] [--remember]` | 提交审批 | `POST /api/agent/approvals/{approvalId}` |
| `revert --session <id> [--run <id>] [--yes]` | 撤回变更 | `POST /api/file-changes/revert-run` / `revert-session` |
| `stop --session <id>` | 停止当前生成 | `POST /api/agent/chat/stop` |
| `client create --name <n> [--scopes a,b]` | 签发客户端令牌 | `POST /api/clients`（本地令牌） |
| `client show` / `client revoke` | 查看/撤销自身 | `GET` / `DELETE /api/clients/self` |
| `doctor` | 探测契约版本与令牌 | `GET /api/health` |

可直接复制执行：

```powershell
node cli/src/main.ts run "把 README 的安装步骤补全" --session 3
node cli/src/main.ts run "重构 src/utils.ts" --project 2 --auto-approve low
node cli/src/main.ts watch 42
node cli/src/main.ts changes --session 3 --run 42
node cli/src/main.ts approve 7f3c9a2e
node cli/src/main.ts approve 7f3c9a2e --deny --remember
node cli/src/main.ts revert --session 3 --run 42 --yes
node cli/src/main.ts revert --session 3
node cli/src/main.ts stop --session 3
node cli/src/main.ts client create --name ci --scopes chat:write,run:read
node cli/src/main.ts client show
node cli/src/main.ts client revoke
node cli/src/main.ts doctor --base-url http://127.0.0.1:18082
```

全局参数：`--base-url`、`--token`、`--json`、`--quiet`、`--timeout <秒>`。

- `run` 遇到 `approval_required` 会打印工具名、风险、preview、过期时间与可复制命令，然后以退出码 4 结束；
  **进程退出不等于任务结束**，审批后可用 `lg watch <runId>` 继续看。
- `--auto-approve low` 只自动批准低风险（`risk == WRITE`）；`EXECUTE` / `DESTRUCTIVE` 仍然停在退出码 4。
- `revert` 必须显式 `--session`；无 `--yes` 且非 TTY 时直接退出 2，交互终端会先列出待撤回文件再确认。
- `client create` 默认签发全量 CLI scope；想最小授权就显式传 `--scopes`。

## 退出码

| 码 | 含义 | 典型触发 |
|---|---|---|
| 0 | 成功 | 收到 `done` 且没有文件变更；只读命令成功 |
| 1 | 内部/网络错误 | 无法连接、5xx、响应结构不合法、doctor 版本不匹配 |
| 2 | 用法错误 | 参数缺失/冲突、非 TTY 未加 `--yes`、缺本地令牌 |
| 3 | 认证/授权失败 | 401（令牌无效/已撤销）、403（scope 不足/接口未开放） |
| 4 | 需要人工审批 | 流停在 `approval_required` 且未自动批准；审批提交失败 |
| 5 | 任务未完成 | 收到 `error`、被 `stop`、FAILED/CANCELLED、流结束但无法续看 |
| 6 | 有变更但未验证 | `done` 后存在文件变更（首版不触发 Verify） |

403 且 message 含「缺少 scope」时，stderr 会追加一条可直接执行的补 scope 命令。

## `--json` 输出（NDJSON）

stdout 每行一个 JSON 对象，`type` 固定为以下 7 种：

| type | 字段 |
|---|---|
| `session` | `sessionId` |
| `run` | `runId` |
| `content` | `text`（增量文本） |
| `tool` | `event`（`call`/`result`/`file_change`）、`tool`、`args`、`success`、`costMs`、`error`、`path`、`additions`、`deletions` |
| `approval` | `approvalId`、`tool`、`risk`、`expiresAt`、`preview` |
| `resume` | `sinceSeq` |
| `result` | `exitCode`、`status`、`sessionId`、`runId`、`turnId`、`filesChanged`、`completion`、`error` |

`changes` / `revert` / `client show` 的 `result` 里带 `data`（后端原始结构）；
`run` / `watch` 结束一定会输出一行 `result`，脚本应以它为准，而不是解析正文。

## 测试

```powershell
node --test "cli/test/**/*.test.ts"
```

端到端（真机起 desktop 后端，端口 18082）：

```powershell
# 只用已验证的模型 Key（库里已有 Key 时可省略 -ApiKey）
powershell -ExecutionPolicy Bypass -File scripts\cli-e2e.ps1 -ApiKey $env:DEEPSEEK_API_KEY

# 不跑需要模型的部分，只验证启动 / client / doctor
powershell -ExecutionPolicy Bypass -File scripts\cli-e2e.ps1 -SkipRun
```

脚本每次自建 `build\cli-e2e\data` 与工作区副本，失败时打印后端日志尾部与 CLI 输出。

## SSE 解析与 fixture

解析器在 `cli/src/sse.ts`，移植自 `frontend/src/api/sse.ts`；fixture 在
`cli/test/fixtures/sse/`：`basic` / `crlf` / `multiline-data` / `heartbeat` /
`tail-without-blank-line` / `done`。

> 本次没有在 `frontend/` 下新增 fixture 副本（硬约束：`frontend/` 零改动）。
> 两端协议一致性目前由同一组用例定义；将来抽共享包时，迁移点只有 `cli/src/sse.ts` 与这组文件。

## 已知限制

1. **SSE 帧没有事件序号**：流内 `id` 是每连接自增的连接计数器，不等于库里的 `seq`。
   因此 `run` 断流后的续看只能「全量回放 + 去重」：
   - 正文按累计长度取后缀（后端 `MODEL_CHUNK` 的 `payload.text` 是累计全文）；
   - 工具/审批/完成事件按指纹计数跳过已输出次数；
   - `[resume from seq=N]` 里的 N 是本次已确认的最大 `seq`，首次断流时为 0。
   `watch` 直接用返回的 `seq` 单调续看，不受此限制影响。
2. **首版不触发 Verify**：只要本轮有文件变更，`done` 后退出码就是 6，不代表任务失败。
3. **不做客户端列表**：P6-01 只开放 `/self`，`client show|revoke` 只作用于当前令牌；跨客户端管理回 Web。
4. 不做交互式 TUI、命令补全、`documents` / `workspace` / `settings` / `api-key` / `mcp` 子命令。
