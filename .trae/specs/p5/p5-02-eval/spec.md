# P5-02 评测集 - Product Requirements Document

## Overview
- **Summary**: 为 Lunar Gold Agent 构建可离线、可重复运行的评测集与 runner，覆盖工具选择、文件编辑、文件删除、命令审批、RAG 引用、拒答六类核心能力。
- **Purpose**: 建立质量度量地基，使提示词/工具/循环变更有可量化的回归基准，为 P5-03 评测门禁提供数据来源。
- **Target Users**: 开发者（本地跑评测）、CI（脚本化评测门禁）。

## Goals
- 抽取 `AgentClient` 接口，解耦 ReAct 编排与具体模型实现，使评测可注入脚本化客户端。
- 提供 `ScriptedAgentClient`，按用例回放固定工具调用序列，结果确定、无外部依赖、可进 CI。
- 建立 `eval/datasets/*.jsonl` 数据集，覆盖 6 类能力，每类 4-6 条。
- 实现评测 runner：隔离工作区、驱动 ReAct 循环、评分、输出分类别报告。
- 一条命令跑完全部用例，同一输入两次运行结果一致。

## Non-Goals
- 不做真实模型评测（仅本地/定时，不在 CI 跑）。
- 不做评测门禁（P5-03），但 runner 输出需为门禁预留结构。
- 不做代码索引（P5-04）、LSP 诊断（P5-05）、验证回路（P5-06）。
- 不重构 ReActAgentService 的编排逻辑，只改依赖注入类型。
- 不做前端评测。

## Background & Context
- P5-01 已完成提示词外置与版本化，`PromptCatalog` 可按场景加载提示词。
- 当前 `ReActAgentService` 直接依赖 `SpringAiAgentClient` 具体类（`streamChat`），`ContextSummaryService`/`SessionMemoryExtractionService` 用 `callSync`，`VisionService` 用 `callVision`。
- `AgentStreamCallback` 已是中立接口，`SpringAiAgentClient.StreamResult` 是 record。
- 现有测试用 JUnit 5 + Mockito，`src/test` 下有大量服务级单测。
- plan.md 第 10 节定义了数据集结构、指标与运行模式。

## Functional Requirements
- **FR-1**: 新增 `AgentClient` 接口，包含 `streamChat`、`callSync`、`callVision` 三个方法，签名与 `SpringAiAgentClient` 现有公开方法一致；`StreamResult` record 迁移为接口级类型。
- **FR-2**: `SpringAiAgentClient` 实现 `AgentClient` 接口，`@Component` 注册为接口 bean；所有依赖方（`ReActAgentService`、`ContextSummaryService`、`SessionMemoryExtractionService`、`VisionService`）改为依赖 `AgentClient` 接口。
- **FR-3**: 新增 `ScriptedAgentClient`（非 Spring bean，由评测 runner 直接构造），按预设的脚本序列回放：每次 `streamChat` 调用根据当前轮次返回预设的工具调用或最终文本；`callSync`/`callVision` 返回预设文本或抛异常。
- **FR-4**: 评测数据集位于 `eval/datasets/*.jsonl`，每行一个用例，字段含 `id`、`category`、`fixture`、`prompt`、`maxRounds`、`expect`、`budget`。六类：`tool_select`、`file_edit`、`file_delete`、`command_approval`、`rag`、`refusal`，每类 4-6 条。
- **FR-5**: fixtures 位于 `eval/fixtures/<name>/`，runner 为每个用例把 fixture 复制到临时工作区，结束销毁；保证可重复。
- **FR-6**: 评测 runner 为可执行的 JUnit 测试或独立 main 类，加载数据集、构造 `ScriptedAgentClient` + 真实 `ReActAgentService`（mock 掉无关依赖）、驱动 ReAct 循环、收集工具调用与文件变更。
- **FR-7**: 评分维度：工具选择（首个/关键工具名与参数是否命中 expect）、文件变更（应用后 diff 是否匹配 expect）、审批正确性（该审批的触发、不该审批的不触发）、RAG 引用命中、拒答正确率、轮次/token/时延。
- **FR-8**: 输出报告到 `eval/reports/<timestamp>.json`，含分类别成功率、平均轮次、平均 token、p95 时延；同时打印摘要表到控制台。

## Non-Functional Requirements
- **NFR-1（可重复性）**: 同一数据集连续运行两次，所有用例的 pass/fail 与指标完全一致。
- **NFR-2（无外部依赖）**: 脚本化评测不调用任何外部模型 API、不访问网络；fixtures 自给自足。
- **NFR-3（性能）**: 全部用例（约 24-30 条）在 60 秒内跑完。
- **NFR-4（可维护性）**: 新增用例只需加 jsonl 行 + fixture，不改 runner 代码。

## Constraints
- **Technical**: Java 17、Spring Boot、JUnit 5、Jackson；不引入新依赖。
- **Business**: 脚本化评测结果确定，不依赖模型随机性。
- **Dependencies**: 依赖 P5-01 的 `PromptCatalog`（已完成）；不依赖批次 2/3。

## Assumptions
- `ReActAgentService` 的编排逻辑可通过 mock 依赖 + `ScriptedAgentClient` 驱动完整循环。
- 文件类工具（`ReadFileTool`、`WriteFileTool`、`EditFileTool`、`DeleteFileTool`）可在临时工作区真实执行，无需 mock。
- `ToolRegistry` 可直接使用真实实现，fixtures 工作区由 `WorkspaceContext` 注入。

## Acceptance Criteria

### AC-1: AgentClient 接口抽取
- **Type**: `rule`
- **Given**: 代码库中存在 `SpringAiAgentClient` 具体类
- **When**: 抽取 `AgentClient` 接口并让 `SpringAiAgentClient implements AgentClient`
- **Then**: `ReActAgentService`、`ContextSummaryService`、`SessionMemoryExtractionService`、`VisionService` 的依赖类型均为 `AgentClient` 接口；`SpringAiAgentClient` 仍为 `@Component` 且唯一 bean
- **Pass Condition**: 编译通过，且 grep 上述四个服务的字段类型均为 `AgentClient`
- **Evidence**: `mvn compile` 成功 + grep 结果

### AC-2: ScriptedAgentClient 可回放工具调用
- **Type**: `rule`
- **Given**: 一个预设了 2 轮工具调用的脚本
- **When**: ReAct 循环连续调用 `streamChat` 两次
- **Then**: 第一次返回预设的工具调用（含 name 和 arguments），第二次返回最终文本；回调顺序正确（onToolCalls → onComplete）
- **Pass Condition**: 单测验证两次 streamChat 返回内容与脚本一致
- **Evidence**: 单测通过

### AC-3: 数据集覆盖六类能力
- **Type**: `rule`
- **Given**: `eval/datasets/` 目录
- **When**: 统计各 jsonl 文件行数与 category 字段
- **Then**: 存在 6 个文件（tool-select、file-edit、file-delete、command-approval、rag、refusal），每类 4-6 条，所有行 JSON 合法且含必填字段
- **Pass Condition**: 6 个文件均存在，每类 ≥4 条，JSON 解析无异常
- **Evidence**: 脚本统计输出

### AC-4: 隔离工作区可重复
- **Type**: `rule`
- **Given**: 一个带 fixture 的 file-edit 用例
- **When**: runner 运行该用例两次
- **Then**: 两次运行的临时工作区互不影响；fixture 文件每次都从原始副本开始；运行结束后临时目录被清理
- **Pass Condition**: 两次运行的文件 diff 结果一致，临时目录不存在残留
- **Evidence**: 两次运行报告中 file-edit 类指标完全一致

### AC-5: 评分正确性
- **Type**: `rule`
- **Given**: 一个 tool_select 用例 expect 工具为 `read_file`，参数 `path=pom.xml`
- **When**: ScriptedAgentClient 回放返回 `read_file` + 正确参数
- **Then**: 该用例评分 pass（工具名命中 + 参数命中）
- **Pass Condition**: 报告中该用例 status=pass，tool_accuracy=1.0
- **Evidence**: 报告 JSON 内容

### AC-6: 一条命令运行 + 报告输出
- **Type**: `rule`
- **Given**: 完整数据集与 fixtures
- **When**: 执行 `mvn test -Dtest=EvalRunner` 或等效命令
- **Then**: 全部用例执行完毕，`eval/reports/<timestamp>.json` 生成，控制台打印分类别摘要表
- **Pass Condition**: 命令退出码 0，报告文件存在且含 category 汇总
- **Evidence**: 命令输出 + 报告文件

### AC-7: 可重复性
- **Type**: `rule`
- **Given**: 完整数据集
- **When**: 连续运行评测两次
- **Then**: 两次报告中所有用例的 pass/fail 状态与分类别指标完全相同
- **Pass Condition**: 两次报告的 category 汇总字段逐字段相等
- **Evidence**: 两次报告 diff 为空

### AC-8: 不破坏现有功能
- **Type**: `rule`
- **Given**: 接口抽取完成
- **When**: 运行现有全部单测
- **Then**: 所有现有测试通过，无新增失败
- **Pass Condition**: `mvn test` 全部通过
- **Evidence**: 测试报告

## Open Questions
- [ ] runner 形式：JUnit 测试类 vs 独立 main 类？（倾向 JUnit，方便 CI 集成）
- [ ] ReActAgentService 依赖较多（11 个构造参数），评测时哪些用真实、哪些 mock？
