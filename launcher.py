"""TuneDeck — masaüstü başlatıcı (PyInstaller ile tek .exe).

İlk çalıştırma:
  1) Kendini %LOCALAPPDATA%\\TuneDeck\\ içine kopyalar (İndirilenler silinse de çalışsın).
  2) YouTube Music kısayoluna uzaktan kumanda bayrağını ekler.
  3) Kendini oturum açılışına kaydeder (sayfadan kapatılabilir).

Her çalıştırma:
  - Köprü zaten açıksa sessizce çıkar (çift başlatmada fazladan sekme açılmaz).
  - Değilse köprüyü başlatır ve /setup sayfasını tarayıcıda açar.
"""
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path

PORT = 8777
BASE = f"http://127.0.0.1:{PORT}"
DATA = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "TuneDeck"


def log(msg: str) -> None:
    try:
        DATA.mkdir(parents=True, exist_ok=True)
        with open(DATA / "launcher.log", "a", encoding="utf-8") as f:
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


def frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def app_dir() -> Path:
    return Path(sys.executable).resolve().parent if frozen() else Path(__file__).resolve().parent


def resource(rel: str) -> Path:
    return Path(getattr(sys, "_MEIPASS", app_dir())) / rel


def open_page_once() -> None:
    """Tarayıcıyı en fazla 60 saniyede bir aç (açılışta iki kez tetiklenirse tek sekme)."""
    try:
        stamp = DATA / ".last-open"
        now = time.time()
        if stamp.exists():
            try:
                if now - float(stamp.read_text(encoding="utf-8") or 0) < 60:
                    return
            except Exception:
                pass
        stamp.write_text(str(now), encoding="utf-8")
    except Exception:
        pass
    try:
        webbrowser.open(BASE + "/setup")
    except Exception:
        pass


def install_copy() -> None:
    """Kendini kalıcı bir yere kopyala."""
    if not frozen():
        return
    src = Path(sys.executable).resolve()
    try:
        DATA.mkdir(parents=True, exist_ok=True)
        dst = DATA / "TuneDeck.exe"
        if src != dst and (not dst.exists() or dst.stat().st_size != src.stat().st_size):
            shutil.copy2(src, dst)
            log("kopyalandi: %s" % dst)
    except Exception as e:
        log("kopyalama hatasi: %r" % (e,))


def first_run() -> None:
    marker = DATA / ".installed"
    if marker.exists():
        return

    ps1 = resource("tools/fix-shortcut.ps1")
    if ps1.exists() and os.name == "nt":
        try:
            subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1)],
                           capture_output=True, timeout=60, creationflags=0x08000000)
            log("kisayol duzeltildi")
        except Exception as e:
            log("kisayol hatasi: %r" % (e,))

    try:
        import winreg

        target = DATA / "TuneDeck.exe"
        if not target.exists():
            target = Path(sys.executable).resolve()
        key = r"Software\Microsoft\Windows\CurrentVersion\Run"
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key, 0, winreg.KEY_SET_VALUE) as k:
            winreg.SetValueEx(k, "TuneDeck", 0, winreg.REG_SZ, '"%s"' % target)
        log("acilisa eklendi: %s" % target)
    except Exception as e:
        log("acilis kaydi hatasi: %r" % (e,))

    try:
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text("ok", encoding="utf-8")
    except Exception:
        pass


def main() -> None:
    if port_open():
        log("kopru zaten calisiyor")
        open_page_once()
        return

    sys.path.insert(0, str(app_dir()))
    install_copy()

    import bridge  # noqa: E402

    first_run()
    threading.Thread(target=bridge.start_server, daemon=True).start()

    for _ in range(60):
        time.sleep(0.2)
        if port_open():
            break
    else:
        log("kopru acilmadi")
        return

    log("kopru hazir: " + BASE)
    open_page_once()

    while True:
        time.sleep(3600)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        import traceback

        log("COKME: " + traceback.format_exc()[-1500:])
        raise
