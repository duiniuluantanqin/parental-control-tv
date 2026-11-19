@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
chcp 65001 >nul

if not "%~1"=="" set "TV_PARENTAL_TV_IP=%~1"

where python >nul 2>&1
if errorlevel 1 (
    echo.
    echo ERROR: Python 3 was not found. Install Python and add it to PATH.
    pause
    exit /b 1
)

python -X utf8 "%~dp0install_device.py"
if errorlevel 1 (
    echo.
    echo TCL installation failed. See the message above for details.
    pause
    exit /b 1
)

echo.
echo Installation completed.
pause
