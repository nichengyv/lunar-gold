# P5-07-3 难例检索评测与模型 reranker 对照 — 验收清单

> 对应规格：[spec.md](spec.md) ｜ 任务：[tasks.md](tasks.md)
> 勾选规则：每项必须有可复现证据（命令输出、报告路径、文件 diff），不能只凭“看起来对”。

## A. 数据集

- [x] A1 `eval/rag-datasets/hard-chunks.jsonl` 存在，干扰切片 ≥ 16，字段与 `chunks.jsonl` 一致（实际 160 条）
- [x] A2 `eval/rag-datasets/hard-queries.jsonl` 存在，`hard` ≥ 12 条、`noise` ≥ 2 条（12 + 2）
- [x] A3 每条 `hard` 查询只有 1 个标注正确切片，且该切片真实存在（加载器校验）
- [x] A4 每条 `hard` 查询与目标切片 concept 交集为空（加载校验通过）
- [x] A5 每条 `hard` 查询的目标切片正文确实回答该问题，干扰切片不覆盖答案（构造时逐条核对；D3 校准 83.33% 亦为佐证）
- [x] A6 `chunks.jsonl`、`queries.jsonl`、`eval/baselines/rag-baseline.json` 未被修改（`git status` 显示新增 `eval/rag-datasets/` 与 `eval/baselines/rag-rerank-baseline.json`，原两文件不在 diff 中）
- [ ] A7 故意构造重复 `chunkId` / 缺失标注 / concept 交集三类错误时，加载器均给出可读失败信息，修复后通过（未逐条做负向注入；加载器已有对应校验分支）

## B. 评测器

- [x] B1 真实 embedding provider 只在有 Key 时启用；无 Key 时不调用模型、不产出报告
- [x] B2 真实 provider 使用 `SpringAiEmbeddingModelConfig` + `embedForResponse`，未修改生产类
- [x] B3 `RerankClient` 请求体含 `model/query/documents/top_n/return_documents`，能解析 `results[].relevance_score` 与 `meta.tokens.input_tokens`
- [ ] B4 `RerankClient` 超时 / 非 2xx / 数量不匹配时抛出可读异常，且异常与日志不含 Key（代码有分支，未用 stub 构造失败用例实测）
- [x] B5 候选池为 20：向量、词法各 20 → RRF 融合 20；`hybrid` 截断 5，`hybrid+rerank` 精排 20 取 5
- [x] B6 两配置使用同一门槛（向量 0.5 / 0.55，词法 0.3 / 0.35）与同一 `rrf-k=60`
- [x] B7 报告含 `top1_hit_rate`、`mrr@5`、`ndcg@5`、`recall@5`、`noise`、`retrieval_p95_ms`、`rerank_p95_ms`、`total_p95_ms`
- [x] B8 报告 `cost` 含 `embedding_query_tokens`（本次为实际值、`estimated=false`）、`rerank_tokens`、`rerank_calls`，单价未配置时货币字段为 null
- [x] B9 报告 `calibration` 逐条给出目标切片在 vector / lexical / hybrid / hybrid+rerank 的排名与是否进候选池
- [x] B10 报告 `decision` 逐条输出 Q1～Q8 的阈值、实测值、通过与否及最终结论
- [ ] B11 结构自检使用 stub reranker 时不把结果标成真实模型结论（未做 stub 自检；本次全程真实模型）

## C. 数据有效性与判定

- [x] C1 D1（规模）与 D2（唯一标注 / concept 交集）在加载时硬校验通过
- [x] C2 D3 校准结果 ≥ 80% 的 hard 查询目标在两路排名均 > 5（83.33%）；未下调阈值
- [x] C3 D4 目标未进候选池的查询缺口为 0；判定口径已按 D4 单列字段（`in_candidate_pool`）
- [x] C4 结论为“保持不启用”，属三选一
- [x] C5 未通过的 Q1（+8.34pp）、Q6（rerank p95 482.81ms）均在报告中写出编号与实测值
- [x] C6 报告已注明 12 条 hard 查询分辨率（1 条 ≈ 8.33pp），spec 8.6 同步说明
- [x] C7 Q6（rerank p95 482.81ms / 总 p95 508.06ms）与 Q7（1093.33 tokens/查询，1.18×）均有实测值
- [x] C8 结论为“保持不启用”，未实施生产 reranker

## D. 门禁与脚本

- [x] D1 `RagRerankEvalGateTest` 在缺 `DESKTOP_PG_RUNTIME` 或 Key 时跳过（`EnabledIfEnvironmentVariable` + `Assumptions`）
- [x] D2 门禁只断言数据结构、D 组有效性与 `hybrid` 基线回退，不断言 reranker 必须提升
- [x] D3 `eval/baselines/rag-rerank-baseline.json` 已由 `-Deval.updateBaseline=true` 写出（先校验后写）
- [x] D4 基线中的模型名与本次不一致时跳过基线对比并说明原因（代码分支，`BAAI/bge-m3` 本次一致）
- [x] D5 `scripts/run-rag-rerank-eval.ps1` 缺 Key 时打印缺口清单、`exit 2`、未启动 Maven
- [x] D6 有 Key 时一次跑完 runner + gate；`-UpdateBaseline` 生效
- [x] D7 `scripts/run-rag-eval.ps1` 未被修改

## E. 前置缺口与诚实性

- [x] E1 未实测阶段 spec 第 8 节只写缺口；实测后改为真实数据，未保留占位结论
- [x] E2 未用 `SimulatedEmbedding` 的结果替代真实 embedding 结论（报告 `embedding.mode=model`，query tokens 175 为实际 usage）
- [x] E3 未用“无 reranker”结果冒充 `hybrid+rerank` 结果（真实 12 次 rerank 调用、13120 tokens）
- [x] E4 未把部分结果写成结论（Key 缺失时 runner 直接抛前置缺口）
- [x] E5 本次 token 全部为实际 usage，`query_tokens_estimated=false`

## F. 回归与范围

- [x] F1 后端全量 `mvn test`：296 项，**2 项失败均为既有迁移计数断言**（`FlywayV18MigrationAcceptanceTest` 期望 latest=18、`DesktopPgVectorIntegrationTest` 期望 latest=15，实际均为已提交的 V19），与本任务改动无关；其余 0 失败、0 错误、3 跳过
- [x] F2 `EvalGate,RuntimeEvalGate` 通过
- [x] F3 `RagEvalGateTest` 通过且 `rag-baseline.json` 未改动
- [x] F4 本次未改生产 Java 代码、迁移、前端、`rag.*` 默认值（`git status` 中的 `DocumentService` / `RagCandidate` / `Reranker` 变更属未提交的 P5-07-2 任务，非本任务）
- [x] F5 本任务新增/修改仅测试作用域、`eval/` 数据集与脚本、`.trae/specs/` 与 docs
- [x] F6 文档同步完成：`plan.md`、`UPDATE_PLAN.md`、`COMPLETED_WORK.md`（8.19）、`04-context-rag.md`、`10-quality-evaluation.md`

## G. 结论归档

- [x] G1 spec 第 8 节记录执行日期、模型名、数据集规模、校准结果、A/B 指标、成本、Q1～Q8 台账与结论
- [x] G2 报告文件路径已记录（`eval/rag-reports/rag-rerank-20261115-183608-796.json`，目录已忽略）
- [x] G3 实测阶段已用真实数据替换“未实测 + 缺口清单”

## 实测记录（2026-11-15）

- 运行命令：`mvn -q -Deval.updateBaseline=true -Dtest=RagRerankEvalRunner,RagRerankEvalGateTest test`（设置 `DESKTOP_PG_RUNTIME`、`RAG_EVAL_EMBEDDING_KEY`、`RAG_EVAL_RERANK_KEY`）。
- 结果：runner 与 gate 均通过；基线写入 `eval/baselines/rag-rerank-baseline.json`。
- 指标与判定：见 spec 第 8 节（D3 83.33%、D4 0；top-1 +8.34pp、nDCG@5 +17.76pp、rerank p95 482.81ms；结论“保持不启用”）。
