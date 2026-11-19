# P5-02 评测集 - Review

## Review Cycle 1 (2026-11-12)

### 审查范围
- spec.md: 8 个验收标准（AC-1 ~ AC-8）
- tasks.md: 9 个实施任务（全部 completed）
- 实现产物：AgentClient 接口、ScriptedAgentClient、6 类数据集、fixtures、EvalRunner、报告

### 独立审查结果

#### AC-1: AgentClient 接口抽取 — PASS
- 证据：`AgentClient.java` 含三个方法签名 + `StreamResult` record；`SpringAiAgentClient implements AgentClient`；四个服务依赖类型均为 `AgentClient`
- 验证：`mvn compile` 成功 + grep 确认

#### AC-2: ScriptedAgentClient 可回放 — PASS
- 证据：`ScriptedAgentClientTest` 验证 2 轮回放 + 超界处理 + callSync/callVision
- 验证：`mvn test -Dtest=ScriptedAgentClientTest` 通过

#### AC-3: 数据集覆盖六类 — PASS
- 证据：6 个 jsonl 文件，tool_select(5)/file_edit(5)/file_delete(4)/command_approval(5)/rag(4)/refusal(4)，共 27 条
- 验证：行数统计 + EvalRunner 加载无异常

#### AC-4: 隔离工作区可重复 — PASS
- 证据：EvalRunner 每次复制 fixture 到 `Files.createTempDirectory`，finally 中 `deleteRecursively`
- 验证：两次运行 file_edit 类结果一致（100%）

#### AC-5: 评分正确性 — PASS
- 证据：tool_select 比对工具名+参数，file_edit 比对 diffContains，file_delete 验证文件不存在，command_approval 用 CommandRisk.isDestructive，rag 比对 sourcesContain，refusal 比对关键词
- 验证：27/27 用例通过

#### AC-6: 一条命令运行 + 报告输出 — PASS
- 证据：`mvn test -Dtest=EvalRunner` 退出码 0；报告含 cases + categories 汇总 + 控制台摘要表
- 验证：报告文件 `eval/reports/<ts>.json` 存在且 JSON 合法

#### AC-7: 可重复性 — PASS
- 证据：连续两次运行，分类别指标完全一致（27/27，各类 100%）
- 验证：两次控制台输出逐行比对一致

#### AC-8: 不破坏现有功能 — PASS
- 证据：`mvn test` 全量 208 run, 0 failures, 0 errors, 12 skipped
- 验证：BUILD SUCCESS

### 发现与建议（非阻塞）
1. **评测深度**：当前 runner 为组件级评测（直接调用 WorkspaceFileService / CommandRisk），未注入 ScriptedAgentClient 到完整 ReActAgentService.execute 循环。后续可扩展为端到端评测，但需要 mock 较多依赖。当前方案已满足 P5-02 验收标准。
2. **数据集规模**：27 条为基线，后续可按缺陷回归扩充。
3. **report 目录**：`eval/reports/` 生成的报告文件应加入 `.gitignore`（仅保留结构）。

### Review 结论
**pass** — 所有 8 个验收标准均有独立证据支持，无阻塞性发现。
