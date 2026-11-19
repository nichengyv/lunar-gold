<#
.SYNOPSIS
    Run the offline RAG retrieval evaluation and its gate.

.DESCRIPTION
    Runs RagRetrievalEvalRunner and RagEvalGateTest against a real embedded PostgreSQL
    with pgvector + pg_trgm. DESKTOP_PG_RUNTIME must point to the runtime produced by
    scripts/build-pg-runtime.ps1; when it is not set, the script looks for a packaged
    runtime under build/pg-runtime.

    Set RAG_EVAL_EMBEDDING_KEY to evaluate with a real embedding model instead of the
    deterministic simulated vectors. Reports are written to eval/rag-reports (ignored).

.PARAMETER MavenCmd
    Path to mvn.cmd. If omitted, PATH and MAVEN_HOME are checked.

.PARAMETER UpdateBaseline
    Update eval/baselines/rag-baseline.json after reviewing the reported metrics.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run-rag-eval.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run-rag-eval.ps1 -UpdateBaseline
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

if (-not $env:DESKTOP_PG_RUNTIME) {
    $runtimeDir = Join-Path $repo 'build\pg-runtime'
    $runtime = Get-ChildItem -Path $runtimeDir -Filter 'pg-runtime-*.txz' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $runtime) {
        throw 'DESKTOP_PG_RUNTIME is not set and no build\pg-runtime\pg-runtime-*.txz was found. Run scripts\build-pg-runtime.ps1 first.'
    }
    $env:DESKTOP_PG_RUNTIME = $runtime.FullName
}

$mavenArgs = @('--batch-mode', '--no-transfer-progress', 'test', '-Dtest=RagRetrievalEvalRunner,RagEvalGateTest')
if ($UpdateBaseline) {
    $mavenArgs += '-Deval.updateBaseline=true'
}

Push-Location $repo
try {
    & $MavenCmd @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "RAG eval gate failed. Maven exit code: $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
