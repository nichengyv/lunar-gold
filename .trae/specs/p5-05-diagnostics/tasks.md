# P5-05 LSP 诊断闭环 - Implementation Plan

## Task 1: 诊断模型与 Provider 抽象
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新建 `src/main/java/com/capsule/agentkey/diagnostics/Diagnostic.java`：`record Diagnostic(Severity severity, String file, int line, int column, String message, String source)`，`Severity` 含 `ERROR/WARNING/INFO`。
  - 新建 `diagnostics/DiagnosticsProvider.java`：`String name()`、`ProviderResult diagnose(Path workspaceRoot, List<Path> files, long timeoutMs)`、内嵌 `record ProviderResult(boolean available, List<Diagnostic> diagnostics)`。
  - 新建 `diagnostics/DiagnosticsResult.java`：`provider/available/analyzedFiles/diagnostics/totalCount/truncated`，实现 `hasErrors()` 与 `formatForPrompt()`（`【变更文件诊断】` 段落，provider 不可用与通过各有明确文案）。
- **Acceptance Criteria Addressed**: AC-1, AC-3
- **Test Requirements**:
  - `rule` TR-1.1: `mvn compile` 通过，新增类型无外部依赖。
  - `rule` TR-1.2: `formatForPrompt()` 对错误、通过、不可用三种状态输出不同且可断言的文案。

## Task 2: JavacDiagnosticsProvider
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `diagnostics/JavacDiagnosticsProvider.java`，`@Component`，`name()` 返回 `javac`。
  - 只处理 `.java`；`ToolProvider.getSystemJavaCompiler()` 为空时 `available=false`。
  - 构造 `-proc:none`、临时 `-d`、`-sourcepath`（`src/main/java`、`src/test/java`、目标文件源码根）、`-classpath`（`target/classes`、`target/test-classes`、`build/classes/java/main`、`lib/*.jar`，存在才加）。
  - `DiagnosticCollector` 收集，只保留分析文件的诊断；独立 daemon 线程 + `Future.get(timeoutMs)`；超时/异常降级为 `available=false`；`finally` 删除临时目录。
- **Acceptance Criteria Addressed**: AC-1, AC-2
- **Test Requirements**:
  - `rule` TR-2.1: 合法 Java 文件无 `ERROR`；语法错误文件至少一条 `ERROR` 且行号正确。
  - `rule` TR-2.2: 非 `.java` 与不存在文件返回空诊断，不抛异常。

## Task 3: LspDiagnosticsProvider
- [x] **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 1, Task 4
- **Description**:
  - 新建 `diagnostics/LspDiagnosticsProvider.java`，`@Component`，`name()` 返回 `lsp`。
  - `command` 为空时 `available=false`；已配置则启动 stdio 子进程，按 `Content-Length` + JSON-RPC 2.0 完成 `initialize → initialized → didOpen → 等待 publishDiagnostics → didClose → shutdown/exit`。
  - 解析 `params.diagnostics[]`，severity 1/2 映射 ERROR/WARNING，其余 INFO；URI 转工作区相对路径；总耗时受 `timeoutMs` 限制。
  - 任何启动/读写/解析失败都降级为 `available=false`；进程与流在 `finally` 清理。
- **Acceptance Criteria Addressed**: AC-2
- **Test Requirements**:
  - `rule` TR-3.1: `command` 为空返回 `available=false` 且不抛异常。
  - `rule` TR-3.2: 包内可见的解析方法对固定 JSON-RPC 消息返回正确 `Diagnostic` 列表（不依赖真实语言服务器）。

## Task 4: 配置属性与 application.yml
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新建 `config/AgentDiagnosticsProperties.java`：`@Component @ConfigurationProperties(prefix = "agent.diagnostics")`；字段 `enabled/provider/maxItems/maxChars/timeoutMs/maxFiles` 与嵌套 `Lsp{command,args,languageId}`。
  - `src/main/resources/application.yml` 的 `agent:` 下新增 `diagnostics:` 配置块，全部使用环境变量占位，默认 `provider=javac`。
- **Acceptance Criteria Addressed**: AC-2, AC-3
- **Test Requirements**:
  - `rule` TR-4.1: 启动/测试上下文能注入该配置 bean，默认值正确。
  - `rule` TR-4.2: 不引入 `@Value` 绑定嵌套结构。

## Task 5: DiagnosticsService 编排
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1, Task 2, Task 4
- **Description**:
  - 新建 `diagnostics/DiagnosticsService.java`，构造注入配置与 `List<DiagnosticsProvider>`；按 `provider` 选择，未知值回落 `javac`。
  - `collect(Path workspaceRoot, List<FileChange> changes)`：过滤 `create/modify`、存在、扩展名受支持；去重；最多 `maxFiles`；调用 provider 并捕获异常。
  - 先按 `maxItems`、再按 `maxChars` 截断 `DiagnosticsResult`，任一截断置 `truncated=true`。
  - 提供 `fileVersion(Path file)`（`lastModified:size`）与诊断指纹计算方法，供 ReAct 使用。
- **Acceptance Criteria Addressed**: AC-2, AC-3
- **Test Requirements**:
  - `rule` TR-5.1: 21 条诊断 → 返回 20 条且 `truncated=true`。
  - `rule` TR-5.2: 超长文本被截到 `maxChars` 以内。
  - `rule` TR-5.3: provider 抛异常/不可用 → `available=false`，不向上抛。
  - `rule` TR-5.4: `enabled=false` 或无受支持文件 → 空结果且 `available=false`。

## Task 6: WorkspaceContext 与 ReAct 接入
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 5
- **Description**:
  - `tool/WorkspaceContext.java` 增加诊断结果、诊断指纹、文件版本字段与 getter/setter。
  - `ReActAgentService` 构造注入 `DiagnosticsService`；在 `drainFileChanges()` 与 `onFileChange` 之后采集诊断，写入上下文并调用 `callback.onDiagnostics`。
  - `analyzedFiles > 0` 时向 `messages` 追加 `user` 消息（`formatForPrompt()`），使下一轮模型可见；通过时注入「未发现编译错误」。
  - 指纹与上一批相同且非空则累加，达到 `maxRepeatedToolCalls` 时 `stopForNoProgress`，reason `REPEATED_DIAGNOSTICS`。
  - `candidateCompletion` 使用最近诊断：有 ERROR → `INCOMPLETE/DIAGNOSTICS_FAILED`；可用且无 ERROR → `COMPLETED/DIAGNOSTICS_PASSED`；否则保持原语义。
- **Acceptance Criteria Addressed**: AC-4, AC-6, AC-7
- **Test Requirements**:
  - `rule` TR-6.1: 坏 Java 写入后，第二次 `streamChat` 的 messages 含 `【变更文件诊断】`。
  - `rule` TR-6.2: 连续相同诊断达到阈值 → `NoProgress.reason=REPEATED_DIAGNOSTICS` 且 completion 为 `INCOMPLETE`。
  - `rule` TR-6.3: 诊断有 ERROR → `DIAGNOSTICS_FAILED`；干净诊断 → `DIAGNOSTICS_PASSED`。
  - `rule` TR-6.4: 非 Java 变更的 completion 仍为 `UNVERIFIED/VERIFICATION_MISSING`。

## Task 7: SSE、Run Event 与消息 trace
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 6
- **Description**:
  - `ReActAgentService.StreamCallback` 增加 `default void onDiagnostics(DiagnosticsResult result) {}`。
  - `entity/AgentRunEvent.java` 的 `EventType` 增加 `DIAGNOSTICS`。
  - `controller/AgentChatController.java` 实现 `onDiagnostics`：组装 `runId/turnId/provider/available/analyzedFiles/totalCount/truncated/at/diagnostics`，`appendRunEvent(EventType.DIAGNOSTICS)`，发送 `diagnostics` SSE，并写入 `traceDiagnostics` 供 `onComplete` 落入消息 trace。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-7.1: 事件 payload 字段完整，SSE 与 Run Event 一致。
  - `rule` TR-7.2: 断线重连重放时 `diagnostics` 事件可被前端还原。

## Task 8: 前端诊断事件消费与紧凑展示
- [x] **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 7
- **Description**:
  - `frontend/src/types/chat.ts` 新增 `DiagnosticsInfo`；`types/api.ts` 的 `UiMessage`、`MessageTrace` 增加 `diagnostics?`。
  - `api/sse.ts` 增加 `onDiagnostics` hook 与 `case 'diagnostics'`。
  - `stores/chat.ts` 实时回调写入 `aiMsg.diagnostics`；历史 trace 还原与断线重放分支同步解析。
  - `ChatMessage.vue` 在完成提示附近增加紧凑状态行：有错误显示「N 个编译错误」，无错误显示「编译检查通过」，不可用显示「诊断不可用」；样式只用 `tokens.css` 变量。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-8.1: `vue-tsc --noEmit` 通过。
  - `rule` TR-8.2: 新事件不再触发「未知事件类型」告警；无硬编码颜色。

## Task 9: 测试
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2, Task 3, Task 5, Task 6
- **Description**:
  - 新增 `src/test/java/com/capsule/agentkey/diagnostics/JavacDiagnosticsProviderTest.java`。
  - 新增 `src/test/java/com/capsule/agentkey/diagnostics/DiagnosticsServiceTest.java`。
  - 新增 `src/test/java/com/capsule/agentkey/diagnostics/LspDiagnosticsProviderTest.java`。
  - 新增 `src/test/java/com/capsule/agentkey/service/ReActDiagnosticsIntegrationTest.java`，复用现有 `ScriptedAgentClient` 与 mock 依赖模式。
- **Acceptance Criteria Addressed**: AC-1 ~ AC-7
- **Test Requirements**:
  - `rule` TR-9.1: 四个测试类全部通过。
  - `rule` TR-9.2: 不依赖外部网络、语言服务器或真实模型。

## Task 10: 验证与文档
- [x] **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 8, Task 9
- **Description**:
  - 后端：`mvn -o -q compile`、`mvn -o -q test`。
  - 前端：`node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`。
  - 更新 `docs/phases/phase5/plan.md` 的 P5-05 状态与完成情况，更新 `docs/COMPLETED_WORK.md` 对应条目；如需批次日志，写入 `docs/lessons/<date>/`。
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `rule` TR-10.1: 后端编译与全量测试 0 失败。
  - `rule` TR-10.2: 前端类型检查 0 错误。
  - `rule` TR-10.3: runtime 评测基线不因本批变化（数据集无 `.java` 写入用例）。

## Verification Commands

```powershell
$env:JAVA_HOME='C:\Users\16680\.jdks\ms-21.0.11'
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q compile
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q test
# frontend/
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
```
