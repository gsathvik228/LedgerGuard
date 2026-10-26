@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\app-launcher.ps1" -Mode Stop
if errorlevel 1 (
  echo.
  echo LedgerGuard could not stop. Review the message above.
  pause
  exit /b 1
)
