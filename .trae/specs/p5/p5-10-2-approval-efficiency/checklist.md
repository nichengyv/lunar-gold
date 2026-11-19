# P5-10-2 审批效率剩余项 — 验收清单

## 需求覆盖

- [x] J/K 在多个待审批间移动，当前项有明确视觉态并滚动到可见位置；边界不循环。
- [x] 输入框和带修饰键场景不触发 J/K/A/D；A/D 只处理当前选中审批。
- [x] 当前项处理、队列缩减或切换会话后，选中项仍有效，不发生误批。
- [x] 至少两个低风险项时出现「允许全部低风险（N）」，一次请求只批准这些明确 ID。
- [x] 命令、删除、移动、建目录、DESTRUCTIVE 和其他非白名单工具始终逐项确认。
- [x] 后端拒绝跨用户、跨会话、非 PENDING 或含高危 ID 的批量预检，前端白名单不作为安全边界。
- [x] 每条批量决策写 `decisionSource=BATCH`，对应 Run Event 含相同 batchId 和完整 approvalId/decision/source。
- [x] 追踪页可回看工具、批准/拒绝、来源和决策时间；本人、批量、超时、会话停止、系统兜底标签正确。
- [x] 旧历史来源显示未知，不推断为本人；历史不返回或展示原始 arguments。
- [x] 审批历史按会话隔离，页面刷新后仍可查询，历史失败不掩盖工具统计。

## 兼容与回归

- [x] 现有 A/D、倒计时、详情折叠、风险摘要和「本会话总是允许」行为不回归。
- [x] 手动/超时/停止竞争不会产生重复或相互覆盖的决策；自动路径仍为 REJECTED。
- [x] 审批提交失败保留待审批，不伪造本地成功状态。
- [x] 旧 APPROVAL_RESOLVED 事件缺新字段时可兼容；ChangeSet 审批摘要正常。
- [x] 断线恢复、会话切换、停止生成和长任务进度行为不回归。
- [x] 亮暗主题下选中态、批量按钮、历史来源和风险状态可读。

## 质量门禁

- [x] 后端 registry、persistence、Run Event、批量与历史归属聚焦测试通过。
- [x] 前端低风险、批量 store、失败分支、SSE、选中交互与历史展示单测通过。
- [x] Playwright 覆盖纯键盘允许/拒绝、混合队列批量和历史来源，完整套件重复运行通过。
- [x] 后端 `mvn -o test` 全量通过。
- [x] 前端 `npx vitest run` 全量通过。
- [x] 前端 `npm run build` 通过。
- [x] V19 迁移可执行，旧数据无需回填，无新增第三方依赖。

## 文档同步

- [x] `docs/UPDATE_PLAN.md` 更新 P5-10-2 状态与测试数。
- [x] `docs/phases/phase5/plan.md` 更新进度快照和 P5-10-2 完成情况。
- [x] `docs/COMPLETED_WORK.md` 追加实现、验证证据和边界。
- [x] `docs/architecture/README.md` 更新审批选中、批量安全边界、来源审计与历史架构。
- [x] 本目录 tasks/checklist 已按实际完成情况勾选，并记录偏差与未覆盖项。

## 实际结果（2026-11-14）

- 后端 `mvn -o test`：290 项通过，0 失败，12 skipped；`ToolApprovalRegistryTest` 14 项、`ToolApprovalRegistryPersistenceTest` 5 项、`AgentRunServiceTest` 8 项。
- 前端 `npx vitest run`：38/38 通过（新增 6 项：白名单、批量 resolvedIds、失败不伪造、成功来源、SSE source/batchId、选中态）。
- Playwright：13/13 全量通过；`e2e/approval.spec.ts --repeat-each=2` 10/10 通过。
- 偏差：本机未安装 Playwright 自带 Chromium，E2E 以 `E2E_CHANNEL=msedge` 复用已运行 dev server 执行；未新增依赖。
- 未覆盖：亮暗主题仅通过 token 复用与构建验证，未做双主题截图对比；批量以当前会话审批队列界定，未引入 `runId` 字段。
