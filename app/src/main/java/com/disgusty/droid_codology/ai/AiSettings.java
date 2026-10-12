package com.disgusty.droid_codology.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Configured AI providers (API keys stay on the device and are never synced) and the chosen
 * model. A signed-in droid_codology server with AI adds its models as the "server" provider.
 */
public final class AiSettings {
    private final SharedPreferences sp;

    public AiSettings(Context ctx) {
        sp = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE);
    }

    public List<AiProvider> providers() {
        ArrayList<AiProvider> out = new ArrayList<AiProvider>();
        try {
            JSONArray a = new JSONArray(sp.getString("providers", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(AiProvider.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void save(List<AiProvider> list) {
        JSONArray a = new JSONArray();
        try {
            for (AiProvider p : list) a.put(p.toJson(true));
        } catch (JSONException ignored) {
        }
        sp.edit().putString("providers", a.toString()).apply();
    }

    /** Models offered by the account's server, cached from /v1/info. */
    public List<String> serverModels() {
        ArrayList<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(sp.getString("serverModels", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void setServerModels(List<String> models) {
        sp.edit().putString("serverModels", new JSONArray(models).toString()).apply();
    }

    /** All usable providers, including the server one when {@code serverUrl} is signed in. */
    public List<AiProvider> all(String serverUrl, boolean signedIn) {
        List<AiProvider> l = providers();
        if (signedIn && serverUrl != null && !serverUrl.isEmpty() && !serverModels().isEmpty()) {
            AiProvider s = new AiProvider("server", "droid_codology server", AiProvider.SERVER, serverUrl, "");
            s.models.addAll(serverModels());
            l.add(0, s);
        }
        return l;
    }

    /** "providerId/model" of the selected model. */
    public String selected() {
        return sp.getString("model", "");
    }

    public void select(String providerId, String model) {
        sp.edit().putString("model", providerId + "/" + model).apply();
    }

    public String systemPrompt() {
        return sp.getString("system", "");
    }

    public void setSystemPrompt(String s) {
        sp.edit().putString("system", s).apply();
    }

    /** Provider list without keys, for settings sync. */
    public JSONArray exportWithoutKeys() throws JSONException {
        JSONArray a = new JSONArray();
        for (AiProvider p : providers()) a.put(p.toJson(false));
        return a;
    }

    /**
     * Merges synced providers, keeping keys already entered on this device. A key stays with its
     * provider only if the synced entry still points at the same service (kind and address):
     * synced data must not be able to redirect a key to another host. Providers that exist only
     * here and hold a key are kept, since keys are never synced and would be lost for good.
     */
    public void importWithoutKeys(JSONArray a, String model, String system) throws JSONException {
        List<AiProvider> mine = providers();
        ArrayList<AiProvider> out = new ArrayList<AiProvider>();
        ArrayList<String> ids = new ArrayList<String>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            AiProvider p = AiProvider.fromJson(o);
            p.apiKey = "";
            for (AiProvider m : mine) {
                if (m.id.equals(p.id) && m.kind.equals(p.kind) && m.baseUrl.equals(p.baseUrl)) p.apiKey = m.apiKey;
            }
            out.add(p);
            ids.add(p.id);
        }
        for (AiProvider m : mine) {
            if (m.apiKey.isEmpty()) continue;
            boolean placed = false;
            for (AiProvider p : out) placed |= p.apiKey.equals(m.apiKey);
            if (placed) continue;
            // Same id but another service in the synced list: keep the local one under a new id.
            if (ids.contains(m.id)) m.id = m.id + "-local";
            out.add(m);
        }
        save(out);
        SharedPreferences.Editor ed = sp.edit();
        if (model != null) ed.putString("model", model);
        if (system != null) ed.putString("system", system);
        ed.apply();
    }
}
