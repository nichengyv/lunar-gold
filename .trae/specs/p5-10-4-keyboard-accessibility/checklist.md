# P5-10-4 键盘与无障碍 — 验收清单

> 状态：已完成（2026-11-15）
> 验证环境：Windows + 系统 Edge（`E2E_CHANNEL=msedge`）+ Vite dev（`localhost:5174`）

## 需求覆盖

- [x] `Ctrl+/` 打开快捷键面板，焦点进入面板，`aria-modal="true"` 生效。
- [x] Tab / Shift+Tab 在面板内循环，不逃逸到页面其他元素（`focusTrap.test.ts` 7 项 + E2E）。
- [x] Esc 或关闭按钮关闭面板后，焦点回到打开前元素；来源已卸载时聚焦输入框。
- [x] 会话主体是原生按钮，Tab 聚焦、Enter / Space 切换；`aria-current` 标记当前会话。
- [x] 仅键盘完成发送（Enter）、审批（J/K + A/D）、停止（Esc Esc）、切会话（Tab + Enter）；发送/审批/停止复用既有 E2E。
- [x] 图标按钮具备可读名称；审批队列 `role="region"`，审批卡片名称为「待审批：路径 / 命令」。
- [x] 审批到达/清空通过 `aria-live` 播报，且不被流式结束提示覆盖。
- [x] 设置页可手动开启「减少动效」，刷新后保持；未手动设置时跟随系统并监听系统变化。
- [x] 减少动效开启后无过渡动画与平滑滚动（`html[data-motion="reduce"]` + `isReducedMotion()`）。
- [x] axe 扫描 `/chat`、`/settings` 的 critical 为 0；serious 也为 0，moderate 剩 `page-has-heading-one`（两页）与 `landmark-unique`（设置页）。

## 兼容与回归

- [x] 现有 A/D/J/K 审批快捷键、批量审批、审批历史行为不变。
- [x] `Ctrl+K` 聚焦输入框、`Esc Esc` 停止、`@` 引用、`/` 命令行为不变（`Ctrl+K` 由浏览器拦截，未纳入 E2E）。
- [x] 断线续传、任务进度、ChangeSet、长会话虚拟滚动行为不变（Playwright 全量回归）。
- [x] 亮暗主题与会话项、按钮焦点环沿用原有 token，无新增硬编码颜色。
- [x] 窄窗口与 1024 图标栏下新增属性不改变布局（仅补属性与按钮结构，样式增量集中在 `.session-main`）。

## 质量门禁

- [x] 前端 `npx vitest run`：**12 文件 85 项通过**。
- [x] Playwright：**19 通过、1 跳过**（`@perf`）。
- [x] 前端 `npm run build` 通过（构建需将 `TEMP` 指向工作区，构建配置未改）。
- [x] 新增逻辑有单测覆盖：焦点循环 7 项、减少动效 3 项。
- [x] 无后端改动、无数据库迁移。
- [x] 新增依赖仅 `@axe-core/playwright`（devDependency）。

## 文档同步

- [x] `docs/phases/phase5/plan.md` 更新 P5-10-4 状态与验证结果。
- [x] `docs/UPDATE_PLAN.md` 更新 P5-10 矩阵、P5-11 计数与后续批次。
- [x] `docs/COMPLETED_WORK.md` 追加 8.16 交付与证据。
- [x] `docs/architecture/README.md` 更新键盘与无障碍现状与设计决策。
- [x] `docs/architecture/06-frontend.md` 更新组件表、样式体系与现状边界。
- [x] 本目录 `spec.md`、`tasks.md` 与 `checklist.md` 已记录实际结果与偏差。
