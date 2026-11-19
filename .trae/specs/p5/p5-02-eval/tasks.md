# P5-02 评测集 - Implementation Plan

## Task 1: 抽取 AgentClient 接口与 StreamResult
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新建 `AgentClient` 接口，含 `streamChat`、`callSync`、`callVision` 三个方法，签名与 `SpringAiAgentClient` 一致。
  - 将 `StreamResult` record 从 `SpringAiAgentClient` 内部类迁为 `AgentClient` 的内部 record。
  - `SpringAiAgentClient implements AgentClient`，方法加 `@Override`。
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-1.1: `mvn compile` 通过，`SpringAiAgentClient` 声明 `implements AgentClient`
  - `rule` TR-1.2: `AgentClient` 接口含三个方法签名，`StreamResult` 可被接口方法引用
- **Completion Evidence**:
  - `mvn -DskipTests compile` exit code 0
  - `AgentClient.java` 含 `streamChat`/`callSync`/`callVision` + `StreamResult` record
  - `SpringAiAgentClient.java` L54 `implements AgentClient`，三个方法均有 `@Override`

## Task 2: 改造依赖方使用 AgentClient 接口
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - `ReActAgentService`、`ContextSummaryService`、`SessionMemoryExtractionService`、`VisionService` 的字段类型与构造参数从 `SpringAiAgentClient` 改为 `AgentClient`。
- **Acceptance Criteria Addressed**: AC-1, AC-8
- **Test Requirements**:
  - `rule` TR-2.1: 四个服务的字段类型均为 `AgentClient`（grep 验证）
  - `rule` TR-2.2: `mvn test` 现有测试全部通过
- **Completion Evidence**:
  - grep 确认四个服务字段/构造参数均为 `AgentClient`
  - `mvn test` 全量 208 用例 0 失败

## Task 3: 实现 ScriptedAgentClient
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `ScriptedAgentClient implements AgentClient`（test 源码），按脚本序列回放工具调用或文本。
- **Acceptance Criteria Addressed**: AC-2
- **Test Requirements**:
  - `rule` TR-3.1: 单测验证 2 轮脚本回放
  - `rule` TR-3.2: 超出脚本长度返回空文本
- **Completion Evidence**:
  - `ScriptedAgentClientTest` 3 用例通过
  - `mvn test -Dtest=ScriptedAgentClientTest` exit code 0

## Task 4: 创建评测数据集（6 类 jsonl）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 6 个 jsonl 文件共 27 条用例，覆盖 tool_select(5)/file_edit(5)/file_delete(4)/command_approval(5)/rag(4)/refusal(4)。
- **Acceptance Criteria Addressed**: AC-3
- **Test Requirements**:
  - `rule` TR-4.1: 6 个文件存在，每类 ≥4 条，Jackson 解析全部成功
  - `rule` TR-4.2: 所有行含必填字段
- **Completion Evidence**:
  - 6 文件行数统计：5/5/4/5/4/4
  - EvalRunner 加载全部 27 条无解析异常

## Task 5: 创建 fixtures 工作区
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - `eval/fixtures/java-small/`（pom.xml + src/Main.java）、`eval/fixtures/simple-project/`（README + src/main.py + src/utils.py）。
- **Acceptance Criteria Addressed**: AC-3, AC-4
- **Test Requirements**:
  - `rule` TR-5.1: fixture 目录存在且含预期文件
  - `rule` TR-5.2: file-edit 用例运行后 diff 与 expect 一致
- **Completion Evidence**:
  - fixture 文件已创建
  - file_edit 类 5/5 通过

## Task 6: 实现评测 runner
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2, Task 3, Task 4, Task 5
- **Description**:
  - `EvalRunner`（JUnit）加载数据集、复制 fixture 到临时目录、按 category 执行、评分、输出报告。
- **Acceptance Criteria Addressed**: AC-4, AC-6
- **Test Requirements**:
  - `rule` TR-6.1: `mvn test -Dtest=EvalRunner` 退出码 0
  - `rule` TR-6.2: 临时工作区运行后被删除
- **Completion Evidence**:
  - `mvn test -Dtest=EvalRunner` exit code 0，27/27 通过
  - 每次运行后临时目录自动清理

## Task 7: 实现评分逻辑
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 6
- **Description**:
  - 六类评分：tool_select 工具名+参数、file_edit diffContains、file_delete 文件不存在、command_approval 破坏性判定、rag 引用命中、refusal 拒答关键词。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-7.1: tool_select 工具名命中时通过
  - `rule` TR-7.2: file_edit diff 匹配时通过
- **Completion Evidence**:
  - tool_select 5/5、file_edit 5/5 全部通过

## Task 8: 实现报告输出
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 7
- **Description**:
  - 输出 `eval/reports/<timestamp>.json`（cases + categories 汇总），控制台打印分类别摘要表。
- **Acceptance Criteria Addressed**: AC-6, AC-7
- **Test Requirements**:
  - `rule` TR-8.1: 报告文件存在且 JSON 合法，含 `categories` 字段
  - `rule` TR-8.2: 连续运行两次 categories 字段逐字段相等
- **Completion Evidence**:
  - 报告文件已生成
  - 两次运行 categories 完全一致（27/27，各类 100%）

## Task 9: 全量验证与回归
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 8
- **Description**:
  - 运行 `mvn test` 确保现有测试通过；运行 EvalRunner 两次验证可重复性；更新 plan.md。
- **Acceptance Criteria Addressed**: AC-7, AC-8
- **Test Requirements**:
  - `rule` TR-9.1: `mvn test` 全部通过
  - `rule` TR-9.2: 两次 EvalRunner 报告 categories diff 为空
- **Completion Evidence**:
  - `mvn test` 208 run, 0 failures, 0 errors
  - 两次 EvalRunner 输出完全一致
  - plan.md P5-02 标记为已完成
