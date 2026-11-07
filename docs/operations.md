# 部署运维手册（Agent Gateway v3）

> 当前基线：Batch F（批 76），并已完成双端改造 PR-01、PR-03、PR-04。系统设计看 `design.md`，状态速查看 `STATUS.md`。

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

---

## 2. 环境变量完整清单

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DEEPSEEK_API_KEY` | — | **必须** |
| `DB_URL` | `jdbc:postgresql://localhost:5433/agentkey_vector` | JDBC URL |
| `DB_USERNAME` | `postgres` | 数据库用户 |
| `DB_PASSWORD` | 空 | dev 也必须显式配置，不保存默认密码 |
| `JPA_DDL_AUTO` | `validate` | dev profile 覆盖为 `update`，prod 固定 `validate` |
| `JWT_SECRET` | 无 | **必须**，UTF-8 至少 32 字节 |
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

---

## 3. 端口与健康检查

| 端口 | 用途 |
|---|---|
| `8080` | 后端 HTTP + SSE + Actuator |
| `5173` | 前端 Vite dev |

| 端点 | 认证 | 说明 |
|---|---|---|
| `GET /actuator/health` | 匿名 | UP = 容器 + DB 都通 |
| `GET /actuator/prometheus` | 匿名 | Prometheus metrics |
| `GET /api/meta` | 匿名 | 部署模式、能力集合与下载声明 |
| `GET /api/agent/metrics` | 需登录 | 当前用户工具调用统计，可按 projectId 过滤 |
| `GET /api/mcp/status` | 需登录 + `MCP_READ` | MCP server 熔断状态；cloud 返回 404 |

---

## 4. 数据库迁移

dev 环境关闭 Flyway，以 `ddl-auto=update` 自动建表；prod 启动时自动执行 Flyway，并以 `ddl-auto=validate` 校验实体。

当前迁移到 **Flyway V14**：
- V13 为 `pending_file_change` 增加 `turn_id`，支持按轮次撤回。
- V14 补建 `pending_file_change`；已有 dev 库可通过 `CREATE TABLE IF NOT EXISTS` 幂等处理。
- **已知边界**：prod 全新库会先执行 V13 的 ALTER，再执行 V14 的 CREATE，仍可能因表不存在而失败；不要宣称 V14 已解决全新安装问题，PR-06 将处理该顺序缺陷。

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
