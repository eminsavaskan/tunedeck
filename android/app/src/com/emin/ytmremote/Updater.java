package com.emin.ytmremote;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Kendi kendini guncelleme: koprunun sundugu /api/version ile karsilastirir,
 * yeni APK'yi indirip PackageInstaller oturumu ile kurar.
 * Sistem her zaman "Yukle" onayini gosterir; bu atlatilamaz (ve atlatilmamali).
 */
public final class Updater {

    public static final String ACTION_RESULT = "com.emin.ytmremote.INSTALL_RESULT";

    private Updater() {}

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

    /** Koprunun surum bilgisi. Hata durumunda exception firlatir. */
    public static JSONObject remote(Context c) throws Exception {
        return new JSONObject(Api.getRaw(c, "/api/version"));
    }

    /** Yeni surum var mi? remote.code > yuklu kod. */
    public static boolean isNewer(Context c, JSONObject remote) {
        int code = remote.optInt("code", 0);
        return code > 0 && code > myCode(c);
    }

    /** APK'yi indirir ve kurulum oturumunu baslatir (ag disi is parcaciginda cagrilmali). */
    public static void downloadAndInstall(Context c, String path) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = pi.createSession(params);

        PackageInstaller.Session session = pi.openSession(id);
        try {
            try (OutputStream out = session.openWrite("base.apk", 0, -1);
                 InputStream in = Api.stream(c, path)) {
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
            try { pi.abandonSession(id); } catch (Throwable ignored) {}
            throw t;
        } finally {
            try { session.close(); } catch (Throwable ignored) {}
        }
    }
}
