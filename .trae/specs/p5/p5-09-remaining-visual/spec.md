# P5-09 剩余视觉项 — 规格说明

> 日期：2026-11-15
> 状态：已完成
> 对应规划：`docs/phases/phase5/plan.md` L292-L326（P5-09-2 / P5-09-3 / P5-09-4）
> 已存在基础：`tokens.css` 单一 token 层、`SkeletonMessage.vue`、`ErrorState.vue`、`wb-empty` 空态、代码块语言头与复制（P5-11-3）、三档侧栏策略

## 1. 目标

补齐 P5-09 中仍可前端自测的视觉项，不依赖外部模型：

1. 工具运行态显示「名称 + 已耗时」，完成后显示实际耗时。
2. 消息密度档位：紧凑（工具行 24px）/ 舒适（32px），只切换 token，不改组件结构。
3. 消息操作条在悬停**或键盘聚焦**时出现，不再只有鼠标可见。
4. Markdown 表格统一斑马纹与紧凑行高；引用 chip 直接显示文档名与位置。
5. 960×640 与三档宽度实测：无横向滚动、输入区与发送按钮可见，结果归档。
6. 复核加载 / 空 / 错误三态，确认不存在空白等待，缺口再补。

## 2. 非目标

- 不新建 `EmptyState.vue` 或 `ToolRunningState.vue`（规划明确要求复用现有组件）。
- 不重做思考 / 工具折叠结构，不重做代码块头与复制（已完成）。
- 不改后端接口、数据库或运行时依赖；不加新的 npm 依赖。
- 不做全站 WCAG 审计（P5-10-4 已完成 axe critical 门禁）。
- 不提供移动端手势、触屏专属布局。

## 3. 现状与缺口

| 能力 | 现状 | 缺口 |
|---|---|---|
| 工具运行态 | `stepLabel()` 运行态已输出「正在读取…」等名称；`ToolInvocation` 仅完成后有 `costMs` | 运行中没有开始时间与实时耗时 |
| 消息密度 | `tokens.css` 无 `--density-*`；`.step-row` / `.timeline` 行高固定 | 无档位 token、无设置入口、无持久化 |
| 消息操作条 | `.message.assistant:hover .msg-actions` 才显示 | 键盘 Tab 进入按钮时不可见 |
| Markdown 表格 | `marked` 默认输出 `<table>`，`style.css` 无表格样式 | 无边框、无斑马纹、行高不紧凑 |
| 引用来源 | `source-chip` 显示文档名，段落在 `title` 里 | 位置不可见，需悬停才能知道 |
| 响应式 | `<1024` 侧栏自动收起；`<768` 抽屉；`<1024` 工作台仍内联挤压正文 | 断点判断 `769/768` 不一致；窄屏工作台缺浮层策略；无 960×640 回归 |
| 三态 | 会话切换用 `SkeletonMessage`、首屏 `chat-empty`、工作台 `wb-empty`、错误 `ErrorState` | 复核后按需补，不重复造 |

## 4. 设计决策

### 4.1 工具运行态与耗时

- `types/api.ts` 的 `ToolInvocation` 增加 `startedAt?: number`，仅前端写入，不参与接口契约。
- `stores/chat.ts` 的 `onToolCall` push 时写入 `startedAt: Date.now()`；`onToolResult` 继续写 `costMs`。
- `ChatMessage.vue`：
  - 新增 `now` ref 与 `startedAt` 轮询：仅当 `hasRunning` 为真时每秒更新，`onUnmounted` 与 `watch(hasRunning)` 负责清理，避免空闲页面持续刷新。
  - 新增 `toolElapsed(tool)`：运行中 `now - startedAt`，结束后用 `costMs`；用现有 `formatDuration()` 输出「不到 1 秒 / N 秒 / M 分 S 秒」。
  - 折叠摘要：运行中显示 `stepLabel(tool) · 已用 X`；步骤行 `.step-state` 运行中显示 `进行中 · X`。
  - 完成后摘要继续用 `msg.durationMs`，保持既有文案。

### 4.2 消息密度档位

- `tokens.css` 新增：
  - `--density-row: 32px`、`--density-gap: 6px`、`--density-pad-y: 6px`（舒适，默认）。
  - `[data-density="compact"]` 覆盖为 `24px / 4px / 4px`。
- `stores/theme.ts` 增加 `density: 'comfortable' | 'compact'`：
  - localStorage 键 `density`，默认 `comfortable`；
  - `applyDensity()` 写 `<html data-density="compact">`，舒适时移除属性；
  - `setDensity()` 持久化并应用，`init()` 时应用。
- `views/SettingsView.vue` 外观区新增「消息密度」卡片，`el-radio-group`（舒适 / 紧凑）+ `aria-label`。
- `styles/style.css` 只切 token：
  - `.step-row { min-height: var(--density-row); }`
  - `.timeline { gap: var(--density-gap); }`
  - Markdown 表格单元格纵向内边距用 `--density-pad-y`。

### 4.3 操作条键盘可见

- `style.css` 增加 `.message.assistant:focus-within .msg-actions { opacity: 1; }`。
- 保留 `@media (max-width:768px)` 下常显，避免窄屏依赖悬停。

### 4.4 Markdown 表格与引用卡

- `style.css` 新增 `.md-content table` 基础样式：`width:100%`、`border-collapse:collapse`、`font-size:var(--fs-sm)`。
- `th` 用 `--surface-2` 背景与 `--text-secondary` 文字；`td` 底边 `1px solid var(--border)`；`tbody tr:nth-child(even)` 用 `--surface-2` 斑马纹；单元格 `padding: var(--density-pad-y) var(--space-3)`。
- `ChatMessage.vue` 的 `.source-chip` 在 `chunkIndex != null` 时追加可见 `<span class="source-pos">第 N 段</span>`；`title` 保留相关度，避免信息丢失。

### 4.5 响应式与 960×640

- 断点统一为 `< 768`：`AppLayout.vue` 的 `toggleSidebar()` 由 `window.innerWidth < 769` 改为 `< 768`，与 `closeSidebarOnMobile()` 一致。
- 768–1023：工作台改为浮层，不再挤压正文：
  - `ChatView.vue` 复用已有 resize 监听维护 `viewportWidth`，新增 `isNarrowDock = computed(() => viewportWidth < 1024)`。
  - 窄屏且 `chat.dockOpen` 时渲染 `.dock-backdrop`（半透明，点击关闭）与浮层化 `.workbench`。
  - `styles/style.css` / `ChatView.vue` 样式：`@media (max-width:1023px)` 下 `.workbench:not(.fullscreen)` 改 `position:fixed; right:0; top:var(--header-height); bottom:0; width:min(calc(100vw - 56px), 520px); z-index:2400`；`.dock-resizer` 隐藏。
- 960×640、900×700、767×700 三档只做回归与归档，不新增断点。

### 4.6 三态复核

- 复核项：会话切换骨架、首屏空态、工作台 5 个 tab 空态、`ErrorState`（错误码 / 可读原因 / 重试 / 导出诊断）。
- 只有发现某处仍空白等待时，才用现有组件补；本规格不预设新增组件。

### 4.7 响应式 E2E 与归档

- 新增 `frontend/e2e/responsive.spec.ts`，复用 `e2e/fixtures/api.ts` 的 `mockApi`：
  - 960×640：`scrollWidth <= clientWidth + 1`；输入框与发送按钮 `toBeInViewport`；侧栏为 56px 图标栏。
  - 900×700：侧栏图标栏；顶栏「任务详情侧边栏」可见；打开工作台后无横向滚动且浮层在视口内。
  - 767×700：侧栏默认不可见；菜单按钮可打开抽屉；正文无横向滚动。
  - 1280×800：侧栏展开（280px）；无横向滚动。
- 截图写入 `frontend/test-results/responsive/`（gitignore，不入库）；关键数值写入 `tasks.md` 与 `docs/phases/phase5/plan.md`。

## 5. 实施范围

| 文件 | 改动 |
|---|---|
| `frontend/src/types/api.ts` | `ToolInvocation` 增加 `startedAt?: number` |
| `frontend/src/stores/chat.ts` | `onToolCall` 写入 `startedAt` |
| `frontend/src/components/ChatMessage.vue` | 运行耗时轮询、摘要与步骤态、引用位置 |
| `frontend/src/stores/theme.ts` | `density` 状态、持久化、`applyDensity` / `setDensity` |
| `frontend/src/views/SettingsView.vue` | 外观区「消息密度」档位 |
| `frontend/src/views/ChatView.vue` | 窄屏工作台浮层与遮罩、`viewportWidth` |
| `frontend/src/layouts/AppLayout.vue` | 断点判断统一 `< 768` |
| `frontend/src/styles/tokens.css` | `--density-*` 与紧凑覆盖 |
| `frontend/src/styles/style.css` | 操作条 `:focus-within`、表格、密度应用、窄屏工作台 |
| `frontend/src/stores/theme.test.ts` | 密度持久化与属性写入单测 |
| `frontend/e2e/responsive.spec.ts` | 新增四组视口回归 |
| `.trae/specs/p5/p5-09-remaining-visual/*` | 本文档、tasks、checklist |

## 6. 验收标准

- [ ] 工具运行中摘要与步骤行显示「已用 N 秒」，完成后显示实际耗时；无运行中工具时不再有每秒定时器。
- [ ] 设置页可切换「舒适 / 紧凑」，`.step-row` 高度分别为 32px / 24px，刷新后保持。
- [ ] 键盘 Tab 进入消息操作条时按钮可见（`:focus-within`）。
- [ ] Markdown 表格有边框、斑马纹与紧凑行高，暗色下可读。
- [ ] 引用 chip 可见「第 N 段」，相关度仍在 `title`。
- [ ] 960×640 无横向滚动，输入区与发送按钮在视口内。
- [ ] 900×700 与 767×700 行为符合断点，打开工作台不产生横向滚动。
- [ ] `npx vitest run`、Playwright 全量、`npm run build` 通过；无后端改动、无新增依赖。

## 7. 风险与边界

- 每秒定时器只在有运行中工具时开启；后台标签页定时器可能被节流，耗时显示略滞后，属可接受。
- 窄屏工作台改为浮层后不再支持拖拽改宽（`.dock-resizer` 隐藏），宽度由视口决定。
- 截图目录不进仓库，归档以 E2E 断言数值与用例名称为准。
- 密度档位只影响工具行、时间线间距与表格内边距，不改变字号与正文行高。

## 8. 实际结果

2026-11-15 实施并验证完成。

- **单元测试**：`npx vitest run` → **12 文件 88 项全部通过**（新增 `theme` 密度 2 项、`ChatMessage` 运行耗时 1 项）。
- **E2E**：系统 Edge（`E2E_CHANNEL=msedge`）`npx playwright test` → **23 通过、1 跳过**（`@perf`）。新增 `responsive.spec.ts` 4 条全部通过。
- **构建**：`npm run build` 通过（本机将 `TEMP/TMP` 指向 `frontend/.tmp` 规避 Windows 临时文件占用）。
- **实测数值**：960×640 侧栏 56px、`scrollWidth - clientWidth = 0`、输入框与发送按钮在视口内；900×700 浮层右缘 900、遮罩点击可关闭、无横向滚动；767×700 侧栏默认 `x < 0`，点击「展开侧栏」后 `x ≥ 0`；1280×800 侧栏 280px。
- **截图归档**：`frontend/test-results/responsive-*/`，已被 `frontend/.gitignore` 忽略；Playwright 每次运行会清理重建。
- **偏差与边界**：
  1. 三态复核未发现空白等待，唯一缺口是 `ErrorState` 缺导出诊断入口，已用可选 `exportable` 补齐；未新建 `ToolRunningState.vue` / `EmptyState.vue`。
  2. 窄屏工作台改为浮层后隐藏拖拽条，宽度由视口决定，不再支持拖拽改宽。
  3. 运行中耗时依赖本地时钟；后台标签页定时器被节流时显示会跳跃而非平滑递增。
  4. 首轮响应式用例读取到侧栏宽度过渡与浮层入场动画的中间态，改为 `expect.poll` 等待稳定；未改产品行为。
  5. 本机临时 dev server 仅监听 IPv6，E2E 运行时使用 `E2E_BASE_URL=http://localhost:5174`；属于当前演示环境，不影响用例本身。
