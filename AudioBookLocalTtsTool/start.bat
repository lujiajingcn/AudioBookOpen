@echo off
rem Double-click to start AudioBookLocalTtsTool
cd /d "%~dp0"
where python >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Python not found in PATH. Please install Python 3.10+ first.
  pause
  exit /b 1
)
python -B run.py
if errorlevel 1 pause
