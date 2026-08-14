package com.shadow.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Ordered portal endpoints. A module path is resolved against the active endpoint. */
public final class ServerConfig {
    public static final String PREFS_NAME = "shell";
    public static final String KEY_ACTIVE = "active_server_url";
    public static final String KEY_URLS = "server_urls";
    public static final String DEFAULT_SERVER_URL = "http://192.168.1.100:55080";

    private ServerConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static List<String> urls(Context context) {
        List<String> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_URLS, "");
        for (String line : raw.split("\n")) {
            String value = UrlTools.normalizeBase(line);
            if (!value.isEmpty() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    public static String active(Context context) {
        String current = UrlTools.normalizeBase(prefs(context).getString(KEY_ACTIVE, ""));
        if (!current.isEmpty()) {
            return current;
        }
        List<String> configured = urls(context);
        return configured.isEmpty() ? "" : configured.get(0);
    }

    public static void save(Context context, List<String> values) {
        prefs(context).edit()
                .putString(KEY_URLS, String.join("\n", values))
                .putString(KEY_ACTIVE, values.isEmpty() ? "" : values.get(0))
                .apply();
    }

    public static String moduleUrl(Context context, ModuleRegistry registry, String moduleId) {
        AppModule module = registry.get(moduleId);
        return module == null ? "" : UrlTools.join(active(context), module.startPath);
    }

    /** Blocking failover probe; only call from a worker thread. Returns a module base URL. */
    public static String resolveModule(Context context, ModuleRegistry registry, String moduleId) {
        String available = availableModule(context, registry, moduleId);
        return available.isEmpty() ? moduleUrl(context, registry, moduleId) : available;
    }

    /** Blocking failover probe; returns an empty string when every endpoint is unavailable. */
    public static String availableModule(Context context, ModuleRegistry registry, String moduleId) {
        AppModule module = registry.get(moduleId);
        if (module == null) {
            return "";
        }
        String current = active(context);
        if (!current.isEmpty() && probe(current, module)) {
            return UrlTools.join(current, module.startPath);
        }
        for (String candidate : urls(context)) {
            if (candidate.equals(current)) {
                continue;
            }
            if (probe(candidate, module)) {
                prefs(context).edit().putString(KEY_ACTIVE, candidate).apply();
                return UrlTools.join(candidate, module.startPath);
            }
        }
        return "";
    }

    /** Probe only configured fallbacks after a foreground page failed to load. */
    public static String availableAlternativeModule(Context context, ModuleRegistry registry,
                                                    String moduleId, String failedBase) {
        AppModule module = registry.get(moduleId);
        if (module == null || module.healthPath.isEmpty()) {
            return "";
        }
        String excluded = UrlTools.normalizeBase(failedBase);
        for (String candidate : urls(context)) {
            if (candidate.equals(excluded)) {
                continue;
            }
            if (probe(candidate, module)) {
                return candidate;
            }
        }
        return "";
    }

    public static void activate(Context context, String baseUrl) {
        String normalized = UrlTools.normalizeBase(baseUrl);
        if (!normalized.isEmpty() && urls(context).contains(normalized)) {
            prefs(context).edit().putString(KEY_ACTIVE, normalized).apply();
        }
    }

    public static String basicAuthHeader(String url) {
        try {
            String info = Uri.parse(url).getUserInfo();
            if (info == null || info.isEmpty()) {
                return null;
            }
            byte[] decoded = Uri.decode(info).getBytes(StandardCharsets.UTF_8);
            return "Basic " + Base64.encodeToString(decoded, Base64.NO_WRAP);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String basicAuthHeaderForUrl(Context context, String url) {
        for (String server : urls(context)) {
            if (UrlTools.sameOrigin(url, server)) {
                return basicAuthHeader(server);
            }
        }
        return null;
    }

    public static String[] credentialsForHost(Context context, String host) {
        for (String server : urls(context)) {
            Uri uri = Uri.parse(server);
            String info = uri.getUserInfo();
            if (info == null || !host.equalsIgnoreCase(uri.getHost())) {
                continue;
            }
            String decoded = Uri.decode(info);
            int split = decoded.indexOf(':');
            return split >= 0
                    ? new String[]{decoded.substring(0, split), decoded.substring(split + 1)}
                    : new String[]{decoded, ""};
        }
        return null;
    }

    public static boolean isTrustedModuleUrl(Context context, String url) {
        for (String server : urls(context)) {
            if (UrlTools.sameOrigin(url, server)) {
                return true;
            }
        }
        return false;
    }

    private static boolean probe(String server, AppModule module) {
        if (module.healthPath.isEmpty()) {
            return true;
        }
        HttpURLConnection connection = null;
        try {
            String target = UrlTools.join(server, module.healthPath);
            connection = (HttpURLConnection) new URL(UrlTools.bare(target)).openConnection();
            String auth = basicAuthHeader(server);
            if (auth != null) {
                connection.setRequestProperty("Authorization", auth);
            }
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
