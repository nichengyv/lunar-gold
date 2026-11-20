<#
P5-04 代码索引收益评测。

阶段 A（默认，不需要任何凭据）：合成 800 文件工作区，测冷/热/增量扫描、
      repo map 预算占用、索引确定性与符号抽取准确率。
阶段 B（-RealModel，需要真实模型 Key）：repo map 开/关两配置的真实模型 A/B，
      只切 agent.repo-map.enabled，模型/提示词/工具/权限完全一致。

前置要求（缺失时脚本直接退出，不启动 Maven、不写报告）：
  P5_04_MODEL_KEY       真实模型 Key（仅 -RealModel 需要）
可选：
  P5_04_MODEL_BASE_URL  默认 https://api.deepseek.com
  P5_04_MODEL_NAME      默认 deepseek-chat
  P5_04_REPEATS         每用例重复次数，默认 3
  P5_04_MAX_CASES       只跑前 N 条用例（冒烟用，会触发数据集形态门禁失败）

用法：
  powershell -ExecutionPolicy Bypass -File scripts/run-index-eval.ps1
  powershell -ExecutionPolicy Bypass -File scripts/run-index-eval.ps1 -RealModel
#>
param(
    [switch]$RealModel
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

# 环境变量缺失时回退到本机密钥文件（已被 .gitignore 忽略）；只读入变量，不打印内容
$keyFile = Join-Path $root '.secrets\p5-04-key.txt'
if (-not $env:P5_04_MODEL_KEY -and (Test-Path $keyFile)) {
    $env:P5_04_MODEL_KEY = (Get-Content $keyFile -Raw).Trim()
}

if ($RealModel -and -not $env:P5_04_MODEL_KEY) {
    Write-Host '[P5-04] 前置缺口：-RealModel 需要 P5_04_MODEL_KEY，本次不运行阶段 B' -ForegroundColor Red
    Write-Host '  需要：OpenAI 兼容 provider 的可用 Key（默认 DeepSeek）'
    Write-Host '  未运行 Maven、未写报告。'
    exit 2
}

$mvn = Resolve-Maven
$tests = 'IndexBenefitEvalTest'
if ($RealModel) {
    $tests = 'IndexBenefitEvalTest,IndexAbEvalTest'
    $repeats = if ($env:P5_04_REPEATS) { $env:P5_04_REPEATS } else { '3' }
    Write-Host "[P5-04] 阶段 B：model=$($env:P5_04_MODEL_NAME) baseUrl=$($env:P5_04_MODEL_BASE_URL) repeats=$repeats"
} else {
    Write-Host '[P5-04] 阶段 A：离线索引基准（不需要模型凭据）'
}

& $mvn "-Dtest=$tests" test
$code = $LASTEXITCODE
Write-Host "[P5-04] 报告目录：eval\index-reports"
exit $code
