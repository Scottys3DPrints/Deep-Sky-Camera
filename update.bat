@echo off
setlocal enabledelayedexpansion
title Deep Sky Camera - build and update phone

REM ---------------------------------------------------------------------------
REM  Rebuilds Deep Sky Camera and installs it OVER the copy already on the phone.
REM
REM  This is an in-place upgrade, not a reinstall: "adb install -r" keeps the
REM  app's data, so your saved camera choice, focus offset and update address all
REM  survive untouched.
REM ---------------------------------------------------------------------------

cd /d "%~dp0"

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo.
echo  == Deep Sky Camera ==
echo.

if not exist "keystore.properties" (
    echo  [X] keystore.properties is missing.
    echo.
    echo      Without the original signing key Android will refuse to update the
    echo      installed app, and the only way forward would be uninstalling it.
    echo      Restore your backup of keystore.properties and deepsky-release.jks.
    echo.
    pause
    exit /b 1
)

echo  Running tests...
call gradlew.bat :app:testDebugUnitTest --quiet
if errorlevel 1 (
    echo.
    echo  [X] Tests failed - nothing was built. The exposure planner is what
    echo      decides your shutter and ISO; a broken one still takes photos, just
    echo      dark or streaked ones.
    pause
    exit /b 1
)

echo  Building release...
call gradlew.bat :app:assembleRelease --quiet
if errorlevel 1 (
    echo.
    echo  [X] Build failed. Nothing was sent to the phone.
    pause
    exit /b 1
)

set "APK=app\build\outputs\apk\release\app-release.apk"

echo.
echo  Looking for your phone...
"%ADB%" start-server >nul 2>&1
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
    if "%%b"=="device" set "DEVICE=%%a"
)

if not defined DEVICE (
    echo.
    echo  [!] No phone detected.
    echo.
    echo      Plug it in and make sure USB debugging is on
    echo      ^(Settings ^> Developer options ^> USB debugging^).
    echo.
    echo      The APK is still built and ready here:
    echo      %CD%\%APK%
    echo.
    pause
    exit /b 1
)

echo  Found !DEVICE!. Installing over the existing app...
echo.
"%ADB%" install -r "%APK%"

if errorlevel 1 (
    echo.
    echo  [X] Install failed.
    echo.
    echo      If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, this APK was signed
    echo      with a different key than the one already on the phone. Do NOT
    echo      uninstall to work around it - find the original
    echo      deepsky-release.jks instead.
    echo.
    pause
    exit /b 1
)

echo.
echo  [OK] Updated in place.
echo.
pause
