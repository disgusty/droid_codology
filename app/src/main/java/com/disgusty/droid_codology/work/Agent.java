package com.disgusty.droid_codology.work;

import com.disgusty.droid_codology.ai.AiClient;
import com.disgusty.droid_codology.ai.AiProvider;
import com.disgusty.droid_codology.git.Diff;
import com.disgusty.droid_codology.git.Repo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent mode: the model works on the project with tools (read, search, edit files, run
 * commands, see problems) in a loop until the task is done. Tool calls use a plain-text
 * protocol, so any chat model works (also local ones and the server proxy). Edits and commands
 * wait for the user's approval unless they allowed them for the session.
 */
final class Agent {
    static final int MAX_STEPS = 40;

    interface Listener {
        /** The model's visible text for the current turn (tool calls removed). UI thread. */
        void onText(String text);

        /** A tool step was added or changed. UI thread. */
        void onStep(Step s);

        /** Finished (error null), stopped or failed. UI thread. */
        void onFinished(String error);
    }

    static final int PENDING = 0, WAITING = 1, RUNNING = 2, DONE = 3, REJECTED = 4, FAILED = 5, REVERTED = 6;

    static final class Step {
        final int id;
        final String tool;
        final JSONObject args;
        String title = "", detail = "", output = "";
        int state = PENDING;
        /** For edits: the file and its text before the agent first touched it (for Revert). */
        File file;
        String before, after;
        boolean created;
        /** The file's encoding on disk, kept when writing (UTF-8 for new files). */
        final Encoding encoding = new Encoding();
        final CountDownLatch decision = new CountDownLatch(1);
        volatile int answer; // 1 approve, 2 reject, 3 approve and always allow

        Step(int id, String tool, JSONObject args) {
            this.id = id;
            this.tool = tool;
            this.args = args;
        }
    }

    private final Workbench wb;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile AiClient.Cancel cancel;
    /** Step ids are unique across runs: the chat keeps steps of earlier runs and finds them by id. */
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_STEP = new java.util.concurrent.atomic.AtomicInteger(1);
    /** Read by the UI thread while the agent's thread adds to it. */
    final java.util.List<Step> steps = new java.util.concurrent.CopyOnWriteArrayList<Step>();
    /**
     * Permissions the user granted with "Always": for one workspace and one chat only (the chat
     * resets them with {@link #resetPermissions}).
     */
    static volatile boolean allowEdits, allowCommands;
    private static volatile String allowedRoot;

    static void resetPermissions() {
        allowEdits = false;
        allowCommands = false;
        allowedRoot = null;
    }

    private boolean allowed(String tool) {
        String root = wb.workspace == null ? "" : wb.workspace.root.getAbsolutePath();
        if (!root.equals(allowedRoot)) {
            // Another folder than the one the user said "always" for.
            allowEdits = false;
            allowCommands = false;
            allowedRoot = root;
        }
        return isEdit(tool) ? allowEdits : tool.equals("run_command") && allowCommands;
    }

    Agent(Workbench wb, Listener listener) {
        this.wb = wb;
        this.listener = listener;
    }

    void stop() {
        stopped = true;
        AiClient.Cancel c = cancel;
        if (c != null) c.cancel();
        for (Step s : steps) {
            if (s.state == WAITING) {
                s.answer = 2;
                s.decision.countDown();
            }
        }
    }

    boolean stopped() {
        return stopped;
    }

    void decide(Step s, int answer) {
        if (s.state != WAITING) return;
        s.answer = answer;
        if (answer == 3) {
            allowedRoot = wb.workspace == null ? "" : wb.workspace.root.getAbsolutePath();
            if (isEdit(s.tool)) allowEdits = true;
            else allowCommands = true;
        }
        s.decision.countDown();
    }

    // ------------------------------------------------------------------ prompt

    static String systemPrompt(Workbench wb) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an autonomous coding agent inside droid_codology, a code editor on Android. ")
                .append("You complete the user's task by working on their project with tools, step by step, until it is done.\n\n")
                .append("To use tools, reply with one or more blocks exactly like this (JSON inside, nothing else in the block):\n")
                .append("<tool_call>\n{\"tool\": \"read_file\", \"args\": {\"path\": \"src/main.py\"}}\n</tool_call>\n\n")
                .append("Then stop writing: the results come back in the next message as <tool_result> blocks. ")
                .append("Call several independent read-only tools at once when useful. ")
                .append("When the task is complete (or you need the user's input), reply WITHOUT any tool call and briefly summarize what you did.\n\n")
                .append("Rules:\n- Paths are relative to the workspace root.\n- Read a file before editing it; keep the existing style.\n")
                .append("- Prefer edit_file with a small unique old_text over rewriting whole files.\n")
                .append("- After changing code, check get_problems (and run tests or the program when that makes sense).\n")
                .append("- The shell is Android's /system/bin/sh (toybox); tools like python, node or git may be missing unless installed (e.g. Termux).\n")
                .append("- Never invent file contents: read them.\n\n")
                .append("Tools:\n")
                .append("- list_files {\"path\": \".\", \"depth\": 2}: files and folders\n")
                .append("- read_file {\"path\", \"start_line\"?, \"end_line\"?}: text with line numbers\n")
                .append("- search {\"query\", \"regex\"?: false, \"path\"?: \".\"}: matching lines in the project\n")
                .append("- edit_file {\"path\", \"old_text\", \"new_text\"}: replaces exactly one occurrence of old_text\n")
                .append("- write_file {\"path\", \"content\"}: creates or overwrites a file\n")
                .append("- delete_file {\"path\"}\n")
                .append("- run_command {\"command\", \"timeout\"?: 60}: runs in the workspace with sh -c; returns output and exit code\n")
                .append("- get_problems {\"path\"?}: errors and warnings from language servers for open files\n")
                .append("- git_status {} and git_diff {\"path\"?}\n\n");
        if (wb.workspace != null) {
            sb.append("Workspace: ").append(wb.workspace.name()).append('\n');
            sb.append("Top level: ");
            File[] fs = wb.workspace.list(wb.workspace.root);
            int n = 0;
            for (File f : fs) {
                if (n++ >= 60) {
                    sb.append("…");
                    break;
                }
                sb.append(f.getName()).append(f.isDirectory() ? "/ " : " ");
            }
            sb.append('\n');
        } else {
            sb.append("No folder is open: file tools work in the app's home folder.\n");
        }
        Document d = wb.activeDocument();
        if (d != null && d.file != null) sb.append("The user is looking at: ").append(relPath(wb, d.file)).append('\n');
        String extra = wb.ai.systemPrompt();
        if (!extra.isEmpty()) sb.append("\nUser instructions:\n").append(extra).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ loop

    /** Runs the task on the calling (background) thread. {@code history} holds earlier chat turns. */
    void run(final AiProvider model, final List<AiClient.Message> history) {
        final ArrayList<AiClient.Message> msgs = new ArrayList<AiClient.Message>(history);
        String system = onUi(new Callable<String>() {
            @Override
            public String call() {
                return systemPrompt(wb);
            }
        });
        String error = null;
        try {
            for (int step = 0; step < MAX_STEPS && !stopped; step++) {
                compact(msgs);
                final StringBuilder text = new StringBuilder();
                cancel = new AiClient.Cancel();
                AiClient.chat(model, model.models.get(0), system, msgs, 0, wb.account.token(), new AiClient.Listener() {
                    @Override
                    public void onDelta(String piece) {
                        text.append(piece);
                        final String visible = visible(text.toString());
                        wb.ui.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onText(visible);
                            }
                        });
                    }
                }, cancel);
                if (stopped) break;
                String reply = text.toString();
                msgs.add(new AiClient.Message("assistant", reply));
                List<Step> calls = parse(reply);
                if (calls.isEmpty()) break;
                StringBuilder results = new StringBuilder();
                for (Step s : calls) {
                    if (stopped) break;
                    String out = execute(s);
                    results.append("<tool_result tool=\"").append(s.tool).append("\" ok=\"").append(s.state == DONE).append("\">\n")
                            .append(out).append("\n</tool_result>\n");
                }
                if (stopped) break;
                msgs.add(new AiClient.Message("user", results.toString()));
                final String next = "";
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onText(next); // a new assistant turn starts
                    }
                });
                if (step == MAX_STEPS - 1) error = I18n.f("Stopped after {0} steps.", MAX_STEPS);
            }
        } catch (Exception e) {
            if (!stopped) error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        final String err = error;
        wb.ui.post(new Runnable() {
            @Override
            public void run() {
                listener.onFinished(err);
            }
        });
    }

    /** Old tool results are shortened so long sessions fit the model's context. */
    private static void compact(List<AiClient.Message> msgs) {
        int total = 0;
        for (AiClient.Message m : msgs) total += m.content.length();
        for (int i = 0; i < msgs.size() - 4 && total > 150000; i++) {
            AiClient.Message m = msgs.get(i);
            if (!m.role.equals("user") || !m.content.startsWith("<tool_result") || m.content.length() < 600) continue;
            String shortened = m.content.substring(0, 400) + "\n[older tool output omitted]\n</tool_result>";
            total -= m.content.length() - shortened.length();
            msgs.set(i, new AiClient.Message("user", shortened));
        }
    }

    private static final Pattern CALL = Pattern.compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL);
    private static final Pattern FENCED = Pattern.compile("```(?:tool_call|json)?\\s*(\\{\\s*\"tool\".*?\\})\\s*```", Pattern.DOTALL);

    List<Step> parse(String reply) {
        ArrayList<Step> out = new ArrayList<Step>();
        Matcher m = CALL.matcher(reply);
        boolean any = false;
        while (m.find()) {
            any = true;
            add(out, m.group(1));
        }
        if (!any) {
            Matcher f = FENCED.matcher(reply);
            while (f.find()) add(out, f.group(1));
        }
        return out;
    }

    private void add(List<Step> out, String json) {
        try {
            JSONObject o = new JSONObject(json);
            String tool = o.optString("tool", o.optString("name"));
            JSONObject args = o.optJSONObject("args");
            if (args == null) args = o.optJSONObject("arguments");
            if (args == null) args = new JSONObject();
            if (!tool.isEmpty()) out.add(new Step(NEXT_STEP.getAndIncrement(), tool, args));
        } catch (JSONException ignored) {
            // Not a tool call after all.
        }
    }

    /** Assistant text without the tool-call blocks (shown in the chat). */
    static String visible(String reply) {
        String s = CALL.matcher(reply).replaceAll("");
        int open = s.indexOf("<tool_call>");
        if (open >= 0) s = s.substring(0, open); // a call still streaming in
        return FENCED.matcher(s).replaceAll("").trim();
    }

    // ------------------------------------------------------------------ tools

    private static boolean isEdit(String tool) {
        return tool.equals("edit_file") || tool.equals("write_file") || tool.equals("delete_file");
    }

    private String execute(final Step s) {
        steps.add(s);
        try {
            describe(s);
        } catch (Exception e) {
            s.state = FAILED;
            s.output = e.getMessage();
            publish(s);
            return "Error: " + e.getMessage();
        }
        boolean needsApproval = (isEdit(s.tool) || s.tool.equals("run_command")) && !allowed(s.tool);
        if (needsApproval) {
            s.state = WAITING;
            publish(s);
            try {
                // Stop may have come while the step was being described: it releases WAITING steps
                // only, so look again before waiting.
                while (!stopped && !s.decision.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    // wait for the user's decision or Stop
                }
            } catch (InterruptedException e) {
                s.answer = 2;
            }
            if (s.answer == 2 || stopped) {
                s.state = REJECTED;
                publish(s);
                return "The user rejected this action. Do not retry it; ask or choose another approach.";
            }
        }
        if (stopped) {
            s.state = REJECTED;
            publish(s);
            return "Stopped by the user.";
        }
        s.state = RUNNING;
        publish(s);
        try {
            String out = run(s);
            s.state = DONE;
            if (s.output.isEmpty()) s.output = out;
            publish(s);
            return out;
        } catch (Exception e) {
            s.state = FAILED;
            s.output = e.getMessage() == null ? e.toString() : e.getMessage();
            publish(s);
            return "Error: " + s.output;
        }
    }

    private void publish(final Step s) {
        wb.ui.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onStep(s);
            }
        });
    }

    private File root() {
        if (wb.workspace != null) return wb.workspace.root;
        File home = new File(wb.activity.getFilesDir(), "home");
        home.mkdirs();
        return home;
    }

    static String relPath(Workbench wb, File f) {
        return wb.workspace != null && wb.workspace.contains(f) ? wb.workspace.relative(f) : f.getAbsolutePath();
    }

    /** A path inside the workspace (no escaping it with ../). */
    private File resolve(String path) throws Exception {
        if (path == null || path.isEmpty()) path = ".";
        File root = root().getCanonicalFile();
        File f = new File(path.startsWith("/") ? new File(path).getPath() : new File(root, path).getPath()).getCanonicalFile();
        String r = root.getPath();
        if (!f.getPath().equals(r) && !f.getPath().startsWith(r + File.separator)) {
            throw new Exception("Path is outside the workspace: " + path);
        }
        return f;
    }

    /** Title, detail (diff / command) and the before-state, computed before approval. */
    private void describe(Step s) throws Exception {
        JSONObject a = s.args;
        String path = a.optString("path", "");
        switch (s.tool) {
            case "list_files":
                s.title = I18n.f("List {0}", path.isEmpty() ? "." : path);
                break;
            case "read_file":
                s.title = I18n.f("Read {0}", path);
                break;
            case "search":
                s.title = I18n.f("Search \"{0}\"", a.optString("query"));
                break;
            case "get_problems":
                s.title = I18n.t("Check problems");
                break;
            case "git_status":
            case "git_diff":
                s.title = "git " + s.tool.substring(4);
                break;
            case "run_command":
                s.title = I18n.t("Run command");
                s.detail = a.optString("command");
                break;
            case "edit_file": {
                File f = resolve(path);
                String cur = currentText(f, s.encoding);
                if (cur == null) throw new Exception("File not found: " + path);
                String oldText = a.optString("old_text"), newText = a.optString("new_text");
                int at = cur.indexOf(oldText);
                if (oldText.isEmpty() || at < 0) throw new Exception("old_text was not found in " + path + "; read the file again");
                if (cur.indexOf(oldText, at + 1) >= 0) throw new Exception("old_text occurs more than once in " + path + "; include more context");
                String next = cur.substring(0, at) + newText + cur.substring(at + oldText.length());
                prepareEdit(s, f, cur, next, path);
                break;
            }
            case "write_file": {
                File f = resolve(path);
                String cur = currentText(f, s.encoding);
                prepareEdit(s, f, cur == null ? "" : cur, a.optString("content"), path);
                s.created = cur == null;
                if (s.created) s.title = I18n.f("Create {0}", path);
                break;
            }
            case "delete_file": {
                File f = resolve(path);
                String cur = currentText(f, s.encoding);
                if (cur == null) throw new Exception("File not found: " + path);
                s.file = f;
                s.before = cur;
                s.title = I18n.f("Delete {0}", path);
                break;
            }
            default:
                throw new Exception("Unknown tool " + s.tool);
        }
    }

    private void prepareEdit(Step s, File f, String cur, String next, String path) {
        s.file = f;
        s.before = cur;
        s.detail = Diff.unified("a/" + path, "b/" + path, cur, next, 2);
        int add = 0, del = 0;
        for (String l : s.detail.split("\n")) {
            if (l.startsWith("+") && !l.startsWith("+++")) add++;
            else if (l.startsWith("-") && !l.startsWith("---")) del++;
        }
        s.title = I18n.f("Edit {0} (+{1} −{2})", path, add, del);
        s.after = next;
    }

    /** Files bigger than this are refused instead of being read whole into memory. */
    private static final long MAX_FILE = 4L * 1024 * 1024;

    /** How a file on disk is encoded, so the agent writes it back the same way. */
    static final class Encoding {
        String charset = "UTF-8", eol = "\n";
        boolean bom;
    }

    /** The text the user sees: the open editor's (maybe unsaved) or the file's. Null if missing. */
    private String currentText(final File f) throws Exception {
        return currentText(f, null);
    }

    private String currentText(final File f, Encoding enc) throws Exception {
        String open = onUi(new Callable<String>() {
            @Override
            public String call() {
                for (Document d : wb.documents()) if (sameFile(f, d.file)) return d.text.toString();
                return null;
            }
        });
        if (open != null) return open;
        if (!f.isFile()) return null;
        if (f.length() > MAX_FILE) throw new Exception("The file is too large for the agent (" + (f.length() / 1024) + " KB)");
        byte[] data = ExtensionHost.readBytes(f);
        com.disgusty.droid_codology.io.TextFiles.Loaded l = com.disgusty.droid_codology.io.TextFiles.decode(data, data.length, null);
        if (l.binary) throw new Exception("This is a binary file (" + f.length() + " bytes); the agent works with text files only");
        if (enc != null) {
            enc.charset = l.charset;
            enc.bom = l.bom;
            enc.eol = l.eol;
        }
        return new String(l.chars, 0, l.length);
    }

    /** Workspace paths are canonical here, editor documents may not be (e.g. /data/user/0 vs /data/data). */
    static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private String run(Step s) throws Exception {
        JSONObject a = s.args;
        switch (s.tool) {
            case "list_files":
                return listFiles(resolve(a.optString("path", ".")), Math.max(1, Math.min(6, a.optInt("depth", 2))));
            case "read_file":
                return readFile(resolve(a.optString("path")), a.optInt("start_line", 1), a.optInt("end_line", 0));
            case "search":
                return search(a.optString("query"), a.optBoolean("regex"), resolve(a.optString("path", ".")));
            case "edit_file":
            case "write_file":
                // The diff the user approved was made from this text: don't overwrite edits made since.
                if (!same(currentText(s.file), s.created ? null : s.before)) {
                    throw new Exception("The file changed after this step was prepared; read it again and redo the edit");
                }
                applyText(s.file, s.after, s.encoding);
                return "Done. " + s.title;
            case "delete_file":
                if (!same(currentText(s.file), s.before)) throw new Exception("The file changed after this step was prepared; read it again");
                closeIfOpen(s.file);
                if (!s.file.delete()) throw new Exception("Couldn't delete " + a.optString("path"));
                refreshFiles();
                return "Deleted.";
            case "run_command":
                return command(a.optString("command"), Math.max(1, Math.min(600, a.optInt("timeout", 60))), s);
            case "get_problems":
                return problems(a.optString("path", ""));
            case "git_status":
                return gitStatus();
            case "git_diff":
                return gitDiff(a.optString("path", ""));
            default:
                throw new Exception("Unknown tool " + s.tool);
        }
    }

    // Results of read-only tools are capped so one call can't flood the context.
    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n[… truncated, " + (s.length() - max) + " more characters]";
    }

    private String listFiles(File dir, int depth) {
        if (!dir.isDirectory()) return "Not a folder.";
        StringBuilder sb = new StringBuilder();
        listInto(sb, dir, "", depth, new int[]{0});
        return cap(sb.toString(), 12000);
    }

    private void listInto(StringBuilder sb, File dir, String indent, int depth, int[] count) {
        File[] fs = wb.workspace != null ? wb.workspace.list(dir) : dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (count[0]++ > 600) {
                sb.append(indent).append("…\n");
                return;
            }
            boolean d = f.isDirectory();
            sb.append(indent).append(f.getName()).append(d ? "/" : "  (" + f.length() + " B)").append('\n');
            if (d && depth > 1 && (wb.workspace == null || !wb.workspace.excludedDirs.contains(f.getName()))) {
                listInto(sb, f, indent + "  ", depth - 1, count);
            }
        }
    }

    private String readFile(File f, int start, int end) throws Exception {
        String text = currentText(f);
        if (text == null) throw new Exception("File not found");
        for (int i = 0; i < Math.min(text.length(), 8000); i++) if (text.charAt(i) == 0) return "(binary file, " + f.length() + " bytes)";
        String[] lines = text.split("\n", -1);
        int s = Math.max(1, start), e = end <= 0 ? lines.length : Math.min(lines.length, end);
        StringBuilder sb = new StringBuilder();
        for (int i = s; i <= e; i++) {
            sb.append(i).append(": ").append(lines[i - 1]).append('\n');
            if (sb.length() > 40000) {
                sb.append("[… stopped at line ").append(i).append(" of ").append(lines.length).append("; read further with start_line]\n");
                break;
            }
        }
        return sb.toString();
    }

    private String search(final String query, boolean regex, File dir) throws Exception {
        if (query.isEmpty()) throw new Exception("Empty query");
        final Pattern p = regex ? Pattern.compile(query) : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        final StringBuilder sb = new StringBuilder();
        final int[] hits = {0};
        // dir is canonical (resolve()); the walk reports paths under the root as the workspace spells it.
        String raw = root().getAbsolutePath(), canon = root().getCanonicalPath(), b = dir.getPath();
        final String base = b.startsWith(canon) ? raw + b.substring(canon.length()) : b;
        Workspace ws = wb.workspace != null ? wb.workspace : new Workspace(root());
        ws.walk(new Workspace.FileVisitor() {
            @Override
            public boolean visit(File f) {
                if (!f.getPath().startsWith(base) || f.length() > 2000000) return true;
                try {
                    String text = new String(ExtensionHost.readBytes(f), "UTF-8");
                    if (text.indexOf('\0') >= 0) return true;
                    String[] lines = text.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        if (p.matcher(lines[i]).find()) {
                            sb.append(relPath(wb, f)).append(':').append(i + 1).append(": ").append(cap(lines[i].trim(), 200)).append('\n');
                            if (++hits[0] >= 200) return false;
                        }
                    }
                } catch (Exception ignored) {
                }
                return true;
            }
        }, 20000);
        return hits[0] == 0 ? "No matches." : cap(sb.toString(), 16000);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Writes through the open editor when the file is open (undoable, then saved in its own
     * encoding), else to disk in {@code enc}.
     */
    private void applyText(final File f, final String text, Encoding enc) throws Exception {
        Boolean viaEditor = onUi(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                for (Document d : wb.documents()) {
                    if (sameFile(f, d.file)) {
                        d.engine.replaceAndSelect(0, d.text.length(), text, 0, 0);
                        wb.save(d, null);
                        if (d == wb.activeDocument()) wb.editor.invalidate();
                        return true;
                    }
                }
                return false;
            }
        });
        if (viaEditor == null) throw new Exception("The editor didn't respond; the file was not changed");
        if (!viaEditor) {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            Encoding e = enc != null ? enc : new Encoding();
            String cs = com.disgusty.droid_codology.io.TextFiles.canEncode(text.toCharArray(), text.length(), e.charset) ? e.charset : "UTF-8";
            FileOutputStream os = new FileOutputStream(f);
            try {
                com.disgusty.droid_codology.io.TextFiles.write(os, text.toCharArray(), text.length(), cs, e.bom && cs.startsWith("UTF"), e.eol);
            } finally {
                os.close();
            }
        }
        refreshFiles();
    }

    private void closeIfOpen(final File f) {
        onUi(new Callable<Void>() {
            @Override
            public Void call() {
                for (Document d : new ArrayList<Document>(wb.documents())) {
                    if (sameFile(f, d.file)) {
                        d.markSaved();
                        wb.close(d, null);
                    }
                }
                return null;
            }
        });
    }

    private void refreshFiles() {
        wb.ui.post(new Runnable() {
            @Override
            public void run() {
                if (wb.workspace != null) wb.workspace.invalidateIndex();
                wb.reloadOpenDocumentsFromDisk();
                wb.scheduleScmRefresh();
            }
        });
    }

    /** Undoes an edit / creation / deletion step. */
    void revert(final Step s) {
        if (s.file == null || s.state != DONE) return;
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // Revert only what this step left: later edits (the user's or other steps') would be lost.
                    String now = currentText(s.file);
                    String expected = s.tool.equals("delete_file") ? null : s.after;
                    if (!same(now, expected)) {
                        throw new Exception(I18n.t("the file was changed after this step; undo it in the editor instead"));
                    }
                    if (s.created) {
                        closeIfOpen(s.file);
                        s.file.delete();
                        refreshFiles();
                    } else {
                        applyText(s.file, s.before, s.encoding);
                    }
                    s.state = REVERTED;
                } catch (Exception e) {
                    s.output = I18n.f("Revert failed: {0}", e.getMessage() == null ? e.toString() : e.getMessage());
                }
                publish(s);
            }
        });
    }

    private String command(String cmd, int timeoutSec, final Step s) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
        pb.directory(root());
        pb.redirectErrorStream(true);
        java.util.Map<String, String> env = pb.environment();
        env.put("HOME", new File(wb.activity.getFilesDir(), "home").getAbsolutePath());
        env.put("TMPDIR", wb.activity.getCacheDir().getAbsolutePath());
        env.put("TERM", "dumb");
        final Process p = pb.start();
        p.getOutputStream().close();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final InputStream in = p.getInputStream();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] b = new byte[8192];
                int n;
                try {
                    while ((n = in.read(b)) > 0) {
                        synchronized (out) {
                            if (out.size() < 200000) out.write(b, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        reader.start();
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        Integer code = null;
        while (code == null) {
            try {
                code = p.exitValue();
            } catch (IllegalThreadStateException running) {
                if (stopped || System.currentTimeMillis() > deadline) {
                    p.destroy();
                    reader.join(1000);
                    String partial;
                    synchronized (out) {
                        partial = out.toString("UTF-8");
                    }
                    s.output = cap(partial, 4000);
                    return cap(partial, 12000) + "\n[stopped: " + (stopped ? "cancelled" : "timeout after " + timeoutSec + " s") + "]";
                }
                Thread.sleep(100);
            }
        }
        reader.join(2000);
        String text;
        synchronized (out) {
            text = out.toString("UTF-8");
        }
        s.output = cap(text, 4000) + "\n[exit code " + code + "]";
        refreshFiles();
        return cap(text, 12000) + "\n[exit code " + code + "]";
    }

    private String problems(final String path) {
        return onUi(new Callable<String>() {
            @Override
            public String call() {
                StringBuilder sb = new StringBuilder();
                for (Document d : wb.documents()) {
                    String rel = d.file != null ? relPath(wb, d.file) : d.name;
                    if (!path.isEmpty() && !rel.equals(path)) continue;
                    for (com.disgusty.droid_codology.editor.Diagnostic g : d.allDiagnostics()) {
                        sb.append(rel).append(':').append(g.line + 1).append(':').append(g.col + 1).append(' ')
                                .append(g.severity == 1 ? "error" : g.severity == 2 ? "warning" : "info").append(": ").append(g.message).append('\n');
                    }
                }
                if (sb.length() == 0) {
                    return wb.lsp.supports(wb.activeDocument()) ? "No problems in the open files."
                            : "No problems reported (no language server is running for these files; consider running the build or tests instead).";
                }
                return cap(sb.toString(), 12000);
            }
        });
    }

    private String gitStatus() throws Exception {
        Repo r = Repo.find(root());
        if (r == null) return "Not a Git repository.";
        Repo.Status st = r.status();
        StringBuilder sb = new StringBuilder("On branch ").append(st.branch).append('\n');
        for (Repo.Change c : st.staged) sb.append("staged ").append(c.kind).append(' ').append(c.path).append('\n');
        for (Repo.Change c : st.unstaged) sb.append(c.kind == '?' ? "untracked " : "changed ").append(c.kind).append(' ').append(c.path).append('\n');
        return cap(sb.toString(), 12000);
    }

    private String gitDiff(String path) throws Exception {
        Repo r = Repo.find(root());
        if (r == null) return "Not a Git repository.";
        Repo.Status st = r.status();
        StringBuilder sb = new StringBuilder();
        com.disgusty.droid_codology.git.GitIndex ix = r.index();
        for (Repo.Change c : st.unstaged) {
            if (c.kind == '?' || (!path.isEmpty() && !c.path.equals(path))) continue;
            com.disgusty.droid_codology.git.GitIndex.Entry e = ix.entries.get(c.path);
            String before = e == null ? "" : new String(r.blob(e.id), "UTF-8");
            File f = r.file(c.path);
            String after = f.isFile() ? new String(ExtensionHost.readBytes(f), "UTF-8") : "";
            sb.append(Diff.unified("a/" + c.path, "b/" + c.path, before, after, 3));
        }
        return sb.length() == 0 ? "No unstaged changes." : cap(sb.toString(), 16000);
    }

    /** Runs {@code c} on the UI thread and waits for it; null if it failed or didn't run within 10 s (then it won't run later). */
    private <T> T onUi(final Callable<T> c) {
        final Object[] box = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean gaveUp = new java.util.concurrent.atomic.AtomicBoolean();
        wb.ui.post(new Runnable() {
            @Override
            public void run() {
                if (gaveUp.get()) return;
                try {
                    box[0] = c.call();
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) gaveUp.set(true);
        } catch (InterruptedException ignored) {
            gaveUp.set(true);
        }
        @SuppressWarnings("unchecked")
        T t = (T) box[0];
        return t;
    }

}
