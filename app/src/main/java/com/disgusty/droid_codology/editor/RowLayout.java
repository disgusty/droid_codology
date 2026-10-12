package com.disgusty.droid_codology.editor;

import com.disgusty.droid_codology.text.TextBuffer;

import java.util.Arrays;

/**
 * Maps logical lines to visual rows. Without word wrap every line is one row. With word wrap the
 * row count of each line is kept in an array and summed by a Fenwick tree, so row↔line lookups
 * stay O(log n) even for huge files; the actual break offsets are computed only for drawn lines.
 */
final class RowLayout implements TextBuffer.Listener {
    interface Measure {
        /** Advance of {@code c} when it starts at {@code x} (tabs depend on x). */
        float advance(char c, float x);
    }

    private final TextBuffer text;
    private final Measure m;
    private boolean wrap;
    private float maxW;
    /** Collapsed folds hide lines (0 rows); null = no folding. */
    private com.disgusty.droid_codology.text.Folding folding;
    /** Row counts are tracked explicitly (word wrap or hidden lines); otherwise row == line. */
    private boolean explicit;

    private int[] rows = new int[0];
    private int[] fen = new int[1];
    private int lines;
    private int total;
    private boolean fenDirty;

    // Small cache of break offsets (absolute text offsets of each continuation row start).
    private static final int CACHE = 128;
    private final int[] cacheLine = new int[CACHE];
    private final int[][] cacheBreaks = new int[CACHE][];
    private final float[] cacheIndent = new float[CACHE];

    RowLayout(TextBuffer text, Measure m) {
        this.text = text;
        this.m = m;
        Arrays.fill(cacheLine, -1);
        text.addListener(this);
    }

    void detach() {
        text.removeListener(this);
    }

    boolean wraps() {
        return wrap;
    }

    void setFolding(com.disgusty.droid_codology.text.Folding f) {
        folding = f;
    }

    /** Recomputes all row counts after folds opened or closed. */
    void refold() {
        boolean want = wrap || (folding != null && folding.hasCollapsed());
        Arrays.fill(cacheLine, -1);
        if (!want) {
            explicit = false;
            rows = new int[0];
            return;
        }
        explicit = true;
        lines = text.lineCount();
        rows = new int[Math.max(16, lines + lines / 4)];
        for (int l = 0; l < lines; l++) rows[l] = rowsFor(l);
        rebuild();
    }

    private int rowsFor(int line) {
        if (folding != null && folding.isHidden(line)) return 0;
        return wrap ? countRows(line) : 1;
    }

    /** Turns wrapping on (width > 0) or off, recomputing all row counts if needed. */
    void configure(boolean wrap, float width) {
        if (wrap && width < 40) width = 40;
        if (this.wrap == wrap && (!wrap || Math.abs(width - maxW) < 0.5f)) return;
        this.wrap = wrap;
        this.maxW = width;
        refold();
    }

    void invalidateMeasure() {
        if (!wrap) {
            refold();
            return;
        }
        boolean w = wrap;
        float width = maxW;
        this.wrap = false;
        configure(w, width);
    }

    @Override
    public void onReplaced(int start, int removedLen, int insertedLen, int firstLine, int removedLines, int insertedLines) {
        Arrays.fill(cacheLine, -1);
        if (!explicit) return;
        int diff = insertedLines - removedLines;
        // CodeView's listener runs first and may already have rebuilt the table for the new text
        // (the gutter grew with the line count, so wrapping was reconfigured): shifting it again
        // would double the change.
        if (diff != 0 && lines == text.lineCount()) return;
        int newLines = lines + diff;
        if (diff != 0) {
            if (rows.length < newLines) rows = Arrays.copyOf(rows, newLines + newLines / 4 + 16);
            int tailFrom = firstLine + removedLines + 1;
            System.arraycopy(rows, tailFrom, rows, tailFrom + diff, lines - tailFrom);
            lines = newLines;
        }
        for (int l = firstLine; l <= firstLine + insertedLines && l < lines; l++) {
            int n = rowsFor(l);
            if (diff == 0 && !fenDirty) {
                int d = n - rows[l];
                if (d != 0) {
                    fenAdd(l, d);
                    total += d;
                }
            }
            rows[l] = n;
        }
        if (diff != 0) fenDirty = true;
    }

    private void rebuild() {
        if (fen.length < lines + 1) fen = new int[rows.length + 1];
        else Arrays.fill(fen, 0);
        total = 0;
        for (int i = 1; i <= lines; i++) {
            fen[i] += rows[i - 1];
            total += rows[i - 1];
            int j = i + (i & -i);
            if (j <= lines) fen[j] += fen[i];
        }
        fenDirty = false;
    }

    private void fenAdd(int line, int d) {
        for (int i = line + 1; i <= lines; i += i & -i) fen[i] += d;
    }

    private void ensureFen() {
        if (fenDirty) rebuild();
    }

    int rowCount() {
        if (!explicit) return text.lineCount();
        ensureFen();
        return total;
    }

    /** First row of a line. */
    int rowOfLine(int line) {
        if (!explicit) return line;
        ensureFen();
        int s = 0;
        for (int i = line; i > 0; i -= i & -i) s += fen[i];
        return s;
    }

    /** The line that contains visual row {@code row}. */
    int lineOfRow(int row) {
        if (!explicit) return Math.max(0, Math.min(row, text.lineCount() - 1));
        ensureFen();
        if (row <= 0) return 0;
        int pos = 0, rem = row;
        int step = Integer.highestOneBit(Math.max(1, lines));
        for (; step > 0; step >>= 1) {
            int next = pos + step;
            if (next <= lines && fen[next] <= rem) {
                pos = next;
                rem -= fen[next];
            }
        }
        return Math.min(pos, lines - 1);
    }

    int rowsOfLine(int line) {
        return explicit ? Math.max(1, rows[line]) : 1;
    }

    /** Absolute offsets where the continuation rows of {@code line} start (empty if not wrapped). */
    int[] breaks(int line) {
        if (!wrap) return EMPTY;
        int slot = line & (CACHE - 1);
        if (cacheLine[slot] == line) return cacheBreaks[slot];
        int[] b = computeBreaks(line, null);
        cacheLine[slot] = line;
        cacheBreaks[slot] = b;
        cacheIndent[slot] = wrapIndent(line);
        return b;
    }

    /** X offset of continuation rows (they line up with the line's indentation). */
    float continuationIndent(int line) {
        if (!wrap) return 0;
        int slot = line & (CACHE - 1);
        if (cacheLine[slot] != line) breaks(line);
        return cacheIndent[slot];
    }

    static final int[] EMPTY = new int[0];

    private float wrapIndent(int line) {
        int s = text.lineStart(line), e = text.lineEnd(line);
        float x = 0;
        for (int i = s; i < e; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t') break;
            x += m.advance(c, x);
        }
        return x > maxW / 2 ? 0 : x;
    }

    private int countRows(int line) {
        int[] counter = new int[1];
        computeBreaks(line, counter);
        return counter[0] + 1;
    }

    /** Word-boundary wrapping; when {@code count} is given only the number of breaks is produced. */
    private int[] computeBreaks(int line, int[] count) {
        int s = text.lineStart(line), e = text.lineEnd(line);
        float indent = wrapIndent(line);
        float x = 0;
        int rowStart = s, lastBreak = -1, n = 0;
        int[] out = count == null ? new int[4] : null;
        for (int i = s; i < e; i++) {
            char c = text.charAt(i);
            float w = m.advance(c, x);
            if (x + w > maxW && i > rowStart) {
                int at = lastBreak > rowStart ? lastBreak : i;
                if (out != null) {
                    if (n == out.length) out = Arrays.copyOf(out, n * 2);
                    out[n] = at;
                }
                n++;
                rowStart = at;
                lastBreak = -1;
                // Re-measure from the break.
                x = indent;
                for (int k = at; k < i; k++) x += m.advance(text.charAt(k), x);
                if (x + w > maxW && i > rowStart) {
                    // The single word doesn't fit even alone; break right here.
                    if (out != null) {
                        if (n == out.length) out = Arrays.copyOf(out, n * 2);
                        out[n] = i;
                    }
                    n++;
                    rowStart = i;
                    x = indent;
                }
            }
            x += w;
            if (c == ' ' || c == '\t' || c == ',' || c == ';' || c == ')' || c == '-' || c == '/' || c == '.') lastBreak = i + 1;
        }
        if (count != null) {
            count[0] = n;
            return null;
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }
}
