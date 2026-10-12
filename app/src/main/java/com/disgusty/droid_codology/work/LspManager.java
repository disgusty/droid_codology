package com.disgusty.droid_codology.work;

import com.disgusty.droid_codology.editor.Diagnostic;
import com.disgusty.droid_codology.lsp.LspClient;
import com.disgusty.droid_codology.text.TextBuffer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/**
 * Language servers for open documents: one {@link LspClient} per language (started on demand
 * through the account's droid_codology server), document sync, diagnostics and the editor
 * features built on them (completion, hover, definition, references, rename, formatting).
 */
final class LspManager {
    private final Workbench wb;
    private final HashMap<String, LspClient> clients = new HashMap<String, LspClient>();
    private final HashSet<String> starting = new HashSet<String>(), failed = new HashSet<String>();
    /** Bumped by reset(): a server that finishes starting after it belongs to the old settings. */
    private int generation;
    private final IdentityHashMap<Document, Integer> versions = new IdentityHashMap<Document, Integer>();
    private final IdentityHashMap<Document, TextBuffer.Listener> listeners = new IdentityHashMap<Document, TextBuffer.Listener>();
    private final IdentityHashMap<Document, Runnable> pendingChange = new IdentityHashMap<Document, Runnable>();

    LspManager(Workbench wb) {
        this.wb = wb;
    }

    /** Languages the signed-in server can run, from its /v1/info. */
    private List<String> available() {
        ArrayList<String> out = new ArrayList<String>();
        if (!wb.account.signedIn() || !wb.prefs.b("lsp.enabled", true)) return out;
        String s = wb.prefs.raw().getString("state.serverInfo", null);
        if (s == null) return out;
        try {
            JSONObject info = new JSONObject(s);
            if (!info.optString("_server").equals(wb.account.server())) return out;
            JSONArray a = info.optJSONArray("lsp");
            for (int i = 0; a != null && i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    boolean supports(Document d) {
        return d != null && available().contains(d.language.id);
    }

    private LspClient client(Document d) {
        LspClient c = d == null ? null : clients.get(d.language.id);
        return c == null || c.closed() ? null : c;
    }

    // ------------------------------------------------------------------ lifecycle

    /** A document was shown: make sure its language server runs and knows the document. */
    void onActive(final Document d) {
        if (d == null || !supports(d)) return;
        final String lang = d.language.id;
        LspClient c = client(d);
        if (c != null) {
            open(c, d);
            return;
        }
        if (starting.contains(lang) || failed.contains(lang)) return;
        starting.add(lang);
        final String server = wb.account.server(), token = wb.account.token();
        final String wsName = wb.workspace != null ? wb.workspace.name() : "workspace";
        final LspClient nc = new LspClient(server, token, lang, new LspClient.Listener() {
            @Override
            public void onNotification(LspClient c, final String method, final JSONObject params) {
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        notification(method, params);
                    }
                });
            }

            @Override
            public void onExit(final LspClient c, final String reason) {
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        // A client replaced since (reset, new server) must not forget the new one's documents.
                        if (clients.get(c.language) != c) return;
                        clients.remove(c.language);
                        for (Document doc : wb.documents()) if (doc.language.id.equals(c.language)) forget(doc);
                        if (reason != null) wb.showMessage(f("Language server ({0}): {1}", c.language, reason));
                    }
                });
            }
        });
        final int gen = generation;
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    nc.start(wsName);
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final String error = err;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation) {
                            // Started for the server or account used before a reset.
                            nc.stop();
                            return;
                        }
                        starting.remove(lang);
                        if (error != null) {
                            failed.add(lang);
                            wb.showMessage(f("Language server ({0}) failed to start: {1}", lang, error));
                            return;
                        }
                        clients.put(lang, nc);
                        for (Document doc : wb.documents()) if (doc.language.id.equals(lang)) open(nc, doc);
                        wb.updateStatus();
                    }
                });
            }
        });
    }

    /** Retry languages that failed (after signing in or changing the server). */
    void reset() {
        generation++;
        for (LspClient c : clients.values()) c.stop();
        clients.clear();
        starting.clear();
        failed.clear();
        for (Document d : new ArrayList<Document>(listeners.keySet())) forget(d);
        Document a = wb.activeDocument();
        if (a != null) onActive(a);
    }

    String relPath(Document d) {
        if (d.file != null && wb.workspace != null && wb.workspace.contains(d.file)) {
            return wb.workspace.root.toURI().relativize(d.file.toURI()).getPath();
        }
        if (d.file != null) return "external/" + d.file.getName();
        return "untitled/" + d.name.replaceAll("[^\\w.-]", "_");
    }

    String uri(LspClient c, Document d) {
        return c.rootUri + (c.rootUri.endsWith("/") ? "" : "/") + encodePath(relPath(d));
    }

    private static String encodePath(String p) {
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(android.net.Uri.encode(seg));
        }
        return sb.toString();
    }

    /** The open document (or the workspace file) a server URI points to. */
    private Object resolve(LspClient c, String uri) {
        String root = c.rootUri.endsWith("/") ? c.rootUri : c.rootUri + "/";
        if (!uri.startsWith(root)) return null;
        String rel = android.net.Uri.decode(uri.substring(root.length()));
        for (Document d : wb.documents()) if (relPath(d).equals(rel)) return d;
        if (wb.workspace != null && !rel.startsWith("external/") && !rel.startsWith("untitled/")) {
            File f = new File(wb.workspace.root, rel);
            if (f.isFile()) return f;
        }
        return null;
    }

    private void open(final LspClient c, final Document d) {
        if (versions.containsKey(d)) return;
        versions.put(d, 1);
        synced.put(d, d.text.version());
        final String text = d.text.toString();
        c.upload(relPath(d), text);
        try {
            c.notify("textDocument/didOpen", new JSONObject().put("textDocument", new JSONObject()
                    .put("uri", uri(c, d)).put("languageId", d.language.id).put("version", 1).put("text", text)));
        } catch (JSONException ignored) {
        }
        TextBuffer.Listener l = new TextBuffer.Listener() {
            @Override
            public void onReplaced(int start, int removedLen, int insertedLen, int firstLine, int removedLines, int insertedLines) {
                scheduleChange(d);
            }
        };
        d.text.addListener(l);
        listeners.put(d, l);
    }

    private void scheduleChange(final Document d) {
        Runnable old = pendingChange.get(d);
        if (old != null) wb.ui.removeCallbacks(old);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                pendingChange.remove(d);
                flushChange(d);
            }
        };
        pendingChange.put(d, r);
        wb.ui.postDelayed(r, 350);
    }

    /** Sends the latest text now (before requests that depend on it). */
    private void flushChange(Document d) {
        Runnable p = pendingChange.remove(d);
        if (p != null) wb.ui.removeCallbacks(p);
        LspClient c = client(d);
        Integer v = versions.get(d);
        if (c == null || v == null) return;
        Integer sent = synced.get(d);
        if (sent != null && sent == d.text.version()) return;
        int nv = v + 1;
        versions.put(d, nv);
        synced.put(d, d.text.version());
        try {
            c.notify("textDocument/didChange", new JSONObject()
                    .put("textDocument", new JSONObject().put("uri", uri(c, d)).put("version", nv))
                    .put("contentChanges", new JSONArray().put(new JSONObject().put("text", d.text.toString()))));
        } catch (JSONException ignored) {
        }
    }

    /** Text version last sent to the server, per document. */
    private final IdentityHashMap<Document, Integer> synced = new IdentityHashMap<Document, Integer>();

    void onSaved(Document d) {
        LspClient c = client(d);
        if (c == null || !versions.containsKey(d)) return;
        flushChange(d);
        c.upload(relPath(d), d.text.toString());
        try {
            c.notify("textDocument/didSave", new JSONObject().put("textDocument", new JSONObject().put("uri", uri(c, d))));
        } catch (JSONException ignored) {
        }
    }

    void onClosed(Document d) {
        LspClient c = client(d);
        if (c != null && versions.containsKey(d)) {
            try {
                c.notify("textDocument/didClose", new JSONObject().put("textDocument", new JSONObject().put("uri", uri(c, d))));
            } catch (JSONException ignored) {
            }
        }
        forget(d);
    }

    private void forget(Document d) {
        versions.remove(d);
        synced.remove(d);
        TextBuffer.Listener l = listeners.remove(d);
        if (l != null) d.text.removeListener(l);
        Runnable p = pendingChange.remove(d);
        if (p != null) wb.ui.removeCallbacks(p);
        if (d.diagnostics.remove("lsp") != null) wb.onDiagnosticsChanged(d);
    }

    // ------------------------------------------------------------------ notifications

    private void notification(String method, JSONObject params) {
        if (params == null) return;
        if (method.equals("textDocument/publishDiagnostics")) {
            String uri = params.optString("uri");
            for (LspClient c : clients.values()) {
                Object target = resolve(c, uri);
                if (!(target instanceof Document)) continue;
                Document d = (Document) target;
                ArrayList<Diagnostic> list = new ArrayList<Diagnostic>();
                JSONArray a = params.optJSONArray("diagnostics");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    JSONObject r = o.optJSONObject("range");
                    if (r == null) continue;
                    JSONObject s = r.optJSONObject("start"), e = r.optJSONObject("end");
                    if (s == null || e == null) continue; // a malformed range from the server
                    String src = o.optString("source", c.language);
                    Object code = o.opt("code");
                    String msg = o.optString("message") + (code != null && code != JSONObject.NULL ? " (" + code + ")" : "");
                    list.add(new Diagnostic(s.optInt("line"), s.optInt("character"), e.optInt("line"), e.optInt("character"),
                            o.optInt("severity", Diagnostic.ERROR), msg, src));
                }
                d.diagnostics.put("lsp", list);
                wb.onDiagnosticsChanged(d);
                return;
            }
        } else if (method.equals("window/showMessage")) {
            int type = params.optInt("type", 3);
            if (type <= 2) wb.showMessage(params.optString("message"));
        }
    }

    // ------------------------------------------------------------------ features

    interface Result<T> {
        void done(T value);
    }

    private JSONObject position(LspClient c, Document d, int offset) throws JSONException {
        int line = d.text.lineOfOffset(offset);
        return new JSONObject()
                .put("textDocument", new JSONObject().put("uri", uri(c, d)))
                .put("position", new JSONObject().put("line", line).put("character", offset - d.text.lineStart(line)));
    }

    private int offsetOf(Document d, JSONObject pos) {
        if (pos == null) pos = new JSONObject(); // a malformed range: line 0, column 0
        int line = Math.max(0, Math.min(d.text.lineCount() - 1, pos.optInt("line")));
        return Math.min(d.text.lineEnd(line), d.text.lineStart(line) + Math.max(0, pos.optInt("character")));
    }

    /** Completion items for the caret (empty list when there's no server). Result on the UI thread. */
    void complete(final Document d, final int offset, String trigger, final Result<List<Completion.Item>> cb) {
        final LspClient c = client(d);
        if (c == null || !c.has("completionProvider")) {
            cb.done(null);
            return;
        }
        flushChange(d);
        try {
            JSONObject p = position(c, d, offset);
            p.put("context", trigger != null ? new JSONObject().put("triggerKind", 2).put("triggerCharacter", trigger)
                    : new JSONObject().put("triggerKind", 1));
            c.request("textDocument/completion", p, new LspClient.Callback() {
                @Override
                public void done(final Object result, String error) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.done(completionItems(d, result));
                        }
                    });
                }
            });
        } catch (JSONException e) {
            cb.done(null);
        }
    }

    private List<Completion.Item> completionItems(Document d, Object result) {
        ArrayList<Completion.Item> out = new ArrayList<Completion.Item>();
        JSONArray items = result instanceof JSONArray ? (JSONArray) result
                : result instanceof JSONObject ? ((JSONObject) result).optJSONArray("items") : null;
        for (int i = 0; items != null && i < items.length() && out.size() < 300; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String label = o.optString("label");
            String insert = o.optString("insertText", label);
            JSONObject edit = o.optJSONObject("textEdit");
            int replaceStart = -1;
            if (edit != null) {
                insert = edit.optString("newText", insert);
                JSONObject range = edit.optJSONObject("range");
                if (range == null) range = edit.optJSONObject("replace");
                if (range != null && range.optJSONObject("start") != null) replaceStart = offsetOf(d, range.optJSONObject("start"));
            }
            boolean snippet = o.optInt("insertTextFormat", 1) == 2;
            Completion.Item it = new Completion.Item(label, snippet ? label : insert, o.optString("detail", null),
                    kindOf(o.optInt("kind", 0)), snippet ? insert : null);
            it.replaceStart = replaceStart;
            it.filterText = o.optString("filterText", label);
            out.add(it);
        }
        return out;
    }

    static int kindOf(int lspKind) {
        switch (lspKind) {
            case 2:
            case 3:
            case 4:
                return Completion.KIND_FUNCTION;
            case 7:
            case 8:
            case 9:
            case 13:
            case 22:
                return Completion.KIND_CLASS;
            case 5:
            case 10:
            case 20:
                return Completion.KIND_FIELD;
            case 6:
            case 21:
                return Completion.KIND_VARIABLE;
            case 14:
                return Completion.KIND_KEYWORD;
            case 15:
                return Completion.KIND_SNIPPET;
            default:
                return Completion.KIND_WORD;
        }
    }

    /** Characters that should open suggestions for this document's server. */
    boolean isTrigger(Document d, String typed) {
        LspClient c = client(d);
        if (c == null || typed.length() != 1) return false;
        JSONObject cp = c.capabilities.optJSONObject("completionProvider");
        JSONArray tc = cp == null ? null : cp.optJSONArray("triggerCharacters");
        for (int i = 0; tc != null && i < tc.length(); i++) if (typed.equals(tc.optString(i))) return true;
        return false;
    }

    void hover(final Document d, int offset) {
        final LspClient c = client(d);
        if (c == null || !c.has("hoverProvider")) {
            wb.showMessage(t("No hover information (no language server for this file)."));
            return;
        }
        flushChange(d);
        try {
            c.request("textDocument/hover", position(c, d, offset), new LspClient.Callback() {
                @Override
                public void done(final Object result, final String error) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            String text = hoverText(result);
                            if (text.trim().isEmpty()) wb.showMessage(error != null ? error : t("No hover information."));
                            else wb.showHover(text);
                        }
                    });
                }
            });
        } catch (JSONException ignored) {
        }
    }

    static String hoverText(Object result) {
        if (!(result instanceof JSONObject)) return "";
        Object contents = ((JSONObject) result).opt("contents");
        StringBuilder sb = new StringBuilder();
        appendMarked(sb, contents);
        return sb.toString().trim();
    }

    private static void appendMarked(StringBuilder sb, Object c) {
        if (c instanceof String) sb.append((String) c).append("\n\n");
        else if (c instanceof JSONObject) {
            JSONObject o = (JSONObject) c;
            if (o.has("language")) sb.append("```").append(o.optString("language")).append('\n').append(o.optString("value")).append("\n```\n\n");
            else sb.append(o.optString("value")).append("\n\n");
        } else if (c instanceof JSONArray) {
            JSONArray a = (JSONArray) c;
            for (int i = 0; i < a.length(); i++) appendMarked(sb, a.opt(i));
        }
    }

    /** Go to Definition (F12). */
    void definition(final Document d, int offset) {
        locations("textDocument/definition", "definitionProvider", d, offset, null, t("No definition found."));
    }

    /** Find All References (Shift+F12). */
    void references(final Document d, int offset) {
        JSONObject ctx = new JSONObject();
        try {
            ctx.put("includeDeclaration", true);
        } catch (JSONException ignored) {
        }
        locations("textDocument/references", "referencesProvider", d, offset, ctx, t("No references found."));
    }

    private void locations(String method, String capability, final Document d, int offset, JSONObject context, final String none) {
        final LspClient c = client(d);
        if (c == null || !c.has(capability)) {
            wb.showMessage(t("This needs a language server for the file's language."));
            return;
        }
        flushChange(d);
        try {
            JSONObject p = position(c, d, offset);
            if (context != null) p.put("context", context);
            c.request(method, p, new LspClient.Callback() {
                @Override
                public void done(final Object result, final String error) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            ArrayList<JSONObject> locs = new ArrayList<JSONObject>();
                            if (result instanceof JSONObject) locs.add((JSONObject) result);
                            else if (result instanceof JSONArray) {
                                JSONArray a = (JSONArray) result;
                                for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) locs.add(a.optJSONObject(i));
                            }
                            if (locs.isEmpty()) {
                                wb.showMessage(error != null ? error : none);
                                return;
                            }
                            if (locs.size() == 1) {
                                jump(c, locs.get(0));
                                return;
                            }
                            ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
                            for (final JSONObject l : locs) {
                                String uri = l.optString("uri", l.optString("targetUri"));
                                JSONObject r = l.optJSONObject("range") != null ? l.optJSONObject("range") : l.optJSONObject("targetSelectionRange");
                                int line = r == null || r.optJSONObject("start") == null ? 0 : r.optJSONObject("start").optInt("line");
                                String name = android.net.Uri.decode(uri.substring(uri.lastIndexOf('/') + 1));
                                QuickPick.Item it = new QuickPick.Item(name + ":" + (line + 1), null, new Runnable() {
                                    @Override
                                    public void run() {
                                        jump(c, l);
                                    }
                                });
                                Object target = resolve(c, uri);
                                it.description = target instanceof Document ? lineText((Document) target, line)
                                        : target instanceof File ? ((File) target).getParentFile().getName() : t("not in this workspace");
                                items.add(it);
                            }
                            wb.quickPick().showItems(f("{0} locations", locs.size()), items);
                        }
                    });
                }
            });
        } catch (JSONException ignored) {
        }
    }

    private static String lineText(Document d, int line) {
        if (line < 0 || line >= d.text.lineCount()) return "";
        return d.text.lineText(line).trim();
    }

    private void jump(LspClient c, JSONObject loc) {
        String uri = loc.optString("uri", loc.optString("targetUri"));
        JSONObject r = loc.optJSONObject("range") != null ? loc.optJSONObject("range") : loc.optJSONObject("targetSelectionRange");
        JSONObject start = r == null || r.optJSONObject("start") == null ? new JSONObject() : r.optJSONObject("start");
        Object target = resolve(c, uri);
        int line = start.optInt("line"), col = start.optInt("character");
        if (target instanceof Document) {
            wb.activate((Document) target);
            wb.goTo(line, col);
        } else if (target instanceof File) {
            wb.openFile((File) target, false, line, col, 0);
        } else {
            wb.showMessage(f("The definition is outside the workspace: {0}", android.net.Uri.decode(uri)));
        }
    }

    /** Rename Symbol (F2). */
    void rename(final Document d, final int offset) {
        final LspClient c = client(d);
        if (c == null || !c.has("renameProvider")) {
            wb.showMessage(t("This needs a language server for the file's language."));
            return;
        }
        int[] w = d.engine.wordAt(offset);
        String current = w == null ? "" : d.text.substring(w[0], w[1]);
        wb.prompt(t("Rename Symbol"), t("New name"), current, new Workbench.TextCallback() {
            @Override
            public void onText(String newName) {
                flushChange(d);
                try {
                    JSONObject p = position(c, d, offset).put("newName", newName);
                    c.request("textDocument/rename", p, new LspClient.Callback() {
                        @Override
                        public void done(final Object result, final String error) {
                            wb.ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (error != null || !(result instanceof JSONObject)) {
                                        wb.showMessage(error != null ? error : t("Nothing to rename here."));
                                        return;
                                    }
                                    int n = applyWorkspaceEdit(c, (JSONObject) result);
                                    wb.showMessage(f("Renamed in {0} file(s).", n));
                                }
                            });
                        }
                    });
                } catch (JSONException ignored) {
                }
            }
        });
    }

    /** Format Document through the server; {@code fallback} runs when it can't. */
    boolean format(final Document d, final Runnable fallback) {
        final LspClient c = client(d);
        if (c == null || !c.has("documentFormattingProvider")) return false;
        flushChange(d);
        try {
            JSONObject p = new JSONObject().put("textDocument", new JSONObject().put("uri", uri(c, d)))
                    .put("options", new JSONObject().put("tabSize", d.engine.tabSize).put("insertSpaces", d.engine.insertSpaces));
            c.request("textDocument/formatting", p, new LspClient.Callback() {
                @Override
                public void done(final Object result, final String error) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (error != null || !(result instanceof JSONArray)) {
                                if (fallback != null) fallback.run();
                                return;
                            }
                            applyEdits(d, (JSONArray) result);
                        }
                    });
                }
            });
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    private int applyWorkspaceEdit(LspClient c, JSONObject we) {
        int files = 0;
        JSONObject changes = we.optJSONObject("changes");
        if (changes != null) {
            java.util.Iterator<String> it = changes.keys();
            while (it.hasNext()) {
                String uri = it.next();
                if (applyToUri(c, uri, changes.optJSONArray(uri))) files++;
            }
        }
        JSONArray dc = we.optJSONArray("documentChanges");
        for (int i = 0; dc != null && i < dc.length(); i++) {
            JSONObject e = dc.optJSONObject(i);
            if (e == null || e.optJSONObject("textDocument") == null) continue;
            if (applyToUri(c, e.optJSONObject("textDocument").optString("uri"), e.optJSONArray("edits"))) files++;
        }
        return files;
    }

    private boolean applyToUri(LspClient c, String uri, JSONArray edits) {
        Object target = resolve(c, uri);
        if (target instanceof File) {
            wb.openFile((File) target, false, -1, 0, 0);
            for (Document d : wb.documents()) if (((File) target).equals(d.file)) target = d;
        }
        if (!(target instanceof Document) || edits == null) return false;
        applyEdits((Document) target, edits);
        return true;
    }

    /** Applies LSP TextEdits (all relative to the current text) as one undo step. */
    void applyEdits(Document d, JSONArray edits) {
        ArrayList<int[]> ranges = new ArrayList<int[]>();
        final ArrayList<String> texts = new ArrayList<String>();
        for (int i = 0; i < edits.length(); i++) {
            JSONObject e = edits.optJSONObject(i);
            if (e == null || e.optJSONObject("range") == null) continue;
            JSONObject r = e.optJSONObject("range");
            ranges.add(new int[]{offsetOf(d, r.optJSONObject("start")), offsetOf(d, r.optJSONObject("end")), texts.size()});
            texts.add(e.optString("newText"));
        }
        Collections.sort(ranges, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                if (b[0] != a[0]) return b[0] < a[0] ? -1 : 1;
                if (b[1] != a[1]) return b[1] < a[1] ? -1 : 1;
                // Same spot: the later edit is applied first so the inserts end up in array order (LSP spec).
                return b[2] - a[2];
            }
        });
        d.engine.begin(com.disgusty.droid_codology.text.UndoStack.KIND_OTHER);
        try {
            for (int[] r : ranges) d.engine.replace(r[0], Math.max(r[0], r[1]), texts.get(r[2]));
        } finally {
            d.engine.end();
        }
        if (d == wb.activeDocument()) wb.editor.invalidate();
        wb.refreshTabs();
    }

    /** "python ✓" / "python …" for the status bar. */
    String statusFor(Document d) {
        if (d == null || !supports(d)) return null;
        String lang = d.language.id;
        if (client(d) != null) return "{ } " + lang;
        if (starting.contains(lang)) return "{ } " + lang + " …";
        if (failed.contains(lang)) return "{ } " + lang + " ✗";
        return null;
    }

    @SuppressWarnings("unused")
    private Map<String, LspClient> clients() {
        return clients;
    }
}
