# 01 Agent Runtime：ReAct 执行层（部分完成）

> 更新日期：2026-11-13
> 状态：已完成真实循环、完成控制与无进展检测；诊断与 verify 尚未接入
> 关联：[总架构](README.md)、[工具与审批](03-tools-approval-sandbox.md)、[质量评测](10-quality-evaluation.md)

---

## 1. 定位

Agent Runtime 是 Lunar Gold 的内部执行层，负责一次任务从“用户提问”到“交付状态”的全过程：

```text
组装上下文 → 调模型 → 解析工具调用 → 审批 → 执行工具 → 回灌结果 → 再推理
                                                        ↓
                                          停止条件 → 完成判定 → 事件收尾
```

它不是独立产品，也不是差异化本身。差异化来自“可控 + 可验证 + 可恢复”，Runtime 只是承载这些约束的地方。

核心文件：

| 文件 | 职责 |
|---|---|
| `service/ReActAgentService.java` | 主循环、消息组装、工具分发、完成判定、无进展检测 |
| `service/AgentPolicy.java` | 推理档位、工作权限、预算 |
| `service/AgentPolicyResolver.java` | 前端参数归一化与回退 |
| `service/AgentRunService.java` | Run 状态机与事件落库 |
| `entity/AgentRun.java`、`AgentRunEvent.java` | 运行与事件实体 |
| `client/AgentClient.java`、`SpringAiAgentClient.java` | 模型调用抽象与实现 |

---

## 2. 入口与输入

`ReActAgentService.execute(...)` 的输入大致分为：

| 类别 | 内容 |
|---|---|
| 身份 | `userId`、`projectId`、`sessionId`、`turnId` |
| 请求 | `apiKey`、`policy`、`userPrompt`、`cancelled` |
| 上下文 | `history`、`contextSummary`、`sessionReferenceContext`、`fileContext`、`imageContext`、`useRag` |
| 输出 | `StreamCallback`（文本增量、工具事件、计划、usage、completion、no_progress） |

执行前会完成：

1. 读取项目指令、工作区文件树；工作区存在时提示词场景切为 `coding`，否则为 `agent`。
2. `useRag` 为真时调用 `DocumentService.retrieveContext`，并把出处通过 `onSources` 推给前端。
3. 按 `policy` 生成工具列表（权限、能力门控、MCP 白名单）。
4. `PromptCatalog` 按场景取系统提示词正文。

---

## 3. 主循环

循环条件：`round < policy.reasoning().maxToolRounds()`。

每轮执行：

1. **取消检查**：客户端断开或用户停止时立即退出，不发起下一次模型调用。
2. **预算提示**：剩余轮次较少时注入收口提示，避免把轮次浪费在探索上。
3. **模型调用**：流式输出文本，同时收集 `tool_calls`；token 超预算则中断并映射 `TOKEN_BUDGET_EXCEEDED`。
4. **无工具调用**：视为“候选完成”，进入完成判定。
5. **有工具调用**：
   - 把 assistant 的 `tool_calls` 写回消息历史；
   - 逐个工具执行（同一轮可多个），结果作为 tool 消息回灌；
   - 写操作先走审批；被拒绝时记录拒绝原因并回灌模型；
   - 记录文件变化、写失败、计划更新、工具签名。
6. **无进展检测**：见第 5 节。

退出路径共有五类：模型自然停止、轮次耗尽、token 预算耗尽、无进展停止、取消。

---

## 4. 完成语义（核心）

Runtime 区分两套状态，禁止混用：

```text
RunState：QUEUED / RUNNING / WAITING_APPROVAL / SUCCEEDED / FAILED / CANCELLED
CompletionStatus：COMPLETED / UNVERIFIED / INCOMPLETE
```

| 条件 | Completion | 典型原因 |
|---|---|---|
| 无文件修改，无未完成计划，无失败 | `COMPLETED` | `NO_VERIFICATION_REQUIRED` |
| 文件已修改，但无诊断或 verify 证据 | `UNVERIFIED` | `VERIFICATION_MISSING` |
| 计划仍有 `pending/in_progress` | `INCOMPLETE` | `PLAN_INCOMPLETE` |
| 所需操作被审批拒绝 | `INCOMPLETE` | `APPROVAL_REJECTED` |
| 最后一次写操作失败且未恢复 | `INCOMPLETE` | `WRITE_TOOL_FAILED` |
| 达到最大工具轮次 | `INCOMPLETE` | `MAX_TOOL_ROUNDS` |
| 触发无进展停止 | `INCOMPLETE` | `REPEATED_TOOL_CALL` / `REPEATED_FAILURE` / `STAGNANT_ROUNDS` |
| 用户取消 / 客户端断开 | `INCOMPLETE` | `CANCELLED` |

补充规则：

- 自然结束时，只有模型通过 `update_plan` 显式提交的 `completed` 才保留；`pending/in_progress` 一律收口为 `aborted`。
- 取消路径也会发送 completion 与 `onComplete`，避免 Run 停在 `RUNNING`。
- 因为 P5-05/P5-06 未落地，当前不存在“文件修改后 `COMPLETED` 且已验证”的真实路径，验证覆盖率如实为 0。

---

## 5. 无进展检测

工具签名：`工具名 + 参数 JSON 规范化结果`（对象 key 排序，避免字段顺序绕过）。

| 停止原因 | 触发条件 | 配置 |
|---|---|---|
| `REPEATED_TOOL_CALL` | 连续相同工具签名达到阈值 | `agent.loop.no-progress.max-repeats`，默认 3 |
| `REPEATED_FAILURE` | 连续相同失败指纹达到阈值 | 同上 |
| `STAGNANT_ROUNDS` | 连续多轮无新成功调用、无文件变化、无计划变化 | `max-stagnant-rounds`，默认 4 |

- 0 表示关闭对应检测。
- 触发后走统一 `stopForNoProgress`：发送 `no_progress` 事件 → 收口计划 → `INCOMPLETE` → `done`。
- 属于安全收尾，不伪装成执行异常，也不把任务标为完成。

---

## 6. 策略与权限

`AgentPolicy` 包含两组正交配置：

| 维度 | 档位 | 影响 |
|---|---|---|
| 推理档位 | `FAST` / `STANDARD` / `DEEP` | 最大工具轮次、历史条数、RAG 历史数、字符预算 |
| 工作权限 | `MANUAL` / `AUTO` / `FULL` / `READ_ONLY` | 写操作与命令是否需要审批、风险命令的处理方式 |

`AgentPolicyResolver` 对模型、档位、权限做归一化：非法值回退默认，模型不在允许列表时回退默认模型。

---

## 7. Run 与事件

- `AgentRunService` 维护状态迁移，非法迁移直接拒绝。
- 事件类型包括：`PROMPT_LOADED`、文本与工具事件、`COMPLETION`、`NO_PROGRESS` 等；事件按 `seq` 递增，供断线重放。
- token 在终态只落库一次（`prompt/completion/total`），避免逐轮累加重复写。
- 服务启动时把遗留 `QUEUED/RUNNING` 标记为 `FAILED/PROCESS_RESTARTED`，避免“幽灵运行”。

---

## 8. 设计取舍

| 取舍 | 说明 |
|---|---|
| 保留 ReAct | 结构简单、可观测、可逐步增强；不引入多智能体或独立 Planner/Critic |
| 完成由运行时判定 | 模型只提供候选完成，避免“口头完成” |
| 停止原因显式化 | 轮次、预算、无进展、取消都有可读原因，便于前端与评测断言 |
| 计划只做收口不做验收 | `update_plan` 记录步骤状态；真正的验证依赖 P5-05/P5-06 |
| 工具串行 | 当前实现简单可控；并行留给 P5-08，先用数据证明收益 |

---

## 9. 现状边界

- 没有真实模型端到端评测；Runtime 评测使用脚本客户端，保证确定性。
- 诊断与 verify 未接入，文件修改只能到 `UNVERIFIED`。
- 计划没有验收条件字段，只有步骤状态。
- 跨实例审批唤醒与互斥未实现（桌面端单实例场景不受影响）。
- 工具仍串行执行，长任务时延优化未开始。

---

## 10. 面试要点

1. **为什么 `Run=SUCCEEDED` 还会 `INCOMPLETE`？** 执行链正常结束不等于任务达成；两者分离才能如实展示。
2. **如何判定完成？** 计划、审批、写失败、文件变化、验证证据五个信号，缺一不可。
3. **如何防止空转？** 三类无进展检测 + 轮次/token 预算，触发后安全停止并说明原因。
4. **为什么不用状态图？** 先证明现有循环的问题在哪，再决定是否换架构；目前问题在上下文和验证，不在编排形式。
