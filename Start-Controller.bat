@echo off
setlocal
cd /d "%~dp0"
if errorlevel 1 (
  echo ERROR: Unable to open the ChordFlow folder.
  pause
  exit /b 1
)
title ChordFlow Controller - http://127.0.0.1:8791
echo ==================================================
echo  ChordFlow Controller
echo  Dashboard: http://127.0.0.1:8791
echo  Leave this window open while using ChordFlow.
echo ==================================================
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-controller.ps1"
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" echo ERROR: Controller stopped with exit code %RC%.
echo Controller exited. Press any key to close this window.
pause >nul
exit /b %RC%
