package com.disgusty.droid_codology.ui;

import android.content.Context;

/**
 * The look of VS Code on the desktop: the "Dark Modern" and "Light Modern" workbench themes
 * (colors from microsoft/vscode, MIT), Codicons, flat square-ish widgets and the activity bar.
 * The accent replaces VS Code's blue (#0078D4 / #005FB8).
 */
public final class OriginalSkin extends Skin {
    public OriginalSkin(Context ctx, boolean dark, int accent) {
        super(ctx, dark, accent == 0 ? (dark ? 0xFF0078D4 : 0xFF005FB8) : accent);
        int a = this.accent;
        onAccent = onColor(a);
        if (dark) {
            windowBg = 0xFF1F1F1F;
            sideBarBg = 0xFF181818;
            sideBarFg = 0xFFCCCCCC;
            sideBarTitleFg = 0xFFCCCCCC;
            activityBg = 0xFF181818;
            activityFg = 0xFFD7D7D7;
            activityInactiveFg = 0xFF868686;
            activityIndicator = a;
            titleBg = 0xFF181818;
            titleFg = 0xFFCCCCCC;
            tabsBg = 0xFF181818;
            tabActiveBg = 0xFF1F1F1F;
            tabActiveFg = 0xFFFFFFFF;
            tabInactiveBg = 0xFF181818;
            tabInactiveFg = 0xFF9D9D9D;
            tabBorder = 0xFF2B2B2B;
            tabActiveIndicator = a;
            statusBg = 0xFF181818;
            statusFg = 0xFFCCCCCC;
            statusBorder = 0xFF2B2B2B;
            panelBg = 0xFF181818;
            border = 0xFF2B2B2B;
            listPressed = 0xFF2A2D2E;
            listActiveBg = 0xFF37373D;
            listActiveFg = 0xFFFFFFFF;
            inputBg = 0xFF313131;
            inputFg = 0xFFCCCCCC;
            inputBorder = 0xFF3C3C3C;
            inputPlaceholder = 0xFF989898;
            buttonBg = a;
            buttonFg = onAccent;
            secondaryButtonBg = 0xFF313131;
            secondaryButtonFg = 0xFFCCCCCC;
            badgeBg = a;
            badgeFg = onAccent;
            menuBg = 0xFF1F1F1F;
            menuFg = 0xFFCCCCCC;
            menuSelectionBg = blend(0xFF1F1F1F, withAlpha(a, 0xCC));
            menuBorder = 0xFF454545;
            widgetBg = 0xFF202020;
            widgetBorder = 0xFF313131;
            focusBorder = a;
            textLink = lighten(a);
            descriptionFg = 0xFF9D9D9D;
            errorFg = 0xFFF85149;
            warningFg = 0xFFCCA700;
            infoFg = 0xFF3794FF;
            keysBg = 0xFF181818;
            keysFg = 0xFFCCCCCC;
            keysPressed = 0xFF37373D;
            scrim = 0x99000000;
            toggleActiveBg = withAlpha(a, 0x40);
            toggleActiveBorder = a;
        } else {
            windowBg = 0xFFFFFFFF;
            sideBarBg = 0xFFF8F8F8;
            sideBarFg = 0xFF3B3B3B;
            sideBarTitleFg = 0xFF3B3B3B;
            activityBg = 0xFFF8F8F8;
            activityFg = 0xFF1F1F1F;
            activityInactiveFg = 0xFF616161;
            activityIndicator = a;
            titleBg = 0xFFF8F8F8;
            titleFg = 0xFF1E1E1E;
            tabsBg = 0xFFF8F8F8;
            tabActiveBg = 0xFFFFFFFF;
            tabActiveFg = 0xFF3B3B3B;
            tabInactiveBg = 0xFFF8F8F8;
            tabInactiveFg = 0xFF868686;
            tabBorder = 0xFFE5E5E5;
            tabActiveIndicator = a;
            statusBg = 0xFFF8F8F8;
            statusFg = 0xFF3B3B3B;
            statusBorder = 0xFFE5E5E5;
            panelBg = 0xFFF8F8F8;
            border = 0xFFE5E5E5;
            listPressed = 0xFFF2F2F2;
            listActiveBg = 0xFFE4E6F1;
            listActiveFg = 0xFF000000;
            inputBg = 0xFFFFFFFF;
            inputFg = 0xFF3B3B3B;
            inputBorder = 0xFFCECECE;
            inputPlaceholder = 0xFF767676;
            buttonBg = a;
            buttonFg = onAccent;
            secondaryButtonBg = 0xFFE5E5E5;
            secondaryButtonFg = 0xFF3B3B3B;
            badgeBg = a;
            badgeFg = onAccent;
            menuBg = 0xFFFFFFFF;
            menuFg = 0xFF3B3B3B;
            menuSelectionBg = blend(0xFFFFFFFF, withAlpha(a, 0x33));
            menuBorder = 0xFFCECECE;
            widgetBg = 0xFFF8F8F8;
            widgetBorder = 0xFFE5E5E5;
            focusBorder = a;
            textLink = a;
            descriptionFg = 0xFF616161;
            errorFg = 0xFFE51400;
            warningFg = 0xFFBF8803;
            infoFg = 0xFF1A85FF;
            keysBg = 0xFFF8F8F8;
            keysFg = 0xFF3B3B3B;
            keysPressed = 0xFFE4E6F1;
            scrim = 0x66000000;
            toggleActiveBg = withAlpha(a, 0x33);
            toggleActiveBorder = a;
        }
    }

    /** Recolors the workbench from a VS Code theme's "colors" (the ids VS Code uses). */
    public void applyWorkbenchColors(java.util.Map<String, Integer> c) {
        windowBg = pick(c, windowBg, "editor.background");
        sideBarBg = pick(c, sideBarBg, "sideBar.background", "editor.background");
        sideBarFg = pick(c, sideBarFg, "sideBar.foreground", "foreground", "editor.foreground");
        sideBarTitleFg = pick(c, sideBarTitleFg, "sideBarTitle.foreground", "sideBar.foreground", "foreground");
        activityBg = pick(c, activityBg, "activityBar.background", "sideBar.background");
        activityFg = pick(c, activityFg, "activityBar.foreground", "foreground");
        activityInactiveFg = pick(c, activityInactiveFg, "activityBar.inactiveForeground");
        activityIndicator = pick(c, activityIndicator, "activityBar.activeBorder", "focusBorder");
        titleBg = pick(c, titleBg, "titleBar.activeBackground", "sideBar.background");
        titleFg = pick(c, titleFg, "titleBar.activeForeground", "foreground");
        tabsBg = pick(c, tabsBg, "editorGroupHeader.tabsBackground", "sideBar.background");
        tabActiveBg = pick(c, tabActiveBg, "tab.activeBackground", "editor.background");
        tabActiveFg = pick(c, tabActiveFg, "tab.activeForeground", "foreground");
        tabInactiveBg = pick(c, tabInactiveBg, "tab.inactiveBackground", "editorGroupHeader.tabsBackground");
        tabInactiveFg = pick(c, tabInactiveFg, "tab.inactiveForeground");
        tabBorder = pick(c, tabBorder, "tab.border");
        tabActiveIndicator = pick(c, tabActiveIndicator, "tab.activeBorderTop", "tab.activeBorder", "focusBorder");
        statusBg = pick(c, statusBg, "statusBar.background");
        statusFg = pick(c, statusFg, "statusBar.foreground");
        statusBorder = pick(c, statusBorder, "statusBar.border", "statusBar.background");
        border = pick(c, border, "sideBar.border", "panel.border", "widget.border");
        listPressed = pick(c, listPressed, "list.hoverBackground");
        listActiveBg = pick(c, listActiveBg, "list.inactiveSelectionBackground", "list.activeSelectionBackground");
        listActiveFg = pick(c, listActiveFg, "list.activeSelectionForeground");
        inputBg = pick(c, inputBg, "input.background");
        inputFg = pick(c, inputFg, "input.foreground", "foreground");
        inputBorder = pick(c, inputBorder, "input.border", "widget.border");
        inputPlaceholder = pick(c, inputPlaceholder, "input.placeholderForeground");
        buttonBg = pick(c, buttonBg, "button.background");
        buttonFg = pick(c, buttonFg, "button.foreground");
        secondaryButtonBg = pick(c, secondaryButtonBg, "button.secondaryBackground");
        secondaryButtonFg = pick(c, secondaryButtonFg, "button.secondaryForeground");
        badgeBg = pick(c, badgeBg, "activityBarBadge.background", "badge.background");
        badgeFg = pick(c, badgeFg, "activityBarBadge.foreground", "badge.foreground");
        menuBg = pick(c, menuBg, "menu.background", "editorWidget.background");
        menuFg = pick(c, menuFg, "menu.foreground", "foreground");
        menuSelectionBg = pick(c, menuSelectionBg, "menu.selectionBackground", "list.activeSelectionBackground");
        menuBorder = pick(c, menuBorder, "menu.border", "widget.border");
        widgetBg = pick(c, widgetBg, "editorWidget.background", "quickInput.background");
        widgetBorder = pick(c, widgetBorder, "editorWidget.border", "widget.border");
        focusBorder = pick(c, focusBorder, "focusBorder");
        textLink = pick(c, textLink, "textLink.foreground");
        descriptionFg = pick(c, descriptionFg, "descriptionForeground");
        errorFg = pick(c, errorFg, "errorForeground");
        keysBg = statusBg;
        keysFg = statusFg;
    }

    private int pick(java.util.Map<String, Integer> c, int current, String... keys) {
        for (String k : keys) {
            Integer v = c.get(k);
            if (v != null) {
                // Translucent colors are composited over the side bar background.
                return (v >>> 24) == 0xFF ? v : blend(sideBarBg, v);
            }
        }
        return current;
    }

    private static int lighten(int c) {
        return mix(c, 0xFFFFFFFF, 0.3f);
    }

    @Override
    public int id() {
        return ORIGINAL;
    }

    @Override
    public boolean hasCommandCenter() {
        return true;
    }

    @Override
    public int iconSet() {
        return Icons.SET_CODICON;
    }

    @Override
    public int defaultAccent(boolean dark) {
        return dark ? 0xFF0078D4 : 0xFF005FB8;
    }
}
