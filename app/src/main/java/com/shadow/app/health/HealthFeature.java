package com.shadow.app.health;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import com.shadow.app.core.ServerConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lifecycle and permission boundary for optional native health capabilities. */
public final class HealthFeature {
    public static final String KEY_INGEST_TOKEN = "ingest_token";
    public static final String KEY_SCALE_SCAN = "scale_scan_enabled";
    public static final String KEY_SCALE_BINDKEY = "scale_bindkey";
    public static final int REQUEST_PERMISSIONS = 42;
    private static final int REQUEST_NOTIFICATIONS = 43;

    private final Activity activity;
    private final SharedPreferences prefs;
    private boolean pendingTimedScan;

    public HealthFeature(Activity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences(ServerConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void restore() {
        if (prefs.getBoolean(KEY_SCALE_SCAN, false) && missingScalePermissions().isEmpty()) {
            startScaleService(false);
        }
        if (prefs.getBoolean(SamsungSync.PREF_ENABLED, false) && SamsungSync.isAvailable()) {
            SamsungSync.schedule(activity.getApplicationContext());
        }
        if (prefs.getBoolean(Reminders.PREF_ENABLED, false)) {
            Reminders.schedule(activity.getApplicationContext());
        }
    }

    public void applySettings(String token, boolean scaleEnabled, String bindkey,
                              boolean samsungEnabled, boolean reminderEnabled) {
        boolean samsungWasEnabled = prefs.getBoolean(SamsungSync.PREF_ENABLED, false);
        String normalizedKey = bindkey == null ? "" : bindkey.trim().toLowerCase(Locale.US);
        if (!normalizedKey.isEmpty() && !normalizedKey.matches("[0-9a-f]{32}")) {
            normalizedKey = "";
            Toast.makeText(activity, "S400 bindkey 格式错误，已清空", Toast.LENGTH_LONG).show();
        }
        prefs.edit()
                .putString(KEY_INGEST_TOKEN, token == null ? "" : token.trim())
                .putBoolean(KEY_SCALE_SCAN, scaleEnabled)
                .putString(KEY_SCALE_BINDKEY, normalizedKey)
                .putBoolean(SamsungSync.PREF_ENABLED, samsungEnabled && SamsungSync.isAvailable())
                .putBoolean(Reminders.PREF_ENABLED, reminderEnabled)
                .apply();

        applyScaleSetting(scaleEnabled);
        if (samsungEnabled && SamsungSync.isAvailable() && !samsungWasEnabled) {
            SamsungSync.enable(activity);
        } else if (samsungEnabled && SamsungSync.isAvailable()) {
            SamsungSync.syncNow(activity.getApplicationContext());
        } else if (samsungWasEnabled) {
            SamsungSync.disable(activity.getApplicationContext());
        }
        if (reminderEnabled) {
            Reminders.schedule(activity.getApplicationContext());
            if (!scaleEnabled) {
                ensureNotificationPermission();
            }
        } else {
            Reminders.cancel(activity.getApplicationContext());
        }
    }

    public void startTimedScaleScan() {
        List<String> missing = missingScalePermissions();
        if (!missing.isEmpty()) {
            pendingTimedScan = true;
            activity.requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startScaleService(true);
        Toast.makeText(activity, "秤监听已开启 3 分钟，请上秤", Toast.LENGTH_SHORT).show();
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        boolean timed = pendingTimedScan;
        pendingTimedScan = false;
        boolean granted = permissions.length > 0;
        for (int i = 0; i < permissions.length; i++) {
            if (!Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])
                    && results[i] != PackageManager.PERMISSION_GRANTED) {
                granted = false;
            }
        }
        if (granted) {
            startScaleService(timed);
        } else {
            if (!timed) {
                prefs.edit().putBoolean(KEY_SCALE_SCAN, false).apply();
            }
            Toast.makeText(activity, "未授予蓝牙权限，秤监听未开启", Toast.LENGTH_LONG).show();
        }
    }

    private void applyScaleSetting(boolean enabled) {
        if (!enabled) {
            activity.stopService(new Intent(activity, ScaleScanService.class));
            return;
        }
        List<String> missing = missingScalePermissions();
        if (missing.isEmpty()) {
            startScaleService(false);
        } else {
            activity.requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private void startScaleService(boolean timed) {
        Intent intent = new Intent(activity, ScaleScanService.class);
        if (timed) {
            intent.putExtra(ScaleScanService.EXTRA_TIMED, true);
        }
        activity.startForegroundService(intent);
    }

    private List<String> missingScalePermissions() {
        List<String> result = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                result.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            result.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            result.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return result;
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }
}
