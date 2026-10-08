@echo off
rem P7: watchdog launcher only - no compile, no pause, propagates JVM exit code.
rem For manual runs use run.bat (compile + run + pause).
rem ADR-0016: prefer bundled JRE (runtime/jre); fallback to system JAVA_HOME.
rem NOTE: keep this file ASCII-only with CRLF endings - cmd mis-parses
rem       UTF-8/LF-only batch files and shatters parenthesized blocks.
chcp 65001 >nul 2>&1

set "BUNDLED_JRE=%~dp0..\runtime\jre"
if exist "%BUNDLED_JRE%\bin\java.exe" goto :bundled
if defined JAVA_HOME goto :systemjava
goto :run

:bundled
set "JAVA_HOME=%BUNDLED_JRE%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
goto :run

:systemjava
set "PATH=%JAVA_HOME%\bin;%PATH%"
goto :run

:run
cd /d "%~dp0"

rem SDK (Finance.class + native DLL), JSON, SQLite/SLF4J
set SDK_DIR=..\java_sdk\WeWorkFinanceSdk
set CP=%SDK_DIR%\bin;%SDK_DIR%\json-20240303.jar;lib\*

rem JNI dependency DLLs (libcrypto/libcurl) are resolved via the process PATH,
rem NOT via java.library.path. Prepend the SDK dir (normalized to absolute):
for %%i in ("%CD%\%SDK_DIR%") do set "PATH=%%~fi;%PATH%"

rem -Djava.library.path: WeWorkFinanceSdk.dll load path
rem -Duser.timezone: lock to Asia/Shanghai
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Djava.library.path=%SDK_DIR% -cp "out;%CP%" com.mumu.bot.Main

rem propagate JVM exit code to watchdog (exit 1 = circuit breaker)
exit /b %errorlevel%
