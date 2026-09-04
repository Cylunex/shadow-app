package com.shadow.app.health;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/** One capture attempt, no health values or credentials. Older callbacks cannot replace it. */
public final class ScaleAttempt {
    private ScaleAttempt() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("health_scale_attempt", Context.MODE_PRIVATE);
    }
    public static synchronized void begin(Context context, String id) {
        prefs(context).edit().clear().putString("id", id).putString("stage", "requested")
                .putLong("updated", System.currentTimeMillis()).apply();
    }
    public static synchronized void stage(Context context, String id, String stage) {
        if (id == null || id.isEmpty() || !id.equals(prefs(context).getString("id", ""))) return;
        prefs(context).edit().putString("stage", stage).putLong("updated", System.currentTimeMillis()).apply();
    }
    public static synchronized JSONObject read(Context context, String id) throws JSONException {
        SharedPreferences p = prefs(context);
        JSONObject out = new JSONObject();
        out.put("attempt_id", id);
        if (!id.equals(p.getString("id", ""))) return out.put("stage", "superseded");
        return out.put("stage", p.getString("stage", "unknown")).put("updated_at_ms", p.getLong("updated", 0));
    }
}
