# P3-06 / P3-08 收尾修复计划

## Context

P3-06 的本地诊断代码和脱敏 ZIP 已通过轻量测试，但用户在旧安装包中改 jar 名后看到原生 Error，真实窗口结论尚未关闭。当前源码已经先初始化 diagnostics 再解析 jar，因此必须先隔离复现，不能把旧包行为当作当前代码缺陷。

P3-08 已有 LICENSE、NOTICE、隐私政策、条款和 Java SBOM，但 Release 未附合规文件，SBOM 项目地址错误且只覆盖 Maven，完整第三方许可文本也未随包提供。本次只修这些已确认缺口，不扩展到签名、CI 全平台或远程诊断上传。

## 1. P3-06：隔离复现，窄范围修复

- 在工作区外临时目录复制当前 `desktop` 启动所需文件，使用仓库现有 Electron 二进制运行。
- 将 `DESKTOP_DATA_DIR`、`APPDATA`、`LOCALAPPDATA`、TEMP/TMP 和 Electron `user-data-dir` 全部指向临时目录；`LUNARGOLD_JAR` 指向不存在的文件，并禁用自动更新。临时 app 附近不得存在可回退的真实 target jar。
- 验证真实 Chromium 中文诊断卡片、错误码、重试、退出和 ZIP 导出；检查临时诊断报告和敏感信息脱敏，真实安装目录及 `%USERPROFILE%\.lunargold` 不变。
- 当前源码若通过，不为旧 v0.1.1 截图重写逻辑；若源码复现原生弹窗，则只修 `desktop/main.js` 中实际绕过 `diagnostics.init()` / `fail()` 的启动路径。
- diagnostics 初始化前的数据目录/AES 文件异常继续保留明确原生错误并退出的最后兜底，不另建无磁盘诊断体系。
- 新 unpacked 构建完成后，在临时副本中移走 sidecar jar 再做一次打包态复核；不修改真实安装目录。

## 2. P3-08：生成可复现合规产物

### 项目与 Java 元数据

修改 `pom.xml`，增加 Lunar Gold 的项目 URL 和 SCM，固定 CycloneDX Maven 插件与 license-maven-plugin 版本/输出配置。由构建显式调用，避免普通后端测试额外下载许可文件。重新生成根 `SBOM.json`，确认不再引用 Spring Boot 的项目 URL/VCS。

### 新增 `scripts/build-compliance.ps1`

统一输出到已忽略的 `build/legal`：

- 复制 LICENSE、NOTICE、中英文隐私政策和条款。
- 生成 Java SBOM；使用 npm 自带 `npm sbom --package-lock-only --sbom-format cyclonedx` 分别从现有锁文件生成前端、桌面生产依赖 SBOM。
- 生成精简运行时 SBOM，记录 Electron、裁剪 JRE、PostgreSQL 和 pgvector 的名称、版本、许可证、来源和实际产物哈希；版本取实际 Electron `version`、JRE `release`、POM/运行时文件，不手写虚构清单。
- 使用固定版本的 Java license 插件与 npm license-checker 工具提取实际许可证和版权文本，并收集 Electron、JRE、PostgreSQL、pgvector 的许可文本，排序生成 `THIRD-PARTY-LICENSES.txt`。工具只由构建调用，不加入应用 dependencies。
- 任一必需文件缺失、JSON 无法解析、运行时组件缺失或许可合集为空时立即失败，禁止只警告后继续发布。

`build/legal`、额外 SBOM 和许可合集作为构建产物不入 Git；修正后的根 `SBOM.json` 保留为审查快照。

## 3. 打包与 Release 集成

- 修改 `scripts/build-desktop-installer.ps1`，在 electron-builder 前调用合规脚本，并校验 staging 与 unpacked 中合规文件哈希一致；`-SkipNsis` 路径同样校验。
- 修改 `desktop/package.json`，通过 `extraResources` 将 `build/legal` 放入安装后的 `resources/legal`。
- 修改 `.github/workflows/release.yml`，将 `build/legal` 复制到 `release/legal`，在创建草稿 Release 前校验必需文件和 SBOM，再显式上传 LICENSE、NOTICE、隐私、条款、全部 SBOM 和许可合集。不得依赖 `fail_on_unmatched_files: false` 静默漏传。
- 更新现有 NOTICE 中的 SBOM/许可合集说明，以及 `docs/UPDATE_PLAN.md` / `docs/COMPLETED_WORK.md` 的真实状态；不替所有者决定法律管辖、隐私联系人或版权声明。

## 4. 验证

1. 对修改的 JS 做 `node --check`，对 PowerShell 做语法解析，对 workflow 做 YAML 解析。
2. 本地运行统一 Windows 构建，确认四个 SBOM 可解析、组件非空，Java 外部引用正确，运行时包含 Electron/JRE/PostgreSQL/pgvector，许可合集非空。
3. 比较 `build/legal`、unpacked `resources/legal` 和 Release staging 文件哈希；确认安装包包含这些文件。
4. 不远程触发 Release、不发布新标签；本地验证工作流上传清单与校验逻辑，远程草稿 Release 留待你明确触发。
5. 用新 unpacked 的临时副本完成 P3-06 缺失 jar 真实窗口复核，记录证据；若本机窗口自动化仍异常，明确保留人工复核项，不伪报通过。
