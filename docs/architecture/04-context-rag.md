# 04 上下文、RAG 与提示词（部分完成）

> 更新日期：2026-11-13
> 状态：文档入库、pgvector 单路向量召回、上下文组装、提示词版本化已落地；混合检索与 rerank 未开始
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

当前只有**单路向量召回**：

- PostgreSQL + pgvector，余弦距离，HNSW 索引；
- SQL 使用 `embedding <=> CAST(? AS vector)` 计算相似度，按分数降序取 topK；
- 严格限定项目范围，避免跨项目泄漏；
- 两级分数：`min-score` 过滤低质量片段，`gate-score` 决定是否注入上下文；
- 返回结果包含文档 id、文件名、相关度与片段内容，前端展示为“出处”。

没有关键词检索、没有融合排序、没有模型 rerank。P5-07 才计划混合检索与引用收益评估，`halfvec` 量化由体积/精度数据决定。

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
| 单路向量召回 | 先保证可用的语义检索，混合检索与 rerank 由评测数据驱动 |
| 提示词资源化 | 可回滚、可按场景区分、可纳入评测 |
| 哈希 + 必备段落校验 | 防止误改或漏改导致行为漂移 |
| 上下文分层与预算 | 控制 token 成本，避免长会话失控 |
| 大结果落盘 | 保住上下文窗口，同时保留可追溯证据 |

---

## 6. 现状边界

- 只有向量召回，专有名词、代码符号命中率有限（P5-04/P5-07 待做）。
- 没有引用强制覆盖校验，无法证明“答案确实用了检索片段”。
- 分块策略固定，未按文件类型或代码结构优化。
- 摘要与记忆抽取依赖模型，缺少质量评测。
- 提示词只有 v1，没有真实模型 AB 对比数据。

---

## 7. 面试要点

1. **为什么用 pgvector？** 桌面端要内嵌、离线、少依赖；PostgreSQL 同时承担业务数据与向量，减少一套存储。
2. **为什么现在不做混合检索？** 没有评测数据证明收益；先建基线，再按数据加功能。
3. **如何控制上下文成本？** 分层注入 + 条数/字符预算 + 大结果落盘 + 推理档位。
4. **提示词怎么做工程化？** 文件化、版本化、哈希校验、场景选择、热重载与事件记录。
