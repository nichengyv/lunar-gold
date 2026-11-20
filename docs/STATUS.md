# Lunar Gold（金月）· 项目状态速查

> 进入项目先读这一页。`design.md` 看架构，`operations.md` 看启动/运维。交接文档（给新 Agent）→ `HANDOFF.md`。
> 当前基线：Phase 5、Phase 6 已收口，Phase 7 进行中；P7-01、P7-02 统计切片、P7-05 浏览器层 E2E 与 P7-06 已完成。P7-02 两轮真实基线均已执行且结论无效：首轮暴露 prompt 占位符与摘要路径绕过工具分发，第二轮（L2 1/3、L3 0/3）暴露并行写调用时诊断消息插入 tool 响应之间导致 provider 400。三处缺陷均已修复并有回归测试，全量 398 tests 通过；第三轮重跑待授权，记忆摘要路径仍未被真实模型验证。历史批次与双端改造完成证据见 `COMPLETED_WORK.md`。

---

## 一句话定位

面向个人开发者的智能体工作台：单 Agent + ReAct 多轮工具循环 + 文件级审查 + 人在环路可控性。对标 Cursor / Claude Code，但强调"看得见计划、拦得住危险、撤得回一轮"。

---

## 架构速览

### 后端（Spring Boot 4.1.2 + Java 21 + Spring AI 2.0.2 BOM）

| 模块 | 关键类 |
|---|---|
| 对话入口 | `AgentChatController` → SSE `/chat/stream`、审批 `/approvals/{id}`、停止 `/chat/stop`、metrics `/agent/metrics` |
| Agent 核心 | `ReActAgentService` — ReAct 循环、`AgentPolicy`（FAST/STANDARD/DEEP × MANUAL/AUTO/FULL）、`ScopeConstraintService`（D1 范围约束）|
| 模型层 | `SpringAiAgentClient` — 流式 ChatClient；D2 fallback（零 chunk 才切互补模型列表）|
| 工具体系 | `ToolRegistry`（部署能力门控 / 注册 / 权限过滤 / Tool Scoring 三维度 / Timer埋点含 publishPercentiles）；内置 `read_file / write_file / edit_file / delete_file / list_dir / search_files / run_command` |
| MCP | 默认关闭；`McpToolBridge`（stdio 启动，9 个内置等价工具注册为 fallback）、`McpHealthMonitor`（熔断冷却）；写入/删除类 MCP 工具必须审批 |
| RAG | `VectorStoreService`（pgvector HNSW）、`EmbeddingService`、`DocumentProcessingService` |
| 记忆 | `SessionMemoryAdvisor`（滚动摘要注入 system）、`SessionMemoryExtractionService`（每 8 轮自动提炼会话事实存 AgentRule）|
| 审查流 | `FileReviewController`、`PendingFileChange`（turnId 关联 / 按轮回滚）|
| 审批 | `ToolApprovalRegistry`（120s 超时 / 会话级「总是允许」/ 断连自动拒绝）|
| 命令沙箱 | `CommandService` — 四道防线：白名单 + eval拦截 + 相对脚本禁止 + git危险子命令 + 敏感路径拦截；shell包装器(cmd/powershell/bash)单独拦截并给友好提示；默认 timeout=120s, max=600s |
| 范围约束 | `ScopeConstraintService` — `#scope src/components` 或自然语言 "only change X"，拦截文件类工具 |

### 前端（Vue 3 + Vite + Pinia + Element Plus + CodeMirror 6）

| 路由 | 组件 | 说明 |
|---|---|---|
| `/login` | `LoginView` | RSA 加密密码、JWT；桌面端由路由守卫自动换取本地令牌，不走登录页 |
| `/chat` | `ChatView` | SSE 流式、计划条、审批卡、工作台（变更/资料/文件/追踪/计划/**监控**）、@引用、斜杠命令、草稿持久化 |
| `/knowledge` | `KnowledgeView` | 资料上传 / 重试 / 删除 |
| `/settings` | `SettingsView` | 7 tab：对话默认 / 模型服务 / Agent 指令 / MCP 工具 / 外观 / 账号安全 / 应用信息；按部署能力与模式动态隐藏 |
| `/download` | `DownloadView` | 公开页：桌面端能力介绍与最新版本，`DOWNLOAD` 能力缺失时重定向 |

Pinia stores 5 个：`auth`、`chat`（最大，含 sessions/messages/pendingChanges/pendingApprovals/drafts/openFiles）、`project`、`meta`（部署能力，驱动入口显隐）、`theme`。

### SSE 事件（13 种）

`session_id` / `turn_id` / `thinking` / `tool_call` / `tool_result` / `content` / `plan` / `approval_required` / `approval_resolved` / `sources` / `file_change` / `done` / `error`

### 数据库（PostgreSQL，dev `ddl-auto=update`）

PostgreSQL + `vector_record`（pgvector）。Flyway 当前到 **V21**：V16～V18 增加 Run/Event、审批与 token 统计，V19/V21 增加审批决策来源与客户端标识，V20 增加 API 客户端凭证。V13 先于 V14 对 `pending_file_change` 执行 ALTER 的全新库顺序缺陷，已由 PR-06 的 Flyway `beforeMigrate` 幂等预检解决（不改已执行迁移）。

---

## 构建与验证命令

```powershell
# 后端（本机没全局 mvn，用 Trae 自带的）
$env:JAVA_HOME='C:\Users\16680\.jdks\ms-21.0.11'
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q compile    # 首次联网装依赖后 -o 离线可用
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q test       # 跑单测

# 前端（在 frontend/ 下）
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit     # 类型检查
# vite build 前需 TEMP/TMP 指向工作区内临时目录（esbuild 清理系统 Temp 会 Access denied）
```

**PowerShell 5 注意**：不能用 `&&`；`.ps1` 执行策略报错可忽略；`-D` 参数必须加引号。

---

## 关键链路（改代码前先读这 3 个类）

1. `AgentChatController.streamChat` — SSE 入口，合并三层配置（会话已存值 → 请求显式值 → 项目默认）
2. `ReActAgentService.execute` — ReAct 主循环，scope解析 / 策略注入 / 工具打分排序 / 审批拦截 / 计划推送
3. `ToolRegistry.dispatch` — 工具执行统一入口，含权限过滤、主工具→fallback 切链、熔断联动、Timer 埋点

---

## 批次完成度（一句话摘要）

- **Phase 1**：骨架 9 批 — 产品定位、工作区隔离、生产化、文件系统、终端命令、多模态、滚动摘要
- **Phase 2**：交互 18 批 — 三栏骨架、三下拉、斜杠命令、@引用、草稿持久化、会话导航、时间线
- **Phase 3**：编辑器 15 批 — 文件树、CodeMirror 6、工作台多 tab、diff 行装饰
- **Phase 4**：Spring AI 迁移 7 批 — Boot 3.5.4 + Spring AI 成唯一模型层、Advisor 注入
- **Phase 5**：MCP + 可观测 5 批 — stdio 接入 filesystem、健康监控 + 熔断、追踪面板聚合
- **Phase 6**：追踪深化 2 批 — Overview 7 格大卡、耗时三段拆分
- **Phase 7 A**：可控性 11 批 — 文件审查流、编辑器合并视图、Tool Scoring、计划面板、工具前置审批、轮次级回滚
- **Batch B**（批 69~70）：安全加固 + 会话自动记忆
- **Batch C**：生产化 Docker sandbox 骨架 + 测试补完
- **Batch D**（批 71~74）：D1 范围约束 / D2 模型 fallback / D3 可观测面板 / D4 diff 打磨
- **Batch E**（批 75）：权限三档重定义（手动审批 / 自动审批 / 完全权限）+ 审批卡可读化（不再展示入参 JSON）
- **Batch F**（批 76）：自动审批只拦破坏性命令（`CommandRisk` + `DESTRUCTIVE` 风险）+ 内置 `delete_file`（沙箱/审查流/撤回）+ 计划卡串台与流式省略号修复
- **双端改造**：PR-01 安全红线、PR-03 运行模式与 `/api/meta`、PR-04 工具注册和控制器能力门控、PR-05 前端能力渲染与下载页、PR-06 desktop 运行时（内嵌 PG + pgvector、无 Redis、`DbLifecycleManager`）、PR-07 桌面本地令牌 + 单用户初始化、P2-08 桌面 pgvector 运行时构建脚本与数据库集成测试（Windows x64）、P2-06 一键启动脚本（`scripts/dev-desktop.ps1`）、P2-07 命令超时与进程树终止测试、PR-12 网页端账号安全闭环（邮箱验证码 + 找回密码）已完成

---

## 路线图

### Level 1 ✅ 完成
单 Agent 对话：SSE / 推理档位 / 权限 / 工具链 / 会话 / 审查流 / 追踪 / 审批 / 范围约束 / fallback / 监控面板

### Level 2 ❌ 跳过
IDE Copilot 交互（Web 定位，不做行内补全 / Terminal 解读）

### Level 3 ❌ 暂不做
多智能体编排（2026 Q2 以后）

### Level 4 🟡 当前方向
| 模块 | 状态 | 说明 |
|---|---|---|
| 设置中心产品化 | ✅ | 默认值持久化、7 tab；MCP 与账号安全按部署模式动态显示 |
| 长期记忆 | 🟡 | 会话级自动提炼已有（每 8 轮）；"主动记住"指令可加 |
| 环境隔离 | ❌ | run_command 直接拿宿主进程权限（但有四道安全防线）|
| 测试覆盖 | ⚠️ | 2026-11-19 验收：后端 358 项 / 0 失败 / 0 错误 / 16 跳过，前端 Vitest 92/92，Playwright 30 通过 + 1 性能跳过；P7-05 四条桌面模式浏览器 E2E 通过。浏览器 E2E 使用 API 仿真，真实 Electron + Java SSE 与真实 PG 定向复跑单独跟踪，不外推为真链路通过 |
| 生产化缺口 | 🟡 | V13/V14 全新库迁移顺序缺陷已由 PR-06 预检关闭，desktop 内嵌 DB、本地令牌认证、单用户初始化与 pgvector 运行时构建（Windows x64）已可用；Electron 壳与侧车生命周期已有（P3-01/P3-02），jlink 裁剪 JRE 已有（P3-03，约 49 MiB）；macOS/Linux 运行时与安装包暂缓，Windows 签名、干净机器与真实桌面窗口链路冒烟未收口 |

### Level 5 🟡 Windows 桌面发布收尾
Electron 壳、侧车、Windows 安装/卸载与更新主体已交付；签名、干净机器完整任务和真实窗口链路验收见 `UPDATE_PLAN.md` Phase 3 / Phase 7。

---

## 已知边界

| 边界 | 影响 | 处理 |
|---|---|---|
| MCP 写入/删除类工具强制审批 | `AUTO` / `FULL` 也不会静默放行 | 用户可选择本会话「总是允许」 |
| 审批状态存进程内存 | 重启时等待线程已消失 | 启动即收口：未完成 Run（含 `WAITING_APPROVAL`）统一标 `FAILED`（`PROCESS_RESTARTED`），残留 `PENDING` 审批标 `REJECTED`（未过期 `SYSTEM` / 过期 `TIMEOUT`），不再接受批准 |
| scope 拦截只覆盖文件类工具 | run_command 有自己的沙箱 | 设计如此，不在此处拦截 |
| 撤回本轮只回滚 PENDING | 已 accept 或编辑器保存过的不回滚 | 文档说明 |
| MCP 熔断冷却默认 300s | 冷却期内不调用该 server，也不回退到内置工具 | 可用 `MCP_COOLDOWN_MS` 调整（`failure-threshold` / `probe-interval-ms` 同样可配） |
| aggregateStats 全量 parse trace JSON | 实测 1000 条助手消息 / 15.6MB trace：p95 27～31ms（不含 JDBC） | 远低于 500ms 阈值，不做缓存；会话继续变长时用 `eval/reports` 下的基准复测 |
| deepseek 硬编码改了但旧 profile 可能残留 | 模型通用性 | 前端 SettingsView 已切通用配置 |

---

## 踩坑速查

| 问题 | 场景 | 处理 |
|---|---|---|
| BOM 混进 CSS 选择器 | `:root` 含 BOM 整段被丢弃 | TrimStart U+FEFF，UTF-8 无 BOM 重写 |
| Windows vite build Access denied | esbuild 清理系统 Temp | `TEMP`/`TMP` 指向工作区内临时目录 |
| PowerShell `-D` 参数被切 | Maven `-DskipTests` | 加引号：`"-DskipTests"` |
| 前端 CORS 不支持 PATCH | 登录过期表现为网络错误 | `allowedMethods` 补 PATCH |
| Maven wrapper 首次构建慢 | 需联网拉依赖 | 之后 `-o` 离线可用 |
| `SessionMemoryAdvisor` 没注册 Bean | Advisor 不生效 | 必须 `@Bean` 注册到 Spring AI advisor 链 |
| 审批阻塞等用户交互 | 固定 SSE 总超时会中断多轮审批 | SSE 不设固定总超时；审批独立 120s 超时并检查断连 |
| `Array.find()` 返回第一个已 resolved 审批 | 批准后下一张审批卡不渲染 | **已修复**：ChatView computed 加 `!a.resolved` |
| Micrometer Timer percentile 永远 0 | 没配 publishPercentiles | **已修复**：Timer builder 加 `.publishPercentiles(0.5, 0.95, 0.99)` + 用正确的 `timer.percentile(double, TimeUnit)` API |
| Agent 试 `cmd /c mvn` 被白名单拦 | 错误提示弱，Agent 多绕 2 轮 | **已修复**：单独拦截 shell 包装器 + 友好 ❌→✅ 对照提示 + RunCommandTool 描述重写 |
| scope 提取混进中文描述 | `[A-Za-z0-9_./\\\-\\\\]+` 正则 | 已修复为只提取有效路径字符 |
