# 02 模型接入与韧性（部分完成）

> 更新日期：2026-11-13
> 状态：多提供商接入、流式调用、超时/重试/熔断、token 计量已落地；真实模型评测未接入
> 关联：[总架构](README.md)、[Agent Runtime](01-agent-runtime.md)、[安全](09-security.md)

---

## 1. 定位

模型接入层把“具体模型服务商”隔离在一个接口之后，让 Runtime 只依赖抽象：

```text
ReActAgentService / ContextSummaryService
        │
        ▼
   AgentClient（接口）
        │
        ▼
SpringAiAgentClient ──► Spring AI ChatModel ──► 服务商 HTTPS
        │
        ├── ProviderCircuitBreaker（熔断）
        └── 超时 / 退避重试 / fallback
```

核心文件：

| 文件 | 职责 |
|---|---|
| `client/AgentClient.java` | 统一接口：流式、同步、视觉 |
| `client/SpringAiAgentClient.java` | 生产实现，封装 Spring AI |
| `client/AgentStreamCallback.java` | 文本增量、思考、工具调用、usage、错误回调 |
| `client/ProviderCircuitBreaker.java` | 按 Provider 隔离的熔断 |
| `service/ApiKeyService.java` | BYOK 密钥存取 |
| `config/SpringAiChatModelConfig.java` | ChatModel 构建与配置 |

---

## 2. 接口设计

`AgentClient` 提供三类能力：

| 方法 | 用途 |
|---|---|
| `streamChat(...)` | 主对话流式调用，回调文本/工具/usage |
| `callSync(...)` | 需要完整结果的内部调用（如摘要、抽取） |
| `callVision(...)` | 图片理解 |

抽象的目的有三个：

1. Runtime 与具体 SDK 解耦，便于替换或测试；
2. 评测可以注入 `ScriptedAgentClient`，不依赖外部模型；
3. 韧性策略集中在实现层，不污染业务循环。

---

## 3. 韧性与成本

| 机制 | 行为 | 边界 |
|---|---|---|
| 流式空闲超时 | 长时间无 chunk 判定超时 | 与总时长兜底配合 |
| 非流式总时长兜底 | 防止请求永久挂起 | 以配置为准 |
| 指数退避重试 | 瞬时错误重试；**已输出内容不重试** | 避免重复输出 |
| Provider 级熔断 | 连续失败后短路，按 Provider 隔离 | 不影响其他提供商 |
| fallback | 显式配置的备用模型 | 默认不隐式切换 |
| token 预算 | 单 Run 超限硬中断并映射 `TOKEN_BUDGET_EXCEEDED` | 仅统计模型 usage |
| 大结果落盘 | 超大工具结果写文件，只回填预览与路径 | 控制上下文体积 |

已知观察：Spring AI SDK 自身带内部重试，原始 HTTP 请求数可能明显高于外层尝试次数，形成“重试放大”。这是已知行为，未在 SDK 内关闭。

---

## 4. BYOK 与密钥

- 用户自带密钥（BYOK），按用户存储，接口层不返回明文。
- 云端模型调用需要登录；桌面端仍使用用户配置的密钥。
- 密钥不写入仓库、不进入诊断包；诊断导出按白名单脱敏。
- 相关管理端点：`ApiKeyController`、`SettingsController`。

---

## 5. 与 Runtime 的边界

| 属于模型层 | 属于 Runtime |
|---|---|
| 请求构造、鉴权、超时重试、熔断 | 轮次、工具分发、审批、完成判定 |
| usage 上报 | token 累加、预算中断与落库 |
| 流式分片 | 把分片转成 SSE 事件与消息 |

这样划分后，更换模型或提供商不需要改 ReAct 循环。

---

## 6. 现状边界

- 未接入外部真实模型的自动化评测；真实模型只保留本地/定时抽样路径。
- Runtime 的 token 指标在脚本评测中是确定性口径，不代表计费口径。
- 熔断参数按 Provider 配置，缺少真实故障注入的长期观测数据。
- 视觉能力仅覆盖图片输入，未做多模态工具调用。

---

## 7. 面试要点

1. **为什么抽 `AgentClient`？** 解耦 SDK、可测试、可替换；评测用脚本客户端驱动真实循环。
2. **重试为什么不能无脑做？** 已输出内容重试会导致重复文本与重复工具调用，因此只对未输出阶段重试。
3. **熔断为什么按 Provider？** 单提供商故障不应拖垮全部模型选择。
4. **如何控制成本？** token 计量 + 单 Run 预算 + 大结果落盘 + 推理档位控制上下文规模。
