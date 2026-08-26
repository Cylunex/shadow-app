package com.shadow.app.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/** Validates and serves the reviewed mobile projection of Shadow Platform Catalog. */
public final class ModuleRegistry {
    private final String identityIssuer;
    private final String deploymentId;
    private final String buildId;
    private final List<AppModule> modules;

    private ModuleRegistry(String identityIssuer, String deploymentId, String buildId,
                           List<AppModule> modules) {
        this.identityIssuer = identityIssuer;
        this.deploymentId = deploymentId;
        this.buildId = buildId;
        this.modules = modules;
    }

    public static ModuleRegistry load(Context context) {
        try (InputStream input = context.getAssets().open("modules.json")) {
            JSONObject root = new JSONObject(readAll(input));
            int schemaVersion = root.optInt("schemaVersion");
            if (schemaVersion != 3 && schemaVersion != 4) {
                throw new JSONException("unsupported module schema");
            }
            JSONObject platform = root.getJSONObject("platform");
            if (platform.optInt("catalogVersion") != 1) {
                throw new JSONException("unsupported Platform Catalog version");
            }
            String issuer = UrlTools.normalizeBase(platform.getString("identityIssuer"));
            if (!issuer.startsWith("https://")) {
                throw new JSONException("Platform Identity issuer must use HTTPS");
            }
            if (URI.create(issuer).getUserInfo() != null) {
                throw new JSONException("Platform Identity issuer cannot embed credentials");
            }
            String deploymentId = platform.optString("deploymentId", "legacy-local");
            String buildId = platform.optString("buildId", "legacy-local");
            if (schemaVersion == 4) {
                if (!deploymentId.matches("[a-z][a-z0-9-]{1,63}")) {
                    throw new JSONException("invalid Platform deploymentId");
                }
                if (!buildId.matches("[a-f0-9]{64}")) {
                    throw new JSONException("invalid Platform buildId");
                }
            }
            JSONArray values = root.getJSONArray("modules");
            List<AppModule> result = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                AppModule module = AppModule.fromJson(values.getJSONObject(i));
                if (schemaVersion == 4 && !module.productId.matches(
                        "shadow-[a-z][a-z0-9-]{1,56}")) {
                    throw new JSONException("invalid product id: " + module.productId);
                }
                if (find(result, module.id) != null) {
                    throw new JSONException("duplicate module id: " + module.id);
                }
                result.add(module);
            }
            result.sort(Comparator.comparingInt(module -> module.order));
            return new ModuleRegistry(issuer, deploymentId, buildId,
                    Collections.unmodifiableList(result));
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Cannot load modules.json", e);
        }
    }

    public List<AppModule> all() {
        return modules;
    }

    public AppModule get(String id) {
        return find(modules, id);
    }

    public boolean isIdentityUrl(String url) {
        return UrlTools.sameOrigin(url, identityIssuer);
    }

    public String identityIssuer() {
        return identityIssuer;
    }

    public String deploymentId() {
        return deploymentId;
    }

    public String buildId() {
        return buildId;
    }

    public String clientJson(Context context) {
        JSONArray out = new JSONArray();
        for (AppModule module : modules) {
            if (!module.enabled) {
                continue;
            }
            try {
                out.put(module.toClientJson(UrlTools.bare(
                        ServerConfig.moduleUrl(context, this, module.id))));
            } catch (JSONException ignored) {
            }
        }
        return out.toString();
    }

    private static AppModule find(List<AppModule> modules, String id) {
        for (AppModule module : modules) {
            if (module.id.equals(id)) {
                return module;
            }
        }
        return null;
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            out.write(buffer, 0, count);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
