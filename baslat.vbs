' YT Music Remote - kopruyu baslatir ve baglanma sayfasini acar
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = "C:\Users\Emin\ytm-remote"
sh.Run """C:\Users\Emin\ytm-remote\runtime\python\pythonw.exe"" ""C:\Users\Emin\ytm-remote\bridge.py""", 0, False
WScript.Sleep 2500
sh.Run "http://127.0.0.1:8777/setup", 1, False
