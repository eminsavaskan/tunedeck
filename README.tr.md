# TuneDeck

> Türkçe doküman. İngilizce (ana) sürüm: [README.md](README.md)

**14 dil:** Türkçe, İngilizce, Almanca, Çince, Japonca, Arapça (RTL), İtalyanca, Lehçe, Rusça,
İspanyolca, Korece, Azerice, Fransızca, Portekizce. Dil, telefonun dilinden otomatik seçilir;
**Ayarlar → Dil** ile değiştirilir. Android bildirim/widget metinleri de aynı dillerde
(`android/app/res/values-*/strings.xml`).

Bilgisayarındaki açık kaynak **YouTube Music** uygulamasını (pear-devs/pear-desktop,
Electron tabanlı) telefondan kumanda etmek için köprü + web uygulaması.

Spotify Connect'in YT Music karşılığı: aynı Wi-Fi'daki telefon; şarkı değiştirme,
ses, arama ve çalma.

## Nasıl çalışır

```
Telefon (aynı Wi-Fi)  ──HTTP──►  bridge.py (port 8777)
                                      │
                                      │ Chromium DevTools Protokolü (127.0.0.1:9223)
                                      ▼
                          YouTube Music masaüstü uygulaması
```

- Uygulamanın kendi arayüzü DOM üzerinden sürülür: oynat/duraklat, sonraki/önceki,
  ses, ileri-geri sarma, arama ve tıklayarak çalma. Yani uygulamanın hesabı,
  beğenileri, geçmişi, çalma listeleri aynen çalışır.
- Uygulama müziği kesmeden kontrol edilir (sayfa yenilenmez).
- Uygulama kapalıysa köprü onu `--remote-debugging-port=9223` ile kendisi açar.

## Kurulum (bir kez)

1. `baslat.vbs` → köprüyü arka planda başlatır ve QR'lı bağlanma sayfasını açar.
2. Telefonda aynı Wi-Fi'a bağlıyken QR'ı okut (veya `http://<bilgisayar-ip>:8777`).
3. Tarayıcı menüsünden **“Ana ekrana ekle”** → telefonun uygulama çekmecesinde
   tam ekran, adres çubuksuz bir uygulama olarak durur.

Giriş anında otomatik başlar: `Başlangıç` klasöründe `YTM Remote.vbs`.

## Dosyalar

| Dosya | İş |
|---|---|
| `bridge.py` | FastAPI köprüsü + web arayüzü sunar (port 8777) |
| `cdp.py` | DevTools Protokolü istemcisi (WebSocket) |
| `ui.html` | Telefon arayüzü (PWA) |
| `baslat.vbs` | Köprüyü başlat + QR sayfasını aç |
| `arka-plan.vbs` | Sessiz başlatma (Başlangıç klasöründekinin aynısı) |
| `durdur.bat` | Köprüyü durdur |
| `runtime/python/` | Projeye gömülü Python 3.14 + bağımlılıklar |

## API (istemci yazmak istersen)

| Uç | Gövde | Döner |
|---|---|---|
| `GET /api/state` | – | `title, byline, art, playing, position, duration, volume, muted, logged_in, online` |
| `POST /api/toggle` | `{}` | oynat/duraklat |
| `POST /api/next` / `prev` | `{}` | sonraki / önceki |
| `POST /api/volume` | `{"v":0..1}` | ses seviyesi |
| `POST /api/mute` | `{}` | sessize al/aç |
| `POST /api/seek` | `{"t":saniye}` | konum |
| `GET /api/home` | – | `shelves: [{title, items:[{kind,id,href,title,sub,img}]}]` |
| `GET /api/library` | – | `items: [...]` (çalma listeleri + albümler) |
| `POST /api/list` | `{"href":"playlist?list=..."}` | `title, cover, items:[sarki]` |
| `POST /api/search` | `{"q":"..."}` | `items: [{id,title,sub,img}]` |
| `POST /api/play` | `{"id":"videoId"}` | o şarkıyı çalar |
| `GET /setup` · `/qr.png` · `/api/info` | – | bağlanma yardımcıları |

## Bilinen sınırlar

- Telefon **oynatıcı** değil, **kumanda**. Ses bilgisayardan çıkar (Spotify Connect'
  teki “başka cihaza aktar” gibi bir cihaz seçimi yok; YT Music'in mobil API'si kapalı).
- Uygulama güncellemesi sonrası kısayol argümanı sıfırlanırsa köprü uygulamayı
  yine de doğru şekilde yeniden başlatır (ilk istekte ~10 sn gecikme).
- Aynı ağdaki herkes portu görebilir; ev ağı için kabul edilebilir, misafir ağında
  paylaşma.

## Android uygulaması (APK)

`ytm-remote.apk` — `http://<bilgisayar-ip>:8777/app.apk` adresinden telefona indirilir
(bağlanma sayfasında QR'ı da var). PWA'dan farkı:

- **Bildirim paneli**: kalıcı medya bildirimi (önceki / çal / sonraki), MediaSession
  sayesinde Android 11+ hızlı ayar medya karuselinde de görünür.
- **Kilit ekranı**: kapak resmi, şarkı adı, sanatçı ve ilerleme çubuğuyla tam medya
  kontrolü (`VISIBILITY_PUBLIC`).
- **Ana ekran widget'ı**: şarkı adı + 3 düğme; servis 3 saniyede bir tazeler,
  içerik değişmediyse yeniden çizmez.
- Telefon açılışında servis kendiliğinden başlar (`BootReceiver`).

Kaynak: `android/app/` (saf framework Java, AndroidX/Gradle yok — derleme `android/build.sh`
ile doğrudan aapt2 + javac + d8 + apksigner üzerinden yapılır).

Widget'ı eklemek: ana ekranı basılı tut → Widget'lar → YT Music Remote.

Sunucu adresi değişirse: uygulama menüsü → "Sunucu adresi".

## Arayüz (YouTube Music görünümü)

Tek sayfa yerine uygulamanın kendi yapısı:

- **Üst çubuk**: logo, bağlantı göstergesi, yenile
- **Ana sayfa**: YT Music'in "Yeniden dinleyin / keşifler / podcast" rafları (yatay kaydırmalı)
- **Keşfet**: canlı arama, sonuca dokun → çalar
- **Kitaplık**: çalma listeleri + albümler ızgarası (kapaklarla)
- **Liste görünümü**: kapak, "Tümünü çal", numaralı şarkı listesi, çalan şarkı kırmızı
- **Mini oynatıcı**: her ekranın altında kapak + ilerleme çizgisi + çal/sonraki
- **Tam ekran oynatıcı**: bulanık kapak fonu, sarma, ses, önceki/sonraki

Arayüz sunucudan geldiği için **APK'yı yeniden derlemeye gerek yok** — telefonda
uygulamayı açtığında yeni hâli görünür (servis çalışan sayfayı her açılışta ağdan çeker).

Not: YT Music görselleri tembel yüklediği için kapaklar Polymer verisinden okunur;
şarkı kapakları video kimliğinden türetilir, kapağı olmayan listelerde baş harfli
gradyan yer tutucu gösterilir.
