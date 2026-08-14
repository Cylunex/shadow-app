package com.shadow.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable module descriptor loaded from assets/modules.json. */
public final class AppModule {
    public final String id;
    public final String name;
    public final String description;
    public final String color;
    public final String icon;
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
        JSONArray routeValues = value.getJSONArray("routes");
        List<ModuleRoute> parsedRoutes = new ArrayList<>();
        for (int i = 0; i < routeValues.length(); i++) {
            ModuleRoute route = ModuleRoute.fromJson(routeValues.getJSONObject(i), id);
            if (route(parsedRoutes, route.server) != null) {
                throw new JSONException("duplicate route server for " + id + ": " + route.server);
            }
            parsedRoutes.add(route);
        }
        routes = Collections.unmodifiableList(parsedRoutes);
    }

    public static AppModule fromJson(JSONObject value) throws JSONException {
        AppModule module = new AppModule(value);
        if (!module.id.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new JSONException("invalid module id: " + module.id);
        }
        if (module.routes.isEmpty()) {
            throw new JSONException("module must have at least one route: " + module.id);
        }
        return module;
    }

    public ModuleRoute route(String server) {
        return route(routes, server);
    }

    public JSONObject toClientJson(String resolvedUrl) throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("description", description)
                .put("url", resolvedUrl)
                .put("color", color)
                .put("icon", icon)
                .put("enabled", enabled)
                .put("capabilities", capabilities);
    }

    private static ModuleRoute route(List<ModuleRoute> routes, String server) {
        for (ModuleRoute route : routes) {
            if (route.server.equals(server)) {
                return route;
            }
        }
        return null;
    }
}
