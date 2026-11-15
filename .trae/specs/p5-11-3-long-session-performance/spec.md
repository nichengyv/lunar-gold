# P5-11-3 长会话性能 — 规格说明

> 日期：2026-11-14
> 状态：已完成（2026-11-14）
> 对应规划：`docs/phases/phase5/plan.md` P5-11-3
> 已存在基础：P5-11-1 Playwright/API 仿真、P5-11-2 本地诊断与 Web Vitals 采集、`ChatMessage.vue`/`MarkdownContent.vue`/`ChatView.vue`

## 1. 目标

1. 500 条消息的会话首屏渲染 < 300ms，消息区 DOM 节点 < 500。
2. 流式输出 60 秒不卡顿，主线程长任务（> 50ms）为 0。
3. 性能改造不牺牲复制、全文搜索定位、缩略导航跳转与流式自动跟随。
4. 剖析方法与数字可复现，结论来自真实消息组件渲染，不用原型数字对外。

## 2. 非目标

- 不改后端、SSE 协议、Run/Event 数据结构与断线恢复语义。
- 不新增数据库迁移，不改变消息顺序、历史还原与审批/ChangeSet 行为。
- 不引入第三方虚拟列表或 Markdown 增量渲染依赖。
- 不做图片懒加载、按需加载历史等与本项指标无关的改造。

## 3. 现状与缺口（2026-11-14 核对）

| 项 | 已确认现状 | 缺口 |
|---|---|---|
| 流式合帧 | `sse.ts` 每个 `content` 事件立即回调；`chat.ts` 的 `onContent` 直接赋值 `aiMsg.content` | 无节流，chunk 频率即渲染频率 |
| 自动滚动 | `ChatView.vue` 对 `chat.messages` 做 deep watch，每次内容变化 `nextTick` 写 `scrollTop = scrollHeight` | 每个 chunk 触发一次强制布局 |
| Markdown | `MarkdownContent.vue` 的 computed 每次全文 `marked.parse` + `DOMPurify` + `hljs` 高亮全部代码块 | 每次更新 O(全文)，代码块重复高亮 |
| 消息列表 | `ChatView.vue` 用 `v-for` 全量渲染 `ChatMessage` | DOM 节点随消息线性增长 |
| 搜索定位 | `focusMessageId` 通过 `querySelector([data-msg-id])` + `scrollIntoView` | 依赖目标消息已在 DOM |
| 缩略导航 | `updateActiveTurn` / `jumpToTurn` 逐轮读取 `[data-msg-id]` 的 `getBoundingClientRect` | 依赖全部消息在 DOM |
| 既有关闭项 | 全仓无 `requestAnimationFrame`、`throttle`、`virtual`、`content-visibility` 实现 | 从 0 开始 |

## 4. 设计决策

### 4.1 流式按帧合并（最多 20 次/秒）

新增 `frontend/src/utils/frameThrottle.ts`：

- 以 `requestAnimationFrame` 调度，同时设 50ms 最小间隔，保证每秒最多 20 次提交。
- `push(value)` 只保留最新值；`flush()` 立即提交最新值；`cancel()` 丢弃未提交值。
- `onContent` 与 `onThinking` 改用该机制；`onDone`、`onError`、用户停止与 `finally` 必须 `flush`，保证最终正文与 SSE 全量文本逐字一致。
- `ChatView` 的自动跟底同样走 rAF 节流，避免每个 chunk 强制布局；用户离开底部后不再自动跟随。

### 4.2 Markdown 只重算变化段落

`MarkdownContent.vue` 增加 `streaming?: boolean` 属性（`ChatMessage.vue` 传入 `msg.streaming`）：

- 非流式（历史消息、已完成消息）：沿用整篇 `marked.parse`，保证与现有输出完全一致。
- 流式：`marked.lexer` 取顶层 token，按 `type + raw` 做有界缓存（上限 200 条，超出按最久未用淘汰），只渲染未命中缓存的 token，最后统一 `DOMPurify`。未变化的段落与代码块不再重复解析和高亮。
- 流式结束（`streaming` 变为 false 或内容不再增长）时执行一次整篇解析替换结果，消除 token 级拼接在引用式链接等跨段语义上的差异。
- 代码块渲染器、语言标签、复制按钮与转义逻辑保持不变。

### 4.3 虚拟滚动（超过 200 条消息）

新增 `frontend/src/composables/useVirtualMessages.ts`，在消息数 > 200 时启用窗口化渲染：

- 高度缓存：以消息 id 为键，`ResizeObserver` 实测渲染项高度；未实测项用最近已知平均高度估算。
- 可视窗口：按累计偏移计算首尾可见项，上下各保留 6 条 overscan；窗口外只保留上下占位高度，不生成 DOM。
- 200 条及以下完全走现有全量渲染，行为与现在一致。

保真要求：

- **复制**：单条消息「复制」与代码块「复制」保持原行为；虚拟窗口内拖选仍可用。
- **搜索定位**：`focusMessageId` 统一走 `scrollToMessage(id)`；目标未渲染时先按估算偏移滚动，渲染后按实测高度校正一次，确保定位到目标且居中。
- **缩略导航**：`updateActiveTurn` 与 `jumpToTurn` 改用高度缓存偏移计算，不依赖全量 DOM。
- **流式跟随**：正在流式的最后一条始终在窗口内；内容增长由实测高度回填，自动跟随仍生效。
- **跨窗口选择边界**：虚拟化下无法一次性拖选屏幕外的全部历史；通过缩略导航跳转后分段复制，验收不把「整段跨消息拖选」列为保证项，文档明确边界。

### 4.4 剖析方法与证据

新增 Playwright 性能用例（`@perf` 标记，默认不进 CI 门禁），用现有 `page.route('**/api/**')` 仿真：

1. 注入 500 条历史消息（含 Markdown 段落、列表、代码块）与一个高频流式回合。
2. 记录首屏：导航开始到 `.messages` 首条消息可见的耗时（`performance.now()` 标记）。
3. 记录 DOM：`.messages` 内 `querySelectorAll('*').length`。
4. 记录长任务：`PerformanceObserver({ type: 'longtask' })` 统计 > 50ms 的任务数与最大时长。
5. 流式持续 60 秒（按约 30 chunk/s 注入），记录长任务与正文最终一致性。

改造前先跑一次记录基线，改造后重跑；两组数字写入 `docs/phases/phase5/plan.md` 与 `docs/COMPLETED_WORK.md`，不使用原型数据。

## 5. 涉及文件

### 前端

- `utils/frameThrottle.ts`（新增）：rAF + 50ms 合帧，push/flush/cancel。
- `utils/markdownSegments.ts`（新增）：token 级增量渲染与有界缓存。
- `components/MarkdownContent.vue`：`streaming` 属性与增量/整篇两条路径。
- `components/ChatMessage.vue`：透传 `streaming`。
- `stores/chat.ts`：content/thinking 合帧与结束 flush。
- `views/ChatView.vue`：自动跟底节流、虚拟窗口接入、定位与缩略导航改用偏移。
- `composables/useVirtualMessages.ts`（新增）：高度缓存、窗口计算与滚动定位。
- 测试：`frameThrottle.test.ts`、`markdownSegments.test.ts`、`useVirtualMessages.test.ts`、`MarkdownContent.test.ts` 增补、`stores/chat.test.ts` 增补。
- `e2e/perf-long-session.spec.ts`（新增，`@perf`）。

### 文档

- `docs/phases/phase5/plan.md`：P5-11-3 完成情况与实测数字。
- `docs/UPDATE_PLAN.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`：状态与渲染架构说明。

## 6. 验收标准

1. 500 条消息会话首屏 < 300ms，消息区 DOM 节点 < 500（`@perf` 用例实测）。
2. 60 秒流式期间无 > 50ms 主线程长任务；若存在，列出任务数与最大时长并说明原因，不以「无明显卡顿」代替数据。
3. 流式结束后正文与后端全量文本逐字一致；合帧不丢最后一段、不重复追加。
4. 消息数 ≤ 200 时行为与现状一致；> 200 时复制、搜索定位、缩略导航跳转、流式跟随全部正确。
5. 既有前端 Vitest、Playwright 全量与后端全量回归通过；`npm run build` 通过。
6. 亮暗主题与 `prefers-reduced-motion` 下无新增视觉回归。

## 7. 验证方式

- Vitest：合帧上限与 flush、token 增量缓存命中与淘汰、Markdown 流式/完成两路径一致性、虚拟窗口边界与定位校正、store 最终文本一致。
- Playwright：既有 14 条用例全量回归；新增 `@perf` 用例记录首屏、DOM、长任务与 60 秒流式数据；虚拟化下列表复制、全文搜索定位、缩略导航跳转各一条断言。
- 构建与回归：`npx vitest run`、`npm run build`、Playwright 全量、后端 `mvn -o test`。
- 完成后同步四份项目文档与本目录 tasks/checklist，并记录实际测试数与偏差。

## 8. 风险与取舍

- 虚拟化会限制跨消息的整段拖选；单条复制、代码复制、搜索定位与缩略导航跳转保持可用，边界写入文档。
- token 级增量渲染在流式中可能延迟引用式链接等跨段语义，结束时整篇解析保证最终一致。
- 估算高度会造成滚动条长度跳变，通过实测缓存与 overscan 缓解；不承诺像素级精确的滚动条比例。
- 长任务观测依赖 Chromium `longtask`；系统 Edge 与 CI chromium 可用，其他浏览器降级为不采集而不阻塞。
