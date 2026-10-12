package com.disgusty.droid_codology.lang;

/**
 * Built-in formatters for when no language server is connected: JSON / JSONC (keeps comments and
 * key order, like VS Code's JSON formatter).
 */
public final class Formatter {
    private Formatter() {
    }

    public static boolean supports(String languageId) {
        return languageId.equals("json");
    }

    /** Formatted text, or null if the language has no built-in formatter. */
    public static String format(String languageId, String text, String indentUnit) {
        if (languageId.equals("json")) return json(text, indentUnit);
        return null;
    }

    static String json(String s, String unit) {
        StringBuilder out = new StringBuilder(s.length() + s.length() / 4);
        int depth = 0, n = s.length();
        boolean lineEmpty = true;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case ' ':
                case '\t':
                case '\r':
                case '\n':
                    continue;
                case '"': {
                    int j = i + 1;
                    while (j < n && s.charAt(j) != '"') j += s.charAt(j) == '\\' ? 2 : 1;
                    out.append(s, i, Math.min(n, j + 1));
                    i = j;
                    lineEmpty = false;
                    continue;
                }
                case '/':
                    if (i + 1 < n && s.charAt(i + 1) == '/') {
                        int e = s.indexOf('\n', i);
                        if (e < 0) e = n;
                        if (!lineEmpty) out.append(' ');
                        out.append(s, i, e);
                        newline(out, depth, unit);
                        lineEmpty = true;
                        i = e;
                        continue;
                    }
                    if (i + 1 < n && s.charAt(i + 1) == '*') {
                        int e = s.indexOf("*/", i + 2);
                        e = e < 0 ? n : e + 2;
                        if (!lineEmpty) out.append(' ');
                        out.append(s, i, e);
                        i = e - 1;
                        lineEmpty = false;
                        continue;
                    }
                    out.append(c);
                    continue;
                case '{':
                case '[': {
                    // Empty containers stay on one line: {} []
                    int k = i + 1;
                    while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                    char close = c == '{' ? '}' : ']';
                    if (k < n && s.charAt(k) == close) {
                        out.append(c).append(close);
                        i = k;
                        lineEmpty = false;
                        continue;
                    }
                    out.append(c);
                    depth++;
                    newline(out, depth, unit);
                    lineEmpty = true;
                    continue;
                }
                case '}':
                case ']':
                    depth = Math.max(0, depth - 1);
                    trimTrailing(out);
                    if (!lineEmpty) newline(out, depth, unit);
                    else {
                        // Re-indent the blank line we are on.
                        int ls = out.lastIndexOf("\n");
                        out.setLength(ls + 1);
                        for (int d = 0; d < depth; d++) out.append(unit);
                    }
                    out.append(c);
                    lineEmpty = false;
                    continue;
                case ',':
                    out.append(c);
                    newline(out, depth, unit);
                    lineEmpty = true;
                    continue;
                case ':':
                    out.append(": ");
                    continue;
                default:
                    out.append(c);
                    lineEmpty = false;
            }
        }
        String r = out.toString().replaceAll("[ \\t]+\\n", "\n");
        return r.endsWith("\n") ? r : r + "\n";
    }

    private static void newline(StringBuilder out, int depth, String unit) {
        trimTrailing(out);
        out.append('\n');
        for (int d = 0; d < depth; d++) out.append(unit);
    }

    private static void trimTrailing(StringBuilder out) {
        int l = out.length();
        while (l > 0 && (out.charAt(l - 1) == ' ' || out.charAt(l - 1) == '\t')) l--;
        out.setLength(l);
    }
}
