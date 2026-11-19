# 04 上下文、RAG 与提示词（部分完成）

> 更新日期：2026-11-15
> 状态：文档入库、pgvector + pg_trgm 混合召回、RRF 融合、chunk 级引用、上下文组装与提示词版本化已落地；检索质量已用标注语料量化，模型 reranker 经评测判定不启用
> 关联：[总架构](README.md)、[Agent Runtime](01-agent-runtime.md)、[数据持久化](08-data-persistence.md)

---

## 1. 知识库入库管线

```text
上传文档
  → DocumentService 校验并保存 Document（PENDING）
  → 事务提交后异步触发 DocumentProcessingService
  → 解析（DocumentParserService）→ 分块
  → 保存 DocumentChunk
  → EmbeddingService 生成向量
  → VectorStoreService 写入 pgvector
  → 状态置 READY（失败记录原因并支持重试）
```

关键文件：

| 文件 | 职责 |
|---|---|
| `service/DocumentService.java` | 上传、检索入口 `retrieveContext`、阈值配置 |
| `service/DocumentProcessingService.java` | 异步解析/分块/向量化/入库与失败重试 |
| `service/DocumentParserService.java` | 文本抽取 |
| `service/EmbeddingService.java` | 向量化调用 |
| `service/VectorStoreService.java` | pgvector 写入与检索 |
| `entity/Document.java`、`DocumentChunk.java` | 文档与分块 |

---

## 2. 检索

当前为**双路召回 + RRF 融合**，`rag.hybrid-enabled=false` 时退化为原来的单路向量：

- **向量路**：PostgreSQL + pgvector，余弦距离，HNSW 索引；SQL 使用 `embedding <=> CAST(? AS vector)` 计算相似度，按分数降序取 topK。
- **词法路**：pg_trgm `word_similarity(query, content)` 在切片原文上召回，覆盖编号、函数名、配置项等精确词；pg_trgm 不可用或 SQL 失败时降级为纯向量。
- **融合（fusion）**：`RrfRankFusion` 按 `1/(k + rank)` 叠加两路排名（`rag.rrf-k` 默认 60），按 `chunkId` 去重并保留两路最高分；只使用排名，不读取切片内容。模型 reranker 若未来引入，属于融合后的独立内容精排环节，不与本层混用命名。
- 两路各自保留两级分数：`min-score` 过滤低质量片段，`gate-score` 决定是否注入上下文（向量 0.55 / 词法 0.35）；点名资料跳过整体门槛。
- 严格限定项目范围，避免跨项目泄漏；返回结果包含文档 id、文件名、`chunkId`、`chunkIndex`、相关度与片段内容，前端展示为“出处”。

### 2.1 离线检索质量（P5-07-2）

`eval/rag-datasets/` 提供 3 篇文档 / 24 个切片 / 22 条查询（semantic 8、exact 6、mixed 3、cross-lingual 2、noise 3），`RagRetrievalEvalRunner` 在真实内嵌 PG + Flyway 上对比三种配置：

| 配置 | Recall@5 | MRR@5 | nDCG@5 | doc_hit_rate | context_tokens | p95 |
|---|---:|---:|---:|---:|---:|---:|
| vector | 68.42% | 68.42% | 68.42% | 68.42% | 24.89 | 1.63ms |
| lexical | 31.58% | 31.58% | 31.58% | 31.58% | 11.42 | 1.09ms |
| hybrid | 100.00% | 100.00% | 100.00% | 100.00% | 36.32 | 2.70ms |

- `exact` 类：vector 0% → hybrid 100%；`semantic` / `mixed` / `cross-lingual`：vector 与 hybrid 均 100%，lexical 0%；`noise` 3 条三配置全部拒绝。
- 模拟向量由 concept 哈希生成（`embedding=simulated`），只验证“语义信号存在时融合是否优于单路”，**不测量 bge-m3**；设置 `RAG_EVAL_EMBEDDING_KEY` 可切真实模型模式复跑。
- 结论：hybrid Recall@5 提升 31.58pp、exact 类提升 100pp、noise 全拒绝，按预写规则**不启用模型 reranker**；`halfvec`（V20）仍按体积与精度数据决定。

### 2.2 难例与真实模型 reranker 对照（P5-07-3）

P5-07-2 的语料 hybrid 已达 100%，无法证明精排增量，因此另建难例集并换真实模型复跑（不改动 P5-07-2 的数据集、基线与门禁）：

- **数据集**：`eval/rag-datasets/hard-chunks.jsonl` 160 个干扰切片（答案埋在长文中的 12 个目标切片 + 多主题干扰 + 逐查询同款措辞干扰），`hard-queries.jsonl` 12 条 hard / 2 条 noise；hard 查询唯一正确切片且与目标 concept 无交集。
- **候选池**：两路各取 20 → RRF 融合 20；`hybrid` 截断 top-5，`hybrid+rerank` 把 20 条交给 `BAAI/bge-reranker-v2-m3` 取 top-5。embedding 为真实 `BAAI/bge-m3`（1024 维）。
- **校准与 A/B**（12 条 hard）：目标两路排名 >5 占比 83.33%、候选池缺口 0；top-1 8.33% → 16.67%、Recall@5 16.67% → 41.67%、MRR@5 12.50% → 27.78%、nDCG@5 13.59% → 31.35%、noise 2/2 两配置全拒绝。
- **延迟与成本**：检索 p95 42.29ms 不变，rerank p95 482.81ms、总 p95 508.06ms；rerank 13120 tokens（1093.33/查询，约候选正文 1.18×）。
- **结论**：按跑数据前写定的阈值，top-1 提升 +8.34pp（要求 ≥ +10pp）与 rerank p95 482.81ms（要求 ≤300ms）不通过 → **保持不启用**。边界：本集查询为同义改写，词法路仅 3/12 条有候选，实际对照接近“向量单路 top-20 → rerank”；换模型或换语料需重跑，不跨版本外推。

---

## 3. 上下文组装

`ReActAgentService.buildMessages` 负责把多类上下文拼进请求。顺序与内容大致为：

1. 系统提示词（按场景从 `PromptCatalog` 取，见第 4 节）；
2. Agent 规则（用户自定义规则，`AgentRule`）；
3. 项目指令（`ProjectService`）；
4. 工作区文件树（桌面端有工作区时）；
5. 会话引用上下文与历史消息；
6. 滚动摘要 `contextSummary`；
7. RAG 片段（`useRag` 为真且过阈值）；
8. 文件上下文与图片上下文；
9. 当前用户提问。

预算控制：

- 历史消息按条数与字符预算裁剪（`historyCharBudget`），不足 2 条时忽略预算；
- RAG 历史数量由推理档位控制；
- 超大工具结果落盘，只回填预览与路径；
- token 上限由 `AgentRun` 预算与策略共同约束。

设计原则：**上下文按“必要性”分层注入，而不是把所有信息一次性塞给模型**。这既控制成本，也减少无关信息干扰。

---

## 4. 提示词版本化

提示词已从代码中抽离，放在 `src/main/resources/prompts/`：

```text
prompts/
  index.json
  agent/system-v1.md
  coding/system-v1.md
  tool-notes/mcp-v1.md
  tool-notes/terminal-v1.md
```

`index.json` 每条登记：`id`、`version`、`scenario`、`path`、`sha256`、`default`。

`PromptCatalog` 的职责：

- 启动时加载并校验 **SHA-256** 与**必备段落**；
- 按 `agent.prompt.scenario.<name>.version` 选择版本，未知版本启动失败；
- 支持热重载 `POST /api/admin/prompts/reload`（仅本机），失败保留旧快照；
- 运行事件记录 `PROMPT_LOADED`，便于线上问题与评测对账。

场景区分：

| 场景 | 用途 | 必备段落（示例） |
|---|---|---|
| `agent` | 通用问答、闲聊、资料问答 | 核心原则、文件修改规则、任务计划、通用对话要求 |
| `coding` | 有工作区的编程任务 | 核心原则、代码理解、文件修改规则、任务计划、完成与验证 |

`coding` 场景明确要求：停止调用工具只是候选完成；修改后应运行可用诊断或验证；未验证必须写“未验证”；不得编造命令与测试结论。

---

## 5. 设计取舍

| 取舍 | 理由 |
|---|---|
| 双路召回 + RRF，而不是先上模型 rerank | 词法补精确编号/符号，融合不增加调用成本；24 切片 / 22 查询实测 Recall@5 68.42% → 100%；难例集换真实 bge-m3 + reranker 复跑后 top-1 仅 +8.34pp、rerank p95 482.81ms，未达预写阈值，模型 reranker 仍无可证明增量 |
| 模拟向量做离线评测 | 无 Key 也可确定性回归；明确不测量 bge-m3，结论只覆盖“融合是否优于单路” |
| 提示词资源化 | 可回滚、可按场景区分、可纳入评测 |
| 哈希 + 必备段落校验 | 防止误改或漏改导致行为漂移 |
| 上下文分层与预算 | 控制 token 成本，避免长会话失控 |
| 大结果落盘 | 保住上下文窗口，同时保留可追溯证据 |

---

## 6. 现状边界

- 检索质量只用 3 篇文档 / 24 个切片 / 22 条查询的标注语料验证，不代表线上分布；模拟向量不测量 bge-m3，真实模型收益需在有 Key 的环境复跑并更新 `eval/baselines/rag-baseline.json`。
- 混合检索会多注入切片，`context_tokens` 68.42% → 100% 召回的同时从 24.89 升到 36.32（估算值），token 成本与召回率需要按场景权衡。
- 没有引用强制覆盖校验，无法证明“答案确实用了检索片段”。
- 分块策略固定，未按文件类型或代码结构优化。
- 摘要与记忆抽取依赖模型，缺少质量评测。
- 提示词只有 v1，没有真实模型 AB 对比数据。
- PDF 页码仍未提供（解析器未保留版式），`halfvec`（V20）未实施。

---

## 7. 面试要点

1. **为什么用 pgvector？** 桌面端要内嵌、离线、少依赖；PostgreSQL 同时承担业务数据与向量，减少一套存储。
2. **为什么先做混合检索而不是模型 reranker？** 精确编号与符号是向量路的确定性缺口，词法 + RRF 能直接补上且无额外调用成本；实测 Recall@5 68.42% → 100%、exact 类 0% → 100%，模型 reranker 没有可证明的增量，因此不做。
3. **如何控制上下文成本？** 分层注入 + 条数/字符预算 + 大结果落盘 + 推理档位。
4. **提示词怎么做工程化？** 文件化、版本化、哈希校验、场景选择、热重载与事件记录。
