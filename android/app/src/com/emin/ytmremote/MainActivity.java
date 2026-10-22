package com.emin.ytmremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * WebView kabugu + yerel (native) baglanti ekrani.
 * Kopruye ulasilamazsa tarayicinin hata sayfasi gosterilmez; uygulamanin kendi
 * ekrani cikar ve arka planda sessizce yeniden baglanmayi dener.
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 41;
    private static final int RETRY_MS = 4000;

    private WebView web;
    private LinearLayout overlay;
    private TextView oTitle, oSub;
    private ProgressBar oBar;
    private EditText oAddr;
    private Button oRetry, oSave;

    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean offline = false;
    private volatile boolean lastLoadFailed = false;
    private volatile boolean alive = true;
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
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        web.setLongClickable(false);                 // metin secimi / "kopyala" menusu cikmasin
        web.setHapticFeedbackEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                lastLoadFailed = false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!lastLoadFailed) hideOverlay();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                boolean main = true;
                try {
                    main = req == null || req.isForMainFrame();
                } catch (Throwable ignored) {
                }
                if (main) onConnectFailed();
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                onConnectFailed();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return false;   // her sey uygulamanin icinde kalsin
            }
        });

        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(web, fill);
        overlay = buildOverlay();
        root.addView(overlay, fill);
        setContentView(root);

        // kullanici uygulamayi acti -> "kapatildi" isareti kalksin
        Api.prefs(this).edit().putBoolean("user_stopped", false).apply();

        showOverlay(true);            // once "Baglaniyor…"
        startRemoteService();
        askNotificationPermission();
        web.loadUrl(Api.base(this));
    }

    // ------------------------------------------------------------------ baglanti ekrani
    private LinearLayout buildOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(30), dp(24), dp(30), dp(24));
        box.setBackgroundColor(Color.parseColor("#030303"));
        box.setClickable(true);
        box.setFocusable(true);

        oTitle = new TextView(this);
        oTitle.setTextSize(21);
        oTitle.setTextColor(0xFFFFFFFF);
        oTitle.setGravity(Gravity.CENTER);
        box.addView(oTitle);

        oSub = new TextView(this);
        oSub.setTextSize(13);
        oSub.setTextColor(0xFF8E8E93);
        oSub.setGravity(Gravity.CENTER);
        oSub.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(10);
        box.addView(oSub, subLp);

        oBar = new ProgressBar(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        barLp.topMargin = dp(22);
        box.addView(oBar, barLp);

        oAddr = new EditText(this);
        oAddr.setSingleLine(true);
        oAddr.setTextSize(14);
        oAddr.setTextColor(0xFFFFFFFF);
        oAddr.setHintTextColor(0xFF6E6E73);
        oAddr.setHint("http://192.168.1.50:8777");
        oAddr.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        oAddr.setBackground(round(0xFF16161A, 12));
        oAddr.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addrLp.topMargin = dp(22);
        box.addView(oAddr, addrLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);

        oRetry = mkBtn(getString(R.string.retry), false);
        oSave = mkBtn(getString(R.string.save_try), true);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b1.rightMargin = dp(8);
        row.addView(oRetry, b1);
        row.addView(oSave, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(row, rowLp);

        oRetry.setOnClickListener(v -> {
            showOverlay(true);
            reload();
        });
        oSave.setOnClickListener(v -> {
            String v2 = oAddr.getText().toString().trim();
            if (v2.isEmpty()) {
                oAddr.requestFocus();
                return;
            }
            if (!v2.startsWith("http")) v2 = "http://" + v2;
            Api.setBase(this, v2);
            showOverlay(true);
            reload();
        });
        return box;
    }

    private Button mkBtn(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(round(primary ? 0xFFFF2D55 : 0xFF232326, 14));
        b.setPadding(dp(18), dp(11), dp(18), dp(11));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void showOverlay(boolean connecting) {
        if (overlay == null) return;
        offline = true;
        oTitle.setText(connecting ? getString(R.string.connecting) : getString(R.string.off_title));
        oSub.setVisibility(connecting ? View.GONE : View.VISIBLE);
        oSub.setText(getString(R.string.off_hint));
        oBar.setVisibility(connecting ? View.VISIBLE : View.GONE);
        oAddr.setVisibility(connecting ? View.GONE : View.VISIBLE);
        oRetry.setVisibility(connecting ? View.GONE : View.VISIBLE);
        oSave.setVisibility(connecting ? View.GONE : View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        offline = false;
        if (overlay != null) overlay.setVisibility(View.GONE);
    }

    private void onConnectFailed() {
        lastLoadFailed = true;
        showOverlay(false);
        oAddr.setText(Api.base(this));
        h.removeCallbacks(retryTask);
        h.postDelayed(retryTask, 1500);
    }

    private final Runnable retryTask = new Runnable() {
        @Override
        public void run() {
            if (!alive || !offline) return;
            new Thread(() -> {
                final boolean up = Api.ping(MainActivity.this);
                runOnUiThread(() -> {
                    if (!alive) return;
                    if (up) {
                        reload();
                        h.postDelayed(() -> {
                            if (offline) hideOverlay();
                        }, 600);
                    } else if (offline) {
                        h.postDelayed(retryTask, RETRY_MS);
                    }
                });
            }, "ytm-ping").start();
        }
    };

    // ------------------------------------------------------------------ JS <-> native koprusu
    public class Bridge {

        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("server", Api.base(MainActivity.this));
                o.put("notifMode", Api.prefs(MainActivity.this).getString("notif_mode", "media"));
                o.put("serviceRunning", RemoteService.running);
                o.put("version", "2.7");
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
            });
        }

        @JavascriptInterface
        public void testNotification() {
            runOnUiThread(() -> {
                Diag.testNotification(getApplicationContext());
                toast(getString(R.string.test_title));
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

        @JavascriptInterface
        public String appVersion() {
            return "{\"name\":\"" + Updater.myName(MainActivity.this) + "\",\"code\":" + Updater.myCode(MainActivity.this) + "}";
        }

        private volatile String updUrl = "";

        @JavascriptInterface
        public void checkUpdate() {
            doCheck(false);
        }

        @JavascriptInterface
        public void forceCheckUpdate() {
            doCheck(true);
        }

        private void doCheck(final boolean force) {
            if (!force && !Updater.mayAutoCheck(MainActivity.this)) return;
            runJs("window.__updState && window.__updState('checking')");
            new Thread(() -> {
                Updater.Info info;
                try {
                    info = Updater.check(MainActivity.this);
                } catch (Throwable t) {
                    info = new Updater.Info();
                    info.error = String.valueOf(t);
                }
                Updater.markChecked(MainActivity.this);
                updUrl = info.newer ? info.url : "";
                final String js = "window.__upd && window.__upd(" + info.toJson() + ")";
                runOnUiThread(() -> runJs(js));
            }, "ytm-upd-check").start();
        }

        @JavascriptInterface
        public void installUpdate() {
            final String url = updUrl;
            if (url == null || url.isEmpty()) {
                runJs("window.__updState && window.__updState('error','no-url')");
                return;
            }
            // ilk kurulumda "bilinmeyen kaynak" izni gerekebilir
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    if (!getPackageManager().canRequestPackageInstalls()) {
                        toast(getString(R.string.upd_permission));
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:" + getPackageName())));
                        runJs("window.__updState && window.__updState('error','permission')");
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            runJs("window.__updState && window.__updState('downloading')");
            new Thread(() -> {
                String err = null;
                try {
                    Updater.downloadAndInstall(MainActivity.this, url);
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                final String e = err;
                runOnUiThread(() -> runJs(e == null
                        ? "window.__updState && window.__updState('installing')"
                        : "window.__updState && window.__updState('error'," + org.json.JSONObject.quote(e) + ")"));
            }, "ytm-upd-dl").start();
        }
    }

    // ------------------------------------------------------------------ servis
    private void startRemoteService() {
        try {
            Intent i = new Intent(this, RemoteService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            h.postDelayed(() -> Diag.send(getApplicationContext(), "ACILIS"), 2500);
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

    private void runJs(final String js) {
        if (web == null) return;
        runOnUiThread(() -> {
            try {
                web.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
            }
        });
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
                    .setTitle(getString(R.string.test_title))
                    .setMessage(rep)
                    .setPositiveButton("Tamam", null)
                    .setNeutralButton(getString(R.string.test_title), (d, w) -> {
                        Diag.testNotification(getApplicationContext());
                        toast(getString(R.string.test_title));
                    })
                    .show());
        }).start();
    }

    // ------------------------------------------------------------------ yasam
    @Override
    protected void onResume() {
        super.onResume();
        if (offline) {
            h.removeCallbacks(retryTask);
            h.post(retryTask);
        }
    }

    @Override
    public void onBackPressed() {
        if (sheetOpen) {
            if (web != null) web.evaluateJavascript(
                    "(function(){ if(window.closeSheet){ window.closeSheet(); } })()", null);
            sheetOpen = false;
            return;
        }
        if (offline) {
            moveTaskToBack(true);      // baglanti ekranindayken geri = uygulamayi arkaya al
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
        alive = false;
        h.removeCallbacksAndMessages(null);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
