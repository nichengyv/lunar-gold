# 07 桌面端与部署形态（部分完成）

> 更新日期：2026-11-13
> 状态：Windows 桌面端、内嵌运行时、安装/更新/诊断已落地；macOS/Linux 暂缓
> 关联：[总架构](README.md)、[安全](09-security.md)、[数据持久化](08-data-persistence.md)

---

## 1. 进程模型

```text
Electron 主进程 (desktop/main.js)
  ├── 渲染进程（Vue SPA）
  ├── Java 侧车 (sidecar.js → java -jar agent-gateway.jar --spring.profiles.active=desktop)
  │     └── 内嵌 PostgreSQL + pgvector 子进程
  └── 更新器 / 诊断模块
```

| 文件 | 职责 |
|---|---|
| `desktop/main.js` | 单实例锁、窗口、启动页、侧车编排、崩溃标记、退出清理 |
| `desktop/sidecar.js` | 选空闲端口、启动 jar、健康检查、进程树与 PG 清理 |
| `desktop/updater.js` | 检查、下载、校验、备份、静默安装、失败回滚 |
| `desktop/diagnostics.js` | 白名单诊断报告、脱敏、ZIP 导出 |
| `desktop/package.json` | electron-builder 配置、发布源、图标与资源映射 |

---

## 2. 启动时序

```text
单实例检查
  → 初始化数据目录、日志、诊断会话、定位 jar
  → 创建窗口并加载 loading 页
  → sidecar 选择空闲端口并启动 Java
  → 轮询 /actuator/health 直到 UP
  → 加载主 UI
  → 首启成功标记（用于更新回滚判定）
```

异常处理：

- 侧车启动失败 → 错误卡片（错误码 + 可读原因 + 重试/导出诊断/退出）。
- 侧车异常退出 → 自动重启，并清理游离的 `postmaster.pid`。
- 窗口关闭/退出 → 先停止侧车，再退出，避免残留 Java/PostgreSQL 进程。

---

## 3. 桌面 profile

`application-desktop.yml` 与云端的主要差异：

| 项 | 桌面端 |
|---|---|
| 数据库 | 内嵌 PostgreSQL + pgvector，数据目录在用户目录 |
| Redis | 排除自动配置 |
| 邮件 | 关闭，注册/验证码入口关闭 |
| 账号 | 本地单用户，本机随机令牌 |
| 绑定地址 | `127.0.0.1` |
| 数据目录 | 删除即重置本地数据 |

`DesktopDataSourceConfig` 在启动时按分配端口创建数据源，Flyway 迁移来自 `classpath:db/migration`。

---

## 4. 打包与运行时

统一构建脚本 `scripts/build-desktop-installer.ps1` 负责：

1. 前端构建并打入后端 jar（`with-frontend`）；
2. 打入 PG + pgvector 运行时（`with-pg-runtime`）；
3. 打包裁剪后的私有 JRE（jlink，19 个模块）；
4. electron-builder 产出 unpacked 与 NSIS 安装器；
5. 校验关键文件与 jar 哈希，生成合规产物。

体积主要来自 Electron 运行时、含 PG 运行时的后端 jar、私有 JRE；桌面壳代码只有十几 KB。

---

## 5. 更新与回滚

`updater.js` 的策略是“检查并提示”，不强制更新：

```text
检查新版本 → 用户确认 → 下载（差分）→ sha512 校验
  → 数据目录备份 → 写升级标记 → 静默安装 → 重启
  → 首启成功清理标记；失败则恢复备份并装回旧版本
```

已验证场景：正常升级、哈希错误、签名错误、磁盘不足、升级后启动失败回滚、退出后无残留进程。

---

## 6. 诊断

- 报告只保留白名单字段：版本、OS、错误码、必要日志尾部。
- 绝对路径脱敏为 `<PATH>`，不包含对话、知识库、数据库、模型 Key、本机令牌、`aes.key`。
- 默认只存本地，无远程上传；导出由用户选择位置。
- 已知边界：ZIP 导出的系统原生保存对话框仍需人工点一次确认。

---

## 7. 能力门控与双形态

`DeploymentMode` 区分 `CLOUD` / `DESKTOP`；`Capability` 声明能力集合；`CapabilityService.has/require` 在服务端兜底，`/api/meta` 让前端按能力显隐入口。

设计原则：**同一套代码、同一套前端，通过能力门控表达差异，不分叉实现**。

---

## 8. 现状边界

- 仅 Windows：macOS Seatbelt / Linux Landlock、对应安装包与签名均暂缓。
- 安装包未签名（SignPath 申请被拒），SmartScreen 会提示“未知发布者”，README 提供 SHA-256 校验方法。
- 干净机器完整任务验收、诊断 ZIP 人工复核仍未关闭。
- 双实例审批互斥未实现（桌面默认单实例，影响有限）。

---

## 9. 面试要点

1. **为什么用侧车而不是把后端编进 Electron？** Java 生态与进程隔离；侧车崩溃不拖垮壳，便于独立重启与诊断。
2. **端口冲突怎么办？** 启动前选择本地空闲端口，健康检查确认后再加载 UI。
3. **更新失败如何保证不砖？** 备份 + 升级标记 + 首启成功判定 + 自动回滚。
4. **桌面与云端如何复用？** 同一后端与前端，按部署模式与能力集合门控。
