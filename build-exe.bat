@echo off
chcp 65001 >nul
setlocal
title TuneDeck exe derleme
cd /d "%~dp0"

echo   TuneDeck.exe derleniyor (Python gerekir, sadece gelistirici icin)
echo.

where python >nul 2>&1 || (echo   [!] Python bulunamadi. & pause & exit /b 1)
python -m pip install -q --disable-pip-version-check pyinstaller -r requirements.txt
if errorlevel 1 (echo   [!] pyinstaller kurulamadi. & pause & exit /b 1)

rem surum bilgisi yoksa manifestten uret
if not exist version.json (
  python -c "import re,json,io;m=open('android/app/AndroidManifest.xml',encoding='utf-8').read();v=re.search(r'versionName=.([0-9.]+).',m).group(1);c=int(re.search(r'versionCode=.(\d+).',m).group(1));json.dump({'version':v,'code':c,'apk':'/app.apk'},open('version.json','w',encoding='utf-8'))"
)

python -m PyInstaller --noconfirm --clean --onefile --windowed --name TuneDeck --icon icon.ico ^
  --add-data "ui.html;." --add-data "i18n.js;." --add-data "icon-192.png;." --add-data "icon-512.png;." ^
  --add-data "ytm-remote.apk;." --add-data "version.json;." --add-data "tools/fix-shortcut.ps1;tools" ^
  --distpath dist --workpath build_pyi --specpath build_pyi launcher.py
if errorlevel 1 (echo   [!] Derleme basarisiz. & pause & exit /b 1)

echo.
echo   Hazir: dist\TuneDeck.exe
dir dist\TuneDeck.exe | findstr TuneDeck
pause
