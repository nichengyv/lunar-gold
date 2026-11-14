# P5-05 LSP 诊断闭环 - Product Requirements Document

## Overview

- **Summary**：为 Lunar Gold Agent 增加变更文件诊断闭环。默认使用进程内 javac 采集 Java 文件诊断，可选通过 JSON-RPC stdio 接入外部 language server；诊断限量进入下一轮上下文，经 SSE 下发并与 runId/turnId 关联，同时参与 P5-13 的无进展判定与完成证据。
- **Purpose**：让「改完文件」不再等于「任务完成」。编译错误可见、可回灌模型、可如实展示，并为 P5-06 Verify、P5-14 任务级交付提供诊断证据。
- **Target Users**：本地开发者、工作区任务使用者、后续评测与交付面板。

## Goals

1. 抽象 `DiagnosticsProvider`，默认 `JavacDiagnosticsProvider` 离线可用。
2. 提供可选 `LspDiagnosticsProvider`，配置后可通过 stdio 接入 java-language-server / ts-server。
3. `write_file` / `edit_file` 成功后采集变更文件诊断，限量（默认 20 条 / 8KB）注入下一轮 prompt。
4. 新增 SSE `diagnostics` 事件与 Run Event，携带 runId、turnId、文件版本与诊断明细。
5. 相同诊断连续出现时触发 P5-13 无进展停止；诊断有错误时不得判定为完成。
6. provider 不可用、超时或文件类型不支持时降级，不阻塞工具结果与主对话。

## Non-Goals

- 不实现 P5-06 的 `.lunargold/verify.json`、命令验证与重试编排。
- 不实现 P5-04 代码索引、repo map。
- 不新增数据库表或迁移；诊断通过 Run Event 与消息 trace 持久化。
- 不实现完整 LSP 语义能力（补全、跳转、重命名）、多根工作区、语言服务器自动下载。
- 不做完整诊断面板；P5-05 只下发事件与紧凑状态行，详细验证摘要留给 P5-14。
- 不修改桌面端打包、发布脚本与 CI 工作流。

## Background & Context

- `Capability.LSP` 枚举已存在但全仓未使用；无 `DiagnosticsProvider`、无 javac/LSP 客户端。
- 工具唯一执行出口为 `ToolRegistry.dispatch → executeTool`；`ReActAgentService` 每轮工具后调用 `WorkspaceContext.drainFileChanges()`，诊断应挂在该批次之后。
- `AgentStreamCallback` 已有 `default` 方法先例（`onUsage`），新增回调不会破坏既有实现。
- 前端 `api/sse.ts` 是事件白名单 switch，新增事件必须同步 case、hooks 类型、`types/*` 与消息 UI。
- `CompletionStatus` 已有 `COMPLETED / UNVERIFIED / INCOMPLETE`，P5-13 当前把「文件已改但无证据」标为 `UNVERIFIED`。
- 现有 runtime 评测数据集没有写入 `.java` 的用例，诊断接入不应改变既有基线。
- 配置遵循项目约定：嵌套配置用 `@ConfigurationProperties`，禁止用 `@Value` 绑定嵌套结构。

## Functional Requirements

### FR-1 诊断模型与 Provider 抽象

新增包 `com.capsule.agentkey.diagnostics`：

- `Diagnostic` record：`Severity severity`（`ERROR / WARNING / INFO`）、`String file`（工作区相对路径，统一 `/`）、`int line`、`int column`、`String message`、`String source`（provider 名称）。
- `DiagnosticsProvider` 接口：
  - `String name()`；
  - `ProviderResult diagnose(Path workspaceRoot, List<Path> files, long timeoutMs)`；
  - `ProviderResult` record：`boolean available`、`List<Diagnostic> diagnostics`；
  - 实现类不得向外抛异常；内部失败返回 `available=false`。
- `DiagnosticsResult` record：`String provider`、`boolean available`、`int analyzedFiles`、`List<Diagnostic> diagnostics`、`int totalCount`、`boolean truncated`；提供 `hasErrors()` 与 `formatForPrompt()`。

### FR-2 JavacDiagnosticsProvider（默认）

文件：`src/main/java/com/capsule/agentkey/diagnostics/JavacDiagnosticsProvider.java`

- 使用 `ToolProvider.getSystemJavaCompiler()`，进程内编译，离线可用；编译器缺失时 `available=false`。
- 只处理 `.java` 文件；忽略不存在的文件与删除动作。
- 编译参数：`-proc:none`、`-d <临时输出目录>`、`-sourcepath`（工作区 `src/main/java`、`src/test/java`，以及目标文件推导出的源码根）、`-classpath`（工作区 `target/classes`、`target/test-classes`、`build/classes/java/main`、`lib/*.jar`，存在才加入）。
- 用 `DiagnosticCollector<JavaFileObject>` 收集；只保留与本次分析文件对应的诊断，避免把依赖源码的错误算到本次变更上。
- 在独立 daemon 线程执行，`timeoutMs` 内未完成视为不可用并降级；临时输出目录在 `finally` 删除。

### FR-3 LspDiagnosticsProvider（可选）

文件：`src/main/java/com/capsule/agentkey/diagnostics/LspDiagnosticsProvider.java`

- 仅当 `agent.diagnostics.provider=lsp` 时启用。
- 从配置读取 `command`、`args`、`languageId`；`command` 为空、进程启动失败、初始化失败或超时 → `available=false`，不抛异常。
- 协议：`Content-Length` 头 + JSON-RPC 2.0。流程：`initialize` → `initialized` → 对每个文件 `textDocument/didOpen` → 等待 `textDocument/publishDiagnostics`（总超时受 `timeoutMs` 限制）→ `textDocument/didClose` → `shutdown` → `exit`。
- 解析 `params.diagnostics[]` 的 `range.start.line/character`、`severity`（1=ERROR、2=WARNING，其余 INFO）、`message`，转换为 `Diagnostic`；文件路径按 URI 还原并转为工作区相对路径。
- 子进程退出时清理流与进程，避免泄漏。

### FR-4 DiagnosticsService 编排

文件：`src/main/java/com/capsule/agentkey/diagnostics/DiagnosticsService.java`

- 构造注入 `AgentDiagnosticsProperties` 与 `List<DiagnosticsProvider>`，按 `properties.getProvider()` 选择；未知值降级为 `javac` 并在日志中告警。
- `collect(Path workspaceRoot, List<FileChange> changes)`：
  - `enabled=false`、工作区为空或无可用变更文件 → 返回 `available=false` 的空结果，不影响主流程。
  - 只取 `create / modify` 且存在、扩展名受 provider 支持的文件；去重；默认最多 5 个文件（`maxFiles`）。
  - 调用 provider，捕获所有异常。
  - 截断：先按 `maxItems`（默认 20）限制条数，再按 `maxChars`（默认 8192）限制 `formatForPrompt()` 文本长度；任一截断置 `truncated=true`。
- `fileVersion(Path file)`：返回 `lastModified + ":" + size`，用于关联诊断与文件版本。

### FR-5 配置

新增 `src/main/java/com/capsule/agentkey/config/AgentDiagnosticsProperties.java`：

- `@Component @ConfigurationProperties(prefix = "agent.diagnostics")`；
- 字段：`enabled=true`、`provider=javac`、`maxItems=20`、`maxChars=8192`、`timeoutMs=20000`、`maxFiles=5`、嵌套 `Lsp { command="", args="", languageId="java" }`。

`src/main/resources/application.yml` 在 `agent:` 下新增：

```yaml
  diagnostics:
    enabled: ${AGENT_DIAGNOSTICS_ENABLED:true}
    provider: ${AGENT_DIAGNOSTICS_PROVIDER:javac}   # javac / lsp / none
    max-items: ${AGENT_DIAGNOSTICS_MAX_ITEMS:20}
    max-chars: ${AGENT_DIAGNOSTICS_MAX_CHARS:8192}
    timeout-ms: ${AGENT_DIAGNOSTICS_TIMEOUT_MS:20000}
    max-files: ${AGENT_DIAGNOSTICS_MAX_FILES:5}
    lsp:
      command: ${AGENT_DIAGNOSTICS_LSP_COMMAND:}
      args: ${AGENT_DIAGNOSTICS_LSP_ARGS:}
      language-id: ${AGENT_DIAGNOSTICS_LSP_LANGUAGE_ID:java}
```

### FR-6 ReAct 接入

文件：`src/main/java/com/capsule/agentkey/service/ReActAgentService.java`、`tool/WorkspaceContext.java`

- `WorkspaceContext` 增加：
  - `DiagnosticsResult diagnostics()` / `setDiagnostics(...)`；
  - `String diagnosticsFingerprint()` / `setDiagnosticsFingerprint(...)`；
  - `String diagnosticsFileVersion()` / `setDiagnosticsFileVersion(...)`。
- 在每批 `drainFileChanges()` 之后、`onFileChange` 推送之后：
  - 调用 `diagnosticsService.collect(workspacePath, fileChanges)`；
  - 结果写入 `WorkspaceContext`；
  - `callback.onDiagnostics(result)`；
  - 当 `analyzedFiles > 0` 时向 `messages` 追加一条 `user` 消息，内容为 `result.formatForPrompt()`，确保下一轮模型可见；已诊断通过时同样注入简短结论「未发现编译错误」，避免模型重复编译。
- 重复诊断：对诊断明细生成稳定指纹（路径 + 行 + 严重级 + 归一化消息，排序后拼接）。
  - 与上一批相同且非空 → `repeatedDiagnostics++`，否则重置为 1；
  - 达到 `agent.loop.no-progress.max-repeats`（复用现有配置，0 表示关闭）→ `stopForNoProgress(callback, workspaceChanged, new NoProgress("REPEATED_DIAGNOSTICS", null, repeatedDiagnostics, stagnantRounds, message))`。
- 完成证据：`candidateCompletion(...)` 增加诊断判断：
  - 最近一次诊断 `available && hasErrors()` → `INCOMPLETE`，reason `DIAGNOSTICS_FAILED`；
  - 最近一次诊断 `available && analyzedFiles > 0 && !hasErrors()` → `COMPLETED`，reason `DIAGNOSTICS_PASSED`；
  - 无诊断、provider 不可用或仅非 Java 文件 → 保持现有 `UNVERIFIED / VERIFICATION_MISSING` 语义。

### FR-7 SSE、Run Event 与前端

- `ReActAgentService.StreamCallback` 新增 `default void onDiagnostics(DiagnosticsResult result) {}`。
- `AgentRunEvent.EventType` 新增 `DIAGNOSTICS`。
- `AgentChatController` 实现 `onDiagnostics`：
  - payload 字段：`runId`、`turnId`、`provider`、`available`、`analyzedFiles`、`totalCount`、`truncated`、`at`、`diagnostics[]`（`file/line/column/severity/message`）；
  - `appendRunEvent(runId, EventType.DIAGNOSTICS, payload)`；
  - `sendEvent(emitter, "diagnostics", json)`；
  - 最新结果写入消息 trace（`traceDiagnostics`），随 `onComplete` 的 trace JSON 落库。
- 前端：
  - `types/chat.ts` 新增 `DiagnosticsInfo`；
  - `types/api.ts` 的 `UiMessage` 与 `MessageTrace` 增加 `diagnostics?: DiagnosticsInfo`；
  - `api/sse.ts` 新增 `onDiagnostics` hook 与 `case 'diagnostics'`；
  - `stores/chat.ts` 实时回调写入 `aiMsg.diagnostics`，历史 trace 还原时同步映射；断线恢复的事件重放分支同样解析 `diagnostics`；
  - `ChatMessage.vue` 在完成提示附近增加紧凑状态行：有错误显示「N 个编译错误」并可悬停查看首条；无错误显示「编译检查通过」；不可用显示「诊断不可用」。不新增独立大面板。

## Non-Functional Requirements

- **NFR-1 不阻塞**：诊断任何失败都不得让工具调用失败或中断 ReAct；单个 provider 调用受 `timeoutMs` 约束。
- **NFR-2 可重复**：同一文件、同一内容产生的诊断指纹稳定；Javac 结果不依赖网络。
- **NFR-3 限量**：进入 prompt 的诊断文本不超过 `maxChars`，条数不超过 `maxItems`。
- **NFR-4 可回放**：SSE 事件与 Run Event 载荷一致，刷新/重连后可还原最近一次诊断。
- **NFR-5 可配置**：provider 切换、阈值、超时均可通过 `application.yml` + 环境变量调整。
- **NFR-6 无新依赖**：只用 JDK `javax.tools` 与现有 Jackson，不新增 Maven 依赖。

## Constraints

- Java 21、Spring Boot 4.1.2、Jackson 2；不引入新库。
- 工作区文件访问必须经 `WorkspaceFileService` 的路径校验；诊断只读文件，不写工作区。
- 嵌套配置使用 `@ConfigurationProperties`，禁止 `@Value` 绑定嵌套结构。
- 前端样式只引用 `tokens.css` 变量，不新增硬编码 hex。
- 不新建第二套 SSE 解析、审批卡或空态组件。

## Assumptions

- 变更文件已落盘，`FileChange.path()` 为工作区相对路径。
- 工作区可能是 Java、前端或混合项目；非 Java 文件由 javac provider 跳过，不影响原有完成语义。
- 外部 language server 由用户自行安装并配置启动命令；P5-05 不负责下载与版本管理。

## Acceptance Criteria

### AC-1 Provider 抽象与 javac 默认实现

- **Given** 工作区内有一个语法错误的 `.java` 文件
- **When** `JavacDiagnosticsProvider.diagnose(root, [badFile], 20000)` 执行
- **Then** 返回 `available=true` 且至少一条 `ERROR`，`file` 为相对路径、`line >= 1`；合法文件返回无 `ERROR`。

### AC-2 Provider 不可用降级

- **Given** `agent.diagnostics.provider=lsp` 但 `lsp.command` 为空，或 javac 编译器不可用
- **When** `DiagnosticsService.collect(...)` 被调用
- **Then** 返回 `available=false`，不抛异常，工具调用与对话继续。

### AC-3 限量与截断

- **Given** 一个文件产生超过 20 条诊断，或格式化文本超过 8KB
- **When** `collect` 返回结果
- **Then** `diagnostics.size() <= 20`、`formatForPrompt().length() <= 8192` 且 `truncated=true`。

### AC-4 诊断进入下一轮 prompt

- **Given** 模型调用 `write_file` 写入有错误的 Java 文件，随后再次调用模型
- **When** 第二次 `streamChat` 收到 messages
- **Then** messages 含一条包含 `【变更文件诊断】` 与错误信息的 `user` 消息。

### AC-5 SSE 与 Run Event

- **Given** 一次工具调用产生诊断
- **When** SSE 流处理
- **Then** 收到 `diagnostics` 事件，载荷含 `runId`、`turnId`、`provider`、`available`、`diagnostics`；`agent_run_event` 中存在同载荷的 `DIAGNOSTICS` 记录。

### AC-6 连续相同诊断触发无进展

- **Given** 连续多轮修改同一 Java 文件但诊断指纹完全相同
- **When** 重复次数达到 `agent.loop.no-progress.max-repeats`
- **Then** 推送 `no_progress` 事件，reason 为 `REPEATED_DIAGNOSTICS`，本轮以 `INCOMPLETE` 收尾。

### AC-7 完成证据

- **Given** 变更文件诊断可用且无 ERROR
- **When** 模型停止调用工具
- **Then** completion 为 `COMPLETED`、reason `DIAGNOSTICS_PASSED`。
- **Given** 诊断可用且存在 ERROR
- **Then** completion 为 `INCOMPLETE`、reason `DIAGNOSTICS_FAILED`。

### AC-8 构建与回归

- **Given** 全部改动完成
- **When** 执行后端编译/测试与前端类型检查
- **Then** `mvn compile`、`mvn test`、`vue-tsc --noEmit` 全部通过；既有 runtime 评测基线不因诊断接入而变化（数据集无 `.java` 写入用例）。

## Verification Plan

1. `JavacDiagnosticsProviderTest`：合法文件、语法错误、非 Java、文件不存在。
2. `DiagnosticsServiceTest`：条数截断、字符截断、去重、provider 不可用、禁用开关。
3. `LspDiagnosticsProviderTest`：空命令降级、启动失败降级、JSON-RPC 消息解析（纯函数级）。
4. `ReActDiagnosticsIntegrationTest`：坏 Java 写入 → 诊断进入第二轮 messages；连续相同诊断 → `REPEATED_DIAGNOSTICS`；诊断错误 → `DIAGNOSTICS_FAILED`；干净诊断 → `DIAGNOSTICS_PASSED`。
5. 后端：`mvn -o -q compile`、`mvn -o -q test`；前端：`vue-tsc --noEmit`。
6. 更新 `docs/phases/phase5/plan.md` 的 P5-05 状态与完成情况，以及 `docs/COMPLETED_WORK.md` 对应条目。

## Required Approval

实施前需用户确认本规格。确认后按 `tasks.md` 顺序执行；若实现中发现与现有代码契约冲突，先记录并回到规格调整，不静默扩大范围。
