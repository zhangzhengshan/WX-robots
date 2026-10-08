@echo off
rem ADR-0016: prefer bundled JDK (runtime/jre); fallback to system JAVA_HOME.
rem NOTE: keep this file ASCII-only with CRLF endings - cmd mis-parses
rem       UTF-8/LF-only batch files and shatters parenthesized blocks.
chcp 65001 >nul 2>&1

set "BUNDLED_JRE=%~dp0..\runtime\jre"
if exist "%BUNDLED_JRE%\bin\javac.exe" goto :bundled
if defined JAVA_HOME goto :run
goto :run

:bundled
set "JAVA_HOME=%BUNDLED_JRE%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
goto :run

:run
cd /d "%~dp0"
if not exist out mkdir out

rem SDK (Finance.class + native DLL), JSON, SQLite/SLF4J
set SDK_DIR=..\java_sdk\WeWorkFinanceSdk
set CP=%SDK_DIR%\bin;%SDK_DIR%\json-20240303.jar;lib\*

echo === Compiling ===
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -cp "%CP%" -d out @sources.txt
if %errorlevel% neq 0 goto :failed
del sources.txt 2>nul
echo Compilation done
exit /b 0

:failed
echo Compilation FAILED
del sources.txt 2>nul
exit /b 1
