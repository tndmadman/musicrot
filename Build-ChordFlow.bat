@echo off
setlocal
cd /d "%~dp0"
if errorlevel 1 (
  echo ERROR: Unable to open the ChordFlow folder.
  pause
  exit /b 1
)
echo ==================================================
echo  ChordFlow ^| Build and verify
echo ==================================================
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build.ps1"
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" echo ERROR: Build or self-tests failed with code %RC%.
if /I "%~1"=="--no-pause" exit /b %RC%
echo.
pause
exit /b %RC%
