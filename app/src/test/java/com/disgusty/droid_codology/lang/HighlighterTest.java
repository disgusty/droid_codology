package com.disgusty.droid_codology.lang;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.disgusty.droid_codology.text.TextBuffer;

import org.junit.Test;

import java.util.Random;

public class HighlighterTest {
    private static int[] fullStates(Lexer lx, TextBuffer t) {
        int n = t.lineCount();
        int[] st = new int[n];
        Lexer.Tokens tok = new Lexer.Tokens();
        int s = 0;
        for (int i = 0; i < n; i++) {
            st[i] = s;
            char[] c = t.lineText(i).toCharArray();
            tok.clear();
            s = lx.lex(c, c.length, s, tok);
        }
        return st;
    }

    @Test
    public void incrementalMatchesFullRelex() {
        Lexer lx = Languages.find("java").lexer();
        Random rnd = new Random(7);
        String[] pieces = {"/*", "*/", "\n", "{", "}", "\"", "x", " ", "// c", "\"\"\"", "(", ")"};
        TextBuffer t = TextBuffer.of("class A {\n  int x; /* c */\n}\n");
        Highlighter h = new Highlighter(t, lx);
        for (int step = 0; step < 3000; step++) {
            int len = t.length();
            int s = rnd.nextInt(len + 1);
            int e = Math.min(len, s + rnd.nextInt(3));
            t.replace(s, e, pieces[rnd.nextInt(pieces.length)]);
            // Sometimes look at a random window, as the view would.
            if (rnd.nextInt(3) == 0) {
                int line = rnd.nextInt(t.lineCount());
                h.ensure(line, Long.MAX_VALUE);
                h.tokens(line);
            }
            if (step % 50 == 0) {
                h.ensure(t.lineCount() - 1, Long.MAX_VALUE);
                int[] full = fullStates(lx, t);
                for (int i = 0; i < full.length; i++) assertEquals("step " + step + " line " + i, full[i], h.stateAt(i));
            }
        }
    }

    @Test
    public void javaTokens() {
        TextBuffer t = TextBuffer.of("public int f(String s) { return 0x1F; } // hi");
        Highlighter h = new Highlighter(t, Languages.find("java").lexer());
        int[] tk = h.tokens(0);
        assertTrue(tk.length > 4);
        assertEquals(Tok.KEYWORD, tk[1]);
        // "// hi" at the end is a comment.
        assertEquals(Tok.COMMENT, tk[tk.length - 1]);
    }

    @Test
    public void detection() {
        assertEquals("python", Languages.forFile("x", "#!/usr/bin/env python3").id);
        assertEquals("shellscript", Languages.forFile("run", "#!/bin/bash").id);
        assertEquals("makefile", Languages.forFile("Makefile", null).id);
        assertEquals("kotlin", Languages.forFile("build.gradle.kts", null).id);
        assertEquals("ini", Languages.forFile(".editorconfig", null).id);
        assertEquals("cpp", Languages.forFile("a.HPP", null).id);
    }

    @Test
    public void htmlEmbedsScript() {
        TextBuffer t = TextBuffer.of("<script>\nvar x = /* a\n b */ 1;\n</script>\n<p class=\"q\">t</p>");
        Highlighter h = new Highlighter(t, Languages.find("html").lexer());
        h.ensure(4, Long.MAX_VALUE);
        int[] tk = h.tokens(2);
        assertEquals(Tok.COMMENT, tk[1]);
        int[] p = h.tokens(4);
        assertArrayEquals(new int[]{0, Tok.PUNCT, 1, Tok.TAG}, java.util.Arrays.copyOf(p, 4));
    }
}
