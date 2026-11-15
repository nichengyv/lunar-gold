﻿﻿﻿# Phase 5 更新规划：智能体能力与工作台体验增强

> 更新日期：2026-11-15
> 状态：实施中（质量地基、核心信息架构、P5-13 真实 ReAct 闭环、P5-05 LSP 诊断闭环、P5-06 Verify、P5-04 代码索引（repo map + 符号索引/增量缓存）、P5-07 混合检索/RRF/引用、P5-08 并行只读工具、P5-14 任务级 ChangeSet、P5-11-1 前端测试基线、P5-11-2 体验可观测性、P5-11-3 长会话性能、P5-10-3 长任务进度、P5-10-2 审批效率与 P5-10-4 键盘与无障碍已完成；下一步推进 P5-07 模型 reranker/收益量化，以及 P5-09 剩余视觉项）
> 主计划见 [UPDATE_PLAN.md](../../UPDATE_PLAN.md)；已完成工作见 [COMPLETED_WORK.md](../../COMPLETED_WORK.md)。
> 视觉与交互原型见 [visual-ux-preview.html](visual-ux-preview.html)（静态、可直接浏览器打开）。
> 本文只描述 Phase 5 的目标、现状、双轨路线、每项设计、评测与验收，不以日历工期作为承诺。

---

## 1. 与主计划的关系

- **产品形态**：Lunar Gold 是本地优先、可控、可验证的 AI 编程工作台，不是“Runtime 产品”。Agent Runtime / 智能体执行引擎只是内部基础执行层，Workbench 是用户直接使用的工作台，两者属于同一个产品。
- **执行架构**：现有 ReAct 是通用且可扩展的基础循环，其他智能体也有同类机制，它本身不是差异化。当前不替换 ReAct，而是用代码上下文、计划、LSP、Verify、完成控制、无进展检测和评测补强。
- **双轨关系**：智能体能力增强负责“把任务做成”，工作台体验增强负责“让用户理解、控制并交付任务”。两条轨道可并行，功能增强仍是每批交付的主体，评测用于证明收益而非取代功能。
- **既有功能不取消**：P5-04 代码索引、P5-07 RAG、P5-08 并行、P5-09～11 视觉与体验继续保留；halfvec、模型 rerank、虚拟滚动等具体技术子项由数据决定先后顺序与实现方式。
- P5-01～03 已建立组件级质量地基，P5-12 核心任务流、P5-13 真实 ReAct 闭环、P5-05 LSP 诊断闭环、P5-06 Verify、P5-04 符号索引/增量缓存、P5-07 混合检索、P5-14 任务级交付、P5-11-1 前端测试基线、P5-11-2 体验可观测性与 P5-10-3 长任务进度已完成；下一阶段推进 P5-11-3 长会话性能，不重做上述已完成项。

---

## 2. 现状盘点（2026-11-12 核实）

> 本节记录**开工前**的盘点结果，用于说明为什么要做；其中部分条目（token 收口、三态、续传、键盘）已经处理，最新状态以第 8 节「进度快照」为准。

| 项 | 现状 | 事实位置 |
|---|---|---|
| 提示词 | 系统提示词大段硬编码；运行时拼接自定义规则、项目指令、权限、MCP、工作区、RAG；无版本化/热更新 | `ReActAgentService.SYSTEM_PROMPT_SKELETON`（L27-L87）、`buildMessages`（L685-L767） |
| 评测 | 无评测集、runner、基线、门禁；测试目录无 Eval/Benchmark | `src/test` |
| 代码索引 | 无 tree-sitter、符号索引、repo map | 全仓未命中 |
| LSP 诊断 | 仅有 `Capability.LSP` 枚举，无客户端与诊断采集 | `config/Capability.java` |
| 验证回路 | 无“修改后自动 verify”编排；命令执行集中在 `CommandService` | `service/CommandService.java` |
| RAG | pgvector + HNSW 单路向量召回、阈值过滤、返回出处；无混合检索/rerank/量化 | `VectorStoreService`、`DocumentService.retrieveContext`（L219-L277） |
| 工具执行 | `ToolRegistry` + `BaseTool` 风险等级；单轮 `for` 串行执行；写操作走审批 | `tool/ToolRegistry.java`、`ReActAgentService`（L504-L543） |
| token/轮次 | V18 已有 prompt/completion/total token 列与 usage 事件，具备统计基础 | `AgentRunService`、V18 迁移 |
| 主题与暗色 | 已有亮/暗切换、持久化、首屏防闪白、Element Plus 暗色变量、字号三档 | `stores/theme.ts`、`index.html` L8-L16 |
| 视觉一致性 | `tokens.css` 只覆盖间距/圆角/字号/动效；`--fs-base` 与 style.css 重复定义（13.5px vs 14px，前者被覆盖）；暗色缺 `--text-tertiary` / `--warning-bg`；9 个 `.vue` 共 59 处硬编码 hex | `styles/tokens.css` L19、`styles/style.css` L2-L46 |
| 加载/空/错误态 | 无骨架屏组件；空态只存在于对话页局部；无统一错误态组件 | `views/ChatView.vue` |
| 消息渲染 | 消息全量渲染，无虚拟滚动；流式每个 chunk 触发 Markdown 重渲染 | `ChatMessage.vue`、`MarkdownContent.vue` |
| 流式续传 | 后端已有 `GET /api/agent/runs/{runId}/events?sinceSeq=` 且支持 Last-Event-ID；前端 fetch 手写 SSE，不记 seq、不重连 | `AgentChatController` L808、`api/sse.ts` |
| 键盘/无障碍 | 已有 Ctrl+K、Esc×2 停止、Enter 发送、@ 引用、/ 命令；全站仅 1 处 `aria-label`，无统一 focus-visible、无快捷键面板、无 reduced-motion | `ChatView.vue` L1010-L1033 |
| 响应式 | 全站仅 1 处 `@media (min-width: 769px)`，窄窗口下侧栏与工作台挤压正文 | `layouts/AppLayout.vue` L940 |
| 前端测试 | P5-11-1 已补齐：Vitest + `@vue/test-utils` + jsdom（22 项），Playwright 关键路径 7 条；CI 增加 `frontend-test` 与 `frontend-e2e` | `frontend/vitest.config.ts`、`frontend/playwright.config.ts`、`.github/workflows/ci.yml` |
| 前端可观测 | 无全局错误捕获、无 Web Vitals、无体验指标 | 全仓未命中 |

结论：P5-01～P5-08 基本是 0 → 1，只有 RAG 与 token 统计有地基；P5-09～P5-11 是“有零件、缺体系”，属于补齐与收口，不是重做。

---

## 3. 目标与出口

1. **评测可重复**：组件级用例与真实 ReAct 端到端用例分别可重复运行，报告可追溯，二者不互相冒充。
2. **有基线**：成功率、轮次、token、时延按类别记录并纳入仓库；真实循环另记验证覆盖与无进展停止。
3. **有门禁**：提示词、工具、循环与验证编排变更后，回归超过阈值即失败。
4. **完成有证据**：代码修改不能仅凭模型宣称完成；诊断、verify 与未验证状态必须明确。
5. **增强可证明**：索引、诊断、verify、RAG、并行分别有对应功能交付与收益数据。
6. **任务可交付**：一个任务边界内的变更、审批与验证可统一审查，支持继续修复与整体撤回。
7. **视觉一致**：token 单一入口，亮暗两套对比度达标，加载/空/错误三态统一。
8. **体验可恢复**：断网与刷新不丢本轮；审批可纯键盘完成；长任务进度可见；窄窗口可用。
9. **体验可度量**：前端关键路径有自动化测试，慢与断有指标可查。

---

## 4. 双轨路线

**已完成基础**：P5-01 提示词外置、P5-02 组件级评测、P5-03 门禁、P5-12 核心任务流、P5-13 真实 ReAct 闭环、P5-04 代码索引（repo map + 符号索引/增量缓存）；P5-09/P5-10 只补“剩余”列，已完成子项不重复实施。

### 4.1 智能体能力增强轨道

| 批次 | 任务 | 交付标志 | 状态 |
|---|---|---|---|
| A1 真实执行与反馈闭环 | P5-13 真实 ReAct E2E / 完成控制 / 无进展检测 / 编程提示词 → P5-05 LSP → P5-06 Verify | 真实循环可评测；修改有诊断与验证；失败可回环，未验证如实呈现 | P5-05、P5-06 已完成 |
| A2 代码理解与检索 | P5-04 代码索引 / repo map → P5-07 混合检索 / 引用 / rerank | 大型项目定位更准，上下文成本与检索收益可量化 | P5-04 与 P5-07 基础能力已完成 |
| A3 执行效率 | P5-08 并行只读工具 | 只读调用并行且 p95 有收益，写入与命令仍串行审批 | 已完成 |

### 4.2 工作台体验增强轨道

| 批次 | 任务 | 交付标志 | 状态 |
|---|---|---|---|
| B1 任务级交付 | P5-14 ChangeSet / 验证结果 / 继续修复 / 整体撤回 + P5-10-3 长任务进度 + P5-11-1 前端 E2E | 用户能审查、恢复并交付完整任务，关键路径有自动化保护 | P5-14、P5-11-1 已完成；P5-10-3 待做 |
| B2 交互与视觉补差 | P5-09 / P5-10 剩余项 | 排版、响应式、审批效率和无障碍达到发布标准 | 部分完成 |
| B3 体验度量与性能 | P5-11-2 指标 → P5-11-3 长会话性能 | 慢与断可定位；虚拟化按真实会话数据实施 | P5-11-2 已完成；P5-11-3 未开始 |

A1 与 B1 可并行；P5-11-1 的断线重连 E2E 复用 P5-10-1 已完成实现。没有 A1 的失败分类与收益数据时，不引入多智能体、复杂状态图或独立 Planner/Critic 来替代 ReAct。

---

## 5. 批次 1：质量地基（已完成）

### P5-01 提示词外置与版本化（已完成）

**目标**：提示词与代码解耦，支持版本切换、热重载与回滚。

- 资源化：`src/main/resources/prompts/agent/system-<version>.md`、`tool-notes/*.md`，配套 `prompts/index.json`（`id`、`version`、`scenario`、`sha256`、`default`）。
- 加载：新增 `PromptCatalog`，启动时校验哈希与必填段落；按场景选择版本，配置可覆盖 `agent.prompt.scenario.<name>.version`；未知版本启动失败。
- 热更新/回滚：`POST /api/admin/prompts/reload`（仅本地/管理员）重载目录；回滚只改配置版本，不改代码。
- 可追溯：把 `promptId@version` 写入 run 事件，评测报告与线上问题可对账。
- 改造：替换 `SYSTEM_PROMPT_SKELETON` 与 `buildMessages` 中的静态文案；权限、工作区、RAG 等动态段落仍由代码生成。
- 验收：
  - 加载/哈希校验通过，坏文件启动失败。
  - 配置切换版本后 `buildMessages` 输出随之变化。
  - 回滚到上一版本无需改代码。
  - 单测覆盖加载、未知版本、切换与回滚。

**完成情况**：

- 新增 `src/main/resources/prompts/agent/system-v1.md`、`prompts/tool-notes/{mcp,terminal}-v1.md` 与 `prompts/index.json`（含 sha256、default）；`buildMessages` 已改为从 `PromptCatalog` 取正文，删除 `SYSTEM_PROMPT_SKELETON`。
- `PromptCatalog` 启动校验哈希、Agent 必备段落与场景版本；版本由 `agent.prompt.scenario.<scenario>.version` 覆盖，未知版本直接启动失败；重载失败保留旧快照。
- `POST /api/admin/prompts/reload` 已可用（Security 白名单 + 控制器回环校验），实测返回 `{agent=agent-system@v1, tool-mcp=tool-mcp@v1, tool-terminal=tool-terminal@v1}`。
- 每轮创建 run 时写入 `PROMPT_LOADED` 事件（payload 为 scenario → id@version）。
- `PromptCatalogTest` 覆盖默认加载、配置切版本、未知版本、哈希不符、重载失败回滚，全部通过。

### P5-02 组件级评测集（已完成）

**目标**：覆盖核心能力、可离线重复运行的数据集与 runner。

- 数据：`eval/datasets/*.jsonl`。用例字段：
  - `id`、`category`：`tool_select` / `file_edit` / `file_delete` / `command_approval` / `rag` / `refusal`；
  - `fixture`、`prompt`、`maxRounds`；
  - `expect`：工具名与参数约束、文件 diff、命令是否需审批、引用命中、拒答；
  - `budget`：最大轮次、token、时延。
- 隔离：每个用例把 `eval/fixtures/<name>` 复制到临时工作区，结束销毁，保证可重复。
- 驱动：抽取 `AgentClient` 接口，`SpringAiAgentClient` 实现之；评测注入 `ScriptedAgentClient` 回放固定工具调用；真实模型模式仅本地/定时使用。
- 评分：工具选择（精确/部分）、文件变更（应用后 diff）、审批正确性、RAG 引用、拒答、轮次/token/时延。
- 规模：六类共 27 条（tool_select 5 / file_edit 5 / file_delete 4 / command_approval 5 / rag 4 / refusal 4）。
- 输出：`eval/reports/<timestamp>.json` 与摘要表。
- 验收：一条命令跑完全部用例；同一输入两次运行结果一致；报告包含分类别指标。

**完成情况**：

- 新增 `AgentClient` 接口（`streamChat`/`callSync`/`callVision` + `StreamResult` record），`SpringAiAgentClient implements AgentClient`，四个依赖服务（`ReActAgentService`/`ContextSummaryService`/`SessionMemoryExtractionService`/`VisionService`）改为依赖接口。
- 新增 `ScriptedAgentClient`（test 源码），按脚本序列回放工具调用或文本，线程安全。
- `eval/datasets/` 下 6 个 jsonl 共 27 条用例；`eval/fixtures/{java-small,simple-project}` 提供隔离工作区。
- `EvalRunner`（JUnit）加载数据集、复制 fixture、按 category 评分、输出 `eval/reports/<ts>.json` + 控制台摘要表。
- 实测：`mvn test -Dtest=EvalRunner` 27/27 通过，连续两次运行分类别指标完全一致；`mvn test` 全量 208 用例 0 失败。

### P5-03 评测门禁（已完成）

**目标**：提示词/工具变更导致质量或成本回退时自动拦截。

- 基线：`eval/baselines/baseline.json`（分类别成功率、平均轮次、平均 token、p95 时延）。
- 阈值：成功率下降 ≤2pp、平均轮次/token 增幅 ≤10%、p95 时延增幅 ≤20%，任一越界失败；p95 另留 25ms 绝对余量，避免短任务受毫秒级计时噪声影响。
- 命令：`scripts/run-eval.ps1`（或 `mvn test "-Dtest=EvalGate,RuntimeEvalGate"`）运行组件与真实 Runtime 脚本化评测并分别对比基线。
- 基线更新：确认变化为预期改进后运行 `scripts/run-eval.ps1 -UpdateBaseline`；新增/删除用例也必须显式更新对应基线。
- CI：后端 `mvn verify` 后显式执行 `EvalGate,RuntimeEvalGate`，提示词/工具/循环变更均在拦截范围内；真实模型评测不进 CI（成本与抖动），两类脚本化报告均归档 14 天。
- 验收：`EvalGateTest` 覆盖用例 pass→fail、token 增幅超 10%、p95 增幅超 20%、增删用例未更新基线，均能产生包含类别与指标的失败原因；正式基线 27/27，真实门禁运行通过。

---

## 6. 智能体能力 A1：真实执行与反馈闭环（进行中）

### P5-13 真实 ReAct 闭环与完成控制（已完成）

**开工时现状判断**：ReAct 本身保留。当时短板是模型不再调用工具就直接结束、自然结束会把进行中计划自动标为完成；`update_plan` 主要记录状态而非验收条件；循环只有轮次/token 上限，缺少重复调用与无进展识别；通用提示词也不足以表达编程任务的完成依据。

- **真实端到端评测**：把 `ScriptedAgentClient` 注入真实 `ReActAgentService.execute`，驱动实际消息组装、工具分发、审批、fixture 文件副作用、Run/Event 终态与计划收口。覆盖多轮工具、修改后失败重试、拒绝审批、工具异常、轮次耗尽与取消；不得用 P5-02 的组件通过率代替任务成功率。
- **完成语义**：模型停止调用工具只表示“候选完成”，不自动等于验证通过。代码修改任务关联诊断/verify 状态；在 P5-05/P5-06 尚未提供证据时明确显示“未验证”，不得把进行中步骤直接包装成已验证成功。
- **无进展检测**：识别规范化后的重复工具名 + 参数、相同诊断/测试失败的连续重试、长时间只探索不推进等模式；阈值可配置。触发后记录事件并选择请求用户、给出替代方案或安全停止，不只等到最大轮次。
- **编程场景提示词**：通过 P5-01 的版本化机制新增 coding 场景版本，强调最小修改、符号定位、诊断、verify 与完成证据；通用问答继续使用适合闲聊/资料问答的版本，不用统一的 100～500 字回答约束限制编程交付。
- **指标与报告**：新增真实循环成功率、验证覆盖率、无进展停止次数、重复调用率、轮次/token/p95；确定性用例进入 CI，真实模型只作本地/定时抽样，不要求外部 Key。
- **验收**：同一脚本两次运行的文件副作用与事件终态一致；模型虚假完成、重复调用和无进展用例被识别；至少一个真实修改任务经过“模型 → 工具 → 文件 → 诊断/验证状态 → 完成/未完成”的完整链路；现有组件级 `EvalGate` 继续通过。

**完成情况（2026-11-13）**：

- `RuntimeEvalRunner` 已驱动真实 `ReActAgentService.execute`、工具注册与分发、审批注册表、工作区文件服务和 Run/Event 状态机。5 个 JSONL 文件共 15 条用例覆盖完成控制、审批、失败恢复、循环控制、消息回灌、取消和 A/B 确定性；同时断言消息快照、真实文件副作用、事件 seq、token 总量、模型调用数和工具轮次。
- 新增 `CompletionStatus=COMPLETED/UNVERIFIED/INCOMPLETE`，与 Run 执行状态分离。文件已修改但无诊断或 verify 证据时为 `UNVERIFIED`；计划未完成、审批拒绝、写入失败、轮次耗尽、无进展停止和取消均为 `INCOMPLETE`。自然结束只保留模型显式提交的已完成计划，其余步骤收口为 `aborted`；取消会进入 Run=CANCELLED。
- `completion` / `no_progress` 已贯通 SSE、Run Event、消息 trace、`resultJson` 和前端展示；前端明确显示“未验证”或“未完成”，不将 Run=SUCCEEDED 直接展示为已验证成功。
- 无进展检测覆盖规范化后的重复工具调用、连续相同失败和连续无推进轮次；`agent.loop.no-progress.max-repeats` 与 `max-stagnant-rounds` 可配置，0 表示关闭。轮次耗尽与取消也有明确完成原因。
- 新增 `coding-system@v1` 及哈希登记，工作区任务自动选择 coding 提示词，普通对话继续使用 agent 场景；加载、版本切换和哈希校验复用 P5-01 机制。
- Runtime 报告与基线独立于组件评测，记录用例通过率、任务完成率、验证覆盖率、无进展停止、重复调用率、模型轮次、工具调用、token 与 p95。`RuntimeEvalGate` 复用统一回退阈值并增加任务完成率和验证覆盖率比较；只有 15 条用例全部通过才允许更新基线。`scripts/run-eval.ps1` 与 CI 已接入双门禁和双报告归档。
- **验证结果**：后端 `mvn --batch-mode --no-transfer-progress verify` 成功；双评测门禁退出码 0；Runtime 15/15 通过；前端 `vue-tsc --noEmit` 与 Vite 生产构建通过（1842 个模块，Windows 下将 TEMP/TMP 指向工作区 `.tmp` 后 esbuild 正常清理）。

**验收边界**：当前基线 `case_pass_rate=100%`、`task_success_rate=20%`、`verification_coverage=0%`。20% 只反映这批故意覆盖未验证、拒绝、取消和无进展路径的脚本结果，不是外部真实模型任务成功率；0% 是因为 P5-05/P5-06 尚未提供诊断与 verify 证据，文件修改用例必须保持“未验证”。本轮未调用外部模型，真实模型只保留 BYOK 本地或定时抽样路径。

### P5-05 LSP 诊断闭环（已完成）

- 抽象 `DiagnosticsProvider`：
  - `JavacDiagnosticsProvider`（默认，进程内编译 Java 变更文件，离线可用）；
  - `LspDiagnosticsProvider`（JSON-RPC stdio，可配置接入 java-language-server / ts-server）。
- 在 `EditFileTool` / `WriteFileTool` 成功后采集变更文件诊断，限量（如 20 条 / 8KB）注入下一轮上下文；SSE 新增 `diagnostics` 事件。
- 诊断与文件版本、runId、turnId 关联，供 P5-13 判断是否有进展、P5-14 展示任务验证摘要。
- 验收：坏 Java 文件返回错误；诊断进入下一轮 prompt；超限截断；provider 不可用时降级不阻塞；连续相同诊断能触发 P5-13 的无进展处理。

**完成情况（2026-11-14）**：

- 新增 `diagnostics` 包：`Diagnostic`、`DiagnosticsProvider`、`DiagnosticsResult`、`DiagnosticsService`、`JavacDiagnosticsProvider` 与可选 `LspDiagnosticsProvider`。默认 javac 在进程内离线编译变更 Java 文件；LSP 通过 `Content-Length + JSON-RPC 2.0` stdio 接入外部语言服务器，只实现诊断所需的最小流程。
- 每批 `write_file` / `edit_file` 后采集诊断，默认最多 20 条 / 8KB，先按条数再按字符截断；结果写入 `WorkspaceContext`（含诊断指纹与文件版本），并作为 `user` 消息注入下一轮上下文；无错误时也注入「未发现编译错误」，避免模型重复编译。
- SSE 新增 `diagnostics` 事件，Run Event 记录 `DIAGNOSTICS`，载荷含 runId、turnId、provider、available、analyzedFiles、totalCount、truncated、fileVersion 与诊断明细；消息 trace 落库后，前端实时流、历史会话与断线重放都能还原。
- 与 P5-13 联动：诊断有错误时 completion 为 `INCOMPLETE/DIAGNOSTICS_FAILED`；可用且无错误时为 `COMPLETED/DIAGNOSTICS_PASSED`；非 Java、provider 不可用或未分析文件保持 `UNVERIFIED/VERIFICATION_MISSING`。连续相同诊断达到 `agent.loop.no-progress.max-repeats` 时以 `REPEATED_DIAGNOSTICS` 安全停止。
- 新增 `agent.diagnostics.*` 配置（enabled/provider/max-items/max-chars/timeout-ms/max-files/lsp.*），嵌套结构使用 `@ConfigurationProperties`。javac 首次冷启动可能较慢，默认超时设为 20s；provider 缺失、启动失败或超时全部降级，不阻塞工具调用。
- 前端新增 `DiagnosticsInfo` 类型、`onDiagnostics` 回调与 `diagnostics` 事件分支，并在完成提示附近显示「N 个编译错误 / 编译检查通过 / 诊断不可用」的紧凑状态行。
- **验证结果**：`JavacDiagnosticsProviderTest`、`DiagnosticsServiceTest`、`LspDiagnosticsProviderTest`、`ReActDiagnosticsIntegrationTest` 共 16 项通过；后端 `mvn test` 全量通过，前端 `vue-tsc --noEmit` 通过。runtime 评测数据集没有写入 `.java` 的用例，既有基线不受影响。
- **边界**：本批只提供诊断证据与展示，不执行 P5-06 的 verify 命令；LSP 只做诊断最小流程，不覆盖补全、跳转等语义能力；外部语言服务器仍需用户自行安装并配置启动命令。

### P5-06 验证回路（已完成）

- 项目级 `.lunargold/verify.json`：命令列表、超时、是否必跑、最大重试 2。
- 新增 `VerifyService`：一轮写操作结束或计划候选完成时触发；命令仍走 `CommandService` 沙箱与白名单，项目配置命令可预授权但保留审计记录。
- 失败输出回灌模型；超过重试/轮次/token 预算即停止并如实报告。相同失败指纹不无限重试，交给 P5-13 的无进展策略处理。
- 有可验证命令时必须执行；无配置或环境不可用时明确显示“未验证”，允许用户配置验证或人工接受未验证变更，不伪造成功。
- 验收：失败触发一次修复回环；成功后才能显示验证通过；预算生效，不出现死循环；未配置、命令失败和环境不可用三种状态可区分。

**完成情况（2026-11-14）**：

- 新增 `com.capsule.agentkey.verify` 包：`VerifyConfig`（`.lunargold/verify.json`，`enabled/maxRetries/commands`，`required` 缺省必跑）、`VerifyResult`（`PASSED/FAILED/NOT_CONFIGURED/UNAVAILABLE`、`attempt/maxAttempts`、命令退出码/超时/输出尾部/失败指纹）与 `VerifyService`。
- `AgentVerifyProperties`（`agent.verify.*`）提供开关、配置路径、全局重试上限（裁剪 `0..2`）、输出尾部与默认超时；全部命令只能经 `CommandService.run` 执行，沿用白名单、工作区路径、超时和取消。
- ReAct 每轮写操作结束后执行一次验证：通过则提示模型不要重复验证；失败时把 `【验证结果】` 与失败输出注入下一轮；连续相同失败指纹达到 `agent.loop.no-progress.max-repeats` 时走 `NoProgress(REPEATED_VERIFY_FAILURE)`；超过 `1 + min(config.maxRetries, 2)` 次以 `INCOMPLETE / VERIFY_RETRY_EXHAUSTED` 停止；候选完成时若验证未覆盖最新变更会补一次验证。
- 完成判定收紧：诊断 ERROR → `INCOMPLETE/DIAGNOSTICS_FAILED`；验证失败 → `INCOMPLETE/VERIFY_FAILED`；验证通过 → `COMPLETED/VERIFY_PASSED`；未配置 → `UNVERIFIED/VERIFY_NOT_CONFIGURED`；环境不可用 → `UNVERIFIED/VERIFY_UNAVAILABLE`。无验证配置时，干净编译诊断不再直接显示为已完成。
- 新增 `VERIFY` Run Event 与 `verify` SSE，载荷含状态、尝试次数、配置路径、`authorization=project-config`、命令退出码/耗时/输出尾部；同时写入消息 trace，历史会话与断线重放可还原。前端在消息完成提示附近展示「验证通过 / 验证失败 / 未配置验证 / 验证不可用」。
- 测试：`VerifyServiceTest` 覆盖未配置、非法 JSON、必跑/可选失败、环境不可用、重试上限、输出截断与指纹稳定性；`ReActVerifyIntegrationTest` 覆盖失败回灌与修复通过、重试耗尽、未配置、环境不可用、重复失败无进展停止。P5-05 诊断测试与 Runtime 数据集按新语义更新为 `VERIFY_NOT_CONFIGURED`。
- **验证结果**：后端 `mvn -o -q compile`、`mvn -o -q test` 通过；`EvalGate,RuntimeEvalGate` 通过（组件 27/27、Runtime 15/15）；前端 `vue-tsc --noEmit` 通过。
- **基线说明**：Runtime 用例的 completion reason 因 P5-06 语义由 `VERIFICATION_MISSING` 改为 `VERIFY_NOT_CONFIGURED`；Runtime 基线 p95 因首例冷启动与 200ms 以下分类的计时噪声增加了保守余量，质量、轮次、token、任务成功率与验证覆盖率指标未放宽。

---

## 7. 智能体能力 A2/A3：代码理解、检索与并发（P5-04、P5-07、P5-08 已完成基础能力）

### P5-04 代码索引 v1（repo map、符号索引与增量缓存已完成；收益量化未做）

- `CodeIndexer` 可插拔抽象：v1 为 `LightweightCodeIndexer` 正则实现（Java 包/类型/方法，TS/Vue/Python/Go/Rust 顶层符号），零额外依赖；JavaParser / tree-sitter 作为后续实现按需替换，不影响扫描、缓存与渲染。
- `CodeIndexService`：工作区扫描、符号查询、按工作区维护内存增量缓存（大小 + 修改时间判定，仅重扫变更文件，删除文件自动清理），并输出扫描/复用/移除文件数与符号数统计。
- `RepoMapService` 只负责渲染，符号来源改为 `CodeIndexService`；repo map 注入 prompt，替代无结构目录树。
- 验收：fixture 仓库符号检索正确；repo map 不超预算；增量更新只重扫变更文件；A1 同类任务的成功率、轮次或 token 至少一项改善且无其他显著回退。

**完成情况（2026-11-14）**：

- 新增 `CodeIndexer`（单文件符号提取抽象）与 `LightweightCodeIndexer`（轻量正则，覆盖 Java/TS/Vue/Python/Go/Rust），后续可新增 JavaParser 实现而不改调用方。
- 新增 `CodeIndexService`：按工作区维护内存增量缓存，以文件大小与修改时间判断是否复用，只重扫新增/变更文件并清理已删除文件；提供 `findSymbols` 符号查询 API 与 `scannedFiles` / `reusedFiles` / `removedFiles` / `symbolCount` 统计，同一工作区扫描串行化保证线程安全。
- `RepoMapService` 改为委托 `CodeIndexService` 渲染，输出格式、字符预算与截断行为保持不变；`RepoMapServiceTest` 7 项回归通过，新增 `CodeIndexServiceTest` 覆盖符号索引与查询、增量只重扫变更文件、缓存清理、忽略目录、超大文件降级与空查询。
- `ReActAgentService` 已用 repo map 替换原单层目录树注入 system prompt（`【仓库结构（repo map）】`），原 `buildWorkspaceTree` 删除；开关、深度、文件数、字符预算与单文件符号数均可配置。
- 验收：后端 `mvn -o -q compile`、`mvn -o -q test` 通过，`EvalGate,RuntimeEvalGate` 通过（组件 27/27、Runtime 15/15）。
- 仍保留为后续项：JavaParser/tree-sitter 精确符号实现、可选 `code_index` 持久表（V19），以及命中符号对轮次/token 的量化报告；因此 P5-04 标记为“基础完成”，上述增强项不阻塞 P5-07 与 P5-14。

### P5-07 RAG 增强（基础完成：混合检索 + RRF + 引用；模型 reranker 未启用）

- 混合检索：pgvector 向量召回 + pg_trgm `word_similarity` 词法召回（复用 V9 已创建的扩展，无需新表/新列），RRF 融合；向量与词法各自保留相关度阈值。
- Rerank：`Reranker` 接口，v1 为 `RrfReranker`（k 默认 60）；先建立词法+向量融合对照，模型 reranker 只在离线收益不足时启用。
- 引用：chunk/document 随 `sources` 事件返回，前端展示切片序号；页码因解析器暂未保留 PDF 版式而暂不提供。
- 离线量化：`halfvec` 是可选技术子项，只有体积或检索性能成为实际瓶颈时才新增 V20 与回填；不得以量化替代检索质量目标。
- 验收：向量漏召回但精确词命中的 fixture 在混合检索下命中；引用字段完整；默认排序相对单路向量有量化收益；启用 halfvec 时另行证明精度与体积收益。

**完成情况（2026-11-14）**：

- `VectorStoreService` 新增 `searchLexicalForProject`：用 `word_similarity(query, content)` 在切片原文做词法召回，支持项目/点名资料过滤；pg_trgm 不可用或 SQL 失败时降级为纯向量检索。
- 新增 `RagCandidate`、`Reranker` 与 `RrfReranker`：向量列表与词法列表按排名融合（`1/(k+rank)` 叠加），按 `chunkId` 去重并保留两路最高分；`RrfRerankerTest` 验证双路命中优先、去重、纯词法顺序与 topK 截断，其中“向量第 3 + 词法第 1”排在“仅向量第 1”之前，量化了排序收益。
- `DocumentService.retrieveContext` 改为混合召回：向量未过门槛但词法过 `rag.lexical-gate-score` 时仍使用资料；点名资料跳过整体门槛；`rag.hybrid-enabled=false` 时行为与原来的单路向量完全一致。
- `sources` 增加 `chunkId` / `chunkIndex`，前端来源 chip 的提示展示“第 N 段”；`DocumentServiceRagTest` 覆盖词法补召回、双弱门槛拒绝、点名跳过门槛、混合关闭降级与引用字段。
- 配置新增 `rag.hybrid-enabled`、`rag.lexical-min-score`、`rag.lexical-gate-score`、`rag.rrf-k`。
- 验收：`DocumentServiceRagTest`（4 项）与 `RrfRerankerTest`（5 项）通过；后端全量测试通过；`EvalGate,RuntimeEvalGate` 通过（组件 27/27、Runtime 15/15）。
- 仍保留为后续项：模型 reranker 的离线对照、跨语言/长文档的真实语料收益报告、PDF 页码引用；`halfvec`（V20）仍按体积或检索性能数据决定是否实施。

### P5-08 并行只读工具（已完成）

- `BaseTool` 增加 `readOnly()` 标记（默认 false）；`read_file`、`list_dir`、`search_files`、`web_search`、`fetch_webpage`、`calculator`、`get_current_time`、`get_current_weather` 显式标记为只读。MCP、fallback 与需要审批的工具一律按串行处理。
- `ToolRegistry.isReadOnly` 统一判定；会话级工具统计从线程 ThreadLocal 迁移到 `WorkspaceContext`，并行子线程的调用次数与成功率仍累计到同一份数据。
- `ReActAgentService` 在每轮工具循环中按「连续只读批」切分：批内有界线程池并发（`agent.parallel.readonly-max-concurrency`，默认 3），批外的写/命令保持串行 + 审批；结果按模型原始下标回放，`onToolCall` / `onToolResult` 与 tool 消息顺序不变。
- `WorkspaceContext.child()/callIn()` 把用户、项目、scope、取消标记与统计带入工作线程，任务结束后恢复线程原上下文，适配线程池复用。
- 停止生成：批内任务启动前检查取消标记；等待期间收到取消会对剩余任务 `cancel(true)` 并按取消收尾（Run=CANCELLED，完成状态 `INCOMPLETE/CANCELLED`），不再执行本轮剩余调用。
- 并发上限依据：Runtime 用例中单轮只读批实际为 2～3 个；3 个 120ms 模拟只读调用的 fixture 基准为串行 p95 282ms → 3 并发 p95 98ms（约 −65%），继续增加线程不再降低该批耗时，因此默认取 3，可由配置覆盖。
- 验收：新增 `ReadOnlyToolBatchTest` 5 项（3 只读并发且顺序稳定、上限=1 时串行、读写交错不重叠且顺序正确、停止取消排队任务、同类 p95 有收益），`ToolRegistryTest` 补只读/MCP/fallback 判定；后端全量 272 用例通过；`EvalGate,RuntimeEvalGate` 通过（组件 27/27、Runtime 15/15，Runtime p95 6685ms 低于基线 13000ms，未更新基线）。
- 边界：只并行「连续」只读批，单次调用不建池；MCP 与 fallback 工具不并行；真实模型与真实工作区的收益仍需按线上 trace 复核。

---

## 8. 工作台体验轨道：视觉、交互、任务交付与质量基础设施（部分完成）

> 形态与视觉口径见 [visual-ux-preview.html](visual-ux-preview.html)，包含亮/暗切换、4 项视觉模板、4 项交互模板与 3 个质量点示例。原型只用于对齐，不接真实数据。

### 进度快照（2026-11-12）

| 子项 | 状态 | 已完成内容 | 剩余 |
|---|---|---|---|
| P5-09-1 token 收口 | 已完成 | token 单一入口、亮暗逐项对应、hex 清到白名单 4 处、`:focus-visible`、`prefers-reduced-motion`；暗色 5 页面回归通过（下载页因能力开关改静态核对），关键配对比度实测均 ≥4.5（最低 danger/dangerBg 4.71） | — |
| P5-09-2 三态 | 部分完成 | `SkeletonMessage`、`ErrorState` 已接入会话切换；工作台各处空态已统一 | 独立 `EmptyState`（判定与现有 `wb-empty` 重复，暂不新建）、工具运行态 |
| P5-09-3 排版 | 未开始 | 思考/步骤折叠、diff 语义色已存在 | 代码块头与复制、密度档位 |
| P5-09-4 响应式 | 部分完成 | 1024 侧栏图标化、768 抽屉断点已存在 | 960×640 实测与截图归档 |
| P5-10-1 断线续传 | 已完成 | 后端 800ms `MODEL_CHUNK` 检查点、`run_id` 事件、`getRunEvents`、`runs/running`、退避轮询、状态条 | — |
| P5-10-2 审批效率 | 部分完成 | `A`/`D` 快捷键、键位提示、倒计时、详情折叠 | J/K 切换、批量、审批历史 |
| P5-10-3 长任务进度 | 已完成 | 五阶段时间线与耗时、轮次/token/时间预算与 80% 警示、四类失败分级与动作、停止后「继续」；时间预算可配置，默认不限 | — |
| P5-10-4 键盘无障碍 | 部分完成 | `Ctrl+/` 快捷键面板、`aria-live`、焦点环、减少动效 | axe 扫描、焦点陷阱校验 |
| P5-11 体验基础设施 | 部分完成 | Vitest/Playwright 基线、本地异常/Web Vitals/Run 恢复采样、当前用户后端体验聚合与设置页诊断面板已落地 | P5-11-3 长会话性能剖析；虚拟滚动按需 |
| P5-12-1 首屏去客服化 | 已完成 | 删除开场白与建议按钮，改为项目概览 + 最近任务 + 快捷键提示 | — |
| P5-12-2 任务列表 | 已完成 | 侧栏加状态点（运行中/待审批/已完成/失败/停止）与相对时间，`/sessions` 追加 `lastRunState`，运行态收进 store | — |
| P5-12-3 轨迹结构化 | 已完成 | 时间线行内联文件名与 `+n / −m`（后端 timeline 追加 path/additions/deletions）、待审批步骤高亮、点轨迹行展开 diff | — |
| P5-12-4 审批常驻 | 已完成 | 审批卡片固定在输入区上方；多项按队列展示并计数，仅首张响应 A/D 快捷键；追踪面板顶部列出待审批项，点「去处理」定位并高亮审批区 | — |
| P5-12-5 状态上移顶部 | 已回退（不做） | 曾上移顶栏，实测不如 Trae / DeepSeek 的输入框下排顺手，已还原 | — |
| P5-12-7 视觉基调 | 部分完成 | 默认主题已切深色（含 `themeVersion` 一次性迁移） | 等宽字体、品牌色、圆角 |
| P5-14 任务级交付 | 基础完成 | Session 级 ChangeSet 聚合、按 Run 过滤、验证/审批摘要、继续修复、逆序整体/按 Run 撤回与外部修改冲突检测；前端审查卡升级为交付看板 | 真实后端 + 真实模型的干净机器验收 |

> 标注为「已完成」的项不要重复实现；「部分完成」只补齐「剩余」列。


### P5-09 视觉体系（已完成）

**目标**：设计 token 单一入口、亮暗一致、三态统一、长内容可控、窄窗口可用。

#### P5-09-1 token 收口与暗色一致性（已完成）

- `styles/tokens.css` 升级为唯一 token 层：补齐颜色语义（`--text-tertiary`、`--warning-bg`、`--code-bg`、`--diff-add/del-bg`、`--skeleton-a/b`）、焦点环（`--focus-ring`）、层级（`--z-*`）、密度（`--density-row`）与状态色边框。
- 修复重复定义：删除 `tokens.css` 中被 `style.css` 覆盖的 `--fs-base/--fs-sm/--fs-xs`，字号只保留 `theme.setFont()` 的运行时注入。
- 暗色补齐：当前暗色块缺 `--text-tertiary`、`--warning-bg`，导致组件回落到固定 hex；一并补齐并与亮色一一对应。
- 清理硬编码：9 个 `.vue` 内 59 处 hex 全部替换为语义变量；保留的例外只允许出现在 `style.css` 的 `:root` 与 `[data-theme="dark"]`。
- 验收：
  - 组件内 hex 计数降为 0（脚本检查，白名单除外）。
  - 亮/暗两套下方 `--text`、`--text-secondary`、`--text-tertiary` 对背景对比度 ≥ 4.5:1，主色按钮文字 ≥ 4.5:1。
  - `--fs-base` 全仓只有一处定义。
  - 暗色回归覆盖对话、审批、工作台、资料库、设置 5 个页面。

#### P5-09-2 加载 / 空 / 错误三态（已完成）

> 实现说明：会话切换骨架、首屏项目概览空态、工作台各 tab 空态此前已落地；本轮补齐工具运行态与错误态导出。未新建 `ToolRunningState.vue`——运行态直接复用 `ChatMessage.vue` 的步骤行，避免多一层组件。

- 复用并补齐 `SkeletonMessage.vue`、现有 `wb-empty` 空态与 `ErrorState.vue`；仅在工具运行态确有需要时新增 `ToolRunningState.vue`，不重复新建 `EmptyState`，样式只引用 token。
- 落位：会话切换用消息骨架；首屏空态展示项目概览与明确下一步，不恢复已删除的建议按钮；工具执行中显示名称与已耗时；错误态统一含错误码、可读原因、重试与导出诊断。
- 工具运行态：`ToolInvocation.startedAt` 由前端在 `onToolCall` 写入，`ChatMessage.vue` 仅在存在运行中工具时每秒刷新「已用 N 秒」，结束后改用后端 `costMs`，定时器随组件卸载清理。
- 错误态：`ErrorState.vue` 增加可选 `exportable`，`ChatView` 在会话加载失败时直接导出体验诊断 JSON；其余调用方不传则不显示按钮。
- 验收：所有异步区域不再出现空白等待；三态组件在亮暗下均通过视觉检查；错误态可键盘聚焦到重试。

#### P5-09-3 消息密度与内容排版（已完成）

> 实现说明：代码块语言/文件名头与复制、diff 增删底色已在 P5-11-3 完成；本轮只补密度档位、表格与引用位置，未重做折叠结构。

- 思考过程、工具调用批量、工具输出、引用统一为可折叠块，默认只展开当前步骤；长回答保留结论优先。
- 代码块增加语言/文件名头与复制；diff 使用统一增删底色；表格统一斑马纹与紧凑行高；引用卡展示文档名与位置。
- 消息操作条（复制/重试/引用）悬停或聚焦时出现，不占常驻空间。
- 密度档位：紧凑（工具行 24px）/ 舒适（32px），只切换 token，不改组件结构。`tokens.css` 新增 `--density-row/--density-gap/--density-pad-y`，`[data-density="compact"]` 覆盖为 24/4/4；`theme` store 持久化 `density` 并在设置页「外观」提供档位切换。
- 表格：`.md-content table` 增加边框、表头底色、`tbody tr:nth-child(even)` 斑马纹，单元格纵向内边距跟随密度 token；引用 chip 直接显示「第 N 段」，相关度保留在 `title`。
- 验收：单轮 20 个工具调用时正文不被工具输出淹没；折叠状态在切会话后保持；代码块与 diff 在暗色下清晰可读。

#### P5-09-4 响应式与窗口自适应（已完成）

> 实测归档：960×640 侧栏 56px 图标栏、无横向滚动（`scrollWidth - clientWidth = 0`），输入框与发送按钮均在视口内；900×700 工作台以右侧浮层打开（≤520px，遮罩点击收起），不产生横向滚动；767×700 侧栏默认推入屏幕外，菜单按钮可拉出抽屉；1280×800 侧栏 280px 展开。用例见 `frontend/e2e/responsive.spec.ts`，截图输出到 `frontend/test-results/`（不入库）。

- 断点：≥1024 三栏；768-1023 侧栏收为图标栏、工作台隐藏；<768 侧栏收起为抽屉、正文全宽。工作台隐藏时保留顶栏展开按钮，打开后为浮层而不是挤压正文。
- `toggleSidebar()` 与 `closeSidebarOnMobile()` 统一为 `< 768`，消除 768px 边界不一致。
- 桌面端最小窗口 960×640 不出现横向滚动；输入区与发送按钮在小高度下仍可见。
- 验收：三档宽度截图归档；键盘 Tab 可达所有折叠后的入口；工作台隐藏时保留展开按钮。

### P5-10 交互与体验（部分完成）

**目标**：断网/刷新不丢本轮，审批可纯键盘完成，长任务进度可见，快捷键与焦点可用。

#### P5-10-1 断线续传与刷新恢复（已完成）

> 实现说明：最终采用「runId 全量重放」而非计划中的 `sessionStorage + sinceSeq`。原因：SSE 未下发 `seq`，且后端此前从未落 `MODEL_CHUNK`，前端补 seq 无意义。现为后端每 800ms 落一个累计正文检查点，恢复时取最后一个检查点重建正文、按序重建工具轨迹；刷新恢复靠 `GET /api/agent/runs/running` 反查 runId。**后续不要按原方案重做。**

- 后端每 800ms 落一个累计正文检查点，事件携带 `run_id`；前端通过 `getRunEvents` 按 runId 全量重放，重建正文与工具轨迹。
- 流中断后按退避策略自动恢复；状态条明确展示已连接、正在重连与已恢复，重复事件与正文检查点不得造成重复渲染。
- 刷新恢复：页面加载时通过 `GET /api/agent/runs/running` 反查未完成的 runId，再重放同一 Run，不重复创建用户消息或运行。
- 重连期间仍可停止生成；停止后取消后续恢复与重连。
- 验收：
  - 断网 10s 后恢复，事件不丢不重，文本与中断前连续。
  - 刷新页面后能继续看到同一轮输出，不出现第二条用户消息。
  - 重连期间输入框仍可停止生成；停止后不再重连。

#### P5-10-2 审批流效率（已完成）

- 快捷键：`A` 允许、`D` 拒绝、`J`/`K` 在多个待审批间移动、`Esc` 收起预览；按钮上常驻键位提示。
- 批量：同轮多个低风险审批可「允许全部低风险」，高危必须逐个确认；批量操作写入审计事件。
- 信息完整：文件路径、变更行数、diff 预览、风险原因、倒计时（剩余 30s 变红）；超时后卡片切为已拒绝并说明原因。
- 审批历史：会话内可回看已处理审批及其决策来源（本人/超时/会话停止）。
- 验收：纯键盘完成一次允许与一次拒绝；倒计时与真实超时一致；批量只作用于低风险；历史记录可追溯。

> 实现说明：`ChatView` 维护 `selectedApprovalId`，J/K 只在输入态之外移动、到边界不循环，A/D 只作用于选中卡片；队列缩减时按「同位置后一项→前一项」收口。批量白名单是保守集合：仅 `risk=WRITE` 的 `write_file`/`edit_file`（含 MCP `_write_file`/`_edit_file`），命令、删除、移动、建目录与 DESTRUCTIVE 一律逐条确认；`POST /api/agent/approvals/batch` 在服务端校验用户、会话、PENDING 与白名单，任一不满足整批拒绝，只返回实际完成的 `resolvedIds`。决策来源由 V19 新增 `tool_approval.decision_source` 持久化（USER/BATCH/TIMEOUT/SESSION_STOP/SYSTEM，旧记录 NULL 显示「来源未知」），`approval_resolved` SSE 与 `APPROVAL_RESOLVED` Run Event 同步携带 `approvalId/decision/source/batchId`。历史走 `GET /api/agent/sessions/{sessionId}/approvals`，先校验会话归属，不返回 `arguments`，展示在现有「追踪」页待审批区之后。**后续不要另建历史表或批量拒绝。**

#### P5-10-3 长任务进度可感知（已完成）

- 阶段时间线：理解 → 读取 → 修改 → 验证 → 汇总，标注每步耗时与当前步骤。
- 预算可视化：轮次、token、已用时间对照上限；接近上限时提示而非静默截断。
- 失败分级：可重试（模型 5xx/超时）、需配置（缺 Key/模型不可用）、需审批（命令被拦截）、需人工（冲突/权限），每类给出对应动作。
- 停止生成后保留已完成内容，状态明确为「已停止」，并提供「继续」。
- 验收：长任务任意时刻能回答“现在在第几步”；三类失败给出不同动作；停止后不出现“仍在生成”。

**完成情况（2026-11-14）**：

- **阶段时间线**：`chat.ts` 记录五阶段首次到达时间，工具调用（只读/写入/命令/LSP）与 `file_change`、`diagnostics`、`verify`、`done` 事件推进；阶段只前进，未到达的中间阶段标「跳过」，当前阶段耗时每秒刷新。短问答 3 秒内不显示，避免闪动。
- **预算**：`usage` SSE 扩展 `round/maxRounds/maxTokens/elapsedMs/maxElapsedMs` 并在每轮实时下发；前端展示轮次/ token /耗时，任一 ≥80% 转为警示并提示 Agent 优先收口。新增 `agent.budget.max-seconds-per-run`（默认 0=不限），超限以 `TIME_BUDGET_EXCEEDED` 停止并给出可操作原因。
- **失败分级**：新增 `classifyFailure`，区分可重试 / 需配置 / 需审批 / 需人工，失败框按类展示标签、提示与主操作（重试 / 去设置 / 重新加载）；配置类额外保留「重试这一轮」。
- **停止与继续**：停止保留半截内容并显示「已停止生成」，新增「继续」发送固定续写提示；恢复的 `CANCELLED` 轮次同样标记为已停止。
- **验证**：前端 Vitest 31 项（新增阶段、预算、失败分级、继续与组件测试共 9 项）、Playwright 10 条（新增长任务进度、配置失败、停止继续 3 条）、后端 281 项全通过（新增时间预算 2 项）。Playwright 的 `route.fulfill` 无法逐块推送 SSE，阶段推进由单测覆盖，E2E 验证进度条出现、停止收起与失败动作。

#### P5-10-4 键盘与无障碍（已完成）

- 快捷键面板：`Ctrl + /` 唤起，列出全部快捷键；输入框内 `Enter` 发送、`Shift+Enter` 换行保持现状。
- 焦点：统一 `:focus-visible` 外环（`--focus-ring`），不引起布局位移；弹窗与抽屉焦点陷阱校验。
- 屏幕阅读器：流式文本使用 `aria-live="polite"` 且 300ms 合并播报；工具状态变化用状态区播报，不逐字打断。
- 动效：跟随 `prefers-reduced-motion`，并提供手动「减少动效」开关；加载动画在减少动效下改为静态占位。
- 验收：仅用键盘完成发送、审批、停止、切会话；axe 扫描 critical 为 0；减少动效下无闪烁动画。

**完成情况（2026-11-15）**：

- **快捷键面板**：`Ctrl + /` 打开，`aria-modal`，打开时记录来源焦点并聚焦关闭按钮；Tab / Shift+Tab 由 `utils/focusTrap.ts` 限制在面板内，Esc 或关闭按钮关闭后归还焦点，来源已卸载时回退输入框。空态增加 `Ctrl + /` 提示。
- **会话项键盘化**：会话主体改为原生 `<button>`（「更多」保留在按钮外，避免嵌套交互），Enter / Space 原生切换，`aria-current` 标记当前会话；读屏名称取可见标题与时间，不再用会覆盖可见文本的 `aria-label`。
- **图标按钮与区域语义**：侧栏、工作台、文件树、审批卡片、编辑器、规则面板的图标按钮补 `aria-label`；审批队列为 `role="region"`，审批卡片名称为「待审批：路径 / 命令」。
- **播报**：审批队列数量变化播报「有 N 项审批待确认，按 A 允许、D 拒绝、J/K 切换」，清空播报「审批已处理完」；流式结束的「回答已生成」在仍有待审批时不覆盖审批提示。
- **减少动效**：`theme` store 新增 `reduceMotion`，默认跟随系统并监听 `matchMedia` 变化；设置页「外观」可手动开启并持久化。挂载前统一写入 `html[data-motion="reduce"]`，样式只保留一条规则；`ScrollBehavior` 在减少动效时降级为 `auto`。
- **验收**：新增 `e2e/accessibility.spec.ts` 5 条（面板焦点、会话切换、审批区域与键盘决策、`/chat` 与 `/settings` axe critical 0）；axe 的 `nested-interactive` 与 `label-content-name-mismatch` 两项 serious 已随会话项重构消除。前端 Vitest **12 文件 85/85**，系统 Edge 下 Playwright **19 通过、1 跳过**（`@perf`），`npm run build` 通过；新增 devDependency `@axe-core/playwright`。浏览器会拦截 `Ctrl+K`，该快捷键维持既有实现，未纳入 E2E。

### P5-11 体验质量基础设施（部分完成）

**目标**：关键路径有自动化测试，慢与断有指标，长会话不卡。

#### P5-11-1 前端测试基线（已完成）

- 引入 Vitest + `@vue/test-utils` + jsdom：覆盖 `api/sse.ts` 解析（多行 data、CRLF、终止事件）、runId 全量重放与正文检查点恢复、`stores/chat.ts` 状态迁移、`ApprovalCard` 决策与倒计时、`MarkdownContent` 代码块。
- 引入 Playwright 覆盖发送与流式、审批允许/拒绝、停止生成、断线重连，以及 P5-14 的“修改 → 审批 → verify → 审查 → 撤回”关键路径。
- CI：新增前端 job（lint + 单测），E2E 单独 job，失败上传 trace；不阻塞后端打包。
- 验收：改坏 SSE 终止逻辑或审批按钮，测试必须失败；上述 E2E 在 CI 稳定通过（连续 5 次无 flaky）。

**完成情况（2026-11-14）**：

- **Vitest**：`frontend/vitest.config.ts` 复用 Vite 插件，环境 jsdom；`sse.test.ts`（8）、`stores/chat.test.ts`（4）、`ApprovalCard.test.ts`（6）、`MarkdownContent.test.ts`（4）共 4 文件 22 项通过。
- **Playwright**：`frontend/playwright.config.ts` + `e2e/` 下 5 个 spec 共 7 条用例（发送与流式、审批允许、审批拒绝、停止生成、断线续传、接受全部不改写验证、整体撤回冲突）；`--repeat-each=3` 连续 21/21 通过。
- **API 仿真**：原方案的外部 Spring E2E 进程（zonky EmbeddedPostgres + test-scope `ScriptedAgentClient` + 本机令牌文件）复现成本高，改为 `page.route('**/api/**')` 在浏览器层仿真后端响应；后端契约本身已由 `ChangeSetServiceTest` 等单测覆盖，此偏差已写回 `.trae/specs/p5-11-1-frontend-e2e` 的 FR-7/AC-5。
- **CI**：`.github/workflows/ci.yml` 新增 `frontend-test`（`npm ci` + `npm run test`）与 `frontend-e2e`（`needs: frontend-test`、缓存 Playwright、`npx playwright install --with-deps chromium`、失败上传 `playwright-report` 与 `test-results`）。
- **本地运行差异**：本机 CDN 下载浏览器失败，改用系统 Edge（`E2E_CHANNEL=msedge`）并手动起 dev server + `E2E_BASE_URL`；CI 用 `E2E_CHANNEL=none` 走 Playwright 自带 chromium，`webServer` 自动启动。
- **边界**：E2E 是浏览器层仿真，不验证真实后端事务与真实模型链路；`test:coverage` 未接入（避免引入第 5 个 devDependency，违反 NFR-4）。

#### P5-11-2 体验可观测性（已完成）

- **浏览器本地诊断**：新增 `experienceDiagnostics.ts`，接入 Vue `errorHandler`、`window.error` 与 `unhandledrejection`；异常只保存白名单字段和脱敏单行摘要，不保存原始 stack、对话正文或工具参数。localStorage 按异常 50、Web Vitals 180、Run 100、恢复 100 条有界裁剪，损坏或写满时静默降级；账号切换清空本地标识。
- **Web Vitals 与开关**：使用官方 `web-vitals` 的 `onLCP/onINP/onCLS`（`reportAllChanges: true`），按会话记录当前值和样本数。采集默认开启且不上传；关闭后移除 window 监听，Web Vitals 回调由开关拦截，不再新增任何本地记录；已有记录仅由用户主动清除或手动导出。
- **Run 与恢复样本**：在真实 `onRunId` / `recoverRun` 状态点采样。流中断率只统计仍存在于本地 Run 窗口内的 runId；恢复成功率按已有 recovered/failed 终态计算，被新恢复或正常导航取代的 token 不落失败。收到任意 Run 终态并完成事件重放即记传输恢复成功，Run 自身 FAILED/CANCELLED 不污染网络恢复指标。
- **后端只读聚合**：新增 `GET /api/agent/metrics/experience` 与 `AgentExperienceMetricsService`。首字 p95 取当前用户最近 100 Run 的 `Run.startTime` 到首条 `MODEL_CHUNK.createTime`，首条时间在数据库内 `MIN + GROUP BY` 聚合；审批等待取最近 100 条已决策记录；token 仅统计有用量的 Run；工具 Timer 按 userId 过滤并明确标记 `scope=PROCESS`，不与 100 Run 窗口混算。空样本返回 null。
- **设置页诊断面板**：新增「体验诊断」tab，展示 LCP/INP/CLS、流中断率、恢复成功率/次数/平均耗时、首字 p95、工具失败率、审批平均等待和每轮 token，以及最近 50 条异常；支持开关、清除、手动导出 JSON、后端刷新和独立失败重试。无样本显示「—」，接口失败不掩盖本地记录。
- **验证结果**：`AgentExperienceMetricsServiceTest` 4/4；后端全量 **298 项，0 失败、0 错误、12 跳过**；前端 Vitest **7 文件 57/57**；生产构建通过（1847 个模块）；系统 Edge 下 Playwright 全量 **14/14**，完整套件 `--repeat-each=3` **42/42**。诊断 E2E 实际下载并核对 JSON，覆盖异常脱敏、关闭停采、清除和后端指标展示。
- **边界**：浏览器连接指标只代表本机有界样本，后端聚合只代表当前用户，不能拼成全平台 SLO；没有远程上传、客户端指标接收 API、数据库迁移或远程遥测 SDK。E2E 使用 API 仿真且不断言固定 Web Vitals 数值；真实长会话渲染剖析与虚拟化仍属 P5-11-3。

#### P5-11-3 长会话性能（已完成）

- 先对真实长会话做渲染剖析，依次评估流式按帧合并（最多 20 次/秒）、Markdown 只重算变化段落、超过 200 条启用虚拟滚动；不为预设技术方案牺牲复制、搜索与定位。
- 指标：500 条会话首屏渲染 < 300ms，DOM 节点 < 500，流式期间主线程长任务（>50ms）为 0。
- 注意：原型中的 DOM 节点对比已可复现（全量 500 节点 vs 可视区 12 节点），耗时数据需接入真实消息组件后重测，不以原型数字对外。
- 验收：500 条会话可流畅滚动；流式 60 秒不卡顿；若启用虚拟滚动，复制、搜索定位、跳转消息仍正确。

**完成情况（2026-11-14）**：

- **剖析方法与基线**：新增 `frontend/e2e/perf-long-session.spec.ts`（`@perf`，`PERF=1` 才运行），用 `page.route` 注入 500 条含标题/列表/代码块的消息与两段会话，用 `MutationObserver` 记录「点击会话 → 首条消息进入 DOM」，用 `PerformanceObserver('longtask')` 只统计流式开始后的长任务；流式由页面内 `fetch` 覆盖返回 ReadableStream，按约 30 chunk/s 持续 60 秒。对生产构建（`vite preview`）实测：
  - 改造前（同一 fixture、同一测试）：首屏 **220ms**，消息区 DOM **9000** 节点、**500** 条全部渲染，8 秒流式出现 1 个 56ms 长任务。
  - 改造后：首屏 **108ms**，消息区 DOM **216** 节点、实际渲染 **11** 条，60 秒流式长任务 **0**。
- **流式按帧合并**：新增 `utils/frameThrottle.ts`，`requestAnimationFrame` 调度 + 50ms 最小间隔，同一帧内只保留最新全量文本；`stores/chat.ts` 的正文与思考改为合帧提交，`onDone`/`onError`/停止/`finally` 强制 flush，保证正文最终与后端全量文本逐字一致。自动跟底同样合帧，并改为「用户停在底部才跟随」，向上翻阅历史不再被拉回。
- **Markdown 只重算变化段落**：新增 `utils/markdownSegments.ts`，非流式仍整篇 `marked.parse`；流式用 `marked.lexer` 顶层 token 按 `type + raw` 做 200 条有界缓存，只渲染新增/变化段落，结束后切回整篇解析。代码块高亮与复制按钮仍随段落渲染，不重复高亮未变化代码。
- **虚拟滚动**：新增 `composables/useVirtualMessages.ts`，消息数 > 200 时只渲染可视窗口（上下各 6 条 overscan，可视区外留 800px 缓冲），`ResizeObserver` 实测高度、未实测按估算，上下占位撑起真实滚动高度；≤ 200 条保持原全量渲染。搜索定位、缩略导航高亮与跳转统一走高度缓存偏移，目标未渲染时先按估算滚动、渲染后按实测校正；正在流式的最后一条始终在窗口内。
- **保真与边界**：单条复制与代码块复制不受影响；虚拟化下跨窗口整段拖选不可用，通过缩略导航跳转后分段复制，边界已写入 spec；引用式链接等跨段语义在流式期间可能延迟到结束才完全正确，结束时整篇解析保证最终一致。
- **验证结果**：前端 Vitest **10 文件 75/75**；生产构建通过；系统 Edge 下 Playwright 全量 **14/14**（`@perf` 默认跳过），`PERF=1` 时 500 条会话性能用例通过；后端 `mvn -o test` **294 项，0 失败、0 错误、12 跳过，BUILD SUCCESS**。

### P5-14 任务级 ChangeSet 与交付（基础完成）

**目标**：把“按轮产生了一些修改”升级为“一个任务可以统一审查、验证、继续修复和撤回”。这是新的用户可见功能，不是评测附属项。

- **任务边界与数据复用**：第一版默认以现有 Session 聚合任务、以 Run 表示每次执行，ChangeSet 先作为现有 `PendingFileChange`、Run/Event、审批与验证结果的聚合视图；支持按单个 Run 过滤。不预先新增 Task/ChangeSet 表，只有实际任务边界无法表达时才评审领域模型变更。
- **统一交付摘要**：展示变更文件、增删行、diff、来源 run/turn、审批记录、诊断与 verify 状态；明确区分进行中、待审批、失败、已停止、未验证、验证通过和用户已接受。
- **继续修复**：从失败诊断或 verify 结果发起继续执行，自动携带失败上下文与尚未完成的计划，不新建一条失去任务上下文的孤立对话。
- **接受与撤回**：接受只表示用户确认交付，不能把未验证改成验证通过；整体撤回按相反执行顺序复用现有变更记录与冲突检测，支持按 Run 查看和撤回。用户已接受的变更仍保留审计。
- **边界诚实**：命令产生的未记录外部副作用不能承诺完整回滚；文件在 Agent 修改后被用户或其他进程改动时必须报冲突，不能覆盖用户新内容。
- **持久化与恢复**：刷新后可恢复同一交付视图；运行中、审批中和验证中状态与现有 SSE/Run 恢复机制一致。
- **验收**：一个包含两次 Run 的 fixture 任务能统一展示变更与验证结果；继续修复保留原任务上下文；整体撤回恢复 Agent 自己产生的文件变化；外部修改触发冲突；未验证任务始终显示未验证；P5-11-1 覆盖“修改 → 审批 → verify → 审查 → 撤回”关键 E2E。

**完成情况（2026-11-14）**：

- **不新增迁移**：`AgentChatController` 在创建 Run 前生成 `turnId` 并写入 `AgentRun.requestJson`，由 `ChangeSetService` 建立 runId ↔ turnId 映射；复用 `PendingFileChange`、Run/Event、审批与 Verify/Diagnostics，未新增 Task/ChangeSet 表。
- **任务级聚合**：新增 `GET /api/file-changes/changeset?sessionId=&runId=`，返回 Run 列表、文件变更、增删行、审批摘要、诊断、验证与 `continuePrompt`；状态区分进行中、待审批、失败、已停止、未完成、未验证、验证通过、用户已接受与已完成。指定 `runId` 时只返回该 Run 及其变更。
- **接受不覆盖验证**：`accepted` 只由 `PendingFileChange.status` 计算；`verification` 只来自最后一个 VERIFY 事件或 completion reason，接受后仍保持 `NOT_CONFIGURED/UNAVAILABLE/MISSING` 等原状态，任务不会变为“验证通过”。
- **继续修复**：仅对失败/未完成/未验证/已停止任务生成 `continuePrompt`，自动包含原任务、失败命令与输出尾部、诊断 ERROR 与最近计划中的未完成步骤；前端在当前 Session 调用现有发送流程，不新建孤立会话。
- **撤回与冲突**：新增 `revert-session` 与 `revert-run`，按 `createTime DESC, id DESC` 逆序回滚 PENDING 变更；`revertOne` 先比较磁盘内容与 `newContent`（换行归一），外部修改/删除返回冲突且不覆盖；ACCEPTED 变更保留审计并计入 `skippedAccepted`。既有 `/{id}/revert`、`/revert-all`、`/revert-turn` 改为复用同一逻辑。
- **前端交付看板**：`PendingChangesCard.vue` 升级为任务交付卡，展示状态、验证摘要、失败命令、审批进度、Run 过滤、按 Run 分组文件与 diff，并提供「撤回此轮」「整体撤回」「继续修复」「全部保留」；刷新后由 ChangeSet API 重建同一视图。
- **验证结果**：新增 `ChangeSetServiceTest` 7 项（多 Run 聚合与过滤、continuePrompt、接受不覆盖验证、逆序整体撤回、按 Run 撤回、外部修改冲突、已接受跳过）；后端全量测试通过；`EvalGate,RuntimeEvalGate` 通过；前端 `vue-tsc --noEmit` 通过。
- **边界**：P5-11-1 的前端 Vitest/Playwright 尚未实施，“修改 → 审批 → verify → 审查 → 撤回”的真实浏览器 E2E 未执行；命令产生的未记录外部副作用不承诺回滚；历史 Run 若缺少 `requestJson.turnId`，其变更仍展示但可能无法按 Run 归属/撤回。
  - 2026-11-14 更新：P5-11-1 已实施，上述关键路径由 Playwright 在浏览器层仿真后端覆盖（7 条用例）；仍未执行真实后端 + 真实模型的干净机器验收，该边界保持不变。

---

## 9. 信息架构（核心任务流已完成）

### P5-12 从聊天机器人到开发者工具（核心任务流已完成）

**来源**：外部评审文档《智能体界面改造方案：从聊天机器人到开发者工具》对主界面的诊断。逐条对照 2026-11-12 的代码现状后分成三档：**采纳**、**已覆盖（只补差量）**、**暂不做**。评审的核心判断成立——「代码与文件是主角，对话只是控制通道」，但不需要照搬三栏布局。

#### P5-12-1 首屏去客服化（已完成）

原空态是「你好，我是你的智能助手」+ 4 个建议按钮，属于客服式引导；现已替换为项目概览。

- 删除开场白与建议按钮。
- 空态改为**项目概览**：最近修改的文件（复用变更面板数据）、进行中的任务（`GET /api/agent/runs/running`）、上次会话入口。
- 保留一句 `Ctrl K 开始新任务` 提示（Ctrl/Cmd+K 已能聚焦输入框）。
- 验收：新会话首屏无客服文案；概览展示真实数据；无数据时给明确下一步；960×640 不溢出。

#### P5-12-2 会话列表 → 任务列表（已完成）

- 左侧每项增加**状态点**与相对时间；运行中为蓝色呼吸点，待审批黄、已完成绿、失败红、停止灰，`title` 给出文字状态与绝对时间。
- 数据来源：`runs/running` 的实时态（`chat.runningRunMap`）优先，其次会话列表扩展字段 `lastRunState`；`/api/agent/sessions` 由实体改为带状态的 DTO，表结构未动。
- 本地即时更新：新建会话、`approval_required`、审批处理、轮次收尾都直接改 store，避免后端落库延迟导致状态点滞后。
- 验收：已用内置浏览器实测一轮任务——失败红点 +「4 分钟前」→ 重新提问后运行中蓝点 +「刚刚」→ 完成后绿点「已完成」；相对时间与 `title` 绝对时间同步。

#### P5-12-3 执行轨迹结构化（已完成）

`ChatMessage` 已折叠思考/步骤；本轮补齐追踪面板的信息密度。

- 后端 `ChatSessionStats.ToolTimelineEntry` 追加 `path / additions / deletions`：聚合 trace 时把 `changes` 反查挂到产生它的工具调用行（MCP 按 `mcp_` 前缀回找），前端时间线行内直接显示文件名与 `+n / −m`。
- 等待审批的调用行高亮（黄色左边条 + 「待审批」标签）；点该行同时定位审批区。
- 点有文件变更的轨迹行，行下展开该文件 diff（取自本轮消息里的 `changes.diff`）；历史轨迹缺 diff 时给明确提示与「在编辑器中打开」入口。
- 每轮结束清掉 trace 缓存并重拉，避免看到上一轮的旧统计。
- 验收（内置浏览器实测）：写文件任务结束后，`write_file` 行显示 `p5-trace-test.txt` 与 `+4 −0`，点击展开含 alpha/beta/gamma 的 diff 预览。

#### P5-12-4 审批常驻醒目（已完成）

`ApprovalCard` 已固定在输入区上方、不在消息流里；本轮补齐队列与联动。

- 多项待审批按队列渲染并给「待审批 n 项」计数，超出 `42vh` 内部滚动，不互相遮蔽；只有队列首张响应 `A / D` 快捷键，避免一次按键批掉全部。
- 「追踪」tab 上显示待审批数量徽标；面板顶部新增待审批区（工具名 + 文件），点「去处理」通过 `chat.requestApprovalFocus()` 让聊天区滚回并高亮审批卡片 1.2s。
- 验收（内置浏览器实测）：写文件触发审批后，追踪面板顶部出现「待审批 1 项」与「去处理 →」；点击后审批卡片可正常允许并继续执行。

#### P5-12-5 关键状态上移顶部（已回退，不做）

曾把模型、推理档位、工作权限移入 `.chat-header`，窄屏折叠成图标；实测后判断不合适：Trae / DeepSeek 都把运行参数放在输入框下排，编辑问题时手不用离开输入区，顶栏放三颗胶囊反而与项目名争宽度。

- 现状：三者保留在输入框下排（[ChatView.vue](file:///d:/test-code/agent-gateway-v3/frontend/src/views/ChatView.vue#L258-L300)），顶栏只放项目名与资料/变更入口。
- 保留的增量：`/permission` 快捷命令（`manual|auto|full`），与 `/model` 对齐。
- 结论：该项不再实施，评审建议中「关键状态上移顶部」按此归档。

#### P5-12-6 输入框命令与引用（已完成）

`@` 引用文件/资料/会话、`/` 命令自动补全均已实现。本轮只补 `/fix`、`/test`、`/review`、`/commit` 四个命令的映射与说明，不重复造能力。

#### P5-12-7 视觉基调：深色与等宽（部分完成）

现状：token 已收口、亮暗两套完备；默认主题已切深色，`--accent` 为紫，圆角 12px。

- 无争议部分（可先做）：新增 `--font-mono`，用于文件路径、代码、diff 行数、耗时、token 数。
- 已定（2026-11-12）：默认主题切深色，并用 `themeVersion` 做一次性迁移，避免旧版自动写入的 `light` 继续生效。
- 待决策：是否放弃现有金色/紫色品牌色改功能蓝、圆角是否收紧到 6-8px。
- 明确不做：直接照搬 #0d1117 / #58a6ff 一套配色，需在现有 token 上做而非另起体系。

#### P5-12-8 中间区改「工作区」（暂不做）

评审给了「最小改动版」：中间标签页（代码 / Diff / 日志）+ 底部可展开 Agent 输出条。判断：与现有 `WorkbenchDock`（右侧工作台 + 文件编辑 + 追踪）职责重叠，先叠加只会更乱。
结论：**暂不重构**；若将来做，用它替换 dock 而不是并存。**三栏布局重构**（左文件树 / 中编辑器 / 右 Agent 面板）不在 Phase 5 范围，留到 Phase 6 单独评估。

#### 评审建议处理对照

| 评审建议 | 处理 | 理由 |
|---|---|---|
| 删开场白与建议按钮 | 采纳 P5-12-1 | 成本最低、客服感最强 |
| 会话改任务列表 | 采纳 P5-12-2 | 有 run 状态可依托，展示层即可实现 |
| 工具调用结构化 | 已覆盖，补 diff 行数等 | 现有折叠步骤 + 追踪面板已具备 |
| 审批独立醒目 | 已覆盖，补队列与联动 | 已固定在输入区上方 |
| 关键状态上移顶部 | 已回退，不做 | Trae / DeepSeek 均把运行参数放输入框下排，实测更顺手 |
| 输入框支持 @ 与 / | 已覆盖，补命令映射 | 现有命令面板已实现 |
| 默认深色 + 等宽字体 | 默认深色已采纳并实施；等宽字体待做 | 品牌色仍待确认 |
| 中间改工作区 / 三栏 | 暂不做 | 与 WorkbenchDock 重叠，收益低于改动成本 |

---

## 10. 评测设计

### 10.1 数据集与 fixtures

```text
eval/
  datasets/
    tool-select.jsonl
    file-edit.jsonl
    file-delete.jsonl
    command-approval.jsonl
    rag.jsonl
    refusal.jsonl
  fixtures/
    <case-name>/...
  runtime-datasets/
    runtime-*.jsonl
  baselines/
    baseline.json
    runtime-baseline.json
  reports/
    <timestamp>.json
  runtime-reports/
    <timestamp>.json
```

用例示例字段：

```json
{"id":"tool-select-001","category":"tool_select","fixture":"java-small",
 "prompt":"读取 pom.xml 的 java.version","maxRounds":5,
 "expect":{"tools":["read_file"],"args":{"path":"pom.xml"}},
 "budget":{"maxTokens":4000,"maxMillis":15000}}
```

P5-13 已新增 5 个 `runtime-*.jsonl`、15 条带预期副作用/事件终态的用例及独立 Runtime 基线；`tool-select` 等 27 条仍是组件级数据集，不得改称真实 ReAct 端到端评测，也不与 Runtime 指标混算。

### 10.2 指标

| 指标 | 说明 |
|---|---|
| 成功率 | 用例级通过率，按类别汇总 |
| 工具选择准确率 | 首个/关键工具与参数是否命中 |
| 平均轮次 | 工具轮次均值 |
| token | prompt / completion / total 均值与 p95 |
| 时延 | 首字与总时长 p95 |
| 审批正确性 | 该审批的触发、不该审批的不打扰 |
| 引用命中 | RAG 用例引用是否覆盖金标片段 |
| 拒答正确率 | 越权/无依据请求被拒 |
| Runtime 用例通过率 | `case_pass_rate`：真实 ReAct 执行后的文件副作用、事件终态与脚本预期一致 |
| 真实任务完成率 | `task_success_rate`：真实循环最终交付状态为 `COMPLETED` 的比例，与脚本断言通过率分开 |
| 验证覆盖率 | 代码修改任务中具备有效诊断/verify 证据的比例 |
| 无进展识别 | 重复调用、相同失败与无推进循环是否被记录并安全处理 |
| 任务交付正确性 | 多 Run ChangeSet 聚合、继续修复、冲突检测与整体撤回是否正确 |

### 10.3 体验指标（P5-11 补充）

| 指标 | 采集方式 | 阈值 |
|---|---|---|
| 首字时延 p95 | run 事件时间戳 | < 3s |
| 流中断率 | 重连次数 / 总 run 数 | < 2% |
| 断线恢复成功率 | 补齐成功 / 重连尝试 | > 95% |
| 审批平均等待 | 创建到决策时间 | < 60s |
| 长会话滚动掉帧 | 前端采样 | 无 >50ms 长任务 |

### 10.4 运行模式

- **组件级脚本化（现有，CI）**：`ScriptedAgentClient` + 组件 runner，结果确定、无外部依赖；继续保护工具选择、编辑、审批、RAG 与拒答。
- **真实 ReAct 脚本化（P5-13，CI）**：同一个脚本客户端驱动真实 `ReActAgentService.execute` 和真实工具副作用，仍无外部模型依赖；单独报告与基线，不与组件级通过率混算。
- **真实模型抽样（本地/定时）**：BYOK，记录模型名与版本，报告归档但不阻塞合并；用于发现脚本无法覆盖的模型行为，不能替代确定性 CI。

---

## 11. 数据与配置增量

- 迁移：
  - V19 `code_index`（P5-04，桌面端缓存，可选）。
  - V20 仅在决定启用 `halfvec` 时用于量化列与必要索引；混合检索/全文辅助单独评估，不与量化捆绑。
  - P5-13 已复用现有 Run/Event，无需新增迁移；P5-14 默认复用 Run/Event、审批与 `PendingFileChange`，不预设 Task/ChangeSet 新表，确需持久化新字段时使用下一条未占用版本并先检查迁移目录。
  - 视觉与体验批次不新增数据库迁移；断线续传复用 V18 的 `agent_run_event`。
- 配置键：
  - `agent.prompt.scenario.<name>.version`（新增 coding 场景版本复用该机制）
  - `agent.loop.no-progress.max-repeats`、`agent.loop.no-progress.max-stagnant-rounds`
  - `agent.diagnostics.provider`（`javac` / `lsp`）
  - `agent.verify.enabled`、`agent.verify.maxRetries`
  - `agent.rag.hybrid.enabled`、`agent.rag.rerank.enabled`
  - `agent.tools.parallelism`
- 前端新增（仅开发依赖与配置，不改构建产物结构）：
  - `frontend/vitest.config.ts`、`frontend/e2e/playwright.config.ts`
  - devDependencies：`vitest`、`@vue/test-utils`、`jsdom`、`@playwright/test`
- 不新增第二套前端；RAG 引用、诊断、验证、续传与任务交付沿用现有 SSE/Run 事件体系。

---

## 12. 待确认决策

1. **P5-04 索引技术**：已定 v1 用轻量正则（`LightweightCodeIndexer`，零原生依赖）；JavaParser / tree-sitter 作为可插拔实现按准确率需求后续评估。
2. **P5-05 诊断**：v1 用 javac 进程内诊断（推荐）还是必须接外部 LSP server？
3. **真实模型抽样预算**：是否提供可用 BYOK Key 与本地/定时抽样预算？P5-13 的确定性 CI 已落地且不依赖外部 Key；抽样只用于补充真实模型行为证据。
4. **Playwright 成本**：是否接受 CI 增加浏览器依赖（约 100MB 缓存）以换取关键路径 E2E？
5. **体验数据边界**：P5-11 的指标是否只留本地诊断，还是允许按 P3-06 的方式用户手动导出？默认仍不远程上传。
6. **品牌色与圆角**：是否保留现有金色/紫色品牌点缀与 12px 圆角，还是改成功能蓝/绿与 6～8px？归入 P5-09，不影响 P5-12 核心任务流已完成。
7. **任务实体是否必要**：P5-14 先以 Session + Run + 聚合 ChangeSet 验证任务交付；只有同一 Session 多任务等真实边界无法表达时，再决定是否新增 Task 实体。

已关闭决策不再留在待确认列表：首批组件级 27 条评测与 `AgentClient` 抽取已完成；审批采用 A/D；默认深色已实施；左侧已统一称“任务”；顶部状态上移已回退。

---

## 13. 风险与非目标

- tree-sitter 原生依赖在 Windows 打包风险高 → v1 用 JavaParser，接口预留。
- LSP 服务端可用性不可控 → javac 兜底，LSP 可选。
- 真实模型评测有成本与抖动 → CI 只跑脚本化，真实模型本地/定时。
- 中文全文检索质量需靠评测调参，不承诺一次到位。
- token 收口已完成，但后续主题调整仍可能影响多个组件与页面 → 沿用暗色回归与对比度清单，避免“改一处坏一处”。
- 虚拟滚动与流式 Markdown 可能冲突 → 先剖析真实长会话，再按数据选择渲染节流、Markdown 增量或虚拟化；启用虚拟化时单独验收复制、搜索与定位。
- 断线续传已采用 runId 全量重放 + 正文检查点，不再按旧方案补前端 seq → 剩余风险是真实断网与桌面壳重启，需人工验收。
- Playwright 在 CI 可能 flaky → 优先覆盖发送、审批、停止、续传与任务交付关键路径，失败上传 trace，不追求覆盖率。
- 信息架构改造容易与现有 WorkbenchDock 职责重叠 → 保留已完成首屏/列表/轨迹/审批，中间区与三栏布局明确推迟。
- 真实 ReAct E2E 若过度 mock 会失去意义 → 必须覆盖真实服务循环、工具副作用与事件终态，但模型仍用脚本保证确定性。
- 无进展检测可能误停长任务 → 阈值可配置、事件可解释、允许用户继续，不以单一重复次数直接判死。
- 多 Run 整体撤回可能覆盖用户后续修改 → 逆序处理并强制冲突检测，命令未记录副作用明确排除。
- 删除开场白会损失「首次使用者引导」→ 用项目概览替代，空数据时仍给下一步入口。
- 不在本阶段做 VS Code fork、插件市场、泛桌面自动化、多实例评测调度、跨端同步、支付/配额、i18n 与移动端布局。
- 不用“ReAct 太弱”作为重写理由；没有真实评测证据时，不引入多智能体、复杂状态图或独立 Planner/Critic。

---

## 14. 验收清单

### 智能体能力增强

- [x] P5-01 提示词资源化、版本化、热重载/回滚，单测通过
- [x] P5-02 组件级评测集与 runner 可一键运行，报告含分类别指标
- [x] P5-03 基线入库，门禁在回退时失败
- [x] 组件级评测可重复运行，成功率与成本基线写入仓库，提示词/工具变更必须过门禁
- [x] P5-13 真实 ReAct E2E 覆盖工具副作用与 Run/Event 终态，单独基线进入 CI
- [x] P5-13 候选完成不伪装成验证通过，重复调用与无进展可识别并安全处理
- [x] P5-13 coding 场景提示词按版本生效，通用问答与编程完成规则分离
- [x] P5-05 编辑后诊断进入下一轮，坏文件可定位，连续相同诊断可供无进展检测使用
- [x] P5-06 verify 失败回环且预算内终止；未配置、失败、环境不可用与验证通过状态明确
- [ ] P5-04 符号索引与 repo map 生效，并证明成功率、轮次或 token 至少一项改善
- [ ] P5-07 混合检索/引用/rerank 有评测对比；若启用 halfvec，另行证明体积与精度收益
- [x] P5-08 只读工具并行、写操作串行且审批不变，同类用例 p95 有改善

### 工作台体验增强

- [ ] P5-14 多 Run 任务统一展示 ChangeSet、审批与验证结果，继续修复保留原任务上下文
- [ ] P5-14 整体撤回按逆序恢复 Agent 变更，外部修改触发冲突，未验证状态不被接受动作覆盖
- [x] P5-09 token 收口完成，组件内 hex 清零（主题预览白名单 4 处）
- [x] P5-09 暗色 5 页面回归通过，关键配色对比度实测均 ≥4.5
- [ ] P5-09 三态覆盖全部异步区域（会话骨架与错误态已完成；复核现有空态并补工具运行态，不重复新建 EmptyState）
- [ ] P5-09 长回答与工具输出默认折叠，代码块与 diff 统一
- [ ] P5-09 三档宽度可用，960×640 无横向滚动
- [x] P5-10 断网/刷新续看本轮已实现（检查点 + 自动重连 + 状态条）
- [ ] P5-10 人工断网 10s 实测补齐，事件不丢不重且文本连续
- [x] P5-10 A/D 纯键盘审批与快捷键面板已可用
- [ ] P5-10 J/K 切换、低风险批量审批与审批历史完成
- [ ] P5-10 长任务阶段与预算可见，失败分级给出动作，停止后可继续
- [x] P5-10 焦点环、aria-live 与减少动效已可用
- [ ] P5-10 axe critical 为 0，弹窗与抽屉焦点陷阱通过校验
- [x] P5-11 Vitest 与 Playwright 覆盖发送、审批、停止、续传及“修改 → verify → 审查 → 撤回”关键路径
- [x] P5-11 前端异常、Web Vitals、断线恢复与四类后端 Agent 指标可在本地诊断查看
- [ ] P5-11 500 条会话首屏 < 300ms、DOM 节点 < 500、无 >50ms 长任务；虚拟滚动按剖析结果实施

### 已完成的信息架构基础（不重做）

- [x] P5-12 新会话首屏无客服文案，空态展示项目概览与最近任务
- [x] P5-12 左侧任务列表可见运行中/待审批/完成/失败状态
- [x] P5-12 模型/推理/权限保留在输入区下排；顶部状态上移方案已回退，不再作为未完成项
- [x] P5-12 轨迹行含 diff 行数，多项审批不互相遮蔽
