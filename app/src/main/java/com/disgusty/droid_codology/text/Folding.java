package com.disgusty.droid_codology.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code folding: ranges by indentation (VS Code's default strategy, honoring the off-side rule for
 * Python/YAML) plus #region / #endregion markers, and the set of collapsed ranges. Collapsed ranges
 * move with edits and open when an edit touches their hidden lines.
 */
public final class Folding implements TextBuffer.Listener {
    private final TextBuffer text;
    public int tabSize = 4;
    /** Off-side languages: trailing blank lines don't belong to a block. */
    public boolean offSide;

    private int[] rStart = new int[0], rEnd = new int[0];
    private boolean dirty = true;
    private int computedVersion = -1;

    /** Collapsed ranges as {start, end} line pairs, sorted by start. */
    private final ArrayList<int[]> collapsed = new ArrayList<int[]>();

    public interface Listener {
        void onFoldsChanged();
    }

    private Listener listener;

    private static final Pattern REGION_START = Pattern.compile("^\\s*(?://|#|<!--|--|/\\*|;|')\\s*#?region\\b");
    private static final Pattern REGION_END = Pattern.compile("^\\s*(?://|#|<!--|--|/\\*|;|')\\s*#?endregion\\b");

    public Folding(TextBuffer text) {
        this.text = text;
        text.addListener(this);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------ ranges

    /** Recomputes ranges if the text changed (O(lines)); cheap to call often. */
    public void ensureRanges() {
        if (!dirty && computedVersion == text.version()) return;
        compute();
    }

    public int rangeCount() {
        ensureRanges();
        return rStart.length;
    }

    /** Range count without recomputing (may lag behind edits; callers clamp to the line count). */
    public int rangeCountStale() {
        return rStart.length;
    }

    public boolean hasRanges() {
        return rStart.length > 0;
    }

    /** True if edits happened since the ranges were computed. */
    public boolean isStale() {
        return dirty || computedVersion != text.version();
    }

    /** Like {@link #rangeEndAt} but never recomputes. */
    public int rangeEndAtStale(int line) {
        int i = Arrays.binarySearch(rStart, line);
        return i >= 0 && rEnd[i] < text.lineCount() ? rEnd[i] : -1;
    }

    public int rangeStart(int i) {
        return rStart[i];
    }

    public int rangeEnd(int i) {
        return rEnd[i];
    }

    /** The end line of the range starting at {@code line}, or -1. */
    public int rangeEndAt(int line) {
        ensureRanges();
        int i = Arrays.binarySearch(rStart, line);
        return i >= 0 ? rEnd[i] : -1;
    }

    private void compute() {
        int n = text.lineCount();
        int[] indent = new int[n];
        for (int l = 0; l < n; l++) indent[l] = indentOf(l);
        int[] sStart = new int[64], sEnd = new int[64];
        int count = 0;
        int[] stackLine = new int[64], stackInd = new int[64];
        int sp = 0;
        int prevNonBlank = -1;
        for (int l = 0; l <= n; l++) {
            int ind = l < n ? indent[l] : -2;
            if (l < n && ind < 0) continue;
            while (sp > 0 && (l == n || stackInd[sp - 1] >= ind)) {
                int s = stackLine[--sp];
                if (prevNonBlank > s) {
                    int end = offSide ? prevNonBlank : l - 1;
                    if (end > s) {
                        if (count == sStart.length) {
                            sStart = Arrays.copyOf(sStart, count * 2);
                            sEnd = Arrays.copyOf(sEnd, count * 2);
                        }
                        sStart[count] = s;
                        sEnd[count] = end;
                        count++;
                    }
                }
            }
            if (l == n) break;
            if (sp == stackLine.length) {
                stackLine = Arrays.copyOf(stackLine, sp * 2);
                stackInd = Arrays.copyOf(stackInd, sp * 2);
            }
            stackLine[sp] = l;
            stackInd[sp] = ind;
            sp++;
            prevNonBlank = l;
        }
        // #region markers (only checked on lines that look like comments, to stay fast).
        ArrayList<Integer> open = new ArrayList<Integer>();
        Matcher ms = REGION_START.matcher(""), me = REGION_END.matcher("");
        for (int l = 0; l < n; l++) {
            int s = text.lineStart(l), e = Math.min(text.lineEnd(l), s + 80);
            if (indent[l] < 0) continue;
            int first = s;
            while (first < e && (text.charAt(first) == ' ' || text.charAt(first) == '\t')) first++;
            char c = first < e ? text.charAt(first) : 0;
            if (c != '/' && c != '#' && c != '<' && c != '-' && c != ';' && c != '\'') continue;
            String head = text.substring(s, e);
            if (ms.reset(head).find()) open.add(l);
            else if (me.reset(head).find() && !open.isEmpty()) {
                int st = open.remove(open.size() - 1);
                if (count == sStart.length) {
                    sStart = Arrays.copyOf(sStart, count * 2);
                    sEnd = Arrays.copyOf(sEnd, count * 2);
                }
                sStart[count] = st;
                sEnd[count] = l;
                count++;
            }
        }
        // Sort by start; for equal starts keep the larger range.
        long[] packed = new long[count];
        for (int i = 0; i < count; i++) packed[i] = ((long) sStart[i] << 32) | (0xFFFFFFFFL - sEnd[i]);
        Arrays.sort(packed);
        int[] st = new int[count], en = new int[count];
        int w = 0;
        for (int i = 0; i < count; i++) {
            int a = (int) (packed[i] >>> 32), b = (int) (0xFFFFFFFFL - (packed[i] & 0xFFFFFFFFL));
            if (w > 0 && st[w - 1] == a) continue;
            st[w] = a;
            en[w] = b;
            w++;
        }
        rStart = Arrays.copyOf(st, w);
        rEnd = Arrays.copyOf(en, w);
        dirty = false;
        computedVersion = text.version();
    }

    /** Indentation width of a line, or -1 if blank. */
    private int indentOf(int line) {
        int s = text.lineStart(line), e = text.lineEnd(line), col = 0;
        for (int i = s; i < e; i++) {
            char c = text.charAt(i);
            if (c == ' ') col++;
            else if (c == '\t') col += tabSize - col % tabSize;
            else return col;
        }
        return -1;
    }

    // ------------------------------------------------------------------ collapsed state

    public boolean hasCollapsed() {
        return !collapsed.isEmpty();
    }

    public boolean isCollapsed(int startLine) {
        return indexOfCollapsed(startLine) >= 0;
    }

    private int indexOfCollapsed(int startLine) {
        for (int i = 0; i < collapsed.size(); i++) if (collapsed.get(i)[0] == startLine) return i;
        return -1;
    }

    /** True if the line is inside a collapsed range (not its first line). */
    public boolean isHidden(int line) {
        for (int[] r : collapsed) {
            if (r[0] >= line) break;
            if (line <= r[1]) return true;
        }
        return false;
    }

    /** The collapsed range hiding {@code line} (outermost), or null. */
    public int[] hidingRange(int line) {
        for (int[] r : collapsed) {
            if (r[0] >= line) break;
            if (line <= r[1]) return r;
        }
        return null;
    }

    public int[] collapsedRanges() {
        int[] out = new int[collapsed.size() * 2];
        for (int i = 0; i < collapsed.size(); i++) {
            out[i * 2] = collapsed.get(i)[0];
            out[i * 2 + 1] = collapsed.get(i)[1];
        }
        return out;
    }

    public void setCollapsed(int[] pairs) {
        collapsed.clear();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i] < pairs[i + 1] && pairs[i + 1] < text.lineCount()) collapsed.add(new int[]{pairs[i], pairs[i + 1]});
        }
        sortCollapsed();
        changed();
    }

    /** Folds the innermost range containing {@code line} (or starting on it). */
    public boolean fold(int line) {
        ensureRanges();
        int best = -1;
        for (int i = 0; i < rStart.length; i++) {
            if (rStart[i] > line) break;
            if (rStart[i] <= line && line <= rEnd[i] && !isCollapsed(rStart[i])) {
                if (rStart[i] == line) {
                    best = i;
                    break;
                }
                if (best < 0 || rStart[i] >= rStart[best]) best = i;
            }
        }
        if (best < 0) return false;
        collapsed.add(new int[]{rStart[best], rEnd[best]});
        sortCollapsed();
        changed();
        return true;
    }

    /** Unfolds collapsed ranges starting on or containing {@code line}. */
    public boolean unfold(int line) {
        boolean any = false;
        for (int i = collapsed.size() - 1; i >= 0; i--) {
            int[] r = collapsed.get(i);
            if (r[0] <= line && line <= r[1]) {
                collapsed.remove(i);
                any = true;
            }
        }
        if (any) changed();
        return any;
    }

    public void toggle(int line) {
        if (isCollapsed(line)) unfold(line);
        else fold(line);
    }

    public void foldAll() {
        ensureRanges();
        collapsed.clear();
        for (int i = 0; i < rStart.length; i++) collapsed.add(new int[]{rStart[i], rEnd[i]});
        changed();
    }

    /** Folds ranges at nesting level {@code level} (1 = outermost). */
    public void foldLevel(int level) {
        ensureRanges();
        collapsed.clear();
        int[] stackEnd = new int[rStart.length + 1];
        int depth = 0;
        for (int i = 0; i < rStart.length; i++) {
            while (depth > 0 && rStart[i] > stackEnd[depth - 1]) depth--;
            if (depth + 1 == level) collapsed.add(new int[]{rStart[i], rEnd[i]});
            stackEnd[depth++] = rEnd[i];
        }
        changed();
    }

    public void unfoldAll() {
        if (collapsed.isEmpty()) return;
        collapsed.clear();
        changed();
    }

    /** Opens every collapsed range hiding {@code line} (e.g. when the caret moves there). */
    public boolean reveal(int line) {
        boolean any = false;
        for (int i = collapsed.size() - 1; i >= 0; i--) {
            int[] r = collapsed.get(i);
            if (r[0] < line && line <= r[1]) {
                collapsed.remove(i);
                any = true;
            }
        }
        if (any) changed();
        return any;
    }

    private void sortCollapsed() {
        java.util.Collections.sort(collapsed, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return a[0] != b[0] ? a[0] - b[0] : b[1] - a[1];
            }
        });
    }

    private void changed() {
        if (listener != null) listener.onFoldsChanged();
    }

    @Override
    public void onReplaced(int start, int removedLen, int insertedLen, int firstLine, int removedLines, int insertedLines) {
        dirty = true;
        if (collapsed.isEmpty()) return;
        int diff = insertedLines - removedLines;
        int lastOld = firstLine + removedLines;
        boolean opened = false;
        for (int i = collapsed.size() - 1; i >= 0; i--) {
            int[] r = collapsed.get(i);
            if (firstLine > r[1]) continue;                 // edit below the range
            if (lastOld < r[0]) {                           // edit above: the range moves
                r[0] += diff;
                r[1] += diff;
            } else if (firstLine == r[0] && removedLines == 0 && insertedLines == 0) {
                // Typing within the visible header line keeps the fold.
            } else {
                collapsed.remove(i);                        // the edit reached hidden lines: open it
                opened = true;
            }
        }
        // The layout must show the lines again; otherwise they stay hidden with no fold marker left.
        if (opened) changed();
    }
}
