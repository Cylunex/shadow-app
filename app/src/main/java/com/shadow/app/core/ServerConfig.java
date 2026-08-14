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

/** Resolves module routes against the NAS and cloud server environments. */
public final class ServerConfig {
    public static final String PREFS_NAME = "shell";
    public static final String KEY_NAS_URL = "nas_server_url";
    public static final String KEY_CLOUD_URL = "cloud_server_url";
    public static final String DEFAULT_NAS_URL = "http://192.168.1.100";

    private static final String KEY_LEGACY_URLS = "server_urls";
    private static final String KEY_LEGACY_ACTIVE = "active_server_url";
    private static final String KEY_ACTIVE_ROUTE_PREFIX = "active_route_";

    private ServerConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String nas(Context context) {
        String configured = UrlTools.normalizeBase(prefs(context).getString(KEY_NAS_URL, ""));
        if (!configured.isEmpty()) {
            return configured;
        }
        return legacyUrl(context, 0);
    }

    public static String cloud(Context context) {
        String configured = UrlTools.normalizeBase(prefs(context).getString(KEY_CLOUD_URL, ""));
        return configured.isEmpty() ? legacyUrl(context, 1) : configured;
    }

    public static boolean isConfigured(Context context) {
        return !nas(context).isEmpty() || !cloud(context).isEmpty();
    }

    public static void save(Context context, String nasUrl, String cloudUrl) {
        prefs(context).edit()
                .putString(KEY_NAS_URL, UrlTools.normalizeBase(nasUrl))
                .putString(KEY_CLOUD_URL, UrlTools.normalizeBase(cloudUrl))
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
        if (active != null) {
            return routeUrl(context, active);
        }
        for (ModuleRoute route : module.routes) {
            String url = routeUrl(context, route);
            if (!url.isEmpty()) {
                return url;
            }
        }
        return "";
    }

    public static List<String> moduleUrls(Context context, ModuleRegistry registry,
                                          String moduleId) {
        List<String> result = new ArrayList<>();
        AppModule module = registry.get(moduleId);
        if (module == null) {
            return result;
        }
        for (ModuleRoute route : module.routes) {
            String url = routeUrl(context, route);
            if (!url.isEmpty() && !result.contains(url)) {
                result.add(url);
            }
        }
        return result;
    }

    /** Blocking failover probe; only call from a worker thread. Returns a module base URL. */
    public static String resolveModule(Context context, ModuleRegistry registry, String moduleId) {
        String available = availableModule(context, registry, moduleId);
        return available.isEmpty() ? moduleUrl(context, registry, moduleId) : available;
    }

    /** Blocking probe; selects the first reachable configured route for this module. */
    public static String availableModule(Context context, ModuleRegistry registry, String moduleId) {
        AppModule module = registry.get(moduleId);
        if (module == null) {
            return "";
        }
        ModuleRoute active = activeRoute(context, module);
        if (active != null && probe(context, active)) {
            return routeUrl(context, active);
        }
        for (ModuleRoute candidate : module.routes) {
            if (active != null && candidate.server.equals(active.server)) {
                continue;
            }
            if (probe(context, candidate)) {
                activate(context, module.id, candidate.server);
                return routeUrl(context, candidate);
            }
        }
        return "";
    }

    /** Probe configured fallbacks after a foreground module page failed to load. */
    public static String availableAlternativeModule(Context context, ModuleRegistry registry,
                                                    String moduleId, String failedUrl) {
        AppModule module = registry.get(moduleId);
        if (module == null) {
            return "";
        }
        for (ModuleRoute candidate : module.routes) {
            String candidateUrl = routeUrl(context, candidate);
            if (candidateUrl.isEmpty() || sameModuleRoute(failedUrl, candidateUrl)) {
                continue;
            }
            if (probe(context, candidate)) {
                activate(context, module.id, candidate.server);
                return candidateUrl;
            }
        }
        return "";
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
        String root = matchingRoot(context, url);
        return root == null ? null : basicAuthHeader(root);
    }

    public static String[] credentialsForHost(Context context, String host) {
        for (String root : roots(context)) {
            Uri uri = Uri.parse(root);
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

    public static boolean isTrustedModuleUrl(Context context, ModuleRegistry registry,
                                             String url) {
        for (AppModule module : registry.all()) {
            for (String moduleUrl : moduleUrls(context, registry, module.id)) {
                if (UrlTools.sameOrigin(url, moduleUrl)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ModuleRoute activeRoute(Context context, AppModule module) {
        String server = prefs(context).getString(KEY_ACTIVE_ROUTE_PREFIX + module.id, "");
        ModuleRoute selected = module.route(server);
        if (selected != null && !routeUrl(context, selected).isEmpty()) {
            return selected;
        }
        for (ModuleRoute route : module.routes) {
            if (!routeUrl(context, route).isEmpty()) {
                return route;
            }
        }
        return null;
    }

    private static void activate(Context context, String moduleId, String server) {
        prefs(context).edit().putString(KEY_ACTIVE_ROUTE_PREFIX + moduleId, server).apply();
    }

    private static String routeUrl(Context context, ModuleRoute route) {
        String root = ModuleRoute.NAS.equals(route.server) ? nas(context) : cloud(context);
        if (root.isEmpty()) {
            return "";
        }
        return UrlTools.join(UrlTools.withPort(root, route.port), route.startPath);
    }

    private static boolean probe(Context context, ModuleRoute route) {
        String moduleUrl = routeUrl(context, route);
        if (moduleUrl.isEmpty()) {
            return false;
        }
        if (route.probePath.isEmpty()) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            String root = ModuleRoute.NAS.equals(route.server) ? nas(context) : cloud(context);
            String target = UrlTools.join(UrlTools.withPort(root, route.port), route.probePath);
            connection = (HttpURLConnection) new URL(UrlTools.bare(target)).openConnection();
            String auth = basicAuthHeader(root);
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

    private static List<String> roots(Context context) {
        List<String> result = new ArrayList<>();
        if (!nas(context).isEmpty()) {
            result.add(nas(context));
        }
        if (!cloud(context).isEmpty()) {
            result.add(cloud(context));
        }
        return result;
    }

    private static String matchingRoot(Context context, String url) {
        for (String root : roots(context)) {
            try {
                Uri target = Uri.parse(url);
                Uri configured = Uri.parse(root);
                if (target.getHost() != null
                        && target.getHost().equalsIgnoreCase(configured.getHost())
                        && target.getScheme() != null
                        && target.getScheme().equalsIgnoreCase(configured.getScheme())) {
                    return root;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static String legacyUrl(Context context, int requestedIndex) {
        // Compatibility with the original ordered portal list: first is NAS, second is cloud.
        int index = 0;
        String legacy = prefs(context).getString(KEY_LEGACY_URLS, "");
        for (String line : legacy.split("\n")) {
            String value = UrlTools.normalizeBase(line);
            if (value.isEmpty()) {
                continue;
            }
            if (index == requestedIndex) {
                return value;
            }
            index++;
        }
        return "";
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
