# P5-10-3 长任务进度可感知 — 任务拆分

> 状态：待实施
> 前置：spec.md 已确认

## T1 后端预算事件扩展

- [x] T1.1 在 `AgentChatController` 流式处理内记录本轮开始时间与已完成的模型轮次数。
- [x] T1.2 扩展 `sendUsageEvent` 载荷：`round`、`maxRounds`、`maxTokens`、`elapsedMs`、`maxElapsedMs`；保留原 token 字段，保持向后兼容。
- [x] T1.3 注入 `agent.budget.max-tokens-per-run` 与新增的 `agent.budget.max-seconds-per-run`；轮次上限取当前 `AgentPolicy.Reasoning`。
- [x] T1.4 在配置文件中增加 `agent.budget.max-seconds-per-run: 0` 与注释（0=不限）。
- [x] T1.5 在 `ReActAgentService` 每轮循环开头检查时间预算，超限复用预算停止路径，reason=`TIME_BUDGET_EXCEEDED`，提示可缩小范围或调高配置。
- [x] T1.6 为时间预算新增聚焦测试（优先放进现有 ReAct 相关测试类）。

验证：`mvn -o -Dtest=<相关测试> test` 通过；`mvn -o test` 全量通过。

## T2 前端类型与 SSE

- [x] T2.1 `types/chat.ts` 增加 `RunUsage`、`TaskStageKey`、`TaskStageItem`、`FailureClass`、`FailureInfo`。
- [x] T2.2 `api/sse.ts` 增加 `onUsage` hook 与 `usage` 事件分支，容错解析。
- [x] T2.3 扩展 `frontend/tests/sse.test.ts`：usage 全字段与旧后端缺字段两种输入。

验证：`npx vitest run tests/sse.test.ts`。

## T3 store：阶段、预算、失败分级、继续

- [x] T3.1 state 增加 `liveBudget`、`liveStages`、`runStartedAt`，新轮次重置。
- [x] T3.2 `sendMessage` 记录“理解”起点；工具调用、`file_change`、`diagnostics`、`verify`、`done` 推进阶段，规则按 spec 4.1。
- [x] T3.3 `onUsage` 更新 `liveBudget`；本地计时与服务端 `elapsedMs` 取较大值。
- [x] T3.4 导出 `classifyFailure(errorText, completion?)`，实现 spec 4.3 四类判定与动作。
- [x] T3.5 新增 `continueTask(messageId)`：仅 stopped 且空闲时，用固定续写提示调用 `sendMessage`。
- [x] T3.6 停止/失败/完成时收口：`isProcessing=false`、`streaming=false`、阶段停在最后到达项。

验证：新增/扩展 store 单测覆盖阶段推进与跳过、预算字段、四类失败、续写提示词。

## T4 UI：进度条、预算、失败分级、继续

- [x] T4.1 `ChatView.vue` 在计划条上方新增 `task-progress`：五阶段标签、状态、耗时、当前步骤；短任务不显示。
- [x] T4.2 同一区域展示预算行；任一比例 ≥80% 转警示色并显示对应提示；上限 0 显示「不限」。
- [x] T4.3 `isProcessing` 时启动 1s tick 刷新当前阶段与耗时，结束后清理定时器。
- [x] T4.4 `ChatMessage.vue` 失败框按 `classifyFailure` 展示标签/提示/主按钮；`config` 增加「去设置」+「重试这一轮」。
- [x] T4.5 `ChatMessage.vue` 停止提示增加「继续」按钮，生成中禁用。
- [x] T4.6 补充样式，沿用 `tokens.css` 变量，亮暗两套可读；窄窗口不挤压正文。

验证：组件单测 + 本地 `npm run build`。

## T5 E2E

- [x] T5.1 复用 `frontend/e2e/fixtures/api.ts` 现有 `stream` / `streamDelayMs` 选项注入错误与长任务；无需新增夹具字段。
- [x] T5.2 新增 `frontend/e2e/long-task-progress.spec.ts`：
  - 长任务阶段条与预算条出现（阶段推进由单测覆盖，route.fulfill 无法逐块推送 SSE）；
  - 配置类失败显示「需配置」与「去设置」；
  - 停止后显示「已停止生成」与「继续」，继续请求携带续写提示。
- [x] T5.3 运行 `E2E_CHANNEL=msedge` + 手动 dev server，`--repeat-each=2` 全绿。

## T6 回归与文档

- [x] T6.1 后端 `mvn -o test` 全量通过。
- [x] T6.2 前端 `npx vitest run` 全量通过。
- [x] T6.3 Playwright 全部用例通过。
- [x] T6.4 同步 `docs/UPDATE_PLAN.md`、`docs/phases/phase5/plan.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`。
- [x] T6.5 勾选本目录 `tasks.md` 与 `checklist.md`，记录偏差与未覆盖项。

## 依赖顺序

T1 → T2 → T3 → T4 → T5 → T6。T1 与 T2 可并行；T4 依赖 T2/T3 的类型与 store 字段。
