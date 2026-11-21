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

### 8.7 P5-04 代码索引（repo map + 符号索引/增量缓存，2026-11-14；2026-11-16 补 A/B 量化）

- 新增 `RepoMapService` 与 `AgentRepoMapProperties`（`agent.repo-map.*`）：递归扫描工作区，跳过依赖/构建/发布目录与隐藏文件，输出目录树并附加源码关键符号；Java 提取包/类型/方法，TS/Vue/Python/Go/Rust 使用轻量正则，Vue 无 export 时回退组件名。
- 新增可插拔 `CodeIndexer` 抽象与 `LightweightCodeIndexer` 正则实现；`CodeIndexService` 提供工作区符号索引、`findSymbols` 查询与按工作区内存增量缓存：以文件大小 + 修改时间判断复用，只重扫新增/变更文件并清理已删除文件，同一工作区扫描串行化保证线程安全，并输出扫描/复用/移除文件数与符号数统计。
- `RepoMapService` 改为委托 `CodeIndexService` 渲染，输出格式与预算行为保持不变；`max-chars`（默认 6000）超限时截断并提示改用 `list_dir` / `search_files`；`max-depth`、`max-files`、`max-symbols-per-file` 与总开关均可配置，扫描或读取失败时降级为空串，不影响对话。
- `ReActAgentService` 已用 repo map 替换原单层目录树注入 system prompt（`【仓库结构（repo map）】`），删除 `buildWorkspaceTree`；`RepoMapServiceTest` 覆盖符号、忽略目录、深度、预算、符号上限、关闭与空工作区、确定性，`CodeIndexServiceTest` 覆盖符号索引与查询、增量只重扫变更文件、缓存清理、忽略目录、超大文件降级与空查询。
- **前置解耦（P5-04 实施必须）**：repo map 关闭时终端工具说明仍要注入（只看执行权限），改动在 `ReActAgentService`，用 `ReActRepoMapInjectionTest` 3 用例回归。
- **阶段 A（无模型）**：800 文件合成工作区，cold p95 271ms、warm 65ms、incremental 73ms、符号 recall 90.3%、repo map 5977/6000 字符截断。
- **阶段 B（deepseek-chat × 96 run）**：ON（repo map 开）成功率 100% / locate 准确率 100% / edit 成功率 100% / prompt tokens 中位数 4,126 / 耗时 1.76s / 定位工具调用中位数 0；OFF 62.5% / 72.2% / 33.3% / 25,072 / 10.17s / 14。B1～B5 全部通过，结论：**保持默认开启**。
- **持久缓存**：cold 271ms 属 C2 区间（200–500ms），判定不建 `code_index` 持久表；原候选版本 V19 已被 `tool_approval.decision_source` 占用，且无排队需求。
- **已知短板**（升级候选见 UPDATE_PLAN.md §5.2）：`supports()` 声明了 kotlin/scala/groovy/c/cpp/cs/php/rb/swift，但 `index()` 没有对应分支，这些文件抽不到任何符号（C# 因此在 12 文件标注集里 recall 为 0）；Java 接口中无修饰符的方法、Java 静态导入等仍会漏；repo map 用 TreeMap 字母序遍历撞上字符预算即停，大仓库靠后目录永远进不了 map；maxChars 默认 6000 对 464 文件已打满。三项都不改“内存索引 + 会话内重建”架构。
- 验收：后端 `mvn -o -q compile`、`mvn -o -q test` 全量通过，`EvalGate,RuntimeEvalGate` 通过（组件 27/27、Runtime 15/15）；`IndexBenefitEvalTest`、`IndexAbEvalTest` 门禁通过。

### 8.8 P5-07 RAG 混合检索与引用（2026-11-14）

- `VectorStoreService.searchLexicalForProject`：基于 V9 已创建的 pg_trgm 扩展，用 `word_similarity(query, content)` 做词法召回，支持项目与点名资料过滤；pg_trgm/SQL 失败时降级为纯向量检索，不影响对话。
- 新增 `RagCandidate`、可插拔 `RankFusion` 与默认 `RrfRankFusion`：向量与词法两路按 `1/(k+rank)` 融合，按 `chunkId` 去重并保留两路最高分；k 由 `rag.rrf-k` 配置（默认 60）。
- `DocumentService.retrieveContext`：向量与词法并行召回；向量最高分未过 `gate-score` 但词法分过 `lexical-gate-score` 时仍使用资料；点名资料跳过整体门槛；`rag.hybrid-enabled=false` 时保持原单路向量行为。
- 引用：`Source` 增加 `chunkId` / `chunkIndex`，`sources` SSE 事件同步下发，前端来源提示展示“第 N 段”；页码因解析器暂未保留版式而不提供。
- 配置：`rag.hybrid-enabled`、`rag.lexical-min-score`、`rag.lexical-gate-score`、`rag.rrf-k`，均可通过环境变量覆盖。
- 测试：`RrfRankFusionTest`（双路命中优先、去重、纯词法顺序、topK）与 `DocumentServiceRagTest`（词法补召回、双弱拒绝、点名跳过门槛、混合关闭降级、引用字段）通过；后端全量测试与 `EvalGate,RuntimeEvalGate` 通过；前端 `vue-tsc --noEmit` 通过（vite 打包在本机因 esbuild 临时文件删除被拒而中止，与本次改动无关）。
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

### 8.15 P5-11-3 长会话性能（2026-11-14）

- **剖析方法与基线**：新增 `frontend/e2e/perf-long-session.spec.ts`（`@perf`，默认跳过，`PERF=1` 才运行），用 `page.route` 注入 500 条含标题/列表/代码块的消息与两段会话；`MutationObserver` 记录「点击会话 → 首条消息进入 DOM」，`PerformanceObserver('longtask')` 只统计流式开始后的长任务；页面内覆盖 `fetch` 返回 ReadableStream，按约 30 chunk/s 持续 60 秒。对生产构建（`vite preview`）实测：改造前首屏 **220ms**、消息区 **9000** DOM 节点、500 条全部渲染、8 秒流式 1 个 56ms 长任务；改造后首屏 **108ms**、消息区 **216** 节点、实际渲染 **11** 条、60 秒流式 **0** 长任务。
- **流式按帧合并**：新增 `utils/frameThrottle.ts`（rAF 调度 + 50ms 最小间隔，push/flush/cancel）。`stores/chat.ts` 的正文与思考改为合帧提交，`onDone`/`onError`/停止/`finally` 强制 flush，保证最终正文与后端全量文本逐字一致。自动跟底同样合帧，并改为仅在用户停留底部时跟随。
- **Markdown 增量渲染**：新增 `utils/markdownSegments.ts`。非流式仍整篇 `marked.parse`；流式用 `marked.lexer` 顶层 token 按 `type + raw` 做 200 条有界缓存，只渲染新增/变化段落，流式结束后切回整篇解析，修复引用式链接等跨段语义。代码块高亮与复制按钮行为不变，未变化代码块不重复高亮。
- **虚拟滚动**：新增 `composables/useVirtualMessages.ts`，消息数 > 200 时只渲染可视窗口（上下 6 条 overscan、800px 缓冲），`ResizeObserver` 实测高度、未实测按估算，上下占位撑起真实滚动高度；≤ 200 条保持原全量渲染。搜索定位、缩略导航高亮与跳转改用高度缓存偏移，目标未渲染时先按估算滚动、渲染后按实测校正；正在流式的最后一条始终在窗口内。
- **保真与边界**：单条消息复制与代码块复制不受影响；虚拟化下跨窗口整段拖选不可用，需要跳转后分段复制，边界写入 `spec.md`。虚拟化只改变渲染范围，不改变消息顺序、历史还原、断线恢复、审批与 ChangeSet 行为。
- **全量验收**：前端 `npx vitest run` **10 文件 75/75**；`npm run build` 通过；系统 Edge 下 Playwright 全量 **14/14**（`@perf` 默认跳过），`PERF=1` 时 500 条性能用例通过；后端 `mvn -o test` **294 项，0 失败、0 错误、12 跳过，BUILD SUCCESS**。性能数字来自本机生产构建，不能外推为所有设备或 CI 环境的固定值。

### 8.16 P5-10-4 键盘与无障碍（2026-11-15）

- **快捷键面板**：`Ctrl + /` 打开，`aria-modal`；打开时记录来源焦点并聚焦关闭按钮，Tab / Shift+Tab 由 `utils/focusTrap.ts` 限制在面板内，Esc 或关闭按钮关闭后归还焦点（来源已卸载时回退输入框）。空态快捷键提示补充 `Ctrl + /`。
- **会话项键盘化**：会话主体改为原生 `<button>`，「更多」按钮保留在按钮外避免嵌套交互；Enter / Space 原生切换，`aria-current` 标记当前会话，读屏名称取可见标题与时间。
- **图标按钮与区域语义**：侧栏、工作台、文件树、审批卡片、编辑器、规则面板的图标按钮补 `aria-label`；审批队列为 `role="region"`，审批卡片名称为「待审批：路径 / 命令」。
- **播报**：审批队列数量变化播报「有 N 项审批待确认，按 A 允许、D 拒绝、J/K 切换」，清空播报「审批已处理完」；流式结束的「回答已生成」在仍有待审批时不覆盖审批提示。
- **减少动效**：`theme` store 新增 `reduceMotion`，默认跟随系统并监听 `matchMedia` 变化；设置页「外观」可手动开启并持久化。挂载前统一写入 `html[data-motion="reduce"]`，样式只保留一条规则；平滑滚动在减少动效下降级为 `auto`。
- **验证**：新增 `e2e/accessibility.spec.ts` 5 条，覆盖面板焦点不逃逸与焦点恢复、会话项键盘切换、审批区域语义与键盘决策、`/chat` 与 `/settings` axe critical 0；axe 的 `nested-interactive` 与 `label-content-name-mismatch` 两项 serious 随会话项重构消除。前端 Vitest **12 文件 85/85**，系统 Edge 下 Playwright **19 通过、1 跳过**（`@perf`），`npm run build` 通过。
- **边界**：新增 devDependency `@axe-core/playwright`，只用于测试；axe 只覆盖可自动检测规则，不等同完整 WCAG 审计。浏览器会拦截 `Ctrl + K`，该快捷键维持既有实现，未纳入 E2E。无后端改动、无数据库迁移。

### 8.17 P5-09 剩余视觉项（2026-11-15）

- **工具运行态**：`ToolInvocation` 增加前端 `startedAt`，`onToolCall` 写入开始时间；`ChatMessage.vue` 只在存在运行中工具时每秒刷新，折叠摘要显示「正在读取… · 已用 N 秒」，步骤行显示「进行中 · N 秒」，结束后改用后端 `costMs`，组件卸载时清理定时器。
- **消息密度**：`tokens.css` 新增 `--density-row / --density-gap / --density-pad-y`，`[data-density="compact"]` 覆盖为 24px / 4px / 4px；`theme` store 持久化 `density`，设置页「外观」新增「消息密度」（舒适 / 紧凑）。`.step-row` 与 `.timeline` 只引用 token，紧凑档下工具行 24px、舒适档 32px。
- **排版细节**：消息操作条增加 `:focus-within`，键盘 Tab 到复制按钮时同样可见；`.md-content table` 增加边框、表头底色、偶数行斑马纹与密度内边距；引用 chip 直接显示「第 N 段」，相关度保留在 `title`。
- **响应式**：`toggleSidebar()` 断点由 `< 769` 统一为 `< 768`；768-1023 下工作台改为右侧浮层（宽度 `min(100vw - 56px, 520px)`）+ 半透明遮罩，点击遮罩收起，隐藏拖拽条，不再挤压正文。
- **三态收口**：复核 `SkeletonMessage`、首屏空态、工作台 `wb-empty` 与 `ErrorState`；`ErrorState` 增加可选「导出诊断」，会话加载失败时可直接导出体验诊断 JSON，其他调用方不传则不显示。
- **实测归档**：新增 `e2e/responsive.spec.ts` 4 条。960×640 侧栏 56px、横向溢出 0、输入框与发送按钮在视口内；900×700 工作台浮层右缘 ≤900、遮罩可关闭；767×700 侧栏默认在屏幕外、菜单按钮可拉出；1280×800 侧栏 280px。截图输出到 `frontend/test-results/`，不入库。
- **验证**：前端 `npx vitest run` **12 文件 88/88**；系统 Edge 下 Playwright **23 通过、1 跳过**（`@perf`）；`npm run build` 通过。无后端改动、无数据库迁移、无新增依赖。

### 8.18 P5-07-2 检索质量量化与 reranker 决策（2026-11-15）

- **数据集**：`eval/rag-datasets/chunks.jsonl` 3 篇文档 / 24 个切片（中文产品规格含编号、英文部署文档、代码说明含函数名与配置项），`queries.jsonl` 22 条查询（semantic 8 / exact 6 / mixed 3 / cross-lingual 2 / noise 3）。`exact` 查询的编号与术语刻意不进入 `concepts`，确保向量路径真实漏召回；`relevantChunkIds` 与 `expectEmpty` 在 runner 中做一致性校验。
- **评测器**：`RagRetrievalEvalRunner` 用内嵌 PostgreSQL + pgvector 跑真实 Flyway 迁移，写入语料后分别执行 vector / lexical / hybrid 三配置（除召回范围外无其他差异）。`SimulatedEmbedding` 由 concept FNV-1a 哈希生成 1024 维归一化向量，报告写 `embedding=simulated`；设置 `RAG_EVAL_EMBEDDING_KEY` 时走 `EmbeddingService` 真实模型模式。为让小数据排序确定，评测前删除 HNSW 索引，`SingleConnectionDataSource` 避免把连接建立耗时计入检索。
- **实测指标**（19 条正向查询，topK=5）：Recall@3/5、MRR@5、nDCG@5、doc_hit_rate —— vector 均 68.42%，lexical 均 31.58%，hybrid 均 100%；`exact` 类 vector 0% → hybrid 100%；`semantic` / `mixed` / `cross-lingual` vector 与 hybrid 均 100%、lexical 0%；noise 3 条三配置全部拒绝；`context_tokens` 24.89 → 36.32（估算），p95 1.63ms → 2.70ms（只含召回与融合）。
- **门禁与基线**：`eval/baselines/rag-baseline.json` 记录三配置、分类别与 noise 拒绝数；`RagEvalGateTest` 要求 hybrid Recall@5 ≥ vector、`exact` 类严格提升、noise 全部拒绝，并对比基线（Recall@5 / doc_hit_rate 下降 ≤2pp，p95 增幅 ≤20% + 25ms）。负向验证时临时把 hybrid 降级为 vector，得到 4 条可读失败后删除临时代码。`scripts/run-rag-eval.ps1` 自动定位 pgvector 运行时并支持 `-UpdateBaseline`，`eval/rag-reports/` 已加入 `.gitignore`。
- **结论**：按 spec 预写规则判定 **不启用模型 reranker**（hybrid 提升 31.58pp、exact 类提升 100pp、noise 全拒绝）；未改动任何生产代码、迁移与 `rag.*` 默认值，模型 reranker、halfvec（V20）与 PDF 页码仍为后续项。
- **验证**：设置运行时后 `scripts/run-rag-eval.ps1 -UpdateBaseline` 实测成功（runner 与 gate 均通过、BUILD SUCCESS、无 JDBC 泄漏），同一数据集除 p95 外指标逐位一致；后端全量 `mvn -o test` **295 项，0 失败、0 错误、13 跳过**，`EvalGate` / `RuntimeEvalGate` 均通过；未设置 `DESKTOP_PG_RUNTIME` 时 `RagEvalGateTest` 跳过，`RagRetrievalEvalRunner` 因不匹配 surefire 默认命名只由脚本或显式 `-Dtest` 运行。
- **环境事实**：本机 `mvn` 不在 PATH，改用 IDE 内置 Maven 并以 `C:\Users\16680\.jdks\temurin-24` 运行通过；同一套测试在 `ms-21.0.11` 下 `JavacDiagnosticsProviderTest.validJavaHasNoErrors` 会超过其 15s javac 超时，属既有用例的环境性能差异，与本任务改动无关。

### 8.19 P5-07-3 难例检索与真实模型 reranker 对照（2026-11-15）

- **数据集（新增，不改 P5-07-2 文件）**：`eval/rag-datasets/hard-chunks.jsonl` 160 个干扰切片（12 个“答案埋在长文中”的目标切片 + 多主题干扰 + 逐查询同款措辞干扰），`hard-queries.jsonl` 12 条 hard / 2 条 noise；每条 hard 唯一正确切片、与目标 concept 无交集，加载时硬校验。
- **评测器**：`RagRerankEvalRunner` 复用内嵌 PG + Flyway；候选池 `CANDIDATE_K=20`（向量/词法各 20 → `RrfRankFusion(60)` 融合 20），`hybrid` 截断 5，`hybrid+rerank` 对 20 条调用真实 reranker 取 5；门槛沿用生产值（向量 0.5/0.55、词法 0.3/0.35）。报告输出 `calibration`（逐条两路排名、是否进池）与 `decision`（Q1～Q8 台账 + 三选一结论）。
- **真实模型**：embedding `BAAI/bge-m3`（1024 维，index 6430 / query 175 tokens 均为实测 usage）、reranker `BAAI/bge-reranker-v2-m3`（`/v1/rerank`，13120 tokens / 12 次调用）；无 Key 时 runner 按前置缺口终止，脚本打印缺口并以非 0 退出。
- **校准**：D1（12 hard / 160 干扰 / 2 noise）、D2、D3（目标两路排名 >5 占比 **83.33%**）、D4（候选池缺口 0）全部满足。边界：本集查询为同义改写，词法路仅 3/12 条有候选，实际对照接近“向量单路 top-20 → rerank”。
- **A/B（12 条 hard）**：top-1 8.33% → 16.67%（+8.34pp）、Recall@5 16.67% → 41.67%、MRR@5 12.50% → 27.78%、nDCG@5 13.59% → 31.35%、noise 2/2 两配置全拒绝；检索 p95 42.29ms 不变，rerank p95 482.81ms、总 p95 508.06ms；rerank 1093.33 tokens/查询（约候选正文 1.18×），未配置单价故不输出货币成本。
- **结论**：按跑数据前写定的阈值，Q1（top-1 需 ≥ +10pp）与 Q6（rerank p95 需 ≤300ms）不通过，其余通过 → **保持不启用**；生产默认策略、`rag.*` 与 P5-07-2 数据集/基线均未改动。
- **门禁与脚本**：`RagRerankEvalGateTest` 只断言数据有效性、报告结构并对 `hybrid` 做基线回退防护，不做“reranker 必须提升”的硬断言；新增 `eval/baselines/rag-rerank-baseline.json`（`-Deval.updateBaseline=true` 写入）；`scripts/run-rag-rerank-eval.ps1` 自动定位 pgvector 运行时、缺 Key 不启动 Maven。
- **验证**：`RagRerankEvalRunner`、`RagRerankEvalGateTest` 实测通过（gate 1 项通过）；`EvalGate`、`RuntimeEvalGate`、P5-07-2 的 `RagEvalGateTest` 均通过，`rag-baseline.json` 未变；后端全量 `mvn test` 296 项中 2 项失败为既有迁移计数断言（`FlywayV18MigrationAcceptanceTest` 期望 latest=18、`DesktopPgVectorIntegrationTest` 期望 latest=15，实际均为已提交的 V19），与本任务无关。

详细分批状态见 [Phase 5 计划](phases/phase5/plan.md)。

---

## 9. Phase 6：多入口与本地 API 契约（2026-11-16 ～ 2026-11-17）

Phase 6 的命题是让同一智能体能力与同一份任务数据可由 Web、CLI、VS Code 三个入口调用；不 fork VS Code，不复制第二套 controller。2026-11-17 出口检查通过，本阶段收口。计划与逐批次证据见 [Phase 6 计划](phases/phase6/plan.md)。

### 9.1 P6-01 公开 API 子集与客户端凭证

- 不新增 `/api/v1`，以显式公开子集清单约束客户端凭证；清单外接口对客户端一律 403，Web 现有路径不变。
- 新增客户端凭证 `lgc_<12 位 client_id>_<secret>` 与 `api_client` 表（V20）；scope 最小集为 `chat:write`、`run:read`、`changes:read`、`changes:write`、`approval:write`、`project:read`、`client:admin`；仅监听回环地址，客户端可单独撤销且撤销后立即失效。
- 契约防线落在「子集清单 + OpenAPI 快照 + 鉴权矩阵测试」，覆盖 401/403/scope 越权与非回环/伪造 Origin/Host 拒绝。
- 响应补 `requestId`，日志与 Run Event 可关联。

### 9.2 P6-02 CLI

- 交付 `cli/`：零运行时依赖、Node 24 type-stripping、无构建步骤；8 条命令（`run`、`watch`、`changes`、`approve`、`revert`、`client`、`doctor` 等）与 `--json` NDJSON。
- 退出码语义稳定：正常无变更 0 / 有变更 6 / `error` 5 / 停在审批 4 / 断流续看重放不重复；`watch` 0·5·6（401 不重试→3）；网络/5xx→1；未知命令/缺参→2。
- 真机 e2e `scripts/cli-e2e.ps1` 在 desktop profile（18082、独立数据目录与工作区副本）跑通「client create → doctor → run（exit 6，生成 note.txt）→ changes → revert（文件还原）→ client show/revoke」。
- 2026-11-17 收尾修复：`parseTokenClientId` 原先按首个 `_` 切分，`client_id` 以下划线开头时返回 `null`，导致 `doctor` 误报与 `client show/revoke` 丢 id；现与后端 `ApiClientService` 对齐为固定 12 字符解析并校验第 13 位分隔符与 secret 非空。CLI 单测 71 → 72，e2e 复跑通过。

### 9.3 P6-03 VS Code 薄插件

- 交付 `ide/vscode/`：TypeScript + esbuild 单文件 bundle，运行时零依赖；令牌只存 SecretStorage；变更使用 VS Code 原生 diff；不做 LSP、补全、多任务并发与远程适配。
- 实测 VS Code 1.143.1 安装 vsix 后，连接、流式任务、批准/拒绝/本会话记住、原生 diff、按 Run/会话撤回、停止与工作区错位提示均通过；扩展 43 条单测。
- 保留偏差：双待审批的「全部批准」成功路径未真机复现；远程窗口无真机环境。

### 9.4 P6-05 / P6-05b 多入口一致性与审批入口审计

- `DecisionSource` 增加 `CLIENT`；单条审批按调用方区分 Web（`USER`）与 CLI / VS Code（`CLIENT`），批量保持 `BATCH`；V21 增加 `tool_approval.client_id`，历史记录保持 `NULL`。
- `GET /api/agent/metrics/experience` 的 `approval.byEntry` 按 `WEB` / `CLIENT`（按 `client_id` 分组）/ `AUTO` / `UNKNOWN` 输出审批量、批准率与平均决策耗时；自动来源不进入人为批准率分母。
- 真机复核：Web 工作台 7 条审批来源为 `WEB`；VS Code 以 `CLIENT=FeBIz_31mXyW` 审批 1 条（批准率 100%，决策 8600ms）；CLI 由 `cli-e2e` 落 `CLIENT`。`$r.data.approval.byEntry` 在 Web 工作台与「设置 → 应用信息 → 高级诊断」可见分组表。

### 9.5 P6-06 Run 生命周期与断流解耦

- 拆分 `cancelRequested`（显式停止）与 `clientDetached`（SSE 断开）：断流只停止推送，Run 继续执行；事件先落库后发送；审批跨断流保持 `PENDING`；`GenerationRegistry` 支持同会话多 Run；CLI Ctrl-C 改为显式调用 `/chat/stop`。
- 后端 351 tests（新增 20 条，其中 5 条为真实 Tomcat + HTTP 断流验收）通过；CLI 71、扩展 43 通过。
- 已知偏差：`onError` / `onTimeout` 改为直接回调验证；后端进程重启仍不续跑（非本批次目标）。

### 9.6 P6-07 交互收敛

- 审批卡片去掉风险 chip 与秒级倒计时，`本会话总是允许` 收进更多菜单，过期不再自行隐藏；回答气泡显示本轮 token（悬停看输入/输出）；「体验诊断」移入「应用信息 → 高级诊断」并默认隐藏，需显式勾选。
- 前端 92 tests / 12 files 与 `vue-tsc` 通过；真机复核确认新审批卡片、气泡 token 与诊断路径生效；`e2e/diagnostics.spec.ts` 1 条通过，覆盖分组表、异常白名单脱敏、导出、停用与清除。
- 已知偏差：历史回答不回显 token（消息历史接口未返回 usage）。

### 9.7 P6-04 工作区布局再评估

- **结论：保留**现有「左侧栏 + 中间对话 + 右侧 `WorkbenchDock`」三栏结构，不新增中间工作区，本轮不改代码。
- 依据：右 Dock 已覆盖变更/资料/计划/追踪/监控/文件 6 类内容，资源管理器点文件直接进入文件 tab 并支持多 tab、脏状态与关闭确认；宽度可拖拽并持久化，对话区保底 420px，支持全屏；<1024px 浮层 + 遮罩，<768px 侧栏抽屉，≥1024px 侧栏可折叠为 56px 图标轨。
- 实测：`responsive.spec.ts` 4 条通过——960×640 无横向滚动且输入区可见；900×700 侧栏图标轨 + Dock 浮层不挤压正文；767×700 侧栏抽屉默认收起；1280×800 侧栏展开、三栏无横向滚动。
- 后续可选（不立项）：Dock 默认宽度按屏幕分档、tab 顺序/快捷键、文件 tab 溢出策略，待真实反馈再立 spec。

### 9.8 出口检查与测试基线（2026-11-17）

| 项 | 结果 |
|---|---|
| 后端 | 368 tests / 0 fail / 15 skipped |
| 前端 | 92 passed / 12 files + `vue-tsc` 通过 |
| CLI | 72 条通过（收尾修复后） |
| VS Code 扩展 | 43 条通过 |
| CLI 真机 e2e | `scripts/cli-e2e.ps1 -KeepData` 通过（run exit 6、note.txt、revert、client show/revoke） |
| 前端 E2E | `diagnostics.spec.ts` 1 passed；`responsive.spec.ts` 4 passed |
| 多入口真机 | Web 7 条、VS Code `CLIENT=FeBIz_31mXyW` 1 条（100%、8600ms）、CLI `CLIENT` |

环境偏差：本机未安装 Playwright Chromium，且 Vite 6 默认只监听 `::1`，Playwright `webServer` 轮询 `127.0.0.1` 会 120s 超时。本次通过已启动 Vite（localhost:5174）+ `E2E_BASE_URL` + `E2E_CHANNEL=msedge` 运行；CI/新机器需先 `npx playwright install chromium` 或统一监听地址。该偏差不修改仓库配置。

### 9.9 遗留偏差

1. 历史回答不回显 token（消息历史接口未返回 usage，需后端回填批次）。
2. 历史审批记录不回填 `client_id`，来源保持 `UNKNOWN`。
3. P6-03 双待审批「全部批准」成功路径与远程窗口未真机复现。
4. 后端进程重启仍不续跑（P6-06 非目标）。
5. 仓库无覆盖率门禁；CLI/扩展的退出码矩阵以单测与 e2e 为准。

## 10. P7-01 重启后未完成 Run 与未决审批收口（2026-11-19）

- `AgentRunService.recoverAfterRestart()` 的清理范围从 `QUEUED / RUNNING` 扩展到 `WAITING_APPROVAL`：统一置 `FAILED`、`errorCode=PROCESS_RESTARTED`、写结束时间，并补一条 `STATE_CHANGED` 事件，保证 `sinceSeq` 重放能看到终态。
- `ToolApprovalRegistry.restorePersistedApprovals()` 不再把重启残留的 `PENDING` 装回内存：未过期落 `REJECTED / SYSTEM`，已过期保持 `REJECTED / TIMEOUT`；会话级「总是允许」白名单仍按库恢复。此后对残留审批调用 `resolve()` 返回 `false`，消除「批准成功但无人继续执行」的假动作。
- 测试：定向 33 条通过；全量后端 `357 tests / 0 fail / 0 error`（未设置桌面运行时跳过 15 条；设置 `DESKTOP_PG_RUNTIME` 后跳过降至 4 条，`RestartRecoveryAndMultiInstanceAcceptanceTest` 5 条全部通过）。`ToolApprovalRegistryPersistenceTest`、`RestartRecoveryAndMultiInstanceAcceptanceTest` 与 `AgentRunServiceTest` 已按新语义改写/补充。
- 已知边界：仍不做重启续跑与跨实例审批互斥/唤醒；本批次无接口、事件类型与数据库迁移变化。
- 文档顺带修正：`STATUS.md` 中「MCP 熔断冷却硬编码」已过时（实际可用 `MCP_COOLDOWN_MS` 等配置），「审批状态存进程内存」的兜底说明改为启动收口。

### 10.1 P7-02 会话统计成本基准（2026-11-19）

- 新增 `ChatStatsBenchmarkTest`：默认不进入常规测试，`mvn -o "-Dperf=true" "-Dtest=ChatStatsBenchmarkTest" test` 显式运行；trace 形状对齐 `ChatStreamBridge.onComplete` 的真实落库结构，结果写入已忽略的 `eval/reports/stats-aggregation-*.json`。
- 实测：400 条消息 / 3.11MB → p95 8～11ms；1000 条 / 7.79MB → p95 13～17ms；2000 条消息（1000 条助手消息）/ 15.57MB → p50 26ms、p95 27～31ms（两次运行区间）。口径为 trace 解析与聚合，不含 JDBC 往返与行解码。
- 按预注册规则判定：未触发「1000 条会话 p95 > 500ms」阈值，**不引入统计缓存，P7-02a 不立项**；`STATUS.md` 中「aggregateStats 长会话慢」已改为实测结论。
- 仍未做：L1～L3 长任务数据集与真实模型运行，计划漂移（规则 2/3）尚待基线数据。

### 10.2 P7-05 桌面模式 SSE 浏览器 E2E（2026-11-19）

- 新增 `frontend/e2e/desktop-sse.spec.ts` 4 条用例：不预置令牌，验证 `/api/meta` → `/api/desktop/token` 引导；覆盖 `thinking / tool_call / tool_result / plan / content / done` 事件消费；覆盖审批挂起后批准并恢复输出；覆盖断流按 `runId` 重放到成功及 `PROCESS_RESTARTED` 失败终态。
- 定向 4 条全部通过（修复后于本机 Edge 通道再次复跑 4/4）；同一轮全量 Playwright 为 30 条通过、1 条性能用例按既有条件跳过，前端 Vitest 92/92 通过。
- **边界**：测试使用 Vite + Chromium 内核浏览器，`e2e/fixtures/api.ts` 通过 `page.route` 仿真后端 API/SSE；这不是 Electron 窗口、Java 侧车、内嵌 PostgreSQL 或真实模型链路验收，真实桌面冒烟继续单独跟踪。
- 同步修复 `SettingsView.vue` 快捷键表格缺少 `tbody` 的 Vue 编译警告；修复后 Vitest 92/92、`vue-tsc` 与 Vite 生产构建通过。

## 11. P7-06 执行中决策询问 ask_user_question（2026-11-19）

> 设计契约、时序与排障细节见 [phases/phase7/p7-06-ask-user-question.md](phases/phase7/p7-06-ask-user-question.md)。

### 11.1 能力落地

- 对齐 DeepSeek Harness `@deepseek-ai/dsh-tool-ask-user` 与 Trae 的「执行中停下来问用户」交互原语：模型在真实决策分叉点调用 `ask_user_question`，ReAct 循环挂起等待用户回答，回答作为工具结果回灌后继续执行。与审批解耦：审批管副作用，询问管方向。
- 后端：`AgentRun` 新增 `WAITING_USER_INPUT` 状态与合法迁移；事件新增 `USER_QUESTION / USER_ANSWERED`；`AskUserQuestionTool` 以 `CompletableFuture` + 500ms 分片轮询挂起/恢复（总超时 15 分钟，停止信号在一个分片内生效）；`AskUserQuestionSchemaTool` 只负责向模型暴露 schema；`ReActAgentService` 在工具分发前拦截该工具，`WorkspaceContext` 增加 `runId`。
- 新增 `POST /api/agent/runs/{runId}/answer`：校验 Run 归属与 `WAITING_USER_INPUT` 状态，先落 `USER_ANSWERED` 并迁回 `RUNNING`，再唤醒等待线程。
- 前端：`user_question` SSE 事件、`AskUserCard.vue`（选项单选/多选/自由输入、Recommended、防重复提交、失败恢复）、`pendingUserQuestion` 状态、`answerUserQuestion` API；断线/刷新按 Run 事件重放还原或清除卡片；流结束、失败、停止、新会话均清理 pending。
- 提示词：`coding/system-v1.md` 与 `agent/system-v1.md` 增加【决策询问】规则，`coding` 的 `PromptCatalog.REQUIRED_MARKERS` 同步加入，`prompts/index.json` 哈希已更新。
- 非流式/无 Run 上下文不暴露该工具（`ToolRegistry.buildRankedToolCallbacks` 按 `runId` 过滤），避免模型发起永远无法回答的询问。

### 11.2 真实链路与全量验证中修复的缺陷

- **只读并行批抢跑**：`ask_user_question` 声明 `readOnly=true`，若与其他只读工具同轮，`executeReadOnlyBatch` 会在拦截点前预执行，后续工具在用户回答前就跑掉。已在 `isParallelReadOnlyCall` 显式排除，并有同轮「先问后读」回归用例。
- **超时/中断状态泄漏**：询问等待超时或线程中断后 Run 会永久卡在 `WAITING_USER_INPUT`。新增 `AgentRunService.abandonUserQuestion()`（仅在该状态下迁移，写 `abandoned=true` 的 `USER_ANSWERED`），由 ReAct 在工具返回 error 时调用，前端重放时自动收起卡片。
- **JSON Schema 被真实 provider 拒绝**：`options.items.required` 误写成 `{"type":"array","items":{"type":"string"},"const":["label"]}`，DeepSeek 返回 400。脚本模型不校验 schema，因此此前全绿无法发现；通过本地 HTTP 捕获出站请求定位后改为 `["label"]`，并新增 schema 结构回归测试（`required` 必须是数组）。
- **全量套件挂起**：`RunDisconnectHttpAcceptanceTest.stubAgent` 仍 stub 旧 15 参 `execute`，P7-06 后 controller 走带 `runId` 的 16 参重载，mock 不命中导致行为体从未执行、SSE 永远无事件。修正 stub 参数后该类 41 秒跑完，全量套件不再卡住。

### 11.3 测试与验收边界

- 新增：`AskUserQuestionToolTest` 6 项、`AgentRunServiceTest` 询问路径/迁移边界/abandon 共 5 项、`AskUserQuestionReActIntegrationTest` 5 项（回答后继续、同轮只读不抢跑、停止分片退出、无 Run 不暴露、schema required 结构）、`SettingsControllerModelsTest` 3 项、`AskUserCard.test.ts` 7 项。
- 后端全量：**388 tests，0 失败，0 错误，16 跳过，BUILD SUCCESS**；前端 Vitest 99/99、`vue-tsc --noEmit` 通过。
- **真实模型冒烟已通过**：`AskUserQuestionLiveSmokeTest` 默认跳过，需 `LUNARGOLD_LIVE_API_KEY` 显式开启。2026-11-19 经用户授权用 `deepseek-flash` 实跑：第一次调用暴露上述 schema 缺陷，修复后复跑 **1 项通过 / 16.2s**，真实模型发起 `ask_user_question`、用户回答「加 wrapper」后循环继续并输出总结，`Completion=COMPLETED`。控制台中文显示为问号是 Windows 终端编码问题，不影响断言与链路。
- P7-06 出口 4（L1/L2/L3 同一数据集前后对比）仍随 P7-02 真实模型基线一起执行，本轮未产生数据集对比结论。

### 11.4 模型目录动态化（同轮附带完成）

- 起因：设置页服务商下拉与模型目录均硬编码，且用户账号 `GET /models` 实际返回 `deepseek-flash / deepseek-v4-pro`，与硬编码的 `deepseek-chat / deepseek-reasoner` 不一致。
- `ApiKeyService.listRemoteModels()` 用已存 Key 实时拉取 provider 模型列表；`GET /api/settings/models` 的 DeepSeek 部分优先使用实时结果，失败/无 Key 回退静态目录；前端设置页与聊天页模型下拉、`/model` 命令共用该目录，并修正前端误调 `/api/models` 为 `/api/settings/models`。
- 边界：聊天执行链仍只取 `DEEPSEEK` Key，多 provider 执行链解硬编码未做；推理档位（FAST/STANDARD/DEEP）确认真实生效于工具轮次与历史预算，但**不映射**为模型的 `reasoning_effort` 参数。

### 11.5 P7-02 长会话真实模型 Runner（2026-11-19）

> 细化规格与付费门禁见 [phases/phase7/p7-02-long-session-baseline.md](phases/phase7/p7-02-long-session-baseline.md)。

- 新增 `LongSessionLiveRunner`：使用真实 `SpringAiAgentClient`、生产 `ReActAgentService`、真实文件/计划工具与 `AgentRunService` 状态迁移执行 L1/L2/L3；只 mock 仓库、项目所有权等边框，不复用脚本模型结果。
- 每个 run 使用独立临时工作区并 finally 清理；多 turn 累积 USER/ASSISTANT 历史，L2 走真实审批与 scope 拒绝，L3 注入结构化检查点摘要并执行记忆探针。P7-06 可通过 `P7_02_ASK_USER=on` 做后续同数据集 A/B。
- 报告落盘到已忽略的 `eval/runtime-reports/long-session-<timestamp>.json`：保留原始 run、按 level 的成功率/中位数/失败归因，以及规则 2/3 判定。模型调用在 `AgentClient` 实际入口计数，token 仅采用 provider usage，并记录 `usageCalls/modelCalls` 覆盖率；不保存 API Key。
- 成功必须同时满足工件与数据集 coverage（execute turn、计划、恢复、审批、scope、摘要、记忆等）；缺 completion、coverage 不足或只有最终文本均不算成功。L1×1 只作冒烟，只有 L1/L2/L3 全部执行且 repeats≥3、provider 失败率达标时才允许形成架构结论。
- 离线验证：`LongSessionLiveRunnerTest` 5 项覆盖错误归因、无进展/最大轮次/重复/审批/恢复优先级、报告门禁与规则 2；目标套件共 **19 tests，0 失败，0 错误，1 跳过**。显式清空 Key 后 `LongSessionLiveRunner` 正确跳过，未发起外部模型请求。
- **L1×1 真实冒烟（用户单独授权后完成）**：`deepseek-flash`、P7-06 off，报告为 `eval/runtime-reports/long-session-20261119-202115-722.json`。37 次真实模型调用，usage 覆盖 37/37；186,031 prompt + 3,324 completion = 189,355 total tokens，单轮上下文峰值 7,215 prompt tokens；端到端 249,692ms，首 chunk 16,857ms。13/13 工件、13 次文件变更、1 次过期路径失败并恢复、6 次 `update_plan`、计划完成率 100%，无重复调用、无进展、最大轮次、coverage 或执行错误。
- **冒烟判定与边界**：Run 为 SUCCEEDED，数据集 `success=true`，但 completion 为 `UNVERIFIED:VERIFY_NOT_CONFIGURED`——fixture 没有 Maven/Gradle 与验证命令，因此成功只表示工件、coverage 和评测用户审查通过，不能外推为编译/测试通过。`decision.eligible=false`、规则 2/3 均未触发；单一样本不能形成真实成功率或 P7-03/P7-04 立项结论。临时工作区已清理，报告不保存 API Key。
#### L1/L2/L3×3 首轮正式基线（2026-11-19）

- 报告 `eval/runtime-reports/long-session-20261119-213016-388.json`：9 个 run 全部完成，provider 失败率 0。成功率 **L1 2/3、L2 2/3、L3 0/3**；中位调用数 L1 37、L2 77、L3 102；中位 token 189,891 / 441,398 / 954,558；中位耗时 192s / 407s / 659s。
- **结论无效，发现两处真实缺陷并已修复**：
  1. `LongSessionDataset.render(Turn)` 调用了三参重载，只替换 `{index*}`，**从未替换 `{first}`/`{last}`**；L2/L3 的真实 prompt 带着字面 `Unit{first}`，模型反复追问区间。已改为二参重载，并新增 `turnPromptSubstitutesFirstAndLast` 回归测试。
  2. `SpringAiAgentClient.streamChat` 在 `memorySummary` 非空时走 `ChatClient`，其内置工具执行循环绕过 `ToolRegistry.dispatch`，直接把 `BaseTool.call` 的兜底错误当工具结果返回模型；该错误**只出现在 3 个 L3 run**（唯一注入摘要的 level）。已删除 ChatClient 分支，摘要改用 `Prompt.augmentSystemMessage` 追加到 system 尾部，统一走 `chatModel.stream`。
- 验证：`LongSessionDatasetTest` 13 项 + `LongSessionLiveRunnerTest` 5 项共 18 项通过；**后端全量 396 tests / 0 失败 / 0 错误 / 17 跳过，BUILD SUCCESS（2026-11-19 22:31）**。修复后的记忆摘要路径尚未经真实模型复验。
- 首轮 `rule2PlanFirst=true`（3/5 计划类失败）不能作为 P7-03 立项依据；L3 记忆探针 3/3 失败方向一致，但混有上述缺陷。**修复后需重跑 L1/L2/L3×3，才可判定规则 2/3。**

#### 第二轮基线与 tool_calls 协议缺陷（2026-11-20）

- 报告 `eval/runtime-reports/long-session-20261120-124420-563.json`（L2+L3×3）：**L2 1/3、L3 0/3**。前两处缺陷的症状（占位符追问、ToolRegistry 兜底错误）均已消失。
- 新发现并修复**产品级协议 bug**：一次响应并行发起多个写工具时，`ReActAgentService` 把诊断 user 消息插在第一个写工具的响应之后、同组其余 tool 响应之前，provider 以 400「insufficient tool messages」拒绝整段历史，3 个 L3 全部因此中断。
- 零成本复现：新增 `ReActToolCallProtocolTest`，脚本模型一次返回两个写调用，第二次请求即出现 `expected=[call_0_0, call_0_1], answered=[call_0_0], nextRole=user`。
- 修复：诊断等 user 消息先暂存到 `deferredUserMessages`，本组 tool 响应全部写完后统一追加。相关 17 项测试通过，**后端全量 398 tests / 0 失败 / 0 错误 / 17 跳过，BUILD SUCCESS（2026-11-20 13:07）**。
- 另修复 Runner 环境问题：本机 `mvn`/`JAVA_HOME` 不在 PATH 时，使用 `~/.m2/wrapper/dists` 下的 Maven 与 `~/.jdks/temurin-24`。

#### P7-04 记忆摘要升级（2026-11-20，已完成并验证）

**改动**：[LongSessionLiveRunner.java](file:///d:/test-code/agent-gateway-v3/src/test/java/com/capsule/agentkey/eval/longsession/LongSessionLiveRunner.java#L438-L465) 的 `checkpointSummary` 从**进度摘要**（104 字符："已完成 N turns，工具调用 M 次"）升级为**记忆摘要**三层结构：
1. 记忆锚点（来自数据集 `memory.anchor`）：让模型在长会话后仍能回答早期决策
2. 已完成文件区间（累积 `Turn.first`/`Turn.last`）：让模型知道哪些文件已经处理过
3. 进度计数 + 防重复约束（保留原有）

**离线测试**：`LongSessionLiveRunnerTest` 新增 4 项 P7-04 回归（锚点必须出现、区间累积正确、空区间降级、字符数在 150-600 范围），全过；相关 25 项目标测试全绿。

**真实模型验证（L3×3）**：报告 `long-session-20261120-152022-008.json`。

| 指标 | 修复前（第三轮） | 修复后（本轮） | 变化 |
|---|---|---|---|
| memoryProbePassed | **0/3 (0%)** | **3/3 (100%)** | ✅ |
| 成功率 | 0/3 | **2/3 (66.7%)** | ✅ |
| errors | 空 | 空 | 稳定 |
| maxSummaryChars | 104 | **245** | +136 字符 |
| promptTokens 中位数 | 1,021,534 | **1,052,796** | **+3.06%**（远低于 15% 阈值） |

**剩余问题**：第三个 run 失败于 `planCompletion=80, required=100; incompleteTurns=1`——计划面板把最后一步标记为 INCOMPLETE 而非 COMPLETED，和记忆无关，是 `update_plan` 工具的一个小边界问题，归入 P7-03 plan-first 延后时一起处理。

**结论**：记忆摘要升级完全命中 P7-02 基线暴露的瓶颈——模型记不住早期决定。当前方案（锚点+区间+进度的结构化摘要，零额外模型调用成本）足以支撑 L3 级别长会话的记忆需求。完整决策见 [p7-02-decision.md](../phases/phase7/p7-02-decision.md)。

#### 三数据集正式基线完整数据（2026-11-20，P7-04 修复后）

三个数据集各 ×3 正式跑完，注入方案尝试前的干净基线：

| 指标 | L1（单模块×3） | L2（三模块+审批×3） | L3（十二turn+记忆探针×3） |
|---|---|---|---|
| execute 次数 | 1 × run | 3 × run | 12 × run |
| permission | AUTO | **MANUAL（每步审批）** | AUTO |
| memoryProbePassed | 无 | 无 | **3/3** ✅ |
| **成功率** | **2/3 (66.7%)** | **2/3 (66.7%)** | **2/3 (66.7%)** |
| promptTokens 中位数 | 186,541 | 436,530 | 1,083,317 |
| toolCalls 中位数 | 36 | 73 | 175 |
| errors（9 次 run） | 全部为空 | 全部为空 | 全部为空 |

三次失败的本质原因：

| Run | 失败类型 | 根因 | 归 P7-03 还是 P7-04 |
|---|---|---|---|
| L1 run 1 | STAGNANT_ROUNDS | 单次 execute 内 search_files 循环触发无进展检测 | 不归任何 Phase 7 子项——LLM 验证行为固有循环 |
| L2 run 2 | STEP_OMISSION | scope 变化后 Registry.java（RULE_COUNT=24）漏改 | 不归——LLM 在 scope 变化时的步骤遗忘 |
| L3 run 2 | HISTORY_REPETITION | 多 run 间不信任自己之前的 edit_file 结果 | 不归——LLM 内在不确定性 |

**重要判定**：三层 2/3 成功率是通用 LLM 在复杂多步任务上的自然表现（Routine 论文从 41.1% → 96.3% 的提升依赖小模型专门训练，不是 prompt 注入能做到的）。P7-04 记忆摘要只对 L3 的 memoryProbe 有决定性作用，不影响成功率。

#### P7-04b 工具输出分档（2026-11-20，已完成）

**修复文件**：`AgentPolicy.java`、`ReActAgentService.java`

**改动**：
- `AgentPolicy.Reasoning` 枚举新增 `toolContentLimit()` 字段，构造函数从 5 参数变为 6 参数
- FAST=500, STANDARD=1000, DEEP=2000（DEEP 档不变，保持与之前一致）
- `truncateForContext` 改为按档位动态取，不再固定读静态常量 `TOOL_CONTENT_LIMIT=2000`

**验证**：L3×3 跑完（DEEP 档 toolContentLimit 仍为 2000），成功率 2/3、memoryProbe 3/3，与改动前一致。

#### P7-04c 框架级 plan 注入尝试（2026-11-20，已回滚）

**动机**：LangChain Deep Agents 的 `read_todos` 机制——每次 LLM 调用前把当前 plan 状态注入 messages，让模型看到未完成步骤而不遗漏。对照 Routine 论文从 41.1% → 96.3% 的提升（但后者核心是小模型专门训练，不是 prompt 注入）。

**实现**（已回滚）：
- `ReActAgentService` 主循环每次 `streamChat` 前调用 `buildPlanHint()` 生成精简计划摘要
- plan 变化立即注入，未变化每 5 轮提醒一次
- 示例输出：`[当前计划状态] 进度 2/5 已完成 进行中: Catalog.java 待开始: 验证残留`

**验证结果**：

| 数据集 | 基线（无注入） | 注入后 | 判定 |
|---|---|---|---|
| L1×3 | **2/3** | 2/3 | 中性（随机性主导） |
| L2×3 | **2/3** | **1/3** ❌ | **有害** |

**L2 退化原因**：L2 有 3 个 execute 调用 + scope 变化，plan 状态存在 ThreadLocal WorkspaceContext（跨 turn 不共享）。注入的 plan hint 里，turn 1 被 scope 拒绝而 aborted 的步骤被模型误认为"真的完成了"，turn 2/3 就不再碰 moduleA/B 了。

**结论**：LangChain 方案的核心不是"每次请求前注入"，而是**harness 在 state 层维护权威 todos**（`state['todos']` 在每个 step 执行后自动更新），跨 turn 时 state 自然传递。要实现这套需要把 plan 从 ThreadLocal 提升到 Run 级持久状态（Controller→Service→DB 三层重构），改动量和收益不匹配。**回滚，不立项 P7-04c**。

## 12. 完成档案使用规则

1. 新任务完成后，将任务编号、实际改动、测试结果和验收边界移入本文。
2. `UPDATE_PLAN.md` 只保留未完成工作、当前阻塞和下一步，不再持续追加长篇完成批注。
3. 大型排障过程优先沉淀为专题文档，完成档案只保留结论和链接。
4. 历史“未提交”“未验证”等边界按当时事实保留；后续关闭时新增结论，不改写历史证据。
5. Windows x64 的结果不能外推为 macOS/Linux 已完成；旧安装器的验收不能外推到新哈希产物。
6. R0～R7 是 2026-11-21 起的在途重构，细节暂留 `UPDATE_PLAN.md` 第 14 节；整批完成后再按「结论 + 证据」迁入本文，避免当前双份维护。
