<#
.SYNOPSIS
    P6-02 CLI 端到端：真机启动 desktop 后端，用 lg 完成「建客户端 → 跑任务 → 看变更 → 撤回」。

.DESCRIPTION
    默认流程（每次自建干净数据目录与工作区副本，互不依赖）：
      1. 起后端（desktop profile + 内嵌 PG），端口 18082；
      2. 用本地令牌调 POST /api/keys 注入模型 Key（-ApiKey 或 LG_E2E_API_KEY）；
      3. lg client create / doctor；
      4. 建项目并把工作区指到 fixture 副本；
      5. lg run --auto-approve low 创建 note.txt；
      6. lg changes 断言文件变更，lg revert 断言文件被还原；
      7. lg client show / revoke 收尾。

    失败时打印后端日志尾部与 CLI stderr，便于 CI 定位。

.PARAMETER Port
    后端端口，默认 18082（避开日常开发用的 8080）。

.PARAMETER DataDir
    数据目录，默认 build\cli-e2e\data；默认每次清空重建。

.PARAMETER ApiKey
    模型 Key（DEEPSEEK）。也可用环境变量 LG_E2E_API_KEY；库里已有 Key 时可省略。

.PARAMETER SkipRun
    只验证启动、doctor、client，不跑需要模型的 run/watch/changes/revert。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\cli-e2e.ps1 -ApiKey $env:DEEPSEEK_API_KEY

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\cli-e2e.ps1 -SkipRun
#>
[CmdletBinding()]
param(
    [int]$Port = 18082,
    [string]$DataDir,
    [string]$ApiKey = $env:LG_E2E_API_KEY,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCmd,
    [switch]$KeepData,
    [switch]$SkipRun
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $DataDir) { $DataDir = Join-Path $repo 'build\cli-e2e\data' }
$workRoot = Split-Path $DataDir -Parent
$workspace = Join-Path $workRoot 'workspace'
$cliConfig = Join-Path $DataDir 'cli-config.json'
$backendLog = Join-Path $DataDir 'backend.log'
$baseUrl = "http://127.0.0.1:$Port"
$backendProcess = $null
$cliTranscript = New-Object System.Collections.Generic.List[string]

function Write-Step($message) { Write-Host "[cli-e2e] $message" -ForegroundColor Cyan }

function Invoke-Lg {
    param([string[]]$Arguments)
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    & node (Join-Path $repo 'cli\src\main.ts') @Arguments 1> $stdoutFile 2> $stderrFile
    $code = $LASTEXITCODE
    $out = if (Test-Path $stdoutFile) { Get-Content -Raw $stdoutFile } else { '' }
    $err = if (Test-Path $stderrFile) { Get-Content -Raw $stderrFile } else { '' }
    Remove-Item $stdoutFile, $stderrFile -ErrorAction SilentlyContinue
    $cliTranscript.Add("`$ lg $($Arguments -join ' ')  => $code")
    if ($out) { $cliTranscript.Add($out.TrimEnd()) }
    if ($err) { $cliTranscript.Add("[stderr] " + $err.TrimEnd()) }
    return [pscustomobject]@{ Code = $code; Out = $out; Err = $err }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        [string]$Token
    )
    $headers = @{ Authorization = "Bearer $Token"; Accept = 'application/json' }
    $params = @{ Method = $Method; Uri = "$baseUrl$Path"; Headers = $headers; TimeoutSec = 30 }
    if ($null -ne $Body) {
        $params['ContentType'] = 'application/json; charset=utf-8'
        $params['Body'] = ($Body | ConvertTo-Json -Depth 6 -Compress)
    }
    return Invoke-RestMethod @params
}

function Wait-Health {
    $tokenFile = Join-Path $DataDir 'local-token'
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Path $backendLog) {
            $text = Get-Content $backendLog -Raw -ErrorAction SilentlyContinue
            if ($text -match 'APPLICATION FAILED TO START|Web server failed to start|already in use') {
                throw "后端启动失败，详见 $backendLog"
            }
        }
        # /api/health 也要求认证：本地令牌落盘后才用 Bearer 探测
        if (-not (Test-Path $tokenFile)) { continue }
        $token = (Get-Content $tokenFile -Raw).Trim()
        try {
            $health = Invoke-RestMethod -Uri "$baseUrl/api/health" -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 2
            if ($health.status -eq 'UP') { return $health }
        } catch {
            # 尚未就绪，继续等
        }
    }
    throw "后端 120 秒内没有就绪（$baseUrl/api/health）"
}

function Stop-Backend {
    if ($backendProcess -and -not $backendProcess.HasExited) {
        & taskkill.exe /PID $backendProcess.Id /T /F 2> $null | Out-Null
    }
    $listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    foreach ($listener in $listeners) {
        Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    $backendProcess = $null
}

function Show-Failure([string]$message) {
    Write-Host ''
    Write-Host "----- backend.log 末尾 -----" -ForegroundColor Yellow
    Get-Content $backendLog -Tail 40 -ErrorAction SilentlyContinue
    Write-Host "----- CLI 输出 -----" -ForegroundColor Yellow
    $cliTranscript | ForEach-Object { Write-Host $_ }
    Write-Host '-----------------------------' -ForegroundColor Yellow
    Write-Host "失败：$message" -ForegroundColor Red
}

try {
    # ---------- 工具链 ----------
    if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
        throw '找不到 JDK：请设置 JAVA_HOME 或用 -JavaHome 指定'
    }
    if (-not $MavenCmd) {
        $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($onPath) {
            $MavenCmd = $onPath.Source
        } elseif ($env:MAVEN_HOME) {
            $MavenCmd = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
        }
    }
    if (-not $MavenCmd -or -not (Test-Path $MavenCmd)) {
        throw '找不到 mvn.cmd：请把 Maven 加入 PATH、设置 MAVEN_HOME，或用 -MavenCmd 指定'
    }
    $runtime = Get-ChildItem (Join-Path $repo 'build\pg-runtime') -Filter 'pg-runtime-*.txz' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $runtime) {
        throw '找不到 PostgreSQL 运行时：先执行 scripts\build-pg-runtime.ps1'
    }

    # ---------- 干净数据目录与工作区 ----------
    if (Test-Path $DataDir) {
        if ($KeepData) {
            Write-Step "复用数据目录：$DataDir"
        } else {
            Remove-Item $DataDir -Recurse -Force
        }
    }
    New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
    if (Test-Path $workspace) { Remove-Item $workspace -Recurse -Force }
    Copy-Item (Join-Path $repo 'cli\test\fixtures\workspace') $workspace -Recurse

    $keyFile = Join-Path $DataDir 'aes.key'
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

    # ---------- 启动后端 ----------
    Write-Step "启动后端：$baseUrl（日志 $backendLog）"
    $backendCommand = @(
        "`$env:JAVA_HOME = '$JavaHome'"
        "`$env:AES_MASTER_KEY = '$aesKey'"
        "`$env:DESKTOP_PG_RUNTIME = '$($runtime.FullName)'"
        "`$env:DESKTOP_DATA_DIR = '$DataDir'"
        "`$env:DESKTOP_SERVER_PORT = '$Port'"
        "& '$MavenCmd' -o spring-boot:run '-Dspring-boot.run.profiles=desktop' 2>&1 | Tee-Object -FilePath '$backendLog'"
    ) -join '; '
    $backendProcess = Start-Process -FilePath 'powershell.exe' -WorkingDirectory $repo `
        -ArgumentList @('-NoProfile', '-Command', $backendCommand) -PassThru -WindowStyle Hidden
    $health = Wait-Health
    Write-Step "后端已就绪：apiContractVersion=$($health.apiContractVersion)"

    $localTokenPath = Join-Path $DataDir 'local-token'
    if (-not (Test-Path $localTokenPath)) { throw "本地令牌文件未生成：$localTokenPath" }
    $localToken = (Get-Content $localTokenPath -Raw).Trim()

    # CLI 的配置与本地令牌都指向本次数据目录，避免污染开发者真实 ~/.lunargold
    $env:LG_CONFIG_PATH = $cliConfig
    $env:LG_LOCAL_TOKEN_PATH = $localTokenPath
    $env:LG_BASE_URL = $baseUrl

    # ---------- client create / doctor ----------
    Write-Step 'lg client create'
    $created = Invoke-Lg @('client', 'create', '--name', 'cli-e2e')
    if ($created.Code -ne 0) { throw "client create 失败（退出码 $($created.Code)）" }

    Write-Step 'lg doctor'
    $doctor = Invoke-Lg @('doctor')
    if ($doctor.Code -ne 0) { throw "doctor 失败（退出码 $($doctor.Code)）" }

    if ($SkipRun) {
        Write-Step 'SkipRun：跳过 run/changes/revert'
        Write-Host ''
        Write-Step 'cli-e2e 通过（SkipRun 模式）'
        exit 0
    }

    # ---------- 种子模型 Key ----------
    if ($ApiKey) {
        Write-Step '注入模型 Key（DEEPSEEK）'
        Invoke-Api -Method 'POST' -Path '/api/keys' -Body @{ provider = 'DEEPSEEK'; apiKey = $ApiKey } -Token $localToken | Out-Null
    } else {
        $keys = Invoke-Api -Method 'GET' -Path '/api/keys' -Token $localToken
        if (-not $keys.data -or $keys.data.Count -eq 0) {
            throw '库里没有模型 Key：请传 -ApiKey 或设置 LG_E2E_API_KEY（也可用 -SkipRun 只验证启动与 client）'
        }
    }

    # ---------- 项目与工作区 ----------
    Write-Step "绑定工作区：$workspace"
    $project = Invoke-Api -Method 'POST' -Path '/api/projects' -Body @{ name = 'cli-e2e' } -Token $localToken
    $projectId = $project.data.id
    Invoke-Api -Method 'POST' -Path "/api/projects/$projectId/workspace/set-path" `
        -Body @{ path = $workspace } -Token $localToken | Out-Null

    # ---------- run ----------
    Write-Step 'lg run（--auto-approve low，--json）'
    $prompt = '在当前工作区创建文件 note.txt，文件内容只写一行：hello from lg cli。不要修改其他文件。'
    $run = Invoke-Lg @('run', $prompt, '--project', "$projectId", '--auto-approve', 'low', '--json')
    if ($run.Code -ne 0 -and $run.Code -ne 6) {
        throw "run 未成功（退出码 $($run.Code)）"
    }
    $sessionId = $null
    $runId = $null
    foreach ($line in ($run.Out -split "`n")) {
        if (-not $line.Trim()) { continue }
        $event = $line | ConvertFrom-Json
        if ($event.type -eq 'session') { $sessionId = $event.sessionId }
        if ($event.type -eq 'run') { $runId = $event.runId }
    }
    if (-not $sessionId -or -not $runId) { throw 'run 输出里没有 session/run 事件' }
    Write-Step "run 完成：session=$sessionId run=$runId exit=$($run.Code)"

    $notePath = Join-Path $workspace 'note.txt'
    if (-not (Test-Path $notePath)) { throw "run 没有生成 note.txt（$notePath）" }
    $noteText = Get-Content $notePath -Raw
    Write-Step "note.txt：$($noteText.Trim())"

    # ---------- changes ----------
    Write-Step 'lg changes'
    $changes = Invoke-Lg @('changes', '--session', "$sessionId", '--run', "$runId")
    if ($changes.Code -ne 0) { throw "changes 失败（退出码 $($changes.Code)）" }
    if ($changes.Out -notmatch 'note\.txt') { throw 'changes 输出里没有 note.txt' }

    # ---------- revert ----------
    Write-Step 'lg revert --run'
    $revert = Invoke-Lg @('revert', '--session', "$sessionId", '--run', "$runId", '--yes')
    if ($revert.Code -ne 0) { throw "revert 失败（退出码 $($revert.Code)）" }
    if (Test-Path $notePath) { throw 'revert 后 note.txt 仍然存在' }
    Write-Step 'revert 后 note.txt 已删除'

    # ---------- client show / revoke ----------
    Write-Step 'lg client show'
    $show = Invoke-Lg @('client', 'show')
    if ($show.Code -ne 0) { throw "client show 失败（退出码 $($show.Code)）" }

    Write-Step 'lg client revoke'
    $revoke = Invoke-Lg @('client', 'revoke')
    if ($revoke.Code -ne 0) { throw "client revoke 失败（退出码 $($revoke.Code)）" }

    Write-Host ''
    Write-Step 'cli-e2e 通过'
    exit 0
} catch {
    Show-Failure $_.Exception.Message
    exit 1
} finally {
    Stop-Backend
}
