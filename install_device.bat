@echo off
setlocal EnableExtensions
cd /d "%~dp0"
chcp 65001 >nul
echo ================================
echo    安装到真机/TV设备
echo ================================
echo.

rem 检查 APK 是否存在
set APK_FILE=app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK_FILE%" (
    echo ❌ 错误: 找不到 APK 文件
    echo.
    echo 请先运行 build.bat 编译项目
    pause
    exit /b 1
)

rem 检查 adb 是否可用
where adb >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ❌ 错误: 找不到 adb 命令
    echo.
    echo 请确保 Android SDK 已安装并添加到系统 PATH
    pause
    exit /b 1
)

echo [1/5] 检查已连接的设备...
adb devices
echo.

rem 统计设备数量
for /f %%i in ('adb devices ^| find /c "device"') do set DEVICE_COUNT=%%i
set /a DEVICE_COUNT=%DEVICE_COUNT%-1

if %DEVICE_COUNT% EQU 0 (
    echo ❌ 未找到已连接的设备
    echo.
    echo 请检查：
    echo 1. 设备是否通过 USB 连接或网络连接 ^(adb connect IP:PORT^)
    echo 2. 设备是否开启了 USB 调试
    echo 3. 是否已授权此电脑进行调试
    echo.
    echo TCL TV 连接示例：
    echo   adb connect 10.0.0.44:5555
    pause
    exit /b 1
)

if %DEVICE_COUNT% GTR 1 (
    echo ⚠ 发现多个设备，请手动指定
    echo.
    echo 使用方法：
    echo   adb -s [设备ID] install app\build\outputs\apk\debug\app-debug.apk
    pause
    exit /b 1
)

rem 获取设备ID（排除 emulator）
for /f "tokens=1" %%i in ('adb devices ^| findstr /v "emulator" ^| findstr "device"') do set DEVICE=%%i

if "%DEVICE%"=="" (
    echo ❌ 未找到真机设备
    echo.
    echo 提示：如需连接 TCL TV，请先运行：
    echo   adb connect 10.0.0.44:5555
    pause
    exit /b 1
)

echo ✓ 找到设备: %DEVICE%
echo.

echo [2/5] 卸载旧版本...
adb -s %DEVICE% uninstall com.example.tvparental >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo ✓ 已卸载旧版本
) else (
    echo - 未安装旧版本，跳过
)
echo.

echo [3/5] 安装新版本...
echo.
echo ⚠ 注意：TCL 电视需要在电视端确认安装！
echo.
adb -s %DEVICE% install %APK_FILE%

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ 安装失败！
    echo.
    echo TCL 电视特殊说明：
    echo - 该固件不支持 adb install -r
    echo - 需要使用 TCL MultiScreen Interaction 应用安装
    echo - 详见 docs\INSTALL_TCL.md
    pause
    exit /b 1
)

echo.
echo [4/5] 授予悬浮窗权限...
adb -s %DEVICE% shell appops set com.example.tvparental SYSTEM_ALERT_WINDOW allow
echo ✓ 权限已授予
echo.

echo [5/5] 检查安装版本...
for /f "tokens=2 delims==" %%v in ('adb -s %DEVICE% shell dumpsys package com.example.tvparental ^| findstr "versionName"') do (
    echo ✓ 已安装版本: %%v
)
echo.

echo ================================
echo    安装成功！✓
echo ================================
echo.
echo 是否立即启动应用？(Y/N)
set /p LAUNCH=

if /i "%LAUNCH%"=="Y" (
    echo.
    echo 正在启动应用...
    adb -s %DEVICE% shell am start -n com.example.tvparental/.MainActivity
    echo.
    echo ✓ 应用已启动
)

echo.
pause
