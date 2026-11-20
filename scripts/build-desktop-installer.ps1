<#
.SYNOPSIS
    一条命令构建 Windows x64 桌面端 NSIS 安装包。

.DESCRIPTION
    按锁文件安装前后端依赖，依次构建前端、含 UI/PG 运行时的后端 jar、
    裁剪 JRE 和 electron-builder 安装包。unpacked 默认写入工作区外
    D:\test\01\desktop-pack，避免 IDE 索引锁定 asar；最终安装包固定写入
    项目 release/installer。不清理上传文件、开发数据库或用户数据目录。

.PARAMETER JavaHome
    带 javac/jlink 的 JDK 21 目录；默认取 JAVA_HOME。

.PARAMETER MavenCmd
    mvn.cmd 路径；默认从 PATH 或 MAVEN_HOME 解析。

.PARAMETER PackRoot
    unpacked 中间构建目录；默认 D:\test\01\desktop-pack。
    最终安装器始终写入项目 release/installer。

.PARAMETER SkipInstall
    复用现有 node_modules，跳过 npm ci。

.PARAMETER SkipTests
    跳过 Maven 测试；正式发布不建议使用。

.PARAMETER SkipNsis
    停止在 unpacked 阶段（用于 CI 在打包前对主程序 exe 签名）；
    NSIS 安装包随后由 electron-builder --prepackaged 单独生成。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build-desktop-installer.ps1 -JavaHome C:\Java\jdk-21
#>
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCmd,
    [string]$PackRoot = 'D:\test\01\desktop-pack',
    [switch]$SkipInstall,
    [switch]$SkipTests,
    [switch]$SkipNsis
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$buildDir = Join-Path $repo 'build'
$jarPath = Join-Path $repo 'target\agent-gateway-1.0.0.jar'
$pgRuntime = Join-Path $buildDir 'pg-runtime\pg-runtime.txz'
$jreJava = Join-Path $buildDir 'desktop-runtime\jre\bin\java.exe'
$unpacked = Join-Path $PackRoot 'win-unpacked'
$installerDir = Join-Path $repo 'release\installer'

function Write-Step($message) { Write-Host "[desktop-installer] $message" -ForegroundColor Cyan }
function Assert-NativeSuccess($message) {
    if ($LASTEXITCODE -ne 0) { throw "$message（退出码 $LASTEXITCODE）" }
}
function Assert-File($path, $message) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$message：$path" }
}

if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw '找不到 JDK：请用 -JavaHome 指定带 javac/jlink 的 JDK 21 目录'
}
if (-not $MavenCmd) {
    $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($onPath) { $MavenCmd = $onPath.Source }
    if (-not $MavenCmd -and $env:MAVEN_HOME) { $MavenCmd = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' }
}
Assert-File $MavenCmd '找不到 mvn.cmd，请设置 MAVEN_HOME 或使用 -MavenCmd'
$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npm) { throw '找不到 npm.cmd，请先安装 Node.js 22+' }
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
New-Item -ItemType Directory -Force -Path (Join-Path $buildDir 'ci-tmp') | Out-Null
$env:TEMP = Join-Path $buildDir 'ci-tmp'
$env:TMP = $env:TEMP

Write-Step '构建前端静态资源'
Push-Location (Join-Path $repo 'frontend')
try {
    if (-not $SkipInstall) { & npm ci; Assert-NativeSuccess '前端 npm ci 失败' }
    & npm run build
    Assert-NativeSuccess '前端构建失败'
} finally { Pop-Location }

if (-not (Test-Path -LiteralPath $pgRuntime -PathType Leaf)) {
    Write-Step '缺少 PostgreSQL + pgvector 运行时，开始构建'
    Write-Step '预拉 Maven 依赖（zonky embedded-postgres Windows 二进制是 test scope，需全 scope 拉取）'
    & $MavenCmd -B -q --no-transfer-progress dependency:resolve
    Assert-NativeSuccess 'Maven 依赖拉取失败'
    & (Join-Path $PSScriptRoot 'build-pg-runtime.ps1')
    Assert-NativeSuccess 'PG 运行时构建失败'
}
Assert-File $pgRuntime 'PG 运行时不存在，拒绝生成不完整安装包'

Write-Step '构建并验证后端 fat jar'
$mavenArgs = @('-B', '--no-transfer-progress', '-Pwith-frontend,with-pg-runtime', 'verify')
if ($SkipTests) { $mavenArgs = @('-B', '--no-transfer-progress', '-DskipTests', '-Pwith-frontend,with-pg-runtime', 'package') }
& $MavenCmd @mavenArgs
Assert-NativeSuccess 'Maven 构建失败'
Assert-File $jarPath '后端 jar 未生成'
$jarTool = Join-Path $JavaHome 'bin\jar.exe'
$entries = & $jarTool tf $jarPath
Assert-NativeSuccess '无法读取后端 jar'
foreach ($required in @('BOOT-INF/classes/static/index.html', 'BOOT-INF/classes/desktop/pg-runtime.txz')) {
    if ($entries -notcontains $required) { throw "后端 jar 缺少必需条目 $required，停止打包" }
}

Write-Step '裁剪桌面端私有 JRE'
& (Join-Path $PSScriptRoot 'build-desktop-runtime.ps1') -JdkHome $JavaHome
Assert-NativeSuccess 'JRE 裁剪失败'
Assert-File $jreJava '裁剪 JRE 缺少 java.exe'

Write-Step '生成 unpacked 应用'
Push-Location (Join-Path $repo 'desktop')
try {
    if (-not $SkipInstall) {
        & npm ci
        # npm 在某些平台会把 stderr 警告（如 deprecated）当成 NativeCommandError 抛给 PowerShell，
        # 但进程退出码仍是 0；这里显式检查退出码，避免 CI 环境误判
        if ($LASTEXITCODE -ne 0) { Assert-NativeSuccess '桌面壳 npm ci 失败' }
    }
    # Electron 包特殊：npm ci 只装 JS wrapper，dist/ 二进制需要 postinstall 下载。
    # CI runner 上 Electron 自带的 install.js 在 Windows PowerShell 下经常卡死（30min+ 无输出），
    # 绕过它直接用 curl.exe 下载 zip + Expand-Archive 解压，更可靠且有清晰进度。
    $electronVersionFile = Join-Path (Join-Path $repo 'desktop') 'node_modules\electron\dist\version'
    if (-not (Test-Path -LiteralPath $electronVersionFile)) {
        # 从 desktop/package.json 读取 Electron 版本
        $pkgJson = Get-Content (Join-Path $repo 'desktop\package.json') -Raw | ConvertFrom-Json
        $electronVer = $pkgJson.devDependencies.electron -replace '[\^~]', ''
        Write-Step "Electron dist 缺失，手动下载 v$electronVer (win32-x64)"
        $distDir = Split-Path $electronVersionFile
        New-Item -ItemType Directory -Force -Path $distDir | Out-Null
        $zip = Join-Path $env:TEMP "electron-v$electronVer-win32-x64.zip"
        $url = "https://github.com/electron/electron/releases/download/v$electronVer/electron-v$electronVer-win32-x64.zip"
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            Write-Host "curl.exe 下载 attempt $attempt/3: $url"
            & curl.exe @('-L', '--fail', '--retry', '3', '--retry-delay', '5', '--connect-timeout', '30', '-o', $zip, $url)
            if ($LASTEXITCODE -eq 0 -and (Get-Item $zip).Length -gt 100MB) { break }
            Write-Host "下载失败或太小，重试..."
            if (Test-Path $zip) { Remove-Item $zip -Force }
        }
        if (-not (Test-Path $zip) -or (Get-Item $zip).Length -lt 100MB) {
            throw "Electron zip 下载失败：$url"
        }
        Write-Host "解压到 $distDir"
        Expand-Archive -LiteralPath $zip -DestinationPath $distDir -Force
        Remove-Item $zip -Force
    }
    Assert-File $electronVersionFile "Electron dist 不完整：找不到 dist\version（CI 上常因 postinstall 跳过，已尝试强制 install）"
    $builder = Join-Path $repo 'desktop\node_modules\.bin\electron-builder.cmd'
    Assert-File $builder '桌面壳依赖不完整，请去掉 -SkipInstall 后重试'

    Write-Step '生成 SBOM 与第三方许可文件'
    & (Join-Path $PSScriptRoot 'build-compliance.ps1') -MavenCmd $MavenCmd

    & $builder --dir --publish never "--config.directories.output=$PackRoot"
    Assert-NativeSuccess 'electron-builder unpacked 构建失败'

    $legalSource = Join-Path $buildDir 'legal'
    $packagedLegal = Join-Path $unpacked 'resources\legal'
    $legalNames = @(
        'LICENSE', 'NOTICE', 'NOTICE.en', 'PRIVACY.md', 'PRIVACY.en.md', 'TERMS.md', 'TERMS.en.md',
        'SBOM.json', 'SBOM.frontend.json', 'SBOM.desktop.json', 'SBOM.runtime.json', 'THIRD-PARTY-LICENSES.txt'
    )
    foreach ($name in $legalNames) {
        $sourceFile = Join-Path $legalSource $name
        $packagedFile = Join-Path $packagedLegal $name
        Assert-File $sourceFile "合规 staging 缺少 $name"
        Assert-File $packagedFile "unpacked 缺少合规文件 $name"
        if ((Get-FileHash -LiteralPath $sourceFile).Hash -ne (Get-FileHash -LiteralPath $packagedFile).Hash) {
            throw "unpacked 合规文件与 staging 哈希不一致：$name"
        }
    }
    foreach ($name in @('SBOM.json', 'SBOM.frontend.json', 'SBOM.desktop.json', 'SBOM.runtime.json')) {
        $bom = Get-Content -LiteralPath (Join-Path $packagedLegal $name) -Raw | ConvertFrom-Json
        if ($bom.bomFormat -ne 'CycloneDX' -or @($bom.components).Count -eq 0) {
            throw "unpacked SBOM 校验失败：$name"
        }
    }

    # --dir 不产出 app-update.yml（它只在正式打包目标创建时才写），
    # 而 CI 是先 --dir、签名 exe、再 --prepackaged 出安装包，跳过这一步会导致装好的应用读不到更新源。
    Write-Step '写入自动更新清单 app-update.yml'
    $pkg = Get-Content -LiteralPath (Join-Path $repo 'desktop\package.json') -Raw | ConvertFrom-Json
    $publish = @($pkg.build.publish)[0]
    if (-not $publish) { throw 'desktop/package.json 缺少 build.publish，自动更新无法定位发布源' }
    $appUpdateYml = @(
        "provider: $($publish.provider)"
        "owner: $($publish.owner)"
        "repo: $($publish.repo)"
        "releaseType: $($publish.releaseType)"
        "updaterCacheDirName: $($pkg.name)-updater"
    ) -join "`r`n"
    $appUpdatePath = Join-Path $unpacked 'resources\app-update.yml'
    [System.IO.File]::WriteAllText($appUpdatePath, $appUpdateYml, (New-Object System.Text.UTF8Encoding($false)))
    Assert-File $appUpdatePath '自动更新清单未写入'

    if (-not $SkipNsis) {
        & $builder --prepackaged $unpacked --publish never "--config.directories.output=$installerDir"
        Assert-NativeSuccess 'electron-builder NSIS 构建失败'
    }
} finally { Pop-Location }

if ($SkipNsis) {
    Write-Warning '已按 -SkipNsis 停止在 unpacked 阶段；NSIS 安装包由后续步骤（签名后）用 --prepackaged 生成'
    return
}

$package = Get-Content -LiteralPath (Join-Path $repo 'desktop\package.json') -Raw | ConvertFrom-Json
$installerName = $package.build.artifactName.Replace('${version}', $package.version).Replace('${ext}', 'exe')
Assert-File (Join-Path $unpacked 'resources\app-update.yml') 'unpacked 应用缺少自动更新清单'
$installer = Join-Path $installerDir $installerName
$packagedJar = Join-Path $unpacked 'resources\sidecar\agent-gateway-1.0.0.jar'
Assert-File $installer 'NSIS 安装包未生成'
Assert-File $packagedJar 'unpacked 应用缺少后端 jar'
Assert-File (Join-Path $unpacked 'resources\jre\bin\java.exe') 'unpacked 应用缺少私有 JRE'
Assert-File (Join-Path $unpacked 'resources\app.asar') 'unpacked 应用缺少桌面壳'
if ((Get-FileHash -LiteralPath $jarPath).Hash -ne (Get-FileHash -LiteralPath $packagedJar).Hash) {
    throw 'unpacked 内的后端 jar 与 Maven 产物哈希不一致'
}

$installerMiB = [math]::Round((Get-Item -LiteralPath $installer).Length / 1MB, 2)
$unpackedMiB = [math]::Round(((Get-ChildItem -LiteralPath $unpacked -File -Recurse |
    Measure-Object Length -Sum).Sum) / 1MB, 2)
Write-Step "安装包：$installer ($installerMiB MiB)"
Write-Step "unpacked：$unpacked ($unpackedMiB MiB)"
Write-Warning '当前产物未签名；代码签名、macOS/Linux 安装包和真机安装/卸载仍需单独验收'
