@echo off
rem ============================================================
rem  Problem feedback: export diagnostics bundle and send to
rem  the developer by email. A copy is left on the Desktop.
rem  (Chinese guide: see README.txt)
rem  NOTE: keep this file ASCII-only with CRLF endings.
rem ============================================================
title Export Diagnostics
cd /d "%~dp0"
echo.
echo  Collecting logs / configs / db ... please wait 10-60s
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0export-diagnostics.ps1" -OutDir "%USERPROFILE%\Desktop" -Mail
echo.
echo  Done. See messages above. A zip copy is on your Desktop.
pause
