# P3-05 Windows 自动更新与失败回滚

日期：2026-11-10
范围：桌面端自动更新（仅 Windows x64），不改后端、不改前端业务代码。

## 改动文件

| 文件 | 说明 |
| --- | --- |
| `desktop/updater.js`（新增） | 检查更新、下载、校验、升级前备份、安装、失败回滚 |
| `desktop/main.js` | 接入更新器；升级后首启成功清标记；首启失败进入回滚入口 |
| `desktop/package.json` | 版本 0.1.1；新增 `electron-updater` 依赖与 `build.publish`（GitHub provider）；`files` 纳入 `updater.js` |
| `scripts/build-desktop-installer.ps1` | `--dir` 构建后补写 `resources/app-update.yml`，并在 NSIS 阶段前校验其存在 |
| `.github/workflows/release.yml` | 上传 `latest.yml`；正式 Release 不再统一标 prerelease；签名后重算 `latest.yml` 的 sha512/size 并删除失配 blockmap |

## 行为约定

- 开发态（未打包）、非 Windows、`LUNARGOLD_DISABLE_UPDATE=1` 时完全不检查更新。
- 首启 8 秒后检查；发现新版本弹窗，用户可选择「下载更新 / 稍后提醒」。
- 下载完成后：sha512 强校验；`LUNARGOLD_REQUIRE_SIGNATURE=1` 时额外校验 Authenticode（未签名直接放弃安装）。
- 安装前把数据目录整目录备份到 `~/.lunargold/backup/<旧版本>-<时间戳>`（保留最近 2 份），并写 `upgrade-state.json`。
- 升级后首启成功：清理标记、清理旧备份；首启失败：提供「回滚到上一版本 / 打开日志 / 退出」。
- 回滚：恢复数据备份（原目录保留为 `*.failed-<时间戳>`，删除备份中的 `postmaster.pid`），以 `--updated /S --force-run /D=<安装目录>` 静默装回上一版本并重启。
- 陈旧升级标记（标记版本 ≠ 当前版本）自动清理。

## 验证结果（本地 generic 更新源）

| 场景 | 结果 |
| --- | --- |
| 0.1.1 → 0.1.2 升级 | 通过：下载 → 备份 → 静默安装 → 重启 → 首启成功；`local-token` 哈希、`db`、canary 文件完整 |
| sha512 错误 | 通过：`sha512 checksum mismatch`，不安装，版本不变，无备份产生 |
| 未签名 + 强制校验 | 通过：`NotSigned`，不安装，版本不变 |
| 磁盘空间不足 | 通过：下载前拦截，不下载不安装 |
| 升级后启动失败 | 通过：自动回滚到 0.1.1，数据备份恢复一致，现场目录保留，标记清理 |
| 进程残留 | 通过：正常退出与回滚后 `lunar-gold` / `java` / `postgres` 均 0 残留 |

## 发现的问题与修复

1. **`--prepackaged` 不生成 `app-update.yml`**：两段式构建下 electron-builder 不会写更新源清单，装好的应用检查更新直接报错。改为构建脚本在 `--dir` 之后显式写入并以 `Assert-File` 兜底。
2. **下载缓存目录写死**：初版按产品名拼接 `%LOCALAPPDATA%\Lunar Gold-updater`，实际是 `lunargold-desktop-updater`，导致校验阶段找不到安装包。改为扫描所有 `*-updater/pending` 取最新 exe。
3. **回滚下载只支持 https**：本地 generic 源是 http，报 `Protocol "http:" not supported`。改为按 URL 协议选择 `node:http` / `node:https`。
4. **回滚改用 PowerShell wrapper 启动安装器不可靠**：隐藏窗口的 powershell 子进程在部分环境不执行。改为与 electron-updater 一致的路径：直接以独立进程启动安装器，随后 `app.quit()`，由安装器 `--updated /S --force-run` 静默覆盖并重新拉起应用。
5. **签名会改变安装包哈希**：签名后 `latest.yml` 的 sha512 与 blockmap 全部失配，客户端会一直校验失败。CI 增加签名后重写 `latest.yml` 并删除 blockmap，退回整包下载。

## 限制与后续

- GitHub Release 端到端复核待 `v0.1.1` 正式发布后进行（线上源需包含 `latest.yml`）。
- SignPath 通过前保持未签名，不开启 `LUNARGOLD_REQUIRE_SIGNATURE`；开启后 CI 与客户端会同时校验签名。
- macOS/Linux 暂缓（项目所有者决定本阶段只适配 Windows）。
- 本次两次构建均使用 `-SkipTests`（后端未改动）；完整测试结论沿用最近一次全量通过记录。
