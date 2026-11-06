@echo off
chcp 65001 >nul
setlocal
call "%~dp0gradlew.bat" --no-daemon assembleDebug
if errorlevel 1 exit /b 1
echo.
echo APK created at: app\build\outputs\apk\debug\app-debug.apk
pause
