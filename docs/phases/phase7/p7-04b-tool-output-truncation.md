# P7-04b 工具输出截断 + ReAct turn 内历史预算

> 状态：规格已完成，待实现
> 前置条件：P7-04 记忆摘要升级已完成，L3 memoryProbe 0/3 → 3/3，成功率 0/3 → 2/3
> 规格依据：P7-04 真实模型基线 L3 run #3 promptTokens 1.05M（217 次调用 × 平均 4,839 tokens/次）

## 1. 问题定义

### 1.1 当前截断机制

| 截断点 | 当前值 | 作用范围 | 实际是否生效 |
|---|---|---|---|
| `TOOL_CONTENT_LIMIT` | **固定 2000 字符** | 每次工具结果进 ReAct messages 前 | ✅ 对所有工具生效 |
| `truncateForContext()` | 头部+尾部各留一半，超 `toolSpillChars` 落文件 | 单次工具结果 | ✅ 生效 |
| Runner history 双重上限 | 按 `AgentPolicy.Reasoning.historyCount` + `historyCharBudget` | 多 turn 累积 history → `buildMessages` | ✅ 生效（DEEP 档 18 条 + 12000 字符） |
| **ReAct turn 内 messages** | **无** | 单次 `execute()` 内部累积的 tool 结果 + assistant tool_calls | ❌ **完全缺失** |

### 1.2 缺口：ReAct turn 内无预算

一个 `execute()` 调用内，ReAct 循环不断 append：
- assistant（带 `tool_calls`）
- tool 结果（截断到 2000 字符）

这些 messages **不走 Runner 层的 history 双重上限**，每次 `streamChat` 时**全量传给模型**。

影响：
- L3 每个 turn 平均 15-20 次循环 → messages 累积 30-40 条（assistant + tool 各半）
- 累积效应让后期每次 `streamChat` 的 prompt token 数高于前期
- 所有档位都一样截断，FAST 档（短任务）不需要 2000 字符

### 1.3 为什么现在才做

P7-02 基线时 L3 的瓶颈是**记忆缺失**（memoryProbe 0/3），不是 token 成本。P7-04 记忆摘要修好后，成功率从 0/3 升到 2/3，token 成本成为下一阶段需要优化的点。

## 2. 修复方案

### 2.1 第一部分：工具输出上限分档

把固定 `TOOL_CONTENT_LIMIT = 2000` 改成按推理档位动态：

| 档位 | 单工具结果上限 | 理由 |
|---|---|---|
| FAST | 500 字符 | 短任务 1-2 步，工具结果通常只有单行 |
| STANDARD | 1000 字符 | 中等任务 3-5 步，可能有多行输出但仍可控 |
| DEEP | 2000 字符（保持不变） | 长任务，`search_files` 等工具可能返回较多路径 |

`AgentPolicy.Reasoning` 新增 `toolContentLimit()` 方法返回对应字符数。

### 2.2 第二部分：ReAct turn 内历史预算

每次 `streamChat` 前，对 ReAct 循环内部累积的 messages 做预算截断：

1. **保留 system prompt**（永远在 messages[0]）
2. **保留 Runner 传入的 history**（已在 `buildMessages` 阶段被双重上限裁剪，最多 18 条）
3. **保留本轮 user prompt**（单独位置，最后几条）
4. **截断 turn 内累积的 assistant/tool 循环**：
   - 保留最近 **N 个循环**（默认 3，对应 3×2=6 条 assistant+tool）
   - 更老的循环：只保留一个摘要 `"[省略 {K} 次工具调用，结果已纳入当前执行]"`

截断时机：在每次 `streamChat()` 调用前，对即将传入的 messages 做一次预处理。

### 2.3 示例

假设 turn 内已经跑了 6 次循环（12 条 assistant+tool 消息），预算设为 3：

```
messages:
  [0] system                     ← 保留
  [1-10] Runner history (10条)   ← 已裁剪过，保留
  [11] user prompt               ← 保留
  [12] assistant (tool_calls A)  ← 保留（最近3次循环）
  [13] tool (A的结果)            ← 保留
  [14] assistant (tool_calls B)  ← 保留
  [15] tool (B的结果)            ← 保留
  [16] assistant (tool_calls C)  ← 保留
  [17] tool (C的结果)            ← 保留
  ── 以上 保留，以下 截断 ──
  [18] assistant (tool_calls D)  ← 截断
  [19] tool (D的结果)            ← 截断
  [20] assistant (tool_calls E)  ← 截断
  [21] tool (E的结果)            ← 截断
  [22] assistant (tool_calls F)  ← 截断
  [23] tool (F的结果)            ← 截断
```

但截断会让模型**不知道自己之前调过 D、E、F**。这会导致重复调用或逻辑断裂吗？

实际上：
- D、E、F 的执行结果已经被纳入了 turn 内的进度（比如文件已经改好了）
- 模型在后续 turn 里通过 `read_file` 或 `search_files` 能看到真实结果
- 截断只是不让模型在对话历史里反复看同一份工具结果

所以截断是安全的。

### 2.4 不做的事

- **不引入外部记忆**：当前本地优先约束下，不引入向量库或语义检索
- **不改 Runner 层 history 截断**：已有双重上限（条数+字符），工作正常
- **不截断最终 answer**：turn 结束时的 ASSISTANT answer 不进 ReAct 内部 messages，不受影响
- **不改生产代码的摘要路径**：P7-04 只改 Runner（评测），P7-04b 这次改**生产代码**（ReActAgentService），因为工具截断和 ReAct 循环是生产路径

## 3. 实现范围

### 3.1 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `AgentPolicy.Reasoning` | 新增 `toolContentLimit()` 方法 |
| 2 | `ReActAgentService` | `TOOL_CONTENT_LIMIT` 从 `static final` 改成按档位动态计算 |
| 3 | `ReActAgentService.truncateForContext()` | 接收 Reasoning 参数或直接读取 policy |
| 4 | `ReActAgentService` 主循环 | 每次 `streamChat` 前对 turn 内累积 messages 做预算截断 |
| 5 | `ReActAgentServiceTest` | 新增截断回归测试 |

### 3.2 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 模型不知道早期工具结果 → 重复调用 | 轻微增加工具次数 | 预算设 3 次循环，覆盖"当前正在执行"的上下文；更早的结果模型通过文件状态能看到 |
| 截断摘要被模型误解 | 低风险 | 明确标注为"省略"，不让模型误以为"还没执行" |
| 截断逻辑 bug → messages 格式不合法 | 高风险 | 离线脚本模型 + 真实模型 L3×1 验证 |

## 4. 验证计划

### 4.1 离线回归（零付费）

新增测试：
- `toolContentLimit_byReasoning`：FAST=500, STANDARD=1000, DEEP=2000
- `turnHistoryBudget_respectRecentCalls`：6 次循环后 streamChat 只保留最近 3 次
- `turnHistoryBudget_keepSystemAndHistory`：截断不误删 system 和 Runner history

### 4.2 真实模型验证（付费，约 30 分钟）

L3×3 重跑，对比：
- **promptTokens 中位数**：预期从 1.05M 降到 0.7-0.8M（-25% 到 -33%）
- **成功率**：保持 2/3 不下降
- **memoryProbePassed**：保持 3/3

## 5. 出口

- `ReActAgentServiceTest` 新增测试全过
- 全量回归 BUILD SUCCESS
- L3×3 真实基线：promptTokens 中位数下降 ≥ 20%，成功率 ≥ 2/3，memoryProbe=3/3
