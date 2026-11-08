# AgentKey Pro · 交接文档（2026-11-05 更新）

> 给下一个接手的 Agent。先读这一份，再按需查 `docs/STATUS.md`（速查）、`docs/design.md`（架构）、`docs/operations.md`（启动运维）。

---

## 0. 一句话现状

单 Agent + ReAct 多轮工具循环的个人开发工作台，核心能力已闭环：SSE 流式、三档权限、工具审批、计划面板、文件审查流与横幅撤回。**本轮补齐了「文件删除」这一缺失能力**，并修正了自动审批语义、计划卡串台、思考中无反馈三个体验问题。

验证状态（本轮结束时）：

```powershell
mvn -q -DskipTests compile          # 退出码 0
cd frontend; npx vue-tsc --noEmit   # 退出码 0
```

> 注意：本轮只跑了编译与类型检查，**没有跑 `mvn test`**。上一轮全量测试（约 82 个）是绿的，但新增 `DeleteFileTool` 与审批风险分支尚无单测覆盖。

---

## 1. 本轮（Batch E 及其修复）完成内容

### 1.1 Batch E：权限三档 + 审批卡可读化

| 改动 | 结果 |
|---|---|
| 权限档位由「只读/写入/执行」改为 **手动审批 / 自动审批 / 完全权限** | `AgentPolicy.Permission` = `MANUAL / AUTO / FULL`（保留 `READ_ONLY` 兼容旧数据） |
| 审批卡不再展示原始入参 JSON | `ApprovalPreview` DTO：`kind/path/command/diff/additions/deletions/newFile/note`，前端默认只显示摘要 |
| 运行中切换权限实时生效 | `SessionPermissionTracker`（ConcurrentHashMap），ReAct 每次工具调用前读一次，O(1) |

### 1.2 自动审批语义修正

原问题：AUTO 档写成「只要风险是 EXECUTE 就审批」，等价于手动审批；`rm` 这类普通命令也被反复要求确认。

现在：

- `AUTO` 只拦**破坏性命令**：递归删除、`format/mkfs/diskpart`、强推、硬重置、`git clean`、`DROP/TRUNCATE`
- 判断逻辑集中在 `util/CommandRisk.isDestructive(command)`
- 工具审批签名改为 `requiresApproval(risk, command)`；`ReActAgentService.commandOf(arguments)` 从工具入参取命令原文
- 新增风险等级 **`DESTRUCTIVE`**：递归删除目录在 AUTO 档也要确认，FULL 档不拦
- 前端三处权限文案同步更新（ChatView 下拉、SettingsView、AppLayout）

### 1.3 补齐文件删除能力（本轮重点）

背景：`WorkspaceFileService` 早有 `deleteEntry`，但只给了人工文件面板；Agent 侧没有删除工具，模型只能尝试 `run_command` 的 `rm`。而当前终端不经过 shell，Windows 上 `rm/del/rd` 都不是可执行程序，必然失败。

新增 `tool/DeleteFileTool`（`delete_file`）：

| 能力 | 说明 |
|---|---|
| 沙箱 | 复用 `resolveInWorkspace` + 真实路径校验，软链接只删链接本身 |
| 保护 | 拒绝删除工作区根目录、拒绝路径含 `.git` |
| 目录 | 非空目录必须显式 `recursive=true`，否则返回可执行的错误提示 |
| 审查流 | 删除前把文本文件旧内容写入 `PendingFileChange`（action=`delete`），可在「待审查改动」撤回 |
| 上限 | 单文件快照 512KB；目录最多逐文件记录 200 个，超出部分明确告知无法撤回 |
| 前端 | 消息文件清单/工作台变更列表显示「已删除」，点击不再尝试打开已删文件；审查卡展示「查看将删除的内容」 |

配套修复：

- `FileReviewController.revertOnDisk` 恢复前先 `Files.createDirectories(parent)`，否则撤销整目录删除会失败
- 系统提示词新增：「删除文件/目录用 `delete_file`，不要用 rm/del；非空目录传 `recursive=true`」
- `RunCommandTool` 描述增加同一条规则
- `ScopeConstraintService.FILE_TOOLS` 与路径提取加入 `delete_file`
- `ToolRegistry.WRITE_TOOLS` 加入 `delete_file`（READ_ONLY 档不暴露）

### 1.4 另外两个体验修复

| 问题 | 根因 | 修法 |
|---|---|---|
| 新任务仍弹出上一个任务的计划卡 | 计划取值会向前扫描任意一条带 plan 的助手消息 | 运行中只认 `chat.livePlan`；结束后只认最后一条 assistant 消息（`ChatView.vue` / `WorkbenchDock.vue`） |
| 模型思考时界面看起来卡死 | 动态省略号只在正文为空时显示 | 已有正文且仍在流式、且没有运行中工具时，在回答末尾追加动态省略号（`ChatMessage.vue`） |

---

## 2. 架构入口（改代码前必读）

### 后端

| 类 | 作用 |
|---|---|
| `controller/AgentChatController` | SSE 入口 `streamChat`；审批 REST；`GET /api/agent/metrics` |
| `service/ReActAgentService` | ReAct 主循环。`execute()` 合并策略、scope 解析；循环内做工具审批拦截、计划推送、`finalizePlan` 收口 |
| `service/AgentPolicy` | 推理档位（FAST 10 / STANDARD 30 / DEEP 60 轮）+ 权限三档 + `requiresApproval(risk, command)` |
| `service/ToolApprovalRegistry` | `riskOf(toolName, arguments)` / 120s 超时自动拒绝 / 会话级总是允许 |
| `service/SessionPermissionTracker` | 运行中改权限立即生效的内存快照 |
| `tool/ToolRegistry` | 工具注册、权限过滤（WRITE_TOOLS / EXECUTE_TOOLS）、fallback 链、Timer 埋点 |
| `tool/BaseTool` 实现类 | `read_file / write_file / edit_file / delete_file / list_dir / search_files / run_command` |
| `util/CommandRisk` | 破坏性命令判定（AUTO 档唯一拦截依据） |
| `service/CommandService` | 命令沙箱：白名单 + eval 拦截 + 相对脚本禁止 + git 危险子命令 + 敏感路径 + shell 包装器拦截 |
| `service/WorkspaceFileService` | 沙箱路径解析、读写/编辑/重命名/删除（`renameEntry` 目前仅人工面板使用） |
| `controller/FileReviewController` | 审查流：单条撤回、全量撤回、按轮次撤回 |

### 前端

| 文件 | 作用 |
|---|---|
| `views/ChatView.vue` | SSE 消息区、计划条、审批卡、输入栏；`currentPlan` 只认本次任务 |
| `components/ChatMessage.vue` | 消息渲染、思考/步骤时间线、文件变更清单、流式省略号 |
| `components/ApprovalCard.vue` | 紧凑审批卡（write / edit / delete / command 四种摘要） |
| `components/PendingChangesCard.vue` | 审查流卡：inline diff、单文件撤回/保留 |
| `components/WorkbenchDock.vue` | 文件 / 资料 / 变更 / 追踪 / 计划 / 监控 多 tab |
| `stores/chat.ts` | 最大 store：messages / pendingApprovals / pendingChanges / livePlan / openFiles |

---

## 3. 已踩坑（不要再犯）

1. **Micrometer Timer**：`pausePercentile` 不存在；必须 `timer.percentile(0.95, TimeUnit.MILLISECONDS)`，且 builder 要配 `.publishPercentiles(...)`，否则永远是 0。
2. **审批卡列表**：`Array.find()` 必须带 `!a.resolved`，否则永远命中第一张已处理的卡。
3. **SSE 收尾顺序**：`finalizePlan` 必须在 `onComplete()` 之前推 plan 事件，否则前端收到 done 后不再处理计划。
4. **Windows 可执行解析**：后缀顺序必须是 `.exe / .cmd / .bat / 无后缀`；`rm/del/rd/ls/cat` 是 shell 内建，`ProcessBuilder` 起不来。
5. **PowerShell 5**：不能用 `&&`；Maven `-D` 参数要加引号；`vue-tsc` 走 `node node_modules/vue-tsc/bin/vue-tsc.js`。
6. **`SessionMemoryAdvisor`** 必须 `@Bean` 注册才会进 Advisor 链。
7. **审批超时**：审批 120s 必须早于 SSE 超时。

---

## 4. 已知边界 / 缺口

| 缺口 | 严重度 | 说明 |
|---|---|---|
| 移动/重命名、复制没有内置工具 | 中 | 人工文件面板有 `renameEntry`；Agent 侧只能用 MCP（若配置）或 write+delete 拼，丢 git 历史、二进制搬不动 |
| MCP filesystem 写入不入审批 | 中 | 审批只覆盖内置工具 |
| MCP 熔断 5 分钟内不降级 | 中 | 冷却期体验差 |
| 审批状态存进程内存 | 低 | 重启丢正在等待的卡；120s 超时兜底 |
| 桌面端尚无安装包 | 中 | 前端与 pgvector 运行时都可随 jar 分发（P3-00/P3-00b）；Electron 壳与侧车生命周期已有 `desktop/`（P3-01/P3-02，含崩溃重启与游离 postmaster 终止），窗口与单实例双开需真机确认；jlink 裁剪 JRE 已有（P3-03，19 个模块约 49 MiB，打包态由 `resources/jre` 提供）、`DESKTOP_EXPOSE_TOKEN_ENDPOINT` 仍为 true；安装包/签名/更新（P3-04~P3-05）未做 |
| 桌面端 pgvector 运行时仅 Windows x64 | 中 | 由 `scripts/build-pg-runtime.ps1` 产出（PG 16.11.0 + pgvector 0.8.7，SHA-256 锁定的社区二进制），可用 `-Pwith-pg-runtime` 内置进 jar，也可用 `DESKTOP_PG_RUNTIME` 覆盖；macOS/Linux 与安装包内布局未做（P2-08/P3-00b 边界） |
| 桌面端本地令牌存文件而非 OS 凭据库 | 低 | `<data-dir>/local-token`，POSIX 收紧为 600；Windows 依赖用户目录 ACL（PR-07 记录的差异） |
| 桌面端登录/改密接口未显式关闭 | 低 | 账号 UI 已隐藏（无账号 tab、用户名与登出入口，`/profile` 重定向到对话）；本地用户密码为一次性随机值，正常路径只走本地令牌 |
| ReAct / SSE / 审批阻塞 无集成测试 | 低 | 纯编译验证无法覆盖行为回归 |
| `edit_file` 只替换第一处匹配 | 低 | 多处相同片段时可能改错位置；提示词要求用最小唯一锚点缓解 |
| 删除大目录时审查流只记录前 200 个文件 | 低 | 超出部分不可撤回，工具结果已明确告知 |

---

## 5. 后续更新建议

### P0 — 正确性 / 生产化

1. **给本轮改动补测试**
   - `CommandRiskTest`：破坏性/非破坏性命令矩阵
   - `AgentPolicyTest`：MANUAL / AUTO / FULL × WRITE / EXECUTE / DESTRUCTIVE
   - `DeleteFileToolTest`：根目录拒绝、`.git` 拒绝、非空目录无 recursive、文件删除与审查记录
2. **Flyway V14（已完成）**：`pending_file_change` + `agent_mcp_server_status` 建表；PR-06 另加 `beforeMigrate` 幂等预检，修复 V13 在全新库先于 V14 执行 ALTER 的顺序缺陷
3. **MCP 写入工具接入审批**：`McpToolAdapter` 桥接 `ToolApprovalRegistry`
4. **MCP 熔断 → 内置 fallback**：熔断期间自动切到等价内置工具

### P1 — 能力补齐

5. **`move_file`**（推荐）：`from → to`，复用 `WorkspaceFileService.renameEntry`；目标已存在时拒绝覆盖；审查流记「源删除 + 目标创建」；风险 WRITE（AUTO 放行）
6. **`copy_file`**（可选，优先级低）：文本可用 read+write 替代，仅在需要复制二进制/大文件时才有价值
7. **`edit_file` 唯一性校验**：命中多处时返回数量与建议（扩大上下文锚点），避免静默改错位置
8. **长期记忆主动触发**：`POST /rules/session/remember`
9. **设置中心默认值持久化**：模型/权限/推理档位写入项目 defaults

### P2 — 体验与可观测

10. 监控 Timer 持久化（当前重启即丢）
11. 追踪面板增加「审批事件」时间线
12. 前端文件变更列表支持「已删除文件查看删除前内容」（可复用审查流 diff）

---

## 6. 快速验证清单（接手先跑）

```powershell
# 1. 后端编译
$env:JAVA_HOME='C:\Users\16680\.jdks\ms-21.0.11'
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q -DskipTests compile

# 2. 后端测试（上一轮全绿，本轮新增代码建议先补测试再跑）
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q test

# 3. 前端类型
cd frontend
npx vue-tsc --noEmit

# 4. 手动冒烟（重启后端）
#    - 自动审批档：让它「删除 tmp/test.txt」→ 不弹审批，直接删除，消息底部出现「已删除」
#    - 自动审批档：让它「递归删除某个非空目录」→ 弹确认，显示「递归删除」
#    - 手动审批档：任何写/删/命令都弹卡，批准后下一张卡仍能正常出现
#    - 新开任务：不应再出现上一个任务的计划卡
#    - 长回答中途停顿：末尾应持续有动态省略号
#    - 审查流：撤回被删文件 → 文件恢复；撤回被删目录 → 父目录自动重建
```

---

## 7. 技术栈备忘

| 组件 | 版本 / 路径 |
|---|---|
| Java | 21（`C:\Users\16680\.jdks\ms-21.0.11`） |
| Spring Boot | 3.5.4，Spring AI 1.5 BOM |
| PostgreSQL | localhost:5433，db=`agentkey_vector`（账号与密码见本地 dev 配置，不入库） |
| 前端 | Vue 3 + Vite + Pinia + Element Plus + CodeMirror 6 |
| Maven | `C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd` |
| 端口 | 后端 8080（dev），前端 5173（`/api/*` 代理到 8080） |

真实密钥放在未跟踪的 `application-dev.yml` 或环境变量中；缺失或强度不足时，启动校验会拒绝启动。
