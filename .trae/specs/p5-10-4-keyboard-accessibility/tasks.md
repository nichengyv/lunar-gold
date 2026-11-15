# P5-10-4 键盘与无障碍 — 任务拆分

> 状态：已完成（2026-11-15）
> 前置：spec.md 已确认

## T1 焦点工具与单测

- [x] T1.1 新增 `frontend/src/utils/focusTrap.ts`：`getFocusable(container)`、`trapTabKey(container, event)`。
- [x] T1.2 新增 `frontend/src/utils/focusTrap.test.ts`：首尾循环、Shift+Tab 反向、无可聚焦元素时不拦截。

验证：`npx vitest run src/utils/focusTrap.test.ts`（7 项通过）。

## T2 快捷键面板焦点管理

- [x] T2.1 面板加 `aria-modal="true"`，关闭按钮补 `aria-label`。
- [x] T2.2 打开时记录来源焦点并聚焦关闭按钮；关闭时恢复焦点，来源已卸载则回退输入框。
- [x] T2.3 `onGlobalKeydown` 中面板打开时用 `trapTabKey` 捕获 Tab。
- [x] T2.4 空态快捷键提示补「`Ctrl+/` 全部快捷键」。

验证：E2E 面板焦点用例 + `npm run build`。

## T3 会话项与图标按钮无障碍

- [x] T3.1 `AppLayout.vue` 会话项改为外层容器 + 原生 `<button class="session-main">`；Enter / Space 切换，`aria-current` 标记当前会话；「更多」按钮留在按钮外。
- [x] T3.2 为图标按钮补 `aria-label`：新任务、搜索、清空、新建工作区、删除工作区、在此新建任务、更多、侧栏收起/展开、主题、任务侧栏、附件、发送、停止、回到底部。
- [x] T3.3 `ApprovalCard.vue` 卡片补 `aria-label`（文件路径 / 命令摘要），关闭按钮补 `aria-label`；审批队列容器加 `role="region"` 与名称。
- [x] T3.4 缩略导航 `turn-tick` 补 `aria-label="跳到第 N 轮问答"` 与 `aria-current`。

验证：`npm run build`；E2E 键盘切会话用例。偏差：会话项未采用 `role="button"` + `aria-label`，改为原生按钮以避免嵌套交互与可读名称不匹配。

## T4 状态播报

- [x] T4.1 审批队列数量变化时写入 `liveAnnounce`：「有 N 项审批待确认，按 A 允许、D 拒绝、J/K 切换」。
- [x] T4.2 队列清空时播报「审批已处理完」；流式结束的「回答已生成」在仍有待审批时不覆盖审批提示。

验证：E2E 审批用例断言 `aria-live` 文本。

## T5 手动「减少动效」

- [x] T5.1 `theme` store 增加 `reduceMotion`、持久化、`applyMotion`，`init()` 时应用；`main.ts` 挂载前初始化。
- [x] T5.2 `utils/motion.ts` 新增 `isReducedMotion()`。
- [x] T5.3 `style.css` 只保留一条 `html[data-motion="reduce"]` 规则。
- [x] T5.4 `ChatView` 的 `scrollToBottom` / `scrollToMessage` 按 `isReducedMotion()` 选择 `auto` / `smooth`。
- [x] T5.5 `SettingsView` 外观区新增「减少动效」开关与说明。
- [x] T5.6 新增 `frontend/src/stores/theme.test.ts`：手动开关、持久化、跟随系统与系统变化监听。

验证：`npx vitest run src/stores/theme.test.ts`（3 项通过）。

## T6 axe 与键盘 E2E

- [x] T6.1 新增 devDependency `@axe-core/playwright` 并安装。
- [x] T6.2 新增 `frontend/e2e/accessibility.spec.ts`：
  - `Ctrl+/` 打开面板 → 焦点在面板内 → Tab 循环不逃逸 → Esc 关闭 → 焦点恢复；
  - 会话项 Tab 聚焦、Enter 切换、输入框保持可用；
  - `/chat` 与 `/settings` axe critical 为 0。
- [x] T6.3 系统 Edge 下 `E2E_CHANNEL=msedge` 运行新用例与全量回归。

验证：新用例 5/5，全量 19 通过、1 跳过（`@perf`）。

## T7 回归与文档

- [x] T7.1 前端 `npx vitest run` 全量通过（12 文件 85 项）。
- [x] T7.2 Playwright 全量通过；`npm run build` 通过。
- [x] T7.3 同步 `docs/phases/phase5/plan.md`、`docs/UPDATE_PLAN.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`、`docs/architecture/06-frontend.md`。
- [x] T7.4 勾选本目录 `tasks.md` 与 `checklist.md`，记录实际结果与偏差。
