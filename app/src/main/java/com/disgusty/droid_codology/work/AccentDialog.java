package com.disgusty.droid_codology.work;

import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.os.Build;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.disgusty.droid_codology.ui.ColorPicker;
import com.disgusty.droid_codology.ui.Skin;

import static com.disgusty.droid_codology.work.I18n.t;

/** Accent color chooser: the style's palette as swatches, "wallpaper" on Android 12+, and a custom HSV color. */
final class AccentDialog {
    private final Workbench wb;
    private final Skin skin;
    private final int style;
    private final Runnable onChanged;

    AccentDialog(Workbench wb, int style, Runnable onChanged) {
        this.wb = wb;
        this.skin = wb.skin;
        this.style = style;
        this.onChanged = onChanged;
    }

    /** A round swatch; draws a check when selected, a rainbow ring for "custom". */
    private final class Swatch extends View {
        final int color;
        final boolean selected, rainbow;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Swatch(int color, boolean selected, boolean rainbow) {
            super(wb.activity);
            this.color = color;
            this.selected = selected;
            this.rainbow = rainbow;
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - skin.dp(4);
            if (rainbow) {
                p.setShader(new SweepGradient(cx, cy, new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000}, null));
                c.drawCircle(cx, cy, r, p);
                p.setShader(null);
                p.setColor(color);
                c.drawCircle(cx, cy, r * 0.62f, p);
            } else {
                p.setColor(color);
                c.drawCircle(cx, cy, r, p);
            }
            if (selected) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(skin.dp(2.5f));
                p.setColor(Skin.onColor(color));
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(cx - r * 0.38f, cy, cx - r * 0.08f, cy + r * 0.3f, p);
                c.drawLine(cx - r * 0.08f, cy + r * 0.3f, cx + r * 0.4f, cy - r * 0.3f, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
    }

    void show() {
        final Prefs prefs = wb.prefs;
        int stored = Accents.stored(prefs, style);
        LinearLayout content = new LinearLayout(wb.activity);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] holder = new Dialog[1];

        int[] presets = Accents.presets(style);
        String[] names = Accents.names(style);
        int perRow = 6, size = skin.dp(48);
        LinearLayout row = null;
        int count = 0;
        // Default + wallpaper first.
        int defaultColor = skin.defaultAccent(skin.dark);
        java.util.ArrayList<Object[]> items = new java.util.ArrayList<Object[]>();
        items.add(new Object[]{0, defaultColor, t("Default"), false});
        if (Build.VERSION.SDK_INT >= 31) items.add(new Object[]{Accents.DYNAMIC, Accents.wallpaperSeed(wb.activity), t("Wallpaper"), false});
        for (int i = 0; i < presets.length; i++) items.add(new Object[]{presets[i], presets[i], names[i], false});
        boolean custom = stored != 0 && stored != Accents.DYNAMIC;
        for (int p : presets) if (p == stored) custom = false;
        items.add(new Object[]{-2, custom ? stored : skin.accent, t("Custom..."), true});
        for (final Object[] it : items) {
            if (count % perRow == 0) {
                row = new LinearLayout(wb.activity);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                content.addView(row);
            }
            count++;
            final int value = (Integer) it[0];
            int color = (Integer) it[1];
            boolean rainbow = (Boolean) it[3];
            boolean sel = rainbow ? custom : value == stored;
            Swatch s = new Swatch(color, sel, rainbow);
            s.setContentDescription((String) it[2]);
            s.setClickable(true);
            s.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    holder[0].dismiss();
                    if (value == -2) showCustom();
                    else apply(value);
                }
            });
            s.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    skin.toast(v, (String) it[2]);
                    return true;
                }
            });
            row.addView(s, new LinearLayout.LayoutParams(size, size));
        }
        Skin.DialogSpec spec = new Skin.DialogSpec();
        spec.title = t("Accent Color");
        spec.content = content;
        spec.negative = t("Cancel");
        holder[0] = skin.dialog(spec);
        holder[0].show();
    }

    private void apply(int value) {
        Accents.store(wb.prefs, style, value);
        if (onChanged != null) onChanged.run();
    }

    private void showCustom() {
        LinearLayout content = new LinearLayout(wb.activity);
        content.setOrientation(LinearLayout.VERTICAL);
        final ColorPicker picker = new ColorPicker(wb.activity);
        picker.setColor(skin.accent);
        content.addView(picker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(wb.activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final View preview = new View(wb.activity);
        preview.setBackgroundDrawable(skin.rounded(skin.accent, skin.dp(18)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(skin.dp(36), skin.dp(36));
        plp.rightMargin = skin.dp(12);
        row.addView(preview, plp);
        final EditText hex = skin.input("#RRGGBB");
        hex.setText(Accents.hex(skin.accent));
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        row.addView(hex, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = skin.dp(8);
        content.addView(row, rlp);
        final boolean[] updating = {false};
        picker.setListener(new ColorPicker.Listener() {
            @Override
            public void onColor(int argb) {
                preview.setBackgroundDrawable(skin.rounded(argb, skin.dp(18)));
                updating[0] = true;
                hex.setText(Accents.hex(argb));
                updating[0] = false;
            }
        });
        hex.addTextChangedListener(wb.simpleWatcher(new Runnable() {
            @Override
            public void run() {
                if (updating[0]) return;
                Integer c = parse(hex.getText().toString());
                if (c != null) {
                    picker.setColor(c);
                    preview.setBackgroundDrawable(skin.rounded(c, skin.dp(18)));
                }
            }
        }));
        Skin.DialogSpec spec = new Skin.DialogSpec();
        spec.title = t("Custom Accent Color");
        spec.content = content;
        spec.positive = t("OK");
        spec.negative = t("Cancel");
        spec.onPositive = new Runnable() {
            @Override
            public void run() {
                Integer c = parse(hex.getText().toString());
                apply(c != null ? c : picker.color());
            }
        };
        skin.dialog(spec).show();
    }

    static Integer parse(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        if (s.length() != 6) return null;
        try {
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static TextView label(Skin skin, String s) {
        return skin.caption(s);
    }
}
