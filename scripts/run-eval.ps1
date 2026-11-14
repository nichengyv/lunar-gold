<#
.SYNOPSIS
    Run component and real ReAct runtime evaluations against their committed baselines.

.DESCRIPTION
    Runs EvalGate and RuntimeEvalGate. A non-zero exit code means quality, rounds,
    tokens, or p95 latency regressed beyond the allowed threshold. Use -UpdateBaseline
    only after reviewing and accepting the new metrics.

.PARAMETER MavenCmd
    Path to mvn.cmd. If omitted, PATH and MAVEN_HOME are checked.

.PARAMETER UpdateBaseline
    Update both eval/baselines/baseline.json and runtime-baseline.json after review.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run-eval.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run-eval.ps1 -UpdateBaseline
#>
[CmdletBinding()]
param(
    [string]$MavenCmd,
    [switch]$UpdateBaseline
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent

if (-not $MavenCmd) {
    $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($onPath) {
        $MavenCmd = $onPath.Source
    } elseif ($env:MAVEN_HOME) {
        $MavenCmd = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
    }
}
if (-not $MavenCmd -or -not (Test-Path $MavenCmd)) {
    throw 'mvn.cmd not found. Add Maven to PATH, set MAVEN_HOME, or use -MavenCmd.'
}

$mavenArgs = @('--batch-mode', '--no-transfer-progress', 'test', '-Dtest=EvalGate,RuntimeEvalGate')
if ($UpdateBaseline) {
    $mavenArgs += '-Deval.updateBaseline=true'
}

Push-Location $repo
try {
    & $MavenCmd @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Eval gate failed. Maven exit code: $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
