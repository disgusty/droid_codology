package com.disgusty.droid_codology.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SnippetTest {
    @Test
    public void expandStopsAndMirrors() {
        Snippet.Result r = Snippet.expand("for (int ${1:i} = 0; $1 < ${2:n}; $1++) {\n\t$0\n}", "  ", "    ", null);
        assertEquals("for (int i = 0; i < n; i++) {\n      \n  }", r.text);
        assertEquals(3, r.stops.get(1).size());
        assertEquals(1, r.stops.get(2).size());
    }

    @Test
    public void choicesVariablesEscapes() {
        Snippet.Result r = Snippet.expand("${1|public,private|} \\$x ${TM_FILENAME_BASE:Foo}${2:a ${3:b}}", "", "\t",
                new Snippet.Variables() {
                    @Override
                    public String get(String name) {
                        return name.equals("TM_FILENAME_BASE") ? "Main" : null;
                    }
                });
        assertEquals("public $x Maina b", r.text);
        assertEquals(2, r.choices.get(1).length);
        int[] s3 = r.stops.get(3).get(0);
        assertEquals("b", r.text.substring(s3[0], s3[1]));
    }

    @Test
    public void sessionTabsThroughStopsAndMirrors() {
        EditEngine e = new EditEngine(TextBuffer.of("x\n"));
        e.setSelection(0, 1);
        e.insertSnippet(0, 1, "fn ${1:name}(${2}) { $1 }$0", null);
        assertEquals("fn name() { name }\n", e.text.toString());
        // Stop 1 selects both mirrors.
        assertEquals(2, e.sel.count());
        e.type("go");
        assertEquals("fn go() { go }\n", e.text.toString());
        e.tab();
        assertEquals(1, e.sel.count());
        e.type("a");
        assertEquals("fn go(a) { go }\n", e.text.toString());
        e.tab(); // $0
        assertFalse(e.snippet.isActive());
        assertEquals(e.text.toString().indexOf('\n'), e.sel.caret());
        e.tab(); // a real tab now
        // Column 15 → next tab stop at 16.
        assertEquals("fn go(a) { go } \n", e.text.toString());
        assertTrue(!e.snippet.isActive());
    }
}
