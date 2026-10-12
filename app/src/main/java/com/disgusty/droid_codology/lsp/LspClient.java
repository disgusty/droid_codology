package com.disgusty.droid_codology.lsp;

import com.disgusty.droid_codology.net.Http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Language Server Protocol client talking to a language server through a droid_codology
 * server's bridge: messages go out by POST, come back over a server-sent-events stream.
 */
public final class LspClient {
    public interface Listener {
        /** Notifications from the server (diagnostics, logs...). Called on a background thread. */
        void onNotification(LspClient c, String method, JSONObject params);

        void onExit(LspClient c, String reason);
    }

    public interface Callback {
        /** {@code error} is null on success. Called on a background thread. */
        void done(Object result, String error);
    }

    public final String language;
    private final String server, token;
    private final Listener listener;
    private String session;
    public String rootUri, rootPath;
    public JSONObject capabilities = new JSONObject();
    private final AtomicInteger ids = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, Callback> pending = new ConcurrentHashMap<Integer, Callback>();
    /** Outgoing messages keep their order on one thread. */
    private final ExecutorService out = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile Http.Stream events;

    public LspClient(String server, String token, String language, Listener listener) {
        this.server = server.replaceAll("/+$", "");
        this.token = token;
        this.language = language;
        this.listener = listener;
    }

    private Map<String, String> auth() {
        HashMap<String, String> h = new HashMap<String, String>();
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/json");
        return h;
    }

    /** Starts the language server and runs initialize. Blocking. */
    public void start(String workspaceName) throws IOException {
        try {
            Http.Response r = Http.request("POST", server + "/v1/lsp/sessions", auth(),
                    new JSONObject().put("language", language).toString().getBytes("UTF-8"), "application/json");
            JSONObject o = new JSONObject(r.text());
            if (!r.ok()) throw new IOException(o.optString("error", "HTTP " + r.code));
            session = o.getString("id");
            rootUri = o.getString("rootUri");
            rootPath = o.optString("root");
        } catch (JSONException e) {
            throw new IOException("Bad answer from the server: " + e.getMessage());
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                readEvents();
            }
        }, "lsp-" + language);
        t.setDaemon(true);
        t.start();
        final Object[] result = new Object[2];
        final CountDownLatch latch = new CountDownLatch(1);
        request("initialize", initializeParams(workspaceName), new Callback() {
            @Override
            public void done(Object res, String error) {
                result[0] = res;
                result[1] = error;
                latch.countDown();
            }
        });
        String failure = null;
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) failure = "The language server didn't answer";
        } catch (InterruptedException e) {
            failure = "interrupted";
        }
        if (failure == null && result[1] != null) failure = (String) result[1];
        if (failure != null) {
            // Don't leave the event stream, its thread and the server-side session running.
            stop();
            throw new IOException(failure);
        }
        if (result[0] instanceof JSONObject) {
            JSONObject caps = ((JSONObject) result[0]).optJSONObject("capabilities");
            if (caps != null) capabilities = caps;
        }
        notify("initialized", new JSONObject());
    }

    private JSONObject initializeParams(String workspaceName) {
        try {
            JSONObject td = new JSONObject()
                    .put("synchronization", new JSONObject().put("didSave", true).put("dynamicRegistration", false))
                    .put("completion", new JSONObject().put("completionItem", new JSONObject()
                            .put("snippetSupport", true).put("documentationFormat", new JSONArray().put("markdown").put("plaintext"))))
                    .put("hover", new JSONObject().put("contentFormat", new JSONArray().put("markdown").put("plaintext")))
                    .put("definition", new JSONObject().put("linkSupport", false))
                    .put("references", new JSONObject())
                    .put("rename", new JSONObject().put("prepareSupport", false))
                    .put("formatting", new JSONObject())
                    .put("publishDiagnostics", new JSONObject().put("relatedInformation", false))
                    .put("signatureHelp", new JSONObject());
            JSONObject caps = new JSONObject().put("textDocument", td)
                    .put("workspace", new JSONObject().put("workspaceFolders", true).put("configuration", true).put("applyEdit", false))
                    .put("window", new JSONObject().put("workDoneProgress", false));
            return new JSONObject()
                    .put("processId", JSONObject.NULL)
                    .put("clientInfo", new JSONObject().put("name", "droid_codology"))
                    .put("rootUri", rootUri)
                    .put("rootPath", rootPath)
                    .put("workspaceFolders", new JSONArray().put(new JSONObject().put("uri", rootUri).put("name", workspaceName)))
                    .put("capabilities", caps);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean has(String capability) {
        Object v = capabilities.opt(capability);
        return v != null && v != JSONObject.NULL && !Boolean.FALSE.equals(v);
    }

    public boolean closed() {
        return closed;
    }

    // ------------------------------------------------------------------ messages

    public void request(String method, Object params, Callback cb) {
        int id = ids.getAndIncrement();
        if (cb != null) pending.put(id, cb);
        try {
            post(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params));
        } catch (JSONException e) {
            pending.remove(id);
            if (cb != null) cb.done(null, e.toString());
        }
    }

    public void notify(String method, Object params) {
        try {
            post(new JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params));
        } catch (JSONException ignored) {
        }
    }

    private void respond(Object id, Object result) {
        try {
            post(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result == null ? JSONObject.NULL : result));
        } catch (JSONException ignored) {
        }
    }

    private void post(final JSONObject msg) {
        if (closed) {
            failPending(msg, "The language server has stopped");
            return;
        }
        exec(new Runnable() {
            @Override
            public void run() {
                try {
                    Http.Response r = Http.request("POST", server + "/v1/lsp/sessions/" + session + "/send", auth(),
                            msg.toString().getBytes("UTF-8"), "application/json");
                    if (!r.ok()) {
                        failPending(msg, "HTTP " + r.code);
                        if (r.code == 404 || r.code == 410) shutdownLocally("The language server session ended");
                    }
                } catch (IOException e) {
                    failPending(msg, e.getMessage());
                }
            }
        });
    }

    private void failPending(JSONObject msg, String error) {
        if (!msg.has("id") || msg.has("result")) return;
        Callback cb = pending.remove(msg.optInt("id"));
        if (cb != null) cb.done(null, error);
    }

    /** Runs on the sending thread; dropped once the client is shut down (no crash from a late message). */
    private void exec(Runnable r) {
        try {
            out.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    /** Copies a file's text into the server's workspace so cross-file features see it. */
    public void upload(final String relPath, final String text) {
        exec(new Runnable() {
            @Override
            public void run() {
                try {
                    Http.request("PUT", server + "/v1/lsp/sessions/" + session + "/files", auth(),
                            new JSONObject().put("path", relPath).put("text", text).toString().getBytes("UTF-8"), "application/json");
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------ incoming

    private void readEvents() {
        String reason = "The language server stopped";
        int failures = 0;
        while (!closed) {
            try {
                Http.Stream s = Http.stream("GET", server + "/v1/lsp/sessions/" + session + "/events", auth(), null, null);
                events = s;
                if (!s.ok()) {
                    reason = "Language server events: HTTP " + s.code;
                    s.close();
                    if (s.code == 404 || s.code == 401) break;
                    throw new IOException(reason);
                }
                failures = 0;
                BufferedReader r = new BufferedReader(new InputStreamReader(s.body, "UTF-8"));
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.length() > 5 && line.charAt(5) == ' ' ? line.substring(6) : line.substring(5));
                    } else if (line.isEmpty() && data.length() > 0) {
                        String m = data.toString();
                        data.setLength(0);
                        if (handle(m)) {
                            closed = true;
                            break;
                        }
                    }
                }
                s.close();
            } catch (IOException e) {
                // Reconnect a few times (networks drop on phones).
                if (closed || ++failures > 5) {
                    reason = "Lost the connection to the language server: " + e.getMessage();
                    break;
                }
                try {
                    Thread.sleep(1000L * failures);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        shutdownLocally(reason);
    }

    /** Returns true when the session is over. */
    private boolean handle(String raw) {
        Object v;
        try {
            v = new JSONTokener(raw).nextValue();
        } catch (JSONException e) {
            return false;
        }
        if (!(v instanceof JSONObject)) return false;
        JSONObject m = (JSONObject) v;
        if ("exit".equals(m.optString("droidcodology"))) return true;
        String method = m.optString("method", null);
        if (method == null && m.has("id")) {
            Callback cb = pending.remove(m.optInt("id"));
            if (cb != null) {
                JSONObject err = m.optJSONObject("error");
                cb.done(m.opt("result"), err == null ? null : err.optString("message", "error"));
            }
            return false;
        }
        if (method != null && m.has("id")) {
            // Requests from the server: answer what's needed so it doesn't wait forever.
            Object id = m.opt("id");
            if (method.equals("workspace/configuration")) {
                JSONArray items = m.optJSONObject("params") == null ? null : m.optJSONObject("params").optJSONArray("items");
                JSONArray res = new JSONArray();
                for (int i = 0; items != null && i < items.length(); i++) res.put(JSONObject.NULL);
                respond(id, res);
            } else if (method.equals("workspace/workspaceFolders")) {
                try {
                    respond(id, new JSONArray().put(new JSONObject().put("uri", rootUri).put("name", "workspace")));
                } catch (JSONException ignored) {
                }
            } else {
                respond(id, null);
            }
            return false;
        }
        if (method != null && listener != null) listener.onNotification(this, method, m.optJSONObject("params"));
        return false;
    }

    private void shutdownLocally(String reason) {
        if (closed && pending.isEmpty() && reason == null) return;
        boolean first = !closed;
        closed = true;
        for (Integer id : pending.keySet()) {
            Callback cb = pending.remove(id);
            if (cb != null) cb.done(null, reason);
        }
        if (first && listener != null) listener.onExit(this, reason);
        if (first) out.shutdown();
    }

    /** shutdown + exit, then the session is removed on the server. */
    public void stop() {
        if (closed) return;
        request("shutdown", JSONObject.NULL, null);
        notify("exit", JSONObject.NULL);
        exec(new Runnable() {
            @Override
            public void run() {
                try {
                    Http.request("DELETE", server + "/v1/lsp/sessions/" + session, auth(), null, null);
                } catch (IOException ignored) {
                }
            }
        });
        closed = true;
        Http.Stream s = events;
        if (s != null) s.close();
        out.shutdown();
    }
}
