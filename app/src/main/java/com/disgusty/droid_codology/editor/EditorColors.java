package com.disgusty.droid_codology.editor;

import com.disgusty.droid_codology.lang.Tok;

/**
 * Colors used to paint the code area. The built-in sets are VS Code's "Dark Modern" and "Light
 * Modern" (MIT-licensed theme values from microsoft/vscode); extension themes overwrite fields.
 */
public final class EditorColors {
    public String name;
    public boolean dark;
    public int background, foreground, lineHighlight, lineHighlightBorder, selection, inactiveSelection,
            cursor, lineNumber, lineNumberActive, indentGuide, indentGuideActive, whitespace, findMatch,
            findMatchCurrent, findMatchBorder, wordHighlight, bracketMatch, bracketMatchBorder, rulers,
            scrollThumb, scrollThumbActive, composingUnderline, handle, foldBg, errorSquiggle,
            warningSquiggle, gutterBg, minimapSlider;
    public final int[] tokens = new int[Tok.COUNT];
    /** Bit 0 = bold, bit 1 = italic. */
    public final int[] fontStyle = new int[Tok.COUNT];

    public EditorColors copy() {
        EditorColors c = new EditorColors();
        c.name = name;
        c.dark = dark;
        c.background = background;
        c.foreground = foreground;
        c.lineHighlight = lineHighlight;
        c.lineHighlightBorder = lineHighlightBorder;
        c.selection = selection;
        c.inactiveSelection = inactiveSelection;
        c.cursor = cursor;
        c.lineNumber = lineNumber;
        c.lineNumberActive = lineNumberActive;
        c.indentGuide = indentGuide;
        c.indentGuideActive = indentGuideActive;
        c.whitespace = whitespace;
        c.findMatch = findMatch;
        c.findMatchCurrent = findMatchCurrent;
        c.findMatchBorder = findMatchBorder;
        c.wordHighlight = wordHighlight;
        c.bracketMatch = bracketMatch;
        c.bracketMatchBorder = bracketMatchBorder;
        c.rulers = rulers;
        c.scrollThumb = scrollThumb;
        c.scrollThumbActive = scrollThumbActive;
        c.composingUnderline = composingUnderline;
        c.handle = handle;
        c.foldBg = foldBg;
        c.errorSquiggle = errorSquiggle;
        c.warningSquiggle = warningSquiggle;
        c.gutterBg = gutterBg;
        c.minimapSlider = minimapSlider;
        System.arraycopy(tokens, 0, c.tokens, 0, tokens.length);
        System.arraycopy(fontStyle, 0, c.fontStyle, 0, fontStyle.length);
        return c;
    }

    public static EditorColors darkModern() {
        EditorColors c = new EditorColors();
        c.name = "Dark Modern";
        c.dark = true;
        c.background = 0xFF1F1F1F;
        c.foreground = 0xFFCCCCCC;
        c.lineHighlight = 0x00000000;
        c.lineHighlightBorder = 0xFF282828;
        c.selection = 0xFF264F78;
        c.inactiveSelection = 0xFF3A3D41;
        c.cursor = 0xFFAEAFAD;
        c.lineNumber = 0xFF6E7681;
        c.lineNumberActive = 0xFFCCCCCC;
        c.indentGuide = 0xFF404040;
        c.indentGuideActive = 0xFF707070;
        c.whitespace = 0x29E3E4E2;
        c.findMatchBorder = 0x00000000;
        c.wordHighlight = 0x48575757;
        c.bracketMatchBorder = 0xFF888888;
        c.rulers = 0xFF5A5A5A;
        c.scrollThumb = 0x5579797A;
        c.scrollThumbActive = 0x66BFBFBF;
        c.composingUnderline = 0xFFCCCCCC;
        c.handle = 0xFF0078D4;
        c.foldBg = 0x33264F78;
        c.errorSquiggle = 0xFFF14C4C;
        c.warningSquiggle = 0xFFCCA700;
        c.gutterBg = c.background;
        c.minimapSlider = 0x3379797A;
        int[] t = c.tokens;
        t[Tok.PLAIN] = 0xFFCCCCCC;
        t[Tok.KEYWORD] = 0xFF569CD6;
        t[Tok.CONTROL] = 0xFFC586C0;
        t[Tok.TYPE] = 0xFF4EC9B0;
        t[Tok.FUNCTION] = 0xFFDCDCAA;
        t[Tok.STRING] = 0xFFCE9178;
        t[Tok.NUMBER] = 0xFFB5CEA8;
        t[Tok.COMMENT] = 0xFF6A9955;
        t[Tok.DOC_COMMENT] = 0xFF6A9955;
        t[Tok.OPERATOR] = 0xFFD4D4D4;
        t[Tok.PUNCT] = 0xFFCCCCCC;
        t[Tok.CONSTANT] = 0xFF569CD6;
        t[Tok.ANNOTATION] = 0xFFDCDCAA;
        t[Tok.TAG] = 0xFF569CD6;
        t[Tok.ATTR] = 0xFF9CDCFE;
        t[Tok.REGEX] = 0xFFD16969;
        t[Tok.ESCAPE] = 0xFFD7BA7D;
        t[Tok.PREPROC] = 0xFFC586C0;
        t[Tok.PROPERTY] = 0xFF9CDCFE;
        t[Tok.HEADING] = 0xFF569CD6;
        t[Tok.BOLD] = 0xFFCCCCCC;
        t[Tok.ITALIC] = 0xFFCCCCCC;
        t[Tok.LINK] = 0xFF3794FF;
        t[Tok.CODE] = 0xFFCE9178;
        t[Tok.INVALID] = 0xFFF44747;
        t[Tok.DIFF_ADD] = 0xFFB5CEA8;
        t[Tok.DIFF_DEL] = 0xFFCE9178;
        t[Tok.VARIABLE] = 0xFF9CDCFE;
        t[Tok.PARAMETER] = 0xFF9CDCFE;
        t[Tok.NAMESPACE] = 0xFF4EC9B0;
        // editorBracketHighlight.foreground1..3
        t[Tok.BRACKET1] = 0xFFFFD700;
        t[Tok.BRACKET2] = 0xFFDA70D6;
        t[Tok.BRACKET3] = 0xFF179FFF;
        c.fontStyle[Tok.HEADING] = 1;
        c.fontStyle[Tok.BOLD] = 1;
        c.fontStyle[Tok.ITALIC] = 2;
        c.fontStyle[Tok.DOC_COMMENT] = 0;
        fixups(c);
        return c;
    }

    /**
     * "Matrix": phosphor on black, every shade derived from {@code glow} (#00FF41 by default).
     * Keywords are near-white like the leading glyph of a falling code column; comments are dim.
     */
    public static EditorColors matrix(int glow) {
        int bright = glow | 0xFF000000;
        int white = mix(bright, 0xFFFFFFFF, 0.72f);
        int light = mix(bright, 0xFFFFFFFF, 0.4f);
        int mid = mix(bright, 0xFF000000, 0.22f);
        int dim = mix(bright, 0xFF000000, 0.45f);
        int faint = mix(bright, 0xFF000000, 0.78f);
        int ghost = mix(bright, 0xFF000000, 0.9f);
        int lime = mix(bright, 0xFFE0FF40, 0.45f);
        EditorColors c = new EditorColors();
        c.name = "Matrix";
        c.dark = true;
        c.background = 0xFF000000;
        c.foreground = mid;
        c.lineHighlight = mix(bright, 0xFF000000, 0.94f);
        c.lineHighlightBorder = 0x00000000;
        c.selection = mix(bright, 0xFF000000, 0.7f);
        c.inactiveSelection = mix(bright, 0xFF000000, 0.82f);
        c.cursor = bright;
        c.lineNumber = faint;
        c.lineNumberActive = bright;
        c.indentGuide = ghost;
        c.indentGuideActive = faint;
        c.whitespace = (faint & 0x00FFFFFF) | 0x80000000;
        c.findMatchBorder = bright;
        c.wordHighlight = (bright & 0x00FFFFFF) | 0x30000000;
        c.bracketMatchBorder = bright;
        c.rulers = faint;
        c.scrollThumb = (bright & 0x00FFFFFF) | 0x40000000;
        c.scrollThumbActive = (bright & 0x00FFFFFF) | 0x80000000;
        c.composingUnderline = bright;
        c.handle = bright;
        c.foldBg = (bright & 0x00FFFFFF) | 0x22000000;
        c.errorSquiggle = 0xFFFF4040;
        c.warningSquiggle = 0xFFE8E040;
        c.gutterBg = c.background;
        c.minimapSlider = (bright & 0x00FFFFFF) | 0x33000000;
        int[] t = c.tokens;
        t[Tok.PLAIN] = mid;
        t[Tok.KEYWORD] = white;
        t[Tok.CONTROL] = white;
        t[Tok.TYPE] = light;
        t[Tok.FUNCTION] = bright;
        t[Tok.STRING] = lime;
        t[Tok.NUMBER] = lime;
        t[Tok.COMMENT] = dim;
        t[Tok.DOC_COMMENT] = dim;
        t[Tok.OPERATOR] = light;
        t[Tok.PUNCT] = dim;
        t[Tok.CONSTANT] = lime;
        t[Tok.ANNOTATION] = light;
        t[Tok.TAG] = bright;
        t[Tok.ATTR] = light;
        t[Tok.REGEX] = lime;
        t[Tok.ESCAPE] = white;
        t[Tok.PREPROC] = white;
        t[Tok.PROPERTY] = light;
        t[Tok.HEADING] = white;
        t[Tok.BOLD] = white;
        t[Tok.ITALIC] = mid;
        t[Tok.LINK] = light;
        t[Tok.CODE] = lime;
        t[Tok.INVALID] = 0xFFFF4040;
        t[Tok.DIFF_ADD] = bright;
        t[Tok.DIFF_DEL] = 0xFFFF6060;
        t[Tok.VARIABLE] = mid;
        t[Tok.PARAMETER] = light;
        t[Tok.NAMESPACE] = light;
        t[Tok.BRACKET1] = bright;
        t[Tok.BRACKET2] = lime;
        t[Tok.BRACKET3] = light;
        c.fontStyle[Tok.KEYWORD] = 1;
        c.fontStyle[Tok.HEADING] = 1;
        c.fontStyle[Tok.BOLD] = 1;
        c.fontStyle[Tok.ITALIC] = 2;
        c.fontStyle[Tok.COMMENT] = 2;
        fixups(c);
        c.findMatch = (bright & 0x00FFFFFF) | 0x40000000;
        c.findMatchCurrent = (bright & 0x00FFFFFF) | 0x99000000;
        c.bracketMatch = (bright & 0x00FFFFFF) | 0x26000000;
        return c;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    public static EditorColors lightModern() {
        EditorColors c = new EditorColors();
        c.name = "Light Modern";
        c.dark = false;
        c.background = 0xFFFFFFFF;
        c.foreground = 0xFF3B3B3B;
        c.lineHighlight = 0x00000000;
        c.lineHighlightBorder = 0xFFEEEEEE;
        c.selection = 0xFFADD6FF;
        c.inactiveSelection = 0xFFE5EBF1;
        c.cursor = 0xFF000000;
        c.lineNumber = 0xFF6E7681;
        c.lineNumberActive = 0xFF171184;
        c.indentGuide = 0xFFD3D3D3;
        c.indentGuideActive = 0xFF939393;
        c.whitespace = 0x33333333;
        c.findMatchBorder = 0x00000000;
        c.wordHighlight = 0x40575757;
        c.bracketMatchBorder = 0xFFB9B9B9;
        c.rulers = 0xFFD3D3D3;
        c.scrollThumb = 0x66646464;
        c.scrollThumbActive = 0x99000000;
        c.composingUnderline = 0xFF3B3B3B;
        c.handle = 0xFF005FB8;
        c.foldBg = 0x4DADD6FF;
        c.errorSquiggle = 0xFFE51400;
        c.warningSquiggle = 0xFFBF8803;
        c.gutterBg = c.background;
        c.minimapSlider = 0x33646464;
        int[] t = c.tokens;
        t[Tok.PLAIN] = 0xFF3B3B3B;
        t[Tok.KEYWORD] = 0xFF0000FF;
        t[Tok.CONTROL] = 0xFFAF00DB;
        t[Tok.TYPE] = 0xFF267F99;
        t[Tok.FUNCTION] = 0xFF795E26;
        t[Tok.STRING] = 0xFFA31515;
        t[Tok.NUMBER] = 0xFF098658;
        t[Tok.COMMENT] = 0xFF008000;
        t[Tok.DOC_COMMENT] = 0xFF008000;
        t[Tok.OPERATOR] = 0xFF000000;
        t[Tok.PUNCT] = 0xFF3B3B3B;
        t[Tok.CONSTANT] = 0xFF0000FF;
        t[Tok.ANNOTATION] = 0xFF795E26;
        t[Tok.TAG] = 0xFF800000;
        t[Tok.ATTR] = 0xFFE50000;
        t[Tok.REGEX] = 0xFF811F3F;
        t[Tok.ESCAPE] = 0xFFEE0000;
        t[Tok.PREPROC] = 0xFFAF00DB;
        t[Tok.PROPERTY] = 0xFF001080;
        t[Tok.HEADING] = 0xFF800000;
        t[Tok.BOLD] = 0xFF000080;
        t[Tok.ITALIC] = 0xFF3B3B3B;
        t[Tok.LINK] = 0xFF005FB8;
        t[Tok.CODE] = 0xFF800000;
        t[Tok.INVALID] = 0xFFCD3131;
        t[Tok.DIFF_ADD] = 0xFF098658;
        t[Tok.DIFF_DEL] = 0xFFA31515;
        t[Tok.VARIABLE] = 0xFF001080;
        t[Tok.PARAMETER] = 0xFF001080;
        t[Tok.NAMESPACE] = 0xFF267F99;
        t[Tok.BRACKET1] = 0xFF0431FA;
        t[Tok.BRACKET2] = 0xFF319331;
        t[Tok.BRACKET3] = 0xFF7B3814;
        c.fontStyle[Tok.HEADING] = 1;
        c.fontStyle[Tok.BOLD] = 1;
        c.fontStyle[Tok.ITALIC] = 2;
        fixups(c);
        return c;
    }

    private static void fixups(EditorColors c) {
        // editor.findMatchHighlightBackground (other matches) and editor.findMatchBackground (current).
        if (c.dark) {
            c.findMatch = 0x55EA5C00;
            c.findMatchCurrent = 0xFF9E6A03;
            c.bracketMatch = 0x1A0064FF;
        } else {
            c.findMatch = 0x4DEA5C00;
            c.findMatchCurrent = 0xFFA8AC94;
            c.bracketMatch = 0x1A006400;
        }
    }
}
