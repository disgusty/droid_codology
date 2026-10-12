package com.disgusty.droid_codology.git;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** .git/config, edited in place so comments and unknown settings survive. */
public final class GitConfig {
    private final File file;
    private final ArrayList<String> lines = new ArrayList<String>();

    public GitConfig(File file) throws IOException {
        this.file = file;
        if (file.isFile()) {
            for (String l : new String(ObjectDb.readFile(file), GitObject.UTF8).split("\r?\n", -1)) lines.add(l);
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        }
    }

    private static String header(String section, String sub) {
        return sub == null ? "[" + section + "]" : "[" + section + " \"" + sub.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    }

    private static boolean isHeader(String l, String section, String sub) {
        String t = l.trim();
        if (!t.startsWith("[")) return false;
        int close = t.indexOf(']');
        if (close < 0) return false;
        String inner = t.substring(1, close).trim();
        String sec, s = null;
        int q = inner.indexOf('"');
        if (q >= 0) {
            sec = inner.substring(0, q).trim();
            s = inner.substring(q + 1, inner.lastIndexOf('"')).replace("\\\"", "\"").replace("\\\\", "\\");
        } else {
            int dot = inner.indexOf('.');
            sec = dot < 0 ? inner : inner.substring(0, dot);
            if (dot >= 0) s = inner.substring(dot + 1);
        }
        if (!sec.equalsIgnoreCase(section)) return false;
        return sub == null ? s == null : sub.equals(s);
    }

    /** All values of section(.sub).key, in order. */
    public List<String> getAll(String section, String sub, String key) {
        ArrayList<String> out = new ArrayList<String>();
        boolean in = false;
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("[")) {
                in = isHeader(l, section, sub);
                continue;
            }
            if (!in || t.isEmpty() || t.startsWith("#") || t.startsWith(";")) continue;
            int eq = t.indexOf('=');
            String k = (eq < 0 ? t : t.substring(0, eq)).trim();
            if (!k.equalsIgnoreCase(key)) continue;
            String v = eq < 0 ? "true" : unquote(t.substring(eq + 1).trim());
            out.add(v);
        }
        return out;
    }

    public String get(String section, String sub, String key) {
        List<String> all = getAll(section, sub, key);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static String unquote(String v) {
        int hash = -1;
        boolean q = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"') q = !q;
            else if ((c == '#' || c == ';') && !q) {
                hash = i;
                break;
            }
        }
        if (hash >= 0) v = v.substring(0, hash).trim();
        return v.replace("\"", "").replace("\\n", "\n").replace("\\t", "\t");
    }

    public void set(String section, String sub, String key, String value) {
        int sectionAt = -1;
        boolean in = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i), t = l.trim();
            if (t.startsWith("[")) {
                in = isHeader(l, section, sub);
                if (in) sectionAt = i;
                continue;
            }
            if (!in) continue;
            int eq = t.indexOf('=');
            String k = (eq < 0 ? t : t.substring(0, eq)).trim();
            if (k.equalsIgnoreCase(key)) {
                if (value == null) lines.remove(i);
                else lines.set(i, "\t" + key + " = " + quote(value));
                return;
            }
        }
        if (value == null) return;
        if (sectionAt < 0) {
            lines.add(header(section, sub));
            lines.add("\t" + key + " = " + quote(value));
            return;
        }
        int at = sectionAt + 1;
        while (at < lines.size() && !lines.get(at).trim().startsWith("[")) at++;
        lines.add(at, "\t" + key + " = " + quote(value));
    }

    public void removeSection(String section, String sub) {
        for (int i = 0; i < lines.size(); i++) {
            if (!isHeader(lines.get(i), section, sub)) continue;
            lines.remove(i);
            while (i < lines.size() && !lines.get(i).trim().startsWith("[")) lines.remove(i);
            i--;
        }
    }

    /** Subsection names of a section (remote names, branch names). */
    public List<String> subsections(String section) {
        ArrayList<String> out = new ArrayList<String>();
        for (String l : lines) {
            String t = l.trim();
            if (!t.startsWith("[") || !t.toLowerCase(Locale.ROOT).startsWith("[" + section.toLowerCase(Locale.ROOT))) continue;
            int q = t.indexOf('"');
            if (q < 0) continue;
            String s = t.substring(q + 1, t.lastIndexOf('"'));
            if (isHeader(l, section, s) && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private static String quote(String v) {
        boolean needs = v.startsWith(" ") || v.endsWith(" ") || v.contains("#") || v.contains(";") || v.contains("\"");
        String e = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
        return needs ? "\"" + e + "\"" : e;
    }

    public void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        FileOutputStream os = new FileOutputStream(file);
        try {
            os.write(sb.toString().getBytes(GitObject.UTF8));
        } finally {
            os.close();
        }
    }
}
