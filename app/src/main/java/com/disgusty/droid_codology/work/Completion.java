package com.disgusty.droid_codology.work;

import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.disgusty.droid_codology.lang.Emmet;
import com.disgusty.droid_codology.lang.Snippets;
import com.disgusty.droid_codology.text.EditEngine;
import com.disgusty.droid_codology.text.Snippet;
import com.disgusty.droid_codology.text.TextBuffer;
import com.disgusty.droid_codology.ui.Skin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * IntelliSense without a language server (VS Code's word-based suggestions): words of the
 * document, the language's keywords and its snippets, filtered as you type. A language server
 * (later) adds its items through {@link #extra}.
 */
final class Completion {
    static final int KIND_WORD = 0, KIND_KEYWORD = 1, KIND_SNIPPET = 2, KIND_FUNCTION = 3, KIND_CLASS = 4, KIND_VARIABLE = 5,
            KIND_FIELD = 6, KIND_CHOICE = 7;

    static final class Item {
        final String label, insert, detail;
        final int kind;
        final String snippetBody;
        int score;
        /** Absolute start of the text to replace when it isn't the word before the caret (Emmet). */
        int replaceStart = -1;
        /** Text matched against what's typed, when it differs from the label (language servers). */
        String filterText;

        Item(String label, String insert, String detail, int kind, String snippetBody) {
            this.label = label;
            this.insert = insert;
            this.detail = detail;
            this.kind = kind;
            this.snippetBody = snippetBody;
        }
    }

    private final Workbench wb;
    private PopupWindow popup;
    private LinearLayout list;
    private ScrollView scroll;
    private final ArrayList<Item> shown = new ArrayList<Item>();
    private int selected;
    private String prefix = "";
    private boolean manual;
    /** Extra items from a language server for the current position (phase 5). */
    List<Item> extra;

    // Word index cache.
    private Document wordsDoc;
    private int wordsVersion = -1;
    private HashSet<String> words = new HashSet<String>();

    Completion(Workbench wb) {
        this.wb = wb;
    }

    boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    void dismiss() {
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
        popup = null;
        manual = false;
    }

    private static final java.util.HashSet<String> EMMET_HTML = new java.util.HashSet<String>(java.util.Arrays.asList("html", "php", "xml"));
    private static final java.util.HashSet<String> EMMET_CSS = new java.util.HashSet<String>(java.util.Arrays.asList("css", "scss", "less"));

    /** An Emmet expansion of the abbreviation before the caret, offered first (VS Code's Emmet suggestion). */
    private Item emmetItem(Document d) {
        String lang = d.language.id;
        boolean css = EMMET_CSS.contains(lang);
        if (!css && !EMMET_HTML.contains(lang)) return null;
        EditEngine e = d.engine;
        int caret = e.sel.caret();
        int ls = e.text.lineStart(e.text.lineOfOffset(caret));
        int st = caret, depth = 0;
        while (st > ls) {
            char c = e.text.charAt(st - 1);
            if (c == '}' || c == ']') depth++;
            else if ((c == '{' || c == '[') && depth > 0) depth--;
            else if (depth == 0 && (c == ' ' || c == '\t' || c == '<' || (css && (c == ';' || c == '{')))) break;
            st--;
        }
        String abbr = e.text.substring(st, caret);
        if (abbr.length() < 1 || !Emmet.looksLikeAbbreviation(abbr, css)) return null;
        String body = css ? Emmet.cssExpand(abbr) : Emmet.expandHtml(abbr, e.indentUnit());
        if (body == null) return null;
        Item it = new Item(abbr, abbr, "Emmet Abbreviation", KIND_SNIPPET, body);
        it.replaceStart = st;
        return it;
    }

    /** VS Code's editor.quickSuggestions default: on in code, off in comments and strings. */
    static boolean inCommentOrString(Document d) {
        EditEngine e = d.engine;
        int caret = e.sel.caret();
        int line = e.text.lineOfOffset(caret);
        int[] tok = d.highlighter.tokens(line);
        if (tok == null) return false;
        int col = caret - e.text.lineStart(line) - 1;
        int style = com.disgusty.droid_codology.lang.Tok.PLAIN;
        for (int i = 0; i + 1 < tok.length; i += 2) {
            if (tok[i] <= col) style = tok[i + 1];
            else break;
        }
        return style == com.disgusty.droid_codology.lang.Tok.COMMENT || style == com.disgusty.droid_codology.lang.Tok.DOC_COMMENT
                || style == com.disgusty.droid_codology.lang.Tok.STRING;
    }

    /** After typing: show/refresh suggestions if a word is being typed. */
    void onTyped() {
        Document d = wb.activeDocument();
        if (d == null) return;
        if (!manual && !isShowing() && inCommentOrString(d)) return;
        String p = currentPrefix(d.engine);
        if (p.isEmpty() && !manual) {
            dismiss();
            return;
        }
        requestRemote(d, null);
        show(d, p);
    }

    void trigger() {
        Document d = wb.activeDocument();
        if (d == null) return;
        manual = true;
        requestRemote(d, null);
        show(d, currentPrefix(d.engine));
    }

    /** A language's trigger character ("." in most languages): ask the servers right away. */
    void onTriggerChar(String c) {
        Document d = wb.activeDocument();
        if (d == null) return;
        remoteDoc = null;
        manual = true;
        extra = null;
        requestRemote(d, c);
    }

    // Remote items (language server, extensions) are fetched once per word being typed.
    private Document remoteDoc;
    private int remoteAnchor = -1;

    private void requestRemote(final Document d, final String trigger) {
        int caret = d.engine.sel.caret();
        final int anchor = d.engine.wordStart(caret);
        if (remoteDoc == d && remoteAnchor == anchor) return;
        remoteDoc = d;
        remoteAnchor = anchor;
        extra = null;
        wb.remoteCompletions(d, caret, trigger, new LspManager.Result<List<Item>>() {
            @Override
            public void done(List<Item> items) {
                if (remoteDoc != d || remoteAnchor != anchor || wb.activeDocument() != d) return;
                if (items == null || items.isEmpty()) return;
                extra = items;
                // Still typing in the same word: refresh the list with the new items.
                int c = d.engine.sel.caret();
                if (d.engine.wordStart(c) == anchor && (isShowing() || manual)) show(d, currentPrefix(d.engine));
            }
        });
    }

    /** Choices of a snippet stop (${1|a,b|}) offered as a list. */
    void showChoices(String[] choices) {
        Document d = wb.activeDocument();
        if (d == null) return;
        shown.clear();
        for (String c : choices) shown.add(new Item(c, c, null, KIND_CHOICE, null));
        prefix = "";
        selected = 0;
        render();
    }

    private static String currentPrefix(EditEngine e) {
        int caret = e.sel.head(e.sel.primary());
        if (!e.sel.isEmpty(e.sel.primary())) return "";
        int ws = e.wordStart(caret);
        return e.text.substring(ws, caret);
    }

    private void show(Document d, String p) {
        prefix = p;
        ArrayList<Item> all = new ArrayList<Item>();
        Item emmet = emmetItem(d);
        // Snippets and keywords first in ties (they are what the user most likely wants).
        for (Snippets.Item s : Snippets.forLanguage(d.language.id)) {
            all.add(new Item(s.prefix, s.prefix, s.description, KIND_SNIPPET, s.body));
        }
        Set<String> kw = d.language.keywords();
        for (String k : kw) all.add(new Item(k, k, null, KIND_KEYWORD, null));
        if (extra != null) all.addAll(extra);
        HashSet<String> labels = new HashSet<String>();
        for (Item i : all) labels.add(i.label);
        for (String w : words(d)) {
            if (w.equals(p) || labels.contains(w)) continue;
            all.add(new Item(w, w, null, KIND_WORD, null));
        }
        shown.clear();
        for (Item it : all) {
            int s = score(p, it.filterText != null ? it.filterText : it.label);
            if (s < 0) continue;
            if (it.kind == KIND_WORD && s == Integer.MAX_VALUE) continue;
            it.score = s + (it.kind == KIND_SNIPPET ? 3 : it.kind == KIND_KEYWORD ? 2 : 0);
            shown.add(it);
        }
        if ((shown.isEmpty() && emmet == null) || (!manual && emmet == null && shown.size() == 1 && shown.get(0).label.equals(p))) {
            dismiss();
            return;
        }
        Collections.sort(shown, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.score != b.score) return b.score - a.score;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        if (emmet != null) shown.add(0, emmet);
        while (shown.size() > 60) shown.remove(shown.size() - 1);
        selected = 0;
        render();
    }

    /**
     * Fuzzy score: the first char must match a word start and the rest must appear in order.
     * Higher is better; -1 = no match.
     */
    static int score(String query, String label) {
        if (query.isEmpty()) return 0;
        if (label.length() < query.length()) return -1;
        String q = query.toLowerCase(Locale.ROOT), l = label.toLowerCase(Locale.ROOT);
        if (l.charAt(0) != q.charAt(0)) return -1;
        int score = label.charAt(0) == query.charAt(0) ? 2 : 0;
        int li = 1, run = 1;
        for (int qi = 1; qi < q.length(); qi++) {
            int f = l.indexOf(q.charAt(qi), li);
            if (f < 0) return -1;
            if (f == li) {
                run++;
                score += run * 2;
            } else {
                run = 0;
                // Camel/snake word starts are good places to match.
                char prev = label.charAt(f - 1);
                if (Character.isUpperCase(label.charAt(f)) || prev == '_' || prev == '-') score += 3;
            }
            li = f + 1;
        }
        if (l.startsWith(q)) score += 10;
        return score;
    }

    private Set<String> words(Document d) {
        if (wordsDoc == d && wordsVersion == d.text.version()) return words;
        HashSet<String> set = new HashSet<String>();
        TextBuffer t = d.text;
        int caret = d.engine.sel.caret();
        int from = 0, to = t.length();
        if (to > 400000) {
            from = Math.max(0, caret - 200000);
            to = Math.min(t.length(), caret + 200000);
        }
        int ws = -1;
        for (int i = from; i <= to; i++) {
            boolean w = i < to && EditEngine.isWordChar(t.charAt(i));
            if (w && ws < 0) ws = i;
            else if (!w && ws >= 0) {
                int len = i - ws;
                if (len >= 2 && len <= 60 && !Character.isDigit(t.charAt(ws))) {
                    set.add(t.substring(ws, i));
                    if (set.size() > 20000) break;
                }
                ws = -1;
            }
        }
        words = set;
        wordsDoc = d;
        wordsVersion = d.text.version();
        return set;
    }

    // ------------------------------------------------------------------ UI

    private void render() {
        final Skin skin = wb.skin;
        if (list == null || popup == null) {
            list = new LinearLayout(wb.activity);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll = new ScrollView(wb.activity);
            scroll.addView(list);
            scroll.setBackgroundDrawable(skin.quickInputBackground());
            scroll.setPadding(skin.dp(2), skin.dp(2), skin.dp(2), skin.dp(2));
            scroll.setVerticalScrollBarEnabled(true);
        }
        list.removeAllViews();
        for (int i = 0; i < shown.size(); i++) list.addView(row(shown.get(i), i));
        int rowH = skin.dp(34);
        int h = Math.min(shown.size(), 7) * rowH + skin.dp(6);
        int screenW = wb.activity.getResources().getDisplayMetrics().widthPixels;
        int w = Math.min(screenW - skin.dp(16), skin.dp(340));
        float[] pos = wb.editor.caretPos(wb.activeDocument().engine.sel.caret());
        int[] loc = new int[2];
        wb.editor.getLocationInWindow(loc);
        int x = (int) (loc[0] + pos[0]) - skin.dp(28);
        x = Math.max(skin.dp(4), Math.min(x, screenW - w - skin.dp(4)));
        int below = (int) (loc[1] + pos[2]);
        int y = below;
        if (below + h > loc[1] + wb.editor.getHeight()) y = (int) (loc[1] + pos[1]) - h; // flip above the line
        if (popup == null) {
            popup = new PopupWindow(scroll, w, h, false);
            popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
            popup.setOutsideTouchable(false);
            popup.setClippingEnabled(true);
            if (Build.VERSION.SDK_INT >= 21) popup.setElevation(skin.dp(6));
            popup.showAtLocation(wb.editor, Gravity.LEFT | Gravity.TOP, x, y);
        } else {
            popup.update(x, y, w, h);
        }
        scrollToSelected();
    }

    private View row(final Item it, final int index) {
        Skin skin = wb.skin;
        LinearLayout r = new LinearLayout(wb.activity);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(skin.dp(6), 0, skin.dp(8), 0);
        ImageView iv = new ImageView(wb.activity);
        iv.setImageDrawable(skin.icon(kindIcon(it.kind), kindColor(it.kind), 16));
        r.addView(iv, new LinearLayout.LayoutParams(skin.dp(22), skin.dp(22)));
        SpannableStringBuilder sb = new SpannableStringBuilder(it.label);
        highlight(sb, prefix, it.label, skin.textLink);
        TextView t = skin.text(sb, skin.uiSp() - 1, skin.menuFg, android.graphics.Typeface.MONOSPACE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(skin.dp(6), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (it.detail != null && index == selected) {
            TextView d = skin.text(it.detail, skin.uiSp() - 3, skin.descriptionFg, skin.regular());
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(skin.dp(8), 0, 0, 0);
            d.setMaxWidth(skin.dp(150));
            r.addView(d);
        }
        r.setBackgroundDrawable(index == selected ? skin.rounded(skin.menuSelectionBg, skin.dp(3)) : skin.listSelector(false));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                accept(index);
            }
        });
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, skin.dp(34)));
        return r;
    }

    private static void highlight(SpannableStringBuilder sb, String q, String label, int color) {
        String l = label.toLowerCase(Locale.ROOT), ql = q.toLowerCase(Locale.ROOT);
        int from = 0;
        for (int i = 0; i < ql.length(); i++) {
            int at = l.indexOf(ql.charAt(i), from);
            if (at < 0) break;
            sb.setSpan(new ForegroundColorSpan(color), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = at + 1;
        }
    }

    private static String kindIcon(int k) {
        switch (k) {
            case KIND_KEYWORD: return "symbol_keyword";
            case KIND_SNIPPET: return "symbol_snippet";
            case KIND_FUNCTION: return "symbol_method";
            case KIND_CLASS: return "symbol_class";
            case KIND_VARIABLE: return "symbol_variable";
            case KIND_FIELD: return "symbol_field";
            default: return "symbol_text";
        }
    }

    /** VS Code's suggest widget icon colors. */
    private static int kindColor(int k) {
        switch (k) {
            case KIND_FUNCTION: return 0xFFB180D7;
            case KIND_CLASS: return 0xFFEE9D28;
            case KIND_VARIABLE:
            case KIND_FIELD: return 0xFF75BEFF;
            case KIND_KEYWORD:
            case KIND_SNIPPET: return 0xFFC5C5C5;
            default: return 0xFF9D9D9D;
        }
    }

    private void scrollToSelected() {
        if (scroll == null) return;
        final int rowH = wb.skin.dp(34);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int top = selected * rowH, bottom = top + rowH;
                if (top < scroll.getScrollY()) scroll.scrollTo(0, top);
                else if (bottom > scroll.getScrollY() + scroll.getHeight()) scroll.scrollTo(0, bottom - scroll.getHeight());
            }
        });
    }

    boolean onKey(KeyEvent e) {
        if (!isShowing()) return false;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                selected = (selected + 1) % shown.size();
                render();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                selected = (selected - 1 + shown.size()) % shown.size();
                render();
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                selected = Math.min(shown.size() - 1, selected + 7);
                render();
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                selected = Math.max(0, selected - 7);
                render();
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_TAB:
                if (e.isShiftPressed() || e.isCtrlPressed()) return false;
                accept(selected);
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                dismiss();
                return true;
        }
        return false;
    }

    void accept(int index) {
        Document d = wb.activeDocument();
        if (d == null || index < 0 || index >= shown.size()) {
            dismiss();
            return;
        }
        Item it = shown.get(index);
        dismiss();
        EditEngine e = d.engine;
        int caret = e.sel.caret();
        int ws = it.replaceStart >= 0 ? it.replaceStart : it.kind == KIND_CHOICE ? e.sel.start(e.sel.primary()) : e.wordStart(caret);
        int we = it.kind == KIND_CHOICE ? e.sel.end(e.sel.primary()) : caret;
        if (it.snippetBody != null) {
            e.insertSnippet(ws, we, it.snippetBody, wb.snippetVariables(d));
            String[] ch = e.snippet.currentChoices();
            if (ch != null) showChoices(ch);
        } else if (e.sel.count() > 1 && it.kind != KIND_CHOICE) {
            // Every caret gets the completion over its own prefix.
            int plen = caret - ws;
            e.begin(com.disgusty.droid_codology.text.UndoStack.KIND_OTHER);
            for (int i = e.sel.count() - 1; i >= 0; i--) {
                int h = e.sel.head(i);
                int s = Math.max(0, h - plen);
                e.replace(s, h, it.insert);
                e.sel.setAt(i, s + it.insert.length(), s + it.insert.length());
            }
            e.end();
        } else {
            e.replaceAndSelect(ws, we, it.insert, ws + it.insert.length(), ws + it.insert.length());
        }
        wb.editor.restartInputIfComposing();
    }
}
