# P6-05b 审批体验指标按入口拆分 - Product Requirements Document

> 状态：**已实施并通过全量回归**（2026-11-17）。范围只覆盖「审批体验指标按入口维度可区分」；实施结果见 `tasks.md` / `checklist.md`。
> 上游：P6-05 偏差 1「体验指标未按入口维度拆分」；P5-11-2 `GET /api/agent/metrics/experience` 既有口径。

## Overview

- **Summary**：在 P5-11-2 的体验指标响应里，为审批指标增加按入口拆分的分组数据（审批量 / 批准率 / 平均决策耗时），入口以 P6-05 落库的 `tool_approval.client_id` 与 `decision_source` 判定，不新增接口、不新增迁移、不做远程遥测。
- **Purpose**：P6-05 已经让每条审批记录能回答「谁从哪个入口批的」，但体验指标仍是混合口径：Web 与 CLI / VS Code 的审批量、批准率、决策耗时被平均在一起，无法判断某个入口是否被滥用或响应迟缓。
- **Target Users**：需要在本地诊断页查看「三入口审批行为是否一致」的用户；排查某客户端批准率异常或审批耗时异常的维护者。

## Goals

1. **入口可区分**：审批指标按 `WEB` / `CLIENT`（按 `client_id` 分组）/ `AUTO` / `UNKNOWN` 输出，四类互斥且可加总回原样本数。
2. **口径可解释**：批准率只统计人为决策（`USER` / `BATCH` / `CLIENT`），自动拒绝（`TIMEOUT` / `SESSION_STOP` / `SYSTEM`）不拉低批准率；决策耗时按组各自计算。
3. **纯增量**：既有 `approval.averageWaitMs` / `approval.sampleCount` 保持不变，只新增 `approval.byEntry`；不新增端点、不加索引、不改审批策略。
4. **本地可见**：设置页「体验诊断」的 Agent 体验卡片能直接看到分组结果，无样本时显示「—」，不伪造 0。

## Non-Goals

- **不按入口拆分 Run 指标**：首字时延、token、工具 Timer 仍为原有口径。发起入口 `agent_run.request_json.clientId` 与本批次无关。
- **不做历史数据回填**：V21 之前的审批 `client_id` 仍为 `NULL`；`decision_source` 为 `NULL` 的历史记录归入 `UNKNOWN`，不猜测为 `WEB`。
- **不新增数据库迁移或索引**：复用 `findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc`，在内存按最近 100 条窗口聚合；当前量级下无需 `(user_id, client_id)` 索引。
- **不新增接口 / 事件 / 配置项 / 采集开关**；不上传，不接遥测。
- **不改审批策略、scope、批量白名单**；不改 CLI / VS Code 代码。
- **不做跨用户或跨客户端对比报表**：只输出当前认证用户窗口内的分组。

## Background & Context

| 项 | 事实 | 位置 |
|---|---|---|
| 体验指标接口 | `GET /api/agent/metrics/experience` 返回 `ExperienceMetrics` | `controller/AgentMetricsController.java:39-42` |
| 审批指标口径 | 最近 100 条已决策审批，`decisionTime - createTime` 求平均，含手动 / 批量 / 自动 | `service/AgentExperienceMetricsService.java:85-97` |
| 审批窗口查询 | `findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc` | `repository/ToolApprovalRepository.java:14` |
| 审批来源枚举 | `DecisionSource = { USER, BATCH, CLIENT, TIMEOUT, SESSION_STOP, SYSTEM }` | `service/ToolApprovalRegistry.java:43` |
| 审批入口列 | `tool_approval.client_id VARCHAR(32)`，仅人为决策写入，自动来源与历史记录为 `NULL` | `entity/ToolApproval.java:52-54`、`db/migration/V21__add_tool_approval_client_id.sql` |
| 来源写入规则 | Web 单条 `USER`、客户端单条 `CLIENT`、批量 `BATCH`、自动三项来源 + `NULL` | P6-05 spec 语义矩阵 |
| 前端类型 | `AgentExperienceMetrics.approval = { averageWaitMs, sampleCount }` | `frontend/src/types/api.ts:423-443` |
| 前端展示 | Agent 体验卡片单格显示“审批平均等待” | `frontend/src/components/ExperienceDiagnosticsPanel.vue:103-107` |
| 测试基线 | 后端 367 / 0 / 0 / 15；前端 88；CLI 71；扩展 43 | P6-05 checklist |

关键推论：

- **入口只能从服务端已落库的字段推断**：`client_id` 区分 CLI / VS Code，`decision_source` 区分 Web 手动、批量与自动；不存在其它可信来源。
- **自动来源必须先判定**：自动拒绝也写 `state = REJECTED`，若直接按 `state` 计批准率会被超时稀释；因此 `AUTO` 单独成组，批准率为 `null`。
- **分组是窗口内聚合**：分组 `totalCount` 之和等于原 `sampleCount`，不改变 P5-11-2 的“最近 100 条”窗口语义。

## 设计决策

1. **入口判定（互斥，按顺序）**：
   - `decision_source ∈ {TIMEOUT, SESSION_STOP, SYSTEM}` → `entry = AUTO`，`clientId = null`（即使将来误写 `client_id` 也不改变归属）；
   - 否则 `client_id != null` → `entry = CLIENT`，按 `client_id` 分组；
   - 否则 `decision_source ∈ {USER, BATCH}` → `entry = WEB`，`clientId = null`；
   - 否则（`decision_source` 为 `NULL`，V19 之前的历史记录）→ `entry = UNKNOWN`，`clientId = null`。
2. **指标定义**（每个分组独立计算）：
   - `totalCount`：该组窗口内已决策审批条数；
   - `approvedCount` / `rejectedCount`：按 `state` 统计；
   - `approvalRate = approvedCount / (approvedCount + rejectedCount)`，分母为 0 时为 `null`；`AUTO` 恒为 `null`（自动拒绝不代表人工否决）；
   - `averageDecisionMs`：该组 `decisionTime - createTime` 的算术平均，无有效样本为 `null`。
3. **响应只做增量扩展**：`ApprovalMetrics` 增加 `List<ApprovalEntryMetrics> byEntry`；字段顺序为 `totalCount desc, entry asc, clientId asc`，便于前端稳定展示与测试断言。既有 `averageWaitMs` / `sampleCount` 不变。
4. **不改 Controller**：`AgentMetricsController` 与路径、响应信封不变，仅序列化新增字段。
5. **前端只加一张分组小表**：Agent 体验卡片内新增「审批入口」四列（入口 / 审批量 / 批准率 / 平均耗时），沿用既有 `.metric-grid` 与卡片样式；入口展示为「工作台」「客户端 · `<clientId>`」「自动」「来源未知」，无样本显示「—」。
6. **不做索引与 SQL 聚合**：窗口最多 100 条，Java 内存分组即可；避免为未知查询模式提前加索引。

## 响应结构（增量）

```json
{
  "approval": {
    "averageWaitMs": 2000.5,
    "sampleCount": 6,
    "byEntry": [
      { "entry": "WEB", "clientId": null, "totalCount": 3, "approvedCount": 2, "rejectedCount": 1,
        "approvalRate": 0.6666666666666666, "averageDecisionMs": 1800.0 },
      { "entry": "CLIENT", "clientId": "vscode-abc12345", "totalCount": 2, "approvedCount": 2, "rejectedCount": 0,
        "approvalRate": 1.0, "averageDecisionMs": 900.0 },
      { "entry": "AUTO", "clientId": null, "totalCount": 1, "approvedCount": 0, "rejectedCount": 1,
        "approvalRate": null, "averageDecisionMs": 120000.0 }
    ]
  }
}
```

## Functional Requirements

### FR-1 判定与分组

- 聚合输入仍是当前用户最近 100 条已决策审批，不新增查询条件。
- 分组必须满足上文“入口判定”的互斥顺序；同一 `client_id` 的 `CLIENT` 记录合为一组。
- 分组 `totalCount` 之和必须等于 `sampleCount`；不丢弃 `decision_source = NULL` 的历史记录。

### FR-2 指标计算

- `approvedCount` / `rejectedCount` 按 `state` 精确匹配 `APPROVED` / `REJECTED`，其它状态忽略（已决策查询本身应只含这两种，防御式忽略即可）。
- `approvalRate` 分母只含人为决策组（`WEB` / `CLIENT` / `UNKNOWN`）；`AUTO` 返回 `null`。
- `averageDecisionMs` 使用 `createTime` 与 `decisionTime`，任一为空不计入样本；无样本返回 `null`。
- 既有 `averageWaitMs` / `sampleCount` 公式与取值不变（仍含自动来源）。

### FR-3 接口与前端

- `GET /api/agent/metrics/experience` 路径、鉴权、scope、响应信封不变；新增字段向后兼容。
- `frontend/src/types/api.ts` 的 `AgentExperienceMetrics.approval` 增加 `byEntry` 数组及条目类型。
- `ExperienceDiagnosticsPanel.vue` 的 Agent 体验卡片增加分组展示；不新增页面、tab 或交互。
- 无分组数据（如无已决策审批）时不渲染空表，或显示「暂无已决策审批」空态；不显示伪造的 0%。

### FR-4 测试

- 后端单测（`AgentExperienceMetricsServiceTest`）覆盖：
  - Web 单条（`USER` + `null`）与批量（`BATCH` + `null`）合并为 `WEB`；
  - 不同 `client_id` 的 `CLIENT` 记录各自成组，同 `client_id` 合并；
  - `TIMEOUT` / `SESSION_STOP` / `SYSTEM` 归 `AUTO` 且 `approvalRate = null`；
  - `decision_source = NULL` 归 `UNKNOWN`；
  - 批准率分母、平均耗时、分组求和等于 `sampleCount`、排序稳定；
  - 空样本时 `byEntry` 为空数组。
- 前端：`ExperienceDiagnosticsPanel.test.ts` 增加分组渲染断言；`e2e/diagnostics.spec.ts` 与 `e2e/fixtures/api.ts` 的体验指标响应补 `byEntry`。
- 回归：后端 `mvn -o test`、前端 `npm test` + `vue-tsc`；CLI / 扩展测试不受影响（预期零改动）。

## 影响面

| 范围 | 影响 |
|---|---|
| 后端主代码 | `service/AgentExperienceMetricsService`（分组聚合与 record 扩展） |
| 后端其他 | 无。Controller、Repository、实体、迁移、权限均不改 |
| 后端测试 | `AgentExperienceMetricsServiceTest` 新增分组用例 |
| 前端 | `types/api.ts`、`components/ExperienceDiagnosticsPanel.vue`、对应单测与 e2e fixture |
| 数据库 | 无迁移、无索引 |
| 契约 | 响应新增字段，`public-api.json` 与 scope 不变 |
| CLI / VS Code | 零改动 |

## 验收

1. 单测矩阵：给出 6 条窗口内审批（2 Web 单条、1 Web 批量、2 个不同 CLI/VS Code 客户端、1 超时），`byEntry` 得到 `WEB totalCount=3`、两个 `CLIENT` 分组、`AUTO totalCount=1 且 approvalRate=null`，且 `totalCount` 之和为 6。
2. 口径校验：`AUTO` 拒绝不进入 `WEB` / `CLIENT` 批准率分母；`averageWaitMs` 与 P6-05 前一致。
3. 历史记录：`decision_source = NULL` 的审批归 `UNKNOWN`，不并入 `WEB`。
4. 前端：诊断页能看到分组表；无数据时为空态；`vue-tsc` 通过。
5. 零回归：后端 `mvn -o test` 0 failures；前端 `npm test` 通过；CLI / 扩展测试通过。

## 风险

| 风险 | 应对 |
|---|---|
| 自动拒绝污染批准率 | `AUTO` 单独成组且批准率为 `null`，判定优先级最高 |
| 历史 `NULL` 来源被误算成 Web | 单列 `UNKNOWN`，文档与测试同时约束 |
| 客户端被撤销后 `client_id` 无名称可读 | 只展示 `clientId`，不 join `api_client`；如需名称另立批次 |
| 响应扩展打破前端类型 | 同批更新 `types/api.ts` 与组件测试，`vue-tsc` 作为门禁 |
| 分组过多导致卡片拥挤 | 最多 4 类 + 客户端个数；按 `totalCount` 倒序，卡片内滚动/换行沿用现有网格 |
