package com.disgusty.droid_codology.work;

import android.graphics.drawable.Drawable;

import com.disgusty.droid_codology.ui.Skin;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;

/** File and folder icons, tinted per file type with the Seti theme's colors (VS Code default). */
public final class FileIcons {
    private FileIcons() {
    }

    private static final HashMap<String, Integer> COLORS = new HashMap<String, Integer>();

    private static void c(int color, String exts) {
        for (String e : exts.split(" ")) COLORS.put(e, color);
    }

    static {
        // Seti palette: blue #519aba, green #8dc149, yellow #cbcb41, orange #e37933, red #cc3e44,
        // pink #f55385, purple #a074c4, grey #4d5a5e, white #d4d7d6, ignore #41535b.
        c(0xFFCBCB41, "js mjs cjs json jsonc json5");
        c(0xFF519ABA, "ts tsx mts cts c h cpp cc hpp cs go kt kts dart swift py pyw md markdown sql xml sh bash zsh ps1 bat cmd lua");
        c(0xFF8DC149, "vue sass scss rb rake gemspec");
        c(0xFFE37933, "html htm xhtml rs swift java jar class kts gradle groovy toml");
        c(0xFFCC3E44, "rb gitignore gitattributes lock");
        c(0xFFA074C4, "php hs elm clj cljs ex exs erl");
        c(0xFFF55385, "svg less scss");
        c(0xFF6D8086, "txt log ini cfg conf properties env editorconfig");
        c(0xFF519ABA, "css");
        c(0xFF8DC149, "yml yaml");
        c(0xFFE37933, "smali");
        c(0xFFCC3E44, "diff patch");
        c(0xFFA074C4, "png jpg jpeg gif webp bmp ico");
    }

    public static int colorFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : lower;
        Integer c = COLORS.get(ext);
        if (c == null && lower.equals("dockerfile")) c = 0xFF519ABA;
        if (c == null && lower.startsWith("makefile")) c = 0xFFE37933;
        return c != null ? c : 0xFF8B9499;
    }

    public static Drawable file(Skin skin, String name, int sizeDp) {
        String icon = "file";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg")) icon = "file";
        else if (lower.matches(".*\\.(c|h|cpp|java|kt|js|ts|py|go|rs|cs|php|rb|swift|dart|lua|sh|html|css|json|xml|yaml|yml|sql|smali)$")) icon = "file_code";
        return skin.icon(icon, colorFor(name), sizeDp);
    }

    public static Drawable folder(Skin skin, boolean open, int sizeDp) {
        return skin.icon(open ? "folder_open" : "folder", skin.dark ? 0xFFC09553 : 0xFFDCB67A, sizeDp);
    }

    public static Drawable of(Skin skin, File f, boolean open, int sizeDp) {
        return f.isDirectory() ? folder(skin, open, sizeDp) : file(skin, f.getName(), sizeDp);
    }
}
