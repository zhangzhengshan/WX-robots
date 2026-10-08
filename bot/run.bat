@echo off
rem Manual entry: compile + run + pause (to see errors)
rem For watchdog auto-restart use run-only.bat (no compile, no pause)
call build.bat
if %errorlevel% neq 0 (
    pause
    exit /b 1
)
echo === Running ===
call run-only.bat
echo.
pause