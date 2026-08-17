package com.shadow.app.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Selects canonical/alias endpoints from the built-in Shadow Platform Catalog projection. */
public final class ServerConfig {
    public static final String PREFS_NAME = "shell";

    private static final String KEY_ACTIVE_ROUTE_PREFIX = "active_route_";
    private static final String KEY_LEGACY_NAS_URL = "nas_server_url";
    private static final String KEY_LEGACY_CLOUD_URL = "cloud_server_url";
    private static final String KEY_LEGACY_URLS = "server_urls";
    private static final String KEY_LEGACY_ACTIVE = "active_server_url";

    private ServerConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Remove obsolete user-entered endpoints, including any legacy embedded credentials. */
    public static void discardLegacyOverrides(Context context) {
        prefs(context).edit()
                .remove(KEY_LEGACY_NAS_URL)
                .remove(KEY_LEGACY_CLOUD_URL)
                .remove(KEY_LEGACY_URLS)
                .remove(KEY_LEGACY_ACTIVE)
                .apply();
    }

    public static String moduleUrl(Context context, ModuleRegistry registry, String moduleId) {
        AppModule module = registry.get(moduleId);
        if (module == null) {
            return "";
        }
        ModuleRoute active = activeRoute(context, module);
        return active == null ? "" : active.url;
    }

    public static List<String> moduleUrls(ModuleRegistry registry, String moduleId) {
        List<String> result = new ArrayList<>();
        AppModule module = registry.get(moduleId);
        if (module == null) {
            return result;
        }
        for (ModuleRoute route : module.routes) {
            if (!result.contains(route.url)) {
                result.add(route.url);
            }
        }
        return result;
    }

    /** Blocking failover probe; only call from a worker thread. Returns a module base URL. */
    public static String resolveModule(Context context, ModuleRegistry registry, String moduleId) {
        String available = availableModule(context, registry, moduleId);
        return available.isEmpty() ? moduleUrl(context, registry, moduleId) : available;
    }

    /** Blocking probe; selects the first reachable Platform endpoint for this module. */
    public static String availableModule(Context context, ModuleRegistry registry, String moduleId) {
        AppModule module = registry.get(moduleId);
        if (module == null) {
            return "";
        }
        ModuleRoute active = activeRoute(context, module);
        if (active != null && probe(active)) {
            return active.url;
        }
        for (ModuleRoute candidate : module.routes) {
            if (active != null && candidate.key.equals(active.key)) {
                continue;
            }
            if (probe(candidate)) {
                activate(context, module.id, candidate.key);
                return candidate.url;
            }
        }
        return "";
    }

    /** Probe Platform fallbacks after a foreground module page failed to load. */
    public static String availableAlternativeModule(Context context, ModuleRegistry registry,
                                                    String moduleId, String failedUrl) {
        AppModule module = registry.get(moduleId);
        if (module == null) {
            return "";
        }
        for (ModuleRoute candidate : module.routes) {
            if (sameModuleRoute(failedUrl, candidate.url)) {
                continue;
            }
            if (probe(candidate)) {
                activate(context, module.id, candidate.key);
                return candidate.url;
            }
        }
        return "";
    }

    public static boolean isTrustedNavigationUrl(ModuleRegistry registry, String url) {
        if (registry.isIdentityUrl(url)) {
            return true;
        }
        for (AppModule module : registry.all()) {
            for (ModuleRoute route : module.routes) {
                if (UrlTools.sameOrigin(url, route.url)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ModuleRoute activeRoute(Context context, AppModule module) {
        String key = prefs(context).getString(KEY_ACTIVE_ROUTE_PREFIX + module.id, "");
        ModuleRoute selected = module.route(key);
        return selected == null ? module.routes.get(0) : selected;
    }

    private static void activate(Context context, String moduleId, String key) {
        prefs(context).edit().putString(KEY_ACTIVE_ROUTE_PREFIX + moduleId, key).apply();
    }

    private static boolean probe(ModuleRoute route) {
        if (route.probeUrl.isEmpty()) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(UrlTools.bare(route.probeUrl)).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean sameModuleRoute(String first, String second) {
        if (!UrlTools.sameOrigin(first, second)) {
            return false;
        }
        String a = UrlTools.bare(first);
        String b = UrlTools.bare(second);
        return a != null && b != null && (a.startsWith(b) || b.startsWith(a));
    }
}
