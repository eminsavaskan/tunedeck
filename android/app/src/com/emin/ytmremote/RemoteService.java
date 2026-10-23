package com.emin.ytmremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On planda calisan servis: durumu yoklar, MediaSession'i besler.
 * MediaSession sayesinde kontrol dugmeleri hem bildirim panelinde
 * hem kilit ekraninda (ve Android 11+ medya karuselinde) cikar.
 */
public class RemoteService extends Service {

    public static final String CHANNEL_ID = "ytm_playback_v2";
    public static final String CHANNEL_PLAIN = "ytm_plain";
    public static final int NOTIF_ID = 1001;
    public static volatile boolean running = false;
    public static volatile boolean sessionActive = false;
    public static volatile String lastError = "";

    private static final int POLL_MS = 3000;

    private MediaSession session;
    private MediaSession.Token token;
    private VolumeProvider volumeProvider;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);   // komutlar icin ayri havuz
    private volatile boolean alive = true;
    private volatile Api.State last = Api.State.offline("bekleniyor");
    private volatile Bitmap artwork;
    private volatile String artworkUrl = "";
    private boolean foregroundStarted = false;
    private int offlineCount = 0;   // ust uste kac yoklamadir kopru yok
    private Thread pollThread;

    // ------------------------------------------------------------------ yasam
    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        try {
            last = Api.cached(this);
            createChannel();

            session = new MediaSession(this, "YTMusicRemote");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() {
                    io.execute(() -> { if (!last.playing) Api.toggle(RemoteService.this); pause(600); refresh(); });
                }
                @Override public void onPause() {
                    io.execute(() -> { if (last.playing) Api.toggle(RemoteService.this); pause(600); refresh(); });
                }
                @Override public void onSkipToNext() {
                    io.execute(() -> { Api.next(RemoteService.this); pause(900); refresh(); });
                }
                @Override public void onSkipToPrevious() {
                    io.execute(() -> { Api.prev(RemoteService.this); pause(900); refresh(); });
                }
                @Override public void onSeekTo(long pos) {
                    io.execute(() -> { Api.seek(RemoteService.this, pos / 1000.0); pause(600); refresh(); });
                }
            });
            session.setActive(true);
            sessionActive = true;
            token = session.getSessionToken();

            // Uzaktan kumanda oturumu: ses tuslari PC'nin sesini surer.
            // Bu cagri olmadan sistem oturumu "medya oturumu" saymayabiliyor.
            volumeProvider = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, 50) {
                @Override public void onSetVolumeTo(int volume) {
                    final int v = Math.max(0, Math.min(100, volume));
                    io.execute(() -> Api.cmd(RemoteService.this, "/api/volume", "{\"pct\":" + v + "}"));
                }
                @Override public void onAdjustVolume(int direction) {
                    final int d = direction;
                    io.execute(() -> {
                        Api.State s = Api.state(RemoteService.this);
                        int nv = Math.max(0, Math.min(100, s.volumePct + d * 5));
                        Api.cmd(RemoteService.this, "/api/volume", "{\"pct\":" + nv + "}");
                        final int fv = nv;
                        main.post(() -> { if (volumeProvider != null) volumeProvider.setCurrentVolume(fv); });
                    });
                }
            };
            session.setPlaybackToRemote(volumeProvider);
            session.setSessionActivity(openPI());

            startForegroundCompat(buildNotification(last));
            pollThread = new Thread(this::loop, "ytm-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        } catch (Throwable t) {
            lastError = "onCreate: " + t;
            Diag.send(getApplicationContext(), "SERVIS-HATASI");
            running = false;
            try { stopSelf(); } catch (Throwable ignored) {}   // uygulamayi cokertme
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = intent == null ? null : intent.getStringExtra("cmd");
        if ("stop".equals(cmd)) {
            Api.prefs(this).edit().putBoolean("user_stopped", true).apply();
            Diag.log(this, "USER-STOP");
            try { stopForeground(true); } catch (Throwable ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        if (cmd != null) {
            io.execute(() -> { doCmd(cmd); pause(800); refresh(); });
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        alive = false;
        running = false;
        sessionActive = false;
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Exception ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    private void doCmd(String cmd) {
        Diag.log(this, "CMD " + cmd);
        switch (cmd) {
            case "next": case "ileri": Api.next(this); break;
            case "prev": case "geri": Api.prev(this); break;
            case "rebuild": break;              // sadece bildirimi yeniden kur
            default: Api.toggle(this); break;
        }
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ------------------------------------------------------------------ dongu
    private void loop() {
        while (alive) {
            try { refresh(); } catch (Throwable ignored) {}
            for (int i = 0; i < POLL_MS / 100 && alive; i++) pause(100);
        }
    }

    private void refresh() {
        Api.State s = Api.state(this);
        last = s;
        Api.cache(this, s);

        // PC'de uygulama kapatildiysa bildirim/kilit ekrani karti asili kalmasin:
        // 3 yoklama (~9 sn) ust uste basarisizsa servisi kapat.
        if (!s.online) {
            offlineCount++;
            if (offlineCount >= 3) {
                Diag.log(this, "OFFLINE-STOP");
                stopSelf();
                return;
            }
        } else {
            offlineCount = 0;
        }

        if (s.online && s.art != null && !s.art.isEmpty()) {
            if (!s.art.equals(artworkUrl)) {
                artworkUrl = s.art;
                Bitmap b = download(s.art);
                if (b != null) artwork = b;
            }
        } else if (artwork != null) {
            artwork = null;
            artworkUrl = "";
        }

        final Api.State snap = s;
        main.post(() -> {
            if (!alive) return;
            updateSession(snap);
            pushNotification(snap);
            RemoteWidget.refreshAll(this);
        });
    }

    private Bitmap download(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "YTMRemote/1.0");
            InputStream is = c.getInputStream();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            byte[] raw = Api.readAllBytes(is);
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int scale = 1;
            int max = Math.max(o.outWidth, o.outHeight);
            while (max / scale > 640) scale *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = scale;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ session
    private void updateSession(Api.State s) {
        if (session == null) return;
        if (volumeProvider != null) {
            try { volumeProvider.setCurrentVolume(Math.max(0, Math.min(100, s.volumePct))); } catch (Throwable ignored) {}
        }
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title.isEmpty() ? "YT Music Remote" : s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs());
        if (artwork != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        session.setMetadata(md.build());

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        int state = s.playing ? PlaybackState.STATE_PLAYING
                : (s.online ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_ERROR);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, s.positionMs(), s.playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
    }

    // ------------------------------------------------------------------ bildirim
    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Kilit ekranı ve bildirim paneli kontrolleri");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setSound(null, null);          // sessiz: her guncellemede ses cikmasin
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private void createPlainChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_PLAIN, getString(R.string.channel_plain),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Medya kartı görünmeyen cihazlar için düğmeli bildirim");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent openPI() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent cmdPI(String cmd, int code) {
        Intent i = new Intent(this, RemoteService.class).putExtra("cmd", cmd);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification(Api.State s) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // mod: "media" = medya karti (kilit ekrani/hizli ayar), "plain" = dugmeli normal bildirim
        boolean plain = "plain".equals(Api.prefs(this).getString("notif_mode", "media"));
        String chId = plain ? CHANNEL_PLAIN : CHANNEL_ID;
        if (plain) createPlainChannel();

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, chId)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(content)
                .setContentTitle(s.title.isEmpty() ? "YT Music Remote" : s.title)
                .setContentText(s.online ? s.sub() : getString(R.string.unreachable))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(false)              // kaydirarak kapatilabilsin
                .setAutoCancel(true)
                .setDeleteIntent(cmdPI("stop", 15))   // kaydirinca servis de dursun
                .setCategory(Notification.CATEGORY_TRANSPORT);

        if (artwork != null) b.setLargeIcon(artwork);

        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_prev), getString(R.string.action_prev), cmdPI("prev", 11)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, s.playing ? R.drawable.ic_pause : R.drawable.ic_play),
                s.playing ? getString(R.string.action_pause) : getString(R.string.action_play), cmdPI("toggle", 12)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_next), getString(R.string.action_next), cmdPI("next", 13)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.action_stop), cmdPI("stop", 14)).build());
        b.setColor(0xFF7C3AED);

        if (plain) {
            // duz bildirim: medya karti gostermeyen cihazlarda kontrol yine elinizde olsun
            b.setStyle(new Notification.BigTextStyle().bigText(
                    (s.title.isEmpty() ? "YT Music Remote" : s.title) + " — " + s.sub()));
        } else {
            Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
            if (token != null) style.setMediaSession(token);
            b.setStyle(style);
        }
        return b.build();
    }

    private void startForegroundCompat(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foregroundStarted = true;
        } catch (Exception e) {
            lastError = "startForeground: " + e;
            Diag.send(getApplicationContext(), "SF-HATASI");
            try {
                startForeground(NOTIF_ID, n);
                foregroundStarted = true;
            } catch (Exception e2) {
                lastError = "startForeground(2): " + e2;
                Diag.send(getApplicationContext(), "SF-HATASI-2");
            }
        }
    }

    private void pushNotification(Api.State s) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = buildNotification(s);
        if (!foregroundStarted) startForegroundCompat(n);
        else nm.notify(NOTIF_ID, n);
    }
}
