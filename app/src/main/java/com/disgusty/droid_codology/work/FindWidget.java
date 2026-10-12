package com.disgusty.droid_codology.work;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.disgusty.droid_codology.text.UndoStack;
import com.disgusty.droid_codology.ui.Skin;

import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/** VS Code's find/replace widget over the editor. Matching runs on a background thread. */
final class FindWidget extends LinearLayout {
    private final Workbench wb;
    private final Skin skin;
    private final EditText findInput, replaceInput;
    private final LinearLayout replaceRow;
    private final TextView count;
    private final TextSearch opts = new TextSearch();
    private int[] starts = new int[0], ends = new int[0];
    private int current = -1;
    private Future<?> job;
    private int generation;
    private Pattern pattern;
    private final LinearLayout toggles;
    private ImageView expand;

    FindWidget(Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        setOrientation(HORIZONTAL);
        setVisibility(GONE);
        int border = skin.widgetBorder;
        setBackgroundDrawable(skin.stroked(skin.widgetBg, border, Math.max(1, skin.dp(1)), skin.dp(4)));
        setPadding(skin.dp(2), skin.dp(4), skin.dp(4), skin.dp(4));
        setClickable(true);

        expand = new ImageView(wb.activity);
        expand.setScaleType(ImageView.ScaleType.CENTER);
        expand.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setReplaceVisible(replaceRow.getVisibility() != VISIBLE);
            }
        });
        addView(expand, new LayoutParams(skin.dp(22), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(wb.activity);
        col.setOrientation(VERTICAL);
        LinearLayout findRow = new LinearLayout(wb.activity);
        findRow.setGravity(Gravity.CENTER_VERTICAL);
        findInput = skin.input(t("Find"));
        findInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        findRow.addView(findInput, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        toggles = new LinearLayout(wb.activity);
        findRow.addView(toggles);
        count = skin.caption(t("No results"));
        count.setPadding(skin.dp(6), 0, skin.dp(4), 0);
        count.setMinWidth(skin.dp(64));
        count.setSingleLine(true);
        findRow.addView(count);
        findRow.addView(button("arrow_up", t("Previous Match"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                prev();
            }
        }));
        findRow.addView(button("arrow_down", t("Next Match"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                next();
            }
        }));
        findRow.addView(button("close", t("Close"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        }));
        col.addView(findRow);

        replaceRow = new LinearLayout(wb.activity);
        replaceRow.setGravity(Gravity.CENTER_VERTICAL);
        replaceInput = skin.input(t("Replace"));
        replaceInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        replaceRow.addView(replaceInput, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        replaceRow.addView(button("replace", t("Replace"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                replaceOne();
            }
        }));
        replaceRow.addView(button("replace_all", t("Replace All"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                replaceAll();
            }
        }));
        LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = skin.dp(4);
        col.addView(replaceRow, rlp);
        addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        setReplaceVisible(false);
        buildToggles();

        findInput.addTextChangedListener(wb.simpleWatcher(new Runnable() {
            @Override
            public void run() {
                opts.query = findInput.getText().toString();
                schedule();
            }
        }));
        findInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                next();
                return true;
            }
        });
        findInput.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.isShiftPressed()) prev();
                    else next();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    hide();
                    return true;
                }
                return false;
            }
        });
        replaceInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                replaceOne();
                return true;
            }
        });
    }

    private View button(String icon, String label, OnClickListener l) {
        Skin.Action a = new Skin.Action(icon, label, l);
        ImageView b = skin.iconButton(a, skin.inputFg, 32);
        b.setLayoutParams(new LayoutParams(skin.dp(32), skin.dp(32)));
        return b;
    }

    private void buildToggles() {
        toggles.removeAllViews();
        toggles.addView(toggle("case", "Aa", t("Match Case"), opts.matchCase, 0));
        toggles.addView(toggle("whole_word", "ab", t("Match Whole Word"), opts.wholeWord, 1));
        toggles.addView(toggle("regex", ".*", t("Use Regular Expression"), opts.regex, 2));
    }

    private View toggle(String icon, String text, String desc, boolean on, final int which) {
        TextView t = skin.inputToggle(icon, text, on, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (which == 0) opts.matchCase = !opts.matchCase;
                else if (which == 1) opts.wholeWord = !opts.wholeWord;
                else opts.regex = !opts.regex;
                buildToggles();
                schedule();
            }
        });
        t.setContentDescription(desc);
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(28));
        lp.leftMargin = skin.dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private void setReplaceVisible(boolean on) {
        replaceRow.setVisibility(on ? VISIBLE : GONE);
        Drawable d = skin.icon(on ? "chevron_down" : "chevron_right", skin.inputFg, 16);
        expand.setImageDrawable(d);
    }

    void show(boolean withReplace, String seed) {
        setVisibility(VISIBLE);
        setReplaceVisible(withReplace || replaceRow.getVisibility() == VISIBLE);
        if (seed != null && !seed.isEmpty()) findInput.setText(seed);
        EditText focus = withReplace && findInput.length() > 0 ? replaceInput : findInput;
        focus.requestFocus();
        focus.selectAll();
        opts.query = findInput.getText().toString();
        schedule();
    }

    void hide() {
        setVisibility(GONE);
        wb.editor.setFindMatches(new int[0], new int[0], -1);
        if (job != null) job.cancel(true);
        generation++;
        wb.editor.requestFocus();
    }

    void onDocumentChanged() {
        if (getVisibility() == VISIBLE) schedule();
    }

    void onTextChanged() {
        if (getVisibility() == VISIBLE) {
            removeCallbacks(search);
            postDelayed(search, 300);
        }
    }

    private void schedule() {
        removeCallbacks(search);
        postDelayed(search, 120);
    }

    private final Runnable search = new Runnable() {
        @Override
        public void run() {
            runSearch();
        }
    };

    private void runSearch() {
        final Document d = wb.activeDocument();
        if (d == null || getVisibility() != VISIBLE) return;
        if (job != null) job.cancel(true);
        final int gen = ++generation;
        try {
            pattern = opts.compile();
        } catch (PatternSyntaxException e) {
            count.setText(t("No results"));
            count.setTextColor(skin.errorFg);
            starts = new int[0];
            ends = new int[0];
            wb.editor.setFindMatches(starts, ends, -1);
            return;
        }
        count.setTextColor(skin.descriptionFg);
        if (pattern == null) {
            starts = new int[0];
            ends = new int[0];
            current = -1;
            count.setText(t("No results"));
            wb.editor.setFindMatches(starts, ends, -1);
            return;
        }
        final Pattern p = pattern;
        // A String snapshot: regex over the gap buffer from another thread would race with edits.
        final String snapshot = d.text.toString();
        final int caret = d.engine.sel.start(d.engine.sel.primary());
        job = wb.bg.submit(new Runnable() {
            @Override
            public void run() {
                final int[][] m = TextSearch.findAll(p, snapshot, TextSearch.MAX_MATCHES);
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || wb.activeDocument() != d) return;
                        starts = m[0];
                        ends = m[1];
                        current = -1;
                        for (int i = 0; i < starts.length; i++) {
                            if (starts[i] >= caret) {
                                current = i;
                                break;
                            }
                        }
                        if (current < 0 && starts.length > 0) current = 0;
                        updateCount();
                        wb.editor.setFindMatches(starts, ends, current);
                    }
                });
            }
        });
    }

    private void updateCount() {
        if (starts.length == 0) count.setText(t("No results"));
        else count.setText(f("{0} of {1}", current + 1, starts.length + (starts.length >= TextSearch.MAX_MATCHES ? "+" : "")));
    }

    private void go(int dir) {
        Document d = wb.activeDocument();
        if (d == null) return;
        if (getVisibility() != VISIBLE || starts.length == 0) {
            if (getVisibility() != VISIBLE) show(false, wb.selectedWord());
            return;
        }
        int caret = dir > 0 ? d.engine.sel.end(d.engine.sel.primary()) : d.engine.sel.start(d.engine.sel.primary());
        int idx = -1;
        if (dir > 0) {
            for (int i = 0; i < starts.length; i++) if (starts[i] >= caret && !(starts[i] == caret && ends[i] == caret)) { idx = i; break; }
            if (idx < 0) idx = 0;
            if (current >= 0 && starts[current] == d.engine.sel.start(d.engine.sel.primary()) && ends[current] == caret) idx = (current + 1) % starts.length;
        } else {
            for (int i = starts.length - 1; i >= 0; i--) if (ends[i] <= caret && starts[i] < caret) { idx = i; break; }
            if (idx < 0) idx = starts.length - 1;
        }
        current = idx;
        d.engine.setSelection(starts[idx], ends[idx]);
        wb.editor.revealLine(d.text.lineOfOffset(starts[idx]));
        wb.editor.ensureCaretVisible();
        wb.editor.setFindMatches(starts, ends, current);
        updateCount();
    }

    void next() {
        go(1);
    }

    void prev() {
        go(-1);
    }

    private void replaceOne() {
        Document d = wb.activeDocument();
        if (d == null || d.readOnly || pattern == null) return;
        // Matches of the text as it is now: the background search may not be back after an edit,
        // and stale offsets would replace the wrong text.
        refreshMatches(d);
        if (starts.length == 0) return;
        int ss = d.engine.sel.start(d.engine.sel.primary()), se = d.engine.sel.end(d.engine.sel.primary());
        int on = -1;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] == ss && ends[i] == se) {
                on = i;
                break;
            }
        }
        if (on < 0) {
            next();
            return;
        }
        String rep = opts.replacement(pattern, d.text, ss, se, replaceInput.getText().toString());
        d.engine.replaceAndSelect(ss, se, rep, ss + rep.length(), ss + rep.length());
        refreshMatches(d);
        current = -1;
        next();
        runSearch();
    }

    private void refreshMatches(Document d) {
        int[][] m = TextSearch.findAll(pattern, d.text.toString(), TextSearch.MAX_MATCHES);
        starts = m[0];
        ends = m[1];
        if (current >= starts.length) current = -1;
    }

    private void replaceAll() {
        Document d = wb.activeDocument();
        if (d == null || d.readOnly || pattern == null) return;
        String rep = replaceInput.getText().toString();
        String text = d.text.toString();
        // Matches of the text as it is now (the last background search may predate an edit).
        int[][] m = TextSearch.findAll(pattern, text, TextSearch.MAX_MATCHES);
        if (m[0].length == 0) return;
        d.engine.begin(UndoStack.KIND_OTHER);
        try {
            for (int i = m[0].length - 1; i >= 0; i--) {
                d.engine.replace(m[0][i], m[1][i], opts.replacement(pattern, text, m[0][i], m[1][i], rep));
            }
        } finally {
            d.engine.end();
        }
        wb.showMessage(f("Replaced {0} occurrences.", m[0].length));
        runSearch();
    }
}
