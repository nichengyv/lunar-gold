<#
.SYNOPSIS
    P2-06：一条命令启动桌面端开发环境（后端 desktop profile + 前端 Vite）。

.DESCRIPTION
    做三件事后分别开两个独立窗口：
    1. 确保 build\pg-runtime 下存在含 pgvector 的 PostgreSQL 运行时（没有就调用 build-pg-runtime.ps1）；
    2. 复用/生成开发用 AES 主密钥，并固定 DESKTOP_DATA_DIR 到 build\dev-desktop-data；
    3. 启动后端（等 /actuator/health 变 UP）再启动前端，浏览器自动打开。

    两个窗口独立，关掉窗口即结束对应进程；数据目录可随时整体删除以重置桌面端。

.PARAMETER JavaHome
    JDK 21 目录，默认取 JAVA_HOME。

.PARAMETER MavenCmd
    mvn.cmd 路径；默认在 PATH / MAVEN_HOME / 常见 IDEA 目录里查找。

.PARAMETER NoOpen
    不自动打开浏览器。

.PARAMETER SkipInstall
    跳过 npm install（node_modules 已就绪时加快启动）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev-desktop.ps1
#>
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCmd,
    [switch]$NoOpen,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

function Write-Step($message) { Write-Host "[dev-desktop] $message" -ForegroundColor Cyan }

$repo = Split-Path $PSScriptRoot -Parent
$runtimeDir = Join-Path $repo 'build\pg-runtime'
$dataDir = Join-Path $repo 'build\dev-desktop-data'

# ---------- 1. 工具链 ----------
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    throw "找不到 JDK 21：请设置 JAVA_HOME，或用 -JavaHome 指定（例如 C:\Users\<you>\.jdks\ms-21.0.11）"
}
if (-not $MavenCmd) {
    $candidates = @()
    $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    if ($env:MAVEN_HOME) { $candidates += Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' }
    foreach ($root in @("$env:ProgramFiles\JetBrains", "${env:ProgramFiles(x86)}\JetBrains", "$env:LOCALAPPDATA\Programs", 'D:\IDEA')) {
        if ($root -and (Test-Path $root)) {
            $candidates += Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName 'plugins\maven\lib\maven3\bin\mvn.cmd' }
        }
    }
    $MavenCmd = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}
if (-not $MavenCmd) {
    throw "找不到 mvn.cmd：请把 Maven 加入 PATH、设置 MAVEN_HOME，或用 -MavenCmd 指定（IDEA 自带路径：<IDEA 安装目录>\plugins\maven\lib\maven3\bin\mvn.cmd）"
}
Write-Step "JDK: $JavaHome"
Write-Step "Maven: $MavenCmd"

# ---------- 2. PostgreSQL + pgvector 运行时 ----------
$runtime = Get-ChildItem $runtimeDir -Filter 'pg-runtime-*.txz' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $runtime) {
    Write-Step "未找到运行时，先执行 scripts\build-pg-runtime.ps1 ..."
    & (Join-Path $PSScriptRoot 'build-pg-runtime.ps1')
    if ($LASTEXITCODE -ne 0) { throw "运行时构建失败，退出码 $LASTEXITCODE" }
    $runtime = Get-ChildItem $runtimeDir -Filter 'pg-runtime-*.txz' | Sort-Object LastWriteTime | Select-Object -Last 1
}
Write-Step "运行时: $($runtime.FullName)"

# ---------- 3. 开发用密钥与数据目录 ----------
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$keyFile = Join-Path $dataDir 'aes.key'
$aesKey = if ($env:AES_MASTER_KEY) {
    $env:AES_MASTER_KEY
} elseif (Test-Path $keyFile) {
    (Get-Content $keyFile -Raw).Trim()
} else {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $generated = [Convert]::ToBase64String($bytes)
    Set-Content -LiteralPath $keyFile -Value $generated -Encoding ascii
    $generated
}
Write-Step "数据目录: $dataDir（删除该目录等于重置桌面端数据）"

# ---------- 4. 启动后端 ----------
# 子窗口的日志同时落盘：启动失败时脚本能把真实原因打出来，而不是只说"没就绪"
$backendLog = Join-Path $dataDir 'backend.log'
$backendCommand = @(
    "`$env:JAVA_HOME = '$JavaHome'"
    "`$env:AES_MASTER_KEY = '$aesKey'"
    "`$env:DESKTOP_PG_RUNTIME = '$($runtime.FullName)'"
    "`$env:DESKTOP_DATA_DIR = '$dataDir'"
    "Write-Host 'AgentKey 桌面端后端启动中（desktop profile）...' -ForegroundColor Cyan"
    "& '$MavenCmd' 'spring-boot:run' '-Dspring-boot.run.profiles=desktop' 2>&1 | Tee-Object -FilePath '$backendLog'"
) -join '; '

Start-Process -FilePath 'powershell.exe' -WorkingDirectory $repo `
    -ArgumentList @('-NoExit', '-NoProfile', '-Command', $backendCommand)
Write-Step "后端窗口已打开，等待 http://127.0.0.1:8080/actuator/health 就绪 ..."

$healthUrl = 'http://127.0.0.1:8080/actuator/health'
$ready = $false
$failed = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    if (Test-Path $backendLog) {
        $logText = Get-Content $backendLog -Raw -ErrorAction SilentlyContinue
        if ($logText -match 'APPLICATION FAILED TO START|Web server failed to start|already in use') {
            $failed = $true
            break
        }
    }
    try {
        $resp = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 3
        # Content 可能是 byte[]（actuator 返回 v3+json），只看状态码：DOWN 时 Boot 返回 503
        if ($resp.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        # 后端尚未监听，继续等
    }
}
if (-not $ready) {
    Write-Host ''
    Write-Host "----- backend.log 末尾 -----" -ForegroundColor Yellow
    Get-Content $backendLog -Tail 30 -ErrorAction SilentlyContinue
    Write-Host "---------------------------" -ForegroundColor Yellow
    $reason = if ($failed) { '后端启动失败' } else { '后端 3 分钟内没有就绪' }
    throw "$reason（完整日志：$backendLog）。常见原因：8080 端口被占用、pgvector 运行时缺失。"
}
Write-Step "后端已就绪"

# ---------- 5. 启动前端 ----------
$viteListening = (Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue) -ne $null
if ($viteListening) {
    Write-Step "5173 已有前端在监听，复用现有进程，不再重复启动"
} else {
    if (-not (Test-Path (Join-Path $repo 'frontend\node_modules')) -and -not $SkipInstall) {
        Write-Step "安装前端依赖 ..."
        Push-Location (Join-Path $repo 'frontend')
        try { & npm install } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw "npm install 失败，退出码 $LASTEXITCODE" }
    }

    $viteArgs = if ($NoOpen) { '' } else { ' -- --open' }
    $frontendCommand = "npm run dev$viteArgs"
    Start-Process -FilePath 'powershell.exe' -WorkingDirectory (Join-Path $repo 'frontend') `
        -ArgumentList @('-NoExit', '-NoProfile', '-Command', $frontendCommand)
    Write-Step "前端窗口已打开"
}

Write-Host ''
Write-Step "打开 http://localhost:5173 即可使用（桌面端自动换取本地令牌，无需登录）"
Write-Step "本地令牌文件: $dataDir\local-token"
