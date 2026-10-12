package com.disgusty.droid_codology.lang.textmate;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Translates Oniguruma regular expressions (as used by TextMate grammars) to java.util.regex,
 * which on Android is ICU. Handles the differences that matter in real grammars: \h / \H (hex
 * digits), POSIX bracket classes, literal '{', a leading ']' in classes, \x{...} and \z.
 */
public final class OnigRegex {
    private OnigRegex() {
    }

    private static final java.util.HashMap<String, Pattern> COMPILED = new java.util.HashMap<String, Pattern>();
    private static final Pattern INVALID = Pattern.compile("(?!)");

    /**
     * Compiles, or returns null for patterns the platform engine can't handle (the rule is skipped).
     * Identical sources share one Pattern, so the tokenizer's per-pattern cache covers repeats.
     */
    public static synchronized Pattern compile(String onig) {
        Pattern p = COMPILED.get(onig);
        if (p != null) return p == INVALID ? null : p;
        p = compileNew(onig);
        COMPILED.put(onig, p == null ? INVALID : p);
        return p;
    }

    private static Pattern compileNew(String onig) {
        try {
            return Pattern.compile(translate(onig));
        } catch (PatternSyntaxException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String translate(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        int n = s.length();
        boolean inClass = false;
        int classStart = -1;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char d = s.charAt(i + 1);
                if (d == 'h') {
                    out.append(inClass ? "0-9a-fA-F" : "[0-9a-fA-F]");
                    i++;
                    continue;
                }
                if (d == 'H') {
                    out.append(inClass ? "\\x00-\\x2F\\x3A-\\x40\\x47-\\x60\\x67-\\x{10FFFF}" : "[^0-9a-fA-F]");
                    i++;
                    continue;
                }
                if (d == 'A' && !inClass) {
                    out.append("\\A");
                    i++;
                    continue;
                }
                // Copy the escape verbatim (including \x{..}, \p{..}, \k<..>).
                out.append(c).append(d);
                i++;
                continue;
            }
            if (inClass) {
                if (c == '[' && i + 1 < n && s.charAt(i + 1) == ':') {
                    int end = s.indexOf(":]", i + 2);
                    if (end > 0) {
                        String name = s.substring(i + 2, end);
                        boolean neg = name.startsWith("^");
                        if (neg) name = name.substring(1);
                        String cls = posix(name);
                        if (cls != null) {
                            if (neg) {
                                // Negated POSIX inside a class: use the negated \P form where it exists.
                                out.append(cls.startsWith("\\p") ? "\\P" + cls.substring(2) : cls.toUpperCase(java.util.Locale.ROOT));
                            } else out.append(cls);
                            i = end + 1;
                            continue;
                        }
                    }
                }
                if (c == '[') {
                    // Nested class (Oniguruma allows it, so does Java): keep it.
                    out.append(c);
                    continue;
                }
                if (c == ']' && i != classStart) {
                    inClass = false;
                    out.append(c);
                    continue;
                }
                if (c == ']') {
                    out.append("\\]");
                    continue;
                }
                out.append(c);
                continue;
            }
            if (c == '[') {
                inClass = true;
                out.append(c);
                classStart = i + 1;
                if (i + 1 < n && s.charAt(i + 1) == '^') {
                    out.append('^');
                    i++;
                    classStart = i + 1;
                }
                continue;
            }
            if (c == '{' && !isQuantifier(s, i)) {
                out.append("\\{");
                continue;
            }
            if (c == '}' && !closesQuantifier(s, i)) {
                out.append("\\}");
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String posix(String name) {
        switch (name) {
            case "alpha": return "\\p{Alpha}";
            case "digit": return "0-9";
            case "alnum": return "\\p{Alnum}";
            case "upper": return "\\p{Upper}";
            case "lower": return "\\p{Lower}";
            case "space": return "\\s";
            case "blank": return " \\t";
            case "punct": return "\\p{Punct}";
            case "xdigit": return "0-9a-fA-F";
            case "word": return "\\w";
            case "cntrl": return "\\p{Cntrl}";
            case "print": return "\\p{Print}";
            case "graph": return "\\p{Graph}";
            case "ascii": return "\\x00-\\x7F";
            default: return null;
        }
    }

    /** "{2}", "{2,}", "{2,5}", "{,5}" after an atom. */
    private static boolean isQuantifier(String s, int i) {
        int j = i + 1, n = s.length();
        boolean digits = false;
        while (j < n && Character.isDigit(s.charAt(j))) {
            j++;
            digits = true;
        }
        if (j < n && s.charAt(j) == ',') {
            j++;
            while (j < n && Character.isDigit(s.charAt(j))) {
                j++;
                digits = true;
            }
        }
        return digits && j < n && s.charAt(j) == '}' && i > 0;
    }

    private static boolean closesQuantifier(String s, int i) {
        int j = i - 1;
        while (j >= 0 && (Character.isDigit(s.charAt(j)) || s.charAt(j) == ',')) j--;
        return j >= 0 && j < i - 1 && s.charAt(j) == '{' && isQuantifier(s, j);
    }

    /** Escapes text for literal use (back-references substituted into end patterns). */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
