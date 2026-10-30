@echo off
rem Backward-compatible alias. The single recommended launcher is command_center_windows.bat.
call "%~dp0command_center_windows.bat" %*
exit /b %ERRORLEVEL%
