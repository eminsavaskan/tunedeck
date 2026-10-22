package com.emin.ytmremote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Kopru sunucusuyla (bridge.py) konusan ince HTTP katmani.
 * Tum ag cagrilari arka plan is parcaciklarindan yapilmalidir.
 */
public final class Api {

    /** Otomatik guncelleme icin GitHub deposu. Kendi reponu yaz (ya da CI'da UPDATE_REPO ile gecir). */
    public static final String DEFAULT_UPDATE_REPO = "owner/ytm-remote";

    public static final String DEFAULT_BASE = "http://192.168.1.231:8777";
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 7000;

    private Api() {}

    // ------------------------------------------------------------- ayarlar
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ytm", Context.MODE_PRIVATE);
    }

    public static String base(Context c) {
        String b = prefs(c).getString("base", DEFAULT_BASE);
        if (b == null || b.trim().isEmpty()) b = DEFAULT_BASE;
        b = b.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    public static void setBase(Context c, String b) {
        prefs(c).edit().putString("base", b.trim()).apply();
    }

    // ------------------------------------------------------------- http
    private static String request(Context c, String path, String method, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base(c) + path).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is);
            if (code >= 400) throw new Exception("HTTP " + code);
            return text;
        } finally {
            conn.disconnect();
        }
    }

    public static String getRaw(Context c, String path) throws Exception {
        return request(c, path, "GET", null);
    }

    /** APK indirme akisi (buyuk govde, uzun okuma zaman asimi). */
    public static InputStream stream(Context c, String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base(c) + path).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("GET");
        return conn.getInputStream();
    }

    /** Kopru ayakta mi? Kisa zaman asimi ile tek istek atar (ag disi is parcaciginda cagrilmali). */
    public static boolean ping(Context c) {
        try {
            return requestShort(c, "/api/info", 1200) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String requestShort(Context c, String path, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base(c) + path).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code >= 500) return null;
            return readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        return new String(readAllBytes(is), "UTF-8");
    }

    public static byte[] readAllBytes(InputStream is) throws Exception {
        if (is == null) return new byte[0];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toByteArray();
    }

    // ------------------------------------------------------------- son durum onbellegi (widget icin)
    public static void cache(Context c, State s) {
        prefs(c).edit()
                .putString("c_title", s.title)
                .putString("c_byline", s.byline)
                .putBoolean("c_playing", s.playing)
                .putBoolean("c_online", s.online)
                .putLong("c_dur", s.durationMs())
                .apply();
    }

    public static State cached(Context c) {
        SharedPreferences p = prefs(c);
        State s = new State();
        s.title = p.getString("c_title", "");
        s.byline = p.getString("c_byline", "");
        s.playing = p.getBoolean("c_playing", false);
        s.online = p.getBoolean("c_online", false);
        s.duration = p.getLong("c_dur", 0) / 1000.0;
        return s;
    }

    // ------------------------------------------------------------- komutlar
    public static State state(Context c) {
        try {
            JSONObject root = new JSONObject(request(c, "/api/state", "GET", null));
            return State.from(root.optJSONObject("state"));
        } catch (Exception e) {
            return State.offline(shortMsg(e));
        }
    }

    public static boolean cmd(Context c, String path, String body) {
        try {
            request(c, path, "POST", body == null ? "{}" : body);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Duz metin govde yollar (teshis raporu). */
    public static void postText(Context c, String path, String text) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base(c) + path).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.close();
            conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    public static void toggle(Context c)  { cmd(c, "/api/toggle", "{}"); }
    public static void next(Context c)    { cmd(c, "/api/next", "{}"); }
    public static void prev(Context c)    { cmd(c, "/api/prev", "{}"); }
    public static void seek(Context c, double t) { cmd(c, "/api/seek", "{\"t\":" + t + "}"); }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    // ------------------------------------------------------------- durum
    public static class State {
        public boolean online, playing, loggedIn, muted;
        public String title = "", byline = "", art = "", error = "";
        public double position, duration;
        public int volumePct;

        public static State from(JSONObject o) {
            State s = new State();
            if (o == null) { s.error = "bos yanit"; return s; }
            s.online = o.optBoolean("online", true);
            s.playing = o.optBoolean("playing", false);
            s.loggedIn = o.optBoolean("logged_in", false);
            s.muted = o.optBoolean("muted", false);
            s.title = o.optString("title", "");
            s.byline = o.optString("byline", "");
            s.art = o.optString("art", "");
            s.error = o.optString("error", "");
            s.position = o.optDouble("position", 0);
            s.duration = o.optDouble("duration", 0);
            s.volumePct = o.optInt("volume_pct", (int) Math.round(o.optDouble("volume", 0) * 100));
            return s;
        }

        public static State offline(String err) {
            State s = new State();
            s.online = false;
            s.error = err == null ? "baglanti yok" : err;
            return s;
        }

        /** "Sertab Erener • Albüm • 2003" -> "Sertab Erener" */
        public String artist() {
            String[] p = byline.split("•");
            return p.length > 0 ? p[0].trim() : "";
        }

        /** Albüm adı (varsa) */
        public String album() {
            String[] p = byline.split("•");
            return p.length > 1 ? p[1].trim() : "";
        }

        public String sub() {
            if (!online) return "";   // ceviri cagiran tarafta
            if (!title.isEmpty()) {
                String a = artist(), al = album();
                if (!a.isEmpty() && !al.isEmpty()) return a + " • " + al;
                return a.isEmpty() ? al : a;
            }
            return loggedIn ? "hazır" : "giriş yapılmamış";
        }

        public long durationMs() { return (long) (duration * 1000); }
        public long positionMs() { return (long) (position * 1000); }
    }
}
