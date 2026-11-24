# 插件管理中心（Plugin Center）· 0.1.4 细化设计

> 更新日期：2026-11-23
> 状态：阶段 1 已完成（0.1.4，T1–T12 落地）；方向调整为「插件管理器」：市场 UI 与安装指引页已移除，引导压缩到「手动配置」弹窗与「从哪获取配置？」浮层；安装包内置 Node 运行时（`resources/node-runtime`），用户无需自装 Node。0.1.3 已发布，在 `feature/0.1.4-plugin-center` 分支开发
> 关联：[当前状态](../../STATUS.md)、[更新规划 5.4 节](../../UPDATE_PLAN.md)、[工具与 MCP 架构](../../architecture/03-tools-approval-sandbox.md)、[数据持久化](../../architecture/08-data-persistence.md)
> 用途：把设置页「扩展工具」从手填四框的工程师配置面板，升级为用户视角的「插件中心」（市场浏览 → 一键安装 → 已装管理）。本文是实施依据，覆盖数据模型、传输抽象、市场目录、安装流程、UI 与分期；单一事实源，其他文档只保留结论与链接。

---

## 1. 背景与问题

当前设置页「扩展工具」tab 嵌入 [McpServersPanel.vue](../../../frontend/src/components/McpServersPanel.vue)，用户添加 MCP server 需手填四个框：

| 输入框 | 后端字段 | 用户痛点 |
|---|---|---|
| 名称 | `mcp_server.name` | 要自己想名字，且是工具前缀，填错不能改 |
| 命令 | `mcp_server.command` | 内置 Node 时 `npx`/`npm` 走内置运行时（`node <npx-cli.js>`）；无内置时 Windows 下 npx/npm/pnpm/yarn 自动补 `.cmd` |
| 启动参数 | `mcp_server.args_json` | 每行一个参数，要会拆 `npx -y @xxx/server` |
| 环境变量 | `mcp_server.env_encrypted` | 要知道哪个插件需要哪个 token |

这套是工程师视角的配置面板，对普通用户门槛过高：不知道 command/args 是什么、不知道去哪找 token 对应哪个变量、填错了进程起不来。对比 Trae 的「MCP 市场一键装 + JSON 粘贴」，金月体验明显落后。

**目标**：让用户点一张卡片、填一个 token，30 秒装好一个 MCP 插件，全程不碰 command/args。

---

## 2. 目标与非目标

### 2.1 目标（0.1.4 阶段 1 交付）

1. 设置页「扩展工具」重构为「插件中心」：市场 tab（卡片网格）+ 已安装 tab（现有列表迁移）。
2. 市场内置 5–10 个官方 curated 插件，点卡片 → 弹窗只填凭证 → 一键安装并启动。
3. 安装弹窗 schema-driven：由插件元数据的 `credentials` 数组动态渲染表单，新增插件不改前端代码。
4. 运行时：安装包内置 Node 运行时，`npx` 类 server 开箱可用；uvx/python 类仍检测系统环境，缺失时在连接失败信息里给出原因。
   - 不复用 Electron 自带 Node 的取舍（2026-11-24 决策）：MCP 进程由 Java 后端 spawn，Electron 的 Node 需以 `ELECTRON_RUN_AS_NODE=1` 启动应用主程序才可用，且不含 npm/npx；npx 的子进程也未必继承该环境变量。为稳定性与「npm 官方支持」内置独立 node.exe，安装包约 +80MB；瘦身留作后续优化。
5. 保留「手动添加」高级入口（已装 tab 右上角），兼容本地自写 server 与手填场景。
6. 已装插件可启停、更新、卸载，状态与现有 MCP 健康监控打通。

### 2.2 非目标（明确不做）

- **不做开放用户提交的插件市场/平台**：不做上传接口、不做用户评分、不做插件商店。清单由项目所有者人工 curated（见第 4.4 节）。
- **阶段 1 不做远程传输**：不支持 HTTP/SSE 远程 MCP，仅 stdio 本地进程。传输口子留好（见第 6 节），阶段 3 再加。
- **阶段 1 不做远程目录**：市场清单内嵌静态 JSON，不 fetch 远程。阶段 2 再换远程。
- **不重构现有 MCP 运行时**：`McpToolBridge` / `McpServerRuntime` / `McpHealthMonitor` 主体不动，只在接缝处扩展。
- **不做「万物皆插件」**：执行约束、审批、验证、恢复保持统一；插件只是 MCP server 的分发层，不是新的执行模型。

---

## 3. 现状盘点（金月已有什么）

插件中心不从零建，复用以下现有能力：

| 能力 | 实现 | 复用方式 |
|---|---|---|
| MCP server 配置存储 | [McpServer.java](../../../src/main/java/com/capsule/agentkey/entity/McpServer.java)（name/command/argsJson/envEncrypted/enabled） | install 最终落到这张表 |
| 进程生命周期 | [McpServerRuntime.java](../../../src/main/java/com/capsule/agentkey/mcp/McpServerRuntime.java)（启动/重连/断开） | install 后由它拉起进程 |
| 工具注册 | [McpToolBridge.java](../../../src/main/java/com/capsule/agentkey/mcp/McpToolBridge.java) → `ToolRegistry` | 复用，插件工具自动进 Agent 工具列表 |
| 健康熔断 | [McpHealthMonitor.java](../../../src/main/java/com/capsule/agentkey/mcp/McpHealthMonitor.java) | 复用 |
| 密钥加密 | env AES-GCM 加密落库，接口只返回 key 名不回显值 | install 的凭证走同一加密通道 |
| 传输 | [McpClientFactory.java](../../../src/main/java/com/capsule/agentkey/mcp/McpClientFactory.java) 接口 + [StdioMcpClientFactory.java](../../../src/main/java/com/capsule/agentkey/mcp/StdioMcpClientFactory.java) 实现 | 阶段 1 复用 stdio；扩展入参留远程口子 |
| 现有 API | [McpServerController.java](../../../src/main/java/com/capsule/agentkey/controller/McpServerController.java)：`GET/POST/PUT /api/mcp/servers` | 已装 tab 复用；install 是带 plugin 元数据的 create |
| 现有 UI | [McpServersPanel.vue](../../../frontend/src/components/McpServersPanel.vue) 四框表单 + 列表 | 列表迁移到已装 tab；四框表单降级为「手动添加」弹窗 |

**缺的（本项目要补）**：插件元数据目录、schema-driven 安装表单、运行时依赖检测、市场 UI、`plugin_id`/`plugin_version` 数据列、`InstallablePlugin` 运行时抽象。

---

## 4. 核心决策

### 4.1 市场数据源：阶段 1 内嵌 → 阶段 2 远程

| 阶段 | 数据源 | 理由 |
|---|---|---|
| 阶段 1 | 后端 classpath 内嵌静态 `src/main/resources/marketplace.json`（前端经 `/api/plugins/catalog` 获取，不内嵌副本） | 零网络依赖、零证书问题、零白屏降级；Electron 打包后随包走，内网/代理环境也能用；先跑通 UI 与安装流程；单一数据源避免前后端双份漂移 |
| 阶段 2 | fetch 远程 JSON（GitHub raw 托管） | 可热更新插件列表，不用发版；fetch 失败回退内嵌 JSON；加可信来源提示 |
| 阶段 3 | （可选）自建服务端 | 用户量起来后再考虑审核/统计/版本管理，当前不做 |

内嵌换远程的成本极低：只改 `PluginCatalogService` 的加载逻辑（读 classpath → fetch 远程 + 缓存），Controller 与前端组件均不动。

### 4.2 远程传输：阶段 1 不加，扩展 `McpClientFactory` 留口子（不新造 transport 接口）

**关键结论：不要自己造 `McpTransport` 接口。** 金月用的是 MCP 官方 SDK（`io.modelcontextprotocol`），SDK 已提供 `ClientTransport` 抽象及三个实现：

- `StdioClientTransport`（当前在用，见 [StdioMcpClientFactory.java](../../../src/main/java/com/capsule/agentkey/mcp/StdioMcpClientFactory.java)）
- `HttpClientTransport`（Streamable HTTP，SDK 自带）
- `SseClientTransport`（SSE，SDK 自带）

阶段 1 只做一件事：把现有 [McpClientFactory](../../../src/main/java/com/capsule/agentkey/mcp/McpClientFactory.java) 的 `create(command, args, env)` 入参扩展为能容纳 url/headers 的 spec，但仍只实现 stdio。阶段 3 再加 `HttpMcpClientFactory` / `SseMcpClientFactory`，是新增实现类，不改现有代码。

> ⚠️ 实施前确认当前 `io.modelcontextprotocol` 依赖版本是否包含 `HttpClientTransport` / `SseClientTransport`；若版本较旧需升级 SDK，升级要跑全量测试。

### 4.3 数据模型：DB 加两列，运行时抽 `InstallablePlugin` 接口（不重构 JPA 继承）

**为什么不把 `McpServer` 改成 `Plugin` 抽象类的子类**：
- 金月 `ddl-auto: validate`，改 JPA 继承策略（SINGLE_TABLE/JOINED）要动表结构 + 数据迁移，0.1.4 初期动表结构风险高，故只加列不重构继承。
- `install()/uninstall()` 行为在 MCP（建配置+起进程）和未来指令模板（存条记录）间差异巨大，抽象基类易成空壳。

**正确做法：分两层**：

| 层 | 改动 | 风险 |
|---|---|---|
| DB 层 | `mcp_server` 表加 `plugin_id` / `plugin_version` 两个可空列（手填记录为 NULL） | 极低，加列而已，不动现有数据 |
| 运行时层 | 定义 `InstallablePlugin` 接口，先做 `McpInstaller` 一个实现 | 低，纯新增 |

未来加第二种插件类型（指令模板/模型预设）时，加一个 `InstallablePlugin` 实现类 + 对应存储，不改现有 MCP 代码。

### 4.4 审核机制：官方 curated，不开放提交（安全责任）

`marketplace.json` 里的 `command` 字段能在用户机器上执行任意命令。如果允许用户提交，一旦出现恶意插件导致用户数据泄露，**责任在发行方**——因为是金月客户端执行了它。单人开发没有审核团队、安全扫描流水线、举报响应机制，开放提交等于埋雷。

**做法**：
- `marketplace.json` 由项目所有者手工维护，每个插件亲自跑过、确认行为。
- 每个插件标注 `requiredPermissions`（如「读写指定目录」「发起网络请求」）。
- UI 显示「官方收录」标识，明确告诉用户这是筛选过的，不是任意提交的。
- 未来若开放，走 GitHub PR 审核制（用户提 PR，人工审完合并），不做上传接口。

---

## 5. 数据模型

### 5.1 `mcp_server` 表加列（DB 层）

现有表（见 [McpServer.java](../../../src/main/java/com/capsule/agentkey/entity/McpServer.java)）：`id / user_id / name / command / args_json / env_encrypted / enabled / create_time / update_time`。

新增两列（Flyway 迁移，版本号按 [UPDATE_PLAN 6.1](../../UPDATE_PLAN.md) 规则选未占用号）：

```sql
ALTER TABLE mcp_server ADD COLUMN plugin_id VARCHAR(128);
ALTER TABLE mcp_server ADD COLUMN plugin_version VARCHAR(32);
```

- `plugin_id`：对应 `marketplace.json` 里的插件 id（如 `github`）；手填记录为 NULL。
- `plugin_version`：安装时的插件版本，用于检查更新；手填记录为 NULL。
- 两列均可空，不破坏现有记录；`ddl-auto: validate` 下需同步 Flyway 迁移与实体字段。

实体加字段：

```java
// McpServer.java 新增
@Column(length = 128)
private String pluginId;        // null = 手填，非空 = 市场安装
@Column(length = 32)
private String pluginVersion;   // 市场安装时的版本
```

### 5.2 运行时 `InstallablePlugin` 接口

```java
package com.capsule.agentkey.plugin;

import java.util.List;

/**
 * 可安装插件的运行时抽象。0.1.4 先有 McpInstaller 一个实现；
 * 未来加指令模板/模型预设时新增实现，不改现有代码。
 */
public interface InstallablePlugin {

    /** 插件元数据（来自 catalog） */
    PluginManifest getManifest();

    /** 返回安装表单需要用户填的凭证字段，驱动前端 schema-driven 渲染 */
    List<CredentialField> getConfigSchema();

    /**
     * 安装：用用户填的凭证生成完整配置并落库、启动。
     * @param userInput 用户填写的凭证值（key=字段名，value=用户输入）
     * @return 安装后的插件实例 id（对 MCP 即 mcp_server.id）
     */
    Long install(Long userId, Map<String, String> userInput);

    /** 卸载：停用并删除配置 */
    void uninstall(Long userId, Long instanceId);
}
```

`CredentialField` 描述一个凭证输入框：

```java
public record CredentialField(
    String name,        // 如 "GITHUB_TOKEN"
    String label,       // 如 "GitHub Token"
    String description, // 如 "在 github.com/settings/tokens 创建，勾选 repo 权限"
    boolean required,
    boolean secret,     // true → 前端密码框 + 后端加密
    String placeholder
) {}
```

### 5.3 `McpConnectionSpec`（传输层扩展入参）

```java
package com.capsule.agentkey.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 连接规格：统一 stdio 与未来远程传输的创建参数。
 * 阶段 1 只用到 stdio 字段；阶段 3 远程传输填 url/headers。
 */
public record McpConnectionSpec(
    TransportType transport,   // STDIO / HTTP / SSE
    String command,            // stdio 用
    List<String> args,         // stdio 用
    Map<String, String> env,   // stdio 用（注入子进程环境）
    String url,                // 远程用
    Map<String, String> headers // 远程用（鉴权 header）
) {
    public enum TransportType { STDIO, HTTP, SSE }
}
```

`McpClientFactory` 改造：

```java
public interface McpClientFactory {
    // 旧签名保留（兼容现有调用），内部委托到新签名
    McpSyncClient create(String command, List<String> args, Map<String, String> env);

    // 新签名：阶段 1 StdioMcpClientFactory 只处理 STDIO，遇到 HTTP/SSE 抛 UnsupportedOperationException
    McpSyncClient create(McpConnectionSpec spec);
}
```

---

## 6. 传输层扩展

### 6.1 阶段 1：只扩展接口，不实现远程

`StdioMcpClientFactory.create(McpConnectionSpec)`：

```java
@Override
public McpSyncClient create(McpConnectionSpec spec) {
    if (spec.transport() != McpConnectionSpec.TransportType.STDIO) {
        throw new UnsupportedOperationException("阶段 1 仅支持 stdio，远程传输见阶段 3");
    }
    return create(spec.command(), spec.args(), spec.env());
}
```

### 6.2 阶段 3：新增 HTTP/SSE 实现（新增类，不改现有）

```java
@Component
public class HttpMcpClientFactory implements McpClientFactory {
    @Override public McpSyncClient create(McpConnectionSpec spec) {
        // 用 SDK 的 HttpClientTransport：new HttpClientTransport(spec.url(), headers, ...)
        // 阶段 1 不实现
    }
}
```

`McpServerRuntime` 按 `spec.transport()` 选 factory；现有 stdio 路径零改动。

---

## 7. 市场目录（catalog）

### 7.1 `marketplace.json` schema

阶段 1 放 `src/main/resources/marketplace.json`（后端 classpath 内嵌，前端通过 `GET /api/plugins/catalog` 获取，不内嵌副本）；阶段 2 换成远程 URL，仅 `PluginCatalogService` 一处改动。

```json
{
  "version": "1",
  "plugins": [
    {
      "id": "github",
      "name": "GitHub",
      "description": "管理 issue、PR、仓库，搜索代码与用户",
      "icon": "github",
      "category": "开发协作",
      "transport": "stdio",
      "command": "npx.cmd",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "runtime": ["node>=18"],
      "toolCount": 18,
      "requiredPermissions": ["发起到 api.github.com 的网络请求"],
      "credentials": [
        {
          "name": "GITHUB_TOKEN",
          "label": "GitHub Token",
          "description": "在 github.com/settings/tokens 创建，勾选 repo 权限",
          "required": true,
          "secret": true,
          "placeholder": "ghp_xxxxxxxxxxxx"
        }
      ],
      "homepageUrl": "https://github.com/modelcontextprotocol/servers",
      "version": "1.0.0"
    }
  ]
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `id` | 唯一标识，对应 `mcp_server.plugin_id`；不可变 |
| `name` / `description` / `icon` / `category` | 卡片展示用 |
| `transport` | `stdio`（阶段 1 仅此一种）/ `http` / `sse`（阶段 3） |
| `command` / `args` | stdio 启动参数，Windows 下 `.cmd` 后缀必须写全 |
| `runtime` | 运行时依赖，安装前检测（如 `node>=18`、`python>=3.9`） |
| `toolCount` | 展示用，不参与逻辑 |
| `requiredPermissions` | 安装弹窗展示的权限说明 |
| `credentials` | schema-driven 表单的字段定义 |
| `homepageUrl` | 插件详情/源码链接 |
| `version` | 插件版本，对应 `mcp_server.plugin_version`，用于检查更新 |

### 7.2 收录流程（curated）

1. 项目所有者在本地跑通该 MCP server（`npx -y @xxx/server` 能起、工具能调）。
2. 确认 `command`/`args` 无恶意行为，`credentials` 字段名与官方文档一致。
3. 填入 `marketplace.json`，标注 `requiredPermissions` 与 `runtime`。
4. 提交 PR → 人工 self-review → 合并。
5. 阶段 2 起，远程 JSON 更新无需发版，但首次收录仍需走 PR。

### 7.3 阶段 1 首批收录建议（5–10 个）

| 插件 | 用途 | 凭证 |
|---|---|---|
| GitHub | issue/PR/仓库/代码搜索 | `GITHUB_TOKEN` |
| Filesystem | 读写指定目录（金月内置已有，此为用户自定义目录） | 无 |
| PostgreSQL | 查库 | `PG_CONNECTION_STRING` |
| Fetch | 网页抓取 | 无 |
| Playwright | 浏览器自动化 | 无 |
| Git | 仓库操作 | 无 / SSH key |

> 首批以「无凭证或单 token」为主，降低安装门槛；多 OAuth 插件留到阶段 3 远程传输。

---

## 8. 安装流程

### 8.1 schema-driven 安装弹窗

点市场卡片 → 弹窗只渲染该插件 `credentials` 数组定义的字段：

```
┌─ 安装 GitHub 插件 ────────────────────┐
│ 提供 18 个工具：issue / PR / 仓库…      │
│ 权限：发起到 api.github.com 的网络请求  │
│ 运行时：需要 node ≥ 18（✅ 已检测到）   │
│                                       │
│ GitHub Token *                         │
│ [____________________________]         │
│ 在 github.com/settings/tokens 创建…    │
│                                       │
│           取消    [ 安装 ]              │
└───────────────────────────────────────┘
```

前端根据 `credentials[].secret` 决定用密码框还是文本框，`required` 做必填校验。新增插件不改前端代码——纯数据驱动。

### 8.2 运行时依赖检测

安装前按 `runtime` 字段检测：

- `node>=18`：前端调 `node --version`（桌面端可通过 preload 暴露），或后端启动时检测。
- 缺失时弹窗显示「需要 node ≥ 18，[去下载]」，不让用户装一个起不来的插件。
- 检测逻辑放前端（快速反馈），后端 install 时二次校验（防绕过）。

> 桌面端 Electron 可在 preload 暴露 `window.lunargold.detectRuntime(name)`，返回版本号或 null。阶段 1 先做 node/uvx/python 三个。

### 8.3 install 接口

新增 `PluginController`（或复用 `McpServerController`，二选一，实施时定）：

```
POST /api/plugins/install
Body: { "pluginId": "github", "credentials": { "GITHUB_TOKEN": "ghp_xxx" } }
Response: ApiResponse<McpServerResponse>  // 返回创建好的 server（含状态）
```

后端流程：
1. 按 `pluginId` 从 catalog 查 `PluginManifest`（阶段 1 读内嵌 JSON，阶段 2 读远程缓存）。
2. 校验 `credentials` 与 manifest 的 `credentials` 定义匹配（必填、字段名）。
3. 运行时依赖二次校验。
4. `McpInstaller.install()`：把 `manifest.command/args` + 用户凭证（注入 env）组装成 `McpServerRequest`，调现有 `McpServerService.create()`，填 `pluginId/pluginVersion`。
5. `McpServerRuntime` 拉起进程，`McpToolBridge` 注册工具。
6. 返回 `McpServerResponse`（env 只回 key 名，不回显值）。

**安全**：`credentials` 中的 secret 字段走现有 AES-GCM 加密落 `env_encrypted`，响应不回显。

### 8.4 凭证不回显

与现有 MCP env 一致：`McpServerResponse` 只返回 `envKeys`（`["GITHUB_TOKEN"]`），不返回值。编辑已装插件时，凭证字段留空 = 保持原值，填写 = 整体替换（沿用 [McpServersPanel.vue](../../../frontend/src/components/McpServersPanel.vue) 现有逻辑）。

---

## 9. UI 设计

### 9.1 插件中心结构（设置页）

设置页左侧 tab 把「扩展工具」改名为「插件中心」，内容区：

```
┌──────────────────────────────────────────────┐
│  [ 市场 ]  [ 已安装 ]              🔍 搜索    │
├──────────────────────────────────────────────┤
│  市场 tab（默认）：                             │
│  ┌──────┐ ┌──────┐ ┌──────┐                  │
│  │GitHub│ │ PG    │ │FS    │  ...             │
│  │18工具│ │5工具 │ │3工具 │                  │
│  │[安装]│ │[安装]│ │[安装]│                  │
│  └──────┘ └──────┘ └──────┘                  │
│  空状态：暂无插件（阶段 1 不会出现）            │
│                                              │
│  已安装 tab：                                  │
│  ┌────────────────────────────────────────┐  │
│  │ github  🟢 已连接 18 工具  ⚙️  🗑️  ↻更新 │  │
│  └────────────────────────────────────────┘  │
│  [+ 手动添加]（高级入口，右上角）               │
└──────────────────────────────────────────────┘
```

### 9.2 卡片与列表

- **市场卡片**：图标 + 名称 + 描述 + 工具数 + 「安装」按钮 + 「官方收录」标识。
- **已装列表项**：名称 + 连接状态（🟢/🔴 来自现有健康监控）+ 工具数 + 操作（配置/启停/更新/卸载）。
- 复用现有 `McpServersPanel` 的列表渲染与状态逻辑，仅换壳。

### 9.3 手动添加（高级入口）

已装 tab 右上角「+ 手动添加」→ 弹出当前四框表单（name/command/args/env），给本地自写 server 和小众插件用。默认收起，不挡新手。保存走现有 `POST /api/mcp/servers`，`plugin_id` 为 NULL。

---

## 10. API 契约

| 端点 | 方法 | 阶段 | 说明 |
|---|---|---|---|
| `/api/mcp/servers` | GET | 已有 | 已装列表（含 plugin_id/plugin_version） |
| `/api/mcp/servers` | POST | 已有 | 手动添加（plugin_id 为 null） |
| `/api/mcp/servers/{id}` | PUT | 已有 | 更新配置 |
| `/api/mcp/servers/{id}/enabled` | PUT | 已有 | 启停 |
| `/api/mcp/servers/{id}` | DELETE | 已有 | 卸载（确认现有是否有，无则补） |
| `/api/mcp/status` | GET | 已有 | 健康状态（已装列表用） |
| `/api/plugins/catalog` | GET | 阶段 1 | 返回 `marketplace.json` 内容（阶段 1 读内嵌，阶段 2 读远程缓存） |
| `/api/plugins/install` | POST | 阶段 1 | 一键安装（见 8.3） |
| `/api/plugins/check-update` | POST | 阶段 2 | 检查已装插件是否有新版本 |

> 新增端点必须同步 `PublicApiCatalog.DECLARED` 与 `src/test/resources/contract/public-api.json`，否则 `PublicApiContractTest` 漂移守卫会让应用启动自检失败（见 [STATUS.md 踩坑速查](../../STATUS.md)）。

---

## 11. 安全

| 风险 | 对策 |
|---|---|
| 恶意插件 `command` 在用户机器执行任意命令 | 官方 curated，不开放提交；每个插件人工跑过；UI 显示「官方收录」 |
| 凭证泄露 | env AES-GCM 加密落库，响应不回显；与现有 MCP env 同一加密通道 |
| 阶段 2 远程清单被篡改 | HTTPS + 来源域名白名单（只认指定 GitHub raw 域名）；fetch 失败回退内嵌；清单签名校验留到阶段 3（此时再做，避免过早引入密钥管理复杂度） |
| 阶段 3 远程传输数据出境 | 远程 URL 必须 HTTPS；UI 提示「将连接到外部服务」；超时/重定向受限 |
| 安装时运行时缺失导致半残 | 安装前检测 runtime，缺失不允许安装 |

---

## 12. 分期与验收

### 阶段 1（0.1.4 主功能，1–2 天）：插件管理器骨架

**前提**：0.1.3 已发布；从 main 拉新分支 `feature/0.1.4-plugin-center`（启动前确认 main 干净、工作区改动已处理）。

| 任务 | 交付 | 验收 |
|---|---|---|
| 运行时抽象 | `InstallablePlugin` 接口 + `CredentialField` record + `McpInstaller` 实现 | 单测覆盖 install 流程 |
| DB 加列 | `mcp_server` 加 `plugin_id`/`plugin_version` Flyway 迁移 + 实体字段 | `ddl-auto: validate` 通过；现有记录 plugin_id 为 NULL；`mvn clean test` 全绿 |
| 传输扩展 | `McpConnectionSpec` record + `McpClientFactory.create(spec)` 新签名；`StdioMcpClientFactory` 实现 | 现有 stdio 调用零回归；传 HTTP/SSE 抛明确异常 |
| 内嵌目录 | `frontend/src/plugins/marketplace.json`（5–10 插件）+ `PluginManifest` TS 类型 | 类型校验通过 |
| 后端 API | `GET /api/plugins/catalog` + `POST /api/plugins/install`；同步 PublicApi 契约 | `PublicApiContractTest` 通过；install 端到端可用 |
| 前端 UI | 设置页重构「插件中心」：市场 tab 卡片 + 已装 tab 列表 + 安装弹窗（schema-driven）+ 手动添加入口 | `vue-tsc --noEmit` 零诊断；手填场景仍可用 |
| 依赖检测 | 安装前 runtime 检测（node/uvx/python） | 缺 node 时弹窗引导，不允许安装 |
| 运行时打通 | install 后进程拉起、工具注册进 Agent、可在对话中调用 | 装 GitHub 插件 → 对话里能调 `github__xxx` 工具 |

**阶段 1 出口标志**：装一个带 token 的插件（如 GitHub）全程 <30 秒、不碰 command/args；手填四框仍可用；现有 MCP 功能零回归。

### 阶段 2（半天）：远程目录

| 任务 | 交付 | 验收 |
|---|---|---|
| 远程化 | catalog 从内嵌 JSON 换为 fetch 远程 JSON（GitHub raw） | 网络正常时拉到最新清单 |
| 离线降级 | fetch 失败/超时回退内嵌 JSON | 断网时市场 tab 不白屏，显示内嵌清单 + 「离线模式」提示 |
| 可信提示 | UI 显示清单来源 + 「官方收录」 | 来源域名白名单校验 |
| 检查更新 | `POST /api/plugins/check-update` + 已装列表「更新」按钮 | 有新版本时提示，点更新重装 |

### 阶段 3（按需）：远程传输 + 安全加固

| 任务 | 交付 | 验收 |
|---|---|---|
| 远程传输 | `HttpMcpClientFactory` / `SseMcpClientFactory`（SDK 自带 transport） | 能连一个远程 MCP（如自建 HTTP 网关） |
| 清单签名 | 远程 JSON 签名校验（私钥不入库，公钥打包进客户端） | 篡改清单被拒绝 |
| OAuth | M2-2 OAuth 2.1（PKCE、刷新/撤销） | 能连 GitHub/Notion 托管 MCP |

---

## 13. 与现有文档的关系（单一事实源）

| 事实 | 唯一归属 | 本文角色 |
|---|---|---|
| 插件中心决策与分期 | 本文 | 权威源 |
| 「不做开放插件市场」边界 | [UPDATE_PLAN.md](../../UPDATE_PLAN.md) 第 1 节 / 第 10 节 | 本文只引用结论 |
| MCP 现有架构 | [architecture/03-tools-approval-sandbox.md](../../architecture/03-tools-approval-sandbox.md) | 本文只描述扩展点 |
| `mcp_server` 加列 | [architecture/08-data-persistence.md](../../architecture/08-data-persistence.md) | 本文给出迁移 SQL，表结构事实归 08 |
| 当前状态 | [STATUS.md](../../STATUS.md) | 本文标注「阶段 1 已完成」 |

其他文档提及插件中心时，写结论并链接到本文，不复制细节。

---

## 14. 开放问题（实施前确认）

1. `PluginController` 独立建，还是 install 复用 `McpServerController`？倾向独立（未来非 MCP 插件也走 `/api/plugins`），但阶段 1 只有 MCP，复用也行——实施时定。
2. 运行时检测放前端还是后端？倾向前端快速反馈 + 后端兜底校验。
3. `marketplace.json` 的 `icon` 字段：用图标名映射前端组件，还是远程图片 URL？阶段 1 用内置图标名（零网络）。
4. 首批收录哪 5–10 个插件？需项目所有者确认（见 7.3 建议）。
5. 0.1.3 已发布（2026-11-20）；启动前确认 main 干净、未提交的工作区改动（含本会话的文档与 UI 修改）已妥善提交或暂存。
