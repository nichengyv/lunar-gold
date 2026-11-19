# P5-09 剩余视觉项 — 验收清单

> 状态：已完成
> 说明：仅在实测通过后勾选；未跑过的测试不得标记完成。

## A. 工具运行态

- [x] A1 工具运行中折叠摘要显示名称与「已用 N 秒」，每秒更新（`ChatMessage.test.ts` 假时钟 4 秒 → 7 秒）。
- [x] A2 步骤行运行中显示「进行中 · N 秒」，结束后显示「已完成」，定时器随状态与卸载清理。
- [x] A3 耗时由 `Date.now()` 差值实时计算，后台节流只影响刷新频率，不会出现负数或 `NaN`（无 `startedAt` 时退回空串）。

## B. 消息密度

- [x] B1 设置页「外观」出现「消息密度」档位，默认舒适。
- [x] B2 切到紧凑后 `html[data-density="compact"]` 生效，`--density-row` 为 24px；舒适为 32px（`theme.test.ts` 2 项）。
- [x] B3 刷新后档位保持，亮暗两套共用同一组密度 token。

## C. 操作条与排版

- [x] C1 `.msg-actions` 增加 `:focus-within`，键盘 Tab 进入操作条时按钮可见。
- [x] C2 Markdown 表格有边框、表头底色、偶数行斑马纹，单元格内边距跟随密度 token。
- [x] C3 引用 chip 可见「第 N 段」，相关度仍保留在 `title`。

## D. 响应式

- [x] D1 960×640：横向溢出 0，输入框与发送按钮在视口内。
- [x] D2 900×700：侧栏 56px 图标栏，工作台以浮层打开（右缘 ≤900），遮罩可关闭，无横向滚动。
- [x] D3 767×700：侧栏默认在屏幕外，菜单按钮可打开抽屉，正文无横向滚动。
- [x] D4 1280×800：侧栏 280px，无横向滚动。
- [x] D5 `toggleSidebar()` 与 `closeSidebarOnMobile()` 断点一致（均为 `< 768`）。

## E. 三态与回归

- [x] E1 会话切换出现骨架，首屏空态展示项目概览，工作台各 tab 均有 icon + 主文案 + 次文案。
- [x] E2 `ErrorState` 含错误码 / 原因 / 重试 / 导出诊断（导出按钮为可选，会话加载失败时启用）。
- [x] E3 `npx vitest run` 12 文件 88 项全部通过。
- [x] E4 Playwright 全量 23 通过、1 跳过（`@perf` 按需运行，跳过原因既有）。
- [x] E5 `npm run build` 通过。

## F. 文档

- [x] F1 `spec.md` 第 8 节填写实际结果与偏差。
- [x] F2 `docs/phases/phase5/plan.md` 更新 P5-09 状态与验收结论。
- [x] F3 `docs/UPDATE_PLAN.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`、`docs/architecture/06-frontend.md` 同步。
- [x] F4 无后端改动、无数据库迁移、无新增依赖；截图目录 `frontend/test-results/` 已被 gitignore。
