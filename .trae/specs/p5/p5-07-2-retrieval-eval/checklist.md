# P5-07-2 检索质量量化与 reranker 决策 — 验收清单

> 状态：已完成
> 只勾选实际验证过的项；未运行的项保留并注明原因

## 数据集

- [x] 语料 ≥3 篇、≥24 个切片，单篇 ≥6 个切片。（3 篇 / 24 切片 / 每篇 8 个）
- [x] 查询 ≥20 条，五类齐全：semantic、exact、mixed、cross-lingual、noise。（22 条）
- [x] `relevantChunkIds` 无悬空引用；每条查询至少 1 个相关切片（noise 除外）。
- [x] `exact` 类查询的 concepts 不含对应编号 / 术语，能真实暴露向量漏召回。

## 评测器

- [x] 未设置 `DESKTOP_PG_RUNTIME` 时整类跳过，不报错、不拖慢默认测试。
- [x] 设置后一次跑完 vector / lexical / hybrid 三配置。
- [x] 指标包含 Recall@3、Recall@5、MRR@5、nDCG@5、doc_hit_rate、context_tokens、p95_latency_ms。
- [x] 报告含 `embedding` 模式、数据集规模、按 category 分列与 `hybrid - vector` 差值。
- [x] 连续两次运行指标完全一致。（p95 为计时值，其余完全一致）

## 门禁

- [x] hybrid Recall@5 ≥ vector。
- [x] exact 类 hybrid Recall@5 严格高于 vector。
- [x] noise 查询全部被门槛拒绝。
- [x] 相对基线超阈值时失败，并打印具体类别与指标。
- [x] `scripts/run-rag-eval.ps1 -UpdateBaseline` 能更新基线。

## 结论

- [x] 按预先写定的 4.5 规则给出“启用 / 不启用模型 reranker”的结论。（不启用）
- [x] 结论有对应数字支撑，未用定性描述替代指标。
- [x] 若结论为“需要 reranker”，明确记为下一任务，不在本任务实现。（结论为不需要，未转入下一任务；reranker 保留为后续可选）

## 文档

- [x] spec 第 8 节记录环境、规模、指标、偏差与未执行项。
- [x] `plan.md`、`UPDATE_PLAN.md`、`COMPLETED_WORK.md`、`04-context-rag.md`、`10-quality-evaluation.md` 已同步。
- [x] 明确写明“模拟向量不测量 bge-m3”，未夸大结论。

## 回归

- [x] 后端全量 `mvn test` 通过。（295 项，0 失败、0 错误、13 跳过）
- [x] `EvalGate,RuntimeEvalGate` 通过。
- [x] 生产代码、迁移、前端与运行时依赖无改动。
- [x] `.gitignore` 已忽略 `eval/rag-reports/`。
