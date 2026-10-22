#!/usr/bin/env python3
"""
YT Music Remote — kopru sunucusu.

Bilgisayarda calisan acik kaynak "YouTube Music" (Electron) uygulamasini
Chromium DevTools Protokolu uzerinden kontrol eder ve ayni agdaki telefonlar
icin bir web uygulamasi (PWA) yayinlar.

Calistir:  python bridge.py
"""
import asyncio, io, json, os, re, socket, subprocess, sys, time, unicodedata, urllib.parse
from contextlib import asynccontextmanager
from pathlib import Path

import qrcode
import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse, Response, PlainTextResponse

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cdp import CDP

HERE = Path(__file__).resolve().parent

# pythonw.exe ile calisirken stdout/stderr NULL olur; loglamayi dosyaya yonlendir
if sys.stdout is None or sys.stderr is None:
    _lf = open(HERE / "bridge.log", "a", encoding="utf-8", buffering=1)
    if sys.stdout is None:
        sys.stdout = _lf
    if sys.stderr is None:
        sys.stderr = _lf

PORT = int(os.environ.get("YTM_PORT", "8777"))
DEBUG_PORT = int(os.environ.get("YTM_DEBUG_PORT", "9223"))
APP_EXE = os.path.expandvars(os.environ.get(
    "YTM_APP", r"%LOCALAPPDATA%\Programs\youtube-music\YouTube Music.exe"))

# settings.json: {"browser": "C:/.../brave.exe"} doluysa masaustu uygulama yerine
# resmi siteyi normal tarayicida calistirir (ayni kopru, ayni seciciler).
_cfg = {}
try:
    _cfgf = HERE / "settings.json"
    if _cfgf.exists():
        _cfg = json.loads(_cfgf.read_text(encoding="utf-8")) or {}
except Exception:
    _cfg = {}

if _cfg.get("app"):
    APP_EXE = os.path.expandvars(str(_cfg["app"]))

BROWSER_EXE = os.environ.get("YTM_BROWSER") or _cfg.get("browser", "") or ""
BROWSER_PROFILE = os.environ.get("YTM_BROWSER_PROFILE") or str(HERE / "browser-profile")

cdp = CDP(DEBUG_PORT)
_app_lock = asyncio.Lock()
_last_launch = 0.0


# ------------------------------------------------------------------ uygulama
def app_running():
    try:
        out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq YouTube Music.exe", "/NH"],
                             capture_output=True, timeout=10)
        return b"YouTube Music.exe" in out.stdout
    except Exception:
        return False


def kill_app():
    try:
        subprocess.run(["taskkill", "/F", "/IM", "YouTube Music.exe"],
                       capture_output=True, timeout=15)
    except Exception:
        pass


def launch_app():
    if BROWSER_EXE:
        exe = os.path.expandvars(BROWSER_EXE)
        if not os.path.exists(exe):
            print(f"Tarayici bulunamadi: {exe}")
            return False
        subprocess.Popen([exe, f"--remote-debugging-port={DEBUG_PORT}",
                          f"--user-data-dir={BROWSER_PROFILE}",
                          "--no-first-run", "--no-default-browser-check",
                          "https://music.youtube.com/"],
                         creationflags=0x00000008, close_fds=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    if not os.path.exists(APP_EXE):
        return False
    subprocess.Popen([APP_EXE, f"--remote-debugging-port={DEBUG_PORT}"],
                     creationflags=0x00000008, close_fds=True,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return True


def ensure_app_sync():
    """Uygulama kapaliysa ya da debug portu olmadan calisiyorsa duzgun sekilde baslat."""
    global _last_launch
    if cdp.browser_up():
        return True
    if time.time() - _last_launch < 25:
        return False
    _last_launch = time.time()
    if not BROWSER_EXE and app_running():
        kill_app()
        time.sleep(2)
    if not launch_app():
        return False
    for _ in range(60):
        time.sleep(0.5)
        if cdp.browser_up():
            if cdp.wait_page(25):
                _last_launch = 0.0           # basarili: kilidi ac
                time.sleep(3.0)              # oynatici arayuzu yuklensin
                return True
            _last_launch = 0.0
            return True
    return False


async def ensure_app():
    """Sadece baglanmayi dener. Uygulamayi KENDI KENDINE baslatmaz:
    kullanici PC'de kapatinca kapali kalir, telefondan 'Ac' ile baslatilir."""
    async with _app_lock:
        up = await asyncio.to_thread(cdp.browser_up)
        if not up:
            try:
                await cdp.close()          # bayat socket'i kapat, sonraki istek temiz baglansin
            except Exception:
                pass
            raise RuntimeError("YouTube Music kapalı — bilgisayarda uygulamayı aç")
        return True


# ------------------------------------------------------------------ js parcalari
STATE_JS = r"""
(() => {
  const q = s => document.querySelector(s);
  const v = q('video');
  const md = navigator.mediaSession && navigator.mediaSession.metadata;
  const txt = e => e ? (e.textContent || '').trim().replace(/\s+/g, ' ') : '';
  const bar = q('ytmusic-player-bar');
  const img = bar && bar.querySelector('img');
  const vs = q('ytmusic-player-bar #volume-slider') || q('#volume-slider');
  const pb = q('ytmusic-player-bar #progress-bar');
  const art = (img && img.src) || (md && md.artwork && md.artwork.length ? md.artwork[md.artwork.length-1].src : '') || '';
  return JSON.stringify({
    logged_in: !!(window.ytcfg && ytcfg.get && ytcfg.get('LOGGED_IN')),
    title: txt(bar && bar.querySelector('.title')) || (md ? md.title : '') || '',
    byline: txt(bar && bar.querySelector('.byline')) || (md ? md.artist : '') || '',
    art: art,
    playing: v ? !v.paused : false,
    position: pb ? Number(pb.value) || 0 : (v && isFinite(v.currentTime) ? v.currentTime : 0),
    duration: pb ? Number(pb.max) || 0 : (v && isFinite(v.duration) ? v.duration : 0),
    volume: v ? v.volume : 0,
    volume_pct: vs ? Number(vs.value) : (v ? Math.round(v.volume * 100) : 0),
    muted: v ? !!v.muted : false,
    video_id: (() => {
      const fromUrl = new URLSearchParams(location.search).get('v');
      if (fromUrl) return fromUrl;
      const a = q('ytmusic-player-bar .title a') || q('ytmusic-player-bar a[href*="v="]');
      const m = a ? (a.getAttribute('href') || '').match(/v=([A-Za-z0-9_-]{6,})/) : null;
      return m ? m[1] : '';
    })()
  });
})()
"""

TOGGLE_JS = r"""
(() => {
  const v = document.querySelector('video');
  const b = document.querySelector('#play-pause-button') || document.querySelector('ytmusic-play-button-renderer');
  if (b) { (b.querySelector('button') || b).click(); return 'button'; }
  if (v) { v.paused ? v.play() : v.pause(); return 'video'; }
  return 'none';
})()
"""

NEXT_JS = r"""(() => { const b = document.querySelector('.next-button'); if (!b) return 'none'; (b.querySelector('button') || b).click(); return 'ok'; })()"""
PREV_JS = r"""(() => { const b = document.querySelector('.previous-button'); if (!b) return 'none'; (b.querySelector('button') || b).click(); return 'ok'; })()"""

VOL_JS = r"""(pct) => {
  const s = document.querySelector('ytmusic-player-bar #volume-slider') || document.querySelector('#volume-slider');
  const v = document.querySelector('video');
  if (s) {
    const p = Math.max(0, Math.min(100, Math.round(pct)));
    s.value = p;
    s.dispatchEvent(new CustomEvent('value-change', { detail: { value: p }, bubbles: true, composed: true }));
    s.dispatchEvent(new Event('change', { bubbles: true }));
    if (v && v.muted) v.muted = false;
    return p;
  }
  if (v) { v.muted = false; v.volume = Math.max(0, Math.min(1, pct / 100)); return Math.round(v.volume * 100); }
  return null;
}"""

MUTE_JS = r"""(() => { const e = document.querySelector('video'); if (!e) return null; e.muted = !e.muted; return e.muted; })()"""

SEEK_JS = r"""(t) => {
  const pb = document.querySelector('ytmusic-player-bar #progress-bar');
  if (pb) {
    const max = Number(pb.max) || 0;
    const val = Math.max(0, Math.min(max, t));
    pb.value = val;
    pb.dispatchEvent(new CustomEvent('value-change', { detail: { value: val }, bubbles: true, composed: true }));
    pb.dispatchEvent(new Event('change', { bubbles: true }));
    return val;
  }
  const e = document.querySelector('video');
  if (!e) return null;
  e.currentTime = Math.max(0, Math.min(t, (e.duration || t)));
  return e.currentTime;
}"""

FOCUS_JS = r"""
(() => {
  const sb = document.querySelector('ytmusic-search-box');
  if (!sb) return 'no-box';
  const inp = sb.querySelector('input');
  if (!inp) return 'no-input';
  if (!inp.offsetParent || inp.offsetWidth < 40) {
    const btn = sb.querySelector('#button, button, yt-icon-button');
    if (btn) btn.click();
  }
  inp.focus(); inp.click();
  try { inp.setSelectionRange(0, (inp.value || '').length); } catch (e) {}
  return document.activeElement === inp ? 'ok' : 'nofocus';
})()
"""

KEY_ENTER_DOWN = {"type": "keyDown", "key": "Enter", "code": "Enter", "text": "\r",
                  "unmodifiedText": "\r", "windowsVirtualKeyCode": 13, "nativeVirtualKeyCode": 13}
KEY_ENTER_CHAR = {"type": "char", "key": "Enter", "text": "\r", "unmodifiedText": "\r"}
KEY_ENTER_UP = {"type": "keyUp", "key": "Enter", "code": "Enter",
                "windowsVirtualKeyCode": 13, "nativeVirtualKeyCode": 13}

RESULTS_JS = r"""
(() => {
  const items = [], seen = new Set();
  const clean = s => (s || '').replace(/\s+/g, ' ').trim();
  const bad = s => !s || /^(Şarkı|Video|Albüm|Single|EP|Podcast|Çalma Listesi|Song|Album)$/i.test(s)
                    || /kez dinlendi|görüntüleme|views|dinlenme/i.test(s);
  const visible = r => {
    if (r.closest('ytmusic-card-shelf-renderer')) return true;   // "en iyi sonuc" karti
    if (r.offsetParent === null) return !!r.closest('#items');
    const b = r.getBoundingClientRect();
    return b.width > 80 && b.height > 18;
  };
  const grab = row => {
    if (!visible(row)) return;                      // gizli/onbellek satirlarini at
    const a = row.querySelector('a#thumbnail') || row.querySelector('a[href*="v="]');
    if (!a) return;
    const href = a.getAttribute('href') || '';
    const m = href.match(/[?&]v=([A-Za-z0-9_-]{6,})/);
    if (!m || seen.has(m[1])) return;
    seen.add(m[1]);
    const title = clean((row.querySelector('.title') || {}).textContent || '');
    const sec = [...row.querySelectorAll('.secondary-flex-columns > *')].map(e => clean(e.textContent));
    const parts = (sec[0] || '').split('•').map(s => s.trim()).filter(Boolean);
    const artist = parts.filter(s => !bad(s))[0] || '';
    const album = (sec[1] && !bad(sec[1])) ? sec[1] : '';
    const dur = /^\d+:\d{2}$/.test(sec[2] || '') ? sec[2] : '';
    const img = row.querySelector('img');
    const typeRaw = (sec[0] || '').split('•')[0].trim();
    const type = /şarkı|song/i.test(typeRaw) ? 'song' : (/video/i.test(typeRaw) ? 'video' : '');
    items.push({ id: m[1], title: title || 'Bilinmeyen', type: type,
                 sub: [artist, album, dur].filter(Boolean).join(' • '),
                 img: img ? (img.src || '') : '', href });
  };
  // once ana sonuc bolumleri (sarkilar/videolar), sonra kalan kartlar
  document.querySelectorAll('ytmusic-item-section-renderer').forEach(sec =>
    sec.querySelectorAll('ytmusic-responsive-list-item-renderer').forEach(grab));
  document.querySelectorAll('ytmusic-responsive-list-item-renderer, ytmusic-two-row-item-renderer').forEach(grab);
  return JSON.stringify(items.slice(0, 20));
})()
"""

SCROLL_JS = r"""(() => { window.scrollBy(0, 1100); return 1; })()"""

PLAY_JS = r"""
(vid) => {
  const cur = (new URLSearchParams(location.search)).get('v');
  if (cur === vid) {
    const v = document.querySelector('video');
    if (v && v.paused) { const b = document.querySelector('#play-pause-button'); if (b) (b.querySelector('button') || b).click(); }
    return 'already';
  }
  const links = [...document.querySelectorAll('a[href*="v="]')];
  const el = links.find(a => (a.getAttribute('href') || '').includes('v=' + vid));
  if (el) { el.click(); return 'clicked'; }
  return 'none';
}
"""


def jsarg(val):
    return "(function(v){" + "" + "})(%s)" % json.dumps(val)


def _norm(s):
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


async def ev(expr):
    await ensure_app()
    return await cdp.js(expr)


def big_art(url):
    if not url:
        return ""
    return re.sub(r"=w\d+-h\d+", "=w544-h544", url)


def clean_byline(s):
    return re.sub(r"\s*•\s*", " • ", (s or "").strip())


async def read_state():
    raw = await ev(STATE_JS)
    st = json.loads(raw) if raw else {}
    st["art"] = big_art(st.get("art", ""))
    st["byline"] = clean_byline(st.get("byline", ""))
    st["online"] = True
    return st


async def run_state():
    last = None
    for attempt in range(2):
        try:
            return await read_state()
        except Exception as e:
            last = e
            if "kapalı" in str(e):
                break                      # uygulama kapali: tekrar denemenin anlami yok
            if attempt == 0:
                await asyncio.sleep(0.6)
                try:
                    await ensure_app()
                except Exception:
                    pass
    return {"online": False, "error": str(last)[:160], "title": "", "byline": "", "art": "",
            "playing": False, "position": 0, "duration": 0, "volume": 0, "volume_pct": 0, "muted": False,
            "logged_in": False}


async def act(expr, *args):
    try:
        await ensure_app()
        r = await cdp.js(expr)
        return {"ok": True, "result": r, "state": await read_state()}
    except Exception as e:
        return {"ok": False, "error": str(e)[:160], "state": await run_state()}


# ------------------------------------------------------------------ sunucu
@asynccontextmanager
async def lifespan(_app):
    print("\n" + "=" * 58)
    print("  YT Music Remote hazir")
    print(f"  Telefondan ac:  {base_url()}")
    print(f"  Oyuncu:         {'tarayici: ' + BROWSER_EXE if BROWSER_EXE else 'YouTube Music uygulamasi'}")
    print("=" * 58)
    if _cfg.get("autostart"):
        asyncio.create_task(asyncio.to_thread(ensure_app_sync))
        print("  Otomatik baslatma: ACIK")
    else:
        print("  Otomatik baslatma: kapali (uygulamayi sen acarsin)")
    yield
    await cdp.close()


app = FastAPI(title="YT Music Remote", lifespan=lifespan)


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def base_url():
    return f"http://{lan_ip()}:{PORT}"


def _token():
    try:
        return str(_cfg.get("token") or "").strip()
    except Exception:
        return ""


@app.middleware("http")
async def _auth(request: Request, call_next):
    tok = _token()
    if tok and request.url.path.startswith("/api/"):
        got = request.query_params.get("t") or request.headers.get("x-token") or ""
        if got != tok:
            return JSONResponse({"ok": False, "error": "unauthorized"}, status_code=401)
    return await call_next(request)


@app.get("/", response_class=HTMLResponse)
async def index():
    html = (HERE / "ui.html").read_text(encoding="utf-8")
    tok = _token()
    if tok:
        inject = "<script>window.__TOKEN__=" + json.dumps(tok) + ";</script>"
        html = html.replace('<script src="/i18n.js"></script>', inject + '<script src="/i18n.js"></script>', 1)
    return html


@app.get("/setup", response_class=HTMLResponse)
async def setup_page():
    url = base_url()
    return f"""<!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>YT Music Remote — bağlan</title>
<style>
body{{margin:0;min-height:100vh;display:grid;place-items:center;background:#08080b;color:#f2f2f5;
font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;text-align:center;padding:28px}}
h1{{font-size:20px;margin:0 0 6px}} p{{color:#8b8b99;font-size:13px;margin:0 0 22px;line-height:1.5}}
img{{width:240px;height:240px;border-radius:16px;background:#fff;padding:10px}}
code{{display:inline-block;margin-top:18px;background:#17171d;border:1px solid #26262f;padding:10px 14px;
border-radius:10px;font-size:15px;color:#fff}}
a{{display:inline-block;margin-top:16px;color:#ff2d55;font-size:13px;text-decoration:none}}
</style></head><body><div>
<h1>YT Music Remote</h1>
<p>Telefonun aynı Wi-Fi ağında olmalı.<br>QR'ı okut veya adresi tarayıcıya yaz, sonra<br>
tarayıcı menüsünden <b>“Ana ekrana ekle”</b> de.</p>
<img src="/qr.png" alt="QR">
<div><code>{url}</code></div>
<a href="/">Uygulamayı bu bilgisayarda aç →</a>

<hr style="margin:26px 0;border:0;border-top:1px solid #26262f">
<p style="margin-bottom:14px"><b>Android uygulaması (APK)</b><br>
Bildirim paneli, kilit ekranı ve ana ekran widget'ı için.</p>
<a href="/app.apk" style="display:inline-block;background:#ff2d55;color:#fff;text-decoration:none;
padding:12px 22px;border-radius:12px;font-size:14px;font-weight:600">APK'yı indir</a>
<div style="margin-top:18px"><img src="/qr.png?u=apk" alt="APK QR"
style="width:150px;height:150px;padding:8px;border-radius:14px;background:#fff"></div>
<p style="font-size:11.5px;margin-top:6px;color:#6a6a78">QR'ı telefonla okut — APK doğrudan iner</p>
</div></body></html>"""


@app.get("/api/state")
async def api_state():
    return {"ok": True, "state": await run_state()}


@app.post("/api/toggle")
async def api_toggle():
    return await act(TOGGLE_JS)


@app.post("/api/next")
async def api_next():
    return await act(NEXT_JS)


@app.post("/api/prev")
async def api_prev():
    return await act(PREV_JS)


@app.post("/api/volume")
async def api_volume(req: Request):
    body = await req.json()
    if "pct" in body:
        pct = max(0, min(100, int(round(float(body["pct"])))))
    else:
        pct = max(0, min(100, int(round(float(body.get("v", 0.5)) * 100))))
    return await act(f"({VOL_JS})({pct})")


@app.post("/api/mute")
async def api_mute():
    return await act(MUTE_JS)


@app.post("/api/seek")
async def api_seek(req: Request):
    body = await req.json()
    t = max(0.0, float(body.get("t", 0)))
    return await act(f"({SEEK_JS})({t})")


@app.post("/api/search")
async def api_search(req: Request):
    body = await req.json()
    q = (body.get("q") or "").strip()
    if not q:
        return {"ok": True, "items": []}
    try:
        await ensure_app()
        if await cdp.js(FOCUS_JS) == "no-box":
            return {"ok": False, "error": "arama kutusu bulunamadi", "items": []}
        await cdp.send("Input.insertText", {"text": q})
        await asyncio.sleep(0.35)
        for ev in (KEY_ENTER_DOWN, KEY_ENTER_CHAR, KEY_ENTER_UP):
            await cdp.send("Input.dispatchKeyEvent", ev)
        # arama sayfasina gecisi bekle: URL /search?q=... olana kadar
        want = _norm(q)
        for _ in range(24):
            await asyncio.sleep(0.25)
            href = await cdp.js("location.href") or ""
            if "/search" in href:
                got = _norm(urllib.parse.unquote(href.split("q=")[-1].split("&")[0]).replace("+", " "))
                if not got or want in got or got in want:
                    break
        await asyncio.sleep(0.7)
        # sonuclar oturana kadar bekle (eski sayfadan kalan satirlar karismasin)
        items, stable, last = [], 0, -1
        for _ in range(20):
            cur = json.loads(await cdp.js(RESULTS_JS) or "[]")
            if len(cur) >= 5 and len(cur) == last:
                stable += 1
            else:
                stable = 0
            items, last = cur, len(cur)
            if stable >= 2:
                break
            await asyncio.sleep(0.3)
        if not items:
            await cdp.js(SCROLL_JS)
            await asyncio.sleep(0.9)
            items = json.loads(await cdp.js(RESULTS_JS) or "[]")
        out = [{"id": it["id"],
                "title": (it.get("title") or "Bilinmeyen").strip(),
                "type": it.get("type", ""),
                "sub": (it.get("sub") or "")[:90],
                "img": thumb_for({"img": it.get("img"), "id": it["id"]}, "song"),
                "href": it.get("href")} for it in items]
        return {"ok": True, "items": rank_results(out, q)}
    except Exception as e:
        return {"ok": False, "error": str(e)[:160], "items": []}


@app.post("/api/play")
async def api_play(req: Request):
    body = await req.json()
    vid = (body.get("id") or "").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{6,20}", vid):
        return {"ok": False, "error": "gecersiz sarki kimligi"}
    try:
        await ensure_app()
        r = await cdp.js(f"({PLAY_JS})({json.dumps(vid)})")
        if r == "none":
            await cdp.send("Page.navigate", {"url": f"https://music.youtube.com/watch?v={vid}"})
            await asyncio.sleep(4.0)
        else:
            await asyncio.sleep(1.8)
        # bazi sonuclar tiklamada duraklatilmis baslar
        st = await read_state()
        if not st.get("playing"):
            await cdp.js(TOGGLE_JS)
            await asyncio.sleep(0.6)
        return {"ok": True, "result": r, "state": await read_state()}
    except Exception as e:
        return {"ok": False, "error": str(e)[:160], "state": await run_state()}


@app.get("/app.apk")
async def app_apk():
    f = HERE / "ytm-remote.apk"
    if not f.exists():
        return JSONResponse({"ok": False, "error": "APK henuz derlenmemis"}, status_code=404)
    return Response(f.read_bytes(), media_type="application/vnd.android.package-archive",
                    headers={"Content-Disposition": 'attachment; filename="ytm-remote.apk"'})


@app.get("/manifest.webmanifest")
async def manifest():
    return JSONResponse({
        "name": "YT Music Remote", "short_name": "YTM Remote",
        "start_url": "/", "scope": "/", "display": "standalone", "orientation": "portrait",
        "background_color": "#08080b", "theme_color": "#08080b",
        "icons": [
            {"src": "/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any maskable"},
            {"src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any maskable"},
        ],
    }, media_type="application/manifest+json")


@app.get("/sw.js")
async def sw():
    code = """
const C = 'ytm-remote-v2';
self.addEventListener('install', e => { self.skipWaiting(); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== C).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (u.pathname.startsWith('/api/')) return;            // canli veri, cache yok
  if (e.request.method !== 'GET') return;
  // once ag (her zaman guncel arayuz), cevrimdisiysa onbellek
  e.respondWith(
    fetch(e.request).then(r => {
      const copy = r.clone();
      caches.open(C).then(c => c.put(e.request, copy)).catch(() => {});
      return r;
    }).catch(() => caches.match(e.request))
  );
});
"""
    return Response(code, media_type="application/javascript")


@app.get("/icon-192.png")
async def icon192():
    return Response((HERE / "icon-192.png").read_bytes(), media_type="image/png")


@app.get("/icon-512.png")
async def icon512():
    return Response((HERE / "icon-512.png").read_bytes(), media_type="image/png")


@app.get("/qr.png")
async def qr(u: str = ""):
    img = qrcode.make(base_url() + "/app.apk" if u == "apk" else base_url())
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return Response(buf.getvalue(), media_type="image/png")


@app.post("/api/debug")
async def api_debug(req: Request):
    """Telefonun teshis raporunu dosyaya yazar."""
    try:
        raw = await req.body()
        txt = raw.decode("utf-8", "replace")[:8000]
        with open(HERE / "debug.log", "a", encoding="utf-8") as f:
            f.write(txt.rstrip() + chr(10))
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)[:120]}


@app.get("/api/debug")
async def api_debug_read():
    f = HERE / "debug.log"
    if not f.exists():
        return PlainTextResponse("(rapor yok)")
    return PlainTextResponse(f.read_text(encoding="utf-8")[-20000:])


@app.post("/api/launch")
async def api_launch(req: Request):
    """Uygulamayi telefondan elle baslatmak icin (otomatik baslatma kapali)."""
    body = {}
    try:
        body = await req.json() or {}
    except Exception:
        pass
    if await asyncio.to_thread(cdp.browser_up):
        return {"ok": True, "already": True, "state": await run_state()}
    restart = bool(body.get("restart"))
    running = False if BROWSER_EXE else await asyncio.to_thread(app_running)
    if running and not restart:
        return {"ok": False, "need_restart": True,
                "error": "Uygulama açık ama uzaktan kumanda kapalı"}
    ok = await asyncio.to_thread(ensure_app_sync)
    if not ok:
        return {"ok": False, "error": "Uygulama başlatılamadı"}
    return {"ok": True, "state": await run_state()}


@app.get("/i18n.js")
async def i18n_js():
    return Response((HERE / "i18n.js").read_bytes(), media_type="application/javascript")


@app.get("/api/info")
async def info():
    return {"ok": True, "url": base_url(), "device": socket.gethostname(),
            "app_up": await asyncio.to_thread(cdp.browser_up)}



# ------------------------------------------------------------------ gezinme + raflar
GUIDE_JS = r"""(i) => {
  const es = [...document.querySelectorAll('ytmusic-guide-entry-renderer, ytmusic-mini-guide-entry-renderer')];
  if (!es[i]) return 'yok';
  es[i].click();
  return 'ok';
}"""

NAV_HREF_JS = r"""(href) => {
  const a = [...document.querySelectorAll('a[href]')].find(x => x.getAttribute('href') === href);
  if (!a) return 'yok';
  a.click();
  return 'ok';
}"""

CARDS_JS = r"""
(onlyLists) => {
  const clean = s => (s || '').replace(/\s+/g, ' ').trim();
  const vis = e => e && e.offsetParent !== null;
  // YT Music gorselleri tembel yukler: gercek URL Polymer verisinde duruyor
  const thumb = c => {
    const d = c.data || c.__data || null;
    if (!d) return '';
    let best = '';
    JSON.stringify(d).split('"').forEach(p => {
      if (p.indexOf('http') === 0 && (p.indexOf('googleusercontent') >= 0 || p.indexOf('ytimg') >= 0)
          && p.length > best.length) best = p;
    });
    return best;
  };
  const out = [], seen = new Set();
  const roots = [...document.querySelectorAll(
    'ytmusic-carousel-shelf-renderer, ytmusic-shelf-renderer, ytmusic-card-shelf-renderer, ytmusic-grid-renderer')];
  const shelves = [];
  for (const r of roots) {
    if (!vis(r)) continue;
    const st = clean((r.querySelector('#title, .title, h2') || {}).textContent);
    const items = [];
    r.querySelectorAll('ytmusic-two-row-item-renderer, ytmusic-responsive-list-item-renderer').forEach(c => {
      if (!vis(c)) return;
      const a = c.querySelector('a[href]');
      if (!a) return;
      const href = a.getAttribute('href') || '';
      if (!href || seen.has(href)) return;
      if (!/^(watch\?v=|playlist\?list=|browse\/)/.test(href)) return;
      if (onlyLists && !/^(playlist\?list=|browse\/)/.test(href)) return;
      seen.add(href);
      const img = c.querySelector('img');
      const src = (img && img.src && img.src.indexOf('data:') !== 0) ? img.src : thumb(c);
      const sub = [...c.querySelectorAll('.subtitle, .secondary-flex-columns yt-formatted-string, .byline')]
        .map(e => clean(e.textContent)).filter(Boolean).join(' • ');
      items.push({
        href,
        title: clean((c.querySelector('.title') || {}).textContent).slice(0, 90),
        sub: sub.slice(0, 70),
        img: src
      });
    });
    if (items.length) shelves.push({ title: st, items: items.slice(0, 18) });
  }
  return JSON.stringify(shelves.slice(0, 8));
}
"""

LIST_JS = r"""
() => {
  const clean = s => (s || '').replace(/\s+/g, ' ').trim();
  const vis = e => e && e.offsetParent !== null;
  const header = document.querySelector('ytmusic-detail-header-renderer, ytmusic-editable-playlist-detail-header-renderer, ytmusic-responsive-header-renderer');
  const headerVisible = header && vis(header);
  const title = clean(headerVisible ? ((header.querySelector('#title, .title') || {}).textContent || '') : '');
  const hImg = headerVisible ? header.querySelector('img') : null;
  const cover = hImg ? (hImg.src || '') : '';
  const items = [], seen = new Set();
  const rows = [...document.querySelectorAll('ytmusic-responsive-list-item-renderer')];
  for (const r of rows) {
    if (!vis(r)) continue;
    const a = r.querySelector('a[href*="v="]');
    if (!a) continue;
    const m = (a.getAttribute('href') || '').match(/[?&]v=([A-Za-z0-9_-]{6,})/);
    if (!m || seen.has(m[1])) continue;
    seen.add(m[1]);
    const t = clean((r.querySelector('.title') || {}).textContent);
    const sec = [...r.querySelectorAll('.secondary-flex-columns > *')].map(e => clean(e.textContent));
    const parts = (sec[0] || '').split('•').map(s => s.trim()).filter(Boolean);
    const artist = parts.filter(p => p && !/^(Şarkı|Video|Albüm|Song)$/i.test(p)
      && !/kez dinlendi|görüntüleme|views/i.test(p))[0] || '';
    const dur = /^\d+:\d{2}$/.test(sec[2] || '') ? sec[2] : '';
    const img = r.querySelector('img');
    items.push({ id: m[1], title: t, sub: [artist, dur].filter(Boolean).join(' • '),
                 img: img ? (img.src || '') : '' });
  }
  return JSON.stringify({ title, cover, items: items.slice(0, 250) });
}
"""


def thumb_for(item, kind):
    """YT Music gorselleri tembel yuklenir; sarki kapaklarini video id'den turet."""
    img = big_art(item.get("img", "") or "")
    if img.startswith("data:"):          # tembel yukleme placeholder'i
        img = ""
    if not img and kind == "song" and item.get("id"):
        img = f"https://i.ytimg.com/vi/{item['id']}/mqdefault.jpg"
    return img

_TR = str.maketrans({"ı": "i", "İ": "i", "ş": "s", "Ş": "s", "ğ": "g", "Ğ": "g",
                     "ç": "c", "Ç": "c", "ö": "o", "Ö": "o", "ü": "u", "Ü": "u",
                     "â": "a", "î": "i", "û": "u", "&": " ve "})


def _fold(s):
    """Turkce harfleri sadelestirip karsilastirilabilir hale getirir."""
    s = (s or "").translate(_TR).lower()
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def rank_results(items, q):
    """Arama sonuclarini sorguya gore puanlar; en alakali uste gelir."""
    fq = _fold(q)
    qtok = [t for t in fq.split() if len(t) > 1] or fq.split()
    if not qtok or not items:
        return items

    def score(it):
        t = _fold(it.get("title", ""))
        a = _fold(it.get("sub", ""))
        sc = 0.0
        if t == fq:
            sc += 5.0
        elif t.startswith(fq):
            sc += 3.0
        elif fq and fq in t:
            sc += 2.0
        tt, at = set(t.split()), set(a.split())
        hit_t = sum(1 for k in qtok if k in tt or any(w.startswith(k) for w in tt))
        hit_a = sum(1 for k in qtok if k in at)
        sc += 3.0 * hit_t / len(qtok)
        sc += 1.5 * hit_a / len(qtok)
        if it.get("type") == "song":
            sc += 0.8          # album sarkisi > rastgele video yuklemesi
        return sc

    scored = [(score(it), it) for it in items]
    # sorguyla hic ortusmeyen satirlari at (eski aramadan kalanlar / alakasiz oneriler)
    good = [x for x in scored if x[0] >= 0.5]
    if len(good) >= 3:
        scored = good
    scored.sort(key=lambda x: x[0], reverse=True)
    return [it for _, it in scored]


def classify(href):
    """DOM href'ini {kind,id} haline getirir."""
    if not href:
        return None
    if href.startswith("watch") and "v=" in href:
        return {"kind": "song", "id": href.split("v=")[1].split("&")[0]}
    if href.startswith("playlist?list="):
        return {"kind": "list", "id": href.split("list=")[1].split("&")[0]}
    if href.startswith("browse/"):
        return {"kind": "list", "id": href.split("/", 1)[1]}
    return None


# rota takibi: gereksiz gezinmeyi onler
_route = {"at": "", "ts": 0.0}


def _at(cur, expect):
    """Rota eslesmesi: '/' her seyin alt dizgisi oldugu icin tam kontrol sart."""
    cur = cur or "/"
    if expect == "/":
        return cur == "/"
    return cur == expect or cur.startswith(expect + "/")


async def go_guide(idx, expect):
    """Ust menu girisini tiklayarak sayfa degistirir (muzik kesilmez)."""
    cur = await cdp.js("location.pathname")
    if _at(cur, expect):
        return True
    await cdp.js(f"({GUIDE_JS})({idx})")
    for _ in range(24):
        await asyncio.sleep(0.25)
        if _at(await cdp.js("location.pathname"), expect):
            await asyncio.sleep(0.8)
            return True
    return False


async def scrape_shelves(only_lists=False):
    raw = await cdp.js(f"({CARDS_JS})({'true' if only_lists else 'false'})")
    try:
        shelves = json.loads(raw or "[]")
    except Exception:
        shelves = []
    out = []
    for sh in shelves:
        items = []
        for it in sh.get("items", []):
            c = classify(it.get("href"))
            if not c:
                continue
            items.append({**c, "href": it["href"], "title": it.get("title", ""),
                          "sub": it.get("sub", ""), "img": thumb_for({**it, "id": c["id"]}, c["kind"])})
        if items:
            out.append({"title": sh.get("title") or "Öneriler", "items": items})
    return out


@app.get("/api/home")
async def api_home():
    try:
        await ensure_app()
        await go_guide(0, "/")
        shelves = await scrape_shelves(False)
        if not shelves:
            await asyncio.sleep(1.2)
            shelves = await scrape_shelves(False)
        return {"ok": True, "shelves": shelves, "state": await read_state()}
    except Exception as e:
        return {"ok": False, "error": str(e)[:160], "shelves": []}


@app.get("/api/library")
async def api_library():
    try:
        await ensure_app()
        await go_guide(2, "/library")
        shelves = await scrape_shelves(True)
        flat = []
        seen = set()
        for sh in shelves:
            for it in sh["items"]:
                key = it["id"]
                if key in seen:
                    continue
                seen.add(key)
                flat.append(it)
        if not flat:
            await asyncio.sleep(1.2)
            shelves = await scrape_shelves(True)
            for sh in shelves:
                for it in sh["items"]:
                    if it["id"] not in seen:
                        seen.add(it["id"])
                        flat.append(it)
        return {"ok": True, "items": flat[:60], "state": await read_state()}
    except Exception as e:
        return {"ok": False, "error": str(e)[:160], "items": []}


@app.post("/api/list")
async def api_list(req: Request):
    body = await req.json()
    href = (body.get("href") or "").strip()
    if not re.match(r"^(playlist\?list=|browse/)[A-Za-z0-9_\-]+", href):
        return {"ok": False, "error": "gecersiz liste adresi"}
    try:
        await ensure_app()
        r = await cdp.js(f"({NAV_HREF_JS})({json.dumps(href)})")
        if r == "yok":
            # liste ana sayfada yoksa once kitapliga git
            await go_guide(2, "/library")
            await asyncio.sleep(0.8)
            r = await cdp.js(f"({NAV_HREF_JS})({json.dumps(href)})")
        if r == "yok":
            return {"ok": False, "error": "liste bulunamadi"}
        data = {"title": "", "cover": "", "items": []}
        for _ in range(30):
            await asyncio.sleep(0.5)
            raw = await cdp.js(f"({LIST_JS})()")
            try:
                data = json.loads(raw or "{}")
            except Exception:
                data = {}
            if data.get("items"):
                break
        items = [{"id": it["id"], "title": it.get("title", ""), "sub": it.get("sub", ""),
                  "img": thumb_for(it, "song")} for it in data.get("items", [])]
        cover = big_art(data.get("cover", "")) if not str(data.get("cover", "")).startswith("data:") else ""
        if not cover and items:
            cover = items[0].get("img", "")
        return {"ok": True, "title": data.get("title", ""), "cover": cover,
                "items": items, "state": await read_state()}
    except Exception as e:
        return {"ok": False, "error": str(e)[:160], "items": []}


if __name__ == "__main__":
    # ayni portta zaten bir kopru varsa sessizce cik
    _probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        _probe.bind(("0.0.0.0", PORT))
    except OSError:
        print(f"Port {PORT} zaten kullanimda - kopru zaten calisiyor.")
        sys.exit(0)
    finally:
        _probe.close()
    uvicorn.run(app, host="0.0.0.0", port=PORT, log_level="warning")
