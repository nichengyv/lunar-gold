# Lunar Gold（金月）

免费、开源的本地 AI 编程助手：命令需审批，改动可按轮撤回。

Lunar Gold 是运行在 Windows 上的桌面 AI 智能体。它可以读写项目文件、执行命令，并集成 Git、MCP 工具与 LSP 诊断。所有破坏性操作都需要你显式审批，每次改动都可以按轮撤回。Web 版共用同一套代码；桌面版无需账号，使用你自己的模型 Key。

> English version: [README.en.md](README.en.md)

## 特点

- **本地优先** — 自带内嵌数据库（PostgreSQL + pgvector）和私有 Java 运行时。项目、对话、知识库和模型 Key 都留在你的机器上；没有遥测，也不会上传崩溃报告。
- **可控** — 命令执行必须审批；文件删除与回滚都是显式、可撤销的。
- **无锁定** — Apache-2.0 许可，无订阅，桌面版无需账号。

## 下载与安装

从 [GitHub Releases](https://github.com/nichengyv/lunar-gold/releases) 下载 `LunarGold-Setup-x.y.z.exe` 和 `SHA256SUMS.txt`。

系统要求：Windows 10/11 x64。

### 1. 校验下载

```powershell
Get-FileHash .\LunarGold-Setup-x.y.z.exe -Algorithm SHA256
```

输出的哈希必须与 `SHA256SUMS.txt` 和 Release 说明中的值一致。不一致时请删除文件并重新下载。

### 2. 运行安装器

安装器默认按当前用户安装（无需管理员权限），安装时可自行选择目录。

**当前安装包未签名。** Windows SmartScreen 可能提示「未知发布者」；请先完成上面的 SHA-256 校验，再选择「更多信息 → 仍要运行」。我们申请过 [SignPath Foundation](https://signpath.org/foundation) 的免费开源签名计划，但当前因项目公开可见度不足被拒；后续会随项目成长重新申请。

### 3. 首次启动

首次启动会初始化内嵌数据库，可能需要几秒钟，无需账号。应用数据保存在 `%USERPROFILE%\.lunargold\desktop`（可用 `DESKTOP_DATA_DIR` 环境变量覆盖）。

应用会在启动约 8 秒后检查 GitHub Releases 是否有新版本，下载前一定会征求你的同意。

### 卸载

在 Windows 设置 → 应用 中卸载。卸载会删除程序文件，包括私有运行时；**数据目录 `%USERPROFILE%\.lunargold` 会保留**（数据库、加密密钥、local-token、日志、诊断报告、升级备份、对话/知识库附件），需要彻底清除时请手动删除。

## 隐私与网络行为

桌面版没有遥测、统计分析或崩溃上传。只有以下情况会访问网络：

- 向 GitHub Releases 检查更新；
- 调用你配置的模型 / 向量服务商（API Key 本地加密保存，只发送给该服务商）；
- 你主动调用内置联网工具（网页搜索、天气、网页抓取）；
- 你添加的 MCP 服务自行联网。

本地诊断报告只写入磁盘；导出诊断包时会让你选择保存位置，不会自动上传。详见 [PRIVACY.md](PRIVACY.md)（[English](PRIVACY.en.md)）。

## 故障排查

如果应用无法启动，或窗口显示了诊断卡片：

1. **在卡片上导出诊断包**（或复现问题后从下一次卡片导出）。ZIP 只含白名单字段的 `report.json` 和脱敏后的日志尾部。
2. 直接查看日志：
   - 后端：`%USERPROFILE%\.lunargold\desktop\sidecar.log`
   - 更新器：`%USERPROFILE%\.lunargold\updater.log`
   - 诊断报告：`%USERPROFILE%\.lunargold\desktop\diagnostics\reports\`
3. 常见情况：
   - **提示「未知发布者」/ SmartScreen 拦截** — 当前未签名版本的正常现象；校验 SHA-256 后允许运行。
   - **窗口立即关闭或一直停在加载页** — 后端启动失败；查看 `sidecar.log` 末尾，并使用卡片上的「重试」。
   - **端口不可用** — 重试时应用会重新选择本地端口；确认安全软件没有拦截 `127.0.0.1`。
   - **数据库初始化失败** — 检查数据目录所在磁盘空间和写入权限后重试。
   - **安装器被杀毒软件拦截** — 先校验哈希，再从隔离区恢复，或为安装器添加排除项。
   - **重置应用** — 退出后删除 `%USERPROFILE%\.lunargold`（会删除本地数据、密钥、日志、诊断和备份）。对话与知识库附件位于安装目录的 `uploads` 文件夹，需要单独删除。

## 合规文件

- [LICENSE](LICENSE) — Apache-2.0
- [PRIVACY.md](PRIVACY.md) — 隐私政策、网络边界与数据保留（[English](PRIVACY.en.md)）
- [TERMS.md](TERMS.md) — 服务条款（[English](TERMS.en.md)）
- [NOTICE](NOTICE) — 第三方组件与许可证（[English](NOTICE.en)）
- [SBOM.json](SBOM.json) — CycloneDX 软件物料清单（Java 后端 243 个组件）
- 安装包 `resources/legal` 与每个 Release 还提供 `SBOM.frontend.json`（前端生产依赖）、`SBOM.desktop.json`（桌面壳生产依赖）、`SBOM.runtime.json`（Electron、裁剪 JRE、PostgreSQL、pgvector 实际运行时）和 `THIRD-PARTY-LICENSES.txt`（完整第三方许可证与版权文本）。

每个 GitHub Release 同时提供安装器、`latest.yml`、用于差分更新的 blockmap、`SHA256SUMS.txt` 以及上述合规文件。

## 从源码构建

环境要求：Windows x64、JDK 21、Maven、Node.js 22。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-desktop-installer.ps1
```

脚本会构建前端、运行后端测试、裁剪私有 JRE、打包 Electron 外壳，并在 `release/installer/` 下生成 NSIS 安装器。PostgreSQL 与 pgvector 运行时由 `scripts/build-pg-runtime.ps1` 和 `scripts/build-desktop-runtime.ps1` 构建。

依赖变化后重新生成合规产物（Java SBOM、前端/桌面/运行时 SBOM、第三方许可合集）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-compliance.ps1
```

（CycloneDX 会一并解析 test 作用域，因此 `SBOM.json` 中有少量测试专用构件；它们不会随安装包发布。）

## 文档

- [文档索引与书写规则](docs/README.md)
- [更新规划与路线图](docs/UPDATE_PLAN.md)
- [已完成工作归档](docs/COMPLETED_WORK.md)
- [Windows 打包排障](docs/troubleshooting/windows-nsis.md)

## 许可证

[Apache-2.0](LICENSE) — Copyright 2026 杨俊懿 (Junyi Yang)
