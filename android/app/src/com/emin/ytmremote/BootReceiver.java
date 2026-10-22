package com.emin.ytmremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Telefon acildiginda servisi baslatir: bildirim/kilit ekrani kontrolleri ve widget hazir olsun. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            Intent i = new Intent(context, RemoteService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Throwable ignored) {
        }
    }
}
