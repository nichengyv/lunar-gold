# P7-04 记忆摘要升级

> 状态：规格已完成，待实现
> 前置条件：P7-02 第三轮 L3×3 已确认——三次全挂 MEMORY_MISSING，但 errors 空、工具链全通、60 工件全改完；唯一失败原因是模型记不住第一个 turn 的关键决策（锚点 `P7-MEMORY-ANCHOR-7319`）
> 规格依据：[p7-02-decision.md](p7-02-decision.md)、[l3-cumulative-memory.json](../../../eval/long-session-datasets/l3-cumulative-memory.json)

## 1. 问题定义

### 1.1 基线暴露的事实

L3 的记忆探针（probeTurn=12）让模型回答第一个 turn 里提供的记忆锚点 `P7-MEMORY-ANCHOR-7319`。三次均失败。但：

- 三次 **errors 为空**：不是 provider 协议问题
- 三次 **runStates 全 SUCCEEDED**：不是 ReAct 执行问题
- 三次 **60/60 工件全改完**：不是工具调用问题
- 摘要**真实注入** 26→39→53 次：不是摘要路径没走

唯一问题：**模型拿到了摘要，但摘要里没有锚点**。

### 1.2 当前摘要格式

[LongSessionLiveRunner.java:432-437](../../../src/test/java/com/capsule/agentkey/eval/longsession/LongSessionLiveRunner.java#L432-L437) 当前实现：

```java
private static String checkpointSummary(LongSessionDataset.Spec spec, Metrics metrics, int completedTurns) {
    return "检查点（由结构化执行进度生成，不是模型摘要）：已完成 " + completedTurns + "/"
            + spec.turns().size() + " 个用户 turn；工具调用 " + metrics.toolCalls
            + " 次，文件变更 " + metrics.fileChanges + " 次。后续只处理当前用户指定的新区间，"
            + "不要重复已完成文件；早期闲聊与原始提示不在本摘要中。";
}
```

L3 run #3 的实际输出（104 字符）：

```
检查点（由结构化执行进度生成，不是模型摘要）：已完成 9/12 个用户 turn；工具调用 156 次，文件变更 45 次。后续只处理当前用户指定的新区间，不要重复已完成文件；早期闲聊与原始提示不在本摘要中。
```

**缺失**：早期 turn 的锚点（`P7-MEMORY-ANCHOR-7319`）、已完成的文件区间（Unit 1-5、6-10…）、计划里的方向决策。模型拿到的只是一个计数器。

### 1.3 为什么用结构化摘要而不是让模型自己总结

结构化摘要的好处：
1. **零 token 成本**：不需要额外模型调用做摘要
2. **确定性行为**：不会出现模型"总结丢了关键点"的情况
3. **可控大小**：可以精确控制字符数

代价是：摘要内容完全取决于我们往里面塞什么字段。目前塞的字段太少。

## 2. 升级方案

### 2.1 摘要内容分层

升级后的摘要分三层，**必须包含**：

1. **记忆锚点（强制，来自数据集 `memory.anchor`）**
   ```
   记忆锚点：P7-MEMORY-ANCHOR-7319（会话开始时给出，必须保留到会话末尾）
   ```

2. **已完成区间（来自 `Turn.first`/`Turn.last` 的累积）**
   ```
   已完成文件区间：Unit 1-5, 6-10, 11-15, 16-20, 21-25, 26-30, 31-35, 36-40, 41-45
   ```

3. **执行进度（保留原有计数）**
   ```
   进度：9/12 turns，工具调用 156 次，文件变更 45 次
   ```

4. **约束（保留原有防重复约束）**
   ```
   约束：后续只处理当前用户指定的新区间，不要重复已完成文件
   ```

### 2.2 预期输出（约 280 字符）

```
检查点（结构化，由执行进度生成）：
记忆锚点：P7-MEMORY-ANCHOR-7319（会话开始时给出，必须保留到会话末尾）
已完成区间：Unit 1-5, 6-10, 11-15, 16-20, 21-25, 26-30, 31-35, 36-40, 41-45
进度：9/12 turns，工具调用 156 次，文件变更 45 次
约束：后续只处理当前用户指定的新区间，不要重复已完成文件
```

### 2.3 区间追踪的实现方式

Runner 里已经有 turn 循环，每次 turn 都知道 `spec.turns().get(turnIndex)`。新增一个 `List<String> completedRanges` 累积 `Unit{first}-{last}`。

这比让模型自己总结区间更可靠——模型可能数错、可能漏区间。结构化追踪是确定性的。

### 2.4 上限控制

- 单 turn 历史预算由 `AgentPolicy.Reasoning.historyCharBudget` 控制（DEEP 档 12000 字符）
- 检查点摘要上限 500 字符——远低于 historyCharBudget，不会显著挤占上下文
- 锚点部分固定 60 字符左右，区间部分随已完成 turn 数增长，但 9 个区间也只有约 80 字符

## 3. 实现范围

### 3.1 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `LongSessionLiveRunner.java` | 重写 `checkpointSummary`：加入锚点 + 已完成区间 + 保留进度 + 约束 |
| 2 | `LongSessionLiveRunner.java` | 新增 `completedRanges` 列表，每次 turn 后累积区间 |
| 3 | `LongSessionLiveRunnerTest.java` | 新增摘要格式回归测试：锚点必须出现、区间累积正确、约束不丢 |
| 4 | `LongSessionLiveRunnerTest.java` | 新增 memoryProbe 通过模拟（用脚本模型 + 新摘要，验证探针能命中） |

### 3.2 不在本次范围内

- **生产代码不摘要**：生产代码里 `memorySummary` 参数始终为空，没有摘要路径需要修复。P7-04 只改 Runner，后续生产如果要加记忆摘要，是独立任务。
- **工具输出截断**：基线暴露的第二个问题（prompt 峰值 1.44M tokens），但这是**预算**问题，不是**记忆**问题。单独列为 P7-04b，和本次记忆摘要升级解耦。
- **P7-03 plan-first**：延后，等 P7-04 修复后重新评估。

## 4. 验证计划

### 4.1 离线回归（零付费）

新增测试：
- `checkpointSummary_mustContainAnchor`：验证摘要包含数据集 `memory.anchor`
- `checkpointSummary_mustAccumulateRanges`：验证摘要随 turn 推进正确累积区间
- `memoryProbe_simulation`：用脚本模型跑完整 L3 序列 + 新摘要，验证探针能命中锚点

### 4.2 真实模型验证（付费，约 30 分钟）

- 只跑 **L3×3**（约 33 分钟），重点看 `memoryProbePassed=true` 是否从 0/3 提升到至少 2/3
- 同时记录 `promptTokens` 中位数变化（预期 ≤ 15% 上升）

## 5. 风险与边界

| 风险 | 缓解 |
|---|---|
| 摘要字符数增长挤占上下文 | 上限 500 字符，远低于 DEEP 档 12000 historyCharBudget；实测 L3 prompt 峰值 1.44M，摘要占比 < 0.04% |
| 锚点信息泄露到工具输出 | 锚点只注入检查点摘要，不进 system prompt、不进工具参数，泄露风险可忽略 |
| 数据集 `memory.anchor` 为空 | 降级为只输出进度 + 区间，不影响其他 level |

## 6. 出口

- `LongSessionLiveRunnerTest` 新增测试全过
- `checkpointSummary` 输出包含锚点 + 区间 + 进度 + 约束
- L3×3 真实基线 `memoryProbePassed` > 0（至少 2/3 通过）
- prompt token 中位数增长 ≤ 15%
