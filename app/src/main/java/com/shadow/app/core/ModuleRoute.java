package com.shadow.app.core;

import org.json.JSONException;

import java.net.URI;

/** One immutable endpoint projected from a Platform canonical URL or alias. */
public final class ModuleRoute {
    public static final String CANONICAL = "canonical";

    public final String key;
    public final String url;
    public final String probeUrl;

    private ModuleRoute(String key, String url, String probeUrl) {
        this.key = key;
        this.url = url;
        this.probeUrl = probeUrl;
    }

    public static ModuleRoute fromPlatformUrl(String key, String rawUrl, String healthPath,
                                              String moduleId, boolean canonical)
            throws JSONException {
        String normalized = UrlTools.normalizeBase(rawUrl);
        if (normalized.isEmpty()) {
            throw new JSONException("invalid Platform URL for " + moduleId + ": " + rawUrl);
        }
        if (canonical && !normalized.startsWith("https://")) {
            throw new JSONException("canonical URL must use HTTPS: " + moduleId);
        }
        if (URI.create(normalized).getUserInfo() != null) {
            throw new JSONException("Platform URLs cannot embed credentials: " + moduleId);
        }
        String entryUrl = UrlTools.join(normalized, "/");
        String probe = healthPath == null || healthPath.isEmpty()
                ? "" : UrlTools.join(normalized, healthPath);
        return new ModuleRoute(key, entryUrl, probe);
    }
}
