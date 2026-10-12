package com.disgusty.droid_codology.work;

import android.annotation.SuppressLint;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;

import com.disgusty.droid_codology.editor.EditorColors;
import com.disgusty.droid_codology.lang.Language;
import com.disgusty.droid_codology.lang.Languages;
import com.disgusty.droid_codology.lang.Lexer;
import com.disgusty.droid_codology.lang.MarkdownHtml;
import com.disgusty.droid_codology.ui.Skin;

import java.util.ArrayList;

import static com.disgusty.droid_codology.work.I18n.t;

/** Markdown: Open Preview — rendered HTML in a WebView that follows the document as you type. */
final class MarkdownPreview extends LinearLayout {
    private final Workbench wb;
    private final WebView web;
    private Document doc;
    private int renderedVersion = -1;

    @SuppressLint("SetJavaScriptEnabled")
    MarkdownPreview(Workbench wb, Document d) {
        super(wb.activity);
        this.wb = wb;
        this.doc = d;
        setOrientation(VERTICAL);
        Skin skin = wb.skin;
        setBackgroundColor(wb.editor.colors().background);
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("close", t("Close"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                MarkdownPreview.this.wb.closePreview();
            }
        }));
        addView(skin.sideBarHeader(t("Preview") + " " + d.name, acts));
        web = new WebView(wb.activity);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new PreviewClient(wb.activity));
        web.setBackgroundColor(wb.editor.colors().background);
        addView(web, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        update();
    }

    Document document() {
        return doc;
    }

    void setDocument(Document d) {
        doc = d;
        renderedVersion = -1;
        update();
    }

    void update() {
        if (doc == null || doc.text.version() == renderedVersion) return;
        renderedVersion = doc.text.version();
        final EditorColors k = wb.editor.colors();
        MarkdownHtml md = new MarkdownHtml(new MarkdownHtml.CodeColorizer() {
            @Override
            public String colorize(String hint, String code) {
                return highlight(hint, code, k);
            }
        });
        String body = md.render(doc.text.toString());
        String base = doc.file != null ? "file://" + doc.file.getParent() + "/" : null;
        web.loadDataWithBaseURL(base, page(body, k, wb.skin), "text/html", "UTF-8", null);
    }

    /** Colors a fenced code block with the editor's lexer for that language. */
    static String highlight(String hint, String code, EditorColors k) {
        if (hint == null || hint.isEmpty()) return null;
        Language lang = Languages.byId(hint.toLowerCase(java.util.Locale.ROOT));
        if (lang == null) lang = Languages.forFile("x." + hint.toLowerCase(java.util.Locale.ROOT), null);
        if (lang == null || lang == Languages.PLAIN) return null;
        Lexer lx = lang.lexer();
        StringBuilder sb = new StringBuilder();
        int state = 0;
        Lexer.Tokens toks = new Lexer.Tokens();
        String[] lines = code.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            char[] c = lines[li].toCharArray();
            toks.clear();
            try {
                state = lx.lex(c, c.length, state, toks);
            } catch (RuntimeException e) {
                return null;
            }
            for (int t = 0; t < toks.count(); t++) {
                int s = toks.start(t), e = t + 1 < toks.count() ? toks.start(t + 1) : c.length;
                if (e <= s) continue;
                int color = k.tokens[toks.style(t)];
                sb.append("<span style=\"color:#").append(String.format("%06X", color & 0xFFFFFF)).append("\">")
                        .append(MarkdownHtml.esc(new String(c, s, e - s))).append("</span>");
            }
            if (li < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    /** VS Code's markdown preview look, in the editor theme's colors. */
    static String page(String body, EditorColors k, Skin skin) {
        int bg = k.background, fg = k.foreground;
        int codeBg = Skin.blend(bg, k.dark ? 0x1AFFFFFF : 0x0F000000);
        int border = Skin.blend(bg, k.dark ? 0x33FFFFFF : 0x22000000);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>body{background:" + hex(bg) + ";color:" + hex(fg) + ";font-family:-apple-system,Roboto,'Segoe UI',sans-serif;"
                + "font-size:15px;line-height:1.6;padding:0 16px 32px;word-wrap:break-word}"
                + "h1,h2{border-bottom:1px solid " + hex(border) + ";padding-bottom:.3em}h1,h2,h3,h4,h5,h6{font-weight:600;margin:1.2em 0 .5em}"
                + "a{color:" + hex(skin.textLink) + ";text-decoration:none}code{font-family:monospace;font-size:13px;background:" + hex(codeBg)
                + ";border-radius:3px;padding:1px 4px}pre{background:" + hex(codeBg) + ";padding:12px;border-radius:4px;overflow:auto}"
                + "pre code{padding:0;background:none}blockquote{margin:0;padding:0 1em;border-left:4px solid " + hex(border) + ";opacity:.85}"
                + "table{border-collapse:collapse;margin:1em 0}th,td{border:1px solid " + hex(border) + ";padding:5px 12px}"
                + "hr{border:0;border-top:1px solid " + hex(border) + "}img{max-width:100%}li.task{list-style:none;margin-left:-1.4em}"
                + "</style></head><body>" + body + "</body></html>";
    }
}
