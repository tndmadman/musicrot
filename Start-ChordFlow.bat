@echo off
setlocal
cd /d "%~dp0"
if errorlevel 1 (
  echo ERROR: Unable to open the ChordFlow folder.
  pause
  exit /b 1
)
title ChordFlow - Startup
echo ==================================================
echo  ChordFlow ^| One-click Windows startup
echo ==================================================
echo.
echo [1/3] Checking requirements, building and testing...
call "%~dp0Build-ChordFlow.bat" --no-pause
if errorlevel 1 (
  echo.
  echo Startup stopped: Java/FFmpeg dependency, build or test failure.
  echo Install JDK 21 and FFmpeg/ffprobe, then retry.
  pause
  exit /b 1
)
echo.
echo [2/3] Opening controller and worker consoles...
start "ChordFlow Controller" "%~dp0Start-Controller.bat"
if errorlevel 1 (
  echo ERROR: Could not start the controller window.
  pause
  exit /b 1
)
start "ChordFlow Worker" "%~dp0Start-Worker.bat"
if errorlevel 1 (
  echo ERROR: Could not start the worker window.
  pause
  exit /b 1
)
echo.
echo [3/3] Opening dashboard...
timeout /t 3 /nobreak >nul
start "" "http://127.0.0.1:8791"
echo.
echo Output: chordflow-data\output
echo Keep the controller and worker windows open while making videos.
echo This starter window can now be closed.
pause
exit /b 0
