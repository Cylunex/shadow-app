package com.shadow.app.health;

import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Authenticated JSON transport shared by native health data sources. */
final class HealthHttpPost {
    private HealthHttpPost() {
    }

    static boolean postJson(String tag, String url, String token, String json,
                            int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(HealthServerConfig.bare(url)).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);
            applyAuth(connection, url, token);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            Log.i(tag, "POST " + HealthServerConfig.bare(url) + " -> " + code);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.w(tag, "POST failed", e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static void applyAuth(HttpURLConnection connection, String url, String token) {
        String basic = HealthServerConfig.basicAuthHeader(url);
        if (basic == null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        } else {
            connection.setRequestProperty("Authorization", basic);
            connection.setRequestProperty("X-Ingest-Token", token);
        }
    }
}
