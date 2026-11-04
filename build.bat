@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
chcp 65001 >nul

if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined JAVA_HOME if exist "D:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=D:\Program Files\Android\Android Studio\jbr"
if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"

if not defined JAVA_HOME (
    echo 错误: 找不到 JDK。请安装 Android Studio 或设置 JAVA_HOME。
    pause
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo 错误: JAVA_HOME 路径无效: %JAVA_HOME%
    pause
    exit /b 1
)
echo ================================
echo    Parental TV 编译脚本
echo ================================
echo.

rem 检查 gradlew.bat 是否存在
if not exist "gradlew.bat" (
    echo 错误: 找不到 gradlew.bat
    echo 请确保在项目根目录运行此脚本
    pause
    exit /b 1
)

echo [1/3] 清理旧的编译文件...
call gradlew.bat clean
if errorlevel 1 (
    echo 清理失败，已停止构建。
    pause
    exit /b 1
)

echo.
echo [2/3] 开始编译 Debug APK...
call gradlew.bat assembleDebug

if errorlevel 1 (
    echo.
    echo ❌ 编译失败！
    echo.
    echo 可能的原因：
    echo 1. 未安装 JDK 或 JAVA_HOME 未设置
    echo 2. 代码有语法错误
    echo 3. Gradle 配置问题
    echo.
    echo 解决方案：
    echo - 在 Android Studio 中打开项目并编译
    echo - 检查是否有红色错误提示
    pause
    exit /b 1
)

echo.
echo [3/3] 编译完成！
echo.
echo ================================
echo    编译成功！✓
echo ================================
echo.
echo APK 文件位置：

set APK_FILE=app\build\outputs\apk\debug\app-debug.apk
if exist "%APK_FILE%" (
    echo %cd%\%APK_FILE%
    echo.
    for %%A in ("%APK_FILE%") do (
        set /a SIZE_MB=%%~zA/1024/1024
        echo 文件大小: %%~zA 字节 ^(!SIZE_MB! MB^)
        echo 修改时间: %%~tA
    )
)

echo.
echo 下一步：运行 install_emulator.bat 或 install_device.bat 安装应用
echo.
pause
