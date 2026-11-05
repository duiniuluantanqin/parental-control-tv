@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
chcp 65001 >nul
echo ================================
echo    安装到模拟器
echo ================================
echo.

rem 检查 APK 是否存在
set APK_FILE=app\build\outputs\parental\Parental-TV.apk
if not exist "%APK_FILE%" (
    echo ❌ 错误: 找不到 APK 文件
    echo.
    echo 请先运行 build.bat 编译项目
    pause
    exit /b 1
)

rem 检查 adb 是否可用
set "ADB_CMD="
if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB_CMD=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB_CMD if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_CMD=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_CMD if exist "local.properties" (
    set "SDK_DIR="
    for /f "tokens=1,* delims==" %%A in ('findstr /b /c:"sdk.dir=" local.properties 2^>nul') do set "SDK_DIR=%%B"
    set "SDK_DIR=!SDK_DIR:\:=:!"
    set "SDK_DIR=!SDK_DIR:\\=\!"
    set "SDK_DIR=!SDK_DIR:\ = !"
    if exist "!SDK_DIR!\platform-tools\adb.exe" set "ADB_CMD=!SDK_DIR!\platform-tools\adb.exe"
)
if not defined ADB_CMD if defined LOCALAPPDATA if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_CMD=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_CMD (
    where adb >nul 2>&1
    if not errorlevel 1 set "ADB_CMD=adb"
)
if not defined ADB_CMD (
    echo ❌ 错误: 找不到 adb 命令
    echo.
    echo 请确保 Android SDK 已安装并添加到系统 PATH
    pause
    exit /b 1
)

echo [1/4] 检查已连接的设备...
"%ADB_CMD%" devices
echo.

rem 查找模拟器
for /f "tokens=1" %%i in ('"%ADB_CMD%" devices ^| findstr "emulator"') do set EMULATOR=%%i

if "%EMULATOR%"=="" (
    echo ❌ 未找到运行中的模拟器
    echo.
    echo 请先启动 Android 模拟器：
    echo 1. 打开 Android Studio
    echo 2. 点击 AVD Manager
    echo 3. 启动一个模拟器
    pause
    exit /b 1
)

echo ✓ 找到模拟器: %EMULATOR%
echo.

echo [2/4] 卸载旧版本...
"%ADB_CMD%" -s %EMULATOR% uninstall com.example.tvparental >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo ✓ 已卸载旧版本
) else (
    echo - 未安装旧版本，跳过
)
echo.

echo [3/4] 安装新版本...
"%ADB_CMD%" -s %EMULATOR% install %APK_FILE%

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ 安装失败！
    pause
    exit /b 1
)

echo.
echo [4/4] 授予悬浮窗权限...
"%ADB_CMD%" -s %EMULATOR% shell appops set com.example.tvparental SYSTEM_ALERT_WINDOW allow
echo ✓ 权限已授予
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
    "%ADB_CMD%" -s %EMULATOR% shell am start -n com.example.tvparental/.MainActivity
    echo.
    echo ✓ 应用已启动
)

echo.
pause
