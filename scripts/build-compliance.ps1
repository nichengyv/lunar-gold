<#
.SYNOPSIS
    生成桌面发布所需的 SBOM、第三方许可文本和合规文件。
.DESCRIPTION
    输出固定为 build/legal（可用 -OutputDir 覆盖）。所有中间文件位于 build/compliance-work，
    不修改 uploads、开发数据库或用户数据目录。任一生成步骤失败都会中止发布构建。
#>
[CmdletBinding()]
param(
    [string]$MavenCmd,
    [string]$OutputDir
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$buildDir = Join-Path $repo 'build'
if (-not $OutputDir) { $OutputDir = Join-Path $buildDir 'legal' }
$work = Join-Path $buildDir 'compliance-work'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$script:licenseSections = @()

function Write-Step($message) { Write-Host "[compliance] $message" -ForegroundColor Cyan }
function Assert-NativeSuccess($message) {
    if ($LASTEXITCODE -ne 0) { throw "$message（退出码 $LASTEXITCODE）" }
}
function Assert-File($path, $message) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$message：$path" }
    if ((Get-Item -LiteralPath $path).Length -eq 0) { throw "$message（空文件）：$path" }
}
function Get-SafeFileName($name) {
    return ($name -replace '[^\w\.\-]+', '_')
}
function Write-Utf8($path, $content) {
    [System.IO.File]::WriteAllText($path, $content, $utf8)
}
function Copy-Required($name) {
    $source = Join-Path $repo $name
    Assert-File $source '缺少合规源文件'
    Copy-Item -LiteralPath $source -Destination (Join-Path $OutputDir $name) -Force
}
function Add-LicenseText($category, $name, $content) {
    if ([string]::IsNullOrWhiteSpace($content)) { return }
    $script:licenseSections += "================================================================================`n$category :: $name`n================================================================================`n$content"
}
function Add-LicenseFiles($path, $category) {
    if (-not (Test-Path -LiteralPath $path -PathType Container)) { throw "许可目录不存在：$path" }
    $files = @(Get-ChildItem -LiteralPath $path -File -Recurse | Sort-Object FullName)
    if ($files.Count -eq 0) { throw "许可目录为空：$path" }
    foreach ($file in $files) {
        Add-LicenseText $category $file.FullName.Substring($path.Length).TrimStart('\') (Get-Content -LiteralPath $file.FullName -Raw)
    }
}
function Invoke-NpmSbom($directory, $output) {
    Write-Step "生成 npm SBOM：$(Split-Path $directory -Leaf)"
    Push-Location $directory
    try {
        $json = & npm.cmd sbom --omit dev --package-lock-only --sbom-format cyclonedx --sbom-type application
        Assert-NativeSuccess 'npm sbom 失败'
        Write-Utf8 $output ($json -join "`n")
    } finally { Pop-Location }
    Assert-File $output 'npm SBOM 未生成'
}
function Invoke-Download($uri, $outFile, $description) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $uri -OutFile $outFile -TimeoutSec 60
            Assert-File $outFile $description
            return
        } catch {
            if ($attempt -eq 3) { throw "$description（$uri）：$($_.Exception.Message)" }
            Start-Sleep -Seconds 3
        }
    }
}
function Get-SpdxLicenseText($id) {
    $cacheDir = Join-Path $work 'spdx'
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
    $file = Join-Path $cacheDir "$id.txt"
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        Write-Step "下载 SPDX 许可文本：$id"
        Invoke-Download "https://cdn.jsdelivr.net/gh/spdx/license-list-data@v3.27.0/text/$id.txt" $file "无法获取 SPDX 许可文本 $id"
    }
    return Get-Content -LiteralPath $file -Raw
}
function Invoke-NpmLicenses($directory, $reportPath, $filesDir) {
    Write-Step "提取 npm 许可：$(Split-Path $directory -Leaf)"
    New-Item -ItemType Directory -Force -Path $filesDir | Out-Null
    Push-Location $directory
    try {
        & npx.cmd --yes license-checker-rseidelsohn@4.4.2 --production --json --out $reportPath --files $filesDir
        Assert-NativeSuccess 'npm 许可提取失败'
    } finally { Pop-Location }
    Assert-File $reportPath 'npm 许可报告未生成'
    # 本项目自身的私有包不进入第三方许可合集
    $ownPackage = (Get-Content -LiteralPath (Join-Path $directory 'package.json') -Raw | ConvertFrom-Json).name
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    $expected = 0
    foreach ($property in $report.PSObject.Properties) {
        if ($property.Name -like "$ownPackage@*") { continue }
        $entry = $property.Value
        if ($entry.licenseFile) {
            $expected++
        } elseif ($entry.licenseText) {
            Add-LicenseText 'npm inline license' $property.Name $entry.licenseText
        } elseif ($entry.licenses -and $entry.licenses -match '^[A-Za-z0-9.+()-]+$') {
            # 少数包未随 npm 包分发许可文件，按声明的 SPDX 标识补充官方文本。
            Add-LicenseText "npm $($entry.licenses) license" $property.Name (Get-SpdxLicenseText $entry.licenses)
        } else {
            throw "npm 组件缺少许可文本：$($property.Name)"
        }
    }
    # 带 scope 的包会被复制到子目录（如 @vue/），必须递归计数
    $copied = @(Get-ChildItem -LiteralPath $filesDir -File -Recurse)
    if ($copied.Count -lt $expected) {
        throw "npm 许可文件不完整：应有 $expected，实际 $($copied.Count)（$directory）"
    }
    Add-LicenseFiles $filesDir "npm $(Split-Path $directory -Leaf)"
}
function Get-HashObject($path) {
    return @(@{ alg = 'SHA-256'; content = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() })
}

if (-not $MavenCmd) {
    $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($onPath) { $MavenCmd = $onPath.Source }
    if (-not $MavenCmd -and $env:MAVEN_HOME) { $MavenCmd = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' }
}
Assert-File $MavenCmd '找不到 mvn.cmd'
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw '找不到 npm.cmd' }
if (-not (Get-Command npx.cmd -ErrorAction SilentlyContinue)) { throw '找不到 npx.cmd' }

if (Test-Path -LiteralPath $OutputDir) { Remove-Item -LiteralPath $OutputDir -Recurse -Force }
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutputDir, $work | Out-Null

foreach ($name in @('LICENSE', 'NOTICE', 'NOTICE.en', 'PRIVACY.md', 'PRIVACY.en.md', 'TERMS.md', 'TERMS.en.md')) {
    Copy-Required $name
}

Write-Step '生成 Java SBOM'
Push-Location $repo
try {
    & $MavenCmd -B --no-transfer-progress org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom
    Assert-NativeSuccess 'Java SBOM 生成失败'
} finally { Pop-Location }
$javaSbom = Join-Path $repo 'SBOM.json'
Assert-File $javaSbom 'Java SBOM 未生成'
Copy-Item -LiteralPath $javaSbom -Destination (Join-Path $OutputDir 'SBOM.json') -Force

Write-Step '下载 Java 依赖许可与 NOTICE'
$javaLicenseDir = Join-Path $work 'java-licenses'
$javaLicenseReport = Join-Path $work 'java-licenses.xml'
Push-Location $repo
try {
    & $MavenCmd -B --no-transfer-progress org.codehaus.mojo:license-maven-plugin:2.7.1:aggregate-download-licenses `
        "-DlicensesOutputDirectory=$javaLicenseDir" "-DlicensesOutputFile=$javaLicenseReport"
    Assert-NativeSuccess 'Java 许可下载失败'
} finally { Pop-Location }
Assert-File $javaLicenseReport 'Java 许可映射未生成'
# 插件以 warn 运行以容忍单次网络抖动；这里逐项校验，并对未下载的许可证用固定地址重试。
# 任何组件最终仍没有本地许可文本时立即失败，不会带缺陷发布。
[xml]$javaLicenseDoc = Get-Content -LiteralPath $javaLicenseReport -Raw
$retried = 0
foreach ($dependency in $javaLicenseDoc.licenseSummary.dependencies.dependency) {
    $component = "$($dependency.groupId):$($dependency.artifactId):$($dependency.version)"
    foreach ($license in @($dependency.licenses.license)) {
        if (-not $license) { throw "Java 组件缺少许可证声明：$component" }
        if ($license.file -and (Test-Path -LiteralPath (Join-Path $javaLicenseDir $license.file) -PathType Leaf)) { continue }
        if (-not $license.url) { throw "Java 组件许可证缺少 URL：$component / $($license.name)" }
        $retried++
        $target = Join-Path $javaLicenseDir ('{0:D3} - {1}.txt' -f $retried, (Get-SafeFileName $license.name))
        Invoke-Download $license.url $target "Java 组件许可证无法下载：$component / $($license.name)"
    }
}
if ($retried -gt 0) { Write-Step "补全 Java 许可文本：$retried 项" }
Add-LicenseFiles $javaLicenseDir 'Java dependency license'
Add-LicenseText 'Java dependency license/copyright mapping' 'java-licenses.xml' (Get-Content -LiteralPath $javaLicenseReport -Raw)

Invoke-NpmSbom (Join-Path $repo 'frontend') (Join-Path $OutputDir 'SBOM.frontend.json')
Invoke-NpmSbom (Join-Path $repo 'desktop') (Join-Path $OutputDir 'SBOM.desktop.json')
Invoke-NpmLicenses (Join-Path $repo 'frontend') (Join-Path $work 'frontend-licenses.json') (Join-Path $work 'frontend-license-files')
Invoke-NpmLicenses (Join-Path $repo 'desktop') (Join-Path $work 'desktop-licenses.json') (Join-Path $work 'desktop-license-files')

Write-Step '生成实际桌面运行时 SBOM'
$electronDist = Join-Path $repo 'desktop\node_modules\electron\dist'
$electronExe = Join-Path $electronDist 'electron.exe'
$electronVersion = (Get-Content -LiteralPath (Join-Path $electronDist 'version') -Raw).Trim().TrimStart('v')
Assert-File $electronExe 'Electron 运行时缺失'
# 只取 Electron 的两份许可文本；dist 里还有 exe/dll 等二进制，不能整个目录当文本拼接。
foreach ($name in @('LICENSE', 'LICENSES.chromium.html')) {
    $licenseFile = Join-Path $electronDist $name
    Assert-File $licenseFile "Electron 许可文件缺失：$name"
    Add-LicenseText 'Electron distribution' $name (Get-Content -LiteralPath $licenseFile -Raw)
}

$jreDir = Join-Path $repo 'build\desktop-runtime\jre'
$jreJava = Join-Path $jreDir 'bin\java.exe'
$jreRelease = Get-Content -LiteralPath (Join-Path $jreDir 'release') -Raw
Assert-File $jreJava '裁剪 JRE 缺失'
if ($jreRelease -notmatch 'JAVA_VERSION="([^"]+)"') { throw '无法从 JRE release 读取版本' }
$jreVersion = $Matches[1]
$jreVendor = if ($jreRelease -match 'IMPLEMENTOR="([^"]+)"') { $Matches[1] } else { 'Unknown' }
Add-LicenseFiles (Join-Path $jreDir 'legal') 'Temurin JRE legal'

$pgDir = Join-Path $repo 'build\pg-runtime\work\pg'
$pgVersionText = (& (Join-Path $pgDir 'bin\postgres.exe') --version) -join ' '
Assert-NativeSuccess '无法读取 PostgreSQL 版本'
if ($pgVersionText -notmatch '(\d+\.\d+)') { throw "无法解析 PostgreSQL 版本：$pgVersionText" }
$pgVersion = $Matches[1]
$pgRuntime = Join-Path $repo 'build\pg-runtime\pg-runtime.txz'
Assert-File $pgRuntime 'PostgreSQL 运行时包缺失'
$vectorControl = Get-Content -LiteralPath (Join-Path $pgDir 'share\extension\vector.control') -Raw
if ($vectorControl -notmatch "default_version\s*=\s*'([^']+)'") { throw '无法读取 pgvector 版本' }
$vectorVersion = $Matches[1]
$vectorDll = Join-Path $pgDir 'lib\vector.dll'
Assert-File $vectorDll 'pgvector 运行时文件缺失'

$pgLicense = Join-Path $work 'postgresql-COPYRIGHT'
$vectorLicense = Join-Path $work 'pgvector-LICENSE'
# 使用固定版本的上游文本镜像；jsDelivr 对超大仓库（postgres）返回 403，回退到 GitHub raw。
$pgBranch = 'REL_' + ($pgVersion -replace '\.', '_')
try {
    Invoke-Download "https://cdn.jsdelivr.net/gh/postgres/postgres@$pgBranch/COPYRIGHT" $pgLicense '无法获取 PostgreSQL COPYRIGHT'
} catch {
    Invoke-Download "https://raw.githubusercontent.com/postgres/postgres/$pgBranch/COPYRIGHT" $pgLicense '无法获取 PostgreSQL COPYRIGHT'
}
Invoke-Download "https://cdn.jsdelivr.net/gh/pgvector/pgvector@v$vectorVersion/LICENSE" $vectorLicense '无法获取 pgvector LICENSE'
Add-LicenseText 'runtime' "PostgreSQL $pgVersion" (Get-Content -LiteralPath $pgLicense -Raw)
Add-LicenseText 'runtime' "pgvector $vectorVersion" (Get-Content -LiteralPath $vectorLicense -Raw)

$desktopVersion = (Get-Content -LiteralPath (Join-Path $repo 'desktop\package.json') -Raw | ConvertFrom-Json).version
$runtimeSbom = [ordered]@{
    bomFormat = 'CycloneDX'
    specVersion = '1.6'
    serialNumber = "urn:uuid:$([guid]::NewGuid().ToString())"
    version = 1
    metadata = [ordered]@{
        timestamp = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        component = [ordered]@{ type = 'application'; name = 'Lunar Gold Desktop Runtime'; version = $desktopVersion }
    }
    components = @(
        [ordered]@{ type = 'application'; name = 'electron'; version = $electronVersion; purl = "pkg:npm/electron@$electronVersion"; licenses = @(@{ expression = 'MIT' }); hashes = (Get-HashObject $electronExe); externalReferences = @(@{ type = 'distribution'; url = 'https://github.com/electron/electron' }) },
        [ordered]@{ type = 'application'; name = 'temurin-jre'; version = $jreVersion; supplier = @{ name = $jreVendor }; purl = "pkg:generic/temurin-jre@$jreVersion"; licenses = @(@{ expression = 'GPL-2.0-only WITH Classpath-exception-2.0' }); hashes = (Get-HashObject $jreJava); externalReferences = @(@{ type = 'distribution'; url = 'https://adoptium.net' }) },
        [ordered]@{ type = 'application'; name = 'postgresql'; version = $pgVersion; purl = "pkg:generic/postgresql@$pgVersion"; licenses = @(@{ expression = 'PostgreSQL' }); hashes = (Get-HashObject $pgRuntime); externalReferences = @(@{ type = 'distribution'; url = 'https://www.postgresql.org' }) },
        [ordered]@{ type = 'library'; name = 'pgvector'; version = $vectorVersion; purl = "pkg:generic/pgvector@$vectorVersion"; licenses = @(@{ expression = 'PostgreSQL' }); hashes = (Get-HashObject $vectorDll); externalReferences = @(@{ type = 'distribution'; url = 'https://github.com/pgvector/pgvector' }) }
    )
}
Write-Utf8 (Join-Path $OutputDir 'SBOM.runtime.json') ($runtimeSbom | ConvertTo-Json -Depth 12)

Write-Step '汇总第三方许可文本'
$licenseHeader = "Lunar Gold Third-Party Licenses and Notices`nGenerated from the actual desktop build inputs. Component groups are sorted by source path.`n`n"
Write-Utf8 (Join-Path $OutputDir 'THIRD-PARTY-LICENSES.txt') ($licenseHeader + ($script:licenseSections -join "`n`n"))

foreach ($name in @('SBOM.json', 'SBOM.frontend.json', 'SBOM.desktop.json', 'SBOM.runtime.json')) {
    $path = Join-Path $OutputDir $name
    Assert-File $path 'SBOM 缺失'
    $bom = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($bom.bomFormat -ne 'CycloneDX' -or @($bom.components).Count -eq 0) { throw "SBOM 校验失败：$name" }
}
Assert-File (Join-Path $OutputDir 'THIRD-PARTY-LICENSES.txt') '第三方许可合集为空'
$javaMeta = Get-Content -LiteralPath (Join-Path $OutputDir 'SBOM.json') -Raw | ConvertFrom-Json
$referenceText = $javaMeta.metadata.component.externalReferences | ConvertTo-Json -Depth 8
if ($referenceText -match 'spring-projects/spring-boot/agent-gateway|spring\.io/projects/spring-boot/agent-gateway') {
    throw 'Java SBOM 仍包含 Spring Boot 项目地址'
}
Write-Step "合规产物已生成：$OutputDir"
