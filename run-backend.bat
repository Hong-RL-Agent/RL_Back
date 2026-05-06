@echo off
set "GRADLE_USER_HOME=%~dp0.gradle-user"
call "%~dp0gradlew.bat" bootRun --console=plain
