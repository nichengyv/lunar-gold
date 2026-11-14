# 10 质量与评测（部分完成）

> 更新日期：2026-11-13
> 状态：单元/集成测试、组件评测、真实 Runtime 评测、双基线与双门禁已落地
> 关联：[总架构](README.md)、[Agent Runtime](01-agent-runtime.md)、[上下文与提示词](04-context-rag.md)

---

## 1. 三层验证

```text
第一层：单元与集成测试
  Spring Boot Test / Mockito / 临时工作区
  验证工具、沙箱、审批、状态机、控制器契约

第二层：组件评测（EvalRunner / EvalGate）
  脚本化模型输出，验证提示词、工具选择、参数、边界行为
  数据集：eval/datasets/  基线：eval/baselines/baseline.json

第三层：真实 Runtime 评测（RuntimeEvalRunner / RuntimeEvalGate）
  ScriptedAgentClient 驱动真实 ReActAgentService.execute
  贯通工具注册、审批、文件系统、Run/Event、完成判定
  数据集：eval/runtime-datasets/  基线：eval/baselines/runtime-baseline.json
```

关键原则：**组件通过率不能冒充真实任务成功率**。

---

## 2. Runtime 评测做了什么

5 个 JSONL 文件，共 15 条用例：

| 文件 | 覆盖 |
|---|---|
| `runtime-core.jsonl` | 写文件后未验证、计划未完成收口 |
| `runtime-approval.jsonl` | 批准后真实写入、拒绝后无副作用 |
| `runtime-recovery.jsonl` | 越界写入失败后恢复、读取缺失文件后降级 |
| `runtime-loop.jsonl` | 重复调用、连续相同失败、连续无推进、轮次耗尽 |
| `runtime-messages.jsonl` | 多工具消息回灌、取消终态、A/B 确定性、纯文本完成 |

断言维度：

- 文件副作用（内容、是否存在、是否被拒）；
- Run 状态与事件 seq 连续性；
- `Completion` 状态与原因；
- 计划最终状态（`aborted` 收口）；
- 模型调用数、工具轮次、尝试工具调用数；
- token 总量与 usage 一致性；
- system prompt 场景（coding）；
- 同一脚本两次运行结果一致。

---

## 3. 指标口径

| 指标 | 含义 |
|---|---|
| `case_pass_rate` | 脚本断言通过率 |
| `task_success_rate` | 最终交付状态为 `COMPLETED` 的比例 |
| `verification_coverage` | 文件变化且具备验证证据的比例 |
| `no_progress_stops` | 无进展停止次数 |
| `repeated_call_rate` | 重复工具调用占比 |
| 轮次 / 工具调用 / token / p95 | 成本与性能 |

当前基线：`case_pass_rate=100%`、`task_success_rate=20%`、`verification_coverage=0%`。

解读方式：

- 20% 是因为大量用例**刻意**覆盖未验证、拒绝、取消与无进展路径，不是产品真实模型成功率；
- 0% 是因为 P5-05/P5-06 尚未提供诊断与 verify 证据，文件修改必须保持“未验证”。

---

## 4. 门禁

`RuntimeEvalGate` 复用 `EvalGate` 的比较逻辑：

| 检查 | 阈值 |
|---|---|
| 用例状态回退 | 不允许 |
| 通过率下降 | 最多 2 个百分点 |
| 平均轮次 / token 上升 | 10% |
| p95 上升 | 20% + 25ms 绝对余量 |
| 任务完成率 / 验证覆盖率下降 | 最多 2 个百分点 |

更新基线的前提：**所有用例通过且总数大于 0**，防止把失败结果写成基线。

---

## 5. CI 与命令

```bash
# 后端全量
mvn --batch-mode --no-transfer-progress verify

# 组件 + Runtime 双门禁
mvn --batch-mode --no-transfer-progress test "-Dtest=EvalGate,RuntimeEvalGate"

# 脚本方式（可选 -UpdateBaseline）
powershell -ExecutionPolicy Bypass -File scripts\run-eval.ps1
```

CI 流程：

```text
checkout → JDK 21 → mvn verify
  → 双评测门禁
  → 归档 eval/reports/*.json 与 eval/runtime-reports/*.json（14 天）
frontend job → npm ci → npm run build（vue-tsc + vite）
```

真实模型评测不进 CI：成本高、结果抖动，只保留 BYOK 本地或定时抽样。

---

## 6. 提示词质量

- 提示词版本化 + SHA-256 + 必备段落校验，误改会在启动或测试时失败。
- 场景区分：`agent`（通用问答）与 `coding`（编程任务）。
- `coding` 明确“停止调用工具只是候选完成”“未验证要写未验证”“不得编造测试结论”。
- 缺失内容只记录缺口，不伪装为已覆盖。

---

## 7. 现状边界

- 没有真实模型的自动化成功率评测，只有确定性脚本。
- 没有前端自动化测试（Vitest / Playwright）。
- 没有真实断网 10 秒等人工异常复核归档。
- 评测数据集规模小，覆盖的是关键路径而非长尾。
- p95 在本地易受毫秒噪声影响，已加绝对余量，但仍非稳定性能基准。

---

## 8. 面试要点

1. **为什么做两层评测？** 组件评测验证局部行为，Runtime 评测验证真实执行链；二者不可互相替代。
2. **如何避免“自测自嗨”？** 断言真实文件副作用、事件终态与完成状态，并明确脚本指标不等于真实模型成功率。
3. **门禁怎么防回退？** 通过率、轮次、token、p95、完成率、验证覆盖率多维比较，失败即让 CI 失败。
4. **为什么真实模型不进 CI？** 成本与抖动；用确定性脚本守底线，用抽样看趋势。
