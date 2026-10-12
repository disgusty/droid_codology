package com.disgusty.droid_codology.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One configured AI endpoint: its API format, address, key and the models to offer. */
public final class AiProvider {
    /** API formats. SERVER is a droid_codology server (uses the signed-in account). */
    public static final String OPENAI = "openai", ANTHROPIC = "anthropic", GEMINI = "gemini", SERVER = "server";

    public String id, name, kind, baseUrl, apiKey;
    public final ArrayList<String> models = new ArrayList<String>();

    public AiProvider(String id, String name, String kind, String baseUrl, String apiKey, String... models) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        for (String m : models) this.models.add(m);
    }

    public boolean needsKey() {
        return !kind.equals(SERVER) && !baseUrl.contains("127.0.0.1") && !baseUrl.contains("localhost");
    }

    public JSONObject toJson(boolean withKey) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("kind", kind);
        o.put("baseUrl", baseUrl);
        if (withKey) o.put("apiKey", apiKey);
        o.put("models", new JSONArray(models));
        return o;
    }

    public static AiProvider fromJson(JSONObject o) {
        AiProvider p = new AiProvider(o.optString("id"), o.optString("name"), o.optString("kind", OPENAI),
                o.optString("baseUrl"), o.optString("apiKey", ""));
        JSONArray a = o.optJSONArray("models");
        if (a != null) for (int i = 0; i < a.length(); i++) p.models.add(a.optString(i));
        return p;
    }

    /** Templates for "Add provider". Model lists are starting points; users can edit them. */
    public static List<AiProvider> presets() {
        ArrayList<AiProvider> l = new ArrayList<AiProvider>();
        l.add(new AiProvider("anthropic", "Anthropic", ANTHROPIC, "https://api.anthropic.com", "",
                "claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-4-5-20251001"));
        l.add(new AiProvider("openai", "OpenAI", OPENAI, "https://api.openai.com/v1", "", "gpt-4o-mini", "gpt-4o"));
        l.add(new AiProvider("gemini", "Google Gemini", GEMINI, "https://generativelanguage.googleapis.com", "",
                "gemini-2.5-flash", "gemini-2.5-pro"));
        l.add(new AiProvider("openrouter", "OpenRouter", OPENAI, "https://openrouter.ai/api/v1", "",
                "anthropic/claude-sonnet-5-5", "openai/gpt-4o-mini", "deepseek/deepseek-chat"));
        l.add(new AiProvider("deepseek", "DeepSeek", OPENAI, "https://api.deepseek.com/v1", "", "deepseek-chat", "deepseek-reasoner"));
        l.add(new AiProvider("mistral", "Mistral", OPENAI, "https://api.mistral.ai/v1", "", "codestral-latest", "mistral-large-latest"));
        l.add(new AiProvider("groq", "Groq", OPENAI, "https://api.groq.com/openai/v1", "", "llama-3.3-70b-versatile"));
        l.add(new AiProvider("ollama", "Ollama (local network)", OPENAI, "http://192.168.1.2:11434/v1", "", "qwen2.5-coder:7b"));
        l.add(new AiProvider("lmstudio", "LM Studio", OPENAI, "http://192.168.1.2:1234/v1", "", "local-model"));
        l.add(new AiProvider("custom", "OpenAI-compatible", OPENAI, "https://", "", "model"));
        return l;
    }
}
