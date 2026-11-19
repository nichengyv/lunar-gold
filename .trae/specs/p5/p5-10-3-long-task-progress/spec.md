# P5-10-3 长任务进度可感知 — 规格说明

> 日期：2026-11-14
> 状态：待确认
> 对应规划：`docs/phases/phase5/plan.md` L353-L359、`docs/UPDATE_PLAN.md` 第 5 节 P5-10-3
> 已存在基础：计划条（步骤/进度/详情）、停止后保留半截内容、后端 `usage` SSE（前端未消费）

## 1. 目标

长任务执行期间，用户在任何时刻都能回答三个问题：

1. 现在在第几步、这一步已经花了多久；
2. 本轮预算（轮次 / token / 时间）用了多少、离上限还有多远；
3. 失败属于哪一类、下一步应该做什么。

停止生成后必须明确显示「已停止」，保留已生成内容，并提供「继续」。

## 2. 非目标

- 不重做或替换现有计划条 `update_plan` 的步骤展示。
- 不引入新的持久化表；不把实时预算写入数据库。
- 不改变 ReAct 循环结构，不新增 Planner/Critic。
- 不改造审批卡片（P5-10-2 范围）。
- 不做服务端渲染或跨端同步。

## 3. 现状与缺口

| 能力 | 现状 | 缺口 |
|---|---|---|
| 计划步骤 | `ChatView.vue` 计划条展示步骤、当前步骤、完成比、详情面板 | 无阶段耗时 |
| 阶段时间线 | 无 | 理解→读取→修改→验证→汇总，含每步耗时与当前步骤 |
| 预算 | 后端 `usage` SSE 已下发 token；前端 `sse.ts` 未处理，控制台报「未知事件类型」 | 轮次、token、时间与上限对照；接近上限提示 |
| 失败 | `ChatMessage.vue` 统一 `failed-box` +「重试这一轮」 | 四类失败分级与对应动作 |
| 停止 | `stopGenerating` abort，`aiMsg.stopped=true`，显示「已停止生成」 | 无「继续」入口；无“未在生成”的显式校验 |

## 4. 设计决策

### 4.1 阶段时间线（前端推导，后端不改工具语义）

阶段固定为五步：`understand`（理解）→ `read`（读取）→ `modify`（修改）→ `verify`（验证）→ `summarize`（汇总）。

在 `frontend/src/stores/chat.ts` 的 `sendMessage` 中记录每个阶段的首次到达时间：

| 阶段 | 触发 |
|---|---|
| 理解 | 发送成功、开始流式时立即记录 |
| 读取 | 首次只读工具调用：`read_file` / `list_files` / `list_dir` / `grep` / `glob` / `search_code` / `repo_map` / `read_document`；修改前首次 `run_command` 也归为读取 |
| 修改 | 首次写工具：`write_file` / `edit_file` / `apply_patch` / `delete_file` / `create_file`，或收到 `file_change` 事件 |
| 验证 | 收到 `diagnostics` / `verify` 事件；或修改后首次 `run_command` / LSP 工具 |
| 汇总 | 收到 `done` 事件（或流正常结束） |

规则：

- 阶段只前进不后退；已到达阶段保持时间戳。
- 后续阶段已到达但中间阶段从未到达时，中间阶段标记为「跳过」，不伪造耗时。
- `liveStages` 存在 store，随本轮重置；`ChatView` 仅在 `chat.isProcessing` 时展示。
- 当前阶段显示为“进行中”，第 N 步 = 当前阶段序号；耗时 = 下一阶段开始时间 − 本阶段开始时间，当前阶段用本地秒级 tick 实时增长。
- 计划条与阶段条并存：计划条回答“任务计划完成到哪”，阶段条回答“执行处于哪个环节”。

### 4.2 预算可视化

后端扩展既有 `usage` SSE 载荷（向后兼容，新字段可选）：

```json
{
  "promptTokens": 1200,
  "completionTokens": 300,
  "totalTokens": 1500,
  "round": 2,
  "maxRounds": 30,
  "maxTokens": 200000,
  "elapsedMs": 45210,
  "maxElapsedMs": 600000
}
```

- `round`：本次运行已完成的模型轮次数（控制器内按 `onUsage` 计数，首个 usage 为 1）。
- `maxRounds`：当前推理档位上限（快速 10 / 标准 30 / 深度 60，取 `AgentPolicy.Reasoning.maxToolRounds()`，前端不硬编码）。
- `maxTokens`：`agent.budget.max-tokens-per-run`，0 表示不限。
- `elapsedMs` / `maxElapsedMs`：服务端运行耗时与时长预算；`maxElapsedMs=0` 表示不限。
- 前端 `frontend/src/api/sse.ts` 增加 `onUsage`，`chat.ts` 落 `liveBudget`；本地按 `runStartedAt` 每秒推进耗时，服务端值取较大者纠偏。
- 展示（`ChatView`）：`第 {round}/{maxRounds} 轮 · {tokens}/{maxTokens} token · {elapsed}/{maxElapsed}`；上限为 0 显示「不限」。
- 任一比例 ≥ 80% 时整行转为警示色，并显示具体提示（轮次接近上限 / token 接近上限 / 时间接近上限）。
- 达到上限时不再静默结束：由 `completion.reason`（`MAX_TOOL_ROUNDS` / `TOKEN_BUDGET_EXCEEDED` / `TIME_BUDGET_EXCEEDED`）在失败/完成提示中给出原因与建议。
- 时间预算新增配置 `agent.budget.max-seconds-per-run: 0`（0=不限）。`ReActAgentService` 每轮循环开始时检查，超限走现有预算停止路径，reason=`TIME_BUDGET_EXCEEDED`，并提示可缩小任务范围或调高配置。
- 断线恢复后的降级：`usage` 事件不落库，重放后轮次从 0 重新计数，token 以 `/runs/{runId}/events` 返回的 `totalTokens` 兜底；界面显示「轮次 —（恢复后重新计数）」，不伪造数据。

### 4.3 失败分级

在 `frontend/src/stores/chat.ts` 导出纯函数 `classifyFailure(errorText, completion?)`，返回：

```ts
type FailureClass = 'retryable' | 'config' | 'approval' | 'manual';
interface FailureInfo { class: FailureClass; label: string; hint: string; primaryAction: 'retry' | 'settings' | 'reload'; }
```

| 类别 | 判定 | 标签 | 主操作 | 说明 |
|---|---|---|---|---|
| 可重试 `retryable` | 5xx、timeout、429、网络/Failed to fetch、`MAX_TOOL_ROUNDS` | 可重试 | 重试这一轮 | 轮次耗尽时提示可切换深度推理 |
| 需配置 `config` | 401/403、API Key/未配置、模型不可用/404 | 需配置 | 去设置 | 跳转设置页模型配置；次操作保留重试 |
| 需审批 `approval` | `completion.reason=APPROVAL_REJECTED`、审批/拒绝/被拦截 | 需审批 | 重新发起 | 提示上次审批被拒绝，重试会重新请求审批 |
| 需人工 `manual` | 权限/冲突/越界/写入失败/诊断失败/验证失败及兜底 | 需人工 | 重新加载 | 提示需要检查工作区或日志 |

- 停止（`CANCELLED`）不算失败，走「已停止」路径。
- `ChatMessage.vue` 的 `failed-box` 按类别显示标签、提示和主按钮；仅 `config` 提供次按钮「重试这一轮」。
- 「去设置」使用 `useRouter().push('/settings')`；若设置页已支持 tab 查询参数则带 `?tab=model`。

### 4.4 停止后「继续」

- `stopGenerating` 现有行为保留：不标失败、保留半截内容、`isProcessing=false`、`streamStatus=idle`。
- 新增 store action `continueTask(messageId)`：
  1. 仅当目标回答 `stopped` 且当前无任务时执行；
  2. 以用户可见消息发送固定提示：`请继续完成上一条未完成的回答，从中断处接着做，不要重复已完成的部分。`；
  3. 复用 `sendMessage`，保留上下文与计划。
- `ChatMessage.vue` 的 `.stopped-hint` 增加「继续」按钮，直接调用 `chat.continueTask(msg.id)`；生成中禁用。
- 停止后必须满足：无 typing 指示、无「正在生成」状态条、输入框可用、侧栏状态为已停止（现有 `CANCELLED`）。

## 5. 涉及文件

### 后端

- `src/main/java/com/capsule/agentkey/controller/AgentChatController.java`
  - 流式接口内维护 `runStartAt` 与轮次计数器；
  - `sendUsageEvent` 载荷追加 `round/maxRounds/maxTokens/elapsedMs/maxElapsedMs`；
  - 注入 `agent.budget.max-tokens-per-run`、`agent.budget.max-seconds-per-run`，轮次上限取 `policy.reasoning().maxToolRounds()`。
- `src/main/java/com/capsule/agentkey/service/ReActAgentService.java`
  - 新增 `maxSecondsPerRun` 配置；
  - `while` 循环开头检查时间预算，超限复用 `stopForBudget(...)` 并给 `TIME_BUDGET_EXCEEDED`。
- `src/main/resources/application.yml`（实际配置文件以仓库为准）
  - 增加 `agent.budget.max-seconds-per-run: 0` 及注释。

### 前端

- `frontend/src/types/chat.ts`：`RunUsage`、`TaskStageKey`、`TaskStageItem`、`FailureClass`、`FailureInfo`。
- `frontend/src/api/sse.ts`：`StreamHooks.onUsage` + `case 'usage'`。
- `frontend/src/stores/chat.ts`：
  - state：`liveBudget`、`liveStages`、`runStartedAt`；
  - `sendMessage` 内接 `onUsage`、阶段标记（工具、file_change、diagnostics、verify、done）；
  - 导出 `classifyFailure`；新增 `continueTask`；
  - 新一轮重置实时进度，停止/失败按现有路径收口。
- `frontend/src/views/ChatView.vue`：
  - 计划条上方新增 `task-progress` 条（阶段时间线 + 预算行 + 80% 警示）；
  - `isProcessing` 时启动 1s tick，结束后停止；
  - 仅长任务（开始后 ≥3s 或已进入读取及以后）显示，避免短问答闪动。
- `frontend/src/components/ChatMessage.vue`：
  - 失败框分级展示与按钮；
  - 「已停止生成」同行增加「继续」按钮。

## 6. 验收标准

1. 长任务运行中，界面持续显示当前阶段（第 N 步）、该阶段已耗时、轮次/token/时间相对上限；任意时刻可回答“现在在第几步”。
2. 预算任一维度 ≥80% 有警示，达到上限时给出明确原因与建议，不出现静默截断。
3. 四类失败在界面上有不同标签、提示与主操作；`APPROVAL_REJECTED` 与 5xx/超时、缺 Key、权限冲突分别命中不同类别。
4. 点击停止后：内容保留、显示「已停止生成」、无 typing/“正在生成”、输入框可用；点击「继续」发出固定续写提示并开始新一轮。
5. 旧后端（无新增 usage 字段）下界面正常降级：轮次显示「—」，token/耗时仍可用。
6. 现有计划条、断线续传、审批、ChangeSet 行为不回归。

## 7. 验证方式

- 前端单测：`frontend` 下 `npx vitest run`
  - `sse`：`usage` 事件解析与新字段；
  - store：阶段推进/跳过、预算更新、四类失败分类、`continueTask` 提示词；
  - 组件：停止态「继续」、四类失败按钮。
- 前端构建：`npm run build`（Windows 下把 TEMP/TMP 指向工作区临时目录）。
- E2E：`E2E_CHANNEL=msedge`，手动 dev server + `E2E_BASE_URL=... npx playwright test`，新增 `long-task-progress.spec.ts`：
  1. 长任务阶段与预算条出现并随事件推进；
  2. 配置类失败显示「需配置/去设置」；
  3. 停止后显示「已停止」与「继续」，继续请求携带续写提示。
- 后端：`mvn -o test` 全量通过；时间预算新增聚焦用例。
- 全量回归后同步 `docs/UPDATE_PLAN.md`、`docs/phases/phase5/plan.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`，并勾选本目录 tasks/checklist。

## 8. 风险与取舍

- 阶段由事件启发式推导，极端工具组合可能把 `run_command` 归错阶段；规则固定为“修改前算读取、修改后算验证”，并在单测中锁定。
- 轮次计数依赖 `usage` 事件，不持久化；断线恢复后明确显示「—」，不猜数。
- 时间预算默认关闭，避免改变现有长任务行为；开启后才会中断。
- 不新增依赖，不改数据库结构。
