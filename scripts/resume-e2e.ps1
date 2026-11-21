<#
.SYNOPSIS
    R7b 验收补齐：真实进程崩溃后的 Run 级断点续跑端到端。

.DESCRIPTION
    流程（每次自建干净数据目录与工作区副本）：
      1. 启动零依赖假模型（scripts\fake-openai-server.mjs，默认 18083）与 desktop 后端（默认 18082）；
      2. 注入模型 Key、建项目并把工作区指到 fixture 副本；
      3. POST /api/agent/chat/stream：假模型返回 write_file 工具调用，后端落 CHECKPOINT 后第二次模型调用卡住；
      4. 确认 CHECKPOINT 落库后 taskkill /F 真实杀掉后端进程树（不做任何接口模拟）；
      5. 同一数据目录重启后端，断言 run 变为 FAILED / PROCESS_RESTARTED / resumable=true；
      6. 切假模型为 finish 模式，POST /runs/{runId}/resume，断言 SUCCEEDED；
      7. 断言 note.txt、续跑请求 messages 快照（tool 消息恰 1 条且含原 prompt）、最终助手输出、checkpoint round=1。

.PARAMETER Port
    后端端口，默认 18082。

.PARAMETER ModelPort
    假模型端口，默认 18083。

.PARAMETER DataDir
    数据目录，默认 build\resume-e2e\data；默认每次清空重建。

.PARAMETER JavaHome
    JDK 目录，默认取 JAVA_HOME。

.PARAMETER MavenCmd
    mvn.cmd 路径，默认从 PATH / MAVEN_HOME 查找。

.PARAMETER KeepData
    复用已有数据目录（aes.key、数据库），工作区仍会重建。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\resume-e2e.ps1
#>
[CmdletBinding()]
param(
    [int]$Port = 18082,
    [int]$ModelPort = 18083,
    [string]$DataDir,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCmd,
    [switch]$KeepData
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $DataDir) { $DataDir = Join-Path $repo 'build\resume-e2e\data' }
if (-not [System.IO.Path]::IsPathRooted($DataDir)) { $DataDir = Join-Path $repo $DataDir }

$workRoot = Split-Path $DataDir -Parent
$workspace = Join-Path $workRoot 'workspace'
$modelLog = Join-Path $workRoot 'model-requests.jsonl'
$modelOutLog = Join-Path $workRoot 'fake-model.log'
$sseLog = Join-Path $workRoot 'sse.log'
$resumeSseLog = Join-Path $workRoot 'resume-sse.log'
$baseUrl = "http://127.0.0.1:$Port"
$modelUrl = "http://127.0.0.1:$ModelPort"
$fakeServer = Join-Path $repo 'scripts\fake-openai-server.mjs'
$promptText = '在工作区写 note.txt，内容 crash-resume-e2e'
$expectedContent = 'crash-resume-e2e'

$backendProcess = $null
$modelProcess = $null
$chatCurl = $null
$resumeCurl = $null
$localToken = $null
$backendLogs = New-Object System.Collections.Generic.List[string]

function Write-Step($message) { Write-Host "[resume-e2e] $message" -ForegroundColor Cyan }

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        [string]$Token,
        [int]$TimeoutSec = 30
    )
    $headers = @{ Authorization = "Bearer $Token"; Accept = 'application/json' }
    $params = @{ Method = $Method; Uri = "$baseUrl$Path"; Headers = $headers; TimeoutSec = $TimeoutSec; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $params['ContentType'] = 'application/json; charset=utf-8'
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    return Invoke-RestMethod @params
}

function Get-RunEvents([string]$runId, [string]$token) {
    return (Invoke-Api -Method 'GET' -Path "/api/agent/runs/$runId/events" -Token $token).data
}

function Wait-Until {
    param(
        [scriptblock]$Condition,
        [int]$TimeoutSec = 60,
        [string]$What = '条件',
        [int]$IntervalMs = 400
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $result = & $Condition
        if ($result) { return $result }
        Start-Sleep -Milliseconds $IntervalMs
    }
    throw "等待超时（${TimeoutSec}s）：$What"
}

function Convert-Payload($payload) {
    if ($null -eq $payload) { return $null }
    if ($payload -is [string]) {
        if (-not $payload.Trim()) { return $null }
        try { return $payload | ConvertFrom-Json } catch { return $null }
    }
    return $payload
}

function Assert-PortFree([int]$p, [string]$label) {
    $listeners = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($listeners) {
        $owners = ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ','
        throw "$label 端口 $p 已被占用（PID: $owners），请先释放"
    }
}

function Wait-PortClosed([int]$p, [int]$TimeoutSec = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)) { return }
        Start-Sleep -Milliseconds 300
    }
    throw "端口 $p 在 ${TimeoutSec}s 内没有释放"
}

function Stop-Port([int]$p) {
    $listeners = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    foreach ($listener in $listeners) {
        Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
    }
}

function Stop-Tree($proc) {
    if ($proc -and -not $proc.HasExited) {
        & taskkill.exe /PID $proc.Id /T /F 2> $null | Out-Null
    }
}

function Start-Backend([string]$logPath) {
    $command = @(
        "`$env:JAVA_HOME = '$JavaHome'"
        "`$env:AES_MASTER_KEY = '$script:aesKey'"
        "`$env:DESKTOP_PG_RUNTIME = '$script:runtimePath'"
        "`$env:DESKTOP_DATA_DIR = '$DataDir'"
        "`$env:DESKTOP_SERVER_PORT = '$Port'"
        "`$env:AGENT_SPRING_AI_BASE_URL = '$modelUrl'"
        "`$env:AGENT_MODEL_IDLE_TIMEOUT_SECONDS = '600'"
        "`$env:AGENT_MODEL_MAX_ATTEMPTS = '1'"
        "`$env:AGENT_MODEL_CIRCUIT_THRESHOLD = '100'"
        "& '$MavenCmd' -o spring-boot:run '-Dspring-boot.run.profiles=desktop' 2>&1 | Tee-Object -FilePath '$logPath'"
    ) -join '; '
    return Start-Process -FilePath 'powershell.exe' -WorkingDirectory $repo `
        -ArgumentList @('-NoProfile', '-Command', $command) -PassThru -WindowStyle Hidden
}

function Start-FakeModel {
    $command = "`$env:FAKE_MODEL_PORT = '$ModelPort'; `$env:FAKE_MODEL_LOG = '$modelLog'; " +
        "`$env:FAKE_MODEL_NAME = 'deepseek-chat'; " +
        "& node '$fakeServer' 2>&1 | Tee-Object -FilePath '$modelOutLog'"
    return Start-Process -FilePath 'powershell.exe' -WorkingDirectory $repo `
        -ArgumentList @('-NoProfile', '-Command', $command) -PassThru -WindowStyle Hidden
}

function Start-CurlStream([string]$url, [string]$outFile, [string]$errFile, [string[]]$Extra) {
    $arguments = @('-N', '-sS', '-o', $outFile, '-X', 'POST', $url, '-H', "Authorization: Bearer $localToken") + $Extra
    # PowerShell 5 的 Start-Process 不会自动给含空格的参数加引号，头字段必须先自行包一层双引号
    $argumentLine = ($arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    return Start-Process -FilePath 'curl.exe' -ArgumentList $argumentLine -PassThru -WindowStyle Hidden `
        -RedirectStandardError $errFile
}

function Wait-Health([string]$TokenPath, [string]$LogPath, [int]$TimeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 800
        if (Test-Path $LogPath) {
            $text = Get-Content $LogPath -Raw -ErrorAction SilentlyContinue
            if ($text -match 'APPLICATION FAILED TO START|Application run failed|Error starting ApplicationContext|Web server failed to start|already in use') {
                throw "后端启动失败，详见 $LogPath"
            }
        }
        if (-not (Test-Path $TokenPath)) { continue }
        $token = (Get-Content $TokenPath -Raw).Trim()
        try {
            $health = Invoke-RestMethod -Uri "$baseUrl/api/health" -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 2
            if ($health.status -eq 'UP') { return $token }
        } catch {
            # 尚未就绪，继续等
        }
    }
    throw "后端 ${TimeoutSec}s 内没有就绪（$baseUrl/api/health）"
}

function Show-Failure([string]$message) {
    Write-Host ''
    foreach ($log in $backendLogs) {
        Write-Host "----- $log 末尾 -----" -ForegroundColor Yellow
        Get-Content $log -Tail 80 -ErrorAction SilentlyContinue
    }
    if (Test-Path $modelLog) {
        Write-Host '----- model-requests.jsonl 末尾 -----' -ForegroundColor Yellow
        Get-Content $modelLog -Tail 6 -ErrorAction SilentlyContinue
    }
    foreach ($file in @($sseLog, $resumeSseLog)) {
        if (Test-Path $file) {
            Write-Host "----- $file 末尾 -----" -ForegroundColor Yellow
            Get-Content $file -Tail 30 -ErrorAction SilentlyContinue
        }
    }
    Write-Host '------------------------------------' -ForegroundColor Yellow
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
    $script:runtimePath = $runtime.FullName
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw '找不到 node：假模型服务需要 Node.js'
    }
    if (-not (Test-Path $fakeServer)) {
        throw "找不到假模型脚本：$fakeServer"
    }
    $fixture = Join-Path $repo 'cli\test\fixtures\workspace'
    if (-not (Test-Path $fixture)) {
        throw "找不到工作区 fixture：$fixture"
    }

    Assert-PortFree $Port '后端'
    Assert-PortFree $ModelPort '假模型'

    # ---------- 干净数据目录与工作区 ----------
    if (Test-Path $DataDir) {
        if ($KeepData) {
            Write-Step "复用数据目录：$DataDir"
        } else {
            Remove-Item $DataDir -Recurse -Force
        }
    }
    New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
    New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
    if (Test-Path $workspace) { Remove-Item $workspace -Recurse -Force }
    Copy-Item $fixture $workspace -Recurse
    Remove-Item $modelLog, $modelOutLog, $sseLog, $resumeSseLog -Force -ErrorAction SilentlyContinue

    $keyFile = Join-Path $DataDir 'aes.key'
    $script:aesKey = if ($env:AES_MASTER_KEY) {
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

    # ---------- 启动假模型与后端 ----------
    Write-Step "启动假模型：$modelUrl（crash 模式，日志 $modelOutLog）"
    $modelProcess = Start-FakeModel
    $null = Wait-Until -TimeoutSec 30 -What '假模型端口监听' -Condition {
        Get-NetTCPConnection -LocalPort $ModelPort -State Listen -ErrorAction SilentlyContinue
    }
    Write-Step '假模型已就绪'

    $backendLog = Join-Path $DataDir 'backend-before-crash.log'
    $backendLogs.Add($backendLog)
    Write-Step "启动后端：$baseUrl（日志 $backendLog）"
    $backendProcess = Start-Backend $backendLog
    $localToken = Wait-Health -TokenPath (Join-Path $DataDir 'local-token') -LogPath $backendLog
    Write-Step '后端已就绪'

    # ---------- 种子模型 Key / 项目 / 工作区 ----------
    Write-Step '注入模型 Key（DEEPSEEK）'
    Invoke-Api -Method 'POST' -Path '/api/keys' -Body @{ provider = 'DEEPSEEK'; apiKey = 'sk-e2e-dummy' } -Token $localToken | Out-Null

    Write-Step "绑定工作区：$workspace"
    $project = Invoke-Api -Method 'POST' -Path '/api/projects' -Body @{ name = 'resume-e2e' } -Token $localToken
    $projectId = $project.data.id
    Invoke-Api -Method 'POST' -Path "/api/projects/$projectId/workspace/set-path" `
        -Body @{ path = $workspace } -Token $localToken | Out-Null

    # ---------- 发起聊天流 ----------
    $bodyFile = Join-Path $workRoot 'chat-body.json'
    $bodyJson = @{
        prompt     = $promptText
        projectId  = $projectId
        useRag     = $false
        permission = 'auto'
        reasoning  = 'fast'
    } | ConvertTo-Json -Depth 4 -Compress
    [System.IO.File]::WriteAllText($bodyFile, $bodyJson, (New-Object System.Text.UTF8Encoding($false)))

    Write-Step "发起聊天流（输出 $sseLog）"
    $chatCurl = Start-CurlStream -url "$baseUrl/api/agent/chat/stream" -outFile $sseLog `
        -errFile (Join-Path $workRoot 'chat-curl.err') `
        -Extra @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile")

    $sessionId = $null
    $runId = $null
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline -and (-not $sessionId -or -not $runId)) {
        if (Test-Path $sseLog) {
            $text = Get-Content $sseLog -Raw -ErrorAction SilentlyContinue
            if ($text) {
                if (-not $sessionId) {
                    $m = [regex]::Match($text, 'event:\s*session_id\s*\r?\ndata:\s*(\d+)')
                    if ($m.Success) { $sessionId = $m.Groups[1].Value }
                }
                if (-not $runId) {
                    $m = [regex]::Match($text, 'event:\s*run_id\s*\r?\ndata:\s*(\d+)')
                    if ($m.Success) { $runId = $m.Groups[1].Value }
                }
            }
        }
        Start-Sleep -Milliseconds 300
    }
    if (-not $sessionId) { throw "未能从 $sseLog 解析 sessionId" }
    if (-not $runId) {
        $running = (Invoke-Api -Method 'GET' -Path '/api/agent/runs/running' -Token $localToken).data
        $match = @($running) | Where-Object { [string]$_.sessionId -eq [string]$sessionId } | Select-Object -First 1
        if ($match) { $runId = [string]$match.runId }
    }
    if (-not $runId) { throw "未能解析 runId（sse.log: $sseLog）" }
    Write-Step "run 已创建：sessionId=$sessionId runId=$runId"

    # ---------- 等待 CHECKPOINT 落库 ----------
    Write-Step '等待 CHECKPOINT 事件（超时 120s）'
    $checkpoint = Wait-Until -TimeoutSec 120 -What 'CHECKPOINT 事件' -Condition {
        $data = Get-RunEvents $runId $localToken
        if ($data.state -eq 'FAILED' -or $data.state -eq 'SUCCEEDED' -or $data.state -eq 'CANCELLED') {
            throw "run 在 CHECKPOINT 之前进入终态：state=$($data.state) errorCode=$($data.errorCode)"
        }
        $event = @($data.events) | Where-Object { $_.type -eq 'CHECKPOINT' } | Select-Object -First 1
        if (-not $event) { return $false }
        return (Convert-Payload $event.payload)
    }
    $checkpointRound = [int]$checkpoint.round
    Write-Step "CHECKPOINT 已落库：round=$checkpointRound messages=$($checkpoint.messages) bytes=$($checkpoint.bytes)"

    # 等第二次模型调用真正卡住（连接已建立、无数据），模拟更真实的故障窗口
    try {
        $null = Wait-Until -TimeoutSec 20 -IntervalMs 300 -What '假模型收到第二次流式请求' -Condition {
            if (-not (Test-Path $modelLog)) { return $false }
            $entries = @(Get-Content $modelLog -ErrorAction SilentlyContinue | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
            $streams = @($entries | Where-Object { ([string]$_.url) -like '*/chat/completions' -and $_.body.stream -eq $true })
            return ($streams.Count -ge 2)
        }
        Write-Step '假模型第二次流式请求已卡住（不返回数据）'
    } catch {
        Write-Step '警告：未观察到第二次模型请求，仍继续真实崩溃'
    }

    # ---------- 真实崩溃 ----------
    Write-Step "真实崩溃：taskkill /PID $($backendProcess.Id) /T /F"
    & taskkill.exe /PID $backendProcess.Id /T /F 2> $null | Out-Null
    Wait-PortClosed -p $Port -TimeoutSec 30
    Write-Step "后端进程已消失，端口 $Port 已释放"
    if ($chatCurl -and -not $chatCurl.HasExited) {
        Stop-Process -Id $chatCurl.Id -Force -ErrorAction SilentlyContinue
    }

    # 内嵌 PG 是独立子进程，taskkill /T 后不保证立刻消失；postmaster.pid 里 PID 仍存活时，
    # 后端启动自检会判定数据目录被占用。先等它自然退出，仍在则强制结束这条残留进程树。
    $pgPidFile = Join-Path $DataDir 'db\postmaster.pid'
    $pgPid = 0
    if (Test-Path $pgPidFile) {
        try { $pgPid = [int]((Get-Content $pgPidFile -TotalCount 1).Trim()) } catch { $pgPid = 0 }
    }
    $pgProc = if ($pgPid -gt 0) { Get-Process -Id $pgPid -ErrorAction SilentlyContinue } else { $null }
    if ($pgProc -and $pgProc.ProcessName -ne 'postgres') {
        Write-Step "警告：postmaster.pid 的 PID $pgPid 已被其他进程复用（$($pgProc.ProcessName)），不做清理"
    } elseif ($pgProc) {
        $graceDeadline = (Get-Date).AddSeconds(15)
        while ((Get-Date) -lt $graceDeadline -and (Get-Process -Id $pgPid -ErrorAction SilentlyContinue)) {
            Start-Sleep -Milliseconds 500
        }
        if (Get-Process -Id $pgPid -ErrorAction SilentlyContinue) {
            Write-Step "清理崩溃后残留的内嵌 PG 进程树：taskkill /PID $pgPid /T /F"
            & taskkill.exe /PID $pgPid /T /F 2> $null | Out-Null
            $killDeadline = (Get-Date).AddSeconds(30)
            while ((Get-Date) -lt $killDeadline -and (Get-Process -Id $pgPid -ErrorAction SilentlyContinue)) {
                Start-Sleep -Milliseconds 300
            }
        }
    }
    if ($pgPid -gt 0 -and (Get-Process -Id $pgPid -ErrorAction SilentlyContinue)) {
        Write-Step '警告：内嵌 PG 进程仍未退出，继续尝试重启（可能被启动自检拒绝）'
    } else {
        Write-Step '崩溃进程树（含内嵌 PG）已完全退出'
    }

    # ---------- 同一数据目录重启 ----------
    $backendLog = Join-Path $DataDir 'backend-after-restart.log'
    $backendLogs.Add($backendLog)
    Write-Step "重启后端（同一数据目录，日志 $backendLog）"
    $backendProcess = Start-Backend $backendLog
    $localToken = Wait-Health -TokenPath (Join-Path $DataDir 'local-token') -LogPath $backendLog
    Write-Step '后端已重启并就绪'

    $afterCrash = Wait-Until -TimeoutSec 30 -What '重启后 run 变为 FAILED/PROCESS_RESTARTED' -Condition {
        $data = Get-RunEvents $runId $localToken
        if ($data.state -eq 'FAILED' -and $data.errorCode -eq 'PROCESS_RESTARTED') { return $data }
        return $false
    }
    if (-not $afterCrash.resumable) {
        throw "重启后 run 不可续跑：state=$($afterCrash.state) errorCode=$($afterCrash.errorCode)"
    }
    Write-Step "崩溃后状态：state=$($afterCrash.state) errorCode=$($afterCrash.errorCode) resumable=$($afterCrash.resumable)"

    $resumable = (Invoke-Api -Method 'GET' -Path "/api/agent/sessions/$sessionId/resumable-run" -Token $localToken).data
    if (-not $resumable.resumable -or [string]$resumable.runId -ne [string]$runId) {
        throw "resumable-run 端点不符：$($resumable | ConvertTo-Json -Compress)"
    }
    Write-Step "resumable-run 端点：resumable=$($resumable.resumable) runId=$($resumable.runId) round=$($resumable.round) errorCode=$($resumable.errorCode)"

    # ---------- 切换到 finish 并续跑 ----------
    Write-Step '切换假模型为 finish 模式'
    $null = Invoke-RestMethod -Method 'POST' -Uri "$modelUrl/__mode" -ContentType 'application/json' `
        -Body '{"mode":"finish"}' -TimeoutSec 5

    Write-Step "POST /api/agent/runs/$runId/resume（输出 $resumeSseLog）"
    $resumeCurl = Start-CurlStream -url "$baseUrl/api/agent/runs/$runId/resume" -outFile $resumeSseLog `
        -errFile (Join-Path $workRoot 'resume-curl.err') -Extra @()

    # 续跑请求刚发出时，后端可能还没把 FAILED 切成 RUNNING；必须先看到运行态，
    # 之后的 FAILED/CANCELLED 才能判定为续跑失败，避免误读崩溃遗留的 FAILED。
    $script:resumeStarted = $false
    $final = Wait-Until -TimeoutSec 120 -What '续跑结束且 state=SUCCEEDED' -Condition {
        $data = Get-RunEvents $runId $localToken
        if ($data.state -eq 'SUCCEEDED') { return $data }
        if ($data.state -ne 'FAILED' -and $data.state -ne 'CANCELLED') {
            $script:resumeStarted = $true
            return $false
        }
        if ($script:resumeStarted) {
            throw "续跑失败：state=$($data.state) errorCode=$($data.errorCode)"
        }
        return $false
    }
    Write-Step "续跑后状态：state=$($final.state) totalTokens=$($final.totalTokens)"
    if ($resumeCurl) { $null = $resumeCurl.WaitForExit(15000) }

    # ---------- 断言 ----------
    if ($checkpointRound -ne 1) { throw "CHECKPOINT round 应为 1，实际 $checkpointRound" }
    if ([int]$resumable.round -ne 1) { throw "resumable-run round 应为 1，实际 $($resumable.round)" }

    $notePath = Join-Path $workspace 'note.txt'
    if (-not (Test-Path $notePath)) { throw "续跑后没有 note.txt：$notePath" }
    $noteContent = Get-Content $notePath -Raw -Encoding UTF8
    if ($noteContent -cne $expectedContent) {
        throw "note.txt 内容不符：期望 [$expectedContent]，实际 [$noteContent]"
    }
    Write-Step "note.txt 校验通过：内容 = $expectedContent"

    if (-not (Test-Path $modelLog)) { throw "假模型请求日志不存在：$modelLog" }
    $entries = @(Get-Content $modelLog -Encoding UTF8 | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    $chatRequests = @($entries | Where-Object { ([string]$_.url) -match '/chat/completions$' })
    if ($chatRequests.Count -lt 2) {
        throw "假模型只收到 $($chatRequests.Count) 次聊天请求，无法比对续跑快照"
    }
    $lastRequest = $chatRequests[$chatRequests.Count - 1]
    $messages = @($lastRequest.body.messages)
    $toolMessages = @($messages | Where-Object { $_.role -eq 'tool' })
    if ($toolMessages.Count -ne 1) {
        throw "续跑请求里 role=tool 的消息应为 1 条，实际 $($toolMessages.Count)"
    }
    # write_file 的 tool 结果只有「已写入文件：note.txt」，写入内容在 assistant 的 tool_calls 参数里；
    # 通过整条快照 + write_file 参数双重校验，确认用的是 checkpoint 快照而非重建历史。
    $lastBodyJson = $lastRequest.body | ConvertTo-Json -Depth 20 -Compress
    if ($lastBodyJson -notlike "*$expectedContent*") {
        throw "续跑请求快照里不含 $expectedContent"
    }
    $writeCallHit = @($messages | Where-Object {
            $_.role -eq 'assistant' -and
            @($_.tool_calls | Where-Object { $_.function.name -eq 'write_file' -and ([string]$_.function.arguments) -like "*$expectedContent*" }).Count -gt 0
        }).Count
    if ($writeCallHit -lt 1) {
        throw "续跑请求快照里没有携带 $expectedContent 的 write_file 调用"
    }
    $promptHits = @($messages | Where-Object { $_.role -eq 'user' -and ([string]$_.content) -like '*在工作区写 note.txt*' }).Count
    if ($promptHits -lt 1) {
        throw '续跑请求里没有原始用户 prompt，无法证明使用了 checkpoint 快照'
    }
    Write-Step "续跑请求 messages 校验通过：共 $($messages.Count) 条，role=tool 1 条，write_file 参数含 $expectedContent，原始 prompt 命中"

    $assistantTextOk = $false
    if (Test-Path $resumeSseLog) {
        # 正文是分多个 SSE content 事件流式输出的，需要先拼接再断言
        $resumeSse = Get-Content $resumeSseLog -Raw -Encoding UTF8
        $pieces = @([regex]::Matches($resumeSse, 'event:content\s*\r?\ndata:\s*(\{[^\r\n]*\})') | ForEach-Object {
                try { [string](($_.Groups[1].Value | ConvertFrom-Json).text) } catch { '' }
            })
        if (($pieces -join '') -like '*续跑完成*') { $assistantTextOk = $true }
    }
    if (-not $assistantTextOk) {
        $history = (Invoke-Api -Method 'GET' -Path "/api/agent/sessions/$sessionId/messages" -Token $localToken).data
        $assistantTextOk = @($history | Where-Object {
                ([string]$_.role).ToUpper() -eq 'ASSISTANT' -and ([string]$_.content) -like '*续跑完成*'
            }).Count -gt 0
    }
    if (-not $assistantTextOk) { throw '续跑输出里没有「续跑完成」（SSE 与历史消息均未命中）' }
    Write-Step '续跑输出校验通过：包含「续跑完成」'

    Write-Host ''
    Write-Step '通过'
    Write-Step "runId=$runId checkpointRound=$checkpointRound"
    Write-Step "崩溃前/后：RUNNING -> state=$($afterCrash.state) errorCode=$($afterCrash.errorCode) resumable=$($afterCrash.resumable)"
    Write-Step "续跑后：state=$($final.state)"
    Write-Step "note.txt=$expectedContent；续跑请求 role=tool=$($toolMessages.Count)；总消息=$($messages.Count)"
    exit 0
} catch {
    $detail = $_.Exception.Message
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $detail += "`n" + $_.ErrorDetails.Message }
    Show-Failure $detail
    exit 1
} finally {
    if ($chatCurl -and -not $chatCurl.HasExited) { Stop-Process -Id $chatCurl.Id -Force -ErrorAction SilentlyContinue }
    if ($resumeCurl -and -not $resumeCurl.HasExited) { Stop-Process -Id $resumeCurl.Id -Force -ErrorAction SilentlyContinue }
    Stop-Tree $backendProcess
    Stop-Tree $modelProcess
    Stop-Port $Port
    Stop-Port $ModelPort
    Start-Sleep -Milliseconds 500
    $left = Get-NetTCPConnection -LocalPort $Port, $ModelPort -State Listen -ErrorAction SilentlyContinue
    if ($left) {
        $ports = ($left | ForEach-Object { $_.LocalPort } | Sort-Object -Unique) -join ','
        Write-Host "[resume-e2e] 警告：端口仍有监听：$ports" -ForegroundColor Yellow
    } else {
        Write-Host "[resume-e2e] 已释放端口 $Port/$ModelPort" -ForegroundColor DarkGray
    }
}
