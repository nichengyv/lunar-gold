# AgentKey 已完成任务与验收档案

> 整理日期：2026-11-09
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

完整根因、命令、证据和防回归清单见 [WINDOWS_NSIS_TROUBLESHOOTING.md](WINDOWS_NSIS_TROUBLESHOOTING.md)。

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

- 图标定为**单色黑白**（白色圆角方块 + 黑色月亮线条 + 三颗星），与同行业桌面智能体风格一致；深底金色版本作为备选保留，未打包。
- 源图经白底转透明、亮度归一化处理，输出 `desktop/app-icon/icon.png`（1024×1024，圆角外透明）与 `desktop/app-icon/icon.ico`（16/24/32/48/64/128/256 七档）。
- `desktop/package.json` 增加 `build.win.icon = app-icon/icon.ico`；图标刻意放在 `desktop/app-icon/` 而非 `desktop/build/`，因为根 `.gitignore` 的 `build/` 规则会把后者排除在仓库之外。
- 重新打包并回归：安装器 `release/installer/LunarGold-Setup-0.1.0.exe`，**371,353,795 字节**，SHA-256 `6FE3C93D39656A578BCBC4D8BFBB230AF24BA16B884E04BD845B4FE65CECC220`；**22 项静默回归全部通过**；`ExtractAssociatedIcon` 从包内 `lunar-gold.exe` 取出的图标与设计一致。

仍未完成：SignPath 申请与受信签名构建、GitHub Release、干净机器验收、macOS/Linux 安装包。以上均由项目所有者操作或在 P3-04 后续跟踪。

---

## 6. 完成档案使用规则

1. 新任务完成后，将任务编号、实际改动、测试结果和验收边界移入本文。
2. `UPDATE_PLAN.md` 只保留未完成工作、当前阻塞和下一步，不再持续追加长篇完成批注。
3. 大型排障过程优先沉淀为专题文档，完成档案只保留结论和链接。
4. 历史“未提交”“未验证”等边界按当时事实保留；后续关闭时新增结论，不改写历史证据。
5. Windows x64 的结果不能外推为 macOS/Linux 已完成；旧安装器的验收不能外推到新哈希产物。
