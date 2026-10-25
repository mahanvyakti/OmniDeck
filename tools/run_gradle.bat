@echo off
setlocal
if exist "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot" (
    set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
)
call "%~dp0..\gradlew.bat" %*
exit /b %ERRORLEVEL%
