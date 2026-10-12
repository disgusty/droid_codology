package com.disgusty.droid_codology.lang.textmate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.disgusty.droid_codology.lang.Highlighter;
import com.disgusty.droid_codology.lang.Lexer;
import com.disgusty.droid_codology.lang.Tok;
import com.disgusty.droid_codology.text.TextBuffer;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Runs real VS Code grammars (test resources) through the engine. */
public class TextMateTest {
    private static String res(String name) throws Exception {
        InputStream in = TextMateTest.class.getResourceAsStream("/grammars/" + name);
        assertNotNull(name, in);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toString("UTF-8");
    }

    private static GrammarRegistry registry(String... files) throws Exception {
        GrammarRegistry r = new GrammarRegistry();
        for (String f : files) r.registerJson(res(f));
        return r;
    }

    /** Style of the token covering column {@code col} of {@code line}. */
    private static int styleAt(Lexer lx, String line, int state, int col) {
        Lexer.Tokens t = new Lexer.Tokens();
        lx.lex(line.toCharArray(), line.length(), state, t);
        int style = Tok.PLAIN;
        for (int i = 0; i < t.count(); i++) if (t.start(i) <= col) style = t.style(i);
        return style;
    }

    @Test
    public void onigTranslation() {
        assertEquals("[0-9a-fA-F]+", OnigRegex.translate("\\h+"));
        assertEquals("[\\p{Alpha}_]", OnigRegex.translate("[[:alpha:]_]"));
        assertEquals("a\\{b\\}", OnigRegex.translate("a{b}"));
        assertEquals("x{2,3}", OnigRegex.translate("x{2,3}"));
        assertEquals("[\\]a]", OnigRegex.translate("[]a]"));
        assertNotNull(OnigRegex.compile("(?<=\\.)\\s*[[:alpha:]_$][[:alnum:]_$]*"));
    }

    @Test
    public void json() throws Exception {
        GrammarRegistry r = registry("JSON.tmLanguage.json");
        TextMateLexer lx = new TextMateLexer(r.grammar("source.json"));
        String line = "{\"name\": \"demo\", \"n\": 12.5, \"ok\": true}";
        assertEquals(Tok.PROPERTY, styleAt(lx, line, 0, 2));
        assertEquals(Tok.STRING, styleAt(lx, line, 0, 11));
        assertEquals(Tok.NUMBER, styleAt(lx, line, 0, 24));
        assertEquals(Tok.CONSTANT, styleAt(lx, line, 0, 36));
    }

    @Test
    public void javascriptMultiline() throws Exception {
        GrammarRegistry r = registry("JavaScript.tmLanguage.json");
        Grammar g = r.grammar("source.js");
        assertNotNull(g);
        TextMateLexer lx = new TextMateLexer(g);
        String src = "/* block\n still */ const x = 42;\nfunction foo(a, b) { return `t ${a}` + 'q'; }\n// done\nif (x > 1) { foo(1, 2); }";
        TextBuffer t = TextBuffer.of(src);
        Highlighter h = new Highlighter(t, lx);
        h.ensure(t.lineCount() - 1, Long.MAX_VALUE);
        int[] l1 = h.tokens(1);
        assertEquals(Tok.COMMENT, l1[1]);
        assertEquals(Tok.KEYWORD, styleAt(lx, " still */ const x = 42;", h.stateAt(1), 10));
        assertEquals(Tok.NUMBER, styleAt(lx, " still */ const x = 42;", h.stateAt(1), 20));
        assertEquals(Tok.FUNCTION, styleAt(lx, "function foo(a, b) { return `t ${a}` + 'q'; }", h.stateAt(2), 10));
        assertEquals(Tok.CONTROL, styleAt(lx, "function foo(a, b) { return `t ${a}` + 'q'; }", h.stateAt(2), 22));
        assertEquals(Tok.COMMENT, styleAt(lx, "// done", h.stateAt(3), 3));
        assertEquals(Tok.CONTROL, styleAt(lx, "if (x > 1) { foo(1, 2); }", h.stateAt(4), 0));
    }

    @Test
    public void pythonAndMarkdownLoad() throws Exception {
        GrammarRegistry r = registry("MagicPython.tmLanguage.json", "markdown.tmLanguage.json", "css.tmLanguage.json", "html.tmLanguage.json", "JavaScript.tmLanguage.json");
        TextMateLexer py = new TextMateLexer(r.grammar("source.python"));
        assertEquals(Tok.KEYWORD, styleAt(py, "def hello(x):", 0, 0));
        assertEquals(Tok.FUNCTION, styleAt(py, "def hello(x):", 0, 5));
        TextMateLexer md = new TextMateLexer(r.grammar("text.html.markdown"));
        assertEquals(Tok.HEADING, styleAt(md, "# Title", 0, 3));
        TextMateLexer html = new TextMateLexer(r.grammar("text.html.basic"));
        assertEquals(Tok.TAG, styleAt(html, "<div class=\"a\">", 0, 2));
        assertEquals(Tok.ATTR, styleAt(html, "<div class=\"a\">", 0, 6));
    }

    @Test
    public void speed() throws Exception {
        GrammarRegistry r = registry("JavaScript.tmLanguage.json");
        TextMateLexer lx = new TextMateLexer(r.grammar("source.js"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append("  const item").append(i).append(" = compute(").append(i).append(", 'x', [1, 2]); // note\n");
        TextBuffer t = TextBuffer.of(sb);
        Highlighter h = new Highlighter(t, lx);
        long t0 = System.nanoTime();
        h.ensure(t.lineCount() - 1, Long.MAX_VALUE);
        long ms = (System.nanoTime() - t0) / 1000000;
        System.out.println("TextMate JS: 3000 lines in " + ms + " ms");
        TextMateLexer.profiling = true;
        TextBuffer t2 = TextBuffer.of(sb);
        Highlighter h2 = new Highlighter(t2, new TextMateLexer(r.grammar("source.js")));
        h2.ensure(t2.lineCount() - 1, Long.MAX_VALUE);
        java.util.List<java.util.Map.Entry<String, long[]>> es = new java.util.ArrayList<java.util.Map.Entry<String, long[]>>(TextMateLexer.PROFILE.entrySet());
        java.util.Collections.sort(es, new java.util.Comparator<java.util.Map.Entry<String, long[]>>() { public int compare(java.util.Map.Entry<String, long[]> a, java.util.Map.Entry<String, long[]> b) { return Long.compare(b.getValue()[1], a.getValue()[1]); } });
        long calls = 0; for (long[] v : TextMateLexer.PROFILE.values()) calls += v[0];
        System.out.println("finds: " + calls + " patterns: " + es.size());
        for (int i = 0; i < Math.min(12, es.size()); i++) System.out.println(es.get(i).getValue()[1] / 1000000 + "ms " + es.get(i).getValue()[0] + "x  " + es.get(i).getKey().substring(0, Math.min(140, es.get(i).getKey().length())));
        TextMateLexer.profiling = false;
        assertTrue("too slow: " + ms, ms < 5000);
    }
}
