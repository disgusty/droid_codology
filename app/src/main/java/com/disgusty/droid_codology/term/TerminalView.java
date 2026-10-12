package com.disgusty.droid_codology.term;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/** Draws a {@link TerminalSession} and turns keys and touches into terminal input. */
public final class TerminalView extends View {
    public interface Host {
        /** Lets the editor's extra-keys row apply its sticky Ctrl / Alt to typed text. */
        boolean interceptTyped(TerminalView v, String s);

        void onLongPress(TerminalView v, float x, float y);
    }

    private TerminalSession session;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private float charW, charH, ascent;
    private int scrollRows; // how many rows we're scrolled back into the history
    private float scrollRemainder;
    private final int[] palette = new int[256];
    public int background = 0xFF1E1E1E, foreground = 0xFFCCCCCC, cursorColor = 0xFFAEAFAD, selectionBg = 0x66264F78;
    private Host host;
    private final GestureDetector gestures;

    public TerminalView(Context ctx) {
        super(ctx);
        setFocusable(true);
        setFocusableInTouchMode(true);
        paint.setTypeface(Typeface.MONOSPACE);
        setTextSize(14);
        darkPalette();
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                requestFocus();
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(TerminalView.this, 0);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (session == null) return false;
                scrollRemainder += dy / charH;
                int rows = (int) scrollRemainder;
                if (rows != 0) {
                    scrollRemainder -= rows;
                    scrollBy(-rows);
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (host != null) host.onLongPress(TerminalView.this, e.getX(), e.getY());
            }
        });
    }

    public void setHost(Host h) {
        host = h;
    }

    public TerminalSession session() {
        return session;
    }

    public void setSession(TerminalSession s) {
        session = s;
        scrollRows = 0;
        if (getWidth() > 0) fitSize();
        invalidate();
    }

    public void setTextSize(float sp) {
        paint.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
        Paint.FontMetrics fm = paint.getFontMetrics();
        charW = paint.measureText("M");
        charH = (float) Math.ceil(fm.descent - fm.ascent);
        ascent = -fm.ascent;
        if (getWidth() > 0) fitSize();
        invalidate();
    }

    public void setTypeface(Typeface t) {
        paint.setTypeface(t);
        setTextSize(paint.getTextSize() / getResources().getDisplayMetrics().scaledDensity);
    }

    /** VS Code's default dark terminal colors, then the xterm 256-color cube and grays. */
    public void darkPalette() {
        int[] base = {0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510, 0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5,
                0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543, 0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFE5E5E5};
        fillPalette(base);
    }

    public void lightPalette() {
        int[] base = {0xFF000000, 0xFFCD3131, 0xFF00BC00, 0xFF949800, 0xFF0451A5, 0xFFBC05BC, 0xFF0598BC, 0xFF555555,
                0xFF666666, 0xFFCD3131, 0xFF14CE14, 0xFFB5BA00, 0xFF0451A5, 0xFFBC05BC, 0xFF0598BC, 0xFFA5A5A5};
        fillPalette(base);
    }

    private void fillPalette(int[] base) {
        System.arraycopy(base, 0, palette, 0, 16);
        int[] steps = {0, 95, 135, 175, 215, 255};
        for (int i = 0; i < 216; i++) {
            palette[16 + i] = 0xFF000000 | (steps[i / 36] << 16) | (steps[(i / 6) % 6] << 8) | steps[i % 6];
        }
        for (int i = 0; i < 24; i++) {
            int v = 8 + i * 10;
            palette[232 + i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
    }

    private int color(int c, boolean isFg) {
        if (c == TerminalEmulator.DEFAULT) return isFg ? foreground : background;
        if (c < 256) return palette[c];
        return 0xFF000000 | (c & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ size

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fitSize();
    }

    private void fitSize() {
        if (session == null || charW <= 0) return;
        int cols = Math.max(4, (int) ((getWidth() - getPaddingLeft() - getPaddingRight()) / charW));
        int rows = Math.max(2, (int) ((getHeight() - getPaddingTop() - getPaddingBottom()) / charH));
        if (cols != session.emulator.cols() || rows != session.emulator.rows()) session.resize(rows, cols);
    }

    public void scrollBy(int rows) {
        if (session == null) return;
        int max = session.emulator.historySize();
        scrollRows = Math.max(0, Math.min(max, scrollRows + rows));
        invalidate();
    }

    public void scrollToBottom() {
        scrollRows = 0;
        invalidate();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(background);
        if (session == null) return;
        TerminalEmulator t = session.emulator;
        synchronized (t) {
            int rows = t.rows(), cols = t.cols();
            float x0 = getPaddingLeft(), y0 = getPaddingTop();
            char[] buf = new char[cols * 2];
            for (int r = 0; r < rows; r++) {
                TerminalEmulator.Row row = t.row(r - scrollRows);
                if (row == null) continue;
                float y = y0 + r * charH;
                int n = Math.min(cols, row.ch.length);
                // Backgrounds as runs.
                int runStart = 0;
                int runBg = cellBg(row, 0);
                for (int c = 1; c <= n; c++) {
                    int b = c < n ? cellBg(row, c) : 0x7FFFFFFF;
                    if (b != runBg) {
                        if (runBg != background) {
                            bgPaint.setColor(runBg);
                            canvas.drawRect(x0 + runStart * charW, y, x0 + c * charW, y + charH, bgPaint);
                        }
                        runStart = c;
                        runBg = b;
                    }
                }
                // Text as runs of the same style.
                int c = 0;
                while (c < n) {
                    int fgc = cellFg(row, c), at = row.at[c];
                    int start = c, len = 0;
                    while (c < n && cellFg(row, c) == fgc && row.at[c] == at) {
                        int ch = row.ch[c];
                        if (ch == TerminalEmulator.WIDE_TAIL) {
                            c++;
                            continue;
                        }
                        if (ch >= 0x10000) {
                            len += Character.toChars(ch, buf, len);
                        } else {
                            buf[len++] = (char) ch;
                        }
                        c++;
                        // Wide characters and surrogates break monospace alignment: draw them alone.
                        if (ch >= 0x1100 && TerminalEmulator.width(ch) == 2) break;
                    }
                    if ((at & TerminalEmulator.INVISIBLE) != 0) continue;
                    boolean blank = true;
                    for (int i = 0; i < len; i++) if (buf[i] != ' ') {
                        blank = false;
                        break;
                    }
                    if (blank && (at & (TerminalEmulator.UNDERLINE | TerminalEmulator.STRIKE)) == 0) continue;
                    paint.setColor(fgc);
                    paint.setFakeBoldText((at & TerminalEmulator.BOLD) != 0);
                    paint.setTextSkewX((at & TerminalEmulator.ITALIC) != 0 ? -0.2f : 0);
                    paint.setUnderlineText((at & TerminalEmulator.UNDERLINE) != 0);
                    paint.setStrikeThruText((at & TerminalEmulator.STRIKE) != 0);
                    if ((at & TerminalEmulator.DIM) != 0) paint.setAlpha(150);
                    // Draw char by char at grid positions, so fonts with odd widths stay aligned.
                    float x = x0 + start * charW;
                    int cell = start;
                    for (int i = 0; i < len; ) {
                        int cp = Character.codePointAt(buf, i);
                        int k = Character.charCount(cp);
                        while (cell < n && row.ch[cell] == TerminalEmulator.WIDE_TAIL) cell++;
                        x = x0 + cell * charW;
                        canvas.drawText(buf, i, k, x, y + ascent, paint);
                        cell += TerminalEmulator.width(cp) == 2 ? 2 : 1;
                        i += k;
                    }
                    paint.setAlpha(255);
                }
            }
            // Cursor.
            if (t.cursorVisible && scrollRows == 0 && !session.exited()) {
                float cx = x0 + t.cursorX() * charW, cy = y0 + t.cursorY() * charH;
                bgPaint.setColor(cursorColor);
                if (hasFocus()) {
                    bgPaint.setAlpha(170);
                    canvas.drawRect(cx, cy, cx + charW, cy + charH, bgPaint);
                    bgPaint.setAlpha(255);
                } else {
                    bgPaint.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(cx, cy, cx + charW, cy + charH, bgPaint);
                    bgPaint.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    private int cellFg(TerminalEmulator.Row row, int c) {
        int at = row.at[c];
        int f = row.fg[c];
        if ((at & TerminalEmulator.BOLD) != 0 && f >= 0 && f < 8) f += 8; // bold brightens the basic colors
        return (at & TerminalEmulator.INVERSE) != 0 ? color(row.bg[c], false) : color(f, true);
    }

    private int cellBg(TerminalEmulator.Row row, int c) {
        return (row.at[c] & TerminalEmulator.INVERSE) != 0 ? color(row.fg[c], true) : color(row.bg[c], false);
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        gestures.onTouchEvent(e);
        return true;
    }

    // ------------------------------------------------------------------ keyboard

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        // No suggestions or autocorrect: every keystroke goes straight to the shell.
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                send(text.toString());
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Keyboards that insist on composing: send finished words only.
                return true;
            }

            @Override
            public boolean finishComposingText() {
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                for (int i = 0; i < Math.max(1, before); i++) sendRaw("\u007f");
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) onKeyDown(event.getKeyCode(), event);
                return true;
            }
        };
    }

    private void send(String s) {
        if (session == null || s.isEmpty()) return;
        if (host != null && host.interceptTyped(this, s)) return;
        sendRaw(s.replace('\n', '\r'));
    }

    /** Writes to the shell as typed (scrolls back to the prompt). */
    public void sendRaw(String s) {
        if (session == null) return;
        if (scrollRows != 0) scrollToBottom();
        session.write(s);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (session == null) return super.onKeyDown(code, e);
        String seq = keySequence(code, e, session.emulator.appCursorKeys);
        if (seq != null) {
            sendRaw(seq);
            return true;
        }
        int uc = e.getUnicodeChar(e.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK));
        if (uc != 0) {
            String s = new String(Character.toChars(uc));
            if (e.isCtrlPressed()) s = ctrl(uc);
            if (e.isAltPressed()) s = "\033" + s;
            sendRaw(s);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private static String ctrl(int c) {
        if (c >= 'a' && c <= 'z') return String.valueOf((char) (c - 'a' + 1));
        if (c >= 'A' && c <= 'Z') return String.valueOf((char) (c - 'A' + 1));
        switch (c) {
            case ' ':
            case '2':
            case '@':
                return "\u0000";
            case '[':
            case '3':
                return "\033";
            case '\\':
            case '4':
                return "\u001c";
            case ']':
            case '5':
                return "\u001d";
            case '^':
            case '6':
                return "\u001e";
            case '_':
            case '7':
            case '/':
                return "\u001f";
            case '8':
            case '?':
                return "\u007f";
            default:
                return new String(Character.toChars(c));
        }
    }

    /** Escape sequence for a non-character key (xterm conventions), or null. */
    static String keySequence(int code, KeyEvent e, boolean appCursor) {
        int mod = (e.isShiftPressed() ? 1 : 0) | (e.isAltPressed() ? 2 : 0) | (e.isCtrlPressed() ? 4 : 0);
        String m = mod == 0 ? null : String.valueOf(mod + 1);
        switch (code) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return e.isAltPressed() ? "\033\r" : "\r";
            case KeyEvent.KEYCODE_DEL:
                return e.isAltPressed() ? "\033\u007f" : e.isCtrlPressed() ? "\u0008" : "\u007f";
            case KeyEvent.KEYCODE_TAB:
                return e.isShiftPressed() ? "\033[Z" : "\t";
            case KeyEvent.KEYCODE_ESCAPE:
                return "\033";
            case KeyEvent.KEYCODE_DPAD_UP:
                return cursor('A', m, appCursor);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return cursor('B', m, appCursor);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return cursor('C', m, appCursor);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return cursor('D', m, appCursor);
            case KeyEvent.KEYCODE_MOVE_HOME:
                return cursor('H', m, appCursor);
            case KeyEvent.KEYCODE_MOVE_END:
                return cursor('F', m, appCursor);
            case KeyEvent.KEYCODE_PAGE_UP:
                return tilde(5, m);
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return tilde(6, m);
            case KeyEvent.KEYCODE_INSERT:
                return tilde(2, m);
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return tilde(3, m);
            case KeyEvent.KEYCODE_F1:
                return m == null ? "\033OP" : "\033[1;" + m + "P";
            case KeyEvent.KEYCODE_F2:
                return m == null ? "\033OQ" : "\033[1;" + m + "Q";
            case KeyEvent.KEYCODE_F3:
                return m == null ? "\033OR" : "\033[1;" + m + "R";
            case KeyEvent.KEYCODE_F4:
                return m == null ? "\033OS" : "\033[1;" + m + "S";
            case KeyEvent.KEYCODE_F5:
                return tilde(15, m);
            case KeyEvent.KEYCODE_F6:
                return tilde(17, m);
            case KeyEvent.KEYCODE_F7:
                return tilde(18, m);
            case KeyEvent.KEYCODE_F8:
                return tilde(19, m);
            case KeyEvent.KEYCODE_F9:
                return tilde(20, m);
            case KeyEvent.KEYCODE_F10:
                return tilde(21, m);
            case KeyEvent.KEYCODE_F11:
                return tilde(23, m);
            case KeyEvent.KEYCODE_F12:
                return tilde(24, m);
            default:
                return null;
        }
    }

    private static String cursor(char c, String mod, boolean app) {
        if (mod != null) return "\033[1;" + mod + c;
        return (app ? "\033O" : "\033[") + c;
    }

    private static String tilde(int n, String mod) {
        return "\033[" + n + (mod != null ? ";" + mod : "") + "~";
    }
}
