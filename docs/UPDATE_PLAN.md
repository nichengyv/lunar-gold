﻿﻿﻿﻿﻿﻿﻿# Lunar Gold（金月）未完成更新规划（v1.4 · 免费版）

> 更新日期：2026-11-21
> 本文只记录未完成工作、当前阻塞、后续任务和验收标准。
> 已完成任务与历史验收见 [COMPLETED_WORK.md](COMPLETED_WORK.md)；当前代码快照见 [STATUS.md](STATUS.md)。
> Windows 安装排障见 [troubleshooting/windows-nsis.md](troubleshooting/windows-nsis.md)。
> 本文按"阶段 + 出口标准"组织，不以日历工期作为承诺。

---

## 0. 当前位置与下一步

Phase 0～2、P3-00～03 已完成；Windows 安装/卸载、GitHub Release、自动更新与回滚、本地诊断和合规产物的主体工作已交付，历史证据见 [COMPLETED_WORK.md](COMPLETED_WORK.md)。Phase 4 已落地运行状态、审批持久化、Windows 沙箱、模型韧性与成本预算，剩余风险见第 4 节。**当前只适配 Windows**，macOS/Linux 安装包继续暂缓。

**最终产品形态：Lunar Gold 是本地优先、可控、可验证的 AI 编程工作台，桌面端是首个完整客户端。** Agent Runtime 只是内部的基础执行层之一，不是独立产品类型，也不是差异化本身；现有 ReAct 循环继续保留，差异化由任务成功率、可控性、验证、恢复与使用体验共同证明。

**当前产品主线：Phase 5（2026-11-16）、Phase 6（2026-11-17）、Phase 7（2026-11-20）均已收口。2026-11-21 完成全量架构审计后重规划：R0～R6 架构重构已完成；R7 长任务能力进行中（R7a 评测扩容、R7b 断点续跑已完成，R7b-live 真实模型续跑完成率仅完成 n=1 冒烟，R7c 进行中：L5 验证自动探测、纯 Java 裸源码有界全量编译、计划证据契约与检索型记忆已完成），随后做 P3-08 干净机器验收。详见第 14 节与 `ARCHITECTURE_AUDIT_20261121.md`。**

1. **智能体能力增强轨道**：P5-13 真实 ReAct 端到端评测与完成控制、P5-05 LSP 诊断闭环、P5-06 验证回路、P5-04 代码索引（repo map + 符号索引/增量缓存）、P5-07 RAG 混合检索/RRF/引用与检索质量量化、P5-08 并行只读工具均已完成；P5-07-2 用 24 切片 / 22 查询的标注语料实测 hybrid Recall@5 100% vs vector 68.42%（exact 类 0%→100%），按预写规则判定不启用模型 reranker。P5-04 已于 2026-11-16 用 deepseek-chat 完成 96 run A/B：repo map 开成功率 100% vs 关 62.5%，prompt tokens 4,126 vs 25,072，耗时 1.76s vs 10.17s，B1～B5 全过，判定保持默认开启；持久缓存按 C2 判定不建。**Phase 5 出口条件四条已全部满足，本阶段收口。**
2. **工作台体验增强轨道**：P5-14 任务级 ChangeSet 与交付已完成基础交付（矩阵见第 5 节），P5-11-1 前端 Vitest/Playwright 基线、P5-11-2 体验可观测性、P5-11-3 长会话性能、P5-10-3 长任务进度、P5-10-2 审批效率、P5-10-4 键盘与无障碍与 P5-09 视觉体系已落地；剩余未勾选项（三态覆盖、长回答折叠、三档宽度、断网实测、J/K 与批量审批、axe 校验、500 会话性能）为打磨与合规加固，不阻塞 Phase 5 出口，转入后续批次。
3. **并行 Windows 发布收尾**：签名、诊断 ZIP 导出人工复核、干净机器完整任务验收和 P3-08 所有者确认项单独跟踪，不阻塞 Phase 5。
4. **Phase 6（已收口，2026-11-17）**：本地 API 子集与客户端凭证 → CLI → VS Code 薄插件，复用同一后端能力；不 fork VS Code。收尾完成 CLI 令牌解析修复、diagnostics E2E、P6-04 布局评估（结论保留）与出口检查；计划与证据见 [docs/phases/phase6/plan.md](phases/phase6/plan.md)，完成档案见 [COMPLETED_WORK.md](COMPLETED_WORK.md)。
5. **P7 稳定性与长会话（已收口，2026-11-20）**：P7-01 已收口重启后未完成 Run 与残留审批；P7-02 统计成本切片未触发 500ms 缓存阈值，**统计慢不再是已知边界**；L1～L3×3 真实模型正式基线均 2/3 成功率、工具链 0 error，三次失败均为 LLM 固有表现；P7-04 记忆摘要升级使 L3 memoryProbe 0/3→3/3、成功率 0/3→2/3；P7-04b 工具输出按档位截断已完成；P7-05 SSE 真机链路已验证畅通；P7-06 ask_user_question 已落地并通过真实模型冒烟。P7-03 plan-first 评估后不立项，P7-04c plan 注入尝试失败已回滚。收口后转入 P7-07～P7-09 三项收尾改进，详见第 13 节。

---

## 1. 不变的产品与技术边界

- **产品类型**：对外统一称"AI 编程工作台"；"Agent Runtime / 智能体执行引擎"只用于描述模型、工具、审批、执行、状态和验证等内部机制。
- **执行架构**：保留 ReAct 作为基础循环，以计划、代码上下文、LSP、Verify、无进展检测和评测补强；没有评测证据时，不为了架构名词改做多智能体或复杂状态图。
- **明确不做**：不 fork VS Code，不做开放用户提交的插件市场/平台与泛桌面自动化；模型、MCP、LSP 与未来客户端入口可以扩展，执行约束、审批、验证和恢复保持统一。**官方 curated 插件管理中心除外**（0.1.4 规划，见 [phases/phase8/plugin-center.md](phases/phase8/plugin-center.md)）：插件清单由项目所有者人工审核收录，不开放上传接口。
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
| P3-07 | Windows 发布流水线 | **已收口（2026-11-20）**：标签触发恢复，CI 构建、安装包冒烟与制品上传通过；签名按未签名发布策略并行跟踪 |
| Phase 4 剩余风险 | 双实例审批互斥、完整安全链、真实预算与并发验证（P7-01 已收口重启时未完成 Run 与残留审批） | 剩余场景行为明确且有对应验收，不用已完成单测代替端到端结论 |
| Phase 5 | 智能体能力增强 + 工作台体验增强 | 真实 ReAct 可评测，修改有诊断与验证，任务可审查/恢复/撤回，既有功能增强有量化收益 |
| Phase 6 | 本地 API 子集、CLI、IDE 薄插件与工作区布局再评估 | **已收口（2026-11-17）**：不同入口复用同一后端；P6-04 结论为保留现有三栏布局；详见 [phase6/plan.md](phases/phase6/plan.md) |
| Phase 7 | 长会话：三数据集正式基线 ×3 + 记忆摘要 + 工具输出分档 + ask_user_question + SSE 真机链路 | **已收口（2026-11-20）**：P7-01 重启残局 ✅ / P7-02 统计切片 + 三数据集 ×3 正式基线（L1/L2/L3 均 2/3 成功率，LLM 固有表现）✅ / P7-04 记忆摘要升级（L3 memoryProbe 0/3→3/3）✅ / P7-04b 工具输出分档（FAST=500 / STANDARD=1000 / DEEP=2000）✅ / P7-05 SSE 真机 ✅ / P7-06 ask_user_question ✅ / P7-04c plan 注入尝试失败已回滚；P7-03 plan-first 不立项；详见 COMPLETED_WORK.md Phase 7 章节 |
| Phase 7 收尾改进 | P7-07 计划持久化与软回注、P7-08 停滞渐进式恢复、P7-09 长会话上下文压缩 | 三项均按 L1～L3 数据集 A/B + 预注册阈值验证；完成率不降、token/耗时退化 <15% 才保留；不引入 plan-first 控制流 |
| Phase 8（候选） | 长任务能力建设：Run 级持久化续跑、完成强校验、大任务分解、评测扩容、检索型记忆 | **未立项**；P7-07～P7-09 收口且 L1～L4 达标后启动；8 条验收标准（L1～L8）与排期见 13.12；2026-11-21 重规划后并入 R7 |
| Phase 9 架构重构 | R0 安全网 → R1 状态地基 → R2 Context 瘦身 → R3 工具接缝 → R4 编排接缝 → R5 上下文接缝 → R6 前端拆分 → R7 长任务能力 | **当前主线**；每批行为保持/可回滚，先补测试再动代码；目标与验收见第 14 节 |
| 0.1.4 插件管理中心 | 设置页 MCP tab 升级为「插件中心」：官方 curated 市场浏览 + schema-driven 一键安装 + 已装管理；阶段 1 内嵌静态目录，阶段 2 远程 JSON，阶段 3 远程传输 | 见 [phases/phase8/plugin-center.md](phases/phase8/plugin-center.md)；0.1.3 收口后从 main 拉新分支启动，不动已验证的打包/schema 路径 |

---

## 3. Phase 3 剩余任务：分发与发布

### P3-04：安装包、开源签名与 Release

| 工作 | 当前状态 / 下一步 | 验收 |
|---|---|---|
| 最新 Windows 安装器最小回归 | **已完成**（2026-11-10）；发布基线哈希 `3E085FAB…9022`，22 项检查全通过。**0.1.3 复验（2026-11-22）**：安装、首启 `/actuator/health=UP`、正常退出无残留、静默卸载清理均通过；安装包 330,235,757 字节，SHA-256 `DCB11A78…EAED` | 安装、首启、卸载通过；目录、注册项、health 和进程状态正确 |
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
  - 工作区为用户自选目录、`uploads/` 自 0.1.3 起位于数据目录，升级与回滚均未删除。
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
- **已完成（2026-11-20）**：`f3ac5e6` 恢复 `v*` 标签触发并把版本推进到 0.1.3，`a78cedf` 修复 CI 中文日志解析；本地 314 MB NSIS 安装包与 CI 全绿后打通。未配置 `SIGNPATH_*` secrets 时签名步骤自动跳过，流程产出未签名草稿 Release；安装器、`latest.yml`、`SHA256SUMS.txt` 与合规文件随流程上传。签名仍按 P3-04 的未签名发布策略并行跟踪。
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
- [x] 隐私政策重写 `PRIVACY.md`（2026-11-11）：本地数据目录逐项说明；本地诊断的报告位置、10 份上限、白名单与脱敏、导出内容、无自动上传；自动更新访问 GitHub Releases；模型 / 向量服务商、内置联网工具（Tavily、wttr.in、Open-Meteo、网页抓取）与 MCP 的网络边界；卸载保留 `%USERPROFILE%\.lunargold`（含 uploads）。
- [x] 服务条款新增 `TERMS.md`：Apache-2.0 授权不变、审批与备份责任、BYOK 费用、第三方服务、无担保与责任限制、Web 版账号与终止。
- [x] README 扩充：SHA-256 校验命令、首启说明、自动更新行为、卸载保留行为、基础故障排查、合规文件入口；修正原先「sends nothing anywhere」的不准确表述。
- [x] 文档语言：中文为主，英文对照版保留在 `README.en.md`、`PRIVACY.en.md`、`TERMS.en.md`、`NOTICE.en`，各文档首页互相链接；`SBOM.json` 保持机器可读的英文标识。
- [x] 合规产物可复现（2026-11-11）：新增 `scripts/build-compliance.ps1` 与 `scripts/licenses-config.xml`，输出到忽略的 `build/legal`（12 个文件，含 `THIRD-PARTY-LICENSES.txt`）；`pom.xml` 补齐项目 URL/SCM 并固定 CycloneDX 2.9.3、license-maven-plugin 2.7.1；任一必需文件缺失、SBOM 不可解析或许可合集为空即中止。安装器构建前自动生成并逐文件校验 `resources/legal` 哈希，CI 严格校验后显式上传。
- [ ] 所有者确认项（不阻塞文档发布）：`TERMS.md` 采用中华人民共和国法律与维护者住所地法院管辖，需确认；Web 版是否对外托管、以及该部署的数据主体联系方式需在真正上线前补充。
- [x] 发布流程：`release.yml` 已把 `build/legal` 复制为 `release/legal`、逐项校验并显式上传（`fail_on_unmatched_files: true`）；下一次 Release 将随附 `SHA256SUMS.txt`、`NOTICE`、`PRIVACY.md`、`TERMS.md`、四份 SBOM 与 `THIRD-PARTY-LICENSES.txt`。
- [x] 发布卫生（2026-11-22）：Spring Boot 4 的 `spring-boot-starter-test` 把 `junit-jupiter` 声明为 compile 作用域，导致发布 jar 内含测试框架；已在 [pom.xml](../pom.xml) 排除并以 test 作用域显式重新引入。重新打包后 `BOOT-INF/lib` 不再含 junit / opentest4j / apiguardian（旧包约 1.38 MB 压缩体积），全量 623 项测试通过。

**Phase 3 出口**：陌生机器可完成一个标准编程 fixture 任务：修改文件 → 必要审批 → 验证 → 审查 diff → 撤回；升级和回滚可用，数据可备份，合规文件随版本发布。P5-06 验证回路与 P5-14 任务级交付已落地，审查与撤回可通过 ChangeSet API/看板执行；前端关键路径已由 P5-11-1 的 Playwright 用例在浏览器层用 API 仿真覆盖，但真实后端 + 真实模型的干净机器完整任务验收仍需人工执行，记录时不得把仿真 E2E 写成真机验收已通过。

---

## 4. Phase 4：安全与可靠性加固

**目标**：从"能跑"到"敢开放"。开放给真实用户操作不可信项目时，P4-01 和 P4-08 是高优先级。

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
| P4-03 | V17 `tool_approval`；`ToolApprovalRegistry` 数据库权威 + 内存加速；启动恢复待审批、过期直接 `REJECTED`、恢复"本会话总是允许" | `ToolApprovalRegistryTest` 6 项、`ToolApprovalRegistryPersistenceTest` 4 项通过；**出境验收**：真实库重启恢复（未过期可批准、过期即拒绝、会话总是允许恢复）与双实例决策落库可见均通过；**已知边界**：两实例各持内存 future，跨实例唤醒与重复决策互斥未实现 |
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
| P5-04 | 代码索引 v1 | repo map 符号索引与增量缓存 | **已完成**：A/B 量化保持默认开启，成功率 +37.5pp / token −83% / 时延 −83%；持久缓存按 C2 判定不建；正则抽取与字母序截断为已知短板，升级候选见 5.2 |
| P5-05 | LSP 诊断闭环 | 编辑后自动取诊断并反馈给模型 | 已完成 |
| P5-06 | 验证回路 | 修改后自动运行 verify，失败回到循环，未验证不得伪装成功 | 已完成 |
| P5-07 | RAG 增强 | 向量 + pg_trgm 词法召回、RRF 融合、chunk 级引用；halfvec 按体积与精度数据决定 | 完成：混合检索与引用已落地；P5-07-2 用 24 切片 / 22 查询实测 hybrid Recall@5 100% vs vector 68.42%、exact 类 0%→100%、noise 全拒绝，判定不启用模型 reranker；P5-07-3 用 160 干扰切片 / 12 难例 + 真实 bge-m3 与 bge-reranker-v2-m3 复跑，top-1 +8.34pp（<10pp）、rerank p95 482.81ms（>300ms），仍判定保持不启用；halfvec 未做 |
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

### 5.1 后续多入口（Phase 6，已收口 2026-11-17）

先稳定本地 API 子集，再增加 CLI 与 VS Code / JetBrains 薄插件；它们只是同一智能体能力和任务数据的客户端入口。Phase 6 已完成公开 API 子集与客户端凭证、CLI、VS Code 薄插件、审批入口审计、Run 断流解耦与交互收敛；P6-04 评估结论为**保留现有三栏布局，不新增中间工作区**，不与现有 `WorkbenchDock` 重复建设。批次、决策点、出口条件与证据见 [phase6/plan.md](phases/phase6/plan.md)。

### 5.2 代码索引升级候选（P5-04 衍生，暂缓）

P5-04 实施中暴露的实现短板。三项都不改"内存索引 + 会话内重建"的架构；持久缓存已按 C2 判定不建（cold 271ms），不在本候选内。

**2026-11-16 决定：暂缓，不排期。** 依据是 P5-04 的 96 run A/B 已满足 Phase 5 出口（成功率 +37.5pp / token −83% / 时延 −83%，B1～B5 全过），而这三项解决的都是边缘场景：C# 完全漏（但主力语言 Java/TS/Python/Go/Rust 均已覆盖）、本仓库 464 文件打满 6000 字符预算（但 37 文件 fixture 的 repo map 仅 2,920 字符、未截断）、tree-sitter 需跟随三平台原生库、对桌面安装包体积影响明显。**不为了"技术上更完整"做，等真实用户反馈触发再开。**

触发条件（满足任一即重新评估，不自动立项）：

| 触发条件 | 观测方式 |
|---|---|
| 用户反馈大仓库（>1000 文件）repo map 截断严重、靠后目录定位失败 | 用户反馈 / 诊断报告中的 `truncated=true` 与文件数 |
| 用户主力语言为 C# 或 Kotlin/Scala/Swift 等当前抽取缺失的语言 | 项目类型分布、定位用例失败样本 |
| A/B 重跑时 recall 跌破 90% 门禁 | `IndexBenefitEvalTest` 的符号 recall 断言 |

| 编号 | 升级项 | 现状 | 目标 | 前置 / 验证 |
|---|---|---|---|---|
| P5-04-a | 用解析器替换正则抽取 | `LightweightCodeIndexer.supports()` 声明了 kotlin/scala/groovy/c/cpp/cs/php/rb/swift，但 `index()` 无对应分支，这些文件抽不到符号（C# 在标注集 recall 为 0）；Java 接口中无修饰符的方法也漏 | 多语言 AST 抽取，符号准确率作为门禁 | 优先 JavaParser（纯 Java、无原生依赖）；tree-sitter 仅在确认必须多语言精确解析时再评估；用现有 12 个标注符号文件的断言做回归 |
| P5-04-b | repo map 加相关性排序 | `RepoMapService` 用 `TreeMap` 字母序遍历，撞到字符预算即停，大仓库靠后目录永远进不了 map | 按符号引用次数/入口距离排序后截断（Aider PageRank 同思路） | 依赖 a 的解析结果，需要构建引用图；用 800 文件合成工作区对比截断分布 |
| P5-04-c | 预算动态化 | `maxChars` 默认 6000，本仓库 464 文件已占 5977 字符并截断 | 按文件数/符号数缩放，或分层（顶层目录必现 + 其余按预算） | 在 800 与 5000 文件合成工作区上测截断率与 map 覆盖率 |

### 5.3 MCP 远程传输（M2，暂缓为深度优化选项）

**2026-11-22 决定：本轮不做，列为后续可选深度优化，不排期。**

M1 已交付用户自助 stdio MCP（设置页增删改、启停、重连、环境变量加密、健康检查）。M2 指远程 MCP，分三档：

| 档位 | 内容 | 说明 |
|---|---|---|
| M2-1 | Streamable HTTP + 静态 Bearer/Header | 覆盖自建网关与支持 PAT 的远程服务 |
| M2-2 | OAuth 2.1（PKCE、资源元数据、动态注册、刷新/撤销） | 行业标准，覆盖 GitHub/Notion 等托管服务 |
| M2-3 | 旧版 SSE 兼容 | 仅为存量服务，非必需 |

暂缓理由：

1. 产品定位是本地优先，多数 MCP 服务可通过本机 `npx` / `uvx` + 环境变量接入，M1 已覆盖；
2. M2-2 的复杂度集中在授权服务器兼容、令牌刷新/撤销与回调安全，测试成本明显高于当前收益；
3. 不做半套 OAuth：要么完整授权链，要么只做静态凭据，避免“界面有入口但连不上”的体验。

触发条件（满足任一即重新评估）：

| 触发条件 | 观测方式 |
|---|---|
| 用户明确要求连接托管 MCP（GitHub、Notion、Sentry 等） | 用户反馈 / issue |
| 目标场景从本地开发扩展到 SaaS 工具链 | 产品决策 |
| 主流 MCP 服务停止提供本地 stdio 或 PAT 方式 | 服务商公告 |

实施前置（恢复时直接按此拆）：数据表增加 `transport/url/headers`；`McpServerRuntime` 按 transport 选工厂；设置页增加“本地/远程”；令牌加密且只写不读；远程传输补齐 TLS、超时、重定向与数据出境提示。传输层抽象与插件中心统一规划，见 [phases/phase8/plugin-center.md](phases/phase8/plugin-center.md)（扩展现有 `McpClientFactory` 入参容纳 url/headers，不新造 transport 接口——MCP SDK 已提供 `ClientTransport` 抽象，`StdioClientTransport` / `HttpClientTransport` / `SseClientTransport` 均为 SDK 自带实现）。

### 5.4 插件管理中心（0.1.4 规划）

把设置页「扩展工具」tab 从手填四框的工程师配置面板，升级为用户视角的「插件中心」：市场浏览 → 一键安装 → 已装管理。

核心决策（详见 [phases/phase8/plugin-center.md](phases/phase8/plugin-center.md)）：

- **市场数据源**：阶段 1 内嵌静态 `marketplace.json`（随包、零网络依赖）；阶段 2 换远程 JSON（GitHub 托管，可热更新），fetch 失败回退内嵌。
- **审核机制**：官方 curated，项目所有者人工收录，不开放上传接口；UI 显示「官方收录」标识。`command` 字段能在用户机器执行任意命令，安全责任在发行方，必须 curated。
- **安装流程**：schema-driven，用户只填凭证（如 `GITHUB_TOKEN`），不碰 command/args；安装前检测运行时依赖（node/uvx/python）。
- **传输**：阶段 1 不加远程，仅扩展 `McpClientFactory` 入参留口子（不新造 transport 接口）；阶段 3 加 SDK 自带的 HTTP/SSE transport。
- **数据模型**：DB 层只给 `mcp_server` 加 `plugin_id`/`plugin_version` 两列（不重构 JPA 继承）；运行时层抽 `InstallablePlugin` 接口，为未来指令模板/模型预设留扩展点。
- **约束**：0.1.3 冻结收口，插件中心作为 0.1.4 主功能从 main 拉新分支，不碰已验证的打包/schema 路径。

保留「手动添加」作为高级入口（已装 tab 右上角），给本地自写 server 的用户用。

**开发进度**（2026-11-23）：在 `feature/0.1.4-plugin-center` 分支开发。T1–T9 已完成（V26 迁移、`McpConnectionSpec` 传输抽象、`plugin` 包与 `PluginCatalogService`、`InstallablePlugin`/`McpInstaller`、`PluginController`、`RuntimeDetector`、6 个 curated 插件的 `marketplace.json`、前端 `types/plugin.ts` + `api/plugin.ts` + `PluginCenter.vue`），后端单测（`PluginCatalogServiceTest`/`McpInstallerTest`/`RuntimeDetectorTest`/`PublicApiContractTest`）与前端 `vue-tsc --noEmit` 均通过。T10（前端运行时检测引导）、T11（端到端集成验收）、T12（文档同步，进行中）。

---

## 6. 后续数据库与接口契约

### 6.1 数据库迁移

V15 已被邮箱唯一索引占用；Phase 4 的运行状态表已使用 **V16**，不再沿用旧规划中的 `V15__add_agent_run.sql`。后续迁移必须选择实际未占用版本。

| 版本 | 内容 | 说明 |
|---|---|---|
| V16 | `agent_run` / `agent_run_event` | 已落地；保留为契约说明，不再作为待实施迁移 |
| V19 | `tool_approval.decision_source` 可空列 + 历史查询索引 | 已落地（2026-11-14）；`code_index` 持久缓存候选原定 V19 但因 P5-04 判定不建，已取消 |
| V20 | `api_client`（客户端令牌） | P6-01 已落地（2026-11-16）；`halfvec` 量化列候选顺延到后续未占用版本 |
| V21 | `tool_approval.client_id` 可空列 | P6-05 已落地（2026-11-17）；记录审批入口，旧记录保持 `NULL` |
| 下一条未占用版本 | V22（暂不需要） | P5-14 已复用 `AgentRun.requestJson` 保存 turnId；后续默认先复用现有结构，`halfvec` 候选仅在 P5-07 证明瓶颈后启用 |

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
| 运行与审批状态 | Run/审批已持久化；P7-01 已收口桌面重启后的未完成 Run 与残留审批；双实例唤醒与互斥仍待验收 | 剩余场景行为明确，状态不丢、审批不重复，云端可共享 run/stop |
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
6. 开放用户提交的插件或规则市场（官方 curated 插件管理中心除外，见 0.1.4 规划 [phases/phase8/plugin-center.md](phases/phase8/plugin-center.md)）。
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
智能体能力 A2：P5-04 代码索引（repo map + 符号索引/增量缓存，已完成）→ P5-04 收益量化 → P5-07 RAG 混合检索/RRF/引用（已完成）→ P5-07 检索质量量化与 reranker 决策（已完成，判定不启用模型 reranker）→ P5-07-3 难例 + 真实模型 reranker 对照（已完成，仍判定保持不启用）
智能体能力 A3：P5-08 并行只读工具（已完成）
工作台体验 B1：P5-14 任务级交付 + P5-10-3 长任务进度 + P5-10-2 审批效率 + P5-10-4 键盘与无障碍 + P5-11-1 前端 E2E（已完成）
工作台体验 B2：P5-09 剩余视觉项（已完成）
工作台体验 B3：P5-11-2 体验指标（已完成）→ P5-11-3 长会话性能（已完成）
Windows 发布收尾与 Phase 4 剩余风险：独立并行跟踪

P5-04 衍生升级（5.2：tree-sitter 抽取 → repo map 排序 → 预算动态化）：待 A/B 结论落定后单独立项，不阻塞出口
```

| 后续批次 | 内容 | 依赖 / 决策原则 |
|---|---|---|
| A1 | LSP 诊断与 Verify 验证回路（已完成） | 已复用 P5-01～03 与 P5-13 的真实循环、完成状态和无进展检测；保留 ReAct，未重写为多智能体 |
| A2 | 代码索引、repo map、混合检索、引用与 rerank | 功能目标保留；halfvec 与模型 rerank 由评测数据决定；P5-07-2 已量化 hybrid 收益并按预写规则判定不启用模型 reranker；P5-07-3 已用真实 bge-m3 + bge-reranker-v2-m3 在难例集复跑（top-1 +8.34pp、rerank p95 482.81ms），仍未达预写阈值，保持不启用；仅当换模型或换语料后重跑达标再重启 |
| A3 | 并行只读工具（已完成） | 已确认串行只读是瓶颈；并发上限由 Runtime 批规模与 fixture p95 定为默认 3，写入与命令仍串行审批 |
| B1 | 任务级 ChangeSet、验证结果、继续修复、整体撤回、长任务进度和关键路径 E2E | 复用 P5-12 与现有 Run/PendingFileChange，不先重建领域模型；P5-11-1 的 E2E 在浏览器层用 Playwright route 仿真后端，未启动真实 Spring 进程 |
| B2 | 视觉、审批与无障碍剩余项 | 只补剩余列，不重做已完成能力 |
| B3 | 体验指标与长会话性能 | P5-11-2 已完成本地异常、Web Vitals、Run/恢复采样和当前用户后端聚合；P5-11-3 已用真实长会话剖析落地流式合帧、Markdown 增量渲染与超过 200 条的虚拟滚动（500 条首屏 108ms、DOM 216、60 秒流式 0 长任务）；后续视觉与无障碍剩余项归入 B2 |
| 发布收尾 | Windows 签名、干净机器完整任务、诊断导出与合规确认 | 不阻塞 Phase 5；macOS/Linux 暂缓 |
| Phase 6（已收口） | 本地 API 子集、CLI、IDE 薄插件与工作区布局再评估 | 已完成（2026-11-17）；不 fork VS Code，P6-04 结论为保留现有布局 |
| P7 收尾改进（2026-11-21 排定） | P7-07 计划持久化与软回注 → P7-08 停滞渐进式恢复（可并行）→ P7-09 长会话上下文压缩 | 保留 ReAct 循环，不引入 plan-first 控制流；三项均按 L1～L3 A/B + 预注册阈值验证，退化即回滚 |
| Phase 8（候选，未立项） | P8-01 Run 级持久化续跑、P8-02 完成强校验、P8-03 大任务分解、P8-04 评测扩容、P8-05 检索型记忆 | 以 L1～L8 八条长任务验收标准为门禁；P7 三项收口后才启动，详见 13.12 |

---

## 13. Phase 7：长会话与执行循环演进（P7）

> 原则：先量化，再决定是否动执行模型。项目约束仍是「没有评测证据时，不为架构名词改做多智能体或复杂状态图」；ReAct 保留为基础循环。

### 13.1 问题拆分

| 长会话问题 | 与循环架构关系 | 对应工作 | 状态 |
|---|---|---|---|
| 上下文膨胀、历史成本高、记忆丢失 | 部分相关 | P7-04 记忆摘要升级 / P7-04b 工具输出分档 | ✅ 已完成（2026-11-20） |
| **计划漏项**（scope 变化后漏改文件，STEP_OMISSION） | 直接相关（计划状态跨 turn 陈旧） | **P7-07 计划持久化与软回注** | 拟立项（2026-11-21） |
| **无进展 / 重复循环**（陷入 search_files 循环被截断，STAGNANT_ROUNDS） | 直接相关（检测到即硬 stop） | **P7-08 停滞渐进式恢复** | 拟立项（2026-11-21） |
| **长会话 transcript 线性膨胀**（缺整体压缩，仅单条截断） | 直接相关 | **P7-09 长会话上下文压缩** | 拟立项（2026-11-21） |
| 长任务跑偏、计划漂移（架构层 P&E） | 直接相关 | P7-03 plan-first | ❌ **不立项**（2026-11-21） |
| 追踪统计慢（`aggregateStats` 全量解析 trace JSON） | 无关 | P7-02a 统计缓存 | ❌ 已否决（p95 未达 500ms） |
| 重启后状态残局 | 无关 | P7-01 | ✅ 已完成 |
| 桌面 SSE 自动化缺口 | 无关 | P7-05 链路 + Electron 壳冒烟 | ✅ 链路已通 |
| **agent 不会停下来问用户**（缺决策分支交互原语） | 直接相关 | P7-06 ask_user_question | ✅ 已完成 |

现状：并非纯 ReAct——已有 `update_plan` 工具、计划面板、`finalizePlan`、`PLAN_INCOMPLETE` 完成校验与 `AgentPolicy` 三档；有审批（工具风险拦截）但没有**决策询问**，P7-06 已补上 ask_user_question。

**2026-11-21 方向调整**：P7-06 落地后 P7-03 plan-first 的触发门槛进一步抬高，最终判定**不立项**（决策记录见 13.5）。替代路线是"轻量规划"——把已有的 `update_plan`、停滞检测、记忆摘要三个零件串成闭环：计划跨 turn 持久化后每轮软回注（P7-07）、停滞检测改渐进式 nudge 而非硬停（P7-08）、transcript 整体压缩（P7-09）。三项都不改循环形状，符合行业主流（ReAct 为默认，plan 以软提示形式存在）。

对比业界：DeepSeek Harness 的 `@deepseek-ai/dsh-tool-ask-user`（每周 72 万下载）把决策询问做成了模型可调用的一等 tool（`ask_user_question({ questions: [{ id, question, options, multi_select }] })`），Trae 则作为系统级能力提供。金月当前只有审批（风险门禁），缺决策询问（方向分支），这是"用户在环"的第二环缺口。

### 13.2 已完成工作总结（2026-11-19）

**P7-01 重启残局收口**（已完成）：重启后未完成 Run 与残留审批处理。

**P7-02 统计成本切片**（已完成，判定不立项缓存）：
- 基准：`ChatStatsBenchmarkTest`（默认不跑，`-Dperf=true` 显式执行），trace 形状对齐 `ChatStreamBridge` 的真实落库结构；结果写入 `eval/reports/stats-aggregation-*.json`（已忽略）。
- 数据：400 条消息 / 3.11MB → p95 8～11ms；1000 条 / 7.79MB → p95 13～17ms；2000 条消息（1000 条助手消息）/ 15.57MB → p50 26ms、p95 27～31ms（两次运行区间）。
- 口径：仅 trace 解析与聚合，不含 JDBC 往返与行解码。
- 判定：规则 1（1000 条会话 p95 > 500ms）未命中，**不引入统计缓存，P7-02a 不立项**；长会话统计慢从已知边界中移除，后续会话继续变长时用同一基准复测。

**P7-02 L1～L3 数据集离线验证 + 正式基线**（已完成）：
- 三个数据集（`eval/long-session-datasets/l1~l3-*.json`）schema 校验全过：L1 单模块重构（20 轮）、L2 三模块 scope 变化 + 审批审查（40 轮）、L3 十二次 turn + 记忆探针（100 轮）。
- `LongSessionDatasetTest`（12 个 JUnit 断言）全过；`LongSessionOfflineRunner` 0 blocker、artifact 覆盖 100%（13/13、25/25、60/60）。
- **真实模型正式基线 L1/L2/L3 ×3（2026-11-20）**：三层均 2/3 成功率、errors 全部为空、工具链全通。三层失败原因各异（STAGNANT_ROUNDS / STEP_OMISSION / HISTORY_REPETITION），均为 LLM 内在不确定性而非代码缺陷。详见 COMPLETED_WORK.md 「三数据集正式基线完整数据」章节。
- **P7-04 记忆摘要升级**（已完成）：L3 memoryProbe 0/3 → 3/3；成功率 0/3 → 2/3。三层 Runner 判定 `rule2PlanFirst=false, rule3ContextMemory=false`，P7-03 不立项。
- **P7-04b 工具输出分档**（已完成）：AgentPolicy.Reasoning 新增 toolContentLimit()，FAST=500 / STANDARD=1000 / DEEP=2000；truncateForContext 改为按档位动态取。
- **P7-04c 框架级 plan 注入**（已回滚）：LangChain 式 buildPlanHint() 每次 streamChat 前注入精简计划摘要，L1 中性、L2 从 2/3 降到 1/3。根因是 ThreadLocal WorkspaceContext 跨 turn 不共享，注入信息与模型真实状态错位。回滚到 commit 799b982。

**real-sse-smoke harness 缺陷修正**（已完成）：
- `desktop/real-sse-smoke.cjs` 的 SSE 请求体从 `permission: 'READ_ONLY'` / `reasoning: 'FAST'` 改为 `permission: 'AUTO'` / `reasoning: 'STANDARD'`——READ_ONLY 档 system prompt 会强制「只读不能执行」，虽然冒烟不涉及文件，但 AUTO 档语义更中性；FAST 档只有 10 轮工具循环，STANDARD 给更多宽松度。

**SSE `AccessDeniedException` 根因定性 + 修复 + 真机验证**（已完成）：
- **根因**：两层叠加：
  1. **ASYNC dispatch**：Tomcat 对 SseEmitter 的异步写入会以 ASYNC dispatch 重跑整个 Security filter chain。原始 REQUEST 线程上 `LocalTokenFilter` 已完成认证，但 ASYNC dispatch 时 `OncePerRequestFilter` 的 request attribute 防重机制让 LocalTokenFilter 跳过（SecurityContext 此时可能已空），`FilterSecurityInterceptor` 再次检查 `.authenticated()` 就 deny。
  2. **/error dispatch**：SSE 期间 response already committed 时 Tomcat 会尝试 forward 到 `/error`，但 `.anyRequest().authenticated()` 覆盖了 `/error`，FORWARD/ERROR dispatch 同样被 deny，抛出 `Unable to handle the Spring Security Exception because the response is already committed`。
- **修复**：`SecurityConfig.filterChain()` 在 `authorizeHttpRequests` 最前面加：
  - `dispatcherTypeMatchers(ASYNC, FORWARD, ERROR).permitAll()` — 跳过所有非 REQUEST dispatch 的二次鉴权
  - `requestMatchers("/error").permitAll()` — /error 是容器内部 forward 目标，不应该要求认证
- **真机验证**（desktop profile + 内嵌 PG + 打包态 jar + 本地令牌）：
  1. `GET /actuator/health` → 200 ✅
  2. `GET /api/desktop/token` → 返回本地令牌 ✅
  3. `POST /api/agent/chat/stream`（带 Bearer token + Node.js http 原生支持 async stream）→ **STATUS 200 + Content-Type: text/event-stream + 业务层 error 事件（未找到 API Key）** ✅
  4. 后端日志零 `AuthorizationDeniedException`，SSE 链路完整畅通
- **回归**：后端全量测试 ✅ exit 0、前端 vue-tsc ✅ exit 0。
- **额外发现**：这次本机用 GUID 隔离目录后 initdb + PG 启动**成功了**（之前交接文档报告的 `File exists` 锁文件问题可能已经被修复或数据目录残留已清理）。

### 13.3 P7-06 执行中用户询问（ask_user_question）——**已落地**

**目的**：补上"agent 停下来问用户"这个交互原语缺口。当前金月的"用户在环"只有审批（工具风险拦截），没有决策询问（执行中遇到分叉停下来让用户选方向、缺信息时向用户要）。

**对比业界**：
| 实现 | 方式 | 用户体验 |
|---|---|---|
| DeepSeek Harness `@deepseek-ai/dsh-tool-ask-user` | 模型可调用的 `ask_user_question` tool，schema 包含 `{ questions: [{ id, question, options, multi_select }] }` | 弹出结构化选项卡（label + description）；tool call 挂起直到用户回答，不超时；子 agent 不可用（DELEGATED_CALLER） |
| Trae | 系统级 `AskUserQuestion` 指令 + `browser_waiting_for_user_interaction` | 弹出选项卡 + 自由文本输入；agent 进入等待状态直到用户确认/取消 |
| 金月现状 | 只有审批（WAITING_APPROVAL） | ❌ agent 遇到分叉只能自己选、缺信息只能报错结束 |

**为什么必要**：这不是 P&E（Plan-and-Execute）那种"架构选型"，而是**缺失的交互原语**——就像 Trae 和 DHS 都有但金月没有的东西。它能解决 P&E 能解决的问题还加上 P&E 解决不了的（执行中分叉就地停下来问、需要外部信息时等用户提供）。

**实现范围**（约 5~7 个文件，复用已有基础设施）：

后端：
- `AgentRunService.java`：`RunState` 枚举新增 `WAITING_USER_INPUT`（和已有的 `WAITING_APPROVAL` 并列，不改动审批语义）
- `ReActAgentService.java`：循环里加控制流——检测到模型输出需要用户输入（通过新增的 `ask_user_question` tool 或 system 指令触发）→ 推 `event:user_question` → 挂起 Run → await 用户回答后恢复循环
- `AgentChatController.java`：新增 `POST /api/agent/runs/{runId}/answer` 端点，携带用户回答恢复 Run
- SSE 事件类型：新增 `event:user_question`（含 id、question、options、header、multi_select），已有的 `event:approval_required` 不改动

前端：
- 新增 `AskUserCard.vue` 组件（复用 `ApprovalCard.vue` 的选项卡 UI 结构：label + description、J/K 切换、自由文本输入）
- `ChatView.vue`：监听 `event:user_question` 事件 → 弹出 AskUserCard → 用户选择后调 `/answer` 端点

数据库：不需要新表——question 存 RunState + event 流里，和审批一样通过 `agent_run_event` 表查询。

**决策**：ask_user_question 作为**模型可调用的 tool** 实现（和 DHS 同思路），而不是系统级指令——这样 tool schema 作为 tool catalog 一部分可见，KV cache 友好，且可以在 system prompt 里引导模型"遇到不确定方向时调用这个 tool"。

**非目标**：
- 不做"agent 随时可以问、问了就停"——需要预定义触发条件（scope 扩大、方案分叉、缺关键信息）
- 不做子 agent 询问——只由 root agent 调用（和 DHS 同约束）
- 不做超时自动续行——等待上限 15 分钟，超时返回错误让模型自己选稳妥方案继续；等待期间按 500ms 分片响应 Run 停止

**落地结果**（完整实现与缺陷清单见 [COMPLETED_WORK.md](COMPLETED_WORK.md) 第 11 节，设计契约见 [phases/phase7/p7-06-ask-user-question.md](phases/phase7/p7-06-ask-user-question.md)）：
- 后端：`AskUserQuestionTool`（挂起/恢复 + 分片轮询取消）、`AskUserQuestionSchemaTool`（模型可见的 schema）、`AgentRunService` 新增 `WAITING_USER_INPUT` 迁移与 `abandonUserQuestion`（超时/中断把 Run 拉回 RUNNING）、`POST /api/agent/runs/{runId}/answer` 端点、`ToolRegistry` 在无 Run 上下文时不暴露该 tool
- 前端：`AskUserCard.vue` 选项卡（单选/多选/自由文本/防重复提交）、SSE `user_question` 事件、断线重放 `USER_QUESTION`/`USER_ANSWERED` 还原卡片、Run 结束与停止时清理挂起卡片
- 提示词：`coding` / `agent` 两个 system prompt 增加【决策询问】触发规则（只在真实分叉时问、先做只读调研、一次一问 + Recommended、严格按选择执行）
- 验证中修复的缺陷：只读并行批在用户回答前抢跑、询问超时后 Run 状态泄漏、`options.items.required` 误写成 schema 对象被 DeepSeek 400 拒绝、`RunDisconnectHttpAcceptanceTest` stub 旧签名导致全量套件挂起
- 测试：后端全量 **388 tests / 0 失败 / 0 错误 / 16 跳过**；`AskUserQuestionReActIntegrationTest` 5 项覆盖真实 ReAct 循环；前端 Vitest 99 项通过
- 真实模型冒烟：`AskUserQuestionLiveSmokeTest` 经用户授权以 `deepseek-flash` 复跑通过（16.2s，COMPLETED），schema 缺陷修复后的真实链路已闭环。
- 仍未做：出口 4（L1/L2/L3 数据集前后对比）归入 P7-02 基线一起跑。

**出口**：
1. ReAct 循环能在执行中停下来向用户问选择题 + 自由文本
2. 用户回答后 Run 恢复，答案进入模型上下文继续循环
3. 断网/刷新后 WAITING_USER_INPUT 状态可恢复（和 WAITING_APPROVAL 共享重连机制）
4. 同数据集（L1/L2/L3）P7-06 前后对比：完成率、轮次、token 不退化 ≥ 5%

**与 P7-03 plan-first 的关系**：P7-06 落地后，P7-03 的触发门槛调高——很多"执行中需要方向确认"的场景已被 ask_user_question 覆盖，plan-first 只在**任务起始**就需要"先完整规划给用户看"时才有价值。

**依赖**：无——不依赖 P7-02 真实模型基线，不依赖 P7-03/P7-04 的条件触发。可并行开发。

### 13.4 P7-02 长会话量化基线（已完成，2026-11-20 决策：规则 3 命中 → 优先 P7-04）

**目的**：用一组固定任务与真实模型跑出可复现数字，判定长会话问题的主要归属，避免先改架构后找理由。

**执行轮次与缺陷清理**

| 轮次 | 数据 | 暴露缺陷 | 修复 |
|---|---|---|---|
| 首轮 L1/L2/L3×3（2026-11-19） | L1 2/3、L2 2/3、L3 0/3 | prompt 占位符 `{first}`/`{last}` 未替换；ChatClient 绕过 ToolRegistry.dispatch | 已修复并补区间替换回归测试 |
| 第二轮 L2+L3×3（2026-11-20 上午） | L2 1/3、L3 0/3 | 协议级产品缺陷：并行写调用时诊断 user 消息插在同组 tool 响应之间 → provider 400 | 已用 `ReActToolCallProtocolTest` 离线复现并修复，全量 398 tests 通过 |
| 第三轮 L3×3（2026-11-20 下午） | **L3 0/3，100% MEMORY_MISSING** | 无新缺陷，确认所有协议/工具链缺陷已清除 | — |

**有效数据**（按方案 A，剔除被占位符污染的第二轮 L2）：

| Level | runs | 成功率 | 失败归因 |
|---|---:|---:|---|
| L1×3（首轮） | 3 | 2/3 | 1× RUN_MAX_ROUNDS（无记忆摘要路径） |
| L2×3（第二轮） | 3 | — | **剔除**（占位符污染） |
| L3×3（第三轮） | 3 | **0/3** | **全 MEMORY_MISSING**，摘要真实注入 26→39→53 次 |

**判定**

- 规则 2（计划漂移/无进展/步骤遗漏 ≥ 50% → P7-03）：4 个失败样本中计划类占 2 个（L1 的 MAX_ROUNDS + L3 run #1 的 NO_PROGRESS），但两个"计划类"失败均在**记忆缺失背景下**发生；P7-06 ask_user_question 已覆盖大部分方向确认场景。**不触发 P7-03**（延后到 P7-04 后重新评估）。
- 规则 3（记忆缺失 → P7-04）：L3 三次 100% 挂在 `memoryProbe=0, required=1`，但 **errors 空、工具链全通、60 工件全改完、runStates 全 SUCCEEDED**——唯一失败原因是模型记不住第一个 turn 的关键决策。**触发 P7-04**。

**根因诊断**：当前检查点摘要本质是**进度摘要**（104 字符："已完成 N turns，改了 M 个文件"），不是**记忆摘要**——早期 turn 的方向决策从未进入摘要。模型即使拿到 53 次摘要，拿到的都是"推进到哪了"，不是"为什么选了这条路"。

完整决策见 [phases/phase7/p7-02-decision.md](phases/phase7/p7-02-decision.md)。

**数据集**（`eval/long-session-datasets/`）：L1 单模块重构（≥ 20 轮）、L2 跨文件修复（≥ 40 轮）、L3 超长会话（≥ 100 轮累积上下文）——schema 和离线验证已完成。

**指标**：完成率与完成原因、轮次、工具调用次数、工具失败/重试次数、无进展检测触发次数、prompt/completion tokens、上下文峰值 token、端到端耗时、首 chunk 时延、计划步骤完成率、`update_plan` 调用次数（近似重规划次数）。

**预注册判定规则**（已写死，跑完即得出结论）：
1. stats p95 > 500ms（1000 条会话）→ 做 P7-02a 缓存/物化，不动循环。**规则 1 未命中（已完成）**。
2. 长任务失败 ≥ 50% 归因「计划漂移 / 无进展 / 步骤遗漏」→ 立项 P7-03。**规则 2 边缘命中但不触发**（见决策文档）。
3. 失败主要由上下文截断、记忆缺失或历史重复导致 → 优先 P7-04。**规则 3 命中（2026-11-20）**。
4. 三项均未命中 → 长会话不立项，回到发布收尾与体验打磨。

**出口**：`eval/runtime-reports/long-session-<date>.json` 落盘，含原始数据、按规则得出的结论与不做的理由；同步 `STATUS.md` / `COMPLETED_WORK.md`。

**阻塞**：已解除——第三轮 L3×3（`long-session-20261120-140053-771.json`）确认协议修复 + 摘要路径均通，可用样本已足够。

### 13.5 P7-03 plan-first（不立项，2026-11-21 决策）

**结论：不引入 `AgentPolicy.PLAN_FIRST` 控制流档位。** 理由：

1. P7-02 规则 2 仅边缘命中（2/4 失败样本），且两个"计划类"失败均发生在记忆缺失背景下；P7-04 修复后 L3 成功率 0/3→2/3，佐证"跑偏"表象主要是记忆问题，不是计划能力本身。
2. P7-06 ask_user_question 已覆盖大部分"执行中需要方向确认"的场景。
3. 行业主流（OpenAI Agents SDK、LangGraph、Claude Code、Cursor）均以 ReAct 循环为默认；plan 以软提示/上下文形式存在，先出完整计划的纯 P&E 仅用于可预测分解的高步数场景，不适用于探索性编码任务。
4. 剩余 `planCompletion` 边界（最后一步被标 INCOMPLETE）本质是计划状态跨 turn 丢失，由 P7-07 计划持久化解决，不需要 plan-first。

原 plan-first 计划内容已移除，替代工作见 13.9（P7-07）～13.11（P7-09）。

### 13.6 P7-04 上下文预算与记忆（已完成，2026-11-20）

**触发原因**：P7-02 第三轮 L3×3 三次 100% 挂在 `memoryProbe=0, required=1`，但 errors 空、工具链全通、60 工件全改完、runStates 全 SUCCEEDED——唯一失败原因是模型记不住第一个 turn 的关键决策。

**修复**：[LongSessionLiveRunner.java](file:///d:/test-code/agent-gateway-v3/src/test/java/com/capsule/agentkey/eval/longsession/LongSessionLiveRunner.java#L438-L465) 的 `checkpointSummary` 从进度摘要（104 字符）升级为记忆摘要三层结构：
1. **记忆锚点**（来自数据集 `memory.anchor`）：让模型在长会话后仍能回答早期决策
2. **已完成文件区间**（累积 `Turn.first`/`Turn.last`）：让模型知道哪些文件已处理
3. **进度计数 + 防重复约束**：保留原有

**真实模型验证（L3×3，report `long-session-20261120-152022-008.json`）**：

| 指标 | 修复前 | 修复后 | 变化 |
|---|---|---|---|
| memoryProbePassed | **0/3 (0%)** | **3/3 (100%)** | ✅ |
| 成功率 | 0/3 | **2/3 (66.7%)** | ✅ |
| maxSummaryChars | 104 | 245 | +136 字符 |
| promptTokens 中位数 | 1.02M | 1.05M | **+3.06%**（远低于 15% 阈值） |

**剩余边界**：第三个 run 失败于 `planCompletion=80, required=100; incompleteTurns=1`——计划面板把最后一步标记为 INCOMPLETE 而非 COMPLETED，与记忆无关，本质是计划状态跨 turn 丢失，归入 **P7-07 计划持久化**一起处理。

**不在本次范围**：生产代码的摘要路径（生产 `memorySummary` 参数始终为空，无摘要逻辑）、工具输出截断（prompt 峰值 1.44M tokens 的预算问题，单独列为 P7-04b）。

### 13.7 P7-05 桌面 SSE 端到端自动化

**已完成（2026-11-19）**：
- 浏览器层：`desktop-sse.spec.ts` 4 条用例覆盖桌面令牌引导、完整 SSE 事件序列、审批后继续、断流按 runId 恢复及失败终态。
- 真机链路：desktop profile + 内嵌 PG + 打包态 jar + 本地令牌，`POST /api/agent/chat/stream` 返回 **200 + Content-Type: text/event-stream**，后端日志零 `AuthorizationDeniedException`。

**验收边界**：浏览器层测试运行在 Vite + Chromium 内核浏览器（本机使用 Edge 通道），后端 API 与 SSE 由 `page.route` 仿真，不是真实 Electron + Java + 内嵌 PostgreSQL 链路。真实桌面窗口与侧车冒烟继续单独验收，不能以本结果代替安装包或真实模型验收。

### 13.8 排期（顺序 + 门禁，2026-11-21 决策更新）

> **2026-11-21 重规划**：以下 P7 排期已被第 14 节「架构重构总纲」取代。P7-07 并入 R1/R5，P7-08 并入 R4，P7-09 并入 R5；Phase 8 并入 R7。本节保留为设计依据，不再作为执行队列。

```text
─── Phase 7 已收口（2026-11-20）──────────────────────────────
P7-01 重启残局收口 ──────────────── ✅ 已完成
P7-02 统计成本切片 ──────────────── ✅ 已完成（规则 1 未命中）
P7-02 L1～L3 离线验证 ───────────── ✅ 已完成（schema + fixture + 门禁全过）
P7-02 L1～L3×3 真实模型正式基线 ─── ✅ 已完成（三层均 2/3，工具链 0 error）
P7-04 记忆摘要升级 ──────────────── ✅ 已完成（memoryProbe 0/3→3/3）
P7-04b 工具输出按档位截断 ────────── ✅ 已完成（FAST=500 / STANDARD=1000 / DEEP=2000）
P7-04c 框架级 plan 注入 ──────────── ❌ 已尝试并回滚（L2 2/3→1/3，ThreadLocal 跨 turn 陈旧）
P7-05 SSE 真机链路 ──────────────── ✅ 已完成
P7-06 ask_user_question ─────────── ✅ 已完成（含真实模型冒烟）
P7-03 plan-first ────────────────── ❌ 不立项（2026-11-21，见 13.5）

─── Phase 7 收尾改进（待执行，2026-11-21 排定）──────────────────
P7-07 计划持久化与软回注 ────────── ⏭ 第一个（杠杆最大，先做 L2 A/B）
P7-08 停滞渐进式恢复 ────────────── ⏭ 可与 P7-07 并行（低风险、独立）
P7-09 长会话上下文压缩 ──────────── ⏸ 排在 P7-07/08 之后（依赖 L3 基线与 token 指标）
```

**排期理由**：
- **P7-07 优先**：直接对应 L2 的 STEP_OMISSION 漏项，杠杆最大；但它和已回滚的 P7-04c 形似，必须先证明"持久化权威源"与"ThreadLocal 陈旧注入"效果不同，否则不上。
- **P7-08 可并行**：完全独立于 plan 状态，只改停滞检测的恢复策略，风险最低，可与 P7-07 同时开发。
- **P7-09 最后**：依赖 P7-07/08 落地后的 token 与记忆指标做对照，且可能与 `SessionMemoryAdvisor` 摘要路径交互，放最后避免混淆归因。

**统一门禁**：每项都以 L1～L3 数据集 A/B 验证，预注册「完成率不降、prompt/耗时退化 < 15%」（沿用 P7-04 口径）；不满足即回滚，不进入默认路径。

### 13.9 P7-07 计划持久化与软回注（拟立项，2026-11-21）

**触发原因**：P7-04c 证明"把计划摘要硬注入每轮"在 ThreadLocal 陈旧时会误导模型。但问题不在注入本身，而在**计划状态的存储与生命周期**。当前 `update_plan` 只写 [WorkspaceContext](file:///d:/test-code/agent-gateway-v3/src/main/java/com/capsule/agentkey/tool/WorkspaceContext.java) 的 ThreadLocal，跨 turn 不共享、不持久化、也不回注模型，只推前端。

**方案（plan-as-context，非 control-flow）**：
1. 新增会话级 `PlanStore`（key = runId/sessionId），`update_plan` 成功时写库（复用 `agent_run` 存储或轻量 plan 列/表，不做 Controller→Service→DB 三层大重构）。
2. 每轮 `streamChat` 前从 `PlanStore` 读**最新快照**，渲染成 `Current plan:` 文本，作为软提示注入 system prompt 末尾或 user message 末尾；**不拦截工具调用、不做控制流**。
3. `update_plan` 成功后额外追加一条"plan updated"事件到聊天历史，作为双保险（即使主动注入遗漏，历史里也有最新快照）。

**与 P7-04c 的关键差异**：注入源从"ThreadLocal 陈旧对象"改为"持久化存储的单一事实源，每轮重读"，从根本上消除陈旧错位。

**参考实现（已核实源码）**：
- OpenHands TaskTrackerTool：`TASKS.json` 落盘 + 会话局部工具按需重载（[definition.py L140-178](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-tools/openhands/tools/task_tracker/definition.py)）；事件日志每轮 `View.from_events()` 重建（[state.py](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-sdk/openhands/sdk/conversation/state.py)）。
- Cline plan 模式软提示：`planModeInstructions()` 追加到 system prompt（[responses.ts L223-226](https://github.com/cline/cline/blob/main/apps/vscode/src/core/prompts/responses.ts)）。

**非目标**：不做 `PLAN_FIRST` 控制流；不做多智能体/状态图；不改现有审批、回溯、审查流语义。

**验证与出口**：先只在 L2（scope 变化场景）跑 A/B，预注册"成功率 ≥ 2/3 且无回退、token 退化 < 15%"；同时确认不再出现"aborted 步骤被误判 completed"。L2 通过后推到 L1/L3。

**改动范围**：`UpdatePlanTool`、`WorkspaceContext`（或新增 `PlanStore`）、`ReActAgentService`（注入点）、`AgentRun` 持久化与对应迁移；测试 `UpdatePlanToolTest` + 新增 L2 回注回归。

### 13.10 P7-08 停滞渐进式恢复（拟立项，2026-11-21）

**触发原因**：L1 出现 STAGNANT_ROUNDS——模型陷入重复 `search_files` 循环，当前 `ReActAgentService` 在 `maxRepeatedToolCalls=3` 或 `maxStagnantRounds=4` 时**直接 stop 收尾**（[ReActAgentService.java](file:///d:/test-code/agent-gateway-v3/src/main/java/com/capsule/agentkey/service/ReActAgentService.java#L93-L98)），把一次本来可恢复的失败变成硬失败。

**方案（渐进式 nudge，而非直接 stop）**：
1. 按 `(工具名, 失败类型)` 维度维护 `consecutiveFailureCount`。
2. 分级注入软提示：第 1 次→提示可能原因；第 2 次→强烈建议换方案；第 3 次→CRITICAL 禁止再重试该工具，要求换策略或调用 `ask_user_question`；给 1 轮自救窗口。
3. 出现任何进展（工具成功且产出变化、plan 推进、新 diff）时计数清零；仍无进展才 stop 或转 ask_user_question。

**参考实现（已核实源码）**：
- Cline 三档分级 nudge：`writeToFileMissingContentError(consecutiveFailures, ...)`（[responses.ts L37-79](https://github.com/cline/cline/blob/main/apps/vscode/src/core/prompts/responses.ts)）。
- OpenHands 阈值化检测 + 显式 `STUCK` 状态交还用户（[state.py L53](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-sdk/openhands/sdk/conversation/state.py)）；四维阈值 `StuckDetectionThresholds`（[types.py](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-sdk/openhands/sdk/conversation/types.py)）。

> 未核实项：OpenHands 在置 `STUCK` 前是否先注入 nudge（`local_conversation.py` 过大未取完整上下文）。P7-08 以 Cline 已核实的渐进式方案为主。

**非目标**：不改变既有阈值语义（保留硬 stop 作为最后兜底），只在其前增加恢复窗口。

**验证与出口**：L1 A/B（重复循环场景），预注册"成功率 ≥ 2/3、轮次/token 退化 < 15%"；确认 nudge 不会引发新的重复循环。

**改动范围**：`ReActAgentService` 停滞检测分支（阈值前插 nudge）；`AgentPolicy` 可选新增 nudge 档位；测试补 nudge 分级与计数清零回归。

### 13.11 P7-09 长会话上下文压缩（拟立项，2026-11-21）

**触发原因**：P7-04b 只做了**单条**工具输出截断，transcript 整体仍随 turn 线性增长（L3 prompt 峰值曾达 1.44M tokens），长会话成本与"早期约束被淹没"问题仍在。

**方案（语义裁剪 → token 滑窗 → LLM 摘要，三层）**：
1. **语义裁剪（先行，成本最低）**：旧文件窗口 / 旧 diff 折叠成一行占位（`"Outdated window with N lines omitted..."`），只保留最近一次。
2. **token 滑窗兜底**：保留首条 SystemMessage，从 index 1 起 FIFO 淘汰；淘汰带 tool_calls 的 AiMessage 时**连带清理孤儿 ToolExecutionResult**（否则 provider 报错）。
3. **LLM 摘要压缩**：上下文占用达阈值（如 70%）时，保留 system + 最后 user + 最后 tool 结果，中间历史摘要成一条 user message 插到前部；摘要结果持久化，避免每轮重复摘要。

**参考实现（已核实源码）**：
- OpenHands RollingCondenser：70% 触发、保留集、摘要插 index 2、`Condensation` 事件持久化（[condenser/base.py](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-sdk/openhands/sdk/context/condenser/base.py) + [架构文档](https://docs.openhands.dev/sdk/arch/condenser.md)）。
- LangChain4j `TokenWindowChatMemory`：保留首条 SystemMessage + 孤儿 tool result 清理（[L162-188](https://github.com/langchain4j/langchain4j/blob/main/langchain4j/src/main/java/dev/langchain4j/memory/chat/TokenWindowChatMemory.java)）。
- SWE-agent `history_processors.py`：`LastNObservations` / `ClosedWindowHistoryProcessor` 语义裁剪（[history_processors.py](https://github.com/SWE-agent/SWE-agent/blob/main/sweagent/agent/history_processors.py)）。

**非目标**：不替换 `SessionMemoryAdvisor`；不做跨会话记忆迁移。

**验证与出口**：L3 A/B，预注册"prompt tokens 峰值下降、memoryProbe 不退化、成功率不降"；摘要持久化需验证不重复压缩、不丢关键早期决策。

**改动范围**：新增 `ContextCondenser`（或等价服务）；`ReActAgentService` 的 `streamChat` 前调用；摘要持久化到会话存储；测试补压缩正确性 + 孤儿 tool result 清理回归。

**依赖**：排在 P7-07/P7-08 之后，避免与 plan 回注、停滞 nudge 的归因混淆。

### 13.12 Phase 8 候选：长任务能力验收标准与建设（2026-11-21 提出，未立项）

**定位**：P7-07～P7-09 解决的是当前数据集上的三个具体失效点，属于长任务的**必要条件**，不等于已经具备长任务能力。本节把"长任务能力"拆成可验收标准并列出建设项；Phase 8 是否立项取决于 P7-07～P7-09 的实测结果。

#### 13.12.1 验收标准（Definition of Long-Task Capability）

| # | 维度 | 验收指标 | 当前状态 |
|---|---|---|---|
| L1 | 目标保持 | 连续 ≥30 turn 不丢关键决策与约束，memoryProbe ≥ 95% | 🟡 已补 30 turn / 150 工件数据集（10 replicas，仅离线校验）；真实模型完成率与 memoryProbe 尚待运行 |
| L2 | 计划跟踪 | 计划偏离 / scope 变化后 2 轮内发现并重规划，不再出现 STEP_OMISSION | 🟡 依赖 P7-07 |
| L3 | 错误恢复 | 工具失败 / 停滞 2 轮内恢复；无法恢复时转 ask_user，而非硬停 | 🟡 依赖 P7-08 |
| L4 | 成本可控 | 上下文占用随 turn 近似线性，prompt 峰值不超窗口 70% | 🟡 依赖 P7-09 |
| L5 | 完成可信 | 完成状态必须通过 Verify / 诊断确认，不接受模型自报 | 🟡 R7c 已加验证命令自动探测：同仓库多个构建文件（pom/gradle/npm/go/cargo/tsc）按固定顺序全部执行，最多 3 条、相同命令去重；没有构建文件时对**纯 Java** 工作区做有界全量源码编译（深度 12 / 2000 文件 / 10 MiB，超限与混合语言降级 UNAVAILABLE）；计划步骤可声明工件/验证证据契约，completed 与最终收口都重新核对。仍缺：无构建文件的混合语言目录需显式配置 |
| L6 | 中断续跑 | 进程重启 / 断线后从最近 checkpoint 续跑，不从头再来 | 🟡 R7b 已完成 `RUNNING` 崩溃后的手动续跑（真实 `taskkill` → 重启 → resume E2E 通过）；审批中 / 等待用户输入仍终止，未做自动续跑 |
| L7 | 大任务分解 | 超出单次 ReAct 窗口的任务可拆成子任务并分别验收 | 🟡 R7c 已实现串行子智能体（默认关闭、深度 1、最多 4 个、分别验收、断点续跑）；真实模型 A/B 未运行 |
| L8 | 评测可信 | 每层 n≥10 重复 + 真实任务集，结论有统计置信度 | 🟡 数据集已扩至每层 n=20，统计门禁已预注册；真实模型 n≥10 正式基线未运行 |

**判定规则**：L1～L8 同时达标才可对外宣称"具备长任务能力"；缺任一项只能宣称"中等长度任务稳定"。

#### 13.12.2 建设项（P8 候选）

| 编号 | 工作 | 对应缺口 | 依赖 | 优先级 |
|---|---|---|---|---|
| P8-01 | Run 级状态持久化与断点续跑（事件日志 + checkpoint + resume） | L6 | P7-07 | P0 |
| P8-02 | 完成判定强校验（Verify / 诊断作为完成门禁，禁止自报） | L5 | P7-07 | P0 |
| P8-03 | 大任务分解（子任务 / 分段执行 + 分层验收） | L7 | P8-01 | P1 |
| P8-04 | 评测集扩容（每层 n≥10 + 真实仓库任务集 + 统计门禁） | L8 | P7-07～P7-09 | P0 |
| P8-05 | 长历史检索型记忆（压缩之外的按需召回） | L1 / L4 | P7-09 | ✅ R7c 2026-11-21：压缩后本地确定性检索，默认关闭 |

#### 13.12.3 排期（候选）

```text
P7-07 → P7-08 → P7-09 收口并出报告
        ↓
P8-04 评测扩容 ─────────── 先建立可信测量（可与 P8-01 并行）
P8-01 Run 级持久化 + 续跑 ─ P0
P8-02 完成判定强校验 ───── 可与 P8-01 并行
P8-03 大任务分解 ───────── 依赖 P8-01，P1
P8-05 检索型记忆 ───────── P2，按实测瓶颈决定是否做
```

**立项门禁**：P7-07～P7-09 全部收口且 L1～L4 达标后才启动 Phase 8；即使 P7 三项使当前数据集稳定 3/3，也**不得**据此宣称长任务能力，必须走完 L5～L8。

#### 13.12.4 可借鉴的成熟实现（2026-11-21 核实）

- **DeepSeek Harness（DSH）**
  - 事件溯源会话日志是唯一事实源，`surface` 投影 + `session resume` 对账（[Context System](https://deepseekdocs.com/en/docs/learn/core/context)）。
  - **折叠而非截断**：`compaction/start → summary → end`，全量日志保留、模型只见摘要后的 surface；`tool-result-pruner` 把超预算结果改写为"有界头 + 省略标记 + 有界尾"（同上）。
  - `token-meter` 用可重放计量 + `projectedTokens` 驱动压缩决策（同上）。
  - 计划 / 团队策略作为 system prompt 固定 section（order 500 / 600），保持前缀字节稳定以复用 KV cache（同上）。
  - **子智能体隔离**：协调者工具面收窄为只读 + 委派 + 汇总，过程数据留在 Subagent 上下文，主会话只保留高价值信息（[dsh-multitask](https://www.npmjs.com/package/dsh-multitask)）。
- **Trae SOLO**
  - 自动拆解任务、确认计划后自动推进；多任务并行管理；对话流节点自动折叠生成摘要（[SOLO 模式概览](https://docs.trae.cn/ide/solo-mode)）。
  - 长任务有边界：工具调用、用量、权限、错误、完成均有上限，不是无限后台进程（[第三方评测](https://diyai.io/ai-tools/code-generation/reviews/trae-ai-review/)）。
- **OpenHands**：EventLog 为单一事实源 + Condenser 折叠，与 DSH 同构。

**对金月的直接启示**：
1. P7-07 的 plan 持久化应直接建在"事件日志 / 单一事实源"上，而不是再引入一个旁路存储——否则 P8-01 的续跑还要二次迁移。
2. P7-09 应采用"折叠 + 原始事件保留"而非真删，为 P8-05 的按需召回留数据。
3. P8-03 的大任务分解可参考 DSH 的"协调者收窄工具面 + 子智能体执行"，而不是让主循环无限延长。

- 每个批次以「出口」为界，不以日历工期承诺；结论必须来自落盘报告，而不是单测通过。
- **P7-06 不依赖 P7-02 结论**——它解决的是交互原语缺口，不是 ReAct 循环内部问题，已并行落地。
- **P7-03 plan-first 已不立项（2026-11-21）**；P7-07～P7-09 均保持 ReAct 循环不变，只做计划持久化、停滞恢复与上下文压缩，避免架构先行。

---

## 14. 架构重构总纲（2026-11-21 重规划，当前主线）

### 14.1 重规划依据

2026-11-21 完成五层只读审计（后端核心 / 工具层 / 持久化 / 前端 / 测试构建），完整证据见 [`ARCHITECTURE_AUDIT_20261121.md`](ARCHITECTURE_AUDIT_20261121.md)。核心结论：

1. `ReActAgentService` 1498 行，承担循环、策略、上下文、工具调度、停滞、计划收口、流式、持久化 8 类职责。
2. `WorkspaceContext` 以 ThreadLocal 承载 13 个混杂字段，跨 turn 无权威状态源（P7-04c 回滚根因）。
3. `agent_run_event.seq` 先查后写，并发写同一 run 会撞唯一约束。
4. 只读批与 `ask_user_question` 绕过审批 / scope 检查。
5. 前端 `useChatStore` 过大，断线恢复只覆盖部分事件。
6. 测试约 400 项，但主循环缺可重复 scripted 回归；评测 n=3。

**决策：停止逐点补丁。** P7-07～P7-09 不再单独排期，作为重构接缝的验收项落地；Phase 8 并入 R7。

### 14.2 目标架构原则

| # | 原则 | 落地要求 |
|---|---|---|
| 1 | 单一事实源 | `agent_run_event` 是运行事实源；`plan_json` 等只是投影/快照，可重建 |
| 2 | 依赖方向 | `controller → orchestration → context/tool/stream/run → tool/mcp`，基础设施不反向依赖领域 |
| 3 | 状态归属 | 传输参数、领域状态、UI 推送标记、统计四类分离；跨 turn 状态必须进 `RunContext` |
| 4 | 接缝优先 | 新功能落在稳定接口上，不继续写进 God Class |
| 5 | 行为保持 | 每批重构不改外部行为；需改行为时单独开关 + A/B |
| 6 | 可回滚 | 每批独立 commit，保留旧路径开关，测试不过立即回滚 |

### 14.3 目标结构

**后端**

```text
agent/
  orchestration/   AgentOrchestrator, LoopGuard, Budget, StopReason
  context/         ContextAssembler, PlanSnapshotProvider, MemoryPort, ContextCondenser
  tool/            ToolContract, ToolContext, ToolExecutor, ApprovalGate, FileChangeService
  stream/          StreamPublisher, SseEventMapper
  run/             RunStateService, RunContext, RunEventStore
tool/              各具体工具（只实现 ToolContract）
mcp/               MCP 适配器
```

**前端**

```text
api/          sse.ts（SSE 协议解析 + 建连/鉴权） + agent.ts（REST）
services/     streamService（事件归一化）/ streamSession（中断句柄 + 恢复代际）/ runRecovery（事件折叠）/ sendMessageService（发送与流式收尾编排）
stores/       chat（会话与兼容 facade）/ messages / stream / approvals / question / fileChanges
utils/        taskStages（阶段时间线与失败分级纯函数）
composables/  useVirtualMessages（消息虚拟窗口）；其余交互仍在 ChatView
views/        ChatView（布局与挂载；暂仍承担较多交互编排）
```

### 14.4 状态归属矩阵

| 状态 | 生命周期 | 归属 | 持久化 |
|---|---|---|---|
| userId / projectId / cancelled | 单次调用 | `ToolContext` | 否 |
| runId / sessionId / turnId | 单次 run | `RunContext` | run 表 |
| plan / scope | 跨 turn | `RunContext` | `plan_json` + `PLAN_UPDATED` |
| diagnostics / verify | 跨工具批次 | `RunContext` | 事件（可选快照） |
| 停滞 / nudge 计数 | 单 turn | `LoopGuard` | 否（必要时 `NUDGE` 事件） |
| 文件变更 | 单 turn | `FileChangeService` | `pending_file_change` |
| 审批 | 跨线程 | `ApprovalGate` | `tool_approval` |
| SSE 连接 | 单连接 | `StreamPublisher` | 否 |

### 14.5 重构批次（R0～R7）

| 批次 | 内容 | 出口（必须同时满足） | 回滚方式 |
|---|---|---|---|
| **R0 安全网** ✅ 2026-11-21 | `ScriptedAgentLoopTest`（固定模型响应驱动主循环）；`RunContext` 契约测试；seq 并发测试 | 覆盖 plan/停滞/审批/取消/恢复；全量测试绿 | 只增测试 |
| **R1 状态地基** ✅ 2026-11-21 | `RunContext` + `RunEventStore`；seq 原子化；`plan_json` 持久化；`PLAN_UPDATED` 事件 | 并发写无冲突；L2 计划可见；A/B 成功率不降 | 旧 ThreadLocal 路径开关 |
| **R2 Context 瘦身** ✅ 2026-11-21 | `WorkspaceContext` 只留传输参数；领域状态迁 `RunContext` | 全量测试绿；跨 turn 计划/scope 可见 | 保留兼容访问器 |
| **R3 工具接缝** ✅ 2026-11-21 | `ToolExecutor` + `ApprovalGate` + `FileChangeService`；只读批与 `ask_user_question` 纳入统一路径 | 审批/scope 无绕过（有测试证明） | 双执行器开关 |
| **R4a 循环与流接缝** ✅ 2026-11-21 | `LoopGuard` 统一五组无进展计数与分级 nudge（P7-08）；`StreamPublisher` 统一流式调用与预算 | 全量测试绿；重复调用先 nudge、超过自救窗口才停止（有测试） | 保留旧 streamChat 调用 |
| **R4b 编排壳** ✅ 2026-11-21 | `AgentOrchestrator` 抽离 `execute` 主循环；`RunTermination` 收口终态；`AskUserQuestionHandler` 接管挂起询问 | `ReActAgentService` 只留入口与协作者装配；行为等价；`AgentOrchestrator` 535 行 | 保留旧 execute |
| **R5 上下文接缝** ✅ 2026-11-21 | `ContextAssembler` + `ContextCondenser`；落地 P7-07 软回注、P7-09 三层压缩 | L2/L3 A/B 达标；token 峰值下降；原始事件保留 | 压缩可关 |
| **R6 前端拆分** ✅ 2026-11-21 | `SSEService` + store 拆分（含 messages）+ 发送编排 + 恢复补全 | 前端测试绿；断线恢复覆盖 plan/file_change/approval/question | 逐 store 迁移 |
| **R7 长任务能力**（进行中） | R7a 评测扩容 ✅ 2026-11-21；R7b Run 级断点续跑 ✅ 2026-11-21（手动触发、仅覆盖 RUNNING 崩溃，真实 kill→重启→resume E2E 通过）；R7b-live 真实模型续跑完成率 🔲 待跑：runner + 预注册门禁已落地，Key 冒烟 n=1 通过，L1 全量 20 对（约 3-4h）与 L2/L3（10h+）待执行；R7c 进行中：L5 完成强校验（验证命令自动探测 + 纯 Java 裸源码有界全量编译）✅ 2026-11-21，计划确定性证据契约 ✅ 2026-11-21，检索型记忆 ✅ 2026-11-21，大任务分解（L7）待做 | L1～L8 验收标准达标；R7b 单测、构建与真实崩溃续跑 E2E 均通过；R7c L5 自动探测、裸源码编译、计划证据契约与检索记忆单测覆盖 | 按项开关 |

### 14.6 与既有 P7/P8 的映射

| 原编号 | 去向 | 说明 |
|---|---|---|
| P7-07 计划持久化软回注 | R1（持久化）+ R5（注入） | 不再单独排期 |
| P7-08 停滞渐进恢复 | R4（LoopGuard） | 不再单独排期 |
| P7-09 上下文压缩 | R5（ContextCondenser） | 不再单独排期 |
| P8-01 Run 级续跑 | R7 | R7b 已落地：`checkpoint_json` 快照 + `CHECKPOINT` 事件 + 显式 resume；仍复用 `agent_run_event` |
| P8-02 完成强校验 | R7 | 复用 diagnostics/verify |
| P8-03 大任务分解 | R7 | 参考 DSH 子智能体 |
| P8-04 评测扩容 | R0 + R7 | R0 先建 scripted，R7 扩 n≥10 |
| P8-05 检索型记忆 | R7 | 依赖折叠保留原始事件 |
| P7-03 plan-first | 不立项 | 结论不变 |

### 14.7 执行纪律

1. **严格顺序**：R0 → R1 → R2 → R3/R4 → R5 → R6 → R7；不跳批、不并行改同一文件。
2. **测试先行**：每批先补能证明行为不变的测试，再动代码。
3. **一批一提交**：每批独立 commit，提交信息含批次号；不达标立即回滚。
4. **预注册门禁**：涉及行为的批次（R1/R4/R5/R7）先写阈值，后看数据；完成率不降、token/耗时退化 <15%。
5. **单一事实源**：任何新状态先问“事件流能否重建”，不能才加投影列。
6. **禁止大爆炸**：不做一次性全量重写；每批保持可运行、可回滚。
7. **文档同步**：每批完成更新 `STATUS.md` 与本节状态，报告落盘 `docs/phases/`。

### 14.8 架构验收（完成定义）

1. `AgentOrchestrator` 单类 ≤600 行，职责单一。
2. `WorkspaceContext` ≤80 行，只含传输参数。
3. 所有工具调用必经 `ToolExecutor`，审批/scope 无绕过（有测试）。
4. `agent_run_event.seq` 并发测试通过（N 线程写同一 run 无冲突）。
5. 前端单 store ≤400 行；断线恢复覆盖 plan/file_change/approval/question。
6. 核心链路有 scripted 回归，不依赖真实模型即可验证行为。
7. 后端全量测试 + 前端 Vitest/Playwright 全绿。

### 14.9 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| ReAct 行为漂移 | 高 | R0 scripted 回归 + 每批 A/B |
| ThreadLocal 迁移遗漏 | 高 | 兼容访问器 + 逐字段迁移 + 全量测试 |
| seq 原子化影响事件重放 | 中 | 先加唯一约束与并发测试，再切生成方式 |
| 前端 store 拆分状态不同步 | 中 | 逐域迁移 + facade |
| 一次改太多无法归因 | 高 | 严格批次顺序 + 独立提交 |

### 14.10 第一刀（R0 + R1 的具体落点）

1. `src/test/java/.../ScriptedAgentLoopTest.java`：用脚本化模型响应驱动 `ReActAgentService`，断言计划推进、停滞触发、审批挂起、取消清理。
2. 新增 `agent/run/RunContext.java`：持有 `runId/sessionId/turnId/plan/scope/diagnostics/verify`，以 `runId` 为 key。
3. 新增 `agent/run/RunEventStore.java`：封装事件追加；`seq` 改为数据库原子生成，保留唯一约束兜底。
4. `V22__add_agent_run_plan.sql`：`agent_run` 增加 `plan_json TEXT`（投影）。
5. `UpdatePlanTool` 写 `RunContext` + `RunEventStore`；`buildMessages` 读取快照并作为 user-role 消息注入（保护 system 前缀缓存）。
6. 先不改 `WorkspaceContext` 字段，只加迁移适配层，保证 R1 可独立回滚。

### 14.11 落地记录

#### R0 安全网（2026-11-21）

| 测试 | 覆盖 |
|---|---|
| `ScriptedAgentLoopTest`（4） | update_plan 推进 + 收尾把未完成步骤标 aborted；连续相同调用触发 `REPEATED_TOOL_CALL`；取消标记置位后不再请求模型并清理 ThreadLocal；续跑从 `plan_json` 恢复计划并以 user 消息注入 |
| `RunContextTest`（5） | 计划快照不可变、drain 一次即清空、持久化回调失败降级 |
| `RunEventStoreTest`（6） | 先锁 Run 行再分配 seq、空事件流从 1 开始、run 不存在拒绝追加、计划只更新 `plan_json` 列、计划读写与 JSON 损坏降级 |

#### R1 状态地基（2026-11-21）

- 新增 `agent/run/PlanStep`、`RunContext`、`RunEventStore`；`AgentRunEvent` 增加 `PLAN_UPDATED`。
- `V22__add_agent_run_plan.sql`：`agent_run.plan_json TEXT`。
- `AgentRunRepository`：`lockForEventAppend`（`PESSIMISTIC_WRITE`）+ `updatePlanJson`（只改投影列，禁止整行 save 覆盖并发状态）。
- `AgentRunService`：事件追加统一委托 `RunEventStore`，新增 `loadPlan`。
- `WorkspaceContext`：新增 RunContext 迁移适配层——有 RunContext 时 runId / plan / 取消标记同源，否则保留本地快照，R1 可独立回滚。
- `ReActAgentService`：执行前构建 RunContext、恢复持久化计划；`buildMessages` 以 user 消息注入计划快照，system 前缀不变，保护 provider 前缀缓存。
- 全量测试通过：**417 tests / 0 failures / 0 errors / 17 skipped**（含 `AgentRunServiceTest` / `UpdatePlanToolTest` / `ReActAgentServiceTest` / 断流与重启验收）。

#### R2 Context 瘦身（2026-11-21）

- `WorkspaceContext` 从 13 个状态字段缩为 2 个：`RunContext` 引用 + 文件变更缓冲；其余访问器全部委托，工具与测试调用签名不变。
- scope / diagnostics / diagnosticsFingerprint / diagnosticsFileVersion / verify / toolStats 迁入 `RunContext`；runId / sessionId / turnId 改为可补齐的 volatile 字段。
- 无外部 RunContext 时（单测 / MCP / 后台任务）由 3 参构造器创建合成 RunContext，消除「有状态 / 无状态」两套分支。
- 子上下文共享 RunContext，仅文件变更缓冲独立；`RunContextTest` 增至 8 例，新增 `WorkspaceContextTest`（2 例）锁定门面语义。
- 全量测试通过：**421 tests / 0 failures / 0 errors / 17 skipped**。

#### R3 工具接缝（2026-11-21）

- 新增 `agent/tool/ApprovalGate`：`assess` 纯判定风险与是否需要审批（档位 + MCP/fallback 强制 + 会话白名单），`dispatch` 是工具执行的唯一出口（先 scope 再 `ToolRegistry`），`rejection` 统一拒绝文案。
- 新增 `agent/tool/ToolExecutor`：串行路径完成「最新权限 → 审批 → dispatch」；`executeReadOnlyBatch` 只并连续只读调用，子线程同样经 `gate.dispatch`，不再存在绕过 scope 的旁路；`isParallelizable` 显式排除 `ask_user_question`、写工具与 fallback。
- 新增 `agent/run/FileChangeService`：接管变更 drain、诊断采集、指纹与文件版本回写，主循环只保留回调推送与无进展判定。
- `ReActAgentService`：删除 `readOnlyToolPool`、`commandOf`、`isParallelReadOnlyCall`、`executeReadOnlyBatch`、`dispatchWithScopeCheck`；只读批线程池迁入 `ToolExecutor`，`shutdownReadOnlyToolPool` 仅委托关闭。
- 新增测试：`ApprovalGateTest`（11 例：只读免审、MANUAL/AUTO/FULL、递归删除、MCP/fallback 强制、会话允许、scope 拦截与放行、非法 JSON、拒绝文案）；`ToolExecutorTest`（6 例：审批通过顺序、拒绝不执行、免审直达 gate、只读批经 gate 且下标对齐、单调用不并行、`ask_user_question`/写/fallback 不可并行）。
- 全量测试通过：**438 tests / 0 failures / 0 errors / 17 skipped**。

#### R4a 循环与流接缝（2026-11-21）

- 新增 `agent/run/LoopGuard`：把重复调用、重复失败、重复诊断、重复验证失败、停滞轮次五组计数器收敛为纯状态机，并落地 P7-08 渐进恢复：
  - 重复调用 / 失败：第 N-1 次软提示 → 第 N 次 CRITICAL → 第 N+1 次才停止（保留一轮自救窗口，默认 N=3）；
  - 诊断 / 验证 / 停滞：在既有停止阈值前一轮插入 nudge，阈值语义不变；
  - 任何真实进展（新签名调用成功、计划推进、文件变化）清零对应计数。
- 新增 `agent/stream/StreamPublisher`：一轮流式模型的唯一发布器，统一 chunk/usage 转发、tool_calls 聚合、错误传播与 token 超预算判定；主循环两处重复的 `streamChat` 收回一处。
- `ReActAgentService`：删除 `lastToolSignature`/`repeatedToolCalls`/`lastFailureFingerprint`/`repeatedFailures`/`seenToolSignatures`/`lastDiagnosticsFingerprint`/`repeatedDiagnostics`/`lastVerifyFingerprint`/`repeatedVerifyFailures`/`stagnantRounds`、`normalizeToolCall`/`normalizeFailure` 与两段匿名 `AgentStreamCallback`；新增 `toNoProgress` / `userMessage` 两个小工具。
- 行为变化（有意）：连续相同工具调用 / 相同失败由「第 3 次直接停止」调整为「第 3 次 CRITICAL + 第 4 次停止」，`ScriptedAgentLoopTest` 已同步并断言 CRITICAL nudge 确实进入模型上下文。
- 新增测试：`LoopGuardTest`（9 例：分级 nudge、参数顺序归一、签名切换重置、失败恢复、诊断/验证/停滞、阈值关闭）；`StreamPublisherTest`（3 例：分片与用量转发、超预算异常、流错误传播）。
- 全量测试通过：**450 tests / 0 failures / 0 errors / 17 skipped**。

#### R4b 编排壳（2026-11-21）

- 新增 `AgentOrchestrator`（535 行）：承接 `execute` 内的 ReAct 主循环——模型流式调用、工具批（只读并行 / 串行审批）、诊断、验证、计划推送、预算与无进展停止；`StreamPublisher` / `LoopGuard` 的调用顺序与终态载荷保持原样。
- 新增 `RunTermination`（148 行）：候选完成判定、诊断/验证证据选择、`NoProgress` 载荷、未完成计划标记 aborted；主循环只决定“何时结束”，终态由它决定。
- 新增 `AskUserQuestionHandler`（119 行）：解析 `ask_user_question` 参数、挂起等待、超时/取消后把 Run 从 `WAITING_USER_INPUT` 拉回 RUNNING；保持串行特判，不进只读批。
- `ReActAgentService` 从 1330 行降到 726 行：保留 public `execute` 重载、RunContext / 权限 / scope 初始化、RAG、消息装配、审批 UI 适配与线程池关闭；`truncateForContext` 以函数式接口注入编排器，配置仍留在 Service。
- 行为等价：轮次上限、时间 / token 预算、回调顺序、tool 消息插入位置、停止原因字符串与 SSE 载荷均未改变；异常仍由 Service 统一收口计划后 `onError`。
- 全量测试通过：**450 tests / 0 failures / 0 errors / 17 skipped**。

#### R5 上下文接缝（2026-11-21）

- 新增 `agent/context/PlanSnapshotProvider`：计划快照读取契约；实现优先读 `agent_run.plan_json`，为空或读取失败时回退内存 `RunContext`，每轮调用一次，消除 P7-04c 的 ThreadLocal 陈旧源。
- 新增 `agent/context/ContextAssembler`：把最新计划渲染为 `Current plan:` 软提示，插在 system 之后；只修改本轮发送副本，不拦截工具、不做 plan-first。开关 `agent.context.plan-injection.enabled`，默认关闭。
- 新增 `agent/context/ContextCondenser`：三层压缩——同一文件旧读取折叠为占位、达到 70% 阈值时把中段历史摘要成一条 user 消息、仍超预算时 FIFO 滑窗；摘要按 run 缓存避免重复压缩；滑窗保留首条 system，并连带清理孤儿 tool 响应。开关 `agent.context.condense.enabled`，默认关闭。
- `AgentOrchestrator`：主循环与轮次耗尽后的收尾调用都改用装配后的消息副本；`ReActAgentService` 负责按开关构建装配器，注入最新计划读取入口与同步摘要调用，两开关都关闭时传 null，保持原路径。
- 行为保持：默认关闭时消息列表、工具顺序、终态载荷与既有测试零改动；压缩不删除 `agent_run_event`、数据库聊天历史或前端事件，只是发给模型的副本变短。
- 新增测试：`ContextAssemblerTest`（5 例：关闭不读取、每轮重读、读取失败降级、空计划、无 system 时插入位置）；`ContextCondenserTest`（8 例：只留最近读取、过短/单次不折叠、摘要生成与复用、摘要异常降级、滑窗保留 system、孤儿 tool 清理、参数钳制）；`ScriptedAgentLoopTest` 新增 1 例，断言两轮分别看到 update_plan 前后的持久化快照。
- 全量测试通过：**464 tests / 0 failures / 0 errors / 17 skipped**。
- **未执行项（如实记录）**：L3 与多次重复的 A/B 未执行；L2 已用真实模型跑单次对照，见下节。两个开关仍默认关闭。
- **执行项（2026-11-21，deepseek-flash，L2×1）**：
  - A 组（plan-injection=off / condense=off）：45 calls、262,196 tokens、峰值 prompt 9,462、update_plan 10 次、planCompletionRate 0.286、终态失败（HISTORY_REPETITION）、447s。
  - B 组（plan-injection=on / condense=on / window=30k 字符）：36 calls、215,805 tokens、峰值 prompt 9,042、update_plan 12 次、planCompletionRate 1.000、终态失败（HISTORY_REPETITION）、225s。
  - 计划软注入有正向信号：planCompletionRate 0.286 → 1.000；但两组都仍因历史重复失败，说明计划连续性不能单独解决该失败归因。
  - token/耗时/调用数下降混入了模型随机轨迹（单次重复），峰值 prompt 仅 -4.4%，**不足以证明压缩收益**；摘要调用走非流式接口，其 usage 未计入报告，对 B 组略有利。
  - 压缩是否实际触发未能从 Maven 日志确认（中文日志在控制台被转码）。正式结论需要 ≥3 次重复与 L3 数据。
  - 复现：`P7_02_LEVELS=L2 P7_02_REPEATS=1 P7_02_PLAN_INJECTION=on P7_02_CONDENSE=on P7_02_CONDENSE_WINDOW_CHARS=30000`。

#### R6a 断线恢复补全（2026-11-21）

- 新增 `services/runRecovery.ts`：把断线重连的事件折叠抽成纯函数 `replayRunEvents`，覆盖 MODEL_CHUNK / TOOL_CALL / TOOL_RESULT / PLAN_UPDATED / APPROVAL_REQUESTED / APPROVAL_RESOLVED / COMPLETION / DIAGNOSTICS / VERIFY / USER_QUESTION / USER_ANSWERED；`chat.applyRunEvents` 改为调用它。
- `recoverRun` 重放后还原 `livePlan`、`pendingApprovals`、询问卡片与 `recoveredTruncated`；已结束或已处理的审批不再把过期卡片带回界面。
- 新增 `services/runRecovery.test.ts`（8 例）；chat store 增加「断线恢复还原计划与文件变更」用例。

#### R6b-1 SSEService（2026-11-21）

- `api/sse.ts` 只保留协议层：`parseSSE` 与 `openChatStream`（建连 + 401 抛 `SseAuthError` + 非 2xx 错误文案）。
- 新增 `services/streamService.ts`：`StreamHooks` 契约与 `streamChat` 事件归一化；登录过期仍清理本地凭证并跳转登录页，行为与旧实现一致。
- 新增 `services/streamSession.ts`：接管原 chat store 模块级 `currentAbort` / `recoveryToken`，提供 `begin/release/abort/isActive` 与 `newRecoveryToken/isRecoveryCurrent/cancelRecovery`；旧流晚到的 `release` 不会覆盖新流。
- 新增测试：`streamSession.test.ts`（4 例）、`streamService.test.ts`（2 例，从 `sse.test.ts` 迁出）；`sse.test.ts` 只保留协议解析。
- 前端测试：**112 tests / 0 failures**；`vue-tsc + vite build` 通过。

#### R6b-2 Pinia store 拆分（2026-11-21）

- 新增四个域 store，`useChatStore` 保留同名 getter/action 作为兼容 facade，ChatView / WorkbenchDock 零改动（仅 `chat.streamStatus = 'idle'` 一处改为 `chat.clearStreamNotice()`）：
  - `stores/stream.ts`：`livePlan` / `liveBudget` / `liveStageMarks` / `runStartedAt` / `streamStatus` / `activeRunId` / `recoveredTruncated`，动作 `beginRun` / `markStage` / `finish` / `resetSessionState`。
  - `stores/approvals.ts`：待审批卡片、`approvalRevision` / `approvalFocusToken` 与会话审批历史；历史请求带 `historySessionId` 校验，切会话后丢弃迟到响应。
  - `stores/question.ts`：单一 `pendingUserQuestion`，动作 `ask` / `answer` / `clearForRun` / `clear`。
  - `stores/fileChanges.ts`：项目级 pending 列表、任务级 ChangeSet 与保留/撤回动作；ChangeSet 用请求令牌丢弃迟到响应。
- `chat.ts` 删除上述 19 个 state 字段与对应请求实现，读写改为委托域 store；`sendMessage` / `recoverRun` / 停止生成 / 新会话 / 切会话的时序保持不变。
- 测试适配：审批用例改为直接写入 `useApprovalStore().pendingApprovals`（facade getter 只读）；其余断言仍走 `chat.xxx`。
- 前端测试：**112 tests / 0 failures**；`vue-tsc + vite build` 通过。
- **未迁移项（如实记录）**：`messages` / `sessions` / 发送编排仍在 `chat.ts`。`sendMessage` 同时驱动流、计划、审批、询问、文件变更与侧栏运行态，单独拆 messages store 需要先拆发送编排（composables/useChatStream），否则只是把 `this` 换成跨 store 调用，收益有限、回归风险高；留到 R6c 或 R7 前处理。

#### R6c messages 与发送编排拆分（2026-11-21）

- 新增 `stores/messages.ts`：承载 `messages` / `messagesLoading` / `loadError` / `sessionAttachments`，动作 `reset` / `setHistory` / `appendUser` / `appendHint` / `beginAssistant` / `truncateFrom` / `mergeSessionAttachments` / `clearAttachments`；`parseTrace` 与 `toUiMessage` 一并迁入。
- 新增 `services/sendMessageService.ts`：`sendChatMessage(params, deps)` 接管原 `sendMessage` 的流式编排——帧合并、`StreamHooks` 全量事件映射、停止/失败的收尾、以及「流提前结束但后端仍在跑」的按 runId 恢复；`chat.sendMessage` 只做参数校验、消息落位、`isProcessing` / 耗时 / 侧栏状态点 / 审查流刷新等 store 簿记。
- 阶段时间线与失败分级的纯函数（`TASK_STAGES` / `stageForTool` / `buildStageTimeline` / `classifyFailure`）移到 `utils/taskStages.ts`，由 `stores/chat` 原样 re-export，避免 store ↔ service 循环依赖；ChatView / ChatMessage / 测试导入路径不变。
- 兼容性：`useChatStore` 继续以 getter 暴露 `messages` / `messagesLoading` / `loadError` / `sessionAttachments`，视图零改动；`focusMessageId` 仍是可写 state（搜索结果定位需要）。`chat.messages.push(...)`、`chat.sessionAttachments[id]` 等只读访问行为不变。
- 前端测试：**112 tests / 0 failures**；`vue-tsc + vite build` 通过。
- 剩余：`sessions` 与会话 CRUD 仍在 `chat.ts`（属会话域，下一步可独立拆出）；ChatView 内仍有较多交互编排。

#### R7a 评测扩容与统计门禁（2026-11-21）

- **预注册阈值（先于数据，调整需单独提交）**：每层 ≥10 个 case 且 ≥2 个独立数据集；每层成功率 Wilson 95% 置信下限 ≥0.50；L3 `memoryProbe` ≥95%；provider 失败率 ≤5%；相对基线成功率不下降、token/耗时中位数退化 <15%。
- `LongSessionDataset` 新增 `replicas`：`loadAll()` 展开为 `base-id#rNN`；L1/L2 各 20 个 case（合成 10 + 真实工程形态 10），L3 10 个 case（合成 5 + 真实工程形态 5）。离线校验强制每层 n≥10、每层 ≥2 个独立 base 数据集，且每层至少含一个 `repo-*`。
- 新增三组**真实工程形态快照**任务集（仓库内固定 fixture，非线上开源仓库采样）：
  - `repo-l1-billing-rounding`：`com.shop.billing` 十个区域税率策略 + `Money` + `InvoiceRegistry`，修复分位截断并恢复过期入口失败。
  - `repo-l2-reporting-scope`：`com.shop.reporting` 三个周期包 21 个 formatter，三次 execute 中途切换 scope，覆盖审批与统一审查。
  - `repo-l3-payment-adapters`：`com.shop.payment` 六个通道包 60 个适配器，12 turns，第 9 turn 注入检查点摘要、末轮验证早期记忆锚点。
- 新增 `LongSessionStatsGate`：输出逐层 n / 成功率 / Wilson 95% 下限 / provider 失败率 / memoryProbe 通过率 / 中位数与基线退化原因；`LongSessionLiveRunner` 报告新增 `statistics` 与 `decision.gatePassed` / `gateReasons`，`eligible` 改为「三层齐备 + 每层 ≥10 + provider ≤5%」，样本不足时不再触发架构规则。
- 基线：`eval/baselines/long-session-baseline.json`（可用 `P7_02_BASELINE` 覆盖）；不存在时退化比较标记 `missing`，不伪造对比。
- 验证：新增 `LongSessionStatsGateTest` 8 例；数据集、离线 Runner、实时报告单测同步更新。后端全量 **477 tests / 0 failures / 17 skipped**。
- **未验证项**：本轮未配置 `LUNARGOLD_LIVE_API_KEY`，未执行真实模型 n≥10 评测，也没有生成新基线；`repo-*` 为工程形态快照，不等同于线上仓库任务采样。

#### R7b Run 级断点续跑（2026-11-21，对应 P8-01）

- **范围（先定后做）**：只覆盖 `RUNNING` 崩溃——即「一轮工具批已全部落地、下一轮模型调用前」；审批中/等待用户输入仍按 P7-01 语义终止。续跑必须由用户显式点击，不在启动时自动消耗 token。
- **checkpoint 粒度与归属**：`agent_run` 新增 `checkpoint_json`（V23），保存最近一份完整模型消息快照 + 轮次、累计 token、耗时、文件变更轮次、验证尝试数、预算提示与未解决写失败/审批拒绝等循环状态；只保留最新一份，超过 4,000,000 字符时跳过本次写入并保留上一份，避免把表撑爆。事件流追加轻量 `CHECKPOINT` 事件（round / 消息条数 / 字节数）作为审计与 seq 边界；快照是可重建投影，事实源仍是事件流 + `AgentRun`。
- **写入位置**：`AgentOrchestrator` 每轮工具批、诊断与验证回灌全部落地后写一次；写失败只记日志降级，不中断主循环。
- **续跑语义**：`POST /api/agent/runs/{runId}/resume` 只接受 `FAILED / PROCESS_RESTARTED` 且带断点的 Run；`ReActAgentService.resume` 直接用快照消息作为初始上下文，不重新检索 RAG、不重建历史；工具清单、工作区、scope 与权限按当前项目/会话重新解析；审批、决策询问、SSE 事件与 `recoverRun` 复用普通发送链路。正常失败（执行异常、预算超限、工具错误）仍保持终态，不可续跑。
- **前端**：会话重新加载时若最近一条 Run 可续跑，失败气泡给出「继续执行」；`GET /api/agent/sessions/{sessionId}/resumable-run` 提供入口，`runEvents` 响应新增 `resumable`。发送与续跑共用 `sendMessageService.runStream`，避免两套事件映射漂移。
- **可见限制**：工具副作用按 at-least-once 处理——若崩溃发生在工具批中途，续跑会从上一份断点重放该轮模型决策，可能重复执行同一工具；验证重试轮不单独落断点，崩溃后回退到上一个工具批断点。两者都只会导致重做，不会跳过未完成工作。
- **验证**：新增 `RunEventStoreTest` 4 例（写入/读回/缺失损坏/超限）、`AgentRunServiceTest` 3 例（可续跑判定、续跑清错、普通失败拒绝）、`ScriptedAgentLoopTest` 2 例（轮末断点内容、续跑不重建历史）；前端新增「继续执行」按钮用例。后端全量 **485 tests / 0 failures / 17 skipped**；前端 **112 tests / 0 failures**；`vue-tsc + vite build` 通过。
- **真实崩溃续跑 E2E**（2026-11-21 通过）：`scripts/resume-e2e.ps1` + `scripts/fake-openai-server.mjs`，用 desktop 真后端 + 内嵌 PG + 假 OpenAI 流式服务，流程为：模型返回 `write_file` → 落 `CHECKPOINT`（round=1）→ **`taskkill /T /F` 真实杀掉后端进程树** → 同一数据目录重启（断言 `FAILED / PROCESS_RESTARTED / resumable=true`）→ `POST /runs/{id}/resume` → `SUCCEEDED`。断言 `note.txt` 内容严格一致、续跑模型请求里 `role=tool` 恰 1 条、原始用户 prompt 与 `write_file` 参数命中、SSE 含最终回答。运行命令：`powershell -ExecutionPolicy Bypass -File scripts\resume-e2e.ps1`。
- **E2E 暴露并修复的回归**：R7b2 新增的两个 `@PublicApi` 端点未登记到 `PublicApiCatalog.DECLARED`，应用启动自检直接失败（既有单测不启动 Spring，故漏网）。已补齐清单与契约快照，并新增反射扫描 controller 的漂移测试 `PublicApiContractTest.代码里每个PublicApi注解都必须登记到清单`，防止再次出现。
- **未验证项**：未用真实模型验证续跑后的任务完成率（runner 与预注册门禁见下一小节，真实数据仍待 Key）；未补 Playwright 真机用例；`write_file` 的 tool 结果本身只有「已写入文件」，写入内容断言落在同快照的 tool_calls 参数上。

#### R7b-live 真实模型续跑完成率评测（2026-11-21，runner + 门禁已落地，真实数据未产出）

- **预注册阈值（先于数据，调整需单独提交并说明理由）**：`LongSessionResumeGate` 固定——每层 ≥10 个「已注入中断并完成续跑」的配对 case；续跑成功率 Wilson 95% 置信下限 ≥0.50；续跑成功率相对同一批直接执行下降 ≤10pp；续跑臂 provider 失败率 ≤5%；续跑 token 中位数 / 直接执行 ≤1.50；耗时中位数 / 直接执行 ≤2.00。耗时阈值更宽是因为真实重启有启动开销；token 更严格，因为中断发生在模型调用前，理论上不应重放已落地的轮次。
- **续跑 A/B 机制**：`P7_02_RESUME=on` 时，每个 case 先跑「直接执行」，再在全新工作区跑「续跑臂」。续跑臂由 `ResumeCrashClient` 在第 1 个 `CHECKPOINT` 落库后的下一次模型调用前抛出模拟崩溃，`Capture.onError` 把 Run 落成 `FAILED / PROCESS_RESTARTED`，随后走生产链路 `AgentRunService.resumeInterrupted` + `ReActAgentService.resume` 继续；工件与 coverage 判定完全复用直接执行的 `evaluate(...)`，不另写一套。
- **报告**：`report.resume.gate` 逐层输出 attempts / cases / notApplicable / unmatched / 续跑与直接执行成功率及下降幅度 / Wilson 下限 / provider 失败率 / token 与耗时比值 / reasons；未注入中断的 case 记 `notApplicable`，不计入成功率，避免把「没测到」算成「通过」。配置项 `resume_mode`、`resume_interrupt_point` 一并落盘。
- **运行方式（需自行提供 Key，会产生真实费用）**：设置 `P7_02_RESUME=on`、`P7_02_LEVELS=L1,L2,L3`、`P7_02_REPEATS=...`，其余沿用 `LUNARGOLD_LIVE_*`；报告写入 `eval/runtime-reports/`。付费前可用 `P7_02_MAX_CASES=1` 每层只跑 1 个 case 做链路冒烟（不改变预注册阈值，仅缩小样本）。
- **离线验证**：新增 `LongSessionResumeGateTest` 5 例（通过、下降 >10pp、未注入不计入、provider/token 触发、缺少配对记 unmatched）；后端全量 **490 tests / 0 failures / 17 skipped**。
- **真实模型冒烟（2026-11-21，`P7_02_MAX_CASES=1`，仅链路诊断）**：`L1 / l1-single-module-refactor#r01` 直接执行 success=true（35 次模型调用、174,565 tokens、173s）；续跑臂 success=true、`resumeInjected=true`（196,954 tokens、173s），`FAILED/PROCESS_RESTARTED → resumeInterrupted → resume` 全链路通过。按预注册门禁 cases=1 判为样本不足，**不构成完成率结论**。Key 仅经环境变量注入，未写入仓库。
- **待办**：L1 全量 20 对（约 3-4 小时）→ 产出 L1 层可引用完成率；L2/L3 全量（预计 10 小时以上、token 成本最高）需另行确认后执行。两者都必须沿用当前预注册阈值，不得事后调整。

#### L3/L4 门禁冒烟（2026-11-21，真实模型 n=1，仅诊断）

- **运行**：Key 经 `.secrets/live-eval.ps1` 注入（已 gitignore）；`P7_02_LEVELS=L3`、`P7_02_MAX_CASES=1`、`P7_02_CONDENSE=off`。
- **结果**：`l3-cumulative-history-memory#r01` 12 turn 全部 SUCCEEDED，60/60 工件完成，memoryProbe 通过；182 次模型调用、193 次工具调用、总 tokens 1,154,751、墙钟 579s；重复调用 19 次、noProgress=0、toolFailures=0、incompleteTurns=1。
- **L4 成本可控：达标（n=1，待全量确认）**。`contextPeakPromptTokens=8,829`，远低于 64k 窗口 70%（44,800）；该数据集每 turn 独立 Run，上下文不跨 turn 累积，因此不再跑 condense=on A/B。
- **L3 初判 STEP_OMISSION 是评测口径问题，不是错误恢复失败**：第 4 turn `INCOMPLETE:PLAN_INCOMPLETE`（计划 5/6 步），其余 turn 均 `UNVERIFIED:VERIFY_NOT_CONFIGURED`；模型在后续 turn 自行完成全部 60 文件。根因：`RunTermination.candidateCompletion` 对任何 pending/in_progress 计划步判 INCOMPLETE，而多 turn 数据集的计划本就跨 turn；runner 又以 `incompleteTurns==0` 作为覆盖门禁，把正常的中间态误判为步骤遗漏。
- **口径调整（单独提交，先于后续数据）**：新增 `LongSessionLiveRunner.incompleteFailures`——多 turn 数据集中间 turn 仅 PLAN_INCOMPLETE 不计失败；其余 INCOMPLETE（写失败 / 审批拒绝 / 预算耗尽等）任何 turn 仍计失败；末 turn 必须收口；单 turn 数据集口径不变。理由：多 turn 计划允许跨 turn，原口径与数据集设计矛盾。这不是放宽成功标准：工件、coverage、memoryProbe、末 turn 收口要求全部保留。新增 `LongSessionLiveRunnerTest` 2 例。
- **缩样决策（用户确认）**：不再跑 L3 全量 10 case 与 condense A/B；L3/L4 先以本次冒烟 + 口径修正记录，全量真实模型运行只在用户再次明确批准时执行。
- **口径修正后复验（同日第二次运行，同一 case）**：`success=true`、`failureAttribution=NONE`、`missingArtifacts=[]`、`coverageFailures=[]`、`incompleteTurns=0`、`planCompletionRate=1.0`、`memoryProbePassed=true`、12 turn 全部 SUCCEEDED、60/60 工件落地；162 次模型调用、177 次工具调用、总 tokens 923,686、墙钟 726s、`contextPeakPromptTokens=7,949`。说明先前 `STEP_OMISSION` 确为评测口径误判，产品侧无对应缺陷。
- **L3 错误恢复的直接证据（本次复验）**：`toolFailures=1`、`recoveredTools=1`，即真实发生一次工具调用失败并在后续轮次自行恢复，未中断任务、未触发停滞。
- **L3 仍存在的真实短板（归入 R7c L5）**：12 个 turn 的 `completionReasons` 全部为 `UNVERIFIED:VERIFY_NOT_CONFIGURED`——评测工作区未配置验证命令，模型只能依据「变更后文件诊断未发现编译错误」自称完成，多次在回答里明确声明「未做整体编译或测试，不能声称验证通过」。这正是「完成强校验」要解决的问题：完成状态目前仍依赖模型自报，缺工具级验证闭环。
- **结论口径**：L3/L4 以 n=1 冒烟给出方向性结论（L4 达标、L3 机制可用），**不构成层完成率**；层完成率需 n≥10 全量，仍待批准。

#### L1 目标保持：30 turn 数据集（2026-11-21，仅离线）

- 新增 `eval/long-session-datasets/l3-30turn-goal-keeping.json`：**30 次 execute、150 个独立 Java 工件、10 replicas、minModelCalls=300**。每 turn 无缝处理 5 个文件，防止用重复调用或空状态汇报凑轮次。
- **命名说明**：它验证门禁表的 **L1 目标保持**，但数据集 `level` 仍为 **L3**；现有 schema 规定 L1=单次 execute，多 turn 累积与 memoryProbe 属于 L3，不为了名字另造 L4。
- **记忆探针**：锚点只出现在首轮，最终第 30 turn 要求复述；第 24 turn 后注入检查点摘要。离线测试明确禁止锚点泄漏到后续 prompt，避免把「复制当前提示」误判成记忆保持。
- **预注册覆盖**：30 个 execute turn、≥6 次计划更新、≥1 次摘要注入、memoryProbe 必须通过、150 个工件逐项校验；沿用现有 `LongSessionStatsGate`，没有新增或放松阈值。
- **离线验证**：新增 `thirtyTurnGoalKeepingDatasetCoversL1Gate`，检查 30 turn、10 replicas、150 工件、区间连续性、摘要/探针位置与锚点不泄漏；数据集形态校验和全量 fixture 物化通过。
- **耗时估算（未运行）**：按 L3 12 turn / 60 文件实测 726s 外推，30 turn / 150 文件 n=1 约 **30–45 分钟**，n=10 约 **5–8 小时**；只是估算，不作为实测结果。当前没有运行真实模型，也没有产生 L1 完成率或 memoryProbe 数字。
- **运行范围提醒**：新数据集排序在旧 `l3-cumulative` 前；需要单独冒烟时可先 `P7_02_LEVELS=L3`、`P7_02_MAX_CASES=1`，但正式结论仍以报告中的 case id 为准，不能只凭参数假设。

#### R7c L5 完成强校验：验证命令自动探测（2026-11-21）

- **要解决的问题**：L3 冒烟暴露「无 verify.json → `NOT_CONFIGURED` → `UNVERIFIED` → 模型自报完成」。真实工作区大多自带构建文件，不该因为少写一个配置文件就失去工具级完成证据。
- **实现**：新增 `VerifyCommandDetector`，在显式配置缺失时按工作区根目录构建文件探测**一条**验证命令，按固定优先级：`pom.xml` → `mvn -B -q -DskipTests compile`；`build.gradle(.kts)` → `gradle -q compileJava`；`package.json` → `npm run typecheck` / `build` / `test`（依序取第一个存在的脚本）；`go.mod` → `go build ./...`；`Cargo.toml` → `cargo check --quiet`；`tsconfig.json` → `tsc --noEmit`。
- **安全边界**：命令文本固定、不含任何工作区内容；只探测根目录、不递归、不读脚本；不使用 `./mvnw` 这类工作区相对脚本（`CommandService` 明确禁止）；探测命令全部落在既有命令白名单内，仍走同一套超时/输出截断。
- **配置**：`agent.verify.auto-detect`（默认 `true`）、`agent.verify.auto-detect-timeout-seconds`（默认 300）；显式 `verify.json` 始终优先。
- **诚实性**：自动探测不改变状态语义——探测不到构建文件仍是 `NOT_CONFIGURED`/`UNVERIFIED`，不会把未验证包装成成功；结论文案标注「自动探测自 pom.xml」，避免冒充用户配置。
- **验证**：新增 `VerifyCommandDetectorTest` 6 例 + `VerifyServiceTest` 3 例（自动探测生效、可关闭、显式配置优先）；后端全量 `mvn clean test` 通过。
- **未覆盖**：裸源码目录（无构建文件，如 L3 评测工作区的 `src/l3/*.java`）仍无法自动验证，需显式配置 `verify.json`；这是有意的边界，不做「猜 javac 文件列表」这类不可靠探测。

#### R7c L5 计划确定性证据契约（2026-11-21）

- **要解决的问题**：模型可以先把计划步骤标成 `completed`，再给出与真实工件无关的完成结论；仅靠 `pending/in_progress` 计数无法约束「这一步到底交付了什么」。
- **数据结构**：`PlanStep` 增加可选 `id` 与 `acceptance`（`artifacts[{path, expectedContains[]}]` + `requireVerify`），保留两字段构造器；旧计划 JSON 的读写结果不变。
- **提交时校验**：带契约的步骤必须有稳定唯一 id；路径拒绝绝对路径、盘符、UNC 与 `..`；契约声明后不可改写或删除；首次提交不能直接 `completed`，已完成步骤不能重新打开，也不能整步移除；每次提交 `completed` 都重新读取工件并核对验证覆盖版本，失败时整次更新失败并保留上一份快照。
- **版本权威**：`changeEpoch/verifiedEpoch` 从 `AgentOrchestrator` 局部变量迁入 `RunContext`，文件落地即递增变更版本，验证完成后追平；`requireVerify` 要求验证为 `PASSED` 且覆盖最新变更，同一工具批里先写文件再完成计划会被识别为证据过期。
- **最终收口**：`RunTermination` 在候选完成与 `finalizePlan` 时再次核对全部完成契约；不满足返回 `INCOMPLETE:PLAN_EVIDENCE_MISSING/PLAN_EVIDENCE_STALE`，并把证据不满足的 `completed` 与其余未完成步骤一起标为 `aborted`，避免 UI 与结论矛盾。
- **验证**：`UpdatePlanToolTest` 21 例、`PlanEvidenceServiceTest` 9 例、`RunTerminationTest` 6 例、`RunContextTest` 10 例与 `ReActAgentServiceTest` 契约收口用例通过；orchestrator 相关回归 34 例通过。
- **边界**：证据只证明「工件存在且包含指定文本 / 验证覆盖最新变更」，不证明自然语言语义；`ReActDiagnosticsIntegrationTest` 聚焦编译诊断，显式关闭裸源码自动验证，避免 Verify 抢先吸收诊断结论。

#### R7c/P8-05 Run 内本地检索型记忆（2026-11-21）

- **要解决的问题**：condense 折叠/摘要/滑窗之后，早期真实消息只剩摘要甚至完全消失；模型需要细节时只能重新读文件，长会话里容易丢线索。
- **实现**：新增 `RunHistoryRetriever`，只对**被 condense 省略的权威消息**做本地检索；查询由最新真实用户输入 + 当前计划标题组成；标识符/英文词/中文 bigram 权重 5/2/1，角色系数 user/assistant/tool 为 3/2/1；按总分降序、原始序号升序取 TopK，并受单条 1500 字、总计 6000 字、最低分 6 的硬预算约束。
- **注入方式**：命中内容渲染为一条合成 user 消息，插在 system 之后；不重新插入原始 assistant/tool 协议节点，不破坏 `tool_call` 配对；与 retained 消息按规范化内容去重；异常、无命中或开关关闭都不注入。
- **配置**：`agent.context.retrieval.enabled=false`（默认）、`top-k=6`、`max-fragment-chars=1500`、`max-total-chars=6000`、`min-score=6`；只有 condense 真正省略消息后才可能有候选，因此检索需与 condense 同时开启。
- **数据一致性**：不修改权威消息，也不新增记忆库；续跑仍从 `RunCheckpoint.messages` 重建，所以重启后能召回同一批旧消息。
- **验证**：`RunHistoryRetrieverTest` 8 例、`ContextAssemblerTest` 9 例通过；默认关闭时装配结果与旧行为一致。






#### R7c/P8-03 串行子智能体与分别验收（2026-11-22）

- **要解决的问题**：单 Run 在长任务里既要做计划、又要读大量文件，容易在同一个上下文里反复自我确认；需要把可独立验收的子目标交给隔离的子 Run，同时避免「父任务替子任务宣布成功」。
- **入口与边界**：仅当 `agent.subtasks.enabled=true` 时，根 Run 的工具列表才追加合成工具 `delegate_subtasks`；该工具**不注册进 ToolRegistry**，由 `AgentOrchestrator` 在工具循环最前拦截。单次委派最多 4 个子任务，严格串行，前一个验收通过才启动下一个；子 Run 的工具列表不含 `delegate_subtasks`，因此深度固定为 1。
- **隔离**：每个子任务对应一条独立 `agent_run`（`parent_run_id + delegation_id` 唯一，`V24` 迁移）；子 Run 拥有自己的 `RunContext`、计划、断点、状态与事件流。父 SSE 只收到 `subtask` 轻量状态（按 `subtaskId` 去重），子 Run 的 chunk/tool/plan/verify 事件写 child runId。
- **验收**：子任务规格强制 `id + objective + acceptance`；`SubtaskRunCoordinator` 在子 Run 结束后用同一个 `PlanEvidenceService` 按验收契约重新读取工件/验证版本，证据不满足即标 `FAILED`，不会因为子 Run 自报完成而通过。父 Run 的 `RunTermination` 再次检查 `findSubtasks(parentRunId)`，存在任何非 `SUCCEEDED` 子 Run 时返回 `INCOMPLETE:SUBTASK_NOT_ACCEPTED`。
- **续跑**：父 Run 在启动子任务前与每个子任务终结后把 `pendingDelegation`（规格、已产生结果、下一未完成下标、toolCallId）写入 `RunCheckpoint`；重启后先复用已 `SUCCEEDED` 的子 Run，再按断点恢复 `FAILED/PROCESS_RESTARTED` 子 Run，补齐 `tool` 响应后回到正常循环，不重新让模型猜委派意图。
- **审批与用户询问**：子 Run 的审批仍走既有 `approvalId` 与 `ApprovalGate`，询问仍走 `ask_user_question`；父 SSE 转发时附带 `runId=childRunId`，前端按 childRunId 回答，父 Run 不会被置为 `WAITING_APPROVAL/WAITING_USER_INPUT`。父取消通过共享 `cancelRequested` 级联终止已启动的子 Run。
- **预算与用量**：父子共享 `RunBudget`，子任务不能重置父预算；子 Run 到达终态后把它自己的 token 用量一次性汇总进父 Run，会话用量查询仍只统计顶层 Run，避免双计。
- **默认行为**：`agent.subtasks.enabled=false`、`agent.subtasks.max-subtasks=4`；关闭时模型看不到委派工具，行为与改造前一致。
- **验证**：后端新增 `SubtaskSpecTest`、`SubtaskRunCoordinatorTest`、`AgentOrchestratorSubtaskResumeTest`、`AgentRunServiceTest` 子 Run 幂等/父子关系用例、`ChatStreamBridgeTest` 轻量 `SUBTASK` 事件用例与 `RunTerminationTest` 子任务收口用例；前端新增 `subtask` SSE 解析、`runRecovery` 按 `subtaskId` 去重、ChatView 子任务状态条与对应单测。**本次未运行真实模型**，没有子智能体完成率或 A/B 结论，只有离线/脚本化验证。
