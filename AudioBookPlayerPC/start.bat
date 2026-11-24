@echo off
chcp 65001 >nul
cd /d "%~dp0"
where python >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] 未找到 python，请先安装并加入 PATH
    pause
    exit /b 1
)
python -B run.py %*
