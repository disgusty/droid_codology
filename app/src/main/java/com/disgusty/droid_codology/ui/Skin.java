package com.disgusty.droid_codology.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * One interface style. Every screen is built through a Skin, so switching style swaps all visuals
 * (colors, shapes, metrics, icons, type) without touching screen logic. The base class implements
 * the VS Code look; {@link OriginalSkin} sets its colors and the Holo / Material skins override
 * shapes and metrics to follow Google's guidelines for their Android era.
 */
public abstract class Skin {
    public static final int ORIGINAL = 0, HOLO = 1, MATERIAL = 2, MATERIAL3 = 3, MATRIX = 4;

    protected final Context ctx;
    protected final float density;
    public final boolean dark;
    public final int accent;

    // ---- palette (names follow VS Code's workbench color ids)
    public int windowBg, sideBarBg, sideBarFg, sideBarTitleFg, activityBg, activityFg, activityInactiveFg,
            activityIndicator, titleBg, titleFg, tabsBg, tabActiveBg, tabActiveFg, tabInactiveBg, tabInactiveFg,
            tabBorder, tabActiveIndicator, statusBg, statusFg, statusBorder, panelBg, border, listPressed,
            listActiveBg, listActiveFg, inputBg, inputFg, inputBorder, inputPlaceholder, buttonBg, buttonFg,
            secondaryButtonBg, secondaryButtonFg, badgeBg, badgeFg, menuBg, menuFg, menuSelectionBg, menuBorder,
            widgetBg, widgetBorder, focusBorder, textLink, descriptionFg, errorFg, warningFg, infoFg, keysBg,
            keysFg, keysPressed, onAccent, scrim, toggleActiveBg, toggleActiveBorder;

    protected Skin(Context ctx, boolean dark, int accent) {
        this.ctx = ctx;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.dark = dark;
        this.accent = accent;
    }

    public static Skin create(Context ctx, int style, boolean dark, int accent) {
        switch (style) {
            case HOLO:
                return new HoloSkin(ctx, dark, accent);
            case MATERIAL:
                return new Material1Skin(ctx, dark, accent);
            case MATERIAL3:
                return new Material3Skin(ctx, dark, accent);
            case MATRIX:
                return new MatrixSkin(ctx, dark, accent);
            default:
                return new OriginalSkin(ctx, dark, accent);
        }
    }

    /** The style matching the running Android version (Holo 3.0-4.4, Material 5-11, Material You 12+). */
    public static int styleForDevice() {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 31) return MATERIAL3;
        if (sdk >= 21) return MATERIAL;
        return HOLO;
    }

    // ------------------------------------------------------------------ era-specific layout hooks

    /** Vertical activity bar (VS Code, Material 3 navigation rail) or tabs above the side panel (Holo, Material 1). */
    public boolean activityBarVertical() {
        return true;
    }

    /** The VS Code command center in the title bar. */
    public boolean hasCommandCenter() {
        return false;
    }

    /** Wraps the side panel shown as a drawer on phones (rounded edge, shadow...). */
    public View decorateDrawer(View panel) {
        return panel;
    }

    /** Lets a style tint the code area (background, selection, caret) to match its surfaces. */
    public void adjustEditorColors(com.disgusty.droid_codology.editor.EditorColors c) {
        c.handle = accent;
    }

    /** Raises a view (Material elevation on 5.0+; earlier styles are flat). */
    public void elevate(View v, float dpValue) {
        if (Build.VERSION.SDK_INT >= 21) com.disgusty.droid_codology.compat.Api21.elevation(v, dp(dpValue));
    }

    /** A soft shadow strip for pre-Lollipop devices, placed under bars that are elevated on 5.0+. */
    public View shadowStrip(int heightDp, boolean dark) {
        View v = new View(ctx);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{dark ? 0x66000000 : 0x33000000, 0x00000000});
        v.setBackgroundDrawable(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
    }

    /** Background of the quick input box (command palette / Go to File). */
    public Drawable quickInputBackground() {
        return stroked(widgetBg, widgetBorder, Math.max(1, dp(1)), dp(6));
    }

    /** Material navigation drawers cover the app bar; VS Code's side bar and Holo drawers sit below it. */
    public boolean drawerCoversAppBar() {
        return id() == MATERIAL || id() == MATERIAL3;
    }

    /** Height of a fake shadow the workbench overlays under the title bar (pre-5.0 Material). */
    public int topShadowDp() {
        return 0;
    }

    /** Color of icons and text in the title bar. */
    public int titleIconColor() {
        return titleFg;
    }

    public abstract int id();

    public abstract int iconSet();

    /** Default accent of this style (VS Code blue, Holo blue, Material indigo/teal, Material You). */
    public abstract int defaultAccent(boolean dark);

    /** Platform theme for system-drawn parts (text selection handles in dialogs, IME). */
    public int nativeTheme() {
        return dark ? android.R.style.Theme_Holo_NoActionBar : android.R.style.Theme_Holo_Light_NoActionBar;
    }

    public int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    public Context context() {
        return ctx;
    }

    // ------------------------------------------------------------------ type & icons

    public Typeface regular() {
        return Typeface.DEFAULT;
    }

    public Typeface medium() {
        return Typeface.DEFAULT_BOLD;
    }

    /** Base UI text size in sp (VS Code uses 13px; touch needs a bit more). */
    public float uiSp() {
        return 14;
    }

    public TextView text(CharSequence s, float sp, int color, Typeface tf) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(true);
        return t;
    }

    public TextView label(CharSequence s) {
        return text(s, uiSp(), sideBarFg, regular());
    }

    public TextView caption(CharSequence s) {
        return text(s, uiSp() - 2, descriptionFg, regular());
    }

    public Drawable icon(String name, int color, int sizeDp) {
        return Icons.make(iconSet(), name, color, dp(sizeDp));
    }

    public Drawable icon(String name, int color) {
        return icon(name, color, iconSizeDp());
    }

    protected int iconSizeDp() {
        return 20;
    }

    // ------------------------------------------------------------------ app/title bar

    public static final class Action {
        public final String icon, label;
        public final View.OnClickListener click;
        public boolean enabled = true;
        public String id;

        public Action(String icon, String label, View.OnClickListener click) {
            this.icon = icon;
            this.label = label;
            this.click = click;
        }
    }

    /** Height of the top bar in dp. */
    public int titleBarHeightDp() {
        return 40;
    }

    /**
     * Top bar: a navigation button (menu / back), a title (and optional subtitle) and actions.
     * {@code centerContent} (the VS Code command center) may be null.
     */
    public View titleBar(Action nav, CharSequence title, CharSequence subtitle, List<Action> actions, View centerContent) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(titleBg);
        bar.setPadding(dp(2), 0, dp(2), 0);
        if (nav != null) bar.addView(iconButton(nav, titleFg, titleBarHeightDp()));
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, uiSp(), titleFg, regular());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(t);
        if (subtitle != null) {
            TextView st = text(subtitle, uiSp() - 3, descriptionFg, regular());
            st.setSingleLine(true);
            st.setEllipsize(TextUtils.TruncateAt.START);
            titles.addView(st);
        }
        titles.setPadding(dp(6), 0, dp(6), 0);
        if (centerContent != null) {
            bar.addView(titles, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bar.addView(centerContent, new LinearLayout.LayoutParams(0, dp(titleBarHeightDp() - 12), 1));
        } else {
            bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        if (actions != null) for (Action a : actions) bar.addView(iconButton(a, titleFg, titleBarHeightDp()));
        bar.setMinimumHeight(dp(titleBarHeightDp()));
        return wrapTitleBar(bar);
    }

    /** Lets skins add a bottom border / shadow under the title bar. */
    protected View wrapTitleBar(View bar) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(titleBarHeightDp())));
        col.addView(hairline(border));
        return col;
    }

    /** VS Code's command center: a search pill in the title bar that opens the command palette. */
    public View commandCenter(CharSequence hint, View.OnClickListener l) {
        TextView t = text(hint, uiSp() - 1, descriptionFg, regular());
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setCompoundDrawablesWithIntrinsicBounds(icon("search", descriptionFg, 14), null, null, null);
        t.setCompoundDrawablePadding(dp(6));
        t.setPadding(dp(8), 0, dp(8), 0);
        t.setBackgroundDrawable(pressable(stroked(inputBg, inputBorder, Math.max(1, dp(1)), dp(6)), stroked(listPressed, inputBorder, Math.max(1, dp(1)), dp(6))));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    public ImageView iconButton(Action a, int color, int sizeDp) {
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon(a.icon, a.enabled ? color : withAlpha(color, 0x60)));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setBackgroundDrawable(iconButtonBackground());
        iv.setClickable(true);
        iv.setFocusable(true);
        iv.setEnabled(a.enabled);
        if (a.label != null) iv.setContentDescription(a.label);
        if (a.click != null) iv.setOnClickListener(a.click);
        final String label = a.label;
        if (label != null) {
            iv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    toast(v, label);
                    return true;
                }
            });
        }
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(Math.max(36, sizeDp)), dp(sizeDp)));
        return iv;
    }

    protected Drawable iconButtonBackground() {
        return pressable(new ColorDrawable(0), rounded(listPressed, dp(5)));
    }

    // ------------------------------------------------------------------ activity bar / navigation

    public static final class NavItem {
        public final String icon, label;
        public int badge;

        public NavItem(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    public interface NavListener {
        void onNav(int index);
    }

    /** Width of the activity bar in dp. */
    public int activityBarWidthDp() {
        return 48;
    }

    /** VS Code activity bar: a vertical icon strip with a selection indicator on the left. */
    public View activityBar(List<NavItem> top, List<NavItem> bottom, int selected, final NavListener l) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(activityBg);
        for (int i = 0; i < top.size(); i++) bar.addView(activityItem(top.get(i), i == selected, i, l));
        View spacer = new View(ctx);
        bar.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        for (int i = 0; i < bottom.size(); i++) bar.addView(activityItem(bottom.get(i), false, top.size() + i, l));
        return bar;
    }

    /**
     * A vertical bar of icon-over-label items (Holo, Material 1): the selected one gets an
     * {@code indicatorDp} accent edge on the left. Top items scroll when there are many.
     */
    protected View labeledRail(List<NavItem> top, List<NavItem> bottom, int selected, final NavListener l,
                               int indicatorDp, boolean caps, float labelSp, Typeface face) {
        // All of it scrolls when the window is short (keyboard up, landscape); with room to spare
        // the bottom items stay at the bottom (fillViewport + a weighted spacer).
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < top.size(); i++) bar.addView(railTab(top.get(i), i == selected, i, l, indicatorDp, caps, labelSp, face));
        bar.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1));
        for (int i = 0; i < bottom.size(); i++) {
            bar.addView(railTab(bottom.get(i), false, top.size() + i, l, indicatorDp, caps, 0, face));
        }
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.setBackgroundColor(activityBg);
        sv.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private View railTab(NavItem item, boolean selected, final int index, final NavListener l,
                         int indicatorDp, boolean caps, float labelSp, Typeface face) {
        int fg = selected ? activityFg : activityInactiveFg;
        FrameLayout f = new FrameLayout(ctx);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon(item.icon, fg, 24));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        col.addView(iv, new LinearLayout.LayoutParams(dp(28), dp(28)));
        if (labelSp > 0) {
            String label = caps ? item.label.toUpperCase(java.util.Locale.getDefault()) : item.label;
            TextView t = text(label, labelSp, fg, face);
            // "Source Control" wraps instead of being cut.
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setLineSpacing(0, 0.9f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(2), dp(2), dp(2), 0);
            col.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        f.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (selected) {
            View ind = new View(ctx);
            ind.setBackgroundColor(activityIndicator);
            f.addView(ind, new FrameLayout.LayoutParams(dp(indicatorDp), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));
        }
        if (item.badge > 0) f.addView(badge(item.badge));
        f.setBackgroundDrawable(pressable(new ColorDrawable(0), new ColorDrawable(listPressed)));
        f.setClickable(true);
        f.setContentDescription(item.label);
        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                l.onNav(index);
            }
        });
        final String label = item.label;
        f.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                toast(v, label);
                return true;
            }
        });
        f.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(labelSp > 0 ? 66 : 48)));
        return f;
    }

    protected View activityItem(NavItem item, boolean selected, final int index, final NavListener l) {
        FrameLayout f = new FrameLayout(ctx);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon(item.icon, selected ? activityFg : activityInactiveFg, 24));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (selected) {
            View ind = new View(ctx);
            ind.setBackgroundColor(activityIndicator);
            f.addView(ind, new FrameLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));
        }
        if (item.badge > 0) f.addView(badge(item.badge), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM));
        f.setBackgroundDrawable(pressable(new ColorDrawable(0), new ColorDrawable(listPressed)));
        f.setClickable(true);
        f.setContentDescription(item.label);
        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                l.onNav(index);
            }
        });
        final String label = item.label;
        f.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                toast(v, label);
                return true;
            }
        });
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(activityBarWidthDp()), dp(activityBarWidthDp())));
        return f;
    }

    public TextView badge(int n) {
        TextView b = text(n > 99 ? "99+" : String.valueOf(n), 10, badgeFg, medium());
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(16));
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackgroundDrawable(rounded(badgeBg, dp(8)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16), Gravity.RIGHT | Gravity.BOTTOM);
        lp.setMargins(0, 0, dp(6), dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    /** Header of a side bar view ("EXPLORER") with trailing actions. */
    public View sideBarHeader(CharSequence title, List<Action> actions) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(4), 0);
        TextView t = text(title.toString().toUpperCase(java.util.Locale.getDefault()), uiSp() - 3, sideBarTitleFg, regular());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (actions != null) for (Action a : actions) row.addView(iconButton(a, sideBarFg, 34));
        row.setMinimumHeight(dp(38));
        return row;
    }

    /** Collapsible section header inside a side bar view ("OPEN EDITORS", folder name). */
    public View sectionHeader(CharSequence title, boolean expanded, View.OnClickListener toggle) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), 0, dp(8), 0);
        ImageView chev = new ImageView(ctx);
        chev.setImageDrawable(icon(expanded ? "chevron_down" : "chevron_right", sideBarFg, 16));
        chev.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(chev, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = text(title.toString().toUpperCase(java.util.Locale.getDefault()), uiSp() - 3, sideBarFg, medium());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setMinimumHeight(dp(30));
        row.setBackgroundDrawable(pressable(new ColorDrawable(0), new ColorDrawable(listPressed)));
        row.setClickable(true);
        row.setOnClickListener(toggle);
        return row;
    }

    // ------------------------------------------------------------------ lists / trees

    public int rowHeightDp() {
        return 32;
    }

    /**
     * A tree/list row: indent level, an optional chevron (expanded true/false, null = leaf),
     * an icon, a label, a dim description and an optional trailing view.
     */
    public LinearLayout treeRow(int depth, Boolean expanded, Drawable icon, CharSequence label, CharSequence desc, View trailing) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4 + depth * 12), 0, dp(6), 0);
        ImageView chev = new ImageView(ctx);
        if (expanded != null) chev.setImageDrawable(icon(expanded ? "chevron_down" : "chevron_right", sideBarFg, 16));
        chev.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(chev, new LinearLayout.LayoutParams(dp(18), dp(18)));
        if (icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
            lp.rightMargin = dp(6);
            row.addView(iv, lp);
        }
        TextView t = text(label, uiSp(), sideBarFg, regular());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(t, new LinearLayout.LayoutParams(desc == null ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, desc == null ? 1 : 0));
        if (desc != null) {
            TextView d = text(desc, uiSp() - 2, descriptionFg, regular());
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.START);
            d.setPadding(dp(8), 0, 0, 0);
            row.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        if (trailing != null) row.addView(trailing);
        row.setMinimumHeight(dp(rowHeightDp()));
        row.setBackgroundDrawable(listSelector(false));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    public Drawable listSelector(boolean active) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(listPressed));
        s.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(listPressed));
        s.addState(new int[]{}, new ColorDrawable(active ? listActiveBg : 0));
        return s;
    }

    /** Simple one/two-line list item (settings, pickers). */
    public LinearLayout listItem(Drawable icon, CharSequence title, CharSequence subtitle) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setMinimumHeight(dp(subtitle == null ? 48 : 60));
        if (icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
            lp.rightMargin = dp(16);
            row.addView(iv, lp);
        }
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, uiSp() + 1, sideBarFg, regular());
        texts.addView(t);
        if (subtitle != null) texts.addView(text(subtitle, uiSp() - 1, descriptionFg, regular()));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setBackgroundDrawable(listSelector(false));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    public View hairline(int color) {
        View v = new View(ctx);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 1)));
        return v;
    }

    public View hline(int color) {
        View v = new View(ctx);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    public View vline(int color) {
        View v = new View(ctx);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    // ------------------------------------------------------------------ editor tabs

    public int tabHeightDp() {
        return 36;
    }

    public View tab(CharSequence label, Drawable fileIcon, boolean active, boolean dirty, boolean preview,
                    View.OnClickListener click, View.OnLongClickListener longClick, View.OnClickListener close) {
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(10), 0, dp(2), 0);
        if (fileIcon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(fileIcon);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(16), dp(16));
            lp.rightMargin = dp(6);
            t.addView(iv, lp);
        }
        TextView tv = text(label, uiSp() - 1, active ? tabActiveFg : tabInactiveFg, regular());
        tv.setSingleLine(true);
        if (preview) tv.setTypeface(regular(), Typeface.ITALIC);
        t.addView(tv);
        ImageView x = new ImageView(ctx);
        x.setImageDrawable(icon(dirty ? "dirty" : "close", active ? tabActiveFg : tabInactiveFg, dirty ? 12 : 16));
        x.setScaleType(ImageView.ScaleType.CENTER);
        x.setBackgroundDrawable(iconButtonBackground());
        x.setOnClickListener(close);
        x.setContentDescription("Close");
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(dp(28), dp(28));
        xl.leftMargin = dp(4);
        t.addView(x, xl);
        int bg = active ? tabActiveBg : tabInactiveBg;
        // Indicator and separator are painted by the background: child views with MATCH_PARENT
        // would stretch a wrap_content tab to the full strip width.
        t.setBackgroundDrawable(pressable(new EdgeDrawable(bg, active ? tabActiveIndicator : 0, Math.max(1, dp(1.5f)), tabBorder, Math.max(1, dp(1)), true),
                new EdgeDrawable(blend(bg, listPressed), active ? tabActiveIndicator : 0, Math.max(1, dp(1.5f)), tabBorder, Math.max(1, dp(1)), true)));
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setOnLongClickListener(longClick);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(tabHeightDp())));
        return t;
    }

    /** A fill with an optional accent bar on the top (or bottom) edge and a 1px separator on the right. */
    public static final class EdgeDrawable extends Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint();
        private final int fill, bar, barPx, sep, sepPx;
        private final boolean top;

        public EdgeDrawable(int fill, int bar, int barPx, int sep, int sepPx, boolean top) {
            this.fill = fill;
            this.bar = bar;
            this.barPx = barPx;
            this.sep = sep;
            this.sepPx = sepPx;
            this.top = top;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            p.setColor(fill);
            c.drawRect(b, p);
            if ((sep >>> 24) != 0) {
                p.setColor(sep);
                c.drawRect(b.right - sepPx, b.top, b.right, b.bottom, p);
            }
            if ((bar >>> 24) != 0) {
                p.setColor(bar);
                if (top) c.drawRect(b.left, b.top, b.right - ((sep >>> 24) != 0 ? sepPx : 0), b.top + barPx, p);
                else c.drawRect(b.left, b.bottom - barPx, b.right, b.bottom, p);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    /** Breadcrumbs row under the tabs. */
    public View breadcrumbs(List<String> parts, final BreadcrumbListener l) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                ImageView ch = new ImageView(ctx);
                ch.setImageDrawable(icon("chevron_right", descriptionFg, 14));
                row.addView(ch, new LinearLayout.LayoutParams(dp(14), dp(14)));
            }
            TextView t = text(parts.get(i), uiSp() - 2, i == parts.size() - 1 ? sideBarFg : descriptionFg, regular());
            t.setSingleLine(true);
            t.setPadding(dp(3), 0, dp(3), 0);
            final int idx = i;
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    l.onCrumb(idx, v);
                }
            });
            row.addView(t);
        }
        return row;
    }

    public interface BreadcrumbListener {
        void onCrumb(int index, View anchor);
    }

    // ------------------------------------------------------------------ status bar

    public int statusBarHeightDp() {
        return 24;
    }

    public TextView statusItem(CharSequence s, String iconName, View.OnClickListener l) {
        TextView t = text(s, uiSp() - 2, statusFg, regular());
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setPadding(dp(7), 0, dp(7), 0);
        if (iconName != null) {
            t.setCompoundDrawablesWithIntrinsicBounds(icon(iconName, statusFg, 14), null, null, null);
            t.setCompoundDrawablePadding(dp(4));
        }
        if (l != null) {
            t.setBackgroundDrawable(pressable(new ColorDrawable(0), new ColorDrawable(withAlpha(statusFg, 0x30))));
            t.setClickable(true);
            t.setOnClickListener(l);
        }
        return t;
    }

    public void styleStatusBar(View bar) {
        bar.setBackgroundColor(statusBg);
    }

    // ------------------------------------------------------------------ buttons & inputs

    public TextView button(CharSequence label, boolean primary, View.OnClickListener l) {
        TextView b = text(label, uiSp(), primary ? buttonFg : secondaryButtonFg, regular());
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setMinHeight(dp(36));
        b.setMinWidth(dp(64));
        int bg = primary ? buttonBg : secondaryButtonBg;
        b.setBackgroundDrawable(pressable(rounded(bg, dp(4)), rounded(blend(bg, dark ? 0x30FFFFFF : 0x30000000), dp(4))));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    public TextView textButton(CharSequence label, View.OnClickListener l) {
        TextView b = text(label, uiSp(), textLink, medium());
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setMinHeight(dp(36));
        b.setBackgroundDrawable(pressable(new ColorDrawable(0), rounded(listPressed, dp(4))));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    public void styleInput(EditText e) {
        e.setTextColor(inputFg);
        e.setHintTextColor(inputPlaceholder);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, uiSp());
        e.setBackgroundDrawable(inputBackground());
        e.setPadding(dp(8), dp(6), dp(8), dp(6));
        e.setMinHeight(dp(34));
    }

    protected Drawable inputBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, stroked(inputBg, focusBorder, Math.max(1, dp(1)), dp(2)));
        s.addState(new int[]{}, stroked(inputBg, inputBorder, Math.max(1, dp(1)), dp(2)));
        return s;
    }

    public EditText input(CharSequence hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setSingleLine(true);
        styleInput(e);
        return e;
    }

    /** A small toggle inside an input (Match Case / Whole Word / Regex). */
    public TextView inputToggle(String iconName, String text, boolean on, View.OnClickListener l) {
        TextView t = text(text == null ? "" : text, uiSp() - 2, inputFg, medium());
        t.setGravity(Gravity.CENTER);
        if (iconName != null && Icons.has(iconSet(), iconName)) {
            t.setText("");
            t.setCompoundDrawablesWithIntrinsicBounds(icon(iconName, inputFg, 16), null, null, null);
        }
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setMinWidth(dp(30));
        t.setBackgroundDrawable(on ? stroked(toggleActiveBg, toggleActiveBorder, Math.max(1, dp(1)), dp(3)) : pressable(new ColorDrawable(0), rounded(listPressed, dp(3))));
        t.setClickable(true);
        t.setOnClickListener(l);
        t.setSelected(on);
        return t;
    }

    /** A labelled on/off setting row. */
    public View switchRow(CharSequence title, CharSequence desc, boolean on, final ToggleListener l) {
        LinearLayout row = listItem(null, title, desc);
        final CheckMark box = new CheckMark(ctx, this, on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(checkWidthDp()), dp(checkHeightDp()));
        lp.leftMargin = dp(12);
        row.addView(box, lp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                box.setChecked(!box.isChecked());
                l.onToggle(box.isChecked());
            }
        });
        return row;
    }

    public interface ToggleListener {
        void onToggle(boolean on);
    }

    protected int checkSizeDp() {
        return 20;
    }

    /** Size of the on/off control: a check box here, switches in the Material styles. */
    protected int checkWidthDp() {
        return checkSizeDp();
    }

    protected int checkHeightDp() {
        return checkSizeDp();
    }

    /** Draws the check box / switch of this style. */
    public void drawCheck(android.graphics.Canvas c, int w, int h, boolean on, android.graphics.Paint p) {
        float r = dp(3);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(on ? accent : inputBg);
        c.drawRoundRect(new android.graphics.RectF(1, 1, w - 1, h - 1), r, r, p);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, dp(1)));
        p.setColor(on ? accent : inputBorder);
        c.drawRoundRect(new android.graphics.RectF(1, 1, w - 1, h - 1), r, r, p);
        if (on) {
            p.setColor(onAccent);
            p.setStrokeWidth(dp(2));
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            c.drawLine(w * 0.25f, h * 0.52f, w * 0.43f, h * 0.7f, p);
            c.drawLine(w * 0.43f, h * 0.7f, w * 0.76f, h * 0.32f, p);
        }
    }

    public static final class CheckMark extends View {
        private final Skin skin;
        private boolean checked;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        public CheckMark(Context c, Skin s, boolean on) {
            super(c);
            skin = s;
            checked = on;
        }

        public boolean isChecked() {
            return checked;
        }

        public void setChecked(boolean on) {
            checked = on;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            skin.drawCheck(c, getWidth(), getHeight(), checked, paint);
        }
    }

    // ------------------------------------------------------------------ dialogs

    public static final class DialogSpec {
        public CharSequence title, message;
        public String positive, negative, neutral;
        public View content;
        public Runnable onPositive, onNegative, onNeutral;
        public boolean destructive;
        public boolean fullWidth;
        /** Buttons don't close the dialog (it checks input first and calls dismiss itself). */
        public boolean keepOpen;
    }

    public Dialog dialog(DialogSpec spec) {
        final Dialog d = new Dialog(ctx, nativeTheme());
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        buildDialog(d, panel, spec);
        d.setContentView(panel);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            int screen = ctx.getResources().getDisplayMetrics().widthPixels;
            int width = spec.fullWidth ? screen - dp(24) : Math.min(screen - dp(32), dp(dialogWidthDp()));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(dark ? 0.6f : 0.4f);
        }
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    protected int dialogWidthDp() {
        return 420;
    }

    /** VS Code dialog: title, message, buttons right-aligned, square-ish widget box with border. */
    protected void buildDialog(Dialog d, LinearLayout panel, DialogSpec spec) {
        panel.setBackgroundDrawable(stroked(widgetBg, widgetBorder, Math.max(1, dp(1)), dp(6)));
        panel.setPadding(dp(18), dp(16), dp(18), dp(12));
        if (spec.title != null) {
            TextView t = text(spec.title, uiSp() + 2, sideBarFg, medium());
            t.setPadding(0, 0, 0, dp(8));
            panel.addView(t);
        }
        panel.addView(dialogBody(spec), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(10), 0, 0);
        if (spec.neutral != null) {
            TextView n = textButton(spec.neutral, spec.keepOpen ? runOnly(spec.onNeutral) : dismissThen(d, spec.onNeutral));
            buttons.addView(n);
            View sp = new View(ctx);
            buttons.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        }
        if (spec.negative != null) {
            TextView b = button(spec.negative, false, dismissThen(d, spec.onNegative));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            buttons.addView(b, lp);
        }
        if (spec.positive != null) buttons.addView(button(spec.positive, true, spec.keepOpen ? runOnly(spec.onPositive) : dismissThen(d, spec.onPositive)));
        panel.addView(buttons);
    }

    protected View dialogBody(DialogSpec spec) {
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        if (spec.message != null) {
            TextView m = text(spec.message, uiSp(), descriptionFg, regular());
            m.setLineSpacing(0, 1.15f);
            body.addView(m);
        }
        if (spec.content != null) {
            if (spec.content.getParent() != null) ((ViewGroup) spec.content.getParent()).removeView(spec.content);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (spec.message != null) lp.topMargin = dp(10);
            body.addView(spec.content, lp);
        }
        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        sv.addView(body);
        return sv;
    }

    private static View.OnClickListener runOnly(final Runnable r) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (r != null) r.run();
            }
        };
    }

    protected View.OnClickListener dismissThen(final Dialog d, final Runnable r) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                if (r != null) r.run();
            }
        };
    }

    // ------------------------------------------------------------------ menus

    public static final class MenuItem {
        public final String label;
        public String shortcut, icon;
        public boolean enabled = true, checked, separatorBefore;
        public final Runnable action;

        public MenuItem(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }

        public MenuItem key(String k) {
            shortcut = k;
            return this;
        }

        public MenuItem enabled(boolean e) {
            enabled = e;
            return this;
        }

        public MenuItem separator() {
            separatorBefore = true;
            return this;
        }

        public MenuItem checked(boolean c) {
            checked = c;
            return this;
        }

        /** Segments of a switch row (two or more buttons side by side), or null for a plain item. */
        public String[] segments;
        public int selectedSegment;
        public SegmentListener onSegment;

        /** A row of adjacent buttons, the selected one in the accent color. */
        public static MenuItem segmented(String[] labels, int selected, SegmentListener l) {
            MenuItem m = new MenuItem(null, null);
            m.segments = labels;
            m.selectedSegment = selected;
            m.onSegment = l;
            return m;
        }
    }

    public interface SegmentListener {
        void onSegment(int index);
    }

    /** Buttons joined into one control: rounded only at its outer ends, the selected one filled with the accent. */
    protected View segmentedRow(final MenuItem mi, final PopupWindow pw) {
        LinearLayout row = new LinearLayout(ctx);
        row.setPadding(dp(menuPadDp()), dp(4), dp(menuPadDp()), dp(4));
        int n = mi.segments.length;
        float r = dp(segmentRadiusDp());
        int stroke = Math.max(1, dp(1));
        for (int i = 0; i < n; i++) {
            final int index = i;
            boolean on = i == mi.selectedSegment;
            TextView t = text(mi.segments[i], uiSp(), on ? onAccent : menuFg, on ? medium() : regular());
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? accent : 0);
            bg.setStroke(stroke, on ? accent : menuBorder);
            float left = i == 0 ? r : 0, right = i == n - 1 ? r : 0;
            bg.setCornerRadii(new float[]{left, left, right, right, right, right, left, left});
            t.setBackgroundDrawable(bg);
            t.setClickable(true);
            t.setFocusable(true);
            t.setContentDescription(mi.segments[i] + (on ? " ✓" : ""));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pw.dismiss();
                    if (mi.onSegment != null) mi.onSegment.onSegment(index);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(segmentHeightDp()), 1);
            // the halves share one border line
            if (i > 0) lp.leftMargin = -stroke;
            row.addView(t, lp);
        }
        return row;
    }

    protected int segmentRadiusDp() {
        return 4;
    }

    protected int segmentHeightDp() {
        return 32;
    }

    public PopupWindow popup(View anchor, List<MenuItem> items) {
        return popupAt(anchor, items, 0, 0, false);
    }

    /** Context menu in this style; {@code atPoint} places it at (x, y) of the anchor instead of below it. */
    public PopupWindow popupAt(View anchor, List<MenuItem> items, int x, int y, boolean atPoint) {
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(4));
        ScrollView sv = new ScrollView(ctx);
        sv.addView(list);
        sv.setBackgroundDrawable(menuBackground());
        int width = dp(menuWidthDp());
        final PopupWindow pw = new PopupWindow(sv, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(0));
        pw.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT >= 21) pw.setElevation(dp(8));
        for (final MenuItem mi : items) {
            if (mi.separatorBefore && list.getChildCount() > 0) {
                View sep = hairline(menuBorder);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                lp.setMargins(dp(8), dp(4), dp(8), dp(4));
                list.addView(sep, lp);
            }
            if (mi.segments != null) {
                list.addView(segmentedRow(mi, pw), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                continue;
            }
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(menuPadDp()), 0, dp(menuPadDp()), 0);
            int fg = mi.enabled ? menuFg : withAlpha(menuFg, 0x60);
            TextView check = text(mi.checked ? "✓" : "", uiSp(), fg, regular());
            check.setMinWidth(dp(mi.checked || hasChecks(items) ? 20 : 0));
            row.addView(check);
            TextView t = text(mi.label, uiSp(), fg, regular());
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (mi.shortcut != null) {
                TextView k = text(mi.shortcut, uiSp() - 2, withAlpha(fg, 0xA0), regular());
                k.setPadding(dp(12), 0, 0, 0);
                row.addView(k);
            }
            row.setMinimumHeight(dp(menuRowDp()));
            if (mi.enabled) {
                row.setBackgroundDrawable(menuSelector());
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pw.dismiss();
                        if (mi.action != null) mi.action.run();
                    }
                });
            }
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (atPoint) {
            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            int px = Math.min(loc[0] + x, screenW - width - dp(4));
            pw.showAtLocation(anchor, Gravity.LEFT | Gravity.TOP, Math.max(dp(4), px), loc[1] + y);
        } else {
            pw.showAsDropDown(anchor, 0, 0);
        }
        return pw;
    }

    private static boolean hasChecks(List<MenuItem> items) {
        for (MenuItem m : items) if (m.checked) return true;
        return false;
    }

    protected int menuWidthDp() {
        return 250;
    }

    protected int menuRowDp() {
        return 36;
    }

    protected int menuPadDp() {
        return 10;
    }

    protected Drawable menuBackground() {
        return stroked(menuBg, menuBorder, Math.max(1, dp(1)), dp(5));
    }

    protected Drawable menuSelector() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(menuSelectionBg, dp(3)));
        s.addState(new int[]{android.R.attr.state_focused}, rounded(menuSelectionBg, dp(3)));
        s.addState(new int[]{}, new ColorDrawable(0));
        return s;
    }

    /** A horizontal floating bar of text actions (Cut / Copy / Paste) for touch selection. */
    public View actionStrip(List<MenuItem> items, final Runnable dismiss) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundDrawable(menuBackground());
        row.setPadding(dp(2), dp(2), dp(2), dp(2));
        for (final MenuItem mi : items) {
            TextView t = text(mi.label, uiSp(), menuFg, regular());
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(12), 0, dp(12), 0);
            t.setMinHeight(dp(40));
            t.setBackgroundDrawable(menuSelector());
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (dismiss != null) dismiss.run();
                    mi.action.run();
                }
            });
            row.addView(t);
        }
        return row;
    }

    // ------------------------------------------------------------------ notifications

    /** Short message (VS Code notification toast style). */
    public void toast(View anchor, CharSequence msg) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ window

    public void decorate(Activity a) {
        Window w = a.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(windowBg));
        if (Build.VERSION.SDK_INT >= 21) com.disgusty.droid_codology.compat.Api21.barColors(w, statusBarColor(), navigationBarColor(), !isDark(statusBarColor()), !isDark(navigationBarColor()));
        if (Build.VERSION.SDK_INT >= 35) {
            com.disgusty.droid_codology.compat.Api30.edgeToEdge(w.getDecorView().findViewById(android.R.id.content), statusBarColor(), navigationBarColor());
        }
    }

    public int statusBarColor() {
        return titleBg;
    }

    public int navigationBarColor() {
        return statusBg;
    }

    // ------------------------------------------------------------------ helpers

    public static boolean isDark(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 < 140;
    }

    public static int withAlpha(int c, int alpha) {
        return (c & 0x00FFFFFF) | (alpha << 24);
    }

    /** Alpha-composites {@code over} onto {@code base}. */
    public static int blend(int base, int over) {
        float a = (over >>> 24) / 255f;
        int r = (int) (((base >> 16) & 0xFF) * (1 - a) + ((over >> 16) & 0xFF) * a);
        int g = (int) (((base >> 8) & 0xFF) * (1 - a) + ((over >> 8) & 0xFF) * a);
        int b = (int) ((base & 0xFF) * (1 - a) + (over & 0xFF) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Readable text color on a background. */
    public static int onColor(int bg) {
        return isDark(bg) ? Color.WHITE : 0xFF1F1F1F;
    }

    public GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    public GradientDrawable stroked(int fill, int stroke, int strokePx, float radius) {
        GradientDrawable g = rounded(fill, radius);
        g.setStroke(strokePx, stroke);
        return g;
    }

    public Drawable pressable(Drawable normal, Drawable pressed) {
        if (usesRipple() && Build.VERSION.SDK_INT >= 21) {
            Drawable r = com.disgusty.droid_codology.compat.Api21.ripple(rippleColor(), normal, pressed);
            if (r != null) return r;
        }
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{android.R.attr.state_focused}, pressed);
        s.addState(new int[]{}, normal != null ? normal : new ColorDrawable(0));
        return s;
    }

    protected boolean usesRipple() {
        return false;
    }

    protected int rippleColor() {
        return listPressed;
    }

    public ColorStateList colors(int normal) {
        return ColorStateList.valueOf(normal);
    }
}
