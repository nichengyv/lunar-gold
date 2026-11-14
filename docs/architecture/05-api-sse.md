# 05 后端 API 与事件流（已完成）

> 更新日期：2026-11-13
> 状态：控制器、SSE、断线续传、鉴权与能力门控已落地
> 关联：[总架构](README.md)、[前端工作台](06-frontend.md)、[安全](09-security.md)

---

## 1. 分层

```text
HTTP 请求
  → filter（JWT / 本机令牌）
  → controller（参数、归属校验、能力门控）
  → service（业务编排）
  → repository / tool / client
  → SSE 或 JSON 响应
```

约定：

- 所有资源访问都带用户维度校验（`findByIdAndUserId` 或 `getOwnedSession`）。
- 客户端断开即取消后端执行，避免继续消耗 token。
- 状态变化既走 SSE，也落 `agent_run_event`，保证可重放。

---

## 2. 控制器清单

| 控制器 | 职责 |
|---|---|
| `AgentChatController` | 对话流式端点、会话消息、运行事件、停止/取消、审批结果 |
| `AuthController` | 注册、登录、找回密码、验证码 |
| `DesktopTokenController` | 桌面端本机令牌换取 |
| `DocumentController` | 知识库文档上传、列表、删除 |
| `ProjectController` | 项目、工作区路径、项目指令 |
| `WorkspaceController` | 工作区文件树、文件读写、文件审查与撤回 |
| `FileReviewController` | 待处理文件变更与按轮撤回 |
| `AgentRuleController` | 用户自定义 Agent 规则 |
| `SettingsController` | 模型、主题、上下文等设置 |
| `ApiKeyController` | BYOK 密钥管理 |
| `McpController` | MCP 开关与状态 |
| `PromptAdminController` | 提示词热重载（仅本机） |
| `MetaController` | 部署模式、版本、能力、下载声明 |
| `HealthController` | 健康检查 |

---

## 3. 对话与 SSE 协议

主端点：`POST /api/agent/chat/stream`，`text/event-stream`。

请求携带：会话、项目、模型与推理档位、权限档位、`useRag`、附件与图片、`clientRequestId`。

主要事件：

| 事件 | 含义 |
|---|---|
| `run_id` | 本次运行 id，用于续传与审批 |
| `content` | 文本增量 |
| `thinking` | 思考过程增量（若模型提供） |
| `tool_call` | 工具调用开始（名称、参数、风险） |
| `tool_result` | 工具结果（成功/失败、文件变化） |
| `approval_required` | 需要用户审批（含工具、参数、超时） |
| `plan` | 计划更新或收口 |
| `sources` | RAG 出处（文档、文件名、相关度、片段） |
| `usage` | token 使用 |
| `completion` | 交付状态与原因（`COMPLETED/UNVERIFIED/INCOMPLETE`） |
| `no_progress` | 无进展停止原因 |
| `done` | 流结束 |
| `error` | 错误（含错误码与可读原因） |

前端按事件类型分流更新消息、工具卡片、计划条、审批卡与完成状态。

---

## 4. 断线续传

三个层次共同保证“刷新不丢本轮”：

1. **检查点**：后端按固定间隔发送模型文本检查点事件；
2. **事件持久化**：关键事件写 `agent_run_event`，`seq` 递增；
3. **补偿接口**：`GET /api/agent/runs/{runId}/events?sinceSeq=` 支持 `Last-Event-ID` 语义；`runs/running` 用于发现当前仍在运行的 run。

前端恢复路径：

```text
刷新/切换会话
  → 读历史消息与 trace（含 completion）
  → 若有 running run，拉取 run 事件重放
  → 重新订阅实时 SSE
```

---

## 5. 运行与取消

- 创建 run 时写 `QUEUED → RUNNING`。
- 需要审批时置 `WAITING_APPROVAL`。
- 正常收尾 `SUCCEEDED`，异常 `FAILED`，用户停止或连接断开 `CANCELLED`。
- 客户端断开或 `reason=CANCELLED` 时，后端主动取消执行并写终态，不留 `RUNNING`。
- 服务重启把遗留 `QUEUED/RUNNING` 标 `FAILED/PROCESS_RESTARTED`。

---

## 6. 能力门控

`MetaController` 公开当前部署模式、版本与能力集合；前端据此隐藏或禁用入口，后端再用 `CapabilityService.require(...)` 兜底。

原则：**前端隐藏只是体验，后端拒绝才是边界**。

---

## 7. 错误与幂等

- 相同 `clientRequestId` 不会重复创建消息或运行，避免网络重试导致重复执行。
- 错误返回包含错误码与可读原因，前端可展示“重试/导出诊断”。
- SSE 错误只回调一次，避免前端重复渲染。

---

## 8. 现状边界

- 部分控制器端点缺少独立契约文档，以代码为准。
- 双实例下运行状态可见性与审批互斥未完全解决。
- 真实断网 10 秒的人工复核仍待补。
- 没有 OpenAPI 自动生成与契约测试。

---

## 9. 面试要点

1. **SSE 为什么不用 WebSocket？** 单向流式足够，HTTP 语义简单，易穿透代理与调试。
2. **断线怎么恢复？** 事件落库 + `seq` 重放 + 运行中 run 查询，三层配合。
3. **如何防止重复执行？** `clientRequestId` 幂等，创建即去重。
4. **能力门控为什么前后端都做？** 前端提升体验，后端保证安全。
