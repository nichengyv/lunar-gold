# P7-02 长会话真实模型基线

> 状态：两轮真实基线均已执行且结论无效；三处缺陷已修复并各有回归测试，第三轮重跑待授权
> 关联：UPDATE_PLAN 13.4；P7-06 出口 4

## 1. 目标与边界

用固定 L1/L2/L3 数据集、真实模型和生产 ReAct 循环取得可复核数字，按预注册规则决定：

- 失败中计划漂移 / 无进展 / 步骤遗漏是否 ≥ 50% → 才评估 P7-03。
- 失败是否主要来自上下文截断、记忆缺失、历史重复 → 才评估 P7-04。
- 均未命中 → Phase 7 收口，不为了架构名词继续改造。

本轮只实现 Runner、离线测试与报告结构。真实 L1×1、L1～L3×3 均需单独授权，不设 Key 时必须跳过。

## 2. 不伪造的验证语义

- `LongSessionOfflineRunner` 只验证数据集，不能当任务成功率。
- `RuntimeEvalRunner` 使用脚本模型，不能当真实模型基线。
- 新 Runner 使用 `SpringAiAgentClient` + 生产 `ReActAgentService`、真实工作区工具与真实 `AgentRunService` 状态迁移；只 mock 外部仓库、项目所有权等边框。
- 模型调用次数由 `AgentClient` 装饰器在实际 `streamChat` 入口计数，不用工具轮次或 usage 次数冒充。
- token 只取 provider usage；同时报告 `usageCalls/modelCalls` 覆盖率，缺 usage 时不按字符数估算。
- 成功必须同时满足：工件断言、所需 execute turn、计划/恢复/审批/scope/摘要/记忆等数据集 coverage。单看最终文本或 Run 终态不算成功。

## 3. 数据集执行

| 级别 | 下限 | 必检语义 |
|---|---:|---|
| L1 | 20 次模型调用 | 单 turn；过期路径失败并恢复；至少 2 次 update_plan；12 个策略文件 + Catalog 工件 |
| L2 | 40 次模型调用 | 多 turn 中途 scope 切换；真实 scope 拒绝；工具审批；统一变更由评测用户在接受工件后确认；跨模块 + Registry 工件 |
| L3 | 100 次模型调用 | ≥10 turn 累积历史；检查点摘要注入；最后 turn 探测首轮记忆锚点；≥50 个独立工件 |

每个 turn 都调用一次生产 `execute`。历史按 USER/ASSISTANT 消息累积，由生产历史裁剪逻辑决定保留范围。

L3 检查点摘要由 Runner 根据结构化进度生成，只包含已完成 turn 数、工具统计和下一步约束，不复制原始首轮 prompt，也不额外调用模型做摘要；报告记录摘要注入次数与内容长度。这样记忆探针检验的是「原始历史 + 检查点摘要」的真实表现，而不是把答案偷偷塞回摘要。

## 4. 执行配置

环境变量：

- `LUNARGOLD_LIVE_API_KEY`：必填；缺省时 JUnit 跳过，不写成功报告。
- `LUNARGOLD_LIVE_BASE_URL`：默认 `https://api.deepseek.com`。
- `LUNARGOLD_LIVE_MODEL`：默认 `deepseek-flash`。
- `P7_02_LEVELS`：默认 `L1`，可设 `L1,L2,L3`。
- `P7_02_REPEATS`：默认 `1`；正式基线显式设 `3`。
- `P7_02_ASK_USER`：默认 `off`；`on` 时注册 P7-06 工具并由评测用户选择首个推荐项/首项，报告询问次数，用于 P7-06 出口 4 A/B。
- `P7_02_MAX_SECONDS_PER_TURN`：默认 `300` 的单 turn 安全预算，防止失控消耗。

固定：同一模型、同一提示词版本、同一工具集、同一数据顺序；repeat 逐轮交替 L1/L2/L3。报告保存模型、base URL、配置与数据 id，不保存 API Key。

## 5. 指标与报告

每个 run 记录：

- id / level / repeat / success / failureAttribution / 失败细节。
- 实际模型请求数、usage 回报数、execute turns、工具调用/失败/恢复/重复次数。
- prompt / completion / total tokens、单轮上下文峰值 prompt tokens。
- 端到端耗时、首个文本 chunk 时延（无文本时为 null）。
- update_plan 次数、最终计划完成率、无进展次数与原因。
- 审批数、scope 变化/拒绝数、评测用户审查接受数。
- 摘要注入次数、记忆探针命中情况、ask_user_question 次数。

输出：`eval/runtime-reports/long-session-<yyyyMMdd-HHmmss-SSS>.json`。

聚合保留每个原始 run，并给出按 level 的成功率、中位数和失败归因分布。只有 `P7_02_LEVELS=L1,L2,L3` 且 `P7_02_REPEATS>=3` 时，`decision.eligible=true`；L1×1 只标为 smoke，不触发 Phase 7 架构结论。

## 6. 失败归因优先级

1. provider / 执行异常 → `MODEL_OR_PROVIDER`
2. 无进展停止 → `NO_PROGRESS`
3. 达到最大工具轮次 → `PLAN_DRIFT`
4. 记忆探针失败 → `MEMORY_MISSING`
5. 重复调用显著且工件未完成 → `HISTORY_REPETITION`
6. scope/审批覆盖缺失 → `APPROVAL_OR_SCOPE`
7. 计划未完成 → `STEP_OMISSION`
8. 工件缺失且存在工具恢复缺口 → `TOOL_RECOVERY`
9. 其他工件缺失 → `STEP_OMISSION`

`CONTEXT_TRUNCATION` 不凭猜测判定；只有 provider 明确返回上下文长度错误时归入此类。归因规则与原始指标一起落盘，便于人工复核。

## 7. 执行门禁

1. 离线：数据集测试 + Runner 报告/归因单元测试 + 目标测试全绿。
2. 冒烟（2026-11-19 已授权并完成）：L1×1 只验证链路和报告，不作架构结论。

```powershell
$env:LUNARGOLD_LIVE_API_KEY='<你的 DeepSeek Key>'
$env:LUNARGOLD_LIVE_MODEL='deepseek-flash'
$env:P7_02_LEVELS='L1'
$env:P7_02_REPEATS='1'
$env:P7_02_ASK_USER='off'
mvn -o "-Dtest=LongSessionLiveRunner" test
```

本次结果：`eval/runtime-reports/long-session-20261119-202115-722.json`。37 次模型调用，usage 覆盖 37/37，189,355 total tokens，249,692ms；13/13 工件、1 次工具失败恢复、6 次计划更新、计划完成率 100%，无 coverage 缺口。`completion=UNVERIFIED:VERIFY_NOT_CONFIGURED`，因为 fixture 无构建/测试配置；成功仅表示工件、coverage 与评测用户审查通过。`decision.eligible=false`，不触发规则 2/3。

3. 正式（2026-11-19 已授权执行）：报告 `eval/runtime-reports/long-session-20261119-213016-388.json`，9 个 run 全部完成，provider 失败率 0。结果：L1 2/3、L2 2/3、L3 0/3；L3 三次均 MEMORY_MISSING/STEP_OMISSION，且 3 次 L3 全部出现工具分发错误。

**首轮结论无效，原因是有两处真实缺陷（均已修复）**

1. **评测工具缺陷**：`LongSessionDataset.render(Turn)` 误调了三参 `render(template, module, index)`，只替换 `{index*}`/`{module}`，从不替换 `{first}`/`{last}`；真正替换区间的二参重载成了死代码。L2/L3 的真实 prompt 里因此带着字面 `Unit{first}`，模型只能猜测区间并反复追问，计划类失败被系统性放大。
2. **产品缺陷**：`SpringAiAgentClient.streamChat` 在 `memorySummary` 非空时改走 `ChatClient` + Advisor。ChatClient 自带工具执行循环，绕过 `ToolRegistry.dispatch` 直接调用 `BaseTool.call`，把「工具执行必须经由 ToolRegistry.dispatch」的兜底错误当作工具结果喂回模型。证据：该错误只出现在 3 个 L3 run（唯一注入摘要的 level），L1/L2 一次都没有。

**修复内容**

- `LongSessionDataset.render(Turn)` 改为调用二参重载，并新增 `turnPromptSubstitutesFirstAndLast` 回归测试（L2 首/末 turn 断言区间已替换且不含占位符）。
- `SpringAiAgentClient.streamChat` 删除 ChatClient 分支，统一走 `chatModel.stream`；摘要用 `Prompt.augmentSystemMessage` 追加到 system 文本尾部，保持与 Advisor 相同的追加语义，不再改变工具执行路径。
- 目标测试：`LongSessionDatasetTest` 13 项 + `LongSessionLiveRunnerTest` 5 项，共 18 项通过；主代码编译通过。

**因此**：首轮 `decision.rule2PlanFirst=true` 不能作为立项依据——3 个计划类失败至少部分由占位符缺陷造成；L3 的记忆探针失败虽与预期方向一致，但同样混有工具错误与 prompt 缺陷。必须在修复后重跑 L1/L2/L3×3 才能形成架构结论。

**第二轮（2026-11-20，L2+L3×3，复用首轮 L1）**：报告 `long-session-20261120-124420-563.json`。L2 1/3、L3 0/3。占位符问题与「ToolRegistry.dispatch」兜底错误均已消失，但 3 个 L3 全部死于 provider 400「insufficient tool messages following tool_calls message」。

**第三处缺陷（产品，协议级）**：`ReActAgentService` 在一次响应并行发起多个写工具时，诊断 user 消息在第一个写工具后就插入消息列表，把同组剩余 tool 响应隔到 user 之后，provider 因此拒绝整段历史。已用 `ReActToolCallProtocolTest` 零成本复现（`expected=[call_0_0, call_0_1], answered=[call_0_0], nextRole=user`），修复为诊断消息先暂存、本组 tool 响应全部写完后统一追加。相关 17 项测试 + 后端全量 398 tests 通过。

**仍未验证**：第二轮 L3 三次分别只走到第 8/2/5 个 turn，`summaryInjectedCalls=0`，记忆摘要注入路径至今没有经过真实模型检验。第三轮重跑前不得声称该路径已修复。
4. P7-06 出口 4：同数据集分别跑 `P7_02_ASK_USER=off/on`，比较成功率、额外询问、token 与耗时；无足够样本时不宣称收益。

## 8. 清理与安全

- 每次 run 使用独立临时工作区，finally 删除；失败也不保留用户文件。
- 不读取 `.secrets`，不把 Key 写入命令行参数、报告或仓库。
- Runner 默认 skip，禁止在全量测试套件中自动消耗费用。
