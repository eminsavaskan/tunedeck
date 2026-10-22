"""YT Music Remote - Electron uygulamasina CDP ile baglanan katman."""
import asyncio, json, socket, time, urllib.parse, urllib.request
import websockets

DEBUG_PORT = 9223


class CDP:
    def __init__(self, port=DEBUG_PORT):
        self.port = port
        self.ws = None
        self.n = 0

    # ---------- HTTP tarafi ----------
    def _http(self, path, method="GET"):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", method=method)
        with urllib.request.urlopen(req, timeout=5) as r:
            body = r.read().decode("utf-8", "replace")
        return json.loads(body) if body.strip() else None

    def _port_open(self, timeout=0.25):
        """Kapali porta urllib ile 5 sn beklemek yerine hizli TCP kontrolu."""
        sk = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sk.settimeout(timeout)
        try:
            sk.connect(("127.0.0.1", self.port))
            return True
        except Exception:
            return False
        finally:
            try:
                sk.close()
            except Exception:
                pass

    def browser_up(self):
        if not self._port_open():
            return False
        try:
            self._http("/json/version")
            return True
        except Exception:
            return False

    def _pick(self):
        targets = self._http("/json/list") or []
        pages = [t for t in targets if t.get("type") == "page" and t.get("webSocketDebuggerUrl")]
        for t in pages:
            if "music.youtube.com" in (t.get("url") or ""):
                return t
        return pages[0] if pages else None

    def wait_page(self, timeout=25.0):
        """Uygulama acildiktan sonra sayfa hedefi hazir olana kadar bekle."""
        end = time.time() + timeout
        while time.time() < end:
            try:
                t = self._pick()
                if t:
                    return True
            except Exception:
                pass
            time.sleep(0.4)
        return False

    # ---------- WS ----------
    async def close(self):
        try:
            if self.ws is not None:
                await self.ws.close()
        except Exception:
            pass
        self.ws = None

    async def ensure(self):
        if self.ws is not None:
            # kapali/yarim socket'i tekrar kullanma
            st = getattr(getattr(self.ws, "state", None), "name", "")
            if st and st != "OPEN":
                await self.close()
        if self.ws is not None:
            return
        t = await asyncio.to_thread(self._pick)
        if t is None:
            raise RuntimeError("YouTube Music penceresi bulunamadi")
        self.ws = await websockets.connect(t["webSocketDebuggerUrl"], max_size=64 * 1024 * 1024,
                                          ping_interval=None, open_timeout=8)

    async def send(self, method, params=None, timeout=20):
        async with _lock:
            try:
                await self.ensure()
                self.n += 1
                mid = self.n
                await self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
                while True:
                    raw = await asyncio.wait_for(self.ws.recv(), timeout)
                    msg = json.loads(raw)
                    if msg.get("id") == mid:
                        if "error" in msg:
                            raise RuntimeError(str(msg["error"])[:200])
                        return msg.get("result", {})
            except Exception:
                await self.close()
                raise

    async def js(self, expr, timeout=20):
        r = await self.send("Runtime.evaluate", {
            "expression": expr, "returnByValue": True, "awaitPromise": True,
            "userGesture": True, "timeout": int(timeout * 1000),
        }, timeout=timeout)
        if r.get("exceptionDetails"):
            det = r["exceptionDetails"]
            raise RuntimeError((det.get("exception") or {}).get("description") or det.get("text") or "js error")
        return (r.get("result") or {}).get("value")


_lock = asyncio.Lock()
