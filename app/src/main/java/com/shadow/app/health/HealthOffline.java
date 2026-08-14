package com.shadow.app.health;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.shadow.app.core.UrlTools;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Narrow public facade keeping health-specific offline behavior out of the shell core. */
public final class HealthOffline {
    public static final long BOOTSTRAP_REFRESH_MS = 60 * 60_000L;

    private HealthOffline() {
    }

    public static WebResourceResponse intercept(Context context, WebResourceRequest request) {
        String server = HealthServerConfig.matching(context, request.getUrl());
        return server == null ? null : SnapshotCache.intercept(context, request, server);
    }

    public static boolean consumeReplayedMain(String url) {
        return SnapshotCache.consumeReplayedMain(url);
    }

    public static String availableEndpoint(Context context) {
        return HealthServerConfig.available(context);
    }

    public static int queueSize(Context context) {
        return OfflineStore.queueSize(context);
    }

    public static void scheduleFlush(Context context) {
        OfflineStore.scheduleFlush(context);
    }

    public static long bootstrapAgeMs(Context context) {
        return OfflineStore.bootstrapAgeMs(context);
    }

    public static void fetchBootstrap(Context context) {
        OfflineStore.fetchBootstrap(context);
    }

    public static String bootstrap(Context context) {
        return OfflineStore.bootstrap(context);
    }

    public static int enqueue(Context context, String type, String payloadJson) {
        if (!"habit".equals(type) && !"diet".equals(type)
                && !"workout".equals(type) && !"metric".equals(type)) {
            return -1;
        }
        return OfflineStore.enqueue(context, type, localDate(), payloadJson);
    }

    public static int setQueuedHabit(Context context, int habitId, int doneCount) {
        return OfflineStore.setQueuedHabit(context, habitId, localDate(), doneCount);
    }

    public static String queuedHabits(Context context) {
        return OfflineStore.queuedHabits(context, localDate());
    }

    public static String status(Context context, String error) {
        try {
            return new JSONObject()
                    .put("queued", queueSize(context))
                    .put("error", error == null ? "" : error)
                    .put("server", UrlTools.bare(HealthServerConfig.active(context)))
                    .toString();
        } catch (JSONException ignored) {
            return "{}";
        }
    }

    private static String localDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
