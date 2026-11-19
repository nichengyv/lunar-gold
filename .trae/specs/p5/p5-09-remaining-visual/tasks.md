# P5-09 剩余视觉项 — 任务拆分

> 状态：已完成
> 前置：spec.md 已确认

## T1 工具运行态名称与耗时

- [x] T1.1 `types/api.ts`：`ToolInvocation` 增加 `startedAt?: number`。
- [x] T1.2 `stores/chat.ts`：`onToolCall` push 时写 `startedAt: Date.now()`。
- [x] T1.3 `ChatMessage.vue`：`now` 轮询（仅 `hasRunning` 时每秒）、`toolElapsed()`、摘要追加「已用 X」、`.step-state` 运行中显示「进行中 · X」。

验证：`npx vitest run src/components/ChatMessage.test.ts` 5 项通过；`chat-stream` E2E 通过。

## T2 消息密度档位

- [x] T2.1 `tokens.css`：新增 `--density-row / --density-gap / --density-pad-y` 与 `[data-density="compact"]` 覆盖（24px / 4px / 4px）。
- [x] T2.2 `stores/theme.ts`：`density` 状态、`density` 持久化键、`applyDensity()`、`setDensity()`，`init()` 时应用。
- [x] T2.3 `SettingsView.vue`：外观区新增「消息密度」单选（舒适 / 紧凑）。
- [x] T2.4 `style.css`：`.step-row` 用 `--density-row`，`.timeline` 用 `--density-gap`。
- [x] T2.5 `theme.test.ts`：补充密度持久化与 `data-density` 写入断言（2 项）。

验证：`npx vitest run src/stores/theme.test.ts` 5 项通过；设置页切换后检查 `html[data-density]`。

## T3 操作条键盘可见

- [x] T3.1 `style.css`：`.message.assistant:focus-within .msg-actions { opacity: 1; }`。

验证：无头浏览器全量用例通过；`:focus-within` 规则生效。

## T4 Markdown 表格与引用卡

- [x] T4.1 `style.css`：`.md-content table` 边框、`th` 背景、`td` 底边、`tbody tr:nth-child(even)` 斑马纹、单元格用 `--density-pad-y`。
- [x] T4.2 `ChatMessage.vue`：`source-chip` 内增加可见「第 N 段」（`chunkIndex != null`）。

验证：`npm run build` 通过；亮暗 token 复用既有变量。

## T5 断点统一与窄屏工作台

- [x] T5.1 `AppLayout.vue`：`toggleSidebar()` 断点由 `< 769` 改为 `< 768`。
- [x] T5.2 `ChatView.vue`：维护 `viewportWidth`，新增 `isNarrowDock`；窄屏渲染 `.dock-backdrop` 并点击关闭。
- [x] T5.3 样式：`@media (max-width:1023px)` 下 `.workbench:not(.fullscreen)` 浮层化，`.dock-resizer` 隐藏。

验证：900×700 E2E 通过，浮层右缘 ≤900，遮罩可关闭。

## T6 响应式 E2E 与归档

- [x] T6.1 新增 `frontend/e2e/responsive.spec.ts`：960×640、900×700、767×700、1280×800 四组断言。
- [x] T6.2 截图写入 `frontend/test-results/responsive-*/`；实测数值：960×640 侧栏 56px、横向溢出 0；900×700 浮层右缘 900；767×700 侧栏默认 x<0、打开后 x≥0；1280×800 侧栏 280px。
- [x] T6.3 系统 Edge 下运行新用例与全量回归：4 条新用例通过，全量 23 通过 / 1 跳过。

验证：`E2E_CHANNEL=msedge E2E_BASE_URL=http://localhost:5174 npx playwright test`。

## T7 三态复核、回归与文档

- [x] T7.1 复核会话切换骨架、首屏空态、工作台空态、`ErrorState`；唯一缺口为错误态缺导出诊断，已用可选 `exportable` 补齐，未新建组件。
- [x] T7.2 `npx vitest run` 12 文件 88 项通过、Playwright 23 通过 / 1 跳过、`npm run build` 通过。
- [x] T7.3 同步 `docs/phases/phase5/plan.md`、`docs/UPDATE_PLAN.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`、`docs/architecture/06-frontend.md`。
- [x] T7.4 勾选本目录 `tasks.md` 与 `checklist.md`，填写 spec 第 8 节实际结果与偏差。
