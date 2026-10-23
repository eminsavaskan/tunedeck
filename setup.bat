@echo off
chcp 65001 >nul
setlocal
title TuneDeck kurulum
cd /d "%~dp0"

echo.
echo   TuneDeck kurulum
echo   ===============
echo.

rem ---------------------------------------------------------------- python
set "PY="
where python >nul 2>&1 && set "PY=python"
if not defined PY (
  where py >nul 2>&1 && set "PY=py -3"
)
if not defined PY (
  echo   [!] Python bulunamadi.
  echo       https://www.python.org/downloads/ adresinden kurarken
  echo       "Add python.exe to PATH" kutusunu isaretle, sonra bu dosyayi tekrar calistir.
  echo.
  pause
  exit /b 1
)
echo   [1/4] Python bulundu.

rem ---------------------------------------------------------------- bagimliliklar
echo   [2/4] Gerekli paketler kuruluyor (ilk seferde 20-60 saniye)...
%PY% -m pip install -q --disable-pip-version-check -r requirements.txt
if errorlevel 1 (
  echo   [!] Paketler kurulamadi. Internet baglantisini kontrol et.
  pause
  exit /b 1
)
echo         tamam.

rem ---------------------------------------------------------------- kisayol
echo   [3/4] YouTube Music kisayolu kontrol ediliyor...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\fix-shortcut.ps1"

rem ---------------------------------------------------------------- baslangic
echo   [4/4] Bilgisayar acilinca kendiliginden baslamasi icin kisayol ekleniyor...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$w=New-Object -ComObject WScript.Shell; $l=$w.CreateShortcut([Environment]::GetFolderPath('Startup') + '\TuneDeck.lnk'); $l.TargetPath='wscript.exe'; $l.Arguments='\"%~dp0arka-plan.vbs\"'; $l.WorkingDirectory='%~dp0'; $l.Save()"

rem ---------------------------------------------------------------- baslat
echo.
echo   Kopru baslatiliyor...
start "" wscript.exe "%~dp0arka-plan.vbs"
echo.
echo   Hazir. Telefonundan, ayni Wi-Fi'daki su adresi ac:
echo.
echo   (adresi gormek icin tarayicida http://127.0.0.1:8777/setup ac)
echo.
echo   Telefona kurmak icin: http://^<bilgisayar-ip^>:8777/app.apk
echo.
pause
