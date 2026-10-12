package com.disgusty.droid_codology.work;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Find options and matching (literal / whole word / regex, case sensitivity), plus VS Code-style replace. */
public final class TextSearch {
    public String query = "";
    public boolean matchCase, wholeWord, regex, preserveCase;

    public static final int MAX_MATCHES = 19999;

    /** Compiled form; null when the query is empty. Throws PatternSyntaxException for a bad regex. */
    public Pattern compile() {
        if (query.isEmpty()) return null;
        String p = regex ? query : Pattern.quote(query);
        if (wholeWord) p = "(?<![\\p{L}\\p{N}_])(?:" + p + ")(?![\\p{L}\\p{N}_])";
        int flags = Pattern.MULTILINE;
        if (!matchCase) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(p, flags);
    }

    public TextSearch copy() {
        TextSearch t = new TextSearch();
        t.query = query;
        t.matchCase = matchCase;
        t.wholeWord = wholeWord;
        t.regex = regex;
        t.preserveCase = preserveCase;
        return t;
    }

    /** Matches in {@code text}: {starts[], ends[]}; empty arrays if none. */
    public static int[][] findAll(Pattern p, CharSequence text, int max) {
        int[] s = new int[64], e = new int[64];
        int n = 0;
        if (p != null) {
            Matcher m = p.matcher(text);
            int from = 0;
            while (n < max && from <= text.length() && m.find(from)) {
                int a = m.start(), b = m.end();
                if (n == s.length) {
                    s = Arrays.copyOf(s, n * 2);
                    e = Arrays.copyOf(e, n * 2);
                }
                s[n] = a;
                e[n] = b;
                n++;
                from = b == a ? b + 1 : b;
                if (Thread.currentThread().isInterrupted()) break;
            }
        }
        return new int[][]{Arrays.copyOf(s, n), Arrays.copyOf(e, n)};
    }

    /** The replacement for one match: $1-style groups for regex, case kept when preserveCase. */
    public String replacement(Pattern p, CharSequence text, int start, int end, String replace) {
        String matched = text.subSequence(start, end).toString();
        String out;
        if (regex) {
            // Matched again in its context (lookarounds and anchors see the text around it), so
            // groups like $1 are filled exactly as the search found them.
            Matcher m = p.matcher(text);
            m.region(start, text.length());
            m.useTransparentBounds(true);
            m.useAnchoringBounds(false);
            out = m.lookingAt() && m.end() == end ? expand(m, unescape(replace)) : replace;
        } else out = replace;
        if (preserveCase && !matched.isEmpty()) {
            if (matched.equals(matched.toUpperCase(Locale.ROOT))) out = out.toUpperCase(Locale.ROOT);
            else if (Character.isUpperCase(matched.charAt(0)) && out.length() > 0) out = Character.toUpperCase(out.charAt(0)) + out.substring(1);
        }
        return out;
    }

    /** $0..$99 and \$ in a replacement, from the groups of {@code m}. */
    static String expand(Matcher m, String r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '\\' && i + 1 < r.length()) {
                sb.append(r.charAt(++i));
            } else if (c == '$' && i + 1 < r.length() && Character.isDigit(r.charAt(i + 1))) {
                int g = r.charAt(++i) - '0';
                // Two digits when that group exists ($10), else one ($1 followed by "0").
                if (i + 1 < r.length() && Character.isDigit(r.charAt(i + 1)) && g * 10 + (r.charAt(i + 1) - '0') <= m.groupCount()) {
                    g = g * 10 + (r.charAt(++i) - '0');
                }
                if (g <= m.groupCount() && m.group(g) != null) sb.append(m.group(g));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Lets "\n" and "\t" in a regex replacement mean newline / tab, as in VS Code. */
    private static String unescape(String r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '\\' && i + 1 < r.length()) {
                char n = r.charAt(i + 1);
                if (n == 'n') {
                    sb.append('\n');
                    i++;
                    continue;
                }
                if (n == 't') {
                    sb.append('\t');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static boolean isValid(TextSearch t) {
        try {
            t.compile();
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
