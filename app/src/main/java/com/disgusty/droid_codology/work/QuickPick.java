package com.disgusty.droid_codology.work;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.disgusty.droid_codology.ui.Skin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/**
 * VS Code's quick input: one box for Go to File (fuzzy), "&gt;" Show All Commands, ":" Go to
 * Line, and ad-hoc pickers (language mode, encoding...). Arrow keys, Enter and Escape work.
 */
final class QuickPick extends FrameLayout {
    static final class Item {
        final String label;
        String detail, description;
        final Runnable action;
        int score;
        String matchText;

        Item(String label, String detail, Runnable action) {
            this.label = label;
            this.detail = detail;
            this.action = action;
        }
    }

    private final Workbench wb;
    private final Skin skin;
    private final LinearLayout box;
    private final EditText input;
    private final TextView title;
    private final ListView list;
    private final ArrayList<Item> shown = new ArrayList<Item>();
    private final Adapter adapter = new Adapter();
    private List<Item> fixedItems;
    private int selected;
    private boolean runningCommand;
    private final ArrayList<String> recentCommands = new ArrayList<String>();

    QuickPick(Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        box = new LinearLayout(wb.activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundDrawable(skin.quickInputBackground());
        skin.elevate(box, 6);
        int pad = skin.id() == Skin.MATERIAL3 ? 14 : 6;
        box.setPadding(skin.dp(pad), skin.dp(pad), skin.dp(pad), skin.dp(pad));
        box.setClickable(true);
        title = skin.caption("");
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, skin.dp(4));
        box.addView(title);
        input = skin.input("");
        input.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        box.addView(input);
        list = new ListView(wb.activity);
        list.setDivider(null);
        list.setCacheColorHint(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setAdapter(adapter);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = skin.dp(4);
        box.addView(list, llp);
        int w = Math.min(wb.activity.getResources().getDisplayMetrics().widthPixels - skin.dp(16), skin.dp(600));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = skin.dp(skin.titleBarHeightDp() + 4);
        addView(box, lp);

        input.addTextChangedListener(wb.simpleWatcher(new Runnable() {
            @Override
            public void run() {
                filter();
            }
        }));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                accept(selected);
                return true;
            }
        });
    }

    /** True while a picked command runs or the command list is built (editor commands apply to the active editor). */
    boolean isRunningCommand() {
        return runningCommand || listing;
    }

    private boolean listing;

    /** Opens in file mode with an optional prefix ('&gt;' commands, ':' go to line). */
    void showFiles(String prefix) {
        fixedItems = null;
        open("", prefix);
        if (wb.workspace != null && wb.workspace.index() == null) {
            final Workspace ws = wb.workspace;
            wb.bg.execute(new Runnable() {
                @Override
                public void run() {
                    ws.buildIndex();
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (getVisibility() == VISIBLE && fixedItems == null) filter();
                        }
                    });
                }
            });
        }
    }

    void showItems(String header, List<Item> items) {
        fixedItems = items;
        open(header, "");
    }

    private void open(String header, String text) {
        // A picker replaced by another one counts as cancelled (set onDismiss after showItems).
        Runnable prev = onDismiss;
        onDismiss = null;
        if (prev != null) prev.run();
        title.setText(header);
        title.setVisibility(header.isEmpty() ? GONE : VISIBLE);
        setBackgroundColor(Skin.withAlpha(0xFF000000, 0x40));
        setVisibility(VISIBLE);
        bringToFront();
        input.setText(text);
        input.setSelection(text.length());
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) wb.activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && !wb.hasHardwareKeyboard()) imm.showSoftInput(input, 0);
        filter();
    }

    /** Runs once when the picker closes (picked or cancelled), before a picked item's action. */
    Runnable onDismiss;

    void dismiss() {
        setVisibility(GONE);
        wb.hideSoftKeyboard(input);
        fixedItems = null;
        Runnable r = onDismiss;
        onDismiss = null;
        if (r != null) r.run();
        if (wb.activeDocument() != null) wb.editor.requestFocus();
    }

    boolean onKey(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_ESCAPE:
                dismiss();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_ENTER:
                accept(selected);
                return true;
        }
        return false;
    }

    private void move(int d) {
        if (shown.isEmpty()) return;
        selected = (selected + d + shown.size()) % shown.size();
        adapter.notifyDataSetChanged();
        list.setSelection(Math.max(0, selected - 3));
    }

    private void accept(int i) {
        if (i < 0 || i >= shown.size()) {
            // ":<line>" with no items still navigates.
            String q = input.getText().toString();
            if (q.startsWith(":")) gotoLine(q.substring(1));
            return;
        }
        Item it = shown.get(i);
        dismiss();
        if (it.action != null) {
            runningCommand = true;
            try {
                it.action.run();
            } finally {
                runningCommand = false;
            }
        }
    }

    private void filter() {
        String q = input.getText().toString();
        shown.clear();
        selected = 0;
        if (fixedItems != null) {
            for (Item it : fixedItems) {
                int s = Workspace.fuzzyScore(q, it.label, 0);
                if (s >= 0) {
                    it.score = s;
                    it.matchText = q;
                    shown.add(it);
                }
            }
            if (!q.isEmpty()) sortByScore();
        } else if (q.startsWith(">")) {
            commands(q.substring(1).trim());
        } else if (q.startsWith(":")) {
            gotoLineItems(q.substring(1).trim());
        } else if (q.startsWith("@")) {
            symbolItems(q.substring(1).trim());
        } else {
            files(q.trim());
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        // Cap the list height so the box stays compact.
        int max = Math.min(shown.size(), 10);
        ViewGroup.LayoutParams lp = list.getLayoutParams();
        lp.height = max == 0 ? 0 : max * skin.dp(40);
        list.setLayoutParams(lp);
    }

    private void sortByScore() {
        Collections.sort(shown, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return b.score - a.score;
            }
        });
    }

    private void commands(String q) {
        // Recently used commands first, like VS Code.
        ArrayList<Item> recent = new ArrayList<Item>(), rest = new ArrayList<Item>();
        listing = true;
        List<Commands.Command> available;
        try {
            available = wb.commands.all();
        } finally {
            listing = false;
        }
        for (final Commands.Command c : available) {
            final String id = c.id;
            Item it = new Item(c.label(), c.keyLabel(), new Runnable() {
                @Override
                public void run() {
                    recentCommands.remove(id);
                    recentCommands.add(0, id);
                    wb.commands.run(id);
                }
            });
            int s = Workspace.fuzzyScore(q, c.label(), 0);
            if (s < 0) {
                // Also match by command id.
                s = Workspace.fuzzyScore(q, c.id, 0);
                if (s < 0) continue;
                s -= 10;
            }
            it.score = s;
            it.matchText = q;
            int ri = recentCommands.indexOf(c.id);
            if (ri >= 0) {
                it.score += 1000 - ri;
                it.description = t("recently opened");
                recent.add(it);
            } else rest.add(it);
        }
        shown.addAll(recent);
        shown.addAll(rest);
        if (!q.isEmpty() || !recent.isEmpty()) sortByScore();
        else {
            Collections.sort(shown, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
        }
    }

    private void gotoLineItems(String q) {
        Document d = wb.activeDocument();
        if (d == null) return;
        final String spec = q;
        String label;
        int[] lc = parseLineCol(q);
        if (lc == null) label = t("Type a line number to navigate to.") + " (1 - " + d.text.lineCount() + ")";
        else if (lc[1] > 0) label = f("Go to line {0}, column {1}.", lc[0], lc[1]);
        else label = f("Go to line {0}.", lc[0]);
        shown.add(new Item(label, null, lc == null ? null : new Runnable() {
            @Override
            public void run() {
                gotoLine(spec);
            }
        }));
    }

    /** "@": symbols of the active editor (in document order when the query is empty). */
    private void symbolItems(String q) {
        Document d = wb.activeDocument();
        if (d == null) return;
        for (final com.disgusty.droid_codology.lang.Symbols.Symbol s : wb.symbols(d)) {
            int sc = Workspace.fuzzyScore(q, s.name, 0);
            if (sc < 0) continue;
            StringBuilder indent = new StringBuilder();
            if (q.isEmpty()) for (int i = 0; i < Math.min(6, s.depth); i++) indent.append("  ");
            Item it = new Item(indent + s.name, String.valueOf(s.line + 1), new Runnable() {
                @Override
                public void run() {
                    wb.goTo(s.line, s.column);
                }
            });
            it.score = q.isEmpty() ? -s.line : sc;
            it.matchText = q;
            shown.add(it);
        }
        if (!q.isEmpty()) sortByScore();
    }

    private static int[] parseLineCol(String q) {
        q = q.trim();
        if (q.isEmpty()) return null;
        String[] parts = q.split("[:,]");
        try {
            int line = Integer.parseInt(parts[0].trim());
            int col = parts.length > 1 && !parts[1].trim().isEmpty() ? Integer.parseInt(parts[1].trim()) : 0;
            return new int[]{line, col};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void gotoLine(String spec) {
        Document d = wb.activeDocument();
        int[] lc = parseLineCol(spec);
        if (d == null || lc == null) return;
        int lines = d.text.lineCount();
        int line = lc[0] < 0 ? lines + lc[0] : lc[0] - 1; // negative counts from the end, like VS Code
        line = Math.max(0, Math.min(lines - 1, line));
        int off = Math.min(d.text.lineStart(line) + Math.max(0, lc[1] - 1), d.text.lineEnd(line));
        d.engine.setSelection(off, off);
        wb.editor.revealLine(line);
        wb.editor.requestFocus();
    }

    private void files(String q) {
        // "file.txt:12" opens at a line.
        String lineSpec = null;
        int colon = q.lastIndexOf(':');
        if (colon > 0 && parseLineCol(q.substring(colon + 1)) != null) {
            lineSpec = q.substring(colon + 1);
            q = q.substring(0, colon);
        }
        final String ls = lineSpec;
        // Open editors first.
        for (final Document d : wb.documents()) {
            int s = Workspace.fuzzyScore(q, d.name, 0);
            if (s < 0) continue;
            Item it = new Item(d.name, d.file != null && wb.workspace != null && wb.workspace.contains(d.file) ? wb.workspace.relative(d.file.getParentFile()) : null, new Runnable() {
                @Override
                public void run() {
                    wb.activate(d);
                    if (ls != null) gotoLine(ls);
                }
            });
            it.score = s + 500;
            it.matchText = q;
            it.description = d.isDirty() ? "●" : null;
            shown.add(it);
        }
        Workspace ws = wb.workspace;
        List<File> idx = ws == null ? null : ws.index();
        if (idx != null) {
            ArrayList<Item> hits = new ArrayList<Item>();
            for (final File f : idx) {
                String rel = ws.relative(f);
                int nameStart = rel.length() - f.getName().length();
                int s = Workspace.fuzzyScore(q, rel, nameStart);
                if (s < 0) continue;
                boolean open = false;
                for (Document d : wb.documents()) if (f.equals(d.file)) open = true;
                if (open) continue;
                Item it = new Item(f.getName(), rel.length() > f.getName().length() ? rel.substring(0, nameStart - 1) : null, new Runnable() {
                    @Override
                    public void run() {
                        wb.openFile(f, false, -1, 0, 0);
                        if (ls != null) {
                            wb.ui.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    gotoLine(ls);
                                }
                            }, 100);
                        }
                    }
                });
                it.score = s;
                it.matchText = q;
                hits.add(it);
            }
            Collections.sort(hits, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return b.score - a.score;
                }
            });
            for (int i = 0; i < Math.min(200, hits.size()); i++) shown.add(hits.get(i));
        }
        if (q.isEmpty()) {
            shown.add(new Item(t("Show and Run Commands >"), null, new Runnable() {
                @Override
                public void run() {
                    showFiles(">");
                }
            }));
        }
        sortByScore();
    }

    /** Label with the fuzzy-matched characters in bold accent color. */
    private CharSequence highlight(String label, String q) {
        if (q == null || q.isEmpty()) return label;
        SpannableStringBuilder sb = new SpannableStringBuilder(label);
        String l = label.toLowerCase(Locale.ROOT), ql = q.toLowerCase(Locale.ROOT);
        int from = 0;
        for (int i = 0; i < ql.length(); i++) {
            char c = ql.charAt(i);
            if (c == ' ') continue;
            int at = l.indexOf(c, from);
            if (at < 0) break;
            sb.setSpan(new ForegroundColorSpan(skin.textLink), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = at + 1;
        }
        return sb;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(final int i, View convert, ViewGroup parent) {
            Item it = shown.get(i);
            LinearLayout row = new LinearLayout(wb.activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(skin.dp(8), 0, skin.dp(8), 0);
            TextView l = skin.text(highlight(it.label, it.matchText), skin.uiSp(), skin.menuFg, skin.regular());
            l.setSingleLine(true);
            l.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
            if (it.detail != null) {
                TextView d = skin.text(it.detail, skin.uiSp() - 2, skin.descriptionFg, skin.regular());
                d.setSingleLine(true);
                d.setEllipsize(android.text.TextUtils.TruncateAt.START);
                d.setPadding(skin.dp(8), 0, 0, 0);
                row.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            } else {
                View sp = new View(wb.activity);
                row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
            }
            if (it.description != null) {
                TextView d = skin.text(it.description, skin.uiSp() - 2, skin.descriptionFg, skin.regular());
                d.setPadding(skin.dp(8), 0, 0, 0);
                row.addView(d);
            }
            row.setBackgroundDrawable(i == selected ? skin.rounded(skin.menuSelectionBg, skin.dp(3)) : skin.listSelector(false));
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    accept(i);
                }
            });
            row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, skin.dp(40)));
            return row;
        }
    }
}
