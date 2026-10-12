package com.disgusty.droid_codology.work;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.disgusty.droid_codology.ext.Extension;
import com.disgusty.droid_codology.lang.Language;
import com.disgusty.droid_codology.lang.Languages;
import com.disgusty.droid_codology.text.TextBuffer;
import com.disgusty.droid_codology.ui.Icons;
import com.disgusty.droid_codology.ui.Skin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/**
 * Runs the code of installed extensions ("main"/"browser" entry points) in a headless WebView,
 * where assets/jshost/host.js implements a subset of the VS Code API on top of this bridge.
 * Declarative contributions (themes, grammars, snippets) don't need it; it only starts when an
 * enabled extension has code.
 */
final class ExtensionHost {
    static final String LOG_CHANNEL = "Extension Host";

    private final Workbench wb;
    private WebView web;
    private boolean ready;
    private final ArrayDeque<String> outbox = new ArrayDeque<String>();
    private boolean pumpScheduled;

    private final IdentityHashMap<Document, Integer> ids = new IdentityHashMap<Document, Integer>();
    private final HashMap<Integer, Document> byId = new HashMap<Integer, Document>();
    private final HashMap<Document, TextBuffer.Listener> changeListeners = new HashMap<Document, TextBuffer.Listener>();
    private final HashSet<Document> changePending = new HashSet<Document>();
    private final HashMap<Integer, Integer> versions = new HashMap<Integer, Integer>();
    private int nextId = 1;

    /** Extensions with code, by id. */
    private final LinkedHashMap<String, Extension> codeExts = new LinkedHashMap<String, Extension>();
    private final HashSet<String> activationRequested = new HashSet<String>();
    /** Commands registered from JS without a contribution (callable from status items etc.). */
    private final HashSet<String> jsCommands = new HashSet<String>();
    private final HashMap<String, Object> configDefaults = new HashMap<String, Object>();
    private final LinkedHashMap<String, JSONObject> statusItems = new LinkedHashMap<String, JSONObject>();
    private final LinkedHashMap<String, StringBuilder> outputs = new LinkedHashMap<String, StringBuilder>();
    private final HashMap<String, Document> outputDocs = new HashMap<String, Document>();
    private final HashMap<Integer, Reply> javaPending = new HashMap<Integer, Reply>();
    private int nextJavaReq = 1;
    private Document lastActive;

    interface Reply {
        void done(boolean ok, Object value);
    }

    ExtensionHost(Workbench wb) {
        this.wb = wb;
    }

    // ------------------------------------------------------------------ lifecycle

    boolean running() {
        return web != null || node != null;
    }

    /** The host on Node.js in Alpine Linux (Settings → Terminal), in alpine mode when chosen and installed. */
    private NodeHost node;

    boolean nodeMode() {
        return wb.alpineMode() && wb.prefs.b(Prefs.NODE_EXT_HOST, false) && NodeHost.available(wb.alpine());
    }

    /** The running host is the Node.js one. */
    boolean onNode() {
        return node != null;
    }

    /** Registers contributed commands, keybindings and settings without starting any code. */
    /** Commands and key bindings added for extensions, taken back when the set of extensions changes. */
    private final java.util.HashSet<String> contributedCommands = new java.util.HashSet<String>();
    private final ArrayList<Object[]> contributedBindings = new ArrayList<Object[]>();

    void registerOnly() {
        // Commands of extensions that are now disabled or gone must not stay in the palette:
        // running one would bounce between Java and JS forever.
        for (String id : contributedCommands) wb.commands.remove(id);
        contributedCommands.clear();
        for (Object[] b : contributedBindings) wb.commands.unbind((String) b[0], (Commands.Command) b[1]);
        contributedBindings.clear();
        codeExts.clear();
        configDefaults.clear();
        for (Extension e : wb.extensions.installed()) {
            if (!e.enabled) continue;
            collectConfigDefaults(e);
            // Desktop-only code (Claude Code and the like) would only fail: it needs Node.js and
            // desktop programs. Its page says why; themes, languages and snippets still work.
            // (On Node.js in Alpine they get their chance: much of what they need is there.)
            if (e.entryPoint() == null || e.desktopOnly() && !nodeMode()) continue;
            codeExts.put(e.id, e);
            registerContributions(e);
        }
    }

    /** Registers contributions and starts the host if any enabled extension has code. */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    void start() {
        if (running()) return;
        registerOnly();
        if (codeExts.isEmpty()) return;
        if (nodeMode()) {
            startNode();
            return;
        }
        web = new WebView(wb.activity);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "DC");
        web.setWebViewClient(new android.webkit.WebViewClient() {
            /** Extension code crashed the renderer (or the system killed it): restart the host, keep the app. */
            @Override
            @android.annotation.TargetApi(26)
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (view != web) {
                    PreviewClient.dropDeadWebView(view);
                    return true;
                }
                log("", "error", "the extension host's renderer stopped; restarting it");
                stop();
                wb.ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!running()) start();
                    }
                }, 1000);
                return true;
            }
        });
        web.loadUrl("file:///android_asset/jshost/host.html");
    }

    /** The same host on Node.js inside Alpine Linux: messages over pipes (NodeHost), same handlers. */
    private void startNode() {
        final Bridge b = new Bridge();
        node = new NodeHost(wb, new NodeHost.Bridge() {
            @Override
            public String call(String method, String argsJson) {
                return b.call(method, argsJson);
            }

            @Override
            public void callAsync(int reqId, String method, String argsJson) {
                b.callAsync(reqId, method, argsJson);
            }

            @Override
            public void log(final String level, final String text) {
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        ExtensionHost.this.log("", level, text);
                    }
                });
            }

            @Override
            public void exited(final String reason) {
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (node == null) return;
                        ExtensionHost.this.log("", "error", reason);
                        stop();
                        wb.showMessage(f("The Node.js extension host stopped: {0}", reason));
                    }
                });
            }
        });
        try {
            node.start();
        } catch (Exception e) {
            node = null;
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            log("", "error", "Node.js host: " + msg);
            wb.showMessage(f("Couldn't start Node.js for extensions: {0}", msg));
        }
    }

    void stop() {
        if (!running()) return;
        for (String id : codeExts.keySet()) send("deactivate", id);
        final WebView w = web;
        web = null;
        NodeHost n = node;
        node = null;
        if (n != null) n.stop();
        ready = false;
        outbox.clear();
        activationRequested.clear();
        jsCommands.clear();
        statusItems.clear();
        for (Map.Entry<Document, TextBuffer.Listener> e : changeListeners.entrySet()) e.getKey().text.removeListener(e.getValue());
        changeListeners.clear();
        ids.clear();
        byId.clear();
        for (Reply r : javaPending.values()) r.done(false, "extension host stopped");
        javaPending.clear();
        closeAllWebviews();
        try {
            if (w != null) w.destroy();
        } catch (RuntimeException ignored) {
            // already gone with its renderer
        }
        wb.updateStatus();
    }

    void restart() {
        stop();
        start();
    }

    private void onReady() {
        if (!running()) return;
        ready = true;
        JSONArray list = new JSONArray();
        for (Extension e : codeExts.values()) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", e.id);
                o.put("path", e.dir.getAbsolutePath());
                o.put("main", e.entryPoint());
                o.put("manifest", e.manifest);
            } catch (JSONException ignored) {
            }
            list.put(o);
        }
        // Before anything queued while the page loaded (e.g. a command run at startup).
        synchronized (outbox) {
            outbox.addFirst(new JSONArray().put("init").put(list).toString());
        }
        schedulePump();
        for (Extension e : codeExts.values()) {
            for (String ev : activationEvents(e)) {
                if (ev.equals("*") || ev.equals("onStartupFinished") || ev.startsWith("workspaceContains:") && wb.workspace != null) {
                    activate(e.id);
                    break;
                }
            }
        }
        Document d = wb.activeDocument();
        if (d != null) onActiveChanged(d);
        for (Document doc : wb.documents()) activateForLanguage(doc.language.id);
    }

    private List<String> activationEvents(Extension e) {
        ArrayList<String> out = new ArrayList<String>();
        JSONArray a = e.manifest.optJSONArray("activationEvents");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        // Since VS Code 1.74 contributions imply their activation events.
        JSONObject c = e.contributes();
        JSONArray langs = c.optJSONArray("languages");
        if (langs != null) for (int i = 0; i < langs.length(); i++) {
            JSONObject l = langs.optJSONObject(i);
            if (l != null && l.has("id")) out.add("onLanguage:" + l.optString("id"));
        }
        return out;
    }

    void activate(String extId) {
        if (!ready || !activationRequested.add(extId)) return;
        send("activate", extId);
    }

    private void activateForLanguage(String lang) {
        if (!ready) return;
        for (Extension e : codeExts.values()) {
            if (activationRequested.contains(e.id)) continue;
            if (activationEvents(e).contains("onLanguage:" + lang)) activate(e.id);
        }
    }

    private void registerContributions(final Extension e) {
        JSONObject c = e.contributes();
        JSONArray cmds = c.optJSONArray("commands");
        if (cmds != null) {
            for (int i = 0; i < cmds.length(); i++) {
                JSONObject o = cmds.optJSONObject(i);
                if (o == null) continue;
                final String id = o.optString("command", "");
                if (id.isEmpty()) continue;
                String title = o.optString("title", id);
                if (title.startsWith("%")) title = id;
                String category = o.optString("category", null);
                if (category != null && category.startsWith("%")) category = null;
                // A built-in command with the same id stays (the extension's own code still gets it via JS).
                if (wb.commands.get(id) != null && !contributedCommands.contains(id)) continue;
                contributedCommands.add(id);
                wb.commands.add(id, category, title, new Runnable() {
                    @Override
                    public void run() {
                        if (!running()) start();
                        activationRequested.add(e.id);
                        send("run", 0, id, new JSONArray());
                    }
                });
            }
        }
        JSONArray keys = c.optJSONArray("keybindings");
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                JSONObject o = keys.optJSONObject(i);
                if (o == null) continue;
                Commands.Command cmd = wb.commands.get(o.optString("command"));
                String key = o.optString("key", "");
                if (cmd != null && !key.isEmpty() && !cmd.keys.contains(key.toLowerCase(Locale.ROOT))) {
                    wb.commands.bind(key, cmd);
                    contributedBindings.add(new Object[]{key, cmd});
                }
            }
        }
    }

    private void collectConfigDefaults(Extension e) {
        Object conf = e.contributes().opt("configuration");
        ArrayList<JSONObject> blocks = new ArrayList<JSONObject>();
        if (conf instanceof JSONObject) blocks.add((JSONObject) conf);
        else if (conf instanceof JSONArray) {
            JSONArray a = (JSONArray) conf;
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) blocks.add(a.optJSONObject(i));
        }
        for (JSONObject b : blocks) {
            JSONObject props = b.optJSONObject("properties");
            if (props == null) continue;
            Iterator<String> it = props.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject p = props.optJSONObject(k);
                if (p != null && p.has("default")) configDefaults.put(k, p.opt("default"));
            }
        }
    }

    // ------------------------------------------------------------------ editor events (UI thread)

    void onActiveChanged(Document d) {
        if (!ready) return;
        lastActive = d;
        send("fire", "activeEditor", d == null ? JSONObject.NULL : docEvent(d));
        if (d != null) activateForLanguage(d.language.id);
    }

    void onSaved(Document d) {
        if (ready && ids.containsKey(d)) send("fire", "save", docEvent(d));
    }

    void onClosed(Document d) {
        Integer id = ids.remove(d);
        if (id == null) return;
        byId.remove(id);
        TextBuffer.Listener l = changeListeners.remove(d);
        if (l != null) d.text.removeListener(l);
        if (ready) send("fire", "close", docEvent(id));
    }

    void onSelectionChanged(Document d) {
        if (ready && ids.containsKey(d)) send("fire", "selection", docEvent(d));
    }

    private JSONObject docEvent(Document d) {
        return docEvent(idOf(d));
    }

    private static JSONObject docEvent(int id) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private int idOf(final Document d) {
        Integer id = ids.get(d);
        if (id != null) return id;
        final int nid = nextId++;
        ids.put(d, nid);
        byId.put(nid, d);
        versions.put(nid, 1);
        TextBuffer.Listener l = new TextBuffer.Listener() {
            @Override
            public void onReplaced(int start, int removedLen, int insertedLen, int firstLine, int removedLines, int insertedLines) {
                Integer v = versions.get(nid);
                versions.put(nid, v == null ? 1 : v + 1);
                if (changePending.add(d)) {
                    wb.ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            changePending.remove(d);
                            if (ready && ids.containsKey(d)) send("fire", "change", docEvent(nid));
                        }
                    }, 250);
                }
            }
        };
        d.text.addListener(l);
        changeListeners.put(d, l);
        return nid;
    }

    private Document doc(JSONArray a, int i) {
        Document d = byId.get(a.optInt(i, -1));
        if (d == null) throw new IllegalArgumentException("document is closed");
        return d;
    }

    // ------------------------------------------------------------------ status bar items

    /** Appends extension status bar items (called from Workbench.updateStatus). */
    void addStatusItems(LinearLayout left, LinearLayout right) {
        if (statusItems.isEmpty()) return;
        ArrayList<JSONObject> items = new ArrayList<JSONObject>();
        for (JSONObject o : statusItems.values()) if (o.optBoolean("visible")) items.add(o);
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(b.optDouble("priority", 0), a.optDouble("priority", 0));
            }
        });
        int rightPos = 0;
        for (final JSONObject o : items) {
            String text = o.optString("text", "");
            String icon = null;
            java.util.regex.Matcher m = Pattern.compile("\\$\\(([a-z0-9-]+)(~[a-z]+)?\\)").matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                if (icon == null && Icons.has(Icons.SET_CODICON, m.group(1))) icon = m.group(1);
                m.appendReplacement(sb, "");
            }
            m.appendTail(sb);
            final String cmd = o.isNull("command") ? null : o.optString("command", null);
            View v = wb.skin.statusItem(sb.toString().trim(), icon, cmd == null ? null : new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    JSONArray args = o.optJSONArray("args");
                    runCommand(cmd, args == null ? new JSONArray() : args);
                }
            });
            if (o.optInt("alignment", 1) == 2) right.addView(v, rightPos++);
            else left.addView(v);
        }
    }

    /** Runs a command id from anywhere: built-in first, then extension commands. */
    void runCommand(String id, JSONArray args) {
        if (wb.commands.get(id) != null) {
            if (args.length() == 0 || !jsCommands.contains(id)) {
                wb.commands.run(id);
                return;
            }
        }
        if (ready) send("run", 0, id, args);
    }

    // ------------------------------------------------------------------ requests to JS

    /** Asks extension formatters to format {@code d}; {@code fallback} runs if none handles it. */
    boolean format(Document d, final Runnable fallback) {
        if (!ready || activationRequested.isEmpty()) return false;
        int req = nextJavaReq++;
        javaPending.put(req, new Reply() {
            @Override
            public void done(boolean ok, Object value) {
                if (!ok && fallback != null) fallback.run();
            }
        });
        JSONObject opts = new JSONObject();
        try {
            opts.put("tabSize", d.engine.tabSize);
            opts.put("insertSpaces", d.engine.insertSpaces);
        } catch (JSONException ignored) {
        }
        send("format", req, idOf(d), opts);
        return true;
    }

    /** Children of an extension tree view item (parentHandle null for the roots); null result = unavailable. */
    void treeChildren(String viewId, String parentHandle, final LspManager.Result<Object> cb) {
        if (!running()) start();
        if (!running()) {
            cb.done(I18n.t("The extension that provides this view has no code to run."));
            return;
        }
        int req = nextJavaReq++;
        javaPending.put(req, new Reply() {
            @Override
            public void done(boolean ok, Object value) {
                cb.done(ok ? value : String.valueOf(value));
            }
        });
        send("treeChildren", req, viewId, parentHandle == null ? JSONObject.NULL : parentHandle);
    }

    void treeClick(String handle) {
        send("treeClick", handle);
    }

    // ------------------------------------------------------------------ webviews

    private final java.util.LinkedHashMap<String, ExtWebview> webviews = new java.util.LinkedHashMap<String, ExtWebview>();

    /** Asks the extension behind side bar view {@code viewId} to fill its webview (answer: webview.create or webview.unavailable). */
    void resolveWebviewView(String viewId) {
        if (!running()) start();
        if (!running()) {
            wb.onWebviewViewUnavailable(viewId, I18n.t("The extension that provides this view has no code to run."));
            return;
        }
        send("resolveWebviewView", viewId);
    }

    void webviewMessage(String handle, String json) {
        send("webviewMessage", handle, json);
    }

    /** The user closed a panel or a view went away. */
    void webviewClosed(ExtWebview w) {
        if (webviews.remove(w.handle) == null) return;
        w.destroy();
        send("webviewDisposed", w.handle);
    }

    /** Drops every webview (host restart). */
    private void closeAllWebviews() {
        for (ExtWebview w : new ArrayList<ExtWebview>(webviews.values())) {
            webviews.remove(w.handle);
            if ("panel".equals(w.kind)) wb.closeWebviewPanel(w, false);
            else wb.onWebviewViewUnavailable(w.viewId, null);
            w.destroy();
        }
    }

    private Object handleWebview(String m, JSONArray a) throws JSONException {
        if (m.equals("webview.viewRegistered")) {
            wb.onWebviewViewRegistered(a.getString(0));
            return null;
        }
        if (m.equals("webview.unavailable")) {
            wb.onWebviewViewUnavailable(a.getString(0), a.optString(1));
            return null;
        }
        if (m.equals("webview.create")) {
            ExtWebview w = new ExtWebview(wb, this, a.getString(0), a.getString(1), a.getJSONObject(2));
            webviews.put(w.handle, w);
            if ("panel".equals(w.kind)) wb.showWebviewPanel(w);
            else wb.onWebviewViewCreated(w);
            return null;
        }
        ExtWebview w = webviews.get(a.getString(0));
        if (w == null) return null;
        if (m.equals("webview.html")) w.setHtml(a.getString(1));
        else if (m.equals("webview.options")) w.setOptions(a.optJSONObject(1));
        else if (m.equals("webview.post")) w.post(a.getString(1));
        else if (m.equals("webview.title")) {
            w.title = a.getString(1);
            if ("panel".equals(w.kind)) wb.updateWebviewPanelTitle(w);
        } else if (m.equals("webview.reveal")) {
            if ("panel".equals(w.kind)) wb.showWebviewPanel(w);
        } else if (m.equals("webview.dispose")) {
            webviews.remove(w.handle);
            if ("panel".equals(w.kind)) wb.closeWebviewPanel(w, false);
            else wb.onWebviewViewUnavailable(w.viewId, null);
            w.destroy();
        }
        return null;
    }

    /** Items from extensions' completion providers (null when no extension is running). */
    void complete(final Document d, int offset, String trigger, final LspManager.Result<List<Completion.Item>> cb) {
        if (!ready || activationRequested.isEmpty()) {
            cb.done(null);
            return;
        }
        int req = nextJavaReq++;
        javaPending.put(req, new Reply() {
            @Override
            public void done(boolean ok, Object value) {
                ArrayList<Completion.Item> out = new ArrayList<Completion.Item>();
                if (ok && value instanceof JSONArray) {
                    JSONArray a = (JSONArray) value;
                    for (int i = 0; i < a.length() && out.size() < 300; i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        boolean snippet = o.optBoolean("snippet");
                        String label = o.optString("label"), insert = o.optString("insert", label);
                        Completion.Item it = new Completion.Item(label, snippet ? label : insert, o.optString("detail", null),
                                LspManager.kindOf(o.optInt("kind", -1) + 1), snippet ? insert : null);
                        it.replaceStart = o.optInt("start", -1);
                        it.filterText = o.optString("filter", label);
                        out.add(it);
                    }
                }
                cb.done(out);
            }
        });
        send("complete", req, idOf(d), offset, trigger == null ? JSONObject.NULL : trigger);
    }

    // ------------------------------------------------------------------ Java -> JS

    /** Queues a call of {@code __host[fn](args...)}; host.js pulls the queue (no URL encoding issues). */
    private void send(String fn, Object... args) {
        if (!running()) return;
        JSONArray a = new JSONArray();
        a.put(fn);
        for (Object o : args) a.put(o == null ? JSONObject.NULL : o);
        synchronized (outbox) {
            outbox.add(a.toString());
        }
        if (ready) schedulePump();
    }

    private void schedulePump() {
        if (pumpScheduled) return;
        pumpScheduled = true;
        wb.ui.post(new Runnable() {
            @Override
            public void run() {
                pumpScheduled = false;
                if (!running() || !ready) return;
                if (node != null) {
                    // Node.js reads its queue from stdin
                    for (;;) {
                        String m;
                        synchronized (outbox) {
                            m = outbox.poll();
                        }
                        if (m == null) break;
                        node.post(m);
                    }
                    return;
                }
                if (Build.VERSION.SDK_INT >= 19) com.disgusty.droid_codology.compat.Api19.evaluate(web, "__host.pull()");
                else web.loadUrl("javascript:__host.pull()");
            }
        });
    }

    // ------------------------------------------------------------------ bridge (JavaBridge thread)

    final class Bridge {
        @JavascriptInterface
        public String take() {
            synchronized (outbox) {
                String s = outbox.poll();
                return s == null ? "" : s;
            }
        }

        @JavascriptInterface
        public String call(final String method, String argsJson) {
            JSONObject r = new JSONObject();
            try {
                final JSONArray args = new JSONArray(argsJson);
                Object v;
                if (method.startsWith("fs.") || method.equals("log") || method.startsWith("state.")) {
                    v = handleAnyThread(method, args);
                } else {
                    v = onUi(new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                            return handleUi(method, args);
                        }
                    });
                }
                r.put("ok", v == null ? JSONObject.NULL : v);
            } catch (Throwable e) {
                try {
                    r.put("err", e.getMessage() == null ? e.toString() : e.getMessage());
                } catch (JSONException ignored) {
                }
            }
            return r.toString();
        }

        @JavascriptInterface
        public void callAsync(final int reqId, final String method, final String argsJson) {
            wb.ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!running()) return;
                    Reply reply = new Reply() {
                        boolean sent;

                        @Override
                        public void done(boolean ok, Object value) {
                            if (sent) return;
                            sent = true;
                            send("reply", reqId, ok, value == null ? JSONObject.NULL : value);
                        }
                    };
                    try {
                        handleAsync(method, new JSONArray(argsJson), reply);
                    } catch (Throwable e) {
                        reply.done(false, e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                }
            });
        }
    }

    private Object onUi(final Callable<Object> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final Object[] result = new Object[1];
        final Exception[] error = new Exception[1];
        final CountDownLatch latch = new CountDownLatch(1);
        wb.ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = c.call();
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("editor busy");
        if (error[0] != null) throw error[0];
        return result[0];
    }

    // ------------------------------------------------------------------ handlers

    private Object handleAnyThread(String m, JSONArray a) throws Exception {
        if (m.equals("log")) {
            log(a.optString(0), a.optString(1), a.optString(2));
            return null;
        }
        if (m.equals("fs.resolve")) return resolveModule(a.optString(0), a.optString(1), a.optString(2));
        if (m.equals("fs.read")) {
            File f = new File(a.getString(0));
            if (!f.isFile()) return null;
            return Base64.encodeToString(readBytes(f), Base64.NO_WRAP);
        }
        if (m.equals("fs.write")) {
            File f = new File(a.getString(0));
            FileOutputStream os = new FileOutputStream(f);
            try {
                os.write(Base64.decode(a.getString(1), Base64.DEFAULT));
            } finally {
                os.close();
            }
            return null;
        }
        if (m.equals("fs.stat")) {
            File f = new File(a.getString(0));
            if (!f.exists()) return null;
            JSONObject o = new JSONObject();
            o.put("type", f.isDirectory() ? 2 : 1);
            o.put("size", f.length());
            o.put("mtime", f.lastModified());
            o.put("ctime", f.lastModified());
            return o;
        }
        if (m.equals("fs.list")) {
            File[] fs = new File(a.getString(0)).listFiles();
            if (fs == null) throw new IOException("not a directory: " + a.getString(0));
            JSONArray out = new JSONArray();
            for (File f : fs) out.put(new JSONArray().put(f.getName()).put(f.isDirectory() ? 2 : 1));
            return out;
        }
        if (m.equals("fs.mkdir")) {
            File f = new File(a.getString(0));
            if (!f.isDirectory() && !f.mkdirs()) throw new IOException("cannot create " + f);
            return null;
        }
        if (m.equals("fs.delete")) {
            deleteFile(new File(a.getString(0)), a.optBoolean(1));
            return null;
        }
        if (m.equals("fs.rename")) {
            if (!new File(a.getString(0)).renameTo(new File(a.getString(1)))) throw new IOException("rename failed");
            return null;
        }
        if (m.equals("fs.copy")) {
            File dst = new File(a.getString(1));
            FileOutputStream os = new FileOutputStream(dst);
            try {
                os.write(readBytes(new File(a.getString(0))));
            } finally {
                os.close();
            }
            return null;
        }
        if (m.equals("fs.find")) return findFiles(a.isNull(0) ? null : a.optString(0), a.getString(1), a.isNull(2) ? null : a.optString(2), a.optInt(3, 10000));
        if (m.equals("state.dir")) {
            File d = new File(new File(wb.activity.getFilesDir(), "extstate"), a.getString(0));
            if (!d.isDirectory()) d.mkdirs();
            return d.getAbsolutePath();
        }
        if (m.equals("state.load")) {
            File f = stateFile(a.getString(0), a.getString(1));
            if (!f.isFile()) return new JSONObject();
            return new JSONObject(new String(readBytes(f), "UTF-8"));
        }
        if (m.equals("state.save")) {
            File f = stateFile(a.getString(0), a.getString(1));
            FileOutputStream os = new FileOutputStream(f);
            try {
                os.write(a.getJSONObject(2).toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
            return null;
        }
        throw new IllegalArgumentException("unknown method " + m);
    }

    private File stateFile(String ext, String scope) {
        File d = new File(new File(wb.activity.getFilesDir(), "extstate"), ext.replaceAll("[^\\w.-]", "_"));
        if (!d.isDirectory()) d.mkdirs();
        String key = scope;
        if (scope.equals("workspace")) key = "ws-" + (wb.workspace == null ? "none" : Integer.toHexString(wb.workspace.root.getAbsolutePath().hashCode()));
        return new File(d, key + ".json");
    }

    private Object handleUi(String m, JSONArray a) throws Exception {
        if (m.equals("ready")) {
            wb.ui.post(new Runnable() {
                @Override
                public void run() {
                    onReady();
                }
            });
            return null;
        }
        if (m.equals("ext.activated")) {
            String id = a.optString(0);
            if (a.optBoolean(1)) {
                log(id, "info", "activated");
            } else {
                String err = a.optString(2);
                log(id, "error", "activation failed: " + err);
                Extension e = codeExts.get(id);
                String first = err.contains("\n") ? err.substring(0, err.indexOf('\n')) : err;
                if (err.contains("(a Node.js module, not available in droid_codology)")) {
                    // Not a bug to report: the extension is written for desktop VS Code only.
                    wb.showMessage(f("'{0}' needs Node.js (desktop VS Code) and can't run on Android.",
                            e != null ? e.displayName : id));
                } else {
                    wb.showMessage(f("Activating extension '{0}' failed: {1}", e != null ? e.displayName : id, first));
                }
            }
            return null;
        }
        if (m.equals("doc.active")) {
            Document d = wb.activeDocument();
            return d == null ? null : idOf(d);
        }
        if (m.equals("doc.list")) {
            JSONArray out = new JSONArray();
            for (Document d : wb.documents()) out.put(idOf(d));
            return out;
        }
        if (m.equals("doc.info")) {
            int id = a.optInt(0, -1);
            Document d = byId.get(id);
            JSONObject o = new JSONObject();
            Integer v = versions.get(id);
            o.put("version", v == null ? 1 : v);
            if (d == null) {
                o.put("closed", true);
                o.put("name", "closed");
                o.put("languageId", "plaintext");
                o.put("eol", 1);
                return o;
            }
            o.put("closed", false);
            o.put("path", d.file != null ? d.file.getAbsolutePath() : JSONObject.NULL);
            o.put("name", d.name);
            o.put("languageId", d.language.id);
            o.put("dirty", d.isDirty());
            o.put("untitled", d.isUntitled());
            o.put("eol", d.eol.equals(com.disgusty.droid_codology.io.TextFiles.CRLF) ? 2 : 1);
            o.put("tabSize", d.engine.tabSize);
            o.put("insertSpaces", d.engine.insertSpaces);
            o.put("encoding", d.charset.toLowerCase(Locale.ROOT));
            return o;
        }
        if (m.equals("doc.text")) return doc(a, 0).text.toString();
        if (m.equals("doc.edit")) {
            Document d = doc(a, 0);
            if (d.readOnly) return false;
            JSONArray edits = a.getJSONArray(1);
            ArrayList<int[]> order = new ArrayList<int[]>();
            for (int i = 0; i < edits.length(); i++) order.add(new int[]{edits.getJSONArray(i).getInt(0), edits.getJSONArray(i).getInt(1), i});
            Collections.sort(order, new Comparator<int[]>() {
                @Override
                public int compare(int[] x, int[] y) {
                    if (x[0] != y[0]) return y[0] < x[0] ? -1 : 1;
                    if (x[1] != y[1]) return y[1] < x[1] ? -1 : 1;
                    // Same spot: applied back to front, the later edit goes first so they end up in array order.
                    return y[2] - x[2];
                }
            });
            for (int i = 1; i < order.size(); i++) if (order.get(i)[1] > order.get(i - 1)[0]) throw new IllegalArgumentException("overlapping edits");
            int len = d.text.length();
            d.engine.begin(com.disgusty.droid_codology.text.UndoStack.KIND_OTHER);
            try {
                for (int[] e : order) {
                    int s = Math.max(0, Math.min(e[0], len)), en = Math.max(s, Math.min(e[1], len));
                    d.engine.replace(s, en, edits.getJSONArray(e[2]).getString(2).replace("\r\n", "\n").replace('\r', '\n'));
                }
            } finally {
                d.engine.end();
            }
            int eol = a.optInt(2, 0);
            if (eol == 1 || eol == 2) {
                String want = eol == 2 ? com.disgusty.droid_codology.io.TextFiles.CRLF : com.disgusty.droid_codology.io.TextFiles.LF;
                if (!want.equals(d.eol)) {
                    d.eol = want;
                    d.metaDirty = true;
                }
            }
            if (d == wb.activeDocument()) {
                wb.editor.invalidate();
                wb.updateStatus();
            }
            wb.refreshTabs();
            return true;
        }
        if (m.equals("doc.selections")) {
            Document d = doc(a, 0);
            JSONArray out = new JSONArray();
            com.disgusty.droid_codology.text.Selections s = d.engine.sel;
            int p = s.primary();
            out.put(new JSONArray().put(s.anchor(p)).put(s.head(p)));
            for (int i = 0; i < s.count(); i++) if (i != p) out.put(new JSONArray().put(s.anchor(i)).put(s.head(i)));
            return out;
        }
        if (m.equals("doc.select")) {
            Document d = doc(a, 0);
            JSONArray sels = a.getJSONArray(1);
            if (sels.length() == 0) return null;
            int len = d.text.length();
            d.engine.sel.set(clamp(sels.getJSONArray(0).getInt(0), len), clamp(sels.getJSONArray(0).getInt(1), len));
            for (int i = 1; i < sels.length(); i++) d.engine.sel.add(clamp(sels.getJSONArray(i).getInt(0), len), clamp(sels.getJSONArray(i).getInt(1), len));
            d.engine.selectionChangedExternally();
            if (d == wb.activeDocument()) wb.editor.ensureCaretVisible();
            return null;
        }
        if (m.equals("doc.options")) {
            Document d = doc(a, 0);
            JSONObject o = a.optJSONObject(1);
            if (o != null) {
                if (o.has("tabSize")) d.engine.tabSize = Math.max(1, Math.min(16, o.optInt("tabSize", d.engine.tabSize)));
                if (o.has("insertSpaces")) d.engine.insertSpaces = o.optBoolean("insertSpaces");
                if (d == wb.activeDocument()) {
                    wb.editor.setTabSize(d.engine.tabSize);
                    wb.updateStatus();
                }
            }
            return null;
        }
        if (m.equals("doc.snippet")) {
            Document d = doc(a, 0);
            JSONArray targets = a.getJSONArray(1);
            if (targets.length() == 0 || d.readOnly) return false;
            int len = d.text.length();
            int s = clamp(targets.getJSONArray(0).getInt(0), len), e = clamp(targets.getJSONArray(0).getInt(1), len);
            d.engine.insertSnippet(s, Math.max(s, e), a.getString(2), wb.snippetVariables(d));
            if (d == wb.activeDocument()) {
                wb.editor.ensureCaretVisible();
                wb.editor.invalidate();
            }
            return true;
        }
        if (m.equals("doc.reveal")) {
            Document d = doc(a, 0);
            if (d == wb.activeDocument()) wb.editor.revealLine(d.text.lineOfOffset(clamp(a.getInt(1), d.text.length())));
            return null;
        }
        if (m.equals("doc.show")) {
            Document d = doc(a, 0);
            if (d != wb.activeDocument()) wb.activate(d);
            return null;
        }
        if (m.equals("doc.open")) {
            String path = a.getString(0);
            for (Document d : wb.documents()) {
                if (d.file != null && d.file.getAbsolutePath().equals(path)) return idOf(d);
                if (d.file == null && d.name.equals(path)) return idOf(d);
            }
            File f = new File(path);
            if (!f.isFile()) throw new IOException("file not found: " + path);
            wb.openFile(f, false, -1, 0, 0);
            for (Document d : wb.documents()) if (d.file != null && d.file.getAbsolutePath().equals(f.getAbsolutePath())) return idOf(d);
            throw new IOException("file is too large to open synchronously: " + path);
        }
        if (m.equals("doc.new")) {
            wb.newUntitled(a.optString(0, ""));
            Document d = wb.activeDocument();
            Language l = Languages.byId(a.optString(1, ""));
            if (l != null && d != null) d.setLanguage(l);
            wb.updateStatus();
            return d == null ? null : idOf(d);
        }
        if (m.equals("doc.setLanguage")) {
            Document d = doc(a, 0);
            Language l = Languages.byId(a.getString(1));
            if (l == null) throw new IllegalArgumentException("unknown language " + a.getString(1));
            d.setLanguage(l);
            wb.editor.invalidate();
            wb.updateStatus();
            return null;
        }
        if (m.equals("tree.changed")) {
            wb.onExtensionTreeChanged(a.getString(0));
            return null;
        }
        if (m.startsWith("webview.")) return handleWebview(m, a);
        if (m.equals("diag.set")) {
            // [collection, uri, [[line, col, endLine, endCol, severity(0-3), message, source]...]]
            String key = "ext:" + a.getString(0);
            String uri = a.getString(1);
            String path = uri.startsWith("file://") ? Uri.parse(uri).getPath() : uri;
            JSONArray list = a.optJSONArray(2);
            for (Document d : wb.documents()) {
                boolean match = d.file != null ? d.file.getAbsolutePath().equals(path) : d.name.equals(path) || uri.endsWith("/" + d.name);
                if (!match) continue;
                ArrayList<com.disgusty.droid_codology.editor.Diagnostic> out = new ArrayList<com.disgusty.droid_codology.editor.Diagnostic>();
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONArray g = list.getJSONArray(i);
                    out.add(new com.disgusty.droid_codology.editor.Diagnostic(g.getInt(0), g.getInt(1), g.getInt(2), g.getInt(3),
                            g.getInt(4) + 1, g.getString(5), g.isNull(6) ? null : g.getString(6)));
                }
                if (out.isEmpty()) d.diagnostics.remove(key);
                else d.diagnostics.put(key, out);
                wb.onDiagnosticsChanged(d);
            }
            return null;
        }
        if (m.equals("status.set")) {
            statusItems.put(a.getString(0), a.getJSONObject(1));
            wb.updateStatus();
            return null;
        }
        if (m.equals("status.remove")) {
            if (statusItems.remove(a.getString(0)) != null) wb.updateStatus();
            return null;
        }
        if (m.equals("config.get")) return configGet(a.getString(0));
        if (m.equals("config.inspect")) {
            String k = a.getString(0);
            JSONObject o = new JSONObject();
            o.put("defaultValue", configDefaults.containsKey(k) ? configDefaults.get(k) : JSONObject.NULL);
            String user = configPrefs().getString(k, null);
            o.put("globalValue", user == null ? JSONObject.NULL : new JSONArray("[" + user + "]").get(0));
            return o;
        }
        if (m.equals("config.update")) {
            String k = a.getString(0);
            Object v = a.get(1);
            SharedPreferences.Editor ed = configPrefs().edit();
            if (v == JSONObject.NULL) ed.remove(k);
            else ed.putString(k, v instanceof String ? JSONObject.quote((String) v) : String.valueOf(v));
            ed.apply();
            JSONObject ev = new JSONObject();
            ev.put("keys", new JSONArray().put(k));
            send("fire", "config", ev);
            return null;
        }
        if (m.equals("commands.register")) {
            jsCommands.add(a.getString(1));
            return null;
        }
        if (m.equals("commands.list")) {
            JSONArray out = new JSONArray();
            for (Commands.Command c : wb.commands.registered()) out.put(c.id);
            return out;
        }
        if (m.equals("clipboard.read")) {
            ClipboardManager cm = (ClipboardManager) wb.activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(wb.activity);
            return s == null ? "" : s.toString();
        }
        if (m.equals("clipboard.write")) {
            ClipboardManager cm = (ClipboardManager) wb.activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", a.getString(0)));
            return null;
        }
        if (m.equals("env.info")) {
            JSONObject o = new JSONObject();
            o.put("language", I18n.language());
            String aid = Settings.Secure.getString(wb.activity.getContentResolver(), Settings.Secure.ANDROID_ID);
            o.put("machineId", aid == null ? "unknown" : Integer.toHexString(aid.hashCode()) + Integer.toHexString(aid.length()));
            o.put("home", wb.activity.getFilesDir().getAbsolutePath());
            o.put("tmp", wb.activity.getCacheDir().getAbsolutePath());
            o.put("dark", wb.editor.colors().dark);
            return o;
        }
        if (m.equals("env.open")) {
            try {
                wb.activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(a.getString(0))));
            } catch (Exception e) {
                wb.showMessage(e.getMessage());
            }
            return null;
        }
        if (m.equals("output.append")) {
            appendOutput(a.getString(0), a.getString(1));
            return null;
        }
        if (m.equals("output.clear")) {
            outputs.remove(a.getString(0));
            return null;
        }
        if (m.equals("output.show")) {
            showOutput(a.getString(0));
            return null;
        }
        if (m.equals("lang.list")) {
            JSONArray out = new JSONArray();
            for (Language l : Languages.all()) out.put(l.id);
            return out;
        }
        if (m.equals("workspace.root")) return wb.workspace == null ? null : wb.workspace.root.getAbsolutePath();
        throw new IllegalArgumentException("unknown method " + m);
    }

    private void handleAsync(String m, JSONArray a, final Reply reply) throws Exception {
        if (m.equals("reply")) {
            Reply r = javaPending.remove(a.getInt(0));
            if (r != null) r.done(a.getBoolean(1), a.opt(2));
            return;
        }
        if (m.equals("msg.show")) {
            showMessageWithItems(a.optInt(0), a.getString(1), a.getJSONArray(2), a.optBoolean(3), a.optString(4, ""), reply);
            return;
        }
        if (m.equals("quickpick")) {
            JSONObject o = a.getJSONObject(0);
            JSONArray items = o.getJSONArray("items");
            final boolean[] picked = {false};
            ArrayList<QuickPick.Item> list = new ArrayList<QuickPick.Item>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                final int index = i;
                String detail = it.optString("detail", "");
                QuickPick.Item qi = new QuickPick.Item(it.optString("label"), detail.isEmpty() ? null : detail, new Runnable() {
                    @Override
                    public void run() {
                        picked[0] = true;
                        reply.done(true, index);
                    }
                });
                String desc = it.optString("description", "");
                if (!desc.isEmpty()) qi.description = desc;
                list.add(qi);
            }
            String header = o.optString("title", "");
            if (header.isEmpty()) header = o.optString("placeHolder", "");
            QuickPick qp = wb.quickPick();
            qp.showItems(header, list);
            qp.onDismiss = new Runnable() {
                @Override
                public void run() {
                    // Item actions run right after dismiss; decide once they had their chance.
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!picked[0]) reply.done(true, -1);
                        }
                    });
                }
            };
            return;
        }
        if (m.equals("input")) {
            showInput(a.getJSONObject(0), reply);
            return;
        }
        if (m.equals("doc.save")) {
            Document d = doc(a, 0);
            // Like TextDocument.save(): false when it can't be saved (untitled, read-only, changed on
            // disk...), without dialogs an extension didn't ask the user for.
            boolean saved = d != null && (!d.isDirty() || wb.save(d, null, true, false));
            reply.done(true, saved);
            return;
        }
        if (m.equals("commands.execute")) {
            String id = a.getString(0);
            JSONArray args = a.optJSONArray(1);
            if (id.equals("setContext")) {
                reply.done(true, null);
                return;
            }
            if (id.equals("vscode.open") && args != null && args.length() > 0) {
                Object u = args.get(0);
                String scheme = "file", whole;
                if (u instanceof JSONObject) {
                    JSONObject o = (JSONObject) u;
                    scheme = o.optString("scheme", "file");
                    // A serialized vscode.Uri: put the URL back together (not just its path).
                    whole = scheme + ":" + (o.optString("authority").isEmpty() && !scheme.startsWith("http") ? "" : "//" + o.optString("authority"))
                            + o.optString("path") + (o.optString("query").isEmpty() ? "" : "?" + o.optString("query"))
                            + (o.optString("fragment").isEmpty() ? "" : "#" + o.optString("fragment"));
                    if (scheme.equals("file")) whole = o.optString("path");
                } else {
                    whole = String.valueOf(u);
                    int colon = whole.indexOf(':');
                    if (colon > 1 && !whole.startsWith("/")) scheme = whole.substring(0, colon);
                }
                if (scheme.equals("file")) {
                    wb.openFile(new File(whole.startsWith("file:") ? Uri.parse(whole).getPath() : whole), false, -1, 0, 0);
                } else {
                    try {
                        wb.activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(whole)));
                    } catch (Exception e) {
                        reply.done(false, "nothing can open " + whole);
                        return;
                    }
                }
                reply.done(true, null);
                return;
            }
            String mapped = mapBuiltin(id);
            // Asked for by JS because JS doesn't have it: an extension command whose extension
            // isn't running. Running it here would send it back to JS, forever.
            if (wb.commands.get(mapped) == null || contributedCommands.contains(mapped)) {
                reply.done(false, "command '" + id + "' not found");
                return;
            }
            wb.commands.run(mapped);
            reply.done(true, null);
            return;
        }
        throw new IllegalArgumentException("unknown method " + m);
    }

    /** VS Code command ids that are named differently here. */
    private static String mapBuiltin(String id) {
        if (id.equals("workbench.action.files.save")) return "workbench.action.files.save";
        if (id.equals("editor.action.formatDocument")) return "editor.action.formatDocument";
        if (id.equals("cursorUndo") || id.equals("undo")) return "undo";
        if (id.equals("redo")) return "redo";
        return id;
    }

    private static int clamp(int v, int len) {
        return Math.max(0, Math.min(v, len));
    }

    // ------------------------------------------------------------------ messages & input

    private void showMessageWithItems(int severity, String msg, JSONArray titles, boolean modal, String detail, final Reply reply) throws JSONException {
        if (titles.length() == 0 && !modal) {
            wb.showMessage((severity == 2 ? "⚠ " : "") + msg);
            log("", severity == 2 ? "error" : severity == 1 ? "warn" : "info", msg);
            reply.done(true, -1);
            return;
        }
        if (titles.length() > 3) {
            ArrayList<QuickPick.Item> list = new ArrayList<QuickPick.Item>();
            final boolean[] picked = {false};
            for (int i = 0; i < titles.length(); i++) {
                final int index = i;
                list.add(new QuickPick.Item(titles.getString(i), null, new Runnable() {
                    @Override
                    public void run() {
                        picked[0] = true;
                        reply.done(true, index);
                    }
                }));
            }
            QuickPick qp = wb.quickPick();
            qp.showItems(msg, list);
            qp.onDismiss = new Runnable() {
                @Override
                public void run() {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!picked[0]) reply.done(true, -1);
                        }
                    });
                }
            };
            return;
        }
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = msg;
        if (!detail.isEmpty()) s.message = detail;
        // Buttons keep the extension's order next to each other; Back/outside tap cancels.
        if (titles.length() > 0) {
            s.positive = titles.getString(0);
            s.onPositive = replyRunnable(reply, 0);
        }
        if (titles.length() > 1) {
            s.negative = titles.getString(1);
            s.onNegative = replyRunnable(reply, 1);
        } else {
            s.negative = titles.length() == 0 ? t("OK") : t("Cancel");
            s.onNegative = replyRunnable(reply, -1);
        }
        if (titles.length() > 2) {
            s.neutral = titles.getString(2);
            s.onNeutral = replyRunnable(reply, 2);
        }
        Dialog d = wb.skin.dialog(s);
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                // Button handlers run before dismiss; this only answers when the dialog was cancelled.
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        reply.done(true, -1);
                    }
                });
            }
        });
        d.show();
    }

    private static Runnable replyRunnable(final Reply r, final int index) {
        return new Runnable() {
            @Override
            public void run() {
                r.done(true, index);
            }
        };
    }

    private void showInput(JSONObject o, final Reply reply) {
        final EditText input = wb.skin.input(o.optString("placeHolder", ""));
        input.setText(o.optString("value", ""));
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        if (o.optBoolean("password")) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Skin.DialogSpec s = new Skin.DialogSpec();
        String title = o.optString("title", "");
        String prompt = o.optString("prompt", "");
        s.title = title.isEmpty() ? prompt : title;
        if (!prompt.isEmpty() && !prompt.equals(s.title)) s.message = prompt;
        s.content = input;
        s.positive = t("OK");
        s.negative = t("Cancel");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                reply.done(true, input.getText().toString());
            }
        };
        s.onNegative = new Runnable() {
            @Override
            public void run() {
                reply.done(true, null);
            }
        };
        final Dialog d = wb.skin.dialog(s);
        input.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int actionId, android.view.KeyEvent event) {
                reply.done(true, input.getText().toString());
                d.dismiss();
                return true;
            }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        reply.done(true, null);
                    }
                });
            }
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    // ------------------------------------------------------------------ configuration

    private SharedPreferences configPrefs() {
        return wb.activity.getSharedPreferences("ext_config", Context.MODE_PRIVATE);
    }

    private Object configGet(String key) throws JSONException {
        String user = configPrefs().getString(key, null);
        if (user != null) return new JSONArray("[" + user + "]").get(0);
        if (configDefaults.containsKey(key)) return configDefaults.get(key);
        Document d = wb.activeDocument();
        if (key.equals("editor.tabSize")) return d != null ? d.engine.tabSize : 4;
        if (key.equals("editor.insertSpaces")) return d == null || d.engine.insertSpaces;
        if (key.equals("files.eol")) return d != null && d.eol.equals(com.disgusty.droid_codology.io.TextFiles.CRLF) ? "\r\n" : "\n";
        if (key.equals("editor.wordWrap")) return wb.prefs.b(Prefs.WORD_WRAP, false) ? "on" : "off";
        // A section: an object of everything below it.
        String prefix = key + ".";
        JSONObject obj = null;
        for (Map.Entry<String, Object> e : configDefaults.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                if (obj == null) obj = new JSONObject();
                putPath(obj, e.getKey().substring(prefix.length()), configGet(e.getKey()));
            }
        }
        for (Map.Entry<String, ?> e : configPrefs().getAll().entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                if (obj == null) obj = new JSONObject();
                putPath(obj, e.getKey().substring(prefix.length()), configGet(e.getKey()));
            }
        }
        return obj;
    }

    private static void putPath(JSONObject o, String path, Object v) throws JSONException {
        int dot = path.indexOf('.');
        if (dot < 0) {
            o.put(path, v == null ? JSONObject.NULL : v);
            return;
        }
        String head = path.substring(0, dot);
        JSONObject child = o.optJSONObject(head);
        if (child == null) {
            child = new JSONObject();
            o.put(head, child);
        }
        putPath(child, path.substring(dot + 1), v);
    }

    // ------------------------------------------------------------------ output channels

    void log(String ext, String level, String msg) {
        final String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new java.util.Date())
                + " [" + level + "]" + (ext == null || ext.isEmpty() ? "" : " [" + ext + "]") + " " + msg + "\n";
        if (Looper.myLooper() == Looper.getMainLooper()) appendOutput(LOG_CHANNEL, line);
        else wb.ui.post(new Runnable() {
            @Override
            public void run() {
                appendOutput(LOG_CHANNEL, line);
            }
        });
        if ((wb.activity.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) android.util.Log.d("DCExtHost", line.trim());
    }

    private void appendOutput(String name, String text) {
        StringBuilder sb = outputs.get(name);
        if (sb == null) outputs.put(name, sb = new StringBuilder());
        sb.append(text);
        // Keep channels bounded.
        if (sb.length() > 512 * 1024) sb.delete(0, sb.length() - 384 * 1024);
        Document d = outputDocs.get(name);
        if (d != null && wb.documents().contains(d)) {
            int end = d.text.length();
            d.text.insert(end, text);
            d.markSaved();
            if (d == wb.activeDocument()) wb.editor.invalidate();
        }
    }

    void showOutput(String name) {
        Document d = outputDocs.get(name);
        if (d != null && wb.documents().contains(d)) {
            wb.activate(d);
            return;
        }
        StringBuilder sb = outputs.get(name);
        wb.newUntitled(sb == null ? "" : sb.toString());
        d = wb.activeDocument();
        if (d == null) return;
        d.name = f("Output - {0}", name);
        Language log = Languages.byId("log");
        if (log != null) d.setLanguage(log);
        d.readOnly = true;
        d.markSaved();
        wb.editor.readOnlyView = true;
        outputDocs.put(name, d);
        wb.refreshTabs();
        wb.updateStatus();
    }

    // ------------------------------------------------------------------ files

    /** Resolves a CommonJS request inside an extension; returns {path (relative), text} or null. */
    private Object resolveModule(String extId, String fromDir, String request) throws Exception {
        Extension e = codeExts.get(extId);
        if (e == null) return null;
        String root = e.dir.getCanonicalPath();
        ArrayList<String> bases = new ArrayList<String>();
        if (request.startsWith("./") || request.startsWith("../") || request.startsWith("/")) {
            bases.add(request.startsWith("/") ? request : (fromDir.isEmpty() ? "" : fromDir + "/") + request);
        } else {
            // node_modules lookup from the requiring folder up to the extension root.
            String dir = fromDir;
            while (true) {
                bases.add((dir.isEmpty() ? "" : dir + "/") + "node_modules/" + request);
                if (dir.isEmpty()) break;
                int slash = dir.lastIndexOf('/');
                dir = slash < 0 ? "" : dir.substring(0, slash);
            }
        }
        for (String b : bases) {
            File base = b.startsWith("/") ? new File(b) : new File(e.dir, b);
            File hit = firstFile(base);
            if (hit == null) continue;
            String canon = hit.getCanonicalPath();
            if (!canon.startsWith(root + File.separator)) continue;
            JSONObject o = new JSONObject();
            o.put("path", canon.substring(root.length() + 1).replace(File.separatorChar, '/'));
            o.put("text", new String(readBytes(hit), "UTF-8"));
            return o;
        }
        return null;
    }

    private static File firstFile(File base) throws IOException {
        String[] exts = {"", ".js", ".json", ".cjs"};
        for (String x : exts) {
            File f = new File(base.getPath() + x);
            if (f.isFile()) return f;
        }
        if (base.isDirectory()) {
            File pkg = new File(base, "package.json");
            if (pkg.isFile()) {
                try {
                    JSONObject p = new JSONObject(new String(readBytes(pkg), "UTF-8"));
                    String main = p.optString("main", "");
                    if (!main.isEmpty()) {
                        File m = firstFile(new File(base, main));
                        if (m != null) return m;
                    }
                } catch (JSONException ignored) {
                }
            }
            File idx = new File(base, "index.js");
            if (idx.isFile()) return idx;
        }
        return null;
    }

    private JSONArray findFiles(String base, String include, String exclude, int max) {
        JSONArray out = new JSONArray();
        File root = base != null ? new File(base) : wb.workspace != null ? wb.workspace.root : null;
        if (root == null) return out;
        Pattern inc = Pattern.compile(include), exc = exclude == null ? null : Pattern.compile(exclude);
        ArrayDeque<File> stack = new ArrayDeque<File>();
        stack.push(root);
        String rootPath = root.getAbsolutePath();
        int visited = 0;
        while (!stack.isEmpty() && out.length() < max && visited < 200000) {
            File dir = stack.pop();
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                visited++;
                String rel = f.getAbsolutePath().substring(rootPath.length() + 1);
                if (exc != null && exc.matcher(rel).find()) continue;
                if (f.isDirectory()) {
                    if (!f.getName().equals(".git") && !f.getName().equals("node_modules")) stack.push(f);
                } else if (inc.matcher(rel).find()) {
                    out.put(f.getAbsolutePath());
                    if (out.length() >= max) break;
                }
            }
        }
        return out;
    }

    private static void deleteFile(File f, boolean recursive) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null && kids.length > 0) {
                if (!recursive) throw new IOException("directory not empty: " + f);
                for (File k : kids) deleteFile(k, true);
            }
        }
        if (f.exists() && !f.delete()) throw new IOException("cannot delete " + f);
    }

    static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(f.length(), 1 << 24));
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
