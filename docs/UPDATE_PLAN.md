# Lunar Gold（金月）未完成更新规划（v1.4 · 免费版）

> 更新日期：2026-11-09
> 本文只记录未完成工作、当前阻塞、后续任务和验收标准。
> 已完成任务与历史验收见 [COMPLETED_WORK.md](COMPLETED_WORK.md)；当前代码快照见 [STATUS.md](STATUS.md)。
> Windows 安装排障见 [WINDOWS_NSIS_TROUBLESHOOTING.md](WINDOWS_NSIS_TROUBLESHOOTING.md)。
> 本文按“阶段 + 出口标准”组织，不以日历工期作为承诺。

---

## 0. 当前位置与下一步

Phase 0～2、P3-00～03 已完成；P3-04 的 Windows 交互安装/卸载闭环、统一构建与 v0.1.0 预发布已完成，详细证据已归档。**本阶段只适配 Windows**，macOS/Linux 安装包按项目所有者决定暂缓。

**当前主线：P3-05 已发布 v0.1.1 基线，下一步做 0.1.2（版本号 + 体积裁剪）并用线上源复核 N-1 → N。**

1. ~~最新安装器最小回归~~ **已完成**：当前发布基线安装器为 `release/installer/LunarGold-Setup-0.1.0.exe`（371,662,112 字节，含定稿黑白图标与 `Lunar Gold Project` author 元数据），SHA-256 `3E085FAB3D709EE0CD44894F7BF10838B21F477DC0CD06A91971B921ED9A9022`，静默安装 → 首启 → 退出 → 卸载 **22 项检查全部通过**；GitHub `v0.1.0` 资产大小与本地产物一致（371,662,112 字节）。证据见 [COMPLETED_WORK.md](COMPLETED_WORK.md) 5.4～5.6。
2. 开源许可证已选定 **Apache-2.0** 并写入 `LICENSE`；GitHub 公开仓库已完成，**v0.1.1 已于 2026-11-10 作为正式 Release 发布**（非 prerelease，`/releases/latest` 指向 v0.1.1，含安装器、blockmap、`latest.yml`、`SHA256SUMS.txt`，哈希与本地一致）。
3. **SignPath Foundation 申请已被拒（2026-11-10）**：理由是项目缺少公开可见度信号（GitHub star/fork/贡献者、外部文章或社区讨论、机构背书），非质量判断；官方建议积累社区后重申，或改用其付费订阅。当前结论：**未签名正常发布，README 明示「未知发布者」提示与 SHA-256 校验方式**；签名待有可见度后重申或另选商业/Azure 方案，不再阻塞 P3-05。接入后由 [release.yml](../.github/workflows/release.yml) 自动签名并复核 Authenticode。
4. 代码侧直接进入 P3-05 自动更新；签名与 clean-machine 验收作为并行阻塞项跟踪，不阻塞自动更新开发。Phase 4、Phase 5 不是当前前置条件。

---

## 1. 不变的产品与技术边界

- **网页端**：注册/登录、对话、知识库 RAG、附件问答和桌面端下载；不操作本地工作区、不执行命令。
- **桌面端**：本地项目读写、命令执行、Git、MCP、LSP、审批、审查流和按轮撤回；完全本地、不强制登录。
- **双端共用**：同一套后端和 Vue 前端，通过 `DeploymentMode`、`CapabilityService` 与 `/api/meta` 区分能力。
- **免费版**：无支付、无订阅；网页端采用 BYOK。未来若提供平台 Key，必须同时上线配额和注册防滥用。
- **数据库**：云端继续使用 PostgreSQL；桌面端使用内嵌 PostgreSQL + pgvector；Redis 只属于云端。
- **不做两个前端**，不做两端账号打通与数据同步。

---

## 2. 剩余路线总览

| 阶段 | 未完成主题 | 出口标志 |
|---|---|---|
| P3-04 | Windows 签名发布、GitHub Release、陌生机器验收；其他平台安装包 | 签名有效，Release 下载可安装、首启、首个任务和卸载 |
| P3-05 | 自动更新与失败回滚 | N-1 → N 升级通过，失败不破坏旧版本和用户数据 |
| P3-06 | 崩溃诊断与经同意的上报 | 壳、Java、PG 故障可定位；脱敏、可关闭 |
| P3-07 | 三平台发布流水线 | 构建、安装包冒烟、签名、制品上传可追溯 |
| P3-08 | 发布合规 | 许可证、隐私、服务条款、遥测和第三方 NOTICE 随版本发布 |
| Phase 4 | 安全与可靠性 | 断网、重启、多实例、审批超时、模型 5xx 行为明确 |
| Phase 5 | Agent 质量与差异化 | 评测可重复，成功率和成本有基线，提示词变更有门禁 |

---

## 3. Phase 3 剩余任务：分发与发布

### P3-04：安装包、开源签名与 Release

| 工作 | 当前状态 / 下一步 | 验收 |
|---|---|---|
| 最新 Windows 安装器最小回归 | **已完成**（2026-11-10）；发布基线哈希 `3E085FAB…9022`，22 项检查全通过 | 安装、首启、卸载通过；目录、注册项、health 和进程状态正确 |
| Windows 应用与安装器签名 | **SignPath Foundation 申请已被拒**（2026-11-10，缺公开可见度信号）；改按未签名发布并在 README 明示提示，待可见度积累后重申或评估商业/Azure 证书 | Authenticode 为 `Valid`，发布者与实际证书一致，可信时间戳有效 |
| GitHub Release | **已完成**（2026-11-10）：`v0.1.1` 为正式 Release（非 prerelease），`/releases/latest` 指向它；资产为 `LunarGold-Setup-0.1.1.exe`（371,688,816 字节，SHA-256 `5ECB4371…6400B`）、`.blockmap`、`latest.yml`（声明 v0.1.1，sha512/size 与实际一致）、`SHA256SUMS.txt`。未签名声明与 SHA-256 已写入 Release 说明和 README | 从 Release 重新下载后哈希一致，干净机器安装/首启/首个任务/卸载通过 |
| macOS/Linux 运行时与安装包 | **暂缓**；项目所有者决定本阶段只适配 Windows | 恢复时每个 OS/arch 独立安装与首启通过，不外推 Windows 结果 |
| 图标与发布元数据 | **图标已完成**（2026-11-10）：黑白月亮线稿接入 `desktop/app-icon/icon.ico`，含 16–256 七档尺寸；`author` 已填。Release 文案元数据随 P3-08 补齐 | 安装器、快捷方式、卸载项和 Release 信息一致 |

#### GitHub + SignPath 免费开源路线

GitHub 托管和公开仓库标准 Actions runner可以免费，但 GitHub 不提供 Windows Authenticode 证书。SignPath Foundation **2026-11-10 已拒**（缺 star/fork/贡献者、外部文章、社区讨论或机构背书等可见度信号），待项目积累用户与讨论后重申；当前不为签名阻塞发布。

- [x] **选择开源许可证**：已选定 Apache-2.0 并写入仓库根目录 `LICENSE`；发布前仍需确认全部被打包组件满足 SignPath 的开源要求。
- [x] **建立 GitHub 公开仓库**：`https://github.com/nichengyv/lunar-gold` 已公开，`v0.1.0` 预发布已上传；默认分支与标签保护待按需开启。
- [x] **申请 SignPath Foundation**：2026-11-10 提交，同日被拒，理由为公开可见度不足（非质量判断），官方建议积累社区后重申或改用付费订阅。
- [ ] **积累可见度后重申**：发布正式版本、完善 README、在社区（V2EX / 少数派 / Reddit r/selfhosted 等）介绍项目后再次申请。
- [ ] **接入受信构建**：审核通过后按最小权限安装 SignPath GitHub App，关联指定仓库、项目和 GitHub Actions；私钥由签名服务管理，不导出、不入库。
- [ ] **签名应用 exe 和安装器**：在受信 Release 构建中签名并加可信时间戳，禁止只签外层安装器而遗漏主程序。
- [ ] **验证签名与下载产物**：检查 `Get-AuthenticodeSignature`、证书链、发布者和时间戳；Release 同时提供 SHA-256。
- [ ] **验收 SmartScreen 实际表现**：OV 级签名不等于立即获得 SmartScreen 信誉，不承诺消除所有提示。
- [ ] **准备备选方案**：已达可见度门槛前按未签名分发，README 明示「未知发布者」提示并提供 SHA-256；正式大规模分发再评估商业证书或 Azure Trusted Signing。自签名不作为信任方案。

SignPath 的 Windows 方案不覆盖 macOS Developer ID 和 Apple 公证；macOS 的费用与凭据需单独评估。

### P3-05：自动更新与失败回滚

- [x] 接入 `electron-updater`、GitHub Release 清单和 blockmap；开发态不自动更新。
  - `desktop/updater.js` 统一封装检查/下载/校验/备份/安装/回滚；`desktop/package.json` 增加 `build.publish`，electron-builder 已产出 `latest.yml` + `.blockmap`。
  - 构建脚本补写 `resources/app-update.yml`（`--dir` + `--prepackaged` 两段式构建不会自动生成，缺失会导致装好的应用读不到更新源）。
- [x] 默认采用检查并提示，不强制用户立即更新。
  - 首启延迟 8 秒检查，发现新版弹窗选择「下载更新 / 稍后提醒」；下载完成后可选「立即重启安装 / 退出时安装」。
- [x] 下载后校验版本、签名和哈希，校验失败不得安装。
  - sha512 由 electron-updater 强校验；`LUNARGOLD_REQUIRE_SIGNATURE=1` 时额外校验 Authenticode（SignPath 接入后开启）。
- [x] 升级前备份数据目录；数据库迁移失败不得删除工作区、会话、知识库或 uploads。
  - 备份整目录到 `~/.lunargold/backup/<旧版本>-<时间戳>`，保留最近 2 份；安装前写 `upgrade-state.json` 标记，首启成功自动清理，失败保留证据目录 `*.failed-<时间戳>`。
  - 工作区为用户自选目录、`uploads/` 在安装目录，升级与回滚均未删除。
- [x] 验证 N-1 → N 的下载、退出、安装、重启和首启。
  - 本地 generic 源实测 `0.1.1 → 0.1.2`：下载 → 备份 → 静默安装 → 重启 → 首启成功 → 标记清理；`local-token` 哈希、`db` 数据、用户 canary 文件均完整。
- [x] 模拟下载中断、磁盘不足、签名错误、安装失败和迁移失败，确认旧版本可恢复。
  - 哈希错误：`sha512 checksum mismatch`，不安装，版本不变。
  - 签名错误：`NotSigned`，不安装，版本不变。
  - 磁盘不足：下载前预检拦截（`磁盘空间不足`），不下载不安装。
  - 迁移/启动失败：人为破坏侧车 jar 触发回滚，自动恢复数据备份并静默装回 `0.1.1`；`local-token` 哈希与 canary 一致，`data2.failed-*` 保留现场，陈旧升级标记自动清理。
- [x] 升级、回滚结束后不得残留无关 Lunar Gold/Java/PostgreSQL 进程。
  - 正常退出与回滚后检查：`lunar-gold` / `java` / `postgres` 均 0 残留。

**出口**：真实 N-1 升级和至少一类失败回滚通过，用户数据完整。**代码侧已完成（2026-11-10）**，待项目所有者发布 `v0.1.1` 正式 Release（含 `latest.yml`）后，用线上源再跑一次 N-1 → N 复核；SignPath 签名通过后打开签名强制校验。

### P3-06：崩溃诊断与上报

- 覆盖 Electron 主进程、渲染进程、Java 侧车和内嵌 PostgreSQL。
- 记录版本、OS、错误码和必要日志；本地诊断可查看、可导出。
- 默认只保留本地信息；远程上传必须征得同意并可关闭。
- 不上传源码、对话、知识库、模型 Key 或敏感路径；上报字段采用白名单。
- 验证壳崩溃、Java 启动失败、PG 初始化失败和端口异常均可定位。

**出口**：四类故障都有可读诊断；远程上报脱敏且可关闭。

### P3-07：GitHub Actions 发布流水线

```text
lint / build / test
  → 准备 PG、JRE 和前端产物
  → Windows / macOS / Linux 分别打包
  → unpacked 与安装包冒烟
  → 签名 / 公证
  → 上传预发布 Artifacts
  → 标签或手动确认后发布 GitHub Release
```

- 公开仓库使用标准 GitHub-hosted runner；私有仓库或自托管 runner 的成本单独评估。
- **现状**：[release.yml](../.github/workflows/release.yml) 的 `push.tags` 触发在 CI 打包链路冒烟通过前临时关闭（仅留 `workflow_dispatch`），发布使用本地已验证产物 + 手工上传，避免同名标签另起 CI 草稿 Release 冲突；P3-07 打通后恢复标签触发。
- 发布凭据只放 CI 加密凭据或签名服务，限制 `GITHUB_TOKEN` 和第三方 App 权限。
- 发布任务只由版本标签或手动触发，不能随普通 push 自动公开。
- 每个制品可追溯到 commit、工作流运行、测试结果和 SHA-256。
- 安装器、blockmap 和调试元数据不提交进 Git；`release/` 继续作为本地忽略目录。
- **体积预算**：当前安装器 354 MiB，主要来自 Electron 运行时 + 242.9 MB 后端 jar（内含 PG 运行时、`openai-java-core`）+ 49.4 MB JRE，桌面壳代码仅约 15 KB。0.1.2 起做依赖裁剪：jar 内多余的 zonky Windows PG 二进制（21.9 MB）、非 Windows 的 netty native 库（7.7 MB）、electron-builder 语言包（`electronLanguages`），目标约 320 MB；不为体积更换内嵌 PG 或私有 JRE 架构。
- CI 冒烟必须检查真实目录、health、进程和卸载结果，不能只认启动器退出码。

### P3-08：发布合规

- 选择并发布项目开源许可证，整理 Electron、JDK、PostgreSQL、pgvector 及其他依赖的许可证与 NOTICE。
- 生成 SBOM，记录发布包中的直接和运行时依赖。
- 发布隐私政策、服务条款、离线边界、模型 Key 与费用说明。
- 说明卸载后程序、会话、知识库、工作区和诊断数据的保留/删除行为。
- 遥测默认脱敏、可关闭；远程诊断上传需单独同意。
- README 提供下载、安装、首启、校验和基础故障排查。

**Phase 3 出口**：陌生机器可完成首个任务；升级和回滚可用；数据可备份；合规文件随版本发布。

---

## 4. Phase 4：安全与可靠性加固

**目标**：从“能跑”到“敢开放”。开放给真实用户操作不可信项目时，P4-01 和 P4-08 是高优先级。

| ID | 任务 | 验收 |
|---|---|---|
| P4-01 | OS 级沙箱 | Windows Job Object；macOS Seatbelt；Linux Landlock/cgroups |
| P4-02 | 运行状态机与 `AgentRunService` | 使用下一条未占用迁移版本，覆盖 QUEUED/RUNNING/WAITING_APPROVAL/SUCCEEDED/FAILED/CANCELLED |
| P4-03 | 审批与运行状态持久化 | 桌面重启后待审批可恢复；云端共享 run/stop 状态，不实现云端审批 |
| P4-04 | SSE 可靠性 | 心跳、事件 id、断线续传；审批不占用主流生命周期 |
| P4-05 | 幂等与防重 | 相同 `clientRequestId` 不重复创建消息或运行 |
| P4-06 | 模型调用韧性 | 超时、重试退避、熔断、按 Provider 隔离 |
| P4-07 | 上下文与成本 | token 计量、预算上限、大结果落文件 |
| P4-08 | 安全回归 | 路径逃逸、命令注入、越权、跨租户均有测试 |

**出口**：断网、重启、双实例、审批超时、模型 5xx 五类场景均有明确行为；安全清单全绿。

---

## 5. Phase 5：Agent 质量与差异化

**目标**：让“可控性”成为可证明的质量优势，可与发布和 Phase 4 工作并行，但应先建立评测基线。

| ID | 任务 | 验收 |
|---|---|---|
| P5-01 | 提示词外置与版本化 | 可热更新/回滚，按场景配置 |
| P5-02 | 评测集 | 覆盖工具选择、文件编辑/删除、命令审批、RAG、拒答 |
| P5-03 | 评测门禁 | 完成率、轮次、token、时延有基线，回归即失败 |
| P5-04 | 代码索引 v1 | tree-sitter 解析、符号索引和 repo map |
| P5-05 | LSP 诊断闭环 | 编辑后自动取诊断并反馈给模型 |
| P5-06 | 验证回路 | 修改后自动运行 verify，失败回到循环 |
| P5-07 | RAG 增强 | 混合检索、rerank、引用和离线量化 |
| P5-08 | 并行只读工具 | 只读工具并行；写操作仍串行并审批 |

**出口**：评测可重复运行，核心任务成功率与成本有基线，提示词变更必须过评测。

---

## 6. 后续数据库与接口契约

### 6.1 数据库迁移

V15 已被邮箱唯一索引占用，Phase 4 的运行状态表应使用 **V16** 或下一条实际未占用版本，不再沿用旧规划中的 `V15__add_agent_run.sql`。

| 版本 | 内容 | 说明 |
|---|---|---|
| V16 | `agent_run` / `agent_run_event` | 运行状态与事件流，实施前再次检查迁移目录 |

原则：

1. 不直接修改已执行的 V1～V15，避免 Flyway 校验失败。
2. 云端与桌面端共用 PostgreSQL 方言和 `db/migration`。
3. 同时覆盖全新库与存量库；大版本升级前自动备份数据目录。
4. 新增表带 `created_at` / `updated_at`。

### 6.2 版本清单

`GET /api/releases/latest?os=windows&arch=x64` 的目标契约：

```json
{
  "version": "3.1.0",
  "url": "https://github.com/<org>/<repo>/releases/download/.../LunarGold-Setup.exe",
  "sha256": "...",
  "signature": "...",
  "notesUrl": "https://github.com/<org>/<repo>/releases/...",
  "minSupportedVersion": "3.0.0",
  "mandatory": false
}
```

字段示例不是实际发布地址；实施时由 GitHub Release 和真实版本生成，不能写死虚构 URL。

### 6.3 请求幂等

- `POST /api/agent/chat` 支持 `X-Client-Request-Id`。
- 相同 id 在有效期内返回同一 `runId`，不重复创建消息。

---

## 7. 测试与发布门禁

| 层 | 范围 | 工具 / 频率 |
|---|---|---|
| 单元 | 路径、命令风险、工具参数、加密 | JUnit；每次提交 |
| 集成 | ReAct、SSE、审批、DB/Flyway | Testcontainers + WireMock；每次 PR |
| 前端单元 | store、工具函数、能力渲染 | Vitest；每次 PR |
| E2E | cloud 对话/知识库/下载；desktop 本地读写/审批/diff/撤回 | Playwright；核心用例每次 PR |
| 桌面壳冒烟 | 启动、健康、退出、单实例 | Playwright Electron 或等价方案；发布前 |
| 安装包矩阵 | 安装、卸载、升级、签名 | 对应 OS runner；每次发布 |
| 手工验收 | 杀毒、防火墙、权限弹窗、更新回滚 | Checklist；每次发布 |

门禁：

- 核心链路集成测试必须通过。
- Critical 依赖漏洞阻断发布。
- Phase 5 起，评测回归不得低于基线。
- 安装包超出体积预算时告警并记录原因。
- 双端共用用例分别在 cloud 和 desktop 模式运行，不要求同一用例在两种模式下都成立。

---

## 8. 剩余安全与可观测性工作

| 项 | 当前差距 | 目标 |
|---|---|---|
| 命令执行 | 已有白名单与应用层校验，无完整 OS 沙箱 | 桌面 OS 沙箱，云端不执行 |
| 运行与审批状态 | 仍有进程内存状态 | run 持久化、重启恢复、云端可共享 run/stop |
| 本地密钥与 token | 数据目录文件与权限收紧 | 评估 OS 凭据库，保持无额外原生依赖时的安全替代 |
| 审计 | 部分链路可追踪 | 按 run 导出、可追溯 |
| 依赖安全 | 历史扫描已完成，持续门禁待发布流水线补齐 | CI 阻断 Critical |
| Trace / Metrics / Logs | 需要跨壳、Java、PG 和更新链路串联 | 脱敏 `runId`/`sessionId`，关键指标持久化 |
| 告警 | 发布故障和模型失败缺少统一告警 | 模型、DB/Redis、安装与下载失败可告警 |

远程诊断和指标标签不得泄露用户内容；租户标签注意基数。

---

## 9. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| 跨平台 PG/pgvector 或 JRE 失效 | 对应平台无法发布 | 每 OS/arch 独立构建和安装冒烟 |
| 安装包过大 | 下载和更新成本高 | jlink、体积预算、展示大小，必要时再评估按需组件 |
| Windows 杀毒或 SmartScreen 提示 | 用户不敢安装 | 有效签名、提前送检、SHA-256、真实下载验收 |
| 更新或迁移失败 | 应用不可用、数据损失 | 升级前备份、签名校验、失败回滚 |
| 壳与侧车生命周期不稳 | 卡死或僵尸进程 | 单实例、健康检查、整树终止、诊断日志 |
| 遥测泄露隐私 | 合规与用户信任风险 | 默认本地、上传同意、字段白名单、可关闭 |
| 双端耦合回潮 | 维护成本翻倍 | 能力声明与接口门面，禁止业务层散落模式判断 |
| 模型成本不可控 | 免费版产生平台成本 | 网页端仅 BYOK；平台 Key 必须先有配额和防滥用 |

---

## 10. 非目标（v1）

1. 支付、订阅、设备管理和平台模型 Key 成本兜底。
2. 云端代码执行沙箱；网页端不运行用户代码。
3. 网页端虚拟工作区、代码 diff 和 zip 导出。
4. 跨设备同步和两端账号打通；握手接口仅预留。
5. 团队协作、组织权限和 SSO。
6. 插件或规则市场。
7. 移动端和私有化部署套件。

---

## 11. 发布验收标准

### 功能

- 网页端完成注册、登录、找回密码、对话、知识库、附件问答和桌面下载。
- 桌面端完成安装、首启、本地项目读写、删除/撤回、命令执行、审批和自动更新。
- 桌面端不依赖云端账号。

### 质量

- 核心评测达到基线，连续两个版本不下降。
- p95 首 token、任务成功率和关键错误可监控、可定位。
- 安装、更新、回滚和异常场景均有真实验收记录。

### 安全

- 安全清单全绿，无 Critical 依赖漏洞。
- 安装包签名有效，本地 API 不可被非授权页面调用。
- 密钥不入库、不入日志、不入安装包。

### 合规

- 开源许可证、隐私政策、服务条款和第三方 NOTICE 可访问。
- 遥测脱敏且可关闭；云端账号数据可导出/删除。

---

## 12. 执行顺序与后续 PR

```text
P3-04 新包回归 → 开源许可证 / GitHub / SignPath → Release 验收
  → P3-05 自动更新与回滚
  → P3-06 / P3-07 / P3-08 可拆分并行
  → Phase 4 安全可靠性
Phase 5 评测与质量工作可并行，但先建立基线
```

| 后续 PR | 内容 | 依赖 |
|---|---|---|
| PR-09a | 新安装器回归、LICENSE、GitHub Release 与 SignPath 接入 | 当前 P3-04 基线 |
| PR-09b | 自动更新、N-1 升级与失败回滚 | PR-09a |
| PR-09c | 崩溃诊断、发布流水线和合规 | 可按模块拆分，与 PR-09b 协调 |
| PR-10 | 运行状态机、审批持久化、SSE 续传和幂等 | 既有能力门控；迁移使用 V16 |
| PR-11 | 评测集与 CI 质量门禁 | 既有双端基线 |
