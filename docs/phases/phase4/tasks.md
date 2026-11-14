# Phase 4 出境验收 Tasks

依赖 Spec：[spec.md](spec.md)

## 任务清单

- [x] T1 内嵌 PG 支撑
  - 新建 `src/test/java/com/capsule/agentkey/acceptance/EmbeddedPgSupport.java`
  - 读取 `DESKTOP_PG_RUNTIME`，启动含 pgvector 的 `EmbeddedPostgres`，暴露 JDBC URL
  - 验证：编译通过

- [x] T2 A：Flyway V16/V17/V18 真实迁移
  - 新建 `FlywayV18MigrationAcceptanceTest.java`
  - 断言：迁移到 18；三表存在；token 三列存在；`uk_agent_run_client_request` 存在；重复 migrate 为 0；`validate()` 通过
  - 验证：2 项通过（真实 PostgreSQL 16.11 + pgvector 0.8.7）

- [x] T3 B：重启恢复（真实库 + 新实例）
  - 新建 `RestartRecoveryAndMultiInstanceAcceptanceTest.java` 的 B 部分
  - 断言：QUEUED/RUNNING → FAILED + `PROCESS_RESTARTED` + 事件；未过期审批可恢复并落 APPROVED；过期审批落 REJECTED
  - 验证：3 项通过

- [x] T4 D：双实例共用同库
  - 同文件 D 部分
  - 断言：run 状态跨实例可见；审批决策落库后另一实例可见；记录跨实例唤醒未实现
  - 验证：2 项通过；已知边界已记录

- [x] T5 C：模型故障真实 HTTP 演练
  - 新建 `ProviderOutageAcceptanceTest.java`
  - 覆盖：500 退避重试、429 可重试、连接拒绝、流式空闲超时、首片后不重试、熔断快速失败且不再发请求、错误回调只一次
  - 验证：6 项通过

- [x] T6 E：HTTP 层跨账号越权（真实 JWT）
  - 新建 `HttpCrossTenantAcceptanceTest.java`
  - 覆盖：他人 run 事件流 400 / 本人 200；他人审批 400 且不唤醒 / 本人 200；他人 stop 400 / 本人 200；无 token 400、伪造 token 401
  - 验证：4 项通过

- [x] T7 清理与归档
  - 删除 `D:\AgentKeyBuild\tmp` 本轮产物：PG 数据目录与 surefire 临时文件已被环境的回收机制移入回收站；`embedded-pg` 二进制缓存与 4 个 `mockitoboot*.jar` 直接删除被系统拒绝（Access denied），仍留在该目录，均为可重建的缓存/临时 jar
  - 确认无本轮遗留 `postgres`/`java` 测试进程（在跑的 postgres 属于本机 `postgresql-x64-16` 服务，路径 `D:\PostgreSQL\data`，与本轮无关）
  - 更新 `docs/UPDATE_PLAN.md` 4.1 表：A～E 实际结果与未覆盖边界

## 最终验证记录（2026-11-12）

统一命令一次跑完 4 个验收类：

```text
FlywayV18MigrationAcceptanceTest                  Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
HttpCrossTenantAcceptanceTest                     Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
ProviderOutageAcceptanceTest                      Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
RestartRecoveryAndMultiInstanceAcceptanceTest     Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
合计                                              Tests run: 17, Failures: 0, Errors: 0
BUILD SUCCESS
```

## 已知边界与未覆盖

- D：两个实例各持内存 future，跨实例唤醒与重复决策互斥未实现；验收如实断言了「另一实例仍可重复提交决策」的现状。
- C：Spring AI SDK 自身带内部重试，原始 HTTP 请求数约为外层尝试的 4 倍（重试放大），因此断言用「重试确实发生」而非固定次数。
- E：未覆盖完整 `SecurityConfig` 过滤器链与 Redis 白名单（`TokenSessionService` 为 mock）；只验证真实 JWT 解析 + 控制器归属校验。
- 未做桌面壳重启人工验收、前端 SSE 自动续传、macOS/Linux 沙箱。
