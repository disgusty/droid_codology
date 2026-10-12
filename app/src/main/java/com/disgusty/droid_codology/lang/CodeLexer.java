package com.disgusty.droid_codology.lang;

import java.util.HashMap;
import java.util.Locale;

/**
 * A fast table-driven lexer for C-like, script and config languages: keywords, types, strings
 * (incl. multi-line ones), comments, numbers, annotations, function calls and depth-colored
 * brackets. One pass over each line, no regular expressions.
 */
public final class CodeLexer implements Lexer {
    static final int M_NORMAL = 0, M_BLOCK = 1, M_MLSTRING = 2, M_HEREDOC = 3;

    /** Word class lookup. */
    private final HashMap<String, Integer> words = new HashMap<String, Integer>();
    private final String[] lineComments, docLines;
    private final String[] blockOpen, blockClose;
    private final boolean[] blockDoc;
    private final String quotes;
    /** Multi-line string delimiters (open == close), e.g. """ ''' `. */
    private final String[] mlStrings;
    private final boolean hashPreproc, atAnnotation, dollarVars, capsTypes, calls, jsRegex, ignoreCase,
            lispy, dashWords, keyColon, sqlStrings, noEscapes;
    private final char escape;

    public static final class Spec {
        final HashMap<String, Integer> words = new HashMap<String, Integer>();
        String[] lineComments = {};
        String[] docLines = {};
        String[] blockOpen = {}, blockClose = {};
        boolean[] blockDoc = {};
        String quotes = "\"'";
        String[] mlStrings = {};
        boolean hashPreproc, atAnnotation, dollarVars, capsTypes = true, calls = true, jsRegex, ignoreCase,
                lispy, dashWords, keyColon, sqlStrings, noEscapes;

        public Spec kw(int style, String list) {
            for (String w : list.split(" ")) if (!w.isEmpty()) words.put(ignoreCase ? w.toLowerCase(Locale.ROOT) : w, style);
            return this;
        }

        public Spec keywords(String l) {
            return kw(Tok.KEYWORD, l);
        }

        public Spec control(String l) {
            return kw(Tok.CONTROL, l);
        }

        public Spec types(String l) {
            return kw(Tok.TYPE, l);
        }

        public Spec constants(String l) {
            return kw(Tok.CONSTANT, l);
        }

        public Spec builtins(String l) {
            return kw(Tok.FUNCTION, l);
        }

        public Spec line(String... c) {
            lineComments = c;
            return this;
        }

        /** Documentation line comments ("///"); checked before plain line comments. */
        public Spec docLine(String c) {
            docLines = append(docLines, c);
            return this;
        }

        public Spec block(String open, String close) {
            blockOpen = append(blockOpen, open);
            blockClose = append(blockClose, close);
            boolean[] d = new boolean[blockDoc.length + 1];
            System.arraycopy(blockDoc, 0, d, 0, blockDoc.length);
            blockDoc = d;
            return this;
        }

        /** Doc comments ("/**") get their own color; must be added before the plain form. */
        public Spec docBlock(String open, String close) {
            block(open, close);
            blockDoc[blockDoc.length - 1] = true;
            return this;
        }

        public Spec quotes(String q) {
            quotes = q;
            return this;
        }

        public Spec ml(String... d) {
            mlStrings = d;
            return this;
        }

        public Spec preproc() {
            hashPreproc = true;
            return this;
        }

        public Spec annotations() {
            atAnnotation = true;
            return this;
        }

        public Spec dollar() {
            dollarVars = true;
            return this;
        }

        public Spec noCapsTypes() {
            capsTypes = false;
            return this;
        }

        public Spec noCalls() {
            calls = false;
            return this;
        }

        public Spec regex() {
            jsRegex = true;
            return this;
        }

        /** Keywords match case-insensitively; call before adding keywords. */
        public Spec ignoreCase() {
            ignoreCase = true;
            return this;
        }

        public Spec lisp() {
            lispy = true;
            dashWords = true;
            return this;
        }

        public Spec dashes() {
            dashWords = true;
            return this;
        }

        /** "key:" / "key =" at line start is a property (YAML, TOML, INI). */
        public Spec keys() {
            keyColon = true;
            return this;
        }

        /** '' inside '...' is an escaped quote (SQL, Pascal). */
        public Spec doubledQuotes() {
            sqlStrings = true;
            return this;
        }

        public Spec rawStrings() {
            noEscapes = true;
            return this;
        }

        public CodeLexer build() {
            return new CodeLexer(this);
        }

        private static String[] append(String[] a, String s) {
            String[] n = new String[a.length + 1];
            System.arraycopy(a, 0, n, 0, a.length);
            n[a.length] = s;
            return n;
        }
    }

    /** Keywords, types, constants and builtins known to this lexer (for completion). */
    public java.util.Set<String> words() {
        return java.util.Collections.unmodifiableSet(words.keySet());
    }

    public static Spec spec() {
        return new Spec();
    }

    private CodeLexer(Spec s) {
        words.putAll(s.words);
        lineComments = s.lineComments;
        docLines = s.docLines;
        blockOpen = s.blockOpen;
        blockClose = s.blockClose;
        blockDoc = s.blockDoc;
        quotes = s.quotes;
        mlStrings = s.mlStrings;
        hashPreproc = s.hashPreproc;
        atAnnotation = s.atAnnotation;
        dollarVars = s.dollarVars;
        capsTypes = s.capsTypes;
        calls = s.calls;
        jsRegex = s.jsRegex;
        ignoreCase = s.ignoreCase;
        lispy = s.lispy;
        dashWords = s.dashWords;
        keyColon = s.keyColon;
        sqlStrings = s.sqlStrings;
        noEscapes = s.noEscapes;
        escape = '\\';
    }

    private static boolean at(char[] s, int len, int i, String w) {
        int n = w.length();
        if (i + n > len) return false;
        for (int k = 0; k < n; k++) if (s[i + k] != w.charAt(k)) return false;
        return true;
    }

    static boolean identStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private boolean identPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || (dashWords && (c == '-' || c == '?' || c == '!'));
    }

    static int depthOf(int state) {
        return (state >>> 16) & 0x7FFF;
    }

    static int withDepth(int mode, int detail, int depth) {
        if (depth < 0) depth = 0;
        if (depth > 0x7FFF) depth = 0x7FFF;
        return (depth << 16) | ((detail & 0xFF) << 8) | (mode & 0xFF);
    }

    static int bracketStyle(int depth) {
        return Tok.BRACKET1 + (depth % 3);
    }

    @Override
    public int lex(char[] s, int len, int state, Tokens out) {
        int mode = state & 0xFF, detail = (state >> 8) & 0xFF, depth = depthOf(state);
        int i = 0;
        // Continue a construct from the previous line.
        if (mode == M_BLOCK) {
            String close = blockClose[detail];
            out.add(0, blockDoc[detail] ? Tok.DOC_COMMENT : Tok.COMMENT);
            int e = find(s, len, 0, close);
            if (e < 0) return state;
            i = e + close.length();
            mode = M_NORMAL;
        } else if (mode == M_MLSTRING) {
            String d = mlStrings[detail];
            out.add(0, Tok.STRING);
            int e = findUnescaped(s, len, 0, d);
            if (e < 0) return state;
            i = e + d.length();
            mode = M_NORMAL;
        }
        boolean lineStart = true;
        for (int k = 0; k < i; k++) if (s[k] != ' ' && s[k] != '\t') lineStart = false;
        int prevSignificant = i == 0 ? -1 : Tok.STRING; // style of the last non-space token, for regex detection
        char prevChar = 0;

        while (i < len) {
            char c = s[i];
            if (c == ' ' || c == '\t') {
                int st = i;
                while (i < len && (s[i] == ' ' || s[i] == '\t')) i++;
                out.add(st, Tok.PLAIN);
                continue;
            }
            // Block comments (before line comments: Lua's "--[[" starts with "--").
            int bi = -1;
            for (int b = 0; b < blockOpen.length; b++) if (at(s, len, i, blockOpen[b])) { bi = b; break; }
            if (bi >= 0) {
                out.add(i, blockDoc[bi] ? Tok.DOC_COMMENT : Tok.COMMENT);
                int e = find(s, len, i + blockOpen[bi].length(), blockClose[bi]);
                if (e < 0) return withDepth(M_BLOCK, bi, depth);
                i = e + blockClose[bi].length();
                lineStart = false;
                continue;
            }
            // Line comments.
            for (String dl : docLines) {
                if (at(s, len, i, dl)) {
                    out.add(i, Tok.DOC_COMMENT);
                    return withDepth(M_NORMAL, 0, depth);
                }
            }
            boolean done = false;
            for (String lc : lineComments) {
                if (at(s, len, i, lc) && !(lc.equals("#") && hashPreproc)) {
                    // Shell-style '#' must start a word (so $# or a#b are not comments).
                    if (lc.equals("#") && i > 0 && s[i - 1] != ' ' && s[i - 1] != '\t' && dollarVars) break;
                    out.add(i, Tok.COMMENT);
                    done = true;
                    break;
                }
            }
            if (done) return withDepth(M_NORMAL, 0, depth);
            // Preprocessor lines.
            if (hashPreproc && c == '#' && lineStart) {
                out.add(i, Tok.PREPROC);
                int st = i + 1;
                while (st < len && (s[st] == ' ' || s[st] == '\t')) st++;
                int we = st;
                while (we < len && identPart(s[we])) we++;
                String dir = new String(s, st, we - st);
                if (dir.equals("include") || dir.equals("import")) {
                    out.add(we, Tok.PLAIN);
                    int q = we;
                    while (q < len && s[q] != '<' && s[q] != '"') q++;
                    if (q < len) out.add(q, Tok.STRING);
                    return withDepth(M_NORMAL, 0, depth);
                }
                i = we;
                lineStart = false;
                prevSignificant = Tok.PREPROC;
                continue;
            }
            // Multi-line strings.
            int mi = -1;
            for (int m = 0; m < mlStrings.length; m++) if (at(s, len, i, mlStrings[m])) { mi = m; break; }
            if (mi >= 0) {
                String d = mlStrings[mi];
                out.add(i, Tok.STRING);
                int e = findUnescaped(s, len, i + d.length(), d);
                if (e < 0) return withDepth(M_MLSTRING, mi, depth);
                i = e + d.length();
                out.add(i, Tok.PLAIN);
                lineStart = false;
                prevSignificant = Tok.STRING;
                continue;
            }
            // Strings.
            if (quotes.indexOf(c) >= 0) {
                int st = i;
                i = scanString(s, len, i + 1, c);
                out.add(st, Tok.STRING);
                markEscapes(s, st + 1, i, out);
                out.add(i, Tok.PLAIN);
                if (keyColon) {
                    int k = i;
                    while (k < len && s[k] == ' ') k++;
                    if (k < len && s[k] == ':') {
                        // JSON-style key.
                        retag(out, st, Tok.PROPERTY);
                    }
                }
                lineStart = false;
                prevSignificant = Tok.STRING;
                continue;
            }
            // Numbers.
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(s[i + 1]) && prevChar != '.')) {
                int st = i;
                i = scanNumber(s, len, i);
                out.add(st, Tok.NUMBER);
                lineStart = false;
                prevSignificant = Tok.NUMBER;
                prevChar = s[i - 1];
                continue;
            }
            // Annotations / decorators.
            if (atAnnotation && c == '@' && i + 1 < len && identStart(s[i + 1])) {
                int st = i++;
                while (i < len && (identPart(s[i]) || s[i] == '.')) i++;
                out.add(st, Tok.ANNOTATION);
                lineStart = false;
                prevSignificant = Tok.ANNOTATION;
                continue;
            }
            // $variables.
            if (dollarVars && c == '$' && i + 1 < len && (identStart(s[i + 1]) || s[i + 1] == '{' || Character.isDigit(s[i + 1]) || "@#?*!$-".indexOf(s[i + 1]) >= 0)) {
                int st = i++;
                if (s[i] == '{') {
                    while (i < len && s[i] != '}') i++;
                    if (i < len) i++;
                } else if (!identStart(s[i])) i++;
                else while (i < len && identPart(s[i])) i++;
                out.add(st, Tok.VARIABLE);
                lineStart = false;
                prevSignificant = Tok.VARIABLE;
                continue;
            }
            // Identifiers and keywords.
            if (identStart(c) || (c > 127 && Character.isLetter(c))) {
                int st = i;
                while (i < len && identPart(s[i])) i++;
                String w = new String(s, st, i - st);
                Integer style = words.get(ignoreCase ? w.toLowerCase(Locale.ROOT) : w);
                int tok;
                if (style != null) tok = style;
                else {
                    int k = i;
                    while (k < len && s[k] == ' ') k++;
                    if (keyColon && lineStart && k < len && (s[k] == ':' || s[k] == '=')) tok = Tok.PROPERTY;
                    else if (calls && k < len && s[k] == '(' && !lispy) tok = Tok.FUNCTION;
                    else if (lispy && st > 0 && s[st - 1] == '(') tok = Tok.FUNCTION;
                    else if (capsTypes && Character.isUpperCase(c)) tok = isAllCaps(w) && w.length() > 1 ? Tok.CONSTANT : Tok.TYPE;
                    else if (st > 0 && s[st - 1] == '.' && !(st > 1 && s[st - 2] == '.')) tok = Tok.PROPERTY;
                    else tok = Tok.PLAIN;
                }
                out.add(st, tok);
                lineStart = false;
                prevSignificant = tok;
                prevChar = s[i - 1];
                continue;
            }
            // JS-style regex literal.
            if (jsRegex && c == '/' && regexAllowed(prevSignificant, prevChar)) {
                int e = scanRegex(s, len, i + 1);
                if (e > 0) {
                    out.add(i, Tok.REGEX);
                    i = e;
                    out.add(i, Tok.PLAIN);
                    lineStart = false;
                    prevSignificant = Tok.REGEX;
                    continue;
                }
            }
            // Brackets, colored by depth.
            if (c == '(' || c == '[' || c == '{') {
                out.add(i, bracketStyle(depth));
                depth++;
                i++;
                lineStart = false;
                prevSignificant = Tok.PUNCT;
                prevChar = c;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                if (depth > 0) depth--;
                out.add(i, bracketStyle(depth));
                i++;
                lineStart = false;
                prevSignificant = Tok.PLAIN;
                prevChar = c;
                continue;
            }
            if ("+-*/%=&|^!<>?:~".indexOf(c) >= 0) {
                out.add(i, Tok.OPERATOR);
                prevSignificant = Tok.OPERATOR;
            } else {
                out.add(i, Tok.PUNCT);
                prevSignificant = Tok.PUNCT;
            }
            prevChar = c;
            i++;
            lineStart = false;
        }
        return withDepth(M_NORMAL, 0, depth);
    }

    private static boolean isAllCaps(String w) {
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLowerCase(c)) return false;
        }
        return true;
    }

    private static boolean regexAllowed(int prevTok, char prevChar) {
        if (prevTok == -1) return true;
        if (prevTok == Tok.OPERATOR || prevTok == Tok.KEYWORD || prevTok == Tok.CONTROL) return true;
        if (prevTok == Tok.PUNCT) return true;
        return prevChar == '(' || prevChar == '[' || prevChar == '{' || prevChar == ',' || prevChar == ';';
    }

    private static int scanRegex(char[] s, int len, int i) {
        boolean cls = false;
        while (i < len) {
            char c = s[i];
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') cls = true;
            else if (c == ']') cls = false;
            else if (c == '/' && !cls) {
                i++;
                while (i < len && Character.isLetter(s[i])) i++;
                return i;
            }
            i++;
        }
        return -1;
    }

    private int scanString(char[] s, int len, int i, char q) {
        while (i < len) {
            char c = s[i];
            if (c == escape && !noEscapes && !(sqlStrings && q == '\'')) {
                i += 2;
                continue;
            }
            if (c == q) {
                if (sqlStrings && i + 1 < len && s[i + 1] == q) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private void markEscapes(char[] s, int from, int to, Tokens out) {
        if (noEscapes) return;
        int end = Math.min(to, s.length);
        for (int i = from; i < end - 1; i++) {
            if (s[i] == '\\') {
                out.add(i, Tok.ESCAPE);
                int e = i + 2;
                if (s[i + 1] == 'u' || s[i + 1] == 'x') {
                    while (e < end - 1 && e < i + 6 && isHex(s[e])) e++;
                }
                out.add(e, Tok.STRING);
                i = e - 1;
            }
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static void retag(Tokens out, int start, int style) {
        for (int k = out.size - 2; k >= 0; k -= 2) {
            if (out.data[k] == start) {
                out.data[k + 1] = style;
                return;
            }
            if (out.data[k] < start) return;
        }
    }

    private static int scanNumber(char[] s, int len, int i) {
        if (s[i] == '0' && i + 1 < len && (s[i + 1] == 'x' || s[i + 1] == 'X' || s[i + 1] == 'b' || s[i + 1] == 'B' || s[i + 1] == 'o')) {
            i += 2;
            while (i < len && (isHex(s[i]) || s[i] == '_')) i++;
        } else {
            while (i < len && (Character.isDigit(s[i]) || s[i] == '_' || s[i] == '.')) {
                if (s[i] == '.' && i + 1 < len && s[i + 1] == '.') break; // range operator 1..2
                i++;
            }
            if (i < len && (s[i] == 'e' || s[i] == 'E')) {
                int k = i + 1;
                if (k < len && (s[k] == '+' || s[k] == '-')) k++;
                if (k < len && Character.isDigit(s[k])) {
                    i = k;
                    while (i < len && Character.isDigit(s[i])) i++;
                }
            }
        }
        // Suffixes: 10L, 1.5f, 42u8, 3i64, 1n
        while (i < len && Character.isLetterOrDigit(s[i])) i++;
        return i;
    }

    private static int find(char[] s, int len, int from, String w) {
        char f = w.charAt(0);
        for (int i = from; i <= len - w.length(); i++) {
            if (s[i] == f && at(s, len, i, w)) return i;
        }
        return -1;
    }

    private int findUnescaped(char[] s, int len, int from, String w) {
        char f = w.charAt(0);
        for (int i = from; i <= len - w.length(); i++) {
            if (s[i] == escape && !noEscapes) {
                i++;
                continue;
            }
            if (s[i] == f && at(s, len, i, w)) return i;
        }
        return -1;
    }
}
