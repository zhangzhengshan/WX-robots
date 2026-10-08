@echo off
rem resume.bat - clear the circuit-breaker sentinel so the watchdog can run again
rem When a circuit breaker trips, watchdog writes bot\data\circuit_breaker and
rem exits; it refuses to start while that file exists (even after reboot).
rem Run this file once, then double-click start.vbs if the bot is not running.
rem NOTE: keep this file ASCII-only with CRLF endings - cmd mis-parses UTF-8/LF.
chcp 65001 >nul 2>&1

set "SENTINEL=%~dp0bot\data\circuit_breaker"

if not exist "%SENTINEL%" goto noclear

del /f /q "%SENTINEL%" >nul 2>&1
if exist "%SENTINEL%" goto delfail

echo Sentinel cleared: %SENTINEL%
echo Now double-click start.vbs to start the bot.
pause
exit /b 0

:noclear
echo No sentinel found, nothing to clear:
echo   %SENTINEL%
pause
exit /b 0

:delfail
echo FAILED to delete sentinel. Close bot/watchdog windows and retry:
echo   %SENTINEL%
pause
exit /b 1
