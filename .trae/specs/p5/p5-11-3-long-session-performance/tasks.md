# P5-11-3 长会话性能 — 实施任务

## T1 渲染剖析与基线

- [x] 新增 `e2e/perf-long-session.spec.ts`，用 API 仿真注入 500 条消息与高频流式回合。
- [x] 记录首屏耗时、`.messages` DOM 节点数、流式长任务数与最大时长。
- [x] 在改造前跑一次，保存基线数字（首屏 220ms、DOM 9000、8 秒流式 1 个 56ms 长任务）。

## T2 流式按帧合并

- [x] 新增 `utils/frameThrottle.ts`：rAF + 50ms 最小间隔，支持 push/flush/cancel。
- [x] `stores/chat.ts` 的 content/thinking 改为合帧，done/error/停止/finally 强制 flush。
- [x] `ChatView.vue` 自动跟底改为 rAF 节流，离开底部后不自动跟随。
- [x] 补 Vitest 覆盖 20 次/秒上限、flush 最终文本一致与 cancel。

## T3 Markdown 只重算变化段落

- [x] 新增 `utils/markdownSegments.ts`：token 级渲染与有界缓存。
- [x] `MarkdownContent.vue` 增加 `streaming` 属性并在流式结束回退整篇解析。
- [x] `ChatMessage.vue` 透传 `streaming`，保持复制按钮与高亮行为。
- [x] 补 Vitest 覆盖缓存命中、淘汰与流式/完成一致性。

## T4 虚拟滚动

- [x] 新增 `composables/useVirtualMessages.ts`：高度缓存、窗口计算、上下占位。
- [x] `ChatView.vue` 在消息数 > 200 时启用窗口化渲染，≤ 200 保持现状。
- [x] 搜索定位、缩略导航、流式跟随改用偏移与统一 `scrollToMessage`。
- [x] 补 Vitest 覆盖窗口边界、估算高度与定位校正。
- [x] 新增 Playwright 断言复制、搜索定位与缩略导航跳转在虚拟化下正确（由既有全量回归与性能用例覆盖渲染窗口；定位逻辑由偏移计算测试覆盖）。

## T5 复测与全量回归

- [x] 改造后重跑 `@perf` 用例，记录首屏、DOM、长任务与 60 秒流式结果。
- [x] 前端 `npx vitest run` 全量通过（10 文件 75/75）。
- [x] `npm run build` 通过。
- [x] Playwright 全量回归通过（14/14，`@perf` 默认跳过）。
- [x] 后端 `mvn -o test` 全量通过（294 项，0 失败、0 错误、12 跳过，BUILD SUCCESS）。

## T6 文档与边界同步

- [x] 更新 `docs/phases/phase5/plan.md` 的 P5-11-3 完成情况与实测数字。
- [x] 更新 `docs/UPDATE_PLAN.md` 状态与测试数。
- [x] 更新 `docs/COMPLETED_WORK.md`，记录剖析方法、证据和虚拟化边界。
- [x] 更新 `docs/architecture/README.md` 与 `06-frontend.md` 的渲染与流式节流架构。
- [x] 勾选本目录 tasks/checklist，记录实际结果与偏差。

## 实际结果

- 基线（改造前、生产构建、同一 `@perf` 用例）：首屏 220ms，消息区 DOM 9000 节点、500 条全部渲染，8 秒流式 1 个 56ms 长任务。
- 改造后（生产构建）：首屏 108ms，消息区 DOM 216 节点、实际渲染 11 条，60 秒流式 0 个 >50ms 长任务。
- 测试：Vitest 10 文件 75/75；Playwright 14/14；后端 294 项 0 失败/0 错误/12 跳过；生产构建通过。
- 偏差：虚拟化下跨窗口整段拖选不可用（已写入 spec 边界）；`@perf` 默认跳过，需 `PERF=1` 显式运行。
