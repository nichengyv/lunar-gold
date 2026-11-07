# 系统设计文档（Agent Gateway v3）

> 当前基线：Batch F（批 76），并已完成双端改造 PR-01、PR-03、PR-04。快速状态看 `STATUS.md`，运维启动看 `operations.md`。

---

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│  前端（Vue 3 + Pinia + Element Plus + CodeMirror 6）            │
│  frontend/src/                                                  │
│  ├─ views/ChatView.vue（对话主视图）                            │
│  ├─ layouts/AppLayout.vue（工作区切换 + 全局状态）              │
│  ├─ stores/（chat / project / theme）                          │
│  ├─ components/WorkbenchDock.vue（右侧 dock：变更/资料/文件/追踪/计划/监控）│
│  ├─ components/FileEditorPane.vue（CodeMirror 编辑器）         │
│  ├─ components/PendingChangesCard.vue（审查卡片 + inline diff） │
│  ├─ components/ApprovalCard.vue（审批卡，120s 倒计时）          │
│  └─ api/（agent / workspace / fileChanges / http）             │
└─────────────────────────────────────────────────────────────────┘
                              ↓ HTTP + SSE
┌─────────────────────────────────────────────────────────────────┐
│  后端（Spring Boot 4.1.2 + Java 21 + Spring AI 2.0.2）         │
│  src/main/java/com/capsule/agentkey/                           │
│  ├─ controller/（AgentChat / Workspace / Project / FileReview / │
│  │              Mcp / McpTool / McpHealth / Agent / Chat）      │
│  ├─ service/                                                    │
│  │  ├─ ReActAgentService（核心 ReAct 循环）                     │
│  │  ├─ SpringAiAgentClient（唯一模型层，streamChat + fallback） │
│  │  ├─ CommandService（终端沙箱：白名单+eval拦截+git拦截+敏感路径）│
│  │  ├─ ScopeConstraintService（D1 范围约束，拦截文件类工具）     │
│  │  ├─ ChatService（会话 CRUD + aggregateStats 追踪聚合）      │
│  │  ├─ ToolApprovalRegistry（审批注册 / 超时 / 会话级 always）   │
│  │  ├─ ToolRegistry（能力门控 + 注册 + 权限过滤 + 埋点）       │
│  │  ├─ SessionMemoryExtractionService（每 8 轮自动提炼会话事实）│
│  │  ├─ ContextSummaryService（滚动摘要）                        │
│  │  ├─ EmbeddingService（Spring AI Embedding）                  │
│  │  ├─ DocumentService / VectorStoreService（RAG）             │
│  │  └─ FileReviewService（撤销/保留 PendingFileChange）        │
│  ├─ tool/（内置工具：WriteFile / EditFile / RunCommand / ...）  │
│  ├─ mcp/                                                        │
│  │  ├─ McpToolBridge（ApplicationRunner，启动初始化 MCP server）│
│  │  ├─ McpToolAdapter（MCP Tool → BaseTool 适配器）             │
│  │  └─ McpHealthMonitor（@Scheduled 30s 探活 + 熔断 COOLING）   │
│  ├─ repository/（JPA：ChatSession / ChatMessage / ...）        │
│  ├─ entity/（ChatSession / ChatMessage / PendingFileChange /   │
│  │           AgentMcpServerStatus / AgentProject / AgentRule /   │
│  │           AgentPolicy / ChatToolTrace / ...）                 │
│  ├─ advisor/SessionMemoryAdvisor（Spring AI Advisor，摘要注入） │
│  └─ config/（Security / Capability / Actuator / CORS）         │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  外部依赖                                                        │
│  ├─ PostgreSQL（dev JPA update；prod Flyway + validate）       │
│  ├─ Spring AI ChatModel（多 provider 兼容，当前 deepseek 默认） │
│  ├─ 固定版本 MCP filesystem（stdio，默认关闭）                 │
│  └─ Tavily（web_search，可选）                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 核心数据流

### 2.1 ReAct 循环（单次对话）

```
用户发送消息
  ↓
AgentChatController.sendStream()
  ├─ chatService.createOrGetCurrentSession()  ← 没有就新建
  └─ reactAgentService.execute(chatSession, message, permission, reasoning, model)
      ↓
      [循环 start → thinking → tool call → tool result → repeat → finish]
      ├─ ScopeConstraintService.parseAndApply(userPrompt)  ← D1 范围约束解析
      ├─ buildInitialSystemPrompt()
      │     └─ 拼接：工作区说明 + 权限约束 + 任务指令 + 最近摘要 + 会话自动提炼记忆 + 范围约束声明
      ├─ SpringAiAgentClient.streamChat(   ← D2 fallback 编排入口
      │     SystemMessage, UserMessage, ChatOptions { model, temperature, toolCallbacks })
      │     ├─ 主模型零 chunk 失败 → 迭代 fallbackModelsFor() 互补模型列表
      │     ├─ 流式 token 逐 chunk → 前端 SSE
      │     └─ 工具调用时自动 ToolCallback.call()
      │         └─ ToolRegistry.dispatch(toolName, args, ctx)
      │             ├─ 权限过滤（permission < EXECUTE → 拒绝）
      │             ├─ ScopeConstraintService.checkToolCall()  ← D1 拦截
      │             ├─ 内置工具：WriteFileTool / EditFileTool / RunCommandTool / ...
      │             │   ├─ RunCommandTool → CommandService.execute()
      │             │   │   └─ 四道安全防线 + shell 包装器友好拦截
      │             │   └─ 写入类 → WorkspaceContext.recordFileChange() + PendingFileChange 落库
      │             ├─ MCP 工具：mcpClient.callTool()
      │             │   └─ McpHealthMonitor 熔断中 → 直接拒绝
      │             └─ Timer: agent.tool.execution{tool, source, result}.publishPercentiles(0.5,0.95,0.99)
      │
      ├─ 所有工具结果 → 拼成下一轮 UserMessage → 再调 streamChat
      ├─ 直到模型 finish_reason=stop 或 maxTurns 超上限
      ├─ 完整响应 → 落 ChatMessage(trace JSON)
      ├─ SessionMemoryExtractionService.onAssistantMessage()  ← B2 每 8 轮触发提炼
      └─ 通知前端：done + durationMs + segments[setup/tools/answer]
```

### 2.2 追踪面板数据聚合

```
前端进入 dock「追踪」tab
  ↓
GET /api/agent/sessions/{id}/stats
  ↓
ChatService.aggregateStats(sessionId, userId)
  ├─ 查 ChatSession + 该 session 所有 ChatMessage（ORDER BY seq ASC）
  ├─ 过滤 role=ASSISTANT → 每条 parse trace JSON
  ├─ ToolAccumulator：按 name 分组 → calls/success/failure/totalCostMs → P95（排序法）
  ├─ FileAccumulator：按 filePath 分组 → additions/deletions/最新 diff
  ├─ Timeline：每条 assistant 的 tools[] 展平成有序时间线
  └─ Overview：轮次 / 工具调用 / 成功率 / 变更文件数 / 耗时三段
  ↓
返回 ChatSessionStats { overview, tools, files, timeline, timeBreakdown }
```

### 2.3 用户/项目维度可观测面板（Batch D3，PR-01 隔离）

```
前端 dock「监控」tab
  ↓
GET /api/agent/metrics
  ↓
AgentChatController.metrics(projectId?)
  └─ 遍历 meterRegistry.find("agent.tool.execution").timers()
      ├─ 仅保留当前 userId；传 projectId 时同时校验归属并过滤项目
      ├─ 按 tool tag 分组
      ├─ 聚合：calls / p50Ms / p95Ms / p99Ms / maxMs / successRate / sources
      └─ summary：totalCalls / uniqueTools / successRate / 加权 P95
```

### 2.4 审批流

```
工具调用命中 ToolApprovalRegistry.riskOf（内置写入/删除/命令及 MCP 写入/删除）
  ↓
ToolApprovalRegistry.create(userId, sessionId, tool, args, risk)
  ↓
SSE: approval_required { approvalId, tool, preview, risk, expiresAt(120s) }
  ↓
前端 ApprovalCard → 倒计时 → 批准/拒绝/本会话总是允许
  ↓
POST /api/agent/approvals/{approvalId}
Body: { decision: APPROVE|REJECT, remember: NONE|SESSION }
  ↓
registry.resolve() → 唤醒 ReAct 循环继续
  ↓
（120s 未操作或断连 → 后端自动 reject → SSE approval_resolved）
```

### 2.5 文件变更审查流

```
Agent 写文件（WriteFileTool / EditFileTool）
  ↓
1. WorkspaceContext.recordFileChange(path, action, additions, deletions, diffText, source)
     → 内存 accumulator（同文件多次改只保留最新）
2. FileReviewService.createPendingChange(...)
     → PendingFileChange 实体落库（status=PENDING, turnId 关联）
3. SSE done 事件后 → 前端 fetchPendingChanges → 输入框上方浮现「N 个文件改动待审查」
4. Batch D4：PendingChangesCard 支持 inline diff 展开 + 单文件撤回/保留按钮
```

---

## 3. 工具体系

### 3.1 权限与审批

工作权限按「审批方式」分三档（与 Claude Code 的 default / acceptEdits / bypassPermissions、Cursor 的 ask / 白名单 / 全自动对齐）：

| 档位 | 写文件 | 执行命令 | 能力 |
|---|---|---|---|
| `MANUAL` 手动审批（默认） | 需确认 | 需确认 | 读写 + 执行 |
| `AUTO` 自动审批 | 自动通过 | 普通命令自动通过，破坏性命令需确认 | 读写 + 执行 |
| `FULL` 完全权限 | 自动通过 | 自动通过 | 读写 + 执行 |
| `READ_ONLY` 只读（旧版兼容，UI 已不提供） | ❌ 工具不暴露 | ❌ 工具不暴露 | 只读 |

审批拦截点：`ReActAgentService` 在工具执行前判断 `permission.requiresApproval(risk, command)`，命中则挂起等
`ToolApprovalRegistry` 的决策（120s 超时自动拒绝）。MCP 写入/删除类工具及带 MCP fallback 的内置写工具即使处于 `AUTO` / `FULL` 也必须审批，除非用户已对该工具选择本会话「总是允许」。前端卡片展示的是后端整理的
`ApprovalPreview`（路径 / 命令 / 增删行数 / 截断 diff），不再下发工具入参 JSON 原文。

### 3.2 工具注册

```java
@ApplicationRunner
public class McpToolBridge implements ApplicationRunner {
    // MCP_ENABLED=false（默认）时不创建 MCP client，也不进入工具注册
    // 启动后逐个初始化 MCP server → mcpClient.listTools()
    //   → 9 个内置等价工具注册为 fallback，独有工具正常注册
    //   → 按风险声明 MCP_READ / MCP_WRITE，cloud 缺少能力时 ToolRegistry 拒绝注册
    //   → 仅注册成功才建立 fallback 映射；工具名：mcp_<serverName>_<toolName>
    // 初始化失败 → log.warn + continue，不阻塞其他 server 和主启动链
}
```

内置工具通过 `BaseTool.requiredCapability()` 声明部署能力：工作区文件工具要求 `LOCAL_WORKSPACE`，`run_command` 要求 `COMMAND_EXEC`；普通云端工具默认不限制部署模式。运行时全局开关 `McpGlobalSwitch` 默认也为关闭，启用 MCP client 后仍需在设置页打开才能执行外部工具。

---

## 4. 数据库主要表

Flyway 当前到 V14。V13 为 `pending_file_change` 增加 `turn_id`，V14 补建该表；但全新库按版本顺序执行时 V13 会先于建表语句执行 ALTER，该缺陷尚未修复，由 PR-06 通过前置幂等预检或全新安装基线处理。dev 关闭 Flyway、使用 `ddl-auto=update`，prod 启用 Flyway 并使用 `validate`。

| 表 | 关键列 | 用途 |
|---|---|---|
| `chat_session` | id, user_id, project_id, model, permission, reasoning_level, context_summary, summary_until_id, status | 会话 |
| `chat_message` | id, session_id, seq, role(USER/ASSISTANT/SYSTEM), content, trace(TEXT JSON), attachments | 消息 + trace 聚合数据 |
| `agent_project` | id, user_id, root_path, default_permission, name | 工作区 |
| `pending_file_change` | id, project_id, session_id, user_id, file_path, action, source, old_content(TEXT), new_content(TEXT), diff_text(TEXT), additions, deletions, status(PENDING/REVERTED/ACCEPTED), turn_id | 文件变更审查流 |
| `agent_mcp_server_status` | id, server_name, status(UP/COOLING_DOWN/FAILED), cooldown_until_ms, last_error, last_heartbeat_at, failure_count | MCP 熔断状态（内存重建）|
| `agent_rule` | id, user_id, project_id, session_id, scope(BUILTIN/GLOBAL/PROJECT/SESSION), name, instruction, builtin, enabled | Agent 指令库（会话自动提炼结果也存这里，scope=SESSION, builtin=true）|
| `document` / `document_chunk` | ... | RAG |

---

## 5. 前端状态管理

| Store | 关键字段 | 关键方法 |
|---|---|---|
| `chat` | sessions[], currentSessionId, dockTab('changes'\|'docs'\|'file'\|'trace'\|'plan'\|'metrics'), pendingChanges[], pendingApprovals[], model, permission, reasoningLevel, isProcessing | sendStream(), openFile(), openDock(), fetchPendingChanges(), revertChange(), acceptAll(), dismissApproval() |
| `project` | projects[], currentProjectId, currentRootPath | load(), switchProject(), createProject() |
| `theme` | isDark | toggle() |

### 审批显示逻辑（已修复）
`currentApproval = chat.pendingApprovals.find(a => a.sessionId === chat.currentSessionId && !a.resolved)`
→ 加了 `!a.resolved` 跳过已处理卡片，否则 Array.find 永远返回第一张已批准的卡，后续新审批被挡。

---

## 6. 可观测性

| 指标 | 来源 | 维度 |
|---|---|---|
| `agent.tool.execution` | ToolRegistry 手动埋点 | tool, source(builtin/mcp/fallback), result(success/fail/error)；已配置 publishPercentiles(0.5,0.95,0.99) |
| `gen_ai_client_operation_seconds` | Spring AI ObservationRegistry | gen_ai_operation_name, gen_ai_model_provider |
| `gen_ai_client_token_usage_input` | 同上 | 同上 |
| `gen_ai_client_token_usage_output` | 同上 | 同上 |

端点：`/actuator/prometheus`（匿名放行）、`/actuator/health`（匿名放行）、`/api/agent/metrics`（需登录，Batch D3；PR-01 起按当前 userId 过滤，可选 projectId 做归属校验与项目过滤）
