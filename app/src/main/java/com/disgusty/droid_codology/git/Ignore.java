package com.disgusty.droid_codology.git;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/** .gitignore rules (root, nested directories and .git/info/exclude). */
final class Ignore {
    private static final class Rule {
        final Pattern re;
        final boolean negate, dirOnly;
        /** Directory the .gitignore lives in, relative to the work tree ("" for the root). */
        final String base;

        Rule(Pattern re, boolean negate, boolean dirOnly, String base) {
            this.re = re;
            this.negate = negate;
            this.dirOnly = dirOnly;
            this.base = base;
        }
    }

    private final File work;
    private final HashMap<String, List<Rule>> byDir = new HashMap<String, List<Rule>>();
    private final List<Rule> global = new ArrayList<Rule>();

    Ignore(File work, File gitDir) {
        this.work = work;
        load(new File(gitDir, "info/exclude"), "", global);
    }

    private void load(File f, String base, List<Rule> into) {
        if (!f.isFile()) return;
        String text;
        try {
            text = new String(ObjectDb.readFile(f), GitObject.UTF8);
        } catch (IOException e) {
            return;
        }
        for (String line : text.split("\r?\n")) {
            String l = line;
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (!l.endsWith("\\ ")) l = l.replaceAll("\\s+$", "");
            if (l.isEmpty()) continue;
            boolean neg = false;
            if (l.startsWith("!")) {
                neg = true;
                l = l.substring(1);
            } else if (l.startsWith("\\!") || l.startsWith("\\#")) {
                l = l.substring(1);
            }
            boolean dirOnly = l.endsWith("/");
            if (dirOnly) l = l.substring(0, l.length() - 1);
            boolean anchored = l.startsWith("/") || l.contains("/");
            if (l.startsWith("/")) l = l.substring(1);
            String rx = glob(l);
            rx = anchored ? "^" + rx + "$" : "^(?:.*/)?" + rx + "$";
            try {
                into.add(new Rule(Pattern.compile(rx), neg, dirOnly, base));
            } catch (java.util.regex.PatternSyntaxException e) {
                // A pattern we can't translate ("[!]", "[]a]"...) is skipped, not fatal to the whole status.
            }
        }
    }

    static String glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                    boolean slashAfter = i + 2 < g.length() && g.charAt(i + 2) == '/';
                    sb.append(slashAfter ? "(?:.*/)?" : ".*");
                    i += slashAfter ? 2 : 1;
                } else sb.append("[^/]*");
            } else if (c == '?') sb.append("[^/]");
            else if (c == '[') {
                int j = g.indexOf(']', i + 1);
                if (j < 0) sb.append("\\[");
                else {
                    String cls = g.substring(i + 1, j).replace("\\", "\\\\");
                    if (cls.startsWith("!")) cls = "^" + cls.substring(1);
                    sb.append('[').append(cls).append(']');
                    i = j;
                }
            } else if (c == '\\' && i + 1 < g.length()) {
                sb.append(Pattern.quote(String.valueOf(g.charAt(++i))));
            } else if ("().+^$|{}".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        return sb.toString();
    }

    private List<Rule> rulesFor(String dir) {
        List<Rule> r = byDir.get(dir);
        if (r == null) {
            r = new ArrayList<Rule>();
            load(new File(dir.isEmpty() ? work : new File(work, dir), ".gitignore"), dir, r);
            byDir.put(dir, r);
        }
        return r;
    }

    /** Whether {@code path} (relative, '/' separated) is ignored. Parent directories are checked by the caller's walk. */
    boolean ignored(String path, boolean isDir) {
        if (path.equals(".git") || path.endsWith("/.git")) return true;
        Boolean result = null;
        result = match(global, path, isDir, result);
        // Deeper .gitignore files override shallower ones.
        String dir = "";
        result = match(rulesFor(""), path, isDir, result);
        int slash = -1;
        while ((slash = path.indexOf('/', slash + 1)) >= 0) {
            dir = path.substring(0, slash);
            result = match(rulesFor(dir), path.substring(slash + 1), isDir, result);
        }
        return result != null && result;
    }

    private static Boolean match(List<Rule> rules, String rel, boolean isDir, Boolean current) {
        Boolean r = current;
        for (Rule rule : rules) {
            if (rule.dirOnly && !isDir) continue;
            if (rule.re.matcher(rel).matches()) r = !rule.negate;
        }
        return r;
    }
}
