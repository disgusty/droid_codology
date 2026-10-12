package com.disgusty.droid_codology.term;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * An xterm-compatible screen: UTF-8 decoding, cursor movement, scroll regions, insert/delete,
 * SGR (16 / 256 / true colors), the alternate screen, bracketed paste, application cursor keys
 * and a scrollback buffer. Fed with process output; answers queries through {@link Output}.
 */
public final class TerminalEmulator {
    public interface Output {
        void write(String s);
    }

    public static final int DEFAULT = 256;
    public static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, INVERSE = 8, DIM = 16, INVISIBLE = 32, STRIKE = 64;
    /** Marks the right half of a wide character. */
    public static final int WIDE_TAIL = -2;

    public static final class Row {
        public int[] ch, fg, bg, at;
        public boolean wrapped;

        Row(int cols) {
            ch = new int[cols];
            fg = new int[cols];
            bg = new int[cols];
            at = new int[cols];
            clear(0, cols, DEFAULT, DEFAULT);
        }

        void clear(int from, int to, int f, int b) {
            for (int i = from; i < to; i++) {
                ch[i] = ' ';
                fg[i] = f;
                bg[i] = b;
                at[i] = 0;
            }
        }

        Row resized(int cols) {
            Row r = new Row(cols);
            int n = Math.min(cols, ch.length);
            System.arraycopy(ch, 0, r.ch, 0, n);
            System.arraycopy(fg, 0, r.fg, 0, n);
            System.arraycopy(bg, 0, r.bg, 0, n);
            System.arraycopy(at, 0, r.at, 0, n);
            r.wrapped = wrapped;
            return r;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (int c : ch) if (c != WIDE_TAIL) sb.appendCodePoint(c);
            int e = sb.length();
            while (e > 0 && sb.charAt(e - 1) == ' ') e--;
            return sb.substring(0, e);
        }
    }

    private int rows, cols;
    private Row[] main, alt, screen;
    private final ArrayList<Row> history = new ArrayList<Row>();
    private final int maxHistory;
    private int cx, cy;
    private boolean pendingWrap;
    private int top, bottom; // scroll region, inclusive
    private int fg = DEFAULT, bg = DEFAULT, attrs;
    private int savedX, savedY, savedFg = DEFAULT, savedBg = DEFAULT, savedAttrs;
    private boolean savedOrigin;
    private boolean[] tabStops;
    private boolean lineDrawing, g1LineDrawing, shiftOut;
    private boolean savedLineDrawing;

    // Modes.
    public boolean appCursorKeys, appKeypad, bracketedPaste, cursorVisible = true, autoWrap = true, originMode, insertMode;
    public boolean mouseTracking, reverseVideo;
    public String title = "";
    /** Bumped on every change, so views know when to redraw. */
    public int version;

    private final Output out;

    public TerminalEmulator(int rows, int cols, int maxHistory, Output out) {
        this.rows = rows;
        this.cols = cols;
        this.maxHistory = maxHistory;
        this.out = out;
        main = newScreen(rows, cols);
        alt = newScreen(rows, cols);
        screen = main;
        top = 0;
        bottom = rows - 1;
        resetTabs();
    }

    private static Row[] newScreen(int rows, int cols) {
        Row[] s = new Row[rows];
        for (int i = 0; i < rows; i++) s[i] = new Row(cols);
        return s;
    }

    private void resetTabs() {
        tabStops = new boolean[cols];
        for (int i = 8; i < cols; i += 8) tabStops[i] = true;
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public int cursorX() {
        return cx;
    }

    public int cursorY() {
        return cy;
    }

    public boolean altScreen() {
        return screen == alt;
    }

    public int historySize() {
        return screen == alt ? 0 : history.size();
    }

    /** Row {@code y} counted from the top of the screen; negative values reach into the scrollback. */
    public Row row(int y) {
        if (y >= 0) return screen[y];
        int h = history.size() + y;
        return h >= 0 ? history.get(h) : null;
    }

    // ------------------------------------------------------------------ resize

    public void resize(int newRows, int newCols) {
        if (newRows == rows && newCols == cols) return;
        if (newRows < 1 || newCols < 1) return;
        // Keep the cursor row visible: push rows above it into the history when shrinking.
        if (screen == main) {
            int keepFrom = Math.max(0, cy - newRows + 1);
            for (int i = 0; i < keepFrom; i++) pushHistory(main[i]);
            Row[] n = new Row[newRows];
            for (int i = 0; i < newRows; i++) n[i] = i + keepFrom < rows ? main[i + keepFrom].resized(newCols) : new Row(newCols);
            main = n;
            cy -= keepFrom;
            for (int i = 0; i < history.size(); i++) if (history.get(i).ch.length != newCols) history.set(i, history.get(i).resized(newCols));
        } else {
            Row[] n = new Row[newRows];
            for (int i = 0; i < newRows; i++) n[i] = i < rows ? main[Math.min(i, main.length - 1)].resized(newCols) : new Row(newCols);
            main = n;
        }
        Row[] na = new Row[newRows];
        for (int i = 0; i < newRows; i++) na[i] = i < rows ? alt[i].resized(newCols) : new Row(newCols);
        boolean onAlt = screen == alt;
        alt = na;
        screen = onAlt ? alt : main;
        rows = newRows;
        cols = newCols;
        top = 0;
        bottom = rows - 1;
        cx = Math.min(cx, cols - 1);
        cy = Math.max(0, Math.min(cy, rows - 1));
        pendingWrap = false;
        resetTabs();
        version++;
    }

    private void pushHistory(Row r) {
        history.add(r);
        if (history.size() > maxHistory) history.remove(0);
    }

    // ------------------------------------------------------------------ input

    private int utfNeed, utfCp, utfMin;
    private int state;
    private static final int GROUND = 0, ESC = 1, CSI = 2, OSC = 3, CHARSET = 4, IGNORE_ST = 5, ESC_HASH = 6;
    private final StringBuilder params = new StringBuilder();
    private char privateMarker, intermediate;
    private final StringBuilder osc = new StringBuilder();
    private char charsetTarget;
    private boolean escInString;

    public void feed(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) {
            int v = b[i] & 0xFF;
            if (utfNeed > 0) {
                if ((v & 0xC0) == 0x80) {
                    utfCp = (utfCp << 6) | (v & 0x3F);
                    if (--utfNeed == 0) {
                        // Overlong forms, surrogates and values past U+10FFFF aren't characters
                        // (drawing them would throw): binary output shows them as U+FFFD.
                        boolean ok = utfCp >= utfMin && utfCp <= 0x10FFFF && (utfCp < 0xD800 || utfCp > 0xDFFF);
                        process(ok ? utfCp : 0xFFFD);
                    }
                    continue;
                }
                utfNeed = 0;
                process(0xFFFD);
            }
            if (v < 0x80) process(v);
            else if ((v & 0xE0) == 0xC0) {
                utfCp = v & 0x1F;
                utfNeed = 1;
                utfMin = 0x80;
            } else if ((v & 0xF0) == 0xE0) {
                utfCp = v & 0x0F;
                utfNeed = 2;
                utfMin = 0x800;
            } else if (v >= 0xF0 && v <= 0xF4) {
                utfCp = v & 0x07;
                utfNeed = 3;
                utfMin = 0x10000;
            } else process(0xFFFD);
        }
        version++;
    }

    public void feed(String s) {
        byte[] b;
        try {
            b = s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        feed(b, 0, b.length);
    }

    private void process(int c) {
        switch (state) {
            case GROUND:
                ground(c);
                return;
            case ESC:
                esc(c);
                return;
            case CSI:
                csi(c);
                return;
            case OSC:
                if (c == 7 || (escInString && c == '\\')) {
                    oscDone();
                    state = GROUND;
                } else if (c == 27) {
                    escInString = true;
                } else {
                    escInString = false;
                    if (osc.length() < 4096) osc.appendCodePoint(c);
                }
                return;
            case IGNORE_ST:
                if (c == 7 || (escInString && c == '\\')) state = GROUND;
                escInString = c == 27;
                return;
            case CHARSET:
                boolean dec = c == '0';
                if (charsetTarget == '(') lineDrawing = dec;
                else if (charsetTarget == ')') g1LineDrawing = dec;
                state = GROUND;
                return;
            case ESC_HASH:
                if (c == '8') { // DECALN: fill with E
                    for (Row r : screen) for (int x = 0; x < cols; x++) r.ch[x] = 'E';
                }
                state = GROUND;
                return;
            default:
                state = GROUND;
        }
    }

    private void ground(int c) {
        if (c < 32 || c == 127) {
            control(c);
            return;
        }
        if ((lineDrawing && !shiftOut) || (g1LineDrawing && shiftOut)) c = lineDrawing(c);
        print(c);
    }

    private void control(int c) {
        switch (c) {
            case 7: // BEL
                break;
            case 8: // BS
                if (cx > 0) cx--;
                pendingWrap = false;
                break;
            case 9: // HT
                cx = nextTab(cx);
                pendingWrap = false;
                break;
            case 10:
            case 11:
            case 12:
                lineFeed();
                break;
            case 13:
                cx = 0;
                pendingWrap = false;
                break;
            case 14:
                shiftOut = true;
                break;
            case 15:
                shiftOut = false;
                break;
            case 27:
                state = ESC;
                params.setLength(0);
                privateMarker = 0;
                intermediate = 0;
                break;
            default:
                break;
        }
    }

    private int nextTab(int x) {
        for (int i = x + 1; i < cols; i++) if (tabStops[i]) return i;
        return cols - 1;
    }

    private void print(int c) {
        int w = width(c);
        if (w == 0) return; // combining marks: drop
        if (pendingWrap) {
            if (autoWrap) {
                screen[cy].wrapped = true;
                cx = 0;
                lineFeed();
            }
            pendingWrap = false;
        }
        if (w == 2 && cx == cols - 1) {
            if (autoWrap) {
                screen[cy].ch[cx] = ' ';
                screen[cy].wrapped = true;
                cx = 0;
                lineFeed();
            } else return;
        }
        Row r = screen[cy];
        if (insertMode) {
            System.arraycopy(r.ch, cx, r.ch, cx + w, cols - cx - w);
            System.arraycopy(r.fg, cx, r.fg, cx + w, cols - cx - w);
            System.arraycopy(r.bg, cx, r.bg, cx + w, cols - cx - w);
            System.arraycopy(r.at, cx, r.at, cx + w, cols - cx - w);
        }
        put(r, cx, c);
        if (w == 2) put(r, cx + 1, WIDE_TAIL);
        if (cx + w >= cols) {
            cx = cols - 1;
            pendingWrap = true;
        } else {
            cx += w;
        }
    }

    private void put(Row r, int x, int c) {
        r.ch[x] = c;
        r.fg[x] = fg;
        r.bg[x] = bg;
        r.at[x] = attrs;
    }

    private void lineFeed() {
        pendingWrap = false;
        if (cy == bottom) scrollUp(1);
        else if (cy < rows - 1) cy++;
    }

    private void reverseIndex() {
        if (cy == top) scrollDown(1);
        else if (cy > 0) cy--;
    }

    private void scrollUp(int n) {
        for (int k = 0; k < n; k++) {
            Row gone = screen[top];
            if (screen == main && top == 0) pushHistory(gone);
            System.arraycopy(screen, top + 1, screen, top, bottom - top);
            Row fresh = new Row(cols);
            fresh.clear(0, cols, DEFAULT, bg);
            screen[bottom] = fresh;
        }
    }

    private void scrollDown(int n) {
        for (int k = 0; k < n; k++) {
            System.arraycopy(screen, top, screen, top + 1, bottom - top);
            Row fresh = new Row(cols);
            fresh.clear(0, cols, DEFAULT, bg);
            screen[top] = fresh;
        }
    }

    private void esc(int c) {
        state = GROUND;
        switch (c) {
            case '[':
                state = CSI;
                params.setLength(0);
                privateMarker = 0;
                intermediate = 0;
                break;
            case ']':
                state = OSC;
                osc.setLength(0);
                escInString = false;
                break;
            case 'P':
            case 'X':
            case '^':
            case '_':
                state = IGNORE_ST;
                escInString = false;
                break;
            case '(':
            case ')':
            case '*':
            case '+':
                charsetTarget = (char) c;
                state = CHARSET;
                break;
            case '#':
                state = ESC_HASH;
                break;
            case '7':
                saveCursor();
                break;
            case '8':
                restoreCursor();
                break;
            case 'D':
                lineFeed();
                break;
            case 'E':
                cx = 0;
                lineFeed();
                break;
            case 'M':
                reverseIndex();
                break;
            case 'H':
                if (cx < cols) tabStops[cx] = true;
                break;
            case 'c':
                reset();
                break;
            case '=':
                appKeypad = true;
                break;
            case '>':
                appKeypad = false;
                break;
            default:
                break;
        }
    }

    private void saveCursor() {
        savedX = cx;
        savedY = cy;
        savedFg = fg;
        savedBg = bg;
        savedAttrs = attrs;
        savedOrigin = originMode;
        savedLineDrawing = lineDrawing;
    }

    private void restoreCursor() {
        cx = Math.min(savedX, cols - 1);
        cy = Math.min(savedY, rows - 1);
        fg = savedFg;
        bg = savedBg;
        attrs = savedAttrs;
        originMode = savedOrigin;
        lineDrawing = savedLineDrawing;
        pendingWrap = false;
    }

    public void reset() {
        main = newScreen(rows, cols);
        alt = newScreen(rows, cols);
        screen = main;
        cx = cy = 0;
        top = 0;
        bottom = rows - 1;
        fg = bg = DEFAULT;
        attrs = 0;
        appCursorKeys = appKeypad = bracketedPaste = originMode = insertMode = mouseTracking = reverseVideo = false;
        cursorVisible = autoWrap = true;
        lineDrawing = g1LineDrawing = shiftOut = false;
        pendingWrap = false;
        resetTabs();
    }

    // ------------------------------------------------------------------ CSI

    private void csi(int c) {
        if (c >= '0' && c <= '9' || c == ';' || c == ':') {
            if (params.length() < 256) params.append((char) c);
            return;
        }
        if (c == '?' || c == '>' || c == '=' || c == '<') {
            privateMarker = (char) c;
            return;
        }
        if (c >= 0x20 && c <= 0x2F) {
            intermediate = (char) c;
            return;
        }
        if (c < 0x20) {
            control(c); // C0 controls execute inside CSI
            if (c == 27) state = ESC;
            return;
        }
        state = GROUND;
        int[] p = parseParams();
        if (privateMarker == '?') {
            if (c == 'h' || c == 'l') for (int m : p) decMode(m, c == 'h');
            return;
        }
        if (privateMarker == '>') {
            if (c == 'c') out.write("\033[>0;10;1c"); // secondary device attributes
            return;
        }
        if (intermediate == ' ' && c == 'q') return; // cursor style
        if (intermediate == '!' && c == 'p') { // soft reset
            originMode = insertMode = false;
            autoWrap = cursorVisible = true;
            top = 0;
            bottom = rows - 1;
            fg = bg = DEFAULT;
            attrs = 0;
            return;
        }
        int a = p.length > 0 ? p[0] : 0;
        int n = Math.max(1, a);
        switch (c) {
            case 'A':
                cy = Math.max(cy >= top ? top : 0, cy - n);
                pendingWrap = false;
                break;
            case 'B':
            case 'e':
                cy = Math.min(cy <= bottom ? bottom : rows - 1, cy + n);
                pendingWrap = false;
                break;
            case 'C':
            case 'a':
                cx = Math.min(cols - 1, cx + n);
                pendingWrap = false;
                break;
            case 'D':
                cx = Math.max(0, cx - n);
                pendingWrap = false;
                break;
            case 'E':
                cy = Math.min(bottom, cy + n);
                cx = 0;
                pendingWrap = false;
                break;
            case 'F':
                cy = Math.max(top, cy - n);
                cx = 0;
                pendingWrap = false;
                break;
            case 'G':
            case '`':
                cx = Math.min(cols - 1, n - 1);
                pendingWrap = false;
                break;
            case 'd':
                cy = clampRow(n - 1);
                pendingWrap = false;
                break;
            case 'H':
            case 'f': {
                int r = p.length > 0 ? Math.max(1, p[0]) : 1, col = p.length > 1 ? Math.max(1, p[1]) : 1;
                cy = clampRow(r - 1);
                cx = Math.min(cols - 1, col - 1);
                pendingWrap = false;
                break;
            }
            case 'J':
                eraseDisplay(a);
                break;
            case 'K':
                eraseLine(a);
                break;
            case 'L':
                if (cy >= top && cy <= bottom) {
                    int saveTop = top;
                    top = cy;
                    scrollDown(Math.min(n, bottom - cy + 1));
                    top = saveTop;
                    cx = 0;
                }
                break;
            case 'M':
                if (cy >= top && cy <= bottom) {
                    // Deleted lines are gone for good (they don't go to the scrollback).
                    int saveTop = top;
                    top = cy;
                    deleteLines(Math.min(n, bottom - cy + 1));
                    top = saveTop;
                    cx = 0;
                }
                break;
            case '@': {
                Row r = screen[cy];
                int k = Math.min(n, cols - cx);
                shiftRight(r, cx, k);
                break;
            }
            case 'P': {
                Row r = screen[cy];
                int k = Math.min(n, cols - cx);
                System.arraycopy(r.ch, cx + k, r.ch, cx, cols - cx - k);
                System.arraycopy(r.fg, cx + k, r.fg, cx, cols - cx - k);
                System.arraycopy(r.bg, cx + k, r.bg, cx, cols - cx - k);
                System.arraycopy(r.at, cx + k, r.at, cx, cols - cx - k);
                r.clear(cols - k, cols, DEFAULT, bg);
                break;
            }
            case 'X':
                screen[cy].clear(cx, Math.min(cols, cx + n), DEFAULT, bg);
                break;
            case 'S':
                scrollUp(n);
                break;
            case 'T':
                scrollDown(n);
                break;
            case 'r': {
                int t = p.length > 0 && p[0] > 0 ? p[0] - 1 : 0;
                int bm = p.length > 1 && p[1] > 0 ? p[1] - 1 : rows - 1;
                if (t < bm && bm < rows) {
                    top = t;
                    bottom = bm;
                }
                cx = 0;
                cy = originMode ? top : 0;
                pendingWrap = false;
                break;
            }
            case 'm':
                sgr(p);
                break;
            case 'n':
                if (a == 6) out.write("\033[" + (cy + 1 - (originMode ? top : 0)) + ";" + (cx + 1) + "R");
                else if (a == 5) out.write("\033[0n");
                break;
            case 'c':
                if (a == 0) out.write("\033[?62;22c");
                break;
            case 'g':
                if (a == 0 && cx < cols) tabStops[cx] = false;
                else if (a == 3) Arrays.fill(tabStops, false);
                break;
            case 'h':
            case 'l':
                for (int m : p) if (m == 4) insertMode = c == 'h';
                break;
            case 's':
                saveCursor();
                break;
            case 'u':
                restoreCursor();
                break;
            case 't': // window manipulation: report size in characters
                if (a == 18) out.write("\033[8;" + rows + ";" + cols + "t");
                break;
            case 'b': { // repeat the previous character
                Row r = screen[cy];
                int prev = cx > 0 ? r.ch[cx - 1] : ' ';
                for (int i = 0; i < Math.min(n, 1000); i++) print(prev);
                break;
            }
            default:
                break;
        }
    }

    private void deleteLines(int n) {
        for (int k = 0; k < n; k++) {
            System.arraycopy(screen, top + 1, screen, top, bottom - top);
            Row fresh = new Row(cols);
            fresh.clear(0, cols, DEFAULT, bg);
            screen[bottom] = fresh;
        }
    }

    private void shiftRight(Row r, int at, int k) {
        System.arraycopy(r.ch, at, r.ch, at + k, cols - at - k);
        System.arraycopy(r.fg, at, r.fg, at + k, cols - at - k);
        System.arraycopy(r.bg, at, r.bg, at + k, cols - at - k);
        System.arraycopy(r.at, at, r.at, at + k, cols - at - k);
        r.clear(at, at + k, DEFAULT, bg);
    }

    private int clampRow(int r) {
        if (originMode) return Math.max(top, Math.min(bottom, r + top));
        return Math.max(0, Math.min(rows - 1, r));
    }

    private int[] parseParams() {
        if (params.length() == 0) return new int[0];
        String[] parts = params.toString().split("[;:]", -1);
        int[] p = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                p[i] = parts[i].isEmpty() ? 0 : Math.min(9999, Integer.parseInt(parts[i]));
            } catch (NumberFormatException e) {
                p[i] = 0;
            }
        }
        return p;
    }

    private void eraseDisplay(int mode) {
        if (mode == 0) {
            eraseLine(0);
            for (int y = cy + 1; y < rows; y++) screen[y].clear(0, cols, DEFAULT, bg);
        } else if (mode == 1) {
            eraseLine(1);
            for (int y = 0; y < cy; y++) screen[y].clear(0, cols, DEFAULT, bg);
        } else if (mode == 2) {
            for (Row r : screen) {
                r.clear(0, cols, DEFAULT, bg);
                r.wrapped = false;
            }
        } else if (mode == 3) {
            history.clear();
        }
    }

    private void eraseLine(int mode) {
        Row r = screen[cy];
        if (mode == 0) r.clear(cx, cols, DEFAULT, bg);
        else if (mode == 1) r.clear(0, Math.min(cols, cx + 1), DEFAULT, bg);
        else r.clear(0, cols, DEFAULT, bg);
        if (mode != 1) r.wrapped = false;
    }

    private void decMode(int m, boolean on) {
        switch (m) {
            case 1:
                appCursorKeys = on;
                break;
            case 5:
                reverseVideo = on;
                break;
            case 6:
                originMode = on;
                cx = 0;
                cy = on ? top : 0;
                break;
            case 7:
                autoWrap = on;
                break;
            case 25:
                cursorVisible = on;
                break;
            case 47:
            case 1047:
                switchAlt(on, false);
                break;
            case 1048:
                if (on) saveCursor();
                else restoreCursor();
                break;
            case 1049:
                switchAlt(on, true);
                break;
            case 1000:
            case 1002:
            case 1003:
            case 1006:
                mouseTracking = on;
                break;
            case 2004:
                bracketedPaste = on;
                break;
            default:
                break;
        }
    }

    private void switchAlt(boolean on, boolean cursor) {
        if (on == (screen == alt)) return;
        if (on) {
            if (cursor) saveCursor();
            alt = newScreen(rows, cols);
            screen = alt;
        } else {
            screen = main;
            if (cursor) restoreCursor();
        }
        top = 0;
        bottom = rows - 1;
        pendingWrap = false;
    }

    private void sgr(int[] p) {
        if (p.length == 0) p = new int[]{0};
        for (int i = 0; i < p.length; i++) {
            int v = p[i];
            if (v == 0) {
                fg = bg = DEFAULT;
                attrs = 0;
            } else if (v == 1) attrs |= BOLD;
            else if (v == 2) attrs |= DIM;
            else if (v == 3) attrs |= ITALIC;
            else if (v == 4) attrs |= UNDERLINE;
            else if (v == 7) attrs |= INVERSE;
            else if (v == 8) attrs |= INVISIBLE;
            else if (v == 9) attrs |= STRIKE;
            else if (v == 21 || v == 22) attrs &= ~(BOLD | DIM);
            else if (v == 23) attrs &= ~ITALIC;
            else if (v == 24) attrs &= ~UNDERLINE;
            else if (v == 27) attrs &= ~INVERSE;
            else if (v == 28) attrs &= ~INVISIBLE;
            else if (v == 29) attrs &= ~STRIKE;
            else if (v >= 30 && v <= 37) fg = v - 30;
            else if (v == 39) fg = DEFAULT;
            else if (v >= 40 && v <= 47) bg = v - 40;
            else if (v == 49) bg = DEFAULT;
            else if (v >= 90 && v <= 97) fg = v - 90 + 8;
            else if (v >= 100 && v <= 107) bg = v - 100 + 8;
            else if (v == 38 || v == 48) {
                int color = DEFAULT;
                if (i + 2 < p.length && p[i + 1] == 5) {
                    color = Math.max(0, Math.min(255, p[i + 2]));
                    i += 2;
                } else if (i + 4 < p.length && p[i + 1] == 2) {
                    color = 0x1000000 | (clamp255(p[i + 2]) << 16) | (clamp255(p[i + 3]) << 8) | clamp255(p[i + 4]);
                    i += 4;
                } else {
                    i = p.length;
                }
                if (v == 38) fg = color;
                else bg = color;
            }
        }
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private void oscDone() {
        String s = osc.toString();
        int semi = s.indexOf(';');
        if (semi < 0) return;
        String code = s.substring(0, semi);
        if (code.equals("0") || code.equals("2")) title = s.substring(semi + 1);
    }

    // ------------------------------------------------------------------ characters

    private static int lineDrawing(int c) {
        switch (c) {
            case 'j':
                return 0x2518;
            case 'k':
                return 0x2510;
            case 'l':
                return 0x250C;
            case 'm':
                return 0x2514;
            case 'n':
                return 0x253C;
            case 'q':
                return 0x2500;
            case 't':
                return 0x251C;
            case 'u':
                return 0x2524;
            case 'v':
                return 0x2534;
            case 'w':
                return 0x252C;
            case 'x':
                return 0x2502;
            case 'a':
                return 0x2592;
            case '`':
                return 0x25C6;
            case 'f':
                return 0x00B0;
            case 'g':
                return 0x00B1;
            case '~':
                return 0x00B7;
            case 'y':
                return 0x2264;
            case 'z':
                return 0x2265;
            case '{':
                return 0x03C0;
            case '|':
                return 0x2260;
            case '}':
                return 0x00A3;
            default:
                return c;
        }
    }

    /** Display width: 0 for combining marks, 2 for East Asian wide and most emoji. */
    public static int width(int c) {
        if (c < 0x300) return 1;
        if ((c >= 0x300 && c <= 0x36F) || (c >= 0x1AB0 && c <= 0x1AFF) || (c >= 0x1DC0 && c <= 0x1DFF)
                || (c >= 0x20D0 && c <= 0x20FF) || (c >= 0xFE20 && c <= 0xFE2F) || c == 0x200B || c == 0x200D
                || (c >= 0xFE00 && c <= 0xFE0F)) return 0;
        if ((c >= 0x1100 && c <= 0x115F) || (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F) || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFE30 && c <= 0xFE4F) || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6) || (c >= 0x1F300 && c <= 0x1F64F) || (c >= 0x1F900 && c <= 0x1F9FF)
                || (c >= 0x1F680 && c <= 0x1F6FF) || (c >= 0x20000 && c <= 0x3FFFD)) return 2;
        return 1;
    }

    /** The visible screen as text (tests, "copy all"). */
    public String screenText() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < rows; y++) {
            sb.append(screen[y].text());
            if (y < rows - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /** Scrollback plus screen as text. */
    public String allText() {
        StringBuilder sb = new StringBuilder();
        if (screen == main) for (Row r : history) sb.append(r.text()).append(r.wrapped ? "" : "\n");
        for (int y = 0; y < rows; y++) sb.append(screen[y].text()).append(screen[y].wrapped ? "" : "\n");
        return sb.toString().replaceAll("\n+$", "\n");
    }

    public int currentFg() {
        return fg;
    }

    public int currentAttrs() {
        return attrs;
    }
}
