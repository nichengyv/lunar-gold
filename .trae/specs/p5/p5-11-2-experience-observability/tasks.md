# P5-11-2 体验可观测性 — 实施任务

## T1 后端体验指标聚合

- [x] 增加当前用户最近 Run、首条 MODEL_CHUNK 时间和已决策审批的限量查询。
- [x] 实现首字 p95、工具失败率、审批平均等待与每轮 token 聚合，空样本返回 null。
- [x] 增加 `GET /api/agent/metrics/experience`，保证用户隔离与窗口说明。
- [x] 补 `AgentExperienceMetricsServiceTest` 并跑后端聚焦测试。

## T2 浏览器本地诊断基础

- [x] 实现白名单存储、脱敏、容量裁剪、开关、清除和导出快照。
- [x] 接入 `web-vitals`、Vue errorHandler、window error 与 unhandledrejection。
- [x] 关闭时移除监听/停止采样，存储不可用时静默降级。
- [x] 补工具模块 Vitest。

## T3 Run 与断线恢复采样

- [x] 在 onRunId 与 recoverRun 的真实状态迁移点记录本地样本。
- [x] 按 run 去重计算中断率，按恢复终态计算成功率和平均恢复耗时。
- [x] 账号切换清理本地 run/session 标识，正常切会话不伪造恢复失败。
- [x] 补 store/聚合聚焦测试。

## T4 设置页体验诊断

- [x] 新增体验诊断 tab 与面板，展示 Web Vitals、连接、Agent 指标和最近异常。
- [x] 接入开关、清除、手动导出、后端刷新与失败重试。
- [x] 无样本显示「—」，后端失败不掩盖本地诊断，复用亮暗主题 token。
- [x] 补面板 Vitest。

## T5 浏览器验收与全量回归

- [x] 新增 Playwright 覆盖异常展示、关闭停止采集、清除、导出与 Agent 指标。
- [x] 后端 `mvn -o test` 全量通过。
- [x] 前端 `npx vitest run` 与 `npm run build` 全量通过。
- [x] Playwright 全量及关键用例重复运行通过。

## T6 文档与边界同步

- [x] 更新 `docs/UPDATE_PLAN.md` 的 P5-11-2 状态和测试数。
- [x] 更新 `docs/phases/phase5/plan.md` 的完成情况与体验指标口径。
- [x] 更新 `docs/architecture/README.md` 的本地诊断和指标聚合架构。
- [x] 更新 `docs/COMPLETED_WORK.md`，记录隐私边界、验证证据和未覆盖项。
- [x] 勾选本目录 tasks/checklist，记录实际结果与偏差；不创建 Git commit。

## 实际结果

- T1～T6 均已完成；后端 298 项（0 失败 / 0 错误 / 12 跳过）、前端 Vitest 57/57、生产构建、Playwright 14/14 与完整套件三轮重复 42/42 通过。
- 偏差与未覆盖项已写入 `spec.md` 第 9 节：Web Vitals 关闭采用回调开关拦截；E2E 为 API 仿真，不断言固定 Web Vitals 数值；长会话性能留待 P5-11-3。
- 按用户要求保留工作区全部既有改动，未创建 Git commit。
