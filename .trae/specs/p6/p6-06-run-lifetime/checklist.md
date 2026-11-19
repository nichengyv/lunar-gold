# P6-06 Run 生命周期与客户端断流解耦 - 验收清单

> 状态：**核心语义已实施并通过单测 / ReAct 集成 / 真实 HTTP 断流验收 / 全量回归**（2026-11-17）。`[x]` 附证据；未勾选项见文末「偏差记录」，不以代码核对冒充行为验证。
>
> 验收环境：Windows / JDK（Maven 离线）/ 后端 `mvn -o test` / CLI Node 24 / 扩展 Node 24 + tsc / 前端 Vitest 3 + Vite 6。
>
> 通过基线：断流后 Run 继续且事件完整；只有显式停止、预算耗尽或异常才会终止；断流不得产生 `CANCELLED`，也不得丢弃助手消息或拒绝待审批。

## 批次边界

- [x] 主代码改动仅落在 Run / SSE / 审批 / 注册表相关类与既有测试：`ChatSseChannel`、`AgentChatController`、`ChatStreamBridge`、`ReActAgentService`、`ToolApprovalRegistry`、`GenerationRegistry`；证据：`git status` 审查。
- [x] 未新增公开端点，未修改 `PublicApiCatalog`、`public-api.json`、scope 与鉴权矩阵；证据：`mvn -o test` 契约测试通过。
- [x] 未新增事件类型；事件 `type` 集合不变；证据：`AgentRunEvent.EventType` 未改动。
- [x] 未新增配置项；预算、审批与工具超时沿用现有配置；证据：仅改信号来源与收尾逻辑。
- [x] 未新增数据库迁移；`agent_run` 与 `run_events` 结构不变；证据：无资源文件改动。
- [x] 前端无 UI 结构改动；CLI 仅新增 Ctrl-C 显式 stop 调用（`cli/src/commands/run.ts`），输出结构与退出码未变；证据：`npm test` 71 条通过。

## 信号拆分

- [x] 显式停止只置取消信号，不把连接状态改成断开；证据：`ChatSseChannelTest.显式停止不会误置断流标记`。
- [x] 断流只停止推送，不置位取消信号；证据：`ChatSseChannelTest.断流不会置位取消信号且后续发送静默`。
- [x] `/api/agent/chat/stop` 只置位取消并拒绝待审批；证据：`GenerationRegistryTest`、`ToolApprovalRegistryTest.显式停止标记按会话停止记录来源`。
- [x] ReAct 的轮次边界与工具批次后只读取消信号；证据：`RunDisconnectIntegrationTest` 在通道断流、取消未置位时完整跑完并成功。
- [x] 审批等待只接收取消信号；证据：`ToolApprovalRegistry.await` 参数与调用点仅传 `cancelFlag()`。
- [x] Controller 注册与注销使用同一取消句柄；证据：`AgentChatController` 编译通过，`GenerationRegistryTest.旧Run收尾不会误删同会话新Run的标记`。
- [x] SSE 写出失败、心跳失败、`onError`、`onTimeout` 的捕获分支只调用 `markDetached`；证据：`RunDisconnectHttpAcceptanceTest` 用裸 Socket + RST 制造真实写出失败（日志 `Connection reset by peer`）；`心跳失败只标记断流且Run继续完成` 在断流后静默 17s，由 `sse-heartbeat` 线程真实触发 `心跳发送失败`；`ChatSseChannelTest.容器报错只标记断流不置取消` / `容器超时只标记断流不置取消` 直接触发注册在 emitter 上的两个回调。四者均未置取消。

## 事件完整性与续传

- [x] `tool_call`、`tool_result` 在断流时仍落库；证据：`ChatStreamBridgeTest.断流后工具事件仍落库`。
- [x] `completion` 在断流时仍落库；证据：`ChatStreamBridgeTest.断流后完成仍保存回答并标记成功` 断言 `appendEvent(COMPLETION)`。
- [x] `verify`、`diagnostics`、`no_progress` 保持原有「先落库后发送」顺序；证据：改造前即为落库在前，未改动其顺序。
- [x] 正文检查点在断流期间继续写入；证据：`RunDisconnectHttpAcceptanceTest.真实HTTP断流后Run继续完成且事件与回答完整` 断言断流后仍有 `MODEL_CHUNK`，且完整回答含首尾片段。
- [x] 断流期间 `run_events.seq` 连续；证据：同一用例断言保存的事件 `seq` 从 1 起逐条递增。重连重建依赖既有 `runId + sinceSeq` 接口，由 P6-01/02 用例覆盖。
- [x] 事件的可观察顺序与改造前一致；证据：仅调整 `tool_call` / `tool_result` 两个事件的落库与发送先后。
- [x] 断流后发送为静默 no-op；证据：`ChatSseChannelTest.断流不会置位取消信号且后续发送静默`。

## 终态与消息

- [x] 断流后正常完成保存完整助手消息；证据：`ChatStreamBridgeTest.断流后完成仍保存回答并标记成功`、`RunDisconnectIntegrationTest`。
- [x] 断流本身不再导致 `CANCELLED`；证据：上述两测断言 `cancelIfActive` 从未调用。
- [x] 显式停止仍为 `CANCELLED`，reason 为 `agent cancelled`；证据：`ChatStreamBridgeTest.显式停止仍标记取消且保留已生成内容`。
- [ ] 执行异常路径保持失败终态；未新增断言，依赖既有失败路径（见偏差 3）。
- [x] 断流时跳过 `done` 发送；证据：`ChatSseChannelTest.断流不会置位取消信号且后续发送静默` + HTTP 用例中断流后写出仅报一次失败日志，后续事件不再尝试写出。
- [x] Run 内部结果记录 `clientDetached`；证据：`ChatStreamBridgeTest` 断言 `succeedIfActive` 的 JSON 含 `"clientDetached":true`。
- [x] usage 只落库一次；证据：`RunDisconnectHttpAcceptanceTest` 断言断流后 `promptTokens=7`、`completionTokens=11`（仅上报一次）。

## 审批与停止

- [x] 断流期间待审批保持 `PENDING`；证据：`RunDisconnectHttpAcceptanceTest.断流期间审批保持PENDING且恢复后仍可批准完成`：RST 后服务端先出现一次 `connection reset` 写出失败（`thinking`），1.5s 后审批 future 仍未完成。
- [x] 恢复后批准，Run 继续并完成；证据：同一用例经 `/api/agent/approvals/{id}` 批准后 Run 变为 `SUCCEEDED`，后续输出被保存。
- [x] 断流期间拒绝按现有工具失败路径处理；证据：`RunDisconnectHttpAcceptanceTest.断流期间拒绝审批后Run继续收尾`：断流后经 `/api/agent/approvals/{id}` 拒绝，返回 `REJECT` / `USER`，Run 继续收尾为 `SUCCEEDED`。
- [x] 断流后审批结果仍推动 Run 恢复；证据：`ChatStreamBridgeTest.断流后审批结果仍推动Run恢复`。
- [x] 审批决策写入审批表且来源正确；证据：`ToolApprovalRegistryPersistenceTest.断流期间拒绝写审批表并返回本人来源`、`等待超时写审批表并记录超时来源`。
- [x] 审批超时仍按 `TIMEOUT` 拒绝；证据：既有 `ToolApprovalRegistryTest` 在完整套件中通过。
- [x] 显式停止按 `SESSION_STOP` 拒绝；证据：`ToolApprovalRegistryTest.显式停止标记按会话停止记录来源`。
- [x] 同一会话两个活跃 Run 一次停止全部取消；证据：`GenerationRegistryTest.同一会话多个活跃Run会被一次停止全部取消`。
- [x] 旧 Run 收尾不会误删新 Run 的句柄；证据：`GenerationRegistryTest.旧Run收尾不会误删同会话新Run的标记`。
- [x] `/chat/stop` 入参、响应与权限校验不变；证据：完整后端套件通过，未改 Controller 签名。

## 生命周期与资源

- [x] 断流后结束 emitter 不再写出；证据：`RunDisconnectHttpAcceptanceTest` 触发真实写出失败后，后续事件不再出现写出失败日志（发送已 no-op）。心跳任务在 `complete()` 时由既有 `onCompletion` 取消，取消本身仍为代码核对。
- [x] 断流 + 时间预算耗尽仍能终止；证据：`ReActAgentService` 预算检查不依赖连接状态，完整套件通过。
- [x] 断流 + token 预算耗尽仍能终止；证据：同上。
- [ ] 断流后长期无客户端不会无限执行；由预算与审批超时兜底，未做长跑实测（见偏差 3）。
- [ ] 收尾后注册表与审批无残留；未做进程级残留断言（见偏差 3）。
- [x] 未引入新的线程池或定时器；证据：仅复用既有 SSE 心跳池。

## 集成验证（HTTP / SSE）

- [x] 「通道断流 → 真实 ReAct 继续 → 成功」用例通过；证据：`RunDisconnectIntegrationTest.SSE断流后Run继续执行并保存完整回答`。
- [x] 「真实 HTTP SSE 断开 → Run 继续」用例通过；证据：`RunDisconnectHttpAcceptanceTest.真实HTTP断流后Run继续完成且事件与回答完整`（真实 Tomcat + 裸 Socket RST）。
- [x] 「真实 HTTP SSE 断开 → stop」用例通过；证据：`RunDisconnectHttpAcceptanceTest.断流后显式停止仍能终止Run`。
- [x] 「断流期间审批 → PENDING → 批准继续」用例通过；证据：`RunDisconnectHttpAcceptanceTest.断流期间审批保持PENDING且恢复后仍可批准完成`。
- [x] 用例可在离线环境执行，不依赖真实模型与外部网络；证据：`ScriptedAgentClient` + `mvn -o test`。

## 多入口一致性

- [ ] Web 刷新后看到仍在运行的 Run 且未标记取消；本批次未改前端，未做浏览器真机复核（见偏差 4）。
- [ ] Web 关闭页面不再终止 Run；同上。
- [ ] Web 停止按钮仍显式取消；依赖既有前端逻辑与后端接口，未真机复核（见偏差 4）。
- [ ] CLI 本地流中断不等于停止；后端语义已改，CLI 未做断网实测（见偏差 4）。
- [ ] CLI Ctrl-C 显式调用 `/chat/stop`；代码已补（`stopOnInterrupt`），Windows 无可靠信号注入测试（见偏差 4）。
- [ ] VS Code 窗口重载后 Run 继续、审批可处理；扩展代码未改，本批次未重跑真机（见偏差 4）。
- [x] VS Code 扩展无代码改动；证据：`npm run check`、`npm test` 43 条通过。
- [x] CLI 输出结构与退出码未变；证据：`npm test` 71 条通过，新增逻辑仅在 SIGINT 路径。

## 回归

- [x] 后端 `mvn -o test`：**351 tests / 0 failures / 0 errors / 15 skipped**（331 条基线 + P6-06 单测/集成 10 条 + HTTP 断流验收 5 条 + 通道回调 2 条 + 审批桥接 1 条 + 审批表持久化 2 条）；证据：Maven 汇总。
- [x] CLI `npm test`：**71 passed**；证据：命令输出。
- [x] 扩展 `npm run check` 与 `npm test`：**43 passed**；证据：命令输出。
- [x] 前端 `npm test`：**88 passed / 12 files**；证据：Vitest 输出。
- [ ] 前端 `npm run build`：`vue-tsc --noEmit` 通过，`vite build` 因 esbuild 临时文件 `Access is denied` 失败；前端代码未改动（见偏差 5）。
- [x] 契约测试无 diff；证据：完整后端套件通过。

## 文档

- [x] `ide/vscode/README.md` 改为「断流不终止、重连补事件与审批」，移除旧限制；证据：README 第 8 步与已知限制。
- [x] `docs/phases/phase6/plan.md` 标注 P6-03 断流偏差已由 P6-06 修复，并新增 P6-06 条目；证据：plan.md。
- [x] spec / tasks / checklist 语义一致；证据：三份文档同日更新。

## 不通过判定

出现以下任一情况，本批次不得标记完成：

- 只改注释、日志或测试，未改变实际取消信号来源。
- 通过吞异常或延后发送掩盖事件缺失，导致 `seq` 仍有缺口。
- 断流后 Run 仍被标记 `CANCELLED`，或助手消息仍被丢弃。
- 断流后待审批仍被自动拒绝，或重连后无法继续处理。
- 为了让测试通过而放宽超时、跳过集成用例，或只验证单测不验证真实 SSE 断流。
- 修改了公开契约、事件类型集合或数据库结构而未在 spec 中重新评审。

## 偏差记录

| 偏差 | 影响 | 原因 | 后续 |
|---|---|---|---|
| 1. `onError` / `onTimeout` 无法由真实客户端触发 | 两条分支改为「直接调用 emitter 上注册的回调」验证，不是容器真实派发 | emitter 超时为 0，永不超时；Tomcat 把客户端 RST 表现为发送时 `IOException`（已真实覆盖），不会走 `onError` | 若将来出现容器级 `onError`（Jetty/Undertow 或异步错误），可再加一条真实故障用例 |
| 2. 断流重连后的审批列表查询接口未单独断言 | 列表可见性依赖既有只读接口；决策、落库与「批准/拒绝后继续」均已覆盖 | 本轮以审批表持久化与 `/api/agent/approvals/{id}` 为验收边界 | 发布前用 Web/CLI 各做一次断流后打开审批列表 |
| 3. 执行异常终态、长跑兜底、注册表残留未单独断言 | 异常与长时间无客户端的表现缺少直接证据 | 需长跑与进程级观察，成本高 | 发布前做一次无人值守长跑观察 |
| 4. Web / CLI / VS Code 未做真机复核 | 多入口表现依赖代码与既有测试，缺少本轮真机截图 | 本批次无前端与扩展代码改动；CLI 信号测试在 Windows 不可靠 | 发布前用 P6-03 的隔离 VS Code 环境各跑一次断流与停止 |
| 5. 前端 `vite build` 因 esbuild 临时文件 `Access is denied` 失败 | 无法给出本轮完整构建证据 | 环境/权限问题，`vue-tsc --noEmit` 已通过，前端未改动 | 在本机手动重跑 `npm run build` 确认 |
| 6. 后端进程重启仍不续跑 | 重启期间的 Run 丢失 | 执行线程在进程内，非本批次目标 | 维持现状并在文档中明示 |
| 7. 仓库没有覆盖率工具与门禁 | 「覆盖率达标」没有可比较的基线；只能按分支清单验收 | `pom.xml` 无 JaCoCo，本地离线仓库也没有该插件；仓库约定以用例清单表达覆盖 | 如需门禁，另立批次统一引入 JaCoCo 与阈值，避免只为本批次临时加插件 |
