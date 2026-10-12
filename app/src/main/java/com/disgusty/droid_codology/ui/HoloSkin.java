package com.disgusty.droid_codology.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.disgusty.droid_codology.editor.EditorColors;

import java.util.List;
import java.util.Locale;

/**
 * Android 3.0-4.4 "Holo": colors from AOSP colors_holo.xml (holo_blue_light #33B5E5, black /
 * #F3F3F3 windows), the 48dp action bar with app icon, ALL CAPS action-bar tabs with an accent
 * underline, underlined text fields with end ticks, the overhanging check mark, and alert
 * dialogs with an accent title rule and split borderless buttons. Holo Light uses the dark
 * action bar (Theme.Holo.Light.DarkActionBar), as most 4.x apps did.
 */
public final class HoloSkin extends Skin {
    public HoloSkin(Context ctx, boolean dark, int accent) {
        super(ctx, dark, accent == 0 ? (dark ? 0xFF33B5E5 : 0xFF0099CC) : accent);
        int a = this.accent;
        onAccent = onColor(a);
        // Dark action bar in both variants.
        titleBg = 0xFF222222;
        titleFg = 0xFFF3F3F3;
        tabsBg = dark ? 0xFF1A1A1A : 0xFFE8E8E8;
        if (dark) {
            windowBg = 0xFF000000;
            sideBarBg = 0xFF111111;
            sideBarFg = 0xFFF3F3F3;
            sideBarTitleFg = a;
            activityBg = 0xFF1A1A1A;
            activityFg = 0xFFF3F3F3;
            activityInactiveFg = 0xFF8C8C8C;
            tabActiveBg = 0xFF1A1A1A;
            tabActiveFg = 0xFFF3F3F3;
            tabInactiveBg = 0xFF1A1A1A;
            tabInactiveFg = 0xFFBEBEBE;
            tabBorder = 0x33FFFFFF;
            statusBg = 0xFF1A1A1A;
            statusFg = 0xFFBEBEBE;
            statusBorder = 0xFF2E2E2E;
            panelBg = 0xFF111111;
            border = 0x33FFFFFF;
            listActiveFg = 0xFFFFFFFF;
            inputBg = 0x00000000;
            inputFg = 0xFFF3F3F3;
            inputBorder = 0xFF808080;
            inputPlaceholder = 0xFF808080;
            secondaryButtonBg = 0xFF3A3A3A;
            secondaryButtonFg = 0xFFF3F3F3;
            menuBg = 0xFF282828;
            menuFg = 0xFFF3F3F3;
            menuBorder = 0xFF3D3D3D;
            widgetBg = 0xFF282828;
            widgetBorder = 0xFF3D3D3D;
            descriptionFg = 0xFFBEBEBE;
            keysBg = 0xFF1A1A1A;
            keysFg = 0xFFF3F3F3;
        } else {
            windowBg = 0xFFF3F3F3;
            sideBarBg = 0xFFF3F3F3;
            sideBarFg = 0xFF000000;
            sideBarTitleFg = a;
            activityBg = 0xFFE8E8E8;
            activityFg = 0xFF000000;
            activityInactiveFg = 0xFF6D6D6D;
            tabActiveBg = 0xFFE8E8E8;
            tabActiveFg = 0xFF000000;
            tabInactiveBg = 0xFFE8E8E8;
            tabInactiveFg = 0xFF323232;
            tabBorder = 0x1F000000;
            statusBg = 0xFFE8E8E8;
            statusFg = 0xFF323232;
            statusBorder = 0xFFD0D0D0;
            panelBg = 0xFFF3F3F3;
            border = 0x1F000000;
            listActiveFg = 0xFF000000;
            inputBg = 0x00000000;
            inputFg = 0xFF000000;
            inputBorder = 0xFF8C8C8C;
            inputPlaceholder = 0xFF808080;
            secondaryButtonBg = 0xFFE2E2E2;
            secondaryButtonFg = 0xFF000000;
            menuBg = 0xFFFFFFFF;
            menuFg = 0xFF000000;
            menuBorder = 0xFFC8C8C8;
            widgetBg = 0xFFFFFFFF;
            widgetBorder = 0xFFC8C8C8;
            descriptionFg = 0xFF323232;
            keysBg = 0xFFE8E8E8;
            keysFg = 0xFF000000;
        }
        activityIndicator = a;
        tabActiveIndicator = a;
        listPressed = withAlpha(a, 0x66);
        listActiveBg = withAlpha(a, 0x40);
        buttonBg = secondaryButtonBg;
        buttonFg = secondaryButtonFg;
        badgeBg = a;
        badgeFg = onAccent;
        menuSelectionBg = withAlpha(a, 0x66);
        focusBorder = a;
        textLink = a;
        errorFg = 0xFFFF4444;
        warningFg = 0xFFFFBB33;
        infoFg = a;
        keysPressed = withAlpha(a, 0x66);
        scrim = 0x99000000;
        toggleActiveBg = withAlpha(a, 0x33);
        toggleActiveBorder = a;
    }

    @Override
    public int id() {
        return HOLO;
    }

    @Override
    public int iconSet() {
        return Icons.SET_MATERIAL;
    }

    @Override
    public int defaultAccent(boolean dark) {
        return dark ? 0xFF33B5E5 : 0xFF0099CC;
    }

    @Override
    public boolean activityBarVertical() {
        return true;
    }

    @Override
    public float uiSp() {
        return 15;
    }

    @Override
    public int titleBarHeightDp() {
        return 48;
    }

    @Override
    public int tabHeightDp() {
        return 44;
    }

    @Override
    public int rowHeightDp() {
        return 44;
    }

    @Override
    public int statusBarHeightDp() {
        return 26;
    }

    @Override
    protected int iconSizeDp() {
        return 24;
    }

    /** Holo pressed / focused: the accent at 40%. */
    @Override
    protected Drawable iconButtonBackground() {
        return pressable(new ColorDrawable(0), new ColorDrawable(listPressed));
    }

    @Override
    public void adjustEditorColors(EditorColors c) {
        c.handle = accent;
        c.cursor = accent;
        c.selection = blend(dark ? 0xFF000000 : 0xFFFFFFFF, withAlpha(accent, 0x66));
        c.inactiveSelection = blend(dark ? 0xFF000000 : 0xFFFFFFFF, withAlpha(accent, 0x33));
        if (dark) {
            c.background = 0xFF000000;
            c.lineHighlightBorder = 0xFF1C1C1C;
        } else {
            c.background = 0xFFFFFFFF;
            c.lineHighlightBorder = 0xFFEAEAEA;
        }
        c.gutterBg = c.background;
    }

    // ------------------------------------------------------------------ action bar

    @Override
    public View titleBar(Action nav, CharSequence title, CharSequence subtitle, List<Action> actions, View centerContent) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(titleBg);
        if (nav != null) {
            // The "up" area: drawer indicator + app icon, pressed together like the 4.x action bar.
            LinearLayout up = new LinearLayout(ctx);
            up.setGravity(Gravity.CENTER_VERTICAL);
            up.setPadding(dp(4), 0, dp(6), 0);
            boolean back = "arrow_left".equals(nav.icon);
            ImageView ind = new ImageView(ctx);
            ind.setImageDrawable(back ? new CaretDrawable(withAlpha(titleFg, 0xCC), dp(6), dp(12)) : icon("menu", withAlpha(titleFg, 0xCC), 18));
            up.addView(ind, new LinearLayout.LayoutParams(dp(back ? 12 : 20), dp(24)));
            ImageView app = new ImageView(ctx);
            app.setImageResource(ctx.getApplicationInfo().icon);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(32), dp(32));
            alp.leftMargin = dp(2);
            up.addView(app, alp);
            up.setBackgroundDrawable(iconButtonBackground());
            up.setClickable(true);
            up.setOnClickListener(nav.click);
            up.setContentDescription(nav.label);
            bar.addView(up, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.setPadding(dp(8), 0, dp(4), 0);
        TextView t = text(title, 18, titleFg, regular());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(t);
        if (subtitle != null) {
            TextView st = text(subtitle, 13, 0xFFBEBEBE, regular());
            st.setSingleLine(true);
            titles.addView(st);
        }
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (actions != null) {
            for (Action a : actions) {
                View b = iconButton(a, withAlpha(titleFg, 0xE6), titleBarHeightDp());
                b.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(titleBarHeightDp())));
                bar.addView(b);
            }
        }
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(titleBarHeightDp())));
        return col;
    }

    /** The thin "‹" of the Holo up affordance. */
    static final class CaretDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int w, h;

        CaretDrawable(int color, int w, int h) {
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2, w / 3f));
            this.w = w;
            this.h = h;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.centerX(), cy = b.centerY();
            c.drawLine(cx + w / 2f, cy - h / 2f, cx - w / 2f, cy, p);
            c.drawLine(cx - w / 2f, cy, cx + w / 2f, cy + h / 2f, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @Override
    public int statusBarColor() {
        return 0xFF000000;
    }

    @Override
    public int navigationBarColor() {
        return 0xFF000000;
    }

    // ------------------------------------------------------------------ tabs (action bar tab style)

    private Drawable tabBackground(boolean selected) {
        int bar = selected ? accent : 0;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new EdgeDrawable(blend(tabsBg, listPressed), bar, dp(3), 0, 0, false));
        s.addState(new int[]{}, new EdgeDrawable(tabsBg, bar, dp(3), 0, 0, false));
        return s;
    }

    /** Side panel switcher: a vertical strip in the ActionBar tab style (ALL CAPS bold labels, 3dp accent edge). */
    @Override
    public View activityBar(List<NavItem> top, List<NavItem> bottom, int selected, final NavListener l) {
        return labeledRail(top, bottom, selected, l, 3, true, 9, Typeface.DEFAULT_BOLD);
    }

    @Override
    public int activityBarWidthDp() {
        return 64;
    }

    @Override
    public View tab(CharSequence label, Drawable fileIcon, boolean active, boolean dirty, boolean preview,
                    View.OnClickListener click, View.OnLongClickListener longClick, View.OnClickListener close) {
        LinearLayout t = new LinearLayout(ctx);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), 0, dp(2), 0);
        TextView tv = text(label, 14, active ? tabActiveFg : tabInactiveFg, active ? Typeface.DEFAULT_BOLD : regular());
        tv.setSingleLine(true);
        if (preview) tv.setTypeface(regular(), Typeface.ITALIC);
        t.addView(tv);
        ImageView x = new ImageView(ctx);
        x.setImageDrawable(icon(dirty ? "dirty" : "close", active ? tabActiveFg : tabInactiveFg, dirty ? 10 : 16));
        x.setScaleType(ImageView.ScaleType.CENTER);
        x.setBackgroundDrawable(iconButtonBackground());
        x.setOnClickListener(close);
        t.addView(x, new LinearLayout.LayoutParams(dp(32), dp(32)));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new EdgeDrawable(blend(tabsBg, listPressed), active ? accent : 0, dp(3), tabBorder, Math.max(1, dp(1)), false));
        s.addState(new int[]{}, new EdgeDrawable(tabsBg, active ? accent : 0, dp(3), tabBorder, Math.max(1, dp(1)), false));
        t.setBackgroundDrawable(s);
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setOnLongClickListener(longClick);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(tabHeightDp())));
        return t;
    }

    // ------------------------------------------------------------------ lists: Holo list section headers

    @Override
    public View sideBarHeader(CharSequence title, List<Action> actions) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(2), 0);
        TextView t = text(title.toString().toUpperCase(Locale.getDefault()), 14, accent, Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (actions != null) for (Action a : actions) row.addView(iconButton(a, sideBarFg, 40));
        col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        View rule = new View(ctx);
        rule.setBackgroundColor(withAlpha(accent, 0xB0));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        rl.setMargins(dp(8), 0, dp(8), 0);
        col.addView(rule, rl);
        return col;
    }

    @Override
    public View sectionHeader(CharSequence title, boolean expanded, View.OnClickListener toggle) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);
        ImageView chev = new ImageView(ctx);
        chev.setImageDrawable(icon(expanded ? "chevron_down" : "chevron_right", descriptionFg, 18));
        row.addView(chev, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(title.toString().toUpperCase(Locale.getDefault()), 13, descriptionFg, Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setMinimumHeight(dp(36));
        row.setBackgroundDrawable(listSelector(false));
        row.setClickable(true);
        row.setOnClickListener(toggle);
        return row;
    }

    // ------------------------------------------------------------------ status bar

    @Override
    public TextView statusItem(CharSequence s, String iconName, View.OnClickListener l) {
        TextView t = super.statusItem(s, iconName, l);
        t.setTextSize(12);
        if (l != null) t.setBackgroundDrawable(pressable(new ColorDrawable(0), new ColorDrawable(listPressed)));
        return t;
    }

    // ------------------------------------------------------------------ buttons, fields, checks

    @Override
    public TextView button(CharSequence label, boolean primary, View.OnClickListener l) {
        TextView b = text(label, 15, secondaryButtonFg, regular());
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setMinHeight(dp(48));
        b.setMinWidth(dp(64));
        // btn_default_holo: a flat 2dp-rounded gray body with a darker bottom edge; pressed = accent.
        b.setBackgroundDrawable(pressable(new HoloButtonDrawable(secondaryButtonBg, dark ? 0xFF2A2A2A : 0xFFBDBDBD, dp(2)),
                new HoloButtonDrawable(withAlpha(accent, 0xCC), accent, dp(2))));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    static final class HoloButtonDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int fill, edge;
        private final float r;
        private final RectF rect = new RectF();

        HoloButtonDrawable(int fill, int edge, float r) {
            this.fill = fill;
            this.edge = edge;
            this.r = r;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float inset = r * 1.5f;
            rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            p.setColor(edge);
            c.drawRoundRect(rect, r, r, p);
            rect.bottom -= Math.max(1, r / 2);
            p.setColor(fill);
            c.drawRoundRect(rect, r, r, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @Override
    public TextView textButton(CharSequence label, View.OnClickListener l) {
        TextView b = text(label, 15, sideBarFg, regular());
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setMinHeight(dp(48));
        b.setBackgroundDrawable(pressable(new ColorDrawable(0), new ColorDrawable(listPressed)));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** textfield_default_holo: a bottom rule with short end ticks; 2dp accent when focused. */
    static final class FieldDrawable extends Drawable {
        private final Paint p = new Paint();
        private final int color, px, tick;

        FieldDrawable(int color, int px, int tick) {
            this.color = color;
            this.px = px;
            this.tick = tick;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            p.setColor(color);
            int y = b.bottom - px - tick / 2;
            c.drawRect(b.left + px, y, b.right - px, y + px, p);
            c.drawRect(b.left + px, y - tick, b.left + px * 2, y + px, p);
            c.drawRect(b.right - px * 2, y - tick, b.right - px, y + px, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @Override
    protected Drawable inputBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, new FieldDrawable(accent, Math.max(2, dp(1.5f)), dp(4)));
        s.addState(new int[]{}, new FieldDrawable(inputBorder, Math.max(1, dp(1)), dp(4)));
        return s;
    }

    @Override
    public void styleInput(android.widget.EditText e) {
        super.styleInput(e);
        e.setTextSize(16);
        e.setPadding(dp(8), dp(8), dp(8), dp(10));
        e.setMinHeight(dp(44));
    }

    @Override
    public TextView inputToggle(String iconName, String text, boolean on, View.OnClickListener l) {
        TextView t = super.inputToggle(iconName, text, on, l);
        if (on) t.setBackgroundDrawable(new EdgeDrawable(withAlpha(accent, 0x26), accent, dp(2), 0, 0, false));
        return t;
    }

    @Override
    protected int checkSizeDp() {
        return 32;
    }

    /** btn_check_holo: a gray square whose accent check mark overhangs its top-right corner. */
    @Override
    public void drawCheck(Canvas c, int w, int h, boolean on, Paint p) {
        float s = Math.min(w, h);
        float box = s * 0.5f, left = (w - box) / 2f - s * 0.05f, top = (h - box) / 2f + s * 0.05f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, dp(1.5f)));
        p.setColor(dark ? 0xFF8C8C8C : 0xFF6D6D6D);
        p.setStrokeCap(Paint.Cap.SQUARE);
        c.drawRect(left, top, left + box, top + box, p);
        if (on) {
            p.setColor(accent);
            p.setStrokeWidth(dp(3));
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(left + box * 0.15f, top + box * 0.5f, left + box * 0.45f, top + box * 0.82f, p);
            c.drawLine(left + box * 0.45f, top + box * 0.82f, left + box * 1.15f, top - box * 0.2f, p);
        }
    }

    // ------------------------------------------------------------------ dialogs (AlertDialog.Holo)

    @Override
    protected void buildDialog(Dialog d, LinearLayout panel, DialogSpec spec) {
        panel.setBackgroundDrawable(stroked(widgetBg, dark ? 0xFF3D3D3D : 0xFFC8C8C8, Math.max(1, dp(1)), dp(2)));
        if (spec.title != null) {
            TextView t = text(spec.title, 20, accent, regular());
            t.setPadding(dp(16), dp(16), dp(16), dp(14));
            panel.addView(t);
            View rule = new View(ctx);
            rule.setBackgroundColor(accent);
            panel.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
        }
        View body = dialogBody(spec);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (spec.positive == null && spec.negative == null && spec.neutral == null) return;
        panel.addView(hairline(border));
        LinearLayout buttons = new LinearLayout(ctx);
        String[] labels = {spec.negative, spec.neutral, spec.positive};
        Runnable[] actions = {spec.onNegative, spec.onNeutral, spec.onPositive};
        boolean first = true;
        for (int i = 0; i < 3; i++) {
            if (labels[i] == null) continue;
            if (!first) buttons.addView(vline(border));
            first = false;
            TextView b = textButton(labels[i], dismissThen(d, actions[i]));
            buttons.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        panel.addView(buttons);
    }

    @Override
    protected View dialogBody(DialogSpec spec) {
        View v = super.dialogBody(spec);
        return v;
    }

    // ------------------------------------------------------------------ menus

    @Override
    protected int menuRowDp() {
        return 48;
    }

    @Override
    protected int segmentRadiusDp() {
        return 2;
    }

    @Override
    protected int segmentHeightDp() {
        return 40;
    }

    @Override
    protected int menuPadDp() {
        return 16;
    }

    @Override
    protected Drawable menuBackground() {
        return stroked(menuBg, menuBorder, Math.max(1, dp(1)), dp(1));
    }

    @Override
    protected Drawable menuSelector() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(menuSelectionBg));
        s.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(menuSelectionBg));
        s.addState(new int[]{}, new ColorDrawable(0));
        return s;
    }

    @Override
    public Drawable listSelector(boolean active) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(listPressed));
        s.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(withAlpha(accent, 0x40)));
        s.addState(new int[]{}, new ColorDrawable(active ? listActiveBg : 0));
        return s;
    }

    @Override
    public Drawable quickInputBackground() {
        return stroked(menuBg, menuBorder, Math.max(1, dp(1)), dp(2));
    }

    @Override
    public View decorateDrawer(View panel) {
        // A 1dp divider on the drawer's open edge, as in the 4.3 navigation drawer.
        FrameLayout f = new FrameLayout(ctx);
        f.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View edge = new View(ctx);
        edge.setBackgroundColor(dark ? 0xFF2E2E2E : 0xFFC8C8C8);
        f.addView(edge, new FrameLayout.LayoutParams(Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT));
        return f;
    }

    @Override
    public void elevate(View v, float dpValue) {
        // Holo is flat.
    }

    @Override
    public Typeface medium() {
        return Typeface.DEFAULT_BOLD;
    }

    @Override
    public int nativeTheme() {
        return dark ? android.R.style.Theme_Holo_NoActionBar : android.R.style.Theme_Holo_Light_NoActionBar;
    }

    static boolean preLollipop() {
        return Build.VERSION.SDK_INT < 21;
    }
}
