package com.disgusty.droid_codology.ext;

import com.disgusty.droid_codology.editor.EditorColors;
import com.disgusty.droid_codology.io.Jsonc;
import com.disgusty.droid_codology.lang.Tok;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Reads a VS Code color theme (.json, with "include" of a base theme) into editor colors, token
 * colors and the workbench color table that the VS Code style applies to its chrome.
 */
public final class ThemeLoader {
    private ThemeLoader() {
    }

    public static final class Loaded {
        public final EditorColors editor;
        /** Workbench color ids ("activityBar.background"...) → ARGB. */
        public final HashMap<String, Integer> workbench = new HashMap<String, Integer>();

        Loaded(EditorColors e) {
            editor = e;
        }
    }

    /** Scopes to resolve per token style, most specific candidate first. */
    private static final String[][] CANDIDATES = new String[Tok.COUNT][];

    static {
        for (int i = 0; i < Tok.COUNT; i++) CANDIDATES[i] = new String[]{Tok.SCOPES[i]};
        CANDIDATES[Tok.KEYWORD] = new String[]{"keyword", "storage.type", "storage.modifier", "storage"};
        CANDIDATES[Tok.CONTROL] = new String[]{"keyword.control", "keyword"};
        CANDIDATES[Tok.TYPE] = new String[]{"entity.name.type", "entity.name.class", "support.type", "support.class", "storage.type"};
        CANDIDATES[Tok.FUNCTION] = new String[]{"entity.name.function", "support.function", "meta.function-call"};
        CANDIDATES[Tok.CONSTANT] = new String[]{"constant.language", "constant", "support.constant"};
        CANDIDATES[Tok.PUNCT] = new String[]{"punctuation", "meta.brace"};
        CANDIDATES[Tok.OPERATOR] = new String[]{"keyword.operator"};
        CANDIDATES[Tok.PROPERTY] = new String[]{"variable.other.property", "support.type.property-name", "meta.object-literal.key", "variable.other.object.property"};
        CANDIDATES[Tok.VARIABLE] = new String[]{"variable.other.readwrite", "variable", "variable.other"};
        CANDIDATES[Tok.PARAMETER] = new String[]{"variable.parameter", "variable"};
        CANDIDATES[Tok.ANNOTATION] = new String[]{"meta.decorator", "storage.type.annotation", "entity.name.function.decorator", "punctuation.decorator"};
        CANDIDATES[Tok.PREPROC] = new String[]{"meta.preprocessor", "keyword.control.directive", "keyword.control"};
        CANDIDATES[Tok.DOC_COMMENT] = new String[]{"comment.block.documentation", "comment"};
        CANDIDATES[Tok.HEADING] = new String[]{"markup.heading", "entity.name.section"};
        CANDIDATES[Tok.CODE] = new String[]{"markup.inline.raw", "markup.raw", "string"};
        CANDIDATES[Tok.LINK] = new String[]{"markup.underline.link", "string.other.link"};
        CANDIDATES[Tok.NAMESPACE] = new String[]{"entity.name.namespace", "entity.name.type"};
    }

    public static Loaded load(File file, boolean dark) throws Exception {
        EditorColors base = dark ? EditorColors.darkModern() : EditorColors.lightModern();
        Loaded out = new Loaded(base.copy());
        HashMap<String, Integer> colors = new HashMap<String, Integer>();
        JSONArray tokenColors = new JSONArray();
        collect(file, colors, tokenColors, 0);
        out.workbench.putAll(colors);
        EditorColors e = out.editor;
        e.name = file.getName();
        e.dark = dark;
        applyEditor(e, colors);
        applyTokens(e, tokenColors);
        return out;
    }

    /** Reads a theme file and its "include" chain (base first, so the theme overrides it). */
    private static void collect(File f, Map<String, Integer> colors, JSONArray tokens, int depth) throws Exception {
        if (depth > 5 || f == null || !f.isFile()) return;
        JSONObject o = new JSONObject(Jsonc.clean(ExtensionManager.read(f)));
        String inc = o.optString("include", null);
        if (inc != null) collect(new File(f.getParentFile(), inc), colors, tokens, depth + 1);
        JSONObject c = o.optJSONObject("colors");
        if (c != null) {
            Iterator<String> keys = c.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Integer v = parse(c.optString(k, null));
                if (v != null) colors.put(k, v);
            }
        }
        Object tc = o.opt("tokenColors");
        if (tc instanceof JSONArray) {
            JSONArray a = (JSONArray) tc;
            for (int i = 0; i < a.length(); i++) tokens.put(a.opt(i));
        } else if (tc instanceof String) {
            // tokenColors in a separate .tmTheme / json file.
            File tf = new File(f.getParentFile(), (String) tc);
            if (tf.getName().endsWith(".json")) {
                JSONObject to = new JSONObject(Jsonc.clean(ExtensionManager.read(tf)));
                JSONArray a = to.optJSONArray("tokenColors");
                if (a != null) for (int i = 0; i < a.length(); i++) tokens.put(a.opt(i));
            }
        }
        JSONObject sem = o.optJSONObject("semanticTokenColors");
        if (sem != null) {
            // Not applied (no semantic tokens without a language server).
        }
    }

    /** "#RGB", "#RGBA", "#RRGGBB", "#RRGGBBAA" → ARGB. */
    public static Integer parse(String s) {
        if (s == null || !s.startsWith("#")) return null;
        String h = s.substring(1);
        try {
            if (h.length() == 3 || h.length() == 4) {
                StringBuilder x = new StringBuilder();
                for (char c : h.toCharArray()) x.append(c).append(c);
                h = x.toString();
            }
            if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
            if (h.length() == 8) {
                long v = Long.parseLong(h, 16);
                int rgb = (int) (v >> 8) & 0xFFFFFF, a = (int) (v & 0xFF);
                return (a << 24) | rgb;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static void applyEditor(EditorColors e, Map<String, Integer> c) {
        e.background = opaque(get(c, "editor.background", e.background), e.dark);
        e.foreground = get(c, "editor.foreground", e.foreground);
        e.tokens[Tok.PLAIN] = e.foreground;
        e.lineNumber = get(c, "editorLineNumber.foreground", e.lineNumber);
        e.lineNumberActive = get(c, "editorLineNumber.activeForeground", e.lineNumberActive);
        e.selection = over(e.background, get(c, "editor.selectionBackground", e.selection));
        e.inactiveSelection = over(e.background, get(c, "editor.inactiveSelectionBackground", Skin_alpha(e.selection, 0x99)));
        e.cursor = get(c, "editorCursor.foreground", e.cursor);
        e.lineHighlight = get(c, "editor.lineHighlightBackground", 0);
        e.lineHighlightBorder = get(c, "editor.lineHighlightBorder", c.containsKey("editor.lineHighlightBackground") ? 0 : e.lineHighlightBorder);
        e.indentGuide = get(c, "editorIndentGuide.background1", get(c, "editorIndentGuide.background", e.indentGuide));
        e.indentGuideActive = get(c, "editorIndentGuide.activeBackground1", get(c, "editorIndentGuide.activeBackground", e.indentGuideActive));
        e.whitespace = get(c, "editorWhitespace.foreground", e.whitespace);
        e.findMatchCurrent = get(c, "editor.findMatchBackground", e.findMatchCurrent);
        e.findMatch = get(c, "editor.findMatchHighlightBackground", e.findMatch);
        e.wordHighlight = get(c, "editor.wordHighlightBackground", e.wordHighlight);
        e.bracketMatch = get(c, "editorBracketMatch.background", e.bracketMatch);
        e.bracketMatchBorder = get(c, "editorBracketMatch.border", e.bracketMatchBorder);
        e.rulers = get(c, "editorRuler.foreground", e.rulers);
        e.scrollThumb = get(c, "scrollbarSlider.background", e.scrollThumb);
        e.scrollThumbActive = get(c, "scrollbarSlider.activeBackground", e.scrollThumbActive);
        e.gutterBg = opaque(get(c, "editorGutter.background", e.background), e.dark);
        e.minimapSlider = get(c, "minimapSlider.background", e.minimapSlider);
        e.errorSquiggle = get(c, "editorError.foreground", e.errorSquiggle);
        e.warningSquiggle = get(c, "editorWarning.foreground", e.warningSquiggle);
        e.tokens[Tok.BRACKET1] = get(c, "editorBracketHighlight.foreground1", e.tokens[Tok.BRACKET1]);
        e.tokens[Tok.BRACKET2] = get(c, "editorBracketHighlight.foreground2", e.tokens[Tok.BRACKET2]);
        e.tokens[Tok.BRACKET3] = get(c, "editorBracketHighlight.foreground3", e.tokens[Tok.BRACKET3]);
        e.composingUnderline = e.foreground;
        e.handle = e.cursor;
    }

    private static int Skin_alpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private static int opaque(int c, boolean dark) {
        return (c >>> 24) == 0xFF ? c : over(dark ? 0xFF1E1E1E : 0xFFFFFFFF, c);
    }

    /** Alpha-composites a translucent theme color over the background. */
    private static int over(int base, int c) {
        float a = (c >>> 24) / 255f;
        if (a >= 0.999f) return c;
        int r = (int) (((base >> 16) & 0xFF) * (1 - a) + ((c >> 16) & 0xFF) * a);
        int g = (int) (((base >> 8) & 0xFF) * (1 - a) + ((c >> 8) & 0xFF) * a);
        int b = (int) ((base & 0xFF) * (1 - a) + (c & 0xFF) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int get(Map<String, Integer> c, String key, int def) {
        Integer v = c.get(key);
        return v != null ? v : def;
    }

    /**
     * For each token style, the theme rule whose selector best matches its candidate scopes: a
     * selector matches a scope when it is a dot-prefix of it; longer prefixes are more specific.
     */
    private static void applyTokens(EditorColors e, JSONArray rules) {
        // Global settings (rule without scope) set the default foreground.
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.optJSONObject(i);
            if (r == null || r.has("scope")) continue;
            JSONObject s = r.optJSONObject("settings");
            Integer fg = s == null ? null : parse(s.optString("foreground", null));
            if (fg != null) {
                e.foreground = fg;
                e.tokens[Tok.PLAIN] = fg;
            }
        }
        for (int tok = 0; tok < Tok.COUNT; tok++) {
            if (tok == Tok.BRACKET1 || tok == Tok.BRACKET2 || tok == Tok.BRACKET3) continue;
            boolean found = false;
            for (String scope : CANDIDATES[tok]) {
                int bestLen = -1;
                Integer bestFg = null;
                int bestStyle = -1;
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject r = rules.optJSONObject(i);
                    if (r == null) continue;
                    JSONObject s = r.optJSONObject("settings");
                    if (s == null) continue;
                    Object sc = r.opt("scope");
                    String[] selectors;
                    if (sc instanceof JSONArray) {
                        JSONArray a = (JSONArray) sc;
                        selectors = new String[a.length()];
                        for (int k = 0; k < a.length(); k++) selectors[k] = a.optString(k);
                    } else if (sc instanceof String) {
                        selectors = ((String) sc).split(",");
                    } else continue;
                    for (String sel : selectors) {
                        sel = sel.trim();
                        if (sel.isEmpty()) continue;
                        // "source.js keyword": only the last part is compared (no scope stack here).
                        int sp = sel.lastIndexOf(' ');
                        String last = sp >= 0 ? sel.substring(sp + 1) : sel;
                        if (last.startsWith("-")) continue;
                        if (!(scope.equals(last) || scope.startsWith(last + "."))) continue;
                        int len = last.length() - (sp >= 0 ? 1 : 0); // descendant selectors are less certain
                        if (len >= bestLen) {
                            Integer fg = parse(s.optString("foreground", null));
                            String fs = s.optString("fontStyle", null);
                            if (fg == null && fs == null) continue;
                            bestLen = len;
                            if (fg != null) bestFg = fg;
                            if (fs != null) bestStyle = (fs.contains("bold") ? 1 : 0) | (fs.contains("italic") ? 2 : 0);
                        }
                    }
                }
                if (bestFg != null || bestStyle >= 0) {
                    if (bestFg != null) e.tokens[tok] = bestFg;
                    if (bestStyle >= 0) e.fontStyle[tok] = bestStyle;
                    found = true;
                    break;
                }
            }
            if (!found && tok != Tok.PLAIN && (tok == Tok.PUNCT || tok == Tok.OPERATOR || tok == Tok.VARIABLE || tok == Tok.PARAMETER || tok == Tok.PROPERTY)) {
                e.tokens[tok] = e.foreground;
            }
        }
    }
}
