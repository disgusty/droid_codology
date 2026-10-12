package com.disgusty.droid_codology.lang;

/**
 * HTML / XML / SVG: tags, attributes, values, comments, CDATA, entities. Inside &lt;script&gt; and
 * &lt;style&gt; (HTML only) the embedded JavaScript / CSS lexer takes over.
 */
public final class MarkupLexer implements Lexer {
    // Modes. Embedded modes keep the inner lexer's mode in the detail bits.
    static final int M_TEXT = 0, M_COMMENT = 1, M_TAG = 2, M_ATTRVAL_D = 3, M_ATTRVAL_S = 4, M_CDATA = 5,
            M_SCRIPT = 6, M_STYLE = 7;

    private final boolean html;
    private final Lexer script, style;

    public MarkupLexer(boolean html, Lexer script, Lexer style) {
        this.html = html;
        this.script = script;
        this.style = style;
    }

    private static boolean at(char[] s, int len, int i, String w) {
        if (i + w.length() > len) return false;
        for (int k = 0; k < w.length(); k++) {
            if (Character.toLowerCase(s[i + k]) != w.charAt(k)) return false;
        }
        return true;
    }

    /** Tag name being opened inside M_TAG: 1 = script, 2 = style (stored in detail bits). */
    @Override
    public int lex(char[] s, int len, int state, Tokens out) {
        int mode = state & 0xFF, detail = (state >> 8) & 0xFF, depth = CodeLexer.depthOf(state);
        int i = 0;
        while (i <= len) {
            if (mode == M_SCRIPT || mode == M_STYLE) {
                String endTag = mode == M_SCRIPT ? "</script" : "</style";
                int e = -1;
                for (int k = i; k <= len - endTag.length(); k++) {
                    if (s[k] == '<' && at(s, len, k, endTag)) {
                        e = k;
                        break;
                    }
                }
                Lexer inner = mode == M_SCRIPT ? script : style;
                int innerEnd = e < 0 ? len : e;
                // Run the inner lexer on the slice, then shift its tokens.
                char[] slice = new char[innerEnd - i];
                System.arraycopy(s, i, slice, 0, slice.length);
                Tokens t = new Tokens();
                // The inner mode (4 bits) and its detail (4 bits) are packed into our detail byte.
                int innerIn = (detail & 0xF) | (((detail >> 4) & 0xF) << 8) | (depth << 16);
                int innerState = inner.lex(slice, slice.length, innerIn, t);
                for (int k = 0; k < t.count(); k++) out.add(t.start(k) + i, t.style(k));
                depth = CodeLexer.depthOf(innerState);
                int packed = (innerState & 0xF) | (((innerState >> 8) & 0xF) << 4);
                if (e < 0) return CodeLexer.withDepth(mode, packed, depth);
                i = e;
                mode = M_TEXT;
                detail = 0;
                continue;
            }
            if (i >= len) break;
            char c = s[i];
            switch (mode) {
                case M_TEXT: {
                    if (c == '<') {
                        if (at(s, len, i, "<!--")) {
                            out.add(i, Tok.COMMENT);
                            mode = M_COMMENT;
                            i += 4;
                        } else if (at(s, len, i, "<![cdata[")) {
                            out.add(i, Tok.STRING);
                            mode = M_CDATA;
                            i += 9;
                        } else {
                            out.add(i, Tok.PUNCT);
                            int k = i + 1;
                            if (k < len && (s[k] == '/' || s[k] == '?' || s[k] == '!')) k++;
                            out.add(k, Tok.TAG);
                            int ns = k;
                            while (k < len && (Character.isLetterOrDigit(s[k]) || s[k] == ':' || s[k] == '-' || s[k] == '_' || s[k] == '.')) k++;
                            detail = 0;
                            if (html && s[i + 1] != '/') {
                                String name = new String(s, ns, k - ns).toLowerCase(java.util.Locale.ROOT);
                                if (name.equals("script")) detail = 1;
                                else if (name.equals("style")) detail = 2;
                            }
                            out.add(k, Tok.PLAIN);
                            i = k;
                            mode = M_TAG;
                        }
                    } else if (c == '&') {
                        int k = i + 1;
                        while (k < len && k < i + 12 && (Character.isLetterOrDigit(s[k]) || s[k] == '#')) k++;
                        if (k < len && s[k] == ';') {
                            out.add(i, Tok.ESCAPE);
                            i = k + 1;
                            out.add(i, Tok.PLAIN);
                        } else {
                            out.add(i, Tok.PLAIN);
                            i++;
                        }
                    } else {
                        out.add(i, Tok.PLAIN);
                        i++;
                    }
                    break;
                }
                case M_COMMENT: {
                    out.add(i, Tok.COMMENT);
                    int e = indexOf(s, len, i, "-->");
                    if (e < 0) return CodeLexer.withDepth(M_COMMENT, 0, depth);
                    i = e + 3;
                    mode = M_TEXT;
                    break;
                }
                case M_CDATA: {
                    out.add(i, Tok.STRING);
                    int e = indexOf(s, len, i, "]]>");
                    if (e < 0) return CodeLexer.withDepth(M_CDATA, 0, depth);
                    i = e + 3;
                    mode = M_TEXT;
                    break;
                }
                case M_TAG: {
                    if (c == '>' || (c == '/' && i + 1 < len && s[i + 1] == '>') || (c == '?' && i + 1 < len && s[i + 1] == '>')) {
                        out.add(i, Tok.PUNCT);
                        boolean selfClose = c != '>';
                        i += c == '>' ? 1 : 2;
                        out.add(i, Tok.PLAIN);
                        if (!selfClose && detail == 1) mode = M_SCRIPT;
                        else if (!selfClose && detail == 2) mode = M_STYLE;
                        else mode = M_TEXT;
                        detail = 0;
                    } else if (c == '"' || c == '\'') {
                        out.add(i, Tok.STRING);
                        int e = indexOf(s, len, i + 1, String.valueOf(c));
                        if (e < 0) return CodeLexer.withDepth(c == '"' ? M_ATTRVAL_D : M_ATTRVAL_S, detail, depth);
                        i = e + 1;
                        out.add(i, Tok.PLAIN);
                    } else if (c == '=') {
                        out.add(i, Tok.OPERATOR);
                        i++;
                        out.add(i, Tok.PLAIN);
                    } else if (Character.isLetter(c) || c == '_' || c == ':' || c == '@' || c == '[' || c == '(' || c == '*' || c == '#') {
                        int k = i;
                        while (k < len && s[k] != '=' && s[k] != '>' && s[k] != ' ' && s[k] != '\t' && s[k] != '/' && s[k] != '"' && s[k] != '\'') k++;
                        out.add(i, Tok.ATTR);
                        i = Math.max(k, i + 1);
                        out.add(i, Tok.PLAIN);
                    } else {
                        out.add(i, Tok.PLAIN);
                        i++;
                    }
                    break;
                }
                case M_ATTRVAL_D:
                case M_ATTRVAL_S: {
                    out.add(i, Tok.STRING);
                    int e = indexOf(s, len, i, mode == M_ATTRVAL_D ? "\"" : "'");
                    if (e < 0) return CodeLexer.withDepth(mode, detail, depth);
                    i = e + 1;
                    out.add(i, Tok.PLAIN);
                    mode = M_TAG;
                    break;
                }
                default:
                    i++;
            }
        }
        return CodeLexer.withDepth(mode, detail, depth);
    }

    private static int indexOf(char[] s, int len, int from, String w) {
        outer:
        for (int i = from; i <= len - w.length(); i++) {
            for (int k = 0; k < w.length(); k++) if (s[i + k] != w.charAt(k)) continue outer;
            return i;
        }
        return -1;
    }
}
