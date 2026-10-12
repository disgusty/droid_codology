package com.disgusty.droid_codology.ext;

import android.content.Context;

import com.disgusty.droid_codology.io.Jsonc;
import com.disgusty.droid_codology.io.TextFiles;
import com.disgusty.droid_codology.lang.Language;
import com.disgusty.droid_codology.lang.Languages;
import com.disgusty.droid_codology.lang.Lexer;
import com.disgusty.droid_codology.lang.Snippets;
import com.disgusty.droid_codology.lang.textmate.Grammar;
import com.disgusty.droid_codology.lang.textmate.GrammarRegistry;
import com.disgusty.droid_codology.lang.textmate.TextMateLexer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs VS Code extensions (.vsix) and loads their declarative contributions: languages and
 * language configuration, TextMate grammars, snippets and color themes. Code contributions
 * ("main"/"browser") are run by {@link JsHost} when the extension's API usage is supported.
 */
public final class ExtensionManager {
    private final File root;
    /** Replaced as a whole by loadAll() (which may run on a background thread), never changed in place. */
    private volatile List<Extension> installed = Collections.emptyList();
    private final HashSet<String> disabled = new HashSet<String>();
    /** Languages that came from extensions (built-in ones keep their fast lexers). */
    private final HashMap<String, String> languageScopes = new HashMap<String, String>();

    public ExtensionManager(Context ctx) {
        root = new File(ctx.getFilesDir(), "extensions");
        if (!root.exists()) root.mkdirs();
    }

    public List<Extension> installed() {
        return installed;
    }

    public Extension find(String id) {
        for (Extension e : installed) if (e.id.equalsIgnoreCase(id)) return e;
        return null;
    }

    // ------------------------------------------------------------------ loading

    /** Reads every installed extension and registers what the enabled ones contribute. */
    public synchronized void loadAll() {
        ArrayList<Extension> fresh = new ArrayList<Extension>();
        disabled.clear();
        File state = new File(root, "disabled.txt");
        if (state.exists()) {
            try {
                for (String l : read(state).split("\n")) if (!l.trim().isEmpty()) disabled.add(l.trim());
            } catch (IOException ignored) {
            }
        }
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            // ".installing" and other dot folders are work in progress, not extensions.
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            Extension e = load(d);
            if (e == null) continue;
            e.enabled = !disabled.contains(e.id);
            fresh.add(e);
        }
        Collections.sort(fresh, new java.util.Comparator<Extension>() {
            @Override
            public int compare(Extension a, Extension b) {
                return a.displayName.compareToIgnoreCase(b.displayName);
            }
        });
        installed = Collections.unmodifiableList(fresh);
        for (Extension e : fresh) if (e.enabled) register(e);
    }

    private Extension load(File dir) {
        File pkg = new File(dir, "package.json");
        if (!pkg.exists()) return null;
        Extension ext;
        try {
            String text = read(pkg);
            File nls = new File(dir, "package.nls.json");
            if (nls.exists()) text = localize(text, new JSONObject(Jsonc.clean(read(nls))));
            ext = new Extension(dir, new JSONObject(Jsonc.clean(text)));
        } catch (Exception e) {
            return null;
        }
        readNativeInfo(ext);
        return ext;
    }

    // ------------------------------------------------------------------ desktop-only packages

    /** Written at install: the package's target platform and how many desktop programs were left out. */
    static final String NATIVE_INFO = ".droid_codology.json";

    private static void writeNativeInfo(File dir, String target, int nativeFiles) {
        try {
            JSONObject o = new JSONObject();
            o.put("targetPlatform", target == null ? JSONObject.NULL : target);
            o.put("nativeFiles", nativeFiles);
            OutputStream os = new FileOutputStream(new File(dir, NATIVE_INFO));
            try {
                os.write(o.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (Exception ignored) {
            // only a hint; without it the folder is scanned again next time
        }
    }

    private static void readNativeInfo(Extension ext) {
        File f = new File(ext.dir, NATIVE_INFO);
        if (f.exists()) {
            try {
                JSONObject o = new JSONObject(read(f));
                ext.targetPlatform = o.isNull("targetPlatform") ? null : o.optString("targetPlatform", null);
                ext.nativeFiles = o.optInt("nativeFiles", 0);
                return;
            } catch (Exception ignored) {
            }
        }
        // Installed before this was recorded: look for desktop programs once.
        ext.nativeFiles = countNative(ext.dir, new int[]{5000});
        writeNativeInfo(ext.dir, null, ext.nativeFiles);
    }

    private static int countNative(File dir, int[] budget) {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        byte[] head = new byte[4];
        for (File f : fs) {
            if (--budget[0] < 0) break;
            if (f.isDirectory()) {
                n += countNative(f, budget);
                continue;
            }
            int got = 0;
            // Programs are big; reading the first bytes of every small file isn't worth it.
            if (f.length() >= 16 * 1024) {
                try {
                    InputStream in = new FileInputStream(f);
                    try {
                        got = readAtLeast(in, head, 4);
                    } finally {
                        in.close();
                    }
                } catch (IOException ignored) {
                }
            }
            if (isNative(f.getName(), head, got)) n++;
        }
        return n;
    }

    /** "TargetPlatform" of a .vsix manifest; null for universal and web packages. */
    static String targetPlatform(String vsixManifest) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("TargetPlatform\\s*=\\s*\"([^\"]*)\"").matcher(vsixManifest);
        if (!m.find()) return null;
        String t = m.group(1).trim();
        return t.isEmpty() || t.equals("universal") || t.equals("web") ? null : t;
    }

    /** A desktop program or Node add-on, by its name or its first bytes (ELF, Mach-O, Windows PE). */
    static boolean isNative(String name, byte[] head, int n) {
        String l = name.toLowerCase(java.util.Locale.ROOT);
        int slash = l.lastIndexOf('/');
        String base = l.substring(slash + 1);
        if (base.endsWith(".node") || base.endsWith(".exe") || base.endsWith(".dll") || base.endsWith(".dylib")
                || base.matches(".*\\.so(\\.\\d+)*")) return true;
        if (n < 4) return false;
        int m = (head[0] & 0xFF) << 24 | (head[1] & 0xFF) << 16 | (head[2] & 0xFF) << 8 | (head[3] & 0xFF);
        if (m == 0x7F454C46) return true; // ELF (Linux)
        if (m == 0xFEEDFACE || m == 0xFEEDFACF || m == 0xCEFAEDFE || m == 0xCFFAEDFE) return true; // Mach-O (macOS)
        if (m == 0xCAFEBABE && !base.endsWith(".class")) return true; // universal Mach-O
        // Windows programs without an .exe name: "MZ" plus no extension at all.
        return head[0] == 'M' && head[1] == 'Z' && base.indexOf('.') < 0;
    }

    private static int readAtLeast(InputStream in, byte[] buf, int want) throws IOException {
        int got = 0;
        while (got < want) {
            int r = in.read(buf, got, buf.length - got);
            if (r <= 0) break;
            got += r;
        }
        return got;
    }

    private static String readSome(InputStream in, int max) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            if (bo.size() < max) bo.write(b, 0, Math.min(n, max - bo.size()));
        }
        return bo.toString("UTF-8");
    }

    /** Replaces "%key%" string values with their package.nls.json translations. */
    private static String localize(String manifest, JSONObject nls) {
        Iterator<String> keys = nls.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = nls.opt(k);
            String value = v instanceof JSONObject ? ((JSONObject) v).optString("message", "") : String.valueOf(v);
            manifest = manifest.replace("\"%" + k + "%\"", JSONObject.quote(value));
        }
        return manifest;
    }

    private void register(Extension e) {
        JSONObject c = e.contributes();
        // Grammars first, so languages can find their scope.
        JSONArray grammars = c.optJSONArray("grammars");
        if (grammars != null) {
            for (int i = 0; i < grammars.length(); i++) {
                JSONObject g = grammars.optJSONObject(i);
                if (g == null) continue;
                String scope = g.optString("scopeName", null), path = g.optString("path", null);
                if (scope == null || path == null) continue;
                final File f = e.file(path);
                if (f == null || !f.exists()) continue;
                GrammarRegistry.SHARED.register(scope, new GrammarRegistry.Source() {
                    @Override
                    public String load() {
                        try {
                            String s = read(f);
                            return f.getName().endsWith(".json") ? s : PlistGrammar.toJson(s);
                        } catch (Exception ex) {
                            return null;
                        }
                    }
                });
                String lang = g.optString("language", null);
                if (lang != null && !lang.isEmpty()) languageScopes.put(lang, scope);
            }
        }
        JSONArray langs = c.optJSONArray("languages");
        if (langs != null) {
            for (int i = 0; i < langs.length(); i++) {
                JSONObject l = langs.optJSONObject(i);
                if (l == null) continue;
                registerLanguage(e, l);
            }
        }
        JSONArray snippets = c.optJSONArray("snippets");
        if (snippets != null) {
            for (int i = 0; i < snippets.length(); i++) {
                JSONObject s = snippets.optJSONObject(i);
                if (s == null) continue;
                File f = e.file(s.optString("path", ""));
                String lang = s.optString("language", null);
                if (f == null || !f.exists() || lang == null) continue;
                try {
                    Snippets.add(lang, read(f));
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void registerLanguage(Extension e, JSONObject l) {
        final String id = l.optString("id", null);
        if (id == null) return;
        Language existing = Languages.byId(id);
        StringBuilder exts = new StringBuilder(), names = new StringBuilder();
        JSONArray ex = l.optJSONArray("extensions");
        if (ex != null) for (int k = 0; k < ex.length(); k++) exts.append(k > 0 ? " " : "").append(ex.optString(k).replaceFirst("^\\.", ""));
        JSONArray fn = l.optJSONArray("filenames");
        if (fn != null) for (int k = 0; k < fn.length(); k++) names.append(k > 0 ? " " : "").append(fn.optString(k));
        if (existing != null && existing.lexer() != Lexer.PLAIN) return; // keep the built-in fast lexer
        JSONArray aliases = l.optJSONArray("aliases");
        String name = aliases != null && aliases.length() > 0 ? aliases.optString(0) : id;
        if (existing != null && exts.length() == 0) for (String x : existing.extensions) exts.append(x).append(' ');
        Language lang = new Language(id, name, exts.toString().trim(), names.toString().trim(), new Language.Factory() {
            @Override
            public Lexer create() {
                String scope = languageScopes.get(id);
                Grammar g = scope == null ? null : GrammarRegistry.SHARED.grammar(scope);
                return g == null ? Lexer.PLAIN : new TextMateLexer(g);
            }
        });
        // language-configuration.json: comments and bracket pairs.
        File conf = e.file(l.optString("configuration", ""));
        if (conf != null && conf.isFile()) {
            try {
                JSONObject cfg = new JSONObject(Jsonc.clean(read(conf)));
                JSONObject comments = cfg.optJSONObject("comments");
                if (comments != null) {
                    JSONArray block = comments.optJSONArray("blockComment");
                    lang.comments(comments.optString("lineComment", null), block != null ? block.optString(0) : null, block != null ? block.optString(1) : null);
                }
                JSONArray pairs = cfg.optJSONArray("autoClosingPairs");
                if (pairs != null) {
                    StringBuilder open = new StringBuilder(), close = new StringBuilder();
                    for (int k = 0; k < pairs.length(); k++) {
                        Object p = pairs.opt(k);
                        String o = null, cl = null;
                        if (p instanceof JSONArray) {
                            o = ((JSONArray) p).optString(0);
                            cl = ((JSONArray) p).optString(1);
                        } else if (p instanceof JSONObject) {
                            o = ((JSONObject) p).optString("open");
                            cl = ((JSONObject) p).optString("close");
                        }
                        if (o != null && cl != null && o.length() == 1 && cl.length() == 1) {
                            open.append(o);
                            close.append(cl);
                        }
                    }
                    if (open.length() > 0) lang.pairs(open.toString(), close.toString());
                }
            } catch (Exception ignored) {
            }
        }
        Languages.register(lang);
    }

    // ------------------------------------------------------------------ install / remove

    /** Installs a .vsix (a zip whose "extension/" folder is the extension). Returns the installed extension. */
    public synchronized Extension install(File vsix) throws IOException {
        return install(vsix, false);
    }

    /**
     * @param keepLinuxPrograms keep Linux programs (ELF): the package is the Alpine Linux build of a
     *                          desktop extension, for the Node.js host, where they can run
     */
    public synchronized Extension install(File vsix, boolean keepLinuxPrograms) throws IOException {
        File tmp = new File(root, ".installing");
        deleteRecursive(tmp);
        tmp.mkdirs();
        String tmpPath = tmp.getCanonicalPath();
        ZipInputStream zin = new ZipInputStream(new FileInputStream(vsix));
        int files = 0, nativeFiles = 0;
        String target = null;
        boolean extracted = false;
        try {
            ZipEntry ze;
            byte[] buf = new byte[32 * 1024];
            while ((ze = zin.getNextEntry()) != null) {
                String name = ze.getName().replace('\\', '/');
                if (name.equals("extension.vsixmanifest")) {
                    target = targetPlatform(readSome(zin, 256 * 1024));
                    continue;
                }
                if (!name.startsWith("extension/")) continue;
                String rel = name.substring("extension/".length());
                if (rel.isEmpty()) continue;
                File out = new File(tmp, rel);
                // Zip-slip guard: entries must stay inside the target folder.
                if (!out.getCanonicalPath().startsWith(tmpPath + File.separator)) throw new IOException("bad entry " + name);
                if (ze.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                // Desktop programs (Claude Code's is 250 MB) can never run on Android: leave them out.
                int head = readAtLeast(zin, buf, 4);
                boolean elf = head >= 4 && buf[0] == 0x7F && buf[1] == 'E' && buf[2] == 'L' && buf[3] == 'F';
                if (isNative(rel, buf, head) && !(keepLinuxPrograms && (elf || rel.endsWith(".node")))) {
                    nativeFiles++;
                    while (zin.read(buf) > 0) {
                        // skip the rest of the entry
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                OutputStream os = new FileOutputStream(out);
                try {
                    if (head > 0) os.write(buf, 0, head);
                    int n;
                    while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    os.close();
                }
                // zip keeps no permissions: Linux programs must be runnable
                if (keepLinuxPrograms && elf) com.disgusty.droid_codology.term.Pty.chmod(out.getPath(), 0755);
                if (++files > 20000) throw new IOException("too many files");
            }
            writeNativeInfo(tmp, target, nativeFiles);
            extracted = true;
        } finally {
            zin.close();
            // A half-extracted folder must not stay behind (it would load as an extension).
            if (!extracted) deleteRecursive(tmp);
        }
        Extension probe = load(tmp);
        if (probe == null) {
            deleteRecursive(tmp);
            throw new IOException("Not a VS Code extension (no package.json)");
        }
        // The id comes from the package: keep it to plain characters so the folder stays here.
        File dest = new File(root, (probe.id + "-" + probe.version).replaceAll("[^A-Za-z0-9._-]", "_"));
        if (!dest.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) {
            deleteRecursive(tmp);
            throw new IOException("Bad extension name " + probe.id);
        }
        // Replace older versions of the same extension: by the id in each folder's package.json,
        // not by name prefix (installing golang.go must not delete golang.go-nightly).
        File[] dirs = root.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory() || d.equals(tmp) || d.getName().startsWith(".")) continue;
                Extension other = load(d);
                if (other != null && other.id.equalsIgnoreCase(probe.id)) deleteRecursive(d);
            }
        }
        if (!tmp.renameTo(dest)) {
            deleteRecursive(tmp);
            throw new IOException("Cannot install " + probe.id);
        }
        loadAll();
        return find(probe.id);
    }

    public synchronized void uninstall(Extension e) {
        deleteRecursive(e.dir);
        disabled.remove(e.id);
        saveDisabled();
        loadAll();
    }

    public synchronized void setEnabled(Extension e, boolean on) {
        if (on) disabled.remove(e.id);
        else disabled.add(e.id);
        saveDisabled();
        loadAll();
    }

    private void saveDisabled() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String d : disabled) sb.append(d).append('\n');
            OutputStream os = new FileOutputStream(new File(root, "disabled.txt"));
            try {
                os.write(sb.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** Every color theme offered by enabled extensions. */
    public List<Object[]> themes() {
        ArrayList<Object[]> out = new ArrayList<Object[]>();
        for (Extension e : installed) {
            if (!e.enabled) continue;
            for (Extension.Theme t : e.themes()) out.add(new Object[]{e, t});
        }
        return out;
    }

    static String read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            TextFiles.Loaded l = TextFiles.read(in, "UTF-8");
            String s = new String(l.chars, 0, l.length);
            return s.startsWith("\uFEFF") ? s.substring(1) : s;
        } finally {
            in.close();
        }
    }

    static void deleteRecursive(File f) {
        if (f.isDirectory() && !com.disgusty.droid_codology.work.Workspace.isSymlink(f)) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        f.delete();
    }
}
