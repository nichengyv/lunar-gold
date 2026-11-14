# 文档索引与书写规则

## 主线文档（docs/ 根目录）

| 文档 | 用途 |
|---|---|
| [STATUS.md](STATUS.md) | 当前状态速查：能跑什么、卡在哪 |
| [UPDATE_PLAN.md](UPDATE_PLAN.md) | 总路线：各阶段目标、任务与出口 |
| [COMPLETED_WORK.md](COMPLETED_WORK.md) | 已完成工作归档与验收证据 |
| [design.md](design.md) | 架构设计与关键取舍 |
| [operations.md](operations.md) | 启动、构建、运维与发布 |

## 架构现状文档

| 文档 | 用途 |
|---|---|
| [architecture/README.md](architecture/README.md) | 总架构：全景、模块边界、任务生命周期、设计取舍与面试讲解 |
| [architecture/01-agent-runtime.md](architecture/01-agent-runtime.md) | Agent Runtime：ReAct 循环、完成语义、无进展检测、策略与 Run 状态 |
| [architecture/02-model-gateway.md](architecture/02-model-gateway.md) | 模型接入：客户端抽象、流式调用、超时重试熔断、BYOK 与 token |
| [architecture/03-tools-approval-sandbox.md](architecture/03-tools-approval-sandbox.md) | 工具、审批、文件/命令沙箱与 MCP |
| [architecture/04-context-rag.md](architecture/04-context-rag.md) | 文档入库、pgvector 检索、上下文组装与提示词版本化 |
| [architecture/05-api-sse.md](architecture/05-api-sse.md) | 控制器清单、SSE 事件协议、断线续传与能力门控 |
| [architecture/06-frontend.md](architecture/06-frontend.md) | 前端页面、Store、SSE 消费、三条恢复路径与组件 |
| [architecture/07-desktop-deployment.md](architecture/07-desktop-deployment.md) | Electron 壳、侧车、内嵌运行时、打包、更新回滚与诊断 |
| [architecture/08-data-persistence.md](architecture/08-data-persistence.md) | 数据选型、V1~V18 迁移、实体关系与恢复策略 |
| [architecture/09-security.md](architecture/09-security.md) | 威胁模型、认证授权、租户隔离、沙箱与密钥隐私 |
| [architecture/10-quality-evaluation.md](architecture/10-quality-evaluation.md) | 三层验证、Runtime 评测、指标口径、双门禁与 CI |

## 阶段细化文档

| 文档 | 用途 |
|---|---|
| [phases/phase3/p3-05-auto-update.md](phases/phase3/p3-05-auto-update.md) | P3-05 自动更新的 5 个问题与修复 |
| [phases/phase4/spec.md](phases/phase4/spec.md) | Phase 4 出境验收方案 |
| [phases/phase4/tasks.md](phases/phase4/tasks.md) | Phase 4 出境验收任务清单 |
| [phases/phase5/plan.md](phases/phase5/plan.md) | Phase 5 计划：P5-01～P5-12 设计与验收 |
| [phases/phase5/visual-ux-preview.html](phases/phase5/visual-ux-preview.html) | Phase 5 视觉与交互原型（可直接浏览器打开） |

## 排障

| 文档 | 用途 |
|---|---|
| [troubleshooting/windows-nsis.md](troubleshooting/windows-nsis.md) | Windows NSIS 打包与安装排障 |

## 书写规则

1. **状态标记**：标题末尾统一写 `（已完成）`、`（部分完成）`、`（未开始）`、`（暂不做）`；不写日期、不用 emoji。
2. **部分完成要写剩余**：在小节内或进度表里列出「剩余：…」，未完成原因一并写清；「已完成」只留结论与关键文件，不复述实现过程。
3. **标题层级**：`##` 章节或批次，`###` 任务项（P5-xx）或批次内小节，`####` 子项（P5-xx-y）。
4. **文档开头**：写明更新日期、状态、关联的主线文档，避免读者先猜归属。
5. **命名与位置**：主线文档用大写文件名放在 `docs/` 根目录；阶段文档放 `docs/phases/phaseN/`，用小写中划线命名；排障文档放 `docs/troubleshooting/`。
6. **链接**：一律相对路径；跨目录用 `../../`，不要写绝对路径。
7. **进度表**：统一列为「子项 | 状态 | 已完成内容 | 剩余」，状态用文字，不用 ✅/🟡/⬜。
8. **不重复实现**：标为「已完成」的条目再次被提及时，先读原结论与文件位置，不重做。
