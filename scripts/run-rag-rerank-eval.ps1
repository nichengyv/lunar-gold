<#
P5-07-3 难例检索与 reranker 对照评测。

前置要求（缺失时脚本直接退出，不启动 Maven、不写报告、不改基线）：
  DESKTOP_PG_RUNTIME        含 pgvector 的内嵌 PostgreSQL 运行时
  RAG_EVAL_EMBEDDING_KEY    真实 embedding Key（回退 EMBEDDING_API_KEY）
  RAG_EVAL_RERANK_KEY       真实 reranker Key（SiliconFlow /v1/rerank）

用法：
  powershell -ExecutionPolicy Bypass -File scripts/run-rag-rerank-eval.ps1
  powershell -ExecutionPolicy Bypass -File scripts/run-rag-rerank-eval.ps1 -UpdateBaseline
#>
param(
    [switch]$UpdateBaseline
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Resolve-Maven {
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvn) { return $mvn.Source }
    $wrapper = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Filter mvn.cmd -Recurse -Depth 5 -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($wrapper) { return $wrapper }
    throw '未找到 mvn，请将 Maven 加入 PATH 或安装 Maven Wrapper'
}

function Resolve-PgRuntime {
    if ($env:DESKTOP_PG_RUNTIME -and (Test-Path $env:DESKTOP_PG_RUNTIME)) {
        return (Resolve-Path $env:DESKTOP_PG_RUNTIME).Path
    }
    $candidate = Get-ChildItem "build\pg-runtime" -Filter "pg-runtime-*.txz" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($candidate) { return (Resolve-Path $candidate).Path }
    return $null
}

$pgRuntime = Resolve-PgRuntime
if (-not $pgRuntime) {
    Write-Host '[P5-07-3] 前置缺口：DESKTOP_PG_RUNTIME 未设置，且 build\pg-runtime 下没有运行时压缩包' -ForegroundColor Red
    Write-Host '  需要：含 pgvector 的 postgres 运行时（.txz），例如 build\pg-runtime\pg-runtime-windows-x64-pg16.11.0-vector0.8.7.txz'
    exit 2
}

$embeddingKey = $env:RAG_EVAL_EMBEDDING_KEY
if (-not $embeddingKey) { $embeddingKey = $env:EMBEDDING_API_KEY }
$rerankKey = $env:RAG_EVAL_RERANK_KEY

$missing = @()
if (-not $embeddingKey) { $missing += 'RAG_EVAL_EMBEDDING_KEY（或 EMBEDDING_API_KEY）' }
if (-not $rerankKey) { $missing += 'RAG_EVAL_RERANK_KEY' }
if ($missing.Count -gt 0) {
    Write-Host '[P5-07-3] 前置缺口：缺少真实模型凭据，本次不运行' -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  - $_" }
    Write-Host '  需要：SiliconFlow（或兼容 OpenAI 结构）embedding 与 /v1/rerank 的可用 Key'
    Write-Host '  未运行 Maven、未写报告、未改基线。'
    exit 2
}

$env:DESKTOP_PG_RUNTIME = $pgRuntime
$mvn = Resolve-Maven
$args = @('-Dtest=RagRerankEvalRunner,RagRerankEvalGateTest', 'test')
if ($UpdateBaseline) {
    $args = @('-Deval.updateBaseline=true') + $args
}

Write-Host "[P5-07-3] embedding=$($env:RAG_EVAL_EMBEDDING_MODEL) reranker=$($env:RAG_EVAL_RERANK_MODEL)"
Write-Host "[P5-07-3] pgRuntime=$pgRuntime"
& $mvn @args
exit $LASTEXITCODE
