package com.emin.ytmremote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Ana ekran widget'i: sarki adi + onceki / cal-duraklat / sonraki.
 * Servis her durum yoklamasinda widget'i tazeler, boylece canli kalir.
 */
public class RemoteWidget extends AppWidgetProvider {

    public static final String ACTION_CMD = "com.emin.ytmremote.WIDGET_CMD";
    private static String lastSig = null;

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        RemoteViews v = build(context);
        for (int id : ids) mgr.updateAppWidget(id, v);
        lastSig = sig(Api.cached(context));
        ensureService(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent == null ? null : intent.getStringExtra("cmd");
        if (cmd == null) {
            super.onReceive(context, intent);
            return;
        }
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                switch (cmd) {
                    case "next": Api.next(app); break;
                    case "prev": Api.prev(app); break;
                    default:     Api.toggle(app); break;
                }
                // iyimser guncelleme; servis 3 sn icinde gercek durumla duzeltir
                Api.State s = Api.cached(app);
                if ("toggle".equals(cmd)) s.playing = !s.playing;
                Api.cache(app, s);
                lastSig = null;                    // zorla ciz
                refreshAll(app, s);
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }

    // ------------------------------------------------------------------ cizim
    static RemoteViews build(Context c) {
        return build(c, Api.cached(c));
    }

    static RemoteViews build(Context c, Api.State s) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.w_title, s.title.isEmpty() ? "YT Music Remote" : s.title);
        v.setTextViewText(R.id.w_sub, s.online ? s.sub() : c.getString(R.string.unreachable));
        v.setImageViewResource(R.id.w_play, s.playing ? R.drawable.ic_pause : R.drawable.ic_play);

        v.setOnClickPendingIntent(R.id.w_prev, cmdPI(c, "prev", 21));
        v.setOnClickPendingIntent(R.id.w_play, cmdPI(c, "toggle", 22));
        v.setOnClickPendingIntent(R.id.w_next, cmdPI(c, "next", 23));
        v.setOnClickPendingIntent(R.id.w_root, openPI(c));
        return v;
    }

    private static String sig(Api.State s) {
        return s.title + "\u0001" + s.sub() + "\u0001" + (s.playing ? "1" : "0");
    }

    private static PendingIntent cmdPI(Context c, String cmd, int code) {
        Intent i = new Intent(c, RemoteWidget.class).setAction(ACTION_CMD).putExtra("cmd", cmd);
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openPI(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 20, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------------ tazeleme
    public static void refreshAll(Context c) {
        refreshAll(c, null);
    }

    public static void refreshAll(Context c, Api.State state) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, RemoteWidget.class));
            if (ids == null || ids.length == 0) return;
            Api.State s = (state == null) ? Api.cached(c) : state;
            String now = sig(s);
            if (now.equals(lastSig)) return;       // degismediyse cizme
            lastSig = now;
            RemoteViews v = build(c, s);
            for (int id : ids) mgr.updateAppWidget(id, v);
        } catch (Throwable ignored) {
        }
    }

    static void ensureService(Context c) {
        try {
            if (!RemoteService.running) {
                Intent i = new Intent(c, RemoteService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }
}
