# P5-04 代码索引收益量化与持久缓存决策 — 任务清单

> 对应规格：[spec.md](spec.md)
> 状态：待确认（未实施）
> 原则：先修前置耦合，再跑阶段 A 确定性指标，最后在 Key 到位后跑阶段 B；缺 Key 只报缺口，不写收益结论。

## 阶段 0：前置修复（阻塞后续测量）

### T1 解耦终端工具说明与 repo map

- 在 `ReActAgentService.buildMessages` 中，把 `tool-terminal` 注入移出 `repoMap` 非空分支，仍以 `policy.permission().canExecute()` 为条件。
- 补单测：`agent.repo-map.enabled=false` 或 repo map 为空时，system prompt 仍包含终端规则；开启时行为不变。

验证：新增单测通过；A/B 两配置的差异只剩 repo map 一段。

### T2 迁移版本号事实修正

- 只在文档中修正：V19 已被 `V19__add_tool_approval_decision_source.sql` 占用，`code_index` 若实施应使用 V20（halfvec 顺延 V21）。
- 本任务不改迁移文件。

验证：`docs/UPDATE_PLAN.md` 与 `docs/phases/phase5/plan.md` 不再出现“V19 = code_index”。

## 阶段 1：阶段 A 确定性测量

### T3 生成式工作区

- 测试作用域新增确定性工作区生成器：800 文件 / 深度 8（Java 500、TS 150、Vue 50、md/json 100），写入临时目录，结束删除。
- 生成内容固定（不使用随机数），保证两次运行文件集合与符号集合一致。

验证：连续两次生成的 `path + symbol` 哈希一致；文件数与深度符合预期。

### T4 符号标注 fixture 与准确率

- 新增 12 个标注文件（Java 4、TS 2、Vue 1、Python 1、Go 2、Rust 1、C# 1），每文件列出期望符号名集合。
- 计算 name-level precision / recall，按语言分组；Java 额外记录 package / type / method 识别情况。

验证：标注集合可被测试读取；准确率输出到报告与控制台。

### T5 实现 `CodeIndexBenefitEvalRunner`

- 采集 spec 4.2 的全部指标：cold / warm / incremental 耗时、reuse_rate、removed_files、repo_map 字符与预算占用、构建耗时、确定性、符号准确率。
- 对本仓库做一次信息性测量并标注 `informational=true`。
- 输出 `eval/index-reports/index-<timestamp>.json` 与控制台指标表。

验证：报告字段齐全；计时为实测值；本仓库测量不参与门槛。

### T6 实现 `CodeIndexBenefitEvalTest`

- 断言 D3（大仓库确定性）、D4（recall ≥ 0.8）、增量复用正确性（reused > 0、只重扫变更文件、删除被清理）、报告结构完整。
- 计时只记录，不设通过门槛。

验证：无任何外部 Key 时稳定通过；故意破坏确定性或复用逻辑时失败。

## 阶段 2：阶段 B 真实模型 A/B

### T7 navigation fixture 与用例集

- 新增 `eval/fixtures/navigation-project/`：约 36 文件、6 包、深度 5，含同名 / 近名干扰，每个目标符号唯一。
- 新增 `eval/index-datasets/navigation-queries.jsonl`：12 条 `locate` + 4 条 `edit`，字段按 spec 4.3。
- 加载时校验 D1 / D2：目标唯一、目标符号能在 repo map 中命中；未命中标记 `info_gap`。

验证：加载校验通过；逐条人工确认目标路径与符号正确。

### T8 实现真实模型 A/B runner

- 测试作用域构造生产同款 `SpringAiChatModelConfig` + `SpringAiAgentClient`，Key 与 base-url / 模型名只从 `P5_04_*` 环境变量读取。
- 复用 `RuntimeEvalRunner` 的 Run/Event 与回调装配方式；工具集按 spec 4.3；verify 关闭、诊断启用。
- 每个用例每配置重复 `P5_04_REPEATS`（默认 3）次，两配置交替执行，聚合中位数。
- 输出 `eval/index-reports/index-ab-<timestamp>.json`，含逐用例成对结果与 B1～B5 台账。

验证：无 Key 时不执行、不生成报告；有 Key 时单用例可跑通并记录实际 usage token。

### T9 实现 `CodeIndexAbEvalTest`

- `@EnabledIfEnvironmentVariable(named="P5_04_MODEL_KEY")`；断言 D1、D5、D6、报告结构、每用例完整性。
- 不对“ON 必须优于 OFF”做硬断言。

验证：缺 Key 跳过且不写报告；有 Key 时结构与数据有效性断言生效。

### T10 编写 `scripts/run-index-eval.ps1`

- 默认运行阶段 A 的 runner + gate。
- `-RealModel` 时先检查 `P5_04_MODEL_KEY`，缺失打印缺口并 `exit 2`，不启动 Maven。
- 不改动 `run-eval.ps1`、`run-rag-eval.ps1`、`run-rag-rerank-eval.ps1`。

验证：无 Key 时输出明确缺口；有 Key 时一次跑完两阶段。

## 阶段 3：实测与结论

### T11 阶段 A 实跑与记录

- 跑 800 文件工作区与本仓库测量，记录 cold / warm / incremental、复用率、repo map 字符占用、符号准确率。
- 写入 spec 第 8 节（阶段 A 部分）。

验证：报告中每个数值可追溯到一次命令输出。

### T12 阶段 B 实跑与判定

- Key 到位后：记录模型名、日期、用例数、重复次数；先检查 D1～D6，再跑全量 A/B。
- 按 B1～B5 输出逐条台账与三选一结论；未通过的编号必须写出实测值。
- 缺 Key：只写前置缺口，不写收益结论，不修改 repo map 默认值。

验证：结论唯一，与阈值编号一一对应。

### T13 持久缓存结论

- 按 C1～C4 给出“建 / 不建 / 先优化”的结论与实测耗时依据。
- 本任务不创建迁移、不写持久化代码。

验证：结论可对应到具体 `cold_scan_p95_ms` / `warm_scan_p95_ms` 数值。

## 阶段 4：文档与回归

### T14 同步文档

- `docs/phases/phase5/plan.md`：P5-04 小节补收益结论或缺口、持久缓存结论。
- `docs/UPDATE_PLAN.md`：P5-04 状态行、第 0 节下一步、第 12 节执行顺序、迁移版本号修正。
- `docs/COMPLETED_WORK.md`：新增 P5-04 收益量化条目。
- `docs/architecture/10-quality-evaluation.md`：补充索引成本 / 符号质量 / A/B 口径。

验证：文档口径与报告一致；未实测时不写指标数字。

### T15 回归验证

- 后端全量 `mvn test`；`EvalGate,RuntimeEvalGate` 通过；既有基线未漂移。
- 前端未改动，无需重新构建（在结论中说明）。
- `git diff --stat` 仅包含测试作用域、`eval/` 数据集、脚本、spec 与文档，以及 T1 的一处生产改动。

验证：命令输出与实际一致；记录用例数、失败数与跳过数。
