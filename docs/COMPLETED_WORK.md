# AgentKey 已完成任务与验收档案

> 整理日期：2026-11-14
> 用途：集中保存已完成任务、关键实现、测试结果和历史验收边界。
> 当前待办见 [UPDATE_PLAN.md](UPDATE_PLAN.md)；当前代码快照见 [STATUS.md](STATUS.md)。
> 本文中的测试数量和扫描结果均为对应任务完成时的记录，不代表后续变更自动继承结论。

---

## 1. Phase 0：工程基线、安全红线与数据库选型

| ID | 完成内容 | 验收记录 |
|---|---|---|
| P0-01 | 后端、前端、测试、迁移、CI、容器配置和文档纳入版本管理；前端锁文件入库；密钥、运行数据、上传文件和构建产物保持忽略 | 完成时 `git status` 干净，前端不再未跟踪；本地调试工具和数据库口令未入库 |
| P0-02 | 新增 `.github/workflows/ci.yml` 与 `.workflow/ci.yml`，覆盖后端 JDK 21 Maven `verify`、前端 Node 22 `npm ci` 和生产构建 | Gitee Go 的 `master` 流水线真实运行成功；修复干净环境编译问题后提交 `a07bb0e`，本地 119 项测试 0 失败、0 错误、2 跳过。GitHub Release 流水线不属于本项已完成范围 |
| P0-03 | 删除默认 JWT/AES 密钥并增加启动校验 | 缺少密钥时 prod 启动失败，仓库不保存可用密钥 |
| P0-04 | 完成密钥安全、文件 realpath 沙箱、命令环境清洗、MCP 安全、接口隔离、指标租户隔离和 SSE 超时解耦等安全加固 | PR-01 完成时 90 项测试全部通过 |
| P0-05 | 对齐 `STATUS.md`、`design.md`、`operations.md` 与代码事实 | 修正迁移版本、MCP 默认值、审批、SSE、能力门控和环境变量等文档漂移 |
| P0-06 | 升级 Spring Boot 4.1.2、Spring AI 2.0.2 等依赖；补 `CommandRiskTest`、`AgentPolicyTest`、`DeleteFileToolTest` | 完成时全量 119 项，0 失败、0 错误、2 跳过；OSV 覆盖 255 个 Maven 坐标，0 Critical。OWASP Dependency Check 和 OSS Index 未形成有效通过结论 |
| P0-07 / PR-02 | 完成 SQLite + sqlite-vec 与内嵌 PostgreSQL + pgvector 双路线实测，选定内嵌 PostgreSQL | 两条路线均验证迁移、1024 维向量写入与余弦检索、会话保存/读取；最终选择迁移与向量 SQL 改动最小的 PG 路线 |

### 1.1 数据库选型实测摘要

| 指标 | SQLite + sqlite-vec | 内嵌 PostgreSQL + pgvector |
|---|---:|---:|
| 当时发布构成 | 123.837 MiB | 155.805 MiB |
| 干净数据库全链路 | 3/3 成功 | 3/3 成功 |
| 整体测试进程树峰值 | 573.1～608.5 MiB | 596.6～618.7 MiB |
| 独立数据库开销 | 无独立进程 | PG 子进程聚合约 59.4 MiB 峰值 |
| 迁移与 `VectorStoreService` 改动 | 15 个文件，+170/-294 行 | 隔离实验仅 V13 修正，向量服务 0 行 |

实测只覆盖 Windows x64 和 Spring 测试切片，不是三平台安装包验收。原始 V13/V14 的全新库顺序缺陷后来由 PR-06 的 Flyway 前置幂等预检关闭，不直接修改已执行迁移。

---

## 2. Phase 1：运行时拆分与网页端收敛

| ID / PR | 完成内容 | 验收记录 |
|---|---|---|
| P1-01 / PR-03 | 新增 `DeploymentMode`、`Capability`、`CapabilityService`；默认保持 cloud 行为 | 新增能力与元信息测试；完成时全量 95 项，0 失败、0 错误、2 跳过 |
| P1-02 / PR-03 | 新增公开 `GET /api/meta`，返回模式、版本、认证方式、能力和下载声明 | cloud/desktop 能力映射及 MVC 序列化有测试 |
| P1-03～04 / PR-04 | `ToolRegistry` 按能力注册；工作区、审批和 MCP 控制器按能力收敛 | cloud 未启用能力返回 404；完成时全量 105 项，0 失败、0 错误、2 跳过 |
| P1-05～06 / PR-05 | 前端 `meta` store、能力路由、网页端入口收敛和公开下载页 | `vue-tsc --noEmit`、Vite 构建和模拟元信息页面验证通过；真实 `/api/releases/latest` 留待后续 |
| P1-07 / PR-12 | 邮箱验证码注册、找回密码、发码限频、错误作废、会话撤销和邮箱唯一索引 | 新增服务与控制器测试各 6 项；真实 SMTP 投递未包含在该轮验收中 |

本阶段完成后，网页端只保留账号、对话、知识库和下载入口；桌面能力由同一套前后端按 `/api/meta` 声明启用，没有维护第二套前端。

---

## 3. Phase 2：桌面运行时

| ID / PR | 完成内容 | 验收记录 |
|---|---|---|
| P2-01、02、04 / PR-06 | 新增 desktop profile、内嵌 PG 生命周期、健康检查、优雅关闭、异常残留恢复和 Redis 自动配置排除 | Windows x64 全新数据目录启动成功，15 个迁移完成，health 为 UP，无 Redis 重试日志；强杀后可清理 `postmaster.pid` 并复用原目录 |
| P2-03、05 / PR-07 | 本地随机 token、本机接口鉴权、单用户初始化、桌面注册入口关闭和前端自动换令牌 | 完成时 152 项，0 失败、0 错误、2 跳过；token 采用数据目录文件与权限收紧，未改为 OS 凭据库 |
| P2-06 | 新增 `scripts/dev-desktop.ps1`，自动准备运行时和开发密钥、启动后端与前端 | 实跑可等待 health 就绪后启动 Vite，已有数据目录可复用 |
| P2-07 | 增加命令超时与整棵进程树终止测试；既有路径、软链接和环境变量清洗保持回归 | 新增父子进程实测证明超时后子进程也终止 |
| P2-08 | 新增 Windows x64 PG + pgvector 运行时构建脚本和真实数据库集成测试 | V1～V15、`CREATE EXTENSION vector`、1024 维余弦排序和错误维度拒绝通过；完成时全量 156 项，0 失败、0 错误、5 跳过 |

桌面账号 UI 已收敛：隐藏账号安全、用户名和退出入口，但保留本地令牌保护本机 API。macOS/Linux 的 PG/pgvector 运行时不包含在 Windows 完成结论中。

---

## 4. Phase 3 已完成部分：桌面壳与自包含运行时

| ID | 完成内容 | 验收记录 |
|---|---|---|
| P3-00 | `with-frontend` 将 SPA 打入后端 jar；增加 SPA 深链回退，保持 API 与缺失静态资源语义 | 单 jar 可直接提供 UI；`/`、`/chat`、`/settings` 等页面和鉴权请求实测通过 |
| P3-00b | `with-pg-runtime` 将 PG + pgvector 运行时打入 jar；运行时缺失由统一发布脚本强制拦截 | 清空外部 `DESKTOP_PG_RUNTIME` 后，全新数据目录仍可从 jar 内置运行时启动并完成 V1～V15 |
| P3-01 | Electron 壳、单实例锁、启动画面、错误对话框、AES 主密钥管理和 UI 加载 | 后续真实 unpacked Electron 已验证窗口、双开单实例和关闭退出 |
| P3-02 | 侧车动态端口、健康检查、日志、快速失败、崩溃重启、进程树和游离 postmaster 终止 | jar 缺失快速失败、异常退出、原地重启和无残留进程均有实测；未影响用户既有 PostgreSQL |
| P3-03 | `jlink` 裁剪 19 个模块的私有 JRE，打包态优先使用 `resources/jre` | 裁剪 JRE 无头启动完整 jar，内嵌 PG、Flyway、health 和 SPA 均通过；后续统一构建 JRE 为 49.43 MiB |

---

## 5. P3-04 已完成部分：Windows 安装与统一构建

### 5.1 安装器和 NSIS 修复

- 统一构建脚本已覆盖前端、含 SPA/PG 运行时的后端 jar、私有 JRE、unpacked 和 NSIS，并校验关键条目与 jar 哈希。
- 主程序改为 `agentkey-pro.exe`，安装器改为 `AgentKeyPro-Setup-${version}.exe`，避免同名进程判断歧义。
- NSIS 自定义目录卸载通过 `_?=` 恢复原始 `$INSTDIR`，固定当前用户模式，避免通用卸载模式页和更新专用恢复逻辑误删默认目录。
- 运行检测只针对目标主程序；不再因空目录或无关进程反复弹出“无法关闭”。
- 项目 `release` 曾因 Low Mandatory Level 和额外 ACE 导致同一哈希安装器与工作区外副本表现不同；经用户同意通过 UAC 修复为 Medium，文件字节未改变。
- 用户已确认项目内安装器的 GUI 覆盖安装、系统“应用和功能”卸载、全新自定义目录安装与首启闭环通过。该结论对应当时的验收安装器，不自动覆盖后续新哈希产物。

完整根因、命令、证据和防回归清单见 [windows-nsis.md](troubleshooting/windows-nsis.md)。

### 5.2 最新统一构建记录（2026-11-09）

- 构建脚本按 `artifactName` 展开安装器文件名，多余 BOM 已清理，PowerShell 语法检查通过。
- unpacked 默认使用工作区外 `D:\AgentKeyBuild\desktop-pack`，避免 IDE 锁住 asar；最终安装器仍归档到项目 `release/installer`。
- 前端 1836 个模块构建通过。
- Maven `verify`：**156 项，0 失败、0 错误、5 跳过**。
- 私有 JRE：**49.43 MiB**。
- unpacked：**638.97 MiB**。
- 安装器：**353.37 MiB / 370,531,105 字节**。
- 安装器 SHA-256：`D8ADAD11AE7E96FC514E38B4C98167F96A943EB345C684663A5FA998310CF6A0`。
- 包内主程序、JRE 和后端 jar 均存在，Maven 与包内 jar 哈希一致。
- 发布目录和安装器均为 Medium Mandatory Level。

本轮只证明统一构建链路完整通过。上述新哈希安装器的最小安装/首启/卸载回归、签名、GitHub Release 和陌生机器验收仍在 [UPDATE_PLAN.md](UPDATE_PLAN.md) 的 P3-04 中跟踪。

### 5.3 P3-04 发布基线收尾（2026-11-09）

- 重建 Windows 安装器：**353.37 MiB / 370,531,148 字节**，SHA-256 `6BEF673C8B0C04C8BC3514AF7D33849B34D7C058855B5853800C78D36E1AD959`。本次重建额外修正了卸载残留，故哈希与 5.2 不同。
- 构建脚本首个字符为双 BOM，导致 PowerShell 5 直接报 `Unexpected attribute 'CmdletBinding'`；已清理为单 BOM。
- 本机 npm 策略跳过 Electron 的 postinstall，`electronDist` 缺失导致打包中止；改用本地缓存的 `electron-v43.3.0-win32-x64.zip` 解包到 `desktop/node_modules/electron/dist`，包内 exe 与旧构建逐字节一致。
- 新增仓库根目录 `LICENSE`（Apache-2.0）。图标与发布元数据本轮按项目所有者决定跳过。
- **修复静默卸载残留**（[include.nsh](file:///d:/test-code/agent-gateway-v3/desktop/nsis/include.nsh)）：更新流程走 `_?=`，静默卸载不会；原模板在程序文件删除后才清快捷方式和注册表，实测会残留开始菜单快捷方式与 HKCU 卸载项。现在真实卸载时由 `customUnInstall` 先行按当前用户清理桌面/开始菜单快捷方式与两个 HKCU 注册键，更新流程不受影响。
- 静默回归（安装到 `%LOCALAPPDATA%\Programs\agentkey-pro`，数据目录 `%TEMP%\p304-data`）：**22 项检查，22 通过、0 失败**。覆盖安装器哈希、安装退出码、主程序/卸载器/JRE/jar、卸载注册项与版本、桌面快捷方式、`/actuator/health = UP`、壳+Java+PostgreSQL 进程、`db/postmaster.pid`、正常退出无残留进程、卸载后目录/注册项/桌面与开始菜单快捷方式清理、`DESKTOP_DATA_DIR` 保留。
- 回归中的两个环境事实：静默安装必须显式传 `/D=<无空格目录>`，否则只写注册项不落文件；`.tmp` 位于工作区内时，继承的沙箱 ACE 会让 Electron 启动即 `STATUS_BREAKPOINT`，故回归目标必须放在工作区外。

### 5.4 产品更名 Lunar Gold 与重打包（2026-11-09）

- 产品更名为 **Lunar Gold（金月）**：`appId=com.lunargold.desktop`、`productName=Lunar Gold`、`executableName=lunar-gold`、安装器 `LunarGold-Setup-${version}.exe`、快捷方式 `Lunar Gold`、数据目录 `~/.lunargold/desktop`、jar 覆盖变量 `LUNARGOLD_JAR`。
- 刻意未改：Java 包 `com.capsule.agentkey`、数据库 `agentkey_vector`、jar 名 `agent-gateway-1.0.0.jar`。这些属于 jar 内部标识，用户不可见，重命名需改两百多个文件。
- 安装器：`release/installer/LunarGold-Setup-0.1.0.exe`，**354.14 MiB / 371,342,808 字节**，SHA-256 `A46FF10F0D2126018C3DA587B9CFD87C66A50A04A30CBE3CBB05DB4AC0D24D2D`（此版无自定义图标）。
- 包内 `lunar-gold.exe`、`resources/jre/bin/java.exe` 存在，包内 jar 与 Maven 产物哈希一致。
- 静默回归（安装到 `D:\test\02\Lunar Gold`，数据目录 `D:\test\03\data`）：**22 项，22 通过、0 失败**，覆盖安装、首启 health、PG 进程、退出无残留、卸载清理与用户数据保留。
- 本次构建新增两条环境事实：
  1. 通过工具的后台任务方式运行构建会被沙箱限制，在工作区外建目录报 `EPERM mkdir`；同样的命令前台执行即可通过。构建必须前台跑。
  2. 回归脚本原先用 `CommandLine` 匹配 PostgreSQL 进程，机器上既有的 PG 服务进程 `CommandLine` 为空，导致计数为 0 的假失败；改为「PG 进程数相对基线增加」后判定稳定。

### 5.5 黑白图标接入与重打包（2026-11-10）

- 图标最终采用**黑白单色月亮**（白底圆角方块 + 黑色弯月笑脸 + 四颗四角星），与同行业桌面智能体风格一致；`desktop/app-icon/icon-gold.png` 为深底金色备选版，仅保留不打包。
- 源图经白底转透明、亮度归一化、4px 轻度加粗处理，输出 `desktop/app-icon/icon.png`（1024×1024，圆角外透明）与 `desktop/app-icon/icon.ico`（16/24/32/48/64/128/256 七档）。
- `desktop/package.json` 增加 `build.win.icon = app-icon/icon.ico`；图标刻意放在 `desktop/app-icon/` 而非 `desktop/build/`，因为根 `.gitignore` 的 `build/` 规则会把后者排除在仓库之外。
- 重新打包并回归：安装器 `release/installer/LunarGold-Setup-0.1.0.exe`，**371,359,923 字节**，SHA-256 `40C06B8AC86054FBCC4777C81B4253A7D1B5B058632D12367BB027CF5EE6A92E`；**22 项静默回归全部通过**；`ExtractAssociatedIcon` 从包内 `lunar-gold.exe` 取出的图标与设计一致。
- 中间产物（AI 生成草稿、加粗对比图）已清理，仅保留定稿 `icon.png`/`icon.ico` 与金色备选 `icon-gold.png`。

### 5.6 author 元数据重打包与 v0.1.0 预发布（2026-11-10）

- `desktop/package.json` 的 `author` 改为 `Lunar Gold Project` 后重打包，发布基线更新为 **371,662,112 字节**，SHA-256 `3E085FAB3D709EE0CD44894F7BF10838B21F477DC0CD06A91971B921ED9A9022`（含定稿黑白图标）。
- GitHub 预发布 `v0.1.0` 已上传该产物，Release 资产大小 **371,662,112 字节**，与本地基线一致；配套发布 `.blockmap` 与 SHA256 说明。
- 同批次新增 [release.yml](../.github/workflows/release.yml)：`v*` 标签触发，SignPath secrets 未配置时自动跳过签名并产出未签名草稿 Release。

仍未完成：SignPath 申请与受信签名构建、干净 Windows 机器验收；macOS/Linux 安装包按所有者决定暂缓。以上在 UPDATE_PLAN.md 的 P3-04 中跟踪。

### 5.7 v0.1.1 正式发布（2026-11-10）

- `v0.1.1` 在 GitHub 发布为**正式 Release**（非 prerelease），`/releases/latest` 已指向它；原 `v0.1.0` 预发布不再作为更新源基线。
- 资产：`LunarGold-Setup-0.1.1.exe` 371,688,816 字节 / SHA-256 `5ECB437109E7DDAA81C06D6C50D26ECBD6086D31520F0DE3C33937EB0DB6400B`、同名 `.blockmap`、`latest.yml`（v0.1.1，sha512 与 size 与实际文件一致）、`SHA256SUMS.txt`。四个资产的 GitHub digest 与本地逐一核对一致。
- 发布说明与 [README.md](../README.md) 同步写明未签名现状、SmartScreen「未知发布者」处理方式与 SHA-256 校验方法；SignPath 拒绝理由记录在 UPDATE_PLAN。
- `release.yml` 的 `push.tags` 触发临时关闭，避免手工发布的同名标签另起 CI 草稿 Release；P3-07 打通 CI 打包后再恢复。
- 待办：用线上源验证 N-1 → N 需要下一个版本（计划 0.1.2，同时做安装包体积裁剪）。

---

## 6. P3-05：Windows 自动更新与失败回滚（2026-11-10）

### 6.1 改动

- 新增 [updater.js](file:///d:/test-code/agent-gateway-v3/desktop/updater.js)：封装检查、提示、下载、校验、备份、静默安装与回滚。默认「检查并提示」，不强制更新；未打包态不检查。
- [main.js](file:///d:/test-code/agent-gateway-v3/desktop/main.js) 接入更新器；`runtime` 增加 `dataDir`/`logPath`。首启加载页面成功后才 `markStartupSuccess()` 并排期检查；加载失败且存在升级标记时走 `rollbackAfterFailedUpgrade()`。
- [package.json](file:///d:/test-code/agent-gateway-v3/desktop/package.json)：版本 `0.1.1`，新增 `electron-updater@6.8.11`、`files` 增加 `updater.js`、`build.publish` 指向 GitHub（owner `nichengyv` / repo `lunar-gold`，`releaseType=draft`）。
- [build-desktop-installer.ps1](file:///d:/test-code/agent-gateway-v3/scripts/build-desktop-installer.ps1)：`--dir` 构建后补写 `resources/app-update.yml` 并 `Assert-File` 校验。两段式 `--dir` + `--prepackaged` 不会自动生成该文件，缺失会导致装好的应用读不到更新源。
- [release.yml](file:///d:/test-code/agent-gateway-v3/.github/workflows/release.yml)：上传 `latest.yml`；新增签名后重算 `latest.yml` 的 sha512/size 并删除失配 `*.blockmap`；`prerelease` 改为仅在标签含 `rc`/`beta` 时为真（GitHub provider 只识别正式 Release）。

### 6.2 验证

本地 generic 源实测 `0.1.1 → 0.1.2`：下载 → 数据备份 → 静默安装 → 重启 → 首启成功 → 标记清理；`local-token` 哈希、`db` 数据与用户 canary 文件均完整。

| 场景 | 结果 |
|---|---|
| 正常升级 | 通过，数据完整，进程 0 残留 |
| sha512 错误 | `checksum mismatch`，不安装，版本不变 |
| 签名错误（强制校验） | `NotSigned`，不安装，版本不变 |
| 磁盘不足 | 下载前预检拦截，不下载不安装 |
| 升级后启动失败 | 自动恢复数据备份并静默装回 `0.1.1`；`*.failed-<时间戳>` 保留现场；陈旧标记自动清理 |
| 回滚/退出后进程 | `lunar-gold`/`java`/`postgres` 均 0 残留 |

过程中的 5 个问题与修复（`app-update.yml` 缺失、下载缓存目录名、http feed 协议、detached PowerShell wrapper 不执行、`spawnSync` 等待导致误判）见 [lessons 记录](phases/phase3/p3-05-auto-update.md)。

### 6.3 边界

- 只覆盖 Windows；macOS/Linux 更新按所有者决定暂缓。
- 线上 N-1 → N 尚未复核：需所有者发布 `v0.1.1` 正式 Release（含 `latest.yml`）后用线上源再跑一次。
- Authenticode 强制校验默认关闭，待 SignPath 接入后以 `LUNARGOLD_REQUIRE_SIGNATURE=1` 打开。

---

## 7. P3-06 崩溃诊断与 P3-08 发布合规（2026-11-11）

### 7.1 P3-06 打包态真实窗口复核

把 `build\desktop-pack\win-unpacked` 复制到工作区内的隔离目录 `build\p3-06-repro\app`，删除 `resources\sidecar\*.jar`，并把 `DESKTOP_DATA_DIR`、`APPDATA`、`LOCALAPPDATA`、`TEMP`、`--user-data-dir`、`LUNARGOLD_JAR` 全部指向隔离目录（其中 `LUNARGOLD_JAR` 指向不存在的文件）。因此本轮没有触碰本机真实安装目录和 `%USERPROFILE%\.lunargold`。

真实窗口证据（`--disable-gpu --no-sandbox --remote-debugging-port`，Electron 43.3.0）：

| 检查项 | 结果 |
|---|---|
| 诊断卡片渲染 | 通过：标题「启动失败」，错误码 `ELECTRON_MAIN_START_FAILED`，版本 v0.1.2，操作入口「重试启动 / 导出诊断包 / 退出」 |
| 报告落盘与脱敏 | 通过：`diagnostics\reports\*.json`（758 字节），详细日志中的绝对路径全部为 `<PATH>`，无 `local-token` / `aes.key` |
| 重试启动 | 通过：新增 `2026-11-11T14-51-44-749Z-bab498-JAVA_START_FAILED.json`（882 字节），窗口重新渲染新诊断编号，进程存活 |
| 退出 | 通过：进程结束，`lunar-gold` 残留 0 |
| 导出诊断包 | 部分：保存对话框正常弹出（窗口类 `#32770`，标题「导出 Lunar Gold 诊断包」，默认文件名 `LunarGold-diagnostic-<code>-<id>.zip`），但本机自动化无法驱动该系统原生对话框完成保存（标题匹配、UI Automation、鼠标点击、键盘输入均未落盘），保留人工复核 |

自动化探针位于 `build\p3-06-run.ps1` 与 `build\p3-06-cdp.mjs`（`build/` 为忽略目录，未入库）。测试期间机器上原有的 2 个 `java`、7 个 `postgres` 进程属于开发环境，数量未因本轮变化；本应用没有拉起任何 Java/PG 子进程。

### 7.2 P3-08 合规产物可复现

- 新增 [build-compliance.ps1](file:///d:/test-code/agent-gateway-v3/scripts/build-compliance.ps1)：输出 `build/legal` 的 LICENSE、NOTICE（中英）、隐私政策与服务条款（中英）、四份 SBOM 与 `THIRD-PARTY-LICENSES.txt`，共 12 个文件。必需文件缺失/为空、SBOM 不可解析或组件为空、许可合集为空均直接失败；Java 许可逐项校验并对缺失项按固定地址重试，npm 无许可文件时按 SPDX 文本补齐，许可文件计数按递归统计。
- 新增 [licenses-config.xml](file:///d:/test-code/agent-gateway-v3/scripts/licenses-config.xml)：为 16 个缺少许可 URL 或 URL 不可达的 Maven 组件固定 SPDX 官方文本（jsDelivr 固定版本镜像）。
- [pom.xml](file:///d:/test-code/agent-gateway-v3/pom.xml)：补齐项目 URL/SCM，固定 `cyclonedx-maven-plugin` 2.9.3 与 `license-maven-plugin` 2.7.1（`errorRemedy=warn`，最终由脚本逐项强制校验）。
- 集成：[build-desktop-installer.ps1](file:///d:/test-code/agent-gateway-v3/scripts/build-desktop-installer.ps1) 在 electron-builder 前调用合规脚本，并对 `build/legal` 与 `win-unpacked/resources/legal` 逐文件比对 SHA-256；[package.json](file:///d:/test-code/agent-gateway-v3/desktop/package.json) 用 `extraResources` 映射 `../build/legal`；[release.yml](file:///d:/test-code/agent-gateway-v3/.github/workflows/release.yml) 复制到 `release/legal`、严格校验并显式上传（`fail_on_unmatched_files: true`）。
- 构建证据：`build/legal` 12 个文件（`THIRD-PARTY-LICENSES.txt` 为 23,980,744 字节）；`build\desktop-pack\win-unpacked` 586.84 MiB；`release\installer\LunarGold-Setup-0.1.2.exe` 329,706,096 字节（314.43 MiB），`tar -tf` 可列出 `resources/legal` 下 12 个文件，`resources/legal` 与 `build/legal` 哈希一致。
- 文档：[NOTICE](file:///d:/test-code/agent-gateway-v3/NOTICE) / [NOTICE.en](file:///d:/test-code/agent-gateway-v3/NOTICE.en)、[README.md](file:///d:/test-code/agent-gateway-v3/README.md) / [README.en.md](file:///d:/test-code/agent-gateway-v3/README.en.md) 更新为四份 SBOM 与第三方许可合集的说明及重新生成命令。

边界：法律管辖与隐私联系人仍由项目所有者确认；本轮没有发布标签，也没有触发远程 Release。

---

## 8. Phase 5：提示词、评测门禁与真实 ReAct 闭环（2026-11-13）

### 8.1 P5-01 提示词外置与版本化

- 系统提示词与工具提示已外置到 `src/main/resources/prompts/`，由 `index.json` 按场景管理 ID、版本和校验值。
- `PromptCatalog` 在启动时校验版本、SHA-256 和必备段落；热重载失败保留旧快照，并提供 `POST /api/admin/prompts/reload` 管理入口。
- 每轮 Agent 运行通过 `PROMPT_LOADED` 事件记录实际使用的场景与提示词版本。
- 验收：`PromptCatalogTest` 5 项通过。

### 8.2 P5-02 评测集

- 抽取 `AgentClient` 接口，生产实现为 `SpringAiAgentClient`；测试使用 `ScriptedAgentClient` 离线回放工具调用和文本响应。
- 建立工具选择、文件编辑、文件删除、命令审批、RAG、拒答 6 类共 27 条 JSONL 用例；fixture 在临时工作区运行，报告包含整体及分类别成功率、轮次和 token 指标。
- 验收：27/27 用例通过，脚本化客户端 3 项测试通过。
- 边界：当前为组件级脚本化评测，不等同于完整 `ReActAgentService` 循环或真实模型能力的端到端评测。

### 8.3 P5-03 评测门禁

- 基线入库为 `eval/baselines/baseline.json`，记录 27 条用例状态及整体、分类别成功率、平均轮次、平均 token 和 p95 时延。
- `EvalGate` 对基线通过用例回退、用例集合未同步、成功率下降超过 2 个百分点、轮次/token 增长超过 10%、p95 增长超过 20%（另含 25ms 绝对余量）进行拦截；只有显式设置 `-Deval.updateBaseline=true` 才更新基线。
- `scripts/run-eval.ps1` 提供一键运行与基线更新入口；CI 的 `mvn verify` 强制执行门禁，并通过 artifact 归档评测报告。
- 验收：真实评测 27/27 通过，5 项门禁回退测试通过，一键脚本实测退出码为 0；全量回归 **213 项，0 失败、0 错误、12 跳过，BUILD SUCCESS**。

### 8.4 P5-13 真实 ReAct 闭环与完成控制

- **真实 Runtime 评测**：`ScriptedAgentClient` 已注入真实 `ReActAgentService.execute`，贯通消息组装、工具分发、审批、fixture 文件副作用、计划收口和 Run/Event 终态。`eval/runtime-datasets/` 的 5 个 JSONL 文件共 15 条用例，覆盖完成控制、审批批准/拒绝、读写失败恢复、多工具消息回灌、重复调用、相同失败、连续无推进、轮次耗尽、取消与 A/B 确定性。
- **完成控制**：新增 `COMPLETED / UNVERIFIED / INCOMPLETE`，与 Run 的 `SUCCEEDED / FAILED / CANCELLED` 等执行状态分离。文件变化但尚无诊断或 verify 证据时明确为 `UNVERIFIED`；计划未完成、审批拒绝、写入失败、轮次耗尽、无进展与取消均为 `INCOMPLETE`。自然结束不再把进行中计划伪装成完成，取消路径也能到达 Run=CANCELLED。
- **事件与界面**：`completion`、`no_progress` 已接入 SSE、Run Event、消息 trace 和 `resultJson`；前端从实时流、历史 trace 与 Run Event 恢复状态，并显示“未验证”或“未完成”及原因。
- **无进展检测**：工具签名使用工具名与参数 JSON 规范化结果，支持连续重复调用、连续相同失败和连续无推进轮次检测；阈值由 `agent.loop.no-progress.max-repeats`（默认 3）和 `max-stagnant-rounds`（默认 4）控制，0 为关闭。
- **编程提示词**：新增 `prompts/coding/system-v1.md` 与 `coding-system@v1` 哈希登记，工作区任务使用 coding 场景，普通对话保留 agent 场景；版本选择、SHA-256 校验和重载继续复用 P5-01。
- **独立指标与门禁**：`eval/runtime-reports/`、`eval/baselines/runtime-baseline.json` 与组件报告/基线分离，独立记录用例通过率、任务完成率、验证覆盖率、无进展停止、重复调用率、轮次、工具调用、token 和 p95。`RuntimeEvalGate` 增加任务完成率与验证覆盖率回退检查，且只有全部用例通过才允许更新基线；`scripts/run-eval.ps1`、GitHub CI 和报告归档已同时覆盖 `EvalGate` 与 `RuntimeEvalGate`。
- **验证**：后端 `mvn --batch-mode --no-transfer-progress verify` 成功；双评测门禁退出码 0；Runtime 15/15 通过、`case_pass_rate=100%`；前端 `vue-tsc --noEmit` 与 Vite 生产构建通过，1842 个模块完成转换。Windows 构建将 TEMP/TMP 指向工作区 `.tmp`，解决 esbuild 清理系统 Temp 时的权限错误。
- **边界**：基线 `task_success_rate=20%` 是用例刻意覆盖未完成/未验证路径后的脚本指标，不能解释为真实模型任务成功率；`verification_coverage=0%` 如实反映当时 P5-05/P5-06 尚未提供诊断和 verify 证据。未接入外部模型，真实模型仍只做 BYOK 本地或定时抽样。P5-05、P5-06 已完成，下一依赖项为 P5-14 任务级交付。

### 8.5 P5-05 LSP 诊断闭环（2026-11-14）

- 新增 `diagnostics` 包与 `DiagnosticsProvider` 抽象：`JavacDiagnosticsProvider` 默认在进程内离线编译变更 Java 文件；`LspDiagnosticsProvider` 通过 `Content-Length + JSON-RPC 2.0` stdio 接入外部语言服务器，仅实现 initialize → didOpen → publishDiagnostics → didClose → shutdown 的诊断最小流程。
- `DiagnosticsService` 负责 provider 选择、变更文件过滤/去重、条数与字符截断，以及全部异常降级；`write_file` / `edit_file` 成功后按批采集，结果写入 `WorkspaceContext` 并作为 `user` 消息进入下一轮上下文。
- SSE 新增 `diagnostics` 事件，Run Event 记录 `DIAGNOSTICS`，载荷含 runId、turnId、provider、available、analyzedFiles、totalCount、truncated、fileVersion 与明细；消息 trace 落库后前端实时流、历史会话和断线重放均可还原。
- 完成控制接入诊断证据：有 ERROR 为 `INCOMPLETE/DIAGNOSTICS_FAILED`，可用且无 ERROR 为 `COMPLETED/DIAGNOSTICS_PASSED`，其余保持 `UNVERIFIED/VERIFICATION_MISSING`；连续相同诊断达到阈值以 `REPEATED_DIAGNOSTICS` 安全停止。
- 配置 `agent.diagnostics.*`（enabled/provider/max-items/max-chars/timeout-ms/max-files/lsp.*），嵌套结构走 `@ConfigurationProperties`；javac 冷启动较慢，默认超时 20s。前端新增 `DiagnosticsInfo`、`onDiagnostics` 和紧凑状态行。
- 验收：4 个测试类共 16 项通过，后端 `mvn test` 全量通过，前端 `vue-tsc --noEmit` 通过；runtime 数据集无 `.java` 写入用例，基线不受影响。
- **边界**：不执行 P5-06 的 verify 命令；LSP 不覆盖补全/跳转等语义能力；外部语言服务器需用户自行安装配置。

### 8.6 P5-06 验证回路（2026-11-14）

- 新增 `com.capsule.agentkey.verify` 包：`VerifyConfig` 读取工作区 `.lunargold/verify.json`，支持 `enabled`、`maxRetries`（裁剪 0..2）与命令列表（`name/command/cwd/timeoutSeconds/required`）；`VerifyResult` 区分 `PASSED/FAILED/NOT_CONFIGURED/UNAVAILABLE`，携带尝试次数、命令退出码、超时、耗时、输出尾部与失败指纹。
- `VerifyService` 全部命令经 `CommandService.run` 沙箱执行，复用白名单、工作区路径、超时与取消；`BusinessException` 转为环境不可用；必跑命令失败为 `FAILED`，可选命令失败只记录；`maxAttempts = 1 + min(config.maxRetries, agent.verify.max-retries)`。
- `ReActAgentService` 在每轮写操作结束后验证一次，失败输出以 `【验证结果】` 注入下一轮；连续相同失败指纹达到 `agent.loop.no-progress.max-repeats` 时推送 `NoProgress(REPEATED_VERIFY_FAILURE)`；超过重试上限以 `INCOMPLETE/VERIFY_RETRY_EXHAUSTED` 停止；候选完成时若验证未覆盖最新变更会补验证。
- 完成判定：诊断 ERROR → `DIAGNOSTICS_FAILED`；验证失败 → `VERIFY_FAILED`；验证通过 → `VERIFY_PASSED`；未配置 → `VERIFY_NOT_CONFIGURED`；环境不可用 → `VERIFY_UNAVAILABLE`。无验证配置时干净编译诊断不再直接显示为已完成。
- 新增 `VERIFY` Run Event 与 `verify` SSE（含 `authorization=project-config` 审计字段），并写入消息 trace；前端新增 `VerifyInfo`、`onVerify` 与「验证通过 / 验证失败 / 未配置验证 / 验证不可用」状态行。
- 配置 `agent.verify.*`（enabled/config-path/max-retries/max-output-chars/default-timeout-seconds），使用 `@ConfigurationProperties`。
- 验收：`VerifyServiceTest` 与 `ReActVerifyIntegrationTest` 覆盖失败回灌修复、重试耗尽、未配置、环境不可用、重复失败停止、指纹与输出截断；后端全量测试通过，`EvalGate,RuntimeEvalGate` 通过（组件 27/27、Runtime 15/15），前端 `vue-tsc --noEmit` 通过。
- **基线说明**：Runtime 用例 reason 按新语义更新为 `VERIFY_NOT_CONFIGURED`；Runtime 基线 p95 因首例冷启动与 200ms 以下分类的计时噪声增加了保守余量，质量、轮次、token、任务成功率与验证覆盖率未放宽。
- **边界**：验证命令由项目配置维护者提供；不自动安装工具链、不提供配置编辑器；人工接受未验证变更以提示和用户审查为准，不新增后端状态机。

### 8.7 P5-04 代码索引（repo map + 符号索引/增量缓存，2026-11-14）

- 新增 `RepoMapService` 与 `AgentRepoMapProperties`（`agent.repo-map.*`）：递归扫描工作区，跳过依赖/构建/发布目录与隐藏文件，输出目录树并附加源码关键符号；Java 提取包/类型/方法，TS/Vue/Python/Go/Rust 使用轻量正则，Vue 无 export 时回退组件名。
- 新增可插拔 `CodeIndexer` 抽象与 `LightweightCodeIndexer` 正则实现；`CodeIndexService` 提供工作区符号索引、`findSymbols` 查询与按工作区内存增量缓存：以文件大小 + 修改时间判断复用，只重扫新增/变更文件并清理已删除文件，同一工作区扫描串行化保证线程安全，并输出扫描/复用/移除文件数与符号数统计。
- `RepoMapService` 改为委托 `CodeIndexService` 渲染，输出格式与预算行为保持不变；`max-chars`（默认 6000）超限时截断并提示改用 `list_dir` / `search_files`；`max-depth`、`max-files`、`max-symbols-per-file` 与总开关均可配置，扫描或读取失败时降级为空串，不影响对话。
- `ReActAgentService` 已用 repo map 替换原单层目录树注入 system prompt（`【仓库结构（repo map）】`），删除 `buildWorkspaceTree`；`RepoMapServiceTest` 覆盖符号、忽略目录、深度、预算、符号上限、关闭与空工作区、确定性，`CodeIndexServiceTest` 覆盖符号索引与查询、增量只重扫变更文件、缓存清理、忽略目录、超大文件降级与空查询。
- 验收：后端 `mvn -o -q compile`、`mvn -o -q test` 全量通过，`EvalGate,RuntimeEvalGate` 通过（组件 27/27、Runtime 15/15）。
- **边界**：JavaParser/tree-sitter 精确符号、可选 `code_index` 持久表（V19）与命中符号对轮次/token 的量化报告仍为后续增强，不阻塞 P5-07 与 P5-14。

### 8.8 P5-07 RAG 混合检索与引用（2026-11-14）

- `VectorStoreService.searchLexicalForProject`：基于 V9 已创建的 pg_trgm 扩展，用 `word_similarity(query, content)` 做词法召回，支持项目与点名资料过滤；pg_trgm/SQL 失败时降级为纯向量检索，不影响对话。
- 新增 `RagCandidate`、可插拔 `Reranker` 与默认 `RrfReranker`：向量与词法两路按 `1/(k+rank)` 融合，按 `chunkId` 去重并保留两路最高分；k 由 `rag.rrf-k` 配置（默认 60）。
- `DocumentService.retrieveContext`：向量与词法并行召回；向量最高分未过 `gate-score` 但词法分过 `lexical-gate-score` 时仍使用资料；点名资料跳过整体门槛；`rag.hybrid-enabled=false` 时保持原单路向量行为。
- 引用：`Source` 增加 `chunkId` / `chunkIndex`，`sources` SSE 事件同步下发，前端来源提示展示“第 N 段”；页码因解析器暂未保留版式而不提供。
- 配置：`rag.hybrid-enabled`、`rag.lexical-min-score`、`rag.lexical-gate-score`、`rag.rrf-k`，均可通过环境变量覆盖。
- 测试：`RrfRerankerTest`（双路命中优先、去重、纯词法顺序、topK）与 `DocumentServiceRagTest`（词法补召回、双弱拒绝、点名跳过门槛、混合关闭降级、引用字段）通过；后端全量测试与 `EvalGate,RuntimeEvalGate` 通过；前端 `vue-tsc --noEmit` 通过（vite 打包在本机因 esbuild 临时文件删除被拒而中止，与本次改动无关）。
- **边界**：模型 reranker 离线对照、真实语料上的排序收益报告、PDF 页码引用仍未完成；`halfvec`（V20）继续按体积或检索性能数据决定是否实施。

### 8.9 P5-08 并行只读工具（2026-11-14）

- `BaseTool` 增加 `readOnly()`（默认 false）；`read_file`、`list_dir`、`search_files`、`web_search`、`fetch_webpage`、`calculator`、`get_current_time`、`get_current_weather` 显式标记只读。`ToolRegistry.isReadOnly` 统一判定，MCP、fallback 与需要审批的工具一律按串行处理。
- `ReActAgentService` 每轮按「连续只读批」切分：批内使用有界线程池（`agent.parallel.readonly-max-concurrency`，默认 3），批外的写/命令保持串行 + 审批。批内结果按模型原始下标回放，`onToolCall` / `onToolResult` 与 tool 消息顺序不变。
- 并行所需上下文：`WorkspaceContext.child()/callIn()` 把用户、项目、scope、取消标记与工具统计带入工作线程并在结束后恢复；会话工具统计由 ThreadLocal 迁入上下文，避免并行子线程丢失成功率数据。
- 停止生成：批内任务启动前检查取消标记，等待期间收到取消会 `cancel(true)` 剩余任务，并按 Run=CANCELLED、`INCOMPLETE/CANCELLED` 立即收尾，不再执行本轮剩余调用。
- 并发上限：Runtime 用例中单轮只读批实际为 2～3 个；3 个 120ms 模拟只读调用的 fixture 基准为串行 p95 282ms → 3 并发 p95 98ms（约 −65%），继续加线程不再降低该批耗时，因此默认 3，可由配置覆盖。
- 验收：新增 `ReadOnlyToolBatchTest` 5 项（并发且顺序稳定、上限=1 串行、读写交错不重叠、停止取消排队任务、p95 收益），`ToolRegistryTest` 补只读/MCP/fallback 判定；后端全量 **272 项，0 失败、0 错误、12 跳过**；`EvalGate,RuntimeEvalGate` 通过（组件 27/27、Runtime 15/15，Runtime p95 6685ms 低于基线 13000ms，基线未更新）。
- **边界**：只并行「连续」只读批，单次调用不建池；MCP 与 fallback 不并行；真实模型与真实工作区的收益仍需按线上 trace 复核。

### 8.10 P5-14 任务级 ChangeSet 与交付（2026-11-14，基础完成）

- **不新增迁移**：`AgentChatController` 在 `AgentRunService.create` 前生成 `turnId` 并写入 `AgentRun.requestJson`；`ChangeSetService` 据此建立 runId ↔ turnId 映射。复用 `PendingFileChange`、Run/Event、审批与 Verify/Diagnostics，未新增 Task/ChangeSet 表。
- **聚合视图**：新增 `GET /api/file-changes/changeset?sessionId=&runId=`，按 Session 聚合多次 Run 的文件、增删行、diff、来源 run/turn、审批、诊断与验证；`runId` 可过滤单个 Run。状态覆盖进行中、待审批、失败、已停止、未完成、未验证、验证通过、用户已接受与已完成，刷新后由 DB 重建。
- **未验证保护**：`verification` 只取最后一个 VERIFY 事件或 completion reason；接受/全部保留只改 `PendingFileChange.status`，不会把 `NOT_CONFIGURED/UNAVAILABLE/MISSING` 改写为验证通过，任务状态仍为 `UNVERIFIED`。
- **继续修复**：`continuePrompt` 仅对失败/未完成/未验证/已停止生成，包含原任务、失败命令与 `outputTail`、诊断 ERROR 和最近计划未完成步骤；前端在当前 Session 调用现有发送流程。
- **撤回与冲突**：新增 `POST /api/file-changes/revert-session` 与 `/revert-run`，按 `createTime DESC, id DESC` 逆序回滚 PENDING 变更；写盘前将磁盘内容与 `newContent` 比较（换行归一），外部修改/删除返回冲突且不覆盖；ACCEPTED 变更保留审计并计入 `skippedAccepted`。既有单条、项目批量与按轮撤回统一复用同一逻辑。
- **前端看板**：`PendingChangesCard.vue` 升级为任务交付卡，展示状态、验证摘要、失败命令、审批进度、Run 过滤、按 Run 分组文件与 diff，提供「撤回此轮」「整体撤回」「继续修复」「全部保留」；`ChatView` 在本地工作区首屏触发加载。
- **验证结果**：新增 `ChangeSetServiceTest` 7 项，覆盖多 Run 聚合与过滤、continuePrompt、接受不覆盖验证、逆序整体撤回、按 Run 撤回、外部修改冲突、已接受跳过；后端全量测试通过；`EvalGate,RuntimeEvalGate` 通过；前端 `vue-tsc --noEmit` 通过。
- **边界**：P5-11-1 的前端 Vitest/Playwright 未实施，真实浏览器 E2E 未执行；命令产生的未记录外部副作用不承诺回滚；历史 Run 缺少 `turnId` 时其变更仍展示，但可能无法按 Run 归属或撤回。
  - 2026-11-14 更新：前端测试基线已由 P5-11-1 补齐，见 8.11；真实后端 + 真实模型的干净机器验收仍未执行。

### 8.11 P5-11-1 前端测试基线（2026-11-14）

- **Vitest**：新增 `frontend/vitest.config.ts`（`mergeConfig` 复用 Vite 插件、jsdom 环境、`passWithNoTests`），`package.json` 增加 `test` / `test:watch` / `test:e2e` 与 `vitest`、`@vue/test-utils`、`jsdom`、`@playwright/test` 四个 devDependency。
- **单测覆盖**：`api/sse.test.ts`（8 项：多行 data 合并、CRLF、done 终止并 cancel、error 终止、流掐断残余派发、忽略 id/retry/注释、data 去一个空格、`streamChat` 全事件解析）；`stores/chat.test.ts`（4 项：发送后释放 runId、停止保留半截内容、审批提交状态迁移、`recoverRun` 重放 MODEL_CHUNK）；`ApprovalCard.test.ts`（6 项：渲染、允许/拒绝、resolved 态、倒计时 dismiss、A/D 快捷键、非首张不响应）；`MarkdownContent.test.ts`（4 项：语言代码块、无语言回落、复制反馈、标题/列表/链接）。共 4 文件 22 项通过。
- **Playwright**：新增 `frontend/playwright.config.ts`（`testDir: e2e`、workers=1、trace `retain-on-failure`、`webServer` 自动起 Vite 5174、`E2E_BASE_URL` 覆盖、`E2E_CHANNEL` 选择浏览器）与 `e2e/` 5 个 spec 共 7 条用例：发送与流式、审批允许（校验请求 decision=APPROVE）、审批拒绝（decision=REJECT）、停止生成保留内容、断线按 runId 续传、接受全部不改写验证状态、整体撤回冲突提示。
- **API 仿真偏差**：原规格要求启动独立 Spring E2E 后端，实施中发现 `ScriptedAgentClient` 位于 test scope、桌面登录依赖本机令牌文件，外部进程稳定复现成本过高；改为 `page.route('**/api/**')` 在浏览器层仿真后端（含 SSE 文本、ChangeSet、审批与撤回结果），真实前端组件、store 与 SSE 解析全部走真实代码。规格 FR-7/AC-5 与 tasks/checklist 已同步修订。
- **验收**：`npx vitest run` 22/22 通过；`npx playwright test --repeat-each=3` 21/21 通过（连续 3 轮无 flaky）；后端 `mvn -o test` 279 项通过（0 失败 / 0 错误 / 12 skipped）；`npm run build` 通过（本机需把 TEMP/TMP 重定向到工作区规避 esbuild 临时文件删除失败）。
- **变异验证**：临时破坏 SSE done 终止逻辑后 `sse.test.ts` 2 项失败；把审批主按钮改为提交 `REJECT` 后 `ApprovalCard.test.ts` 1 项失败；均已还原。
- **CI**：`.github/workflows/ci.yml` 新增 `frontend-test`（`npm ci` + `npm run test`）与 `frontend-e2e`（依赖 `frontend-test`、缓存 `~/.cache/ms-playwright`、`npx playwright install --with-deps chromium`、失败上传 `playwright-report` 与 `test-results`、`timeout-minutes: 15`），均不阻塞后端打包。
- **边界**：E2E 为浏览器层仿真，不启动真实 Spring 进程、不验证真实数据库事务与真实模型链路；`test:coverage` 未接入（会引入第 5 个前端 devDependency，违反 NFR-4），覆盖率暂以用例清单与验收项体现。

### 8.12 P5-10-3 长任务进度可感知（2026-11-14）

- **阶段时间线**：`frontend/src/stores/chat.ts` 记录理解/读取/修改/验证/汇总五个阶段的首次到达时间；只读工具、写工具、`run_command`（按是否已修改归类）、`file_change`、`diagnostics`、`verify` 与 `done` 事件推进阶段。阶段只前进，未到达的中间阶段显示「跳过」；`ChatView` 每秒刷新当前阶段耗时，短问答 3 秒内不显示进度条。
- **预算可视化**：`usage` SSE 载荷新增 `round`、`maxRounds`、`maxTokens`、`elapsedMs`、`maxElapsedMs`，并在每轮 `onUsage` 时实时下发（终态仍保留一次）。前端显示轮次 / token / 耗时及上限，任一 ≥80% 转警示并提示 Agent 优先收口；旧后端缺字段时轮次显示「—」，token 与耗时仍可用。
- **时间预算**：新增 `agent.budget.max-seconds-per-run`（默认 0=不限）；`ReActAgentService` 在每轮循环开始检查，超限复用预算停止路径并以 `TIME_BUDGET_EXCEEDED` 收口，提示缩小任务范围或调高配置。`isTimeBudgetExceeded` 为包级纯函数，新增 2 项单测。
- **失败分级**：新增 `classifyFailure`，区分可重试（5xx/超时/429/网络/轮次耗尽）、需配置（401/403/缺 Key/模型不可用）、需审批（`APPROVAL_REJECTED`）与需人工（权限/冲突/写入失败/预算耗尽及兜底）。`ChatMessage` 失败框按类显示标签、提示与主操作（重试 / 去设置 / 重新加载），配置类保留次操作「重试这一轮」。
- **停止与继续**：停止保留半截内容并显示「已停止生成」；新增 `continueTask`，以固定提示「请继续完成上一条未完成的回答…」复用发送流程。恢复的 `CANCELLED` 轮次无论是否有内容都标记为已停止，避免刷新后继续显示生成中。
- **验证**：`npx vitest run` 31/31 通过（新增阶段推进/跳过、`run_command` 归类、四类失败、运行中阶段预算、继续提示与 `ChatMessage` 组件 9 项）；Playwright 10/10 通过（新增长任务进度条、配置类失败「去设置」、停止后继续 3 条）；`npm run build` 通过；后端 `mvn -o test` 281 项通过（0 失败 / 12 skipped）。
- **边界**：Playwright `route.fulfill` 不能逐块推送 SSE，E2E 只验证进度条出现、停止收起与失败动作，阶段随事件推进由单测覆盖；轮次不落库，断线恢复后显示「—」并按 run token 汇总兜底，不伪造轮次。

### 8.13 P5-10-2 审批效率剩余项（2026-11-14）

- **J/K 当前项**：`ChatView` 维护 `selectedApprovalId`；`J`/`K` 在输入态、修饰键之外移动，到边界不循环，输入框内仍正常输入。队列首次出现自动选中第一项，处理或删除后按「同位置后一项 → 前一项」收口，切会话按新队列重新收口；选中卡片用主色描边并 `scrollIntoView({ block: 'nearest' })`，点击卡片同步选中。`A`/`D` 由固定第一张改为只作用于当前项，队列提示更新为「J/K 切换 · A 允许 / D 拒绝」。
- **低风险批量**：保守白名单只含 `risk=WRITE` 的 `write_file`/`edit_file`（含 MCP `_write_file`/`_edit_file`）；命令、删除、移动、建目录与 `DESTRUCTIVE` 一律排除。新增 `POST /api/agent/approvals/batch`，服务端校验用户、`sessionId`、PENDING 与白名单，任一不满足整批拒绝；只允许 APPROVE，不提供批量拒绝或 `remember=SESSION`；每次生成 `batchId`，预检后并发失效的项只从 `resolvedIds` 剔除。前端只在低风险项 ≥2 时显示「允许全部低风险（N）」，且只标记响应中的 `resolvedIds`。
- **决策来源**：V19 为 `tool_approval` 新增可空 `decision_source` 及历史查询索引。`ToolApprovalRegistry` 的等待结果扩展为 `Resolution(decision, source, batchId)`，手动、批量、超时、会话停止、系统兜底分别落 USER / BATCH / TIMEOUT / SESSION_STOP / SYSTEM；重启恢复的过期 PENDING 落 TIMEOUT，旧记录保持 NULL。手动、停止与超时竞争时以先完成的结果为准。
- **审计与历史**：`approval_resolved` SSE 与 `APPROVAL_RESOLVED` Run Event 补齐 `approvalId/decision/source/batchId`，`ChangeSetService` 现有 approvalId 关联继续兼容。新增 `GET /api/agent/sessions/{sessionId}/approvals`，先经 `chatService.getOwnedSession` 校验归属，按 `sessionId + userId` 返回已处理记录且不含 `arguments`；追踪页待审批区之后展示工具、批准/拒绝、来源、时间与「本会话总是允许」标记，打开追踪页、切会话或审批完成时刷新。单条提交失败不再乐观标记 resolved。
- **验证**：`mvn -o test` 290 项通过（0 失败 / 12 skipped，新增来源、批量预检、并发剔除与历史查询用例）；`npx vitest run` 38/38 通过（新增白名单、批量只标 resolvedIds、失败不伪造、SSE source/batchId 解析与选中态 6 项）；`npm run build` 通过；Playwright 13/13 通过，审批用例 `--repeat-each=2` 10/10 通过（新增 J/K+A/D、混合队列批量、历史来源与切会话隔离 3 条）。
- **边界**：当前没有 `approval.runId`，批量以 `sessionId + 明确 ID 集合` 界定当前队列；批量仅允许低风险写入，不扩展到建目录/移动文件；历史不返回原始参数 JSON，也不提供跨会话查询。

### 8.14 P5-11-2 体验可观测性（2026-11-14）

- **后端体验聚合**：新增 `AgentExperienceMetricsService` 与 `GET /api/agent/metrics/experience`，仅使用 SecurityContext 当前用户数据。首字 p95 取最近 100 Run 的 `startTime` 到首条 `MODEL_CHUNK.createTime`，通过数据库 `MIN(createTime) + GROUP BY runId` 获取首片时间，不加载事件正文；nearest-rank 计算 p95。审批等待取最近 100 条已决策记录的 `decisionTime-createTime` 平均值；token 只统计 `totalTokens > 0` 的 Run；工具 Timer 按 `userId` 过滤、非 success 计失败，响应以 `scope=PROCESS` 标明进程累计。所有无样本指标返回 null。
- **本地异常与 Web Vitals**：新增 `experienceDiagnostics.ts`，接入 Vue `errorHandler`、window `error` / `unhandledrejection` 和官方 `web-vitals` 的 LCP/INP/CLS。localStorage 使用版本化键与有界白名单记录：异常 50、Web Vitals 180、Run 100、恢复 100；损坏 JSON、写满或浏览器不支持指标时静默降级。异常摘要最多 2000 字符并清理密钥/token、内容字段、绝对路径和 URL 查询串，不保存原始 Error/reason/stack。账号切换清空本地 run/session 标识。
- **开关与导出**：采集默认开启但不上传；关闭后移除 window 监听，Web Vitals 回调被开关拦截，异常、Run、恢复与 Web Vitals 均停止新增。已有记录只在用户点击清除时删除；导出只在用户点击后生成白名单 JSON，包含本地记录与当前已缓存的后端指标快照，不包含用户名。未新增远程遥测 SDK、客户端指标接收 API、数据库表或 Flyway 迁移。
- **连接样本**：`onRunId` 按 runId 去重标记本地 Run；`recoverRun` 记录真实轮询次数、恢复终态和耗时。流中断率只统计仍存在于本地 Run 窗口内的 runId，避免两个环形窗口容量不同导致比例超过 100%；恢复成功率按 recovered/failed 终态计算。正常切会话或新恢复取代旧 token 不写失败；收到任意 Run 终态并完成事件重放即记传输 recovered，Run 自身 FAILED/CANCELLED 不误算为网络恢复失败。
- **诊断面板**：设置页新增「体验诊断」tab，展示 Web Vitals 当前值/样本数、流中断率、恢复成功率/次数/平均耗时、首字 p95、工具失败率、审批平均等待和每轮平均 token，以及最近 50 条脱敏异常。支持全部/当前会话筛选、开关、清除、手动导出、后端刷新和独立失败重试；无样本显示「—」，后端失败不掩盖本地记录，样式复用亮暗主题 token。
- **聚焦验证**：`AgentExperienceMetricsServiceTest` 4/4，覆盖窗口与聚合调用、p95/失败率/平均公式、空样本和跨用户隔离；诊断工具 11 项、面板 6 项通过，chat store 增加网络重试耗尽与 Run 失败终态不污染传输恢复的回归用例。
- **全量验收**：后端 `mvn -o test` **298 项，0 失败、0 错误、12 跳过，BUILD SUCCESS**；前端 `npx vitest run` **7 文件 57/57**；`npm run build` 通过（1847 个模块，本机 TEMP/TMP 指向工作区 `.tmp` 规避既有 esbuild 清理权限问题）；系统 Edge 下 Playwright 全量 **14/14**，完整套件 `--repeat-each=3` **42/42**。诊断 E2E 制造 window error/unhandled rejection，实际下载并读取 JSON，核验脱敏、关闭停采与清除。
- **边界**：浏览器连接指标是当前设备的有界样本，后端指标是当前用户的审计聚合，均不是全平台 SLO；工具指标为进程累计而非最近 100 Run。E2E 继续采用浏览器层 API 仿真，不验证真实 Spring 事务或真实模型链路，也不断言 CI 必然产生固定 LCP/INP 数值；亮暗样式通过复用主题 token 保持，未新增整页视觉截图档案。长会话性能剖析与虚拟化仍属 P5-11-3。

详细分批状态见 [Phase 5 计划](phases/phase5/plan.md)。

---

## 9. 完成档案使用规则

1. 新任务完成后，将任务编号、实际改动、测试结果和验收边界移入本文。
2. `UPDATE_PLAN.md` 只保留未完成工作、当前阻塞和下一步，不再持续追加长篇完成批注。
3. 大型排障过程优先沉淀为专题文档，完成档案只保留结论和链接。
4. 历史“未提交”“未验证”等边界按当时事实保留；后续关闭时新增结论，不改写历史证据。
5. Windows x64 的结果不能外推为 macOS/Linux 已完成；旧安装器的验收不能外推到新哈希产物。
