package com.disgusty.droid_codology.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/**
 * Every text-changing command of the editor, applied to all selections at once (multi-cursor),
 * recorded in the undo history. Independent of any View so it can be unit tested on the JVM.
 */
public final class EditEngine {
    public final TextBuffer text;
    public final Selections sel = new Selections();
    public final UndoStack undo = new UndoStack();

    // Settings.
    public int tabSize = 4;
    public boolean insertSpaces = true;
    public boolean autoClose = true;
    public boolean autoIndent = true;
    /** Line comment token ("//", "#", "--"), or null. */
    public String lineComment = "//";
    /** Block comment tokens, or null. */
    public String blockOpen = "/*", blockClose = "*/";
    /** Pairs typed together: openers and their closers at the same index. */
    public String pairOpen = "([{\"'`", pairClose = ")]}\"'`";
    /** Extra chars that open an indented block on Enter (':' for Python). */
    public String indentAfter = "";

    public interface Listener {
        /** The selection (or text) changed; {@code textChanged} is false for pure caret moves. */
        void onSelectionChanged(boolean textChanged);
    }

    private Listener listener;
    private int depth;
    private boolean changedInBatch;

    public EditEngine(TextBuffer text) {
        this.text = text;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------ transactions

    /** Opens a compound edit; nested calls join the outer one. */
    public void begin(int kind) {
        if (depth++ == 0) {
            undo.begin(kind, sel.snapshot());
            changedInBatch = false;
        }
    }

    public void end() {
        if (--depth == 0) {
            sel.normalize();
            undo.end(sel.snapshot());
            fire(changedInBatch);
        }
    }

    private void fire(boolean textChanged) {
        if (listener != null) listener.onSelectionChanged(textChanged);
    }

    /** Low-level replace inside a transaction: records undo and shifts every selection. */
    public void replace(int start, int end, String s) {
        if (start == end && s.isEmpty()) return;
        String removed = text.substring(start, end);
        if (removed.equals(s)) return;
        boolean own = depth == 0;
        if (own) begin(UndoStack.KIND_OTHER);
        undo.record(start, removed, s);
        text.replace(start, end, s);
        sel.adjust(start, end - start, s.length());
        changedInBatch = true;
        if (own) end();
    }

    /** Replaces and puts a single caret/selection at the given place. */
    public void replaceAndSelect(int start, int end, String s, int anchor, int head) {
        begin(UndoStack.KIND_OTHER);
        replace(start, end, s);
        sel.set(anchor, head);
        end();
    }

    public void setSelection(int anchor, int head) {
        int len = text.length();
        sel.set(clamp(anchor, len), clamp(head, len));
        fire(false);
    }

    public void selectionChangedExternally() {
        sel.normalize();
        fire(false);
    }

    private static int clamp(int v, int len) {
        return v < 0 ? 0 : Math.min(v, len);
    }

    // ------------------------------------------------------------------ per-selection edits

    /** Result of editing one selection: replace [rs, re) with text, then caret at rs+ca..rs+ch. */
    private static final class Op {
        int rs, re, ca, ch;
        String text;

        Op set(int rs, int re, String text, int ca, int ch) {
            this.rs = rs;
            this.re = re;
            this.text = text;
            this.ca = ca;
            this.ch = ch;
            return this;
        }
    }

    private interface PerSel {
        /** Fills {@code op} for selection {@code i} (anchor a, head h) in current coordinates; false = leave it. */
        boolean edit(int a, int h, Op op);
    }

    private void each(int kind, PerSel fn) {
        begin(kind);
        int n = sel.count;
        // Process in ascending order; earlier edits shift later selections by `delta`.
        int[] as = Arrays.copyOf(sel.anchor, n), hs = Arrays.copyOf(sel.head, n);
        int delta = 0, floor = 0;
        Op op = new Op();
        for (int i = 0; i < n; i++) {
            int a = as[i] + delta, h = hs[i] + delta;
            if (!fn.edit(a, h, op)) {
                as[i] = a;
                hs[i] = h;
                continue;
            }
            int rs = Math.max(op.rs, floor), re = Math.max(op.re, rs);
            String removed = text.substring(rs, re);
            if (!removed.equals(op.text)) {
                undo.record(rs, removed, op.text);
                text.replace(rs, re, op.text);
                changedInBatch = true;
            }
            int d = op.text.length() - (re - rs);
            delta += d;
            as[i] = rs + op.ca;
            hs[i] = rs + op.ch;
            floor = rs + op.text.length();
        }
        System.arraycopy(as, 0, sel.anchor, 0, n);
        System.arraycopy(hs, 0, sel.head, 0, n);
        end();
    }

    // ------------------------------------------------------------------ typing

    /** Types {@code s} at every caret (replacing selections), with auto-closing and surrounding. */
    /** Text of the last type() call (completion triggers on typed word chars); cleared by the reader. */
    public String lastTyped;

    public void type(final String s) {
        if (s.isEmpty()) return;
        lastTyped = s;
        if (s.equals("\n")) {
            newline();
            return;
        }
        final boolean single = s.length() == 1;
        final char c = single ? s.charAt(0) : 0;
        each(UndoStack.KIND_TYPE, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                if (single && autoClose) {
                    int pi = pairOpen.indexOf(c), ci = pairClose.indexOf(c);
                    if (st != en && pi >= 0) {
                        // Surround the selection.
                        String inner = text.substring(st, en);
                        op.set(st, en, c + inner + pairClose.charAt(pi), 1, 1 + inner.length());
                        if (h < a) { int t = op.ca; op.ca = op.ch; op.ch = t; }
                        return true;
                    }
                    if (st == en && ci >= 0 && st < text.length() && text.charAt(st) == c) {
                        // Over-type the closer.
                        op.set(st, st, "", 1, 1);
                        return true;
                    }
                    if (st == en && pi >= 0 && shouldAutoClose(c, st)) {
                        op.set(st, st, "" + c + pairClose.charAt(pi), 1, 1);
                        return true;
                    }
                }
                op.set(st, en, s, s.length(), s.length());
                return true;
            }
        });
        if (autoCloseTags && single && (c == '>' || c == '/')) closeTag(c);
    }

    private boolean shouldAutoClose(char c, int at) {
        char next = at < text.length() ? text.charAt(at) : '\n';
        if (!(Character.isWhitespace(next) || pairClose.indexOf(next) >= 0 || next == ',' || next == ';' || next == '.')) {
            return false;
        }
        boolean quote = c == '"' || c == '\'' || c == '`';
        if (quote) {
            char prev = at > 0 ? text.charAt(at - 1) : ' ';
            if (isWordChar(prev) || prev == c) return false;
            // An odd number of this quote on the line so far means we are closing one.
            int ls = text.lineStart(text.lineOfOffset(at));
            int n = 0;
            for (int i = ls; i < at; i++) if (text.charAt(i) == c && (i == ls || text.charAt(i - 1) != '\\')) n++;
            if ((n & 1) == 1) return false;
        }
        return true;
    }

    /** HTML/XML: typing "&gt;" closes the open tag, typing "&lt;/" completes the closing tag. */
    public boolean autoCloseTags;

    private static final java.util.HashSet<String> VOID_TAGS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr", "param", "!doctype"));
    private static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("<(/?)([A-Za-z][\\w:.-]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*?)(/?)>");

    private void closeTag(char c) {
        if (sel.count() != 1 || !sel.isEmpty(0)) return;
        int caret = sel.caret();
        int ls = text.lineStart(text.lineOfOffset(caret));
        if (c == '>') {
            int lt = -1;
            for (int i = caret - 2; i >= ls; i--) {
                char x = text.charAt(i);
                if (x == '<') {
                    lt = i;
                    break;
                }
                if (x == '>') return;
            }
            if (lt < 0 || caret >= 2 && text.charAt(caret - 2) == '/') return;
            int ns = lt + 1, ne = ns;
            while (ne < caret && (Character.isLetterOrDigit(text.charAt(ne)) || text.charAt(ne) == '-' || text.charAt(ne) == ':' || text.charAt(ne) == '.' || text.charAt(ne) == '_')) ne++;
            if (ne == ns || !Character.isLetter(text.charAt(ns))) return;
            String tag = text.substring(ns, ne);
            if (VOID_TAGS.contains(tag.toLowerCase(java.util.Locale.ROOT))) return;
            String close = "</" + tag + ">";
            if (caret + close.length() <= text.length() && text.substring(caret, caret + close.length()).equals(close)) return;
            begin(UndoStack.KIND_OTHER);
            replace(caret, caret, close);
            sel.set(caret, caret);
            end();
        } else if (c == '/') {
            if (caret < 2 || text.charAt(caret - 2) != '<') return;
            String open = unclosedTag(caret - 2);
            if (open == null) return;
            begin(UndoStack.KIND_OTHER);
            replace(caret, caret, open + ">");
            int after = caret + open.length() + 1;
            sel.set(after, after);
            end();
        }
    }

    /** The innermost element still open at {@code pos} (scanning a window before it). */
    private String unclosedTag(int pos) {
        int from = Math.max(0, pos - 30000);
        java.util.regex.Matcher m = TAG.matcher(text.subSequence(from, pos));
        java.util.ArrayList<String> stack = new java.util.ArrayList<String>();
        while (m.find()) {
            String name = m.group(2);
            if (m.group(1).length() > 0) {
                for (int i = stack.size() - 1; i >= 0; i--) {
                    if (stack.get(i).equalsIgnoreCase(name)) {
                        while (stack.size() > i) stack.remove(stack.size() - 1);
                        break;
                    }
                }
            } else if (m.group(4).isEmpty() && !VOID_TAGS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                stack.add(name);
            }
        }
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    public void newline() {
        each(UndoStack.KIND_OTHER, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                if (!autoIndent) {
                    op.set(st, en, "\n", 1, 1);
                    return true;
                }
                int line = text.lineOfOffset(st);
                int ls = text.lineStart(line);
                String indent = leadingWhitespace(line);
                if (indent.length() > st - ls) indent = indent.substring(0, st - ls);
                // Drop trailing whitespace left before the caret.
                int ws = st;
                while (ws > ls + indent.length() && (text.charAt(ws - 1) == ' ' || text.charAt(ws - 1) == '\t')) ws--;
                char prev = ws > ls ? text.charAt(ws - 1) : 0;
                char next = en < text.length() ? text.charAt(en) : 0;
                boolean opens = prev != 0 && ("([{".indexOf(prev) >= 0 || indentAfter.indexOf(prev) >= 0);
                if (opens) {
                    String inner = indent + indentUnit();
                    int pi = "([{".indexOf(prev);
                    if (pi >= 0 && next == ")]}".charAt(pi)) {
                        String t = "\n" + inner + "\n" + indent;
                        op.set(ws, en, t, 1 + inner.length(), 1 + inner.length());
                    } else {
                        op.set(ws, en, "\n" + inner, 1 + inner.length(), 1 + inner.length());
                    }
                    return true;
                }
                // Typing Enter inside the indent keeps the rest of the line aligned.
                int e2 = en;
                while (e2 < text.length() && (text.charAt(e2) == ' ' || text.charAt(e2) == '\t') && st - ls <= indent.length()) e2++;
                String t = "\n" + indent;
                op.set(ws, e2, t, t.length(), t.length());
                return true;
            }
        });
    }

    public String indentUnit() {
        if (!insertSpaces) return "\t";
        char[] c = new char[tabSize];
        Arrays.fill(c, ' ');
        return new String(c);
    }

    public String leadingWhitespace(int line) {
        int s = text.lineStart(line), e = text.lineEnd(line), i = s;
        while (i < e && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return text.substring(s, i);
    }

    /** Visual column of {@code offset} with tabs expanded. */
    public int column(int offset) {
        int ls = text.lineStart(text.lineOfOffset(offset));
        int col = 0;
        for (int i = ls; i < offset; i++) {
            if (text.charAt(i) == '\t') col += tabSize - col % tabSize;
            else col++;
        }
        return col;
    }

    // ------------------------------------------------------------------ deleting

    public void backspace() {
        each(UndoStack.KIND_DELETE, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                if (st != en) {
                    op.set(st, en, "", 0, 0);
                    return true;
                }
                if (st == 0) return false;
                int line = text.lineOfOffset(st), ls = text.lineStart(line);
                char prev = text.charAt(st - 1);
                // Inside an empty auto-pair: delete both.
                if (autoClose && st < text.length()) {
                    int pi = pairOpen.indexOf(prev);
                    if (pi >= 0 && text.charAt(st) == pairClose.charAt(pi)) {
                        op.set(st - 1, st + 1, "", 0, 0);
                        return true;
                    }
                }
                // In leading spaces: back to the previous tab stop.
                if (insertSpaces && prev == ' ') {
                    boolean allWs = true;
                    for (int i = ls; i < st; i++) if (text.charAt(i) != ' ') { allWs = false; break; }
                    if (allWs) {
                        int col = st - ls;
                        int target = ((col - 1) / tabSize) * tabSize;
                        op.set(ls + target, st, "", 0, 0);
                        return true;
                    }
                }
                int from = st - 1;
                if (from > 0 && Character.isLowSurrogate(text.charAt(from)) && Character.isHighSurrogate(text.charAt(from - 1))) from--;
                op.set(from, st, "", 0, 0);
                return true;
            }
        });
    }

    public void deleteForward() {
        each(UndoStack.KIND_DELETE, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                if (st != en) {
                    op.set(st, en, "", 0, 0);
                    return true;
                }
                if (st >= text.length()) return false;
                int to = st + 1;
                if (to < text.length() && Character.isHighSurrogate(text.charAt(st)) && Character.isLowSurrogate(text.charAt(to))) to++;
                op.set(st, to, "", 0, 0);
                return true;
            }
        });
    }

    /** Deletes the selected text of every non-empty selection (empty carets are left alone). */
    public void deleteSelections() {
        each(UndoStack.KIND_DELETE, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                if (a == h) return false;
                op.set(Math.min(a, h), Math.max(a, h), "", 0, 0);
                return true;
            }
        });
    }

    public void deleteWord(final boolean forward) {
        each(UndoStack.KIND_DELETE, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                if (st != en) {
                    op.set(st, en, "", 0, 0);
                    return true;
                }
                if (forward) {
                    int t = wordRight(st);
                    if (t == st) return false;
                    op.set(st, t, "", 0, 0);
                } else {
                    int t = wordLeft(st);
                    if (t == st) return false;
                    op.set(t, st, "", 0, 0);
                }
                return true;
            }
        });
    }

    /** Deletes from the caret to the start of the line (or the line break before it). */
    public void deleteToLineStart() {
        each(UndoStack.KIND_DELETE, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                int ls = text.lineStart(text.lineOfOffset(st));
                if (st != en) op.set(st, en, "", 0, 0);
                else if (st == ls) {
                    if (st == 0) return false;
                    op.set(st - 1, st, "", 0, 0);
                } else op.set(ls, st, "", 0, 0);
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ indentation

    public final SnippetSession snippet = new SnippetSession(this);

    /**
     * Inserts a snippet body in place of {@code [start, end)} (re-indented to the current line)
     * and starts visiting its tab stops.
     */
    public void insertSnippet(int start, int end, String body, Snippet.Variables vars) {
        int line = text.lineOfOffset(start);
        String indent = leadingWhitespace(line);
        if (indent.length() > start - text.lineStart(line)) indent = indent.substring(0, start - text.lineStart(line));
        Snippet.Result r = Snippet.expand(body, indent, indentUnit(), vars);
        begin(UndoStack.KIND_OTHER);
        replace(start, end, r.text);
        end();
        snippet.start(start, r);
    }

    /** Tab key: indents selected lines, or inserts an indent at each caret. */
    public void tab() {
        if (snippet.isActive() && snippet.next()) return;
        if (spansLines()) {
            indentLines(true);
            return;
        }
        each(UndoStack.KIND_TYPE, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                String t;
                if (insertSpaces) {
                    int col = column(st);
                    int n = tabSize - col % tabSize;
                    char[] c = new char[n];
                    Arrays.fill(c, ' ');
                    t = new String(c);
                } else t = "\t";
                op.set(st, en, t, t.length(), t.length());
                return true;
            }
        });
    }

    private boolean spansLines() {
        for (int i = 0; i < sel.count; i++) {
            if (text.lineOfOffset(sel.start(i)) != text.lineOfOffset(sel.end(i))) return true;
        }
        return false;
    }

    /** Lines covered by the selections, as sorted distinct line numbers. */
    private int[] selectedLines() {
        ArrayList<Integer> out = new ArrayList<Integer>();
        int last = -1;
        for (int i = 0; i < sel.count; i++) {
            int l0 = text.lineOfOffset(sel.start(i));
            int e = sel.end(i);
            int l1 = text.lineOfOffset(e);
            if (l1 > l0 && e == text.lineStart(l1)) l1--; // selection ending at column 0 excludes that line
            for (int l = Math.max(l0, last + 1); l <= l1; l++) {
                out.add(l);
                last = l;
            }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    public void indentLines(boolean in) {
        int[] lines = selectedLines();
        begin(UndoStack.KIND_OTHER);
        String unit = indentUnit();
        for (int k = lines.length - 1; k >= 0; k--) {
            int l = lines[k];
            int ls = text.lineStart(l);
            if (in) {
                if (text.lineLength(l) == 0 && lines.length > 1) continue;
                // Keep selection starts at column 0 from sliding right.
                replaceKeepingStart(ls, unit);
            } else {
                int e = text.lineEnd(l), n = 0;
                if (ls < e && text.charAt(ls) == '\t') n = 1;
                else while (n < tabSize && ls + n < e && text.charAt(ls + n) == ' ') n++;
                if (n > 0) replace(ls, ls + n, "");
            }
        }
        end();
    }

    /** Inserts at {@code at}; selections that start exactly there grow instead of moving. */
    private void replaceKeepingStart(int at, String s) {
        int n = sel.count;
        boolean[] anchorAt = new boolean[n], headAt = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean empty = sel.anchor[i] == sel.head[i];
            anchorAt[i] = !empty && sel.anchor[i] == at && sel.anchor[i] < sel.head[i];
            headAt[i] = !empty && sel.head[i] == at && sel.head[i] < sel.anchor[i];
        }
        replace(at, at, s);
        for (int i = 0; i < n; i++) {
            if (anchorAt[i]) sel.anchor[i] = at;
            if (headAt[i]) sel.head[i] = at;
        }
    }

    // ------------------------------------------------------------------ comments

    public void toggleLineComment() {
        if (lineComment == null) {
            toggleBlockComment();
            return;
        }
        int[] lines = selectedLines();
        // Comment out unless every non-blank line is already commented.
        boolean allCommented = true;
        int minIndent = Integer.MAX_VALUE;
        for (int l : lines) {
            String ws = leadingWhitespace(l);
            int ls = text.lineStart(l), e = text.lineEnd(l);
            if (ls + ws.length() == e) continue; // blank
            minIndent = Math.min(minIndent, ws.length());
            if (!startsWith(ls + ws.length(), lineComment)) allCommented = false;
        }
        if (minIndent == Integer.MAX_VALUE) {
            allCommented = false;
            minIndent = 0;
        }
        begin(UndoStack.KIND_OTHER);
        for (int k = lines.length - 1; k >= 0; k--) {
            int l = lines[k];
            int ls = text.lineStart(l), e = text.lineEnd(l);
            String ws = leadingWhitespace(l);
            if (allCommented) {
                int p = ls + ws.length();
                if (!startsWith(p, lineComment)) continue;
                int n = lineComment.length();
                if (p + n < e && text.charAt(p + n) == ' ') n++;
                replace(p, p + n, "");
            } else {
                if (ls + ws.length() == e && lines.length > 1) continue;
                int p = ls + Math.min(minIndent, ws.length());
                replace(p, p, lineComment + " ");
            }
        }
        end();
    }

    public void toggleBlockComment() {
        if (blockOpen == null) return;
        each(UndoStack.KIND_OTHER, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                int ol = blockOpen.length(), cl = blockClose.length();
                // Already wrapped (markers just outside or just inside the selection)?
                if (st >= ol && en + cl <= text.length() && startsWith(st - ol, blockOpen) && startsWith(en, blockClose)) {
                    String inner = text.substring(st, en);
                    op.set(st - ol, en + cl, inner, 0, inner.length());
                    return true;
                }
                if (en - st >= ol + cl && startsWith(st, blockOpen) && startsWith(en - cl, blockClose)) {
                    String inner = text.substring(st + ol, en - cl);
                    op.set(st, en, inner, 0, inner.length());
                    return true;
                }
                String inner = text.substring(st, en);
                String t = blockOpen + " " + inner + " " + blockClose;
                if (st == en) op.set(st, en, t, ol + 1, ol + 1);
                else op.set(st, en, t, ol + 1, ol + 1 + inner.length());
                return true;
            }
        });
    }

    private boolean startsWith(int at, String s) {
        if (at < 0 || at + s.length() > text.length()) return false;
        for (int i = 0; i < s.length(); i++) if (text.charAt(at + i) != s.charAt(i)) return false;
        return true;
    }

    // ------------------------------------------------------------------ line commands

    /** Line blocks [firstLine, lastLine] per selection, merged when they touch. */
    private int[][] lineBlocks() {
        ArrayList<int[]> out = new ArrayList<int[]>();
        for (int i = 0; i < sel.count; i++) {
            int l0 = text.lineOfOffset(sel.start(i));
            int e = sel.end(i);
            int l1 = text.lineOfOffset(e);
            if (l1 > l0 && e == text.lineStart(l1)) l1--;
            if (!out.isEmpty() && out.get(out.size() - 1)[1] >= l0) {
                out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], l1);
            } else out.add(new int[]{l0, l1});
        }
        return out.toArray(new int[out.size()][]);
    }

    public void duplicateLines(boolean down) {
        int[][] blocks = lineBlocks();
        begin(UndoStack.KIND_OTHER);
        for (int k = blocks.length - 1; k >= 0; k--) {
            int s = text.lineStart(blocks[k][0]), e = text.lineEnd(blocks[k][1]);
            String block = text.substring(s, e);
            if (down) {
                int[] before = sel.snapshot();
                replace(e, e, "\n" + block);
                // Selections inside the block move down with the copy.
                int shift = block.length() + 1;
                for (int i = 0; i < sel.count; i++) {
                    int a = before[1 + i * 2], h = before[2 + i * 2];
                    if (a >= s && a <= e && h >= s && h <= e) sel.setAt(i, a + shift, h + shift);
                }
            } else {
                replace(s, s, block + "\n");
            }
        }
        end();
    }

    public void moveLines(boolean up) {
        int[][] blocks = lineBlocks();
        if (up && blocks[0][0] == 0) return;
        if (!up && blocks[blocks.length - 1][1] >= text.lineCount() - 1) return;
        begin(UndoStack.KIND_OTHER);
        int[] before = sel.snapshot();
        int n = sel.count;
        int[] newA = new int[n], newH = new int[n];
        for (int i = 0; i < n; i++) {
            newA[i] = before[1 + i * 2];
            newH[i] = before[2 + i * 2];
        }
        // Each block swaps with the neighbouring line; selections in the block shift by that line's length.
        if (up) {
            for (int[] b : blocks) {
                int other = b[0] - 1;
                int os = text.lineStart(other), oe = text.lineEnd(other);
                int bs = text.lineStart(b[0]), be = text.lineEnd(b[1]);
                String o = text.substring(os, oe), blk = text.substring(bs, be);
                int shift = o.length() + 1;
                replaceRaw(os, be, blk + "\n" + o);
                for (int i = 0; i < n; i++) {
                    if (newA[i] >= bs && newA[i] <= be) newA[i] -= shift;
                    if (newH[i] >= bs && newH[i] <= be) newH[i] -= shift;
                }
            }
        } else {
            for (int k = blocks.length - 1; k >= 0; k--) {
                int[] b = blocks[k];
                int other = b[1] + 1;
                int os = text.lineStart(other), oe = text.lineEnd(other);
                int bs = text.lineStart(b[0]), be = text.lineEnd(b[1]);
                String o = text.substring(os, oe), blk = text.substring(bs, be);
                int shift = o.length() + 1;
                replaceRaw(bs, oe, o + "\n" + blk);
                for (int i = 0; i < n; i++) {
                    if (newA[i] >= bs && newA[i] <= be) newA[i] += shift;
                    if (newH[i] >= bs && newH[i] <= be) newH[i] += shift;
                }
            }
        }
        System.arraycopy(newA, 0, sel.anchor, 0, n);
        System.arraycopy(newH, 0, sel.head, 0, n);
        end();
    }

    /** Replace without remapping selections (the caller sets them). */
    private void replaceRaw(int start, int end, String s) {
        String removed = text.substring(start, end);
        if (removed.equals(s)) return;
        undo.record(start, removed, s);
        text.replace(start, end, s);
        changedInBatch = true;
    }

    public void deleteLines() {
        int[][] blocks = lineBlocks();
        begin(UndoStack.KIND_OTHER);
        int n = blocks.length;
        int[] starts = new int[n], lens = new int[n];
        for (int k = 0; k < n; k++) {
            int s = text.lineStart(blocks[k][0]);
            int e = blocks[k][1] + 1 < text.lineCount() ? text.lineStart(blocks[k][1] + 1) : text.length();
            if (blocks[k][1] + 1 >= text.lineCount() && s > 0) s--; // last line: eat the break before it
            // That break may already belong to the block just above (carets on the last two
            // lines): ranges must not overlap, or the second delete runs past the end.
            if (k > 0 && s < starts[k - 1] + lens[k - 1]) s = starts[k - 1] + lens[k - 1];
            starts[k] = s;
            lens[k] = e - s;
        }
        for (int k = n - 1; k >= 0; k--) replaceRaw(starts[k], starts[k] + lens[k], "");
        // Final caret of block k: its start minus everything removed by earlier blocks.
        if (sel.anchor.length < n) {
            sel.anchor = new int[n];
            sel.head = new int[n];
        }
        int removed = 0;
        for (int k = 0; k < n; k++) {
            int c = Math.min(starts[k] - removed, text.length());
            // After deleting the last line the offset is the end of the line above; go to its start.
            c = text.lineStart(text.lineOfOffset(c));
            sel.anchor[k] = c;
            sel.head[k] = c;
            removed += lens[k];
        }
        sel.count = n;
        sel.primary = Math.min(sel.primary, n - 1);
        end();
    }

    public void insertLine(final boolean above) {
        each(UndoStack.KIND_OTHER, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int line = text.lineOfOffset(h);
                String indent = leadingWhitespace(line);
                if (above) {
                    int ls = text.lineStart(line);
                    op.set(ls, ls, indent + "\n", indent.length(), indent.length());
                } else {
                    int le = text.lineEnd(line);
                    op.set(le, le, "\n" + indent, 1 + indent.length(), 1 + indent.length());
                }
                return true;
            }
        });
    }

    public void joinLines() {
        each(UndoStack.KIND_OTHER, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                int l0 = text.lineOfOffset(st), l1 = Math.max(text.lineOfOffset(en), l0 + 1);
                if (l1 >= text.lineCount()) return false;
                int s = text.lineStart(l0), e = text.lineEnd(l1);
                StringBuilder sb = new StringBuilder();
                for (int l = l0; l <= l1; l++) {
                    String t = text.lineText(l);
                    if (l > l0) {
                        t = t.replaceFirst("^[ \\t]+", "");
                        if (sb.length() > 0 && !t.isEmpty() && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                    }
                    sb.append(t);
                }
                int caret = text.lineEnd(l0) - s;
                op.set(s, e, sb.toString(), caret, caret);
                return true;
            }
        });
    }

    public void transformCase(final int mode) {
        each(UndoStack.KIND_OTHER, new PerSel() {
            @Override
            public boolean edit(int a, int h, Op op) {
                int st = Math.min(a, h), en = Math.max(a, h);
                if (st == en) {
                    st = wordStart(st);
                    en = wordEnd(en);
                    if (st == en) return false;
                }
                String s = text.substring(st, en), t;
                if (mode == 0) t = s.toUpperCase(Locale.ROOT);
                else if (mode == 1) t = s.toLowerCase(Locale.ROOT);
                else {
                    StringBuilder sb = new StringBuilder(s.length());
                    boolean up = true;
                    for (int i = 0; i < s.length(); i++) {
                        char c = s.charAt(i);
                        sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                        up = !Character.isLetterOrDigit(c);
                    }
                    t = sb.toString();
                }
                if (h >= a) op.set(st, en, t, 0, t.length());
                else op.set(st, en, t, t.length(), 0);
                return true;
            }
        });
    }

    public void sortLines(boolean descending) {
        int[][] blocks = lineBlocks();
        begin(UndoStack.KIND_OTHER);
        for (int k = blocks.length - 1; k >= 0; k--) {
            if (blocks[k][0] == blocks[k][1]) continue;
            int s = text.lineStart(blocks[k][0]), e = text.lineEnd(blocks[k][1]);
            ArrayList<String> ls = new ArrayList<String>(Arrays.asList(text.substring(s, e).split("\n", -1)));
            Collections.sort(ls);
            if (descending) Collections.reverse(ls);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ls.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(ls.get(i));
            }
            replace(s, e, sb.toString());
        }
        end();
    }

    public void trimTrailingWhitespace() {
        begin(UndoStack.KIND_OTHER);
        for (int l = text.lineCount() - 1; l >= 0; l--) {
            int s = text.lineStart(l), e = text.lineEnd(l), i = e;
            while (i > s && (text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) i--;
            if (i < e) replace(i, e, "");
        }
        end();
    }

    // ------------------------------------------------------------------ undo

    public boolean undo() {
        if (depth != 0) return false;
        int[] s = undo.undo(text);
        if (s == null) return false;
        sel.restore(s, text.length());
        fire(true);
        return true;
    }

    public boolean redo() {
        if (depth != 0) return false;
        int[] s = undo.redo(text);
        if (s == null) return false;
        sel.restore(s, text.length());
        fire(true);
        return true;
    }

    // ------------------------------------------------------------------ caret movement (logical)

    public static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int charClass(char c) {
        if (isWordChar(c)) return 2;
        if (Character.isWhitespace(c)) return 0;
        return 1;
    }

    public int wordLeft(int p) {
        if (p == 0) return 0;
        int ls = text.lineStart(text.lineOfOffset(p));
        if (p == ls) return p - 1;
        int i = p;
        while (i > ls && (text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) i--;
        if (i == ls) return i;
        int cls = charClass(text.charAt(i - 1));
        while (i > ls && charClass(text.charAt(i - 1)) == cls) i--;
        return i;
    }

    public int wordRight(int p) {
        int len = text.length();
        if (p >= len) return len;
        int le = text.lineEnd(text.lineOfOffset(p));
        if (p == le) return p + 1;
        int i = p;
        while (i < le && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i == le) return i;
        int cls = charClass(text.charAt(i));
        while (i < le && charClass(text.charAt(i)) == cls) i++;
        return i;
    }

    public int wordStart(int p) {
        while (p > 0 && isWordChar(text.charAt(p - 1))) p--;
        return p;
    }

    public int wordEnd(int p) {
        int len = text.length();
        while (p < len && isWordChar(text.charAt(p))) p++;
        return p;
    }

    public static final int MOVE_LEFT = 0, MOVE_RIGHT = 1, MOVE_WORD_LEFT = 2, MOVE_WORD_RIGHT = 3,
            MOVE_HOME = 4, MOVE_END = 5, MOVE_DOC_START = 6, MOVE_DOC_END = 7;

    /** Logical caret movement for every selection. Vertical moves need layout and live in the view. */
    public void move(int how, boolean extend) {
        int len = text.length();
        for (int i = 0; i < sel.count; i++) {
            int a = sel.anchor[i], h = sel.head[i];
            int st = Math.min(a, h), en = Math.max(a, h);
            int nh;
            switch (how) {
                case MOVE_LEFT:
                    if (!extend && st != en) { nh = st; break; }
                    nh = h > 0 ? h - 1 : 0;
                    if (nh > 0 && Character.isLowSurrogate(text.charAt(nh)) && Character.isHighSurrogate(text.charAt(nh - 1))) nh--;
                    break;
                case MOVE_RIGHT:
                    if (!extend && st != en) { nh = en; break; }
                    nh = h < len ? h + 1 : len;
                    if (nh < len && Character.isLowSurrogate(text.charAt(nh)) && Character.isHighSurrogate(text.charAt(nh - 1))) nh++;
                    break;
                case MOVE_WORD_LEFT: nh = wordLeft(h); break;
                case MOVE_WORD_RIGHT: nh = wordRight(h); break;
                case MOVE_HOME: nh = smartHome(h); break;
                case MOVE_END: nh = text.lineEnd(text.lineOfOffset(h)); break;
                case MOVE_DOC_START: nh = 0; break;
                default: nh = len; break;
            }
            sel.anchor[i] = extend ? a : nh;
            sel.head[i] = nh;
        }
        sel.normalize();
        fire(false);
    }

    /** Home toggles between the first non-blank char and column 0. */
    public int smartHome(int p) {
        int line = text.lineOfOffset(p);
        int ls = text.lineStart(line);
        int fnb = ls + leadingWhitespace(line).length();
        return p == fnb ? ls : fnb;
    }

    public void selectAll() {
        sel.set(0, text.length());
        fire(false);
    }

    /** Expands every selection to whole lines (repeat to grow). */
    public void selectLine() {
        for (int i = 0; i < sel.count; i++) {
            int st = sel.start(i), en = sel.end(i);
            int l0 = text.lineOfOffset(st), l1 = text.lineOfOffset(en);
            int s = text.lineStart(l0);
            if (st != en && en == text.lineStart(l1) && l1 > l0) l1--;
            int e = l1 + 1 < text.lineCount() ? text.lineStart(l1 + 1) : text.length();
            if (st == s && en == e && e < text.length()) {
                int l2 = text.lineOfOffset(e);
                e = l2 + 1 < text.lineCount() ? text.lineStart(l2 + 1) : text.length();
            }
            sel.anchor[i] = s;
            sel.head[i] = e;
        }
        sel.normalize();
        fire(false);
    }

    /** The word at {@code p}, or an empty range. */
    public int[] wordAt(int p) {
        int s = wordStart(p), e = wordEnd(p);
        return new int[]{s, e};
    }

    /**
     * Ctrl+D: selects the word at the caret, or adds the next occurrence of the primary selection
     * as a new selection. Returns false when nothing more was found.
     */
    public boolean addNextOccurrence() {
        int pi = sel.primary;
        if (sel.isEmpty(pi)) {
            int[] w = wordAt(sel.head[pi]);
            if (w[0] == w[1]) return false;
            sel.setAt(pi, w[0], w[1]);
            fire(false);
            return true;
        }
        String needle = text.substring(sel.start(pi), sel.end(pi));
        int from = sel.end(pi);
        int found = indexOf(needle, from);
        if (found < 0) found = indexOf(needle, 0);
        // Skip occurrences that are already selected.
        int guard = 0;
        while (found >= 0 && isSelected(found, found + needle.length()) && guard++ < sel.count + 1) {
            int next = indexOf(needle, found + 1);
            if (next < 0) next = indexOf(needle, 0);
            if (next == found) return false;
            found = next;
        }
        if (found < 0 || isSelected(found, found + needle.length())) return false;
        sel.add(found, found + needle.length());
        fire(false);
        return true;
    }

    public boolean selectAllOccurrences() {
        int pi = sel.primary;
        if (sel.isEmpty(pi)) {
            int[] w = wordAt(sel.head[pi]);
            if (w[0] == w[1]) return false;
            sel.setAt(pi, w[0], w[1]);
        }
        String needle = text.substring(sel.start(pi), sel.end(pi));
        int keep = sel.start(pi);
        sel.count = 0;
        int p = indexOf(needle, 0);
        int prim = 0;
        while (p >= 0) {
            if (sel.count == sel.anchor.length) {
                sel.anchor = Arrays.copyOf(sel.anchor, sel.count * 2);
                sel.head = Arrays.copyOf(sel.head, sel.count * 2);
            }
            if (p == keep) prim = sel.count;
            sel.anchor[sel.count] = p;
            sel.head[sel.count] = p + needle.length();
            sel.count++;
            p = indexOf(needle, p + Math.max(1, needle.length()));
        }
        if (sel.count == 0) sel.setCaret(keep);
        sel.primary = prim;
        fire(false);
        return true;
    }

    private boolean isSelected(int s, int e) {
        for (int i = 0; i < sel.count; i++) if (sel.start(i) == s && sel.end(i) == e) return true;
        return false;
    }

    public int indexOf(String needle, int from) {
        int n = needle.length(), len = text.length();
        if (n == 0) return -1;
        char first = needle.charAt(0);
        for (int i = Math.max(0, from); i <= len - n; i++) {
            if (text.charAt(i) != first) continue;
            int k = 1;
            while (k < n && text.charAt(i + k) == needle.charAt(k)) k++;
            if (k == n) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ brackets

    /**
     * The bracket matching the one just before or at {@code p}: returns {open, close} offsets or null.
     * Scans at most {@code limit} chars.
     */
    public int[] matchBracket(int p, int limit) {
        int len = text.length();
        int[] r = null;
        if (p > 0) r = matchAt(p - 1, limit);
        if (r == null && p < len) r = matchAt(p, limit);
        return r;
    }

    private int[] matchAt(int i, int limit) {
        char c = text.charAt(i);
        int oi = "([{".indexOf(c), ci = ")]}".indexOf(c);
        if (oi >= 0) {
            char close = ")]}".charAt(oi);
            int d = 0, len = text.length(), stop = Math.min(len, i + limit);
            for (int k = i; k < stop; k++) {
                char x = text.charAt(k);
                if (x == c) d++;
                else if (x == close && --d == 0) return new int[]{i, k};
            }
        } else if (ci >= 0) {
            char open = "([{".charAt(ci);
            int d = 0, stop = Math.max(0, i - limit);
            for (int k = i; k >= stop; k--) {
                char x = text.charAt(k);
                if (x == c) d++;
                else if (x == open && --d == 0) return new int[]{k, i};
            }
        }
        return null;
    }

    /** Jumps every caret to its matching bracket. */
    public void jumpToBracket() {
        for (int i = 0; i < sel.count; i++) {
            int[] m = matchBracket(sel.head[i], 1 << 20);
            if (m == null) continue;
            int h = sel.head[i];
            int t = (h == m[0] || h == m[0] + 1) ? m[1] : m[0];
            sel.anchor[i] = t;
            sel.head[i] = t;
        }
        sel.normalize();
        fire(false);
    }

    /** Expands each selection to the enclosing word, then string/bracket contents, then brackets, then lines. */
    public void expandSelection() {
        for (int i = 0; i < sel.count; i++) {
            int st = sel.start(i), en = sel.end(i);
            int[] w = wordAt(st);
            if (st == en && w[0] < w[1]) {
                sel.anchor[i] = w[0];
                sel.head[i] = w[1];
                continue;
            }
            int[] b = enclosingBrackets(st, en);
            if (b != null) {
                if (st == b[0] + 1 && en == b[1]) {
                    sel.anchor[i] = b[0];
                    sel.head[i] = b[1] + 1;
                } else {
                    sel.anchor[i] = b[0] + 1;
                    sel.head[i] = b[1];
                }
                continue;
            }
            sel.anchor[i] = 0;
            sel.head[i] = text.length();
        }
        sel.normalize();
        fire(false);
    }

    private int[] enclosingBrackets(int st, int en) {
        int d0 = 0, d1 = 0, d2 = 0;
        for (int k = st - 1; k >= 0 && st - k < (1 << 20); k--) {
            char c = text.charAt(k);
            int ci = ")]}".indexOf(c), oi = "([{".indexOf(c);
            if (ci == 0) d0++;
            else if (ci == 1) d1++;
            else if (ci == 2) d2++;
            else if (oi >= 0) {
                int d = oi == 0 ? d0 : oi == 1 ? d1 : d2;
                if (d > 0) {
                    if (oi == 0) d0--;
                    else if (oi == 1) d1--;
                    else d2--;
                    continue;
                }
                int[] m = matchAt(k, 1 << 20);
                if (m != null && m[1] >= en) return m;
            }
        }
        return null;
    }
}
