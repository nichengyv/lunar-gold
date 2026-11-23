# 03 工具、审批与沙箱（部分完成）

> 更新日期：2026-11-13
> 状态：内置工具、审批、命令/文件沙箱、MCP 桥接已落地；并行工具未开始
> 关联：[总架构](README.md)、[Agent Runtime](01-agent-runtime.md)、[安全](09-security.md)

---

## 1. 工具模型

所有工具实现 `BaseTool`，注册到 `ToolRegistry`。Runtime 只拿到“当前策略允许的工具列表”，工具本身负责参数解析与执行。

| 文件 | 职责 |
|---|---|
| `tool/BaseTool.java` | 工具契约：名称、描述、参数 schema、风险等级 |
| `tool/ToolRegistry.java` | 注册、按策略过滤、查找 |
| `tool/ToolResult.java` | 统一结果（成功/失败/内容/文件变化） |
| `tool/WorkspaceContext.java` | 工作区根目录与执行上下文 |

内置工具（13 个）：

| 分类 | 工具 |
|---|---|
| 文件读 | `ReadFileTool`、`ListDirTool`、`SearchFilesTool` |
| 文件写 | `WriteFileTool`、`EditFileTool`、`DeleteFileTool` |
| 执行 | `RunCommandTool` |
| 计划 | `UpdatePlanTool` |
| 信息获取 | `SearchTool`、`WebFetchTool`、`WeatherTool`、`CurrentTimeTool`、`CalculatorTool` |
| MCP | 通过 `McpToolAdapter` / `McpToolBridge` 动态注册 |

> 高风险的写与执行工具统一走审批；只读工具直接执行。

---

## 2. 审批流程

```text
Runtime 发现 tool 需要审批
   → ToolApprovalRegistry.request(...)
   → 落库 tool_approval（PENDING）+ 内存 Future
   → SSE 推送审批信息 / Run 置 WAITING_APPROVAL
   → 用户批准或拒绝（HTTP）
   → Registry.resolve(...) 决策落库并唤醒 Future
   → Runtime 继续执行或回灌拒绝原因
```

要点：

- **数据库权威 + 内存加速**：决策先写库，进程内 Future 只用于唤醒等待线程。
- **超时即拒绝**：过期审批启动时直接置 `REJECTED`，不会恢复成可批准状态。
- **会话级“总是允许”**：可按会话恢复，减少重复弹窗。
- **拒绝原因回灌模型**：模型能据此换方案，而不是静默失败。
- **跨实例边界**：两个后端实例各持内存 Future，跨实例唤醒与重复决策互斥未实现；桌面端单实例不受影响。

关键文件：`service/ToolApprovalRegistry.java`、`entity/ToolApproval.java`、`repository/ToolApprovalRepository.java`、V17 迁移。

---

## 3. 文件沙箱

`WorkspaceFileService` 保证所有文件操作落在工作区内：

1. 规范化路径并校验 `startsWith(workspaceRoot)`；
2. 拒绝 `..` 逃逸；
3. 对已存在路径做 `realpath` 校验，拒绝符号链接指向工作区外；
4. 新建路径也按父目录真实路径校验。

边界：不防“工作区内本身是恶意内容”的情况；执行命令与读取敏感文件仍依赖额外白名单。

---

## 4. 命令沙箱

`CommandService` 采用多层限制：

| 层 | 内容 |
|---|---|
| 程序白名单 | 只允许配置过的可执行程序 |
| 工作目录校验 | 必须在工作区内 |
| 参数校验 | 拦截解释器 `eval`、危险 `git` 子命令、敏感绝对路径 |
| 超时 | 到时终止整棵进程树 |
| 输出截断 | 防止超大输出撑爆上下文 |
| OS 级回收 | Windows Job Object，`KILL_ON_JOB_CLOSE`，内核回收进程树 |

进程树回收的意义：即使命令自己 `detach` 或拉起子进程，父进程结束时也不会留下孤儿进程。

关键文件：`service/CommandService.java`、`service/sandbox/ProcessSandbox.java`、`service/sandbox/WindowsJobObjectSandbox.java`。

边界：仅 Windows 实现 OS 级沙箱；macOS Seatbelt / Linux Landlock 暂缓。

---

## 5. MCP 接入

| 文件 | 职责 |
|---|---|
| `mcp/McpGlobalSwitch.java` | 全局开关，读取 `spring.ai.mcp.client.enabled`，默认关闭 |
| `mcp/McpToolBridge.java` | 启动时把 MCP server 工具注册进 `ToolRegistry` |
| `mcp/McpToolAdapter.java` | 把 MCP 工具适配为 `BaseTool` |
| `mcp/McpHealthMonitor.java` | 健康检查与熔断 |

安全约束：

- **白名单**：只连接 `allowed-servers` 中声明的 server。
- **全局开关**：关闭后调用直接拒绝，已注册工具仍存在但不暴露。
- **健康熔断**：`DOWN` 且在冷却期内直接拒绝，冷却后放行一次探测，避免每次调用都等超时。
- **去重与失败提示**：同名工具注册时去重，失败返回可读原因。

> **扩展规划（0.1.4 插件管理中心）**：当前 MCP 接入仅支持 stdio，由用户在设置页手填 command/args/env。0.1.4 规划升级为「插件中心」：传输层扩展现有 `McpClientFactory` 入参容纳 url/headers（不新造 transport 接口，MCP SDK 已提供 `ClientTransport` 抽象及 `StdioClientTransport`/`HttpClientTransport`/`SseClientTransport` 实现）；数据层 `mcp_server` 表加 `plugin_id`/`plugin_version` 两列（不重构 JPA 继承）；运行时抽 `InstallablePlugin` 接口，为未来指令模板/模型预设留扩展点；安装流程 schema-driven，用户只填凭证。详见 [../phases/phase8/plugin-center.md](../phases/phase8/plugin-center.md)。

---

## 6. 工具执行时序（单轮多工具）

```text
assistant.tool_calls = [call1, call2]
for call in calls:
    校验工具存在 + 权限
    需要审批 → 等待决策
    执行 → 收集 ToolResult / FileChange
    写入 tool 消息
下一轮把全部 tool 消息回灌模型
```

当前为串行执行。P5-08 计划让只读工具并行，写入与命令仍串行并审批。

---

## 7. 现状边界

- 工具串行，长任务时延有优化空间。
- 审批跨实例互斥未实现。
- 文件审查与按轮撤回依赖 `PendingFileChange`，跨 Run 的任务级整体撤回（P5-14）未开始。
- MCP server 进程由外部管理，其自身安全边界不在本项目控制内。

---

## 8. 面试要点

1. **审批为什么落库？** 桌面重启后待审批要能恢复；纯内存 Future 会丢状态。
2. **如何防止命令逃逸？** 白名单 + 参数拦截 + 工作目录校验 + 进程树回收，多层而非单点。
3. **符号链接怎么防？** 规范化 + `startsWith` + `realpath` 三重校验。
4. **MCP 挂了怎么办？** 健康监控熔断，DOWN 期间快速失败，冷却后单次探测恢复。
