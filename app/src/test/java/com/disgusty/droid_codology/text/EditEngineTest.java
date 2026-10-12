package com.disgusty.droid_codology.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EditEngineTest {
    /** Builds an engine from text where '|' marks carets and [..] marks selections (anchor at '['). */
    private static EditEngine of(String marked) {
        StringBuilder t = new StringBuilder();
        java.util.ArrayList<int[]> sels = new java.util.ArrayList<int[]>();
        int open = -1;
        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);
            if (c == '|') sels.add(new int[]{t.length(), t.length()});
            else if (c == '[') open = t.length();
            else if (c == ']') {
                sels.add(new int[]{open, t.length()});
                open = -1;
            } else t.append(c);
        }
        EditEngine e = new EditEngine(TextBuffer.of(t));
        e.sel.set(sels.get(0)[0], sels.get(0)[1]);
        for (int i = 1; i < sels.size(); i++) e.sel.add(sels.get(i)[0], sels.get(i)[1]);
        return e;
    }

    private static String show(EditEngine e) {
        StringBuilder sb = new StringBuilder(e.text.toString());
        // Insert markers from the end so offsets stay valid.
        for (int i = e.sel.count() - 1; i >= 0; i--) {
            int s = e.sel.start(i), en = e.sel.end(i);
            if (s == en) sb.insert(s, '|');
            else {
                sb.insert(en, ']');
                sb.insert(s, '[');
            }
        }
        return sb.toString();
    }

    @Test
    public void typingAndUndoMerge() {
        EditEngine e = of("|");
        for (char c : "hello world".toCharArray()) e.type(String.valueOf(c));
        assertEquals("hello world|", show(e));
        e.undo();
        assertEquals("hello |", show(e));
        e.undo();
        assertEquals("|", show(e));
        e.redo();
        assertEquals("hello |", show(e));
    }

    @Test
    public void multiCursorTyping() {
        EditEngine e = of("a|b\nc|d\ne|f");
        e.type("X");
        assertEquals("aX|b\ncX|d\neX|f", show(e));
        e.backspace();
        e.backspace();
        assertEquals("|b\n|d\n|f", show(e));
        e.undo();
        assertEquals("aX|b\ncX|d\neX|f", show(e));
    }

    @Test
    public void autoClosePairs() {
        EditEngine e = of("|");
        e.type("(");
        assertEquals("(|)", show(e));
        e.type(")");
        assertEquals("()|", show(e));
        e = of("|");
        e.type("\"");
        e.backspace();
        assertEquals("|", show(e));
        e = of("don|");
        e.type("'");
        assertEquals("don'|", show(e));
        e = of("[abc]");
        e.type("(");
        assertEquals("([abc])", show(e));
    }

    @Test
    public void enterIndents() {
        EditEngine e = of("  if (x) {|}");
        e.newline();
        assertEquals("  if (x) {\n      |\n  }", show(e));
        e = of("    foo|");
        e.newline();
        assertEquals("    foo\n    |", show(e));
    }

    @Test
    public void tabAndOutdent() {
        EditEngine e = of("[a\nb]");
        e.tab();
        assertEquals("[    a\n    b]", show(e));
        e.indentLines(false);
        assertEquals("[a\nb]", show(e));
        e = of("ab|");
        e.tab();
        assertEquals("ab  |", show(e));
        e.backspace();
        assertEquals("ab |", show(e));
    }

    @Test
    public void lineComments() {
        EditEngine e = of("  [a\n  b]");
        e.toggleLineComment();
        assertEquals("  // a\n  // b", e.text.toString());
        e.toggleLineComment();
        assertEquals("  a\n  b", e.text.toString());
    }

    @Test
    public void moveAndDuplicateLines() {
        EditEngine e = of("one\ntw|o\nthree");
        e.moveLines(true);
        assertEquals("tw|o\none\nthree", show(e));
        e.moveLines(false);
        e.moveLines(false);
        assertEquals("one\nthree\ntw|o", show(e));
        e.duplicateLines(true);
        assertEquals("one\nthree\ntwo\ntw|o", show(e));
        e.deleteLines();
        assertEquals("one\nthree\n|two", show(e));
    }

    @Test
    public void ctrlD() {
        EditEngine e = of("foo bar fo|o baz foo");
        assertTrue(e.addNextOccurrence());
        assertEquals("foo bar [foo] baz foo", show(e));
        assertTrue(e.addNextOccurrence());
        assertTrue(e.addNextOccurrence());
        assertEquals(3, e.sel.count());
        e.type("x");
        assertEquals("x| bar x| baz x|", show(e));
    }

    @Test
    public void wordMoves() {
        EditEngine e = of("|foo.bar  baz");
        e.move(EditEngine.MOVE_WORD_RIGHT, false);
        assertEquals("foo|.bar  baz", show(e));
        e.move(EditEngine.MOVE_WORD_RIGHT, false);
        e.move(EditEngine.MOVE_WORD_RIGHT, false);
        assertEquals("foo.bar|  baz", show(e));
        e.deleteWord(false);
        assertEquals("foo.|  baz", show(e));
    }

    @Test
    public void autoCloseTags() {
        EditEngine e = of("<div class=\"a\"|");
        e.autoCloseTags = true;
        e.type(">");
        assertEquals("<div class=\"a\">|</div>", show(e));
        e = of("<ul><li>x</li>\n<|");
        e.autoCloseTags = true;
        e.type("/");
        assertEquals("<ul><li>x</li>\n</ul>|", show(e));
        e = of("<br|");
        e.autoCloseTags = true;
        e.type(">");
        assertEquals("<br>|", show(e));
    }

    @Test
    public void expandSelection() {
        EditEngine e = of("f(a, b|c)");
        e.expandSelection();
        assertEquals("f(a, [bc])", show(e));
        e.expandSelection();
        assertEquals("f([a, bc])", show(e));
        e.expandSelection();
        assertEquals("f[(a, bc)]", show(e));
    }
}
