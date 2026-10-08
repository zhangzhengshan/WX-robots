@echo off
chcp 65001 >nul 2>&1
set JAVA_HOME=F:\plug-in\jdk17\jdk-17.0.20.1+1
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d "%~dp0"
if not exist out mkdir out

rem P4Test 不连会话存档 SDK，但复用 lib 下 JSON/SQLite/SLF4J
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

if "%1"=="" (
    rem 无参数：默认 text 模式（第一刀，单条文本任务）
    echo === 运行 P4Test text 模式（默认；dry-run=true 时不点发送、不占配额）===
    java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out;%CP%" com.mumu.bot.schedule.P4Test text
) else (
    rem 透传所有参数：text [自定义文案] / media
    echo === 运行 P4Test %* ===
    java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out;%CP%" com.mumu.bot.schedule.P4Test %*
)

echo.
pause
