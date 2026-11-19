# P6-01 验收清单

> 每项必须能指向具体测试或可复现命令，不接受「看起来没问题」。
> 勾选依据：`[x]` 表示已由测试或真实启动验证；未勾选项以「真实启动自检」小节说明缺口。

## 数据与令牌

- [x] `V20__add_api_client.sql` 在空库与已有 V19 的库上都能执行。（已有库：真实启动时 schema 18 → 19 → 20 实际执行；空库：`FlywayV18MigrationAcceptanceTest`，需 `DESKTOP_PG_RUNTIME`）
- [ ] 创建客户端后，响应里只有一次明文令牌；库中 `secret_hash` 与明文不相等。
- [ ] `last_used_at` 只在认证成功时更新。
- [x] 撤销后同一令牌立即 401，无需重启。（`DELETE /api/clients/self` → 204，同一令牌下一次 GET → 401）

## 认证与授权

- [ ] 无凭证 → 401，信封字段与现有 `ApiResponse` 一致。
- [x] 令牌格式错误 / 未知 `client_id` / secret 不匹配 → 401。（真机：`lgc_abcdefghijkl_badsecret` → 401）
- [ ] 已撤销客户端 → 401。
- [x] 清单外接口（如 `GET /api/keys`）→ 403，消息说明「未对客户端开放」。（真机：`GET /api/keys`、`POST /api/clients` → 403）
- [x] scope 不足（只有 `run:read` 调 `POST /api/agent/chat/stream`）→ 403，消息包含缺失的 scope。（真机：`缺少 scope: chat:write`）
- [x] 有对应 scope → 正常返回。（真机：`project:read` → `GET /api/projects` 200）

## 网络边界

- [ ] 非回环 `remoteAddr` → 403 且日志有 warn。
- [x] `Host` 不是 `localhost` / `127.0.0.1` / `[::1]` → 403。（真机：`Host: evil.example.com` → 403 + warn）
- [x] `Origin` 为外部域名 → 403。（真机：`Origin: https://evil.example.com` → 403，但由 CORS 先拒，信封是 `Invalid CORS request` 而非 `ApiResponse`，见自检缺口）
- [x] 合法回环请求 → 放行。（真机：`Origin: http://localhost:5173` → 200）

## 契约

- [x] `PublicApiContractTest` 通过。
- [ ] 手工新增一个未登记 `@PublicApi` 的端点后，快照测试失败。
- [x] 清单登记了不存在的方法时，应用启动失败。（真机：加一条 `/api/selfcheck/missing` 后启动中止，异常点名该条目）
- [ ] spec FR-5 表格与快照文件逐行一致。

## 零回归

- [x] Web 本地令牌访问 `GET /api/settings/defaults`、`GET /api/keys` 仍为 200。
- [ ] 现有 SSE 行为不变：事件名、顺序、`turn_id` 下发时机与改动前一致。
- [ ] 云端 JWT 路径不使用 `ClientTokenFilter`（profile 隔离验证）。
- [x] `mvn -o test`：331 用例、0 失败、0 错误、15 跳过。
- [ ] 前端 `vue-tsc` 与现有 E2E 路径无改动。

## 审计

- [ ] 客户端发起的 Run，`requestJson.clientId` 有值。
- [ ] Web 发起的 Run，`requestJson` 不含 `clientId`。
- [ ] 审批事件 payload 中的 `clientId` 仅在客户端发起时出现。

## 交付物

- [ ] spec / tasks / checklist 三份文件与实现一致，无「写了没做」的条目。
- [ ] 已知限制写进 PR 描述：不做 schema 级契约快照、不做跨客户端管理。

## 真实启动自检（2026-11-16，desktop profile + 内嵌 PG 16.11）

复现命令：设 `JAVA_HOME` / `AES_MASTER_KEY` / `DESKTOP_PG_RUNTIME` / `DESKTOP_DATA_DIR` / `DESKTOP_SERVER_PORT` 后
`mvn -o spring-boot:run -Dspring-boot.run.profiles=desktop`。

结论：首轮启动**失败**，暴露并修复一处缺陷；修复后启动成功（30.5s），HTTP 矩阵与失败路径均符合预期。

| 项 | 结果 |
|---|---|
| 缺陷 | `PublicApiCatalog` 用 `ObjectProvider<RequestMappingHandlerMapping>` 注入，Actuator 另注册 `controllerEndpointHandlerMapping`，启动报 `NoUniqueBeanDefinitionException` |
| 修复 | 构造函数加 `@Qualifier("requestMappingHandlerMapping")`，并注明只能扫 MVC 那一份 |
| 启动 | `PublicApiCatalog` 日志「公开子集清单已生效，共 15 个端点」；`Started AgentkeyApplication` |
| 迁移 | schema 18 → 19 → 20 实际执行成功，`api_client` 表就位 |
| 健康 | `GET /api/health` 返回 `apiContractVersion=1`、`serverVersion=1.0.0-SNAPSHOT`，既有字段不变 |
| 失败路径 | 登记不存在端点后 `IllegalStateException` 中止启动，异常点名 `GET /api/selfcheck/missing` |
| 回归 | `mvn -o test` 331/0/0/15 |

自检缺口（未在真机覆盖，已由单测覆盖或需另行处理）：

- **非回环 `remoteAddr`**：服务只绑 `127.0.0.1`，真机无法构造非回环来源，仍只有 `ClientTokenAcceptanceTest` 的 mock 覆盖。
- **外部 `Origin`**：Spring 的 CORS 过滤器在 `LoopbackGuardFilter` 之前拒绝，状态码 403 正确，但响应信封为 `Invalid CORS request`，且没有本项目的 warn 日志。若要求「拒绝原因可审计」，需把来源校验前移到 CORS 之前，另开批次。
- **启动自检无 CI 门禁**：本项为手工真机验证；`mvn -o test` 不启动 Spring 上下文，同类装配缺陷仍可能漏网。

