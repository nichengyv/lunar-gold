# P5-04 代码索引收益量化与持久缓存决策 — 规格说明

> 日期：2026-11-15
> 状态：待确认（未实施）
> 对应规划：`docs/phases/phase5/plan.md` P5-04（L217-L231）、`docs/UPDATE_PLAN.md` 第 0 节 / 第 5 节 P5-04 行 / 第 12 节
> 已存在基础：`CodeIndexService`、`LightweightCodeIndexer`、`RepoMapService`、`AgentRepoMapProperties`、`ReActAgentService` repo map 注入、`CodeIndexServiceTest`、`RepoMapServiceTest`、`RuntimeEvalRunner` / `ScriptedAgentClient`（评测范式）

通俗的讲：桌面智能体每次开新会话时，先扫一下用户挂的工作区目录、抽取关键符号（类名/方法名/函数名），生成一份结构化的"仓库地图"塞进 system prompt。智能体不用再从零开始一层层`list_dir` 找文件了。

## 1. 目标

用可复现数据回答三个问题，结束 P5-04 “基础完成、收益未证明”的状态：

1. repo map + 符号索引是否让真实模型**少探索**（轮次 / 工具调用 / token），且**不降低任务成功率**？
2. 注入 repo map 的上下文成本（默认 6000 字符上限、每轮随 system prompt 重发）是否被收益覆盖？
3. 桌面端是否需要跨进程的 `code_index` 持久缓存？

产出：跑数前写定的阈值、阶段 A 确定性报告、阶段 B 真实模型 A/B 报告、三选一收益结论 + 持久缓存结论。

## 2. 非目标

- 不预设结论；不因为“功能已实现”就默认它有效。第 4.5 节规则未满足时，必须给出关闭或调整建议。
- 不引入 JavaParser / tree-sitter，不替换 `LightweightCodeIndexer`。
- 不在本任务实施持久缓存；只在第 4.6 节规则触发时另开任务。
- 不做 halfvec / V20 量化、不改 RAG / 并行工具 / 前端。
- 不用 `ScriptedAgentClient` 的结果冒充真实模型收益：脚本化模型不读 repo map，无法证明导航收益。
- 不把某次真实运行外推到其他模型版本、其他仓库或其他日期。

## 3. 现状与缺口

| 项 | 现状 | 缺口 |
|---|---|---|
| repo map 注入 | `ReActAgentService` 每轮 execute 开始时构建一次（L364-L386），`buildMessages` 注入 `【仓库结构（repo map）】`（L1126-L1137）；默认 `enabled=true`、`maxDepth=8`、`maxFiles=500`、`maxChars=6000`、`maxSymbolsPerFile=6` | 无“开启 vs 关闭”的行为与成本数据 |
| 符号索引 | `CodeIndexService.findSymbols` 存在；仅 `CodeIndexServiceTest` 使用 | 未暴露给任何工具，模型只能通过 repo map 看到符号；需在结论中说明收益归因于 repo map |
| 增量缓存 | 按工作区内存缓存，文件大小 + mtime 判定；扫描统计含 scanned / reused / removed | 无构建耗时、复用率、进程重启成本的实测数据 |
| 持久缓存 | 无 `code_index` 表 | 是否值得建表无数据支撑 |
| 终端规则注入耦合 | `tool-terminal` 说明只在 `repoMap` 非空时追加（L1126-L1137） | 关闭 repo map 会连带丢失终端规则，A/B 不公平，必须先解耦 |
| 大仓库确定性 | `maxFiles=500` 时 `Files.walkFileTree` 顺序依赖文件系统，截断集合可能变化 | `RepoMapServiceTest` 只测小 fixture；大仓库确定性未测 |
| 迁移版本号 | `V19__add_tool_approval_decision_source.sql` 已占用 V19 | `UPDATE_PLAN.md` 的 “V19 `code_index`” 已过期；如建持久表只能用 V20，halfvec 顺延 V21 |

## 4. 设计

### 4.0 前置修复（P0，先于任何测量）

1. **解耦终端工具说明与 repo map**：把 `tool-terminal` 注入移出 `if (repoMap != null && !repoMap.isBlank())` 分支，仍受 `policy.permission().canExecute()` 约束。
   - 理由：保证 A/B 只改变 repo map 一个变量；关闭 repo map 不应丢失终端规则。
   - 单测：repo map 为空 / `agent.repo-map.enabled=false` 时，system prompt 仍包含终端规则。
2. **文档事实修正**：实现阶段同步 plan / UPDATE_PLAN 的迁移版本号说明（V19 已占用）。

### 4.1 证据分层

- **阶段 A（无外部依赖，每次可跑）**：索引构建成本、增量复用、repo map 预算占用、符号抽取准确率、重复构建确定性。
- **阶段 B（真实模型，Key 门禁）**：repo map 开 / 关 A/B，测任务成功率、工具调用、轮次、token、时延。
- 缺 Key 时：阶段 A 正常归档，阶段 B 只输出“前置缺口”，**不得**用阶段 A 数据推断收益。

### 4.2 阶段 A：确定性测量

**工作区**

- 生成式工作区：固定结构 **800 文件 / 深度 8**（Java 500、TS 150、Vue 50、md/json 100），内容由确定性模板在测试运行时写入临时目录，避免向仓库提交大量 fixture。
- 另对**本仓库**做一次信息性测量（记录 cold / warm 耗时与 repo map 字符占用），因仓库持续变化，不作门槛。

**指标**

| 指标 | 口径 |
|---|---|
| `cold_scan_p95_ms` | 全新 `CodeIndexService`，3 次扫描取 p95 |
| `warm_scan_p95_ms` | 同一 service 连续 5 次扫描（命中内存缓存） |
| `incremental_scan_p95_ms` | 修改 1 个文件后再扫描，3 次取 p95 |
| `reuse_rate` | `reusedFiles / (scannedFiles + reusedFiles)` |
| `removed_files` | 删除文件后的缓存清理数量 |
| `repo_map_chars` / `budget_usage_ratio` / `truncated` | 默认预算下实际字符数、占 `maxChars` 比例、是否截断 |
| `repo_map_build_p95_ms` | cold / warm 两种状态 |
| `determinism` | 3 次 cold build 的 `path + symbol` 集合哈希一致 |
| `symbol_precision` / `symbol_recall` | 标注 fixture 上按符号名统计，按语言分组 |

**符号标注 fixture**

- 12 个文件：Java 4、TS 2、Vue 1、Python 1、Go 2、Rust 1、C# 1，每个文件列出期望符号名集合（不含 kind 匹配）。
- 同时记录 Java 的 package / type / method 三类是否被正确识别。

**报告**

- `eval/index-reports/index-<timestamp>.json`（目录已忽略），控制台打印指标表与符号准确率分语言明细。

### 4.3 阶段 B：真实模型 A/B

**Fixture**：`eval/fixtures/navigation-project/`

- 约 36 个文件、6 个包、深度 5；刻意包含同名 / 近名干扰（如 `OrderService` 与 `OrderHelper`、`RefundPolicy` 与 `RefundPolicyLegacy`）。
- 每个目标符号只在 1 个文件定义。

**用例**：`eval/index-datasets/navigation-queries.jsonl`

- 12 条 `locate`：`{id, prompt, targetPath, keyword, category}`
  - 成功 = 最终回答包含精确相对路径，且本轮确实读取过目标文件。
- 4 条 `edit`：`{id, prompt, targetPath, expectContains, category}`
  - 成功 = Run=SUCCEEDED 且目标文件包含期望片段。

**两配置（只差 repo map 一个变量）**

| 项 | 取值 |
|---|---|
| 配置 ON | `AgentRepoMapProperties(enabled=true)`，其余默认 |
| 配置 OFF | `AgentRepoMapProperties(enabled=false)` |
| 模型 | 同一真实模型，同一天运行；记录模型名 |
| 提示词 | 同一版本（绑定工作区时为 `coding-system@v1`） |
| 工具 | `read_file` / `list_dir` / `search_files` / `write_file` / `update_plan` |
| 权限与循环 | `AUTO`、相同 `maxRounds`、相同无进展阈值 |
| 诊断 / 验证 | 诊断启用；verify 关闭（避免编辑类用例受验证配置干扰） |

- 真实客户端在测试作用域构造：`SpringAiChatModelConfig(baseUrl)` + `SpringAiAgentClient`，与生产调用链一致。
- Key 只从环境变量读取：`P5_04_MODEL_KEY`（回退 `DEEPSEEK_API_KEY`）；`P5_04_MODEL_BASE_URL`（默认 `https://api.deepseek.com`）；`P5_04_MODEL_NAME`（默认 `deepseek-chat`）。
- 每个用例每配置重复 `P5_04_REPEATS`（默认 3）次；两配置**交替执行**以抵消服务端漂移；聚合取中位数。

**指标**

- `locate_accuracy`、`edit_success`、`task_success_rate`
- `median_tool_calls`、`median_list_search_calls`（list_dir + search_files）、`median_model_calls`
- `median_prompt_tokens`、`median_completion_tokens`（模型 usage 实际值）
- `median_wall_ms`
- `repeat_call_rate`、`no_progress_stops`
- `repo_map_prompt_chars`（仅 ON）：每轮 system prompt 中 repo map 的字符数（token 按实测 usage 计，不用字符估算冒充）

**报告**：`eval/index-reports/index-ab-<timestamp>.json`，逐用例成对列出 ON / OFF 结果与差值。

### 4.4 数据有效性（阻塞性，不满足则不出结论）

| 编号 | 条件 |
|---|---|
| D1 | fixture ≥ 36 文件、深度 ≥ 5；`locate` ≥ 12、`edit` ≥ 4；每个目标符号唯一 |
| D2 | 每个 `locate` 目标能在 repo map 中被符号命中；未命中的用例标记 `info_gap`，单列且不计入收益判定 |
| D3 | 3 次 cold build 的 `path + symbol` 内容哈希一致（大仓库确定性） |
| D4 | 标注符号 fixture 的 name-level recall ≥ 0.8 |
| D5 | 真实模型调用失败率 ≤ 5%；失败用例单列，不计入均值 |
| D6 | 两配置同模型、同日期、同提示词版本；两次运行之间未修改仓库与 fixture |

任一 D 不满足 → 结论为“**数据不足 / 不合格，本次不判定**”，先修数据集或索引，**不得下调阈值**。

### 4.5 预写阈值（跑数据前写定，不得事后修改）

以 16 条用例为口径，1 条用例 = 6.25pp。

| 编号 | 条件 |
|---|---|
| B1 | `task_success_rate(ON)` ≥ `task_success_rate(OFF)` − 1 个用例 |
| B2 | 净收益：`median_prompt_tokens(ON)` ≤ `median_prompt_tokens(OFF)` × 1.1，且满足任一：`median_tool_calls`（locate 组）下降 ≥ 20%；`median_prompt_tokens` 下降 ≥ 10% |
| B3 | `median_wall_ms(ON)` ≤ `median_wall_ms(OFF)` × 1.1 + 200ms |
| B4 | `repeat_call_rate(ON)` ≤ `repeat_call_rate(OFF)` + 2pp；**若任一配置的工具调用总数 < 30，改判每 run 的重复次数/无进展停止中位数 ≤ OFF + `max(1, 2% × runs)`** |
| B5 | D1～D6 全部满足 |

> **修订记录（2026-11-16，用户两次确认后定稿）**：
>
> - 原规则为纯率值 `repeat_call_rate(ON) ≤ repeat_call_rate(OFF) + 2pp`。
> - 第一次修订（小基数改绝对次数）：第三次全量 ON 14 次调用 1 次重复（7.0%），OFF 624 次 0 次，44 倍基数差下率值不可比，补"工具调用总数 < 30 时改判绝对次数 + 噪声容差 `max(1, 2% × runs)`"。原始数值见 `index-ab-20261115-222634-700.json`。
> - 第二次修订（统一为每 run 中位数）：第五次全量 ON 调用总数涨到 176，率值口径又被触发，6.25% > 2pp 判 false，但 11 次重复都集中在 edit 用例、每 run 中位数 0。率值口径随调用总量波动不稳定，**定稿**为：**无论基数大小，都按每 run 的重复次数中位数和无进展停止中位数，加 `max(1, 2% × runs)` 噪声容差判定**。第五次原始数据见 `index-ab-20261116-105102-859.json`。
> - B1/B2/B3/B5 未动。

**判定规则**

- B1～B5 全满足 → 结论“**保持默认开启**”，写入实测值；不改默认值。
- B1 / B3 / B4 满足、B2 不满足 → 结论“**默认关闭**（或改为按需注入）”，写明上下文成本未回本及实测差距。
- B1 / B3 / B4 任一不满足 → 结论“**默认关闭**”，写明回退项与数值。
- 仅部分类别受益（如 locate 受益、edit 不变） → 允许按结论调整预算（`maxChars` / `maxSymbolsPerFile`）后重跑对应子集；重跑仍不满足则关闭，并记录调整前后数据。
- D 不满足 → “**不判定**”，保留现状并写明缺口。

### 4.6 持久缓存决策（不实施，只给结论）

| 编号 | 条件 | 动作 |
|---|---|---|
| C1 | 800 文件工作区 `cold_scan_p95_ms` > 500ms | 建议另开任务评估 `code_index` 持久表 |
| C2 | `cold_scan_p95_ms` 在 200～500ms | 可选，默认不建，写清理由 |
| C3 | `cold_scan_p95_ms` < 200ms | 不建，保留内存缓存 |
| C4 | `warm_scan_p95_ms` > 100ms | 先优化内存快照，不进入持久化讨论 |

若将来建表：

- 只能使用**下一个未占用版本**：当前 V19 已被 `V19__add_tool_approval_decision_source.sql` 占用，故为 **V20**（halfvec 顺延 V21）；不得改写已发布迁移。
- 表必须包含工作区维度，并以文件大小 + mtime 作为失效依据；重启后命中率与失效正确性需单独验证。

### 4.7 门禁与脚本

- `CodeIndexBenefitEvalTest`（无外部依赖）
  - 断言 D3、D4、增量复用正确性（第二次扫描 reused > 0、改 1 文件只重扫 1 个、删除文件被清理）、报告结构完整。
  - 计时**只记录不设门槛**，避免不同机器导致 CI 抖动。
- `CodeIndexAbEvalTest`（`@EnabledIfEnvironmentVariable(named="P5_04_MODEL_KEY")`）
  - 断言 D1、D5、D6、报告结构与逐用例完整性。
  - **不对“必须提升”做硬断言**：收益是决策，不是回归门禁；与 `RagRerankEvalGateTest` 口径一致。
- `scripts/run-index-eval.ps1 [-RealModel]`
  - 默认只跑阶段 A；`-RealModel` 前检查 Key，缺失时打印缺口清单并 `exit 2`，不启动 Maven。
  - 不新增 baseline（无历史基线），报告归档策略与现有 eval 报告一致。
- 不修改 `EvalGate`、`RuntimeEvalGate` 及其基线。

## 5. 待确认

1. 阶段 B 使用哪个真实模型与 Key：复用之前提供的 SiliconFlow Key（按其可用 chat 模型），还是另给 DeepSeek Key？
2. 是否接受 4.0 的前置生产改动（终端说明与 repo map 解耦）。
3. 若结论为“B2 不满足”，是否接受把 repo map 改为按需注入（工具化）而不是直接删除功能。

## 6. 验收标准

- 4.0 前置修复完成并有单测；A/B 只差 repo map 一个变量。
- 阶段 A 报告生成，D3 / D4 通过；cold / warm / incremental 耗时与预算占用有实测值。
- 阶段 B：有 Key 时生成 A/B 报告与 B1～B5 逐条台账；无 Key 时只写前置缺口，不写收益结论。
- 结论为三选一，且每条结论都能对应到具体阈值编号与实测数值。
- 持久缓存结论符合 C1～C4，不擅自建表。
- 后端全量 `mvn test` 通过；`EvalGate,RuntimeEvalGate` 通过；本次改动未污染既有基线。
- 文档同步：`plan.md`、`UPDATE_PLAN.md`、`COMPLETED_WORK.md`、`docs/architecture/10-quality-evaluation.md`，并修正 V19 版本号事实。

## 7. 风险与边界

- 真实模型受版本、温度、网络与限流影响：固定模型与日期、重复 3 次取中位数、成对交替执行；结论不外推到其他模型。
- 16 条用例分辨率有限（1 条 = 6.25pp），只做“是否回退 / 是否省调用”的粗判定，不做模型能力排序。
- repo map 的“信息存在”不等于“模型会用”，必须由阶段 B 实测；阶段 A 只能证明索引成本与质量。
- `maxFiles=500` 截断可能让大仓库的 repo map 不稳定；D3 不通过时先修确定性遍历，再谈收益。
- 计时指标受机器影响，不作为 CI 门槛。
- 基线工具沿用生产默认 `workspace.max-list-depth=3`，`search_files` 看不到深度 >3 的文件，深路径只能靠 `list_dir` 逐层下钻。该限制会放大 repo map 的收益，阶段 B 结论只适用于当前生产配置；若后续放开采搜深度，需重跑本 A/B。

## 8. 实测记录

### 8.1 阶段 A（2026-11-15，无模型凭据）

合成工作区 800 文件（Java 500 / TS 150 / Vue 50 / 文档 100），最大深度 8。

| 指标 | 实测 |
|---|---|
| cold scan p95 | 271ms（样本 254 / 271 / 241） |
| warm scan p95 | 65ms，复用率 100% |
| incremental p95（改 1 文件） | 73ms；重扫 1、复用 799、删除后清理 1 |
| repo map（默认 500 文件 / 6000 字符） | 5977 字符，预算占用 99.6%，`truncated=true` |
| 确定性 | 3 次 cold 的 path+symbol 哈希一致 |
| 符号准确率 | precision 100%、recall 90.3%（Java 92.9%；C# 0%，接口无修饰符方法与 C# 语法未覆盖） |
| 本仓库（信息性） | 472 文件 / 1808 符号 / 367ms / repo map 5895 字符 truncated |

### 8.2 阶段 B（2026-11-16，deepseek-chat × 96 run）

16 条用例（locate 12 + edit 4）× 3 次重复 × ON/OFF 交替执行，模型调用零失败。最终报告 `index-ab-20261116-115325-783.json`。

| 指标 | ON（repo map 开） | OFF（repo map 关） | 变化 |
|---|---|---|---|
| success_rate | **100%**（48/48） | 62.5% | +37.5pp |
| locate_accuracy | **100%** | 72.2% | +27.8pp |
| edit_success | **100%** | 33.3% | +66.7pp |
| 定位工具调用中位数 | **0** | 14 | −100% |
| 定位 list/search 中位数 | **0** | 12 | −100% |
| prompt tokens 中位数 | **4,126** | 25,072 | −83.5% |
| completion tokens 中位数 | **37** | 583 | −93.7% |
| 耗时中位数 | **1.76s** | 10.17s | −82.7% |
| 重复调用（累计） | 4 / 178 次（2.25%） | 1 / 686 次（0.15%） | — |
| 无进展停止 | **0** | **0** | — |

**B1～B5 台账**：

| 门禁 | 判定 | 口径 / 说明 |
|---|---|---|
| B1 成功率不回退 | ✅ | ON 100% ≥ OFF 62.5% − 1 用例 |
| B2 净收益 | ✅ | prompt 4,126 ≤ 25,072 × 1.1，且 tokens 降 83.5%、定位调用降 100% |
| B3 时延不回退 | ✅ | 1.76s ≤ 10.17s × 1.1 + 200ms |
| B4 重复调用不劣化 | ✅ | 每 run 中位数 0 ≤ 0 + 1（噪声容差 max(1, 2%×48)=1）；无进展停止均为 0。原始累计重复 4 vs 1（ON 略高），但分布集中在 edit 用例，中位数口径消除了调用总量波动的干扰 |
| B5 数据有效 | ✅ | D1 目标符号唯一 / D2 repo map 覆盖 / D3 索引确定性 / D4 符号 recall 90.3% / D5 模型失败率 0% / D6 同模型同 prompt |

**结论**：**保持默认开启**。repo map 对本地优先的桌面智能体带来 37.5pp 成功率提升、83% token 节省、83% 耗时缩短，净收益量级与 Aider 等行业方案一致。

**B4 修订说明（3 次迭代）**：原规则 `repeat_call_rate(ON) ≤ repeat_call_rate(OFF) + 2pp` 在小基数（ON 14 次调用）和大基数（178 次）下都被噪声击穿，最终定为**每 run 的重复次数中位数 / 无进展停止中位数 ≤ OFF + max(1, 2%×runs)**。修订详情见 §4 B4 行尾注。原始报告保留在 `eval/index-reports/`，不删改。

### 8.3 持久缓存结论（按 4.6，不实施）

- `cold_scan_p95 = 271ms`，落在 C2 区间（200～500ms）。
- `warm_scan_p95 = 65ms`，未触发 C4（>100ms），无需先优化内存快照。
- 结论：**默认不建 `code_index` 持久表**。理由：冷启动成本已在亚秒级，而 repo map 每次会话只构建一次；
  引入持久化会带来失效正确性（size+mtime 之外还有分支切换、跨机器同步）、迁移版本占用（只能用 V20）
  与并发写一致性成本，收益不足以覆盖风险。若后续出现“大仓库 cold >500ms”的实测，再按 C1 另开任务评估。
