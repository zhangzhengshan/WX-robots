@echo off
rem stop.bat - stop watchdog and bot processes (ADR-0016)
rem NOTE: keep this file ASCII-only with CRLF endings - cmd mis-parses
rem       UTF-8/LF-only batch files (multi-line caret continuation breaks too).
chcp 65001 >nul 2>&1

echo Stopping watchdog and bot processes...

rem Match process command lines precisely to avoid killing unrelated java/powershell
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match 'com\.mumu\.bot\.Main' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; Get-CimInstance Win32_Process -Filter \"Name='powershell.exe'\" | Where-Object { $_.CommandLine -match 'watchdog\.ps1' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

rem Also stop the orphaned plink tunnel (watchdog exit leaves it running,
rem and its exe file locks the package folder, blocking uninstall/deletion)
powershell -NoProfile -Command "Get-Process plink -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue"

echo Stopped. To restart, double-click start.vbs.
pause
