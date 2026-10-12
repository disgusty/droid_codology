package com.disgusty.droid_codology.lang;

/** Markdown: headings, emphasis, inline code, fenced code blocks, links, lists, quotes. */
public final class MarkdownLexer implements Lexer {
    static final int M_TEXT = 0, M_FENCE = 1, M_HTMLCOMMENT = 2;

    @Override
    public int lex(char[] s, int len, int state, Tokens out) {
        int mode = state & 0xFF;
        int i = 0;
        while (i < len && (s[i] == ' ' || s[i] == '\t')) i++;
        if (mode == M_FENCE) {
            if (i + 2 < len + 0 && startsFence(s, len, i)) {
                out.add(0, Tok.PUNCT);
                return M_TEXT;
            }
            out.add(0, Tok.CODE);
            return M_FENCE;
        }
        if (mode == M_HTMLCOMMENT) {
            out.add(0, Tok.COMMENT);
            for (int k = 0; k + 2 < len; k++) {
                if (s[k] == '-' && s[k + 1] == '-' && s[k + 2] == '>') {
                    out.add(k + 3, Tok.PLAIN);
                    return inline(s, len, k + 3, out);
                }
            }
            return M_HTMLCOMMENT;
        }
        if (startsFence(s, len, i)) {
            out.add(0, Tok.PUNCT);
            int k = i + 3;
            while (k < len && (s[k] == '`' || s[k] == '~')) k++;
            out.add(k, Tok.TYPE);
            return M_FENCE;
        }
        if (i < len && s[i] == '#') {
            int k = i;
            while (k < len && s[k] == '#') k++;
            if (k == len || s[k] == ' ') {
                out.add(0, Tok.HEADING);
                return M_TEXT;
            }
        }
        if (i < len && s[i] == '>') {
            out.add(i, Tok.COMMENT);
            out.add(i + 1, Tok.PLAIN);
            i++;
        } else if (i + 1 < len && (s[i] == '-' || s[i] == '*' || s[i] == '+') && s[i + 1] == ' ') {
            out.add(i, Tok.KEYWORD);
            out.add(i + 1, Tok.PLAIN);
            i += 2;
        } else if (i < len && Character.isDigit(s[i])) {
            int k = i;
            while (k < len && Character.isDigit(s[k])) k++;
            if (k + 1 < len && (s[k] == '.' || s[k] == ')') && s[k + 1] == ' ') {
                out.add(i, Tok.KEYWORD);
                out.add(k + 1, Tok.PLAIN);
                i = k + 2;
            }
        }
        if (isRule(s, len)) {
            out.add(0, Tok.PUNCT);
            return M_TEXT;
        }
        return inline(s, len, i, out);
    }

    private static boolean startsFence(char[] s, int len, int i) {
        return i + 2 < len && ((s[i] == '`' && s[i + 1] == '`' && s[i + 2] == '`') || (s[i] == '~' && s[i + 1] == '~' && s[i + 2] == '~'));
    }

    private static boolean isRule(char[] s, int len) {
        int n = 0;
        char m = 0;
        for (int k = 0; k < len; k++) {
            char c = s[k];
            if (c == ' ') continue;
            if (c != '-' && c != '*' && c != '_') return false;
            if (m != 0 && c != m) return false;
            m = c;
            n++;
        }
        return n >= 3;
    }

    private static int inline(char[] s, int len, int i, Tokens out) {
        out.add(i, Tok.PLAIN);
        while (i < len) {
            char c = s[i];
            if (c == '`') {
                int e = indexOf(s, len, i + 1, '`');
                if (e > 0) {
                    out.add(i, Tok.CODE);
                    i = e + 1;
                    out.add(i, Tok.PLAIN);
                    continue;
                }
            } else if ((c == '*' || c == '_') && i + 1 < len) {
                boolean strong = s[i + 1] == c;
                int from = i + (strong ? 2 : 1);
                int e = strong ? indexOf2(s, len, from, c) : indexOf(s, len, from, c);
                if (e > from && s[from] != ' ' && !(c == '_' && i > 0 && Character.isLetterOrDigit(s[i - 1]))) {
                    out.add(i, strong ? Tok.BOLD : Tok.ITALIC);
                    i = e + (strong ? 2 : 1);
                    out.add(i, Tok.PLAIN);
                    continue;
                }
            } else if (c == '[' || (c == '!' && i + 1 < len && s[i + 1] == '[')) {
                int open = c == '!' ? i + 1 : i;
                int close = indexOf(s, len, open + 1, ']');
                if (close > 0 && close + 1 < len && s[close + 1] == '(') {
                    int pe = indexOf(s, len, close + 2, ')');
                    if (pe > 0) {
                        out.add(i, Tok.STRING);
                        out.add(close + 1, Tok.LINK);
                        i = pe + 1;
                        out.add(i, Tok.PLAIN);
                        continue;
                    }
                }
            } else if (c == '<' && i + 3 < len && s[i + 1] == '!' && s[i + 2] == '-' && s[i + 3] == '-') {
                out.add(i, Tok.COMMENT);
                int end = -1;
                for (int k = i + 4; k + 2 < len; k++) {
                    if (s[k] == '-' && s[k + 1] == '-' && s[k + 2] == '>') {
                        end = k + 3;
                        break;
                    }
                }
                if (end < 0) return M_HTMLCOMMENT;
                i = end;
                out.add(i, Tok.PLAIN);
                continue;
            } else if (c == 'h' && (startsWith(s, len, i, "http://") || startsWith(s, len, i, "https://"))) {
                int k = i;
                while (k < len && s[k] != ' ' && s[k] != ')' && s[k] != '>') k++;
                out.add(i, Tok.LINK);
                i = k;
                out.add(i, Tok.PLAIN);
                continue;
            }
            i++;
        }
        return M_TEXT;
    }

    private static boolean startsWith(char[] s, int len, int i, String w) {
        if (i + w.length() > len) return false;
        for (int k = 0; k < w.length(); k++) if (s[i + k] != w.charAt(k)) return false;
        return true;
    }

    private static int indexOf(char[] s, int len, int from, char c) {
        for (int i = from; i < len; i++) if (s[i] == c) return i;
        return -1;
    }

    private static int indexOf2(char[] s, int len, int from, char c) {
        for (int i = from; i < len - 1; i++) if (s[i] == c && s[i + 1] == c) return i;
        return -1;
    }
}
