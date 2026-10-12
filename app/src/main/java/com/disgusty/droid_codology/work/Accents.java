package com.disgusty.droid_codology.work;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import com.disgusty.droid_codology.ui.Skin;

/**
 * Accent presets per style, taken from each era's official palette, plus a per-style custom
 * color. 0 means "the style's default"; DYNAMIC means the wallpaper color (Android 12+).
 */
public final class Accents {
    private Accents() {
    }

    public static final int DYNAMIC = 1;

    /** VS Code: the default blue plus colors of popular built-in/high-contrast themes. */
    static final int[] ORIGINAL = {0xFF0078D4, 0xFF007ACC, 0xFF68217A, 0xFF16825D, 0xFFC72E0F, 0xFFCC6633, 0xFF2D7D9A, 0xFFB180D7};
    static final String[] ORIGINAL_NAMES = {"Modern Blue", "Classic Blue", "Purple", "Green", "Red", "Orange", "Teal", "Lavender"};

    /** Holo: the five colors of colors_holo.xml (light variants). */
    static final int[] HOLO = {0xFF33B5E5, 0xFFAA66CC, 0xFF99CC00, 0xFFFFBB33, 0xFFFF4444, 0xFF0099CC, 0xFF9933CC, 0xFF669900, 0xFFFF8800, 0xFFCC0000};
    static final String[] HOLO_NAMES = {"Holo Blue", "Holo Purple", "Holo Green", "Holo Orange", "Holo Red", "Holo Blue Dark", "Holo Purple Dark", "Holo Green Dark", "Holo Orange Dark", "Holo Red Dark"};

    /** Material Design 2014 palette, 500 shades. */
    static final int[] MATERIAL = {0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4,
            0xFF009688, 0xFF4CAF50, 0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722, 0xFF795548, 0xFF607D8B};
    static final String[] MATERIAL_NAMES = {"Red", "Pink", "Purple", "Deep Purple", "Indigo", "Blue", "Light Blue", "Cyan", "Teal",
            "Green", "Light Green", "Lime", "Yellow", "Amber", "Orange", "Deep Orange", "Brown", "Blue Grey"};

    /** Material You seeds: the M3 baseline and the Android 12 "basic colors" family. */
    static final int[] MATERIAL3 = {0xFF6750A4, 0xFF1B6EF3, 0xFF006A6A, 0xFF386A20, 0xFF8C4A60, 0xFFB3261E, 0xFF7D5700, 0xFF5E5E5E};
    static final String[] MATERIAL3_NAMES = {"Baseline", "Blue", "Teal", "Green", "Rose", "Red", "Amber", "Neutral"};

    /** Phosphor colors of old screens: the film's green, softer greens, amber and cyan. */
    static final int[] MATRIX = {0xFF00FF41, 0xFF00D936, 0xFF39FF14, 0xFF9DFF00, 0xFF00E5FF, 0xFFFFB000, 0xFFE8E8E8};
    static final String[] MATRIX_NAMES = {"Phosphor", "Terminal Green", "Neon", "Lime", "Cyan", "Amber", "White"};

    public static int[] presets(int style) {
        switch (style) {
            case Skin.MATRIX: return MATRIX;
            case Skin.HOLO: return HOLO;
            case Skin.MATERIAL: return MATERIAL;
            case Skin.MATERIAL3: return MATERIAL3;
            default: return ORIGINAL;
        }
    }

    public static String[] names(int style) {
        switch (style) {
            case Skin.MATRIX: return MATRIX_NAMES;
            case Skin.HOLO: return HOLO_NAMES;
            case Skin.MATERIAL: return MATERIAL_NAMES;
            case Skin.MATERIAL3: return MATERIAL3_NAMES;
            default: return ORIGINAL_NAMES;
        }
    }

    static String key(int style) {
        return Prefs.ACCENT + "." + style;
    }

    /** The stored choice for a style: 0 (default), DYNAMIC or an ARGB color. */
    public static int stored(Prefs p, int style) {
        int v = p.i(key(style), Integer.MIN_VALUE);
        if (v == Integer.MIN_VALUE) v = style == Skin.MATERIAL3 && Build.VERSION.SDK_INT >= 31 ? DYNAMIC : 0;
        return v;
    }

    public static void store(Prefs p, int style, int value) {
        p.put(key(style), value);
    }

    /** The accent to build the skin with (0 = style default). */
    public static int current(Prefs p, int style) {
        int v = stored(p, style);
        if (v == DYNAMIC) return Build.VERSION.SDK_INT >= 31 ? wallpaperSeed(p.context()) : 0;
        return v;
    }

    @TargetApi(31)
    static int wallpaperSeed(Context ctx) {
        // system_accent1_500 is the seed-derived mid tone of the wallpaper palette.
        return ctx.getResources().getColor(android.R.color.system_accent1_500, null);
    }

    public static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }
}
