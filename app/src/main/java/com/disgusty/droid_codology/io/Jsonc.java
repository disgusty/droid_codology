package com.disgusty.droid_codology.io;

/** JSON with comments (VS Code's .code-snippets, settings.json, package.json-ish files) → strict JSON. */
public final class Jsonc {
    private Jsonc() {
    }

    /** Removes // and block comments outside strings, and trailing commas before } or ]. */
    public static String clean(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        boolean inStr = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(s.charAt(++i));
                } else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
                out.append(c);
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 1;
            } else if (c == ',') {
                // Drop a comma whose next significant char closes the container.
                int k = i + 1;
                while (k < n) {
                    char d = s.charAt(k);
                    if (Character.isWhitespace(d)) {
                        k++;
                        continue;
                    }
                    if (d == '/' && k + 1 < n && s.charAt(k + 1) == '/') {
                        while (k < n && s.charAt(k) != '\n') k++;
                        continue;
                    }
                    if (d == '/' && k + 1 < n && s.charAt(k + 1) == '*') {
                        int e = s.indexOf("*/", k + 2);
                        k = e < 0 ? n : e + 2;
                        continue;
                    }
                    break;
                }
                if (k < n && (s.charAt(k) == '}' || s.charAt(k) == ']')) continue;
                out.append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
