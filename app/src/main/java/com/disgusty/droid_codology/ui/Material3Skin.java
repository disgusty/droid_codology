package com.disgusty.droid_codology.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
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

/**
 * Material Design 3 / Material You (Android 12+): every color comes from the tonal scheme built
 * from the accent seed ({@link MaterialColor}); 64dp top app bar on the surface color, navigation
 * rail with pill indicators, secondary tabs, 40dp fully-rounded buttons, 28dp dialogs, the M3
 * switch, 4dp menus on surface containers, the inverse-surface Snackbar and Material Symbols.
 */
public final class Material3Skin extends Skin {
    public final MaterialColor.Scheme s;

    public Material3Skin(Context ctx, boolean dark, int accent) {
        super(ctx, dark, accent == 0 ? 0xFF6750A4 : accent);
        MaterialColor.Scheme sc = MaterialColor.scheme(this.accent, dark);
        s = sc;
        onAccent = sc.onPrimary;
        windowBg = sc.surface;
        titleBg = sc.surface;
        titleFg = sc.onSurface;
        sideBarBg = sc.surfaceContainerLow;
        sideBarFg = sc.onSurface;
        sideBarTitleFg = sc.onSurfaceVariant;
        activityBg = sc.surfaceContainerLow;
        activityFg = sc.onSecondaryContainer;
        activityInactiveFg = sc.onSurfaceVariant;
        activityIndicator = sc.secondaryContainer;
        tabsBg = sc.surface;
        tabActiveBg = sc.surface;
        tabInactiveBg = sc.surface;
        tabActiveFg = sc.onSurface;
        tabInactiveFg = sc.onSurfaceVariant;
        tabBorder = 0;
        tabActiveIndicator = sc.primary;
        statusBg = sc.surfaceContainer;
        statusFg = sc.onSurfaceVariant;
        statusBorder = sc.surfaceContainer;
        panelBg = sc.surfaceContainerLow;
        border = sc.outlineVariant;
        listPressed = withAlpha(sc.onSurface, 0x1F);
        listActiveBg = sc.secondaryContainer;
        listActiveFg = sc.onSecondaryContainer;
        inputBg = 0;
        inputFg = sc.onSurface;
        inputBorder = sc.outline;
        inputPlaceholder = sc.onSurfaceVariant;
        buttonBg = sc.primary;
        buttonFg = sc.onPrimary;
        secondaryButtonBg = sc.secondaryContainer;
        secondaryButtonFg = sc.onSecondaryContainer;
        badgeBg = sc.error;
        badgeFg = sc.onError;
        menuBg = sc.surfaceContainer;
        menuFg = sc.onSurface;
        menuSelectionBg = withAlpha(sc.onSurface, 0x1F);
        menuBorder = 0;
        widgetBg = sc.surfaceContainerHigh;
        widgetBorder = 0;
        focusBorder = sc.primary;
        textLink = sc.primary;
        descriptionFg = sc.onSurfaceVariant;
        errorFg = sc.error;
        warningFg = sc.tertiary;
        infoFg = sc.primary;
        keysBg = sc.surfaceContainer;
        keysFg = sc.onSurfaceVariant;
        keysPressed = withAlpha(sc.onSurface, 0x1F);
        scrim = 0x52000000;
        toggleActiveBg = sc.secondaryContainer;
        toggleActiveBorder = sc.secondaryContainer;
    }

    @Override
    public int id() {
        return MATERIAL3;
    }

    @Override
    public int iconSet() {
        return Icons.SET_SYMBOLS;
    }

    @Override
    public int defaultAccent(boolean dark) {
        return 0xFF6750A4;
    }

    @Override
    protected boolean usesRipple() {
        return true;
    }

    @Override
    protected int rippleColor() {
        return withAlpha(s.onSurface, 0x1F);
    }

    @Override
    public Typeface medium() {
        return Build.VERSION.SDK_INT >= 21 ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT_BOLD;
    }

    @Override
    public float uiSp() {
        return 15;
    }

    @Override
    public int titleBarHeightDp() {
        return 64;
    }

    @Override
    public int tabHeightDp() {
        return 48;
    }

    @Override
    public int rowHeightDp() {
        return 40;
    }

    @Override
    public int statusBarHeightDp() {
        return 28;
    }

    @Override
    public int activityBarWidthDp() {
        return 80;
    }

    @Override
    protected int iconSizeDp() {
        return 24;
    }

    @Override
    public int statusBarColor() {
        return s.surface;
    }

    @Override
    public int navigationBarColor() {
        return s.surfaceContainer;
    }

    @Override
    public void adjustEditorColors(EditorColors c) {
        c.handle = s.primary;
        c.cursor = s.primary;
        c.background = s.surface;
        c.gutterBg = s.surface;
        c.selection = blend(s.surface, withAlpha(s.primary, dark ? 0x4D : 0x3D));
        c.inactiveSelection = blend(s.surface, withAlpha(s.primary, 0x1F));
        c.lineHighlightBorder = 0;
        c.lineHighlight = s.surfaceContainerLow;
        c.lineNumber = withAlpha(s.onSurfaceVariant, 0x99);
        c.lineNumberActive = s.onSurface;
        c.indentGuide = withAlpha(s.outlineVariant, 0xB0);
        c.scrollThumb = withAlpha(s.onSurfaceVariant, 0x55);
        c.foldBg = withAlpha(s.primaryContainer, 0x66);
    }

    @Override
    protected Drawable iconButtonBackground() {
        if (Build.VERSION.SDK_INT >= 21) {
            // Icon buttons: a 40dp circular state layer.
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(0xFFFFFFFF);
            Drawable r = com.disgusty.droid_codology.compat.Api21.ripple(rippleColor(), null, new InsetDrawable(mask, dp(4)));
            if (r != null) return r;
        }
        return pressable(new ColorDrawable(0), rounded(listPressed, dp(20)));
    }

    // ------------------------------------------------------------------ top app bar

    @Override
    public View titleBar(Action nav, CharSequence title, CharSequence subtitle, List<Action> actions, View centerContent) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(titleBg);
        bar.setPadding(dp(4), 0, dp(4), 0);
        if (nav != null) {
            View b = iconButton(nav, s.onSurface, 48);
            b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
            bar.addView(b);
        }
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(nav != null ? 4 : 12), 0, dp(4), 0);
        TextView t = text(title, 22, s.onSurface, regular());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(t);
        if (subtitle != null) {
            TextView st = text(subtitle, 13, s.onSurfaceVariant, regular());
            st.setSingleLine(true);
            titles.addView(st);
        }
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (actions != null) {
            for (Action a : actions) {
                View b = iconButton(a, s.onSurfaceVariant, 48);
                b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
                bar.addView(b);
            }
        }
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(titleBarHeightDp())));
        return bar;
    }

    // ------------------------------------------------------------------ navigation rail

    @Override
    public View activityBar(List<NavItem> top, List<NavItem> bottom, int selected, NavListener l) {
        LinearLayout rail = new LinearLayout(ctx);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(s.surface);
        rail.setPadding(0, dp(12), 0, dp(12));
        for (int i = 0; i < top.size(); i++) rail.addView(railItem(top.get(i), i == selected, i, l));
        View spacer = new View(ctx);
        rail.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        for (int i = 0; i < bottom.size(); i++) rail.addView(railItem(bottom.get(i), false, top.size() + i, l));
        return rail;
    }

    /** Rail destination: 56x32 pill (secondaryContainer when active) + 12sp Medium label. */
    private View railItem(NavItem item, boolean selected, final int index, final NavListener l) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout pill = new FrameLayout(ctx);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon(item.icon, selected ? s.onSecondaryContainer : s.onSurfaceVariant, 24));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        pill.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Drawable bg = selected ? rounded(s.secondaryContainer, dp(16)) : new ColorDrawable(0);
        pill.setBackgroundDrawable(pressable(bg, rounded(selected ? blend(s.secondaryContainer, listPressed) : listPressed, dp(16))));
        if (item.badge > 0) pill.addView(badge(item.badge));
        col.addView(pill, new LinearLayout.LayoutParams(dp(56), dp(32)));
        TextView t = text(item.label, 12, selected ? s.onSurface : s.onSurfaceVariant, medium());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(2), dp(4), dp(2), 0);
        col.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.setClickable(true);
        col.setContentDescription(item.label);
        col.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                l.onNav(index);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        lp.bottomMargin = dp(4);
        col.setLayoutParams(lp);
        return col;
    }

    @Override
    public View decorateDrawer(View panel) {
        // Modal navigation drawer: surfaceContainerLow with a 16dp rounded open edge.
        FrameLayout f = new FrameLayout(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setColor(s.surfaceContainerLow);
        float r = dp(16);
        g.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        f.setBackgroundDrawable(g);
        f.setPadding(0, 0, dp(8), 0);
        f.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        elevate(f, 1);
        return f;
    }

    // ------------------------------------------------------------------ tabs (secondary tabs)

    @Override
    public View tab(CharSequence label, Drawable fileIcon, boolean active, boolean dirty, boolean preview,
                    View.OnClickListener click, View.OnLongClickListener longClick, View.OnClickListener close) {
        LinearLayout t = new LinearLayout(ctx);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), 0, dp(4), 0);
        TextView tv = text(label, 14, active ? tabActiveFg : tabInactiveFg, medium());
        tv.setSingleLine(true);
        if (preview) tv.setTypeface(medium(), Typeface.ITALIC);
        t.addView(tv);
        ImageView x = new ImageView(ctx);
        x.setImageDrawable(icon(dirty ? "dirty" : "close", active ? tabActiveFg : tabInactiveFg, dirty ? 10 : 18));
        x.setScaleType(ImageView.ScaleType.CENTER);
        x.setBackgroundDrawable(iconButtonBackground());
        x.setOnClickListener(close);
        t.addView(x, new LinearLayout.LayoutParams(dp(40), dp(40)));
        Drawable normal = new EdgeDrawable(tabsBg, active ? s.primary : 0, dp(2), 0, 0, false);
        t.setBackgroundDrawable(pressable(normal, new EdgeDrawable(blend(tabsBg, listPressed), active ? s.primary : 0, dp(2), 0, 0, false)));
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setOnLongClickListener(longClick);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(tabHeightDp())));
        return t;
    }

    // ------------------------------------------------------------------ lists

    @Override
    public View sideBarHeader(CharSequence title, List<Action> actions) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(4), 0);
        TextView t = text(title, 14, s.onSurfaceVariant, medium());
        t.setSingleLine(true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (actions != null) {
            for (Action a : actions) {
                View b = iconButton(a, s.onSurfaceVariant, 40);
                b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
                row.addView(b);
            }
        }
        row.setMinimumHeight(dp(56));
        return row;
    }

    @Override
    public View sectionHeader(CharSequence title, boolean expanded, View.OnClickListener toggle) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        ImageView chev = new ImageView(ctx);
        chev.setImageDrawable(icon(expanded ? "chevron_down" : "chevron_right", s.onSurfaceVariant, 20));
        row.addView(chev, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = text(title, 14, s.onSurface, medium());
        t.setSingleLine(true);
        t.setPadding(dp(8), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setMinimumHeight(dp(40));
        row.setBackgroundDrawable(listSelector(false));
        row.setClickable(true);
        row.setOnClickListener(toggle);
        return row;
    }

    /** Selected rows get the drawer's pill indicator (secondaryContainer, fully rounded). */
    @Override
    public Drawable listSelector(boolean active) {
        Drawable normal = active ? new InsetDrawable(rounded(s.secondaryContainer, dp(20)), dp(8), dp(1), dp(8), dp(1)) : new ColorDrawable(0);
        Drawable pressed = new InsetDrawable(rounded(active ? blend(s.secondaryContainer, listPressed) : listPressed, dp(20)), dp(8), dp(1), dp(8), dp(1));
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, pressed);
        st.addState(new int[]{android.R.attr.state_focused}, pressed);
        st.addState(new int[]{}, normal);
        return st;
    }

    @Override
    public LinearLayout treeRow(int depth, Boolean expanded, Drawable icon, CharSequence label, CharSequence desc, View trailing) {
        LinearLayout row = super.treeRow(depth, expanded, icon, label, desc, trailing);
        row.setPadding(dp(12 + depth * 12), 0, dp(12), 0);
        return row;
    }

    // ------------------------------------------------------------------ buttons, fields, switches

    @Override
    public TextView button(CharSequence label, boolean primaryButton, View.OnClickListener l) {
        int bg = primaryButton ? s.primary : s.secondaryContainer, fg = primaryButton ? s.onPrimary : s.onSecondaryContainer;
        TextView b = text(label, 14, fg, medium());
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(24), 0, dp(24), 0);
        b.setMinHeight(dp(40));
        b.setBackgroundDrawable(pressable(rounded(bg, dp(20)), rounded(blend(bg, withAlpha(fg, 0x1F)), dp(20))));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    public TextView textButton(CharSequence label, View.OnClickListener l) {
        TextView b = text(label, 14, s.primary, medium());
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinHeight(dp(40));
        b.setBackgroundDrawable(pressable(rounded(0, dp(20)), rounded(withAlpha(s.primary, 0x1F), dp(20))));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** Outlined text field: 4dp radius, 1dp outline, 2dp primary when focused. */
    @Override
    protected Drawable inputBackground() {
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, stroked(0, s.primary, dp(2), dp(4)));
        st.addState(new int[]{}, stroked(0, s.outline, Math.max(1, dp(1)), dp(4)));
        return st;
    }

    @Override
    public void styleInput(android.widget.EditText e) {
        super.styleInput(e);
        e.setTextSize(16);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setMinHeight(dp(48));
    }

    /** Selected filter chip: secondaryContainer, 8dp radius. */
    @Override
    public TextView inputToggle(String iconName, String text, boolean on, View.OnClickListener l) {
        TextView t = super.inputToggle(iconName, text, on, l);
        t.setBackgroundDrawable(on ? rounded(s.secondaryContainer, dp(8)) : pressable(new ColorDrawable(0), rounded(listPressed, dp(8))));
        return t;
    }

    /** Docked search view: surfaceContainerHigh with a 28dp radius. */
    @Override
    public Drawable quickInputBackground() {
        return rounded(s.surfaceContainerHigh, dp(28));
    }

    @Override
    public View commandCenter(CharSequence hint, View.OnClickListener l) {
        return null;
    }

    @Override
    protected int checkWidthDp() {
        return 52;
    }

    @Override
    protected int checkHeightDp() {
        return 32;
    }

    /** The M3 switch: 52x32 track; off = outlined with a 16dp thumb, on = primary with a 24dp thumb. */
    @Override
    public void drawCheck(Canvas c, int w, int h, boolean on, Paint p) {
        float tw = dp(52), th = dp(32), left = (w - tw) / 2f, top = (h - th) / 2f;
        RectF track = new RectF(left, top, left + tw, top + th);
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? s.primary : s.surfaceContainerHighest);
        c.drawRoundRect(track, th / 2, th / 2, p);
        if (!on) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(s.outline);
            RectF in = new RectF(track.left + dp(1), track.top + dp(1), track.right - dp(1), track.bottom - dp(1));
            c.drawRoundRect(in, th / 2, th / 2, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(left + th / 2, top + th / 2, dp(8), p);
        } else {
            p.setColor(s.onPrimary);
            float cx = left + tw - th / 2;
            c.drawCircle(cx, top + th / 2, dp(12), p);
            p.setColor(s.onPrimaryContainer);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            float cy = top + th / 2;
            c.drawLine(cx - dp(5), cy, cx - dp(1.5f), cy + dp(3.5f), p);
            c.drawLine(cx - dp(1.5f), cy + dp(3.5f), cx + dp(5), cy - dp(3.5f), p);
        }
    }

    // ------------------------------------------------------------------ dialogs, menus, snackbar

    @Override
    protected void buildDialog(Dialog d, LinearLayout panel, DialogSpec spec) {
        panel.setBackgroundDrawable(rounded(s.surfaceContainerHigh, dp(28)));
        elevate(panel, 6);
        panel.setPadding(dp(24), dp(24), dp(24), dp(20));
        if (spec.title != null) {
            TextView t = text(spec.title, 24, s.onSurface, regular());
            t.setPadding(0, 0, 0, dp(16));
            panel.addView(t);
        }
        View body = dialogBody(spec);
        panel.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(24), 0, 0);
        if (spec.neutral != null) {
            buttons.addView(textButton(spec.neutral, dismissThen(d, spec.onNeutral)));
            View sp = new View(ctx);
            buttons.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        }
        if (spec.negative != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            buttons.addView(textButton(spec.negative, dismissThen(d, spec.onNegative)), lp);
        }
        if (spec.positive != null) buttons.addView(textButton(spec.positive, dismissThen(d, spec.onPositive)));
        panel.addView(buttons);
    }

    @Override
    protected View dialogBody(DialogSpec spec) {
        View v = super.dialogBody(spec);
        return v;
    }

    @Override
    protected int dialogWidthDp() {
        return 400;
    }

    @Override
    protected Drawable menuBackground() {
        return rounded(s.surfaceContainer, dp(4));
    }

    @Override
    protected int menuRowDp() {
        return 48;
    }

    /** Material 3 segmented buttons: a full pill, 40dp high. */
    @Override
    protected int segmentRadiusDp() {
        return 20;
    }

    @Override
    protected int segmentHeightDp() {
        return 40;
    }

    @Override
    protected int menuPadDp() {
        return 12;
    }

    @Override
    protected Drawable menuSelector() {
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(withAlpha(s.onSurface, 0x1F)));
        st.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(withAlpha(s.onSurface, 0x1F)));
        st.addState(new int[]{}, new ColorDrawable(0));
        return st;
    }

    @Override
    public void toast(View anchor, CharSequence msg) {
        if (anchor == null || anchor.getWindowToken() == null) {
            super.toast(anchor, msg);
            return;
        }
        TextView t = text(msg, 14, s.inverseOnSurface, regular());
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setMaxLines(2);
        t.setBackgroundDrawable(rounded(s.inverseSurface, dp(4)));
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int w = Math.min(screenW - dp(24), dp(560));
        final android.widget.PopupWindow pw = new android.widget.PopupWindow(t, w, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        if (Build.VERSION.SDK_INT >= 21) pw.setElevation(dp(6));
        pw.setAnimationStyle(android.R.style.Animation_Toast);
        pw.showAtLocation(anchor, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(80));
        t.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    pw.dismiss();
                } catch (RuntimeException ignored) {
                }
            }
        }, 2750);
    }

    @Override
    public TextView statusItem(CharSequence str, String iconName, View.OnClickListener l) {
        TextView t = super.statusItem(str, iconName, l);
        t.setTextSize(12);
        t.setTypeface(medium());
        t.setPadding(dp(8), 0, dp(8), 0);
        return t;
    }

    @Override
    public int nativeTheme() {
        if (Build.VERSION.SDK_INT >= 21) {
            return dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar;
        }
        return super.nativeTheme();
    }
}
