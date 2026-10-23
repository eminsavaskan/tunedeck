#!/usr/bin/env python3
"""TuneDeck — ikon ureteci.

Kullanim:  python tools/make-icon.py
Uretir:
  android/app/res/drawable/ic_launcher.png      (192px, tam tasarim)
  android/app/res/drawable/ic_notification.png  (96px, beyaz tek renk)
  icon-192.png, icon-512.png                    (PWA)
  docs/icon.png                                 (READ ME gorseli)

Tasarim: mor->pembe gecisli yuvarlak kare, beyaz oynat ucgeni ve
yayilan sinyal yaylari (uzaktan kumanda). 4x supersampling ile keskin.
"""
import os
from PIL import Image, ImageDraw

S = 1024          # calisma boyutu
SS = 4            # supersampling
W = S * SS

C1 = (124, 58, 237)    # #7C3AED mor
C2 = (236, 72, 153)    # #EC4899 pembe


def gradient(size, c1, c2, diag=True):
    img = Image.new("RGB", (size, size), c1)
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = ((x + y) / (2 * size)) if diag else (y / size)
            px[x, y] = (
                int(c1[0] + (c2[0] - c1[0]) * t),
                int(c1[1] + (c2[1] - c1[1]) * t),
                int(c1[2] + (c2[2] - c1[2]) * t),
            )
    return img


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def play_polygon(cx, cy, r):
    """Yuvarlatilmis ucgen icin noktalar (hafif saga bakan oynat isareti)."""
    h = r * 1.75
    return [
        (cx - r * 0.62, cy - h / 2),
        (cx - r * 0.62, cy + h / 2),
        (cx + r * 0.95, cy),
    ]


def draw_mark(img, size, fg=(255, 255, 255, 255), pad=0.0):
    """Cizimi verilen boyutta yapar (tam kanvas)."""
    d = ImageDraw.Draw(img, "RGBA")
    k = size / 1024.0
    # oynat ucgeni (hafif sola kayik)
    cx, cy = 384 * k, 602 * k
    r = 143 * k
    d.polygon(play_polygon(cx, cy, r), fill=fg)
    # sinyal yaylari (sag ust kose)
    acx, acy = 544 * k, 466 * k
    for i, rad in enumerate((112, 186)):
        w = int((46 - i * 11) * k)
        box = [acx - rad * k, acy - rad * k, acx + rad * k, acy + rad * k]
        d.arc(box, start=200, end=290, fill=fg, width=w)
    return img


def make_full(size, radius_ratio=0.225):
    base = gradient(W, C1, C2).convert("RGBA")
    base.putalpha(rounded_mask(W, int(W * radius_ratio)))
    base = draw_mark(base, W)
    return base.resize((size, size), Image.LANCZOS)


def make_mono(size):
    """Bildirim ikonu: seffaf zemin, beyaz glif (kucuk olcekte sade)."""
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    k = W / 1024.0
    cx, cy = 384 * k, 602 * k
    r = 126 * k
    d.polygon(play_polygon(cx, cy, r), fill=(255, 255, 255, 255))
    for i, rad in enumerate((104, 172)):
        box = [544 * k - rad * k, 466 * k - rad * k, 544 * k + rad * k, 466 * k + rad * k]
        d.arc(box, start=200, end=290, fill=(255, 255, 255, 255), width=int((42 - i * 11) * k))
    return img.resize((size, size), Image.LANCZOS)


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
targets = {
    "android/app/res/drawable/ic_launcher.png": make_full(192),
    "icon-192.png": make_full(192),
    "icon-512.png": make_full(512),
    "docs/icon.png": make_full(512),
}
for rel, im in targets.items():
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    im.save(p, optimize=True)
    print("yazildi:", rel, im.size)

p = os.path.join(ROOT, "android/app/res/drawable/ic_notification.png")
make_mono(96).save(p, optimize=True)
print("yazildi: android/app/res/drawable/ic_notification.png (96, tek renk)")
