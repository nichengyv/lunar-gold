# AgentKey 双端更新规划（v1.3 · 免费版）

> 版本：v1.3
> 日期：2026-11-06
> 变更说明：完成 SQLite + sqlite-vec 与内嵌 PostgreSQL + pgvector 双路线实测，选定内嵌 PostgreSQL 作为桌面端数据库；补齐 Windows x64 包体、重复启动、内存、迁移与向量 SQL 改动量数据，以及 PR-06 的实施清单。
> 依据：[STATUS.md](STATUS.md)、[design.md](design.md)、[operations.md](operations.md) 及当前代码事实
> 说明：本文按“阶段 + 出口标准”组织，不以日历工期作为承诺；每个阶段完成后可独立演示、独立回滚。

---

## 0. 一页结论

### 产品形态（对标 DeepSeek）

- **网页端**：注册/登录、对话（可生成代码片段）、知识库 RAG、附件问答、**下载桌面端入口**。不操作工作区、不生成可下载工程、不执行命令。
- **桌面端**：完整能力。本地项目读写、命令执行、Git、MCP、LSP、审批与审查流、按轮撤回。
- **两端不是两个产品**：同一套后端代码 + 同一套前端，靠 `DeploymentMode` 与能力声明区分。
- **免费版**：无支付、无订阅。网页端 v1 采用 **BYOK**（用户自备模型 Key），平台不兜底模型成本；网页端账号仅用于会话与知识库数据归属。桌面端完全本地、不强制登录。

### 技术策略

1. **桌面端选定内嵌 PostgreSQL + pgvector，云端继续使用 PostgreSQL**：PR-02 双路线实测已完成。内嵌 PG 的 `VectorStoreService` 与迁移基本复用，以约 32 MiB 的 Windows 发布增量和额外数据库子进程内存，换取最小的方言改造与云端/桌面行为一致性；详细数据与限制见“4.1 DB 选型实测报告”。
2. **Redis 只属于云端**：桌面端依靠现有内存降级路径，排除 `RedisAutoConfiguration`。
3. **先分运行时，后补外壳**：桌面端 = 本地全栈 + 一个薄壳；日常在浏览器里跑 `desktop` profile 即为桌面端开发环境。
4. **不做两个前端**：同一 Vue 应用，按 `/api/meta` 返回的能力渲染不同入口。
5. **网页端 v1 零工作区**：不做虚拟工作区、不做代码 diff、不做 zip 导出；对话内代码片段生成保留。
6. **网页端成本模型**：v1 只支持 BYOK；若未来提供平台 Key，必须同时上线每日配额与注册验证码，不允许“平台付费 + 无配额”。

### 六个阶段

| 阶段 | 主题 | 出口标志 |
|---|---|---|
| Phase 0 | 工程基线、安全红线、**DB 选型 spike** | CI 全绿、无默认密钥、桌面 DB 路线拍板 |
| Phase 1 | 运行时拆分（精简版） | 同一套代码可按 `cloud`/`desktop` 启动；网页端只剩对话 + 知识库 + 下载页 |
| Phase 2 | 桌面运行时（浏览器可测） | 一条命令启动本地模式，浏览器完成本地项目读写、执行、审批 |
| Phase 3 | 桌面外壳与分发 | 三平台安装/卸载/升级/启动冒烟通过 |
| Phase 4 | 安全与可靠性加固 | 安全清单全绿，重启/断网/多实例可恢复 |
| Phase 5 | Agent 质量与差异化 | 评测基线建立，回归门禁生效（可与 2~4 并行） |

---

## 1. 当前基线

| 项目 | 现状 | 与目标的差距 |
|---|---|---|
| 后端 | 单模块 Spring Boot，按层分包（controller/service/tool/config） | 缺少运行时模式与能力抽象 |
| 前端 | Vue 3 + Pinia，单一 Web 形态 | 无能力声明、无下载页、无平台适配层 |
| 数据库 | PostgreSQL + pgvector，Flyway V1~V15；PR-02 已选定桌面端内嵌 PostgreSQL + pgvector | PR-06 已实现本地 DB 生命周期与 V13 前置预检；P2-08 已补齐 Windows x64 含 pgvector 的运行时构建脚本与自动化集成测试，macOS/Linux 运行时仍待补 |
| 缓存 | Redis（限流、登录锁定、JWT 撤销），已有内存降级 | 桌面端已在 PR-06 排除 Redis 自动配置，上述能力改走内存降级 |
| 执行 | 宿主进程执行命令，白名单 + 路径校验 | 无 OS 沙箱、继承服务端环境变量、软链接可逃逸 |
| 状态 | 审批、权限、停止、统计多为进程内存 | 云端多实例不可用；重启丢运行态 |
| 认证 | 多用户 JWT，开放注册；云端邮箱验证与找回密码已完成（PR-12） | 桌面端本地令牌与单用户初始化已完成（PR-07）；凭证存数据目录文件而非 OS 凭据库 |
| 测试 | 10 个测试文件，以工具类与纯逻辑为主 | ReAct/SSE/审批核心链路无集成测试；前端 0 测试 |
| CI/CD | 无 | 无流水线、无发布、无签名 |
| 文档 | 四份交接文档，质量较好 | STATUS/design/operations 与代码存在漂移 |
| 版本管理 | 大量文件未提交，`frontend/` 未跟踪 | 业务连续性风险 |

---

## 2. 目标形态

### 2.1 能力矩阵

| 能力 | 网页端 | 桌面端 | 说明 |
|---|---|---|---|
| 对话、解释、方案设计 | ✅ | ✅ | 共用 Agent 循环 |
| 对话中生成代码片段 | ✅ | ✅ | 仅文本输出，不写入工作区 |
| 知识库 RAG / 附件问答 | ✅ 云端库 | ✅ 本地库 | 同一 `VectorStoreService`（方言按 spike 结果） |
| 会话历史、设置 | ✅ | ✅ | 网页端归账号，桌面端归本地 |
| 下载桌面端 | ✅ | — | 版本清单接口 |
| 本机项目读写 | ❌ | ✅ | 文件类工具仅桌面注册；工作区接口在 cloud 返回能力关闭 |
| 命令执行、构建、测试 | ❌ | ✅ | `RunCommandTool` 仅桌面注册 |
| Git 操作 | ❌ | ✅ | — |
| MCP | ❌ | ✅ | 写操作必须审批 |
| LSP 诊断 | ❌ | ✅ | 桌面端闭环 |
| 审批与“总是允许” | ❌ | ✅ | 网页端无可审批的写操作 |
| 审查流 / 按轮撤回 | ❌ | ✅ | 网页端无文件变更 |

### 2.2 运行模式

| 模式 | profile | 数据库 | Redis | 认证 | 执行 | 用途 |
|---|---|---|---|---|---|---|
| 云端 | `cloud` | 云 PG | 必需 | 多用户 JWT | 无 | 网页端 |
| 桌面开发 | `desktop` | 本地内嵌 DB | 排除 | 本地 token | 本机 + OS 沙箱 | 日常开发/测试 |
| 桌面打包 | `desktop` | 本地内嵌 DB | 排除 | 本地 token | 本机 + OS 沙箱 | 正式发布 |

### 2.3 架构

```
┌─────────────────────────────────────────────────────┐
│ 桌面端（Electron / Tauri 薄壳）                      │
│  ├─ 内嵌 WebView ← 同一套 Vue 前端                  │
│  ├─ 侧车：Spring Boot（desktop profile）             │
│  ├─ 本地内嵌数据库 + 工作区                          │
│  └─ OS 沙箱 / 自动更新 / 崩溃上报                    │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│ 云端（多租户，免费版）                               │
│  ├─ Spring Boot（cloud profile）+ PG + Redis         │
│  ├─ 网页端：注册登录 / 对话 / 知识库 / 下载桌面端    │
│  └─ 版本清单接口 / 遥测（可关闭）                    │
└─────────────────────────────────────────────────────┘
```

> 免费版**不做两端账号打通与数据同步**；桌面端完全本地运行。握手接口预留，v1 不实现。

---

## 3. 关键抽象

以下接口是双端复用的“接缝”。业务代码不直接判断模式，只依赖能力与接口。

### 3.1 运行时与能力

```java
public enum DeploymentMode { CLOUD, DESKTOP }

public enum Capability {
    CHAT, KNOWLEDGE, LOCAL_WORKSPACE,
    COMMAND_EXEC, GIT, MCP_READ, MCP_WRITE, LSP,
    APPROVAL, DOWNLOAD
}

public interface CapabilityService {
    DeploymentMode mode();
    Set<Capability> enabled();
    boolean has(Capability capability);
}
```

- `ToolRegistry` 注册工具时声明所需能力，能力不满足则不注册。
- 前端不硬编码模式，只读取 `/api/meta`。

### 3.2 密钥与认证

- 云端：沿用现有 AES + JWT（密钥走环境变量/KMS，Phase 0 先删默认值）。
- 桌面：`LocalTokenAuth`（每次安装生成，存 OS 凭据库：Windows Credential Manager / macOS Keychain / libsecret）。

### 3.3 执行与运行状态

```java
public interface CommandExecutor {
    CommandResult run(WorkspaceRef ref, List<String> command, Duration timeout);
}

public interface AgentRunStore {
    Long create(RunCreate cmd);
    void markRunning(Long runId);
    void markWaitingApproval(Long runId, String approvalId);
    void finish(Long runId, RunStatus status, RunStats stats);
}
```

- 审批、停止、权限都挂在 `runId` 上，不再依赖进程内 Map（`SessionPermissionTracker` 一并纳入）。

---

## 4. 分阶段任务

### Phase 0：工程基线与安全红线

**目标**：可持续交付的最低条件 + 堵住会阻碍拆分的安全问题 + **把数据库路线定下来**。

| ID | 任务 | 涉及文件 | 验收 |
|---|---|---|---|
| P0-01 | **已完成**：全量纳入版本管理 | `.gitignore`、`frontend/`、`docs/` | `git status` 干净；前端不再未跟踪 |
| P0-02 | **已完成（Gitee Go 实测通过）**：建立 CI 骨架 | `.github/workflows/ci.yml`、`.workflow/ci.yml` | PR 必须通过编译 + 测试 + 前端检查 |
| P0-03 | **已完成（PR-01）**：删除默认密钥并启动校验 | `application.yml`、`application-dev.yml`、`application-prod.yml` | 缺少密钥时 prod 启动失败；仓库无可用密钥 |
| P0-04 | **已完成（PR-01）**：修复安全红线 | `SecurityConfig.java`、`AgentChatController.java`、`ApiKeyService.java`、`CommandService.java`、`McpClientConfig.java` | 见“安全清单”P0 项全部关闭 |
| P0-05 | **已完成**：文档与代码对齐 | `STATUS.md`、`design.md`、`operations.md` | V14、Batch 状态、MCP 默认值一致 |
| P0-06 | **已完成**：依赖与静态扫描 + 补近期测试 | `pom.xml`、`frontend/package.json`、CI | 无 Critical 漏洞；补 `CommandRiskTest`/`AgentPolicyTest`/`DeleteFileToolTest` |
| P0-07 | **DB 选型 spike（已完成）** | 隔离实验目录；报告见 4.1 | 两条路线均实测 V1~V14、1024 维向量写入/余弦检索、会话保存/读取；完成包体、3 轮干净全链路、内存与 diff 统计；**结论：内嵌 PostgreSQL + pgvector** |

**P0 安全红线明细：**

1. `CommandService` 执行子进程前清洗环境变量，仅保留 `PATH`、`HOME`、`TMPDIR`、`SystemRoot` 等白名单项。
2. `WorkspaceFileService` 使用 `toRealPath()` 校验，禁止软链接逃逸（`delete_file` 已局部实现，推广到全部读写路径）。
3. MCP 默认关闭；启用时固定包版本、校验来源，写工具必须走审批。
4. `/api/agent/metrics` 按用户/项目过滤，禁止跨租户聚合。
5. `/api/agent/test-stream` 移出匿名白名单或删除。
6. `ApiKeyService` 的 Provider 测试地址按 Provider 区分，不再统一指向 DeepSeek。
7. SSE 超时与审批等待解耦，避免“两次审批必断流”。

**PR-01 完成批注（2026-11-06）**：已完成密钥安全、文件沙箱、命令执行隔离、MCP 安全、接口隔离、SSE 超时解耦六大类共 12 项安全加固；完成时 90 项测试全部通过。本次补记仅修正规划文档状态，不重复实施 PR-01。

**P0-05 完成批注（2026-11-07，未提交）**：已按当前代码与配置对齐 `STATUS.md`、`design.md`、`operations.md`：基线更新为 Batch F（批 76）及双端 PR-01/03/04；Flyway 更新为 V14，同时明确 V13 先 ALTER、V14 后建表的全新库缺陷仍未修复；MCP 明确默认关闭、固定 filesystem 包版本、9 个等价工具注册为 fallback、写入/删除类工具强制审批。同步修正指标用户/项目隔离、审批接口与事件、SSE 超时、工具能力门控、数据库环境变量及启动必填密钥等相关漂移。旧表述定向检索与文档行尾空白检查均无匹配；本步无代码改动，未重复执行 Maven 测试，沿用 PR-04 的 105 项回归结果。

**P0-05 本步边界**：仅修正能够由当前代码直接证实的文档漂移，不实施 P0-06 扫描与补测，也不把 V13 全新库缺陷标为已解决。Phase 0 的 P0-01、P0-02、P0-06 仍未完成，本步不代表 Phase 0 全部收尾。

**P0-06 完成批注（2026-11-07，未提交）**：为清除 OSV 检出的 14 条 Critical，后端升级为 Spring Boot 4.1.2、Spring AI 2.0.2、springdoc 3.2.0、Tika 3.2.2，Boot 4 的 AOP starter 改为 `spring-boot-starter-aspectj`；同步迁移 OpenAI Java SDK 模型构造、工具回调和 MCP `McpClientCustomizer<McpClient.SyncSpec>`，保留运行时用户 Key、自研工具执行与初始化超时行为。`JwtUtil` 密钥字节显式固定为 UTF-8。新增 `CommandRiskTest`（5 项）、`AgentPolicyTest`（5 项）、`DeleteFileToolTest`（4 项），三组定向测试共 14 项通过；升级后全量回归为 **119 项，0 失败、0 错误、2 项环境相关跳过**。最终有效 OSV 扫描覆盖 **255 个 Maven 坐标，5 条 findings、0 Critical**，而升级前为 212 个坐标、120 条 findings、14 条 Critical。剩余后端风险为 jsoup 1 High + 1 Moderate、POI 1 Moderate、commons-compress 2 Moderate；前端 `npm audit` 为 **0 Critical、1 High、3 Moderate**，本步未做非 Critical 升级。SpotBugs 4.8.6.6 复扫为 **113 条、0 个分析错误**（3 High、110 Medium），其中 JWT 默认编码问题已修复；其余 High 为两处 `SecureRandom` 使用风格提示和 `EmailEncryptor` 实例写静态字段，Medium 主要为 Spring 注入、Lombok/DTO 可变引用及既有空指针/风格候选，未为清零报告做无关重构。

**P0-06 扫描与验收边界**：OWASP Dependency Check 因无 NVD API Key 下载过慢，在 100,000/420,823 条时停止，未生成报告；OSS Index 返回 HTTP 401，两者均不计为有效通过，最终结论以 OSV 公共 API 为准。前端 `vue-tsc --noEmit` 已通过，但 Vite 在清理临时 esbuild 文件时持续报 `Access is denied`，因此不宣称前端完整构建通过。真实模型/MCP 网络链路及完整 desktop 启动、SSE 端到端未在本次升级后专项验收。P0-01、P0-02 仍未完成，本步不提交代码，也不代表 Phase 0 全部收尾。

**P0-02 实现批注（2026-11-07）**：新增 `.github/workflows/ci.yml` 与实际远端使用的 `.workflow/ci.yml`，覆盖 `master` 的 PR、push 及手动触发。后端使用 JDK 21 执行 Maven `verify`；前端使用 Node 22 执行 `npm ci` 与 `npm run build`，同时覆盖 `vue-tsc --noEmit` 和 Vite 生产构建。Gitee Go 插件最高只内置 JDK 17 / Node 15，因此两个任务均在独立引导目录下载所需运行时，不污染仓库或依赖管理员配置。

**P0-02 完成批注（2026-11-07）**：Gitee Go 已识别 `AgentKey CI`，最新 `master` 前后端流水线已实际运行成功。首次干净环境编译暴露出本地缓存未覆盖的发布版 API 差异：`SpringAiAgentClient` 移除了 Spring AI 2.0.2 已不存在的 `internalToolExecutionEnabled` 配置，并确认模型只解析工具定义、实际执行仍由自研 ReAct 循环负责；`McpToolBridge` 按 MCP SDK 2.1.0 直接传递 `Map` 类型 schema。修复后本地 `clean verify` 为 **119 项，0 失败、0 错误、2 跳过**，随后推送提交 `a07bb0e` 并完成远端验证。用于诊断的 Maven 3.9.16 额外下载已撤销，最终流水线使用 Gitee 内置 Maven，避免无关耗时。

**P0-02 验收边界**：本次以真实 Gitee Go `master` 构建关闭 CI 验证，未额外创建测试 PR；push 与 PR 触发规则已写入流水线，但仓库分支保护中的“必需状态检查”仍需在 Gitee 页面单独配置，才能从平台设置上强制拦截不合格 PR。GitHub Actions 配置保留给 GitHub 镜像使用，本次未在 GitHub runner 实际执行。本步仅关闭 P0-02，不代表 Phase 0 的“新环境按 README 可启动”出口标准已验收。

**P0-01 完成批注（2026-11-07）**：后端、前端、测试、Flyway 迁移、CI、容器配置与交接文档已统一纳入基线提交，`frontend/package-lock.json` 一并入库；`node_modules`、`dist`、`.tmp`、日志、上传文件、运行数据及 dev 密钥配置保持忽略。根目录 `KeyCheck.java`、`ResetPwd.java` 为含本机连接信息的临时调试工具，仅保留本地并与 `*.class` 一起排除，未纳入提交；交接文档中的本地数据库口令已移除。提交后 `git status` 干净，前端不再处于未跟踪状态。代码与构建验证沿用 P0-02 的 Maven 119 项测试及前端完整构建结果，本步仅调整版本管理规则、文档脱敏和提交基线。

**出口标准**：CI 全绿；新环境按 README 可启动；安全清单 P0 全关闭；**DB 选型结论与方言改造清单写入本文档**。

#### 4.1 DB 选型实测报告（PR-02，2026-11-06）

**结论：桌面端选用内嵌 PostgreSQL 16 + pgvector，不选 SQLite + sqlite-vec。** 主要原因是迁移与向量 SQL 改动量最小，并能保持云端与桌面的检索语义一致。约 32 MiB 的 Windows 发布增量和额外 PG 子进程内存属于可接受成本；包体预算与内存预算在 Phase 3 继续用真实安装包复核。

实验全部位于 `C:\Users\16680\AppData\Local\Temp\agentkey-db-spike`，未改主源码、dev 配置或密钥，也未进入 PR-03。云端数据库方案不变。

##### 功能与兼容性实测

两条路线均执行同一组断言：Flyway V1~V14 共 14 个迁移；插入两个 1024 维向量并做余弦检索，命中 chunk 为首位且分数大于 0.99；按文档统计向量数；通过 `ChatService` 创建会话、保存消息并读取历史。

| 项目 | SQLite 3.54.1 + sqlite-vec | 内嵌 PostgreSQL 16.11 + pgvector 0.8.1 |
|---|---|---|
| 功能链路 | 通过 | 通过 |
| Flyway | 支持 SQLite，14 个迁移成功 | 14 个迁移成功，`CREATE EXTENSION vector` 成功 |
| Hibernate | 6.6.22 + community dialect 在 `ddl-auto=validate` 读取 SQLite 元数据时抛 `NoSuchElementException`；实测改用 `none` 后通过，生产必须另补 schema 校验 | 原 `validate` 通过 |
| 原生库 | Windows `vec0.dll` 289,280 字节，经 Hikari `connection-init-sql` 对每个连接加载 | Windows pgvector 由 MSVC 编译并装入自定义运行时，`vector.dll` 276,992 字节 |

**既有迁移缺陷**：原始 V1~V14 在全新 PostgreSQL 库实测于 V13 失败，因为 V13 对尚未创建的 `pending_file_change` 执行 ALTER，V14 才补建表。内嵌 PG 的 3 轮成功测试使用了隔离副本中的最小 V13 修正；这不代表原始迁移可直接全新启动。PR-06 必须用 Flyway 前置幂等预检或全新安装基线解决，不得直接修改已执行迁移并破坏存量校验。**已于 PR-06 按前置幂等预检解决，见 Phase 2 的 PR-06 完成批注。**

##### 代码改动量（`git diff --no-index --numstat`）

| 路线 | 迁移 | `VectorStoreService` | 合计 |
|---|---:|---:|---:|
| SQLite + sqlite-vec | 14 个文件，+134/-267 行 | +36/-27 行 | 15 个文件，+170/-294 行 |
| 内嵌 PostgreSQL + pgvector | 仅隔离修正 V13，+20/-0 行 | **0 行** | 1 个文件，+20/-0 行 |

上述统计只比较迁移与向量服务，不含 `pom.xml`、profile、原生库打包、DB 生命周期和测试代码；V13 修正是两种路线共同面对的既有全新安装缺陷，不是 PG 方言成本。

##### Windows x64 包体、重复启动与内存

包体口径为当前共同依赖基线上的 Spring Boot 可执行 jar；SQLite jar 已含 `vec0.dll`，PG 为 jar 加外置 `pg-runtime.txz`。PG 已排除 zonky 默认带入的 macOS/Linux/Linux Alpine 运行时，只保留 Windows amd64。均不含前端、JRE、桌面壳和最终签名安装器。

内存口径为每 200 ms 采样一次 Windows `WorkingSet64` 的峰值；测试进程树包含 Maven、测试 JVM，PG 路线还包含数据库进程，不等于生产稳态 RSS。

| 指标 | SQLite + sqlite-vec | 内嵌 PostgreSQL + pgvector |
|---|---:|---:|
| 可执行 jar | 123.837 MiB | 133.807 MiB |
| 自定义 pgvector 运行时 | — | 21.999 MiB |
| 发布构成合计 | **123.837 MiB** | **155.805 MiB** |
| 干净数据库全链路 | **3/3 成功**；38.77 / 38.35 / 37.66 s | **3/3 成功**；61.11 / 59.22 / 59.92 s |
| 测试 JVM 峰值 | 318.2～322.4 MiB（有单进程明细的 2 轮） | 309.4～329.8 MiB（3 轮） |
| 整体测试进程树峰值 | 573.1～608.5 MiB | 596.6～618.7 MiB |
| PG 子进程聚合峰值 | 无独立 DB 进程 | **59.4 MiB（3 轮）** |

PG 发布构成比 SQLite 多 33,520,788 字节（31.968 MiB，+25.8%）。整体测试树受 Maven/JVM 采样波动影响，不能用其差值替代 DB 开销；可确认的新增开销是独立 PG 子进程约 59.4 MiB 峰值。

##### PR-06 实施文件清单

| 文件/模块 | 需要改动 |
|---|---|
| `pom.xml` | **已完成（PR-06）**：引入 zonky embedded-postgres；当前只引入 Windows x64 二进制并排除 darwin/linux/alpine；桌面依赖尚未做 Maven profile 隔离 |
| `application-desktop.yml` | **已完成（PR-06）**：使用生命周期管理器分配的 JDBC 连接；沿用 `db/migration`；排除 Redis 自动配置 |
| 新增 `service/desktop/DbLifecycleManager.java` | **已完成（PR-06）**：数据目录、首次 initdb、空闲端口、启动/健康检查、优雅关闭、崩溃恢复、V13 前置兼容处理 |
| 新增桌面 PG 运行时解析与构建脚本 | **已完成（P2-08）**：新增 [build-pg-runtime.ps1](../scripts/build-pg-runtime.ps1)，从本地 Maven 仓库取出 zonky 内置 txz，注入 pgvector 的 `vector.dll` 与 `share/extension` 元数据后重打包；产物不入 Git，按 SHA-256 校验上游二进制（Windows x64） |
| `src/main/resources/db/migration/` | **已完成（PR-06）**：保持云端/桌面共用；不改已执行 V1~V14，用 Flyway `beforeMigrate` 幂等预检修复 V13 |
| `VectorStoreService.java` | **无需方言改造**；保留 pgvector SQL |
| 新增桌面 DB 集成测试 | **已完成（P2-08）**：[DesktopPgVectorIntegrationTest.java](../src/test/java/com/capsule/agentkey/service/desktop/DesktopPgVectorIntegrationTest.java) 在真实内嵌库上跑 V1~V15、校验 `pg_extension` 含 vector、1024 维写入与余弦排序、维度不符被拒绝；未配置 `DESKTOP_PG_RUNTIME` 时整类跳过 |
| Phase 3 安装包清单 | 计入各平台 PG + pgvector 运行时和第三方许可，复测安装后冷/热启动与内存 |

##### 实测边界

- 仅验证 Windows x64；macOS/Linux 的 PG 与 pgvector 运行时、sqlite-vec 均未实测，3/3 不是三平台安装成功率。
- 验证的是 Spring 测试切片中的干净数据库全链路，不是完整桌面安装包冷启动，也未覆盖崩溃恢复、长稳与大规模向量性能。
- Windows pgvector 当前为自定义 MSVC 构建；其他 OS/arch 必须分别构建、签名/公证并验证 `CREATE EXTENSION vector`，不能把 Windows 结果外推。
- 若未来安装包或内存预算收紧，可重评 SQLite，但必须先解决 14 个迁移方言维护、向量 SQL 分叉、Hibernate schema 校验和每连接扩展加载问题。

---

### Phase 1：运行时拆分（精简版）

**目标**：同一套代码支持 `cloud` / `desktop` 两种模式；网页端收缩为 DeepSeek 式精简形态。

| ID | 任务 | 涉及文件 | 验收 |
|---|---|---|---|
| P1-01 | **已完成（PR-03）**：引入 `DeploymentMode` 与 `CapabilityService` | 新增 `config/DeploymentMode.java`、`config/Capability.java`、`service/CapabilityService.java` | 默认、`cloud`、`desktop` profile 能力集合均有测试 |
| P1-02 | **已完成（PR-03）**：新增元信息接口 | 新增 `controller/MetaController.java`、`dto/MetaResponse.java`；改造 `SecurityConfig.java`、`application.yml` | 公开 `GET /api/meta` 返回模式、版本、认证模式、能力与下载声明 |
| P1-03 | **已完成（PR-04）**：工具按能力注册 | `tool/ToolRegistry.java`、各 `*Tool.java` | 网页端不注册 `RunCommandTool`、`delete_file`、Git、LSP、MCP |
| P1-04 | **已完成（PR-04）**：控制器按能力收敛 | `WorkspaceController.java`、`FileReviewController.java` 等 | 网页端工作区/审查接口返回 404 或能力关闭响应 |
| P1-05 | **已完成（PR-05）**：前端能力渲染 | 新增 `api/meta.ts`、`stores/meta.ts`；改造 `router/index.ts`、`layouts/AppLayout.vue`、`views/ChatView.vue`、`components/WorkbenchDock.vue`、`views/SettingsView.vue` | 网页端隐藏工作区/终端/审查入口，桌面端显示 |
| P1-06 | **已完成（PR-05）**：网页端引导 | 新增 `views/DownloadView.vue`、下载入口组件 | 桌面功能入口处出现“下载桌面端” |
| P1-07 | **已完成（PR-12）**：网页端账号安全闭环 | 新增 `service/EmailCodeService.java`、`service/MailSender.java`、`config/MailConfig.java`、`dto/EmailCodeRequest.java`、`dto/ResetPasswordRequest.java`、`V15__add_user_email_unique_index.sql`；改造 `AuthController.java`、`UserService.java`、`LoginView.vue` | 注册需邮箱验证码、找回密码可用；发码限频（60 秒 / 每日 10 次）、错 5 次作废 |

**PR-03 完成批注（2026-11-06，未提交）**：未启用 `desktop` profile 时默认声明 `CLOUD`，现有 dev/prod 行为不变；cloud 能力固定为 `CHAT`、`KNOWLEDGE`、`DOWNLOAD`，desktop 声明其余本地能力且不声明 `DOWNLOAD`。`/api/meta` 已加入匿名白名单，直接返回第 6.1 节契约；版本默认 `1.0.0`，桌面最新版本由 `DESKTOP_LATEST_VERSION` 注入，未配置时为 `null`。新增 `CapabilityServiceTest` 与 `MetaControllerTest` 共 5 项测试；全量回归 95 项，0 失败、0 错误、2 项环境相关测试跳过。

**PR-03 本步边界**：仅完成运行模式与能力“声明”；按能力关闭工具或控制器由 PR-04 继续完成。本地 token（PR-07）、内嵌 DB（PR-06）和前端消费（PR-05）不在本步范围；因此 desktop 的 `LOCAL_SINGLE_USER` 是目标契约，不代表本地认证已经落地。本步测试覆盖 profile 能力映射与 MVC 序列化，未宣称完整 desktop 运行时已经可启动。

**PR-04 完成批注（2026-11-06，未提交）**：`BaseTool` 新增所需能力声明，`ToolRegistry` 在内置与动态工具注册前统一过滤；6 个工作区文件工具声明 `LOCAL_WORKSPACE`，`RunCommandTool` 声明 `COMMAND_EXEC`。MCP 工具复用审批风险规则区分 `MCP_READ` / `MCP_WRITE`，仅注册成功时建立 fallback 映射，避免残留无效映射。新增 `@RequiresCapability` 与 `CapabilityGuardAspect`：工作区控制器按 `LOCAL_WORKSPACE` 收敛，文件审查和对话审批入口按 `APPROVAL` 收敛，MCP 状态与开关分别按读、写能力收敛；cloud 未启用能力统一经现有异常体系返回 HTTP 404，普通项目、对话、知识库接口未加本地能力门控。新增 10 项测试，定向回归共 15 项全部通过；全量回归 105 项，0 失败、0 错误、2 项环境相关测试跳过。

**PR-04 本步边界**：当前没有独立 Git/LSP 内置工具，Git 命令仅随 `run_command` 的 `COMMAND_EXEC` 能力关闭；未提前实现 PR-05 前端隐藏、PR-06 内嵌数据库或 PR-07 本地认证。测试使用真实能力服务、AOP 代理与 standalone MockMvc 验证 cloud 拒绝、desktop 放行及业务服务零调用，不等同于完整 desktop 运行时启动验收。

**PR-05 完成批注（2026-11-07，未提交）**：前端接入 `GET /api/meta`，新增 `api/meta.ts` 与 `stores/meta.ts`（幂等加载、8 秒超时、失败不隐藏入口），路由守卫统一等待元信息后按 `capability` 收敛入口，并新增公开的 `/download` 页（`DOWNLOAD` 能力缺失时重定向）。`AppLayout` 在无 `LOCAL_WORKSPACE` 时隐藏“文件”切换、新建工作区、工作台设置弹窗和工作区行，仅保留扁平会话列表；`ChatView` 隐藏文件变更按钮、待审查改动卡片与审批卡；`WorkbenchDock` 隐藏“变更”tab；`SettingsView` 隐藏 MCP tab 并跳过 MCP 状态请求。下载入口以主导航项形式按 `download.enabled` 渲染，页面展示最新版本来自 `/api/meta`，不虚构安装包地址，`/api/releases/latest` 仍属 Phase 3。

**PR-05 验收边界**：`vue-tsc --noEmit` 与 Vite 生产构建通过；用静态预览 + 模拟 `/api/meta` 实测 `/download` 页渲染（标题、4 项能力说明、版本 3.1.0、登录入口），控制台无报错。未连接真实后端验证 cloud 登录后完整界面、desktop profile 下的能力回归，也未做浏览器 E2E；`/api/releases/latest` 与真实下载链接未实现。

**PR-12 完成批注（2026-11-07，未提交）**：新增 `EmailCodeService`（6 位数字码 / 10 分钟有效 / 同邮箱同场景 60 秒冷却 / 每日 10 次上限 / 连续错 5 次作废 / 成功即消费），沿用 `LoginAttemptService` 的 Redis + 进程内降级写法；新增 `MailSender` 抽象与 `MailConfig`，配置 `spring.mail.host` 时走 SMTP，未配置时由 `app.mail.log-codes` 决定是否把验证码打到日志（dev 默认 true，prod 默认 false，避免“接口成功但用户收不到”的静默故障）。`POST /api/auth/email-code` 按 `REGISTER`/`RESET` 场景发码，`RESET` 下未注册邮箱仍返回成功且不投递，防账号枚举；`register` 强制邮箱 + 验证码，邮箱统一小写入库并查重；`POST /api/auth/reset-password` 校验 `RESET` 验证码后改密并 `revokeAll` 撤销全部会话。新增 `V15` 迁移为 `LOWER(email)` 建部分唯一索引。前端 `LoginView` 扩为登录 / 注册 / 找回密码三态，含 60 秒倒计时与发送按钮；`api/auth.ts`、`stores/auth.ts` 同步扩展。新增 `EmailCodeServiceTest` 6 项与 `AuthControllerTest` 6 项，定向测试全部通过。

**PR-12 验收边界**：`mvn -q -DskipTests compile`、新增测试类、`vue-tsc --noEmit` 与 Vite 生产构建均通过。测试覆盖验证码发放限频/消费/作废与控制器编排（验证码错误拒绝建号、重置成功后撤销会话、未注册邮箱不泄露），Redis 路径用“调用即抛异常”的 mock 固定走降级分支，未连接真实 Redis/SMTP。未配置真实 SMTP 时的邮件投递、真实邮箱收信与浏览器端三态交互未做端到端验收；本步未引入图形验证码，开放注册的滥用防护依赖“邮箱验证码 + 发码限频”。

**出口标准**：`cloud` 模式打开网页端只有对话 + 知识库 + 下载页；注册有验证码或邮箱验证、找回密码可用；`desktop` 模式功能不回归；前端只有一套构建产物。

---

### Phase 2：桌面运行时（浏览器可测）

**目标**：不依赖桌面外壳，先让 `desktop` profile 在浏览器里完整跑通。

| ID | 任务 | 涉及文件 | 验收 |
|---|---|---|---|
| P2-01 | **已完成（PR-06）**：新增桌面配置 | 新增 `application-desktop.yml` | 本地内嵌 DB、无 Redis、单用户、工作区可配置 |
| P2-02 | **已完成（PR-06）**：本地 DB 生命周期 | 新增 `service/desktop/DbLifecycleManager.java` | 按 Phase 0 结论实现：首次初始化、启动、健康检查、优雅关闭、崩溃恢复 |
| P2-03 | **已完成（PR-07）**：本地 token 认证 | 新增 `filter/LocalTokenFilter.java`、`service/desktop/LocalTokenService.java`；改造 `SecurityConfig.java` | 无 token 拒绝；token 落 `<data-dir>/local-token`；浏览器开发模式有 dev token |
| P2-04 | **已完成（PR-06）**：排除 Redis 自动配置 | `application-desktop.yml`、`pom.xml` | 启动无 Redis 连接重试日志；限流/锁定走内存 |
| P2-05 | **已完成（PR-07）**：单用户初始化 | 新增 `service/desktop/LocalUserService.java`；改造 `AuthController.java` | 首次启动创建本地用户；注册接口在桌面模式关闭 |
| P2-06 | **已完成（P2-06）**：开发脚本 | 新增 `scripts/dev-desktop.ps1` | 一条命令启动后端 + 前端，浏览器可用；脚本实跑验证通过 |
| P2-07 | **已完成（P2-07）**：本地工作区安全 | `WorkspaceFileService.java`、`CommandService.java`；新增 `CommandServiceTimeoutTest` | 路径、软链接、环境变量、超时、进程树终止均有测试 |
| P2-08 | **已完成（仅 Windows x64）**：pgvector 随包验证 | 新增 `scripts/build-pg-runtime.ps1`；新增 `DesktopPgVectorIntegrationTest`；`docs/operations.md` 补运行时清单 | 扩展库随包存在、`CREATE EXTENSION vector` 成功、版本受控（0.8.7）、1024 维余弦检索通过；排除无关平台运行时 |

**出口标准**：`dev:desktop` 启动后，在浏览器完成“打开本地项目 → 读文件 → 改文件 → 删除文件 → 跑命令 → 审批 → 撤回”；数据落在本地 DB；断网可用。

**PR-06 完成批注（2026-11-07，未提交）**：完成 P2-01、P2-04 与 P2-02 的主体。新增 `application-desktop.yml`（排除 Boot 4 的 `DataRedisAutoConfiguration` 等三个 Redis 自动配置、Flyway 开启、`ddl-auto=validate`、数据目录与 PG 运行时可配）；新增 [DbLifecycleManager.java](../src/main/java/com/capsule/agentkey/service/desktop/DbLifecycleManager.java) 与 `DesktopDataSourceConfig`，实现数据目录创建、复用、空闲端口、启动、`SELECT 1` 健康检查、优雅关闭与 `postmaster.pid` 残留恢复；新增 [PendingFileChangePrecheckCallback.java](../src/main/java/com/capsule/agentkey/config/PendingFileChangePrecheckCallback.java)，在 `beforeMigrate` 按 V14 结构幂等预建 `pending_file_change`，解决原始 V13 在全新库对不存在表 ALTER 的缺陷，且不改已执行迁移。`LoginAttemptService`、`TokenSessionService`、`EmailCodeService`、`RateLimitInterceptor` 的 Redis 依赖改为 `@Nullable` 可选注入，无 Redis 时走既有内存降级。`pom.xml` 引入 zonky `embedded-postgres` 2.2.2 与 Windows x64 二进制，并排除 darwin/linux/alpine 运行时。

**桌面启动冒烟（Windows x64，真实执行）**：以 `--spring-boot.profiles=desktop` 启动，20.7 秒完成；Flyway 在全新数据目录应用 15 个迁移，`pg_extension` 中存在 `vector 0.8.1`，`pending_file_change` 含 `turn_id` 等 16 列；`/api/meta` 返回 `mode=DESKTOP`、`authMode=LOCAL_SINGLE_USER` 与无 `DOWNLOAD` 的能力集；`/actuator/health` 为 `UP`；日志中无任何 Redis 连接/重试记录。另人为强杀进程后重启，日志出现「检测到上次异常退出残留的 postmaster.pid，已清理」，重启复用原数据目录成功；正常停止后 `postmaster.pid` 被清理。`mvn test` 为 **140 项，0 失败、0 错误、2 项环境相关跳过**（新增 `DbLifecycleManagerTest` 5 项、`NoRedisDegradationTest` 4 项）。

**PR-06 顺带修复的两个 Boot 4 回归**：① `pom.xml` 只引 `flyway-core` 时 Boot 4 的 Flyway 自动配置模块不加载，任何 profile 启动都会跳过迁移并直接被 Hibernate `validate` 报 missing table；已改用 `spring-boot-starter-flyway`。② Boot 4 的 JSON 自动配置默认只提供 Jackson 3 的 `ObjectMapper`，而业务代码、jjwt 0.11 与 MCP SDK 仍用 Jackson 2，`ChatService` 注入失败；已引入 `spring-boot-jackson2`。两者均为 P0-06 升级后未做过完整应用启动验证所遗留，本次由桌面冒烟暴露并修复。另将 `MailConfig` 的 Bean 名改为 `agentMailSender`，避免与 Boot 在配置了 `spring.mail.host` 时注册的同名 `mailSender` 冲突。

**PR-06 验收边界**：pgvector 运行时压缩包仍未入库，本次使用 PR-02 spike 产物经 `DESKTOP_PG_RUNTIME` 指定验证；单一平台（Windows x64）通过，其他 OS/arch 与 `P2-08` 的随包构建未做。P2-03 本地 token、P2-05 单用户初始化、P2-06 一键启动脚本、P2-07 工作区加固均未实施，因此桌面端目前仍需环境变量提供 `JWT_SECRET`/`AES_MASTER_KEY`，注册入口未关闭。桌面数据库集成测试（1024 维向量写入与余弦检索）尚未自动化，由本次启动冒烟与 schema 检查替代。未提交代码。本轮同时按用户决定继续跳过 SMTP 真实投递验证，PR-12 的验收边界不变。

**PR-07 完成批注（2026-11-08，未提交）**：完成 P2-03 本地令牌与 P2-05 单用户初始化。新增 [LocalTokenService.java](../src/main/java/com/capsule/agentkey/service/desktop/LocalTokenService.java)：首次启动生成 32 字节随机令牌并写入 `<data-dir>/local-token`（POSIX 收紧为 600，Windows 依赖用户目录默认 ACL），重复启动复用；支持 `DESKTOP_DEV_TOKEN` 供浏览器开发模式配对；校验使用 `MessageDigest.isEqual`。新增 [LocalTokenFilter.java](../src/main/java/com/capsule/agentkey/filter/LocalTokenFilter.java)：兼容 `Authorization: Bearer` 与 `X-Local-Token`，无令牌放行由安全链返回 401，令牌错误立即 401，通过后把本地用户 id 写入 `SecurityContext`（Controller 无需改动）。`JwtAuthenticationFilter` 加 `@Profile("!desktop")`，`SecurityConfig` 改用 `ObjectProvider` 按 profile 装配唯一认证过滤器，避免二者同时生效。

新增 [LocalUserService.java](../src/main/java/com/capsule/agentkey/service/desktop/LocalUserService.java)：`@PostConstruct` 确保本地用户存在（默认用户名 `local`，随机密码哈希仅满足表约束、永不用于登录），并缓存 id。`AuthController` 在 `DESKTOP` 模式下关闭注册、邮箱验证码与找回密码三个入口，返回 404 以免暴露桌面端账号能力；登录/改密接口保留但无可用密码，桌面端正常路径只走本地令牌。新增 [DesktopTokenController.java](../src/main/java/com/capsule/agentkey/controller/DesktopTokenController.java)（`GET /api/desktop/token`）：仅回环地址、`Origin` 必须在 CORS 白名单内，且可用 `DESKTOP_EXPOSE_TOKEN_ENDPOINT=false` 关闭；前端在桌面模式下由路由守卫自动换取令牌（[desktop.ts](../frontend/src/api/desktop.ts)、[auth.ts](../frontend/src/stores/auth.ts)、[router/index.ts](../frontend/src/router/index.ts)）。`SecretStartupValidator` 在 desktop profile 下不再要求 `JWT_SECRET`，仍强制 `AES_MASTER_KEY`。

**PR-07 验收边界**：`mvn test` 为 **152 项，0 失败、0 错误、2 项环境相关跳过**（新增 `LocalTokenServiceTest` 4 项、`LocalTokenFilterTest` 4 项、`LocalUserServiceTest` 2 项、`SecretStartupValidatorTest` 桌面分支 1 项、`AuthControllerTest` 桌面关闭注册 1 项）；`vue-tsc --noEmit` 与 Vite 生产构建通过。计划中的「token 存 OS 凭据库」改为「用户数据目录文件 + 权限收紧」：Windows 凭据管理器需要额外原生依赖，当前文件方案在单机场景下等价，差异记录于此。未做真实桌面启动冒烟——PR-06 使用的 pgvector 运行时压缩包未入库，本次环境已无该文件，V3 迁移无法完成；P2-08 随包运行时落地后再补端到端验收。桌面端登录/改密接口未显式关闭，但本地用户密码为一次性随机值，实际不可用。

**P2-08 完成批注（2026-11-08，未提交）**：完成 P2-08 的 Windows x64 部分。新增 [build-pg-runtime.ps1](../scripts/build-pg-runtime.ps1)：默认从本地 Maven 仓库解析 zonky `embedded-postgres-binaries-windows-amd64`，取出内置 `postgres-windows-x86_64.txz`，解压后注入 pgvector 的 `lib/vector.dll` 与 `share/extension/vector*`，再以 `bin/lib/share` 为根重打包为 `build/pg-runtime/pg-runtime-windows-x64-pg16.11.0-vector0.8.7.txz`（22 MiB）；pgvector 二进制固定版本并校验 SHA-256，产物目录已被 `.gitignore` 覆盖。新增 [DesktopPgVectorIntegrationTest.java](../src/test/java/com/capsule/agentkey/service/desktop/DesktopPgVectorIntegrationTest.java)：设置 `DESKTOP_PG_RUNTIME` 时启动真实内嵌库、执行 V1~V15、断言 `pg_extension` 中 vector 为 0.8.x、`vector_record.embedding` 列类型为 vector、1024 维 one-hot 写入与余弦排序（1.0 / 0.0）、1023 维写入被拒绝；未设置环境变量时整类跳过。

**桌面端到端冒烟（Windows x64，真实执行）**：以 P2-08 产物作为 `DESKTOP_PG_RUNTIME` 用 desktop profile 启动，Flyway 在全新数据目录 0.7 秒完成 V1~V15，`pg_extension` 为 `vector 0.8.7`，`/actuator/health` 为 `UP`，`/api/meta` 返回 `mode=DESKTOP`、`authMode=LOCAL_SINGLE_USER`；无令牌访问 `/api/projects` 返回 401，带本地令牌返回 200；桌面端注册接口返回 404。`mvn test` 为 **155 项，0 失败、0 错误、2 项环境相关跳过**（新增集成测试 3 项，仅在设置 `DESKTOP_PG_RUNTIME` 时执行）。

**P2-08 验收边界**：仅 Windows x64。pgvector 的 Windows 二进制取自社区仓库（pgvector 官方不发布 Windows 构建），脚本以固定 SHA-256 锁定；正式发布前应在可控构建环境自行编译并替换来源与哈希。macOS/Linux 运行时未构建、未验证；运行时压缩包未打进 jar 或安装包，仍需 `DESKTOP_PG_RUNTIME` 指定或由 Phase 3 打包流程注入；未覆盖代码签名、安装后冷/热启动与长稳。

**P2-06 / P2-07 完成批注（2026-11-08，未提交）**：新增 [dev-desktop.ps1](../scripts/dev-desktop.ps1)：解析 JDK/Maven（PATH → MAVEN_HOME → IDEA 自带），运行时缺失时自动调用 `build-pg-runtime.ps1`，把开发用 AES 密钥持久化到 `build/dev-desktop-data/aes.key`（避免每次换密钥导致已存 API Key 无法解密），在独立窗口启动后端并把日志同时写入 `backend.log`，等 `/actuator/health` 返回 200 后再启动 Vite；5173 已监听时复用现有前端，启动失败时打印日志末尾。实跑验证：脚本在已有数据目录上 8.5 秒拉起后端、输出「后端已就绪」，桌面端 `/api/meta` 与令牌鉴权行为不变。

P2-07 补齐最后一项缺口：新增 [CommandServiceTimeoutTest.java](../src/test/java/com/capsule/agentkey/service/CommandServiceTimeoutTest.java)，用 JDK 自带编译器现场编译一个父子两代长睡进程，3 秒超时后断言 `timedOut=true`、子进程确实启动过（`child.started`）、超时后子进程 pid 已不可见，证明 `killTree` 覆盖整棵进程树而非只杀直接子进程。路径逃逸、软链接逃逸、环境变量清洗此前已有测试，本次未改动实现。

**桌面端账号入口收敛（同批）**：按「免费桌面版不需要登录」的结论，只关闭 UI 与账号体系，保留本地令牌作为本机接口保护。`SettingsView` 在 `mode=DESKTOP` 时隐藏「账号与安全」tab（并回落到「对话」），`AppLayout` 隐藏用户名与退出入口，路由守卫把 `/profile` 重定向到对话；`vue-tsc --noEmit` 与 Vite 生产构建通过。`mvn test` 为 **156 项，0 失败、0 错误、5 项跳过**（新增超时测试 1 项；跳过为 2 项既有环境相关 + 3 项未设置 `DESKTOP_PG_RUNTIME` 的集成测试）。

---

### Phase 3：桌面外壳与分发

**目标**：把已验证的本地全栈装进桌面壳，打通安装、更新。

| ID | 任务 | 涉及文件 | 验收 |
|---|---|---|---|
| P3-00 | **已完成（P3-00）**：前端随 jar 分发 | `pom.xml`、`WebConfig.java`、`SecurityConfig.java` | `mvn -Pwith-frontend package` 后单 jar 直接提供 UI，SPA 深链可用 |
| P3-00b | **已完成（同批）**：数据库运行时随 jar 分发 | `pom.xml`、`DbLifecycleManager.java`、`scripts/build-pg-runtime.ps1` | `-Pwith-pg-runtime` 打包后无需 `DESKTOP_PG_RUNTIME` 即可启动并完成 V1~V15 |
| P3-01 | **已完成（骨架）**：Electron 桌面壳 | 新增 `desktop/main.js`、`desktop/sidecar.js` | 单实例、拉起侧车、加载 UI、退出杀树；窗口渲染需在真机桌面确认 |
| P3-02 | **已完成**：侧车生命周期 | `desktop/sidecar.js`、`desktop/main.js` | 选空闲端口、健康检查、整树终止（含游离 postmaster）、崩溃重启、失败快速诊断；单实例双开待真机确认 |
| P3-03 | **已完成**：JRE 裁剪与打包 | `scripts/build-desktop-runtime.ps1`、`desktop/main.js` | `jlink` 定制运行时（19 个模块、约 49 MiB），裁剪 JRE 无头冒烟全链路通过 |
| P3-04 | 安装包与签名 | 构建脚本、证书配置 | Windows/macOS/Linux 可安装；签名校验通过 |
| P3-05 | 自动更新 | 更新模块、版本清单接口 | N-1 升级到 N；失败可回滚 |
| P3-06 | 崩溃上报 | 壳与后端接入 Sentry 或等价方案 | 崩溃可上报；默认脱敏；可关闭 |
| P3-07 | 冒烟流水线 | CI 三平台任务 | 静默安装 → 启动 → `/health` → 运行脚本任务 → 卸载 |
| P3-08 | 发布合规 | 隐私政策、服务条款、遥测开关、第三方许可 | 文档可从网页端与桌面端访问；遥测默认脱敏且可关闭；安装包内含第三方许可 |

**P3-00 完成批注（2026-11-08，未提交）**：为让桌面壳只需加载一个 URL，先做了「前端随 jar 分发」。`pom.xml` 新增 `with-frontend` profile，在 `prepare-package` 阶段把 `frontend/dist` 拷进 `classpath:/static`；[WebConfig.java](../src/main/java/com/capsule/agentkey/config/WebConfig.java) 注册 `/**` 静态处理器与 `SpaFallbackResolver`：真实文件照常返回，无扩展名的前端路由回 `index.html`，`api/`、`actuator/`、`v3/`、`swagger-ui`、`error` 前缀与带扩展名的缺失资源仍按原语义 404；[SecurityConfig.java](../src/main/java/com/capsule/agentkey/config/SecurityConfig.java) 放行 `/assets/**` 与 7 条 SPA 路由（`/chat`、`/knowledge`、`/keys`、`/profile`、`/settings`、`/login`、`/download`），接口鉴权不变。

**P3-00 实测（Windows x64，真实执行）**：`mvn -Pwith-frontend -DskipTests package` 产出 220.1 MB 单 jar，`BOOT-INF/classes/static/` 内含 index.html 与 assets；以 desktop profile 直接 `java -jar` 启动 11.7 秒就绪，`/`、`/chat`、`/settings`、`/login` 均为 200 且返回 SPA 页面，`/assets/index-*.js` 200，`/api/meta` 为 `DESKTOP`，无令牌访问 `/api/projects` 401、用 `/api/desktop/token` 换取令牌后 200，缺失静态资源 404。真实浏览器打开 `http://127.0.0.1:8080/` 自动跳转 `/chat`，侧栏与输入框渲染正常，控制台无报错、无 CSP 拦截，`ProjectController`/`SettingsController`/`FileReviewController` 均收到带令牌的成功请求。`mvn test` 为 **156 项，0 失败、0 错误、5 项跳过**，与上一轮一致。

**P3-00 验收边界**：pgvector 运行时尚未随 jar 分发（已由下一项 P3-00b 补齐）；未做 JRE 裁剪，jar 220 MB 中大部分是依赖与未裁剪的静态资源；未引入桌面壳，`DESKTOP_EXPOSE_TOKEN_ENDPOINT` 仍为 `true`（壳接管后应置 `false` 并改为读取令牌文件）。

**P3-00b 完成批注（2026-11-08，未提交）**：`pom.xml` 新增 `with-pg-runtime` profile，把 `build/pg-runtime/pg-runtime.txz` 拷进 `classpath:/desktop/`；`scripts/build-pg-runtime.ps1` 在产出带版本号文件的同时复制一份固定名 `pg-runtime.txz`，避免运行时按版本号猜文件名；[DbLifecycleManager.java](../src/main/java/com/capsule/agentkey/service/desktop/DbLifecycleManager.java) 增加内置运行时回退：显式 `DESKTOP_PG_RUNTIME` 优先于内置副本，两者都没有时打印明确告警（说明 pgvector 不可用、V3 会失败）而不是只在迁移阶段报错。内置流每次调用重新打开，满足 zonky 的多次读取。

**P3-00b 实测（Windows x64，真实执行）**：`mvn -q -Pwith-frontend,with-pg-runtime -DskipTests package` 产出 242.1 MB jar，含 `BOOT-INF/classes/static/` 与 `BOOT-INF/classes/desktop/pg-runtime.txz`；清空 `DESKTOP_PG_RUNTIME`、使用全新数据目录直接 `java -jar`，日志为「使用 jar 内置 PostgreSQL 运行时」，12 秒就绪，Flyway `Successfully applied 15 migrations ... now at version v15`（V3 的 `CREATE EXTENSION vector` 成功即证明 pgvector 可用），`/api/meta` 返回 `DESKTOP`，无令牌 401。`mvn test` 仍为 **156 项，0 失败、0 错误、5 项跳过**。

**P3-00b 边界**：内置运行时只对应构建平台（当前仅 Windows x64），macOS/Linux 需各自构建；`with-pg-runtime` 在 `build/pg-runtime/pg-runtime.txz` 缺失时不会让构建失败，只是产出一个不含运行时的 jar（启动时打告警），发布流水线应显式校验产物中存在该条目。

**P3-01 / P3-02 完成批注（2026-11-08，未提交）**：选定 Electron（本机无 Rust，Node 侧工具链已就绪）。新增 `desktop/`：`main.js` 负责单实例锁、启动画面、错误对话框、AES 主密钥首次生成与持久化、退出时终止侧车；`sidecar.js` 与 Electron 解耦，负责选空闲端口、以 `--spring.profiles.active=desktop --server.port=<port>` 拉起 jar、轮询 `/actuator/health`（只认 200，DOWN 为 503）、日志追加到 `<data-dir>/sidecar.log`，并用 `taskkill /T /F`（POSIX 用进程组）终止整棵树，避免 Spring Boot 拉起的 PostgreSQL 变成孤儿。侧车崩溃或超时会把 sidecar.log 路径显示在错误框里。

**P3-01 / P3-02 实测与边界**：`sidecar.js` 用 node 直接驱动真实 jar 验证通过——动态端口 50299、日志确认使用 jar 内置运行时、`/api/meta` 为 `DESKTOP`、`/` 返回 SPA、调用终止后 java 子进程消失。Electron 窗口未能实际拉起：本自动化环境无交互桌面会话，连最小 Electron 示例也以退出码 1 结束（`--no-sandbox` 下 `--version` 可输出 v43.3.0），因此「窗口显示与单实例锁」需要在真机桌面用 `npm start` 人工确认。另外本机 Electron 二进制需从缓存（v43.3.0）安装，`npm install` 直接下载会被网络拦截。

**P3-02 收尾批注（2026-11-08，未提交）**：`sidecar.js` 等待健康期间每 500ms 扫描日志，命中 `APPLICATION FAILED TO START`、`Web server failed to start`、`already in use`、`Unable to access jarfile`、`DESKTOP_PG_RUNTIME 指向的文件不存在` 立即失败并把日志末尾 6 行带进错误信息（端口占用、配置错误不再傻等 120 秒）；`main.js` 就绪后监视侧车 `exit`，意外退出弹「重启本地服务 / 退出」对话框，重启走完整启动流程。实测发现一个真实的树终止缺陷：zonky 的 postmaster 由 pg_ctl 分离拉起，不在 java 进程树内，`taskkill /T /F` 杀 java 后 postmaster 仍存活数十秒，立即重启会被后端判定「数据库已被其他进程占用」；修复为 `stopSidecar` 额外读取 `<data-dir>/db/postmaster.pid` 直接终止该 pid（重启路径先补杀再拉起）。

**P3-02 收尾实测（node 直驱 sidecar + 真实 jar）**：jar 缺失场景 112ms 快速失败且错误信息含日志末尾；就绪后强杀 java 树，`exit` 事件触发；补杀 postmaster 后同一数据目录原地重启 14.2 秒就绪，`/api/meta` 为 `DESKTOP`；`stopSidecar` 后 java 与 postmaster 均无残留。排查期间确认本机 5433 的用户自有 PostgreSQL 及其子进程未受影响。

**P3-02 未完成项**：单实例锁的真实双开验证（环境限制，需真机 `npm start` 双开确认）；崩溃日志上报属 P3-06。

**P3-03 完成批注（2026-11-08）**：新增 `scripts/build-desktop-runtime.ps1`，用 jlink 从本机 JDK 21 裁剪桌面端私有 JRE 到 `build/desktop-runtime/jre`（19 个模块：`java.base/compiler/desktop/instrument/management/naming/net.http/prefs/rmi/scripting/security.jgss/sql/sql.rowset/transaction.xa/xml` + `jdk.crypto.ec/unsupported/zipfs/localedata`，`--include-locales=zh,en --strip-debug --compress=2`，产出约 49 MiB，未裁剪 JDK 21 约 300 MiB）。脚本自动跳过非 21 的 JAVA_HOME（本机指向 JDK 24，回退到 `C:\Users\16680\.jdks\ms-21.0.11`）。`desktop/main.js` 的 `resolveJava` 改为打包态优先 `resources/jre/bin/java.exe`（由 P3-04 的 electron-builder extraResources 复制），开发态仍走 JAVA_HOME/PATH。

**P3-03 实测（Windows x64，真实执行）**：裁剪 JRE `java -version` 正常、含 java.sql / jdk.crypto.ec / jdk.localedata 等关键模块；以该 JRE 无头 `java -jar target\agent-gateway-1.0.0.jar --spring.profiles.active=desktop` 完整启动 12.0 秒就绪：内嵌 PostgreSQL 16.11 从 jar 内置 txz 解压启动、Flyway 校验至 V15「up to date」、`/actuator/health` UP、`/` 返回 SPA（200）。模块清单无缺口。

**P3-03 边界与环境发现**：冒烟期间发现本机当前无法执行 `initdb`（bootstrap 阶段必现 `could not create lock file "postmaster.pid": File exists`，残留 pid 为负数的垃圾值，工作区内外、沙箱内外均复现，而同日早些时候 P3-02 曾成功初始化 `build\dev-desktop-data\db`），怀疑杀软/文件过滤驱动干扰，与裁剪 JRE 无关（initdb.exe 被子进程直接拉起，全程不经 JVM）。本次冒烟改为复制已初始化的 dev 数据目录完成全链路验证；全新机器的 initdb 首启路径需在真机复核。安装包体积预算待 P3-04 用真实 NSIS 产物复核。

**出口标准**：陌生机器安装后能完成首个任务；升级、回滚可用；数据目录可备份；隐私政策、服务条款、第三方许可与遥测开关随版本发布。

---

### Phase 4：安全与可靠性加固

**目标**：从“能跑”到“敢开放”。

| ID | 任务 | 涉及文件 | 验收 |
|---|---|---|---|
| P4-01 | OS 级沙箱 | `CommandService.java`、桌面壳 | Windows Job Object；macOS Seatbelt；Linux Landlock/cgroups |
| P4-02 | 运行状态机 | 新增 `V15__add_agent_run.sql`、`AgentRunService.java` | QUEUED/RUNNING/WAITING_APPROVAL/SUCCEEDED/FAILED/CANCELLED |
| P4-03 | 审批与运行状态持久化 | `ToolApprovalRegistry.java`、`SessionPermissionTracker.java` | 桌面端重启后待审批可恢复；云端仅保留 run/stop 状态（多实例可共享），不实现云端审批 |
| P4-04 | SSE 可靠性 | `AgentChatController.java`、`api/sse.ts` | 心跳、事件 id、断线续传；审批不占用主流生命周期 |
| P4-05 | 幂等与防重 | `AgentChatController.java`、请求 DTO | 相同 `clientRequestId` 不重复创建消息/运行 |
| P4-06 | 模型调用韧性 | `SpringAiAgentClient.java` | 超时、重试退避、熔断、按 Provider 隔离 |
| P4-07 | 上下文与成本 | `ReActAgentService.java`、新增预算管理 | token 计量、预算上限、大结果落文件 |
| P4-08 | 安全回归 | 测试与 CI | 路径逃逸、命令注入、越权、跨租户均有测试 |

**出口标准**：断网、重启、双实例、审批超时、模型 5xx 五类场景均有明确行为；安全清单全绿。

---

### Phase 5：Agent 质量与差异化

**目标**：让“可控性”从功能变成可证明的质量优势。可与 Phase 2~4 并行。

| ID | 任务 | 涉及文件 | 验收 |
|---|---|---|---|
| P5-01 | 提示词外置与版本化 | 新增 `resources/prompts/*`、`PromptService.java`，改造 `ReActAgentService.java` | 提示词可热更新/回滚；按场景配置 |
| P5-02 | 评测集 | 新增 `eval/tasks/*.yaml`、评测运行器 | 覆盖工具选择、文件编辑/删除、命令审批、RAG、拒答 |
| P5-03 | 评测门禁 | CI 夜间任务 | 完成率、平均轮次、token、时延有基线；回归即失败 |
| P5-04 | 代码索引 v1 | 新增 `RepoIndexService.java` | tree-sitter 解析 + 符号索引 + repo map |
| P5-05 | LSP 诊断闭环 | 新增 `LspClient.java`（桌面） | 编辑后自动取诊断并反馈给模型 |
| P5-06 | 验证回路 | `ReActAgentService.java`、命令执行 | 改完自动跑配置的 verify 命令，失败回到循环 |
| P5-07 | RAG 增强 | `VectorStoreService.java`、检索服务 | 混合检索 + rerank + 引用；离线评测可量化 |
| P5-08 | 并行只读工具 | `ReActAgentService.java` | 只读工具并行；写操作仍串行并审批 |

**出口标准**：评测集可重复运行；核心任务成功率与成本有基线；提示词变更必须过评测。

---

## 5. 数据库迁移计划

| 版本 | 内容 | 归属 | 说明 |
|---|---|---|---|
| V15 | `agent_run` / `agent_run_event` | 共用 | 运行状态与事件流（Phase 4） |

**原则**：

1. 不直接修改已执行的 V1~V14，避免 Flyway 校验失败；spike 的 V13 修改仅限隔离副本，不作为生产方案。
2. 云端与桌面端共用 PostgreSQL 方言和 `db/migration`；大版本升级前自动备份数据目录。
3. PR-06 在 Flyway 前增加幂等预检或全新安装基线，先解决原始 V13 对不存在表的 ALTER；必须同时覆盖全新库与已执行 V14 的存量库测试。
4. 所有新增表带 `created_at`/`updated_at`。

---

## 6. 接口契约

### 6.1 `GET /api/meta`（公开）

```json
{
  "mode": "CLOUD",
  "version": "3.0.0",
  "authMode": "MULTI_USER",
  "capabilities": ["CHAT", "KNOWLEDGE", "DOWNLOAD"],
  "download": { "enabled": true, "latestVersion": "3.1.0" }
}
```

桌面端返回 `mode=DESKTOP`、`authMode=LOCAL_SINGLE_USER`，并包含 `LOCAL_WORKSPACE`、`COMMAND_EXEC`、`MCP_WRITE`、`LSP`、`APPROVAL`。

### 6.2 `GET /api/releases/latest?os=windows&arch=x64`

```json
{
  "version": "3.1.0",
  "url": "https://example.com/agentkey/AgentKey-3.1.0-x64.exe",
  "sha256": "...",
  "signature": "...",
  "notesUrl": "https://example.com/agentkey/releases/3.1.0",
  "minSupportedVersion": "3.0.0",
  "mandatory": false
}
```

### 6.3 桌面登录握手（预留，免费版不实现）

- `POST /api/auth/desktop-handoff`（网页端，已登录）→ `{ "code": "...", "expiresIn": 60 }`
- `POST /api/auth/desktop-exchange`（桌面端）→ `{ "accessToken": "..." }`
- 一次性、绑定设备指纹、60 秒过期。免费版桌面端完全本地运行，无需登录。

### 6.4 幂等

- `POST /api/agent/chat` 支持 `X-Client-Request-Id`。
- 相同 id 在有效期内返回同一 `runId`，不重复创建消息。

---

## 7. 测试与 CI

### 7.1 测试分层

| 层 | 范围 | 工具 | 运行频率 |
|---|---|---|---|
| 单元 | 路径校验、命令白名单、命令风险判定、工具参数、加密 | JUnit | 每次提交 |
| 集成 | ReAct、SSE、审批、DB/Flyway | Testcontainers + WireMock | 每次 PR |
| 前端单元 | store、工具函数、能力渲染 | Vitest | 每次 PR |
| E2E 浏览器 | 对话、知识库、下载（cloud）；本地读写、审批、diff、撤回（desktop） | Playwright | 每次 PR（核心用例） |
| 桌面壳冒烟 | 启动、健康检查、退出 | Playwright Electron / tauri-driver | 每次发布前 |
| 安装包矩阵 | 安装/卸载/升级/签名 | CI 三平台 | 每次发布 |
| 手工验收 | 杀毒、防火墙、权限弹窗、更新回滚 | Checklist | 每次发布 |

### 7.2 模式矩阵

核心用例按模式拆分，不要求同一用例在两种模式下都成立：

- `cloud`：注册/登录、对话、知识库、附件问答、下载页。
- `desktop`：本地 DB、本地工作区、命令执行、审批、diff、撤回。
- 双端共用用例（对话、知识库、设置）需在两种模式下各跑一遍。

### 7.3 流水线阶段

```
lint → build → unit → integration → frontend-unit → e2e(cloud) → e2e(desktop)
     → security-scan → package(3 OS) → smoke → release
```

**门禁建议：**

- 核心链路集成测试必须通过。
- 评测集回归不得低于基线（Phase 5 起）。
- Critical 依赖漏洞阻断发布。
- 安装包小于预算体积，否则告警。

---

## 8. 安全清单

| 项 | 当前 | 目标 |
|---|---|---|
| 默认密钥 | JWT/AES 有默认值 | 仓库无默认值，启动强校验 |
| 子进程环境 | 继承服务端环境 | 白名单清洗 |
| 软链接 | `normalize` | `toRealPath` + 边界校验 |
| 命令执行 | 宿主进程 | 桌面 OS 沙箱 + 云端不执行 |
| MCP | 默认开启、npx 未固定 | 默认关闭、固定版本、写操作审批 |
| 跨租户指标 | 全局聚合 | 按用户/项目过滤 |
| 匿名调试接口 | `test-stream` | 删除或鉴权 |
| 本地 API | 无 | 127.0.0.1 + 本地 token + Origin 校验 |
| 密钥存储 | 配置文件/DB | OS 凭据库 / 环境变量 / KMS |
| 注册与账号 | 开放注册 | 注册验证码或邮箱验证、找回密码（云端，Phase 1） |
| 审计 | 部分切面 | 按 run 可导出、可追溯 |
| 依赖安全 | 无扫描 | CI 阻断 Critical |

---

## 9. 可观测性

| 维度 | 目标 |
|---|---|
| Trace | run → LLM 调用 → 工具 → MCP 全链路，携带脱敏 `runId`/`userId` |
| Metrics | 运行数、成功率、p95 首 token、工具错误率、审批等待时长、token 成本 |
| Logs | 结构化 JSON，带 `runId`/`sessionId`，密钥与用户内容脱敏 |
| 前端 | 错误上报、关键漏斗埋点、版本分布 |
| 告警 | 模型失败率、DB/Redis 异常、安装包下载失败 |

时序指标全部持久化，避免重启归零；租户维度注意标签基数。

---

## 10. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| 内嵌 PG 生命周期或跨平台运行时失效 | 桌面端无法启动、僵尸进程、平台发布阻塞 | PR-06 做健康检查/恢复/进程树终止；每 OS/arch 构建并实测 pgvector，P2-08 与安装冒烟共同门禁 |
| 原始 V13 全新库顺序缺陷 | 云端或桌面全新安装失败 | Flyway 前置幂等预检或全新安装基线；同时测试全新库和存量库，不直接修改已执行迁移 |
| 安装包过大 | 下载转化低 | `jlink` 裁剪；按需下载组件；展示体积 |
| Windows 杀毒误报 | 用户流失 | 代码签名；提前送检；提供 SHA-256 |
| 桌面壳与侧车生命周期不稳 | 卡死/僵尸进程 | 单实例锁、进程树终止、健康检查、日志面板 |
| 双端耦合回潮 | 维护成本翻倍 | 能力声明 + 接口门面；禁止业务层判断模式 |
| 模型成本不可控 | 免费版烧钱 | 网页端 v1 仅 BYOK，平台不兜底；若未来提供平台 Key，必须先上线每日配额与注册验证码 |
| 网页端被期待写代码 | 定位模糊 | 网页端入口明确引导下载桌面端；`/api/meta` 能力声明驱动 UI |

---

## 11. 非目标（v1）

1. 商业化：支付、订阅、设备管理（免费版不做）；平台模型 Key 及配套配额不属于 v1，网页端采用 BYOK。
2. 云端代码执行沙箱（网页端不跑用户代码）。
3. 虚拟工作区、代码 diff、zip 导出（网页端不操作工作区，对话内仍可生成代码片段）。
4. 跨设备数据同步与两端账号打通（预留握手接口）。
5. 团队协作、组织权限、SSO。
6. 插件/规则市场。
7. 移动端、私有化部署套件。

以上能力保留接口与数据字段，但不在 v1 实现。

---

## 12. 发布验收标准

### 功能

- 网页端：注册（验证码或邮箱验证）、登录、找回密码、对话（可生成代码片段）、知识库、附件问答、下载桌面端。
- 桌面端：安装、首次启动、本地项目读写（含删除/撤回）、命令执行、审批、自动更新。
- 桌面端完全本地可用，不依赖云端账号。

### 质量

- 核心评测集通过率达到基线，且连续两个版本不下降。
- p95 首 token 时延与任务成功率有监控、有告警。
- 崩溃自由会话率达标；关键错误可定位到 `runId`。

### 安全

- 安全清单全绿；无 Critical 依赖漏洞。
- 安装包签名有效；本地 API 不可被非授权页面调用。
- 密钥不入库、不入日志、不入安装包。

### 合规

- 隐私政策、服务条款、第三方许可可访问。
- 遥测默认脱敏且可关闭；用户可导出/删除云端账号数据。

---

## 13. 执行顺序与 PR 拆分

### 依赖关系

```
P0 基线 / DB 选型 spike / 能力声明（可并行）
  └─ P1 能力声明 + /api/meta + 前端适配 + 下载页 + 账号安全
        ├─ P2 桌面运行时（浏览器可测）
        │     ├─ P3 桌面壳 + 安装 + 更新
        │     └─ P4 沙箱 + 可靠性
        └─ P5 评测 + 索引 + LSP（可并行）
```

### 建议的首批 PR

| PR | 内容 | 依赖 |
|---|---|---|
| PR-01 | **已完成**：密钥红线 + 环境变量清洗 + realpath + 匿名接口收敛等 12 项安全加固（完成批注见 Phase 0） | 无 |
| PR-02 | **已完成（未提交）**：双路线实测 + 内嵌 PG 选型 + 实测报告 + PR-06 文件清单（见 4.1） | 无 |
| PR-03 | **已完成（未提交）**：`DeploymentMode` + `CapabilityService` + `/api/meta`（完成批注见 Phase 1） | 无 |
| PR-04 | **已完成（未提交）**：`ToolRegistry` 能力门控 + 控制器按能力收敛（完成批注见 Phase 1） | PR-03 |
| PR-05 | **已完成（未提交）**：前端 `meta` store + 能力路由 + 下载入口（完成批注见 Phase 1） | PR-03 |
| PR-06 | **已完成（未提交，P2-01/02/04）**：`application-desktop.yml` + 本地 DB 生命周期 + 排除 Redis（完成批注见 Phase 2） | PR-02、PR-04 |
| PR-07 | **已完成（未提交，P2-03/05）**：本地 token 认证 + 桌面单用户初始化（完成批注见 Phase 2） | PR-06 |
| PR-08 | 桌面壳骨架 + 侧车启动 + 健康检查 | PR-06 |
| PR-09 | 安装包 + 更新 + 三平台冒烟 | PR-08 |
| PR-10 | 运行状态机 + 审批持久化 + SSE 续传 | PR-04 |
| PR-11 | 评测集 + CI 门禁 | PR-04 |
| PR-12 | **已完成（未提交）**：网页端账号安全闭环（邮箱验证码、找回密码）（完成批注见 Phase 1） | PR-03 |
| PR-13 | 发布合规（隐私政策、服务条款、遥测开关、第三方许可） | PR-09 |

---

## 14. 阶段评审清单

每个阶段结束时，用同一组问题评审：

1. 这个阶段能否在不依赖下一阶段的情况下演示？
2. 新增能力是否有对应的自动化测试？
3. 是否引入了“只有云端/只有桌面”成立且未抽象的假设？
4. 是否更新了 STATUS/design/operations 与变更日志？
5. 是否产生新的安全面？是否已加入安全清单？
6. 是否可以独立回滚？数据是否需要迁移回滚方案？

---

> 本文是执行基线。后续每次范围变更，先改本文，再改代码。
