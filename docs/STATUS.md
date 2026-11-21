# Lunar Gold（金月）· 项目状态速查

> 进入项目先读这一页；细节按「文档导航」跳转，本文只保留当前状态与产品边界。
> 更新日期：2026-11-21；基线提交 `f825fc4`（Gitee `origin/master`）。
> 后端 **502 tests / 0 failures / 17 skipped**（`mvn clean test`）；前端 **112 tests** 通过，`vue-tsc + vite build` 通过。

## 一句话定位

Lunar Gold 是本地优先、可控、可验证的 AI 编程工作台，桌面端是首个完整客户端。Phase 0～7 已收口，Phase 8 并入 R7；当前处于 R7 长任务能力收尾。

## 当前主线：R7 长任务能力

| 子项 | 状态 | 已完成内容 | 剩余 |
|---|---|---|---|
| R7a 评测扩容 | 已完成 | 每层 n≥10（L1/L2/L3 各 20）；`LongSessionStatsGate` 预注册阈值（Wilson 95% 下限、memoryProbe、provider 失败率、基线退化） | 真实模型 n≥10 正式基线待跑 |
| R7b Run 级断点续跑 | 已完成 | 轮末写 `agent_run.checkpoint_json` + `CHECKPOINT` 事件；重启后 `FAILED/PROCESS_RESTARTED` 并保留断点；`POST /api/agent/runs/{runId}/resume` + 前端「继续执行」；真实 `taskkill`→重启→resume E2E 通过 | 审批中/等待用户输入仍终止；仅支持手动触发 |
| R7b-live 续跑完成率 | 部分完成 | `P7_02_RESUME=on` A/B runner + `LongSessionResumeGate` 已落地；Key 冒烟 n=1 通过（直接执行与续跑均 success，`resumeInjected=true`） | L1 全量 20 对（约 3-4h）、L2/L3（10h+）待确认后执行；尚无完成率结论 |
| L1～L4 门禁验证 | 部分完成 | L3/L4 真实模型 n=1：L3 12 turn 全部 SUCCEEDED、60/60 工件、memoryProbe 通过，期间真实工具失败 1 次并自行恢复（`success=true`，口径修正后复验）；L4 prompt 峰值 7,949 tokens 达标，不跑 condense A/B；L1 已补 30 turn / 150 工件数据集（10 replicas，离线通过） | L3 各 turn 均 `UNVERIFIED:VERIFY_NOT_CONFIGURED`，完成仍靠模型自报（→ R7c L5）；L1 真实模型未运行；L2/L3 层完成率待批准 |
| R7c 长任务剩余项 | 进行中 | L5 完成强校验：无 `verify.json` 时按 pom.xml / build.gradle / package.json / go.mod / Cargo.toml / tsconfig.json 自动探测验证命令，标注来源、可关闭、显式配置优先 | 大任务分解（L7）、检索型记忆未开始；裸源码目录仍需显式配置 |
| P3-08 干净机器验收 | 未开始 | — | Windows 干净机器完整任务 + 所有者确认 |

判定规则：**L1～L8 全部达标才能宣称「具备长任务能力」**；当前只可表述为「中等长度任务稳定」。

## 当前能力边界（用户可见）

- 可执行多轮长会话：计划跟踪、循环保护、可选上下文压缩（默认关闭）
- 执行中可调用 `ask_user_question` 向用户提问（仅 root agent；等待上限 15 分钟；断线可还原卡片）
- 崩溃后仅 `RUNNING` 可手动续跑；工具副作用按 at-least-once 语义可能重复执行
- 尚未支持子任务分解、完成状态强校验、自动续跑

## 文档导航

完整索引与「事实归属」见 [README.md](README.md)。常用入口：未完成计划与阈值 → [UPDATE_PLAN.md](UPDATE_PLAN.md)（第 14 节）；完成证据 → [COMPLETED_WORK.md](COMPLETED_WORK.md)；架构实现 → [architecture/README.md](architecture/README.md)；命令与排障 → [operations.md](operations.md)；新会话接手 → [handover-20261121.md](handover-20261121.md)。

## 改代码前先读

| 链路 | 入口 |
|---|---|
| SSE / 聊天请求 | `AgentChatController.streamChat` |
| ReAct 主循环 | `AgentOrchestrator`（`ReActAgentService` 只保留入口与装配） |
| 工具执行与审批 | `ToolExecutor` + `ApprovalGate` + `ToolRegistry.dispatch` |
| Run 状态与断点 | `AgentRunService` + `RunEventStore` + `RunCheckpoint` |
| 上下文组装 | `ContextAssembler` + `ContextCondenser`（`agent/context`） |

## 已知边界

| 边界 | 说明 |
|---|---|
| 重启残局 | `RUNNING` 崩溃 → `FAILED/PROCESS_RESTARTED` + 保留 checkpoint，用户手动续跑；`WAITING_APPROVAL` / `WAITING_USER_INPUT` 终止且不可续跑 |
| 审批 | 挂起 120s 自动拒绝；残留审批随 Run 终止，不再接受批准 |
| 撤销 | `revert-run` / `revert-session` 只回滚平台记录的文件改动，命令等外部副作用不撤销 |
| MCP | 仅支持 stdio；MCP 进程失败不阻塞主流程 |
| 平台 | 当前只适配 Windows；PowerShell 5 与 NSIS 打包限制见 operations.md |
| 完成判定 | 计划收口已强制（R7b 口径），验证命令支持自动探测；裸源码目录与「计划语义正确性」仍为已知边界（R7c 续） |

## 踩坑速查（完整版见 operations.md）

1. **PowerShell 5**：不能用 `&&` / `||`，用 `;`；`-Dtest=A,B` 必须加引号。
2. **`.ps1` 编码**：UTF-8 必须带 BOM，否则中文输出乱码、`irm` 下载脚本可能损坏。
3. **新增公开端点**：必须同步 `PublicApiCatalog.DECLARED` 与 `src/test/resources/contract/public-api.json`，否则应用启动自检失败（`PublicApiContractTest` 有漂移守卫）。
4. **验证用 clean**：涉及新符号时跑 `mvn clean test`；增量编译曾掩盖缺少 import 的编译错误。
5. **前端 build**：沙箱下把 `TEMP`/`TMP` 指向 `frontend/node_modules/.cache/esbuild-tmp`，避免 esbuild 临时文件删除失败。
