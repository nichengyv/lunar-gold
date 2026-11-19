# 08 数据与持久化（已完成）

> 更新日期：2026-11-19
> 状态：PostgreSQL + pgvector、V1~V21 迁移、Run/Event/审批与客户端凭证持久化已落地
> 关联：[总架构](README.md)、[上下文与 RAG](04-context-rag.md)、[安全](09-security.md)

---

## 1. 选型

云端与桌面端**统一使用 PostgreSQL 方言**：

- 云端：外部 PostgreSQL；
- 桌面端：内嵌 PostgreSQL 16 + pgvector 运行时；
- 向量与业务数据同库，减少一套存储与同步逻辑；
- 迁移脚本两端共用 `classpath:db/migration`。

早期对比过 SQLite + sqlite-vec，最终因迁移与向量 SQL 改动最小选择 PG 路线。

---

## 2. 迁移清单

| 版本 | 主题 |
|---|---|
| V1 | 初始表结构 |
| V2 | 知识库 |
| V3 | 向量存储（pgvector） |
| V4 | 项目 |
| V5 | 项目指令 |
| V6 | 项目工作区路径 |
| V7 | 会话摘要 |
| V8 | Agent 策略 |
| V9 | 消息内容索引 |
| V10 | 消息 trace |
| V11 | Agent 规则 |
| V12 | 用户设置 |
| V13 | 待处理文件变更 turn_id |
| V14 | 待处理文件变更表 |
| V15 | 用户邮箱唯一索引 |
| V16 | `agent_run` / `agent_run_event` |
| V17 | `tool_approval` |
| V18 | `agent_run` token 统计 |
| V19 | 审批决策来源 |
| V20 | API 客户端凭证 |
| V21 | 审批客户端标识 |

原则：

1. 已执行迁移不直接修改，避免 Flyway 校验失败；
2. 新字段使用下一条未占用版本，实施前检查迁移目录与真实库历史；
3. 桌面端删除数据目录等于重置，不做跨版本数据兼容承诺之外的处理。

---

## 3. 实体与关系

```text
User ──< Project ──< ChatSession ──< ChatMessage
  │                      │
  │                      └──< AgentRun ──< AgentRunEvent
  │                                  └──< ToolApproval
  ├──< UserApiKey
  ├──< UserSetting
  ├──< AgentRule
  └──< Document ──< DocumentChunk（含 vector）
Project ──< PendingFileChange
```

| 实体 | 说明 |
|---|---|
| `User` / `UserApiKey` / `UserSetting` | 账号、BYOK 密钥、偏好 |
| `Project` | 项目、工作区路径、项目指令 |
| `ChatSession` / `ChatMessage` | 会话与消息，消息带 trace |
| `AgentRun` | 一次运行：状态、token、结果 JSON |
| `AgentRunEvent` | 运行事件，`seq` 递增，可重放 |
| `ToolApproval` | 审批决策与过期时间 |
| `PendingFileChange` | 按轮文件变更，支持审查与撤回 |
| `Document` / `DocumentChunk` | 知识库文档与向量分块 |
| `AgentRule` | 用户自定义规则 |

---

## 4. 关键设计

| 设计 | 原因 |
|---|---|
| 事件表 + `seq` | 支持断线续传与审计，SSE 是传输不是唯一事实来源 |
| Run 与 Event 分表 | 运行状态查询轻量，事件可增长 |
| token 只在终态落库 | 避免逐轮重复写，保证计费口径一致 |
| 审批落库 | 桌面重启后可恢复，过期即拒绝 |
| 文件变更单独表 | 支持按轮审查与撤回，不依赖聊天正文 |
| 向量与业务同库 | 桌面端部署简单，事务与备份一致 |

---

## 5. 一致性与恢复

- 服务启动把遗留 `QUEUED/RUNNING/WAITING_APPROVAL` 标为 `FAILED/PROCESS_RESTARTED`，并写终态事件。
- 重启残留的 `PENDING` 审批不再恢复为可决策：未过期标 `REJECTED/SYSTEM`，已过期标 `REJECTED/TIMEOUT`。
- 更新升级前备份整个数据目录，失败自动回滚。
- 数据库迁移失败不得删除工作区、会话、知识库或上传文件。

---

## 6. 现状边界

- 任务级 ChangeSet 已复用 `PendingFileChange` + Run/Event 实现跨 Run 聚合；未新增独立 Task/ChangeSet 表，命令产生的未记录外部副作用不承诺回滚。
- 没有分区、归档或事件清理策略；长期运行的事件表会持续增长。
- 没有真实大数据量下的索引与查询压测。
- 双实例共享数据库时，审批互斥与内存 Future 唤醒未解决。

---

## 7. 面试要点

1. **为什么不用 SQLite？** 向量检索、迁移与 SQL 兼容性成本更高；PG 两端统一，减少分叉。
2. **为什么事件要落库？** 断线续传、审计、评测断言都需要可重放的事实来源。
3. **token 为什么终态写一次？** 避免重复累加导致成本数据失真。
4. **迁移怎么保证安全？** 不改历史脚本、实施前校验版本占用、失败不破坏用户数据。
