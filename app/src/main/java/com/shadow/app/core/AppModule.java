package com.shadow.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable mobile projection of one Shadow Platform App Catalog entry. */
public final class AppModule {
    public final String id;
    public final String name;
    public final String description;
    public final String color;
    public final String icon;
    public final String authMode;
    public final boolean enabled;
    public final JSONArray capabilities;
    public final List<ModuleRoute> routes;

    private AppModule(JSONObject value) throws JSONException {
        id = value.optString("id");
        name = value.optString("name", id);
        description = value.optString("description");
        color = value.optString("color", "#64748b");
        icon = value.optString("icon", "web");
        enabled = value.optBoolean("enabled", true);
        capabilities = value.optJSONArray("capabilities") == null
                ? new JSONArray() : value.optJSONArray("capabilities");

        JSONObject auth = value.getJSONObject("auth");
        authMode = auth.getString("mode");
        String healthPath = value.isNull("health_path")
                ? "" : value.optString("health_path");

        List<ModuleRoute> parsedRoutes = new ArrayList<>();
        parsedRoutes.add(ModuleRoute.fromPlatformUrl(
                ModuleRoute.CANONICAL,
                value.getString("canonical_url"),
                healthPath,
                id,
                true));
        JSONArray aliases = value.optJSONArray("aliases");
        if (aliases != null) {
            for (int i = 0; i < aliases.length(); i++) {
                ModuleRoute route = ModuleRoute.fromPlatformUrl(
                        "alias-" + i, aliases.getString(i), healthPath, id, false);
                if (containsUrl(parsedRoutes, route.url)) {
                    throw new JSONException("duplicate Platform URL for " + id + ": " + route.url);
                }
                parsedRoutes.add(route);
            }
        }
        routes = Collections.unmodifiableList(parsedRoutes);
    }

    public static AppModule fromJson(JSONObject value) throws JSONException {
        AppModule module = new AppModule(value);
        if (!module.id.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new JSONException("invalid module id: " + module.id);
        }
        return module;
    }

    public ModuleRoute route(String key) {
        for (ModuleRoute route : routes) {
            if (route.key.equals(key)) {
                return route;
            }
        }
        return null;
    }

    public JSONObject toClientJson(String resolvedUrl) throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("description", description)
                .put("url", resolvedUrl)
                .put("authMode", authMode)
                .put("platformManaged", true)
                .put("color", color)
                .put("icon", icon)
                .put("enabled", enabled)
                .put("capabilities", capabilities);
    }

    private static boolean containsUrl(List<ModuleRoute> routes, String url) {
        for (ModuleRoute route : routes) {
            if (route.url.equals(url)) {
                return true;
            }
        }
        return false;
    }
}
