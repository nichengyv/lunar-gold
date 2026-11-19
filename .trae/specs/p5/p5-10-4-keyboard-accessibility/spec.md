# P5-10-4 键盘与无障碍 — 规格说明

> 日期：2026-11-15
> 状态：已完成（2026-11-15）
> 对应规划：`docs/phases/phase5/plan.md` L371-L377、`docs/UPDATE_PLAN.md` P5-10 矩阵
> 已存在基础：`Ctrl+/` 快捷键面板、A/D/J/K 审批键位、`Esc Esc` 停止、`:focus-visible` 焦点环、`aria-live` 播报、`prefers-reduced-motion` 媒体查询

## 1. 目标

让键盘用户和读屏用户能独立走完关键路径，并让无障碍结果可复现、可验收：

1. 仅用键盘完成 **发送 → 审批 → 停止 → 切会话**。
2. 快捷键面板可发现、可聚焦、焦点不逃逸、关闭后焦点回到原处。
3. 图标按钮有可读名称；流式、审批与连接状态由 `aria-live` 播报。
4. 「减少动效」既跟随系统，也能在设置页手动开启并持久化。
5. axe 扫描 `/chat` 与 `/settings` 的 critical 违规为 0。

## 2. 非目标

- 不重做视觉与布局，不改动审批卡片的信息结构（P5-10-2 已完成）。
- 不做完整 WCAG AA 全站审计，只以 axe critical 为门禁，serious/moderate 记录不阻断。
- 不引入读屏软件人工验收结论；播报正确性由 DOM 与单测/E2E 断言证明。
- 不新增后端接口、数据库迁移或运行时依赖。
- 不做移动端手势或语音交互。

## 3. 现状与缺口

| 能力 | 现状 | 缺口 |
|---|---|---|
| 快捷键面板 | `Ctrl+/` 打开，`role="dialog"`，6 组按键说明 | 无 `aria-modal`、无初始焦点、Tab 可逃到页面外、关闭不恢复焦点、关闭按钮仅 `title` |
| 会话切换 | `.session-item` 是带 `@click` 的 `div` | 不可 Tab 聚焦、无 `role`/`aria-current`、Enter/Space 不生效 |
| 发送 / 停止 / 审批 | `Enter` 发送、`Esc Esc` 停止、A/D/J/K 审批已可用 | 无“仅键盘”E2E 回归；切会话后不聚焦输入框 |
| 图标按钮 | 多数只有 `title`（附件、发送、停止、主题、侧栏、回到底部、更多等） | 缺少显式 `aria-label`，可读名称依赖工具提示 |
| 状态播报 | `isProcessing` 与 `streamStatus` 已播报 | 审批到达/队列变化不播报 |
| 焦点环 | 全局 `:focus-visible` 已有 | 自定义面板与列表项未验证；无计算样式断言 |
| 减少动效 | 只有 `prefers-reduced-motion` 媒体查询 | 无手动开关、无持久化；JS `scrollTo({behavior:'smooth'})` 不受媒体查询约束 |
| axe 扫描 | 无 | 无依赖、无用例、无基线 |

## 4. 设计决策

### 4.1 面板焦点管理（新增 `utils/focusTrap.ts`）

- `getFocusable(container)`：返回可见、未禁用、`tabindex >= 0` 的可聚焦元素。
- `trapTabKey(container, event)`：`Tab` / `Shift+Tab` 在首尾之间循环，返回是否已处理。
- 打开面板时：记录 `document.activeElement`，把焦点移到关闭按钮；面板加 `aria-modal="true"`。
- `Tab` 被 `keydown` 捕获并循环；`Esc` 与关闭按钮关闭；关闭后焦点恢复到打开前的元素（元素已卸载时回退到输入框）。
- 面板关闭按钮补 `aria-label="关闭快捷键面板"`。

### 4.2 会话项键盘化

- 会话项外层保留 `div`，主体改为原生 `<button class="session-main">`，「更多」按钮保留在按钮外，避免 `role=button` 包含按钮的嵌套交互。
- 原生按钮自带 Enter / Space 切换与 Tab 聚焦；`aria-current` 标记当前会话，读屏名称取可见标题与时间，不使用会覆盖可见文本的 `aria-label`。

### 4.3 图标按钮与状态播报

- 为无文本按钮补 `aria-label`（附件、发送、停止、回到底部、主题切换、侧栏收起/展开、搜索、清空、更多、删除、滚动缩略条等），保留 `title`。
- 审批区：队列容器加 `role="region" aria-label="待审批队列"`；卡片本身补 `aria-label`（文件路径或命令摘要）。
- 复用现有 `aria-live="polite"`：审批队列数量变化时播报「有 N 项审批待确认，按 A 允许、D 拒绝、J/K 切换」。

### 4.4 手动「减少动效」

- `theme` store 增加 `reduceMotion: boolean`，localStorage 键 `reduceMotion`（`reduce` / `full`）；未设置时跟随系统并监听 `matchMedia` 变化。
- `applyMotion(reduce)`：在 `<html>` 写 `data-motion="reduce"` 或移除属性；`main.ts` 在挂载前初始化 store，系统偏好由 `matchMedia` 读取并监听变化。
- `styles/style.css` 只保留一条 `html[data-motion="reduce"]` 规则，不再重复维护媒体查询块。
- JS 平滑滚动：新增 `utils/motion.ts` 的 `isReducedMotion()`（`data-motion=reduce` 或 `matchMedia` 命中），`ChatView` 的 `scrollToBottom` / `scrollToMessage` 在减少动效时使用 `behavior: 'auto'`。
- 设置页「外观」新增开关「减少动效」，说明文案为「关闭过渡与平滑滚动，减少视觉干扰」。

### 4.5 axe 扫描

- 新增 devDependency `@axe-core/playwright`（已安装）；仅测试使用，不进生产包。
- 新增 `frontend/e2e/accessibility.spec.ts`：对 `/chat` 与 `/settings`（外观 tab）运行 `AxeBuilder`，断言 `violations.filter(v => v.impact === 'critical')` 为空；失败时输出节点摘要。
- 兼容现有 API 仿真夹具，不依赖真实后端。

### 4.6 仅键盘路径回归

- 新增 E2E：`Ctrl+/` 打开面板 → 焦点进入面板 → Tab 循环不逃逸 → Esc 关闭且焦点恢复；会话项 Tab 聚焦 + Enter 切换；`/chat` 与 `/settings` axe critical 为 0。
- 发送、审批、停止的键盘路径复用既有用例（`chat-stream`、`approval`、`stop`），不重复造用例，只在验收清单中引用。`Ctrl+K` 会被浏览器地址栏拦截，维持既有实现，不放入 E2E。

## 5. 实施范围

| 文件 | 改动 |
|---|---|
| `frontend/src/utils/focusTrap.ts` | 新增焦点查询与 Tab 循环纯函数 |
| `frontend/src/utils/motion.ts` | 新增 `isReducedMotion()` |
| `frontend/src/stores/theme.ts` | 增加 `reduceMotion` 状态、持久化与 `applyMotion` |
| `frontend/src/views/ChatView.vue` | 面板焦点管理、会话项外的 `aria-label`、审批播报、滚动行为 |
| `frontend/src/layouts/AppLayout.vue` | 会话项键盘化、图标按钮 `aria-label` |
| `frontend/src/components/ApprovalCard.vue` | 卡片 `aria-label`、关闭按钮 `aria-label` |
| `frontend/src/components/state/*` | 仅补必要 `aria-label`，不改视觉 |
| `frontend/src/views/SettingsView.vue` | 外观区「减少动效」开关 |
| `frontend/src/styles/style.css` | `[data-motion="reduce"]` 与媒体查询共用规则 |
| `frontend/src/utils/focusTrap.test.ts`、`frontend/src/stores/theme.test.ts` | 新增单测 |
| `frontend/e2e/accessibility.spec.ts` | 新增 axe 与键盘 E2E |
| `frontend/package.json` | 新增 `@axe-core/playwright` devDependency |

## 6. 验收标准

- [ ] `Ctrl+/` 打开面板后焦点在面板内，连续 Tab / Shift+Tab 不跳到页面其他元素，Esc 关闭后焦点回到打开前元素。
- [ ] 仅用键盘可完成：Tab 到会话项按 Enter 切换、Enter 发送、J/K 选择审批、A/D 决策、Esc Esc 停止。
- [ ] 所有图标按钮具备可读名称（`aria-label` 或等效文本），审批队列有区域语义。
- [ ] 审批到达与队列变化通过 `aria-live` 播报，不逐字打断。
- [ ] 设置页可手动开启「减少动效」，刷新后保持；开启后无平滑滚动与过渡动画；默认跟随系统。
- [ ] axe 扫描 `/chat`、`/settings` 的 critical 违规为 0。
- [ ] `npx vitest run`、Playwright 全量、`npm run build` 通过；无后端改动、无数据库迁移。

## 7. 风险与边界

- axe 只能覆盖可自动检测的规则，不能证明完全无障碍；结论限定为「automated critical 为 0」。
- `@axe-core/playwright` 为新增测试依赖，若安装受网络限制则改为本地 `axe-core` 注入并记录偏差。
- 会话项「更多」按钮不能嵌在会话按钮内，否则触发 axe `nested-interactive`；采用外层容器 + 并列按钮结构。
- 虚拟滚动与减少动效互不影响；面板焦点逻辑不涉及流式渲染。

## 8. 实际结果（2026-11-15）

- **前端单测**：`npx vitest run` **12 文件 85 项通过**，其中新增 `focusTrap.test.ts` 7 项、`theme.test.ts` 3 项。
- **E2E**：系统 Edge 下 `npx playwright test` **19 通过、1 跳过**（`@perf`）；新增 `accessibility.spec.ts` 5 条全部通过。
- **构建**：`npm run build` 通过。构建时 esbuild 临时文件删除被本机环境拦截，改用工作区 `TEMP` 后通过，未改构建配置。
- **axe 复核**：`/chat` 与 `/settings` critical 与 serious 均为空；moderate 仅剩 `page-has-heading-one`（两页）与 `landmark-unique`（设置页），不阻断本次验收。
- **偏差**：`Ctrl+K` 被浏览器地址栏拦截，未纳入 E2E，快捷键实现未改动；会话项由 `role="button"` 改为原生按钮结构，以消除 axe 的 `nested-interactive` 与 `label-content-name-mismatch`。
- **依赖与范围**：仅新增 devDependency `@axe-core/playwright`；无后端改动、无数据库迁移。
