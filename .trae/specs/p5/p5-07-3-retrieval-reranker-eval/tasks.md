# P5-07-3 难例检索评测与模型 reranker 对照 — 任务清单

> 对应规格：[spec.md](spec.md)
> 状态：已完成（2026-11-15 实测，结论“保持不启用”）
> 原则：先交付可复现的难例集与评测器；真实 Key 到位前只报缺口，不写实测结论。

## 执行结果（2026-11-15）

- T1～T7 已交付：`hard-chunks.jsonl`（160 切片）、`hard-queries.jsonl`（12 hard + 2 noise）、`RagRerankEvalRunner`、`RerankClient`、`RagRerankEvalGateTest`、`scripts/run-rag-rerank-eval.ps1`；未改 P5-07-2 数据集与基线。
- T8 已实测：用户提供 SiliconFlow Key，embedding `BAAI/bge-m3` + reranker `BAAI/bge-reranker-v2-m3`；校准 D3 83.33%、D4 0；A/B 见 spec 第 8 节。
- T9 已记录：Q1（top-1 +8.34pp < +10pp）与 Q6（rerank p95 482.81ms > 300ms）不通过 → 保持不启用；不启 P5-07-4。
- T10 已同步 5 份文档；T11 回归见下文与 checklist F 组。

## 阶段 1：难例数据集（无外部依赖）

### T1 编写难例切片与查询

- 新增 `eval/rag-datasets/hard-chunks.jsonl`：≥ 16 个干扰切片，作为 3 篇现有文档的附录 / FAQ / 反例说明；`documentId` 复用 1001～1003，`chunkIndex` 从 8 起，`chunkId` 从 40001 起。
- 新增 `eval/rag-datasets/hard-queries.jsonl`：≥ 12 条 `hard` + ≥ 2 条 `noise`；`hard` 查询唯一正确切片、concept 与目标切片无交集、词面重叠低。
- 干扰项中至少 3 个能在至少一路过门槛，保证候选池非空。
- 不改 `chunks.jsonl`、`queries.jsonl`、`rag-baseline.json`。

验证：

- 加载校验脚本 / 评测器校验通过：`chunkId` 全局唯一、标注切片存在、`expectEmpty` 一致、`hard` 查询 concept 交集为空。
- 人工核对每条 `hard` 查询：答案确实只在其标注切片中，干扰切片不覆盖要点。

### T2 数据集自检（不依赖网络）

- 在 `RagRerankEvalRunner` 的加载逻辑中实现 D1～D2 校验，失败时抛出含查询 id / 切片 id 的可读异常。
- 提供一次纯加载自检（不启动 PG、不调用模型），确认两份新文件可被解析与校验。

验证：故意制造 `chunkId` 重复、标注切片缺失、concept 交集三条错误，均得到可读失败信息，恢复后通过。

## 阶段 2：评测器（依赖 T1）

### T3 实现真实 embedding provider（测试作用域）

- 在 `src/test/java/com/capsule/agentkey/eval/rag/` 下新增真实 provider：使用 `SpringAiEmbeddingModelConfig.create(key)` + `embedForResponse`，读取 usage（可用则记实际 token，否则按查询文本估算并标注 `estimated=true`）。
- Key 读取顺序：`RAG_EVAL_EMBEDDING_KEY` → `EMBEDDING_API_KEY`；无 Key 时不构造该 provider，runner 直接按前置缺口退出。
- 不修改 `EmbeddingService` / `SpringAiEmbeddingModelConfig`。

验证：无 Key 时分支不执行模型调用；有 Key 时能返回 1024 维向量且 usage 字段可用。

### T4 实现 `RerankClient`（测试作用域）

- JDK `HttpClient` POST 到 `RAG_EVAL_RERANK_URL`（默认 `https://api.siliconflow.cn/v1/rerank`），模型默认 `BAAI/bge-reranker-v2-m3`。
- 请求 `{model, query, documents[], top_n, return_documents:false}`；解析 `results[{index, relevance_score}]` 与 `usage.total_tokens`；超时 `RAG_EVAL_RERANK_TIMEOUT_MS`（默认 10000）。
- 超时 / 非 2xx / 数量不匹配抛可读异常；不做静默降级。
- Key 只从环境变量读取，不写日志、不写报告。

验证：本地用一个临时 HTTP stub 验证请求体、排序与 usage 解析；不可达时异常信息包含 URL 与状态码（不含 Key）。

### T5 实现 `RagRerankEvalRunner`

- 复用 `RagRetrievalEvalRunner` 的内嵌 PG + Flyway 初始化（`DESKTOP_PG_RUNTIME` 门禁、删除 HNSW、`SingleConnectionDataSource`）。
- 加载基础 `chunks.jsonl` + `hard-chunks.jsonl`，查询只读 `hard-queries.jsonl`。
- `CANDIDATE_K=20`：向量 / 词法各取 20 → `RrfRankFusion(60)` 融合 20；`hybrid` 截断 5，`hybrid+rerank` 对 20 条调用 reranker 取 5。
- 门槛沿用生产值（向量 0.5 / 0.55，词法 0.3 / 0.35）。
- 输出指标：top-1、MRR@5、nDCG@5、Recall@5、noise 拒绝、retrieval/rerank/total p95、token 与成本。
- 输出 `calibration`（逐条目标在两路与候选池的排名）与 `decision`（按 Q1～Q8 台账）。
- 报告写 `eval/rag-reports/rag-rerank-<timestamp>.json`，控制台打印对比表与判定。

验证：用临时 stub reranker + 假 Key 跑通流程（仅结构验证，报告 `embedding` 不得标为 `model` 结论）；报告字段齐全、D4 缺池查询被单列。

## 阶段 3：门禁与脚本（依赖 T5）

### T6 实现 `RagRerankEvalGateTest` 与基线

- 同时要求 `DESKTOP_PG_RUNTIME` 与真实 Key（embedding 或 rerank）才运行；缺失跳过。
- 断言 D1～D2、报告结构完整、`decision` 存在；基线存在时仅对 `hybrid` 做回退防护（Recall@5 / doc_hit_rate ≤ -2pp、p95 ≤ +20% + 25ms）。
- 新增 `eval/baselines/rag-rerank-baseline.json`，`-Deval.updateBaseline=true` 先校验再写。
- 不对“reranker 必须提升”做硬断言。

验证：缺 Key 时跳过且不写基线；基线模式或模型名不同时打印跳过原因。

### T7 编写 `scripts/run-rag-rerank-eval.ps1`

- 自动定位 `DESKTOP_PG_RUNTIME`（同 `run-rag-eval.ps1` 的 `build/pg-runtime` 兜底）。
- 运行前检查 embedding / rerank Key；缺失时打印缺口清单并以非 0 退出，不启动 Maven。
- 支持 `-UpdateBaseline`，运行 `-Dtest=RagRerankEvalRunner,RagRerankEvalGateTest`。
- 不改 `run-rag-eval.ps1`。

验证：在无 Key 环境执行，输出明确缺口且未生成报告；临时设置假 Key 与不可达 URL 时得到可读网络/鉴权错误，不写结论。

## 阶段 4：真实实测（依赖 Key 到位）

### T8 前置检查与真实运行

- 确认 `RAG_EVAL_EMBEDDING_KEY`、`RAG_EVAL_RERANK_KEY` 可用；记录模型名与调用日期。
- 先跑校准，检查 D3（≥80% 目标两路 >5）与 D4（全部进候选池）。
- D3 不满足：调整干扰项与查询措辞后重跑；**不改阈值**。
- D3 满足后跑 A/B 全量，输出报告与 `decision`。

验证：报告含模型名、日期、数据集规模、逐条校准与 Q1～Q8 台账；结论唯一。

### T9 记录结论

- spec 第 8 节写入环境、模型、规模、指标、成本、判定；无 Key 则只写缺口。
- 结论为“建议启用”时，写出 P5-07-4 候选范围（开关、超时、降级、成本上限、埋点、灰度），但本任务不实施。

## 阶段 5：文档与回归

### T10 同步文档

- `docs/phases/phase5/plan.md`：P5-07 下新增 P5-07-3 小节（含结论或缺口）。
- `docs/UPDATE_PLAN.md`：P5-07 状态行、第 12 节执行顺序、后续批次表。
- `docs/COMPLETED_WORK.md`：新增 8.19。
- `docs/architecture/04-context-rag.md`：第 2 节补难例口径与 reranker 对照结果。
- `docs/architecture/10-quality-evaluation.md`：第四层补充难例、成本与判定阈值。

验证：文档口径与报告一致；未实测时不写指标数字。

### T11 回归验证

- 后端全量 `mvn test`：新增用例不因缺 Key 失败，其余不回归。
- `EvalGate,RuntimeEvalGate` 通过。
- `RagEvalGateTest` 与 `rag-baseline.json` 指标不变（确认未污染 P5-07-2）。
- `git diff --stat` 仅含测试作用域、数据集、脚本与文档。

验证：命令输出与实际一致；记录用例数、跳过数与基线对比结果。
