# P5-07-2 检索质量量化与 reranker 决策 — 任务拆分

> 状态：已完成
> 前置：spec.md 已确认
> 约束：不改生产 Java 代码、数据库迁移与 `rag.*` 默认值

## T1 语料与查询集

- [x] T1.1 `eval/rag-datasets/chunks.jsonl`：3 篇文档、≥24 个切片，字段 `chunkId / documentId / fileName / chunkIndex / content / concepts`。
- [x] T1.2 `eval/rag-datasets/queries.jsonl`：≥20 条查询，覆盖 semantic / exact / mixed / cross-lingual / noise 五类。
- [x] T1.3 标注校验：`relevantChunkIds` 全部存在于语料；`exact` 类查询的 `concepts` 不含对应编号 / 术语；`noise` 查询 `expectEmpty=true`。

验证：脚本或测试读取两个文件时字段完整、ID 引用无悬空。（runner 内 `validateQuery` 每次执行校验）

## T2 离线检索评测器

- [x] T2.1 `SimulatedEmbedding`（测试作用域）：concept → 确定性 1024 维归一化向量。
- [x] T2.2 `RagRetrievalEvalRunner`：内嵌 PG + Flyway 迁移，写入语料，构造 `VectorStoreService` 与 `RrfRankFusion`。
- [x] T2.3 三配置评测：vector / lexical / hybrid，输出 Recall@3、Recall@5、MRR@5、nDCG@5、doc_hit_rate、context_tokens、p95_latency_ms，并按 category 与 noise 分列。
- [x] T2.4 报告写入 `eval/rag-reports/<timestamp>.json`，控制台打印对比表与 `hybrid - vector` 差值。
- [x] T2.5 可选真实模型模式：`RAG_EVAL_EMBEDDING_KEY` 存在时走 `EmbeddingService`；报告记录 `embedding` 模式。（仅实现与门禁，未实际用真实模型跑过）

验证：设置 `DESKTOP_PG_RUNTIME` 后运行通过；连续两次运行指标一致。（除 p95 外完全一致）

## T3 基线与门禁

- [x] T3.1 `eval/baselines/rag-baseline.json`：三配置指标 + 数据集规模 + embedding 模式。
- [x] T3.2 `RagEvalGateTest`：hybrid Recall@5 ≥ vector；exact 类严格提升；noise 全部拒绝；相对基线 Recall@5 / doc_hit_rate 下降 ≤2pp、p95 增幅 ≤20%+25ms。
- [x] T3.3 失败原因包含类别与具体指标，不输出空泛断言。
- [x] T3.4 `scripts/run-rag-eval.ps1`：运行 runner + gate，支持 `-UpdateBaseline`。
- [x] T3.5 `.gitignore` 增加 `eval/rag-reports/`。

验证：正常数据通过；临时关闭混合检索时门禁失败且原因可读。（负向验证得到 4 条可读失败）

## T4 结论与文档同步

- [x] T4.1 按 spec 4.5 判定规则给出 reranker 结论，写入 spec 第 8 节（含环境、规模、指标、未执行项）。
- [x] T4.2 更新 `docs/phases/phase5/plan.md` P5-07 完成情况与后续项。
- [x] T4.3 更新 `docs/UPDATE_PLAN.md` 第 0 节与 P5-07 勾选项。
- [x] T4.4 更新 `docs/COMPLETED_WORK.md` 新增小节。
- [x] T4.5 更新 `docs/architecture/04-context-rag.md` 与 `10-quality-evaluation.md`（评测口径与数据）。

## T5 回归验证

- [x] T5.1 `mvn test -Dtest=RagRetrievalEvalRunner,RagEvalGate` 通过（本机设置 `DESKTOP_PG_RUNTIME`）。
- [x] T5.2 后端全量 `mvn test` 通过（295 项，0 失败、0 错误、13 跳过）。
- [x] T5.3 `EvalGate,RuntimeEvalGate` 通过，组件与 Runtime 基线未被无意改动。
- [x] T5.4 未设置环境变量时跳过行为符合预期，默认测试时长无明显增加。
