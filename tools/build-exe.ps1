# TuneDeck.exe derleyici — hem yerelde hem CI'da ayni komut.
#   pwsh -File tools/build-exe.ps1
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"
Set-Location $RepoRoot

Write-Host "==> bagimliliklar"
python -m pip install -q --disable-pip-version-check pyinstaller pillow -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw "pip basarisiz" }

Write-Host "==> ikon"
python -c "from PIL import Image; im=Image.open('icon-512.png').convert('RGBA'); im.resize((256,256)).save('icon.ico', sizes=[(16,16),(32,32),(48,48),(256,256)])"

if (-not (Test-Path "version.json")) {
    Write-Host "==> version.json (manifestten)"
    python -c "import re,json;m=open('android/app/AndroidManifest.xml',encoding='utf-8').read();v=re.search(r'versionName=.([0-9.]+).',m).group(1);c=int(re.search(r'versionCode=.(\d+).',m).group(1));json.dump({'version':v,'code':c,'apk':'/app.apk'},open('version.json','w',encoding='utf-8'))"
}

$data = @("ui.html", "i18n.js", "icon-192.png", "icon-512.png", "version.json")
if (Test-Path "ytm-remote.apk") {
    $data += "ytm-remote.apk"
    Write-Host "==> APK pakete dahil"
} else {
    Write-Host "==> APK yok: exe telefona kurulum dosyasi sunamaz (surum sayfasindan indirilebilir)"
}

$pyArgs = @("-m", "PyInstaller", "--noconfirm", "--clean", "--onefile", "--windowed",
            "--name", "TuneDeck", "--icon", (Join-Path $RepoRoot "icon.ico"))
foreach ($f in $data) {
    $pyArgs += "--add-data=" + (Join-Path $RepoRoot $f) + ";."
}
$pyArgs += "--add-data=" + (Join-Path $RepoRoot "tools/fix-shortcut.ps1") + ";tools"
$pyArgs += @("--distpath", "dist", "--workpath", "build_pyi", "--specpath", "build_pyi",
             (Join-Path $RepoRoot "launcher.py"))

Write-Host "==> PyInstaller"
python @pyArgs
if ($LASTEXITCODE -ne 0) { throw "PyInstaller basarisiz" }

$exe = Join-Path $RepoRoot "dist/TuneDeck.exe"
if (-not (Test-Path $exe)) { throw "exe uretilmedi" }
Write-Host ("==> hazir: {0} ({1:N1} MB)" -f $exe, ((Get-Item $exe).Length / 1MB))
