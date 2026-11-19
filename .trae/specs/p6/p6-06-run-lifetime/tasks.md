# P6-06 Run 生命周期与客户端断流解耦 - 技术任务清单

> 状态：**已实施**（2026-11-17）。T1–T8 完成；T6 以通道单测 + ReAct 集成 + 真实 Tomcat/HTTP 断流验收实现，剩余偏差见 checklist。

> 约定：本批次会修改 `src/main/java`（与 P6-03「后端零改动」边界不同）；不改 `PublicApiCatalog`、`public-api.json`、scope 与数据库 schema。
> 语义基线：`cancelRequested` = 显式停止（唯一能中止 ReAct、工具与审批的信号）；`clientDetached` = SSE 连接断开（只影响事件发送）。任何新增代码不得因断流写入取消标记。

## T1 拆分断流与取消信号

- `ChatSseChannel` 保留 `clientDisconnected` 但语义收窄为 `clientDetached`：SSE 写出失败、心跳失败、`onError`、`onTimeout` 只置该标记，不再被当作取消。
- `ChatSseChannel` 新增独立 `cancelRequested`（`AtomicBoolean`）与 `requestCancel()` / `isCancelRequested()`；不新建额外控制类，保持现有调用面最小。
- `AgentChatController.streamChat`：`generationRegistry.register(chatSessionId, channel.cancelFlag())`；`agentService.execute(..., channel.cancelFlag(), bridge)`；`finally` 用同一句柄精确注销。
- `AgentChatController` 中「客户端断开仍通过写出异常或 /chat/stop 置位」的注释同步改写，避免误导。
- 完成判据：单测证明发送失败只置 `clientDetached`、不置 `cancelRequested`；`/chat/stop` 只置 `cancelRequested`；ReAct 与审批等待不再读取断流标记。

## T2 事件持久化先于发送

- `ChatStreamBridge` 调整为「先 `appendRunEvent`，后 `channel.send`」，覆盖 `onToolCall`、`onToolResult`、`onCompletion`、`onVerify`、`onDiagnostics`、`onNoProgress`、`onApprovalResolved` 等既有落库事件。
- `channel.send` 在 `clientDetached` 置位后变为静默 no-op；首次写失败仍记录一次日志并置位，不再向业务线程抛异常，避免跳过后续持久化。
- `onChunk` 保持现有 800ms / 2 万字检查点；断流后检查点继续写入，保证重连可重建正文。
- `onApprovalRequired` 的 `pauseForApproval` 与事件落库顺序保持「先改状态、再落事件、最后尝试发送」。
- 完成判据：注入一个写入即抛错的假通道，断言每个语义事件仍落库、`seq` 连续、正文检查点存在；事件可观察顺序与改造前一致。

## T3 收尾与终态语义

- `ChatStreamBridge.onComplete` 删除「断流即丢弃回复」分支：无论是否断流，都保存完整助手消息与轨迹、落 usage、按业务结果置终态。
- 终态映射：正常完成 → 成功；异常 → 失败；`reason == CANCELLED`（仅显式停止产生）→ `CANCELLED`。`clientDetached` 不参与终态判定。
- `done` 只在连接仍附着时发送；断流时跳过发送但不影响落库。
- Run 结果中记录本次是否发生过断流（内部字段），不新增事件类型。
- 完成判据：断流完成场景断言助手消息存在、终态成功；显式停止场景断言 `CANCELLED` 且 reason 不是断流；异常场景仍为失败。

## T4 审批等待与多 Run 停止

- `ToolApprovalRegistry.await` 只响应 `cancelRequested`：断流不拒绝，保持轮询至 `expiresAt`；超时仍按 `TIMEOUT` 拒绝，显式停止按 `SESSION_STOP` 拒绝。
- `GenerationRegistry` 由「会话 → 单标记」改为「会话 → 多个 Run 句柄」：`register` 追加、`unregister` 按句柄精确移除、`cancel` 取消该会话全部活跃 Run。
- `POST /api/agent/chat/stop` 保持入参与响应不变；内部同时取消全部活跃 Run 与该会话待审批。
- 完成判据：断流 60s 后审批仍为 `PENDING` 且可批准；同一会话两个活跃 Run 均能被一次 stop 取消；旧 Run 收尾不会误删新 Run 的句柄。

## T5 生命周期与资源释放

- 断流后关闭 emitter、取消心跳定时任务；后续事件只落库，不再尝试写出。
- 沿用 `maxSecondsPerRun`、`maxTokensPerRun`、工具自身超时与 120s 审批超时作为无人连接时的兜底；命中后走现有收尾路径，不新增配置。
- 收尾路径确保 `pending` 审批、注册表句柄、心跳任务全部清理。
- 完成判据：断流后心跳不再运行；预算耗尽仍能终止；收尾后注册表与待审批无残留。

## T6 HTTP / SSE 集成验证

- 优先复用现有 `src/test/java/.../acceptance` 的 HTTP 测试方式与 `ScriptedAgentClient` 一类的脚本化模型客户端，不依赖真实模型与外部网络。
- 新增「发起 → 关闭 SSE → Run 继续 → 成功」用例：断言事件 `seq` 连续、终态成功、助手消息完整。
- 新增「发起 → 关闭 SSE → 显式 stop」用例：断言 `CANCELLED`、后续无新工具事件。
- 新增「断流期间审批 → 重连列表仍 PENDING → 批准后继续」用例。
- 完成判据：三条集成用例稳定通过，且可在离线环境执行；不引入新的测试依赖。
- 实现：`RunDisconnectHttpAcceptanceTest` 起真实 Tomcat + `DispatcherServlet`，用裸 Socket 读完首个事件后以 `SO_LINGER=0` 发 RST，服务端日志出现 `Connection reset by peer`；模型层用 Mockito 替身，事件与用量走真实 `AgentRunService` 与内存仓储替身。心跳失败用「断流后 17s 无业务写出」真实触发；`onError` / `onTimeout` 因 emitter 超时为 0、Tomcat 不发 asynchronous error，改为在 `ChatSseChannelTest` 直接调用注册回调；拒绝与审批表交互由 HTTP 用例 + `ToolApprovalRegistryPersistenceTest` 覆盖。

## T7 多入口一致性核查

- Web：核查 `frontend/src/api/sse.ts` 与聊天 store 是否存在「SSE 结束即任务结束」的本地假设；仅做状态映射或文案修正，不新增 UI。
- CLI：核查 `run` 在 Ctrl-C 时是否显式调用 `stop`；若未调用则只补这一处，保持输出结构与退出码不变。
- VS Code：扩展代码不改；用现有 vsix 重新验证「重载后任务继续、事件可补齐、审批仍可处理」。
- 完成判据：三入口分别记录「不误取消 / 可继续 / 可显式停止」的验证结果；若前端必须改动，改动范围与理由写入 checklist。

## T8 文档与回归收口

- 更新 `ide/vscode/README.md`：移除「断流可能取消活跃 Run」的已知限制，改为「断流不终止，重连补事件」。
- 更新 `docs/phases/phase6/plan.md`：P6-03 的偏差标注为「已由 P6-06 修复」，并补 P6-06 状态与链接。
- 回填本批次 `checklist.md` 的证据（测试名、命令输出、手动场景）。
- 零回归：后端 `mvn -o test` 在 331 条基础上新增全绿；CLI 71 条、扩展 43 条不变；`frontend/` 现有测试与构建通过。
- 完成判据：checklist 全勾或逐条交代偏差；`public-api.json` 与契约测试无 diff。

## 依赖顺序

T1 → T2 → T3 → T4 / T5（可并行）→ T6 → T7 → T8。

## 不做

- 不新增端点、事件类型、scope、配置项或数据库迁移。
- 不做断流后的订阅式推送、多客户端广播、跨实例协调。
- 不保证后端进程重启后继续执行进行中的 Run；不改变现有重启恢复语义。
- 不做自动批准、自动重试、自动重新提交任务。
- 不改 CLI 输出结构与退出码；不改 Web 页面结构与交互。
- 不把断流写进公开事件词汇；断流留痕只进日志与 Run 内部结果。
