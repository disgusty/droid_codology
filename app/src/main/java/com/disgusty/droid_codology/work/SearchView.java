package com.disgusty.droid_codology.work;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.disgusty.droid_codology.io.TextFiles;
import com.disgusty.droid_codology.ui.Skin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/** The Search view: find (and replace) across all files of the open folder. */
final class SearchView extends LinearLayout {
    private final Workbench wb;
    private final Skin skin;
    private Workspace ws;
    private final EditText query, replace, include, exclude;
    private final LinearLayout replaceRow, detailsBox;
    private final TextSearch opts = new TextSearch();
    private final TextView summary;
    private final ListView list;
    private final ResultAdapter adapter = new ResultAdapter();
    private final ArrayList<Object> rows = new ArrayList<Object>(); // FileHit or LineHit
    private final HashSet<String> collapsed = new HashSet<String>();
    private Future<?> running;
    private int generation;
    private TextView caseT, wordT, regexT;

    static final class FileHit {
        final File file;
        final ArrayList<LineHit> hits = new ArrayList<LineHit>();

        FileHit(File f) {
            file = f;
        }
    }

    static final class LineHit {
        final FileHit file;
        final int line, col, len;
        final String preview;
        final int previewStart;

        LineHit(FileHit f, int line, int col, int len, String preview, int previewStart) {
            file = f;
            this.line = line;
            this.col = col;
            this.len = len;
            this.preview = preview;
            this.previewStart = previewStart;
        }
    }

    private ArrayList<FileHit> results = new ArrayList<FileHit>();

    SearchView(Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        setOrientation(VERTICAL);
        setBackgroundColor(skin.sideBarBg);
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("refresh", t("Refresh"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                run();
            }
        }));
        acts.add(new Skin.Action("close", t("Clear Search Results"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                query.setText("");
                clear();
            }
        }));
        acts.add(new Skin.Action("collapse_all", t("Collapse All"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                for (FileHit fh : results) collapsed.add(fh.file.getAbsolutePath());
                flatten();
            }
        }));
        addView(skin.sideBarHeader(t("Search"), acts));

        LinearLayout box = new LinearLayout(wb.activity);
        box.setOrientation(VERTICAL);
        box.setPadding(skin.dp(8), 0, skin.dp(8), skin.dp(6));

        LinearLayout qrow = new LinearLayout(wb.activity);
        qrow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView toggleReplace = skin.inputToggle("chevron_right", ">", false, null);
        query = skin.input(t("Search"));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        replace = skin.input(t("Replace"));
        replaceRow = new LinearLayout(wb.activity);
        toggleReplace.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showReplace(replaceRow.getVisibility() != VISIBLE);
            }
        });
        qrow.addView(toggleReplace, new LayoutParams(skin.dp(24), skin.dp(34)));
        qrow.addView(query, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(qrow);

        LinearLayout toggles = new LinearLayout(wb.activity);
        toggles.setPadding(skin.dp(24), skin.dp(4), 0, 0);
        caseT = toggle("case", "Aa", t("Match Case"), 0);
        wordT = toggle("whole_word", "ab", t("Match Whole Word"), 1);
        regexT = toggle("regex", ".*", t("Use Regular Expression"), 2);
        toggles.addView(caseT, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(28)));
        toggles.addView(wordT, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(28)));
        toggles.addView(regexT, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(28)));
        TextView more = skin.inputToggle("more_h", "…", false, null);
        detailsBox = new LinearLayout(wb.activity);
        more.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                detailsBox.setVisibility(detailsBox.getVisibility() == VISIBLE ? GONE : VISIBLE);
            }
        });
        View sp = new View(wb.activity);
        toggles.addView(sp, new LayoutParams(0, 1, 1));
        toggles.addView(more, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(28)));
        box.addView(toggles);

        replaceRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        replaceRow.setPadding(skin.dp(24), skin.dp(4), 0, 0);
        replaceRow.addView(replace, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView replaceAll = skin.inputToggle("replace_all", "⇄", false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                replaceAll();
            }
        });
        replaceAll.setContentDescription(t("Replace All"));
        replaceRow.addView(replaceAll, new LayoutParams(skin.dp(34), skin.dp(34)));
        replaceRow.setVisibility(GONE);
        box.addView(replaceRow);

        detailsBox.setOrientation(VERTICAL);
        detailsBox.setPadding(skin.dp(24), skin.dp(4), 0, 0);
        include = skin.input(t("files to include"));
        exclude = skin.input(t("files to exclude"));
        detailsBox.addView(include);
        LayoutParams elp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = skin.dp(4);
        detailsBox.addView(exclude, elp);
        detailsBox.setVisibility(GONE);
        box.addView(detailsBox);
        addView(box);

        summary = skin.caption("");
        summary.setPadding(skin.dp(16), skin.dp(2), skin.dp(16), skin.dp(4));
        addView(summary);
        list = new ListView(wb.activity);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setFastScrollEnabled(true);
        list.setAdapter(adapter);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView.OnEditorActionListener go = new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                run();
                return true;
            }
        };
        query.setOnEditorActionListener(go);
        include.setOnEditorActionListener(go);
        exclude.setOnEditorActionListener(go);
        // Search as you type (debounced), like VS Code's search.searchOnType.
        query.addTextChangedListener(wb.simpleWatcher(new Runnable() {
            @Override
            public void run() {
                removeCallbacks(typed);
                postDelayed(typed, 400);
            }
        }));
    }

    private final Runnable typed = new Runnable() {
        @Override
        public void run() {
            SearchView.this.run();
        }
    };

    private TextView toggle(String icon, String label, String desc, final int which) {
        final TextView[] holder = new TextView[1];
        holder[0] = skin.inputToggle(icon, label, false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (which == 0) opts.matchCase = !opts.matchCase;
                else if (which == 1) opts.wholeWord = !opts.wholeWord;
                else opts.regex = !opts.regex;
                refreshToggles();
                run();
            }
        });
        holder[0].setContentDescription(desc);
        return holder[0];
    }

    private void refreshToggles() {
        ViewGroup parent = (ViewGroup) caseT.getParent();
        int ci = parent.indexOfChild(caseT);
        parent.removeView(caseT);
        parent.removeView(wordT);
        parent.removeView(regexT);
        caseT = toggle("case", "Aa", t("Match Case"), 0);
        wordT = toggle("whole_word", "ab", t("Match Whole Word"), 1);
        regexT = toggle("regex", ".*", t("Use Regular Expression"), 2);
        if (opts.matchCase) caseT.setBackgroundDrawable(skin.stroked(skin.toggleActiveBg, skin.toggleActiveBorder, Math.max(1, skin.dp(1)), skin.dp(3)));
        if (opts.wholeWord) wordT.setBackgroundDrawable(skin.stroked(skin.toggleActiveBg, skin.toggleActiveBorder, Math.max(1, skin.dp(1)), skin.dp(3)));
        if (opts.regex) regexT.setBackgroundDrawable(skin.stroked(skin.toggleActiveBg, skin.toggleActiveBorder, Math.max(1, skin.dp(1)), skin.dp(3)));
        parent.addView(caseT, ci, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(28)));
        parent.addView(wordT, ci + 1, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(28)));
        parent.addView(regexT, ci + 2, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(28)));
    }

    void setWorkspace(Workspace w) {
        ws = w;
        clear();
        summary.setText(w == null ? t("Open a folder to search in its files.") : "");
    }

    void showReplace(boolean on) {
        replaceRow.setVisibility(on ? VISIBLE : GONE);
    }

    void focusQuery(String seed) {
        if (seed != null && !seed.isEmpty()) query.setText(seed);
        query.requestFocus();
        query.selectAll();
    }

    private void clear() {
        if (running != null) running.cancel(true);
        generation++;
        results = new ArrayList<FileHit>();
        flatten();
        summary.setText("");
    }

    /** Glob list ("*.java, src/**") to a regex over relative paths. */
    static Pattern globs(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        StringBuilder re = new StringBuilder();
        for (String g : s.split("\\s*,\\s*")) {
            if (g.isEmpty()) continue;
            if (re.length() > 0) re.append('|');
            StringBuilder p = new StringBuilder();
            boolean hasSlash = g.contains("/");
            if (!hasSlash) p.append("(?:.*/)?");
            for (int i = 0; i < g.length(); i++) {
                char c = g.charAt(i);
                if (c == '*') {
                    if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                        p.append(".*");
                        i++;
                        if (i + 1 < g.length() && g.charAt(i + 1) == '/') i++;
                    } else p.append("[^/]*");
                } else if (c == '?') p.append("[^/]");
                else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) p.append('\\').append(c);
                else p.append(c);
            }
            // A folder name matches everything under it.
            p.append("(?:/.*)?");
            re.append(p);
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    private void run() {
        if (ws == null) return;
        opts.query = query.getText().toString();
        if (running != null) running.cancel(true);
        final int gen = ++generation;
        if (opts.query.isEmpty()) {
            clear();
            return;
        }
        final Pattern p;
        try {
            p = opts.compile();
        } catch (PatternSyntaxException e) {
            summary.setText(String.valueOf(e.getDescription()));
            return;
        }
        final Pattern inc = globs(include.getText().toString()), exc = globs(exclude.getText().toString());
        summary.setText(t("Searching..."));
        final Workspace w = ws;
        running = wb.bg.submit(new Runnable() {
            @Override
            public void run() {
                final ArrayList<FileHit> out = new ArrayList<FileHit>();
                final int[] total = new int[1];
                w.walk(new Workspace.FileVisitor() {
                    @Override
                    public boolean visit(File f) {
                        if (Thread.currentThread().isInterrupted()) return false;
                        String rel = w.relative(f);
                        if (inc != null && !inc.matcher(rel).matches()) return true;
                        if (exc != null && exc.matcher(rel).matches()) return true;
                        if (f.length() > 4 * 1024 * 1024) return true;
                        FileHit fh = searchFile(f, p);
                        if (fh != null) {
                            out.add(fh);
                            total[0] += fh.hits.size();
                            if (total[0] >= 20000) return false;
                            if (out.size() % 20 == 1) publish(gen, new ArrayList<FileHit>(out), total[0], false);
                        }
                        return true;
                    }
                }, 200000);
                publish(gen, out, total[0], true);
            }
        });
    }

    private void publish(final int gen, final ArrayList<FileHit> out, final int total, final boolean done) {
        wb.ui.post(new Runnable() {
            @Override
            public void run() {
                if (gen != generation) return;
                results = out;
                flatten();
                if (total == 0) summary.setText(done ? t("No results") : t("Searching..."));
                else summary.setText(f("{0} results in {1} files", total, out.size()) + (done ? "" : " …"));
            }
        });
    }

    private static FileHit searchFile(File f, Pattern p) {
        String text;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                TextFiles.Loaded l = TextFiles.read(in, null);
                if (l.binary) return null;
                text = new String(l.chars, 0, l.length);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
        int[][] m = TextSearch.findAll(p, text, 5000);
        if (m[0].length == 0) return null;
        FileHit fh = new FileHit(f);
        int line = 0, lineStart = 0, scan = 0;
        for (int i = 0; i < m[0].length; i++) {
            int s = m[0][i];
            while (scan < s) {
                if (text.charAt(scan) == '\n') {
                    line++;
                    lineStart = scan + 1;
                }
                scan++;
            }
            int lineEnd = text.indexOf('\n', s);
            if (lineEnd < 0) lineEnd = text.length();
            // Preview: some context before the match, trimmed of indentation.
            int from = Math.max(lineStart, s - 14);
            while (from < s && (text.charAt(from) == ' ' || text.charAt(from) == '\t')) from++;
            int to = Math.min(lineEnd, Math.max(m[1][i], s) + 120);
            int len = Math.min(m[1][i], lineEnd) - s;
            fh.hits.add(new LineHit(fh, line, s - lineStart, Math.max(0, len), text.substring(from, to), s - from));
        }
        return fh;
    }

    private void flatten() {
        rows.clear();
        for (FileHit fh : results) {
            rows.add(fh);
            if (!collapsed.contains(fh.file.getAbsolutePath())) rows.addAll(fh.hits);
        }
        adapter.notifyDataSetChanged();
    }

    private void replaceAll() {
        if (ws == null || results.isEmpty()) return;
        final String rep = replace.getText().toString();
        int total = 0;
        for (FileHit fh : results) total += fh.hits.size();
        final List<FileHit> targets = new ArrayList<FileHit>(results);
        wb.confirm(t("Replace All"), f("Replace {0} occurrences across {1} files with '{2}'?", total, targets.size(), rep), t("Replace"), new Runnable() {
            @Override
            public void run() {
                final Pattern p;
                try {
                    p = opts.compile();
                } catch (PatternSyntaxException e) {
                    return;
                }
                // The options as confirmed, even if the find field changes while files are written.
                final TextSearch o = opts.copy();
                // Open editors here (undoable); everything else on disk, off the UI thread.
                int n = 0;
                final List<File> onDisk = new ArrayList<File>();
                for (FileHit fh : targets) {
                    Document d = openDocument(fh.file);
                    if (d == null) onDisk.add(fh.file);
                    else n += replaceInDocument(d, o, p, rep);
                }
                final int inEditors = n;
                wb.bg.submit(new Runnable() {
                    @Override
                    public void run() {
                        int total = inEditors;
                        final List<String> failed = new ArrayList<String>();
                        for (File f : onDisk) {
                            try {
                                total += replaceOnDisk(f, o, p, rep);
                            } catch (Exception e) {
                                failed.add(f.getName() + ": " + e.getMessage());
                            } catch (OutOfMemoryError e) {
                                failed.add(f.getName() + ": " + t("not enough memory"));
                            }
                        }
                        final int done = total;
                        wb.ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (failed.isEmpty()) wb.showMessage(f("Replaced {0} occurrences.", done));
                                else wb.showMessage(f("Replaced {0} occurrences. Not changed: {1}", done, android.text.TextUtils.join("; ", failed)));
                                SearchView.this.run();
                            }
                        });
                    }
                });
            }
        });
    }

    private Document openDocument(File f) {
        for (Document d : wb.documents()) if (f.equals(d.file)) return d;
        return null;
    }

    /** Replaces in an open editor: one undoable edit, then saved. */
    private int replaceInDocument(Document d, TextSearch o, Pattern p, String rep) {
        if (d.readOnly || d.binary) return 0;
        String text = d.text.toString();
        int[][] m = TextSearch.findAll(p, text, Integer.MAX_VALUE);
        if (m[0].length == 0) return 0;
        d.engine.begin(com.disgusty.droid_codology.text.UndoStack.KIND_OTHER);
        try {
            for (int i = m[0].length - 1; i >= 0; i--) {
                d.engine.replace(m[0][i], m[1][i], o.replacement(p, text, m[0][i], m[1][i], rep));
            }
        } finally {
            d.engine.end();
        }
        wb.save(d, null);
        return m[0].length;
    }

    /** Replaces directly on disk, keeping encoding and line endings; written through a temp file. */
    private static int replaceOnDisk(File f, TextSearch o, Pattern p, String rep) throws IOException {
        FileInputStream in = new FileInputStream(f);
        TextFiles.Loaded l;
        try {
            l = TextFiles.read(in, null);
        } finally {
            in.close();
        }
        if (l.binary) throw new IOException("binary file");
        String text = new String(l.chars, 0, l.length);
        int[][] m = TextSearch.findAll(p, text, Integer.MAX_VALUE);
        if (m[0].length == 0) return 0;
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        for (int i = 0; i < m[0].length; i++) {
            sb.append(text, last, m[0][i]).append(o.replacement(p, text, m[0][i], m[1][i], rep));
            last = m[1][i];
        }
        sb.append(text, last, text.length());
        char[] out = sb.toString().toCharArray();
        if (!l.charset.toUpperCase(java.util.Locale.ROOT).startsWith("UTF") && !TextFiles.canEncode(out, out.length, l.charset)) {
            throw new IOException(l.charset + " can't store the replacement");
        }
        File dir = f.getCanonicalFile().getParentFile();
        File target = f.getCanonicalFile();
        File tmp = new File(dir, "." + target.getName() + ".dcsave");
        boolean viaTemp = dir != null && dir.canWrite();
        FileOutputStream os = new FileOutputStream(viaTemp ? tmp : target);
        boolean written = false;
        try {
            TextFiles.write(os, out, out.length, l.charset, l.bom, l.eol);
            os.getFD().sync();
            written = true;
        } finally {
            os.close();
            if (!written && viaTemp) tmp.delete();
        }
        if (viaTemp) {
            if (target.canExecute()) tmp.setExecutable(true, false);
            if (!tmp.renameTo(target)) {
                target.delete();
                if (!tmp.renameTo(target)) throw new IOException("cannot replace the file; the new text is in " + tmp);
            }
        }
        return m[0].length;
    }

    private final class ResultAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Object o = rows.get(i);
            View v;
            if (o instanceof FileHit) {
                final FileHit fh = (FileHit) o;
                boolean exp = !collapsed.contains(fh.file.getAbsolutePath());
                String dir = ws == null ? fh.file.getParent() : ws.relative(fh.file.getParentFile());
                LinearLayout row = skin.treeRow(0, exp, FileIcons.file(skin, fh.file.getName(), 16), fh.file.getName(), dir, skin.badge(fh.hits.size()));
                row.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String p = fh.file.getAbsolutePath();
                        if (!collapsed.remove(p)) collapsed.add(p);
                        flatten();
                    }
                });
                v = row;
            } else {
                final LineHit h = (LineHit) o;
                SpannableStringBuilder sb = new SpannableStringBuilder(h.preview);
                int s = Math.min(h.previewStart, sb.length()), e = Math.min(sb.length(), s + h.len);
                if (e > s) sb.setSpan(new BackgroundColorSpan(Skin.withAlpha(0xFFEA5C00, 0x60)), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                LinearLayout row = skin.treeRow(1, null, null, sb, null, null);
                // Keep the match visible: cut long previews at the end, not in the middle.
                for (int k = 0; k < row.getChildCount(); k++) {
                    if (row.getChildAt(k) instanceof TextView) ((TextView) row.getChildAt(k)).setEllipsize(android.text.TextUtils.TruncateAt.END);
                }
                row.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        wb.openFile(h.file.file, true, h.line, h.col, h.len);
                    }
                });
                v = row;
            }
            v.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
