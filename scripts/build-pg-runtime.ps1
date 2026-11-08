<#
.SYNOPSIS
    构建桌面端 PostgreSQL + pgvector 运行时压缩包（Windows x64 / PostgreSQL 16）。

.DESCRIPTION
    桌面端内嵌数据库由 io.zonky.test:embedded-postgres 启动，依赖内置的
    postgres-windows-x86_64.txz 不含 pgvector，V3 迁移的 CREATE EXTENSION vector 会失败。
    本脚本把该 txz 解出，注入 pgvector 的 vector.dll 与 share/extension 元数据后重新打包，
    产出可直接传给 DESKTOP_PG_RUNTIME 的 pg-runtime.txz。

    产物结构与 zonky 内置包保持一致（bin/ lib/ share/ 直接在压缩包根目录），
    否则 zonky 解压后找不到 bin/postgres.exe。

    pgvector 的 Windows 预编译二进制来自社区仓库（pgvector 官方不发 Windows 二进制）：
    https://github.com/andreiramani/pgvector_pgsql_windows
    脚本按固定 SHA-256 校验，校验失败即中止，避免拿到被替换的 DLL。

.PARAMETER ZonkyJar
    zonky 的 Windows 二进制 jar；默认在本地 ~/.m2 仓库中查找最高版本。

.PARAMETER PgvectorZip
    已下载的 pgvector Windows 压缩包；不传则下载到 -OutputDir 下的缓存文件。

.PARAMETER OutputDir
    产物目录，默认 <仓库根>/build/pg-runtime（build/ 已被 .gitignore 忽略）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build-pg-runtime.ps1

.EXAMPLE
    $env:DESKTOP_PG_RUNTIME = (Resolve-Path build\pg-runtime\pg-runtime-windows-x64-pg16.11-vector0.8.7.txz).Path
#>
[CmdletBinding()]
param(
    [string]$ZonkyJar,
    [string]$PgvectorZip,
    # 默认 <仓库根>/build/pg-runtime；param 默认值里 $PSScriptRoot 为空，故在脚本体内补齐
    [string]$OutputDir,
    [string]$PgvectorVersion = '0.8.7',
    [string]$PgvectorUrl = 'https://github.com/andreiramani/pgvector_pgsql_windows/releases/download/0.8.7/vector.v0.8.7-pg16.zip',
    # 上游资产哈希；升级 pgvector 时必须同时更新版本、URL 与这个值
    [string]$PgvectorSha256 = '69DEE8F237966A5B87EC109EC8ED619613432940A8E6150A1D0DA627FC0F2F7E'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Write-Step($message) { Write-Host "[pg-runtime] $message" }

function Get-ZonkyJar {
    if ($ZonkyJar) {
        if (-not (Test-Path -LiteralPath $ZonkyJar)) { throw "指定的 zonky jar 不存在: $ZonkyJar" }
        return (Resolve-Path -LiteralPath $ZonkyJar).Path
    }
    $repo = Join-Path $env:USERPROFILE '.m2\repository\io\zonky\test\postgres\embedded-postgres-binaries-windows-amd64'
    if (-not (Test-Path -LiteralPath $repo)) {
        throw "本地 Maven 仓库没有 zonky Windows 二进制: $repo（先执行一次 mvn -q dependency:resolve）"
    }
    $jar = Get-ChildItem -LiteralPath $repo -Recurse -Filter 'embedded-postgres-binaries-windows-amd64-*.jar' |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object { [version]($_.BaseName -replace '^embedded-postgres-binaries-windows-amd64-', '') } |
        Select-Object -Last 1
    if (-not $jar) { throw "在 $repo 下找不到 zonky Windows 二进制 jar" }
    return $jar.FullName
}

function Expand-ZipEntry($zipPath, $pattern, $destination) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $entry = $archive.Entries | Where-Object { $_.FullName -like $pattern } | Select-Object -First 1
        if (-not $entry) { throw "$zipPath 中找不到 $pattern" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
    } finally {
        $archive.Dispose()
    }
}

if (-not $OutputDir) {
    $OutputDir = Join-Path $PSScriptRoot '..\build\pg-runtime'
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir = Join-Path $PSScriptRoot $OutputDir
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

# 1. 取出 zonky 内置 txz
$jarPath = Get-ZonkyJar
$zonkyVersion = [System.IO.Path]::GetFileNameWithoutExtension($jarPath) -replace '^embedded-postgres-binaries-windows-amd64-', ''
Write-Step "zonky 运行时: $jarPath (PostgreSQL $zonkyVersion)"

$work = Join-Path $OutputDir 'work'
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work | Out-Null

$baseTxz = Join-Path $work 'postgres-windows-x86_64.txz'
Expand-ZipEntry $jarPath '*.txz' $baseTxz

$pg = Join-Path $work 'pg'
New-Item -ItemType Directory -Force -Path $pg | Out-Null
Write-Step "解压内置运行时到 $pg"
& tar -xf $baseTxz -C $pg
if ($LASTEXITCODE -ne 0) { throw "解压 $baseTxz 失败 (tar exit=$LASTEXITCODE)" }

foreach ($required in @('bin\postgres.exe', 'bin\initdb.exe', 'lib', 'share\extension')) {
    if (-not (Test-Path -LiteralPath (Join-Path $pg $required))) {
        throw "运行时结构不符合预期，缺少 $required（zonky 期望 bin/lib/share 在压缩包根目录）"
    }
}

# 2. 取 pgvector 二进制并校验
$vectorZip = if ($PgvectorZip) { (Resolve-Path -LiteralPath $PgvectorZip).Path } else { Join-Path $OutputDir "vector.v$PgvectorVersion-pg16.zip" }
if (-not (Test-Path -LiteralPath $vectorZip)) {
    Write-Step "下载 pgvector $PgvectorVersion (pg16): $PgvectorUrl"
    Invoke-WebRequest -Uri $PgvectorUrl -OutFile $vectorZip -UseBasicParsing -TimeoutSec 180
}
$actualHash = (Get-FileHash -LiteralPath $vectorZip -Algorithm SHA256).Hash
if ($actualHash -ne $PgvectorSha256.ToUpperInvariant()) {
    throw "pgvector 压缩包 SHA-256 不匹配，可能被替换或上游重新打包。`n期望: $PgvectorSha256`n实际: $actualHash`n如确认上游更新，请同步修改脚本中的 PgvectorVersion/URL/SHA256"
}
Write-Step "pgvector 压缩包校验通过: $vectorZip"

$vectorExtract = Join-Path $work 'vector'
New-Item -ItemType Directory -Force -Path $vectorExtract | Out-Null
[System.IO.Compression.ZipFile]::ExtractToDirectory($vectorZip, $vectorExtract)

# 3. 注入扩展：DLL 进 lib，控制文件与升级脚本进 share/extension
$vectorDll = Join-Path $vectorExtract 'lib\vector.dll'
$vectorControl = Join-Path $vectorExtract 'share\extension\vector.control'
if (-not (Test-Path -LiteralPath $vectorDll)) { throw "pgvector 包缺少 lib\vector.dll" }
if (-not (Test-Path -LiteralPath $vectorControl)) { throw "pgvector 包缺少 share\extension\vector.control" }

Copy-Item -LiteralPath $vectorDll -Destination (Join-Path $pg 'lib\vector.dll') -Force
Copy-Item -Path (Join-Path $vectorExtract 'share\extension\vector*') -Destination (Join-Path $pg 'share\extension') -Force
Write-Step "已注入 vector.dll 与 share/extension 元数据"

$controlVersion = (Select-String -LiteralPath (Join-Path $pg 'share\extension\vector.control') -Pattern "^default_version\s*=\s*'([^']+)'").Matches.Groups[1].Value
if (-not $controlVersion) { throw "无法从 vector.control 读取 default_version" }
Write-Step "vector.control default_version = $controlVersion"

# 4. 重新打包：必须保留 bin/lib/share 作为根目录下的顶层项
$outputName = "pg-runtime-windows-x64-pg$zonkyVersion-vector$controlVersion.txz"
$outputPath = Join-Path $OutputDir $outputName
if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath -Force }

Write-Step "重新打包: $outputPath"
& tar -C $pg -cJf $outputPath bin lib share
if ($LASTEXITCODE -ne 0) { throw "打包失败 (tar exit=$LASTEXITCODE)" }

$txzHash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash
$sizeMb = [math]::Round((Get-Item -LiteralPath $outputPath).Length / 1MB, 2)

# 固定名副本：Maven profile with-pg-runtime 按固定名打进 jar（classpath:desktop/pg-runtime.txz），
# 运行时无需按版本号猜文件名
$bundlePath = Join-Path $OutputDir 'pg-runtime.txz'
Copy-Item -LiteralPath $outputPath -Destination $bundlePath -Force

Write-Host ''
Write-Step "产物: $outputPath"
Write-Step "打包用: $bundlePath"
Write-Step "大小: $sizeMb MiB"
Write-Step "SHA-256: $txzHash"
Write-Step "开发环境使用: `$env:DESKTOP_PG_RUNTIME = '$outputPath'"
Write-Step "打进 jar: mvn -Pwith-frontend,with-pg-runtime package"
