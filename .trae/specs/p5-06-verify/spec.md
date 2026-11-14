# P5-06 验证回路 - Product Requirements Document

## Overview

- **Summary**：为 Lunar Gold Agent 增加项目级验证回路。工作区通过 `.lunargold/verify.json` 声明验证命令；每轮写操作后与候选完成前，`VerifyService` 复用 `CommandService` 沙箱执行命令。失败输出回灌模型触发修复，超过重试/轮次/token 预算或无进展时停止；未配置、环境不可用与命令失败分别如实展示。
- **Purpose**：让「改完文件」不再等于「任务完成」。只有验证命令成功才能显示验证通过；无法验证时明确显示未验证，不伪造成功。
- **Target Users**：本地开发者、工作区任务使用者、后续 P5-14 任务级交付。

## Goals

1. 加载项目级 `.lunargold/verify.json`：命令列表、超时、是否必跑、最大重试 2。
2. 新增 `VerifyService`，在每轮写操作结束和候选完成时触发；命令仍走 `CommandService` 白名单、工作区路径校验、超时与进程树回收。
3. 失败输出注入下一轮模型上下文，形成一次可观察的修复回环。
4. 重试上限、轮次上限与 token 预算生效；相同失败指纹交由 P5-13 无进展策略停止。
5. 未配置、命令失败、环境不可用、验证通过四种状态可区分，并通过 SSE / Run Event / 消息 trace / 前端状态行如实展示。
6. 项目配置命令视为预授权，但每次执行都写入 Run Event 审计记录。

## Non-Goals

- 不实现 P5-04 代码索引、P5-07 RAG、P5-08 并行工具。
- 不新增数据库表或迁移；验证结果通过 Run Event 与消息 trace 持久化。
- 不实现验证配置的可视化编辑器、命令市场或自动安装工具链。
- 不实现「人工接受未验证变更」的后端状态机；前端给出明确提示，用户继续使用/审查变更即视为人工接受。
- 不修改桌面端打包、发布脚本与 CI 工作流。
- 不重做 P5-05 诊断；诊断仍是独立的变更文件证据。

## Background & Context

- `CommandService.run(workspaceRoot, command, cwd, timeoutSeconds, cancelled)` 已提供程序白名单、工作区路径校验、超时终止、输出截断与取消；环境不可用时抛 `BusinessException`（禁用、不在白名单、找不到可执行程序、启动失败、目录不存在）。
- `ReActAgentService` 每轮工具后 `WorkspaceContext.drainFileChanges()`，随后 P5-05 采集诊断。验证应挂在「本批文件变更 + 诊断」之后、下一轮模型调用之前。
- 模型不再返回工具调用只表示候选完成；`candidateCompletion` 当前按计划、审批、写入失败、诊断结果给出 `COMPLETED/UNVERIFIED/INCOMPLETE`。
- `StreamCallback` 已有 `default onDiagnostics/onCompletion/onNoProgress` 先例；新增 `onVerify` 不破坏既有实现。
- SSE 事件白名单：后端 `AgentChatController.sendEvent`，前端 `api/sse.ts` 的 switch、`types/chat.ts`、`types/api.ts`、`stores/chat.ts`、`ChatMessage.vue`。
- 配置约定：嵌套配置使用 `@ConfigurationProperties`，禁止 `@Value` 绑定嵌套结构。
- 不新增 Maven/npm 依赖。

## Functional Requirements

### FR-1 验证配置文件

路径：`<workspaceRoot>/.lunargold/verify.json`。

```json
{
  "enabled": true,
  "maxRetries": 2,
  "commands": [
    {
      "name": "compile",
      "command": "mvn -q -DskipTests compile",
      "cwd": "",
      "timeoutSeconds": 120,
      "required": true
    }
  ]
}
```

- `enabled` 缺省 `true`；`false` 视为未配置。
- `maxRetries` 缺省 `2`，取值裁剪到 `0..2`；再与 `agent.verify.max-retries`（默认 2，裁剪 `0..2`）取较小值。总尝试次数 = `1 + maxRetries`。
- `commands` 为空、文件不存在、`enabled=false` → `NOT_CONFIGURED`。
- JSON 解析失败、配置结构非法、命令为空、配置路径越界 → `UNAVAILABLE`（配置不可用），不抛异常到主循环。
- `required` 缺省 `true`。必跑命令失败 → 整体 `FAILED`；可选命令失败只记录，不让整体失败。
- `cwd` 相对工作区，空表示根目录；`timeoutSeconds` 为空时使用 `agent.verify.default-timeout-seconds`（默认 120）。

### FR-2 验证模型

新增包 `com.capsule.agentkey.verify`：

- `VerifyConfig` record：`Boolean enabled`、`Integer maxRetries`、`List<VerifyCommand> commands`；紧凑构造器补默认值并裁剪重试次数。
- `VerifyConfig.VerifyCommand` record：`String name`、`String command`、`String cwd`、`Integer timeoutSeconds`、`Boolean required`；提供 `isRequired()`。
- `VerifyResult` record：
  - `Status status`：`PASSED / FAILED / NOT_CONFIGURED / UNAVAILABLE`；
  - `String configPath`（固定 `.lunargold/verify.json`）；
  - `boolean configured`（是否加载到有效命令列表）；
  - `int attempt`、`int maxAttempts`；
  - `List<CommandOutcome> commands`；
  - `String failureFingerprint`；
  - `String message`。
- `VerifyResult.CommandOutcome` record：`name`、`command`、`cwd`、`required`、`exitCode`、`timedOut`、`durationMs`、`skipped`、`outputTail`。
- `formatForPrompt()`：输出 `【验证结果】` 段落。失败时包含尝试次数、失败命令、退出码与输出尾部，并明确「不要声称验证已通过」；通过时提示不要重复执行；未配置/不可用时说明原因并禁止声称通过。

### FR-3 VerifyService

文件：`src/main/java/com/capsule/agentkey/verify/VerifyService.java`，`@Service`，构造注入 `AgentVerifyProperties` 与 `CommandService`。

- `VerifyResult verify(Path workspaceRoot, int attempt, AtomicBoolean cancelled)`：
  1. `properties.enabled=false`、配置缺失、`enabled=false` 或无命令 → `NOT_CONFIGURED`，`configured=false`，不调用 `CommandService`。
  2. 读取并解析 JSON；异常 → `UNAVAILABLE`，消息包含可操作原因。
  3. 顺序执行命令，全部经 `CommandService.run`：
     - `BusinessException`（禁用/白名单/找不到程序/启动失败）→ 立即 `UNAVAILABLE`，不再执行后续命令；
     - `timedOut=true` 或 `exitCode != 0` 记为命令失败；必跑失败 → 整体 `FAILED`；仅可选失败 → 继续，最终可为 `PASSED`；
     - 执行中检测到 `cancelled=true` → `UNAVAILABLE`，消息「验证已取消」。
  4. 输出尾部按 `agent.verify.max-output-chars`（默认 4000）截取末尾，保留换行。
  5. `maxAttempts = 1 + min(config.maxRetries, properties.maxRetries)`，并在结果中返回。
  6. 失败时计算稳定指纹：命令名 + 退出码 + 超时标记 + 归一化输出尾部，SHA-256 前 32 位十六进制；通过/未配置/不可用返回空串。
  7. 每次执行记录 `log.info` 审计：配置路径、命令名、退出码、耗时；不记录密钥。
- `VerifyConfig load(Path workspaceRoot)` 允许包内可见，供测试直接校验解析与默认值。

### FR-4 配置属性

新增 `src/main/java/com/capsule/agentkey/config/AgentVerifyProperties.java`：

- `@Component @ConfigurationProperties(prefix = "agent.verify")`；
- `enabled=true`、`configPath=".lunargold/verify.json"`、`maxRetries=2`、`maxOutputChars=4000`、`defaultTimeoutSeconds=120`。

`application.yml` 的 `agent:` 下新增：

```yaml
  verify:
    enabled: ${AGENT_VERIFY_ENABLED:true}
    config-path: ${AGENT_VERIFY_CONFIG_PATH:.lunargold/verify.json}
    max-retries: ${AGENT_VERIFY_MAX_RETRIES:2}
    max-output-chars: ${AGENT_VERIFY_MAX_OUTPUT_CHARS:4000}
    default-timeout-seconds: ${AGENT_VERIFY_DEFAULT_TIMEOUT_SECONDS:120}
```

### FR-5 ReAct 接入

文件：`src/main/java/com/capsule/agentkey/service/ReActAgentService.java`、`tool/WorkspaceContext.java`。

- `WorkspaceContext` 增加 `VerifyResult verify()` / `setVerify(VerifyResult)`。
- 构造注入 `VerifyService`；同步更新所有 `new ReActAgentService(...)` 调用点。
- 新增局部状态：`verifyAttempts`、`changeEpoch`、`verifiedEpoch`、`lastVerifyFingerprint`、`repeatedVerifyFailures`、`verifyUnavailableNotified`。
- **写操作轮结束触发**：每个 ReAct 轮次处理完全部 tool calls 后，若本轮产生文件变更：
  1. `changeEpoch++`；
  2. 调用 `verifyService.verify(root, ++verifyAttempts, cancelled)`；
  3. 结果写入 `WorkspaceContext` 并 `callback.onVerify(result)`；
  4. `verifiedEpoch = changeEpoch`；
  5. `PASSED` → 向 `messages` 追加简短 `user` 消息「验证通过，不要重复执行验证」；
  6. `FAILED` → 计算连续相同指纹次数；达到 `agent.loop.no-progress.max-repeats` 时 `stopForNoProgress(..., "REPEATED_VERIFY_FAILURE", "verify", ...)`；否则若 `attempt >= maxAttempts`，以 `INCOMPLETE / VERIFY_RETRY_EXHAUSTED` 停止；仍有预算则把 `formatForPrompt()` 作为 `user` 消息注入，继续下一轮；
  7. `NOT_CONFIGURED / UNAVAILABLE` → 每次 run 只注入一次简短提示，避免重复噪声；不改写为通过。
- **候选完成触发**：模型不返回工具调用时，若 `workspaceChanged && verifiedEpoch < changeEpoch`，先执行一次验证：
  - `FAILED` 且仍有重试预算且未触发重复指纹 → 注入失败输出，`round++` 后继续循环；
  - 其余情况继续走 `candidateCompletion`。
- **完成判定**（`candidateCompletion`，顺序固定）：
  1. 计划未完成 / 审批拒绝 / 写入失败 → 保持现有 `INCOMPLETE`。
  2. 诊断可用且有 ERROR → `INCOMPLETE / DIAGNOSTICS_FAILED`。
  3. 最近验证 `FAILED` → `INCOMPLETE / VERIFY_FAILED`，消息含失败命令与重试次数。
  4. 最近验证 `PASSED` → `COMPLETED / VERIFY_PASSED`。
  5. 最近验证 `NOT_CONFIGURED` → `UNVERIFIED / VERIFY_NOT_CONFIGURED`，消息「文件已修改，但项目未配置验证命令；可在 .lunargold/verify.json 配置验证，或人工审查并接受未验证变更」。
  6. 最近验证 `UNAVAILABLE` → `UNVERIFIED / VERIFY_UNAVAILABLE`，消息含不可用原因与同样的后续建议。
  7. 无验证结果但诊断可用且无 ERROR → 保留 `COMPLETED / DIAGNOSTICS_PASSED` 作为兜底（仅在没有验证触发路径时出现）。
  8. 其余 → 保持 `UNVERIFIED / VERIFICATION_MISSING`。
- **预算**：验证自身不消耗模型 token；token 预算继续由现有 `maxTokensPerRun` 在每次模型调用后检查。重试次数与轮次上限分别在验证触发点和 ReAct 循环生效，不得出现无限验证循环。

### FR-6 SSE、Run Event 与消息 trace

- `ReActAgentService.StreamCallback` 新增 `default void onVerify(VerifyResult result) {}`。
- `AgentRunEvent.EventType` 新增 `VERIFY`。
- `AgentChatController`：
  - `AtomicReference<Map<String,Object>> traceVerify`；
  - `onVerify` 组装 payload：
    - `runId`、`turnId`、`status`、`configured`、`attempt`、`maxAttempts`、`configPath`、`authorization="project-config"`、`message`、`failureFingerprint`、`at`；
    - `commands[]`：`name/command/cwd/required/exitCode/timedOut/durationMs/skipped/outputTail`。
  - `appendRunEvent(runId, EventType.VERIFY, payload)`；
  - `sendEvent(emitter, "verify", json)`；
  - 最新结果写入 `traceVerify`，`onComplete` 时放入消息 trace 的 `verify` 字段，历史会话与断线重放均可还原。
- 审计：Run Event 中的 `authorization`、`configPath`、命令与退出码即持久审计记录；不经过 `ToolApprovalRegistry`，不弹审批卡。

### FR-7 前端

- `frontend/src/types/chat.ts`：
  - `VerifyStatus = 'PASSED' | 'FAILED' | 'NOT_CONFIGURED' | 'UNAVAILABLE'`；
  - `VerifyCommandOutcome`、`VerifyInfo`。
- `frontend/src/types/api.ts`：`MessageTrace`、`UiMessage` 增加 `verify?: VerifyInfo`。
- `frontend/src/api/sse.ts`：导入 `VerifyInfo`，`StreamHooks` 增加 `onVerify`，switch 增加 `case 'verify'`。
- `frontend/src/stores/chat.ts`：实时回调写入 `aiMsg.verify`；历史 trace 映射；断线重放 `applyRunEvent` 处理 `VERIFY`。
- `frontend/src/components/ChatMessage.vue`：完成提示附近增加紧凑验证状态行：
  - `PASSED` → 「验证通过」；
  - `FAILED` → 「验证失败」并可悬停查看失败命令与输出尾部；
  - `NOT_CONFIGURED` → 「未配置验证」；
  - `UNAVAILABLE` → 「验证不可用」；
  - 颜色只使用 `tokens.css` 变量，不新增硬编码 hex。

## Non-Functional Requirements

- **NFR-1 不阻塞主循环的异常**：配置缺失、解析失败、命令启动失败均转为状态结果，不向外抛异常；只有显式预算耗尽才停止。
- **NFR-2 沙箱一致**：所有验证命令只能通过 `CommandService.run` 执行，遵守白名单、工作区路径、超时、输出截断与取消。
- **NFR-3 有界**：单次最多少量命令、输出尾部有上限、总尝试次数不超过 `1 + min(config.maxRetries, 2)`。
- **NFR-4 稳定指纹**：相同失败内容产生相同指纹；输出中的时间/随机数不影响命令名与退出码主键。
- **NFR-5 可回放**：SSE 与 Run Event 载荷一致，刷新/重连后前端能还原最近一次验证状态。
- **NFR-6 无新依赖**：只用 Jackson、JDK `MessageDigest` 与现有 `CommandService`。

## Constraints

- Java 21、Spring Boot 4.1.2；不引入新库。
- 不直接执行 `ProcessBuilder`，不复制 `CommandService` 的白名单逻辑。
- 不修改 `.lunargold/verify.json`，只读取；不自动创建目录或文件。
- 嵌套配置使用 `@ConfigurationProperties`。
- 不新建第二套 SSE 解析、状态行组件或审批卡。

## Assumptions

- `.lunargold/verify.json` 由项目维护者提交或用户手动创建；内容是可信的项目配置。
- 验证命令通常是 `mvn` / `npm` / `gradle` 等已安装工具；工具缺失属于环境不可用，不是任务失败。
- 验证命令可能较慢；默认单条 120 秒，由 `CommandService` 的 `max-timeout-seconds` 兜底。
- 同一轮多个工具修改文件时，只在该轮全部工具结束后验证一次，避免重复构建。

## Acceptance Criteria

### AC-1 配置与四种状态

- **Given** 工作区没有 `.lunargold/verify.json`
- **When** 有文件变更并触发验证
- **Then** 返回 `NOT_CONFIGURED`，不调用 `CommandService`，completion 为 `UNVERIFIED / VERIFY_NOT_CONFIGURED`。
- **Given** 配置存在但命令找不到可执行程序
- **Then** 返回 `UNAVAILABLE`，completion 为 `UNVERIFIED / VERIFY_UNAVAILABLE`。
- **Given** 必跑命令退出码非 0
- **Then** 返回 `FAILED`，completion 为 `INCOMPLETE / VERIFY_FAILED`。
- **Given** 必跑命令全部退出码 0
- **Then** 返回 `PASSED`，completion 为 `COMPLETED / VERIFY_PASSED`。

### AC-2 失败触发修复回环

- **Given** 模型写文件后验证失败
- **When** 下一轮 `streamChat` 收到 messages
- **Then** messages 含 `【验证结果】`、失败命令与输出尾部；模型再次写文件后验证通过，completion 变为 `COMPLETED / VERIFY_PASSED`。

### AC-3 预算与无死循环

- **Given** `maxRetries=2` 且验证持续失败
- **When** 连续执行第 3 次验证仍失败
- **Then** 不再调用模型或命令，completion 为 `INCOMPLETE / VERIFY_RETRY_EXHAUSTED`。
- **Given** 连续失败指纹完全相同且达到 `agent.loop.no-progress.max-repeats`
- **Then** 推送 `no_progress`，reason 为 `REPEATED_VERIFY_FAILURE`，completion 为 `INCOMPLETE`。

### AC-4 预授权与审计

- **Given** 项目配置了验证命令
- **When** 命令执行
- **Then** 不弹审批卡；`agent_run_event` 中存在 `VERIFY` 记录，包含 `authorization=project-config`、`configPath`、命令名、退出码与耗时。

### AC-5 事件与展示

- **Given** 一次验证产生结果
- **When** SSE 流处理
- **Then** 收到 `verify` 事件，载荷含 `status/configured/attempt/maxAttempts/commands`；Run Event 同载荷；消息 trace 含 `verify`；前端状态行显示对应文案。

### AC-6 构建与回归

- 后端 `mvn -o -q compile`、`mvn -o -q test` 通过；前端 `vue-tsc --noEmit` 通过。
- P5-05 的干净诊断用例按 P5-06 新语义调整为 `UNVERIFIED / VERIFY_NOT_CONFIGURED`（无验证配置时不得显示为已完成）。
- Runtime 评测与双门禁继续通过；如 completion 指标因语义收紧发生预期变化，需在报告中说明并显式更新基线。

## Verification Plan

1. `VerifyServiceTest`：未配置、非法 JSON、必跑成功/失败、可选失败、环境不可用、输出截断、重试上限、指纹稳定性。
2. `ReActVerifyIntegrationTest`：失败回灌与修复回环、成功完成、未配置、环境不可用、重试耗尽、重复失败指纹停止、`onVerify` 事件载荷。
3. 更新 `ReActDiagnosticsIntegrationTest` 与 `RuntimeEvalRunner` 的构造参数与完成语义断言。
4. 后端：`mvn -o -q compile`、`mvn -o -q test`；评测门禁 `mvn test "-Dtest=EvalGate,RuntimeEvalGate"`。
5. 前端：`node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`。
6. 更新 `docs/phases/phase5/plan.md` 与 `docs/COMPLETED_WORK.md`。

## Required Approval

实施前需用户确认本规格。确认后按 `tasks.md` 顺序执行；若实现中发现与现有代码契约冲突，先记录并回到规格调整，不静默扩大范围。
