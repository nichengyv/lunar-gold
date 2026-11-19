# P6-01 实施任务

> 前置：`spec.md` 评审通过。任务按依赖顺序排列，T1-T3 完成后即可先跑鉴权测试。

## T1 数据模型与迁移

- 新增 `src/main/resources/db/migration/V20__add_api_client.sql`，建 `api_client` 表（列与索引见 spec FR-1）。
- 新增 `entity/ApiClient.java`、`repository/ApiClientRepository.java`。
- 完成判据：应用启动时 Flyway 执行成功；`FlywayV18MigrationAcceptanceTest`（真实 PostgreSQL）通过。

## T2 令牌签发与校验

- 新增 `service/ApiClientService.java`：
  - `create(userId, name, scopes)` → 生成 `client_id`（12 位 Base64URL）与 32 字节 secret，返回「一次性的明文令牌 + 视图」；
  - `authenticate(rawToken)` → 解析、查行、校验撤销状态、常量时间比较、更新 `last_used_at`，返回 `ClientPrincipal`；
  - `revokeSelf(clientId)`。
- 常量时间比较用 `MessageDigest.isEqual`；哈希用 SHA-256 十六进制。
- 完成判据：单元测试覆盖「明文不落库」「撤销后 authenticate 失败」「last_used_at 只在成功时更新」。

## T3 认证过滤器接入

- 新增 `filter/ClientTokenFilter.java`，`@Profile("desktop")`，只处理 `lgc_` 前缀的 Bearer 令牌。
- 修改 `filter/LocalTokenFilter.java`：进入方法时若 `SecurityContext` 已有认证则直接放行。
- 修改 `config/SecurityConfig.java`：desktop profile 下按 `ClientTokenFilter` → `LocalTokenFilter` 顺序注册。
- 完成判据：客户端令牌可认证成功；本地令牌仍可用；无效客户端令牌返回 401 信封。

## T4 公开子集清单

- 新增 `config/PublicApi.java` 注解（`scope`、`group`）。
- 新增 `service/PublicApiCatalog.java`：启动时扫描 `RequestMappingHandlerMapping`，构建 `METHOD + 路径模板 → scope` 映射；与注解声明双向比对，不一致抛异常终止启动。
- 注入必须用 `@Qualifier("requestMappingHandlerMapping")`：Actuator 会另注册 `controllerEndpointHandlerMapping`，不限定则启动直接 `NoUniqueBeanDefinitionException`（2026-11-16 真实启动自检发现并修复）。
- 完成判据：故意登记一个不存在的方法时，应用启动失败且日志指出具体条目。（2026-11-16 真机验证通过）

## T5 Scope 校验过滤器

- 新增 `filter/ScopeAuthorizationFilter.java`：仅对 `details instanceof ClientPrincipal` 生效；清单外 403，scope 不足 403，消息区分原因。
- 完成判据：三种情况各一条测试（放行 / 清单外 / scope 不足）。

## T6 回环与来源校验

- 新增 `filter/LoopbackGuardFilter.java`：仅对 `ClientPrincipal` 请求生效；校验 `remoteAddr`、`Host`、`Origin`。
- 完成判据：伪造 `Host`、非回环地址、非法 `Origin` 各一条测试；Web 请求不受影响。

## T7 端点标注

- 给下列方法加 `@PublicApi`（路径与 scope 见 spec FR-5）：`AgentChatController` 的 `streamChat` / `runEvents` / `stop` / `respondApproval` / `respondApprovalBatch` / `listSessionApprovals`；`ChangeSetController` 的 `changeset` / `revertRun` / `revertSession`；`ProjectController.list`；`ChatSessionController` 的 `listSessions` / `getMessages`；`HealthController.health`。
- 只加注解，不改方法体。
- 完成判据：契约快照与 spec FR-5 表格逐行一致。

## T8 客户端自管理端点

- 新增 `controller/ApiClientController.java`：`GET /api/clients/self`、`DELETE /api/clients/self`。
- 仅返回自身信息，不返回 secret；撤销写 `revoked_at`。
- 完成判据：撤销后同一令牌下一次请求 401。

## T9 健康探测字段

- `HealthController` 响应增加 `apiContractVersion`（常量 `1`）与 `serverVersion`。
- 完成判据：现有 health 断言不破；新增字段有测试。

## T10 审计字段

- `AgentChatController.streamChat` 写入 `requestJson` 时，若当前认证为 `ClientPrincipal` 则补 `clientId`。
- 审批事件 payload 补 `clientId`（为空则不带该字段，避免影响现有快照）。
- 完成判据：客户端发起的 Run，`requestJson.clientId` 有值；Web 发起时无此字段。

## T11 测试与契约快照

- 新增 `src/test/resources/contract/public-api.json`。
- 新增 `PublicApiContractTest`：清单生成结果与快照比较，按路径排序。
- 新增 `ClientTokenAcceptanceTest`：401 矩阵、403 矩阵、撤销生效、回环拒绝、Web 零回归。
- 完成判据：新增测试全绿，且故意改快照会失败。

## T12 全量回归

- 运行 `mvn -o test`，要求与基线一致（304 用例、0 失败、0 错误、15 跳过）。
- 手工冒烟：用客户端令牌依次调用 `GET /api/projects`、`POST /api/agent/chat/stream`、`GET /api/agent/runs/{id}/events`，确认事件序列与 Web 一致。
- 完成判据：无回归，冒烟记录附在 PR 描述里。

## 依赖关系

```
T1 → T2 → T3 → T5 → T6
              ↘ T4 → T7 → T11 → T12
              ↘ T8 → T9 → T10
```
