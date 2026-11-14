# P5-10-2 审批效率剩余项 — 任务拆分

> 状态：待确认，确认后实施
> 前置：`spec.md` 已确认

## T1 决策来源与持久化

- [x] T1.1 新增 V19 迁移与 `ToolApproval.decisionSource`，旧记录保持 NULL。
- [x] T1.2 将 registry 等待结果扩展为 decision/source/batchId，手动、超时、会话停止、系统兜底分别落正确来源。
- [x] T1.3 启动恢复过期 PENDING 时写 TIMEOUT；保持决策竞争幂等与现有 REJECTED 安全语义。
- [x] T1.4 扩展 ReAct 审批回调、`AgentRunService` 和 `approval_resolved` SSE/Run Event，补齐 approvalId/source/batchId。
- [x] T1.5 扩展 registry、persistence、AgentRunService 聚焦测试，验证来源、审计字段与 ChangeSet 兼容。

## T2 批量与历史后端接口

- [x] T2.1 实现保守低风险白名单：仅 WRITE 的写/编辑文件工具，命令、删除、移动、建目录、高危均排除。
- [x] T2.2 新增 `POST /approvals/batch`：预检用户、sessionId、PENDING、低风险，生成 batchId，返回实际 resolvedIds。
- [x] T2.3 新增 `GET /sessions/{sessionId}/approvals`：先校验会话归属，再按 sessionId+userId 查询已处理历史，不返回 arguments。
- [x] T2.4 为批量越权/跨会话/高危拒绝、实际批准和历史归属添加后端测试。

## T3 前端类型、API 与 store

- [x] T3.1 扩展决策来源、历史项、批量请求/响应与 approval_resolved 类型。
- [x] T3.2 `api/agent.ts` 增加批量允许和历史查询；`api/sse.ts` 解析 source/batchId。
- [x] T3.3 store 新增批量 action，只乐观更新响应 resolvedIds；增加审批 revision 供历史刷新。
- [x] T3.4 单条提交失败时保留待审批，不再伪造 resolved；服务端事件到达后采用真实来源。
- [x] T3.5 添加低风险判定、批量更新、失败分支与 SSE 解析单测。

## T4 队列键盘交互与历史 UI

- [x] T4.1 `ChatView` 增加 selectedApprovalId，队列出现/缩减/切会话时按规则收口。
- [x] T4.2 实现非输入态 J/K 上下移动、边界不循环、当前项滚动可见和点击选中；A/D 只作用当前项。
- [x] T4.3 队列头更新快捷键提示，并在低风险项 ≥2 时显示「允许全部低风险（N）」。
- [x] T4.4 `ApprovalCard` 增加选中视觉态，沿用 token，亮暗主题可读。
- [x] T4.5 `WorkbenchDock` 追踪页增加审批历史，显示工具、决策、来源、时间及会话记忆标记；打开/切会话/审批完成时刷新。
- [x] T4.6 补充组件/store 测试，覆盖选中快捷键、批量入口和历史来源标签。

## T5 Playwright 审批效率回归

- [x] T5.1 复用/扩展审批夹具，支持同队列多个低/高风险审批、批量接口与历史接口仿真。
- [x] T5.2 新增纯键盘用例：J/K 选中后分别用 A 允许、D 拒绝，验证只提交当前 approvalId。
- [x] T5.3 新增混合队列用例：批量请求只含低风险 ID，高危卡片仍保留并可逐项操作。
- [x] T5.4 新增历史用例：本人/批量/超时/会话停止来源可见，切会话不串历史。
- [x] T5.5 Playwright 全量通过并 `--repeat-each=2` 复跑。

## T6 全量回归与文档

- [x] T6.1 后端 `mvn -o test` 全量通过。
- [x] T6.2 前端 `npx vitest run` 与 `npm run build` 通过。
- [x] T6.3 核对数据库迁移、旧事件兼容、批量安全边界和无原始参数泄漏。
- [x] T6.4 同步 `docs/UPDATE_PLAN.md`、`docs/phases/phase5/plan.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`。
- [x] T6.5 勾选本目录 tasks/checklist，记录实际测试数、偏差与未覆盖项。

## 依赖顺序

T1 → T2 → T3 → T4 → T5 → T6。T1 的审计模型是批量和历史的前置；T3/T4 可在对应后端契约确定后并行推进。
