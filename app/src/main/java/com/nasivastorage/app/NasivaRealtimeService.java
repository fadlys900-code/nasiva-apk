package com.nasivastorage.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

public class NasivaRealtimeService extends Service {

    private static final String CHANNEL_BG_ID = "nasiva_bg_service_channel";
    private static final String CHANNEL_ALERT_ID = "nasiva_realtime_alert_channel";
    private static final String FIREBASE_DB_URL = "https://nasivastorage-3a604-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String ADMIN_NTFY_TOPIC = "nasiva_admin_bekasi_1408";

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread firebaseWatcherThread;
    private Thread ntfyStreamThread;
    private String activeStreamTopic = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String role = prefs.getString(MainActivity.KEY_ROLE, "customer");
        String phone = prefs.getString(MainActivity.KEY_PHONE, "");

        String statusText;
        if ("admin".equals(role)) {
            statusText = "Mode Admin Aktif • Memantau Pesanan Pelanggan Masuk 24 Jam";
        } else if (!phone.isEmpty()) {
            statusText = "Memantau Progres Pesanan WA: " + phone + " Secara Real-Time";
        } else {
            statusText = "Siaga • Sinkronkan Nomor WA di Keranjang untuk Pantau Progres";
        }

        Notification fgNotif = buildForegroundStatusNotification(statusText);
        startForeground(1408, fgNotif);

        if (!isRunning.getAndSet(true)) {
            startFirebaseWatcherLoop();
        }
        restartNtfyStreamIfNeeded(role, phone);

        // START_STICKY memastikan Android menghidupkan ulang service ini jika memori penuh
        return START_STICKY;
    }

    private void startFirebaseWatcherLoop() {
        firebaseWatcherThread = new Thread(() -> {
            boolean isFirstCheck = true;
            long adminBootTimestamp = System.currentTimeMillis();

            while (isRunning.get()) {
                try {
                    SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
                    String role = prefs.getString(MainActivity.KEY_ROLE, "customer");
                    String phone = prefs.getString(MainActivity.KEY_PHONE, "");

                    if ("admin".equals(role)) {
                        checkAdminFirebaseSyncAlerts(prefs, adminBootTimestamp, isFirstCheck);
                    } else if (phone != null && phone.length() >= 8) {
                        checkCustomerFirebaseCartProgress(prefs, phone, isFirstCheck);
                    }

                    isFirstCheck = false;
                } catch (Exception ignored) {
                }

                try {
                    Thread.sleep(8000); // Cek ringan setiap 8 detik di latar belakang
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "NasivaFirebaseWatcher");
        firebaseWatcherThread.start();
    }

    private void checkAdminFirebaseSyncAlerts(SharedPreferences prefs, long bootTime, boolean isFirstCheck) {
        String jsonStr = fetchHttpGet(FIREBASE_DB_URL + "/sinkronisasi_keranjang.json");
        if (jsonStr == null || jsonStr.equals("null") || jsonStr.isEmpty()) return;

        try {
            JSONObject root = new JSONObject(jsonStr);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String phoneKey = keys.next();
                JSONObject phoneNode = root.optJSONObject(phoneKey);
                if (phoneNode == null) continue;

                JSONObject syncAlert = phoneNode.optJSONObject("sync_alert");
                if (syncAlert == null) continue;

                long eventId = syncAlert.optLong("eventId", 0);
                int gameCount = syncAlert.optInt("gameCount", 0);
                String prefKey = "last_admin_sync_" + phoneKey;
                long lastSeenEvent = prefs.getLong(prefKey, 0);

                if (isFirstCheck && lastSeenEvent == 0) {
                    prefs.edit().putLong(prefKey, Math.max(eventId, bootTime)).apply();
                    continue;
                }

                if (eventId > lastSeenEvent && eventId > bootTime) {
                    prefs.edit().putLong(prefKey, eventId).apply();
                    triggerSystemNotification(
                            this,
                            "🔔 PELANGGAN SINKRON WA: " + phoneKey,
                            "Pelanggan " + phoneKey + " [📲 MANDIRI] baru saja menyinkronkan keranjang (" + gameCount + " Game). Ketuk untuk membuka Keranjang Admin.",
                            "open_admin_wa=" + phoneKey
                    );
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void checkCustomerFirebaseCartProgress(SharedPreferences prefs, String phone, boolean isFirstCheck) {
        String jsonStr = fetchHttpGet(FIREBASE_DB_URL + "/carts/" + phone + ".json");
        if (jsonStr == null || jsonStr.equals("null") || jsonStr.isEmpty()) return;

        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONArray allItems = new JSONArray();

            JSONObject pribadi = root.optJSONObject("pribadi");
            if (pribadi != null && pribadi.has("items")) {
                JSONArray arr = pribadi.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        allItems.put(arr.getJSONObject(i));
                    }
                }
            }

            JSONObject rental = root.optJSONObject("rental");
            if (rental != null) {
                Iterator<String> machines = rental.keys();
                while (machines.hasNext()) {
                    String mKey = machines.next();
                    JSONObject mObj = rental.optJSONObject(mKey);
                    if (mObj != null && mObj.has("items")) {
                        JSONArray arr = mObj.optJSONArray("items");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                allItems.put(arr.getJSONObject(i));
                            }
                        }
                    }
                }
            }

            int totalGames = 0;
            int filledGames = 0;
            int testedGames = 0;
            int newlyFilledCount = 0;
            int newlyTestedCount = 0;
            String lastFilledTitle = "";
            String lastTestedTitle = "";

            for (int i = 0; i < allItems.length(); i++) {
                JSONObject item = allItems.getJSONObject(i);
                String title = item.optString("title", "Game");
                String platform = item.optString("platform", "");
                if ("ITEM TAMBAHAN".equalsIgnoreCase(platform)) continue;

                totalGames++;
                boolean isFilled = item.optBoolean("isFilled", false);
                boolean isTested = item.optBoolean("isTested", false);
                String note = item.optString("note", "").trim();

                if (isFilled) filledGames++;
                if (isTested) testedGames++;

                String itemKey = "cust_" + phone + "_" + platform + "_" + title.toLowerCase();
                boolean prevFilled = prefs.getBoolean(itemKey + "_filled", false);
                boolean prevTested = prefs.getBoolean(itemKey + "_tested", false);
                String prevNote = prefs.getString(itemKey + "_note", "");

                if (!isFirstCheck) {
                    if (isFilled && !prevFilled) {
                        newlyFilledCount++;
                        lastFilledTitle = title + " (" + platform + ")";
                    }
                    if (isTested && !prevTested) {
                        newlyTestedCount++;
                        lastTestedTitle = title + " (" + platform + ")";
                    }
                    if (!note.isEmpty() && !note.equals(prevNote)) {
                        triggerSystemNotification(
                                this,
                                "💬 CATATAN DARI ADMIN NASIVA",
                                title + ": \"" + note + "\"",
                                "open_user_wa=" + phone
                        );
                    }
                }

                prefs.edit()
                        .putBoolean(itemKey + "_filled", isFilled)
                        .putBoolean(itemKey + "_tested", isTested)
                        .putString(itemKey + "_note", note)
                        .apply();
            }

            boolean isAllDone = (totalGames > 0 && filledGames == totalGames && testedGames == totalGames);
            boolean wasAllDone = prefs.getBoolean("cust_" + phone + "_all_done", false);
            prefs.edit().putBoolean("cust_" + phone + "_all_done", isAllDone).apply();

            if (!isFirstCheck) {
                if (isAllDone && !wasAllDone && (newlyFilledCount > 0 || newlyTestedCount > 0)) {
                    triggerSystemNotification(
                            this,
                            "🎉 PESANAN SELESAI 100% (" + totalGames + "/" + totalGames + " GAME)!",
                            "Seluruh " + totalGames + " game Anda sudah selesai DIISI dan DITES oleh Admin Nasiva Storage!",
                            "open_user_wa=" + phone
                    );
                } else {
                    if (newlyTestedCount > 0) {
                        triggerSystemNotification(
                                this,
                                "🎮 GAME SELESAI DITES (" + testedGames + "/" + totalGames + ")",
                                lastTestedTitle + " telah selesai dites oleh Admin & siap dimainkan!",
                                "open_user_wa=" + phone
                        );
                    } else if (newlyFilledCount > 0) {
                        triggerSystemNotification(
                                this,
                                "✅ GAME SELESAI DIISI (" + filledGames + "/" + totalGames + ")",
                                lastFilledTitle + " sudah selesai diisi ke penyimpanan, menunggu tahap pengetesan.",
                                "open_user_wa=" + phone
                        );
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void restartNtfyStreamIfNeeded(String role, String phone) {
        String desiredTopic = "";
        if ("admin".equals(role)) {
            desiredTopic = ADMIN_NTFY_TOPIC;
        } else if (phone != null && phone.length() >= 8) {
            desiredTopic = "nasiva_wa_" + phone;
        }

        if (desiredTopic.isEmpty() || desiredTopic.equals(activeStreamTopic)) return;
        activeStreamTopic = desiredTopic;

        if (ntfyStreamThread != null) {
            ntfyStreamThread.interrupt();
        }

        final String topicToListen = desiredTopic;
        final String deepLinkParam = "admin".equals(role) ? "" : ("open_user_wa=" + phone);

        ntfyStreamThread = new Thread(() -> {
            while (isRunning.get() && topicToListen.equals(activeStreamTopic)) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("https://ntfy.sh/" + topicToListen + "/json");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(0); // Infinite stream read
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    while (isRunning.get() && (line = reader.readLine()) != null) {
                        JSONObject obj = new JSONObject(line);
                        if ("message".equals(obj.optString("event"))) {
                            String title = obj.optString("title", "Nasiva Storage");
                            String msg = obj.optString("message", "");
                            if (!msg.isEmpty()) {
                                triggerSystemNotification(this, title, msg, deepLinkParam);
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "NasivaNtfyStream");
        ntfyStreamThread.start();
    }

    private String fetchHttpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                sb.append(line);
            }
            rd.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private Notification buildForegroundStatusNotification(String statusText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_BG_ID)
                .setContentTitle("Nasiva Storage • Live Sync Aktif")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    public static void triggerSystemNotification(Context ctx, String title, String message, String deepLinkQuery) {
        createNotificationChannels(ctx);

        Intent intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (deepLinkQuery != null && !deepLinkQuery.isEmpty()) {
            intent.putExtra("deep_link_query", deepLinkQuery);
        }

        int reqCode = (int) (System.currentTimeMillis() & 0xfffffff);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, reqCode, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ALERT_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setSound(soundUri)
                .setVibrate(new long[]{0, 350, 150, 350})
                .setLights(Color.parseColor("#00f3ff"), 1000, 1000)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(reqCode, builder.build());
        }
    }

    public static void createNotificationChannels(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel bgChannel = new NotificationChannel(
                    CHANNEL_BG_ID,
                    "Status Koneksi Latar Belakang",
                    NotificationManager.IMPORTANCE_LOW
            );
            bgChannel.setDescription("Menjaga pemantau pesanan Nasiva Storage tetap aktif 24 jam");
            nm.createNotificationChannel(bgChannel);

            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ALERT_ID,
                    "Notifikasi Progres Game & Pesanan Baru",
                    NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription("Notifikasi real-time saat game selesai diisi, dites, atau ada pelanggan sinkron WA");
            alertChannel.enableLights(true);
            alertChannel.setLightColor(Color.parseColor("#00f3ff"));
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{0, 350, 150, 350});
            nm.createNotificationChannel(alertChannel);
        }
    }

    @Override
    public void onDestroy() {
        isRunning.set(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
