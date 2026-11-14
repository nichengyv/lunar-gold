# Phase 4 出境验收 Spec（方案 1）

> 目标：在真实 PostgreSQL / 真实 HTTP 栈上验证 Phase 4 的迁移、重启恢复、模型故障韧性、多实例可见性与跨账号越权，并如实记录未覆盖边界。
> 本 Spec 只覆盖验收测试与验证记录，不改生产代码（除非测试暴露必须修复的缺陷）。

## 1. 范围与出口

| 编号 | 验收项 | 出口标准 |
|---|---|---|
| A | Flyway V16/V17/V18 真实迁移 | 空库全量迁移到 18；`agent_run` / `agent_run_event` / `tool_approval` 表存在；token 三列与幂等唯一索引存在；重复 `migrate()` 为 0 条；`validate()` 通过 |
| B | 重启恢复 | 真实库中遗留 `QUEUED`/`RUNNING` 被新实例标记为 `FAILED` + `PROCESS_RESTARTED` 并追加事件；未过期审批可恢复并落库决策；过期审批启动即 `REJECTED`；会话「总是允许」恢复 |
| C | 模型故障真实 HTTP 演练 | 本地 stub HTTP server 让客户端真实收到 500/429/连接拒绝/流式空闲超时；验证指数退避重试次数、已输出不重试、熔断快速失败、错误回调只一次 |
| D | 双实例共用同一 PostgreSQL | 两个服务实例（两套 Service/Registry 实例）共享同库：run 状态与审批决策跨实例可见；并如实记录「跨实例唤醒等待线程」未实现 |
| E | HTTP 层跨账号越权 | 真实 JWT 经 `JwtAuthenticationFilter` 解析出 userId；他人 run 事件流 / stop / approval 被 4xx 拒绝，本人正常 |

## 2. 已确认的代码事实（验收依据）

- `db/migration/V16__add_agent_run.sql`：建 `agent_run`、`agent_run_event`，含 `uk_agent_run_client_request` 部分唯一索引、`ON DELETE CASCADE`、`UNIQUE(run_id, seq)`。
- `db/migration/V17__add_tool_approval.sql`：建 `tool_approval`，`approval_id` 唯一，含 `state`、`session_id/state/remember_session` 索引。
- `db/migration/V18__add_agent_run_tokens.sql`：`agent_run` 增加 `prompt_tokens` / `completion_tokens` / `total_tokens`（BIGINT NOT NULL DEFAULT 0）及 `idx_agent_run_session_user`。
- `V3__add_vector_store.sql` 执行 `CREATE EXTENSION IF NOT EXISTS vector`，因此内嵌库必须使用含 pgvector 的运行时（`build/pg-runtime/pg-runtime.txz`），不能用 zonky 默认二进制。
- `AgentRunService.recoverAfterRestart()`（`@PostConstruct`）：扫描 `QUEUED`/`RUNNING` → `FAILED`，`errorCode=PROCESS_RESTARTED`，写 `endTime`，追加 `STATE_CHANGED` 事件。
- `ToolApprovalRegistry.restorePersistedApprovals()`：未过期 `PENDING` 恢复为可决策内存记录；过期 `PENDING` 直接落 `REJECTED`；`APPROVED + remember_session` 恢复到会话允许集合。
- `ToolApprovalRegistry.resolve()` 只查本实例内存 `pending`，因此别的实例创建的待审批无法被本实例 `resolve` 唤醒——这是 D 项的已知边界，必须在报告中如实写明。
- `SpringAiAgentClient.streamChat()`：已输出 chunk 不重试；`isRetryable` 覆盖 429/500/502/503/504、超时、连接中断；`sleepBackoff(attempt)` 指数退避；`ProviderCircuitBreaker` 连续失败到阈值后窗口期内快速失败；流式 `idleTimeoutSeconds` 由 Reactor `.timeout()` 实现。
- `AgentChatController`：`GET /api/agent/runs/{runId}/events` 先 `findByIdAndUserId`；`POST /api/agent/chat/stop` 先 `chatService.getOwnedSession`；`POST /api/agent/approvals/{approvalId}` 依赖 `toolApprovalRegistry.resolve(..., userId, ...)` 的归属校验；`getCurrentUserId()` 从 `SecurityContextHolder` 的 `principal`（Long）取 userId。
- `JwtAuthenticationFilter`：`Authorization: Bearer <token>` → `JwtUtil.getUserIdFromToken` + jti 白名单 `TokenSessionService.isActive` → 写入 SecurityContext。
- `GlobalExceptionHandler`：`BusinessException` 按其 `ErrorCode` 映射 HTTP 状态与统一信封。
- `DbLifecycleManager.USER = "postgres"`，默认库 `postgres`（内嵌 PG 测试沿用）。

## 3. 新增测试文件（精确路径）

1. `src/test/java/com/capsule/agentkey/acceptance/EmbeddedPgSupport.java`
   - 静态工具：从 `DESKTOP_PG_RUNTIME` 读取 pgvector 运行时，`EmbeddedPostgres.builder().setDataDirectory(...).setCleanDataDirectory(true).setServerConfig("listen_addresses","127.0.0.1").setPgBinaryResolver(...)` 启动；返回 `EmbeddedPostgres` 与 JDBC URL（`postgres`/`postgres`）。
2. `src/test/java/com/capsule/agentkey/acceptance/FlywayV18MigrationAcceptanceTest.java`（A）
   - `@EnabledIfEnvironmentVariable(named="DESKTOP_PG_RUNTIME", matches=".+")`
   - `@TempDir` 数据目录；`Flyway.configure().dataSource(...).locations("classpath:db/migration").callbacks(new PendingFileChangePrecheckCallback())`。
   - 用例：`空库全量迁移到V18且结构完整`、`重复migrate为零条且validate通过`。
3. `src/test/java/com/capsule/agentkey/acceptance/RestartRecoveryAndMultiInstanceAcceptanceTest.java`（B+D）
   - `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@DynamicPropertySource` 指向内嵌 PG；`spring.flyway.enabled=true`、`spring.jpa.hibernate.ddl-auto=validate`。
   - 注入 `AgentRunRepository` / `AgentRunEventRepository` / `ToolApprovalRepository`。
   - B 用例：`重启后遗留RUNNING与QUEUED变FAILED并写PROCESS_RESTARTED事件`、`未过期审批重启可恢复并落库决策`、`过期审批重启即拒绝`。
   - D 用例：`双实例共享run状态`、`双实例审批决策落库可见_跨实例唤醒未实现`。
4. `src/test/java/com/capsule/agentkey/acceptance/ProviderOutageAcceptanceTest.java`（C）
   - JDK `com.sun.net.httpserver.HttpServer` 于 `127.0.0.1:0`，处理器接受任意路径。
   - 用例：`上游500按退避重试后失败且只回调一次错误`、`429限流可重试`、`连接拒绝判为可重试`、`流式空闲超时触发失败`、`熔断打开后快速失败且不再发请求`。
5. `src/test/java/com/capsule/agentkey/acceptance/HttpCrossTenantAcceptanceTest.java`（E）
   - `MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).addFilters(jwtFilter)`。
   - 真实 `JwtUtil`（反射注入 secret/expiration）生成 user1/user2 token；`TokenSessionService` 用 Mockito stub `isActive=true`。
   - 控制器其余依赖为 Mockito mock；`AgentRunService` 用真实实现 + mock Repository；`ToolApprovalRegistry` 用真实无参构造。
   - 用例：`他人run事件流被拒绝_本人可读`、`他人审批不能被代为决策_本人可决策`、`他人会话不能stop_本人可stop`、`无token返回401`。

## 4. 运行方式（Windows，D 盘）

```powershell
$env:TEMP='D:\AgentKeyBuild\tmp'; $env:TMP='D:\AgentKeyBuild\tmp'
$env:DESKTOP_PG_RUNTIME='D:\test-code\agent-gateway-v3\build\pg-runtime\pg-runtime.txz'
& 'C:\Users\16680\.jdks\temurin-24\bin\java.exe' -classpath 'C:\Users\16680\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\boot\plexus-classworlds-2.11.0.jar' '-Dclassworlds.conf=C:\Users\16680\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\m2.conf' '-Dmaven.home=C:\Users\16680\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16' '-Dmaven.multiModuleProjectDirectory=d:\test-code\agent-gateway-v3' org.codehaus.plexus.classworlds.launcher.Launcher -B test '-Dtest=FlywayV18MigrationAcceptanceTest,RestartRecoveryAndMultiInstanceAcceptanceTest,ProviderOutageAcceptanceTest,HttpCrossTenantAcceptanceTest' -DfailIfNoTests=false
```

- 逐个执行、失败即停，不反复重试同一失败动作。
- `@TempDir` 与内嵌 PG 临时目录随 `TEMP/TMP` 落在 `D:\AgentKeyBuild\tmp`；结束后删除该目录下本轮产物。

## 5. 清理与归档

- 测试结束删除 `D:\AgentKeyBuild\tmp` 下本轮临时目录与残留 PG/Java 测试进程。
- 更新 `docs/UPDATE_PLAN.md` 4.1 表：记录 A～E 实际结果与未覆盖边界（尤其 D 的跨实例唤醒、E 未覆盖完整 SecurityConfig 过滤器链与 Redis 白名单）。

## 6. 明确不做 / 未覆盖

- 不启动完整 Spring Boot 应用（Redis、邮件、MCP、桌面壳）做端到端网页/桌面验收。
- E 不验证完整 Spring Security 过滤器链（`SecurityConfig`、登录、限流），只验证真实 JWT 解析 + 控制器归属校验；如需全链路，需额外环境，另行评估。
- 不修改生产代码；若测试暴露缺陷，先报告再决定修复。
