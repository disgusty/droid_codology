package com.disgusty.droid_codology.text;

import java.util.ArrayList;

/**
 * Undo/redo history. Edits are recorded in groups (one user action, which can touch many places
 * with multi-cursor). Consecutive typing and deleting merge into one group, like desktop editors.
 */
public final class UndoStack {
    public static final int KIND_OTHER = 0, KIND_TYPE = 1, KIND_DELETE = 2;

    /** Total chars kept in history before the oldest groups are dropped. */
    private static final long MAX_CHARS = 8L * 1024 * 1024;
    private static final int MAX_GROUPS = 2000;
    private static final long MERGE_MS = 1500;

    static final class Change {
        final int start;
        final String removed, inserted;

        Change(int start, String removed, String inserted) {
            this.start = start;
            this.removed = removed;
            this.inserted = inserted;
        }
    }

    static final class Group {
        final ArrayList<Change> changes = new ArrayList<Change>(2);
        int[] selBefore, selAfter;
        int kind;
        long time;
        int id;
        long chars;
    }

    private final ArrayList<Group> undo = new ArrayList<Group>();
    private final ArrayList<Group> redo = new ArrayList<Group>();
    private Group open;
    private int nextId = 1;
    private int sealedId = -1;
    private long chars;

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Id of the state the text is in now; equal ids mean equal text. 0 is the initial state. */
    public int stateId() {
        return undo.isEmpty() ? 0 : undo.get(undo.size() - 1).id;
    }

    /** Stops later edits from merging into the current group (e.g. after saving). */
    public void seal() {
        sealedId = stateId();
    }

    public void clear() {
        undo.clear();
        redo.clear();
        open = null;
        chars = 0;
    }

    void begin(int kind, int[] selBefore) {
        Group g = new Group();
        g.kind = kind;
        g.selBefore = selBefore;
        g.time = System.currentTimeMillis();
        open = g;
    }

    boolean isOpen() {
        return open != null;
    }

    void record(int start, String removed, String inserted) {
        if (open == null) return;
        open.changes.add(new Change(start, removed, inserted));
        open.chars += removed.length() + inserted.length();
    }

    void end(int[] selAfter) {
        Group g = open;
        open = null;
        if (g == null || g.changes.isEmpty()) return;
        g.selAfter = selAfter;
        redo.clear();
        Group prev = undo.isEmpty() ? null : undo.get(undo.size() - 1);
        if (prev != null && prev.id != sealedId && canMerge(prev, g)) {
            Change last = prev.changes.get(prev.changes.size() - 1);
            if (g.changes.size() == 1 && isRecomposition(last, g.changes.get(0))) {
                // The keyboard rewrote its composing word: keep one change with the newest text.
                Change c = g.changes.get(0);
                prev.changes.set(prev.changes.size() - 1, new Change(last.start, last.removed, c.inserted));
            } else {
                prev.changes.addAll(g.changes);
            }
            prev.selAfter = g.selAfter;
            prev.time = g.time;
            prev.chars += g.chars;
            chars += g.chars;
        } else {
            g.id = nextId++;
            undo.add(g);
            chars += g.chars;
        }
        trim();
    }

    private static boolean canMerge(Group prev, Group g) {
        if (prev.kind != g.kind || g.kind == KIND_OTHER) return false;
        if (g.time - prev.time > MERGE_MS) return false;
        if (prev.changes.size() != g.changes.size() && prev.selAfter.length != g.selBefore.length) return false;
        if (prev.selAfter.length != 3 || g.selBefore.length != 3) {
            // Multi-cursor: merge if the carets continued from where they were.
            return java.util.Arrays.equals(prev.selAfter, g.selBefore);
        }
        Change c = g.changes.get(0);
        if (g.kind == KIND_TYPE) {
            // Break groups at word boundaries, like VS Code.
            if (c.inserted.indexOf('\n') >= 0) return false;
            Change last = prev.changes.get(prev.changes.size() - 1);
            if (g.changes.size() == 1 && isRecomposition(last, c)) return true;
            if (last.start + last.inserted.length() != c.start) return false;
            String li = last.inserted;
            boolean lastSpace = li.length() > 0 && Character.isWhitespace(li.charAt(li.length() - 1));
            boolean thisSpace = c.inserted.length() > 0 && Character.isWhitespace(c.inserted.charAt(0));
            return !(lastSpace && !thisSpace);
        }
        return java.util.Arrays.equals(prev.selAfter, g.selBefore);
    }

    /** {@code c} replaces exactly the text {@code last} inserted (an IME updating its composing word). */
    private static boolean isRecomposition(Change last, Change c) {
        return c.start == last.start && c.removed.equals(last.inserted) && last.inserted.length() > 0;
    }

    private void trim() {
        while (undo.size() > 1 && (chars > MAX_CHARS || undo.size() > MAX_GROUPS)) {
            Group g = undo.remove(0);
            chars -= g.chars;
        }
    }

    /** Reverts the last group; returns the selection to restore, or null. */
    int[] undo(TextBuffer t) {
        if (undo.isEmpty()) return null;
        Group g = undo.remove(undo.size() - 1);
        chars -= g.chars;
        for (int i = g.changes.size() - 1; i >= 0; i--) {
            Change c = g.changes.get(i);
            t.replace(c.start, c.start + c.inserted.length(), c.removed);
        }
        redo.add(g);
        return g.selBefore;
    }

    int[] redo(TextBuffer t) {
        if (redo.isEmpty()) return null;
        Group g = redo.remove(redo.size() - 1);
        for (Change c : g.changes) t.replace(c.start, c.start + c.removed.length(), c.inserted);
        undo.add(g);
        chars += g.chars;
        return g.selAfter;
    }
}
