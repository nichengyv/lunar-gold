# P6-02 CLI - 验收清单

> `[x]` 表示已由测试或真机验证；未勾选项在收口前必须逐条交代原因。

## 证据汇总（2026-11-16）

| 验证 | 命令 | 结果 |
|---|---|---|
| CLI 单测 | `node --test "cli/test/**/*.test.ts"` | `tests 71 / pass 71 / fail 0` |
| 帮助与未知命令 | `node cli/src/main.ts --help`、`node cli/src/main.ts bogus` | 列出 8 条命令；退出码 2 |
| 真机 e2e | `powershell -ExecutionPolicy Bypass -File scripts/cli-e2e.ps1 -DataDir build/cli-e2e/seed -KeepData` | `cli-e2e 通过`（run exit 6、note.txt 生成、changes 含文件、revert 后删除） |
| 后端回归 | `$env:JAVA_HOME='C:\Users\16680\.jdks\temurin-24'; mvn -o test` | `Tests run: 331, Failures: 0, Errors: 0, Skipped: 15` |

## 工程与依赖

- [x] `cli/package.json` 无 `dependencies`，`node cli/src/main.ts --help` 可用（Node 24 type-stripping）。
  - 证据：`package.json` 只有 `bin` / `engines.node >=24` / `scripts.test`，无 `dependencies`；`--help` 实测输出 8 条命令。
- [x] 源码未使用 `enum` / 参数属性 / `namespace`（`grep -n "enum \|private .*(" cli/src` 无命中）。
  - 证据：`Select-String` 扫描 `cli/src/**/*.ts` 无命中；类型统一用 `type` / `interface` + `as const`。
- [x] `node --test "cli/test/**/*.test.ts"` 全绿。
  - 证据：71 pass / 0 fail（含 `--json` approval 与 resume 行字段断言）。
- [x] `frontend/` 的 `package.json`、`vite.config.*`、`tsconfig*.json` 与本次改动前一致（`git diff --stat frontend/` 为空）。
  - 偏差说明：`git diff --stat frontend/` 非空，但只有 `frontend/src/api/fileChanges.ts` 与 `frontend/src/stores/chat.ts` 两处，来自 P5-14 RevertResult 收口（`RevertResult` 类型与 `conflicts[0].reason`），**P6-02 期间未改动 `frontend/` 任何文件**；构建配置与依赖零变化。

## 命令与契约

- [x] 8 条命令 `run` / `watch` / `changes` / `approve` / `revert` / `stop` / `client` / `doctor` 均可执行，`--help` 有用法。
  - 证据：`cli.test.ts` 遍历 8 条命令断言 `--help` 文本包含用法；`cli/src/cli.ts` 的 `helpText()` 列出命令、全局参数与退出码。
- [x] CLI 只用 `src/test/resources/contract/public-api.json` 里的端点；测试断言「端点→命令映射」与该清单一致，无清单外调用。
  - 证据：`endpoints.test.ts` 4 条用例，client 端点逐个比对契约快照；唯一 local 例外 `POST /api/clients` 被单独用例锁定且断言其不在公开子集内。
- [x] `revert-run` / `revert-session` 用查询参数调用（stub 断言无 body）。
  - 证据：`revert.test.ts` 断言 URL 含 `sessionId`/`runId`，请求 body 为空。
- [x] `approvals` 请求体字段为 `decision` / `remember`，`deny` 与 `--remember` 映射正确。
  - 证据：`approve.test.ts` 断言 `{decision:'REJECT',remember:'SESSION'}` 等组合。
- [x] `changeset` 请求带 `sessionId`，有 `--run` 时带 `runId`。
  - 证据：`changes.test.ts` 断言 query 参数组合。

## 流式与续传

- [x] `run` 正常结束（`done`）退出 0；无文件变更时打印结果摘要。
  - 证据：`run.test.ts`「正常 done」退出 0 且输出 `完成` 摘要；`--json` 用例断言 `result.filesChanged=false`。
- [x] `run` 收到 `error` 退出 5，message 来自后端。
  - 证据：`run.test.ts` 断言退出 `EXIT.INCOMPLETE` 且输出后端 message。
- [x] `run` 中途断流时按 `sinceSeq` 续传，`[resume from seq=N]` 出现且事件不重复。
  - 证据：`run.test.ts`「中途断流」断言 `[resume from seq=0]`、输出含 `abcdef` 且不含 `abcabc`；`--json` 用例断言 `{"type":"resume","sinceSeq":0}`。
  - 说明：SSE 帧本身不带 DB `seq`，`run` 采用「全量回放 + 累计正文长度取后缀 + 工具/审批/完成按指纹计数」去重；限制已写入 README。
- [x] `watch` 在 `run` 进程退出后能接着看，`sinceSeq` 值为已消费最大 `seq`。
  - 证据：`watch.test.ts` 断言第二次请求 `sinceSeq=1`、`[resume from seq=1]`、等待 1000ms。
- [x] SSE 解析覆盖 6 个 fixture（`basic`/`crlf`/`multiline-data`/`heartbeat`/`tail-without-blank-line`/`done`），心跳注释不产生事件。
  - 证据：`sse.test.ts` 8 条用例逐一覆盖；`heartbeat` 断言事件数为 0。

## 审批

- [x] `approval_required` 打印工具名、风险、`preview`、过期时间，并给出可复制命令。
  - 证据：`run.test.ts` 断言 `lg approve ap-1` 与 `lg approve ap-1 --deny`；`render.ts` 依次打印路径/命令/说明/增删行/过期时间。
- [x] `lg approve <id>` 成功退出 0；`--deny` 提交 `REJECT`；`--remember` 提交 `SESSION`。
  - 证据：`approve.test.ts` 三条用例分别断言默认、`--deny`、`--remember`。
- [x] 审批失效/不属于当前用户时退出 4，message 原样透出。
  - 证据：`approve.test.ts` 业务错误断言退出 `EXIT.APPROVAL` 且 message 未被改写；`http.test.ts` 断言 `businessExitCode` 生效。
- [x] 未处理审批时 `run` 退出 4，并提示「任务仍在等待审批，可用 lg watch 续看」。
  - 证据：`run.test.ts` 断言 stderr 含「任务仍在等待审批」。
- [x] `--auto-approve low` 只对低风险自动 `APPROVE`，高风险仍退出 4。
  - 证据：`run.test.ts` 两条用例：`WRITE` 自动批准并提交 `{decision:'APPROVE',remember:'NONE'}`；`EXECUTE` 退出 4 且未调用审批接口。

## 退出码与输出

- [x] 0/1/2/3/4/5/6 七种退出码各有测试（spec FR-6 表格）。
  - 证据：0（run/watch/changes）、1（`http.test.ts` 5xx、`doctor.test.ts` 版本不匹配）、2（`revert.test.ts` 非 TTY 无 `--yes`、`client.test.ts` 缺本地令牌、`cli.test.ts` 未知命令）、3（401/403，`http.test.ts`/`watch.test.ts`/`client.test.ts`）、4（`run.test.ts`/`approve.test.ts`）、5（`run.test.ts` error、`watch.test.ts` 失败终态）、6（`watch.test.ts` 有变更；`run` 的 6 由真机 e2e 覆盖）。
- [x] 401 退出 3，不打印堆栈。
  - 证据：`http.test.ts` 断言 `exitCode=3`；`cli.ts` 仅在 `--verbose` 时打印堆栈。
- [x] 403 且 `缺少 scope` 时退出 3，并给出补 scope 的具体命令。
  - 证据：`http.test.ts`「403 + 缺少 scope」断言 message 含 `缺少 scope` 与重签命令；`changes.test.ts` 走同一路径。
- [x] `--json` 的每种 `type` 都有快照测试；`result` 事件包含 `exitCode`。
  - 证据：`session`/`run`/`tool`/`content`/`result` 在 `run.test.ts` 的 `--json` 用例中断言完整序列；`approval` 与 `resume` 由新增的「approval 行字段稳定」「断流续看输出 resume 行」两条用例断言字段。
- [x] 非 TTY 下 `revert` 无 `--yes` 退出 2；有 `--yes` 正常执行。
  - 证据：`revert.test.ts` 注入 `isTty:false` 断言退出 2 与 `--yes` 提示；带 `--yes` 断言请求发出。
- [x] `--quiet` 下 stdout 只有结果与错误。
  - 实现与偏差：`render.ts` 的 `progress()` 在 `--quiet` 下全部抑制，正文与 `result` 摘要保留（正文是任务产出，不应被静默）；`approve`/`changes`/`stop`/`run` 的提示行均受 `quiet` 控制。该行为由代码路径覆盖，未单列用例（`config.test.ts` 仅断言 `quiet` 解析）。

## 令牌与配置

- [x] 配置优先级 `--token` > `LG_TOKEN` > 配置文件；`--base-url` > `LG_BASE_URL` > 配置文件 > 默认 `http://127.0.0.1:8080`。
  - 证据：`config.test.ts` 7 条用例覆盖三条来源的组合与默认值。
- [x] `client create` 从 `~/.lunargold/desktop/local-token`（或 `LG_LOCAL_TOKEN`）引导，缺令牌时提示「先运行 scripts/dev-desktop.ps1」。
  - 证据：`client.test.ts` 断言缺令牌退出 2 且提示 `scripts/dev-desktop.ps1`；真机 e2e 用数据目录下的 `local-token` 成功签发。
- [x] 配置文件落盘权限 0600（POSIX）；`client show` 不回显明文令牌。
  - 证据：`writeConfigFile` 调用 `restrictPermissions`，POSIX 下 `chmodSync(0o600)`；`client show` 只打印 `clientId`/名称/scope/时间。偏差：本次真机为 Windows，`restrictPermissions` 在 `win32` 直接返回，未实测 POSIX 权限位。
- [x] `client revoke` 后同令牌下一次请求退出 3。
  - 证据：`client.test.ts` 撤销后请求断言退出 `EXIT.AUTH`；真机 e2e 收尾执行 `client show`/`revoke` 均为 0。

## 端到端

- [x] `scripts/cli-e2e.ps1` 在真机绿：`run` 产生文件变更 → `changes` 打印 → `revert` 后文件还原。
  - 证据：真机输出 `run 完成：session=6 run=8 exit=6`、`note.txt：hello from lg cli`、`changes` 含 `note.txt`、`revert 后 note.txt 已删除`、`cli-e2e 通过`。
- [x] `doctor` 在 `apiContractVersion` 不匹配时退出 1 并提示升级。
  - 证据：`doctor.test.ts` 两条失败用例（契约版本不符、`status!=UP`）断言退出 `EXIT.ERROR` 与升级提示。
- [x] 脚本重复执行不依赖上一次残留状态。
  - 证据：脚本默认清空并重建 `build\cli-e2e\data` 与工作区副本；`-KeepData` 仅为复用既有模型 Key 时显式使用；后端由脚本自起自停（`taskkill /T` + 端口兜底）。
- [x] 后端 `mvn -o test` 仍为 331/0/0/15。
  - 证据：`Tests run: 331, Failures: 0, Errors: 0, Skipped: 15, BUILD SUCCESS`。

## 文档

- [x] `cli/README.md` 含令牌引导、8 条命令示例、退出码表、`--json` 字段、已知限制。
  - 证据：README 含环境要求、令牌引导、命令表、全局参数、退出码表、`--json` 7 种 `type` 字段表、测试与 e2e 命令。
- [x] 已知限制写明：首版不触发 Verify（因此有变更时常退出 6）、不做客户端列表与跨客户端管理。
  - 证据：README「已知限制」小节；`--json` 与续看去重策略同节说明。
  - 偏差说明：spec T3 原本要求「前端同步用同一组 fixture（软链或复制）」，因硬约束 `frontend/` 零改动，本次只在 `cli/test/fixtures/sse/` 维护，并在 README 记录将来抽共享包时的迁移点（`cli/src/sse.ts` + 该目录）。
- [x] `docs/phases/phase6/plan.md` 的 P6-02 状态与实测退出码矩阵一致。
  - 证据：plan.md 已写入「已完成（2026-11-16）」状态块、交付物、实测退出码矩阵与真机 e2e 结论。
