package com.disgusty.droid_codology.ai;

import android.net.Uri;

import com.disgusty.droid_codology.account.Loopback;
import com.disgusty.droid_codology.net.Http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connecting an AI provider through the browser: the provider's key page is opened, the key
 * the user copies there is picked up from the clipboard and checked by listing the models.
 * OpenRouter supports real OAuth (PKCE), so it needs no copying at all.
 */
public final class KeyLink {
    private KeyLink() {
    }

    public static final class Service {
        public final String presetId, name, keysPage;
        final Pattern key;

        Service(String presetId, String name, String keysPage, String keyRegex) {
            this.presetId = presetId;
            this.name = name;
            this.keysPage = keysPage;
            this.key = Pattern.compile(keyRegex);
        }

        public AiProvider preset() {
            for (AiProvider p : AiProvider.presets()) if (p.id.equals(presetId)) return p;
            throw new IllegalStateException(presetId);
        }

        /** The first key of this service in {@code text}, or null. */
        public String findKey(String text) {
            if (text == null) return null;
            Matcher m = key.matcher(text);
            return m.find() ? m.group() : null;
        }
    }

    public static final Service CLAUDE = new Service("anthropic", "Claude (Anthropic)", "https://console.anthropic.com/settings/keys",
            "sk-ant-[A-Za-z0-9_\\-]{20,}");
    public static final Service GEMINI = new Service("gemini", "Google Gemini", "https://aistudio.google.com/apikey",
            "AIza[0-9A-Za-z_\\-]{35}");
    public static final Service OPENAI = new Service("openai", "OpenAI", "https://platform.openai.com/api-keys",
            "sk-(?!ant-|or-)(?:proj-|svcacct-|admin-)?[A-Za-z0-9_\\-]{20,}");

    public static List<Service> services() {
        ArrayList<Service> l = new ArrayList<Service>();
        l.add(CLAUDE);
        l.add(GEMINI);
        l.add(OPENAI);
        return l;
    }

    // ------------------------------------------------------------------ models (also checks the key)

    /** Chat models the key can use; throws with the provider's message if the key is wrong. */
    public static List<String> listModels(AiProvider p) throws IOException {
        HashMap<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/json");
        String url;
        if (p.kind.equals(AiProvider.ANTHROPIC)) {
            url = p.baseUrl + "/v1/models?limit=100";
            h.put("x-api-key", p.apiKey);
            h.put("anthropic-version", "2023-06-01");
        } else if (p.kind.equals(AiProvider.GEMINI)) {
            url = p.baseUrl + "/v1beta/models?pageSize=200&key=" + Uri.encode(p.apiKey);
        } else {
            url = p.baseUrl + "/models";
            if (!p.apiKey.isEmpty()) h.put("Authorization", "Bearer " + p.apiKey);
        }
        Http.Response r = Http.request("GET", url, h, null, null);
        if (!r.ok()) throw new IOException(AiClient.errorMessage(r.code, r.text()));
        try {
            return filterModels(p.kind, p.id, new JSONObject(r.text()));
        } catch (JSONException e) {
            throw new IOException("Unexpected answer: " + e.getMessage());
        }
    }

    static List<String> filterModels(String kind, String providerId, JSONObject o) {
        ArrayList<String> out = new ArrayList<String>();
        if (kind.equals(AiProvider.GEMINI)) {
            JSONArray a = o.optJSONArray("models");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name").replaceFirst("^models/", "");
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                if (!name.startsWith("gemini-") || methods == null || !methods.toString().contains("generateContent")) continue;
                if (name.matches(".*(embedding|aqa|tts|image|audio|live).*")) continue;
                out.add(name);
            }
        } else {
            JSONArray a = o.optJSONArray("data");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                String id = m == null ? "" : m.optString("id");
                if (id.isEmpty()) continue;
                if (kind.equals(AiProvider.ANTHROPIC)) {
                    if (id.startsWith("claude-")) out.add(id);
                } else if (providerId.startsWith("openai")) {
                    String l = id.toLowerCase(Locale.ROOT);
                    if (!l.matches("^(gpt-|o\\d|chatgpt-).*")) continue;
                    if (l.matches(".*(audio|realtime|transcribe|tts|image|search|embedding|instruct|moderation).*")) continue;
                    out.add(id);
                } else {
                    out.add(id);
                }
            }
            // Anthropic lists newest first already; OpenAI in no particular order.
            if (providerId.startsWith("openai")) Collections.sort(out, Collections.<String>reverseOrder());
        }
        return out.size() > 20 ? new ArrayList<String>(out.subList(0, 20)) : out;
    }

    // ------------------------------------------------------------------ OpenRouter OAuth (PKCE)

    public static String openRouterAuthUrl(Loopback lb) {
        return "https://openrouter.ai/auth?callback_url=" + Uri.encode(lb.redirectUri())
                + "&code_challenge=" + lb.challenge() + "&code_challenge_method=S256";
    }

    /** Exchanges the browser's code for an OpenRouter API key. */
    public static String openRouterKey(Loopback lb, Uri redirect) throws IOException {
        String code = redirect.getQueryParameter("code");
        if (code == null) throw new IOException(redirect.getQueryParameter("error") != null ? redirect.getQueryParameter("error") : "cancelled");
        try {
            String body = new JSONObject().put("code", code).put("code_verifier", lb.verifier).put("code_challenge_method", "S256").toString();
            Http.Response r = Http.request("POST", "https://openrouter.ai/api/v1/auth/keys", null, body.getBytes("UTF-8"), "application/json");
            if (!r.ok()) throw new IOException(AiClient.errorMessage(r.code, r.text()));
            String key = new JSONObject(r.text()).optString("key", "");
            if (key.isEmpty()) throw new IOException("OpenRouter returned no key");
            return key;
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }
}
