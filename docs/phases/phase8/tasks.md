# 0.1.4 插件管理中心 · 可执行 Task 清单

> 更新日期：2026-11-23
> 状态：T1–T9 已完成（✅），T10–T12 进行中（🟡）；0.1.3 已发布，在 `feature/0.1.4-plugin-center` 分支开发
> 关联设计：[plugin-center.md](plugin-center.md)
> 规则：每个 Task 独立可验收；后端任务先于前端联调；T11 为集成验收；新增公开端点必须同步 `PublicApiCatalog` 与 `public-api.json`（`PublicApiContractTest` 是漂移守卫）。

状态标记：⬜ 未开始 / 🟡 进行中 / ✅ 完成。

---

## 阶段 1（0.1.4 主功能）：插件管理器骨架

### T1 · DB 迁移 + 实体加字段 ✅

**改动**：
- 新建 `src/main/resources/db/migration/V26__add_mcp_server_plugin.sql`（最新已占用版本为 V25）：
  ```sql
  ALTER TABLE mcp_server ADD COLUMN plugin_id VARCHAR(128);
  ALTER TABLE mcp_server ADD COLUMN plugin_version VARCHAR(32);
  ```
- `src/main/java/com/capsule/agentkey/entity/McpServer.java` 新增两个可空字段：
  ```java
  @Column(length = 128) private String pluginId;
  @Column(length = 32) private String pluginVersion;
  ```
- `McpServerResponse` 新增 `pluginId` / `pluginVersion` 字段（已装列表区分市场装 vs 手填、更新检查用）。

**涉及文件**：`db/migration/V26__*.sql`、`entity/McpServer.java`、`dto/McpServerResponse.java`、`service/McpServerService.java`（构建 response 时映射新字段）。

**验收**：`mvn clean test` 全绿；`ddl-auto: validate` 启动通过；现有 `mcp_server` 记录 `plugin_id` 为 NULL；手填创建的 server response 中 pluginId=null。

---

### T2 · 传输层扩展入参（不实现远程）✅

**改动**：
- 新建 `src/main/java/com/capsule/agentkey/mcp/McpConnectionSpec.java`（record）：字段 `transport / command / args / env / url / headers`，内嵌 `enum TransportType { STDIO, HTTP, SSE }`。
- `McpClientFactory` 接口新增 `McpSyncClient create(McpConnectionSpec spec)`；旧 `create(command, args, env)` 保留，内部委托新签名（传 STDIO spec）。
- `StdioMcpClientFactory` 实现 `create(spec)`：仅处理 `STDIO`，遇到 HTTP/SSE 抛 `UnsupportedOperationException("阶段 1 仅支持 stdio")`。

**涉及文件**：`mcp/McpConnectionSpec.java`（新）、`mcp/McpClientFactory.java`、`mcp/StdioMcpClientFactory.java`、`mcp/McpServerRuntime.java`（调用点适配）。

**验收**：现有 stdio MCP 端到端零回归（启动、调工具、熔断）；单测覆盖传 HTTP spec 抛异常；`McpServerRuntime` 现有调用路径不变。

---

### T3 · plugin 包 + catalog 读取 ✅

**改动**：
- 新建包 `src/main/java/com/capsule/agentkey/plugin/`：
  - `PluginManifest.java`（record）：对应 `marketplace.json` 的插件元数据（id/name/description/icon/category/transport/command/args/runtime/toolCount/requiredPermissions/credentials/homepageUrl/version）。
  - `CredentialField.java`（record）：name/label/description/required/secret/placeholder。
  - `Catalog.java`（record）：`version` + `List<PluginManifest> plugins`。
  - `PluginCatalogService.java`：阶段 1 从 classpath 读 `marketplace.json`（用 Jackson 反序列化），提供 `List<PluginManifest> listAll()` 与 `Optional<PluginManifest> get(String id)`；阶段 2 换远程时只改这个类。
- `marketplace.json` 放 `src/main/resources/marketplace.json`（后端 classpath 读）；**前端不内嵌副本**，统一通过 `GET /api/plugins/catalog` 拿（避免双份漂移）。

**涉及文件**：`plugin/`（新包 4 个文件）、`resources/plugin/marketplace.json`（T7 填充内容，本 task 先放空壳 `{"version":"1","plugins":[]}` 让 service 能跑）。

**验收**：单测 `PluginCatalogServiceTest` 能解析样例 JSON、按 id 查询、缺 id 返回空；`marketplace.json` 空壳可加载不报错。

---

### T4 · McpInstaller + InstallablePlugin 接口 ✅

**改动**：
- `plugin/InstallablePlugin.java`（接口）：`getManifest()` / `getConfigSchema()` / `install(userId, userInput)` / `uninstall(userId, instanceId)`。
- `plugin/McpInstaller.java`（implements InstallablePlugin）：
  - 构造注入 `PluginCatalogService` + `McpServerService` + 运行时检测器（T6）。
  - `install(userId, userInput)`：按 manifest 校验 `userInput` 的 key 与 `credentials` 定义匹配（必填校验）；组装 `McpServerRequest`（command/args 来自 manifest，env = userInput）；调 `McpServerService` 新方法落库（填 pluginId/pluginVersion）；返回 mcp_server id。
  - `getConfigSchema()` 返回 manifest.credentials 转成的 `List<CredentialField>`。
- `McpServerService` 新增 `createWithPlugin(userId, request, pluginId, pluginVersion)`（或给现有 `create` 加内部重载），构建实体时填 plugin 字段；手填路径仍走原 `create`（plugin 字段为 null）。

**涉及文件**：`plugin/InstallablePlugin.java`（新）、`plugin/McpInstaller.java`（新）、`service/McpServerService.java`。

**验收**：单测 `McpInstallerTest`：传合法凭证 → 调通 create 落库、pluginId/version 正确、env 加密；缺必填凭证 → 抛校验异常；手填 create 不受影响。

---

### T5 · PluginController + API 契约同步 ✅

**改动**：
- 新建 `controller/PluginController.java`，`@RequestMapping("/api/plugins")`：
  - `GET /api/plugins/catalog` → `ApiResponse<List<PluginManifest>>`，`@RequiresCapability(Capability.MCP_READ)`（桌面端才有）。
  - `POST /api/plugins/install` → body `{ pluginId, credentials }` → `ApiResponse<McpServerResponse>`，`@RequiresCapability(Capability.MCP_WRITE)`；调 `McpInstaller.install`，捕获校验异常转 400。
  - 新增 DTO `dto/PluginInstallRequest.java`（record：`String pluginId, Map<String,String> credentials`）。
- ~~同步 `PublicApiCatalog.DECLARED` / `public-api.json`~~：**决策变更**——插件端点与 `/api/mcp/servers` 同属桌面端内部接口，不加 `@PublicApi`、不纳入公开子集（AI 客户端不应自行安装插件），故无需同步 DECLARED 与快照；云端由 `@RequiresCapability(MCP_*)` 拦截禁用。`PublicApiContractTest` 仍通过（17 端点未变）。
- 确认 `PluginManifest` 序列化时不泄露敏感字段（credentials 里的 description 等可展示，无 secret 值）。

**涉及文件**：`controller/PluginController.java`（新）、`dto/PluginInstallRequest.java`（新）、`config/PublicApiCatalog.java`、`test/resources/contract/public-api.json`。

**验收**：`PublicApiContractTest` 通过（漂移守卫不报警）；curl `/api/plugins/catalog` 返回清单（空壳也行）；curl install 传合法 body → 创建 server；传缺凭证 → 400。**注意**：桌面端需 `MCP_ENABLED` + 运行时开关打开，参照现有 MCP 端点的能力门控。

---

### T6 · 运行时依赖检测（后端兜底）✅

**改动**：
- 新建 `service/RuntimeDetector.java`：`String detect(String name)` 返回版本号（如 `"v20.10.0"`）或 null；阶段 1 支持 `node` / `uvx` / `python`（用 `ProcessBuilder` 跑 `node --version` 等，限时 3s）。
- `McpInstaller.install` 里调 `RuntimeDetector` 校验 manifest.runtime，缺失抛明确异常（消息带「需要 node ≥ 18」）。
- 新增端点 `GET /api/plugins/runtime?name=node` 供前端快速检测（或前端通过 preload 本地检测，二选一——见 T10；后端兜底必须有，防前端绕过）。

**涉及文件**：`service/RuntimeDetector.java`（新）、`plugin/McpInstaller.java`、`controller/PluginController.java`（可选加 runtime 端点）。

**验收**：单测 `RuntimeDetectorTest`：node 存在返回版本、不存在返回 null（测试环境需有 node，CI 上确认）；install 时缺 runtime 抛异常且消息可展示。

---

### T7 · 前端 marketplace 数据 + TS 类型 ✅

**改动**：
- 决定数据源：**前端不内嵌 JSON 副本**，统一调 `GET /api/plugins/catalog`（T3 后端读 resources 里的 JSON）。若需离线首屏，可在前端 store 缓存上次拉取结果。
- 新建 `frontend/src/types/plugin.ts`：`PluginManifest` / `CredentialField` / `Catalog` 类型，与后端 record 字段对齐。
- 新建 `frontend/src/plugins/marketplace.json`（仅开发期参考/或后端 resources 那份的来源）——**最终以 T3 决定的单一数据源为准**，本 task 确认前后端不双份维护。
- 填充 5–10 个 curated 插件内容（见 plugin-center.md 7.3 建议清单），每个插件的 command/args/credentials 需所有者实测可用。

**涉及文件**：`frontend/src/types/plugin.ts`（新）、`src/main/resources/plugin/marketplace.json`（T3 空壳 → 本 task 填充）。

**验收**：`vue-tsc --noEmit` 类型对齐；`marketplace.json` 经 `PluginCatalogService` 能解析；至少 5 个插件、每个 credentials 字段名与官方文档一致。

---

### T8 · 前端 API 封装 ✅

**改动**：
- 新建 `frontend/src/api/plugin.ts`：
  - `fetchCatalog(): Promise<PluginManifest[]>`
  - `installPlugin(pluginId, credentials): Promise<McpServerResponse>`
  - `checkRuntime(name): Promise<string | null>`（若走后端端点）
- 复用现有 axios 实例与 `ApiResponse` unwrap 契约（参照 `frontend/src/api/mcp.ts` 或现有 api 目录写法）。
- 复用现有 `McpServerResponse` TS 类型（加 pluginId/pluginVersion 字段，与 T1 后端对齐）。

**涉及文件**：`frontend/src/api/plugin.ts`（新）、现有 `frontend/src/types/` 下的 McpServer 类型（加字段）。

**验收**：`vue-tsc --noEmit` 通过；单测（若有 api 层测试）覆盖正常与错误分支。

---

### T9 · 插件中心 UI 重构 ✅

**改动**：
- `frontend/src/views/SettingsView.vue`：把「扩展工具」tab 改名为「插件中心」，内容区替换为新组件 `PluginCenter.vue`。
- 新建 `frontend/src/components/PluginCenter.vue`：
  - 顶部 tab：`市场` / `已安装`（默认市场）。
  - 市场 tab：卡片网格（图标+名称+描述+工具数+「官方收录」标识+安装按钮），空状态文案。
  - 已安装 tab：迁移现有 `McpServersPanel.vue` 的列表渲染（名称+连接状态+工具数+操作），操作含配置/启停/卸载；右上角「+ 手动添加」按钮 → 弹出原四框表单（复用 `McpServersPanel` 的表单逻辑，或抽成 `McpManualAddDialog.vue`）。
- 新建 `frontend/src/components/PluginInstallDialog.vue`：schema-driven，按 `manifest.credentials` 渲染输入框（secret 用密码框、required 必填校验），显示权限说明 + runtime 检测结果，调 `installPlugin`。
- 新建 `frontend/src/components/PluginCard.vue`：市场卡片展示组件。

**涉及文件**：`views/SettingsView.vue`、`components/PluginCenter.vue`（新）、`components/PluginCard.vue`（新）、`components/PluginInstallDialog.vue`（新）、`components/McpServersPanel.vue`（列表逻辑迁移/复用）。

**验收**：`vue-tsc --noEmit` 零诊断；设置页「插件中心」可见两个 tab；手填四框入口仍可用；现有 MCP 列表在「已安装」tab 正常显示与操作。

> 实现注记：卡片网格与 schema-driven 安装弹窗内联在 `PluginCenter.vue`（未拆 `PluginCard.vue`/`PluginInstallDialog.vue` 独立组件，功能等价、减少文件数）；「手动添加」入口保留在「已安装」tab 内嵌的 `McpServersPanel` 中；卡片图标用首字母彩色圆块（不扩展 `Icon.vue` 图标集）。

---

### T10 · 运行时依赖检测前端 + 缺依赖引导 ⬜

**改动**：
- `PluginInstallDialog` 打开时，按 manifest.runtime 调检测（前端优先 `desktop/preload.js` 暴露的 `window.lunargold.detectRuntime`，无则调后端 `GET /api/plugins/runtime`）。
- 缺失时：安装按钮置灰 + 显示「需要 node ≥ 18，[去下载]」链接；不允许提交。
- `desktop/preload.js` 暴露 `detectRuntime(name)`（用 Node 的 `child_process` 跑版本命令）；`contextBridge` 安全暴露。

**涉及文件**：`components/PluginInstallDialog.vue`、`desktop/preload.js`、`desktop/main.js`（若需主进程配合）。

**验收**：缺 node 的机器上安装按钮禁用并提示；有 node 的机器正常安装；`vue-tsc` 通过；preload 改动不破坏现有上下文隔离。

---

### T11 · 集成验收 + 测试补齐 ⬜

**改动**：
- 后端：`mvn clean test` 全绿；新增 `PluginInstallTest`（端到端：catalog → install → server 创建 → 进程启动 → 工具注册）。
- 前端：`npx vue-tsc --noEmit` + `npm test -- --run` 全绿；补 `PluginCenter` / `PluginInstallDialog` 组件单测（schema 渲染、必填校验、安装调用）。
- 桌面端真机：装一个带 token 的插件（如 GitHub）全程 <30 秒、不碰 command/args；对话里能调 `github__xxx` 工具；卸载后工具消失。
- 回归：现有手填 MCP（四框）零回归；MCP 健康监控/熔断不受影响；云端 profile 下 `/api/plugins/*` 返回 404（能力门控）。

**验收**：上述全过；0.1.3 已验证的打包/schema 路径未受影响（`mvn clean package -DskipTests` + 桌面端打包冒烟）。

---

### T12 · 文档同步 ⬜

**改动**：
- [plugin-center.md](plugin-center.md)：把第 12 节分期表的阶段 1 状态从「未开始」更新为实际进度。
- 架构文档：`architecture/03` / `architecture/08` 的「规划」措辞按需改为「已落地」（若阶段 1 完成）。
- `STATUS.md`：能力边界 MCP 行更新为「插件中心已上线」。
- 新增端点同步：确认 `operations.md` 的端点表（若有）已加 `/api/plugins/*`。

**验收**：文档与代码事实一致；`docs/README.md` 索引无需变动。

---

## 阶段 2（远程目录，阶段 1 完成后启动，半天）

### T13 · catalog 远程化 + 离线降级 ⬜

- `PluginCatalogService`：从读 classpath 改为 fetch 远程 JSON（GitHub raw），加超时（5s）与缓存（内存，TTL 10 分钟）；fetch 失败回退 classpath 内嵌 JSON。
- 前端：市场 tab 加载失败时显示「离线模式（使用内置清单）」提示，不白屏。
- 可信来源：白名单域名校验（只认指定 raw 域名），非白名单拒绝并回退内嵌。

### T14 · 检查更新 ⬜

- 后端 `POST /api/plugins/check-update`：比对已装 `plugin_version` 与远程 catalog 的 `version`，返回有更新的插件列表。
- 前端已装 tab：有更新的插件显示「↻ 更新」按钮，点击重装（卸载旧 + install 新版）。

### T15 · （可选）清单签名校验 ⬜

> 评估后再做：私钥不入库、公钥打包进客户端；若复杂度高于收益则推迟到阶段 3。

---

## 阶段 3（按需，远程传输 + 安全加固）

### T16 · HTTP/SSE 传输实现 ⬜

- 新建 `HttpMcpClientFactory` / `SseMcpClientFactory`（用 MCP SDK 自带 `HttpClientTransport` / `SseClientTransport`，**实施前确认 SDK 版本包含这些类**，否则先升级 SDK 并跑全量测试）。
- `McpServerRuntime` 按 `spec.transport()` 选 factory。
- `mcp_server` 加 `url` / `headers_encrypted` 列（另起迁移 V27）。

### T17 · OAuth 2.1 远程授权 ⬜

- 对应 UPDATE_PLAN 5.3 的 M2-2；PKCE、刷新/撤销、回调安全；测试成本高，仅在有明确用户需求时启动。

---

## 依赖与执行顺序

```
T1(DB) ──┐
T2(传输) ─┼─→ T3(catalog) ─→ T4(installer) ─→ T5(controller) ─→ T6(检测后端)
         │                                                        │
T7(数据+类型) ─→ T8(api封装) ──────────────────────────────────→ T9(UI) ─→ T10(检测前端)
                                                                      │
                                                                      ▼
                                                              T11(集成验收) ─→ T12(文档)
```

- T1/T2/T7 可并行启动（互不依赖）。
- T5 依赖 T4；T9 依赖 T8 + T5（接口要先有）。
- T11 必须在所有功能 task 完成后；T12 在 T11 之后。

## 风险门禁

1. **main 干净门禁**：0.1.3 已发布；启动前确认 main 工作区干净（未提交改动已提交或暂存），从 main 拉新分支。
2. **`ddl-auto: validate`**：T1 加列后必须跑 `mvn clean test` 确认启动自检通过，否则 schema 漂移会让应用起不来。
3. **`PublicApiContractTest`**：T5 新增端点后必须同步契约，否则测试失败、应用启动自检失败。
4. **AES 密钥**：开发环境启动后端需带 `AES_MASTER_KEY`（参照现有 `build/run-backend.ps1`），install 的 env 加密才能跑通。
5. **SDK 版本**：阶段 3 用 `HttpClientTransport` 前确认 `io.modelcontextprotocol` 版本；不确认不启动 T16。
