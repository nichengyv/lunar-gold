# AgentKey Pro · 项目状态速查

> 进入项目先读这一页。`design.md` 看架构，`operations.md` 看启动/运维。交接文档（给新 Agent）→ `HANDOFF.md`。
> 当前基线：Batch F（批 76），并已完成双端改造 PR-01、PR-03、PR-04、PR-05、PR-06、PR-07、P2-06、P2-07、P2-08、PR-12。

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
| `/settings` | `SettingsView` | 6 tab：对话默认 / 模型服务 / Agent 指令 / MCP 工具 / 外观 / 账号安全 |
| `/download` | `DownloadView` | 公开页：桌面端能力介绍与最新版本，`DOWNLOAD` 能力缺失时重定向 |

Pinia stores 5 个：`auth`、`chat`（最大，含 sessions/messages/pendingChanges/pendingApprovals/drafts/openFiles）、`project`、`meta`（部署能力，驱动入口显隐）、`theme`。

### SSE 事件（13 种）

`session_id` / `turn_id` / `thinking` / `tool_call` / `tool_result` / `content` / `plan` / `approval_required` / `approval_resolved` / `sources` / `file_change` / `done` / `error`

### 数据库（PostgreSQL，dev `ddl-auto=update`）

业务表 11 张 + `vector_record`（pgvector）。Flyway 到 V15（V15 为邮箱唯一索引）；V13 先于 V14 对 `pending_file_change` 执行 ALTER 的全新库顺序缺陷，已由 PR-06 的 Flyway `beforeMigrate` 幂等预检解决（预建表，V13/V14 变为空操作，不改已执行迁移）。

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
| 设置中心产品化 | 🟡 | 默认值持久化、6 tab 完善 |
| 长期记忆 | 🟡 | 会话级自动提炼已有（每 8 轮）；"主动记住"指令可加 |
| 环境隔离 | ❌ | run_command 直接拿宿主进程权限（但有四道安全防线）|
| 测试覆盖 | ⚠️ | 当前 156 项回归，0 失败、0 错误、5 项跳过（2 项既有环境相关 + 3 项未设置 `DESKTOP_PG_RUNTIME` 的桌面集成测试）；覆盖桌面数据库 V1~V15 与 pgvector 1024 维余弦检索、命令超时与进程树终止；desktop profile 已在 Windows x64 完成启动冒烟，SSE 端到端与前端自动化仍待补 |
| 生产化缺口 | ❌ | V13/V14 全新库迁移顺序缺陷已由 PR-06 预检关闭，desktop 内嵌 DB、本地令牌认证、单用户初始化与 pgvector 运行时构建（Windows x64）已可用；仍缺桌面壳、macOS/Linux 运行时、把运行时打进安装包与桌面端端到端冒烟 |

### Level 5 ⏳ 尚未实施
桌面外壳与分发见 `UPDATE_PLAN.md` Phase 3；当前仍以浏览器作为 desktop 开发环境。

---

## 已知边界

| 边界 | 影响 | 处理 |
|---|---|---|
| MCP 写入/删除类工具强制审批 | `AUTO` / `FULL` 也不会静默放行 | 用户可选择本会话「总是允许」 |
| 审批状态存进程内存 | 重启丢正在等的卡 | 120s 超时自动拒绝兜底 |
| scope 拦截只覆盖文件类工具 | run_command 有自己的沙箱 | 设计如此，不在此处拦截 |
| 撤回本轮只回滚 PENDING | 已 accept 或编辑器保存过的不回滚 | 文档说明 |
| MCP 熔断冷却 5 分钟硬编码 | 期间不降级到内置等价工具 | 体验差，需补 |
| aggregateStats 全量 parse trace JSON | 长会话慢 | 可考虑物化视图 |
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
