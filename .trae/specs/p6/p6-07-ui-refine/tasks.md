# P6-07 交互收敛 - 任务拆分

> 状态：全部完成（2026-11-17）。

## T1 审批卡片中等精简 + 移除倒计时（完成）

- 删除头部风险 chip 与 `.approval-timer`；文件行合并为 `写入文件 · dir/name · +N −M`，不再重复「删除」徽标。
- 删除 `remaining`、`tick()`、1 秒 `setInterval` 与到期 `dismissApproval`；仅保留 `approval_resolved` 后 3 秒自动收起。
- 主操作保留「拒绝 D」「允许 A」；`本会话总是允许` 移入 `⋯` 菜单，点击外部收起。
- 测试：删除「倒计时到 0 自动关闭」，新增「不显示倒计时且过期不自行关闭」「更多菜单提交本会话总是允许」。

## T2 气泡用量与操作常显（完成）

- `UiMessage` 增加可选 `usage`；`onUsage` 同时写入当前 AI 消息（Run 累计值）。
- `ChatMessage` 在 `重新生成` 右侧渲染 `.msg-usage`（`2.0k token`，悬停显示输入 / 输出）；耗时仍只在执行摘要展示。
- `.msg-actions` 去掉 `opacity: 0` 与悬停规则，移除移动端覆盖。

## T3 诊断入口分层（完成）

- 删除 `BASE_TABS` 中的 `diagnostics` 与对应模板块。
- 应用信息页新增默认收起的「高级诊断」卡片，展开后挂载 `ExperienceDiagnosticsPanel`。
- 新增「显示验收指标」勾选，默认关闭，状态写入 `agent.diagnostics.show-acceptance.v1`；面板新增 `showAcceptance` prop，默认 `true` 以保持既有测试语义。

## T4 测试与文档（完成）

- 前端 `npm test -- --run`：12 个文件 / 92 条通过；`npx vue-tsc --noEmit` 无报错。
- `frontend/e2e/diagnostics.spec.ts` 改为「应用信息 → 高级诊断 → 勾选验收指标」路径，未运行（需后端）。
- 本目录三份文档与 `docs/phases/phase6/plan.md` 的 P6-07 段落同步。
