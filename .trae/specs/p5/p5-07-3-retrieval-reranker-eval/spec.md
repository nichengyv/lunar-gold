# P5-07-3 难例检索评测与模型 reranker 对照 — 规格说明

> 日期：2026-11-15
> 状态：已实施并实测，结论“保持不启用”（2026-11-15，详见第 8 节）
> 对应规划：`docs/phases/phase5/plan.md` P5-07（L233-L257）、`docs/UPDATE_PLAN.md` 第 0 节与第 12 节
> 前置结论：P5-07-2 判定“不启用模型 reranker”（模拟向量、24 切片 / 22 查询、hybrid Recall@5 100%）
> 已存在基础：`RagRetrievalEvalRunner`、`RagEvalGateTest`、`SimulatedEmbedding`、`EmbeddingService` / `SpringAiEmbeddingModelConfig`、`RrfRankFusion`、`DocumentService.retrieveContext`、`eval/rag-datasets/`、`eval/baselines/rag-baseline.json`

## 1. 目标

回答一个 P5-07-2 无法回答的问题：**当正确切片在向量路和词法路都排靠后时，模型 reranker 能否把 top-1 / MRR@5 / nDCG@5 拉到值得上生产？**

1. 补充“两路都排靠后”的难例集，避免继续用 hybrid 已 100% 的简单集证明不出增量。
2. 用**真实 embedding**复跑，不把模拟向量的 100% 当作真实模型结论。
3. 仅在评测环境对比 `hybrid` 与 `hybrid + reranker`，指标固定为 top-1、MRR@5、nDCG@5、noise 拒绝、延迟与模型成本。
4. 在跑数据之前写定启用阈值（第 4.5 节），按数据产出“建议启用 / 保持不启用 / 数据集不合格”三选一结论。
5. 本任务不改生产默认策略、不默认启用 reranker；只有预写阈值全部满足时，才建议另开生产实现任务。

## 2. 非目标

- 不重复 P5-07-2 的混合检索收益验证，不修改其数据集、基线、门禁与结论。
- 不用模拟向量产出 reranker 结论；模拟向量最多用于评测器结构自检，报告中必须与真实模式分开标注。
- 不在生产 Java 代码、数据库迁移、前端或 `rag.*` 默认值中引入 reranker。
- 不做 halfvec / V20、PDF 页码引用、跨语言模型替换。
- 不引入新的 Maven 依赖：embedding 复用 Spring AI，reranker 用 JDK `HttpClient`。
- 不把“某次真实运行”的结论外推到其他模型版本、其他语料或其他日期。

## 3. 现状与缺口

| 能力 | 现状 | 缺口 |
|---|---|---|
| 数据集 | 24 切片 / 22 查询，hybrid Recall@5 100% | 没有“正确切片两路都靠后”的难例，无法测精排 |
| 向量 | 默认 `SimulatedEmbedding`；有 `RAG_EVAL_EMBEDDING_KEY` 真实模型分支但从未实测 | 无真实 embedding 指标 |
| 候选池 | 现有 runner 直接按 topK=5 检索 | 排名 6 以后的切片不可见，reranker 无从发挥 |
| 指标 | Recall@3/5、MRR@5、nDCG@5、doc_hit_rate、context_tokens、p95 | 无 top-1 命中率、无 rerank 延迟拆分、无模型成本 |
| reranker | 仅 `RankFusion` 注释提及；无调用实现 | 无对照实现、无成本口径、无判定数据 |
| 凭据 | 2026-11-15 检查：初检时 `RAG_EVAL_EMBEDDING_KEY`、`EMBEDDING_API_KEY`、`RAG_EVAL_RERANK_KEY` 均未设置；随后用户提供 SiliconFlow Key，embedding 与 rerank 均实测可用 | 已解决 |

## 4. 设计

### 4.1 难例数据集（独立文件，不污染 P5-07-2）

新增两个增量文件，避免改动现有数据集导致 `rag-baseline.json` 与 `RagEvalGateTest` 指标漂移：

- `eval/rag-datasets/hard-chunks.jsonl`：**只放新增干扰切片**，字段与 `chunks.jsonl` 一致（`chunkId`、`documentId`、`fileName`、`chunkIndex`、`content`、`concepts`）。
  - 规模 ≥ 16 个切片，作为 3 篇现有文档的“附录 / FAQ / 反例说明”扩展，`documentId` 复用 1001～1003，`chunkIndex` 从 8 起，`chunkId` 从 40001 起。
  - 干扰切片需与主题相关、与查询用词相近，但**不覆盖查询要点**；至少 3 个干扰项能在至少一路过门槛，保证候选池非空。
- `eval/rag-datasets/hard-queries.jsonl`：只放难例查询，字段与 `queries.jsonl` 一致。
  - 规模 ≥ 12 条 `hard` + ≥ 2 条 `noise`。
  - 每条 `hard` 查询有**唯一**正确切片（`relevantChunkIds` 长度 1）。
  - `hard` 查询的 `concepts` 与目标切片 `concepts` 交集为空（沿用 `exact` 类的校验思路，禁止靠 concept 直接命中）。
  - 目标切片正文与查询的词面重叠低（同义改写、换表述、把答案放在补充说明里），使词法路也排靠后。

评测器加载顺序：基础 `chunks.jsonl` + `hard-chunks.jsonl`，查询只读 `hard-queries.jsonl`。加载时校验 `chunkId` 全局唯一、标注切片存在、`expectEmpty` 与标注一致、`hard` 查询 concept 交集为空，任一不满足立即失败并打印可读原因。

### 4.2 真实 embedding 与 reranker

- **真实 embedding**：复用 `SpringAiEmbeddingModelConfig.create(key)` + `EmbeddingModel.embedForResponse`。
  - Key：`RAG_EVAL_EMBEDDING_KEY`，回退 `EMBEDDING_API_KEY`；URL / 模型沿用 `RAG_EVAL_EMBEDDING_URL` / `RAG_EVAL_EMBEDDING_MODEL`（默认 `https://api.siliconflow.cn/v1/embeddings` / `BAAI/bge-m3`）。
  - 不复用 `EmbeddingService.embedSingle`：评测需要读取响应中的 usage 以记录成本；该改动只在测试作用域新增 provider，不动生产类。
  - 报告写 `"embedding": "model"` 并记录模型名；没有 Key 时**不运行**，不使用 `SimulatedEmbedding` 产出结论。
- **真实 reranker**：测试作用域新增 `RerankClient`，JDK `HttpClient`，OpenAI 兼容 rerank 结构（SiliconFlow）。
  - Key：`RAG_EVAL_RERANK_KEY`；URL：`RAG_EVAL_RERANK_URL`（默认 `https://api.siliconflow.cn/v1/rerank`）；模型：`RAG_EVAL_RERANK_MODEL`（默认 `BAAI/bge-reranker-v2-m3`）；超时：`RAG_EVAL_RERANK_TIMEOUT_MS`（默认 10000）。
  - 请求：`{model, query, documents[], top_n, return_documents:false}`；响应读取 `results[{index, relevance_score}]` 与 `usage.total_tokens`。
  - 超时、非 2xx、返回数量不匹配一律抛可读异常并终止本次运行；**禁止**静默降级为“无 reranker”，否则会伪造对照结果。

### 4.3 评测执行（`RagRerankEvalRunner`，测试作用域）

- 复用 `RagRetrievalEvalRunner` 的内嵌 PG + Flyway 方式：`DESKTOP_PG_RUNTIME` 未设置时整类跳过；评测前删除 HNSW 索引走精确扫描，使用 `SingleConnectionDataSource`。
- 候选池 `CANDIDATE_K=20`（参数化）：向量与词法各取 20 → 同一 `RrfRankFusion`、同一 `rag.rrf-k=60` 融合 20 条。
- 两个配置**除最终精排外完全一致**：
  - `hybrid`：融合结果直接截断 top-5；
  - `hybrid+rerank`：把融合后的 20 条交给 `RerankClient`，取其 top-5。
- 门槛沿用生产值（向量 min 0.5 / gate 0.55；词法 min 0.3 / gate 0.35），不改阈值，否则结论不可迁移。
- 指标口径：
  - `top1_hit_rate` = top-1 是标注相关切片的查询占比（新增，本次核心指标）；
  - `MRR@5` / `nDCG@5` / `Recall@5` = 沿用 P5-07-2 的二值相关性口径；
  - `noise_rejection` = 难例集内 noise 查询被门槛拒绝的数量 / 总数（两配置分别统计）；
  - `retrieval_p95_ms` = 召回 + 融合耗时（不含 embedding）；`rerank_p95_ms` = 单次 rerank 调用耗时；`total_p95_ms` = 两者之和；
  - `cost`：`embedding_query_tokens`（能取到 usage 记实际值，否则按查询文本估算并标 `estimated=true`）、`rerank_tokens`（响应 usage，实际值）、`rerank_calls`、`avg_cost_per_query`（仅当配置单价时输出货币值）。
  - 单价由 `RAG_EVAL_PRICE_EMBEDDING_PER_MTOKENS` / `RAG_EVAL_PRICE_RERANK_PER_MTOKENS` 提供；未配置时只报 token 与实际调用次数，不臆造价格。
- 报告新增 `calibration` 节点，逐条 hard 查询记录：目标切片在 vector / lexical 两路的排名、是否进入 hybrid 候选池、最终在 hybrid 与 hybrid+rerank 中的排名。
- 报告新增 `decision` 节点：按第 4.5 节逐条输出阈值、实测值、是否通过与最终结论，避免事后解释。
- 输出：`eval/rag-reports/rag-rerank-<timestamp>.json`（目录已忽略），控制台打印两配置对比表、`hybrid+rerank - hybrid` 差值、逐条阈值判定与数据集校准摘要。

### 4.4 数据有效性（阻塞性前置，不满足则不出结论）

| 编号 | 条件 |
|---|---|
| D1 | `hard` 查询 ≥ 12，干扰切片 ≥ 16，`noise` ≥ 2 |
| D2 | 每条 `hard` 查询有唯一正确切片，且与目标切片 concept 交集为空 |
| D3 | ≥ 80% 的 `hard` 查询，目标切片在 vector 与 lexical 两路排名均 > 5 |
| D4 | 每条 `hard` 查询的目标切片都出现在 hybrid 候选池（top-20）内；不在池内的查询标记为“召回缺口”，不参与 reranker 收益判定并逐条列出 |

D1～D2 在加载时硬校验；D3～D4 由校准结果判定。D3 不满足时结论为“**数据集不合格，本次不判定**”，必须调整干扰项或用词后重跑；**不得在数据不合格时下调阈值**。

### 4.5 预写启用阈值（跑数据前写定，不得事后修改）

只有以下全部满足，才建议另开生产 reranker 任务（P5-07-4 候选）：

| 编号 | 条件（`hybrid+rerank` 相对 `hybrid`） |
|---|---|
| Q1 | `top1_hit_rate` 绝对提升 ≥ 10pp |
| Q2 | `nDCG@5` 绝对提升 ≥ 5pp |
| Q3 | `MRR@5` 不回退（允许 -1pp 噪声容差） |
| Q4 | `Recall@5` 不回退（允许 -1pp 噪声容差） |
| Q5 | noise 拒绝数不下降，且等于 noise 总数 |
| Q6 | `rerank_p95_ms` ≤ 300ms，且 `total_p95_ms` ≤ 1000ms |
| Q7 | `rerank_tokens` ≤ 候选池正文 token × 1.2；若配置单价，`avg_cost_per_query` ≤ 0.01 元 |
| Q8 | 数据有效性 D1～D4 全部满足 |

判定规则：

- Q1～Q8 全满足 → 结论“**建议启用**”，仅建议另开生产实现任务（开关、超时、降级、成本上限、埋点、灰度），本任务不实施。
- 任一 Q 不满足 → 结论“**保持不启用**”，报告必须写明未通过的编号与实测数值；不得用“整体感觉更好”替代。
- D 不满足 → 结论“**数据集不合格，本次不判定**”，先修数据集，阈值保持不变。

### 4.6 门禁（`RagRerankEvalGateTest`）

- 同时要求 `DESKTOP_PG_RUNTIME` 与 `RAG_EVAL_RERANK_KEY`（或 `RAG_EVAL_EMBEDDING_KEY` / `EMBEDDING_API_KEY`）存在才运行；缺失时跳过，普通 `mvn test` 不受影响。
- 断言只覆盖**结构与数据有效性**，不对模型收益做强断言（真实模型付费且有波动）：
  - D1～D2 通过；
  - 报告含两配置、`calibration`、`cost`、`noise`、`decision` 节点；
  - 基线存在时，仅对 `hybrid` 配置做回退防护（Recall@5 / doc_hit_rate 下降 ≤ 2pp，p95 增幅 ≤ 20% + 25ms，沿用 P5-07-2 口径）。
- 新增 `eval/baselines/rag-rerank-baseline.json`，记录模型名、数据集规模、两配置指标与成本；`-Deval.updateBaseline=true` 时先校验 D 与结构断言，再写入。
- `RagRerankEvalRunner` 不匹配 surefire 默认命名，只由脚本或显式 `-Dtest` 运行。

### 4.7 运行脚本（`scripts/run-rag-rerank-eval.ps1`）

- 自动定位 `DESKTOP_PG_RUNTIME`（与 `run-rag-eval.ps1` 相同的 `build/pg-runtime/pg-runtime-*.txz` 兜底）。
- 运行前显式检查 embedding Key 与 rerank Key：缺任一项时打印缺口清单（缺哪个变量、需要哪个服务）并以非 0 退出，**不启动 Maven、不写报告、不改基线**。
- 支持 `-UpdateBaseline`；不改动 `run-rag-eval.ps1` 与既有 P5-07-2 流程。

### 4.8 前置缺口处理

- 当前环境缺 Key，实施阶段先交付数据集、评测器、门禁、脚本与缺口报告；**未实测前 spec 第 8 节只写缺口，不写任何指标数字或结论**。
- 网络不可达、鉴权失败、模型名不可用同样按“前置缺口”处理：打印可读诊断并停止，不产出部分结果作为结论。
- 严禁：用模拟向量结果补位、用无 rerank 结果冒充 rerank 结果、用估算 token 冒充实际 token（估算必须显式标注）。

## 5. 影响范围

新增：

- `eval/rag-datasets/hard-chunks.jsonl`、`eval/rag-datasets/hard-queries.jsonl`
- `eval/baselines/rag-rerank-baseline.json`（首次真实实测后提交）
- `src/test/java/com/capsule/agentkey/eval/rag/RagRerankEvalRunner.java`
- `src/test/java/com/capsule/agentkey/eval/rag/RerankClient.java`
- `src/test/java/com/capsule/agentkey/eval/rag/RagRerankEvalGateTest.java`
- `scripts/run-rag-rerank-eval.ps1`

修改（仅文档）：

- `docs/phases/phase5/plan.md`（P5-07-3 小节）
- `docs/UPDATE_PLAN.md`（P5-07 状态、第 12 节执行顺序与后续批次）
- `docs/COMPLETED_WORK.md`（8.19）
- `docs/architecture/04-context-rag.md`（第 2 节补难例与 reranker 对照）
- `docs/architecture/10-quality-evaluation.md`（第四层补难例、成本与判定口径）

不改：生产 Java 代码、数据库迁移、前端、`rag.*` 默认值、`.gitignore`（`eval/rag-reports/` 已忽略）、P5-07-2 数据集与其基线。

## 6. 验收标准

1. 难例集满足 4.1 与 D1～D2，加载校验可读失败；基础数据集与 `rag-baseline.json` 未改动。
2. 缺 Key 时脚本输出缺口清单并非 0 退出；`RagRerankEvalGateTest` 跳过，`mvn test` 不受影响；未写报告、未改基线。
3. 有 Key 时一次运行产出 A/B 两配置报告，含 top-1、MRR@5、nDCG@5、Recall@5、noise、retrieval/rerank/total 延迟、token 与成本字段，以及 `calibration` 与 `decision`。
4. 校准结果满足 D3～D4；不满足时结论明确为“数据集不合格”，且不产出启用建议。
5. `decision` 严格按 4.5 逐条给出台账，结论只能是“建议启用 / 保持不启用 / 数据集不合格”之一。
6. `RagRerankEvalGateTest` 通过；基线可写入并可复现对比。
7. 生产代码、迁移、前端与 `rag.*` 无改动；`git diff` 仅含测试作用域、数据集、脚本与文档。
8. spec 第 8 节记录环境、模型名与版本、数据集规模、校准结果、A/B 指标、成本、逐条阈值与结论（或前置缺口）。
9. 后端全量 `mvn test` 通过；`EvalGate,RuntimeEvalGate` 仍通过；P5-07-2 的 `RagEvalGateTest` 与基线指标不变。

## 7. 风险与边界

- **真实模型非确定性**：结论绑定模型名、服务端版本与日期；升级模型必须重跑，不跨版本外推。
- **小样本分辨率**：12 条 hard 查询下 1 条约等于 8.33pp，Q1 的 10pp 实际约等于“多救回 1～2 条”；报告必须写明该分辨率，避免把个位数差异说成显著收益。
- **难例可能变成“召回失败”**：若目标切片未进入候选池，reranker 无法修复；D4 单列这类查询，不能算作 reranker 的失败或成功。
- **成本口径**：货币成本依赖用户配置单价，未配置只报 token；不得用公开定价臆造结论。
- **服务可用性**：付费 Key、网络、限流都可能中断评测；按前置缺口处理，不写部分结论。
- **生产迁移成本**：即使判定“建议启用”，生产化仍需开关、超时、降级、成本上限、埋点与灰度，必须另开任务，不在本任务顺手实施。

## 8. 实施结果（2026-11-15 实测）

### 8.1 环境与模型

| 项 | 值 |
|---|---|
| 运行时间 | 2026-11-15，报告 `rag-rerank-20261115-183608-796.json` |
| 数据库 | 内嵌 PostgreSQL 16.11 + pgvector 0.8.7，评测前删除 HNSW 走精确扫描 |
| embedding | `BAAI/bge-m3`（SiliconFlow `/v1/embeddings`，1024 维），index tokens 6430，query tokens 175（实际 usage，非估算） |
| reranker | `BAAI/bge-reranker-v2-m3`（SiliconFlow `/v1/rerank`） |
| 语料 | 基础 24 切片 + 难例干扰 160 切片 = 184 切片 |
| 查询 | 12 条 hard + 2 条 noise；hard 每条唯一正确切片 |
| 参数 | `CANDIDATE_K=20`、`TOP_K=5`、`RRF_K=60`、门槛沿用生产值、检索计时重复 3 次、rerank 每查询 1 次 |

### 8.2 数据有效性

| 项 | 结果 | 判定 |
|---|---|---|
| D1 | hard 12 / 干扰 160 / noise 2 | 通过 |
| D2 | 唯一正确切片、concept 无交集，加载时硬校验 | 通过 |
| D3 | 目标两路排名 >5 占比 **83.33%**（10/12） | 通过 |
| D4 | 目标进入 hybrid 候选池的缺口 **0** | 通过 |

D3 逐条目标排名：h-01 vec 11、h-02 vec 8、h-03 vec 10、h-04 vec 6、h-05 vec 11、h-06 vec 10、h-07 vec 8、h-08 vec 1、h-09 vec 18、h-10 vec 15、h-11 vec 1、h-12 vec 9。

**D3 口径限制（必须与结论一起引用）**：本难例集的查询均为同义改写，词法路 `word_similarity` 只在 3/12 条查询上返回候选，其余 9 条 `lexical_candidates=0`、目标 `lexical_rank=0`。按 D3 定义“未进词法 top-5”计为 >5 而通过，因此本轮对照实质是**向量单路 top-20 → rerank**，并非“两路都各有关联候选但都排靠后”。若后续要验证真正的双路难例，需构造词法路能召回但排名靠后的查询（换词不完全、保留专有名词），单开数据集迭代。

### 8.3 A/B 指标

| 配置 | top-1 | Recall@5 | MRR@5 | nDCG@5 | doc_hit | 上下文 tokens | 检索 p95 | rerank p95 | 总 p95 |
|---|---|---|---|---|---|---|---|---|---|
| `hybrid` | 8.33% | 16.67% | 12.50% | 13.59% | 100% | 163.33 | 42.29ms | — | 42.29ms |
| `hybrid+rerank` | 16.67% | 41.67% | 27.78% | 31.35% | 100% | 211.33 | 42.29ms | 482.81ms | 508.06ms |
| 差值（pp） | **+8.34** | +25.00 | +15.28 | **+17.76** | 0 | +48.00 | 0 | +482.81 | +465.77 |

noise：2/2 两条配置均全部拒绝（reranker 不参与 noise 判定，避免把付费调用算成收益）。

### 8.4 成本

| 项 | 值 |
|---|---|
| rerank 调用 | 12 次（每条 hard 查询 1 次，noise 不调用） |
| rerank tokens | 13120（响应 `meta.tokens.input_tokens` 实际值） |
| 单查询 rerank tokens | 1093.33，候选池正文估算 924.25，比值 1.18（≤ 1.2） |
| 货币成本 | 未输出：未配置 `RAG_EVAL_PRICE_*` 单价，不臆造价格 |

### 8.5 逐条阈值判定

| 编号 | 条件 | 实测 | 结果 |
|---|---|---|---|
| Q1 | top-1 ≥ +10pp | +8.34pp | **不通过** |
| Q2 | nDCG@5 ≥ +5pp | +17.76pp | 通过 |
| Q3 | MRR@5 ≥ -1pp | +15.28pp | 通过 |
| Q4 | Recall@5 ≥ -1pp | +25.00pp | 通过 |
| Q5 | noise 全拒绝且不下降 | 2/2（hybrid 2/2） | 通过 |
| Q6 | rerank p95 ≤ 300ms 且总 p95 ≤ 1000ms | 482.81ms / 508.06ms | **不通过**（rerank 单项超限；总 p95 通过） |
| Q7 | rerank tokens ≤ 1.2×候选正文；配置单价时 ≤ 0.01 元 | 1.18×；单价未配置 | 通过 |
| Q8 | D1～D4 全满足 | 全满足 | 通过 |

### 8.6 结论

**保持不启用**（预写阈值有两条不满足：Q1 差 1.66pp，Q6 rerank 延迟超限 182ms）。

- 12 条 hard 查询的分辨率为 8.33pp，Q1 的差距约等于“少救回 1 条”，不足以据此判定收益，也不能据此宣称显著。
- reranker 确实把 5 条目标从 top-5 之外提进 top-5（h-02→2、h-04→3、h-06→1、h-08→2、h-11→1），排序质量（nDCG/MRR/Recall）提升明显，但 top-1 提升未达预写门槛，且单次 rerank 延迟 480ms 级，直接上生产会显著抬高一跳对话的检索耗时。
- 本结论绑定 `bge-m3` + `bge-reranker-v2-m3` 与 2026-11-15 的服务端版本；换模型必须重跑，不跨版本外推。
- 生产默认策略未改动，未新增 reranker 开关；若后续要启用，需另开任务补齐开关、超时、降级、成本上限、埋点与灰度。

### 8.7 回归记录

- `RagRerankEvalRunner` + `RagRerankEvalGateTest`：通过（基线已写入 `eval/baselines/rag-rerank-baseline.json`）。
- `EvalGate`、`RuntimeEvalGate`、P5-07-2 的 `RagEvalGateTest`：通过，`rag-baseline.json` 未改动。
- 后端全量 `mvn test`：296 项，**2 项失败为既有迁移计数断言**——`FlywayV18MigrationAcceptanceTest` 期望 latest=18、`DesktopPgVectorIntegrationTest` 期望 latest=15，实际均为已提交的 `V19__add_tool_approval_decision_source.sql`；本任务未新增/修改任何迁移，失败与本任务无关，未顺手改这两个用例。
