<#
.SYNOPSIS
    一条命令构建 Windows x64 桌面端 NSIS 安装包。

.DESCRIPTION
    按锁文件安装前后端依赖，依次构建前端、含 UI/PG 运行时的后端 jar、
    裁剪 JRE 和 electron-builder 安装包。unpacked 与最终安装包都写入系统临时目录，
    避免 IDE 文件监视占用以及工作区 build 目录的低完整性标记影响安装器。
    不清理上传文件、开发数据库或用户数据目录。

.PARAMETER JavaHome
    带 javac/jlink 的 JDK 21 目录；默认取 JAVA_HOME。

.PARAMETER MavenCmd
    mvn.cmd 路径；默认从 PATH 或 MAVEN_HOME 解析。

.PARAMETER SkipInstall
    复用现有 node_modules，跳过 npm ci。

.PARAMETER SkipTests
    跳过 Maven 测试；正式发布不建议使用。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build-desktop-installer.ps1 -JavaHome C:\Java\jdk-21
#>
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCmd,
    [switch]$SkipInstall,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$buildDir = Join-Path $repo 'build'
$jarPath = Join-Path $repo 'target\agent-gateway-1.0.0.jar'
$pgRuntime = Join-Path $buildDir 'pg-runtime\pg-runtime.txz'
$jreJava = Join-Path $buildDir 'desktop-runtime\jre\bin\java.exe'
$packRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'agentkey-desktop-pack'
$unpacked = Join-Path $packRoot 'win-unpacked'
$installerDir = Join-Path ([System.IO.Path]::GetTempPath()) 'agentkey-desktop-installer'

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

Write-Step '生成 unpacked 应用与 NSIS 安装包'
Push-Location (Join-Path $repo 'desktop')
try {
    if (-not $SkipInstall) { & npm ci; Assert-NativeSuccess '桌面壳 npm ci 失败' }
    $builder = Join-Path $repo 'desktop\node_modules\.bin\electron-builder.cmd'
    Assert-File $builder '桌面壳依赖不完整，请去掉 -SkipInstall 后重试'
    & $builder --dir --publish never "--config.directories.output=$packRoot"
    Assert-NativeSuccess 'electron-builder unpacked 构建失败'
    & $builder --prepackaged $unpacked --publish never "--config.directories.output=$installerDir"
    Assert-NativeSuccess 'electron-builder NSIS 构建失败'
} finally { Pop-Location }

$package = Get-Content -LiteralPath (Join-Path $repo 'desktop\package.json') -Raw | ConvertFrom-Json
$installer = Join-Path $installerDir "$($package.build.productName) Setup $($package.version).exe"
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
