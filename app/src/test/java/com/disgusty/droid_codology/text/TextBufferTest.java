package com.disgusty.droid_codology.text;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Random;

public class TextBufferTest {
    /** Line starts computed the slow way, to check the lazy index against. */
    private static void check(TextBuffer b, StringBuilder ref) {
        assertEquals(ref.toString(), b.toString());
        int line = 0;
        assertEquals(0, b.lineStart(0));
        for (int i = 0; i < ref.length(); i++) {
            if (ref.charAt(i) == '\n') {
                line++;
                assertEquals("start of line " + line, i + 1, b.lineStart(line));
            }
        }
        assertEquals(line + 1, b.lineCount());
        for (int i = 0; i <= ref.length(); i += Math.max(1, ref.length() / 50)) {
            int l = 0;
            for (int k = 0; k < i; k++) if (ref.charAt(k) == '\n') l++;
            assertEquals("line of " + i, l, b.lineOfOffset(i));
        }
    }

    @Test
    public void basicEdits() {
        TextBuffer b = TextBuffer.of("hello\nworld");
        StringBuilder r = new StringBuilder("hello\nworld");
        check(b, r);
        b.insert(5, ",\nnew");
        r.insert(5, ",\nnew");
        check(b, r);
        b.delete(0, 7);
        r.delete(0, 7);
        check(b, r);
        b.replace(2, 6, "X\nY\nZ");
        r.replace(2, 6, "X\nY\nZ");
        check(b, r);
        assertEquals("neX", b.lineText(0));
    }

    @Test
    public void randomEditsMatchStringBuilder() {
        Random rnd = new Random(42);
        TextBuffer b = new TextBuffer();
        StringBuilder r = new StringBuilder();
        String alphabet = "ab\n\n c";
        for (int step = 0; step < 4000; step++) {
            int len = r.length();
            int s = len == 0 ? 0 : rnd.nextInt(len + 1);
            // Mostly local edits (like typing), sometimes far jumps.
            int e = Math.min(len, s + (rnd.nextInt(4) == 0 ? rnd.nextInt(20) : 0));
            StringBuilder ins = new StringBuilder();
            int n = rnd.nextInt(6);
            for (int i = 0; i < n; i++) ins.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            b.replace(s, e, ins);
            r.replace(s, e, ins.toString());
            if (step % 97 == 0) check(b, r);
        }
        check(b, r);
    }

    @Test
    public void bigFileIsFast() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200000; i++) sb.append("line number ").append(i).append('\n');
        char[] c = sb.toString().toCharArray();
        TextBuffer b = new TextBuffer(c, c.length);
        assertEquals(200001, b.lineCount());
        long t = System.nanoTime();
        int p = b.lineStart(100000);
        for (int i = 0; i < 20000; i++) b.insert(p + i, i % 40 == 0 ? "\n" : "x");
        long ms = (System.nanoTime() - t) / 1000000;
        assertEquals(200001 + 500, b.lineCount());
        org.junit.Assert.assertTrue("typing 20k chars took " + ms + "ms", ms < 2000);
    }
}
