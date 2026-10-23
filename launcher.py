"""TuneDeck — masaüstü başlatıcı (PyInstaller ile tek .exe).

Yaptığı iş:
  1) Köprü zaten çalışıyorsa sadece /setup sayfasını açar.
  2) İlk çalıştırmada YouTube Music kısayoluna uzaktan kumanda bayrağını ekler.
  3) Köprüyü arka planda başlatır ve /setup sayfasını tarayıcıda açar.
  4) Kapanana kadar (ya da /api/shutdown ile) çalışır.
"""
import os
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path

PORT = 8777
BASE = f"http://127.0.0.1:{PORT}"


def log(msg: str) -> None:
    try:
        d = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "TuneDeck"
        d.mkdir(parents=True, exist_ok=True)
        with open(d / "launcher.log", "a", encoding="utf-8") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n")
    except Exception:
        pass


def port_open(port: int = PORT) -> bool:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.3)
    try:
        return s.connect_ex(("127.0.0.1", port)) == 0
    finally:
        s.close()


def app_dir() -> Path:
    return Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent


def resource(rel: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", app_dir()))
    return base / rel


def first_run_shortcut() -> None:
    """Bir kez: YouTube Music kisayoluna --remote-debugging-port=9223 ekle."""
    if os.name != "nt":
        return
    marker = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "TuneDeck" / ".shortcut-done"
    if marker.exists():
        return
    ps1 = resource("tools/fix-shortcut.ps1")
    if not ps1.exists():
        return
    try:
        subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1)],
                       capture_output=True, timeout=60, creationflags=0x08000000)
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text("ok", encoding="utf-8")
        log("kisayol duzeltildi")
    except Exception as e:
        log("kisayol hatasi: %r" % (e,))


def main() -> None:
    if port_open():
        log("kopru zaten calisiyor, sayfa aciliyor")
        webbrowser.open(BASE + "/setup")
        return

    sys.path.insert(0, str(resource(".")))
    first_run_shortcut()

    import bridge  # noqa: E402  (paket icinden)

    threading.Thread(target=bridge.start_server, daemon=True).start()
    for _ in range(60):
        time.sleep(0.2)
        if port_open():
            break
    else:
        log("kopru acilmadi")
        return

    log("kopru hazir: " + BASE)
    try:
        webbrowser.open(BASE + "/setup")
    except Exception:
        pass

    while True:
        time.sleep(3600)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        import traceback
        log("COKME: " + traceback.format_exc()[-1500:])
        raise
