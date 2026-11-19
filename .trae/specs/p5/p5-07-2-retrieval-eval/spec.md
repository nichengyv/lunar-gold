# P5-07-2 检索质量量化与 reranker 决策 — 规格说明

> 日期：2026-11-15
> 状态：已完成（结论：不启用模型 reranker）
> 对应规划：`docs/phases/phase5/plan.md` P5-07（L233-L249）、`docs/UPDATE_PLAN.md` 第 0 节与 L695
> 已存在基础：`VectorStoreService.searchLexicalForProject`、`RrfRankFusion`、`DocumentService.retrieveContext`、`DocumentServiceRagTest`（mock 级）、`EmbeddedPgSupport` / `DesktopPgVectorIntegrationTest`（真实内嵌 PG 路径）

## 1. 目标

用可复现的离线检索评测回答两个决策问题，而不是先写代码再找理由：

1. **混合检索（向量 + pg_trgm + RRF）相对单路向量，是否有量化收益？**
   指标：Recall@3 / Recall@5、MRR@5、nDCG@5、引用文档命中率、上下文 token、p95 检索时延。
2. **是否需要启用模型 reranker？**
   按第 4.5 节预先写定的判定规则给出结论，避免事后解释。

## 2. 非目标

- 不在本任务实现模型 reranker；只有判定规则明确指向“需要”时，才在下一任务单独设计与实施。
- 不做 halfvec / V20：当前没有体积或检索性能成为瓶颈的数据。
- 不修改生产默认阈值（`rag.*`）与 `DocumentService` 编排；如数据支持调参，另附前后对比并单独确认。
- 不提供 PDF 页码引用：解析器未保留版式，属于独立解析器任务。
- 不引入新的运行时依赖；评测代码只依赖测试作用域已有能力。

## 3. 现状与缺口

| 能力 | 现状 | 缺口 |
|---|---|---|
| 召回实现 | 向量（pgvector）+ 词法（pg_trgm）+ RRF 融合已上线 | 无真实 SQL 下的质量数据 |
| 测试 | `DocumentServiceRagTest` 用 mock 验证编排与门槛；`RrfRankFusionTest` 验证排序规则 | 只证明“按设计工作”，不证明“相对单路有收益” |
| 引用字段 | `chunkId` / `chunkIndex` / `fileName` / `score` 已返回 | 无基于标注语料的引用命中率 |
| 决策依据 | 规划写明“rerank 只在离线收益不足时启用” | 没有离线报告，无法执行该规则 |

## 4. 设计

### 4.1 语料与查询集（`eval/rag-datasets/`）

- `chunks.jsonl`：每行一个切片，字段 `chunkId`、`documentId`、`fileName`、`chunkIndex`、`content`、`concepts[]`。
  - 3 篇真实风格文档：中文产品规格（含编号与专有名词）、英文部署 / API 文档、代码说明（含函数名与配置项）。
  - 合计 ≥ 24 个切片，单篇 ≥ 6 个切片，保证 top-5 有区分度。
- `queries.jsonl`：每行一个查询，字段 `id`、`query`、`category`、`concepts[]`、`relevantChunkIds[]`、`expectEmpty`。
  - `category` 五类：`semantic`（同义改写，无原词）、`exact`（含编号 / 罕见术语）、`mixed`（语义与精确词各占一半）、`cross-lingual`（中文问英文资料）、`noise`（与语料无关，期望不引用）。
  - 合计 ≥ 20 条：semantic 8 / exact 6 / mixed 3 / cross-lingual 2 / noise 3（可微调，但前四类总数 ≥ 17）。
  - `exact` 类中的编号与术语必须**不出现在**该查询的 `concepts` 中，确保向量路径确实漏召回。

### 4.2 向量与词法路径

- 词法：直接调用生产实现 `VectorStoreService.searchLexicalForProject`，走真实 `word_similarity`。
- 向量：新增测试作用域 `SimulatedEmbedding`，由 `concepts[]` 生成确定性 1024 维单位向量（concept 哈希到固定维度、符号权重、L2 归一化）。
  - 它模拟 embedding 的**语义召回**，不测量 bge-m3 本身；报告必须写入 `"embedding": "simulated"`。
  - 可选真实模型模式：设置 `RAG_EVAL_EMBEDDING_KEY` 时通过 `EmbeddingService` + `SpringAiEmbeddingModelConfig` 调用真实模型，报告写入 `"embedding": "model"`。缺 Key 时跳过，不作为验收条件，只在实际跑过时记录数字。

### 4.3 评测执行（`RagRetrievalEvalRunner`，测试作用域）

- 复用 `EmbeddedPgSupport` / `DesktopPgVectorIntegrationTest` 的模式：内嵌 PostgreSQL + pgvector 运行时，`DESKTOP_PG_RUNTIME` 未设置时整类跳过（与现有集成测试一致）。
- Flyway 执行真实迁移，取得 `JdbcTemplate` 后构造 `VectorStoreService`，写入 `chunks.jsonl` 的全部向量，用户 / 项目使用数据集固定 ID。
- 对比三种配置，除召回范围外**不引入其他差异**：
  1. `vector`：仅 `searchForProject`；
  2. `lexical`：仅 `searchLexicalForProject`；
  3. `hybrid`：与 `DocumentService` 相同的融合方式（同一 `RrfRankFusion`、同一 `rag.rrf-k`）。
- 指标定义：
  - `Recall@k` = top-k 中标注相关切片数 / 标注相关切片总数，正向查询取平均；
  - `MRR@5` = 首个相关切片排名的倒数（无命中记 0）取平均；
  - `nDCG@5` = 二值相关性下的归一化折损累计增益；
  - `doc_hit_rate` = 至少命中一篇标注相关文档的查询占比；
  - `context_tokens` = 融合后 top-5 正文的估算 token（CJK 1 字≈1 token，非 CJK 4 字符≈1 token），报告注明为估算；
  - `p95_latency_ms` = 每条查询重复 3 次的检索耗时 p95。
- `noise` 查询单独统计：在 `DocumentService` 的门槛（向量 0.55 / 词法 0.35）下应判为不引用。
- 输出：`eval/rag-reports/<timestamp>.json`（该目录加入 `.gitignore`），控制台打印三配置对比表与 `hybrid - vector` 差值。

### 4.4 基线与门禁

- 新增 `eval/baselines/rag-baseline.json`：三配置的指标、`embedding` 模式、数据集规模。
- 新增 `RagEvalGateTest`（同目录，环境变量缺失时跳过）：
  - `hybrid` 的 Recall@5 **不低于** `vector`；
  - `exact` 分类下 `hybrid` Recall@5 **严格高于** `vector`（至少救回 1 条）；
  - 全部 `noise` 查询被门槛拒绝；
  - 相对基线：Recall@5 / doc_hit_rate 下降 ≤ 2pp，p95 增幅 ≤ 20%（另留 25ms 绝对余量）。
  - 任一不满足即失败，并打印类别与指标。
- 新增 `scripts/run-rag-eval.ps1`：设置 `DESKTOP_PG_RUNTIME` 后运行 `RagRetrievalEvalRunner,RagEvalGate`，支持 `-UpdateBaseline`；不并入默认 CI（依赖本机 PG 运行时），与现有 `run-eval.ps1` 并列。

### 4.5 reranker 判定规则（预先写定）

- **不启用模型 reranker**：`hybrid` 相对 `vector` 的 Recall@5 提升 ≥ 2pp，或 `exact` 类 Recall@5 提升 ≥ 10pp；且 `noise` 全部拒绝。
- **需要模型 reranker（转入下一任务）**：上述条件不满足，或 `hybrid` 的 nDCG@5 与 `vector` 持平但 top-1 命中率明显偏低。
- 无论结论如何，都不在本任务修改默认开关；结论写入 spec 第 8 节、`plan.md` 与 `04-context-rag.md`。

## 5. 影响范围

- 新增：`eval/rag-datasets/chunks.jsonl`、`eval/rag-datasets/queries.jsonl`、`eval/baselines/rag-baseline.json`、`scripts/run-rag-eval.ps1`、测试作用域 `RagRetrievalEvalRunner` / `RagEvalGateTest` / `SimulatedEmbedding`。
- 修改：`.gitignore`（`eval/rag-reports/`）、`docs/phases/phase5/plan.md`、`docs/UPDATE_PLAN.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/04-context-rag.md`、`docs/architecture/10-quality-evaluation.md`。
- 不改生产 Java 代码、数据库迁移、前端与运行时依赖。

## 6. 验收标准

1. `eval/rag-datasets/` 语料与查询集满足 4.1 的规模与标注要求，`exact` 类查询不含对应编号 / 术语的 concept。
2. 设置 `DESKTOP_PG_RUNTIME` 后，`RagRetrievalEvalRunner` 一次跑完三配置并写出报告；同一数据集连续两次运行指标一致（确定性）。
3. `RagEvalGateTest` 通过；故意把 `rag.hybrid-enabled` 设为 false 或降低词法门槛时，门禁能给出可读失败原因（至少验证一项）。
4. `eval/baselines/rag-baseline.json` 已提交，`scripts/run-rag-eval.ps1 -UpdateBaseline` 可更新。
5. 未设置 `DESKTOP_PG_RUNTIME` / `RAG_EVAL_EMBEDDING_KEY` 时，测试与默认 `mvn test` 不受影响，跳过原因明确。
6. spec 第 8 节记录实际运行的环境、数据集规模、三配置指标与 reranker 结论；文档同步完成。
7. 后端全量 `mvn test` 通过，`EvalGate,RuntimeEvalGate` 仍通过。

## 7. 风险与边界

- **模拟向量不等于 bge-m3**：结论只对“语义信号存在时，融合是否优于单路”负责；真实模型收益需在有 Key 的环境用同一数据集复跑并更新报告。文档必须写明，不得表述为“模型检索已验证”。
- **数据集偏小**：用于决策与回归，不等于线上分布；新增语料时同步更新基线。
- **时延噪声**：内嵌 PG 本机数据，p95 只做相对比较与回归门禁，不对外承诺绝对值。
- **PDF 页码**：仍为后续项，不在本任务范围。

## 8. 实施结果（2026-11-15）

- **环境**：Windows x64、JDK 21、JUnit 5。内嵌 PostgreSQL + pgvector（`DESKTOP_PG_RUNTIME` 指向本地 `.txz` 运行时），Flyway 执行真实迁移。为消除小数据下 HNSW 近邻的排序不确定性，评测前删除 `idx_vector_record_embedding`，24 行数据走精确扫描；使用 `SingleConnectionDataSource`，避免把连接建立耗时算进检索时延；每条查询重复 3 次，p95 只统计召回与融合（不含 embedding 调用）。
- **数据集规模**：3 篇文档 / 24 个切片（每篇 8 个），22 条查询（semantic 8 / exact 6 / mixed 3 / cross-lingual 2 / noise 3），topK=5。`exact` 类查询的编号 / 术语均不出现在该查询的 `concepts` 中，向量路径确实漏召回。
- **指标对比**（19 条正向查询，`embedding=simulated`）：

| 配置 | Recall@3 | Recall@5 | MRR@5 | nDCG@5 | doc_hit_rate | context_tokens | p95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| vector | 68.42% | 68.42% | 68.42% | 68.42% | 68.42% | 24.89 | 1.63ms |
| lexical | 31.58% | 31.58% | 31.58% | 31.58% | 31.58% | 11.42 | 1.09ms |
| hybrid | 100.00% | 100.00% | 100.00% | 100.00% | 100.00% | 36.32 | 2.70ms |

分类别看，`exact` 类 vector 全项 0% → hybrid 全项 100%（lexical 单独已达 100%）；`semantic` / `mixed` / `cross-lingual` 三类 vector 与 hybrid 均为 100%，lexical 为 0%。`noise` 3 条在 vector / lexical / hybrid 三配置下全部被门槛拒绝。`hybrid - vector`：Recall@5 +31.58pp，context_tokens +11.43（估算值），p95 +1.07ms。

- **reranker 结论**：**不启用模型 reranker**。hybrid Recall@5 相对 vector 提升 31.58pp（≥2pp），`exact` 类提升 100pp（≥10pp），且 noise 全部拒绝，满足 4.5 的“不启用”条件；模型 reranker 保留为后续可选，本任务不改任何默认开关。
- **门禁负向验证**：临时把 hybrid 降级为 vector 后，`RagEvalGateTest` 给出 4 条可读失败（exact 类未提升、hybrid Recall@5 由 100% 回退到 68.42%、exact/hybrid Recall@5 与文档命中率回退到 0%），验证后已删除临时代码。
- **偏差与未执行项**：
  - 模拟向量只验证“语义信号存在时融合是否优于单路”，**不测量 bge-m3**；真实模型收益需要在有 `RAG_EVAL_EMBEDDING_KEY` 的环境用同一数据集复跑并更新基线。
  - 数据集只有 24 个切片、22 条查询，用于决策与回归，不代表线上分布。
  - p95 为内嵌 PG 本机数据，只用于相对比较与回归门禁，不承诺绝对值。
  - 未实施 halfvec / V20、PDF 页码引用与模型 reranker。
