# P6-01 本地 API 契约与客户端身份 - Product Requirements Document

## Overview

- **Summary**：在现有本地令牌 + JWT 双认证之上，新增一层「客户端身份」：`api_client` 表、`Authorization: Bearer lgc_...` 客户端令牌、scope 最小授权，以及一份显式的公开子集清单。清单外的接口对客户端令牌一律 403，Web 工作台不受影响。
- **Purpose**：让 CLI 与 IDE 插件能以「可识别、可撤销、可限权」的身份调用本地 API，而不是共用用户那枚全权本地令牌。
- **Target Users**：本地开发者；后续 P6-02 CLI 与 P6-03 VS Code 插件。

## Goals

1. 新增 `api_client` 表与客户端令牌的签发 / 校验 / 撤销，令牌只在创建时明文返回一次。
2. 用 scope 表达最小权限：`chat:write`、`run:read`、`changes:read`、`changes:write`、`approval:write`、`project:read`、`client:admin`。
3. 用注解 + 启动自检维护公开子集清单；清单外对客户端令牌 403，缺凭证 401。
4. 仅回环访问；对非回环来源、伪造 `Origin` / `Host` 的客户端请求拒绝并记日志。
5. 冻结契约：公开子集生成快照并纳入测试，字段增删必须显式更新快照。
6. 现有 Web 接口、桌面端本地令牌、云端 JWT 行为零回归。

## Non-Goals

- 不加 `/api/v1` 路径前缀，不做 URL 版本号；破坏性变更出现时再引入 `/api/v2` 或 `X-Api-Version`。
- 不开放 `documents`、`workspace` 读写、`settings`、`api-key`、`mcp`、`prompt-admin`。
- 不做 OAuth、公网访问、多租户、GraphQL、Webhook。
- 不做 CLI / 插件本体（P6-02 / P6-03）。
- 不改 `CurrentUser` 的取用户方式，不引入第二个用户身份模型。

## Background & Context

现状（2026-11-16 核实）：

| 项 | 事实 | 位置 |
|---|---|---|
| 本地认证 | `@Profile("desktop")`；从 `Authorization: Bearer` 或 `X-Local-Token` 取令牌；`LocalTokenService.matches()` 通过后 principal 为 `localUserService.localUserId()`，details 为 `"local-token"` | `filter/LocalTokenFilter.java:47-77` |
| 云端认证 | JWT 过滤器，principal 为 userId | `filter/JwtAuthenticationFilter.java` |
| 错误信封 | `ErrorCode.UNAUTHORIZED`(401) / `FORBIDDEN`(403)，由 `ResponseWriter` 或 `GlobalExceptionHandler` 输出 | `exception/ErrorCode.java:28-31` |
| 流式 | `GET /api/agent/runs/{runId}/events?sinceSeq=`，支持 `Last-Event-ID` | `AgentChatController.java:339` |
| 会话创建 | **没有独立创建端点**，`POST /api/agent/chat/stream` 在 `sessionId` 为空时隐式建会话 | `AgentChatController.java:108` |
| 迁移 | 最新为 `V19__add_tool_approval_decision_source.sql` | `src/main/resources/db/migration` |
| springdoc | 已接入，`SPRINGDOC_ENABLED` 控制是否暴露 `/v3/api-docs` | `application.yml:364-371` |

关键推论：

- 客户端令牌与本地令牌**同为 `Authorization: Bearer`**，必须先按前缀分流，否则本地令牌过滤器会把 `lgc_...` 判为无效令牌并直接 401。
- 客户端令牌最终仍解析到同一个本地用户，因此所有 `getOwnedSession` / `getOwned` 归属校验无需改动。
- 现有 `@RequiresCapability` 表达的是「部署形态是否支持某功能」，与本次的「客户端 scope」正交，两者叠加生效。

## Functional Requirements

### FR-1 数据模型：`api_client` 表（V20）

文件：`src/main/resources/db/migration/V20__add_api_client.sql`

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | 自增 |
| `client_id` | VARCHAR(32) UNIQUE | 对外标识，令牌内携带 |
| `user_id` | BIGINT | 所属本地用户，与本地令牌解析出的用户一致 |
| `name` | VARCHAR(64) | 客户端名称，如 `cli` / `vscode` |
| `secret_hash` | VARCHAR(64) | 明文 secret 的 SHA-256 十六进制，不存明文 |
| `scopes` | VARCHAR(255) | 逗号分隔的 scope 列表 |
| `created_at` | TIMESTAMP | |
| `revoked_at` | TIMESTAMP NULL | 非空即失效 |
| `last_used_at` | TIMESTAMP NULL | 每次认证成功更新，失败不更新 |

索引：`client_id` 唯一；`user_id` 普通索引。

### FR-2 令牌格式与校验

- 令牌形如 `lgc_<client_id>_<secret>`，其中 `secret` 为 32 字节 `SecureRandom` 的 Base64URL（无填充）。
- 校验流程：按 `client_id` 查行 → `revoked_at` 非空即 401 → `SHA-256(secret)` 与库中值按常量时间比较 → 更新 `last_used_at`。
- 明文令牌只在创建响应里返回一次，之后任何接口都不回显。
- 撤销：`revoked_at` 置当前时间；下一次请求立即 401，不做内存缓存。

### FR-3 认证过滤器

新增 `filter/ClientTokenFilter.java`，`@Profile("desktop")`：

- 只处理形如 `lgc_...` 的 Bearer 令牌，其他令牌原样放行。
- 校验失败统一 `ResponseWriter.writeError(response, ErrorCode.UNAUTHORIZED, "...")`，不进入业务层。
- 校验成功构造 `UsernamePasswordAuthenticationToken(localUserId, null, 空权限列表)`，`details` 放一个 `ClientPrincipal(clientId, name, scopes, clientIp)` 记录，供后续 scope 校验与审计读取。
- 注册顺序必须在 `LocalTokenFilter` **之前**；`LocalTokenFilter` 增加一条短路：`SecurityContext` 已有认证则直接放行，避免二次校验把客户端令牌判成无效。
- 云端 profile 不装配本过滤器，行为不变。

### FR-4 Scope 校验

新增 `filter/ScopeAuthorizationFilter.java`（仅 desktop）：

- 只对 `details instanceof ClientPrincipal` 的请求生效，其余请求直接放行（这是「Web 零回归」的保证）。
- 判断顺序：请求是否在公开子集清单内 → 不在则 403；清单要求的 scope 是否被客户端持有 → 不持有则 403。
- 403 用 `ErrorCode.FORBIDDEN`，消息区分「接口未对客户端开放」与「缺少 scope: xxx」，便于 CLI 给出修复提示。

### FR-5 公开子集清单

- 新增注解 `@PublicApi(scope = "...", group = "...")`，打在 controller 方法上。
- 启动时由 `PublicApiCatalog` 扫描 `RequestMappingHandlerMapping`，构建「METHOD + 路径模板 → scope」映射；与静态清单比对，出现「声明了但方法不存在」或「方法重复声明」直接启动失败。
- 清单内容（三组）：

| 组 | 端点 | scope |
|---|---|---|
| 任务闭环 | `POST /api/agent/chat/stream`（`sessionId` 为空即建会话） | `chat:write` |
| | `GET /api/agent/runs/{runId}/events` | `run:read` |
| | `POST /api/agent/chat/stop` | `chat:write` |
| | `GET /api/file-changes/changeset` | `changes:read` |
| | `POST /api/file-changes/revert-run` | `changes:write` |
| | `POST /api/file-changes/revert-session` | `changes:write` |
| | `POST /api/agent/approvals/{approvalId}` | `approval:write` |
| | `POST /api/agent/approvals/batch` | `approval:write` |
| | `GET /api/agent/sessions/{sessionId}/approvals` | `run:read` |
| 只读上下文 | `GET /api/projects` | `project:read` |
| | `GET /api/agent/sessions` | `project:read` |
| | `GET /api/agent/sessions/{sessionId}/messages` | `run:read` |
| 客户端自管理 | `GET /api/clients/self` | `client:admin` |
| | `DELETE /api/clients/self` | `client:admin` |
| | `GET /api/health` | 无需 scope（仅需有效客户端令牌） |

- `GET /api/meta`、`GET /api/auth/me` 不纳入首版：前者用于部署形态探测，客户端改用 `GET /api/health` 返回的 `serverVersion` / `apiContractVersion` 判断兼容性；后者属用户身份，客户端不需要。

### FR-6 客户端自管理端点

新增 `controller/ApiClientController.java`，`@RequestMapping("/api/clients")`：

- `GET /self`：返回当前客户端 `clientId`、`name`、`scopes`、`createdAt`、`lastUsedAt`。只返回自己，不返回 secret。
- `DELETE /self`：撤销自己（置 `revoked_at`），响应 204。撤销后该令牌立即失效，CLI 需重新签发。
- 跨客户端管理（列出全部、撤销其他客户端）不开放给客户端令牌；如需，后续用本地用户令牌实现，不在本批次。

### FR-7 健康与版本探测

- 复用 `GET /api/health`，纳入公开子集（仅要求有效客户端令牌）。
- 响应中补 `apiContractVersion`（当前 `1`）与 `serverVersion`（构建版本），供 CLI/插件做兼容性判断。字段为新增，不影响现有调用方。

### FR-8 回环与来源校验

新增 `filter/LoopbackGuardFilter.java`（仅 desktop，且仅对 `ClientPrincipal` 请求生效）：

- `request.getRemoteAddr()` 非回环 → 403 并记 warn 日志。
- `Host` 头必须是 `localhost`、`127.0.0.1` 或 `[::1]`（允许带端口）。
- 若带 `Origin`，必须是 `http://localhost:<port>`、`http://127.0.0.1:<port>` 或桌面端自定义 scheme；其余 403。
- 校验放在认证之后、业务之前；Web 前端请求不带客户端令牌，完全不经过这段逻辑。

### FR-9 契约快照

- 新增测试 `PublicApiContractTest`：从 `PublicApiCatalog` 生成稳定的 JSON（按路径排序，字段固定），与 `src/test/resources/contract/public-api.json` 比较。
- 快照只含「方法 + 路径 + scope + group」，不含请求/响应 schema；schema 由 springdoc 输出，本批次不做全量 schema 快照。
- 新增或删除公开端点必须同时改快照，否则测试失败。

### FR-10 审计

- `AgentRun.requestJson` 增加 `clientId`（客户端令牌发起时；本地令牌 / JWT 时为空）。
- 审批决策写入时，`decision_source` 仍用现有值，另在 Run Event payload 中补 `clientId`。
- 不新增独立审计表。

## 影响面（明确不动）

- `SecurityConfig` 仅新增两个过滤器的注册顺序与白名单条目，不改现有 `permitAll` 集合。
- 不改任何现有 controller 的路径、请求体、响应体，除 `GET /api/health` 增加两个字段。
- 不改 `CurrentUser`、`LocalTokenService`、`LocalUserService`、JWT 链路。
- 前端代码零改动；现有 75+ 后端单测与 23 条 E2E 路径不变。

## 验收

1. 401 矩阵：无凭证、格式错误、未知 `client_id`、secret 不匹配、已撤销，均返回 401 且信封一致。
2. 403 矩阵：清单外接口、scope 不足，均返回 403，消息可区分原因。
3. 撤销即时生效：撤销后的下一次请求即 401，无需重启。
4. 回环校验：非回环来源、伪造 `Origin` / `Host` 被拒并记日志。
5. 契约快照：`PublicApiContractTest` 通过；手工新增一个未登记端点时该测试失败。
6. 零回归：Web 本地令牌访问任意内部接口（含 `settings`、`api-key`）仍为 200；全量 `mvn -o test` 通过。
7. 同一 Run 由客户端发起时，`turnId`、事件序列、ChangeSet 结构与 Web 发起一致，仅多出 `clientId`。

## 风险

| 风险 | 应对 |
|---|---|
| 客户端令牌与本地令牌同走 `Authorization`，过滤器顺序写错导致本地令牌失效 | `LocalTokenFilter` 增加「已有认证则放行」短路；补一条「本地令牌访问内部接口仍 200」的回归测试 |
| 清单靠注解维护，新增 controller 时漏登记 | 启动自检：清单与 `RequestMappingHandlerMapping` 双向比对，不一致启动失败 |
| `ScopeAuthorizationFilter` 误伤 Web 请求 | 只对 `ClientPrincipal` 生效，并有专门回归测试 |
| 契约快照过于宽松，字段删了也不报 | 快照包含端点集合与 scope；schema 级变更留给 springdoc diff，后续批次再加 |
| 桌面端单用户模型下 `user_id` 冗余 | 保留该列以便与云端演进对齐；签发时写本地用户 id |
