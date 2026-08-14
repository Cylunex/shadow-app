package com.shadow.app.core;

import java.net.URI;

/** URL normalization shared by the shell, downloads and native feature adapters. */
public final class UrlTools {
    private UrlTools() {
    }

    public static String normalizeBase(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                return "";
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return value;
    }

    public static String join(String base, String path) {
        String normalized = normalizeBase(base);
        if (normalized.isEmpty()) {
            return "";
        }
        String suffix = path == null ? "" : path.trim();
        if (suffix.isEmpty() || "/".equals(suffix)) {
            return normalized + "/";
        }
        return normalized + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }

    /** Remove user-info before showing a URL or handing it to WebView. */
    public static String bare(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', scheme + 3);
        int authorityEnd = pathStart < 0 ? url.length() : pathStart;
        int at = url.lastIndexOf('@', authorityEnd - 1);
        if (at <= scheme) {
            return url;
        }
        return url.substring(0, scheme + 3) + url.substring(at + 1);
    }

    public static boolean sameOrigin(String first, String second) {
        try {
            URI a = URI.create(first);
            URI b = URI.create(second);
            return equalsIgnoreCase(a.getScheme(), b.getScheme())
                    && equalsIgnoreCase(a.getHost(), b.getHost())
                    && effectivePort(a) == effectivePort(b);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
