<#
.SYNOPSIS
    用 jlink 为桌面端构建裁剪过的私有 JRE（Windows x64 / JDK 21）。

.DESCRIPTION
    桌面端安装包不能假设用户机器装了 Java，因此用 jlink 从本机 JDK 21 裁剪出一份
    只含所需模块的私有运行时，随 electron-builder 的 extraResources 打进安装包
    （desktop/main.js 在打包态优先使用 resources/jre）。

    模块清单按后端 jar 的实际依赖维护：Spring Boot/Tomcat 需要 java.management、
    java.naming、java.desktop（JavaBeans 内省）；JDBC 需要 java.sql*；
    HttpClient/SSL 需要 java.net.http 与 jdk.crypto.ec；Flyway/工具需要 java.xml、
    java.scripting、jdk.zipfs；pg 内嵌库解压需要 jdk.zipfs；UI 文案要中文 locale，
    故带 jdk.localedata 并 --include-locales=zh,en。

    裁剪清单如果漏模块，运行时会以 NoClassDefFoundError / ServiceConfigurationError
    形式快速失败，此时把缺失模块补进 -Modules 后重跑本脚本并用冒烟验证。

.PARAMETER JdkHome
    用于 jlink 的 JDK 21 目录；默认取 JAVA_HOME，再回退到本机默认安装位置。

.PARAMETER OutputDir
    产物目录，默认 <仓库根>/build/desktop-runtime（build/ 已被 .gitignore 忽略）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build-desktop-runtime.ps1
#>
[CmdletBinding()]
param(
    [string]$JdkHome,
    [string]$OutputDir,
    [string[]]$Modules = @(
        'java.base',
        'java.compiler',          # Spring 运行时字节码/注解处理兜底
        'java.desktop',           # java.beans 内省（Spring 绑定）
        'java.instrument',
        'java.management',        # Tomcat/Actuator JMX
        'java.naming',
        'java.net.http',
        'java.prefs',
        'java.rmi',
        'java.scripting',
        'java.security.jgss',
        'java.sql',
        'java.sql.rowset',
        'java.transaction.xa',
        'java.xml',
        'jdk.crypto.ec',          # TLS
        'jdk.unsupported',        # sun.misc.Unsafe（Netty/部分库）
        'jdk.zipfs',              # 读取 jar 内嵌 txz 之外的 zip 文件系统兜底
        'jdk.localedata'          # 配合 --include-locales=zh,en
    )
)

$ErrorActionPreference = 'Stop'

function Write-Step($message) { Write-Host "[desktop-jre] $message" }

# java -version 走 stderr，PS 5.1 在 $ErrorActionPreference=Stop 下会抛 NativeCommandError，需临时放宽
function Get-JavaVersionLine($javaBin) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return ((& $javaBin -version 2>&1)[0]).ToString() -replace '"', ''
    } finally {
        $ErrorActionPreference = $prev
    }
}

# 1. 定位 JDK 21：后端以 21 构建，JAVA_HOME 若指向其他主版本则回退到本机默认位置
if (-not $JdkHome) {
    $candidates = @($env:JAVA_HOME, 'C:\Users\16680\.jdks\ms-21.0.11') | Where-Object { $_ }
    foreach ($candidate in $candidates) {
        $javaBin = Join-Path $candidate 'bin\java.exe'
        if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\jlink.exe')) -and (Test-Path -LiteralPath $javaBin)) {
            if ((Get-JavaVersionLine $javaBin) -match '^openjdk version "?21\.|^java version "?21\.') {
                $JdkHome = $candidate
                break
            }
            Write-Step "跳过 $candidate（不是 JDK 21）"
        }
    }
    if (-not $JdkHome) { throw "找不到带 jlink 的 JDK 21，请通过 -JdkHome 指定" }
}
$jlink = Join-Path $JdkHome 'bin\jlink.exe'
if (-not (Test-Path -LiteralPath $jlink)) { throw "jlink 不存在: $jlink" }

$javaVersion = Get-JavaVersionLine (Join-Path $JdkHome 'bin\java.exe')
Write-Step "JDK: $JdkHome ($javaVersion)"
if ($javaVersion -notmatch '21') { Write-Warning "桌面端后端以 JDK 21 构建，当前 jlink 来源不是 21，请确认是否故意为之" }

# 2. 输出目录
if (-not $OutputDir) {
    $OutputDir = Join-Path $PSScriptRoot '..\build\desktop-runtime'
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir = Join-Path $PSScriptRoot $OutputDir
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$jreDir = Join-Path $OutputDir 'jre'
if (Test-Path -LiteralPath $jreDir) { Remove-Item -LiteralPath $jreDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

# 3. jlink 裁剪
$moduleList = ($Modules | Sort-Object -Unique) -join ','
Write-Step "模块: $moduleList"
& $jlink `
    --add-modules $moduleList `
    --include-locales=zh,en `
    --strip-debug `
    --no-man-pages `
    --no-header-files `
    --compress=2 `
    --output $jreDir
if ($LASTEXITCODE -ne 0) { throw "jlink 失败 (exit=$LASTEXITCODE)" }

$javaExe = Join-Path $jreDir 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) { throw "裁剪结果缺少 bin\java.exe: $jreDir" }

# 4. 快速自检：裁剪 JRE 能跑、关键模块在
Write-Step "裁剪 JRE 版本: $(Get-JavaVersionLine $javaExe)"
& $javaExe --list-modules | Select-String -Pattern 'java.sql|jdk.crypto.ec|jdk.localedata' | ForEach-Object { Write-Step "含模块: $($_.Line)" }

$sizeMb = [math]::Round(((Get-ChildItem -LiteralPath $jreDir -Recurse -File | Measure-Object -Property Length -Sum).Sum) / 1MB, 2)
Write-Host ''
Write-Step "产物: $jreDir"
Write-Step "大小: $sizeMb MiB"
Write-Step "打包: electron-builder extraResources 把 $jreDir 复制为 resources/jre"
Write-Step "冒烟: & '$javaExe' -jar target\agent-gateway-1.0.0.jar --spring.profiles.active=desktop"
