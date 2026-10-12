package com.disgusty.droid_codology.ai;

import com.disgusty.droid_codology.net.Http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streams chat completions from OpenAI-compatible, Anthropic and Gemini APIs, or from a
 * droid_codology server. Blocking; run it on a background thread.
 */
public final class AiClient {
    private AiClient() {
    }

    public static final class Message {
        public final String role, content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public interface Listener {
        void onDelta(String text);
    }

    /** Lets the caller stop a running request. */
    public static final class Cancel {
        volatile boolean cancelled;
        volatile Http.Stream stream;

        public void cancel() {
            cancelled = true;
            Http.Stream s = stream;
            if (s != null) s.close();
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    public static final class Request {
        public String url;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public JSONObject body;
    }

    /** {@code serverToken} is used for SERVER providers (the account session). */
    static Request build(AiProvider p, String model, String system, List<Message> messages, int maxTokens, String serverToken) throws JSONException {
        Request r = new Request();
        String base = p.baseUrl.replaceAll("/+$", "");
        JSONObject b = new JSONObject();
        JSONArray msgs = new JSONArray();
        if (p.kind.equals(AiProvider.ANTHROPIC)) {
            for (Message m : messages) msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
            b.put("model", model).put("messages", msgs).put("max_tokens", maxTokens > 0 ? maxTokens : 8192).put("stream", true);
            if (system != null && !system.isEmpty()) b.put("system", system);
            r.url = base + "/v1/messages";
            r.headers.put("x-api-key", p.apiKey);
            r.headers.put("anthropic-version", "2023-06-01");
        } else if (p.kind.equals(AiProvider.GEMINI)) {
            for (Message m : messages) {
                msgs.put(new JSONObject().put("role", m.role.equals("assistant") ? "model" : "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", m.content))));
            }
            b.put("contents", msgs);
            if (system != null && !system.isEmpty()) {
                b.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
            }
            if (maxTokens > 0) b.put("generationConfig", new JSONObject().put("maxOutputTokens", maxTokens));
            r.url = base + "/v1beta/models/" + model + ":streamGenerateContent?alt=sse";
            r.headers.put("x-goog-api-key", p.apiKey);
        } else if (p.kind.equals(AiProvider.SERVER)) {
            for (Message m : messages) msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
            b.put("model", model).put("messages", msgs).put("stream", true);
            if (system != null && !system.isEmpty()) b.put("system", system);
            if (maxTokens > 0) b.put("maxTokens", maxTokens);
            r.url = base + "/v1/ai/chat";
            if (serverToken != null) r.headers.put("Authorization", "Bearer " + serverToken);
        } else {
            if (system != null && !system.isEmpty()) msgs.put(new JSONObject().put("role", "system").put("content", system));
            for (Message m : messages) msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
            b.put("model", model).put("messages", msgs).put("stream", true);
            if (maxTokens > 0) b.put("max_tokens", maxTokens);
            r.url = base + "/chat/completions";
            if (!p.apiKey.isEmpty()) r.headers.put("Authorization", "Bearer " + p.apiKey);
            if (base.contains("openrouter.ai")) {
                r.headers.put("HTTP-Referer", "https://github.com/disgusty/droid_codology");
                r.headers.put("X-Title", "droid_codology");
            }
        }
        r.headers.put("Accept", "text/event-stream");
        r.body = b;
        return r;
    }

    /** Text of one SSE data payload, or null when the stream is finished. Throws on provider errors. */
    static String parseEvent(String kind, String data) throws IOException {
        data = data.trim();
        if (data.equals("[DONE]")) return null;
        JSONObject j;
        try {
            j = new JSONObject(data);
        } catch (JSONException e) {
            return "";
        }
        if (j.has("error")) {
            Object e = j.opt("error");
            String msg = e instanceof JSONObject ? ((JSONObject) e).optString("message", e.toString()) : String.valueOf(e);
            throw new IOException(msg);
        }
        if (kind.equals(AiProvider.ANTHROPIC)) {
            String t = j.optString("type");
            if (t.equals("content_block_delta")) {
                JSONObject d = j.optJSONObject("delta");
                return d == null ? "" : d.optString("text", "");
            }
            return t.equals("message_stop") ? null : "";
        }
        if (kind.equals(AiProvider.GEMINI)) {
            StringBuilder sb = new StringBuilder();
            JSONArray cands = j.optJSONArray("candidates");
            if (cands != null) for (int i = 0; i < cands.length(); i++) {
                JSONObject c = cands.optJSONObject(i).optJSONObject("content");
                JSONArray parts = c == null ? null : c.optJSONArray("parts");
                if (parts != null) for (int k = 0; k < parts.length(); k++) sb.append(parts.optJSONObject(k).optString("text", ""));
            }
            return sb.toString();
        }
        if (kind.equals(AiProvider.SERVER)) {
            if (j.optBoolean("done")) return null;
            return j.optString("delta", "");
        }
        StringBuilder sb = new StringBuilder();
        JSONArray choices = j.optJSONArray("choices");
        if (choices != null) for (int i = 0; i < choices.length(); i++) {
            JSONObject d = choices.optJSONObject(i).optJSONObject("delta");
            if (d != null && !d.isNull("content")) sb.append(d.optString("content", ""));
        }
        return sb.toString();
    }

    /** Reads an SSE stream, calling {@code l} for each text piece; returns the whole text. */
    static String readStream(String kind, InputStream in, Listener l, Cancel cancel) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder all = new StringBuilder();
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (cancel != null && cancel.cancelled) break;
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    String t = parseEvent(kind, data.toString());
                    data.setLength(0);
                    if (t == null) break;
                    if (!t.isEmpty()) {
                        all.append(t);
                        if (l != null) l.onDelta(t);
                    }
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).trim());
            }
        }
        if (data.length() > 0 && (cancel == null || !cancel.cancelled)) {
            String t = parseEvent(kind, data.toString());
            if (t != null && !t.isEmpty()) {
                all.append(t);
                if (l != null) l.onDelta(t);
            }
        }
        return all.toString();
    }

    /** Readable message from an error response body. */
    static String errorMessage(int code, String body) {
        try {
            JSONObject j = new JSONObject(body);
            Object e = j.opt("error");
            if (e instanceof JSONObject) return ((JSONObject) e).optString("message", body);
            if (e != null) return String.valueOf(e);
            if (j.has("message")) return j.optString("message");
        } catch (JSONException ignored) {
        }
        String s = body.trim();
        if (s.startsWith("<")) {
            // An HTML error page from a proxy: keep its text only.
            s = s.replaceAll("(?s)<(script|style|head)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", " ")
                    .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replaceAll("\\s+", " ").trim();
        }
        if (s.length() > 300) s = s.substring(0, 300) + "...";
        return "HTTP " + code + (s.isEmpty() ? "" : ": " + s);
    }

    public static String chat(AiProvider p, String model, String system, List<Message> messages, int maxTokens,
                              String serverToken, Listener l, Cancel cancel) throws IOException {
        Request req;
        try {
            req = build(p, model, system, messages, maxTokens, serverToken);
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
        Http.Stream s = Http.stream("POST", req.url, req.headers, req.body.toString().getBytes("UTF-8"), "application/json");
        if (cancel != null) {
            cancel.stream = s;
            if (cancel.cancelled) {
                s.close();
                return "";
            }
        }
        try {
            if (!s.ok()) throw new IOException(errorMessage(s.code, s.text()));
            return readStream(p.kind, s.body, l, cancel);
        } catch (IOException e) {
            if (cancel != null && cancel.cancelled) return "";
            throw e;
        } finally {
            s.close();
        }
    }
}
