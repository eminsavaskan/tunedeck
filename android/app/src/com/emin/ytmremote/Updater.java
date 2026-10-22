package com.emin.ytmremote;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Kendi kendini guncelleme. Iki kanal:
 *   1) kopru  — PC'deki /api/version (LAN icinde, internet gerekmez)
 *   2) github — https://api.github.com/repos/&lt;owner&gt;/&lt;repo&gt;/releases/latest
 *                (kopru yoksa da calisir; herkes icin otomatik guncelleme)
 * Sistem her zaman "Yukle" onayini gosterir; bu atlatilamaz.
 */
public final class Updater {

    public static final String ACTION_RESULT = "com.emin.ytmremote.INSTALL_RESULT";
    private static final int CHECK_EVERY_MS = 12 * 60 * 60 * 1000;   // 12 saatte bir otomatik

    private Updater() {}

    /** Guncelleme sorgusunun sonucu. */
    public static class Info {
        public boolean ok = false;
        public boolean newer = false;
        public String version = "";
        public int code = 0;
        public String url = "";        // mutlak http(s) ya da /app.apk
        public String source = "";     // "bridge" | "github"
        public String current = "";
        public String built = "";
        public String error = "";
        public long size = 0;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                o.put("newer", newer);
                o.put("version", version);
                o.put("code", code);
                o.put("source", source);
                o.put("current", current);
                o.put("built", built);
                o.put("size", size);
                o.put("error", error);
            } catch (Throwable ignored) {
            }
            return o;
        }
    }

    // ------------------------------------------------------------------ surum bilgisi
    public static int myCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String myName(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName == null ? "?" : p.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ kanal 1: kopru
    private static JSONObject bridgeMeta(Context c) {
        try {
            return new JSONObject(Api.getRaw(c, "/api/version"));
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ kanal 2: github
    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(9000);
        conn.setRequestProperty("User-Agent", "YTMusicRemote");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * GitHub'in son surumu. Release'e "version.json" eklendiyse ondan (code dahil),
     * yoksa tag adindan ("v2.3") okur. repo bos/gecersizse null.
     */
    private static JSONObject githubMeta(String repo) {
        if (repo == null || !repo.matches("[\\w.-]+/[\\w.-]+")) return null;
        try {
            String rel = httpGet("https://api.github.com/repos/" + repo + "/releases/latest");
            JSONObject r = new JSONObject(rel);
            String tag = r.optString("tag_name", "");
            JSONArray assets = r.optJSONArray("assets");
            String apkUrl = "";
            String verUrl = "";
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "").toLowerCase();
                    if (name.endsWith(".apk")) apkUrl = a.optString("browser_download_url", "");
                    if (name.equals("version.json") || name.equals("apk-version.json")) verUrl = a.optString("browser_download_url", "");
                }
            }
            if (apkUrl.isEmpty()) return null;

            JSONObject out = new JSONObject();
            if (!verUrl.isEmpty()) {
                JSONObject v = new JSONObject(httpGet(verUrl));
                out.put("version", v.optString("version", tag));
                out.put("code", v.optInt("code", 0));
                out.put("sha256", v.optString("sha256", ""));
            } else {
                // version.json yoksa: etiket adini yuklu surumle AYNI olcekte karsilastir,
                // aksi halde "2.6" -> 20600 gibi kodlar sahte guncelleme gosterir
                String clean = tag.replaceAll("^[vV]", "");
                out.put("version", clean);
                out.put("code", codeFromName(clean));
                out.put("codeIsName", true);
                out.put("sha256", "");
            }
            out.put("apk", apkUrl);
            out.put("built", r.optString("published_at", ""));
            out.put("github", repo);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** "2.3" -> 20003 gibi kaba bir anahtar (release'te version.json yoksa). */
    private static int codeFromName(String v) {
        try {
            String[] p = v.split("[.\\-+]");
            int maj = p.length > 0 ? Integer.parseInt(p[0].replaceAll("[^0-9]", "0")) : 0;
            int min = p.length > 1 ? Integer.parseInt(p[1].replaceAll("[^0-9]", "0")) : 0;
            int pat = p.length > 2 ? Integer.parseInt(p[2].replaceAll("[^0-9]", "0")) : 0;
            return maj * 10000 + min * 100 + pat;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ karar
    /** Kanal sirasi: kopru (LAN, hizli) -> github. */
    public static Info check(Context c) {
        Info info = new Info();
        info.current = myName(c);
        int mine = myCode(c);

        JSONObject b = bridgeMeta(c);
        if (b != null && b.optBoolean("ok", false)) {
            info.ok = true;                 // kopru cevap verdi: sonuc gecerli
            int bcode = b.optInt("code", 0);
            if (bcode > mine) {
                info.ok = true;
                info.newer = true;
                info.code = bcode;
                info.version = b.optString("version");
                info.url = b.optString("apk", "/app.apk");
                info.built = b.optString("built");
                info.size = b.optLong("size");
                info.source = "bridge";
                return info;
            }
            info.version = b.optString("version");
            info.code = bcode;
            info.source = "bridge";
        }

        String repo = b == null ? "" : b.optString("github", "");
        if (repo.isEmpty()) {
            try {
                repo = Api.prefs(c).getString("update_repo", "");
            } catch (Throwable ignored) {
            }
        }
        if (repo.isEmpty()) repo = Api.DEFAULT_UPDATE_REPO;   // APK'ya gomulu depo
        JSONObject g = githubMeta(repo);
        if (g != null) {
            int gcode = g.optInt("code", 0);
            int compareTo = g.optBoolean("codeIsName", false) ? codeFromName(myName(c)) : mine;
            if (gcode > compareTo) {
                info.ok = true;
                info.newer = true;
                info.code = gcode;
                info.version = g.optString("version");
                info.url = g.optString("apk");
                info.built = g.optString("built");
                info.source = "github";
                return info;
            }
            if (!info.ok) {
                info.ok = true;
                info.version = g.optString("version");
                info.code = gcode;
                info.source = "github";
            }
        }

        if (!info.ok) info.error = b == null ? "sunucu yok" : "surum bilgisi yok";
        return info;
    }

    public static boolean mayAutoCheck(Context c) {
        try {
            long last = Api.prefs(c).getLong("upd_last", 0);
            return System.currentTimeMillis() - last > CHECK_EVERY_MS;
        } catch (Throwable t) {
            return true;
        }
    }

    public static void markChecked(Context c) {
        try {
            Api.prefs(c).edit().putLong("upd_last", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void setRepo(Context c, String repo) {
        try {
            Api.prefs(c).edit().putString("update_repo", repo == null ? "" : repo.trim()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ kurulum
    /** url mutlak (http...) ise oradan, degilse kopruden (/app.apk) indirir. */
    public static void downloadAndInstall(Context c, String url) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int id = pi.createSession(params);

        PackageInstaller.Session session = pi.openSession(id);
        try {
            try (OutputStream out = session.openWrite("base.apk", 0, -1);
                 InputStream in = url.startsWith("http") ? openUrl(url) : Api.stream(c, url)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent i = new Intent(ACTION_RESULT).setPackage(c.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent cb = PendingIntent.getBroadcast(c, id, i, flags);
            session.commit(cb.getIntentSender());
        } catch (Throwable t) {
            try {
                pi.abandonSession(id);
            } catch (Throwable ignored) {
            }
            throw t;
        } finally {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static InputStream openUrl(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "YTMusicRemote");
        return conn.getInputStream();
    }
}
