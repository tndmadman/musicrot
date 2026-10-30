@echo off
setlocal EnableExtensions
cd /d "%~dp0"
if errorlevel 1 (
  echo ERROR: Could not open the ChordFlow directory.
  pause
  exit /b 1
)
title ChordFlow Command Center
echo.
echo ============================================================
echo   CHORDFLOW COMMAND CENTER // WINDOWS
echo   Dashboard: http://127.0.0.1:8791
echo   Controller and local production worker
echo ============================================================
echo.

rem Fail closed: never launch a worker if Java, FFmpeg, build or self-tests fail.
call "%~dp0Build-ChordFlow.bat" --no-pause
if errorlevel 1 (
  echo.
  echo STARTUP BLOCKED: Build or self-tests failed. Java 20+ and FFmpeg / FFprobe are required. See the specific error above.
  pause
  exit /b 1
)
if /I "%~1"=="--preflight" (
  echo ChordFlow launcher preflight passed.
  exit /b 0
)

rem If a ChordFlow controller is already online, reuse it rather than binding port 8791 twice.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\controller-health.ps1" >nul 2>&1
if not errorlevel 1 (
  echo Existing ChordFlow controller detected. Reusing it.
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-companion.ps1" -SkipWait
  if errorlevel 1 (
    echo ERROR: Worker/browser startup failed. Check output above.
    pause
    exit /b 1
  )
  exit /b 0
)

echo Starting the command center in THIS console.
echo Worker starts separately when the command center is ready.
echo Keep the controller and worker consoles open while jobs render.
echo Use DRAIN in the dashboard before stopping production.
echo.
rem Start a short-lived companion that waits for readiness, launches one worker
rem if needed, and opens the default browser. It never starts a second controller.
rem CMD START can preserve a previous command's errorlevel even when it succeeds.
rem The prior controller-health probe intentionally returns 1 on a fresh startup.
rem Therefore do not interpret a stale ERRORLEVEL as a failed START.
start "ChordFlow Windows Startup" /min powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-companion.ps1"

rem Like AutoNewsRoller, the controller runs in the launcher's foreground.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-controller.ps1"
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" echo ERROR: ChordFlow controller exited with code %RC%.
echo.
echo ChordFlow command center stopped.
pause
exit /b %RC%
