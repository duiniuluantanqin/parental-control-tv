@echo off
setlocal EnableExtensions
cd /d "%~dp0"
chcp 65001 >nul
echo 正在查找Java...

rem 尝试使用Android Studio自带的JDK
set STUDIO_JDK=%ProgramFiles%\Android\Android Studio\jbr
if exist "%STUDIO_JDK%\bin\javac.exe" (
    echo 找到Android Studio JDK
    set JAVA_HOME=%STUDIO_JDK%
    goto build
)

set STUDIO_JDK=D:\Program Files\Android\Android Studio\jbr
if exist "%STUDIO_JDK%\bin\javac.exe" (
    echo 找到Android Studio JDK
    set JAVA_HOME=%STUDIO_JDK%
    goto build
)

set STUDIO_JDK=%LOCALAPPDATA%\Programs\Android Studio\jbr
if exist "%STUDIO_JDK%\bin\javac.exe" (
    echo 找到Android Studio JDK
    set JAVA_HOME=%STUDIO_JDK%
    goto build
)

rem 尝试使用Android SDK的JDK
set SDK_JDK=%LOCALAPPDATA%\Android\Sdk\jbr
if exist "%SDK_JDK%\bin\javac.exe" (
    echo 找到Android SDK JDK
    set JAVA_HOME=%SDK_JDK%
    goto build
)

echo 错误: 找不到Java编译器
echo 请手动使用Android Studio编译
pause
exit /b 1

:build
echo 使用JAVA_HOME: %JAVA_HOME%
echo 开始编译...
call gradlew.bat assembleDebug
if %ERRORLEVEL% EQU 0 (
    echo 编译成功！
    echo APK位置: app\build\outputs\parental\Parental-TV.apk
) else (
    echo 编译失败
)
pause
