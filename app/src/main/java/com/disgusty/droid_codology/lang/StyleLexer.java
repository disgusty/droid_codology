package com.disgusty.droid_codology.lang;

/**
 * CSS, SCSS and Less: selectors, properties, values, units, colors, at-rules, comments.
 * Detail bit 0 tracks "inside a declaration block" so properties and selectors color differently.
 */
public final class StyleLexer implements Lexer {
    static final int M_NORMAL = 0, M_COMMENT = 1;
    private final boolean lineComments;

    public StyleLexer(boolean lineComments) {
        this.lineComments = lineComments;
    }

    @Override
    public int lex(char[] s, int len, int state, Tokens out) {
        int mode = state & 0xFF, depth = CodeLexer.depthOf(state);
        boolean inBlock = ((state >> 8) & 1) != 0;
        boolean afterColon = false;
        int i = 0;
        if (mode == M_COMMENT) {
            out.add(0, Tok.COMMENT);
            int e = find(s, len, 0, '*', '/');
            if (e < 0) return state;
            i = e + 2;
        }
        while (i < len) {
            char c = s[i];
            if (c == '/' && i + 1 < len && s[i + 1] == '*') {
                out.add(i, Tok.COMMENT);
                int e = find(s, len, i + 2, '*', '/');
                if (e < 0) return CodeLexer.withDepth(M_COMMENT, inBlock ? 1 : 0, depth);
                i = e + 2;
                continue;
            }
            if (lineComments && c == '/' && i + 1 < len && s[i + 1] == '/') {
                out.add(i, Tok.COMMENT);
                break;
            }
            if (c == '"' || c == '\'') {
                out.add(i, Tok.STRING);
                int k = i + 1;
                while (k < len && s[k] != c) k += s[k] == '\\' ? 2 : 1;
                i = Math.min(len, k + 1);
                out.add(i, Tok.PLAIN);
                continue;
            }
            if (c == '{') {
                out.add(i, CodeLexer.bracketStyle(depth++));
                inBlock = true;
                afterColon = false;
                i++;
                continue;
            }
            if (c == '}') {
                if (depth > 0) depth--;
                out.add(i, CodeLexer.bracketStyle(depth));
                inBlock = depth > 0;
                afterColon = false;
                i++;
                continue;
            }
            if (c == ';') {
                out.add(i, Tok.PUNCT);
                afterColon = false;
                i++;
                continue;
            }
            if (c == '@') {
                int k = i + 1;
                while (k < len && (Character.isLetterOrDigit(s[k]) || s[k] == '-')) k++;
                out.add(i, Tok.CONTROL);
                i = k;
                continue;
            }
            if (c == '$' && i + 1 < len) {
                int k = i + 1;
                while (k < len && (Character.isLetterOrDigit(s[k]) || s[k] == '-' || s[k] == '_')) k++;
                out.add(i, Tok.VARIABLE);
                i = k;
                continue;
            }
            if (c == '#' && (afterColon || !inBlock)) {
                int k = i + 1;
                while (k < len && (Character.isLetterOrDigit(s[k]) || s[k] == '-' || s[k] == '_')) k++;
                out.add(i, afterColon ? Tok.NUMBER : Tok.TAG);
                i = k;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(s[i + 1])) || (c == '-' && i + 1 < len && Character.isDigit(s[i + 1]) && afterColon)) {
                int k = i + 1;
                while (k < len && (Character.isDigit(s[k]) || s[k] == '.')) k++;
                out.add(i, Tok.NUMBER);
                int u = k;
                while (u < len && (Character.isLetter(s[u]) || s[u] == '%')) u++;
                if (u > k) out.add(k, Tok.KEYWORD);
                i = u;
                continue;
            }
            if (Character.isLetter(c) || c == '-' || c == '_' || c == '.' || c == '*' || c == '&') {
                int k = i + 1;
                while (k < len && (Character.isLetterOrDigit(s[k]) || s[k] == '-' || s[k] == '_')) k++;
                int tok;
                if (inBlock && !afterColon) {
                    // A property if followed by ':' (before any '{'); otherwise a nested selector.
                    int q = k;
                    while (q < len && s[q] == ' ') q++;
                    boolean prop = q < len && s[q] == ':' && !(q + 1 < len && Character.isLetter(s[q + 1]) && looksLikeSelector(s, len, q));
                    tok = prop ? Tok.PROPERTY : selectorStyle(c);
                } else if (afterColon) {
                    int q = k;
                    tok = q < len && s[q] == '(' ? Tok.FUNCTION : Tok.CONSTANT;
                } else tok = selectorStyle(c);
                out.add(i, tok);
                i = k;
                continue;
            }
            if (c == ':') {
                out.add(i, Tok.PUNCT);
                if (inBlock) {
                    afterColon = true;
                    i++;
                } else {
                    // Pseudo-class / pseudo-element.
                    int k = i + 1;
                    if (k < len && s[k] == ':') k++;
                    while (k < len && (Character.isLetterOrDigit(s[k]) || s[k] == '-')) k++;
                    out.add(i, Tok.FUNCTION);
                    i = k;
                }
                continue;
            }
            if (c == '(' || c == '[') {
                out.add(i, CodeLexer.bracketStyle(depth));
                i++;
                continue;
            }
            if (c == '!' ) {
                int k = i + 1;
                while (k < len && Character.isLetter(s[k])) k++;
                out.add(i, Tok.KEYWORD);
                i = k;
                continue;
            }
            out.add(i, Tok.PUNCT);
            i++;
        }
        return CodeLexer.withDepth(M_NORMAL, inBlock ? 1 : 0, depth);
    }

    private static boolean looksLikeSelector(char[] s, int len, int colon) {
        // "a:hover {" inside a nested block is a selector, "color: red;" is a property.
        for (int k = colon; k < len; k++) {
            if (s[k] == '{') return true;
            if (s[k] == ';') return false;
        }
        return false;
    }

    private static int selectorStyle(char c) {
        if (c == '.') return Tok.TYPE;
        if (c == '&' || c == '*') return Tok.OPERATOR;
        return Tok.TAG;
    }

    private static int find(char[] s, int len, int from, char a, char b) {
        for (int i = from; i < len - 1; i++) if (s[i] == a && s[i + 1] == b) return i;
        return -1;
    }
}
