#!/usr/bin/env python3
"""YT Music Remote — bridge API contract / robustness tests.

Usage:
    python tests/api_test.py [base_url]

Runs against a live bridge (the player may be open or closed; tests that need
playback are skipped automatically). Exits non-zero when a check fails.
"""
import json
import sys
import time
import urllib.error
import urllib.request
import concurrent.futures as cf

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8777"
FAIL = []
WARN = []


def req(path, body=None, method=None, timeout=45, raw=None, headers=None):
    url = BASE + path
    data = None
    h = {"Accept": "application/json"}
    if headers:
        h.update(headers)
    if raw is not None:
        data = raw if isinstance(raw, bytes) else raw.encode()
        h.setdefault("Content-Type", "application/json")
        method = method or "POST"
    elif body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
        method = method or "POST"
    r = urllib.request.Request(url, data=data, headers=h, method=method or "GET")
    t0 = time.time()
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read()
            return resp.status, text, time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read(), time.time() - t0
    except Exception as e:  # connection error / timeout
        return 0, str(e).encode(), time.time() - t0


def check(name, cond, detail=""):
    if cond:
        print(f"  ok   {name}")
    else:
        print(f"  FAIL {name}  {detail}")
        FAIL.append(f"{name} {detail}")


def warn(name, detail):
    print(f"  warn {name}  {detail}")
    WARN.append(f"{name} {detail}")


def jget(path, **kw):
    st, body, dt = req(path, **kw)
    try:
        return st, json.loads(body), dt
    except Exception:
        return st, None, dt


print(f"\n=== bridge: {BASE} ===\n")

# ---------------------------------------------------------------- 1. state
print("[1] /api/state")
st, j, dt = jget("/api/state")
check("200", st == 200, str(st))
check("< 3s yanit", dt < 3.0, f"{dt:.2f}s")
online = bool(j and j.get("state", {}).get("online"))
print(f"       player online: {online}  ({dt*1000:.0f} ms)")
if j and j.get("state"):
    s = j["state"]
    for k in ("title", "byline", "art", "playing", "position", "duration",
              "volume", "volume_pct", "muted", "online"):
        check(f"alan var: {k}", k in s, str(list(s))[:120])
    if s.get("duration"):
        check("position <= duration + 2", s["position"] <= s["duration"] + 2,
              f'{s["position"]}/{s["duration"]}')
    check("volume_pct 0..100", 0 <= s.get("volume_pct", 0) <= 100, str(s.get("volume_pct")))
    check("duration >= 0", s.get("duration", 0) >= 0, str(s.get("duration")))

# ---------------------------------------------------------------- 2. static
print("\n[2] statik dosyalar")
for path, ctype in (("/", "text/html"), ("/i18n.js", "javascript"),
                    ("/manifest.webmanifest", "json"), ("/icon-192.png", "image")):
    st, body, _ = req(path)
    ct = ""
    r = urllib.request.Request(BASE + path)
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            ct = resp.headers.get("content-type", "")
    except Exception:
        pass
    check(f"{path} 200", st == 200, str(st))
    check(f"{path} tip {ctype}", ctype in ct, ct)

st, body, _ = req("/app.apk")
check("/app.apk 200", st == 200, str(st))
check("/app.apk APK imzasi (PK)", body[:2] == b"PK", str(body[:8]))
check("/app.apk > 20KB", len(body) > 20000, f"{len(body)} bayt")

# ---------------------------------------------------------------- 3. arama
print("\n[3] /api/search girdileri")
cases = [
    ("normal", {"q": "sezen aksu"}),
    ("bos", {"q": ""}),
    ("bosluk", {"q": "   "}),
    ("alan yok", {}),
    ("tip yanlis", {"q": 123}),
    ("uzun 500", {"q": "a" * 500}),
    ("ozel karakter", {"q": "?'&<>\"%;--"}),
    ("xss", {"q": "<img src=x onerror=alert(1)>"}),
    ("emoji", {"q": "🎵 tarkan"}),
    ("turkce", {"q": "ışığın şarkısı çğüö"}),
]
for name, body in cases:
    st, j, dt = jget("/api/search", body=body)
    ok = st == 200 and isinstance(j, dict) and "ok" in j
    check(f"search {name}: gecerli JSON", ok, f"{st} {str(j)[:80]}")
    if isinstance(j, dict) and j.get("ok"):
        items = j.get("items", [])
        check(f"search {name}: items liste", isinstance(items, list), str(type(items)))
        for it in items[:3]:
            for f in ("title", "kind"):
                check(f"search {name}: sonuc alani {f}", f in it, str(it)[:80])
            break
    if j and j.get("ok") and not j.get("items") and name in ("ozel karakter", "xss"):
        warn(f"search {name}", "0 sonuc (kabul edilebilir)")

# ---------------------------------------------------------------- 4. transport
print("\n[4] transport uclari (POST)")
for path, body in (("/api/toggle", {}), ("/api/next", {}), ("/api/prev", {}), ("/api/mute", {})):
    st, j, dt = jget(path, body=body)
    check(f"{path} 200", st == 200, str(st))
    check(f"{path} ok", bool(j and j.get("ok")), str(j)[:100])
    check(f"{path} state donuyor", bool(j and j.get("state")), "state yok")
    check(f"{path} < 8s", dt < 8, f"{dt:.2f}s")
# mute'u geri al
req("/api/mute", body={})
# GET ile cagrilirsa 405 olmali (arayuz hatasi bir daha sessiz gecmesin)
st, _, _ = req("/api/toggle")
check("GET /api/toggle -> 405", st == 405, str(st))

# ---------------------------------------------------------------- 5. volume / seek
print("\n[5] /api/volume + /api/seek sinirlar")
for pct, expect in ((-5, 0), (0, 0), (150, 100)):
    st, j, _ = jget("/api/volume", body={"pct": pct})
    if st != 200:
        check(f"volume {pct!r}: 200", False, str(st))
        continue
    v = j.get("state", {}).get("volume_pct")
    check(f"volume {pct!r} -> {expect}", v == expect, f"volume_pct={v}")
st, j, _ = jget("/api/volume", body={"pct": "abc"})
check("volume 'abc' -> 400", st == 400, str(st))
check("volume 'abc' JSON hata", isinstance(j, dict) and j.get("ok") is False, str(j)[:80])
st, j, _ = jget("/api/volume", body={})
check("volume govdesiz: 200 veya 422", st in (200, 400, 422), str(st))

for t in (-10, 0, 999999, "12"):
    st, j, _ = jget("/api/seek", body={"t": t})
    ok = st == 200 and isinstance(j, dict)
    check(f"seek {t!r}: gecerli yanit", ok, f"{st} {str(j)[:80]}")
    if st == 200 and j.get("state"):
        check(f"seek {t!r}: negatif konum yok", j["state"]["position"] >= 0, str(j["state"]["position"]))

# ---------------------------------------------------------------- 6. hatali govde
print("\n[6] bozuk istekler")
for name, raw in (("gecersiz json", b"{q:"), ("bos govde", b""), ("dizi govde", b"[1,2,3]"),
                  ("null govde", b"null")):
    st, body, _ = req("/api/search", raw=raw)
    check(f"search {name}: 4xx/5xx ama cokme yok", st in (200, 400, 422, 500), f"{st} {body[:60]}")
    check(f"search {name}: JSON hata govdesi", body[:1] in (b"{", b"["), str(body[:40]))

# ---------------------------------------------------------------- 7. es zamanlilik
print("\n[7] es zamanli istekler (CDP soketi paylasimli)")
def one(i):
    st, j, dt = jget("/api/state")
    return st, bool(j and j.get("ok")), j.get("state", {}).get("title") if j else None, dt

t0 = time.time()
with cf.ThreadPoolExecutor(max_workers=8) as ex:
    res = list(ex.map(one, range(16)))
total = time.time() - t0
bad = [r for r in res if r[0] != 200 or not r[1]]
check("16 paralel /api/state hepsi 200", not bad, str(bad[:3]))
titles = {r[2] for r in res if r[2]}
check("paralel yanitlar tutarli (tek baslik)", len(titles) <= 1, str(titles)[:120])
print(f"       16 istek {total:.2f}s ({total/16*1000:.0f} ms/istek)")

# komut + durum ayni anda
with cf.ThreadPoolExecutor(max_workers=4) as ex:
    futs = [ex.submit(jget, "/api/toggle", body={}) for _ in range(2)]
    futs += [ex.submit(jget, "/api/state") for _ in range(2)]
    res = [f.result() for f in futs]
check("paralel komut+okuma cokmedi", all(r[0] == 200 for r in res), str([r[0] for r in res]))

# ---------------------------------------------------------------- 8. gezinme
print("\n[8] /api/home, /api/library, /api/list")
st, j, dt = jget("/api/home")
check("home 200", st == 200, str(st))
check("home < 25s", dt < 25, f"{dt:.1f}s")
if j and j.get("ok"):
    shelves = j.get("shelves", [])
    check("home shelves liste", isinstance(shelves, list) and len(shelves) > 0, str(len(shelves)))
    if shelves:
        items = shelves[0].get("items", [])
        check("raf oğeleri dolu", len(items) > 0, str(len(items)))
        for it in items[:5]:
            check("oge alanlari", all(k in it for k in ("kind", "title")), str(it)[:90])
            check("oge kind song|list", it.get("kind") in ("song", "list"), str(it.get("kind")))

st, j, dt = jget("/api/library")
check("library 200", st == 200, str(st))
if j and j.get("ok"):
    check("library items liste", isinstance(j.get("items"), list), str(type(j.get("items"))))

for name, body in (("bos", {}), ("gecersiz href", {"href": "../../etc/passwd"}),
                   ("var olmayan liste", {"href": "playlist?list=PLxxxxxxxxxxxxxxxxxxxxxx"})):
    st, j, dt = jget("/api/list", body=body)
    check(f"list {name}: cokme yok", st in (200, 400, 422), f"{st} {str(j)[:70]}")
    check(f"list {name}: makul sure", dt < 25, f"{dt:.1f}s")

# ---------------------------------------------------------------- 9. play
print("\n[9] /api/play")
for name, body in (("bos", {}), ("gecersiz id", {"id": "zzzzzzzzzzz"}),
                   ("xss id", {"id": "<script>alert(1)</script>"})):
    st, j, dt = jget("/api/play", body=body)
    check(f"play {name}: cokme yok", st in (200, 400, 422), f"{st} {str(j)[:70]}")

# gecerli bir sarki cal
st, j, _ = jget("/api/search", body={"q": "neşet ertaş"})
vid = None
if j and j.get("ok"):
    for it in j.get("items", []):
        if it.get("kind") == "song" and it.get("id"):
            vid = it["id"]
            break
if vid:
    st, j, dt = jget("/api/play", body={"id": vid})
    check("play gecerli id", st == 200 and j.get("ok"), f"{st} {str(j)[:80]}")
    time.sleep(2)
    st, j2, _ = jget("/api/state")
    if j2 and j2.get("state", {}).get("online"):
        title = j2["state"]["title"]
        check("calan parca degisti/caliyor", bool(title), title)

# ---------------------------------------------------------------- 10. digerleri
print("\n[10] info / qr / 404")
st, j, _ = jget("/api/info")
check("info 200", st == 200, str(st))
check("info url alani", bool(j and j.get("url", "").startswith("http")), str(j))
st, body, _ = req("/qr.png")
check("qr.png 200 PNG", st == 200 and body[:4] == b"\x89PNG", str(body[:8]))
st, body, _ = req("/setup")
check("setup 200", st == 200, str(st))
st, body, _ = req("/api/yok-boyle-bir-sey")
check("bilinmeyen api 404", st == 404, str(st))
st, body, _ = req("/../bridge.py")
check("yol tirmanma engelli", st in (404, 400) or b"FastAPI" in body, f"{st}")

print("\n=== sonuc ===")
print(f"basarisiz: {len(FAIL)}   uyari: {len(WARN)}")
for f in FAIL:
    print("  FAIL", f)
for w in WARN:
    print("  WARN", w)
sys.exit(1 if FAIL else 0)
