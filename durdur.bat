@echo off
chcp 65001 >nul
echo YT Music Remote durduruluyor...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8777" ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1
echo Durduruldu.
timeout /t 2 >nul
