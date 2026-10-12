package com.disgusty.droid_codology.term;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalEmulatorTest {
    private final StringBuilder replies = new StringBuilder();

    private TerminalEmulator term(int rows, int cols) {
        return new TerminalEmulator(rows, cols, 100, new TerminalEmulator.Output() {
            @Override
            public void write(String s) {
                replies.append(s);
            }
        });
    }

    @Test
    public void textWrapAndScroll() {
        TerminalEmulator t = term(3, 5);
        t.feed("hello world\r\nline3\r\nline4");
        // "hello" / " worl" / "d" wrap; two rows scroll into the history.
        assertEquals("d\nline3\nline4", t.screenText());
        assertEquals(2, t.historySize());
        assertEquals("hello", t.row(-2).text());
        assertTrue(t.row(-2).wrapped);
        assertEquals("hello world\nline3\nline4\n", t.allText());
    }

    @Test
    public void cursorMovementAndErase() {
        TerminalEmulator t = term(4, 10);
        t.feed("abcdefghij\r\n0123456789");
        t.feed("\033[1;3H\033[K");
        assertEquals("ab", t.row(0).text());
        t.feed("\033[2;5H\033[1K");
        assertEquals("     56789", t.row(1).text());
        t.feed("\033[2J\033[HX");
        assertEquals("X", t.screenText().trim());
        t.feed("\033[6n");
        assertEquals("\033[1;2R", replies.toString());
    }

    @Test
    public void colorsAndAttributes() {
        TerminalEmulator t = term(2, 20);
        t.feed("\033[1;31mR\033[38;5;208mO\033[38;2;1;2;3mT\033[0mN");
        TerminalEmulator.Row r = t.row(0);
        assertEquals(1, r.fg[0]);
        assertEquals(TerminalEmulator.BOLD, r.at[0] & TerminalEmulator.BOLD);
        assertEquals(208, r.fg[1]);
        assertEquals(0x1010203, r.fg[2]);
        assertEquals(TerminalEmulator.DEFAULT, r.fg[3]);
        assertEquals(0, r.at[3]);
    }

    @Test
    public void altScreenRestoresMain() {
        TerminalEmulator t = term(3, 10);
        t.feed("shell$ ");
        t.feed("\033[?1049h\033[2J\033[Hvim screen");
        assertTrue(t.altScreen());
        assertEquals("vim screen", t.row(0).text());
        t.feed("\033[?1049l");
        assertFalse(t.altScreen());
        assertEquals("shell$", t.row(0).text());
        assertEquals(7, t.cursorX());
    }

    @Test
    public void scrollRegionInsertDelete() {
        TerminalEmulator t = term(5, 5);
        t.feed("1\r\n2\r\n3\r\n4\r\n5");
        t.feed("\033[2;4r\033[2;1H\033[L");
        assertEquals("1\n\n2\n3\n5", t.screenText());
        t.feed("\033[M");
        assertEquals("1\n2\n3\n\n5", t.screenText());
        assertEquals(0, t.historySize());
    }

    @Test
    public void utf8WideCharsAndLineDrawing() {
        TerminalEmulator t = term(2, 6);
        t.feed("aЖ漢b");
        assertEquals("aЖ漢b", t.row(0).text());
        assertEquals(TerminalEmulator.WIDE_TAIL, t.row(0).ch[3]);
        t.feed("\r\n\033(0lqk\033(B");
        assertEquals("┌─┐", t.row(1).text());
    }

    @Test
    public void resizeKeepsCursorLine() {
        TerminalEmulator t = term(5, 10);
        t.feed("1\r\n2\r\n3\r\n4\r\n5$ ");
        t.resize(2, 8);
        assertEquals("4\n5$", t.screenText());
        assertEquals(1, t.cursorY());
        assertEquals(3, t.historySize());
    }

    @Test
    public void modes() {
        TerminalEmulator t = term(2, 10);
        t.feed("\033[?1h\033[?2004h\033[?25l");
        assertTrue(t.appCursorKeys);
        assertTrue(t.bracketedPaste);
        assertFalse(t.cursorVisible);
        t.feed("\033]0;my title\007");
        assertEquals("my title", t.title);
    }
}
