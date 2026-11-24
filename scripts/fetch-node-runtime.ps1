# 下载官方 Node.js（默认 v22 LTS）到 build/node-runtime，供后端 spawn MCP server 使用。
# 打包时该目录会作为 extraResources 进入安装包 resources/node-runtime。
param([string]$Version)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $repo 'build\node-runtime'

if (-not $Version) {
    $index = Invoke-RestMethod 'https://nodejs.org/dist/index.json' -TimeoutSec 60
    $Version = ($index | Where-Object { $_.version -like 'v22.*' -and $_.files -contains 'win-x64-zip' } |
        Select-Object -First 1).version
}
if (-not $Version) { throw '无法从 nodejs.org 确定 v22 版本号' }

$file = "node-$Version-win-x64.zip"
$url = "https://nodejs.org/dist/$Version/$file"
$tmp = Join-Path $env:TEMP "lunargold-$file"
$extract = Join-Path $env:TEMP 'lunargold-node-extract'

Write-Host "[node-runtime] downloading $url"
Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing -TimeoutSec 600

if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
Expand-Archive -Path $tmp -DestinationPath $extract
$src = Get-ChildItem $extract -Directory | Select-Object -First 1
if (-not $src) { throw '解压后找不到 node 目录' }

if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
Move-Item $src.FullName $dest
Remove-Item $tmp -Force
Remove-Item $extract -Recurse -Force

$node = Join-Path $dest 'node.exe'
$npxCli = Join-Path $dest 'node_modules\npm\bin\npx-cli.js'
Write-Host "[node-runtime] node $(& $node --version)"
Write-Host "[node-runtime] npx $(& $node $npxCli --version)"
Write-Host "[node-runtime] ready -> $dest"
