package com.shadow.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable module descriptor loaded from assets/modules.json. */
public final class AppModule {
    public final String id;
    public final String name;
    public final String description;
    public final String startPath;
    public final String healthPath;
    public final String color;
    public final String icon;
    public final boolean enabled;
    public final JSONArray capabilities;

    private AppModule(JSONObject value) {
        id = value.optString("id");
        name = value.optString("name", id);
        description = value.optString("description");
        startPath = value.optString("startPath", "/");
        healthPath = value.optString("healthPath");
        color = value.optString("color", "#64748b");
        icon = value.optString("icon", "web");
        enabled = value.optBoolean("enabled", true);
        capabilities = value.optJSONArray("capabilities") == null
                ? new JSONArray() : value.optJSONArray("capabilities");
    }

    public static AppModule fromJson(JSONObject value) throws JSONException {
        AppModule module = new AppModule(value);
        if (!module.id.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new JSONException("invalid module id: " + module.id);
        }
        if (!module.startPath.startsWith("/")) {
            throw new JSONException("startPath must be absolute: " + module.id);
        }
        return module;
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
}
