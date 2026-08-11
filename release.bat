@echo off
setlocal enabledelayedexpansion
title Deep Sky Camera - publish a release

REM ---------------------------------------------------------------------------
REM  Publishes a new version that phones can find on their own.
REM
REM      release.bat 0.2.0
REM
REM  Tags the current commit and pushes it. That tag is what tells GitHub Actions
REM  to build a signed APK and attach it to a Release together with
REM  deepsky-update.json - the manifest the in-app updater polls.
REM
REM  Nothing is built locally here. CI builds it with the same signing key (from
REM  the DSC_KEYSTORE_* repository secrets), which is what lets the new build
REM  install over the old one on your phone.
REM ---------------------------------------------------------------------------

cd /d "%~dp0"

set "VERSION=%~1"

if "%VERSION%"=="" (
    echo.
    echo  Usage: release.bat 0.2.0
    echo.
    echo  Use a plain version number with no leading "v" - the tag gets it added.
    echo.
    pause
    exit /b 1
)

echo.
echo  == Publishing Deep Sky Camera %VERSION% ==
echo.

REM A dirty tree means the tag would not describe what you think it does.
for /f %%s in ('git status --porcelain') do (
    echo  [X] You have uncommitted changes. Commit them first, so the tag points
    echo      at exactly the code that gets published.
    echo.
    git status --short
    echo.
    pause
    exit /b 1
)

echo  Running tests...
call gradlew.bat :app:testDebugUnitTest --quiet
if errorlevel 1 (
    echo.
    echo  [X] Tests failed - not publishing.
    pause
    exit /b 1
)

git tag "v%VERSION%"
if errorlevel 1 (
    echo  [X] Could not create tag v%VERSION% - does it already exist?
    pause
    exit /b 1
)

git push origin "v%VERSION%"
if errorlevel 1 (
    echo  [X] Push failed. The tag exists locally; delete it with
    echo      "git tag -d v%VERSION%" if you want to start over.
    pause
    exit /b 1
)

echo.
echo  [OK] Tag pushed. GitHub Actions is building it now.
echo.
echo       Watch:   https://github.com/Scottys3DPrints/Deep-Sky-Camera/actions
echo       Release: https://github.com/Scottys3DPrints/Deep-Sky-Camera/releases
echo.
echo  Once the run finishes, open the app on your phone and go to
echo  Settings ^> Check for updates. It will offer %VERSION%.
echo.
pause
