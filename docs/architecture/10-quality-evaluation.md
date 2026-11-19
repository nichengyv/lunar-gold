# 10 质量与评测（部分完成）

> 更新日期：2026-11-16
> 状态：单元/集成测试、组件评测、真实 Runtime 评测、RAG 检索评测、代码索引 A/B、多基线与门禁已落地
> 关联：[总架构](README.md)、[Agent Runtime](01-agent-runtime.md)、[上下文与提示词](04-context-rag.md)

---

## 1. 五层验证

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

第四层：RAG 检索评测（RagRetrievalEvalRunner / RagEvalGateTest）
  内嵌 PG + Flyway 写入标注语料，对比 vector / lexical / hybrid
  数据集：eval/rag-datasets/  基线：eval/baselines/rag-baseline.json

第五层：代码索引评测（IndexBenefitEvalRunner / IndexAbEvalRunner）
  阶段 A 无模型确定性测量；阶段 B 真实模型 ON/OFF A/B
  报告：eval/index-reports/（不进基线，计时只记录不设门槛）
```

关键原则：**组件通过率不能冒充真实任务成功率**；检索指标也不能代替端到端回答质量。

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

## 3. RAG 检索评测做了什么

`eval/rag-datasets/`：3 篇文档 / 24 个切片 / 22 条查询，五个类别 `semantic`（同义改写）、`exact`（编号 / 函数名）、`mixed`、`cross-lingual`、`noise`（应与语料无关）。`exact` 查询的编号与术语刻意不出现在 `concepts` 中，确保向量路径真实漏召回。

评测在真实内嵌 PostgreSQL + pgvector 上执行（`DESKTOP_PG_RUNTIME` 门禁），Flyway 跑真实迁移。为让小数据排序确定，评测前删除 HNSW 索引走精确扫描，并使用 `SingleConnectionDataSource` 避免把连接建立耗时算进检索。

| 指标 | 含义 |
|---|---|
| `Recall@3` / `Recall@5` | top-k 命中的标注相关切片 / 标注相关切片总数 |
| `MRR@5` | 首个相关切片排名的倒数均值 |
| `nDCG@5` | 二值相关性下的归一化折损累计增益 |
| `doc_hit_rate` | 至少命中一篇标注相关文档的查询占比 |
| `context_tokens` | top-5 正文估算 token（CJK 1 字≈1 token，非 CJK 4 字符≈1 token） |
| `p95_latency_ms` | 每条查询重复 3 次的检索耗时 p95，只含召回与融合 |

实测（`embedding=simulated`，19 条正向查询）：Recall@5 vector 68.42% / lexical 31.58% / hybrid 100%；`exact` 类 vector 0% → hybrid 100%；noise 3/3 全部拒绝；`context_tokens` 24.89 → 36.32；p95 1.63ms → 2.70ms。据此判定**不启用模型 reranker**。

模拟向量由 concept 哈希生成，只验证“语义信号存在时融合是否优于单路”，**不测量 bge-m3**；设置 `RAG_EVAL_EMBEDDING_KEY` 可切真实模型模式复跑。

### 3.1 难例集与真实模型 reranker 对照（P5-07-3）

主集 hybrid 已达 100%，测不出精排增量，因此新增独立难例集（不改主集与主基线）：

- **数据集**：`eval/rag-datasets/hard-chunks.jsonl` 160 个干扰切片 + `hard-queries.jsonl` 12 条 hard / 2 条 noise；hard 查询唯一正确切片、与目标 `concepts` 无交集。
- **口径**：两路各取 20 → `RrfRankFusion(60)` 融合 20；`hybrid` 截断 5，`hybrid+rerank` 交真实 `BAAI/bge-reranker-v2-m3` 取 5；embedding 为真实 `BAAI/bge-m3`（报告 `embedding.mode=model`）。
- **新增指标**：`top1_hit_rate`（top-1 是否为标注切片）、`rerank_p95_ms` 与 `total_p95_ms` 分列、`cost`（`embedding_query_tokens` 实际值或标注估算、`rerank_tokens` 实际 usage、`rerank_calls`；配置单价时才输出货币成本）、`calibration`（逐条目标两路排名与是否进池）、`decision`（跑数据前写定的 Q1～Q8 台账 + 三选一结论）。
- **数据有效性**：D1 规模、D2 唯一标注与 concept 交集、D3 ≥80% hard 查询目标在两路排名 >5、D4 目标全部进入候选池；D3/D4 不满足时结论只能是“数据集不合格”，不得下调阈值。
- **实测**：D3 83.33%、D4 0；top-1 8.33% → 16.67%、Recall@5 16.67% → 41.67%、nDCG@5 13.59% → 31.35%；rerank p95 482.81ms、总 p95 508.06ms；13120 rerank tokens / 12 次调用。Q1（≥ +10pp）与 Q6（rerank p95 ≤300ms）不通过 → 保持不启用。
- **门槛**：`RagRerankEvalGateTest` 只断言数据有效性、报告结构与 `hybrid` 基线回退，不做“reranker 必须提升”的硬断言（真实模型付费且波动）；`scripts/run-rag-rerank-eval.ps1` 在缺 Key 时打印缺口并以非 0 退出。

### 3.2 代码索引 A/B（P5-04，2026-11-16）

目的：证明 repo map + 符号索引对真实智能体有收益，而不是只凭直觉认为“给结构总比不给好”。

- **阶段 A（无模型）**：800 文件合成工作区，cold p95 271ms、warm 65ms、incremental 73ms、复用率 100%、符号 recall 90.3% / precision 100%、repo map 5,977/6,000 字符（已截断）。`IndexBenefitEvalTest` 断言确定性、recall ≥ 0.8、增量复用与报告结构，**计时指标只记录、不设 CI 门槛**。
- **阶段 B（真实模型）**：37 文件 navigation fixture，12 条 locate + 4 条 edit（含同名/近名干扰），每用例 3 次重复，ON/OFF 交替；`deepseek-chat`，96 run 零调用失败。
- **实测**：成功率 100% vs 62.5%；locate 准确率 100% vs 72.2%；edit 成功率 100% vs 33.3%；定位工具调用中位数 0 vs 14；prompt tokens 中位数 4,126 vs 25,072；耗时中位数 1.76s vs 10.17s；无进展停止均为 0。
- **结论**：B1～B5 全过，**保持 `agent.repo-map.enabled=true` 默认开启**；持久缓存按 cold 271ms 判为不建。
- **门槛**：`IndexAbEvalTest` 只断言数据有效性（D1～D6）、重复次数完整、报告落盘，**不对“必须优于”做硬断言**；缺 `P5_04_MODEL_KEY` 时跳过，且不产出任何收益结论。`scripts/run-index-eval.ps1 -RealModel` 缺 Key 时 `exit 2`。
- **B4 口径修订（透明记录）**：原“重复调用率 ≤ OFF + 2pp”在小调用基数下被单次噪声击穿，三次迭代后定为“每 run 重复次数中位数 ≤ OFF + max(1, 2%×runs)”，并同时要求无进展停止不增加；原始报告全部保留未删改。

---

## 4. 指标口径

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

## 5. 门禁

`RuntimeEvalGate` 复用 `EvalGate` 的比较逻辑：

| 检查 | 阈值 |
|---|---|
| 用例状态回退 | 不允许 |
| 通过率下降 | 最多 2 个百分点 |
| 平均轮次 / token 上升 | 10% |
| p95 上升 | 20% + 25ms 绝对余量 |
| 任务完成率 / 验证覆盖率下降 | 最多 2 个百分点 |

更新基线的前提：**所有用例通过且总数大于 0**，防止把失败结果写成基线。

`RagEvalGateTest` 独立于上面两道门禁，检查检索质量本身：

| 检查 | 阈值 |
|---|---|
| hybrid Recall@5 相对 vector | 不低于 |
| `exact` 类 hybrid Recall@5 相对 vector | 严格高于 |
| noise 查询 | 三种配置全部被门槛拒绝 |
| 相对 `rag-baseline.json` 的 Recall@5 / doc_hit_rate | 下降 ≤2 个百分点 |
| 相对基线的 p95 | 上升 ≤20%（另留 25ms 绝对余量） |

该门禁依赖本机 pgvector 运行时，未设置 `DESKTOP_PG_RUNTIME` 时跳过，不并入默认 CI。

`IndexBenefitEvalTest` / `IndexAbEvalTest` 是**结构性门禁**，不比基线：

| 检查 | 口径 |
|---|---|
| 索引确定性 | 同输入两次扫描的 `path + symbol` 完全一致 |
| 符号 recall | ≥ 0.8（当前 90.3%） |
| 增量正确性 | 改 1 文件只重扫 1 个，复用与删除计数正确 |
| 报告结构 | 字段齐全、逐用例成对列出 ON/OFF |
| 计时指标 | 只记录，不设门禁（机器差异大） |
| 缺 Key | 阶段 B 整体跳过，不产出任何收益结论 |

---

## 6. CI 与命令

```bash
# 后端全量
mvn --batch-mode --no-transfer-progress verify

# 组件 + Runtime 双门禁
mvn --batch-mode --no-transfer-progress test "-Dtest=EvalGate,RuntimeEvalGate"

# 脚本方式（可选 -UpdateBaseline）
powershell -ExecutionPolicy Bypass -File scripts\run-eval.ps1

# RAG 检索评测 + 门禁（需本机 pgvector 运行时，可选 -UpdateBaseline）
powershell -ExecutionPolicy Bypass -File scripts\run-rag-eval.ps1

# 代码索引评测：默认只跑阶段 A；加 -RealModel 才跑真实模型 A/B（缺 Key 退出码 2）
powershell -ExecutionPolicy Bypass -File scripts\run-index-eval.ps1 [-RealModel]
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

## 7. 提示词质量

- 提示词版本化 + SHA-256 + 必备段落校验，误改会在启动或测试时失败。
- 场景区分：`agent`（通用问答）与 `coding`（编程任务）。
- `coding` 明确“停止调用工具只是候选完成”“未验证要写未验证”“不得编造测试结论”。
- 缺失内容只记录缺口，不伪装为已覆盖。

---

## 8. 现状边界

- 没有真实模型的自动化成功率评测，只有确定性脚本。
- 没有前端自动化测试（Vitest / Playwright）。
- 没有真实断网 10 秒等人工异常复核归档。
- 评测数据集规模小，覆盖的是关键路径而非长尾；RAG 语料只有 24 个切片、22 条查询。
- RAG 检索评测使用 concept 生成的模拟向量，不测量 bge-m3；真实模型收益需在有 Key 的环境复跑。
- p95 在本地易受毫秒噪声影响，已加绝对余量，但仍非稳定性能基准；RAG p95 只测召回与融合，不含 embedding。

---

## 9. 面试要点

1. **为什么做四层评测？** 组件评测验证局部行为，Runtime 评测验证真实执行链，RAG 评测验证检索质量，三者不可互相替代；端到端回答质量仍需单独评估。
2. **如何避免“自测自嗨”？** 断言真实文件副作用、事件终态与完成状态，并明确脚本指标不等于真实模型成功率。
3. **门禁怎么防回退？** 通过率、轮次、token、p95、完成率、验证覆盖率多维比较，失败即让 CI 失败。
4. **为什么真实模型不进 CI？** 成本与抖动；用确定性脚本守底线，用抽样看趋势。
5. **怎么证明混合检索值得做？** 用标注语料在内嵌 PG 上跑三配置对照，exact 类从 0% 救回到 100%、整体 Recall@5 68.42% → 100%，再按预写规则判定是否还需要模型 reranker。
