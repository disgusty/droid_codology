package com.disgusty.droid_codology.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An inserted snippet whose tab stops are still being visited: Tab / Shift+Tab move between
 * stops, all ranges of a stop are selected together (multi-cursor), so mirrors update as you
 * type. Ranges follow edits; the session ends at $0, on Escape, or when the caret leaves.
 */
public final class SnippetSession implements TextBuffer.Listener {
    private final EditEngine eng;
    /** Stop ranges in visiting order; each entry is a list of {start, end} absolute offsets. */
    private final ArrayList<List<int[]>> stops = new ArrayList<List<int[]>>();
    private final ArrayList<String[]> choices = new ArrayList<String[]>();
    private int current = -1;
    private boolean active;

    SnippetSession(EditEngine eng) {
        this.eng = eng;
    }

    public boolean isActive() {
        return active;
    }

    /** Choices of the current stop (${1|a,b|}), or null. */
    public String[] currentChoices() {
        return active && current >= 0 && current < choices.size() ? choices.get(current) : null;
    }

    void start(int base, Snippet.Result r) {
        end();
        for (Map.Entry<Integer, List<int[]>> e : r.stops.entrySet()) {
            if (e.getKey() == 0) continue;
            stops.add(abs(base, e.getValue()));
            choices.add(r.choices.get(e.getKey()));
        }
        // $0 last.
        stops.add(abs(base, r.stops.get(0)));
        choices.add(null);
        if (stops.size() == 1) {
            // Nothing to visit: just place the caret at $0.
            int[] fin = stops.get(0).get(0);
            eng.setSelection(fin[0], fin[1]);
            stops.clear();
            choices.clear();
            return;
        }
        active = true;
        eng.text.addListener(this);
        current = -1;
        next();
    }

    private static List<int[]> abs(int base, List<int[]> rel) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        for (int[] r : rel) out.add(new int[]{base + r[0], base + r[1]});
        return out;
    }

    public boolean next() {
        if (!active) return false;
        if (current + 1 >= stops.size()) {
            end();
            return false;
        }
        current++;
        select();
        if (current == stops.size() - 1) end(); // reached $0
        return true;
    }

    public boolean prev() {
        if (!active || current <= 0) return false;
        current--;
        select();
        return true;
    }

    private void select() {
        List<int[]> rs = stops.get(current);
        Selections s = eng.sel;
        s.set(rs.get(0)[0], rs.get(0)[1]);
        for (int i = 1; i < rs.size(); i++) s.add(rs.get(i)[0], rs.get(i)[1]);
        s.primary = 0;
        selecting = true;
        eng.selectionChangedExternally();
        selecting = false;
    }

    private boolean selecting;

    /** True while the session itself moves the selection (so it isn't treated as "caret left"). */
    public boolean isSelecting() {
        return selecting;
    }

    /** Whether {@code offset} lies in the current stop's ranges (or anywhere in the snippet's stops). */
    public boolean contains(int offset) {
        if (!active) return false;
        for (List<int[]> rs : stops) for (int[] r : rs) if (offset >= r[0] && offset <= r[1]) return true;
        return false;
    }

    public void end() {
        if (active) eng.text.removeListener(this);
        active = false;
        stops.clear();
        choices.clear();
        current = -1;
    }

    @Override
    public void onReplaced(int start, int removedLen, int insertedLen, int firstLine, int removedLines, int insertedLines) {
        int end = start + removedLen, delta = insertedLen - removedLen;
        for (int i = 0; i < stops.size(); i++) {
            for (int[] r : stops.get(i)) {
                boolean inside = start >= r[0] && end <= r[1];
                // Only the stop being typed in grows at its edges; another stop that merely touches
                // the edit (the final $0 right after the last placeholder) moves instead, or the
                // next Tab would select the text just typed.
                boolean strictlyInside = start > r[0] && end < r[1];
                if (inside && (i == current || strictlyInside)) {
                    r[1] += delta;
                } else if (r[0] >= end) {
                    r[0] += delta;
                    r[1] += delta;
                } else if (r[1] <= start) {
                    // before the edit: unchanged
                } else {
                    r[0] = Selections.map(r[0], start, end, insertedLen);
                    r[1] = Math.max(r[0], Selections.map(r[1], start, end, insertedLen));
                }
            }
        }
    }
}
