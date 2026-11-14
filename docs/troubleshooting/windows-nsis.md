# Windows NSIS 安装问题复盘与防踩坑记录

> 记录日期：2026-11-09
> 适用范围：AgentKey Pro Windows x64、Electron 43.3、electron-builder 26.18、NSIS 辅助安装向导
> 当前产物：`release/installer/LunarGold-Setup-0.1.0.exe`
> 当前产物 SHA-256：`40C06B8AC86054FBCC4777C81B4253A7D1B5B058632D12367BB027CF5EE6A92E`
> 当前产物说明：2026-11-09 更名 Lunar Gold 后的重打包产物，22 项静默回归全通过；`6BEF673C…1AD959` 为更名前的 AgentKey Pro 产物
> 更名说明：同日产品更名为 **Lunar Gold（金月）**，安装标识改为 `LunarGold-Setup-*.exe`、`lunar-gold.exe`、`%LOCALAPPDATA%\Programs\Lunar Gold`，数据目录改为 `~/.lunargold/desktop`。下文出现的 AgentKey 名称、路径与哈希均为当次排障的原始记录，不再回改。

## 1. 结论先行

这次不是 Electron、electron-builder 或 NSIS“框架本身坏了”，也不是后续 Phase 4、Phase 5 没完成导致无法打包。

实际是多类问题叠加：

1. **项目的自定义 NSIS 安装/卸载逻辑不完善**：通用卸载流程会丢失自定义安装目录，默认运行检测也可能把目标目录下的其他进程当成应用。
2. **Windows 工作区权限/完整性标记影响了同一个安装器的运行**：项目内副本和工作区外副本字节完全相同，但 NTFS ACL 不同；项目内副本继承了 Low Mandatory Level 和额外受限 ACE。
3. **构建中间文件曾被 IDE 扩展宿主占用**：`app.asar`/`default_app.asar` 的 `EBUSY` 是文件锁问题，不是安装包业务逻辑问题。
4. **未签名导致的安全警告是独立的发布问题**：它与“能否生成安装包”无关，但正式分发前必须处理。

因此，不能把某一次“无法关闭”“双击无反应”或“安全警告”都归因于同一个根因，也不能把所有问题都归因于框架。

## 2. 已证实的事实与边界

### 2.1 同一个 exe，在两个目录表现不同

以下两个文件的长度、修改时间和 SHA-256 完全一致：

- `D:\AgentKeyRelease\AgentKeyPro-Setup-0.1.0.exe`
- `D:\test-code\agent-gateway-v3\release\installer\AgentKeyPro-Setup-0.1.0.exe`

SHA-256 均为：

```text
733DB6DBBB6DF7AD861D6D06B0DAD0531B7F2A52A9E3CA6393AE8488C469C7E2
```

这证明最后一次“外部目录可用、项目目录不可用”的差异**不是安装器二进制内容不同**，更不是某一份包含了另一份没有的修复代码。

### 2.2 两边的 NTFS 安全描述符不同

工作区外副本只有正常继承的 Administrators、SYSTEM、Authenticated Users、Users 权限。

项目内副本及其父目录曾带有：

```text
Everyone:(DENY)(DC)
S-1-4-683572756-418055980:(W,D,DC)
Mandatory Label\Low Mandatory Level
```

经用户同意，通过一次 UAC 管理员确认执行了发布目录完整性修复：

```powershell
icacls 'D:\test-code\agent-gateway-v3\release' /setintegritylevel M /T /C
```

修复后，`release/installer` 和安装器均显示：

```text
Mandatory Label\Medium Mandatory Level
```

修复前后安装器 SHA-256 不变。具体由哪个工具首次写入 Low 标记和额外 ACE，目前没有足够证据，不能武断归因于某个 IDE、杀毒软件或 Windows 组件。

### 2.3 验证边界

- 工作区外安装器已成功安装并启动过后端侧车，说明产物本身具备可用安装链路。
- 权限修复后已复核哈希和 ACL。
- 为避免覆盖用户已经安装好的应用，权限修复后没有再次自动执行完整 GUI 安装；因此不能把“ACL 已修复”写成“所有安装/升级/卸载场景均已回归”。
- 当前只讨论 Windows x64，不能外推为 macOS/Linux 安装包已验证。

## 3. 坑一：自定义目录卸载时，卸载器不在原安装目录运行

### 现象

- 自定义目录安装后，卸载向导可能出现“所有用户/仅为我安装”等安装文案。
- 默认目录被删除，自定义安装目录却完整保留。
- 卸载器写入失败或卸载后文件残留。

### 根因

NSIS 正常卸载时，会把卸载器复制到类似下面的位置执行：

```text
%TEMP%\~nsu.tmp\Un_A.exe
```

所以此时 `$EXEDIR` 不是原始安装目录。

真实命令行中带有：

```text
_?=<原始安装目录>
```

但 NSIS 会从 `$CMDLINE` 隐藏这个特殊参数；同时 electron-builder 的通用多用户初始化和模式页可能重新设置 `$INSTDIR`。如果注册表状态也不完整，通用逻辑就无法可靠恢复自定义目录。

### 项目修复

`desktop/nsis/include.nsh` 中：

- 用 `StdUtils.GetAllParameters` 恢复 `_?=` 后的原始安装目录。
- 在 `customUnInit` 和删除文件前重新固定 `$INSTDIR`。
- 用 `customInstallMode` 强制当前用户模式，与 `perMachine: false` 一致。
- 用 `customRemoveFiles` 直接删除已确认的安装目录，避免走更新专用的原子改名/恢复分支。

### 防踩坑

- 不要把卸载器进程 `$EXEDIR` 当作安装目录。
- 不要只读 `$CMDLINE` 查找 `_?=`。
- 修改通用 NSIS 流程时，必须同时验证默认目录和自定义目录。
- 卸载测试必须检查目录和注册项，而不是只看卸载器退出码。

## 4. 坑二：“AgentKey Pro 无法关闭”不等于真的有应用在运行

### 现象

安装尚未开始解压文件，就弹出“AgentKey Pro 无法关闭，请手动关闭它，然后单击重试”。现场检查时又找不到真实 AgentKey 进程。

### 默认逻辑的问题

electron-builder 的 PowerShell 运行检测会按进程路径是否位于 `$INSTDIR` 下判断“应用是否运行”，范围不只是主程序 exe。这会把目标目录下的安装器、卸载器或其他辅助进程纳入检测。

此外，空目录首装本身没有需要关闭的旧应用；如果无条件执行运行检测，就会增加误报机会。

### 项目修复

当前 `desktop/nsis/include.nsh`：

1. 只有目标主程序文件存在时才进入运行检测。
2. 使用 `nsProcess` 按 `APP_EXECUTABLE_FILENAME` 精确匹配主程序。
3. 找到进程后先尝试正常关闭，再尝试强制结束。
4. 关闭结果不再用死循环弹窗阻断安装；真正的文件占用由后续文件复制重试机制兜底。

`desktop/package.json` 还将主程序与安装器文件名区分开：

```text
主程序：agentkey-pro.exe
安装器：AgentKeyPro-Setup-0.1.0.exe
```

显示名称和快捷方式仍为 AgentKey Pro。

### 防踩坑

- 弹窗文案只是安装器当前判断，不是进程事实。
- 先记录进程名、PID、可执行路径、目标安装目录，再决定结束哪个进程。
- 不要为通过安装而结束无关 IDE、Java、PostgreSQL 或用户进程。
- 安装器启动器返回 0，不等于安装成功；必须检查目标 exe、注册项和首启健康状态。

## 5. 坑三：同一个安装器，路径和 ACL 不同，运行结果就不同

### 现象

- `D:\AgentKeyRelease` 中的安装器可用。
- 项目 `release/installer` 中的同名安装器不可用。
- 两份文件互换、改名或重新复制后，仍容易把问题误判为“版本不同”。

### 根因

最后一次目录差异中，两个 exe 的哈希完全相同，差异在文件系统安全描述符：项目目录继承了 Low 完整性及额外 ACE，而工作区外目录没有。

Windows 上“能否执行一个文件”不仅取决于文件字节，还受以下因素影响：

- 文件和父目录 ACL。
- Mandatory Integrity Level。
- 文件是否被其他进程占用。
- NSIS 单实例互斥体及上一次安装状态。
- SmartScreen、杀毒软件和受控文件夹访问。
- 是否已有真实应用进程占用目标目录。

### 防踩坑

比较两个“同名安装器”时，至少同时比较：

```powershell
Get-FileHash -LiteralPath <安装器路径> -Algorithm SHA256
icacls <安装器路径>
icacls <安装器所在目录>
Get-AuthenticodeSignature -LiteralPath <安装器路径>
```

只有哈希相同而 ACL 不同，才能解释“同一二进制在不同路径表现不同”。如果哈希不同，应先视为不同产物，不能沿用旧结论。

## 6. 坑四：构建时 `app.asar` 的 `EBUSY` 是文件锁

### 现象

electron-builder 在 unpacked 阶段报：

```text
EBUSY: resource busy or locked, unlink ...\resources\app.asar
```

或：

```text
default_app.asar 被占用
```

### 已确认的情况

此前通过进程和文件占用检查，发现过 IDE 扩展宿主占用构建文件，并非 AgentKey 正在运行。

### 处理方式

- 先确认占用者，不要直接强制删除整个工作区。
- 使用干净的中间构建目录，完成后再归档到 `release`。
- 如果发布目录继承异常 ACL，构建后检查 `icacls`，不要只看 exe 是否生成。
- 不访问回收站，不清理 uploads、开发数据库或真实用户数据。

## 7. 坑五：未签名警告不是打包失败

当前安装器的 Authenticode 状态为 `NotSigned`，因此可能出现：

- `Unknown Publisher`。
- `Open File - Security Warning`。
- 从网站下载后的 SmartScreen 提示。

代码签名不是生成本地测试安装包的技术前提，但它是正式对外分发的重要发布门槛。不能把引导用户关闭安全功能或一律点击“仍要运行”当作最终方案。

正式发布应完成：

1. 对应用 exe 和安装器进行 Authenticode 签名，并加可信时间戳。
2. 证书私钥放证书介质、HSM、云签名服务或 CI 加密凭据，不入库。
3. 发布前确认 `Get-AuthenticodeSignature` 为 `Valid`。
4. 在 Release 提供 SHA-256。
5. 从真实下载地址在干净 Windows 机器复验。

普通 OV 签名不等于立即获得 SmartScreen 信誉，不能承诺签名后所有提示都会消失。

### 7.1 坑六：静默卸载会残留开始菜单快捷方式和 HKCU 卸载项

`electron-builder` 的卸载流程在删除程序文件之后才清理快捷方式和注册表，且快捷方式路径来自 `setLinkVars` 读取的注册表值。实测 `/S` 静默卸载后会出现：

- `%LOCALAPPDATA%\Programs\agentkey-pro` 已删除；
- `%USERPROFILE%\Desktop\AgentKey Pro.lnk` 已删除；
- `%APPDATA%\Microsoft\Windows\Start Menu\Programs\AgentKey Pro.lnk` 残留；
- `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall` 下的卸载项和 `HKCU\Software\00fbc329-643a-53cf-91bf-cce54e1a52c8` 残留。

修复方式是在 `desktop/nsis/include.nsh` 的 `customUnInstall` 中，用 `$isUpdateFlow` 区分“真实卸载”和“更新前 `_?=` 卸载”，仅对真实卸载按当前用户显式 `Delete` 两个快捷方式并 `DeleteRegKey` 两个 HKCU 键。更新流程不受影响，后续模板清理步骤重复执行也无害。

回归要点：静默安装必须显式传 `/D=<无空格目录>`，否则实测只写注册项、不落文件；卸载检查要等到卸载器副本把注册项和快捷方式清完，目录先消失不等于卸载完成。

### 7.2 坑七：后台任务方式跑构建，工作区外建目录报 EPERM

现象：构建脚本在 IDE 的后台任务里运行时，前端、Maven、jlink 全部通过，最后 electron-builder 报：

```text
EPERM: operation not permitted, mkdir 'D:\test\01\desktop-pack\win-unpacked'
```

同一时刻用前台命令在该目录 `node -e "fs.mkdirSync(...)"` 却成功。原因是后台任务仍受沙箱约束，只有工作区内可写；前台执行同一命令不受此限制。

处理方式：构建脚本一律前台执行，中间目录放 `D:\test\<序号>`；若必须后台跑，应把输出目录改到工作区内并接受 IDE 可能锁定 asar 的风险。

## 8. 后续阶段是不是打包前提

### 8.1 生成 Windows 测试安装包真正需要什么

| 前提 | 是否必须 | 原因 |
|---|---:|---|
| 前端生产构建 | 是 | SPA 需要随后端 jar 分发 |
| 后端 fat jar | 是 | 桌面侧车实际运行该 jar |
| jar 内含 SPA 与 PG/pgvector 运行时 | 是 | 否则安装后无法提供完整桌面运行时 |
| Windows x64 裁剪 JRE | 是 | 避免要求用户另装 Java |
| Electron `main.js`、`sidecar.js` | 是 | 窗口、侧车生命周期和单实例由壳负责 |
| 正确的 NSIS 配置 | 是 | 安装目录、卸载、进程检测都在这里处理 |
| 可写且未被锁定的构建目录 | 是 | 否则无法稳定生成 unpacked/NSIS |
| 正常的发布目录 ACL | 本机直接运行时需要 | 异常完整性标记会让同一 exe 表现不同 |

这些条件主要对应已经完成或正在收尾的 Phase 0～Phase 3 基础工作。它们不是“后面阶段没做”的因果链，而是打包输入必须真实存在。

### 8.2 哪些不是“生成安装包”的前提

| 后续工作 | 本地生成测试包是否需要 | 正式发布定位 |
|---|---:|---|
| P3-05 自动更新 | 否 | 要求 N-1 升级、失败回滚后才能宣称更新链路完成 |
| P3-06 崩溃上报 | 否 | 可观测性和发布质量工作 |
| P3-07 三平台冒烟流水线 | 否 | 持续发布和防回归门禁 |
| P3-08 发布合规 | 否 | 对外发布必须按范围完成 |
| Phase 4 安全与可靠性加固 | 否 | 不是 NSIS 编译依赖，但影响生产发布验收 |
| Phase 5 Agent 质量与差异化 | 否 | 与安装器能否生成无直接关系 |
| macOS/Linux 运行时和安装包 | 否 | 只影响对应平台发布，不阻塞 Windows x64 打包 |
| Authenticode 代码签名 | 否 | 本地测试包可不签名；正式分发必须验收签名与安全提示 |

### 8.3 正确理解

- **能打包**：输入产物齐全、构建目录可写、NSIS 能编译。
- **能安装运行**：安装/卸载逻辑正确、权限正常、文件未被占用、首启链路通过。
- **能正式发布**：还要签名、陌生机器验证、升级回滚、合规和发布回归。

这三种状态不能混为一谈。此次故障发生在第二类和构建环境上，并不是因为 Phase 4 或 Phase 5 没完成。

## 9. 后续验收清单

每次修改安装器或构建路径后，至少执行：

- [ ] 记录安装器路径、字节数和 SHA-256。
- [ ] 检查安装器及父目录 `icacls`，确认没有意外 Low 标记。
- [ ] 全新目录 GUI 安装，不只测试 `/S`。
- [ ] 自定义目录覆盖安装，分别验证无进程、真实主程序运行、目录内无关进程三种情况。
- [ ] 从“应用和功能”运行卸载向导，确认自定义目录被正确删除。
- [ ] 验证用户数据目录按产品约定保留，开发数据库和 uploads 不受影响。
- [ ] 安装后首启检查 `/actuator/health`、`/api/meta` 和真实页面。
- [ ] 验证升级、卸载和构建结束后没有无关 AgentKey/Java/PostgreSQL 残留。
- [ ] 在干净 Windows 机器复验，不能只用开发机结果代替发布验收。
- [ ] 正式发布前验证签名、时间戳和 Release 下载后的哈希。

## 10. 维护提醒

`desktop/package.json` 使用 `artifactName` 固定安装器文件名。构建脚本末尾已改为展开同一 `artifactName` 规则，不再硬编码旧的 `AgentKey Pro Setup 0.1.0.exe`；同时移除了脚本开头多余 BOM，PowerShell 语法检查已通过。

为避免 IDE 再次锁住 unpacked 中的 asar，统一脚本的中间目录默认为 `D:\AgentKeyBuild\desktop-pack`，可用 `-PackRoot` 覆盖；最终安装器始终写入项目 `release/installer`。2026-11-09 已用该布局完成一次完整统一构建。此项属于构建可复现性修复，与此前 GUI 安装器在特定目录无法运行不是同一个根因。
