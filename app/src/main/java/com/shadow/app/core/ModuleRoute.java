package com.shadow.app.core;

import org.json.JSONException;
import org.json.JSONObject;

/** One module deployment route on a configured server environment. */
public final class ModuleRoute {
    public static final String NAS = "nas";
    public static final String CLOUD = "cloud";

    public final String server;
    public final int port;
    public final String startPath;
    public final String probePath;

    private ModuleRoute(JSONObject value) {
        server = value.optString("server");
        port = value.optInt("port", 0);
        startPath = value.optString("startPath", "/");
        probePath = value.optString("probePath");
    }

    public static ModuleRoute fromJson(JSONObject value, String moduleId) throws JSONException {
        ModuleRoute route = new ModuleRoute(value);
        if (!NAS.equals(route.server) && !CLOUD.equals(route.server)) {
            throw new JSONException("invalid route server for " + moduleId + ": " + route.server);
        }
        if (value.has("port") && (route.port < 1 || route.port > 65535)) {
            throw new JSONException("invalid route port for " + moduleId + ": " + route.port);
        }
        if (!route.startPath.startsWith("/")) {
            throw new JSONException("route startPath must be absolute: " + moduleId);
        }
        if (!route.probePath.isEmpty() && !route.probePath.startsWith("/")) {
            throw new JSONException("route probePath must be absolute: " + moduleId);
        }
        return route;
    }
}
