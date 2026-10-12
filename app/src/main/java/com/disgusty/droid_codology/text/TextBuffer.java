package com.disgusty.droid_codology.text;

/**
 * The document text: a gap buffer of chars plus an index of line starts.
 *
 * <p>Line breaks are always stored as a single '\n' (the file's original style is restored on
 * save). Typing is local, so the gap stays near the caret and inserts are O(1) amortized. Line
 * starts after an edit are shifted lazily: entries from {@code dirtyFrom} on are stored without the
 * pending {@code dirtyDelta}, and the delta is folded in only across the lines that later edits
 * actually step over. An edit therefore costs time proportional to its distance from the previous
 * one, not to the size of the file.
 */
public final class TextBuffer implements CharSequence {
    private char[] buf;
    private int gapStart, gapEnd;

    private int[] lines = new int[64];
    private int lineCount = 1;
    private int dirtyFrom = 1, dirtyDelta;

    /** Bumped on every change; lets caches (layout, highlighting, search) detect staleness. */
    private int version;

    public interface Listener {
        /**
         * Called after {@code [start, start + removedLen)} was replaced by {@code insertedLen} chars.
         * {@code firstLine} is the line containing {@code start}; {@code removedLines} and
         * {@code insertedLines} count the line breaks removed and inserted.
         */
        void onReplaced(int start, int removedLen, int insertedLen, int firstLine, int removedLines, int insertedLines);
    }

    private Listener[] listeners = new Listener[0];

    public TextBuffer() {
        this(new char[256], 0);
    }

    /** Takes ownership of {@code chars[0, len)}; the text must already use '\n' line breaks. */
    public TextBuffer(char[] chars, int len) {
        int cap = Math.max(len + 1024, (int) (len * 1.1f));
        if (chars.length >= cap) {
            buf = chars;
        } else {
            buf = new char[cap];
            System.arraycopy(chars, 0, buf, 0, len);
        }
        gapStart = len;
        gapEnd = buf.length;
        lines[0] = 0;
        for (int i = 0; i < len; i++) {
            if (buf[i] == '\n') addLineRaw(i + 1);
        }
    }

    public static TextBuffer of(CharSequence s) {
        char[] c = new char[s.length()];
        for (int i = 0; i < c.length; i++) c[i] = s.charAt(i);
        return new TextBuffer(c, c.length);
    }

    private void addLineRaw(int start) {
        if (lineCount == lines.length) lines = grow(lines, lineCount + 1);
        lines[lineCount++] = start;
        dirtyFrom = lineCount;
    }

    private static int[] grow(int[] a, int need) {
        int[] n = new int[Math.max(need, a.length + (a.length >> 1) + 16)];
        System.arraycopy(a, 0, n, 0, a.length);
        return n;
    }

    public void addListener(Listener l) {
        Listener[] n = new Listener[listeners.length + 1];
        System.arraycopy(listeners, 0, n, 0, listeners.length);
        n[listeners.length] = l;
        listeners = n;
    }

    public void removeListener(Listener l) {
        for (int i = 0; i < listeners.length; i++) {
            if (listeners[i] == l) {
                Listener[] n = new Listener[listeners.length - 1];
                System.arraycopy(listeners, 0, n, 0, i);
                System.arraycopy(listeners, i + 1, n, i, n.length - i);
                listeners = n;
                return;
            }
        }
    }

    public int version() {
        return version;
    }

    // ------------------------------------------------------------------ CharSequence

    @Override
    public int length() {
        return buf.length - (gapEnd - gapStart);
    }

    @Override
    public char charAt(int i) {
        return i < gapStart ? buf[i] : buf[i + gapEnd - gapStart];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return substring(start, end);
    }

    @Override
    public String toString() {
        return substring(0, length());
    }

    public String substring(int start, int end) {
        if (end <= start) return "";
        char[] c = new char[end - start];
        getChars(start, end, c, 0);
        return new String(c);
    }

    /** Copies {@code [start, end)} into {@code dst} at {@code off}. */
    public void getChars(int start, int end, char[] dst, int off) {
        if (end <= gapStart) {
            System.arraycopy(buf, start, dst, off, end - start);
        } else if (start >= gapStart) {
            System.arraycopy(buf, start + gapEnd - gapStart, dst, off, end - start);
        } else {
            int a = gapStart - start;
            System.arraycopy(buf, start, dst, off, a);
            System.arraycopy(buf, gapEnd, dst, off + a, end - gapStart);
        }
    }

    // ------------------------------------------------------------------ lines

    public int lineCount() {
        return lineCount;
    }

    public int lineStart(int line) {
        int v = lines[line];
        return line >= dirtyFrom ? v + dirtyDelta : v;
    }

    /** Offset of the end of the line, before its '\n'. */
    public int lineEnd(int line) {
        return line + 1 < lineCount ? lineStart(line + 1) - 1 : length();
    }

    public int lineLength(int line) {
        return lineEnd(line) - lineStart(line);
    }

    public String lineText(int line) {
        return substring(lineStart(line), lineEnd(line));
    }

    /** The line containing {@code offset} (an offset just after a '\n' belongs to the next line). */
    public int lineOfOffset(int offset) {
        int lo = 0, hi = lineCount - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStart(mid) <= offset) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** Moves the lazy-delta boundary to {@code idx}, touching only the lines in between. */
    private void settle(int idx) {
        int d = dirtyDelta;
        if (d == 0) {
            dirtyFrom = idx;
            return;
        }
        int[] a = lines;
        if (dirtyFrom < idx) {
            for (int i = dirtyFrom; i < idx; i++) a[i] += d;
        } else {
            for (int i = idx; i < dirtyFrom; i++) a[i] -= d;
        }
        dirtyFrom = idx;
    }

    // ------------------------------------------------------------------ editing

    public void insert(int at, CharSequence s) {
        replace(at, at, s);
    }

    public void delete(int start, int end) {
        replace(start, end, "");
    }

    /** Replaces {@code [start, end)} with {@code s}. Any "\r\n" or "\r" in {@code s} must already be converted. */
    public void replace(int start, int end, CharSequence s) {
        int len = length();
        if (start < 0 || end > len || start > end) {
            throw new IndexOutOfBoundsException("replace " + start + ".." + end + " of " + len);
        }
        int ins = s.length();
        int removed = end - start;
        if (removed == 0 && ins == 0) return;

        int l0 = lineOfOffset(start);
        int l1 = removed == 0 ? l0 : lineOfOffset(end);
        int newBreaks = 0;
        for (int i = 0; i < ins; i++) if (s.charAt(i) == '\n') newBreaks++;
        int oldBreaks = l1 - l0;

        // Text.
        moveGap(start);
        gapEnd += removed;
        if (gapEnd - gapStart < ins) growGap(ins);
        for (int i = 0; i < ins; i++) buf[gapStart + i] = s.charAt(i);
        gapStart += ins;

        // Line index: entries l0+1..l1 go away, newBreaks exact entries come in, the rest shift.
        settle(l1 + 1);
        int diff = newBreaks - oldBreaks;
        if (diff != 0) {
            if (lineCount + diff > lines.length) lines = grow(lines, lineCount + diff);
            System.arraycopy(lines, l1 + 1, lines, l1 + 1 + diff, lineCount - l1 - 1);
            lineCount += diff;
        }
        int w = l0 + 1;
        for (int i = 0; i < ins; i++) if (s.charAt(i) == '\n') lines[w++] = start + i + 1;
        dirtyFrom = l0 + 1 + newBreaks;
        dirtyDelta += ins - removed;
        if (dirtyFrom >= lineCount) {
            dirtyFrom = lineCount;
            dirtyDelta = 0;
        }

        version++;
        Listener[] ls = listeners;
        for (Listener l : ls) l.onReplaced(start, removed, ins, l0, oldBreaks, newBreaks);
    }

    private void moveGap(int pos) {
        if (pos == gapStart) return;
        if (pos < gapStart) {
            int n = gapStart - pos;
            System.arraycopy(buf, pos, buf, gapEnd - n, n);
            gapStart -= n;
            gapEnd -= n;
        } else {
            int n = pos - gapStart;
            System.arraycopy(buf, gapEnd, buf, gapStart, n);
            gapStart += n;
            gapEnd += n;
        }
    }

    private void growGap(int need) {
        int len = length();
        int cap = Math.max(len + need + 1024, (int) ((len + need) * 1.5f));
        char[] n = new char[cap];
        System.arraycopy(buf, 0, n, 0, gapStart);
        int tail = buf.length - gapEnd;
        System.arraycopy(buf, gapEnd, n, cap - tail, tail);
        gapEnd = cap - tail;
        buf = n;
    }

    /** Copies the whole text out (used when saving). */
    public char[] toCharArray() {
        char[] c = new char[length()];
        getChars(0, c.length, c, 0);
        return c;
    }
}
