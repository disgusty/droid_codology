package com.disgusty.droid_codology.work;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, named after their VS Code equivalents where one exists. */
public final class Prefs {
    private final SharedPreferences sp;
    private final Context ctx;

    public Prefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public Context context() {
        return ctx;
    }

    // ---- workbench
    public static final String STYLE = "workbench.style";              // Skin.ORIGINAL...
    public static final String THEME = "workbench.colorTheme";         // "system" | "dark" | "light"
    public static final String THEME_EXT = "workbench.colorTheme.extension"; // "extId|path|uiTheme" or unset
    public static final String ACCENT = "workbench.accent";            // 0 = style default
    public static final String SIDEBAR = "workbench.sideBar.visible";
    public static final String EXTRA_KEYS = "workbench.extraKeys";
    /** The film's falling code behind the interface. */
    public static final String MATRIX_RAIN = "workbench.matrixRain";
    /** Extension code runs on Node.js in Alpine Linux instead of the WebView (alpine mode only). Per device. */
    public static final String NODE_EXT_HOST = "state.extensions.nodeHost";
    /**
     * The app's mode: alpine (terminals and, if chosen, extensions run in Alpine Linux) or standard
     * (off: Android only, even with Alpine downloaded). Per device: the system lives on this device
     * only. A new key, so every device starts in standard mode.
     */
    public static final String ALPINE_MODE = "state.mode.alpine";
    public static final String COMMAND_CENTER = "window.commandCenter";
    public static final String BREADCRUMBS = "breadcrumbs.enabled";
    public static final String UI_DENSITY = "workbench.density";       // "touch" | "compact"
    // ---- editor
    public static final String FONT_SIZE = "editor.fontSize";
    public static final String FONT_FILE = "editor.fontFamily";
    public static final String LINE_HEIGHT = "editor.lineHeight";
    public static final String TAB_SIZE = "editor.tabSize";
    public static final String INSERT_SPACES = "editor.insertSpaces";
    public static final String DETECT_INDENT = "editor.detectIndentation";
    public static final String WORD_WRAP = "editor.wordWrap";
    public static final String LINE_NUMBERS = "editor.lineNumbers";    // "on" | "off" | "relative"
    public static final String WHITESPACE = "editor.renderWhitespace";
    public static final String INDENT_GUIDES = "editor.guides.indentation";
    public static final String HIGHLIGHT_LINE = "editor.renderLineHighlight";
    public static final String MATCH_BRACKETS = "editor.matchBrackets";
    public static final String BRACKET_COLORS = "editor.bracketPairColorization.enabled";
    public static final String AUTO_CLOSE = "editor.autoClosingBrackets";
    public static final String AUTO_INDENT = "editor.autoIndent";
    public static final String RULERS = "editor.rulers";
    public static final String SUGGESTIONS = "editor.keyboardSuggestions";
    public static final String FOLDING = "editor.folding";
    public static final String STICKY_SCROLL = "editor.stickyScroll.enabled";
    public static final String MINIMAP = "editor.minimap.enabled";
    public static final String SUGGEST = "editor.quickSuggestions";
    public static final String HIGHLIGHTER = "editor.highlighter";
    // ---- files
    public static final String AUTO_SAVE = "files.autoSave";           // "off" | "afterDelay" | "onFocusChange"
    public static final String ENCODING = "files.encoding";
    public static final String EOL = "files.eol";
    public static final String TRIM_WS = "files.trimTrailingWhitespace";
    public static final String FINAL_NEWLINE = "files.insertFinalNewline";
    public static final String HOT_EXIT = "files.hotExit";
    public static final String SHOW_HIDDEN = "files.showHidden";
    public static final String EXCLUDE = "files.exclude";
    // ---- state
    public static final String FOLDER = "state.folder";
    public static final String RECENT = "state.recentFolders";
    public static final String ZOOM = "state.zoom";

    public String str(String k, String def) {
        return sp.getString(k, def);
    }

    public int i(String k, int def) {
        try {
            return sp.getInt(k, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public float f(String k, float def) {
        try {
            return sp.getFloat(k, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public boolean b(String k, boolean def) {
        try {
            return sp.getBoolean(k, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public void put(String k, Object v) {
        SharedPreferences.Editor e = sp.edit();
        if (v == null) e.remove(k);
        else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else e.putString(k, v.toString());
        e.apply();
    }

    public SharedPreferences raw() {
        return sp;
    }
}
