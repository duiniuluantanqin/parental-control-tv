@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call build.bat
exit /b %ERRORLEVEL%
