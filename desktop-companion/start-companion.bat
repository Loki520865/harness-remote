@echo off
REM ============================================================
REM  Harness助手 · 桌面伴侣一键启动（开源版 v0.7.0）
REM  用法: start-companion.bat [工作区目录]
REM  前提: 1) 已安装 Node.js 22.19+    2) 已 npm install
REM        3) 已设置环境变量 RELAY_URL（你的服务器地址）
REM  服务器地址示例（PowerShell，重开终端生效）:
REM     setx RELAY_URL wss://your-server/relay/device
REM  工作区优先级: 命令行参数 > 环境变量 CWD > 本脚本所在目录 > 用户主目录
REM  本地面板:   http://localhost:8717
REM ============================================================
cd /d "%~dp0"

REM ---- 环境自检：Node 可用性 ----
where node >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 Node.js。请先安装 Node.js 22.19+（https://nodejs.org），
    echo        安装后重新运行本脚本。
    pause
    exit /b 1
)

REM ---- 环境自检：项目依赖（node_modules）----
if not exist "%~dp0node_modules\tsx\package.json" (
    echo [错误] 缺少依赖 node_modules。请在本目录先执行一次：
    echo    npm install
    pause
    exit /b 1
)

REM ---- 服务器地址（必填，由部署者提供；可用环境变量 RELAY_URL 覆盖）----
if "%RELAY_URL%"=="" (
    echo [错误] 未设置 RELAY_URL 环境变量。
    echo        请先部署中继服务器，然后设置：
    echo        setx RELAY_URL wss://your-server/relay/device
    echo        重新打开终端后再次运行本脚本。
    pause
    exit /b 1
)
set "PANEL_PORT=8717"

REM ---- 工作区自动识别：参数 > 环境变量 COMPANION_CWD > 脚本目录 > 用户主目录 ----
set "CWD=%COMPANION_CWD%"
if not "%~1"=="" set "CWD=%~1"
if "%CWD%"=="" set "CWD=%~dp0"
if not exist "%CWD%" set "CWD=%USERPROFILE%"

echo [Harness助手] 启动桌面伴侣（工作区: %CWD%）
echo [Harness助手] 本地面板: http://localhost:8717
npx tsx src/tunnel-run.ts "%CWD%"
pause
