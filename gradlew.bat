@echo off
setlocal
set "VERSION=8.13"
set "GRADLE_ROOT=%USERPROFILE%\.gradle\wrapper\dists\hajala-gradle-%VERSION%"
set "GRADLE_BIN=%GRADLE_ROOT%\gradle-%VERSION%\bin\gradle.bat"
if exist "%GRADLE_BIN%" goto run
if not exist "%GRADLE_ROOT%" mkdir "%GRADLE_ROOT%"
set "ZIP=%GRADLE_ROOT%\gradle-%VERSION%-bin.zip"
if not exist "%ZIP%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%ZIP%'"
  if errorlevel 1 exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path '%GRADLE_ROOT%\gradle-%VERSION%') { Remove-Item -Recurse -Force '%GRADLE_ROOT%\gradle-%VERSION%' }; Expand-Archive -Path '%ZIP%' -DestinationPath '%GRADLE_ROOT%' -Force"
if errorlevel 1 exit /b 1
del "%ZIP%" >nul 2>nul
:run
call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%
