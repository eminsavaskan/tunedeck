# YouTube Music kisayoluna uzaktan kumanda bayragini ekler (bir kez).
# setup.bat bunu kendisi cagirir; elle de calistirilabilir:
#   powershell -ExecutionPolicy Bypass -File tools\fix-shortcut.ps1

$flag = '--remote-debugging-port=9223'
$roots = @(
    [Environment]::GetFolderPath('Desktop'),
    [Environment]::GetFolderPath('Programs'),
    [Environment]::GetFolderPath('CommonPrograms')
) | Where-Object { $_ -and (Test-Path $_) }

$found = 0
$done = 0

foreach ($root in $roots) {
    Get-ChildItem -Path $root -Filter *.lnk -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
        $shell = New-Object -ComObject WScript.Shell
        $sc = $shell.CreateShortcut($_.FullName)
        if ($sc.TargetPath -match 'YouTube Music\.exe$') {
            $found++
            if ($sc.Arguments -notmatch 'remote-debugging-port') {
                $sc.Arguments = ($sc.Arguments + ' ' + $flag).Trim()
                $sc.Save()
                Write-Host "  duzeltildi: $($_.FullName)"
                $done++
            } else {
                Write-Host "  zaten ayarli: $($_.FullName)"
            }
        }
    }
}

if ($found -eq 0) {
    Write-Host "  YouTube Music kisayolu bulunamadi."
    Write-Host "  Uygulamayi bir kez acip masaustunde kisayol olustur, sonra bu dosyayi tekrar calistir."
} elseif ($done -eq 0) {
    Write-Host "  Hepsi hazir, degisiklik gerekmedi."
}
