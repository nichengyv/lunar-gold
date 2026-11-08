# 部署运维手册（Agent Gateway v3）

> 当前基线：Batch F（批 76），并已完成双端改造 PR-01、PR-03、PR-04、PR-06。系统设计看 `design.md`，状态速查看 `STATUS.md`。

---

## 1. 快速启动（开发环境）

### 1.1 前置依赖

| 依赖 | 版本要求 | 说明 |
|---|---|---|
| Java | 21 | `java -version` |
| Maven | 3.9+ | 用 Trae 内置 `C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd` |
| Node | 18+ | `node -v` |
| PostgreSQL | 15+ | localhost:5432 |
| npx | 可用（可选） | MCP filesystem 需要 |

### 1.2 后端（dev）

```powershell
$env:JAVA_HOME = 'C:\Users\16680\.jdks\ms-21.0.11'
$env:DB_URL = 'jdbc:postgresql://localhost:5433/agentkey_vector'   # 本机 5433
$env:DB_USERNAME = 'postgres'
$env:DB_PASSWORD = '<本地数据库密码>'
$env:JWT_SECRET = '<至少 32 字节的随机密钥>'
$env:AES_MASTER_KEY = '<Base64 编码的 16/24/32 字节随机密钥>'
$env:DEEPSEEK_API_KEY = 'sk-your-key'

cd d:\test-code\agent-gateway-v3
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' spring-boot:run
# 启动成功：Started AgentkeyApplication in X.XXs
```

### 1.3 前端（dev）

```powershell
cd d:\test-code\agent-gateway-v3\frontend
npm install     # 首次
npm run dev     # http://localhost:5173, 代理 /api → 8080
```

### 1.4 后端（desktop 桌面模式，PR-06 / PR-07）

桌面模式不需要预先安装 PostgreSQL 与 Redis：启动时由 `DbLifecycleManager` 在进程内拉起内嵌 PostgreSQL + pgvector，数据默认落在 `~/.agentkey/desktop/db`，首次启动自动建库并执行 Flyway V1~V15。

```powershell
$env:JAVA_HOME = 'C:\Users\16680\.jdks\ms-21.0.11'
# 桌面端用本地令牌替代 JWT，不再需要 JWT_SECRET；API Key 落盘加密仍需要 AES 主密钥
$env:AES_MASTER_KEY = '<Base64 编码的 16/24/32 字节随机密钥>'
# 含 pgvector 的 PostgreSQL 运行时压缩包；未配置时使用依赖内置二进制（不含 pgvector，V3 迁移会失败）
$env:DESKTOP_PG_RUNTIME = 'D:\path\to\pg-runtime.txz'
$env:DESKTOP_DATA_DIR = 'D:\agentkey-data'      # 可选，默认 ~/.agentkey/desktop
$env:DESKTOP_SERVER_PORT = '8080'               # 可选
$env:DESKTOP_DB_PORT = '0'                      # 0 = 每次启动自动挑空闲端口

& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' spring-boot:run '-Dspring-boot.run.profiles=desktop'
```

**本地身份（PR-07）**：首次启动自动创建本地单用户（默认 `local`，不开放注册），并在 `<data-dir>/local-token` 生成随机令牌；启动日志会打印令牌与文件路径（`DESKTOP_LOG_TOKEN=false` 可关闭）。之后任意请求带上 `Authorization: Bearer <token>`（或 `X-Local-Token`）即可，没有令牌访问受保护接口返回 401。

浏览器开发模式（Vite 5173）有两种配对方式：
1. 前端路由守卫会自动请求 `GET /api/desktop/token` 换取令牌（该接口只接受回环地址、且 `Origin` 必须在 `APP_CORS` 白名单内）；
2. 直接设置 `DESKTOP_DEV_TOKEN=<固定值>`，前端在 localStorage 写入该值即可，适合反复调试。

验证：`GET /api/meta` 应返回 `mode=DESKTOP`、`authMode=LOCAL_SINGLE_USER`，能力集中不含 `DOWNLOAD`；`GET /actuator/health` 为 `UP`；不带令牌访问 `/api/projects` 返回 401；`POST /api/auth/register` 返回 404（桌面端关闭注册）。日志中不应出现任何 Redis 连接记录。

边界：桌面端登录/改密接口仍在路由上，但本地用户密码是一次性随机值，实际不可用；一键启动脚本（P2-06）、工作区加固（P2-07）与 pgvector 运行时随包（P2-08）尚未实施，因此未配置 `DESKTOP_PG_RUNTIME` 时 V3 迁移会失败。

---

## 2. 环境变量完整清单

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DEEPSEEK_API_KEY` | — | **必须** |
| `DB_URL` | `jdbc:postgresql://localhost:5433/agentkey_vector` | JDBC URL |
| `DB_USERNAME` | `postgres` | 数据库用户 |
| `DB_PASSWORD` | 空 | dev 也必须显式配置，不保存默认密码 |
| `JPA_DDL_AUTO` | `validate` | dev profile 覆盖为 `update`，prod 固定 `validate` |
| `JWT_SECRET` | 无 | dev/prod **必须**，UTF-8 至少 32 字节；desktop 不需要（本地令牌替代） |
| `AES_MASTER_KEY` | 无 | **必须**，Base64 解码后为 16/24/32 字节 |
| `MCP_ENABLED` | `false` | 默认关闭；关闭时不创建 MCP client、不注册 MCP 工具 |
| `MCP_FS_TMPDIR` | `java.io.tmpdir` | stdio 初始安全根目录；项目工作区通过 MCP Roots 动态追加 |
| `MCP_FILESYSTEM_PACKAGE` | `@modelcontextprotocol/server-filesystem@2.2.0` | 固定包版本，避免拉取不可审计的最新代码 |
| `TAVILY_API_KEY` | — | web_search，可选 |
| `SERVER_PORT` | `8080` | |
| `WORKSPACE_COMMAND_TIMEOUT_SECONDS` | **120** | 默认命令超时（已从 60 升级）|
| `WORKSPACE_COMMAND_MAX_TIMEOUT_SECONDS` | **600** | Agent 可传的最大 timeout（已从 300 升级）|
| `WORKSPACE_COMMAND_ALLOWED` | npm,node,git,mvn,... | 白名单，不含 cmd/powershell/sh |
| `WORKSPACE_JAVA_HOME` | C:/Users/16680/.jdks/ms-21.0.11 | 找不到 java 时的兜底 |
| `WORKSPACE_MAVEN_HOME` | D:/IDEA/.../maven3 | 找不到 mvn 时的兜底 |
| `DESKTOP_PG_RUNTIME` | 空 | 仅 desktop：含 pgvector 的 PostgreSQL 运行时压缩包路径；留空则用依赖内置二进制（不含 pgvector） |
| `DESKTOP_DATA_DIR` | `~/.agentkey/desktop` | 仅 desktop：本地数据根目录，数据库在 `<data-dir>/db` |
| `DESKTOP_DB_PORT` | `0` | 仅 desktop：内嵌库端口，`0` 表示每次启动自动挑空闲端口 |
| `DESKTOP_SERVER_PORT` | `8080` | 仅 desktop：HTTP 端口 |
| `DESKTOP_LOCAL_USERNAME` | `local` | 仅 desktop：本地单用户用户名，首次启动自动建号 |
| `DESKTOP_DEV_TOKEN` | 空 | 仅 desktop：额外的固定令牌，供浏览器开发模式配对 |
| `DESKTOP_LOG_TOKEN` | `true` | 仅 desktop：启动日志是否打印本地令牌 |
| `DESKTOP_EXPOSE_TOKEN_ENDPOINT` | `true` | 仅 desktop：是否开放 `GET /api/desktop/token`（仅回环地址 + 来源白名单）；打包发布应置 `false` |

> `REDIS_*` 与 `DB_URL` 只对 `dev`/`prod` 生效；`desktop` 排除了 Redis 自动配置并自行创建数据源，这两组变量会被忽略。

---

## 3. 端口与健康检查

| 端口 | 用途 |
|---|---|
| `8080` | 后端 HTTP + SSE + Actuator |
| `5173` | 前端 Vite dev |
| 自动分配（desktop） | 内嵌 PostgreSQL，仅监听 `127.0.0.1`，端口见启动日志「本地数据库已启动」 |

| 端点 | 认证 | 说明 |
|---|---|---|
| `GET /actuator/health` | 匿名 | UP = 容器 + DB 都通 |
| `GET /actuator/prometheus` | 匿名 | Prometheus metrics |
| `GET /api/meta` | 匿名 | 部署模式、能力集合与下载声明 |
| `GET /api/agent/metrics` | 需登录 | 当前用户工具调用统计，可按 projectId 过滤 |
| `GET /api/mcp/status` | 需登录 + `MCP_READ` | MCP server 熔断状态；cloud 返回 404 |

---

## 4. 数据库迁移

dev 环境关闭 Flyway，以 `ddl-auto=update` 自动建表；prod 与 desktop 启动时自动执行 Flyway，并以 `ddl-auto=validate` 校验实体。

当前迁移到 **Flyway V15**：
- V13 为 `pending_file_change` 增加 `turn_id`，支持按轮次撤回。
- V14 补建 `pending_file_change`；已有 dev 库可通过 `CREATE TABLE IF NOT EXISTS` 幂等处理。
- V15 为 `user.email` 增加唯一索引。
- **全新库顺序缺陷已修复**：原始 V13 会先于 V14 对不存在的表执行 ALTER。PR-06 新增 `PendingFileChangePrecheckCallback`，在 `beforeMigrate` 一次幂等预检中按 V14 最终结构预建该表，随后 V13/V14 均为空操作；存量库不受影响，已执行迁移未被修改。
- **Boot 4 依赖要求**：必须使用 `spring-boot-starter-flyway`。只引 `flyway-core` 时 Flyway 自动配置不会加载，启动会跳过迁移并直接被 JPA `validate` 报 `missing table`。

其他关键表：
- `agent_mcp_server_status` — MCP 熔断状态
- `agent_rule` — Agent 指令库（含会话自动提炼结果）

---

## 5. MCP 开关与熔断恢复

MCP 默认关闭：`MCP_ENABLED=false` 时不创建 MCP client、不注册工具；启用 client 后，`McpGlobalSwitch` 运行时开关默认仍为关闭，需要在设置页显式启用。写入、编辑、创建目录、移动和删除类 MCP 工具必须审批，`AUTO` / `FULL` 也不能默认绕过。

熔断触发：某 server 连续 3 次失败 → 标记 DOWN，默认冷却 5 分钟（`MCP_COOLDOWN_MS`）。
冷却期间：该 server 的 MCP 工具调用直接拒绝（不降级到内置等价工具）。
恢复：等冷却到期后放行探测调用；健康监控默认每 30 秒探活（`MCP_PROBE_INTERVAL_MS`）。

---

## 6. 常见问题速查

| 现象 | 排查 |
|---|---|
| 后端启动 8080 被占用 | `Stop-Process -Id <PID> -Force` |
| 前端"网络连接不稳定" | CorsFilter allowedMethods 补 PATCH |
| CodeMirror 绿标记错位 | 编辑后 diff 过时（预期行为），重新生成 diff |
| vue-tsc `DecorationSet only refers to a type` | `Decoration.set(entries, true)` 第二个参数 instance |
| Agent 试 `cmd /c mvn` 被拦 | 预期行为，看后端友好错误提示让 Agent 直接用 `mvn` |
| Agent 审批卡批准后下一张不显示 | **已修复**，刷新前端即可（加了 `!a.resolved` 过滤）|
| 监控面板 P95 显示 0 | **已修复**，需要 Timer 配 `publishPercentiles` 且有足够样本 |
| `SessionMemoryAdvisor` 不生效 | 必须 `@Bean` 注册到 Spring AI advisor 链 |

---

## 7. 冒烟测试 Checklist（一页纸）

> 改完代码至少跑这些。dev 环境先重启后端（ddl-auto=update 会自动建新表）。

| # | 测试 | 命令 / 操作 | 预期 |
|---|---|---|---|
| 1 | 后端健康 | `curl http://localhost:8080/actuator/health` | `"status":"UP"` |
| 2 | 接口列表 | `curl http://localhost:8080/v3/api-docs` | 200 + JSON |
| 3 | 编译+测试 | `mvn compile test -q` | 退出码 0 |
| 4 | 前端类型 | `cd frontend && node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | 退出码 0 |
| 5 | 对话正常 | 发 "列出 src 下所有 .java 文件" | Agent 调 list_dir / search_files |
| 6 | 审批流 | 发 "写一个 test.txt 文件，内容 hello" | Agent 要 approve → 批准卡出现 → 点批准 → **下一张审批卡也能正常出现** |
| 7 | 文件审查 | 审批通过后看输入框上方 | "1 个文件改动待审查" → 点展开看 inline diff |
| 8 | 监控面板 | 右侧 dock 点「监控」tab | 有 overview 卡片 + 工具排行表（刚跑过工具调用）|
| 9 | 追踪面板 | 右侧 dock 点「追踪」tab | Overview 轮次 ≥ 1、工具调用 ≥ 1 |
| 10 | 命令沙箱 | 后端日志搜白名单拦截 | `cmd /c mvn` 被友好拦截；`mvn.cmd` 不被拦 |
