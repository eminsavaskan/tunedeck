' YT Music Remote - kopruyu arka planda baslatir (giris aninda otomatik calisir)
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = "C:\Users\Emin\ytm-remote"
sh.Run """C:\Users\Emin\ytm-remote\runtime\python\pythonw.exe"" ""C:\Users\Emin\ytm-remote\bridge.py""", 0, False
