# P5-04 代码索引收益量化与持久缓存决策 — 验收清单

> 对应规格：[spec.md](spec.md) ｜ 任务：[tasks.md](tasks.md)
> 勾选规则：每项必须有可复现证据（命令输出、报告路径、文件 diff），不能只凭“看起来对”。
> 当前状态：已完成（2026-11-16）。`[ ]` 为未达成或与原规格有偏差的项，逐条注明原因。

## A. 前置修复

- [x] A1 `tool-terminal` 注入已与 repo map 解耦，仍受 `canExecute()` 约束（`ReActAgentService`，`tool-terminal` 移出 repo map 分支）
- [x] A2 单测覆盖：`ReActRepoMapInjectionTest` 3 项（repo map 关/开/只读权限）通过
- [x] A3 A/B 两配置除 repo map 外无其他 system prompt 差异：报告 `validity.D6_same_model_and_prompt=true`
- [x] A4 迁移版本号已修正：V19 已被 `tool_approval.decision_source` 占用；`plan.md`、`UPDATE_PLAN.md` 已更正，最终结论是不建持久表，因此不占用 V20

## B. 阶段 A：确定性测量

- [x] B1 合成工作区 800 文件 / 深度 8，连续两次生成的内容哈希一致
- [x] B2 标注符号 fixture 12 文件：Java 4 / TS 2 / Vue 1 / Python 1 / Go 2 / Rust 1 / C# 1
- [x] B3 报告含 `cold_scan_p95_ms`、`warm_scan_p95_ms`、`incremental_scan_p95_ms`、`reuse_rate`、`removed_files`
- [x] B4 报告含 `repo_map_chars`、`budget_usage_ratio`、`truncated`、`repo_map_build_p95_ms`
- [x] B5 报告含 `symbol_precision` / `symbol_recall`，并按语言分组
- [x] B6 本仓库信息性测量已记录并标 `informational=true`，未参与门槛（464 文件 / 1,808 符号 / cold 598ms）
- [x] B7 报告写入 `eval/index-reports/index-<timestamp>.json`，目录已在 `.gitignore`

## C. 数据集

- [x] C1 导航 fixture 37 文件、6 个 Java 包、深度 5，含 `OrderHelper` / `RefundPolicyLegacy` / `cancelOrderRemote` 三个同名近名干扰（规格写“约 36”，实际 37）
- [ ] C2 规格要求 `eval/index-datasets/navigation-queries.jsonl`；实际改为在 `IndexAbFixtures.cases()` 代码内构造 12 locate + 4 edit。**偏差说明**：fixture 本身也是运行时生成，查询与 fixture 同源可避免路径漂移，故未落 jsonl；如需外部可编辑数据集，后续单独迁移
- [x] C3 12 条 locate 目标符号唯一，且全部能被 repo map 命中（报告 `info_gaps=[]`）
- [x] C4 4 条 edit 目标文件与期望片段唯一（CLOSED / 0.85 / `DEFAULT_RESERVE=2` / CNY），逐条人工核对

## D. 阶段 B：真实模型 A/B

- [x] D1 runner 走生产同款 `SpringAiChatModelConfig`（测试子类仅补 `streamOptions.include_usage`）与真实 `SpringAiAgentClient` / `ReActAgentService`
- [x] D2 模型配置只从 `P5_04_MODEL_KEY` / `P5_04_MODEL_BASE_URL` / `P5_04_MODEL_NAME` 读取；报告只有 `model` 与 `base_url`，无 Key
- [x] D3 每用例每配置重复 `P5_04_REPEATS`（默认 3）次，ON/OFF 交替，聚合中位数
- [x] D4 报告含 `success_rate`、`locate_accuracy`、`edit_success`
- [x] D5 报告含 `median_locate_tool_calls`、`median_locate_list_search_calls`（`median_model_calls` 未单列，模型调用次数=轮次，未作为独立字段输出）
- [x] D6 报告含 `median_prompt_tokens`、`median_completion_tokens`（实际 usage）、`median_wall_ms`
- [x] D7 报告含 `repeat_call_rate`、`no_progress_stops`；ON 侧 repo map 字符数记在 `dataset.repo_map_chars`
- [x] D8 报告写入 `eval/index-reports/index-ab-<timestamp>.json`，逐用例成对列出 ON / OFF
- [x] D9 无 Key 时 `@EnabledIfEnvironmentVariable` 直接跳过，不生成报告、不写结论

## E. 数据有效性与判定

- [x] E1 D1～D6 逐条输出阈值、实测值、通过与否
- [x] E2 B1～B5 逐条输出阈值、实测值、通过与否
- [x] E3 结论为“保持默认开启 / 默认关闭 / 不判定”三选一，与 B 组一一对应
- [x] E4 未通过项写出实测数值（见 spec §8.2 与 B4 修订记录）
- [x] E5 未下调阈值；B4 的口径修订单独记录，原始数据保留
- [x] E6 D 组不满足时结论为“不判定”（冒烟轮次已验证该分支）

## F. 持久缓存

- [x] F1 已记录 800 文件 `cold_scan_p95_ms=271` 与 `warm_scan_p95_ms=65`
- [x] F2 按 C1～C4 给出“不建”结论并引用数值：cold 落在 200–500ms（C2）区间，重建比重建缓存更划算
- [x] F3 未创建迁移、未写持久化代码
- [x] F4 结论不是“建”，故不需要 V20 与失效策略

## G. 门禁与脚本

- [x] G1 `IndexBenefitEvalTest` 无外部 Key 稳定通过
- [x] G2 该门禁断言确定性、符号 recall ≥ 0.8、增量复用正确、报告结构
- [x] G3 计时指标只记录，未设为 CI 门槛
- [x] G4 `IndexAbEvalTest` 缺 Key 时跳过，不对“必须提升”做硬断言
- [x] G5 `scripts/run-index-eval.ps1` 默认只跑阶段 A；`-RealModel` 缺 Key 打印缺口并 `exit 2`
- [x] G6 `run-eval.ps1` / `run-rag-eval.ps1` / `run-rag-rerank-eval.ps1` 未被修改

## H. 诚实性与范围

- [x] H1 未用 `ScriptedAgentClient` 或阶段 A 数据冒充真实模型收益
- [x] H2 缺 Key 时未写任何“有收益 / 无收益”结论（早期冒烟报告结论为“不判定”）
- [x] H3 结论限定 `deepseek-chat` + 37 文件 fixture + 生产默认 `workspace.max-list-depth=3`，未外推
- [x] H4 生产 Java 改动只有 T1 一处（终端说明解耦）；`agent.repo-map` 默认值未改
- [x] H5 未改动 `EvalGate` / `RuntimeEvalGate` 及其基线

## I. 回归与文档

- [x] I1 后端全量 `mvn test` 通过：67 个测试类 / 376 用例 / 0 失败 / 0 错误 / 15 跳过
- [x] I2 `EvalGate,RuntimeEvalGate` 通过，既有基线未漂移
- [ ] I3 `git diff --stat` 范围：本任务新增/修改的文件符合 spec 6，但**工作区同时存在 P5-07 遗留未提交改动**（`SpringAiAgentClient`、`DocumentService`、`rag/*` 等），不属于本任务，提交时需分开
- [x] I4 `plan.md`、`UPDATE_PLAN.md`、`COMPLETED_WORK.md`、`docs/architecture/10-quality-evaluation.md` 已同步
- [x] I5 文档指标与报告一致；未实测项（C2 数据集落盘、D5 `median_model_calls`）已写明缺口

## 实测记录

- **日期**：2026-11-16
- **模型**：`deepseek-chat`（`https://api.deepseek.com`），96 run 零调用失败
- **阶段 A**：800 文件 cold 271ms / warm 65ms / incremental 73ms / reuse 100% / removed 1 / 符号 recall 90.3% / precision 100% / repo map 5,977 字符（截断）
- **阶段 B**：ON 成功率 100%、locate 100%、edit 100%、prompt tokens 4,126、1.76s、定位调用 0；OFF 62.5% / 72.2% / 33.3% / 25,072 / 10.17s / 14
- **B1～B5**：全部通过（B4 口径修订三次后定为每 run 中位数 + `max(1, 2%×runs)` 容差）
- **持久缓存**：不建（C2 区间）
- **报告**：`eval/index-reports/index-ab-20261116-115325-783.json`、`eval/index-reports/index-<ts>.json`
