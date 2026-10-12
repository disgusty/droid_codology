package com.disgusty.droid_codology.lang;

import com.disgusty.droid_codology.text.TextBuffer;

import java.util.Arrays;

/**
 * Incremental highlighting. Keeps the lexer state at the start of every line; after an edit only
 * the touched lines are re-lexed, and re-lexing stops as soon as a line ends in the same state as
 * before (everything below is then still valid). Tokens themselves are produced on demand for the
 * lines being drawn and cached in a small ring.
 */
public final class Highlighter implements TextBuffer.Listener {
    private final TextBuffer text;
    private Lexer lexer;

    /** states[i] = lexer state at the start of line i; known for i <= valid. */
    private int[] states = new int[1024];
    private int valid;
    /** Old states between valid+1 and oldValid may still be right if lexing converges. */
    private int oldValid;
    /** Lines from here on have unchanged text since the last edit (convergence is meaningful). */
    private int convergeFrom;

    private static final int CACHE = 256;
    private final int[] cacheLine = new int[CACHE];
    private final int[][] cacheTok = new int[CACHE][];
    private final Lexer.Tokens tmp = new Lexer.Tokens();
    private char[] lineBuf = new char[256];

    /** Lines longer than this are not lexed (minified files): drawn plain. */
    public static final int MAX_LINE = 20000;

    public Highlighter(TextBuffer text, Lexer lexer) {
        this.text = text;
        this.lexer = lexer;
        Arrays.fill(cacheLine, -1);
        text.addListener(this);
    }

    public void detach() {
        text.removeListener(this);
    }

    public void setLexer(Lexer l) {
        lexer = l;
        valid = 0;
        oldValid = 0;
        convergeFrom = 0;
        Arrays.fill(cacheLine, -1);
    }

    public Lexer lexer() {
        return lexer;
    }

    @Override
    public void onReplaced(int start, int removedLen, int insertedLen, int firstLine, int removedLines, int insertedLines) {
        int diff = insertedLines - removedLines;
        int lc = text.lineCount();
        if (states.length < lc + 1) states = Arrays.copyOf(states, Math.max(lc + 1, states.length * 3 / 2));
        int tailOld = firstLine + removedLines + 1;
        int newConverge = firstLine + insertedLines + 1;
        if (oldValid > valid) {
            // An earlier edit is still unresolved: its changed lines start at `valid`.
            if (firstLine < valid) {
                // Stale states past `valid` depend on text that changed; keep only those before it.
                oldValid = valid;
            } else {
                int prev = convergeFrom > firstLine + removedLines ? convergeFrom + diff : convergeFrom;
                newConverge = Math.max(newConverge, prev);
            }
        }
        // Shift old states of the untouched tail so convergence can reuse them.
        int prevValidTail = Math.max(valid, oldValid);
        if (diff != 0 && tailOld <= prevValidTail) {
            int n = Math.min(prevValidTail, lc - diff) - tailOld + 1;
            if (n > 0) System.arraycopy(states, tailOld, states, tailOld + diff, n);
        }
        int newOldValid = prevValidTail >= tailOld ? prevValidTail + diff : -1;
        if (valid > firstLine) valid = firstLine;
        oldValid = Math.max(newOldValid, valid);
        convergeFrom = newConverge;
        Arrays.fill(cacheLine, -1);
    }

    /** Lexes forward until line {@code upTo} has a known start state or the time budget runs out. */
    public boolean ensure(int upTo, long budgetNanos) {
        int lc = text.lineCount();
        if (upTo >= lc) upTo = lc - 1;
        if (valid >= upTo) return true;
        long t0 = System.nanoTime();
        if (states.length < lc + 1) states = Arrays.copyOf(states, lc + 1);
        int k = 0;
        while (valid < upTo) {
            int line = valid;
            int end = lexLine(line, states[line], null);
            int next = line + 1;
            if (next >= convergeFrom && next <= oldValid && states[next] == end) {
                // Converged: everything up to oldValid is still correct.
                valid = oldValid;
                oldValid = valid;
                continue;
            }
            states[next] = end;
            valid = next;
            if (valid > oldValid) oldValid = valid;
            if ((++k & 63) == 0 && System.nanoTime() - t0 > budgetNanos) return valid >= upTo;
        }
        return true;
    }

    public int validLines() {
        return valid;
    }

    /** Tokens for a line as (start, style) pairs, or null if its start state isn't known yet. */
    public int[] tokens(int line) {
        if (line > valid) return null;
        int slot = line & (CACHE - 1);
        if (cacheLine[slot] == line) return cacheTok[slot];
        tmp.clear();
        int end = lexLine(line, states[line], tmp);
        if (line + 1 < text.lineCount() && line + 1 > valid) {
            if (states.length <= line + 1) states = Arrays.copyOf(states, line + 2);
            if (!(line + 1 >= convergeFrom && line + 1 <= oldValid && states[line + 1] == end)) {
                states[line + 1] = end;
                valid = line + 1;
                if (valid > oldValid) oldValid = valid;
            } else {
                valid = oldValid;
            }
        }
        int[] t = tmp.toArray();
        cacheLine[slot] = line;
        cacheTok[slot] = t;
        return t;
    }

    /** State at the start of {@code line} (valid only if line <= validLines()). */
    public int stateAt(int line) {
        return states[line];
    }

    private int lexLine(int line, int state, Lexer.Tokens out) {
        int s = text.lineStart(line), e = text.lineEnd(line);
        int len = e - s;
        if (len > MAX_LINE) {
            if (out != null) out.add(0, Tok.PLAIN);
            return state;
        }
        if (lineBuf.length < len) lineBuf = new char[Math.max(len, lineBuf.length * 2)];
        text.getChars(s, e, lineBuf, 0);
        Lexer.Tokens t = out != null ? out : tmp;
        if (out == null) tmp.clear();
        try {
            return lexer.lex(lineBuf, len, state, t);
        } catch (RuntimeException ex) {
            // A buggy (e.g. extension) lexer must never break editing.
            t.clear();
            t.add(0, Tok.PLAIN);
            return state;
        }
    }
}
