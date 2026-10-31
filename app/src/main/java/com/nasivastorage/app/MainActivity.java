package com.nasivastorage.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "NasivaRealtimePrefs";
    public static final String KEY_SITE_URL = "site_url";
    public static final String KEY_ROLE = "user_role"; // "customer" atau "admin"
    public static final String KEY_PHONE = "user_phone";
    public static final String DEFAULT_SITE_URL = "https://jasaisigamesbekasi.blogspot.com";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private SharedPreferences prefs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set warna status bar & navigation bar senada tema #050b14
        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#050b14"));
        window.setNavigationBarColor(Color.parseColor("#050b14"));

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Layout utama: SwipeRefreshLayout + WebView + ProgressBar tipis di atas + Tombol Mengambang Kecil untuk Pengaturan APK
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#050b14"));

        swipeRefresh = new SwipeRefreshLayout(this);
        swipeRefresh.setColorSchemeColors(Color.parseColor("#00f3ff"), Color.parseColor("#bc13fe"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#050b14"));
        swipeRefresh.addView(webView);
        root.addView(swipeRefresh, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 8, Gravity.TOP
        );
        root.addView(progressBar, pbParams);

        setContentView(root);

        // Konfigurasi Ultra-Light WebView
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setLoadsImagesAutomatically(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString(ws.getUserAgentString() + " NasivaStorageAPK/21.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // Pasang Jembatan Javascript (window.NasivaAndroidApp)
        webView.addJavascriptInterface(new NasivaJsBridge(this), "NasivaAndroidApp");

        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        // Cegah SwipeRefresh mengganggu scroll di dalam pop-up modal katalog / keranjang
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            swipeRefresh.setEnabled(scrollY == 0);
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();

                // Buka WhatsApp, Google Maps, YouTube, atau Email langsung di aplikasi aslinya
                if (url.startsWith("whatsapp:") || url.contains("api.whatsapp.com") || url.contains("wa.me")
                        || url.startsWith("mailto:") || url.startsWith("tel:")
                        || url.contains("maps.google.") || url.contains("share.google")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
                // Suntikkan sinkronisasi otomatis ke halaman web
                view.evaluateJavascript(
                        "if(typeof window.syncStateToNativeApk==='function'){window.syncStateToNativeApk();}",
                        null
                );
            }
        });

        // Minta izin POST_NOTIFICATIONS untuk Android 13+ (Tiramisu)
        requestNotificationPermissionIfNeeded();

        // Jalankan Foreground Service Pemantau Real-Time
        startRealtimeBackgroundService();

        // Muat URL (atau Deep-Link dari ketukan notifikasi)
        handleLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        String baseUrl = prefs.getString(KEY_SITE_URL, DEFAULT_SITE_URL);
        if (intent != null && intent.hasExtra("deep_link_query")) {
            String query = intent.getStringExtra("deep_link_query");
            if (query != null && !query.isEmpty()) {
                String target = baseUrl.endsWith("/") ? (baseUrl + "?" + query) : (baseUrl + "/?" + query);
                webView.loadUrl(target);
                return;
            }
        }
        webView.loadUrl(baseUrl);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    public void startRealtimeBackgroundService() {
        Intent serviceIntent = new Intent(this, NasivaRealtimeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void showNativeSettingsDialog() {
        runOnUiThread(() -> {
            String curRole = prefs.getString(KEY_ROLE, "customer");
            String curPhone = prefs.getString(KEY_PHONE, "");
            String curUrl = prefs.getString(KEY_SITE_URL, DEFAULT_SITE_URL);

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (20 * getResources().getDisplayMetrics().density);
            layout.setPadding(pad, pad, pad, pad);

            TextView tvInfo = new TextView(this);
            tvInfo.setText("Status Pemantau Latar Belakang: AKTIF 24 JAM\nMode Saat Ini: "
                    + ("admin".equals(curRole) ? "ADMIN RESMI" : "PELANGGAN")
                    + "\nNomor WA Tersinkron: " + (curPhone.isEmpty() ? "(Belum diisi)" : curPhone));
            tvInfo.setPadding(0, 0, 0, pad / 2);
            layout.addView(tvInfo);

            final EditText inputPhone = new EditText(this);
            inputPhone.setHint("Nomor WA Pesanan (Contoh: 08123456789)");
            inputPhone.setInputType(InputType.TYPE_CLASS_PHONE);
            inputPhone.setText(curPhone);
            layout.addView(inputPhone);

            final EditText inputUrl = new EditText(this);
            inputUrl.setHint("URL Website Nasiva Storage");
            inputUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
            inputUrl.setText(curUrl);
            layout.addView(inputUrl);

            new AlertDialog.Builder(this)
                    .setTitle("⚙️ Pengaturan Real-Time APK")
                    .setView(layout)
                    .setPositiveButton("SIMPAN & AKTIFKAN", (dialog, which) -> {
                        String newPhone = inputPhone.getText().toString().replaceAll("[^0-9]", "");
                        if (newPhone.startsWith("62")) newPhone = "0" + newPhone.substring(2);
                        String newUrl = inputUrl.getText().toString().trim();
                        if (newUrl.isEmpty()) newUrl = DEFAULT_SITE_URL;

                        prefs.edit()
                                .putString(KEY_PHONE, newPhone)
                                .putString(KEY_SITE_URL, newUrl)
                                .apply();

                        startRealtimeBackgroundService();
                        Toast.makeText(this, "Service Real-Time Diperbarui!", Toast.LENGTH_SHORT).show();
                    })
                    .setNeutralButton("MATIKAN HEMAT BATERAI", (dialog, which) -> {
                        requestIgnoreBatteryOptimization();
                    })
                    .setNegativeButton("TES NOTIF", (dialog, which) -> {
                        NasivaRealtimeService.triggerSystemNotification(
                                this,
                                "🔔 TES NOTIFIKASI APK NASIVA!",
                                "Service latar belakang APK aktif! Notifikasi akan tetap muncul walau aplikasi ditutup.",
                                ""
                        );
                    })
                    .show();
        });
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Aplikasi sudah bebas dari batasan baterai!", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Silakan atur Baterai -> Tanpa Batasan di Info Aplikasi.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // Jembatan Javascript <-> Native Android APK
    public class NasivaJsBridge {
        private final Context ctx;

        public NasivaJsBridge(Context ctx) {
            this.ctx = ctx;
        }

        @JavascriptInterface
        public void updateServiceConfig(String role, String phone, String siteOrigin) {
            String cleanRole = ("admin".equalsIgnoreCase(role)) ? "admin" : "customer";
            String cleanPhone = phone != null ? phone.replaceAll("[^0-9]", "") : "";
            if (cleanPhone.startsWith("62")) cleanPhone = "0" + cleanPhone.substring(2);

            SharedPreferences.Editor ed = prefs.edit();
            ed.putString(KEY_ROLE, cleanRole);
            if (!cleanPhone.isEmpty()) {
                ed.putString(KEY_PHONE, cleanPhone);
            }
            if (siteOrigin != null && siteOrigin.startsWith("http")) {
                ed.putString(KEY_SITE_URL, siteOrigin);
            }
            ed.apply();

            startRealtimeBackgroundService();
        }

        @JavascriptInterface
        public void showInstantNotification(String title, String message, String badge) {
            String fullTitle = (badge != null && !badge.isEmpty()) ? (title + " [" + badge + "]") : title;
            NasivaRealtimeService.triggerSystemNotification(ctx, fullTitle, message, "");
        }

        @JavascriptInterface
        public void openNativeServiceDialog() {
            showNativeSettingsDialog();
        }
    }
}
