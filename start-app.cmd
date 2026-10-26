@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\app-launcher.ps1" -Mode Start
if errorlevel 1 (
  echo.
  echo LedgerGuard could not start. Review the message above.
  pause
  exit /b 1
)
