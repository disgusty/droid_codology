package com.disgusty.droid_codology.work;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.regex.Pattern;

public class TextSearchTest {
    private static String replaceAll(TextSearch o, String text, String rep) {
        Pattern p = o.compile();
        int[][] m = TextSearch.findAll(p, text, TextSearch.MAX_MATCHES);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (int i = 0; i < m[0].length; i++) {
            sb.append(text, last, m[0][i]).append(o.replacement(p, text, m[0][i], m[1][i], rep));
            last = m[1][i];
        }
        return sb.append(text.substring(last)).toString();
    }

    private static TextSearch regex(String q) {
        TextSearch o = new TextSearch();
        o.query = q;
        o.regex = true;
        o.matchCase = true;
        return o;
    }

    @Test
    public void groupsAndEscapes() {
        assertEquals("b=a; d=c", replaceAll(regex("(\\w)=(\\w)"), "a=b; c=d", "$2=$1"));
        assertEquals("[x]\t$1", replaceAll(regex("x"), "x", "[$0]\\t\\$1"));
        // $10 when there are fewer groups is $1 followed by "0".
        assertEquals("a0", replaceAll(regex("(a)"), "a", "$10"));
    }

    @Test
    public void lookaroundsSeeTheContext() {
        // Matched alone, "(?<=a)b" would not match "b" at all; in context it must, with its groups.
        assertEquals("aX aX", replaceAll(regex("(?<=a)(b)"), "ab ab", "X"));
        assertEquals("ab-1 cb", replaceAll(regex("b(?= )"), "ab cb", "b-1"));
        assertEquals("[x]\n[y]", replaceAll(regex("^(\\w)$"), "x\ny", "[$1]"));
    }

    @Test
    public void preserveCase() {
        TextSearch o = new TextSearch();
        o.query = "foo";
        o.preserveCase = true;
        assertEquals("bar Bar BAR", replaceAll(o, "foo Foo FOO", "bar"));
    }
}
