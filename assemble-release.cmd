@echo off
REM TVBox Simple - one-shot release build
REM Usage: assemble-release.cmd [version_name] [version_code]
REM   e.g. assemble-release.cmd 1.0.1 2

setlocal EnableDelayedExpansion

set "VERSION_NAME=%~1"
if "%VERSION_NAME%"=="" set "VERSION_NAME=1.0.0"
set "VERSION_CODE=%~2"
if "%VERSION_CODE%"=="" set "VERSION_CODE=1"

echo ============================================
echo  TVBox Simple - Release Build
echo  versionName:  %VERSION_NAME%
echo  versionCode:  %VERSION_CODE%
echo ============================================

REM 0. Locate env
if not defined JAVA_HOME set "JAVA_HOME=D:\jdk-17.0.2"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

REM 1. Pre-build version bump
echo.
echo [1/4] Bumping version to %VERSION_NAME% (%VERSION_CODE%)...
powershell -NoProfile -Command "(Get-Content app\build.gradle.kts) -replace 'versionName = \"[0-9.]+\"', 'versionName = \"%VERSION_NAME%\"' -replace 'versionCode = [0-9]+', 'versionCode = %VERSION_CODE%' | Set-Content app\build.gradle.kts -Encoding UTF8"

REM 2. Clean
echo.
echo [2/4] Cleaning previous release...
call gradlew.bat clean --no-daemon 2>nul
if errorlevel 1 (
    echo   [WARN] clean failed, continuing
)

REM 3. Build release
echo.
echo [3/4] Building release APK...
call gradlew.bat assembleRelease --no-daemon
if errorlevel 1 (
    echo.
    echo [FAIL] assembleRelease failed
    exit /b 1
)

REM 4. Locate output
echo.
echo [4/4] Locating APK...
set "APK=app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" (
    echo [FAIL] APK not found at %APK%
    exit /b 1
)

for %%I in ("%APK%") do set "SIZE=%%~zI"
echo.
echo ============================================
echo  SUCCESS
echo  APK:    %APK%
echo  Size:   %SIZE% bytes
echo ============================================
endlocal
