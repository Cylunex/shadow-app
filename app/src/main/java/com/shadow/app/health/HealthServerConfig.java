package com.shadow.app.health;

import android.content.Context;
import android.net.Uri;

import com.shadow.app.core.ModuleRegistry;
import com.shadow.app.core.ServerConfig;
import com.shadow.app.core.UrlTools;

/** Resolves the health module endpoint for native ingestion workers. */
final class HealthServerConfig {
    private HealthServerConfig() {
    }

    static String active(Context context) {
        return trimTrailingSlash(
                ServerConfig.moduleUrl(context, ModuleRegistry.load(context), "health"));
    }

    static String resolveOrActive(Context context) {
        String resolved = ServerConfig.resolveModule(
                context, ModuleRegistry.load(context), "health");
        return resolved.isEmpty() ? active(context) : trimTrailingSlash(resolved);
    }

    static String available(Context context) {
        return trimTrailingSlash(ServerConfig.availableModule(
                context, ModuleRegistry.load(context), "health"));
    }

    static String matching(Context context, Uri requestUri) {
        ModuleRegistry registry = ModuleRegistry.load(context);
        for (String healthUrl : ServerConfig.moduleUrls(context, registry, "health")) {
            if (SnapshotCache.sameOrigin(requestUri, healthUrl)) {
                return trimTrailingSlash(healthUrl);
            }
        }
        return null;
    }

    static String bare(String url) {
        return UrlTools.bare(url);
    }

    static String basicAuthHeader(String url) {
        return ServerConfig.basicAuthHeader(url);
    }

    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
