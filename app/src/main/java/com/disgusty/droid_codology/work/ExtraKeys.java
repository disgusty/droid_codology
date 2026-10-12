package com.disgusty.droid_codology.work;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.disgusty.droid_codology.text.EditEngine;
import com.disgusty.droid_codology.ui.Skin;

/**
 * A row of keys missing from phone keyboards (Tab, Esc, arrows, brackets, symbols) plus sticky
 * Ctrl / Alt / Shift modifiers. Arrow keys auto-repeat while held.
 */
final class ExtraKeys extends HorizontalScrollView {
    private final Workbench wb;
    private final Skin skin;
    private boolean ctrl, alt, shift;
    private TextView ctrlKey, altKey, shiftKey;

    private static final String[] KEYS = {
            "ESC", "TAB", "CTRL", "ALT", "SHIFT", "←", "↓", "↑", "→", "HOME", "END",
            "PGUP", "PGDN", "&"
    };

    ExtraKeys(Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        setBackgroundColor(skin.keysBg);
        LinearLayout row = new LinearLayout(wb.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String k : KEYS) row.addView(key(k));
        addView(row, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(40)));
    }

    private View key(final String k) {
        final TextView t = skin.text(k, k.length() > 2 ? 11 : 16, skin.keysFg, skin.regular());
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(skin.dp(k.length() > 2 ? 46 : 38));
        t.setPadding(skin.dp(4), 0, skin.dp(4), 0);
        t.setBackgroundDrawable(skin.pressable(new android.graphics.drawable.ColorDrawable(0), new android.graphics.drawable.ColorDrawable(skin.keysPressed)));
        t.setClickable(true);
        if (k.equals("CTRL")) ctrlKey = t;
        if (k.equals("ALT")) altKey = t;
        if (k.equals("SHIFT")) shiftKey = t;
        final boolean repeat = k.startsWith("←") || k.startsWith("↑") || k.startsWith("→") || k.startsWith("↓") || k.equals("PGUP") || k.equals("PGDN");
        if (repeat) {
            t.setOnTouchListener(new OnTouchListener() {
                final Runnable rep = new Runnable() {
                    @Override
                    public void run() {
                        press(k);
                        t.postDelayed(this, 45);
                    }
                };

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        t.setPressed(true);
                        press(k);
                        t.postDelayed(rep, 380);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        t.setPressed(false);
                        t.removeCallbacks(rep);
                    }
                    return true;
                }
            });
        } else {
            t.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    press(k);
                }
            });
        }
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return t;
    }

    private void press(String k) {
        if (k.equals("CTRL")) {
            ctrl = !ctrl;
            mark(ctrlKey, ctrl);
            return;
        }
        if (k.equals("ALT")) {
            alt = !alt;
            mark(altKey, alt);
            return;
        }
        if (k.equals("SHIFT")) {
            shift = !shift;
            mark(shiftKey, shift);
            return;
        }
        int code;
        char ch = 0;
        switch (k) {
            case "ESC": code = KeyEvent.KEYCODE_ESCAPE; break;
            case "TAB": code = KeyEvent.KEYCODE_TAB; break;
            case "←": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "→": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "↑": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "↓": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "HOME": code = KeyEvent.KEYCODE_MOVE_HOME; break;
            case "END": code = KeyEvent.KEYCODE_MOVE_END; break;
            case "PGUP": code = KeyEvent.KEYCODE_PAGE_UP; break;
            case "PGDN": code = KeyEvent.KEYCODE_PAGE_DOWN; break;
            default:
                code = 0;
                ch = k.charAt(0);
        }
        int meta = (ctrl ? KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON : 0) | (alt ? KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON : 0)
                | (shift ? KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON : 0);
        if (code != 0) {
            sendKey(code, meta);
        } else if (meta != 0 && keyCodeFor(ch) != 0) {
            sendKey(keyCodeFor(ch), meta);
        } else if (wb.activity.getCurrentFocus() instanceof com.disgusty.droid_codology.term.TerminalView) {
            ((com.disgusty.droid_codology.term.TerminalView) wb.activity.getCurrentFocus()).sendRaw(String.valueOf(ch));
        } else if (wb.activeDocument() != null && wb.editor.hasFocus()) {
            EditEngine e = wb.activeDocument().engine;
            if (!wb.activeDocument().readOnly) e.type(String.valueOf(ch));
        } else {
            View f = wb.activity.getCurrentFocus();
            if (f instanceof android.widget.EditText) ((android.widget.EditText) f).getText().replace(
                    ((android.widget.EditText) f).getSelectionStart(), ((android.widget.EditText) f).getSelectionEnd(), String.valueOf(ch));
        }
        // Modifiers are one-shot unless the key was itself a modifier.
        if (ctrl || alt || shift) {
            ctrl = alt = shift = false;
            mark(ctrlKey, false);
            mark(altKey, false);
            mark(shiftKey, false);
        }
    }

    /** Sticky Ctrl applied to the next letter typed on the on-screen keyboard. */
    boolean consumeTyped(String s) {
        if (!ctrl && !alt) return false;
        if (s.length() != 1) return false;
        char c = Character.toLowerCase(s.charAt(0));
        int code = c >= 'a' && c <= 'z' ? KeyEvent.KEYCODE_A + (c - 'a') : keyCodeFor(c);
        if (code == 0) return false;
        int meta = (ctrl ? KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON : 0) | (alt ? KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON : 0)
                | (shift ? KeyEvent.META_SHIFT_ON : 0);
        ctrl = alt = shift = false;
        mark(ctrlKey, false);
        mark(altKey, false);
        mark(shiftKey, false);
        sendKey(code, meta);
        return true;
    }

    boolean modifierLatched() {
        return ctrl || alt;
    }

    private static int keyCodeFor(char c) {
        switch (c) {
            case '/': return KeyEvent.KEYCODE_SLASH;
            case '\\': return KeyEvent.KEYCODE_BACKSLASH;
            case '[': return KeyEvent.KEYCODE_LEFT_BRACKET;
            case ']': return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case ',': return KeyEvent.KEYCODE_COMMA;
            case '.': return KeyEvent.KEYCODE_PERIOD;
            case '=': return KeyEvent.KEYCODE_EQUALS;
            case '-': return KeyEvent.KEYCODE_MINUS;
            case ';': return KeyEvent.KEYCODE_SEMICOLON;
            case '\'': return KeyEvent.KEYCODE_APOSTROPHE;
            case '`': return KeyEvent.KEYCODE_GRAVE;
            default:
                if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
                if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
                return 0;
        }
    }

    private void sendKey(int code, int meta) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta);
        if (wb.dispatchKey(down)) return;
        View target = wb.activity.getCurrentFocus();
        if (target == null) target = wb.editor;
        target.dispatchKeyEvent(down);
        target.dispatchKeyEvent(up);
    }

    private void mark(TextView t, boolean on) {
        if (t == null) return;
        t.setTextColor(on ? skin.accent : skin.keysFg);
        t.setBackgroundDrawable(on ? skin.rounded(skin.toggleActiveBg, skin.dp(4))
                : skin.pressable(new android.graphics.drawable.ColorDrawable(0), new android.graphics.drawable.ColorDrawable(skin.keysPressed)));
    }
}
