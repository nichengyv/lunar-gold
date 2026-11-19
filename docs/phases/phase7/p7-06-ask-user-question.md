# P7-06 执行中决策询问 ask_user_question

> 状态：已完成，含真实模型冒烟（2026-11-19，`deepseek-flash`，16.2s，COMPLETED）。
> 仅剩出口 4：L1/L2/L3 数据集前后对比，随 P7-02 基线执行。
> 完成摘要见 [COMPLETED_WORK.md](../../COMPLETED_WORK.md) 第 11 节；排期见 [UPDATE_PLAN.md](../../UPDATE_PLAN.md) 13.3。
> 本文保存设计细节、契约与排障记录，供后续改动该能力时查阅。

## 1. 目标与非目标

金月原有的「用户在环」只有审批（工具风险拦截），缺少执行中的决策询问：遇到方案分叉、缺关键信息时，agent 只能自己选或报错结束。本任务对齐 DeepSeek Harness `@deepseek-ai/dsh-tool-ask-user` 与 Trae 的同名能力，以**模型可调用的 tool** 形式补齐（而非系统级指令，理由是 schema 进入工具目录、KV cache 友好、可由 system prompt 引导触发时机）。

边界划分：审批管「这个动作有副作用，要不要执行」；询问管「接下来往哪个方向做」。询问不替代审批，也不做子 agent 询问、不做超时自动续行。

## 2. 端到端时序

```text
模型输出 tool_call: ask_user_question
  → ReActAgentService 在工具分发前拦截（不进 ToolRegistry.dispatch）
  → AskUserQuestionTool.ask() 注册 CompletableFuture
  → ChatStreamBridge.onUserQuestion()
      ├─ AgentRunService.pauseForUserInput()：写 USER_QUESTION 事件，RUNNING → WAITING_USER_INPUT
      └─ SSE 推送 event:user_question（payload 含 runId）
  → 前端 stores/chat.handleUserQuestion() 设置 pendingUserQuestion，AskUserCard 渲染
  → ReAct 线程 500ms 分片等待（每片检查 cancelRequested 与 15 分钟总 deadline）

用户提交回答
  → POST /api/agent/runs/{runId}/answer
  → 校验 Run 归属 + 状态必须为 WAITING_USER_INPUT
  → AgentRunService.resumeAfterUserInput()：写 USER_ANSWERED，WAITING_USER_INPUT → RUNNING
  → AskUserQuestionTool.resolve() 完成 future
  → 工具结果 JSON 回灌模型上下文，ReAct 循环继续
```

## 3. 关键契约

### 3.1 状态机

新增 `RunState.WAITING_USER_INPUT`，合法迁移：

- `RUNNING → WAITING_USER_INPUT`
- `WAITING_USER_INPUT → RUNNING`（用户回答）
- `WAITING_USER_INPUT → CANCELLED / FAILED`
- `recoverAfterRestart()` 把它与 `RUNNING / WAITING_APPROVAL` 一并终止为 `FAILED / PROCESS_RESTARTED`：等待依赖进程内 ReAct 线程，重启后无人可唤醒。

### 3.2 工具入参与回答

入参（questions 数组 min/maxItems = 1，一次只问一题）：

```json
{"questions":[{"id":"scope","question":"改接口还是加 wrapper？","header":"改动范围",
  "options":[{"label":"改接口 (Recommended)","description":"同步更新调用方"}],
  "multi_select":false}]}
```

工具结果：`{"answers":[{"id":"scope","selected":["..."],"custom":"..."}]}`。

`/answer` 请求体：`{"questionId":"scope","selected":["..."],"custom":"..."}`。

**schema 红线**：JSON Schema 的 `required` 是字段名数组。曾把 `options.items.required` 误写成 `{"type":"array","items":{"type":"string"},"const":["label"]}`，DeepSeek 直接 400；脚本模型不校验 schema，只有真实调用能暴露。`AskUserQuestionReActIntegrationTest` 已固定该结构。

### 3.3 工具暴露条件

`ToolRegistry.buildRankedToolCallbacks`：非 fallback、权限允许之外，`ask_user_question` 还要求 `WorkspaceContext.runId != null`。非流式/后台场景没有 SSE 与 Run 挂起能力，暴露给模型等于让它发起永远无人回答的询问。

`ReActAgentService.isParallelReadOnlyCall` 显式排除该工具：虽然 `readOnly()=true`，但进入只读并行批会导致同轮后续工具在用户回答前预执行，破坏「先问再做」。

## 4. 取消、超时与清理

- 等待循环按 500ms 分片 `future.get(slice)`，每片先查 `cancelRequested`：用户停止后最多一个分片退出，返回 `CANCELLED` 错误结果。
- 总超时 15 分钟，返回 `USER_INPUT_TIMEOUT`。
- `finally` 中 `pendingByRunId.remove(runId)`；ReAct `execute` 的 `finally` 再兜底 `cleanup(runId)` 并清 ThreadLocal。
- 工具以 error 结束（超时/取消/中断）时，`AgentRunService.abandonUserQuestion()` 把仍处于 `WAITING_USER_INPUT` 的 Run 迁回 `RUNNING` 并写 `USER_ANSWERED(abandoned=true)`；若用户已回答或已取消则不覆盖。否则 Run 会永久卡在等待态。
- 前端在流正常结束、失败、停止、新会话时清除 `pendingUserQuestion`，避免留下永远答不了的卡片。

## 5. 断线/刷新恢复

- `USER_QUESTION` / `USER_ANSWERED` 落入 `agent_run_event`，与审批共用 `sinceSeq` 重放。
- `applyRunEvents` 重放时还原未回答的卡片、清除已回答/已 abandon 的卡片。
- `recoverRun` 的继续轮询状态集合包含 `WAITING_USER_INPUT`。

## 6. 提示词

`prompts/coding/system-v1.md` 与 `prompts/agent/system-v1.md` 增加【决策询问】：只在真实分叉时提问、先做只读调研、一次一问且选项带 Recommended、严格按用户选择执行、不得代替审批。`PromptCatalog.REQUIRED_MARKERS` 只给 `coding` 加该标记（`agent` 的测试夹具不含此段，避免破坏 fixtures）；`prompts/index.json` 两个 sha256 已更新。

## 7. 测试矩阵

| 层 | 用例 | 覆盖 |
|---|---|---|
| `AskUserQuestionToolTest` 6 项 | 工具 | selected/custom 回显、取消分片唤醒、cleanup 释放、未知 runId、questionId 不匹配 |
| `AgentRunServiceTest` +5 项 | 状态机 | 询问→回答迁移与事件顺序、非法迁移、重启终止、abandon 语义 |
| `AskUserQuestionReActIntegrationTest` 5 项 | 真实 ReAct（脚本模型） | 回答后继续、同轮只读不抢跑、停止快速退出、无 Run 不暴露、schema required 结构 |
| `SettingsControllerModelsTest` 3 项 | 模型目录 | 实时列表替换、失败回退、无 Key 不调远程 |
| `AskUserCard.test.ts` 7 项 | 前端组件 | 渲染、单/多选、自由输入、防重复、失败恢复 |
| `AskUserQuestionLiveSmokeTest` | 真实模型 | 默认跳过；`LUNARGOLD_LIVE_API_KEY` 开启，`LUNARGOLD_LIVE_MODEL` 默认 `deepseek-flash` |

最近全量：后端 388 tests / 0 失败 / 0 错误 / 16 跳过；前端 Vitest 99/99、`vue-tsc` 通过。

## 8. 排障记录

1. **真实调用 400**：本地 `HttpListener` 捕获出站请求，确认 `required` 被写成 schema 对象；原始 HTTP 重放同一份正确 schema 可通过，定位为源码构造错误而非 Spring AI 转换问题。
2. **全量套件挂起**：`RunDisconnectHttpAcceptanceTest.stubAgent` 仍 stub 旧 15 参 `execute`，controller 改走 16 参重载后 mock 不命中，SSE 永远无事件。教训：给 `execute` 加重载时，必须同时搜索所有 `when(agentService).execute` 的 stub。

## 9. 剩余工作

1. 出口 4：L1/L2/L3 同一数据集前后对比，完成率/轮次/token 退化不得 ≥ 5%，随 P7-02 真实模型基线执行。
2. 关联边界（不属于本任务）：聊天执行链仍只取 `DEEPSEEK` Key；推理档位只控制工具轮次与历史预算，不映射 `reasoning_effort`。

真实模型冒烟已完成（结果见文首状态）。复跑方式：
```powershell
$env:LUNARGOLD_LIVE_API_KEY="sk-..."
mvn -o test "-Dtest=AskUserQuestionLiveSmokeTest"
```
