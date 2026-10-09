@echo off
setlocal
cd /d "%~dp0"

where npm >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js / npm not found. Install from https://nodejs.org first.
  pause
  exit /b 1
)

if not exist "node_modules" (
  echo [1/2] First run: installing dependencies, this may take a while...
  call npm install
  if errorlevel 1 (
    echo [ERROR] npm install failed. Check your network and try again.
    pause
    exit /b 1
  )
)

echo [2/2] Starting Morrow Creative Canvas...
echo       http://localhost:3000/canvas/
echo.
echo       Press Ctrl+C (or close this window) to stop.
echo.
call npm run dev -- --open
