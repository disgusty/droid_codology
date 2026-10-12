package com.disgusty.droid_codology.text;

import java.util.Arrays;

/**
 * One or more selections (multi-cursor). Each has an anchor and a head (the caret); they are kept
 * sorted by start and never overlap. {@code primary} is the one the view follows.
 */
public final class Selections {
    int[] anchor = new int[4], head = new int[4];
    int count = 1;
    int primary;

    public int count() {
        return count;
    }

    public int primary() {
        return primary;
    }

    public int anchor(int i) {
        return anchor[i];
    }

    public int head(int i) {
        return head[i];
    }

    public int start(int i) {
        return Math.min(anchor[i], head[i]);
    }

    public int end(int i) {
        return Math.max(anchor[i], head[i]);
    }

    public boolean isEmpty(int i) {
        return anchor[i] == head[i];
    }

    public boolean anyNonEmpty() {
        for (int i = 0; i < count; i++) if (anchor[i] != head[i]) return true;
        return false;
    }

    public int caret() {
        return head[primary];
    }

    public void set(int a, int h) {
        count = 1;
        primary = 0;
        anchor[0] = a;
        head[0] = h;
    }

    public void setCaret(int c) {
        set(c, c);
    }

    public void setAt(int i, int a, int h) {
        anchor[i] = a;
        head[i] = h;
    }

    /** Adds a selection and makes it primary; overlapping ones are merged. */
    public void add(int a, int h) {
        if (count == anchor.length) {
            anchor = Arrays.copyOf(anchor, count * 2);
            head = Arrays.copyOf(head, count * 2);
        }
        anchor[count] = a;
        head[count] = h;
        primary = count;
        count++;
        normalize();
    }

    public void removeAt(int i) {
        if (count == 1) return;
        System.arraycopy(anchor, i + 1, anchor, i, count - i - 1);
        System.arraycopy(head, i + 1, head, i, count - i - 1);
        count--;
        if (primary >= i && primary > 0) primary--;
    }

    /** Collapses to just the primary selection. */
    public void collapseToPrimary() {
        set(anchor[primary], head[primary]);
    }

    public int[] snapshot() {
        int[] s = new int[count * 2 + 1];
        s[0] = primary;
        for (int i = 0; i < count; i++) {
            s[1 + i * 2] = anchor[i];
            s[2 + i * 2] = head[i];
        }
        return s;
    }

    public void restore(int[] s, int maxLen) {
        int n = (s.length - 1) / 2;
        if (anchor.length < n) {
            anchor = new int[n];
            head = new int[n];
        }
        count = n;
        primary = s[0];
        for (int i = 0; i < n; i++) {
            anchor[i] = Math.min(s[1 + i * 2], maxLen);
            head[i] = Math.min(s[2 + i * 2], maxLen);
        }
        normalize();
    }

    public void clamp(int len) {
        for (int i = 0; i < count; i++) {
            if (anchor[i] > len) anchor[i] = len;
            if (head[i] > len) head[i] = len;
        }
    }

    /** Sorts by start and merges overlapping/touching-caret selections, keeping primary tracked. */
    public void normalize() {
        if (count == 1) return;
        // Insertion sort: counts are small and usually already sorted.
        for (int i = 1; i < count; i++) {
            int a = anchor[i], h = head[i], s = Math.min(a, h);
            int j = i - 1;
            boolean wasPrimary = primary == i;
            while (j >= 0 && Math.min(anchor[j], head[j]) > s) {
                anchor[j + 1] = anchor[j];
                head[j + 1] = head[j];
                if (primary == j) primary = j + 1;
                j--;
            }
            anchor[j + 1] = a;
            head[j + 1] = h;
            if (wasPrimary) primary = j + 1;
        }
        int w = 0, newPrimary = primary == 0 ? 0 : -1;
        for (int i = 1; i < count; i++) {
            int ps = start(w), pe = end(w), s = start(i), e = end(i);
            boolean overlap = s < pe || (s == pe && (s == e || ps == pe));
            if (overlap) {
                boolean forward = head[w] >= anchor[w];
                int ne = Math.max(pe, e);
                if (forward) {
                    anchor[w] = ps;
                    head[w] = ne;
                } else {
                    anchor[w] = ne;
                    head[w] = ps;
                }
            } else {
                w++;
                anchor[w] = anchor[i];
                head[w] = head[i];
            }
            if (primary == i) newPrimary = w;
        }
        count = w + 1;
        primary = newPrimary < 0 ? 0 : newPrimary;
    }

    /** Shifts all selections for a text replacement made outside the edit engine. */
    public void adjust(int start, int removed, int inserted) {
        int end = start + removed;
        for (int i = 0; i < count; i++) {
            anchor[i] = map(anchor[i], start, end, inserted);
            head[i] = map(head[i], start, end, inserted);
        }
    }

    static int map(int p, int start, int end, int inserted) {
        if (p <= start) return p;
        if (p >= end) return p + inserted - (end - start);
        return start + inserted;
    }
}
