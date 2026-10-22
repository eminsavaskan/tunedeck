package com.emin.ytmremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Telefondaki durumu PC'deki kopruye rapor eder (/api/debug).
 * Bildirim/kilit ekrani gorunmediginde sebebi buradan okunur.
 */
public final class Diag {

    private Diag() {}

    public static String report(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            b.append("--- ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
            b.append("cihaz: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" | android ").append(Build.VERSION.SDK_INT)
                    .append(" (").append(Build.VERSION.RELEASE).append(")\n");
            b.append("sunucu: ").append(Api.base(c)).append('\n');

            if (Build.VERSION.SDK_INT >= 33) {
                boolean granted = c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
                b.append("izin POST_NOTIFICATIONS: ").append(granted ? "verildi" : "VERILMEDI").append('\n');
            } else {
                b.append("izin POST_NOTIFICATIONS: gerekmiyor\n");
            }

            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            b.append("bildirimler acik: ").append(nm != null && nm.areNotificationsEnabled()).append('\n');
            NotificationChannel ch = (nm != null) ? nm.getNotificationChannel(RemoteService.CHANNEL_ID) : null;
            b.append("kanal: ").append(ch == null ? "YOK"
                    : ("importance=" + ch.getImportance() + " lockscreen=" + ch.getLockscreenVisibility()))
                    .append('\n');
            try {
                android.service.notification.StatusBarNotification[] act =
                        (nm != null) ? nm.getActiveNotifications() : null;
                b.append("aktif bildirim sayisi: ").append(act == null ? "?" : act.length).append('\n');
                if (act != null) {
                    for (android.service.notification.StatusBarNotification sn : act) {
                        if (sn.getPackageName().equals(c.getPackageName())) {
                            b.append("  bizim bildirim id=").append(sn.getId())
                                    .append(" kanal=").append(sn.getNotification().getChannelId()).append('\n');
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            b.append("servis calisiyor: ").append(RemoteService.running).append('\n');
            b.append("medya oturumu aktif: ").append(RemoteService.sessionActive).append('\n');
            b.append("son hata: ").append(RemoteService.lastError.isEmpty() ? "-" : RemoteService.lastError).append('\n');

            Api.State s = Api.state(c);
            b.append("kopru: online=").append(s.online).append(" caliyor=").append(s.playing)
                    .append(" sarki=").append(s.title).append(" hata=").append(s.error).append('\n');
        } catch (Throwable t) {
            b.append("rapor hatasi: ").append(t).append('\n');
        }
        return b.toString();
    }

    /** Raporu arka planda kopruye yollar; kullanici bir sey yapmaz. */
    public static void send(final Context c, final String tag) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Api.postText(app, "/api/debug", tag + ' ' + report(app));
            } catch (Throwable ignored) {
            }
        }).start();
    }

    /** Kisa tek satirlik kayit (komut izleme). */
    public static void log(final Context c, final String line) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Api.postText(app, "/api/debug", line + " @ " + System.currentTimeMillis());
            } catch (Throwable ignored) {
            }
        }).start();
    }

    /** Bildirim sisteminin calistigini ayirt etmek icin duz bir test bildirimi. */
    public static void testNotification(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String chId = "ytm_test";
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(chId, c.getString(R.string.test_title),
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(c, chId) : new Notification.Builder(c);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(c, 90,
                    new android.content.Intent(c, MainActivity.class),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            b.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(c.getString(R.string.test_title))
                    .setContentText(c.getString(R.string.test_text))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(4242, b.build());
        } catch (Throwable ignored) {
        }
    }
}
