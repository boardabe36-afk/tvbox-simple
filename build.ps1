# TVBox 简版 一键构建脚本（Windows / PowerShell）
# 用法：右键 PowerShell → "使用 PowerShell 运行"，或在 PowerShell 里执行：
#   .\build.ps1

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot

Write-Host "==> 项目目录: $ProjectRoot" -ForegroundColor Cyan

# 1. 检查 Java
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host "✗ 未检测到 Java，请先安装 JDK 17 (https://adoptium.net)" -ForegroundColor Red
    exit 1
}
$javaVer = (& java -version 2>&1 | Select-String 'version "(.+?)"' | ForEach-Object { $_.Matches[0].Groups[1].Value })
Write-Host "✓ Java 版本: $javaVer"

# 2. 检查 ANDROID_HOME
if (-not $env:ANDROID_HOME) {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "C:\Android\Sdk"
    )
    foreach ($p in $candidates) {
        if (Test-Path $p) {
            $env:ANDROID_HOME = $p
            Write-Host "✓ 自动设置 ANDROID_HOME = $p"
            break
        }
    }
    if (-not $env:ANDROID_HOME) {
        Write-Host "✗ 未设置 ANDROID_HOME 环境变量，且未在默认位置找到 Android SDK" -ForegroundColor Red
        Write-Host "  请安装 Android Studio 或 Android command-line tools 后重试" -ForegroundColor Red
        exit 1
    }
}
Write-Host "✓ ANDROID_HOME = $env:ANDROID_HOME"

# 3. 准备 gradle wrapper jar
$wrapperJar = Join-Path $ProjectRoot "gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $wrapperJar)) {
    Write-Host "==> 下载 gradle-wrapper.jar ..." -ForegroundColor Yellow
    $gradleVersion = "8.7"
    $gradleZip = "$env:TEMP\gradle-$gradleVersion-bin.zip"
    $gradleUrl = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
    if (-not (Test-Path $gradleZip)) {
        Invoke-WebRequest -Uri $gradleUrl -OutFile $gradleZip -UseBasicParsing
    }
    $gradleTmp = "$env:TEMP\gradle-$gradleVersion"
    if (Test-Path $gradleTmp) { Remove-Item -Recurse -Force $gradleTmp }
    Expand-Archive -Path $gradleZip -DestinationPath $env:TEMP
    & "$gradleTmp\bin\gradle.bat" wrapper --gradle-version $gradleVersion --distribution-type bin
    if (-not (Test-Path $wrapperJar)) {
        Write-Host "✗ gradle wrapper 初始化失败" -ForegroundColor Red
        exit 1
    }
}
Write-Host "✓ gradle-wrapper.jar 已就绪"

# 4. 执行构建
Set-Location $ProjectRoot
$env:JAVA_HOME = (Get-Command java).Source | Split-Path | Split-Path
Write-Host "==> 开始构建 debug APK ..." -ForegroundColor Green
& .\gradlew.bat assembleDebug --no-daemon

if ($LASTEXITCODE -eq 0) {
    $apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    Write-Host ""
    Write-Host "✓ 构建成功！" -ForegroundColor Green
    Write-Host "  APK 位置: $apk" -ForegroundColor Green
} else {
    Write-Host "✗ 构建失败，退出码 $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}
