﻿# Lunar Gold（金月）未完成更新规划（v1.4 · 免费版）

> 更新日期：2026-11-14
> 本文只记录未完成工作、当前阻塞、后续任务和验收标准。
> 已完成任务与历史验收见 [COMPLETED_WORK.md](COMPLETED_WORK.md)；当前代码快照见 [STATUS.md](STATUS.md)。
> Windows 安装排障见 [windows-nsis.md](troubleshooting/windows-nsis.md)。
> 本文按“阶段 + 出口标准”组织，不以日历工期作为承诺。

---

## 0. 当前位置与下一步

Phase 0～2、P3-00～03 已完成；Windows 安装/卸载、GitHub Release、自动更新与回滚、本地诊断和合规产物的主体工作已交付，历史证据见 [COMPLETED_WORK.md](COMPLETED_WORK.md)。Phase 4 已落地运行状态、审批持久化、Windows 沙箱、模型韧性与成本预算，剩余风险见第 4 节。**当前只适配 Windows**，macOS/Linux 安装包继续暂缓。

**最终产品形态：Lunar Gold 是本地优先、可控、可验证的 AI 编程工作台，桌面端是首个完整客户端。** Agent Runtime 只是内部的基础执行层之一，不是独立产品类型，也不是差异化本身；现有 ReAct 循环继续保留，差异化由任务成功率、可控性、验证、恢复与使用体验共同证明。

**当前产品主线：Phase 5，按双轨推进，功能增强仍是每批交付的主体。**

1. **智能体能力增强轨道**：P5-13 真实 ReAct 端到端评测与完成控制、P5-05 LSP 诊断闭环、P5-06 验证回路、P5-04 代码索引（repo map + 符号索引/增量缓存）、P5-07 RAG 混合检索/RRF/引用与 P5-08 并行只读工具均已完成；下一步继续 P5-07 模型 reranker 与真实语料量化、P5-04 收益量化与可选持久缓存，不取消既有功能增强。
2. **工作台体验增强轨道**：P5-14 任务级 ChangeSet 与交付已完成基础交付（矩阵见第 5 节），P5-11-1 前端 Vitest/Playwright 基线、P5-11-2 体验可观测性、P5-11-3 长会话性能、P5-10-3 长任务进度、P5-10-2 审批效率、P5-10-4 键盘与无障碍与 P5-09 视觉体系已落地；下一步转向功能增强收益量化，体验项只做回归与缺陷修复。
3. **并行 Windows 发布收尾**：签名、诊断 ZIP 导出人工复核、干净机器完整任务验收和 P3-08 所有者确认项单独跟踪，不阻塞 Phase 5。
4. **后续多入口候选**：本地 API v1 → CLI → IDE 薄插件，复用同一后端能力；不属于当前 Phase 5 出口，不 fork VS Code。

---

## 1. 不变的产品与技术边界

- **产品类型**：对外统一称“AI 编程工作台”；“Agent Runtime / 智能体执行引擎”只用于描述模型、工具、审批、执行、状态和验证等内部机制。
- **执行架构**：保留 ReAct 作为基础循环，以计划、代码上下文、LSP、Verify、无进展检测和评测补强；没有评测证据时，不为了架构名词改做多智能体或复杂状态图。
- **明确不做**：不 fork VS Code，不建设“万物皆插件”的平台或插件市场，不做泛桌面自动化；模型、MCP、LSP 与未来客户端入口可以扩展，执行约束、审批、验证和恢复保持统一。
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
| Phase 3 发布收尾 | Windows 签名、诊断导出人工复核、干净机器完整任务、合规所有者确认 | Release 可安装、首启、执行并验证首个编程任务、审查与撤回、卸载 |
| P3-07 | Windows 发布流水线 | 构建、安装包冒烟、签名、制品上传可追溯；其他平台恢复时另立计划 |
| Phase 4 剩余风险 | 桌面重启恢复、双实例审批互斥、完整安全链、真实预算与并发验证 | 剩余场景行为明确且有对应验收，不用已完成单测代替端到端结论 |
| Phase 5 | 智能体能力增强 + 工作台体验增强 | 真实 ReAct 可评测，修改有诊断与验证，任务可审查/恢复/撤回，既有功能增强有量化收益 |
| Phase 6 候选 | 本地 API、CLI、IDE 薄插件与信息架构再评估 | 不同入口复用同一后端；不进入当前 Phase 5 出口 |

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

**出口**：真实 N-1 升级和至少一类失败回滚通过，用户数据完整。**代码侧已完成（2026-11-10）**；**线上复核已通过（2026-11-11）**：安装态 v0.1.1 自动检测到 v0.1.2 → 差分下载 220 MB → sha512 校验通过 → 备份到 `~/.lunargold/backup/0.1.1-<ts>` → 静默安装并重启 → 升级后首启 3 秒稳定检查通过，数据目录 40.46 MB / 1064 文件与升级前完全一致，无残留进程。SignPath 签名通过后再打开签名强制校验。

### P3-06：崩溃诊断与上报

- [x] 覆盖 Electron 主进程、渲染进程、Java 侧车和内嵌 PostgreSQL：主进程异常与渲染崩溃/无响应/加载失败进入统一错误卡片；主进程硬崩溃通过会话标记在下次启动补提示；Java/PG/端口错误按完整日志尾部分类。
- [x] 记录版本、OS、错误码和必要日志；本地诊断可查看、可导出。`desktop/diagnostics.js` 生成白名单 `report.json`，错误卡片可展开脱敏日志，ZIP 只含报告、`sidecar-tail.log` 和 `updater-tail.log`。
- [x] 默认只保留本地信息，当前没有任何远程上传入口或网络请求；导出需用户自行选择保存位置。
- [ ] 远程上传暂缓；未来若增加服务端，必须单独征得同意、可关闭，并继续沿用白名单与脱敏规则。
- [x] 不复制源码、对话、知识库、数据库、模型 Key、`local-token`、`aes.key` 或敏感路径；通用密钥/令牌/路径脱敏外，`LocalTokenService` 的无标记随机令牌日志整段移除。合成金丝雀与真实 PG 启动日志均验证令牌未进入 ZIP。
- [x] 可重复故障验证：主进程异常标记、渲染崩溃卡片、端口分类及五类卡片的重试/导出/退出动作通过模拟测试；真实损坏 jar 得到 `JAVA_START_FAILED`，真实缺失 PG 运行时得到 `PG_INIT_FAILED`，测试后无新增 Java 残留进程。
- [x] 打包态真实窗口复核（2026-11-11）：把打包态 unpacked 复制到工作区隔离目录并移除 sidecar jar 后真实启动，界面实际渲染中文诊断卡片（`ELECTRON_MAIN_START_FAILED`），报告写入隔离数据目录且绝对路径脱敏为 `<PATH>`；点击「重试启动」新增 `JAVA_START_FAILED` 报告并重新渲染卡片，进程保持存活；点击「退出」进程结束且无残留。数据目录、`APPDATA`、`LOCALAPPDATA`、`TEMP`、`user-data-dir` 均指向隔离目录，未触碰真实安装目录与 `%USERPROFILE%\.lunargold`。
- [ ] 诊断包 ZIP 的真实窗口导出待人工确认：保存对话框可正常弹出（标题「导出 Lunar Gold 诊断包」，带默认文件名），但本机窗口自动化无法驱动该系统原生对话框完成保存（标题匹配、UI Automation、鼠标点击、键盘输入均已尝试），ZIP 落盘与内容目前只由轻量测试覆盖。人工点一次「导出诊断包」并解压核对即可关闭。

**出口**：四类故障都有可读诊断；远程上报脱敏且可关闭。**本地诊断已通过打包态真实窗口复核；仅剩 ZIP 导出的人工确认与按计划暂缓的远程上报子项。**

### P3-07：GitHub Actions Windows 发布流水线

```text
lint / build / test / eval gate
  → 准备 PG、JRE 和前端产物
  → Windows 打包
  → unpacked 与安装包冒烟
  → Windows 签名（证书就绪后）
  → 上传预发布 Artifacts
  → 标签或手动确认后发布 GitHub Release
```

macOS/Linux 恢复时分别补充独立打包、签名/公证与首启验收，不属于当前出口。

- 公开仓库使用标准 GitHub-hosted runner；私有仓库或自托管 runner 的成本单独评估。
- **现状**：[release.yml](../.github/workflows/release.yml) 的 `push.tags` 触发在 CI 打包链路冒烟通过前临时关闭（仅留 `workflow_dispatch`），发布使用本地已验证产物 + 手工上传，避免同名标签另起 CI 草稿 Release 冲突；P3-07 打通后恢复标签触发。
- 发布凭据只放 CI 加密凭据或签名服务，限制 `GITHUB_TOKEN` 和第三方 App 权限。
- 发布任务只由版本标签或手动触发，不能随普通 push 自动公开。
- 每个制品可追溯到 commit、工作流运行、测试结果和 SHA-256。
- 安装器、blockmap 和调试元数据不提交进 Git；`release/` 继续作为本地忽略目录。
- **体积预算**：当前安装器 354 MiB，主要来自 Electron 运行时 + 242.9 MB 后端 jar（内含 PG 运行时、`openai-java-core`）+ 49.4 MB JRE，桌面壳代码仅约 15 KB。0.1.2 起做依赖裁剪：jar 内多余的 zonky Windows PG 二进制（21.9 MB）、非 Windows 的 netty native 库（7.7 MB）、electron-builder 语言包（`electronLanguages`），目标约 320 MB；不为体积更换内嵌 PG 或私有 JRE 架构。
- CI 冒烟必须检查真实目录、health、进程和卸载结果，不能只认启动器退出码。

### P3-08：发布合规

- [x] 开源许可证：Apache-2.0（`LICENSE`，P3-04 已完成）。
- [x] 第三方组件清单：新增根目录 `NOTICE`，覆盖 Electron / Chromium / Node.js / electron-updater、Temurin JDK 21 运行时、PostgreSQL 16.11 + pgvector 0.8.7、前端依赖和 243 个 Maven 组件；按许可证分组并注明测试作用域差异。
- [x] SBOM：`SBOM.json`（Java，CycloneDX 1.6，243 个组件）+ `SBOM.frontend.json` + `SBOM.desktop.json` + `SBOM.runtime.json`（Electron、裁剪 JRE、内嵌 PostgreSQL 16.11、pgvector 0.8.7，含哈希与外部引用）；四份均由 `scripts/build-compliance.ps1` 生成并校验可解析、组件非空。
- [x] 隐私政策重写 `PRIVACY.md`（2026-11-11）：本地数据目录逐项说明；本地诊断的报告位置、10 份上限、白名单与脱敏、导出内容、无自动上传；自动更新访问 GitHub Releases；模型 / 向量服务商、内置联网工具（Tavily、wttr.in、Open-Meteo、网页抓取）与 MCP 的网络边界；卸载保留 `%USERPROFILE%\.lunargold`、删除安装目录内 uploads。
- [x] 服务条款新增 `TERMS.md`：Apache-2.0 授权不变、审批与备份责任、BYOK 费用、第三方服务、无担保与责任限制、Web 版账号与终止。
- [x] README 扩充：SHA-256 校验命令、首启说明、自动更新行为、卸载保留行为、基础故障排查、合规文件入口；修正原先「sends nothing anywhere」的不准确表述。
- [x] 文档语言：中文为主，英文对照版保留在 `README.en.md`、`PRIVACY.en.md`、`TERMS.en.md`、`NOTICE.en`，各文档首页互相链接；`SBOM.json` 保持机器可读的英文标识。
- [x] 合规产物可复现（2026-11-11）：新增 `scripts/build-compliance.ps1` 与 `scripts/licenses-config.xml`，输出到忽略的 `build/legal`（12 个文件，含 `THIRD-PARTY-LICENSES.txt`）；`pom.xml` 补齐项目 URL/SCM 并固定 CycloneDX 2.9.3、license-maven-plugin 2.7.1；任一必需文件缺失、SBOM 不可解析或许可合集为空即中止。安装器构建前自动生成并逐文件校验 `resources/legal` 哈希，CI 严格校验后显式上传。
- [ ] 所有者确认项（不阻塞文档发布）：`TERMS.md` 采用中华人民共和国法律与维护者住所地法院管辖，需确认；Web 版是否对外托管、以及该部署的数据主体联系方式需在真正上线前补充。
- [x] 发布流程：`release.yml` 已把 `build/legal` 复制为 `release/legal`、逐项校验并显式上传（`fail_on_unmatched_files: true`）；下一次 Release 将随附 `SHA256SUMS.txt`、`NOTICE`、`PRIVACY.md`、`TERMS.md`、四份 SBOM 与 `THIRD-PARTY-LICENSES.txt`。
- [ ] 发布卫生（本轮盘点发现，未修改代码）：Spring Boot 4 的 `spring-boot-starter-test` 把 `junit-jupiter` / `junit-platform` 以 compile 作用域带入，导致发布 jar 内含约 2 MB 测试框架；后续可在 pom 中显式排除或改用其他测试启动器，并复核测试仍可运行。

**Phase 3 出口**：陌生机器可完成一个标准编程 fixture 任务：修改文件 → 必要审批 → 验证 → 审查 diff → 撤回；升级和回滚可用，数据可备份，合规文件随版本发布。P5-06 验证回路与 P5-14 任务级交付已落地，审查与撤回可通过 ChangeSet API/看板执行；前端关键路径已由 P5-11-1 的 Playwright 用例在浏览器层用 API 仿真覆盖，但真实后端 + 真实模型的干净机器完整任务验收仍需人工执行，记录时不得把仿真 E2E 写成真机验收已通过。

---

## 4. Phase 4：安全与可靠性加固

**目标**：从“能跑”到“敢开放”。开放给真实用户操作不可信项目时，P4-01 和 P4-08 是高优先级。

| ID | 任务 | 验收 | 状态 |
|---|---|---|---|
| P4-01 | OS 级沙箱 | Windows Job Object；macOS Seatbelt；Linux Landlock/cgroups |
| P4-02 | 运行状态机与 `AgentRunService` | 使用下一条未占用迁移版本，覆盖 QUEUED/RUNNING/WAITING_APPROVAL/SUCCEEDED/FAILED/CANCELLED |
| P4-03 | 审批与运行状态持久化 | 桌面重启后待审批可恢复；云端共享 run/stop 状态，不实现云端审批 |
| P4-04 | SSE 可靠性 | 心跳、事件 id、断线续传；审批不占用主流生命周期 |
| P4-05 | 幂等与防重 | 相同 `clientRequestId` 不重复创建消息或运行 |
| P4-06 | 模型调用韧性 | 超时、重试退避、熔断、按 Provider 隔离 |
| P4-07 | 上下文与成本 | token 计量、预算上限、大结果落文件 |
| P4-08 | 安全回归 | 路径逃逸、命令注入、越权、跨租户均有测试 |

**出口**：断网、重启、双实例、审批超时、模型 5xx 五类场景均有明确行为；安全清单全绿。

### 4.1 已落地（代码与单元验证完成）

| ID | 落地内容 | 验证边界 |
|---|---|---|
| P4-02 | `AgentRun` 实体 + `AgentRunService` 状态机；V16 建 `agent_run` / `agent_run_event`；非法迁移抛 `IllegalStateException`；启动时把遗留 `QUEUED/RUNNING` 标为 `FAILED/PROCESS_RESTARTED` | `AgentRunServiceTest` 8 项通过；**出境验收（2026-11-12）**：`FlywayV18MigrationAcceptanceTest` 在真实 PostgreSQL 16.11 上完成 V1~V18 全量迁移、重复迁移 0 条、`validate` 通过；`RestartRecoveryAndMultiInstanceAcceptanceTest` 验证遗留 RUNNING/QUEUED → FAILED + `PROCESS_RESTARTED` 事件 |
| P4-03 | V17 `tool_approval`；`ToolApprovalRegistry` 数据库权威 + 内存加速；启动恢复待审批、过期直接 `REJECTED`、恢复“本会话总是允许” | `ToolApprovalRegistryTest` 6 项、`ToolApprovalRegistryPersistenceTest` 4 项通过；**出境验收**：真实库重启恢复（未过期可批准、过期即拒绝、会话总是允许恢复）与双实例决策落库可见均通过；**已知边界**：两实例各持内存 future，跨实例唤醒与重复决策互斥未实现 |
| P4-05 | `clientRequestId` 全链路；`create()` 返回 `created` 标记，并发命中已存在记录时不重复建消息、不重复调度 | 单元级；并发压测未做 |
| P4-04 | SSE 自增事件 id、15s 心跳注释帧（连接结束即取消）、`GET /agent/runs/{runId}/events?sinceSeq=`；`tool_call` / `tool_result` 落事件表以便重放 | 后端能力完成；P5-10-1 已用 runId 全量重放 + 正文检查点实现前端刷新恢复与自动重连，人工断网 10s 复核仍待补 |
| P4-06 | Provider 级熔断；瞬时错误指数退避重试（已输出 chunk 不重试）；流式空闲超时 + 非流式总时长兜底；fallback 改为显式配置 | `ProviderCircuitBreakerTest` 5 项、`SpringAiAgentClientResilienceTest` 4 项通过；**出境验收**：`ProviderOutageAcceptanceTest` 6 项用本机 stub HTTP server 真实收到 500/429/连接拒绝/流式空闲超时/首片后卡死，验证退避重试、已输出不重试、错误只回调一次、熔断后不再发请求；**观察**：Spring AI SDK 自身带内部重试，原始 HTTP 请求数约为外层尝试的 4 倍（重试放大） |
| P4-01 | 新增 JNA core 依赖，`WindowsJobObjectSandbox` 自声明 kernel32 Job Object API：`KILL_ON_JOB_CLOSE` + 单进程内存上限 + Job 内进程数上限；`CommandService` 在 `pb.start()` 后立即 attach，结束时 `close()` 由内核回收整棵进程树；desktop 的 `server.address` 收紧到 `127.0.0.1` | `WindowsJobObjectSandboxTest` 3 项通过（含真实进程 attach→close→被回收）；`CommandService*Test` 30 项回归通过；macOS Seatbelt / Linux Landlock 未实现 |
| P4-07 | `agent_run` 增加 `prompt_tokens/completion_tokens/total_tokens`（V18）；`AgentStreamCallback.onUsage` 逐轮累加，终态只落库一次；超 `agent.budget.max-tokens-per-run` 硬中断并映射 `TOKEN_BUDGET_EXCEEDED`；超大工具结果落盘到 `~/.lunargold/tool-results` 只回填预览 + 路径；前端追踪面板展示会话 Token | 后端编译通过、`ReActAgentService` 预算分支未做真实模型消耗演练；前端生产构建通过 |
| P4-08 | 安全回归补跨用户越权：会话读取（`getOwnedSession`）、运行与事件流（`findByIdAndUserId`）、待审批代为决策（`ToolApprovalRegistry.resolve`）三条拦截点；路径逃逸与命令注入沿用既有用例 | `CrossTenantAccessTest` 3 项、`WorkspaceFileServiceTest` 4 项、`CommandService*Test` 30 项通过；**出境验收**：`HttpCrossTenantAcceptanceTest` 4 项用真实 `JwtUtil` 签发 token、真实 `JwtAuthenticationFilter` 解析，经 MockMvc 调用真实控制器，他人 run 事件流 / 审批 / stop 均被 4xx 拒绝，本人正常；**未覆盖**完整 `SecurityConfig` 过滤器链与 Redis 白名单（`TokenSessionService` 为 mock） |

**未覆盖**：macOS Seatbelt / Linux Landlock 沙箱（当前暂缓）；完整 SecurityConfig + Redis 白名单的跨账号端到端；双实例审批唤醒与互斥；断网 10s 人工复核；桌面壳重启人工验收。前端 SSE 自动续传已在 P5-10-1 落地，不再列为未覆盖。出境验收测试仅新增 test 作用域依赖 `spring-boot-data-jpa-test`（Boot 4 已把 `@DataJpaTest` 切片拆分为独立模块）。

---

## 5. Phase 5：智能体能力与工作台体验增强

> 详细方案、双轨路线与待确认决策见 [Phase 5 计划](phases/phase5/plan.md)；视觉与交互原型见 [visual-ux-preview.html](phases/phase5/visual-ux-preview.html)。

**目标**：保留并增强现有 ReAct 执行层，用真实任务评测证明代码理解、诊断、验证、检索和并行等功能的收益；同时把任务进度、审批、变更、验证和撤回连成用户可交付的工作台闭环。双轨是同一产品的两层，不是两个产品；质量建设服务于功能增强，不替代功能增强。

| ID | 任务 | 验收 | 状态 |
|---|---|---|---|
| P5-01 | 提示词外置与版本化 | 可热更新/回滚，按场景配置 | 已完成 |
| P5-02 | 组件级评测集 | 覆盖工具选择、文件编辑/删除、命令审批、RAG、拒答 | 已完成 |
| P5-03 | 评测门禁 | 完成率、轮次、token、时延有基线，回归即失败 | 已完成 |
| P5-04 | 代码索引 v1 | 符号索引和 repo map（可插拔 CodeIndexer，v1 轻量正则） | 基础完成：repo map 已注入 prompt，符号索引与增量缓存已落地；收益量化与可选持久缓存未做 |
| P5-05 | LSP 诊断闭环 | 编辑后自动取诊断并反馈给模型 | 已完成 |
| P5-06 | 验证回路 | 修改后自动运行 verify，失败回到循环，未验证不得伪装成功 | 已完成 |
| P5-07 | RAG 增强 | 向量 + pg_trgm 词法召回、RRF 融合、chunk 级引用；halfvec 按体积与精度数据决定 | 基础完成：混合检索与引用已落地；模型 reranker 与真实语料量化未做 |
| P5-08 | 并行只读工具 | 只读工具并行且时延有收益；写操作仍串行并审批 | 已完成：连续只读批默认 3 并发，fixture p95 282→98ms；写/命令与 MCP 保持串行审批 |
| P5-09 | 视觉体系 | token 收口、亮暗一致、加载/空/错误三态、消息排版、响应式 | 已完成：三态收口（工具运行态耗时、错误态导出诊断）、密度档位与表格/引用排版、960×640 与三档宽度回归 |
| P5-10 | 交互与体验 | 断线续传、审批键盘化、长任务进度、快捷键与无障碍 | 已完成：断线续传（P5-10-1）、审批效率（P5-10-2：J/K、低风险批量、审批历史）、长任务进度（P5-10-3）、键盘与无障碍（P5-10-4：焦点面板、会话键盘切换、aria 与播报、手动减少动效、axe critical 0） |
| P5-11 | 体验质量基础设施 | 前端单测 + E2E、错误与性能指标；虚拟滚动按真实长会话数据实施 | 已完成：P5-11-1 测试基线、P5-11-2 体验可观测性与 P5-11-3 长会话性能均已落地；当前后端 294 项、Vitest 88 项通过，Playwright 23 通过 / 1 跳过（`@perf`）；500 条会话实测首屏 108ms、消息区 DOM 216 节点、60 秒流式 0 长任务 |
| P5-12 | 信息架构 | 首屏去客服化、任务列表、轨迹与审批补强；剩余视觉细节归入 P5-09 | 核心已完成 |
| P5-13 | 真实 ReAct 闭环 | 脚本模型驱动真实循环与工具副作用；候选完成、未验证与最终完成语义明确，重复调用/无进展可识别 | 已完成（15/15 Runtime 用例） |
| P5-14 | 任务级 ChangeSet 与交付 | 聚合任务变更、诊断、verify 与审批，支持审查、继续修复和整体撤回 | 基础完成：Session 级聚合、按 Run 过滤、继续修复、逆序整体/按 Run 撤回与外部修改冲突检测已落地；前端看板已接入；E2E 已由 P5-11-1 覆盖 |

**出口**：

- 组件级门禁继续有效，真实 ReAct 循环另有确定性端到端评测，不再用组件通过率代替任务成功率。
- 代码修改有诊断与验证证据；模型直接宣称完成、重复调用或无进展时，系统能识别并如实收尾。
- P5-04、P5-07、P5-08 等功能增强保留，并用成功率、轮次、token 与时延数据证明收益；具体技术子项可按数据调整。
- 用户能在一个任务边界内查看变更、审批与验证结果，继续修复或撤回；未完成和未验证状态不得显示为已验证成功。
- 工作台关键路径有前端 E2E；断网与刷新可恢复，纯键盘可完成审批，窄窗口和亮暗主题可用。

### 5.1 后续多入口候选（不属于当前出口）

先稳定本地 API v1，再增加 CLI 与 VS Code / JetBrains 薄插件；它们只是同一智能体能力和任务数据的客户端入口。是否调整中间工作区或三栏布局留到 Phase 6 评估，不与现有 `WorkbenchDock` 重复建设。

---

## 6. 后续数据库与接口契约

### 6.1 数据库迁移

V15 已被邮箱唯一索引占用；Phase 4 的运行状态表已使用 **V16**，不再沿用旧规划中的 `V15__add_agent_run.sql`。后续迁移必须选择实际未占用版本。

| 版本 | 内容 | 说明 |
|---|---|---|
| V16 | `agent_run` / `agent_run_event` | 已落地；保留为契约说明，不再作为待实施迁移 |
| V19 | `code_index` | P5-04 桌面端可选持久缓存，未实施前索引仅存内存；实施前检查实际未占用版本 |
| V20 | `halfvec` 量化列与必要索引 | 仅 P5-07 证明体积或检索性能瓶颈后启用，不与混合检索捆绑 |
| 下一条未占用版本 | 暂不需要（P5-14 已复用 `AgentRun.requestJson` 保存 turnId） | P5-13、P5-14 均复用 Run/Event、审批与 `PendingFileChange`，未新增 Task/ChangeSet 表；后续默认先复用现有结构 |

原则：

1. 不直接修改任何已经执行的迁移，避免 Flyway 校验失败；实施前检查迁移目录与真实库历史。
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
| 命令执行 | Windows Job Object 已落地；macOS/Linux 沙箱暂缓 | 持续验收 Windows 约束；其他平台恢复时独立实现，云端不执行 |
| 运行与审批状态 | Run/审批已持久化；双实例唤醒与互斥、桌面壳重启恢复仍待验收 | 剩余场景行为明确，状态不丢、审批不重复，云端可共享 run/stop |
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

- 组件级评测与真实 ReAct 端到端评测分别达到各自基线，不能用前者代替后者。
- p95 首 token、任务成功率、验证覆盖率、无进展停止和关键错误可监控、可定位。
- 编程任务的修改、诊断、verify、审批与撤回结果可审查；未验证状态明确可见。
- 安装、更新、回滚和异常场景均有真实验收记录。

### 安全

- 安全清单全绿，无 Critical 依赖漏洞。
- 安装包签名有效，本地 API 不可被非授权页面调用。
- 密钥不入库、不入日志、不入安装包。

### 合规

- 开源许可证、隐私政策、服务条款和第三方 NOTICE 可访问。
- 遥测脱敏且可关闭；云端账号数据可导出/删除。

---

## 12. 执行顺序与后续批次

已完成的 Phase 3 主体、Phase 4 基础、P5-01～03、P5-05、P5-06 和 P5-13 不再进入执行队列。Phase 5 两条轨道可以并行，各自按以下顺序推进：

```text
智能体能力 A1：P5-05 LSP → P5-06 Verify（已完成）
智能体能力 A2：P5-04 代码索引（repo map + 符号索引/增量缓存，已完成）→ P5-04 收益量化 → P5-07 RAG 混合检索/RRF/引用（已完成）→ P5-07 模型 reranker 与量化
智能体能力 A3：P5-08 并行只读工具（已完成）
工作台体验 B1：P5-14 任务级交付 + P5-10-3 长任务进度 + P5-10-2 审批效率 + P5-10-4 键盘与无障碍 + P5-11-1 前端 E2E（已完成）
工作台体验 B2：P5-09 剩余视觉项（已完成）
工作台体验 B3：P5-11-2 体验指标（已完成）→ P5-11-3 长会话性能（已完成）
Windows 发布收尾与 Phase 4 剩余风险：独立并行跟踪
```

| 后续批次 | 内容 | 依赖 / 决策原则 |
|---|---|---|
| A1 | LSP 诊断与 Verify 验证回路（已完成） | 已复用 P5-01～03 与 P5-13 的真实循环、完成状态和无进展检测；保留 ReAct，未重写为多智能体 |
| A2 | 代码索引、repo map、混合检索、引用与 rerank | 功能目标保留；halfvec 与模型 rerank 由评测数据决定 |
| A3 | 并行只读工具（已完成） | 已确认串行只读是瓶颈；并发上限由 Runtime 批规模与 fixture p95 定为默认 3，写入与命令仍串行审批 |
| B1 | 任务级 ChangeSet、验证结果、继续修复、整体撤回、长任务进度和关键路径 E2E | 复用 P5-12 与现有 Run/PendingFileChange，不先重建领域模型；P5-11-1 的 E2E 在浏览器层用 Playwright route 仿真后端，未启动真实 Spring 进程 |
| B2 | 视觉、审批与无障碍剩余项 | 只补剩余列，不重做已完成能力 |
| B3 | 体验指标与长会话性能 | P5-11-2 已完成本地异常、Web Vitals、Run/恢复采样和当前用户后端聚合；P5-11-3 已用真实长会话剖析落地流式合帧、Markdown 增量渲染与超过 200 条的虚拟滚动（500 条首屏 108ms、DOM 216、60 秒流式 0 长任务）；后续视觉与无障碍剩余项归入 B2 |
| 发布收尾 | Windows 签名、干净机器完整任务、诊断导出与合规确认 | 不阻塞 Phase 5；macOS/Linux 暂缓 |
| Phase 6 候选 | 本地 API、CLI、IDE 薄插件与工作区布局再评估 | Phase 5 核心闭环稳定后另立验收，不 fork VS Code |
