package com.disgusty.droid_codology.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * "Matrix": the VS Code layout dressed as the film's screens and the menus of its games: black,
 * phosphor green (the accent, #00FF41 by default) in a monospace face with a soft glow, thin frames
 * with corner brackets, and the selected item drawn inverted, black on green.
 */
public final class MatrixSkin extends Skin {
    public static final int PHOSPHOR = 0xFF00FF41;

    private final int glow, bright, mid, dim, faint, deep;
    private final Typeface mono = Typeface.MONOSPACE, monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    public MatrixSkin(Context ctx, boolean dark, int accent) {
        super(ctx, true, accent == 0 ? PHOSPHOR : accent);
        glow = this.accent | 0xFF000000;
        bright = mix(glow, 0xFFFFFFFF, 0.25f);
        mid = mix(glow, 0xFF000000, 0.25f);
        dim = mix(glow, 0xFF000000, 0.5f);
        faint = mix(glow, 0xFF000000, 0.75f);
        deep = mix(glow, 0xFF000000, 0.9f);
        int black = 0xFF000000;
        onAccent = black;
        windowBg = black;
        sideBarBg = 0xFF000300;
        sideBarFg = mid;
        sideBarTitleFg = glow;
        activityBg = black;
        activityFg = glow;
        activityInactiveFg = dim;
        activityIndicator = glow;
        titleBg = black;
        titleFg = glow;
        tabsBg = black;
        tabActiveBg = deep;
        tabActiveFg = bright;
        tabInactiveBg = black;
        tabInactiveFg = dim;
        tabBorder = faint;
        tabActiveIndicator = glow;
        statusBg = black;
        statusFg = mid;
        statusBorder = faint;
        panelBg = black;
        border = faint;
        listPressed = deep;
        listActiveBg = mix(glow, black, 0.8f);
        listActiveFg = bright;
        inputBg = black;
        inputFg = bright;
        inputBorder = dim;
        inputPlaceholder = faint;
        buttonBg = glow;
        buttonFg = black;
        secondaryButtonBg = black;
        secondaryButtonFg = glow;
        badgeBg = glow;
        badgeFg = black;
        menuBg = black;
        menuFg = mid;
        menuSelectionBg = mix(glow, black, 0.7f);
        menuBorder = dim;
        widgetBg = black;
        widgetBorder = dim;
        focusBorder = glow;
        textLink = bright;
        descriptionFg = dim;
        errorFg = 0xFFFF5050;
        warningFg = 0xFFE8E040;
        infoFg = bright;
        keysBg = black;
        keysFg = glow;
        keysPressed = deep;
        scrim = 0xCC000000;
        toggleActiveBg = mix(glow, black, 0.75f);
        toggleActiveBorder = glow;
    }

    @Override
    public int id() {
        return MATRIX;
    }

    @Override
    public int iconSet() {
        return Icons.SET_CODICON;
    }

    @Override
    public int defaultAccent(boolean dark) {
        return PHOSPHOR;
    }

    @Override
    public boolean hasCommandCenter() {
        return true;
    }

    /** Square, like the games' menu buttons. */
    @Override
    protected int segmentRadiusDp() {
        return 0;
    }

    @Override
    public int nativeTheme() {
        return android.R.style.Theme_Holo_NoActionBar;
    }

    // ------------------------------------------------------------------ type: monospace with a glow

    @Override
    public Typeface regular() {
        return mono;
    }

    @Override
    public Typeface medium() {
        return monoBold;
    }

    @Override
    public float uiSp() {
        return 13;
    }

    @Override
    public TextView text(CharSequence s, float sp, int color, Typeface tf) {
        TextView t = super.text(s, sp, color, tf);
        // Phosphor bleeding into the dark around the letters.
        if ((color >>> 24) > 0x80 && color != 0xFF000000) t.setShadowLayer(dp(3), 0, 0, withAlpha(color, 0x99));
        return t;
    }

    // ------------------------------------------------------------------ chrome

    @Override
    public int activityBarWidthDp() {
        return 64;
    }

    @Override
    public View activityBar(List<NavItem> top, List<NavItem> bottom, int selected, NavListener l) {
        return labeledRail(top, bottom, selected, l, 3, true, 9, monoBold);
    }

    /** Frame with corner brackets around {@code fill}. */
    public MatrixFrame frame(int fill, int line, boolean brackets) {
        return new MatrixFrame(fill, line, Math.max(1, dp(1)), Math.max(2, dp(2)), dp(9), brackets);
    }

    @Override
    public TextView button(CharSequence label, boolean primary, View.OnClickListener l) {
        TextView b = super.button(label.toString().toUpperCase(java.util.Locale.getDefault()), primary, l);
        b.setTypeface(monoBold);
        if (primary) {
            // inverted, like the selected entry of the games' menus
            b.setShadowLayer(0, 0, 0, 0);
            b.setBackgroundDrawable(pressable(frame(glow, glow, false), frame(bright, bright, false)));
        } else {
            b.setBackgroundDrawable(pressable(frame(0, dim, true), frame(deep, glow, true)));
        }
        return b;
    }

    @Override
    protected void buildDialog(Dialog d, LinearLayout panel, DialogSpec spec) {
        if (spec.title != null) spec.title = "// " + spec.title.toString().toUpperCase(java.util.Locale.getDefault());
        super.buildDialog(d, panel, spec);
        panel.setBackgroundDrawable(frame(0xFF000000, dim, true));
    }

    @Override
    public void drawCheck(android.graphics.Canvas c, int w, int h, boolean on, android.graphics.Paint p) {
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, dp(1)));
        p.setColor(on ? glow : dim);
        c.drawRect(1, 1, w - 1, h - 1, p);
        if (on) {
            p.setStyle(android.graphics.Paint.Style.FILL);
            int in = Math.max(3, dp(4));
            c.drawRect(in, in, w - in, h - in, p);
        }
    }

    @Override
    public void elevate(View v, float dpValue) {
        // flat screens: no shadows
    }

    @Override
    public int statusBarColor() {
        return 0xFF000000;
    }

    @Override
    public int navigationBarColor() {
        return 0xFF000000;
    }

    /** A frame for panels that want the look (lock screen, welcome). */
    public android.graphics.drawable.Drawable panelFrame() {
        return frame(0xFF000000, dim, true);
    }

}
