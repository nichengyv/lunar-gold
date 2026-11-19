# P5-06 验证回路 - Implementation Plan

## Task 1: 验证模型与配置加载
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新建 `src/main/java/com/capsule/agentkey/verify/VerifyConfig.java`：`VerifyConfig(Boolean enabled, Integer maxRetries, List<VerifyCommand> commands)`，紧凑构造器补默认值、裁剪 `maxRetries` 到 `0..2`、空命令列表归一为 `List.of()`。
  - `VerifyConfig.VerifyCommand(String name, String command, String cwd, Integer timeoutSeconds, Boolean required)`，提供 `isRequired()`。
  - 新建 `verify/VerifyResult.java`：`Status`、`CommandOutcome`、`configPath`、`configured`、`attempt`、`maxAttempts`、`commands`、`failureFingerprint`、`message`；实现 `configured()`、`passed()`、`failed()`、`formatForPrompt()`。
  - 新建 `config/AgentVerifyProperties.java`：`@Component @ConfigurationProperties(prefix = "agent.verify")`，字段 `enabled/configPath/maxRetries/maxOutputChars/defaultTimeoutSeconds`。
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-1.1: `mvn compile` 通过，新增类型无外部依赖。
  - `rule` TR-1.2: `VerifyConfig` 默认值与重试裁剪、`VerifyResult.formatForPrompt()` 四种状态文案可断言。

## Task 2: VerifyService 命令执行与状态映射
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `verify/VerifyService.java`，构造注入 `AgentVerifyProperties` 与 `CommandService`。
  - `load(Path workspaceRoot)`：只读 `<root>/.lunargold/verify.json`，校验路径不越界；文件缺失/无命令/`enabled=false` 返回未配置语义；JSON 非法或命令为空返回不可用语义。
  - `verify(Path workspaceRoot, int attempt, AtomicBoolean cancelled)`：顺序调用 `CommandService.run`；`BusinessException` → `UNAVAILABLE`；超时/非 0 → 失败；必跑失败 → `FAILED`，可选失败仅记录；全部必跑通过 → `PASSED`。
  - 输出尾部按 `maxOutputChars` 截取；失败指纹为命令名 + 退出码 + 超时标记 + 归一化输出尾部的 SHA-256 前 32 位。
  - `maxAttempts = 1 + min(config.maxRetries, properties.maxRetries)`；每次执行记录审计日志。
- **Acceptance Criteria Addressed**: AC-1, AC-3, AC-4
- **Test Requirements**:
  - `rule` TR-2.1: 必跑命令退出码 0 → `PASSED`；非 0 → `FAILED`。
  - `rule` TR-2.2: `CommandService.run` 抛 `BusinessException` → `UNAVAILABLE`，不向上抛。
  - `rule` TR-2.3: 可选命令失败不影响整体 `PASSED`。
  - `rule` TR-2.4: 相同失败结果指纹一致，不同输出指纹不同；`maxRetries=5` 时 `maxAttempts=3`。

## Task 3: application.yml 配置
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - `src/main/resources/application.yml` 的 `agent:` 下新增 `verify:` 配置块，全部使用环境变量占位，默认 `enabled=true`、`config-path=.lunargold/verify.json`、`max-retries=2`、`max-output-chars=4000`、`default-timeout-seconds=120`。
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-3.1: Spring 测试上下文能注入配置 bean，默认值正确。
  - `rule` TR-3.2: 不使用 `@Value` 绑定嵌套结构。

## Task 4: WorkspaceContext 与 ReAct 接入
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - `tool/WorkspaceContext.java` 增加 `VerifyResult verify()` / `setVerify(...)`。
  - `ReActAgentService` 构造注入 `VerifyService`；同步更新测试与 `RuntimeEvalRunner` 的构造调用。
  - 每轮全部工具结束后，若本轮有文件变更：`changeEpoch++`，调用 `verifyService.verify(root, ++verifyAttempts, cancelled)`，写入上下文并 `callback.onVerify`。
  - `PASSED` → 注入简短通过消息；`FAILED` → 相同指纹计数达到阈值走 `stopForNoProgress("REPEATED_VERIFY_FAILURE", "verify", ...)`，超过 `maxAttempts` 以 `INCOMPLETE / VERIFY_RETRY_EXHAUSTED` 停止，否则注入失败输出继续；`NOT_CONFIGURED / UNAVAILABLE` → 每次 run 只注入一次提示。
  - 候选完成时若 `verifiedEpoch < changeEpoch` 先验证；失败且仍有预算则注入并 `round++` 继续。
  - `candidateCompletion` 按 `DIAGNOSTICS_FAILED → VERIFY_FAILED → VERIFY_PASSED → VERIFY_NOT_CONFIGURED / VERIFY_UNAVAILABLE → 兜底` 顺序给出状态与消息。
- **Acceptance Criteria Addressed**: AC-1, AC-2, AC-3
- **Test Requirements**:
  - `rule` TR-4.1: 写文件后验证失败，下一轮 messages 含 `【验证结果】`；再次修复并通过后 completion 为 `COMPLETED / VERIFY_PASSED`。
  - `rule` TR-4.2: 持续失败达到 `maxAttempts` → `INCOMPLETE / VERIFY_RETRY_EXHAUSTED`，命令调用次数不超过 `maxAttempts`。
  - `rule` TR-4.3: 相同失败指纹达到阈值 → `NoProgress.reason=REPEATED_VERIFY_FAILURE`。
  - `rule` TR-4.4: 未配置 → `UNVERIFIED / VERIFY_NOT_CONFIGURED`；环境不可用 → `UNVERIFIED / VERIFY_UNAVAILABLE`。

## Task 5: SSE、Run Event 与消息 trace
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - `ReActAgentService.StreamCallback` 增加 `default void onVerify(VerifyResult result) {}`。
  - `entity/AgentRunEvent.java` 的 `EventType` 增加 `VERIFY`。
  - `controller/AgentChatController.java` 实现 `onVerify`：组装 `runId/turnId/status/configured/attempt/maxAttempts/configPath/authorization=project-config/message/failureFingerprint/at/commands[]`，`appendRunEvent(EventType.VERIFY)`，发送 `verify` SSE，写入 `traceVerify` 并在 `onComplete` 落入消息 trace。
- **Acceptance Criteria Addressed**: AC-4, AC-5
- **Test Requirements**:
  - `rule` TR-5.1: SSE 与 Run Event payload 字段完整一致，含审计字段。
  - `rule` TR-5.2: 历史 trace 与断线重放可还原 `verify`。

## Task 6: 前端验证事件与状态行
- [x] **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 5
- **Description**:
  - `frontend/src/types/chat.ts` 新增 `VerifyStatus`、`VerifyCommandOutcome`、`VerifyInfo`。
  - `types/api.ts` 的 `MessageTrace`、`UiMessage` 增加 `verify?: VerifyInfo`。
  - `api/sse.ts` 增加 `onVerify` hook 与 `case 'verify'`。
  - `stores/chat.ts` 实时回调、历史 trace、断线重放分支写入/还原 `verify`。
  - `ChatMessage.vue` 在完成提示附近增加紧凑状态行：验证通过 / 验证失败（含失败命令与输出尾部提示）/ 未配置验证 / 验证不可用；样式只用 `tokens.css` 变量。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-6.1: `vue-tsc --noEmit` 通过。
  - `rule` TR-6.2: 新事件不触发「未知事件类型」告警；无硬编码颜色。

## Task 7: 测试与回归
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2, Task 4
- **Description**:
  - 新增 `src/test/java/com/capsule/agentkey/verify/VerifyServiceTest.java`。
  - 新增 `src/test/java/com/capsule/agentkey/service/ReActVerifyIntegrationTest.java`，复用 `ScriptedAgentClient` 与 mock 依赖模式。
  - 更新 `ReActDiagnosticsIntegrationTest`：干净 Java 诊断在无验证配置时断言 `UNVERIFIED / VERIFY_NOT_CONFIGURED`。
  - 更新 `RuntimeEvalRunner` 的 `ReActAgentService` 构造参数。
- **Acceptance Criteria Addressed**: AC-1 ~ AC-5
- **Test Requirements**:
  - `rule` TR-7.1: 新增测试全部通过，不依赖外部网络、真实模型或已安装构建工具（通过 mock `CommandService`）。
  - `rule` TR-7.2: 既有诊断、评测与全量测试无回归。

## Task 8: 验证与文档
- [x] **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 6, Task 7
- **Description**:
  - 后端：`mvn -o -q compile`、`mvn -o -q test`、`mvn test "-Dtest=EvalGate,RuntimeEvalGate"`。
  - 前端：`node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`。
  - 更新 `docs/phases/phase5/plan.md` 的 P5-06 状态与完成情况，更新 `docs/COMPLETED_WORK.md` 对应条目。
- **Acceptance Criteria Addressed**: AC-6
- **Test Requirements**:
  - `rule` TR-8.1: 后端编译与全量测试 0 失败。
  - `rule` TR-8.2: 前端类型检查 0 错误。
  - `rule` TR-8.3: 双评测门禁通过；如 completion 指标发生预期变化，报告说明并显式更新基线。

## Verification Commands

```powershell
$env:JAVA_HOME='C:\Users\16680\.jdks\ms-21.0.11'
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q compile
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q test
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q test "-Dtest=EvalGate,RuntimeEvalGate"
# frontend/
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
```
