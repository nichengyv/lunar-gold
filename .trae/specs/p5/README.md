# Phase 5 总结（智能体能力增强 + 工作台体验）

> 本文件是 Phase 5 的**唯一入口**。后续智能体接手本工作区时，先读这一篇即可；只有在需要追溯某个决策的完整论证、原始阈值或偏差记录时，才进对应的任务子目录。
>
> 阶段状态：**已收口（2026-11-16）**。权威执行记录见 [docs/COMPLETED_WORK.md](../../../docs/COMPLETED_WORK.md) §8，计划与验收见 [docs/phases/phase5/plan.md](../../../docs/phases/phase5/plan.md)，全局取舍见 [docs/UPDATE_PLAN.md](../../../docs/UPDATE_PLAN.md)。

## 1. 阶段目标与出口

Phase 5 解决的问题是：**把"能跑"的智能体变成"可证明有效"的智能体**——每项能力增强都必须有评测数据，而不是凭直觉上线。

出口条件四条，均已满足：

1. 真实 ReAct 可评测（P5-02 / P5-03 / P5-13）
2. 修改有诊断与验证（P5-05 / P5-06）
3. 任务可审查、恢复、撤回（P5-14）
4. 既有功能增强有量化收益（P5-04 / P5-07 / P5-08）

## 2. 目录索引

| 目录 | 内容 |
|---|---|
| `p5-02-eval/` | 离线评测集与 runner（组件级） |
| `p5-04-code-index-benefit/` | repo map 收益 A/B 与持久缓存决策 |
| `p5-05-diagnostics/` | javac / LSP 诊断闭环 |
| `p5-06-verify/` | 项目验证回路 |
| `p5-07-2-retrieval-eval/` | 混合检索质量量化 |
| `p5-07-3-retrieval-reranker-eval/` | 难例 + 真实模型 reranker 对照 |
| `p5-09-remaining-visual/` | 剩余视觉项（密度、响应式、三态） |
| `p5-10-2-approval-efficiency/` | 审批效率（J/K、批量、来源审计） |
| `p5-10-3-long-task-progress/` | 长任务阶段与预算可视化 |
| `p5-10-4-keyboard-accessibility/` | 键盘可达性与 axe |
| `p5-11-1-frontend-e2e/` | 前端 Vitest / Playwright 基线 |
| `p5-11-2-experience-observability/` | 体验指标与本地诊断 |
| `p5-11-3-long-session-performance/` | 长会话性能（虚拟滚动、合帧） |
| `p5-14-changeset/` | 任务级 ChangeSet、撤回与冲突 |

无独立目录的项：**P5-01**（提示词外置）、**P5-03**（评测门禁）、**P5-08**（并行只读工具）、**P5-13**（完成控制）——记录在 `COMPLETED_WORK.md` §8.1 / §8.3 / §8.9 / §8.4。

## 3. 逐项结论

| 编号 | 目标 | 核心决策 | 关键结果 | 状态 / 缺口 |
|---|---|---|---|---|
| P5-01 | 提示词外置与版本化 | 正文放 `classpath:prompts/`，按场景选版本 + SHA-256 校验 | 改文案不再改 Java | 完成 |
| P5-02 | 可离线复现的评测集 | 抽 `AgentClient` 接口，测试用 `ScriptedAgentClient` 回放 | 6 类 27 条 JSONL 用例全过 | 完成；**仅组件级**，不等同端到端 |
| P5-03 | 评测门禁 | 基线入库，成功率降 >2pp / 轮次·token 涨 >10% / p95 涨 >20% 即拦截 | 5 项回退测试通过 | 完成；改基线必须显式 `-Deval.updateBaseline=true` |
| P5-04 | 证明 repo map 有收益 | 阶段 A 确定性测量 + 阶段 B 真实模型 ON/OFF A/B | ON 成功率 100% vs 62.5%；token 4,126 vs 25,072；1.76s vs 10.17s；定位调用 0 vs 14 | 完成，**保持默认开启**；持久缓存按 cold 271ms 判**不建** |
| P5-05 | 变更后自动诊断 | 默认进程内 javac，可选外部 LSP（最小 stdio 协议） | 16 项测试通过 | 完成；不含补全/跳转语义 |
| P5-06 | 修改后自动验证 | 读工作区 `.lunargold/verify.json`，命令走 `CommandService` 沙箱 | 失败回灌 + 重试上限 + 重复失败停止 | 完成；需用户自备配置与工具链 |
| P5-07 | 混合检索 + 引用 | pg_trgm 词法 + RRF 融合，任一通过门槛即用 | hybrid Recall@5 100% vs vector 68.42%，exact 类 0→100% | 完成；`halfvec` 未实施 |
| P5-07-2 | 检索质量量化 | 24 切片 / 22 查询标注语料，三配置对照 | 按预写规则判**不启用 reranker** | 完成 |
| P5-07-3 | 难例 + 真实 reranker | 160 干扰切片，`bge-m3` + `bge-reranker-v2-m3` | top-1 +8.34pp（未达 +10pp 门槛）、rerank p95 483ms（超 300ms 门槛） | 完成，**维持不启用** |
| P5-08 | 只读工具并行 | 只读批并发上限 3，写/命令仍串行 + 审批 | p95 改善 | 完成 |
| P5-09 | 剩余视觉项 | token 化密度、768 断点浮层、三态补齐 | 前端 88/88，Playwright 23 通过 | 完成 |
| P5-10-2 | 审批效率 | J/K 选中、`WRITE` 白名单批量、`decision_source` 审计 | 后端 290 项通过 | 完成；无 `approval.runId`，批量按 session + ID 集合界定 |
| P5-10-3 | 长任务可感知 | 五阶段时间线 + 轮次/token/耗时预算 + 失败分级 | 前端 31/31 | 完成；轮次不落库，断线恢复显示「—」 |
| P5-10-4 | 键盘与无障碍 | 焦点陷阱、原生 button 会话项、`reduceMotion`、axe | `/chat` 与 `/settings` axe critical 0 | 完成 |
| P5-11-1 | 前端测试基线 | Vitest（jsdom）+ Playwright；E2E 用 `page.route` 仿真后端 | 22/22、21/21（重复 3 轮无 flaky） | 完成；**E2E 不启真实后端**（已写入 FR-7/AC-5） |
| P5-11-2 | 体验可观测性 | 后端聚合首字 p95/审批等待/token；前端本地脱敏样本 | 后端 298 项、前端 57/57、E2E 42/42 | 完成；不是全平台 SLO，不上传 |
| P5-11-3 | 长会话性能 | rAF 合帧 + Markdown 增量 + >200 条虚拟滚动 | 首屏 220→108ms、DOM 9000→216、60s 流式 0 长任务 | 完成；**虚拟化下跨窗口整段拖选不可用** |
| P5-13 | 真实 ReAct 闭环 | `COMPLETED/UNVERIFIED/INCOMPLETE` 与执行状态分离；无进展检测 | Runtime 15/15 | 完成；基线 `task_success_rate=20%` 是脚本用例刻意结果，**不能当真实模型成功率** |
| P5-14 | 任务级交付视图 | 聚合多次 Run，不新增 Task/ChangeSet 表（`turnId` 写入 `requestJson`） | `ChangeSetServiceTest` 7 项 | 完成；命令的外部副作用不承诺回滚 |

## 4. 生产默认值（改动前必看）

| 配置 | 默认 | 依据 |
|---|---|---|
| `agent.repo-map.enabled` | `true` | P5-04 A/B，B1～B5 全过 |
| `agent.repo-map.max-chars` | `6000` | 未动态化（升级候选见 UPDATE_PLAN §5.2，**已暂缓**） |
| `rag.hybrid-enabled` | 沿用既有（默认单路向量关闭混合时） | P5-07 上线混合但未改默认开关 |
| 模型 reranker | **不启用** | P5-07-2 / P5-07-3 两轮数据均不达标 |
| `code_index` 持久表 | **不建** | cold 271ms，重建比重建缓存划算 |
| `halfvec`（V20） | 未实施 | 体积/精度收益未证明 |
| `agent.loop.no-progress.max-repeats` | `3` | P5-13 |
| `agent.loop.no-progress.max-stagnant-rounds` | `4` | P5-13 |
| `agent.budget.max-seconds-per-run` | `0`（不限） | P5-10-3 新增，默认不改变既有行为 |

## 5. 已知短板与暂缓项

1. **符号抽取不全**：`LightweightCodeIndexer.supports()` 声明了 kotlin/scala/groovy/c/cpp/cs/php/rb/swift，但 `index()` 无对应分支，这些文件抽不到符号；Java 接口中无修饰符的方法也会漏。
2. **repo map 无相关性排序**：`TreeMap` 字母序遍历撞预算即停，大仓库靠后目录进不了 map。
3. **`max-chars` 固定 6000**：本仓库 464 文件已占满并截断。
4. **搜索深度限制**：生产 `workspace.max-list-depth=3`，`search_files` 看不到更深路径——这是 P5-04 结论的**适用边界**，若放开必须重跑 A/B。
5. **E2E 是浏览器层仿真**：不验证真实 Spring 事务与真实模型链路。
6. **虚拟滚动限制**：>200 条时跨窗口整段拖选不可用。

第 1～3 项为索引升级候选，已在 `UPDATE_PLAN.md` §5.2 标为**暂缓**，触发条件（>1000 文件截断 / C# 等缺失语言 / recall 跌破 90%）满足任一才重新评估。

## 6. 评测体系快照

五层验证，缺一层都不能互相替代：

1. 单元/集成测试
2. 组件评测（`EvalGate` + `eval/baselines/baseline.json`，27 条）
3. 真实 Runtime 评测（`RuntimeEvalGate` + `runtime-baseline.json`，15 条）
4. RAG 检索评测（`RagEvalGateTest` + `rag-baseline.json`）
5. 代码索引评测（`IndexBenefitEvalTest` 结构性门禁 + `IndexAbEvalTest` 有效性门禁）

## 7. 常用命令

```powershell
# 后端子类/全量
mvn -o "-Dtest=XXXX" test
mvn -o test

# 一键评测（组件 + Runtime）
powershell -ExecutionPolicy Bypass -File scripts\run-eval.ps1

# RAG 检索门禁（需 DESKTOP_PG_RUNTIME）
powershell -ExecutionPolicy Bypass -File scripts\run-rag-eval.ps1 [-UpdateBaseline]

# 代码索引：默认阶段 A；-RealModel 才跑真实模型 A/B，缺 Key 退出码 2
powershell -ExecutionPolicy Bypass -File scripts\run-index-eval.ps1 [-RealModel]

# 前端
cd frontend
npx vitest run
npx playwright test
npm run build
```

## 8. 环境事实（踩过的坑）

- **Maven 不在 PATH**：用 IDE 内置 `Maven 3.9.16`，`JAVA_HOME` 指向 `C:\Users\16680\.jdks\temurin-24`。
- **中文日志乱码**：`mvn ... *> log.log` 后读日志或 surefire 报告，不要直接看控制台。
- **esbuild 清理系统 Temp 失败**：把 `TEMP`/`TMP` 指向工作区 `.tmp` 再跑前端构建。
- **Playwright 下载浏览器失败**：本机改用系统 Edge（`E2E_CHANNEL=msedge`）；CI 用自带 chromium。
- **pgvector 评测需运行时**：未设 `DESKTOP_PG_RUNTIME` 时相关门禁跳过，不并入默认 CI。
- **真实模型评测需 Key**：`P5_04_MODEL_KEY`（索引 A/B）、`RAG_EVAL_EMBEDDING_KEY` / rerank Key；缺 Key 只跳过，**不得写收益结论**。

## 9. 后续智能体必须遵守

1. **不要改评测阈值或基线来让门禁变绿**；基线只在显式 `-Deval.updateBaseline=true` 时更新，且必须说明理由。
2. **不要用脚本化/组件级数据冒充真实模型收益**；没有 Key 就写缺口，不下结论。
3. **不要为了"更完整"去动 §5 的暂缓项**；等触发条件出现。
4. 结论必须带适用边界（模型名、fixture 规模、生产配置）。
5. 新任务先在本目录下建 `<task-id>/`（`spec.md` / `tasks.md` / `checklist.md`），经确认后再实施；完成后在本文件第 3 节补一行。
