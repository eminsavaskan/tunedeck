package com.emin.ytmremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private WebView web;
    private static final int REQ_NOTIF = 41;
    private volatile boolean sheetOpen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#030303"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#030303"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        startRemoteService();
        askNotificationPermission();
        web.loadUrl(Api.base(this));
    }

    // ------------------------------------------------------------------ JS <-> native koprusu
    public class Bridge {

        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("server", Api.base(MainActivity.this));
                o.put("notifMode", Api.prefs(MainActivity.this).getString("notif_mode", "media"));
                o.put("serviceRunning", RemoteService.running);
                o.put("version", "2.0");
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("os", Build.VERSION.RELEASE);
                o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setServer(final String url) {
            runOnUiThread(() -> {
                String v = url == null ? "" : url.trim();
                if (v.isEmpty()) return;
                if (!v.startsWith("http")) v = "http://" + v;
                Api.setBase(MainActivity.this, v);
                reload();
            });
        }

        @JavascriptInterface
        public void setNotifMode(final String mode) {
            runOnUiThread(() -> {
                Api.prefs(MainActivity.this).edit().putString("notif_mode",
                        "plain".equals(mode) ? "plain" : "media").apply();
                try {
                    startService(new Intent(MainActivity.this, RemoteService.class).putExtra("cmd", "rebuild"));
                } catch (Throwable ignored) {
                }
                toast("Bildirim modu güncellendi");
            });
        }

        @JavascriptInterface
        public void testNotification() {
            runOnUiThread(() -> {
                Diag.testNotification(getApplicationContext());
                toast("Test bildirimi gönderildi");
            });
        }

        @JavascriptInterface
        public void batterySettings() {
            runOnUiThread(MainActivity.this::askBattery);
        }

        @JavascriptInterface
        public void diag() {
            runOnUiThread(MainActivity.this::showDiag);
        }

        @JavascriptInterface
        public void toggleServiceAction() {
            runOnUiThread(MainActivity.this::toggleService);
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(MainActivity.this::reload);
        }

        @JavascriptInterface
        public void toastMsg(final String m) {
            runOnUiThread(() -> toast(m));
        }

        @JavascriptInterface
        public void ensureService() {
            runOnUiThread(MainActivity.this::startRemoteService);
        }

        @JavascriptInterface
        public void setSheet(final boolean open) {
            sheetOpen = open;
        }
    }

    // ------------------------------------------------------------------ servis
    private void startRemoteService() {
        try {
            Intent i = new Intent(this, RemoteService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> Diag.send(getApplicationContext(), "ACILIS"), 2500);
        } catch (Throwable t) {
            RemoteService.lastError = "startService: " + t;
            toast("Bildirim servisi başlatılamadı: " + t);
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void reload() {
        if (web != null) web.loadUrl(Api.base(this));
    }

    private void toggleService() {
        if (RemoteService.running) {
            stopService(new Intent(this, RemoteService.class));
            toast("Kontroller kapatıldı");
        } else {
            startRemoteService();
            toast("Kontroller açıldı");
        }
    }

    private void askBattery() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("Ayarlar açılamadı: Pil > Uygulama pil kullanımı > YT Music Remote");
            }
        }
    }

    private void showDiag() {
        Diag.send(getApplicationContext(), "ELLE");
        toast("Rapor bilgisayara gönderiliyor…");
        new Thread(() -> {
            final String rep = Diag.report(getApplicationContext());
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Bildirim teşhisi")
                    .setMessage(rep)
                    .setPositiveButton("Tamam", null)
                    .setNeutralButton("Test bildirimi", (d, w) -> {
                        Diag.testNotification(getApplicationContext());
                        toast("Test bildirimi gönderildi");
                    })
                    .show());
        }).start();
    }

    // ------------------------------------------------------------------ yasam
    @Override
    public void onBackPressed() {
        if (sheetOpen) {
            if (web != null) web.evaluateJavascript(
                    "(function(){ if(window.closeSheet){ window.closeSheet(); } })()", null);
            sheetOpen = false;
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void toast(String m) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show();
    }
}
