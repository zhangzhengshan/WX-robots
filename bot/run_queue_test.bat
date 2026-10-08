@echo off
chcp 65001 >nul 2>&1
set JAVA_HOME=F:\plug-in\jdk17\jdk-17.0.20.1+1
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d "%~dp0"
if not exist out mkdir out

rem QueueTest 不依赖 SDK DLL（只用 SQLite），但为简化编译流程仍用全量 classpath
set SDK_DIR=..\java_sdk\WeWorkFinanceSdk
set CP=%SDK_DIR%\bin;%SDK_DIR%\json-20240303.jar;lib\*

echo === 编译 ===
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -cp "%CP%" -d out @sources.txt
if %errorlevel% neq 0 (
    echo 编译失败
    del sources.txt 2>nul
    pause
    exit /b 1
)
del sources.txt 2>nul

echo === 运行 QueueTest ===
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out;%CP%" com.mumu.bot.outbound.QueueTest

echo.
pause
