package com.disgusty.droid_codology.work;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Command registry and keybinding resolver, modeled on VS Code: every action has a command id,
 * a title for the command palette, and default key chords (including two-step chords such as
 * "ctrl+k ctrl+o"). Extensions add commands and bindings through the same tables.
 */
public final class Commands {
    public static final class Command {
        public final String id, title, category;
        public final Runnable run;
        /** Optional predicate deciding whether the command is available now. */
        public When when;
        public final ArrayList<String> keys = new ArrayList<String>();

        Command(String id, String category, String title, Runnable run) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.run = run;
        }

        public String label() {
            return category == null ? title : category + ": " + title;
        }

        public String keyLabel() {
            return keys.isEmpty() ? null : pretty(keys.get(0));
        }
    }

    public interface When {
        boolean ok();
    }

    private final LinkedHashMap<String, Command> byId = new LinkedHashMap<String, Command>();
    private final HashMap<String, Command> byKey = new HashMap<String, Command>();
    /** First halves of two-step chords. */
    private final HashMap<String, Boolean> prefixes = new HashMap<String, Boolean>();
    private String pendingChord;
    private long pendingTime;

    public Command add(String id, String category, String title, Runnable run, String... keys) {
        Command c = new Command(id, category, title, run);
        byId.put(id, c);
        for (String k : keys) bind(k, c);
        return c;
    }

    public void bind(String chord, Command c) {
        chord = chord.toLowerCase(Locale.ROOT);
        c.keys.add(chord);
        byKey.put(chord, c);
        int sp = chord.indexOf(' ');
        if (sp > 0) prefixes.put(chord.substring(0, sp), Boolean.TRUE);
    }

    public Command get(String id) {
        return byId.get(id);
    }

    /** Removes a command and its key bindings (an extension that was disabled or uninstalled). */
    public void remove(String id) {
        Command c = byId.remove(id);
        if (c == null) return;
        for (String k : c.keys) if (byKey.get(k) == c) byKey.remove(k);
    }

    /** Takes {@code chord} off {@code c} (a binding an extension added). */
    public void unbind(String chord, Command c) {
        chord = chord.toLowerCase(Locale.ROOT);
        c.keys.remove(chord);
        if (byKey.get(chord) == c) byKey.remove(chord);
    }

    public boolean run(String id) {
        Command c = byId.get(id);
        if (c == null || (c.when != null && !c.when.ok())) return false;
        c.run.run();
        return true;
    }

    /** Every registered command, available now or not. */
    public java.util.Collection<Command> registered() {
        return byId.values();
    }

    public List<Command> all() {
        ArrayList<Command> l = new ArrayList<Command>();
        for (Command c : byId.values()) if (c.when == null || c.when.ok()) l.add(c);
        return l;
    }

    /** Result of a key press: handled by a command, swallowed as a chord prefix, or not ours. */
    public static final int NONE = 0, RAN = 1, PREFIX = 2;

    public int onKey(KeyEvent e) {
        String k = chordOf(e);
        if (k == null) return NONE;
        if (pendingChord != null && System.currentTimeMillis() - pendingTime < 3000) {
            String full = pendingChord + " " + k;
            pendingChord = null;
            Command c = byKey.get(full);
            if (c != null && (c.when == null || c.when.ok())) {
                c.run.run();
                return RAN;
            }
            return RAN; // an unknown second half is swallowed, as in VS Code
        }
        pendingChord = null;
        if (prefixes.containsKey(k)) {
            pendingChord = k;
            pendingTime = System.currentTimeMillis();
            return PREFIX;
        }
        Command c = byKey.get(k);
        if (c != null && (c.when == null || c.when.ok())) {
            c.run.run();
            return RAN;
        }
        return NONE;
    }

    public String pendingChord() {
        return pendingChord;
    }

    /** "ctrl+shift+p" style name of a key event, or null for bare modifier presses. */
    public static String chordOf(KeyEvent e) {
        String key = keyName(e.getKeyCode());
        if (key == null) return null;
        StringBuilder sb = new StringBuilder();
        if (e.isCtrlPressed()) sb.append("ctrl+");
        if (e.isShiftPressed()) sb.append("shift+");
        if (e.isAltPressed()) sb.append("alt+");
        if (e.isMetaPressed()) sb.append("meta+");
        // Plain characters without modifiers are typing, not commands (except F-keys etc.).
        if (sb.length() == 0 && key.length() == 1) return null;
        if (sb.toString().equals("shift+") && key.length() == 1) return null;
        sb.append(key);
        return sb.toString();
    }

    static String keyName(int code) {
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return String.valueOf((char) ('a' + code - KeyEvent.KEYCODE_A));
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return String.valueOf((char) ('0' + code - KeyEvent.KEYCODE_0));
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) return "f" + (code - KeyEvent.KEYCODE_F1 + 1);
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: return "enter";
            case KeyEvent.KEYCODE_TAB: return "tab";
            case KeyEvent.KEYCODE_ESCAPE: return "escape";
            case KeyEvent.KEYCODE_DEL: return "backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "delete";
            case KeyEvent.KEYCODE_INSERT: return "insert";
            case KeyEvent.KEYCODE_SPACE: return "space";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_MOVE_HOME: return "home";
            case KeyEvent.KEYCODE_MOVE_END: return "end";
            case KeyEvent.KEYCODE_PAGE_UP: return "pageup";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "pagedown";
            case KeyEvent.KEYCODE_LEFT_BRACKET: return "[";
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return "]";
            case KeyEvent.KEYCODE_SLASH: return "/";
            case KeyEvent.KEYCODE_BACKSLASH: return "\\";
            case KeyEvent.KEYCODE_COMMA: return ",";
            case KeyEvent.KEYCODE_PERIOD: return ".";
            case KeyEvent.KEYCODE_EQUALS: return "=";
            case KeyEvent.KEYCODE_PLUS: return "=";
            case KeyEvent.KEYCODE_MINUS: return "-";
            case KeyEvent.KEYCODE_GRAVE: return "`";
            case KeyEvent.KEYCODE_SEMICOLON: return ";";
            case KeyEvent.KEYCODE_APOSTROPHE: return "'";
            case KeyEvent.KEYCODE_NUMPAD_ADD: return "numpad_add";
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return "numpad_subtract";
            default: return null;
        }
    }

    /** "ctrl+shift+p" → "Ctrl+Shift+P". */
    public static String pretty(String chord) {
        StringBuilder sb = new StringBuilder();
        for (String part : chord.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            String[] keys = part.split("\\+", -1);
            for (int i = 0; i < keys.length; i++) {
                String k = keys[i];
                if (k.isEmpty() && i + 1 < keys.length) continue;
                if (i > 0) sb.append('+');
                if (k.isEmpty()) k = "+";
                if (k.length() == 1) sb.append(k.toUpperCase(Locale.ROOT));
                else if (k.equals("pageup")) sb.append("PgUp");
                else if (k.equals("pagedown")) sb.append("PgDn");
                else if (k.startsWith("f") && k.length() <= 3 && Character.isDigit(k.charAt(1))) sb.append(k.toUpperCase(Locale.ROOT));
                else sb.append(Character.toUpperCase(k.charAt(0))).append(k.substring(1));
            }
        }
        return sb.toString();
    }
}
