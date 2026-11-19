# P5-11-2 体验可观测性 — 验收清单

## 需求覆盖

- [x] Vue 异常、window.onerror、unhandledrejection 能在设置页本地诊断中看到。
- [x] 异常记录只含白名单字段，路径/token/密钥脱敏，不保存原始 stack、对话或工具参数。
- [x] LCP/INP/CLS 使用标准 Web Vitals 算法，按聊天会话标记并显示当前值与样本数。
- [x] 首字时延 p95 使用 Run.startTime 到第一条 MODEL_CHUNK.createTime。
- [x] 流中断率、恢复成功率与平均恢复耗时来自真实 onRunId/recoverRun 状态点。
- [x] 工具失败率、审批平均等待、每轮 token 公式、样本数和统计窗口正确。
- [x] 所有无样本指标显示「—」，不伪造成 0 或成功。
- [x] 关闭开关后异常、Web Vitals、Run、恢复记录均停止新增；重新开启可恢复。
- [x] 已有记录需用户主动清除；账号切换清理本地 run/session 标识。
- [x] 手动导出仅产生白名单 JSON；不存在自动上传体验数据的请求。

## 兼容与隐私

- [x] 后端仅聚合当前认证用户数据，不能跨用户读取 Run、审批或工具 Timer。
- [x] 不新增数据库迁移、远程遥测 SDK 或客户端指标接收 API。
- [x] localStorage 损坏、写满、Web Vitals 不受支持时应用仍正常启动和聊天。
- [x] 后端指标失败不掩盖本地异常与 Web Vitals，支持独立重试。
- [x] 恢复被切会话/新恢复取代时不误记失败；Run FAILED/CANCELLED 不与传输恢复混算。
- [x] 聊天、审批、停止、断线恢复、设置页和 ChangeSet 不回归。
- [x] 亮暗主题下指标、异常摘要、开关和操作按钮可读。

## 质量门禁

- [x] 后端聚合、空样本、窗口与跨用户隔离聚焦测试通过。
- [x] 前端脱敏、开关、容量、Run/恢复公式、面板空态与失败分支单测通过。
- [x] Playwright 覆盖异常、开关、清除、导出与 Agent 指标，完整套件重复运行通过。
- [x] 后端 `mvn -o test` 全量通过。
- [x] 前端 `npx vitest run` 全量通过。
- [x] 前端 `npm run build` 通过。
- [x] 依赖锁文件同步，除 `web-vitals` 外无新增依赖。

## 文档同步

- [x] `docs/UPDATE_PLAN.md` 更新 P5-11-2 状态与测试数。
- [x] `docs/phases/phase5/plan.md` 更新完成情况与指标口径。
- [x] `docs/COMPLETED_WORK.md` 追加实现、验证证据和隐私边界。
- [x] `docs/architecture/README.md` 更新本地诊断与体验指标架构。
- [x] 本目录 tasks/checklist 按实际完成情况勾选，并记录偏差与未覆盖项。

## 验收记录（2026-11-14）

- 后端聚焦 4/4、全量 298 项（0 失败 / 0 错误 / 12 跳过）；前端诊断工具 11 项、面板 6 项包含在全量 Vitest 57/57 中。
- 生产构建通过；系统 Edge 下 Playwright 全量 14/14、完整套件三轮重复 42/42；诊断用例实际下载并读取导出 JSON。
- 亮暗可读性通过复用现有主题 token 和组件测试保证，未新增整页视觉截图；真实后端/真实模型链路和固定 Web Vitals 数值不属于本轮仿真 E2E 结论。
