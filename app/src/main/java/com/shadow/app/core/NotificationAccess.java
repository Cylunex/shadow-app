package com.shadow.app.core;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Central notification permission/app-toggle gate used by foreground and worker code. */
public final class NotificationAccess {
    private NotificationAccess() {
    }

    public static boolean isAllowed(Context context) {
        boolean runtimePermissionGranted = Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return allows(Build.VERSION.SDK_INT, runtimePermissionGranted,
                manager != null && manager.areNotificationsEnabled());
    }

    static boolean allows(int sdkInt, boolean runtimePermissionGranted,
                          boolean appNotificationsEnabled) {
        return (sdkInt < 33 || runtimePermissionGranted) && appNotificationsEnabled;
    }
}
