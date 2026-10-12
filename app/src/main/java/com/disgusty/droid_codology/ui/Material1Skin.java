package com.disgusty.droid_codology.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import android.widget.PopupWindow;
import android.widget.TextView;

import com.disgusty.droid_codology.editor.EditorColors;

import java.util.List;
import java.util.Locale;

/**
 * Material Design (2014, Android 5.0-11): the colored 56dp toolbar with 4dp elevation, Roboto
 * Medium, ALL CAPS buttons and tabs with a 2dp indicator, underlined text fields, the Lollipop
 * switch, 2dp-radius dialogs and menus, ripples and the Snackbar. The dark theme uses the spec's
 * surfaces (#212121 app bar, #303030 background, #424242 cards and dialogs).
 */
public final class Material1Skin extends Skin {
    /** colorPrimary / colorPrimaryDark / an accent readable on the current background. */
    private final int primary, primaryDark, control;

    public Material1Skin(Context ctx, boolean dark, int accent) {
        super(ctx, dark, accent == 0 ? 0xFF3F51B5 : accent);
        primary = this.accent;
        primaryDark = mix(primary, 0xFF000000, 0.22f);
        // On dark surfaces a lighter tint (≈ the 200 shade) keeps controls visible.
        control = dark ? mix(primary, 0xFFFFFFFF, 0.35f) : primary;
        onAccent = onColor(primary);
        if (dark) {
            windowBg = 0xFF303030;
            titleBg = 0xFF212121;
            titleFg = 0xFFFFFFFF;
            sideBarBg = 0xFF303030;
            sideBarFg = 0xFFFFFFFF;
            descriptionFg = 0xB3FFFFFF;
            activityBg = 0xFF212121;
            activityFg = 0xFFFFFFFF;
            activityInactiveFg = 0xB3FFFFFF;
            tabsBg = 0xFF212121;
            statusBg = 0xFF212121;
            statusFg = 0xB3FFFFFF;
            border = 0x1FFFFFFF;
            listPressed = 0x33FFFFFF;
            listActiveBg = 0x1FFFFFFF;
            listActiveFg = control;
            inputFg = 0xFFFFFFFF;
            inputBorder = 0xB3FFFFFF;
            inputPlaceholder = 0x80FFFFFF;
            menuBg = 0xFF424242;
            menuFg = 0xFFFFFFFF;
            widgetBg = 0xFF424242;
            secondaryButtonFg = 0xFFFFFFFF;
            keysBg = 0xFF212121;
            keysFg = 0xFFFFFFFF;
        } else {
            windowBg = 0xFFFAFAFA;
            titleBg = primary;
            titleFg = onColor(primary);
            sideBarBg = 0xFFFFFFFF;
            sideBarFg = 0xDE000000;
            descriptionFg = 0x8A000000;
            activityBg = primary;
            activityFg = onColor(primary);
            activityInactiveFg = withAlpha(onColor(primary), 0xB3);
            tabsBg = primary;
            statusBg = 0xFFF5F5F5;
            statusFg = 0x8A000000;
            border = 0x1F000000;
            listPressed = 0x1F000000;
            listActiveBg = 0x0F000000;
            listActiveFg = primary;
            inputFg = 0xDE000000;
            inputBorder = 0x6B000000;
            inputPlaceholder = 0x61000000;
            menuBg = 0xFFFFFFFF;
            menuFg = 0xDE000000;
            widgetBg = 0xFFFFFFFF;
            secondaryButtonFg = 0xDE000000;
            keysBg = 0xFFEEEEEE;
            keysFg = 0xDE000000;
        }
        sideBarTitleFg = control;
        activityIndicator = dark ? control : onColor(primary);
        tabActiveBg = tabsBg;
        tabInactiveBg = tabsBg;
        tabActiveFg = dark ? 0xFFFFFFFF : onColor(primary);
        tabInactiveFg = withAlpha(tabActiveFg, 0xB3);
        tabBorder = 0;
        tabActiveIndicator = dark ? control : onColor(primary);
        statusBorder = border;
        panelBg = sideBarBg;
        inputBg = 0;
        buttonBg = control;
        buttonFg = onColor(control);
        secondaryButtonBg = 0;
        badgeBg = control;
        badgeFg = onColor(control);
        menuSelectionBg = listPressed;
        menuBorder = 0;
        widgetBorder = 0;
        focusBorder = control;
        textLink = control;
        errorFg = dark ? 0xFFEF9A9A : 0xFFD32F2F;
        warningFg = dark ? 0xFFFFE082 : 0xFFF57C00;
        infoFg = control;
        keysPressed = listPressed;
        scrim = 0x8A000000;
        toggleActiveBg = withAlpha(control, 0x33);
        toggleActiveBorder = control;
    }

    @Override
    public int id() {
        return MATERIAL;
    }

    @Override
    public int iconSet() {
        return Icons.SET_MATERIAL;
    }

    @Override
    public int defaultAccent(boolean dark) {
        return 0xFF3F51B5;
    }

    @Override
    public boolean activityBarVertical() {
        return true;
    }

    @Override
    protected boolean usesRipple() {
        return true;
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
        return 56;
    }

    @Override
    public int tabHeightDp() {
        return 48;
    }

    @Override
    public int rowHeightDp() {
        return 44;
    }

    @Override
    public int statusBarHeightDp() {
        return 28;
    }

    @Override
    protected int iconSizeDp() {
        return 24;
    }

    @Override
    public int statusBarColor() {
        return dark ? 0xFF000000 : primaryDark;
    }

    @Override
    public int navigationBarColor() {
        return 0xFF000000;
    }

    @Override
    public void adjustEditorColors(EditorColors c) {
        c.handle = control;
        c.cursor = control;
        int bg = dark ? 0xFF303030 : 0xFFFFFFFF;
        c.background = bg;
        c.gutterBg = bg;
        c.selection = blend(bg, withAlpha(control, dark ? 0x59 : 0x40));
        c.inactiveSelection = blend(bg, withAlpha(control, 0x26));
        c.lineHighlightBorder = 0;
        c.lineHighlight = dark ? 0xFF383838 : 0xFFF5F5F5;
        c.lineNumber = dark ? 0x80FFFFFF : 0x61000000;
        c.lineNumberActive = dark ? 0xFFFFFFFF : 0xDE000000;
    }

    @Override
    protected Drawable iconButtonBackground() {
        if (Build.VERSION.SDK_INT >= 21) {
            // Borderless ripple, as on toolbar action buttons.
            Drawable r = com.disgusty.droid_codology.compat.Api21.ripple(listPressed, null, null);
            if (r != null) return r;
        }
        return pressable(new ColorDrawable(0), new ColorDrawable(listPressed));
    }

    // ------------------------------------------------------------------ toolbar

    @Override
    public View titleBar(Action nav, CharSequence title, CharSequence subtitle, List<Action> actions, View centerContent) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(titleBg);
        bar.setPadding(dp(4), 0, dp(4), 0);
        if (nav != null) {
            View b = iconButton(nav, titleFg, 48);
            b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
            bar.addView(b);
        }
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(nav != null ? 20 : 12), 0, dp(4), 0);
        TextView t = text(title, 20, titleFg, medium());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(t);
        if (subtitle != null) {
            TextView st = text(subtitle, 14, withAlpha(titleFg, 0xB3), regular());
            st.setSingleLine(true);
            titles.addView(st);
        }
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (actions != null) {
            for (Action a : actions) {
                View b = iconButton(a, titleFg, 48);
                b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
                bar.addView(b);
            }
        }
        return wrapElevated(bar, titleBarHeightDp(), 4);
    }

    /** A bar with 4dp elevation: a real shadow on 5.0+; before that the workbench overlays {@link #topShadowDp()}. */
    private View wrapElevated(View bar, int heightDp, int elevationDp) {
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        elevate(bar, elevationDp);
        return bar;
    }

    @Override
    public int topShadowDp() {
        return Build.VERSION.SDK_INT >= 21 ? 0 : 4;
    }

    // ------------------------------------------------------------------ tabs

    private Drawable tabBg(boolean selected, int bg) {
        Drawable normal = new EdgeDrawable(bg, selected ? tabActiveIndicator : 0, dp(2), 0, 0, false);
        if (Build.VERSION.SDK_INT >= 21) {
            Drawable r = com.disgusty.droid_codology.compat.Api21.ripple(dark ? 0x33FFFFFF : withAlpha(onColor(primary), 0x33), normal, null);
            if (r != null) return r;
        }
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new EdgeDrawable(blend(bg, 0x33FFFFFF), selected ? tabActiveIndicator : 0, dp(2), 0, 0, false));
        s.addState(new int[]{}, normal);
        return s;
    }

    /** Side panel sections as a vertical strip in the app bar color: icon over a Medium label, 2dp indicator. */
    @Override
    public View activityBar(List<NavItem> top, List<NavItem> bottom, int selected, final NavListener l) {
        return labeledRail(top, bottom, selected, l, 2, false, 11, medium());
    }

    @Override
    public int activityBarWidthDp() {
        return 72;
    }

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
        t.addView(x, new LinearLayout.LayoutParams(dp(36), dp(36)));
        t.setBackgroundDrawable(tabBg(active, tabsBg));
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setOnLongClickListener(longClick);
        t.setMinimumWidth(dp(90));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(tabHeightDp())));
        return t;
    }

    // ------------------------------------------------------------------ lists

    /** List subheader: 14sp Medium in the accent color. */
    @Override
    public View sideBarHeader(CharSequence title, List<Action> actions) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(4), 0);
        TextView t = text(title, 14, control, medium());
        t.setSingleLine(true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (actions != null) {
            for (Action a : actions) {
                View b = iconButton(a, descriptionFg, 40);
                b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
                row.addView(b);
            }
        }
        row.setMinimumHeight(dp(48));
        return row;
    }

    @Override
    public View sectionHeader(CharSequence title, boolean expanded, View.OnClickListener toggle) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        ImageView chev = new ImageView(ctx);
        chev.setImageDrawable(icon(expanded ? "chevron_down" : "chevron_right", descriptionFg, 20));
        row.addView(chev, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = text(title, 14, sideBarFg, medium());
        t.setSingleLine(true);
        t.setPadding(dp(8), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setMinimumHeight(dp(40));
        row.setBackgroundDrawable(listSelector(false));
        row.setClickable(true);
        row.setOnClickListener(toggle);
        return row;
    }

    @Override
    public Drawable listSelector(boolean active) {
        Drawable normal = new ColorDrawable(active ? listActiveBg : 0);
        if (Build.VERSION.SDK_INT >= 21) {
            Drawable r = com.disgusty.droid_codology.compat.Api21.ripple(listPressed, normal, new ColorDrawable(0xFFFFFFFF));
            if (r != null) return r;
        }
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(listPressed));
        s.addState(new int[]{}, normal);
        return s;
    }

    // ------------------------------------------------------------------ buttons, fields, switches

    @Override
    public TextView button(CharSequence label, boolean primaryButton, View.OnClickListener l) {
        if (!primaryButton) return textButton(label, l);
        TextView b = text(label.toString().toUpperCase(Locale.getDefault()), 14, buttonFg, medium());
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(36));
        b.setMinWidth(dp(88));
        b.setBackgroundDrawable(pressable(rounded(buttonBg, dp(2)), rounded(blend(buttonBg, 0x33000000), dp(2))));
        elevate(b, 2);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    public TextView textButton(CharSequence label, View.OnClickListener l) {
        TextView b = text(label.toString().toUpperCase(Locale.getDefault()), 14, control, medium());
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setMinHeight(dp(36));
        b.setMinWidth(dp(64));
        b.setBackgroundDrawable(pressable(rounded(0, dp(2)), rounded(listPressed, dp(2))));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected Drawable inputBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, new EdgeDrawable(0, control, dp(2), 0, 0, false));
        s.addState(new int[]{}, new EdgeDrawable(0, inputBorder, Math.max(1, dp(1)), 0, 0, false));
        return s;
    }

    @Override
    public void styleInput(android.widget.EditText e) {
        super.styleInput(e);
        e.setTextSize(16);
        e.setPadding(dp(2), dp(8), dp(2), dp(10));
        e.setMinHeight(dp(44));
    }

    @Override
    public TextView inputToggle(String iconName, String text, boolean on, View.OnClickListener l) {
        TextView t = super.inputToggle(iconName, text, on, l);
        if (on) t.setBackgroundDrawable(rounded(withAlpha(control, 0x33), dp(2)));
        return t;
    }

    @Override
    protected int checkWidthDp() {
        return 40;
    }

    @Override
    protected int checkHeightDp() {
        return 24;
    }

    /** The Lollipop switch: a 34x14dp track and a 20dp thumb with a soft shadow. */
    @Override
    public void drawCheck(Canvas c, int w, int h, boolean on, Paint p) {
        p.setStyle(Paint.Style.FILL);
        float trackW = dp(34), trackH = dp(14), cy = h / 2f, left = (w - trackW) / 2f;
        p.setColor(on ? withAlpha(control, 0x80) : (dark ? 0x4DFFFFFF : 0x61000000));
        c.drawRoundRect(new RectF(left, cy - trackH / 2, left + trackW, cy + trackH / 2), trackH / 2, trackH / 2, p);
        float r = dp(10), cx = on ? left + trackW - r + dp(3) : left + r - dp(3);
        p.setColor(0x33000000);
        c.drawCircle(cx, cy + dp(1), r + dp(0.5f), p);
        p.setColor(on ? control : (dark ? 0xFFBDBDBD : 0xFFF1F1F1));
        c.drawCircle(cx, cy, r, p);
    }

    // ------------------------------------------------------------------ dialogs, menus, snackbar

    @Override
    protected void buildDialog(Dialog d, LinearLayout panel, DialogSpec spec) {
        panel.setBackgroundDrawable(rounded(widgetBg, dp(2)));
        elevate(panel, 24);
        panel.setPadding(dp(24), dp(24), dp(8), dp(8));
        if (spec.title != null) {
            TextView t = text(spec.title, 20, sideBarFg, medium());
            t.setPadding(0, 0, dp(16), dp(16));
            panel.addView(t);
        }
        View body = dialogBody(spec);
        body.setPadding(0, 0, dp(16), dp(16));
        panel.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (spec.neutral != null) {
            buttons.addView(textButton(spec.neutral, dismissThen(d, spec.onNeutral)));
            View sp = new View(ctx);
            buttons.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        }
        if (spec.negative != null) {
            TextView b = textButton(spec.negative, dismissThen(d, spec.onNegative));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.rightMargin = dp(8);
            buttons.addView(b, lp);
        }
        if (spec.positive != null) buttons.addView(textButton(spec.positive, dismissThen(d, spec.onPositive)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        panel.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    }

    @Override
    public Drawable quickInputBackground() {
        return rounded(widgetBg, dp(2));
    }

    @Override
    protected int dialogWidthDp() {
        return 400;
    }

    @Override
    protected Drawable menuBackground() {
        return rounded(menuBg, dp(2));
    }

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
        return 36;
    }

    @Override
    protected int menuPadDp() {
        return 16;
    }

    @Override
    protected Drawable menuSelector() {
        return listSelector(false);
    }

    @Override
    public void toast(View anchor, CharSequence msg) {
        snackbar(anchor, msg, 0xFF323232, 0xFFFFFFFF, 0);
    }

    /** Snackbar: a single line at the bottom, shown for 2.75 s. */
    void snackbar(View anchor, CharSequence msg, int bg, int fg, int radiusDp) {
        if (anchor == null || anchor.getWindowToken() == null) {
            super.toast(anchor, msg);
            return;
        }
        TextView t = text(msg, 14, fg, regular());
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(24), dp(14), dp(24), dp(14));
        t.setMaxLines(2);
        t.setBackgroundDrawable(rounded(bg, dp(radiusDp)));
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int w = screenW > dp(600) ? Math.min(dp(568), screenW) : screenW;
        final PopupWindow pw = new PopupWindow(t, w, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        if (Build.VERSION.SDK_INT >= 21) pw.setElevation(dp(6));
        pw.setAnimationStyle(android.R.style.Animation_InputMethod);
        pw.showAtLocation(anchor, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0);
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
    public View decorateDrawer(View panel) {
        FrameLayout f = new FrameLayout(ctx);
        f.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.setBackgroundColor(sideBarBg);
        elevate(f, 16);
        return f;
    }

    @Override
    public TextView statusItem(CharSequence s, String iconName, View.OnClickListener l) {
        TextView t = super.statusItem(s, iconName, l);
        t.setTextSize(12);
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
