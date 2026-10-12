package com.disgusty.droid_codology.work;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.disgusty.droid_codology.editor.CodeView;
import com.disgusty.droid_codology.editor.EditorColors;
import com.disgusty.droid_codology.io.TextFiles;
import com.disgusty.droid_codology.lang.Language;
import com.disgusty.droid_codology.lang.Languages;
import com.disgusty.droid_codology.lang.Tok;
import com.disgusty.droid_codology.text.EditEngine;
import com.disgusty.droid_codology.text.Selections;
import com.disgusty.droid_codology.text.TextBuffer;
import com.disgusty.droid_codology.ui.Skin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/**
 * The VS Code-like workbench: title bar, activity bar + side bar (docked on wide screens, a
 * drawer on phones), editor tabs, breadcrumbs, the code editor with its find widget, the extra
 * keys row and the status bar. Owns the open documents and every workbench command.
 */
public final class Workbench implements CodeView.Host {
    public final Activity activity;
    public final Prefs prefs;
    public Skin skin;
    public final Commands commands = new Commands();
    public final Handler ui = new Handler(Looper.getMainLooper());
    /**
     * Background work. Grows as needed: some tasks wait a long time (a browser sign-in, an agent
     * waiting for approval), and with a fixed pair of threads two of those stalled everything else.
     */
    public final ExecutorService bg = Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
        private final java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "bg-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** The focused editor pane (the other one, when split, is {@link #otherEditor}). */
    public CodeView editor;
    private CodeView otherEditor;
    public Workspace workspace;
    private final ArrayList<Document> docs = new ArrayList<Document>();
    private Document active;
    private final ArrayList<String> closedPaths = new ArrayList<String>();
    private int untitledCounter = 1;

    // views
    private FrameLayout root;
    private LinearLayout columnView;
    private Drawer drawer;
    private LinearLayout sideBar;
    private FrameLayout sideBarContent;
    private boolean narrow;
    private boolean sideBarVisible;
    private int sideView; // 0 explorer, 1 search
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private FrameLayout breadcrumbsHolder;
    private FrameLayout editorArea;
    private View welcome;
    private LinearLayout statusLeft, statusRight;
    private ExtraKeys extraKeys;
    private FindWidget find;
    QuickPick quickPick;
    private ExplorerView explorer;
    private SearchView search;
    private OutlineView outline;
    private MarkdownPreview preview;
    private ExtensionsView extensionsView;
    private View extensionPage;
    public final com.disgusty.droid_codology.ext.ExtensionManager extensions;
    private com.disgusty.droid_codology.ext.ThemeLoader.Loaded extTheme;
    private String extThemeKey;
    private PopupWindow selectionMenu;
    final Completion completion;
    private int choicesShownFor = -1;
    private View settingsPage;
    private boolean zen;

    public Workbench(Activity a) {
        activity = a;
        prefs = new Prefs(a);
        lock = new AppLock(a);
        I18n.setLanguage(prefs.str("workbench.language", "system"));
        editor = new CodeView(a);
        editor.setHost(this);
        if (Build.VERSION.SDK_INT >= 26) com.disgusty.droid_codology.compat.Api26.noFocusHighlight(editor);
        completion = new Completion(this);
        com.disgusty.droid_codology.net.Http.init(a);
        extensions = new com.disgusty.droid_codology.ext.ExtensionManager(a);
        extensions.loadAll();
        extHost = new ExtensionHost(this);
        account = new com.disgusty.droid_codology.account.Account(a);
        ai = new com.disgusty.droid_codology.ai.AiSettings(a);
        lsp = new LspManager(this);
        com.disgusty.droid_codology.lang.Snippets.init(a.getAssets());
        registerCommands();
    }

    final ExtensionHost extHost;
    final LspManager lsp;
    public final com.disgusty.droid_codology.account.Account account;
    final com.disgusty.droid_codology.ai.AiSettings ai;
    private ChatView chatView;
    private ScmView scmView;
    private AccountsPage accountsPage;
    private View accountsView;
    private long lastAutoSync;
    private boolean syncing;
    private boolean extHostStarted;
    private final Runnable selectionToHost = new Runnable() {
        @Override
        public void run() {
            if (active != null) extHost.onSelectionChanged(active);
            if (chatView != null) chatView.onEditorChanged();
        }
    };

    // ------------------------------------------------------------------ look

    public boolean isDarkTheme() {
        String th = prefs.str(Prefs.THEME, "system");
        if (th.equals("dark")) return true;
        if (th.equals("light")) return false;
        // Before Android 10 there is no system dark mode; default to dark like VS Code.
        if (Build.VERSION.SDK_INT < 29) return true;
        int night = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Builds (or rebuilds after a settings change) the whole window. */
    public View build() {
        boolean dark = isDarkTheme();
        com.disgusty.droid_codology.ext.ThemeLoader.Loaded theme = loadExtensionTheme();
        if (theme != null) dark = theme.editor.dark;
        int style = prefs.i(Prefs.STYLE, Skin.ORIGINAL);
        skin = Skin.create(activity, style, dark, Accents.current(prefs, style));
        if (theme != null && skin instanceof com.disgusty.droid_codology.ui.OriginalSkin) ((com.disgusty.droid_codology.ui.OriginalSkin) skin).applyWorkbenchColors(theme.workbench);
        EditorColors colors = theme != null ? theme.editor.copy() : style == Skin.MATRIX ? EditorColors.matrix(skin.accent)
                : dark ? EditorColors.darkModern() : EditorColors.lightModern();
        if (!prefs.b(Prefs.BRACKET_COLORS, true)) {
            colors.tokens[Tok.BRACKET1] = colors.tokens[Tok.PUNCT];
            colors.tokens[Tok.BRACKET2] = colors.tokens[Tok.PUNCT];
            colors.tokens[Tok.BRACKET3] = colors.tokens[Tok.PUNCT];
        }
        if (theme == null) skin.adjustEditorColors(colors);
        boolean rainOn = prefs.b(Prefs.MATRIX_RAIN, false);
        int rainBg = skin.windowBg | 0xFF000000;
        if (rainOn) {
            float w = activity.getResources().getDisplayMetrics().widthPixels / activity.getResources().getDisplayMetrics().density;
            // a side bar sliding over the editor (narrow screens) stays solid
            seeThrough(colors, w >= 720);
        }
        editor.setColors(colors);
        iconStylePending = prefs.i("state.iconStyle", Skin.ORIGINAL) != style;
        applyEditorPrefs();

        float dpW = activity.getResources().getDisplayMetrics().widthPixels / activity.getResources().getDisplayMetrics().density;
        narrow = dpW < 720;
        sideBarVisible = narrow ? false : prefs.b(Prefs.SIDEBAR, true);

        if (otherEditor != null) joinPanes();
        if (editor.getParent() != null) ((ViewGroup) editor.getParent()).removeView(editor);
        root = new FrameLayout(activity);
        root.setBackgroundColor(rainBg);
        rain = null;
        if (rainOn) {
            // The film's falling code, under everything; the panels above let it show through.
            rain = new com.disgusty.droid_codology.ui.MatrixRain(activity, skin.id() == Skin.MATRIX ? skin.accent : com.disgusty.droid_codology.ui.MatrixSkin.PHOSPHOR,
                    rainBg, skin.dp(15));
            root.addView(rain, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        columnView = column;
        if (!zen) column.addView(buildTitleBar());

        LinearLayout middle = new LinearLayout(activity);
        middle.setOrientation(LinearLayout.HORIZONTAL);

        explorer = new ExplorerView(this);
        search = new SearchView(this);
        outline = new OutlineView(this);
        extensionsView = null;
        chatView = null;
        scmView = null;
        for (ExtViewContainer v : extViews.values()) v.dispose();
        extViews.clear();
        sideBar = new LinearLayout(activity);
        boolean railLayout = skin.activityBarVertical();
        sideBar.setOrientation(railLayout ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        sideBar.setBackgroundColor(skin.sideBarBg);
        sideBarContent = new FrameLayout(activity);
        if (railLayout) {
            sideBar.addView(buildActivityBar(), new LinearLayout.LayoutParams(skin.dp(skin.activityBarWidthDp()), ViewGroup.LayoutParams.MATCH_PARENT));
            sideBar.addView(sideBarContent, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        } else {
            sideBar.addView(buildActivityBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sideBar.addView(sideBarContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        showSideView(sideView);

        LinearLayout editorColumn = new LinearLayout(activity);
        editorColumn.setOrientation(LinearLayout.VERTICAL);
        // With the rain the code view's own (see-through) background is the only layer.
        editorColumn.setBackgroundColor(rainOn ? 0 : colors.background);
        tabStrip = new LinearLayout(activity);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll = new HorizontalScrollView(activity);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(true);
        tabScroll.setBackgroundColor(skin.tabsBg);
        tabScroll.addView(tabStrip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(skin.tabHeightDp())));
        editorColumn.addView(tabScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, skin.dp(skin.tabHeightDp())));
        breadcrumbsHolder = new FrameLayout(activity);
        editorColumn.addView(breadcrumbsHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        editorArea = new FrameLayout(activity);
        editorArea.addView(editor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        find = new FindWidget(this);
        int fw = Math.min(activity.getResources().getDisplayMetrics().widthPixels, skin.dp(440));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(narrow ? ViewGroup.LayoutParams.MATCH_PARENT : fw, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        flp.rightMargin = narrow ? 0 : skin.dp(14);
        editorArea.addView(find, flp);
        welcome = buildWelcome();
        editorArea.addView(welcome, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        editorColumn.addView(editorArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // The bottom panel (terminal); kept open across rebuilds.
        panelHolder = new FrameLayout(activity);
        editorColumn.addView(panelHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        terminalPanel = null;
        if (terminalVisible) ui.post(new Runnable() {
            @Override
            public void run() {
                showTerminal();
            }
        });

        boolean fullHeightDrawer = narrow && skin.drawerCoversAppBar();
        if (narrow) {
            middle.addView(editorColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            if (fullHeightDrawer) {
                // Material: the drawer slides over the app bar too; the Drawer wraps the whole window below.
                drawer = null;
                column.addView(withTopShadow(middle), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            } else {
                drawer = new Drawer(activity, middle, skin.scrim);
                int w = Math.min(activity.getResources().getDisplayMetrics().widthPixels - skin.dp(56), skin.dp(360));
                drawer.setPanel(skin.decorateDrawer(sideBar), w);
                column.addView(withTopShadow(drawer), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            }
        } else {
            drawer = null;
            middle.addView(sideBar, new LinearLayout.LayoutParams(sideBarWidth(sideBarVisible), ViewGroup.LayoutParams.MATCH_PARENT));
            sideBarContent.setVisibility(sideBarVisible ? View.VISIBLE : View.GONE);
            if (!railLayout) sideBar.setVisibility(sideBarVisible ? View.VISIBLE : View.GONE);
            middle.addView(skin.vline(skin.border));
            middle.addView(editorColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            column.addView(withTopShadow(middle), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }

        extraKeys = new ExtraKeys(this);
        column.addView(extraKeys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        extraKeys.setVisibility(prefs.b(Prefs.EXTRA_KEYS, true) && !hasHardwareKeyboard() ? View.VISIBLE : View.GONE);
        if (!zen) column.addView(buildStatusBar());

        if (fullHeightDrawer) {
            drawer = new Drawer(activity, column, skin.scrim);
            int w = Math.min(activity.getResources().getDisplayMetrics().widthPixels - skin.dp(56), skin.dp(360));
            drawer.setPanel(skin.decorateDrawer(sideBar), w);
            root.addView(drawer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // Over the window (under the quick pick): double-tap a border to move it.
        borderDrag = new BorderDrag(this);
        root.addView(borderDrag, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        quickPick = new QuickPick(this);
        root.addView(quickPick, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        quickPick.setVisibility(View.GONE);

        skin.decorate(activity);
        activity.setTheme(skin.nativeTheme());
        explorer.setWorkspace(workspace);
        search.setWorkspace(workspace);
        refreshTabs();
        showDocument(active);
        if (!extHostStarted) {
            extHostStarted = true;
            // Contributed commands register now; extension code starts once the window is up.
            extHost.registerOnly();
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    extHost.start();
                    autoSync();
                    if (workspace != null) scheduleScmRefresh();
                    refreshServerInfo();
                }
            }, 400);
        }
        lock.attach(this, root);
        return root;
    }

    /** Overlays the title bar's shadow on top of the content (styles that need a fake one). */
    private View withTopShadow(View content) {
        if (skin.topShadowDp() == 0 || zen) return content;
        FrameLayout f = new FrameLayout(activity);
        f.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View sh = skin.shadowStrip(skin.topShadowDp(), skin.dark);
        f.addView(sh, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, skin.dp(skin.topShadowDp()), Gravity.TOP));
        return f;
    }

    public boolean wideScreen() {
        float dpW = activity.getResources().getDisplayMetrics().widthPixels / activity.getResources().getDisplayMetrics().density;
        return dpW >= 600;
    }

    public boolean hasHardwareKeyboard() {
        return activity.getResources().getConfiguration().keyboard == Configuration.KEYBOARD_QWERTY
                && activity.getResources().getConfiguration().hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    public void rebuild() {
        Session.saveState(this);
        // The new window gets a new chat view (it reloads the conversation): an agent left waiting
        // for approval in the old one could never be answered.
        if (chatView != null) chatView.stop();
        // A picker waiting for an answer (an extension's showQuickPick...) is cancelled, not lost.
        if (quickPick != null && quickPick.getVisibility() == View.VISIBLE) quickPick.dismiss();
        closePreview();
        // Full-screen pages belong to the old window: reopen them (in the new style) afterwards.
        boolean settings = settingsPage != null, accounts = accountsView != null;
        settingsPage = null;
        accountsView = null;
        accountsPage = null;
        extensionPage = null;
        activity.setContentView(build());
        if (settings) openSettings();
        if (accounts) openAccounts();
        // Open webview panels survive a rebuild (theme or style change) on top of the new window.
        for (ExtWebview w : webviewPanels) {
            if (w.page == null) continue;
            if (w.page.getParent() != null) ((ViewGroup) w.page.getParent()).removeView(w.page);
            w.page.setBackgroundColor(skin.windowBg);
            updateWebviewPanelTitle(w);
            root.addView(w.page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** The activity is going away for good: stop what runs on its behalf. */
    public void dispose() {
        if (chatView != null) chatView.stop();
        try {
            extHost.stop();
        } catch (RuntimeException ignored) {
        }
        lsp.reset();
        for (ExtWebview w : new ArrayList<ExtWebview>(webviewPanels)) closeWebviewPanel(w, true);
        ui.removeCallbacksAndMessages(null);
    }

    public void applyEditorPrefs() {
        applyPrefsTo(editor);
        if (otherEditor != null) applyPrefsTo(otherEditor);
        for (Document d : docs) applyDocPrefs(d);
    }

    /** Settings for one editor pane (the parameter shadows the field on purpose). */
    private void applyPrefsTo(CodeView editor) {
        editor.setFontSize(prefs.f(Prefs.FONT_SIZE, 14));
        editor.setLineSpacing(prefs.f(Prefs.LINE_HEIGHT, 1.35f));
        String ln = prefs.str(Prefs.LINE_NUMBERS, "on");
        editor.lineNumbers = !ln.equals("off");
        editor.relativeNumbers = ln.equals("relative");
        editor.renderWhitespace = prefs.b(Prefs.WHITESPACE, false);
        editor.indentGuides = prefs.b(Prefs.INDENT_GUIDES, true);
        editor.highlightLine = prefs.b(Prefs.HIGHLIGHT_LINE, true);
        editor.matchBrackets = prefs.b(Prefs.MATCH_BRACKETS, true);
        editor.foldingEnabled = prefs.b(Prefs.FOLDING, true);
        editor.stickyScroll = prefs.b(Prefs.STICKY_SCROLL, true);
        editor.setMinimap(prefs.b(Prefs.MINIMAP, wideScreen()));
        editor.keyboardSuggestions = prefs.b(Prefs.SUGGESTIONS, false);
        String rulers = prefs.str(Prefs.RULERS, "");
        ArrayList<Integer> rl = new ArrayList<Integer>();
        for (String r : rulers.split("[ ,]+")) {
            try {
                if (!r.isEmpty()) rl.add(Integer.parseInt(r.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        int[] ra = new int[rl.size()];
        for (int i = 0; i < ra.length; i++) ra[i] = rl.get(i);
        editor.rulers = ra;
        String font = prefs.str(Prefs.FONT_FILE, "");
        Typeface tf = null;
        if (!font.isEmpty()) {
            try {
                tf = Typeface.createFromFile(font);
            } catch (RuntimeException ignored) {
            }
        }
        editor.setTypeface(tf);
        editor.setWordWrap(prefs.b(Prefs.WORD_WRAP, false));
        editor.invalidate();
    }

    private void applyDocPrefs(Document d) {
        EditEngine e = d.engine;
        e.autoClose = prefs.b(Prefs.AUTO_CLOSE, true);
        e.autoIndent = prefs.b(Prefs.AUTO_INDENT, true);
        if (!prefs.b(Prefs.DETECT_INDENT, true) || d.text.length() == 0) {
            e.tabSize = prefs.i(Prefs.TAB_SIZE, 4);
            e.insertSpaces = prefs.b(Prefs.INSERT_SPACES, true);
        }
    }

    // ------------------------------------------------------------------ title bar

    private View buildTitleBar() {
        ArrayList<Skin.Action> actions = new ArrayList<Skin.Action>();
        boolean small = narrow;
        boolean center = !small && prefs.b(Prefs.COMMAND_CENTER, true) && skin.hasCommandCenter();
        if (!center) {
            actions.add(new Skin.Action("search", t("Go to File..."), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    commands.run("workbench.action.quickOpen");
                }
            }));
        }
        actions.add(new Skin.Action("undo", t("Undo"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commands.run("undo");
            }
        }));
        actions.add(new Skin.Action("redo", t("Redo"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commands.run("redo");
            }
        }));
        actions.add(new Skin.Action("more", t("More"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppMenu(v);
            }
        }));
        Skin.Action nav = new Skin.Action("menu", t("Toggle Primary Side Bar Visibility"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSideBar();
            }
        });
        View centerView = null;
        if (center) {
            centerView = skin.commandCenter(workspace != null ? workspace.name() : "droid_codology", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    commands.run("workbench.action.quickOpen");
                }
            });
        }
        String title = active != null ? (active.isDirty() ? "● " : "") + active.name : "droid_codology";
        String sub = workspace != null ? workspace.name() : null;
        if (centerView != null) sub = null;
        View bar = skin.titleBar(nav, title, sub, actions, centerView);
        bar.setTag("titlebar");
        return bar;
    }

    private void refreshTitle() {
        if (root == null || zen) return;
        LinearLayout column = columnView;
        if (column == null) return;
        View old = column.getChildAt(0);
        if (old != null && "titlebar".equals(old.getTag())) {
            column.removeViewAt(0);
            column.addView(buildTitleBar(), 0);
        }
    }

    /** VS Code's application menu (File / Edit / Selection / View / Go / Help) as nested popups. */
    private void showAppMenu(final View anchor) {
        ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
        m.add(modeItem());
        m.add(item("workbench.action.files.newUntitledFile").separator());
        m.add(item("workbench.action.files.openFile"));
        m.add(item("workbench.action.files.openFolder"));
        m.add(new Skin.MenuItem(t("Open Recent") + "  ›", new Runnable() {
            @Override
            public void run() {
                showRecentMenu(anchor);
            }
        }));
        m.add(item("workbench.action.files.save").separator());
        m.add(item("workbench.action.files.saveAs"));
        m.add(item("workbench.action.files.saveAll"));
        m.add(new Skin.MenuItem(t("Edit") + "  ›", new Runnable() {
            @Override
            public void run() {
                showSubMenu(anchor, "edit");
            }
        }).separator());
        m.add(new Skin.MenuItem(t("Selection") + "  ›", new Runnable() {
            @Override
            public void run() {
                showSubMenu(anchor, "selection");
            }
        }));
        m.add(new Skin.MenuItem(t("View") + "  ›", new Runnable() {
            @Override
            public void run() {
                showSubMenu(anchor, "view");
            }
        }));
        m.add(item("workbench.action.showCommands").separator());
        m.add(item("workbench.action.openSettings"));
        m.add(item("workbench.action.closeActiveEditor").separator());
        m.add(item("workbench.action.closeFolder"));
        skin.popup(anchor, m);
    }

    private void showRecentMenu(View anchor) {
        ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
        for (final String p : recentFolders()) {
            m.add(new Skin.MenuItem(new File(p).getName() + "  —  " + p, new Runnable() {
                @Override
                public void run() {
                    openFolder(new File(p));
                }
            }));
        }
        if (m.isEmpty()) m.add(new Skin.MenuItem(t("No results"), null).enabled(false));
        skin.popup(anchor, m);
    }

    private void showSubMenu(View anchor, String which) {
        String[] ids;
        if (which.equals("edit")) {
            ids = new String[]{"undo", "redo", "-", "editor.action.clipboardCutAction", "editor.action.clipboardCopyAction",
                    "editor.action.clipboardPasteAction", "-", "actions.find", "editor.action.startFindReplaceAction",
                    "workbench.action.findInFiles", "-", "editor.action.commentLine", "editor.action.blockComment"};
        } else if (which.equals("selection")) {
            ids = new String[]{"editor.action.selectAll", "expandLineSelection", "editor.action.smartSelect.expand", "-",
                    "editor.action.copyLinesUpAction", "editor.action.copyLinesDownAction", "editor.action.moveLinesUpAction",
                    "editor.action.moveLinesDownAction", "editor.action.duplicateSelection", "-",
                    "editor.action.insertCursorAbove", "editor.action.insertCursorBelow",
                    "editor.action.addSelectionToNextFindMatch", "editor.action.selectHighlights"};
        } else {
            ids = new String[]{"workbench.action.showCommands", "workbench.action.quickOpen", "workbench.action.gotoLine", "-",
                    "workbench.view.explorer", "workbench.view.search", "workbench.action.toggleSidebarVisibility", "-",
                    "editor.action.toggleWordWrap", "editor.action.toggleRenderWhitespace", "workbench.action.toggleExtraKeys",
                    "workbench.action.toggleZenMode", "-", "workbench.action.zoomIn", "workbench.action.zoomOut",
                    "workbench.action.zoomReset"};
        }
        ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
        boolean sep = false;
        for (String id : ids) {
            if (id.equals("-")) {
                sep = true;
                continue;
            }
            Skin.MenuItem mi = item(id);
            if (sep) mi.separator();
            sep = false;
            m.add(mi);
        }
        skin.popup(anchor, m);
    }

    private Skin.MenuItem item(final String id) {
        Commands.Command c = commands.get(id);
        if (c == null) return new Skin.MenuItem(id, null).enabled(false);
        Skin.MenuItem mi = new Skin.MenuItem(c.title, new Runnable() {
            @Override
            public void run() {
                commands.run(id);
            }
        });
        mi.shortcut = hasHardwareKeyboard() || !narrow ? c.keyLabel() : null;
        mi.enabled = c.when == null || c.when.ok();
        return mi;
    }

    // ------------------------------------------------------------------ activity bar & side bar

    // Activity bar items: built-in views by id (index == view kind) and extension view containers
    // ("view:<extension id>/<container id>"), in the order the user chose in Settings.
    static final String[] BUILTIN_VIEWS = {"explorer", "search", "scm", "outline", "extensions", "chat"};
    static final String[] BUILTIN_ICONS = {"explorer", "search", "scm", "outline", "extensions", "chat"};

    String builtinLabel(int kind) {
        switch (kind) {
            case 0:
                return t("Explorer");
            case 1:
                return t("Search");
            case 2:
                return t("Source Control");
            case 3:
                return t("Outline");
            case 4:
                return t("Extensions");
            default:
                return t("Chat");
        }
    }

    /** The configured activity bar items (ids). */
    List<String> barIds() {
        ArrayList<String> out = new ArrayList<String>();
        String raw = prefs.str("workbench.activityBar.items", null);
        if (raw == null) {
            out.addAll(Arrays.asList(BUILTIN_VIEWS));
            return out;
        }
        try {
            org.json.JSONArray a = new org.json.JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String id = a.getString(i);
                if (kindOf(id) >= 0 && !out.contains(id)) out.add(id);
            }
        } catch (org.json.JSONException e) {
            out.addAll(Arrays.asList(BUILTIN_VIEWS));
        }
        return out;
    }

    public void setBarIds(List<String> ids) {
        prefs.put("workbench.activityBar.items", new org.json.JSONArray(ids).toString());
        if (sideBar != null) showSideView(sideView);
    }

    /** View containers contributed by enabled extensions, by key. */
    LinkedHashMap<String, ExtViewContainer.Spec> extContainers() {
        LinkedHashMap<String, ExtViewContainer.Spec> out = new LinkedHashMap<String, ExtViewContainer.Spec>();
        for (com.disgusty.droid_codology.ext.Extension e : extensions.installed()) {
            if (!e.enabled) continue;
            org.json.JSONObject c = e.contributes();
            org.json.JSONObject vc = c.optJSONObject("viewsContainers");
            org.json.JSONArray bar = vc == null ? null : vc.optJSONArray("activitybar");
            org.json.JSONObject views = c.optJSONObject("views");
            for (int i = 0; bar != null && i < bar.length(); i++) {
                org.json.JSONObject b = bar.optJSONObject(i);
                if (b == null) continue;
                String cid = b.optString("id");
                String key = "view:" + e.id + "/" + cid;
                String title = b.optString("title", cid);
                if (title.startsWith("%")) title = e.displayName;
                ArrayList<org.json.JSONObject> vs = new ArrayList<org.json.JSONObject>();
                org.json.JSONArray va = views == null ? null : views.optJSONArray(cid);
                for (int k = 0; va != null && k < va.length(); k++) if (va.optJSONObject(k) != null) vs.add(va.optJSONObject(k));
                String iconName = "ext:" + key;
                String iconPath = b.optString("icon", "");
                boolean ok = false;
                File icon = iconPath.isEmpty() ? null : e.file(iconPath);
                if (icon != null && icon.isFile() && icon.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".svg")) {
                    try {
                        ok = com.disgusty.droid_codology.ui.Icons.registerSvg(iconName, new String(ExtensionHost.readBytes(icon), "UTF-8"));
                    } catch (IOException ignored) {
                    }
                }
                out.put(key, new ExtViewContainer.Spec(key, e.id, title, ok ? iconName : "extensions", vs));
            }
        }
        return out;
    }

    private final java.util.HashMap<String, Integer> extKinds = new java.util.HashMap<String, Integer>();
    private final java.util.HashMap<Integer, ExtViewContainer> extViews = new java.util.HashMap<Integer, ExtViewContainer>();

    /** View kind of an item id (built-in index, or 100+ for extension containers); -1 if unknown. */
    int kindOf(String id) {
        int b = Arrays.asList(BUILTIN_VIEWS).indexOf(id);
        if (b >= 0) return b;
        if (!id.startsWith("view:")) return -1;
        Integer k = extKinds.get(id);
        if (k == null) {
            if (!extContainers().containsKey(id)) return -1;
            k = 100 + extKinds.size();
            extKinds.put(id, k);
        }
        return k;
    }

    String idOfKind(int kind) {
        if (kind >= 0 && kind < BUILTIN_VIEWS.length) return BUILTIN_VIEWS[kind];
        for (java.util.Map.Entry<String, Integer> e : extKinds.entrySet()) if (e.getValue() == kind) return e.getKey();
        return BUILTIN_VIEWS[0];
    }

    /** Opens the side bar on the {@code index}-th activity bar item. */
    public void showBarItem(int index) {
        List<String> ids = barIds();
        if (index < 0 || index >= ids.size()) return;
        int kind = kindOf(ids.get(index));
        if (kind < 0) return;
        showSideView(kind);
        setSideBarVisible(true);
    }

    void onExtensionTreeChanged(String viewId) {
        for (ExtViewContainer v : extViews.values()) v.onTreeChanged(viewId);
    }

    private View buildActivityBar() {
        final List<String> ids = barIds();
        LinkedHashMap<String, ExtViewContainer.Spec> containers = null;
        ArrayList<Skin.NavItem> top = new ArrayList<Skin.NavItem>();
        for (String id : ids) {
            int k = kindOf(id);
            if (k < BUILTIN_VIEWS.length) top.add(new Skin.NavItem(BUILTIN_ICONS[k], builtinLabel(k)));
            else {
                if (containers == null) containers = extContainers();
                ExtViewContainer.Spec s = containers.get(id);
                top.add(new Skin.NavItem(s != null ? s.iconName : "extensions", s != null ? s.title : id));
            }
        }
        ArrayList<Skin.NavItem> bottom = new ArrayList<Skin.NavItem>();
        bottom.add(new Skin.NavItem("account", t("Accounts")));
        bottom.add(new Skin.NavItem("settings", t("Manage")));
        int selected = sideBarVisible || narrow ? ids.indexOf(idOfKind(sideView)) : -1;
        return skin.activityBar(top, bottom, selected, new Skin.NavListener() {
            @Override
            public void onNav(int index) {
                if (index == ids.size()) {
                    closeOverlaySideBar();
                    openAccounts();
                    return;
                }
                if (index > ids.size()) {
                    showManageMenu();
                    return;
                }
                int kind = kindOf(ids.get(index));
                if (!narrow && sideBarVisible && sideView == kind) {
                    setSideBarVisible(false);
                    return;
                }
                showSideView(kind);
                setSideBarVisible(true);
            }
        });
    }

    private void showManageMenu() {
        ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
        m.add(modeItem());
        m.add(item("workbench.action.showCommands").separator());
        m.add(item("workbench.action.openSettings"));
        m.add(item("workbench.action.openAccounts").separator());
        m.add(item("workbench.action.selectTheme"));
        m.add(item("workbench.action.customizeActivityBar"));
        View anchor = sideBar.getChildAt(0);
        if (skin.activityBarVertical()) skin.popupAt(anchor, m, skin.dp(skin.activityBarWidthDp()), anchor.getHeight() - skin.dp(200), true);
        else skin.popupAt(anchor, m, anchor.getWidth() - skin.dp(250), anchor.getHeight(), true);
    }

    /** Side views in activity bar order; nav indexes after them are Accounts and Manage. */
    static final int EXPLORER_VIEW = 0, SEARCH_VIEW = 1, SCM_VIEW = 2, OUTLINE_VIEW = 3, EXTENSIONS_VIEW = 4, CHAT_VIEW = 5;
    static final int SIDE_VIEWS = 6;

    public void showSideView(int index) {
        sideView = index;
        if (sideBarContent == null) return;
        sideBarContent.removeAllViews();
        if (index == EXTENSIONS_VIEW && extensionsView == null) extensionsView = new ExtensionsView(this);
        if (index == CHAT_VIEW && chatView == null) chatView = new ChatView(this);
        if (index == SCM_VIEW) scm();
        View v = index == SEARCH_VIEW ? search : index == OUTLINE_VIEW ? outline : index == EXTENSIONS_VIEW ? extensionsView
                : index == CHAT_VIEW ? chatView : index == SCM_VIEW ? scmView : explorer;
        if (index >= 100) {
            ExtViewContainer ev = extViews.get(index);
            if (ev == null) {
                ExtViewContainer.Spec spec = extContainers().get(idOfKind(index));
                if (spec != null) {
                    ev = new ExtViewContainer(this, spec);
                    extViews.put(index, ev);
                }
            }
            if (ev != null) {
                v = ev;
                ev.onShown();
            } else {
                sideView = index = EXPLORER_VIEW;
            }
        }
        if (index == OUTLINE_VIEW) outline.refresh();
        if (index == SCM_VIEW) scmView.refresh();
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        sideBarContent.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Re-render the activity bar selection.
        View oldBar = sideBar.getChildAt(0);
        ViewGroup.LayoutParams lp = oldBar.getLayoutParams();
        sideBar.removeViewAt(0);
        sideBar.addView(buildActivityBar(), 0, lp);
    }

    private int sideBarWidth(boolean open) {
        int content = prefs.i(SIDEBAR_WIDTH_DP, 280);
        if (!skin.activityBarVertical()) return skin.dp(content + 40);
        return skin.dp(skin.activityBarWidthDp() + (open ? content : 0));
    }

    // ------------------------------------------------------------------ movable borders (BorderDrag)

    /** Layout sizes are per device ("state." keys aren't synced). */
    static final String SIDEBAR_WIDTH_DP = "state.layout.sideBarWidthDp", PANEL_RATIO = "state.layout.panelRatio",
            SPLIT_RATIO = "state.layout.splitRatio";
    private BorderDrag borderDrag;

    private void boundsInRoot(View v, Rect out) {
        int[] a = new int[2], r = new int[2];
        v.getLocationInWindow(a);
        root.getLocationInWindow(r);
        out.set(a[0] - r[0], a[1] - r[1], a[0] - r[0] + v.getWidth(), a[1] - r[1] + v.getHeight());
    }

    private static boolean shown(View v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.isShown() && v.getWidth() > 0 && v.getHeight() > 0;
    }

    /** Where border {@code id} is (a line in root coordinates), or false when it isn't on screen. */
    boolean borderLine(int id, Rect out) {
        if (root == null) return false;
        Rect b = new Rect();
        if (id == BorderDrag.SIDEBAR) {
            if (drawer != null || narrow || !sideBarVisible || !shown(sideBarContent)) return false;
            boundsInRoot(sideBar, b);
            out.set(b.right, b.top, b.right + 1, b.bottom);
            return true;
        }
        if (id == BorderDrag.PANEL) {
            if (!terminalVisible || terminalMaximized || !shown(panelHolder) || !shown(editorArea)) return false;
            boundsInRoot(panelHolder, b);
            out.set(b.left, b.top, b.right, b.top + 1);
            return true;
        }
        if (id == BorderDrag.SPLIT) {
            if (otherEditor == null || !shown(splitDivider)) return false;
            boundsInRoot(splitDivider, out);
            return true;
        }
        return false;
    }

    /** The finger dragging border {@code id} is at (x, y) in root coordinates. */
    void moveBorder(int id, float x, float y) {
        Rect b = new Rect();
        if (id == BorderDrag.SIDEBAR) {
            boundsInRoot(sideBar, b);
            int bar = skin.activityBarVertical() ? skin.dp(skin.activityBarWidthDp()) : 0;
            int max = Math.max(skin.dp(160), root.getWidth() - b.left - bar - skin.dp(200));
            int content = Math.max(skin.dp(160), Math.min(max, Math.round(x) - b.left - bar));
            sideBarContentDp = Math.round(content / activity.getResources().getDisplayMetrics().density);
            ViewGroup.LayoutParams lp = sideBar.getLayoutParams();
            lp.width = bar + content;
            sideBar.setLayoutParams(lp);
        } else if (id == BorderDrag.PANEL) {
            Rect e = new Rect(), p = new Rect();
            boundsInRoot(editorArea, e);
            boundsInRoot(panelHolder, p);
            float total = p.bottom - e.top;
            if (total <= 0) return;
            panelRatio = Math.max(0.12f, Math.min(0.88f, (p.bottom - y) / total));
            layoutPanel();
        } else if (id == BorderDrag.SPLIT) {
            boundsInRoot(editorArea, b);
            float r = splitBottom ? (y - b.top) / Math.max(1, b.height()) : (x - b.left) / Math.max(1, b.width());
            // The first pane may have been swapped to the other side.
            splitRatio = Math.max(0.15f, Math.min(0.85f, r));
            layoutSplit();
        }
    }

    void saveBorders() {
        if (sideBarContentDp > 0) prefs.put(SIDEBAR_WIDTH_DP, sideBarContentDp);
        // Only what was laid out (-1 = not used yet in this session).
        if (panelRatio > 0) prefs.put(PANEL_RATIO, panelRatio);
        if (splitRatio > 0) prefs.put(SPLIT_RATIO, splitRatio);
    }

    private int sideBarContentDp;

    private float ratioPref(String key, float def, float min, float max) {
        float r = prefs.f(key, def);
        return r >= min && r <= max ? r : def;
    }
    private float panelRatio = -1, splitRatio = -1;

    public void toggleSideBar() {
        setSideBarVisible(narrow ? !(drawer != null && drawer.isOpen()) : !sideBarVisible);
    }

    public void setSideBarVisible(boolean show) {
        if (narrow) {
            if (drawer == null) return;
            if (show) {
                editor.hideKeyboard();
                drawer.open();
            } else drawer.close();
            return;
        }
        sideBarVisible = show;
        prefs.put(Prefs.SIDEBAR, show);
        sideBarContent.setVisibility(show ? View.VISIBLE : View.GONE);
        // Styles with tabs above the panel hide it entirely; the VS Code / M3 rail stays visible.
        if (!skin.activityBarVertical()) sideBar.setVisibility(show ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams lp = sideBar.getLayoutParams();
        lp.width = sideBarWidth(show);
        sideBar.setLayoutParams(lp);
        View oldBar = sideBar.getChildAt(0);
        ViewGroup.LayoutParams blp = oldBar.getLayoutParams();
        sideBar.removeViewAt(0);
        sideBar.addView(buildActivityBar(), 0, blp);
    }

    /** On phones, opening a file from the side bar closes the drawer. */
    public void closeOverlaySideBar() {
        if (narrow && drawer != null) drawer.close();
    }

    // ------------------------------------------------------------------ welcome

    private View buildWelcome() {
        ScrollView sv = new ScrollView(activity);
        sv.setBackgroundColor(editor.colors().background);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(skin.dp(28), skin.dp(32), skin.dp(28), skin.dp(28));
        TextView title = skin.text("droid_codology", 30, skin.sideBarFg, Typeface.DEFAULT);
        col.addView(title);
        col.addView(skin.text(t("Editing evolved"), 18, skin.descriptionFg, Typeface.DEFAULT));
        View gap = new View(activity);
        col.addView(gap, new LinearLayout.LayoutParams(1, skin.dp(24)));
        col.addView(skin.text(t("Start"), 17, skin.sideBarFg, Typeface.DEFAULT));
        col.addView(welcomeLink("new_file", t("New File..."), "workbench.action.files.newUntitledFile"));
        col.addView(welcomeLink("go_to_file", t("Open File..."), "workbench.action.files.openFile"));
        col.addView(welcomeLink("folder_open", t("Open Folder..."), "workbench.action.files.openFolder"));
        col.addView(welcomeLink("command", t("Show All Commands"), "workbench.action.showCommands"));
        List<String> recent = recentFolders();
        if (!recent.isEmpty()) {
            View g2 = new View(activity);
            col.addView(g2, new LinearLayout.LayoutParams(1, skin.dp(20)));
            col.addView(skin.text(t("Recent"), 17, skin.sideBarFg, Typeface.DEFAULT));
            for (int i = 0; i < Math.min(6, recent.size()); i++) {
                final String p = recent.get(i);
                LinearLayout row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = skin.text(new File(p).getName(), skin.uiSp(), skin.textLink, Typeface.DEFAULT);
                row.addView(name);
                TextView path = skin.text("   " + p, skin.uiSp() - 1, skin.descriptionFg, Typeface.DEFAULT);
                path.setSingleLine(true);
                row.addView(path);
                row.setPadding(0, skin.dp(6), 0, skin.dp(6));
                row.setClickable(true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openFolder(new File(p));
                    }
                });
                col.addView(row);
            }
        }
        sv.addView(col);
        return sv;
    }

    private View welcomeLink(String icon, String label, final String cmd) {
        TextView t = skin.text(label, skin.uiSp() + 1, skin.textLink, Typeface.DEFAULT);
        t.setCompoundDrawablesWithIntrinsicBounds(skin.icon(icon, skin.textLink, 18), null, null, null);
        t.setCompoundDrawablePadding(skin.dp(10));
        t.setPadding(0, skin.dp(8), 0, skin.dp(8));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commands.run(cmd);
            }
        });
        return t;
    }

    // ------------------------------------------------------------------ status bar

    private View buildStatusBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.addView(skin.hairline(skin.statusBorder));
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        skin.styleStatusBar(row);
        statusLeft = new LinearLayout(activity);
        statusLeft.setGravity(Gravity.CENTER_VERTICAL);
        statusRight = new LinearLayout(activity);
        statusRight.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.addView(statusLeft, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        HorizontalScrollView hs = new HorizontalScrollView(activity);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        hs.addView(statusRight, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        bar.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, skin.dp(skin.statusBarHeightDp())));
        updateStatus();
        return bar;
    }

    public void updateStatus() {
        if (statusRight == null) return;
        statusLeft.removeAllViews();
        statusRight.removeAllViews();
        if (workspace != null) {
            statusLeft.addView(skin.statusItem(workspace.name(), "folder", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSideView(0);
                    setSideBarVisible(true);
                }
            }));
        }
        if (scmView != null && scmView.lastStatus() != null && workspace != null) {
            com.disgusty.droid_codology.git.Repo.Status gs = scmView.lastStatus();
            String br = gs.branch != null ? gs.branch : gs.head != null ? gs.head.substring(0, 8) : "";
            boolean dirty = !gs.staged.isEmpty() || !gs.unstaged.isEmpty();
            statusLeft.addView(skin.statusItem(br + (dirty ? "*" : ""), "branch", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    scm().pickBranch();
                }
            }));
            if (gs.upstream != null) {
                statusLeft.addView(skin.statusItem(gs.behind + "↓ " + gs.ahead + "↑", "sync", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        commands.run("git.sync");
                    }
                }));
            }
        }
        int errors = 0, warnings = 0;
        for (Document doc : docs) {
            for (com.disgusty.droid_codology.editor.Diagnostic g : doc.allDiagnostics()) {
                if (g.severity == com.disgusty.droid_codology.editor.Diagnostic.ERROR) errors++;
                else if (g.severity == com.disgusty.droid_codology.editor.Diagnostic.WARNING) warnings++;
            }
        }
        if (errors + warnings > 0) {
            View.OnClickListener problems = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showProblems();
                }
            };
            statusLeft.addView(skin.statusItem(String.valueOf(errors), "error", problems));
            statusLeft.addView(skin.statusItem(String.valueOf(warnings), "warning", problems));
        }
        extHost.addStatusItems(statusLeft, statusRight);
        Document d = active;
        if (d == null) return;
        // The problem under the caret (there's no mouse hover on a phone).
        final com.disgusty.droid_codology.editor.Diagnostic here = editor.diagnosticAt(d.engine.sel.caret());
        if (here != null) {
            String msg = here.message.length() > 80 ? here.message.substring(0, 80) + "…" : here.message;
            statusLeft.addView(skin.statusItem(msg, here.severity == com.disgusty.droid_codology.editor.Diagnostic.ERROR ? "error" : "warning",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showHover(here.message + (here.source == null ? "" : "\n\n(" + here.source + ")"));
                        }
                    }));
        }
        String lspState = lsp.statusFor(d);
        if (lspState != null) statusRight.addView(skin.statusItem(lspState, null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commands.run("lsp.restart");
            }
        }));
        EditEngine e = d.engine;
        Selections s = e.sel;
        int head = s.head(s.primary());
        int line = d.text.lineOfOffset(head);
        int col = e.column(head) + 1;
        String pos = f("Ln {0}, Col {1}", line + 1, col);
        if (s.count() > 1) pos += "  " + f("{0} selections", s.count());
        else if (!s.isEmpty(0)) pos += " " + f("({0} selected)", s.end(0) - s.start(0));
        statusRight.addView(skin.statusItem(pos, null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commands.run("workbench.action.gotoLine");
            }
        }));
        statusRight.addView(skin.statusItem(e.insertSpaces ? f("Spaces: {0}", e.tabSize) : f("Tab Size: {0}", e.tabSize), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickIndentation();
            }
        }));
        statusRight.addView(skin.statusItem(d.charset.toUpperCase(java.util.Locale.ROOT) + (d.bom ? " with BOM" : ""), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickEncoding();
            }
        }));
        statusRight.addView(skin.statusItem(d.eol.equals(TextFiles.CRLF) ? "CRLF" : d.eol.equals(TextFiles.CR) ? "CR" : "LF", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickEol();
            }
        }));
        statusRight.addView(skin.statusItem(d.language.name, null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLanguage();
            }
        }));
        if (d.readOnly) statusRight.addView(skin.statusItem(t("Read-only"), "lock", null));
        String chord = commands.pendingChord();
        if (chord != null) statusLeft.addView(skin.statusItem("(" + Commands.pretty(chord) + ") ...", null, null));
    }

    // ------------------------------------------------------------------ tabs

    public void refreshTabs() {
        if (tabStrip == null) return;
        tabStrip.removeAllViews();
        tabScroll.setVisibility(docs.isEmpty() ? View.GONE : View.VISIBLE);
        View activeTab = null;
        for (final Document d : docs) {
            View tab = skin.tab(d.name, FileIcons.file(skin, d.name, 16), d == active, d.isDirty(), d.preview,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            activate(d);
                        }
                    }, new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            showTabMenu(v, d);
                            return true;
                        }
                    }, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            close(d, null);
                        }
                    });
            tabStrip.addView(tab);
            if (d == active) activeTab = tab;
        }
        final View at = activeTab;
        if (at != null) {
            tabScroll.post(new Runnable() {
                @Override
                public void run() {
                    int l = at.getLeft(), r = at.getRight(), sx = tabScroll.getScrollX(), w = tabScroll.getWidth();
                    if (l < sx) tabScroll.smoothScrollTo(l, 0);
                    else if (r > sx + w) tabScroll.smoothScrollTo(r - w, 0);
                }
            });
        }
        refreshBreadcrumbs();
        refreshTitle();
        scheduleSessionSave();
    }

    private void showTabMenu(View anchor, final Document d) {
        ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
        m.add(new Skin.MenuItem(t("Close"), new Runnable() {
            @Override
            public void run() {
                close(d, null);
            }
        }));
        m.add(new Skin.MenuItem(t("Close Others"), new Runnable() {
            @Override
            public void run() {
                closeMany(othersThan(d));
            }
        }));
        m.add(new Skin.MenuItem(t("Close to the Right"), new Runnable() {
            @Override
            public void run() {
                int i = docs.indexOf(d);
                closeMany(new ArrayList<Document>(docs.subList(i + 1, docs.size())));
            }
        }));
        m.add(new Skin.MenuItem(t("Close Saved"), new Runnable() {
            @Override
            public void run() {
                ArrayList<Document> l = new ArrayList<Document>();
                for (Document x : docs) if (!x.isDirty()) l.add(x);
                closeMany(l);
            }
        }));
        m.add(new Skin.MenuItem(t("Close All"), new Runnable() {
            @Override
            public void run() {
                closeMany(new ArrayList<Document>(docs));
            }
        }));
        if (d.preview) {
            m.add(new Skin.MenuItem(t("Keep Open"), new Runnable() {
                @Override
                public void run() {
                    d.preview = false;
                    refreshTabs();
                }
            }).separator());
        }
        if (d.file != null) {
            m.add(new Skin.MenuItem(t("Copy Path"), new Runnable() {
                @Override
                public void run() {
                    copyText(d.file.getAbsolutePath());
                }
            }).separator());
            if (workspace != null && workspace.contains(d.file)) {
                m.add(new Skin.MenuItem(t("Copy Relative Path"), new Runnable() {
                    @Override
                    public void run() {
                        copyText(workspace.relative(d.file));
                    }
                }));
                m.add(new Skin.MenuItem(t("Reveal in Explorer View"), new Runnable() {
                    @Override
                    public void run() {
                        showSideView(0);
                        setSideBarVisible(true);
                        explorer.reveal(d.file);
                    }
                }));
            }
        }
        skin.popup(anchor, m);
    }

    private ArrayList<Document> othersThan(Document d) {
        ArrayList<Document> l = new ArrayList<Document>(docs);
        l.remove(d);
        return l;
    }

    private void refreshBreadcrumbs() {
        if (breadcrumbsHolder == null) return;
        breadcrumbsHolder.removeAllViews();
        if (active == null || !prefs.b(Prefs.BREADCRUMBS, true)) return;
        final ArrayList<String> parts = new ArrayList<String>();
        final ArrayList<File> files = new ArrayList<File>();
        if (active.file != null && workspace != null && workspace.contains(active.file)) {
            File f = active.file;
            while (f != null && !f.equals(workspace.root)) {
                parts.add(0, f.getName());
                files.add(0, f);
                f = f.getParentFile();
            }
        } else {
            parts.add(active.name);
            files.add(active.file);
        }
        // The symbol at the caret, as in VS Code's breadcrumbs (file > class > method).
        if (active.text.length() < 4000000) {
            int caretLine = active.text.lineOfOffset(active.engine.sel.caret());
            List<com.disgusty.droid_codology.lang.Symbols.Symbol> syms = symbols(active);
            ArrayList<com.disgusty.droid_codology.lang.Symbols.Symbol> chain = new ArrayList<com.disgusty.droid_codology.lang.Symbols.Symbol>();
            for (com.disgusty.droid_codology.lang.Symbols.Symbol s : syms) {
                if (s.line <= caretLine && caretLine <= s.endLine) {
                    while (!chain.isEmpty() && chain.get(chain.size() - 1).endLine < s.line) chain.remove(chain.size() - 1);
                    chain.add(s);
                }
            }
            for (com.disgusty.droid_codology.lang.Symbols.Symbol s : chain) {
                parts.add(s.name);
                files.add(null);
            }
        }
        View b = skin.breadcrumbs(parts, new Skin.BreadcrumbListener() {
            @Override
            public void onCrumb(int index, View anchor) {
                File f = files.get(index);
                if (f == null || workspace == null) return;
                File dir = f.getParentFile();
                ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
                for (final File sib : workspace.list(dir)) {
                    m.add(new Skin.MenuItem((sib.isDirectory() ? "📁 " : "") + sib.getName(), new Runnable() {
                        @Override
                        public void run() {
                            if (sib.isDirectory()) {
                                showSideView(0);
                                setSideBarVisible(true);
                                explorer.reveal(sib);
                            } else openFile(sib, false, -1, 0, 0);
                        }
                    }));
                }
                skin.popup(anchor, m);
            }
        });
        HorizontalScrollView hs = new HorizontalScrollView(activity);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(b, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, skin.dp(24)));
        hs.setBackgroundColor(editor.colors().background);
        breadcrumbsHolder.addView(hs);
    }

    // ------------------------------------------------------------------ documents

    public List<Document> documents() {
        return docs;
    }

    public Document activeDocument() {
        return active;
    }

    public void activate(Document d) {
        if (d == active) {
            editor.showKeyboard();
            return;
        }
        showDocument(d);
        refreshTabs();
        updateStatus();
    }

    private void showDocument(Document d) {
        boolean changed = d != active;
        active = d;
        dismissSelectionMenu();
        if (changed) {
            extHost.onActiveChanged(d);
            if (chatView != null) chatView.onEditorChanged();
            lsp.onActive(d);
        }
        editor.setDiagnostics(d == null ? null : d.allDiagnostics());
        if (d == null) {
            editor.setDocument(null);
            editor.setVisibility(View.GONE);
            if (welcome != null) welcome.setVisibility(View.VISIBLE);
            if (find != null) find.hide();
            updateStatus();
            return;
        }
        editor.setVisibility(View.VISIBLE);
        if (welcome != null) welcome.setVisibility(View.GONE);
        boolean revealCaret = d.scrollY < 0;
        editor.setDocument(d);
        if (revealCaret) {
            editor.post(new Runnable() {
                @Override
                public void run() {
                    editor.ensureCaretVisible();
                    editor.invalidate();
                }
            });
        }
        editor.setTabSize(d.engine.tabSize);
        editor.readOnlyView = d.readOnly;
        if (find != null) find.onDocumentChanged();
        updateStatus();
    }

    public void openFile(final File f, final boolean preview, final int line, final int col, final int selLen) {
        for (Document d : docs) {
            if (d.file != null && d.file.equals(f)) {
                if (!preview) d.preview = false;
                activate(d);
                select(d, line, col, selLen);
                closeOverlaySideBar();
                return;
            }
        }
        final long size = f.length();
        if (size > 64L * 1024 * 1024) {
            showMessage(f("Unable to open '{0}': {1}", f.getName(), "> 64 MB"));
            return;
        }
        final boolean sync = size < 256 * 1024;
        Runnable load = new Runnable() {
            @Override
            public void run() {
                try {
                    final Document d = Document.open(activity, f, null);
                    Runnable deliver = new Runnable() {
                        @Override
                        public void run() {
                            d.preview = preview;
                            addDocument(d);
                            select(d, line, col, selLen);
                            closeOverlaySideBar();
                            if (d.binary) showMessage(t("The file is not displayed in the text editor because it is either binary or uses an unsupported text encoding."));
                        }
                    };
                    // Small files open synchronously, so commands that follow see the new editor.
                    if (sync) deliver.run();
                    else ui.post(deliver);
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showMessage(f("Unable to open '{0}': {1}", f.getName(), String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        };
        if (sync) load.run();
        else {
            showMessage(f("Opening {0}...", f.getName()));
            bg.execute(load);
        }
    }

    public void openUri(final Uri u) {
        if ("file".equals(u.getScheme()) && u.getPath() != null) {
            openFile(new File(u.getPath()), false, -1, 0, 0);
            return;
        }
        for (Document d : docs) {
            if (u.equals(d.uri)) {
                activate(d);
                return;
            }
        }
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Document d = Document.open(activity, u, null);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            addDocument(d);
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showMessage(f("Unable to open '{0}': {1}", u.getLastPathSegment(), String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        });
    }

    void addDocument(Document d) {
        applyDocPrefs(d);
        // A preview tab is replaced by the next preview (VS Code's workbench.editor.enablePreview).
        int insertAt = docs.indexOf(active) + 1;
        if (d.preview) {
            for (int i = 0; i < docs.size(); i++) {
                Document x = docs.get(i);
                // Not one the other split pane still shows: it would stay editable there without a tab.
                if (x.preview && !x.isDirty() && (otherEditor == null || otherEditor.document() != x)) {
                    docs.remove(i);
                    // The same cleanup as closing it (language servers and extensions see it close).
                    x.highlighter.detach();
                    extHost.onClosed(x);
                    lsp.onClosed(x);
                    insertAt = i;
                    break;
                }
            }
        }
        if (insertAt <= 0 || insertAt > docs.size()) insertAt = docs.size();
        docs.add(insertAt, d);
        showDocument(d);
        refreshTabs();
    }

    public void newUntitled(String content) {
        TextBuffer tb = content == null ? new TextBuffer() : TextBuffer.of(content);
        Document d = new Document(tb, f("Untitled-{0}", untitledCounter));
        d.untitledIndex = untitledCounter++;
        // Shared text exists nowhere else: unsaved, so closing asks and hot exit keeps it.
        if (content != null && !content.isEmpty()) d.markUnsaved();
        addDocument(d);
        editor.showKeyboard();
    }

    private void select(Document d, int line, int col, int selLen) {
        if (line < 0) return;
        int l = Math.min(line, d.text.lineCount() - 1);
        int off = Math.min(d.text.lineStart(l) + col, d.text.lineEnd(l));
        d.engine.setSelection(off, Math.min(d.text.length(), off + selLen));
        if (d == active) {
            editor.revealLine(l);
            editor.ensureCaretVisible();
        }
    }

    public void close(final Document d, final Runnable after) {
        if (d.isDirty()) {
            Skin.DialogSpec s = new Skin.DialogSpec();
            s.title = f("Do you want to save the changes you made to {0}?", d.name);
            s.message = t("Your changes will be lost if you don't save them.");
            s.positive = t("Save");
            s.negative = t("Cancel");
            s.neutral = t("Don't Save");
            s.onPositive = new Runnable() {
                @Override
                public void run() {
                    save(d, new Runnable() {
                        @Override
                        public void run() {
                            remove(d);
                            if (after != null) after.run();
                        }
                    });
                }
            };
            s.onNeutral = new Runnable() {
                @Override
                public void run() {
                    remove(d);
                    if (after != null) after.run();
                }
            };
            skin.dialog(s).show();
            return;
        }
        remove(d);
        if (after != null) after.run();
    }

    private void closeMany(final List<Document> list) {
        if (list.isEmpty()) return;
        final Document first = list.remove(0);
        close(first, new Runnable() {
            @Override
            public void run() {
                closeMany(list);
            }
        });
    }

    private void remove(Document d) {
        int i = docs.indexOf(d);
        if (i < 0) return;
        docs.remove(i);
        if (d.file != null) {
            closedPaths.remove(d.file.getAbsolutePath());
            closedPaths.add(d.file.getAbsolutePath());
        }
        d.highlighter.detach();
        extHost.onClosed(d);
        lsp.onClosed(d);
        if (otherEditor != null && otherEditor.document() == d) {
            if (docs.isEmpty()) joinPanes();
            else otherEditor.setDocument(docs.get(Math.min(i, docs.size() - 1)));
        }
        if (d == active) {
            Document next = docs.isEmpty() ? null : docs.get(Math.min(i, docs.size() - 1));
            showDocument(next);
        }
        refreshTabs();
        updateStatus();
    }

    public void save(final Document d, final Runnable after) {
        save(d, after, false, false);
    }

    private boolean askingOverwrite;

    /**
     * {@code auto}: an automatic save (no whitespace trimming under the caret, no questions: a file
     * changed on disk is left alone). {@code overwrite}: the user agreed to replace a file that
     * changed on disk.
     */
    boolean save(final Document d, final Runnable after, boolean auto, boolean overwrite) {
        if (d == null) return false;
        if (d.binary) {
            // Its text is a lossy decoding: writing it back would destroy the file.
            if (!auto) showMessage(t("Binary files can't be saved from the editor."));
            return false;
        }
        if (d.isUntitled()) {
            if (!auto) saveAs(d, after);
            return false;
        }
        if (d.readOnly) {
            if (!auto) showMessage(f("'{0}' is read-only; use Save As to save a copy.", d.name));
            return false;
        }
        if (!overwrite && d.changedOnDisk()) {
            if (auto || askingOverwrite) return false;
            askingOverwrite = true;
            Skin.DialogSpec s = new Skin.DialogSpec();
            s.title = t("File changed on disk");
            s.message = f("'{0}' was changed outside the editor since it was opened (by Git, the terminal or another app). "
                    + "Replace it with your version?", d.name);
            s.positive = t("Overwrite");
            s.negative = t("Cancel");
            s.onPositive = new Runnable() {
                @Override
                public void run() {
                    askingOverwrite = false;
                    save(d, after, false, true);
                }
            };
            s.onNegative = new Runnable() {
                @Override
                public void run() {
                    askingOverwrite = false;
                }
            };
            android.app.Dialog dlg = skin.dialog(s);
            dlg.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(android.content.DialogInterface di) {
                    askingOverwrite = false;
                }
            });
            dlg.show();
            return false;
        }
        // Trimming on autosave would eat the space the user just typed before the next word.
        if (!auto && prefs.b(Prefs.TRIM_WS, false)) d.engine.trimTrailingWhitespace();
        if (prefs.b(Prefs.FINAL_NEWLINE, false) && d.text.length() > 0 && d.text.charAt(d.text.length() - 1) != '\n') {
            int caretA = d.engine.sel.anchor(0), caretH = d.engine.sel.head(0);
            d.engine.replace(d.text.length(), d.text.length(), "\n");
            d.engine.setSelection(caretA, caretH);
        }
        try {
            d.save(activity);
            d.preview = false;
            extHost.onSaved(d);
            lsp.onSaved(d);
            if (workspace != null) scheduleScmRefresh();
            refreshTabs();
            if (workspace != null && d.file != null && workspace.contains(d.file)) workspace.invalidateIndex();
            if (after != null) after.run();
            return true;
        } catch (Document.Unencodable e) {
            if (auto) return false;
            Skin.DialogSpec s = new Skin.DialogSpec();
            s.title = t("Save as UTF-8?");
            s.message = f("'{0}' has characters that {1} can't store; they would be lost. Save the file as UTF-8 instead?", d.name, d.charset);
            s.positive = t("Save as UTF-8");
            s.negative = t("Cancel");
            s.onPositive = new Runnable() {
                @Override
                public void run() {
                    d.charset = "UTF-8";
                    d.bom = false;
                    save(d, after, false, true);
                    updateStatus();
                }
            };
            skin.dialog(s).show();
        } catch (Exception e) {
            showMessage(f("Failed to save '{0}': {1}", d.name, String.valueOf(e.getMessage())));
        } catch (OutOfMemoryError e) {
            showMessage(f("Failed to save '{0}': {1}", d.name, t("not enough memory")));
        }
        return false;
    }

    public void saveAs(final Document d, final Runnable after) {
        File start = d.file != null ? d.file.getParentFile() : workspace != null ? workspace.root : null;
        String name = d.isUntitled() ? "untitled.txt" : d.name;
        FolderPicker.pick(this, t("Save As..."), start, name, new FolderPicker.Callback() {
            @Override
            public void picked(File dir, String fileName) {
                final File target = new File(dir, fileName);
                // Another tab already has this file: two documents on one file overwrite each other.
                for (Document o : docs) {
                    if (o != d && o.file != null && Agent.sameFile(o.file, target)) {
                        showMessage(f("'{0}' is open in another tab; close it first or choose another name.", target.getName()));
                        return;
                    }
                }
                if (target.exists() && !(d.file != null && Agent.sameFile(d.file, target))) {
                    Skin.DialogSpec s = new Skin.DialogSpec();
                    s.title = t("Replace File?");
                    s.message = f("'{0}' already exists in {1}. Replace it?", target.getName(), dir.getPath());
                    s.positive = t("Replace");
                    s.negative = t("Cancel");
                    s.destructive = true;
                    s.onPositive = new Runnable() {
                        @Override
                        public void run() {
                            saveTo(d, target, after);
                        }
                    };
                    skin.dialog(s).show();
                    return;
                }
                saveTo(d, target, after);
            }
        });
    }

    /** Points {@code d} at {@code target} and saves; on failure it keeps pointing where it did. */
    private void saveTo(Document d, File target, Runnable after) {
        File oldFile = d.file;
        android.net.Uri oldUri = d.uri;
        String oldName = d.name;
        boolean oldRo = d.readOnly;
        d.file = target;
        d.uri = null;
        d.name = target.getName();
        d.readOnly = false;
        if (!save(d, after, false, true)) {
            // The write failed: the document still belongs to its old file (or URI) and can be saved there.
            d.file = oldFile;
            d.uri = oldUri;
            d.name = oldName;
            d.readOnly = oldRo;
            refreshTabs();
            return;
        }
        Language l = Languages.forFile(d.name, d.text.lineText(0));
        if (l != Languages.PLAIN || d.language == Languages.PLAIN) d.setLanguage(l);
        refreshTabs();
        if (workspace != null) explorer.refresh();
    }

    public void saveAll() {
        for (Document d : new ArrayList<Document>(docs)) if (d.isDirty() && !d.isUntitled()) save(d, null);
    }

    // ------------------------------------------------------------------ folders

    public void openFolder(File dir) {
        if (!dir.isDirectory()) {
            showMessage(f("Unable to open '{0}': {1}", dir.getPath(), "not a folder"));
            return;
        }
        workspace = new Workspace(dir);
        workspace.showHidden = prefs.b(Prefs.SHOW_HIDDEN, false);
        prefs.put(Prefs.FOLDER, dir.getAbsolutePath());
        addRecent(dir.getAbsolutePath());
        if (explorer != null) explorer.setWorkspace(workspace);
        if (search != null) search.setWorkspace(workspace);
        showSideView(0);
        setSideBarVisible(true);
        scheduleScmRefresh();
        refreshTitle();
        updateStatus();
        refreshBreadcrumbs();
        bg.execute(new Runnable() {
            @Override
            public void run() {
                Workspace w = workspace;
                if (w != null) w.buildIndex();
            }
        });
    }

    /** Opens a folder at startup without popping the side bar open. */
    void openFolderQuiet(File dir) {
        workspace = new Workspace(dir);
        workspace.showHidden = prefs.b(Prefs.SHOW_HIDDEN, false);
        final Workspace w = workspace;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                w.buildIndex();
            }
        });
    }

    public void closeFolder() {
        workspace = null;
        prefs.put(Prefs.FOLDER, null);
        explorer.setWorkspace(null);
        search.setWorkspace(null);
        refreshTitle();
        updateStatus();
        rebuildWelcome();
    }

    private void rebuildWelcome() {
        if (editorArea == null) return;
        int i = editorArea.indexOfChild(welcome);
        editorArea.removeView(welcome);
        welcome = buildWelcome();
        editorArea.addView(welcome, i, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        welcome.setVisibility(active == null ? View.VISIBLE : View.GONE);
    }

    public List<String> recentFolders() {
        String s = prefs.str(Prefs.RECENT, "");
        ArrayList<String> l = new ArrayList<String>();
        for (String p : s.split("\n")) if (!p.isEmpty() && new File(p).isDirectory()) l.add(p);
        return l;
    }

    private void addRecent(String path) {
        List<String> l = recentFolders();
        l.remove(path);
        l.add(0, path);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(12, l.size()); i++) sb.append(l.get(i)).append('\n');
        prefs.put(Prefs.RECENT, sb.toString());
    }

    // ------------------------------------------------------------------ pickers (status bar)

    void pickLanguage() {
        final Document d = active;
        if (d == null) return;
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        for (final Language l : Languages.all()) {
            QuickPick.Item it = new QuickPick.Item(l.name, l.id, new Runnable() {
                @Override
                public void run() {
                    d.setLanguage(l);
                    editor.invalidate();
                    updateStatus();
                }
            });
            if (l == d.language) it.detail = "✓";
            items.add(it);
        }
        quickPick.showItems(t("Select Language Mode"), items);
    }

    void pickEol() {
        final Document d = active;
        if (d == null) return;
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        String[][] opts = {{"LF", TextFiles.LF}, {"CRLF", TextFiles.CRLF}, {"CR", TextFiles.CR}};
        for (final String[] o : opts) {
            items.add(new QuickPick.Item(o[0], null, new Runnable() {
                @Override
                public void run() {
                    if (!d.eol.equals(o[1])) {
                        d.eol = o[1];
                        // Changing the line ending is an edit: mark dirty via a no-op undoable change.
                        markDirtyForMetadata(d);
                    }
                    updateStatus();
                }
            }));
        }
        quickPick.showItems(t("Select End of Line Sequence"), items);
    }

    /** Metadata changes (EOL, encoding) make the file need saving without touching the text. */
    private void markDirtyForMetadata(Document d) {
        d.metaDirty = true;
        refreshTabs();
    }

    void pickIndentation() {
        final Document d = active;
        if (d == null) return;
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        for (final int n : new int[]{2, 4, 8}) {
            items.add(new QuickPick.Item(t("Indent Using Spaces") + ": " + n, null, new Runnable() {
                @Override
                public void run() {
                    d.engine.insertSpaces = true;
                    d.engine.tabSize = n;
                    editor.setTabSize(n);
                    updateStatus();
                }
            }));
        }
        for (final int n : new int[]{2, 4, 8}) {
            items.add(new QuickPick.Item(t("Indent Using Tabs") + ": " + n, null, new Runnable() {
                @Override
                public void run() {
                    d.engine.insertSpaces = false;
                    d.engine.tabSize = n;
                    editor.setTabSize(n);
                    updateStatus();
                }
            }));
        }
        items.add(new QuickPick.Item(t("Convert Indentation to Spaces"), null, new Runnable() {
            @Override
            public void run() {
                convertIndentation(d, true);
            }
        }));
        items.add(new QuickPick.Item(t("Convert Indentation to Tabs"), null, new Runnable() {
            @Override
            public void run() {
                convertIndentation(d, false);
            }
        }));
        quickPick.showItems(t("Select Indentation"), items);
    }

    private void convertIndentation(Document d, boolean toSpaces) {
        EditEngine e = d.engine;
        int ts = e.tabSize;
        e.begin(com.disgusty.droid_codology.text.UndoStack.KIND_OTHER);
        for (int l = d.text.lineCount() - 1; l >= 0; l--) {
            String ws = e.leadingWhitespace(l);
            if (ws.isEmpty()) continue;
            int cols = 0;
            for (int i = 0; i < ws.length(); i++) cols = ws.charAt(i) == '\t' ? cols + ts - cols % ts : cols + 1;
            StringBuilder sb = new StringBuilder();
            if (toSpaces) for (int i = 0; i < cols; i++) sb.append(' ');
            else {
                for (int i = 0; i < cols / ts; i++) sb.append('\t');
                for (int i = 0; i < cols % ts; i++) sb.append(' ');
            }
            if (!sb.toString().equals(ws)) e.replace(d.text.lineStart(l), d.text.lineStart(l) + ws.length(), sb.toString());
        }
        e.end();
        e.insertSpaces = toSpaces;
        updateStatus();
    }

    static final String[] ENCODINGS = {"UTF-8", "UTF-16LE", "UTF-16BE", "windows-1251", "KOI8-R", "IBM866", "ISO-8859-5",
            "windows-1252", "ISO-8859-1", "windows-1250", "ISO-8859-2", "windows-1253", "windows-1254", "windows-1255",
            "windows-1256", "windows-1257", "windows-1258", "Shift_JIS", "EUC-JP", "GBK", "GB18030", "Big5", "EUC-KR",
            "KOI8-U", "x-MacCyrillic", "US-ASCII"};

    void pickEncoding() {
        final Document d = active;
        if (d == null) return;
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        if (d.file != null || d.uri != null) {
            items.add(new QuickPick.Item(t("Reopen with Encoding"), null, new Runnable() {
                @Override
                public void run() {
                    chooseEncoding(d, true);
                }
            }));
        }
        items.add(new QuickPick.Item(t("Save with Encoding"), null, new Runnable() {
            @Override
            public void run() {
                chooseEncoding(d, false);
            }
        }));
        quickPick.showItems(t("Change File Encoding"), items);
    }

    private void chooseEncoding(final Document d, final boolean reopen) {
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        ArrayList<String> encs = new ArrayList<String>(Arrays.asList(ENCODINGS));
        for (final String enc : encs) {
            if (!java.nio.charset.Charset.isSupported(enc)) continue;
            QuickPick.Item it = new QuickPick.Item(enc, enc.equalsIgnoreCase(d.charset) ? "✓" : null, new Runnable() {
                @Override
                public void run() {
                    if (reopen) {
                        Runnable reload = new Runnable() {
                            @Override
                            public void run() {
                                String was = d.charset;
                                d.charset = enc;
                                try {
                                    d.reload(activity);
                                    d.charset = enc;
                                } catch (Exception e) {
                                    // The text is still the old decoding: saving must use the old charset.
                                    d.charset = was;
                                    showMessage(f("Unable to open '{0}': {1}", d.name, String.valueOf(e.getMessage())));
                                }
                                editor.invalidate();
                                updateStatus();
                            }
                        };
                        if (d.isDirty()) {
                            confirm(t("Reopen with Encoding"), f("'{0}' has unsaved changes. Discard them and reopen the file as {1}?", d.name, enc),
                                    t("Discard and Reopen"), reload);
                        } else reload.run();
                        return;
                    } else {
                        d.charset = enc;
                        d.bom = false;
                        markDirtyForMetadata(d);
                    }
                    updateStatus();
                }
            });
            items.add(it);
        }
        quickPick.showItems(reopen ? t("Reopen with Encoding") : t("Save with Encoding"), items);
    }

    // ------------------------------------------------------------------ messages & prompts

    public void showMessage(CharSequence msg) {
        skin.toast(root, msg);
    }

    public void copyText(String s) {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("path", s));
    }

    public interface TextCallback {
        void onText(String s);
    }

    /** A one-field input dialog (new file name, rename...). Selects the name part like VS Code. */
    public void prompt(CharSequence title, String hint, String initial, final TextCallback cb) {
        final EditText input = skin.input(hint);
        input.setText(initial == null ? "" : initial);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (initial != null) {
            int dot = initial.lastIndexOf('.');
            input.setSelection(0, dot > 0 ? dot : initial.length());
        }
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = title;
        s.content = input;
        s.positive = t("OK");
        s.negative = t("Cancel");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                String v = input.getText().toString().trim();
                if (!v.isEmpty()) cb.onText(v);
            }
        };
        final Dialog d = skin.dialog(s);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                d.dismiss();
                String val = input.getText().toString().trim();
                if (!val.isEmpty()) cb.onText(val);
                return true;
            }
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        input.requestFocus();
    }

    public void confirm(CharSequence title, CharSequence message, String ok, final Runnable yes) {
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = title;
        s.message = message;
        s.positive = ok;
        s.negative = t("Cancel");
        s.onPositive = yes;
        skin.dialog(s).show();
    }

    // ------------------------------------------------------------------ CodeView.Host

    @Override
    public boolean onEditorKey(CodeView v, int keyCode, KeyEvent e) {
        return false;
    }

    private final Runnable crumbRefresh = new Runnable() {
        @Override
        public void run() {
            refreshBreadcrumbs();
            if (outline != null && sideView == OUTLINE_VIEW) outline.refresh();
        }
    };

    @Override
    public void onCaretMoved(CodeView v) {
        updateStatus();
        if (extHost.running() || chatView != null) {
            ui.removeCallbacks(selectionToHost);
            ui.postDelayed(selectionToHost, 200);
        }
        ui.removeCallbacks(crumbRefresh);
        ui.postDelayed(crumbRefresh, 350);
        Document d = active;
        if (d == null) return;
        // Snippet stops with choices (${1|a,b|}) open as a suggestion list.
        String[] ch = d.engine.snippet.currentChoices();
        int caret = d.engine.sel.caret();
        if (ch != null && choicesShownFor != caret) {
            choicesShownFor = caret;
            final String[] fch = ch;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    completion.showChoices(fch);
                }
            });
        } else if (completion.isShowing() && ch == null) {
            int ws = d.engine.wordStart(caret);
            if (ws == caret || !d.engine.sel.isEmpty(d.engine.sel.primary())) completion.dismiss();
        }
    }

    private boolean lastDirty;

    @Override
    public void onTextChanged(CodeView v) {
        Document d = active;
        if (d == null) return;
        boolean dirty = d.isDirty();
        if (d.preview) {
            d.preview = false;
            refreshTabs();
        } else if (dirty != lastDirty) refreshTabs();
        lastDirty = dirty;
        if (find != null) find.onTextChanged();
        if (preview != null && preview.document() == d) {
            ui.removeCallbacks(previewUpdate);
            ui.postDelayed(previewUpdate, 400);
        }
        String typed = d.engine.lastTyped;
        d.engine.lastTyped = null;
        if (typed != null && typed.length() == 1 && EditEngine.isWordChar(typed.charAt(0)) && prefs.b(Prefs.SUGGEST, true)) completion.onTyped();
        else if (typed != null && prefs.b(Prefs.SUGGEST, true) && (lsp.isTrigger(d, typed) || typed.equals(".") && extHost.running())) {
            completion.onTriggerChar(typed);
        } else if (completion.isShowing()) completion.onTyped();
        scheduleAutoSave();
    }

    private final Runnable autoSave = new Runnable() {
        @Override
        public void run() {
            for (Document d : docs) if (d.isDirty() && !d.isUntitled() && !d.readOnly) save(d, null, true, false);
        }
    };

    private void scheduleAutoSave() {
        scheduleSessionSave();
        if (!"afterDelay".equals(prefs.str(Prefs.AUTO_SAVE, "off"))) return;
        ui.removeCallbacks(autoSave);
        ui.postDelayed(autoSave, 1000);
    }

    private final Runnable sessionSave = new Runnable() {
        @Override
        public void run() {
            Session.save(Workbench.this);
        }
    };

    /** Hot-exit backups are written shortly after changes: the process can be killed without onPause. */
    void scheduleSessionSave() {
        ui.removeCallbacks(sessionSave);
        ui.postDelayed(sessionSave, 2000);
    }

    @Override
    public void onSelectionMenu(CodeView v, boolean show, float x, float y) {
        dismissSelectionMenu();
        if (!show || active == null) return;
        ArrayList<Skin.MenuItem> items = new ArrayList<Skin.MenuItem>();
        boolean hasSel = active.engine.sel.anyNonEmpty();
        boolean ro = active.readOnly;
        if (hasSel && !ro) items.add(new Skin.MenuItem(t("Cut"), new Runnable() {
            @Override
            public void run() {
                editor.copy(true);
                editor.hideHandles();
            }
        }));
        if (hasSel) items.add(new Skin.MenuItem(t("Copy"), new Runnable() {
            @Override
            public void run() {
                editor.copy(false);
                editor.hideHandles();
            }
        }));
        if (!ro) items.add(new Skin.MenuItem(t("Paste"), new Runnable() {
            @Override
            public void run() {
                editor.paste();
                editor.hideHandles();
            }
        }));
        items.add(new Skin.MenuItem(t("Select All"), new Runnable() {
            @Override
            public void run() {
                editor.selectAllTouch();
            }
        }));
        if (hasSel) items.add(new Skin.MenuItem(t("Find"), new Runnable() {
            @Override
            public void run() {
                commands.run("actions.find");
            }
        }));
        if (hasSel) items.add(new Skin.MenuItem("✦ AI", new Runnable() {
            @Override
            public void run() {
                editor.hideHandles();
                ArrayList<QuickPick.Item> ai = new ArrayList<QuickPick.Item>();
                for (final String id : new String[]{"inlineChat.start", "ai.explainSelection", "ai.fixSelection", "ai.writeTests"}) {
                    Commands.Command c = commands.get(id);
                    if (c == null) continue;
                    ai.add(new QuickPick.Item(c.title, null, new Runnable() {
                        @Override
                        public void run() {
                            commands.run(id);
                        }
                    }));
                }
                quickPick.showItems("✦ AI", ai);
            }
        }));
        View strip = skin.actionStrip(items, new Runnable() {
            @Override
            public void run() {
                dismissSelectionMenu();
            }
        });
        strip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        selectionMenu = new PopupWindow(strip, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        selectionMenu.setClippingEnabled(true);
        if (Build.VERSION.SDK_INT >= 21) selectionMenu.setElevation(skin.dp(6));
        int[] loc = new int[2];
        editor.getLocationInWindow(loc);
        int px = (int) Math.max(skin.dp(4), Math.min(loc[0] + x - strip.getMeasuredWidth() / 2f, activity.getResources().getDisplayMetrics().widthPixels - strip.getMeasuredWidth() - skin.dp(4)));
        int py = (int) (loc[1] + y - strip.getMeasuredHeight() - skin.dp(8));
        if (py < loc[1]) py = (int) (loc[1] + y + editor.lineHeight() + skin.dp(28));
        selectionMenu.showAtLocation(editor, Gravity.LEFT | Gravity.TOP, px, py);
    }

    void dismissSelectionMenu() {
        if (selectionMenu != null) {
            selectionMenu.dismiss();
            selectionMenu = null;
        }
    }

    @Override
    public void onZoom(CodeView v, float sizeSp) {
        prefs.put(Prefs.FONT_SIZE, sizeSp);
    }

    @Override
    public boolean interceptTyped(CodeView v, String s) {
        if (completion.isShowing() && (s.equals("\n") || s.equals("\t"))) {
            completion.onKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            return true;
        }
        return extraKeys != null && extraKeys.modifierLatched() && extraKeys.consumeTyped(s);
    }

    // ------------------------------------------------------------------ keys from the activity

    /** Called by the activity before normal dispatch; returns true if a command consumed the key. */
    private static final java.util.HashSet<String> TERMINAL_PASSTHROUGH = new java.util.HashSet<String>(Arrays.asList(
            "ctrl+shift+p", "ctrl+p", "ctrl+`", "ctrl+shift+`", "ctrl+shift+e", "ctrl+shift+f", "ctrl+shift+g", "ctrl+shift+x",
            "ctrl+b", "ctrl+tab", "ctrl+shift+tab", "f1", "ctrl+shift+c", "ctrl+shift+v", "ctrl+alt+i"));

    private static final java.util.Set<String> TEXT_FIELD_KEYS = new java.util.HashSet<String>(Arrays.asList(
            "ctrl+z", "ctrl+y", "ctrl+shift+z", "ctrl+a", "ctrl+c", "ctrl+x", "ctrl+v", "ctrl+backspace", "ctrl+delete",
            "ctrl+left", "ctrl+right", "shift+left", "shift+right", "home", "end", "shift+home", "shift+end"));

    private boolean overlayOpen() {
        return settingsPage != null || accountsView != null || extensionPage != null || !webviewPanels.isEmpty();
    }

    public boolean dispatchKey(KeyEvent e) {
        if (lock.isLocked()) return false; // keys go to the password field only
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (quickPick != null && quickPick.getVisibility() == View.VISIBLE && quickPick.onKey(e)) return true;
        if (completion.isShowing() && completion.onKey(e)) return true;
        View focus = activity.getCurrentFocus();
        if (focus instanceof com.disgusty.droid_codology.term.TerminalView) {
            // The shell gets every key except a few workbench chords (VS Code's "commands to skip shell").
            String chord = Commands.chordOf(e);
            if (chord == null || !TERMINAL_PASSTHROUGH.contains(chord)) return false;
        }
        if (focus instanceof EditText) {
            // Text-editing keys belong to the focused field (find box, palette, chat input), not to
            // the document behind it: Ctrl+Z there must not undo an edit in the file.
            String chord = Commands.chordOf(e);
            if (chord != null && TEXT_FIELD_KEYS.contains(chord)) return false;
        }
        int r = commands.onKey(e);
        if (r == Commands.PREFIX || r == Commands.RAN) {
            updateStatus();
            return true;
        }
        // A full-screen page (settings, accounts, extension, webview panel) covers the editor:
        // typing must not edit the invisible document behind it.
        return overlayOpen() && focus == editor && e.getKeyCode() != KeyEvent.KEYCODE_BACK;
    }

    public boolean onBack() {
        if (lock.isLocked()) return false; // to the background, like Home
        if (quickPick != null && quickPick.getVisibility() == View.VISIBLE) {
            quickPick.dismiss();
            return true;
        }
        if (!webviewPanels.isEmpty()) {
            closeWebviewPanel(webviewPanels.get(webviewPanels.size() - 1), true);
            return true;
        }
        if (extensionPage != null) {
            closeExtensionPage();
            return true;
        }
        if (settingsPage != null) {
            closeSettings();
            return true;
        }
        if (accountsView != null) {
            closeAccounts();
            return true;
        }
        if (preview != null && narrow) {
            closePreview();
            return true;
        }
        if (drawer != null && drawer.isOpen()) {
            drawer.close();
            return true;
        }
        if (find != null && find.getVisibility() == View.VISIBLE) {
            find.hide();
            return true;
        }
        if (completion.isShowing()) {
            completion.dismiss();
            return true;
        }
        if (selectionMenu != null || editor.isTouchSelecting()) {
            dismissSelectionMenu();
            editor.hideHandles();
            return true;
        }
        return false;
    }

    boolean editorFocused() {
        return active != null && editor.hasFocus();
    }

    // ------------------------------------------------------------------ settings page

    public void openSettings() {
        openSettings(null);
    }

    /** Settings scrolled to (and highlighting) the item {@code focus}, one of SettingsPage.FOCUS_*. */
    void openSettings(String focus) {
        if (settingsPage != null) return;
        settingsPage = new SettingsPage(this).build(focus);
        root.addView(settingsPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        editor.hideKeyboard();
    }

    public void closeSettings() {
        if (settingsPage == null) return;
        root.removeView(settingsPage);
        settingsPage = null;
    }

    // ------------------------------------------------------------------ extension webviews

    /** Open webview panels, the last one on top. */
    private final ArrayList<ExtWebview> webviewPanels = new ArrayList<ExtWebview>();

    /** Shows (or brings to the front) an extension's webview panel as a full-screen page. */
    void showWebviewPanel(final ExtWebview w) {
        if (w.page == null) {
            w.page = new LinearLayout(activity);
            w.page.setOrientation(LinearLayout.VERTICAL);
            w.page.setBackgroundColor(skin.windowBg);
            w.page.setClickable(true);
            w.page.addView(webviewTitleBar(w));
            w.page.addView(w.web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        webviewPanels.remove(w);
        webviewPanels.add(w);
        if (w.page.getParent() == null) {
            root.addView(w.page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            w.page.bringToFront();
        }
        editor.hideKeyboard();
    }

    private View webviewTitleBar(final ExtWebview w) {
        Skin.Action back = new Skin.Action("arrow_left", t("Close"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeWebviewPanel(w, true);
            }
        });
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("refresh", t("Reload"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                w.web.reload();
            }
        }));
        com.disgusty.droid_codology.ext.Extension e = extensions.find(w.extId);
        return skin.titleBar(back, w.title, e != null ? e.displayName : w.extId, acts, null);
    }

    void updateWebviewPanelTitle(ExtWebview w) {
        if (w.page == null) return;
        View old = w.page.getChildAt(0);
        w.page.removeViewAt(0);
        w.page.addView(webviewTitleBar(w), 0, old.getLayoutParams());
    }

    /** {@code byUser}: closed in the UI, so the extension is told (onDidDispose). */
    void closeWebviewPanel(ExtWebview w, boolean byUser) {
        webviewPanels.remove(w);
        if (w.page != null) root.removeView(w.page);
        if (byUser) extHost.webviewClosed(w);
    }

    void onWebviewViewRegistered(String viewId) {
        for (ExtViewContainer v : extViews.values()) v.onWebviewRegistered(viewId);
    }

    void onWebviewViewCreated(ExtWebview w) {
        boolean shown = false;
        for (ExtViewContainer v : extViews.values()) shown |= v.attachWebview(w);
        // Nobody shows it (the container was closed meanwhile): let the extension know.
        if (!shown) extHost.webviewClosed(w);
    }

    /** {@code message} null: the view's webview went away (it can be resolved again). */
    void onWebviewViewUnavailable(String viewId, String message) {
        for (ExtViewContainer v : extViews.values()) v.webviewUnavailable(viewId, message);
    }

    // ------------------------------------------------------------------ split editor

    private View splitDivider;
    private boolean splitBottom;

    boolean isSplit() {
        return otherEditor != null;
    }

    /** Split Editor (Ctrl+\): a second pane, side by side (stacked on narrow screens). */
    void splitEditor() {
        if (active == null || editorArea == null) return;
        if (otherEditor != null) {
            otherEditor.requestFocus();
            return;
        }
        closePreview();
        CodeView v = new CodeView(activity);
        v.setHost(this);
        if (Build.VERSION.SDK_INT >= 26) com.disgusty.droid_codology.compat.Api26.noFocusHighlight(v);
        v.setColors(editor.colors());
        applyPrefsTo(v);
        v.setDocument(active);
        firstPane = editor;
        otherEditor = v;
        splitBottom = narrow;
        splitDivider = new View(activity);
        splitDivider.setBackgroundColor(skin.border);
        editorArea.addView(splitDivider);
        editorArea.addView(v);
        View.OnFocusChangeListener focus = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean has) {
                if (has && view == otherEditor) swapPanes();
            }
        };
        editor.setOnFocusChangeListener(focus);
        v.setOnFocusChangeListener(focus);
        layoutSplit();
        v.requestFocus();
    }

    private void layoutSplit() {
        if (otherEditor == null) return;
        int w = editorArea.getWidth() > 0 ? editorArea.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int h = editorArea.getHeight() > 0 ? editorArea.getHeight() : activity.getResources().getDisplayMetrics().heightPixels / 2;
        int line = Math.max(1, skin.dp(1));
        // The first pane stays where the editor always is; the second fills the other half.
        CodeView first = paneFirst(), second = first == editor ? otherEditor : editor;
        if (splitRatio < 0) splitRatio = ratioPref(SPLIT_RATIO, 0.5f, 0.15f, 0.85f);
        FrameLayout.LayoutParams a = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        FrameLayout.LayoutParams b, d;
        if (splitBottom) {
            int rest = h - Math.round(h * splitRatio); // the second pane and the divider
            a.bottomMargin = rest;
            b = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rest - line, Gravity.BOTTOM);
            d = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, line, Gravity.BOTTOM);
            d.bottomMargin = rest - line;
        } else {
            int rest = w - Math.round(w * splitRatio);
            a.rightMargin = rest;
            b = new FrameLayout.LayoutParams(rest - line, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT);
            d = new FrameLayout.LayoutParams(line, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT);
            d.rightMargin = rest - line;
        }
        first.setLayoutParams(a);
        second.setLayoutParams(b);
        splitDivider.setLayoutParams(d);
    }

    /** The pane in the first position (left / top): the one that existed before splitting. */
    private CodeView firstPane;

    private CodeView paneFirst() {
        return firstPane != null ? firstPane : editor;
    }

    /** The other pane got focus: it becomes the editor everything else talks to. */
    private void swapPanes() {
        CodeView tmp = editor;
        editor = otherEditor;
        otherEditor = tmp;
        Document d = editor.document();
        if (d != null && d != active) {
            active = d;
            dismissSelectionMenu();
            extHost.onActiveChanged(d);
            lsp.onActive(d);
            if (chatView != null) chatView.onEditorChanged();
            editor.setDiagnostics(d.allDiagnostics());
            if (find != null) find.onDocumentChanged();
            refreshTabs();
            updateStatus();
        }
    }

    /** Join All Editor Groups: back to one pane (the focused one). */
    void joinPanes() {
        if (otherEditor == null) return;
        editorArea.removeView(otherEditor);
        editorArea.removeView(splitDivider);
        otherEditor.setDocument(null);
        otherEditor = null;
        splitDivider = null;
        firstPane = null;
        editor.setOnFocusChangeListener(null);
        if (editor.getParent() == editorArea) {
            editor.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        editor.requestFocus();
    }

    void toggleSplitOrientation() {
        if (otherEditor == null) return;
        splitBottom = !splitBottom;
        layoutSplit();
    }

    private com.disgusty.droid_codology.ui.MatrixRain rain;

    /** Large surfaces become see-through so the falling code behind them shows (Settings → Falling Code). */
    private void seeThrough(EditorColors colors, boolean sideBarInLine) {
        int a = skin.dark ? 0xB4 : 0xCC;
        // windowBg stays opaque: full-screen pages (Settings, Accounts...) lie over the editor.
        if (sideBarInLine) {
            skin.sideBarBg = Skin.withAlpha(skin.sideBarBg, a);
            skin.activityBg = Skin.withAlpha(skin.activityBg, a);
        }
        skin.tabsBg = Skin.withAlpha(skin.tabsBg, a);
        skin.tabInactiveBg = Skin.withAlpha(skin.tabInactiveBg, a);
        skin.statusBg = Skin.withAlpha(skin.statusBg, a);
        skin.titleBg = Skin.withAlpha(skin.titleBg, a);
        skin.panelBg = Skin.withAlpha(skin.panelBg, a);
        skin.keysBg = Skin.withAlpha(skin.keysBg, a);
        colors.background = Skin.withAlpha(colors.background, a);
        colors.gutterBg = Skin.withAlpha(colors.gutterBg, a);
    }

    /** Another color theme was chosen while in the Matrix style: back to the style used before it. */
    private void leaveMatrix() {
        if (prefs.i(Prefs.STYLE, Skin.ORIGINAL) != Skin.MATRIX) return;
        prefs.put(Prefs.STYLE, prefs.i("state.styleBeforeMatrix", Skin.ORIGINAL));
    }

    // ------------------------------------------------------------------ Alpine Linux

    /** The optional app password (Settings → Security). */
    final AppLock lock;

    void hideKeyboardFrom(View v) {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    void onUnlocked() {
        if (active != null) editor.requestFocus();
    }

    private com.disgusty.droid_codology.linux.AlpineLinux alpine;
    private boolean installingAlpine;

    com.disgusty.droid_codology.linux.AlpineLinux alpine() {
        if (alpine == null) alpine = new com.disgusty.droid_codology.linux.AlpineLinux(activity);
        return alpine;
    }

    /** Downloads and unpacks Alpine Linux with a progress dialog; {@code then} runs once it's ready. */
    void installAlpine(final Runnable then) {
        if (installingAlpine) return;
        final com.disgusty.droid_codology.linux.AlpineLinux a = alpine();
        if (!a.supported()) {
            showMessage(t("Alpine Linux isn't available in this build or on this processor."));
            return;
        }
        installingAlpine = true;
        final TextView status = skin.text(t("Downloading Alpine Linux..."), skin.uiSp(), skin.menuFg, skin.regular());
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = f("Alpine Linux {0}", com.disgusty.droid_codology.linux.AlpineLinux.VERSION);
        s.content = status;
        s.positive = t("Hide");
        final android.app.Dialog dlg = skin.dialog(s);
        dlg.show();
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    a.install(new com.disgusty.droid_codology.linux.AlpineLinux.Progress() {
                        long last;

                        @Override
                        public void onProgress(final String what, final int percent) {
                            long now = System.currentTimeMillis();
                            if (now - last < 150 && percent < 100) return;
                            last = now;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    status.setText(what.equals("download") ? f("Downloading Alpine Linux... {0}%", percent)
                                            : what.equals("unpack") ? f("Unpacking... {0}%", percent) : t("Ready."));
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                } catch (OutOfMemoryError e) {
                    err = t("not enough memory");
                }
                // Node.js comes along (extensions, npm), downloaded by the app like Alpine itself.
                String nodeErr = null;
                if (err == null && !NodeHost.available(a)) {
                    try {
                        com.disgusty.droid_codology.linux.AlpinePackages.install(a, java.util.Arrays.asList("nodejs"), packageProgress(status, "Node.js"));
                    } catch (Exception e) {
                        nodeErr = e.getMessage() != null ? e.getMessage() : e.toString();
                    }
                }
                final String error = err, nodeError = nodeErr;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        installingAlpine = false;
                        try {
                            dlg.dismiss();
                        } catch (RuntimeException ignored) {
                        }
                        if (error != null) {
                            showMessage(f("Couldn't install Alpine Linux: {0}", error));
                            return;
                        }
                        showMessage(nodeError != null ? f("Alpine Linux is ready, but Node.js couldn't be installed: {0}", nodeError)
                                : t("Alpine Linux is ready."));
                        if (alpineModeWanted) setAlpineMode(true);
                        if (then != null) then.run();
                    }
                });
            }
        });
    }

    /** Alpine mode: terminals (and extensions, if chosen) run in Alpine Linux. Off by default. */
    boolean alpineMode() {
        com.disgusty.droid_codology.linux.AlpineLinux a = alpine();
        return prefs.b(Prefs.ALPINE_MODE, false) && a.supported() && a.installed();
    }

    /** Alpine mode was chosen before Alpine Linux was downloaded: switch once it's ready. */
    boolean alpineModeWanted;

    /** The standard / alpine switch of the menus. Without Alpine Linux, alpine opens its download in Settings. */
    void setAlpineMode(boolean on) {
        if (on == alpineMode()) return;
        com.disgusty.droid_codology.linux.AlpineLinux a = alpine();
        if (on && !a.supported()) {
            showMessage(t("Alpine Linux isn't available in this build or on this processor."));
            return;
        }
        if (on && !a.installed()) {
            alpineModeWanted = true;
            closeSettings();
            openSettings(SettingsPage.FOCUS_ALPINE);
            return;
        }
        prefs.put(Prefs.ALPINE_MODE, on);
        alpineModeWanted = false;
        // The extension host follows the mode when Node.js was chosen for it.
        if (extHost.onNode() != extHost.nodeMode()) extHost.restart();
        showMessage(on ? t("Alpine mode: new terminals run in Alpine Linux.") : t("Standard mode: new terminals run Android's shell."));
    }

    /** The mode switch at the top of the Manage and app menus. */
    private Skin.MenuItem modeItem() {
        return Skin.MenuItem.segmented(new String[]{t("standard"), t("alpine")}, alpineMode() ? 1 : 0, new Skin.SegmentListener() {
            @Override
            public void onSegment(int index) {
                setAlpineMode(index == 1);
            }
        });
    }

    /**
     * Extensions on Node.js from now on (alpine mode): turns the setting on, installs Node.js when
     * it's missing and restarts the extension host on it.
     */
    void ensureNodeHost() {
        prefs.put(Prefs.NODE_EXT_HOST, true);
        if (NodeHost.available(alpine())) {
            if (!extHost.onNode()) extHost.restart();
            return;
        }
        installPackages("Node.js", java.util.Arrays.asList("nodejs"), new Runnable() {
            @Override
            public void run() {
                if (extHost.nodeMode() && !extHost.onNode()) {
                    extHost.restart();
                    showMessage(t("Node.js is ready: extensions now run on it."));
                }
            }
        });
    }

    /** Dialog text for a package download by the app. */
    private com.disgusty.droid_codology.linux.AlpinePackages.Progress packageProgress(final TextView status, final String what) {
        return new com.disgusty.droid_codology.linux.AlpinePackages.Progress() {
            long last;

            @Override
            public void onProgress(final String phase, final int percent) {
                long now = System.currentTimeMillis();
                if (now - last < 150 && percent > 0) return;
                last = now;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(phase.equals("index") ? t("Reading the package list...")
                                : phase.equals("download") ? f("Downloading {0}... {1}%", what, percent)
                                : f("Installing {0}...", what));
                    }
                });
            }
        };
    }

    private boolean installingPackages;

    /**
     * Installs Alpine packages with the app doing the downloads (AlpinePackages): works where
     * programs inside Alpine can't get through to the network. {@code then} runs on success.
     */
    void installPackages(final String what, final List<String> names, final Runnable then) {
        if (!alpine().installed()) {
            installAlpine(new Runnable() {
                @Override
                public void run() {
                    installPackages(what, names, then);
                }
            });
            return;
        }
        if (installingPackages) {
            showMessage(t("Another installation is still running."));
            return;
        }
        installingPackages = true;
        final TextView status = skin.text(t("Reading the package list..."), skin.uiSp(), skin.menuFg, skin.regular());
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = what;
        s.content = status;
        s.positive = t("Hide");
        final android.app.Dialog dlg = skin.dialog(s);
        dlg.show();
        final com.disgusty.droid_codology.linux.AlpineLinux a = alpine();
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    com.disgusty.droid_codology.linux.AlpinePackages.install(a, names, packageProgress(status, what));
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        installingPackages = false;
                        try {
                            dlg.dismiss();
                        } catch (RuntimeException ignored) {
                        }
                        if (error != null) {
                            showMessage(f("Couldn't install {0}: {1}", what, error));
                            return;
                        }
                        showMessage(f("{0} is installed.", what));
                        if (then != null) then.run();
                    }
                });
            }
        });
    }

    /** Opens an Alpine terminal (installing it first if needed) and types {@code command} into it. */
    void runInAlpine(final String command) {
        if (!alpine().installed()) {
            installAlpine(new Runnable() {
                @Override
                public void run() {
                    runInAlpine(command);
                }
            });
            return;
        }
        showTerminal();
        if (terminalPanel == null) return;
        // In Alpine whatever the mode: this terminal exists for the command.
        terminalPanel.newSession(true);
        final com.disgusty.droid_codology.term.TerminalSession s = terminalPanel.current();
        if (s != null && command != null) {
            // after the login shell has printed its prompt
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    s.write(command + "\r");
                }
            }, 800);
        }
    }

    // ------------------------------------------------------------------ terminal

    private FrameLayout panelHolder;
    private TerminalPanel terminalPanel;
    private boolean terminalVisible, terminalMaximized;

    void showTerminal() {
        if (panelHolder == null) return;
        if (terminalPanel == null) {
            terminalPanel = new TerminalPanel(this);
            panelHolder.addView(terminalPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        terminalVisible = true;
        layoutPanel();
        editor.hideKeyboard();
        terminalPanel.view.requestFocus();
    }

    void hideTerminal() {
        terminalVisible = false;
        terminalMaximized = false;
        layoutPanel();
        if (active != null) editor.requestFocus();
    }

    void toggleTerminal() {
        if (terminalVisible && terminalPanel != null && terminalPanel.view.hasFocus()) hideTerminal();
        else showTerminal();
    }

    void toggleTerminalMaximized() {
        terminalMaximized = !terminalMaximized;
        layoutPanel();
    }

    private void layoutPanel() {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panelHolder.getLayoutParams();
        if (!terminalVisible) {
            lp.height = 0;
            lp.weight = 0;
            editorArea.setVisibility(View.VISIBLE);
        } else if (terminalMaximized) {
            lp.height = 0;
            lp.weight = 1;
            editorArea.setVisibility(View.GONE);
        } else {
            if (panelRatio < 0) panelRatio = ratioPref(PANEL_RATIO, 0.44f, 0.12f, 0.88f);
            lp.height = 0;
            lp.weight = panelRatio / (1 - panelRatio); // the editor area keeps weight 1
            editorArea.setVisibility(View.VISIBLE);
        }
        panelHolder.setLayoutParams(lp);
        panelHolder.setVisibility(terminalVisible ? View.VISIBLE : View.GONE);
    }

    /** Applies the extra-keys row's sticky Ctrl / Alt to text typed into the terminal. */
    boolean extraKeysConsume(String s) {
        return extraKeys != null && extraKeys.consumeTyped(s);
    }

    TerminalPanel terminal() {
        return terminalPanel;
    }

    /** Shell command that runs a file, by its type (interpreters must be installed, e.g. via Termux). */
    static String runCommandFor(File f) {
        String p = "'" + f.getAbsolutePath().replace("'", "'\\''") + "'";
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        String dir = "cd '" + f.getParentFile().getAbsolutePath().replace("'", "'\\''") + "' && ";
        if (n.endsWith(".py")) return dir + "python3 " + p;
        if (n.endsWith(".js") || n.endsWith(".mjs")) return dir + "node " + p;
        if (n.endsWith(".rb")) return dir + "ruby " + p;
        if (n.endsWith(".pl")) return dir + "perl " + p;
        if (n.endsWith(".lua")) return dir + "lua " + p;
        if (n.endsWith(".php")) return dir + "php " + p;
        if (n.endsWith(".go")) return dir + "go run " + p;
        if (n.endsWith(".java")) return dir + "java " + p;
        if (n.endsWith(".c")) return dir + "cc " + p + " -o \"$TMPDIR/a.out\" && \"$TMPDIR/a.out\"";
        return dir + "sh " + p;
    }

    // ------------------------------------------------------------------ source control

    /** The Source Control view (created on first use; it also feeds the status bar). */
    ScmView scm() {
        if (scmView == null) scmView = new ScmView(this);
        return scmView;
    }

    /** Refreshes Git status soon (after saves, folder changes). */
    void scheduleScmRefresh() {
        ui.removeCallbacks(scmRefresh);
        ui.postDelayed(scmRefresh, 600);
    }

    private final Runnable scmRefresh = new Runnable() {
        @Override
        public void run() {
            if (workspace != null) scm().refresh();
        }
    };

    /** Shows text in a read-only tab (diffs, logs, output); reuses a tab with the same name. */
    void showReadOnlyText(String name, String text, String languageId) {
        for (Document d : docs) {
            if (d.isUntitled() && d.readOnly && d.name.equals(name)) {
                d.engine.replaceAndSelect(0, d.text.length(), text, 0, 0);
                d.markSaved();
                activate(d);
                return;
            }
        }
        newUntitled(text);
        Document d = active;
        if (d == null) return;
        d.name = name;
        com.disgusty.droid_codology.lang.Language l = com.disgusty.droid_codology.lang.Languages.byId(languageId);
        if (l != null) d.setLanguage(l);
        d.readOnly = true;
        d.markSaved();
        d.engine.setSelection(0, 0);
        editor.readOnlyView = true;
        editor.hideKeyboard();
        refreshTabs();
        updateStatus();
        editor.invalidate();
    }

    /** Reloads open files that changed on disk and have no unsaved edits (after checkout / pull). */
    void reloadOpenDocumentsFromDisk() {
        for (Document d : new ArrayList<Document>(docs)) {
            if (d.isDirty() || !d.changedOnDisk()) continue;
            try {
                d.reload(activity);
            } catch (IOException e) {
                // The file may be gone after a branch switch; leave the tab as it is.
            }
        }
        refreshTabs();
        editor.invalidate();
        updateStatus();
        if (explorer != null) explorer.refresh();
    }

    // ------------------------------------------------------------------ accounts, sync, AI

    public void openAccounts() {
        if (accountsView != null) return;
        closeOverlaySideBar();
        accountsPage = new AccountsPage(this);
        accountsView = accountsPage.build();
        root.addView(accountsView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        editor.hideKeyboard();
    }

    public void closeAccounts() {
        if (accountsView == null) return;
        root.removeView(accountsView);
        accountsView = null;
        accountsPage = null;
    }

    boolean accountsOpen() {
        return accountsView != null;
    }

    /** Sign-in state, server or AI providers changed. */
    void onAccountChanged() {
        if (chatView != null) chatView.updateModelLabel();
        if (accountsPage != null) accountsPage.refill();
        lsp.reset();
    }

    /** Re-reads the server's capabilities (AI models, language servers) in the background. */
    void refreshServerInfo() {
        if (account.server().isEmpty()) return;
        final String server = account.server();
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    org.json.JSONObject info = account.info();
                    info.put("_server", server);
                    prefs.put("state.serverInfo", info.toString());
                    ArrayList<String> models = new ArrayList<String>();
                    org.json.JSONArray m = info.optJSONArray("models");
                    if (m != null) for (int i = 0; i < m.length(); i++) models.add(m.getJSONObject(i).getString("id"));
                    ai.setServerModels(models);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (chatView != null) chatView.updateModelLabel();
                            if (active != null) lsp.onActive(active);
                        }
                    });
                } catch (Exception ignored) {
                    // Offline: keep the cached info.
                }
            }
        });
    }

    // ------------------------------------------------------------------ language features

    /** Completion items from the language server and extension providers, merged. */
    void remoteCompletions(final Document d, int offset, String trigger, final LspManager.Result<List<Completion.Item>> cb) {
        final ArrayList<Completion.Item> all = new ArrayList<Completion.Item>();
        final int[] left = {2};
        final LspManager.Result<List<Completion.Item>> part = new LspManager.Result<List<Completion.Item>>() {
            @Override
            public void done(List<Completion.Item> items) {
                if (items != null) all.addAll(items);
                if (--left[0] == 0) cb.done(all);
            }
        };
        lsp.complete(d, offset, trigger, part);
        extHost.complete(d, offset, trigger, part);
    }

    /** Diagnostics of {@code d} changed (language server or extension). */
    void onDiagnosticsChanged(Document d) {
        if (d == active) editor.setDiagnostics(d.allDiagnostics());
        updateStatus();
    }

    void showHover(String markdown) {
        String text = markdown.replaceAll("(?m)^```\\w*\\s*$", "").replace("**", "").replace("`", "").trim();
        android.widget.TextView tv = skin.text(text, skin.uiSp(), skin.sideBarFg, android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        android.widget.ScrollView sv = new android.widget.ScrollView(activity);
        sv.addView(tv);
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.content = sv;
        s.positive = t("OK");
        skin.dialog(s).show();
    }

    /** All problems of open documents, in a quick pick (View: Problems). */
    void showProblems() {
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        for (final Document d : docs) {
            for (final com.disgusty.droid_codology.editor.Diagnostic g : d.allDiagnostics()) {
                String sev = g.severity == com.disgusty.droid_codology.editor.Diagnostic.ERROR ? t("Error")
                        : g.severity == com.disgusty.droid_codology.editor.Diagnostic.WARNING ? t("Warning") : t("Info");
                QuickPick.Item it = new QuickPick.Item(g.message, null, new Runnable() {
                    @Override
                    public void run() {
                        activate(d);
                        goTo(g.line, g.col);
                    }
                });
                it.description = sev + " · " + d.name + ":" + (g.line + 1) + (g.source == null ? "" : " · " + g.source);
                items.add(it);
            }
        }
        if (items.isEmpty()) {
            showMessage(t("No problems have been detected in the open files."));
            return;
        }
        quickPick.showItems(f("Problems ({0})", items.size()), items);
    }

    /** droid-codology://auth?token=... (or ?error=...) from the browser after OAuth sign-in. */
    public void onAuthRedirect(final Uri uri) {
        // Back from a loopback sign-in page (Google, OpenRouter): the sign-in finishes on its own.
        if (uri.getQueryParameter("done") != null) return;
        // Only the answer to a sign-in started here counts; any web page or app can open this link.
        if (!account.takePendingAuth(uri.getQueryParameter("s"))) {
            showMessage(t("Ignored a sign-in link that droid_codology didn't ask for."));
            return;
        }
        String err = uri.getQueryParameter("error");
        final String token = uri.getQueryParameter("token");
        if (err != null || token == null) {
            showMessage(f("Sign-in failed: {0}", err == null ? "no token" : err));
            return;
        }
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String e = null;
                try {
                    account.useToken(token);
                } catch (Exception ex) {
                    e = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                }
                final String error = e;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showMessage(error != null ? f("Sign-in failed: {0}", error) : f("Signed in as {0}", account.displayName()));
                        if (accountsPage != null) accountsPage.refreshInfo();
                        else onAccountChanged();
                    }
                });
            }
        });
    }

    /** What settings sync uploads, and how downloaded settings are applied. */
    private com.disgusty.droid_codology.account.Account.SyncData syncData() {
        return new com.disgusty.droid_codology.account.Account.SyncData() {
            @Override
            public org.json.JSONObject collect() throws org.json.JSONException {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("format", 1);
                o.put("settings", com.disgusty.droid_codology.account.Account.dumpPrefs(prefs.raw()));
                o.put("extensionSettings", com.disgusty.droid_codology.account.Account.dumpPrefs(
                        activity.getSharedPreferences("ext_config", android.content.Context.MODE_PRIVATE)));
                org.json.JSONArray ext = new org.json.JSONArray();
                for (com.disgusty.droid_codology.ext.Extension e : extensions.installed()) {
                    ext.put(new org.json.JSONObject().put("id", e.id).put("version", e.version).put("enabled", e.enabled));
                }
                o.put("extensions", ext);
                o.put("aiProviders", ai.exportWithoutKeys());
                o.put("aiModel", ai.selected());
                o.put("aiInstructions", ai.systemPrompt());
                return o;
            }

            @Override
            public void apply(final org.json.JSONObject o) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            applySynced(o);
                        } catch (org.json.JSONException e) {
                            showMessage(f("Settings Sync: {0}", e.getMessage()));
                        }
                    }
                });
            }
        };
    }

    private void applySynced(org.json.JSONObject o) throws org.json.JSONException {
        org.json.JSONObject s = o.optJSONObject("settings");
        if (s != null) com.disgusty.droid_codology.account.Account.loadPrefs(prefs.raw(), s);
        org.json.JSONObject es = o.optJSONObject("extensionSettings");
        if (es != null) com.disgusty.droid_codology.account.Account.loadPrefs(
                activity.getSharedPreferences("ext_config", android.content.Context.MODE_PRIVATE), es);
        if (o.has("aiProviders")) ai.importWithoutKeys(o.getJSONArray("aiProviders"), o.optString("aiModel", null), o.optString("aiInstructions", null));
        // Extensions installed on the other device but missing here come from Open VSX.
        org.json.JSONArray ext = o.optJSONArray("extensions");
        final ArrayList<String> missing = new ArrayList<String>();
        boolean toggled = false;
        if (ext != null) {
            for (int i = 0; i < ext.length(); i++) {
                org.json.JSONObject e = ext.getJSONObject(i);
                com.disgusty.droid_codology.ext.Extension have = extensions.find(e.getString("id"));
                if (have == null) missing.add(e.getString("id"));
                else if (have.enabled != e.optBoolean("enabled", true)) {
                    extensions.setEnabled(have, e.optBoolean("enabled", true));
                    toggled = true;
                }
            }
        }
        I18n.setLanguage(prefs.str("workbench.language", "system"));
        rebuild();
        // Enabled or disabled elsewhere: start or stop their code here too.
        if (toggled) onExtensionsChanged();
        if (!missing.isEmpty()) installFromOpenVsx(missing);
    }

    private void installFromOpenVsx(final List<String> ids) {
        showMessage(f("Settings Sync: installing {0} extensions...", ids.size()));
        bg.execute(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                for (String id : ids) {
                    int dot = id.indexOf('.');
                    if (dot <= 0) continue;
                    try {
                        com.disgusty.droid_codology.ext.OpenVsx.Item it = com.disgusty.droid_codology.ext.OpenVsx.details(id.substring(0, dot), id.substring(dot + 1));
                        // A desktop-only build (hundreds of MB, code that can't run here) is only installed by hand.
                        if (it.desktopOnly()) continue;
                        File f = com.disgusty.droid_codology.ext.OpenVsx.download(it, activity.getCacheDir(), null);
                        extensions.install(f);
                        f.delete();
                        ok++;
                    } catch (Exception ignored) {
                        // Not on Open VSX (or offline): skip it.
                    }
                }
                final int n = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (n > 0) onExtensionsChanged();
                        showMessage(f("Settings Sync: installed {0} of {1} extensions.", n, ids.size()));
                    }
                });
            }
        });
    }

    /** Runs settings sync in the background; {@code manual} reports the result and asks on conflicts. */
    void syncNow(final boolean manual) {
        if (!account.syncSignedIn() || syncing) return;
        syncing = true;
        lastAutoSync = System.currentTimeMillis();
        final com.disgusty.droid_codology.account.Account.SyncData data = syncData();
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String result = null, err = null;
                boolean conflict = false;
                try {
                    result = account.sync(data, false);
                } catch (com.disgusty.droid_codology.account.Account.Conflict c) {
                    conflict = true;
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String r = result, error = err;
                final boolean conf = conflict;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        syncing = false;
                        if (accountsPage != null) accountsPage.refill();
                        if (conf) {
                            // Another device saved between our read and write: next sync pulls its version.
                            if (manual) syncNow(true);
                            return;
                        }
                        if (!manual) return;
                        if (error != null) showMessage(f("Settings Sync: {0}", error));
                        else if ("pulled".equals(r)) showMessage(t("Settings Sync: applied settings from your other devices."));
                        else if ("pushed".equals(r)) showMessage(t("Settings Sync: uploaded."));
                        else showMessage(t("Settings Sync: up to date."));
                    }
                });
            }
        });
    }

    private void autoSync() {
        if (account.syncEnabled() && account.syncSignedIn() && System.currentTimeMillis() - lastAutoSync > 60000) syncNow(false);
    }

    /** Opens the Chat view, optionally asking a question right away. */
    void openChat(String question) {
        showSideView(CHAT_VIEW);
        setSideBarVisible(true);
        if (question != null) chatView.ask(question);
        else chatView.focusInput();
    }

    private String selectionOrNull() {
        Document d = active;
        if (d == null || !d.engine.sel.anyNonEmpty()) return null;
        int p = d.engine.sel.primary();
        return d.text.substring(d.engine.sel.start(p), d.engine.sel.end(p));
    }

    // ------------------------------------------------------------------ commands

    private Commands.When editorWhen() {
        return new Commands.When() {
            @Override
            public boolean ok() {
                return editorFocused();
            }
        };
    }

    private Commands.When docWhen() {
        return new Commands.When() {
            @Override
            public boolean ok() {
                return active != null;
            }
        };
    }

    private void ed(String id, String title, final EditorAction a, String... keys) {
        Commands.Command c = commands.add(id, null, t(title), new Runnable() {
            @Override
            public void run() {
                if (active == null) return;
                if (active.readOnly && a.writes()) return;
                a.run(active.engine);
                editor.ensureCaretVisible();
            }
        }, keys);
        c.when = editorWhen();
    }

    private abstract static class EditorAction {
        abstract void run(EditEngine e);

        boolean writes() {
            return true;
        }
    }

    private abstract static class MoveAction extends EditorAction {
        @Override
        boolean writes() {
            return false;
        }
    }

    private void registerCommands() {
        final Commands c = commands;
        c.add("workbench.action.quickOpen", null, t("Go to File..."), new Runnable() {
            @Override
            public void run() {
                quickPick.showFiles("");
            }
        }, "ctrl+p", "ctrl+e");
        c.add("workbench.action.showCommands", null, t("Show All Commands"), new Runnable() {
            @Override
            public void run() {
                quickPick.showFiles(">");
            }
        }, "ctrl+shift+p", "f1");
        c.add("workbench.action.gotoLine", null, t("Go to Line/Column..."), new Runnable() {
            @Override
            public void run() {
                quickPick.showFiles(":");
            }
        }, "ctrl+g").when = docWhen();
        c.add("workbench.action.files.newUntitledFile", t("File"), t("New Text File"), new Runnable() {
            @Override
            public void run() {
                newUntitled(null);
            }
        }, "ctrl+n");
        c.add("workbench.action.files.openFile", t("File"), t("Open File..."), new Runnable() {
            @Override
            public void run() {
                if (activity instanceof Opener) ((Opener) activity).pickFile();
            }
        }, "ctrl+o");
        c.add("workbench.action.files.openFolder", t("File"), t("Open Folder..."), new Runnable() {
            @Override
            public void run() {
                FolderPicker.pick(Workbench.this, t("Open Folder"), workspace != null ? workspace.root : null, null, new FolderPicker.Callback() {
                    @Override
                    public void picked(File dir, String name) {
                        openFolder(dir);
                    }
                });
            }
        }, "ctrl+k ctrl+o");
        c.add("workbench.action.closeFolder", t("File"), t("Close Folder"), new Runnable() {
            @Override
            public void run() {
                closeFolder();
            }
        }, "ctrl+k f").when = new Commands.When() {
            @Override
            public boolean ok() {
                return workspace != null;
            }
        };
        c.add("workbench.action.files.save", t("File"), t("Save"), new Runnable() {
            @Override
            public void run() {
                save(active, null);
            }
        }, "ctrl+s").when = docWhen();
        c.add("workbench.action.files.saveAs", t("File"), t("Save As..."), new Runnable() {
            @Override
            public void run() {
                saveAs(active, null);
            }
        }, "ctrl+shift+s").when = docWhen();
        c.add("workbench.action.files.saveAll", t("File"), t("Save All"), new Runnable() {
            @Override
            public void run() {
                saveAll();
            }
        }, "ctrl+k s");
        c.add("workbench.action.files.revert", t("File"), t("Revert File"), new Runnable() {
            @Override
            public void run() {
                if (active == null || active.isUntitled()) return;
                try {
                    active.reload(activity);
                    refreshTabs();
                } catch (Exception e) {
                    showMessage(String.valueOf(e.getMessage()));
                }
            }
        }).when = docWhen();
        c.add("workbench.action.closeActiveEditor", t("View"), t("Close Editor"), new Runnable() {
            @Override
            public void run() {
                if (active != null) close(active, null);
            }
        }, "ctrl+w", "ctrl+f4").when = docWhen();
        c.add("workbench.action.closeAllEditors", t("View"), t("Close All"), new Runnable() {
            @Override
            public void run() {
                closeMany(new ArrayList<Document>(docs));
            }
        }, "ctrl+k ctrl+w");
        c.add("workbench.action.reopenClosedEditor", t("View"), t("Reopen Closed Editor"), new Runnable() {
            @Override
            public void run() {
                if (closedPaths.isEmpty()) return;
                String p = closedPaths.remove(closedPaths.size() - 1);
                openFile(new File(p), false, -1, 0, 0);
            }
        }, "ctrl+shift+t");
        c.add("workbench.action.nextEditor", t("View"), t("Next Editor"), new Runnable() {
            @Override
            public void run() {
                cycle(1);
            }
        }, "ctrl+pagedown", "ctrl+tab");
        c.add("workbench.action.previousEditor", t("View"), t("Previous Editor"), new Runnable() {
            @Override
            public void run() {
                cycle(-1);
            }
        }, "ctrl+pageup", "ctrl+shift+tab");
        for (int i = 1; i <= 9; i++) {
            final int idx = i - 1;
            c.add("workbench.action.openEditorAtIndex" + i, t("View"), "Open Editor " + i, new Runnable() {
                @Override
                public void run() {
                    if (idx < docs.size()) activate(docs.get(idx));
                }
            }, "alt+" + i);
        }
        c.add("workbench.action.toggleSidebarVisibility", t("View"), t("Toggle Primary Side Bar Visibility"), new Runnable() {
            @Override
            public void run() {
                toggleSideBar();
            }
        }, "ctrl+b");
        c.add("workbench.view.explorer", t("View"), t("Show Explorer"), new Runnable() {
            @Override
            public void run() {
                showSideView(0);
                setSideBarVisible(true);
            }
        }, "ctrl+shift+e");
        c.add("workbench.view.search", t("View"), t("Show Search"), new Runnable() {
            @Override
            public void run() {
                showSideView(1);
                setSideBarVisible(true);
                search.focusQuery(selectedWord());
            }
        }, "ctrl+shift+f");
        c.add("workbench.action.findInFiles", t("Search"), t("Find in Files"), new Runnable() {
            @Override
            public void run() {
                commands.run("workbench.view.search");
            }
        });
        c.add("workbench.action.replaceInFiles", t("Search"), t("Replace in Files"), new Runnable() {
            @Override
            public void run() {
                commands.run("workbench.view.search");
                search.showReplace(true);
            }
        }, "ctrl+shift+h");
        c.add("workbench.action.openSettings", t("Preferences"), t("Open Settings"), new Runnable() {
            @Override
            public void run() {
                openSettings();
            }
        }, "ctrl+,");
        c.add("workbench.action.customizeActivityBar", t("View"), t("Customize Activity Bar"), new Runnable() {
            @Override
            public void run() {
                new SettingsPage(Workbench.this).editActivityBar();
            }
        });
        c.add("workbench.action.selectTheme", t("Preferences"), t("Color Theme"), new Runnable() {
            @Override
            public void run() {
                ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
                String ext = prefs.str(Prefs.THEME_EXT, null);
                for (final String[] th : new String[][]{{"dark", "Dark Modern"}, {"light", "Light Modern"}, {"system", t("System")}}) {
                    boolean on = ext == null && th[0].equals(prefs.str(Prefs.THEME, "system"));
                    items.add(new QuickPick.Item(th[1], on ? "\u2713" : null, new Runnable() {
                        @Override
                        public void run() {
                            prefs.put(Prefs.THEME, th[0]);
                            prefs.put(Prefs.THEME_EXT, null);
                            leaveMatrix();
                            rebuild();
                        }
                    }));
                }
                // The Matrix look is a whole style (fonts, frames, rain); here it's one more theme.
                boolean matrixOn = prefs.i(Prefs.STYLE, Skin.ORIGINAL) == Skin.MATRIX;
                items.add(new QuickPick.Item("Matrix", matrixOn ? "✓" : t("phosphor green, falling code"), new Runnable() {
                    @Override
                    public void run() {
                        int style = prefs.i(Prefs.STYLE, Skin.ORIGINAL);
                        if (style != Skin.MATRIX) prefs.put("state.styleBeforeMatrix", style);
                        prefs.put(Prefs.STYLE, Skin.MATRIX);
                        prefs.put(Prefs.THEME_EXT, null);
                        if (!prefs.raw().contains(Prefs.MATRIX_RAIN)) prefs.put(Prefs.MATRIX_RAIN, true);
                        rebuild();
                    }
                }));
                for (Object[] pair : extensions.themes()) {
                    final com.disgusty.droid_codology.ext.Extension e = (com.disgusty.droid_codology.ext.Extension) pair[0];
                    final com.disgusty.droid_codology.ext.Extension.Theme th = (com.disgusty.droid_codology.ext.Extension.Theme) pair[1];
                    final String key = e.id + "|" + th.path + "|" + th.uiTheme;
                    QuickPick.Item it = new QuickPick.Item(th.label, key.equals(ext) ? "\u2713" : e.displayName, new Runnable() {
                        @Override
                        public void run() {
                            prefs.put(Prefs.THEME_EXT, key);
                            rebuild();
                        }
                    });
                    items.add(it);
                }
                quickPick.showItems(t("Color Theme"), items);
            }
        }, "ctrl+k ctrl+t");
        c.add("workbench.view.extensions", t("View"), t("Show Extensions"), new Runnable() {
            @Override
            public void run() {
                showSideView(EXTENSIONS_VIEW);
                setSideBarVisible(true);
            }
        }, "ctrl+shift+x");
        c.add("workbench.action.splitEditor", t("View"), t("Split Editor"), new Runnable() {
            @Override
            public void run() {
                splitEditor();
            }
        }, "ctrl+\\").when = docWhen();
        c.add("workbench.action.joinAllGroups", t("View"), t("Join All Editor Groups"), new Runnable() {
            @Override
            public void run() {
                joinPanes();
            }
        }).when = new Commands.When() {
            @Override
            public boolean ok() {
                return isSplit();
            }
        };
        c.add("workbench.action.toggleEditorGroupLayout", t("View"), t("Toggle Vertical/Horizontal Editor Layout"), new Runnable() {
            @Override
            public void run() {
                toggleSplitOrientation();
            }
        }, "shift+alt+0").when = new Commands.When() {
            @Override
            public boolean ok() {
                return isSplit();
            }
        };
        c.add("workbench.action.focusOtherEditorGroup", t("View"), t("Focus Other Editor Group"), new Runnable() {
            @Override
            public void run() {
                if (otherEditor != null) otherEditor.requestFocus();
            }
        }, "ctrl+k ctrl+right").when = new Commands.When() {
            @Override
            public boolean ok() {
                return isSplit();
            }
        };
        c.add("editor.action.showHover", null, t("Show Hover"), new Runnable() {
            @Override
            public void run() {
                if (active != null) lsp.hover(active, active.engine.sel.caret());
            }
        }, "ctrl+k ctrl+i").when = docWhen();
        c.add("editor.action.revealDefinition", null, t("Go to Definition"), new Runnable() {
            @Override
            public void run() {
                if (active != null) lsp.definition(active, active.engine.sel.caret());
            }
        }, "f12").when = docWhen();
        c.add("editor.action.goToReferences", null, t("Go to References"), new Runnable() {
            @Override
            public void run() {
                if (active != null) lsp.references(active, active.engine.sel.caret());
            }
        }, "shift+f12").when = docWhen();
        c.add("editor.action.rename", null, t("Rename Symbol"), new Runnable() {
            @Override
            public void run() {
                if (active != null && !active.readOnly) lsp.rename(active, active.engine.sel.caret());
            }
        }, "f2").when = docWhen();
        c.add("workbench.actions.view.problems", t("View"), t("Problems"), new Runnable() {
            @Override
            public void run() {
                showProblems();
            }
        }, "ctrl+shift+m");
        c.add("lsp.restart", t("Language Server"), t("Restart Language Servers"), new Runnable() {
            @Override
            public void run() {
                lsp.reset();
                showMessage(t("Language servers restarted."));
            }
        });
        c.add("workbench.action.terminal.toggleTerminal", t("View"), t("Toggle Terminal"), new Runnable() {
            @Override
            public void run() {
                toggleTerminal();
            }
        }, "ctrl+`");
        c.add("workbench.action.toggleMatrixRain", t("View"), t("Toggle Falling Code"), new Runnable() {
            @Override
            public void run() {
                prefs.put(Prefs.MATRIX_RAIN, !prefs.b(Prefs.MATRIX_RAIN, false));
                rebuild();
            }
        });
        c.add("workbench.action.lockApp", t("Security"), t("Lock the App Now"), new Runnable() {
            @Override
            public void run() {
                if (!lock.enabled()) {
                    showMessage(t("Set an app password first (Settings → Security)."));
                    return;
                }
                lock.lockNow();
                lock.attach(Workbench.this, root);
            }
        });
        c.add("alpine.openTerminal", "Alpine Linux", t("Open Terminal"), new Runnable() {
            @Override
            public void run() {
                runInAlpine(null);
            }
        });
        c.add("alpine.installTools", "Alpine Linux", t("Install Developer Tools (git, Node.js, Python)"), new Runnable() {
            @Override
            public void run() {
                installPackages(t("Developer Tools"), Arrays.asList("git", "openssh-client", "nodejs", "npm", "python3", "py3-pip",
                        "build-base", "bash", "curl"), null);
            }
        });
        c.add("alpine.installPackages", "Alpine Linux", t("Install Packages..."), new Runnable() {
            @Override
            public void run() {
                prompt(t("Install Packages"), t("Package names, e.g. git python3"), "", new TextCallback() {
                    @Override
                    public void onText(String s) {
                        ArrayList<String> names = new ArrayList<String>();
                        for (String n : s.trim().split("[\\s,]+")) if (n.matches("[A-Za-z0-9._+-]+")) names.add(n);
                        if (!names.isEmpty()) installPackages(android.text.TextUtils.join(" ", names), names, null);
                    }
                });
            }
        });
        c.add("alpine.claudeCode", "Alpine Linux", t("Prepare for Claude Code"), new Runnable() {
            @Override
            public void run() {
                // The libraries Anthropic's CLI needs on musl; the user runs the official installer
                // from Anthropic's setup page themselves.
                installPackages(t("Libraries for Claude Code"), Arrays.asList("bash", "curl", "libgcc", "libstdc++", "ripgrep"), new Runnable() {
                    @Override
                    public void run() {
                        runInAlpine("echo 'export USE_BUILTIN_RIPGREP=0' > /etc/profile.d/claude.sh"
                                + " && echo 'Ready. Install Claude Code as described on https://docs.claude.com/en/docs/claude-code/setup'");
                    }
                });
            }
        });
        c.add("alpine.remove", "Alpine Linux", t("Remove Alpine Linux"), new Runnable() {
            @Override
            public void run() {
                confirm(t("Remove Alpine Linux"), t("Delete Alpine Linux and everything installed in it (your projects stay)?"), t("Remove"), new Runnable() {
                    @Override
                    public void run() {
                        prefs.put(Prefs.ALPINE_MODE, false);
                        // Node.js must not run from the files being deleted.
                        final boolean wasNode = extHost.onNode();
                        if (wasNode) extHost.stop();
                        bg.execute(new Runnable() {
                            @Override
                            public void run() {
                                alpine().remove();
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (wasNode) extHost.start();
                                        showMessage(t("Alpine Linux removed."));
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
        c.add("workbench.action.terminal.new", t("Terminal"), t("Create New Terminal"), new Runnable() {
            @Override
            public void run() {
                // Opening the panel starts the first shell; any later call adds another one.
                boolean had = !TerminalPanel.sessions.isEmpty();
                showTerminal();
                if (had && terminalPanel != null) terminalPanel.newSession();
            }
        }, "ctrl+shift+`");
        c.add("workbench.action.terminal.kill", t("Terminal"), t("Kill the Active Terminal Instance"), new Runnable() {
            @Override
            public void run() {
                if (terminalPanel != null) terminalPanel.kill();
            }
        });
        c.add("workbench.action.terminal.runSelectedText", t("Terminal"), t("Run Selected Text In Active Terminal"), new Runnable() {
            @Override
            public void run() {
                String s = selectionOrNull();
                if (s == null && active != null) s = active.text.lineText(active.text.lineOfOffset(active.engine.sel.caret()));
                if (s == null) return;
                showTerminal();
                if (terminalPanel != null) terminalPanel.run(s);
            }
        }).when = docWhen();
        c.add("workbench.action.terminal.runActiveFile", t("Terminal"), t("Run Active File In Active Terminal"), new Runnable() {
            @Override
            public void run() {
                if (active == null || active.file == null) return;
                save(active, new Runnable() {
                    @Override
                    public void run() {
                        showTerminal();
                        if (terminalPanel != null) terminalPanel.run(runCommandFor(active.file));
                    }
                });
            }
        }).when = docWhen();
        c.add("workbench.view.scm", t("View"), t("Show Source Control"), new Runnable() {
            @Override
            public void run() {
                showSideView(SCM_VIEW);
                setSideBarVisible(true);
            }
        }, "ctrl+shift+g");
        c.add("git.clone", "Git", t("Clone"), new Runnable() {
            @Override
            public void run() {
                scm().cloneRepo();
            }
        });
        Commands.When repoWhen = new Commands.When() {
            @Override
            public boolean ok() {
                return workspace != null;
            }
        };
        c.add("git.init", "Git", t("Initialize Repository"), new Runnable() {
            @Override
            public void run() {
                scm().initRepo();
            }
        }).when = repoWhen;
        c.add("git.commit", "Git", t("Commit"), new Runnable() {
            @Override
            public void run() {
                showSideView(SCM_VIEW);
                setSideBarVisible(true);
                scm().commit();
            }
        }).when = repoWhen;
        c.add("git.pull", "Git", t("Pull"), new Runnable() {
            @Override
            public void run() {
                scm().pull(false);
            }
        }).when = repoWhen;
        c.add("git.push", "Git", t("Push"), new Runnable() {
            @Override
            public void run() {
                scm().push();
            }
        }).when = repoWhen;
        c.add("git.sync", "Git", t("Sync"), new Runnable() {
            @Override
            public void run() {
                scm().pull(true);
            }
        }).when = repoWhen;
        c.add("git.fetch", "Git", t("Fetch"), new Runnable() {
            @Override
            public void run() {
                scm().fetch();
            }
        }).when = repoWhen;
        c.add("git.checkout", "Git", t("Checkout to..."), new Runnable() {
            @Override
            public void run() {
                scm().pickBranch();
            }
        }).when = repoWhen;
        c.add("git.viewHistory", "Git", t("View History"), new Runnable() {
            @Override
            public void run() {
                scm().showHistory();
            }
        }).when = repoWhen;
        c.add("workbench.action.openAccounts", t("Accounts"), t("Manage Accounts"), new Runnable() {
            @Override
            public void run() {
                openAccounts();
            }
        });
        c.add("workbench.userDataSync.actions.syncNow", t("Settings Sync"), t("Sync Now"), new Runnable() {
            @Override
            public void run() {
                if (!account.syncSignedIn()) {
                    showMessage(t("Sign in to sync settings."));
                    openAccounts();
                    return;
                }
                syncNow(true);
            }
        });
        c.add("workbench.action.chat.open", t("Chat"), t("Open Chat"), new Runnable() {
            @Override
            public void run() {
                openChat(null);
            }
        }, "ctrl+alt+i", "ctrl+shift+i");
        c.add("workbench.action.chat.selectModel", t("Chat"), t("Select Model"), new Runnable() {
            @Override
            public void run() {
                openChat(null);
            }
        });
        c.add("inlineChat.start", t("AI"), t("Edit with AI..."), new Runnable() {
            @Override
            public void run() {
                InlineAi.start(Workbench.this);
            }
        }, "ctrl+i", "ctrl+k ctrl+i").when = docWhen();
        c.add("ai.explainSelection", t("AI"), t("Explain Selection"), new Runnable() {
            @Override
            public void run() {
                openChat(selectionOrNull() != null ? t("Explain what this code does.") : t("Explain what this file does."));
            }
        }).when = docWhen();
        c.add("ai.fixSelection", t("AI"), t("Find Bugs"), new Runnable() {
            @Override
            public void run() {
                openChat(t("Find bugs in this code and show the fixed version."));
            }
        }).when = docWhen();
        c.add("ai.writeTests", t("AI"), t("Write Tests"), new Runnable() {
            @Override
            public void run() {
                openChat(t("Write unit tests for this code."));
            }
        }).when = docWhen();
        c.add("workbench.action.showExtensionHostLog", t("Developer"), t("Show Extension Host Log"), new Runnable() {
            @Override
            public void run() {
                extHost.showOutput(ExtensionHost.LOG_CHANNEL);
            }
        });
        c.add("workbench.action.restartExtensionHost", t("Developer"), t("Restart Extension Host"), new Runnable() {
            @Override
            public void run() {
                extHost.restart();
                showMessage(t("Extension host restarted."));
            }
        });
        c.add("workbench.action.zoomIn", t("View"), t("Zoom In"), new Runnable() {
            @Override
            public void run() {
                editor.setFontSize(editor.fontSize() + 1);
                prefs.put(Prefs.FONT_SIZE, editor.fontSize());
            }
        }, "ctrl+=", "ctrl+shift+=", "ctrl+numpad_add");
        c.add("workbench.action.zoomOut", t("View"), t("Zoom Out"), new Runnable() {
            @Override
            public void run() {
                editor.setFontSize(editor.fontSize() - 1);
                prefs.put(Prefs.FONT_SIZE, editor.fontSize());
            }
        }, "ctrl+-", "ctrl+shift+-", "ctrl+numpad_subtract");
        c.add("workbench.action.zoomReset", t("View"), t("Reset Zoom"), new Runnable() {
            @Override
            public void run() {
                editor.setFontSize(14);
                prefs.put(Prefs.FONT_SIZE, 14f);
            }
        }, "ctrl+0", "ctrl+numpad0");
        c.add("editor.action.toggleWordWrap", t("View"), t("Toggle Word Wrap"), new Runnable() {
            @Override
            public void run() {
                boolean w = !prefs.b(Prefs.WORD_WRAP, false);
                prefs.put(Prefs.WORD_WRAP, w);
                editor.setWordWrap(w);
            }
        }, "alt+z");
        c.add("editor.action.toggleRenderWhitespace", t("View"), t("Toggle Render Whitespace"), new Runnable() {
            @Override
            public void run() {
                boolean w = !prefs.b(Prefs.WHITESPACE, false);
                prefs.put(Prefs.WHITESPACE, w);
                editor.renderWhitespace = w;
                editor.invalidate();
            }
        });
        c.add("workbench.action.toggleExtraKeys", t("View"), t("Toggle Extra Keys"), new Runnable() {
            @Override
            public void run() {
                boolean w = !prefs.b(Prefs.EXTRA_KEYS, true);
                prefs.put(Prefs.EXTRA_KEYS, w);
                extraKeys.setVisibility(w ? View.VISIBLE : View.GONE);
            }
        });
        c.add("workbench.action.toggleZenMode", t("View"), t("Toggle Zen Mode"), new Runnable() {
            @Override
            public void run() {
                zen = !zen;
                rebuild();
            }
        }, "ctrl+k z");
        c.add("workbench.action.files.copyPathOfActiveFile", t("File"), t("Copy Path of Active File"), new Runnable() {
            @Override
            public void run() {
                if (active != null) copyText(active.path());
            }
        }, "ctrl+k p").when = docWhen();
        c.add("workbench.files.action.showActiveFileInExplorer", t("File"), t("Reveal Active File in Explorer View"), new Runnable() {
            @Override
            public void run() {
                if (active == null || active.file == null) return;
                showSideView(0);
                setSideBarVisible(true);
                explorer.reveal(active.file);
            }
        }, "ctrl+k r").when = docWhen();
        c.add("workbench.action.editor.changeLanguageMode", null, t("Change Language Mode"), new Runnable() {
            @Override
            public void run() {
                pickLanguage();
            }
        }, "ctrl+k m").when = docWhen();
        c.add("workbench.action.editor.changeEncoding", null, t("Change File Encoding"), new Runnable() {
            @Override
            public void run() {
                pickEncoding();
            }
        }).when = docWhen();
        c.add("workbench.action.editor.changeEOL", null, t("Change End of Line Sequence"), new Runnable() {
            @Override
            public void run() {
                pickEol();
            }
        }).when = docWhen();
        c.add("editor.action.indentationToSpaces", null, t("Convert Indentation to Spaces"), new Runnable() {
            @Override
            public void run() {
                if (active != null) convertIndentation(active, true);
            }
        }).when = docWhen();
        c.add("editor.action.indentationToTabs", null, t("Convert Indentation to Tabs"), new Runnable() {
            @Override
            public void run() {
                if (active != null) convertIndentation(active, false);
            }
        }).when = docWhen();
        c.add("workbench.action.closePanels", null, "Escape", new Runnable() {
            @Override
            public void run() {
                onBack();
            }
        }, "escape").when = new Commands.When() {
            @Override
            public boolean ok() {
                return (find != null && find.getVisibility() == View.VISIBLE && !editorHasMultiCursor()) || settingsPage != null
                        || (drawer != null && drawer.isOpen());
            }
        };

        // ---- find
        c.add("actions.find", t("Edit"), t("Find"), new Runnable() {
            @Override
            public void run() {
                if (active != null) find.show(false, selectedWord());
            }
        }, "ctrl+f").when = docWhen();
        c.add("editor.action.startFindReplaceAction", t("Edit"), t("Replace"), new Runnable() {
            @Override
            public void run() {
                if (active != null) find.show(true, selectedWord());
            }
        }, "ctrl+h").when = docWhen();
        c.add("editor.action.nextMatchFindAction", t("Edit"), t("Next Match"), new Runnable() {
            @Override
            public void run() {
                find.next();
            }
        }, "f3").when = docWhen();
        c.add("editor.action.previousMatchFindAction", t("Edit"), t("Previous Match"), new Runnable() {
            @Override
            public void run() {
                find.prev();
            }
        }, "shift+f3").when = docWhen();

        // ---- editing (VS Code default keybindings)
        ed("undo", "Undo", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.undo();
            }
        }, "ctrl+z");
        commands.get("undo").when = docWhen();
        ed("redo", "Redo", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.redo();
            }
        }, "ctrl+y", "ctrl+shift+z");
        commands.get("redo").when = docWhen();
        ed("editor.action.clipboardCutAction", "Cut", new EditorAction() {
            @Override
            void run(EditEngine e) {
                editor.copy(true);
            }
        }, "ctrl+x", "shift+delete");
        ed("editor.action.clipboardCopyAction", "Copy", new MoveAction() {
            @Override
            void run(EditEngine e) {
                editor.copy(false);
            }
        }, "ctrl+c", "ctrl+insert");
        ed("editor.action.clipboardPasteAction", "Paste", new EditorAction() {
            @Override
            void run(EditEngine e) {
                editor.paste();
            }
        }, "ctrl+v", "shift+insert");
        ed("editor.action.selectAll", "Select All", new MoveAction() {
            @Override
            void run(EditEngine e) {
                e.selectAll();
            }
        }, "ctrl+a");
        ed("editor.action.commentLine", "Toggle Line Comment", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.toggleLineComment();
            }
        }, "ctrl+/");
        ed("editor.action.blockComment", "Toggle Block Comment", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.toggleBlockComment();
            }
        }, "shift+alt+a", "ctrl+shift+a");
        ed("editor.action.indentLines", "Indent Line", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.indentLines(true);
            }
        }, "ctrl+]");
        ed("editor.action.outdentLines", "Outdent Line", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.indentLines(false);
            }
        }, "ctrl+[");
        ed("editor.action.copyLinesUpAction", "Copy Line Up", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.duplicateLines(false);
            }
        }, "shift+alt+up");
        ed("editor.action.copyLinesDownAction", "Copy Line Down", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.duplicateLines(true);
            }
        }, "shift+alt+down");
        ed("editor.action.duplicateSelection", "Duplicate Selection", new EditorAction() {
            @Override
            void run(EditEngine e) {
                if (!e.sel.anyNonEmpty()) {
                    e.duplicateLines(true);
                    return;
                }
                String s = e.text.substring(e.sel.start(e.sel.primary()), e.sel.end(e.sel.primary()));
                int end = e.sel.end(e.sel.primary());
                e.replaceAndSelect(end, end, s, end, end + s.length());
            }
        });
        ed("editor.action.moveLinesUpAction", "Move Line Up", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.moveLines(true);
            }
        }, "alt+up");
        ed("editor.action.moveLinesDownAction", "Move Line Down", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.moveLines(false);
            }
        }, "alt+down");
        ed("editor.action.deleteLines", "Delete Line", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.deleteLines();
            }
        }, "ctrl+shift+k");
        ed("editor.action.insertLineAfter", "Insert Line Below", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.insertLine(false);
            }
        }, "ctrl+enter");
        ed("editor.action.insertLineBefore", "Insert Line Above", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.insertLine(true);
            }
        }, "ctrl+shift+enter");
        ed("editor.action.addSelectionToNextFindMatch", "Add Selection to Next Find Match", new MoveAction() {
            @Override
            void run(EditEngine e) {
                e.addNextOccurrence();
            }
        }, "ctrl+d");
        ed("editor.action.selectHighlights", "Select All Occurrences of Find Match", new MoveAction() {
            @Override
            void run(EditEngine e) {
                e.selectAllOccurrences();
            }
        }, "ctrl+shift+l");
        ed("editor.action.insertCursorAbove", "Add Cursor Above", new MoveAction() {
            @Override
            void run(EditEngine e) {
                editor.addCaretVertical(-1);
            }
        }, "ctrl+alt+up", "shift+alt+i");
        ed("editor.action.insertCursorBelow", "Add Cursor Below", new MoveAction() {
            @Override
            void run(EditEngine e) {
                editor.addCaretVertical(1);
            }
        }, "ctrl+alt+down");
        ed("expandLineSelection", "Expand Line Selection", new MoveAction() {
            @Override
            void run(EditEngine e) {
                e.selectLine();
            }
        }, "ctrl+l");
        ed("editor.action.jumpToBracket", "Go to Bracket", new MoveAction() {
            @Override
            void run(EditEngine e) {
                e.jumpToBracket();
            }
        }, "ctrl+shift+\\");
        ed("editor.action.smartSelect.expand", "Expand Selection", new MoveAction() {
            @Override
            void run(EditEngine e) {
                e.expandSelection();
            }
        }, "shift+alt+right");
        ed("editor.action.joinLines", "Join Lines", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.joinLines();
            }
        }, "ctrl+j");
        ed("editor.action.transformToUppercase", "Transform to Uppercase", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.transformCase(0);
            }
        });
        ed("editor.action.transformToLowercase", "Transform to Lowercase", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.transformCase(1);
            }
        });
        ed("editor.action.transformToTitlecase", "Transform to Title Case", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.transformCase(2);
            }
        });
        ed("editor.action.sortLinesAscending", "Sort Lines Ascending", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.sortLines(false);
            }
        });
        ed("editor.action.sortLinesDescending", "Sort Lines Descending", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.sortLines(true);
            }
        });
        ed("editor.action.trimTrailingWhitespace", "Trim Trailing Whitespace", new EditorAction() {
            @Override
            void run(EditEngine e) {
                e.trimTrailingWhitespace();
            }
        }, "ctrl+k ctrl+x");
        c.add("workbench.action.gotoSymbol", null, t("Go to Symbol in Editor..."), new Runnable() {
            @Override
            public void run() {
                quickPick.showFiles("@");
            }
        }, "ctrl+shift+o").when = docWhen();
        c.add("outline.focus", t("View"), t("Show Outline"), new Runnable() {
            @Override
            public void run() {
                showSideView(OUTLINE_VIEW);
                setSideBarVisible(true);
            }
        });
        c.add("markdown.showPreview", "Markdown", t("Open Preview"), new Runnable() {
            @Override
            public void run() {
                openPreview(false);
            }
        }, "ctrl+shift+v").when = docWhen();
        c.add("markdown.showPreviewToSide", "Markdown", t("Open Preview to the Side"), new Runnable() {
            @Override
            public void run() {
                openPreview(true);
            }
        }, "ctrl+k v").when = docWhen();
        c.add("editor.action.formatDocument", null, t("Format Document"), new Runnable() {
            @Override
            public void run() {
                formatDocument();
            }
        }, "shift+alt+f").when = docWhen();
        c.add("editor.action.triggerSuggest", null, t("Trigger Suggest"), new Runnable() {
            @Override
            public void run() {
                completion.trigger();
            }
        }, "ctrl+space", "ctrl+i").when = editorWhen();
        c.add("editor.action.insertSnippet", null, t("Insert Snippet"), new Runnable() {
            @Override
            public void run() {
                pickSnippet();
            }
        }).when = docWhen();

        // ---- folding
        ed("editor.fold", "Fold", new MoveAction() {
            @Override
            void run(EditEngine e) {
                editor.folding().fold(e.text.lineOfOffset(e.sel.caret()));
            }
        }, "ctrl+shift+[");
        ed("editor.unfold", "Unfold", new MoveAction() {
            @Override
            void run(EditEngine e) {
                editor.folding().unfold(e.text.lineOfOffset(e.sel.caret()));
            }
        }, "ctrl+shift+]");
        ed("editor.toggleFold", "Toggle Fold", new MoveAction() {
            @Override
            void run(EditEngine e) {
                int line = e.text.lineOfOffset(e.sel.caret());
                if (!editor.folding().unfold(line)) editor.folding().fold(line);
            }
        }, "ctrl+k ctrl+l");
        ed("editor.foldAll", "Fold All", new MoveAction() {
            @Override
            void run(EditEngine e) {
                editor.folding().foldAll();
            }
        }, "ctrl+k ctrl+0");
        ed("editor.unfoldAll", "Unfold All", new MoveAction() {
            @Override
            void run(EditEngine e) {
                editor.folding().unfoldAll();
            }
        }, "ctrl+k ctrl+j");
        for (int lv = 1; lv <= 7; lv++) {
            final int level = lv;
            ed("editor.foldLevel" + lv, "Fold Level " + lv, new MoveAction() {
                @Override
                void run(EditEngine e) {
                    editor.folding().foldLevel(level);
                }
            }, "ctrl+k ctrl+" + lv);
        }
        // Commands from the palette should work even when the palette had focus.
        for (Commands.Command cmd : commands.registered()) {
            if (cmd.when != null && (cmd.id.startsWith("editor.action.") || cmd.id.equals("expandLineSelection"))) {
                cmd.when = new Commands.When() {
                    @Override
                    public boolean ok() {
                        return active != null && (editor.hasFocus() || (quickPick != null && quickPick.isRunningCommand()));
                    }
                };
            }
        }
    }

    private Document symbolsDoc;
    private int symbolsVersion = -1;
    private List<com.disgusty.droid_codology.lang.Symbols.Symbol> symbolsCache = new ArrayList<com.disgusty.droid_codology.lang.Symbols.Symbol>();

    /** Document symbols (cached per text version), with their body end lines from folding. */
    List<com.disgusty.droid_codology.lang.Symbols.Symbol> symbols(Document d) {
        if (d == symbolsDoc && d.text.version() == symbolsVersion) return symbolsCache;
        List<com.disgusty.droid_codology.lang.Symbols.Symbol> l = com.disgusty.droid_codology.lang.Symbols.find(d.text, d.language.id, d.engine.tabSize, 100000);
        d.folding.ensureRanges();
        for (com.disgusty.droid_codology.lang.Symbols.Symbol s : l) {
            int end = d.folding.rangeEndAt(s.line);
            if (end < 0 && s.line + 1 < d.text.lineCount()) end = d.folding.rangeEndAt(s.line + 1);
            s.endLine = end > s.line ? end : s.line;
        }
        symbolsCache = l;
        symbolsDoc = d;
        symbolsVersion = d.text.version();
        return l;
    }

    /** Moves the caret to a line/column of the active editor and reveals it. */
    void goTo(int line, int col) {
        Document d = active;
        if (d == null) return;
        int l = Math.max(0, Math.min(line, d.text.lineCount() - 1));
        int off = Math.min(d.text.lineStart(l) + Math.max(0, col), d.text.lineEnd(l));
        d.folding.reveal(l);
        d.engine.setSelection(off, off);
        editor.revealLine(l);
        editor.requestFocus();
    }

    // ------------------------------------------------------------------ extensions

    /** The selected extension color theme (cached while its key is unchanged), or null. */
    private com.disgusty.droid_codology.ext.ThemeLoader.Loaded loadExtensionTheme() {
        String key = prefs.str(Prefs.THEME_EXT, null);
        if (key == null) {
            extTheme = null;
            extThemeKey = null;
            return null;
        }
        if (key.equals(extThemeKey) && extTheme != null) return extTheme;
        String[] p = key.split("\\|", 3);
        com.disgusty.droid_codology.ext.Extension e = p.length == 3 ? extensions.find(p[0]) : null;
        if (e == null || !e.enabled) {
            prefs.put(Prefs.THEME_EXT, null);
            return null;
        }
        try {
            extTheme = com.disgusty.droid_codology.ext.ThemeLoader.load(e.file(p[1]), !"vs".equals(p[2]) && !"hc-light".equals(p[2]));
            extThemeKey = key;
            return extTheme;
        } catch (Exception ex) {
            prefs.put(Prefs.THEME_EXT, null);
            return null;
        }
    }

    void openExtensionPage(com.disgusty.droid_codology.ext.Extension e, com.disgusty.droid_codology.ext.OpenVsx.Item item) {
        closeExtensionPage();
        extensionPage = new ExtensionPage(this, e, item);
        root.addView(extensionPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        editor.hideKeyboard();
        closeOverlaySideBar();
    }

    void closeExtensionPage() {
        if (extensionPage == null) return;
        root.removeView(extensionPage);
        extensionPage = null;
    }

    /** After installing/removing/toggling: refresh lists and re-detect languages of open files. */
    void onExtensionsChanged() {
        if (extensionsView != null) extensionsView.refreshInstalled();
        for (Document d : docs) {
            if (d.language == com.disgusty.droid_codology.lang.Languages.PLAIN || com.disgusty.droid_codology.lang.Languages.byId(d.language.id) != d.language) {
                com.disgusty.droid_codology.lang.Language l = com.disgusty.droid_codology.lang.Languages.forFile(d.name, d.text.lineText(0));
                if (l != d.language) d.setLanguage(l);
            }
        }
        if (extThemeKey != null && loadExtensionTheme() == null) rebuild();
        editor.invalidate();
        // Code extensions may have come or gone: start over with the current set.
        extHost.restart();
        for (ExtViewContainer v : extViews.values()) v.dispose();
        extViews.clear();
        if (sideBar != null) showSideView(sideView);
        updateStatus();
    }

    /** Installs a .vsix picked by the user. */
    public void installVsix(final java.io.File f) {
        showMessage(t("Installing..."));
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final com.disgusty.droid_codology.ext.Extension e = extensions.install(f);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            onExtensionsChanged();
                            if (e != null) openExtensionPage(e, null);
                        }
                    });
                } catch (final Exception ex) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showMessage(f("Install failed: {0}", String.valueOf(ex.getMessage())));
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------ markdown preview & formatting

    void openPreview(boolean side) {
        Document d = active;
        if (d == null || editorArea == null) return;
        closePreview();
        joinPanes();
        preview = new MarkdownPreview(this, d);
        boolean split = side && !narrow;
        int w = editorArea.getWidth() > 0 ? editorArea.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(split ? w / 2 : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT);
        editorArea.addView(preview, lp);
        if (split) {
            FrameLayout.LayoutParams elp = (FrameLayout.LayoutParams) editor.getLayoutParams();
            elp.rightMargin = w / 2;
            editor.setLayoutParams(elp);
        }
        editor.hideKeyboard();
    }

    void closePreview() {
        if (preview == null) return;
        editorArea.removeView(preview);
        preview = null;
        FrameLayout.LayoutParams elp = (FrameLayout.LayoutParams) editor.getLayoutParams();
        if (elp.rightMargin != 0) {
            elp.rightMargin = 0;
            editor.setLayoutParams(elp);
        }
    }

    private final Runnable previewUpdate = new Runnable() {
        @Override
        public void run() {
            if (preview != null) preview.update();
        }
    };

    void formatDocument() {
        final Document d = active;
        if (d == null || d.readOnly) return;
        // Language server first, then extension formatters, then the built-in one.
        final Runnable builtin = new Runnable() {
            @Override
            public void run() {
                if (d == active) builtinFormat(d);
            }
        };
        final Runnable viaExtensions = new Runnable() {
            @Override
            public void run() {
                if (!extHost.format(d, builtin)) builtin.run();
            }
        };
        if (!lsp.format(d, viaExtensions)) viaExtensions.run();
    }

    private void builtinFormat(Document d) {
        String formatted = com.disgusty.droid_codology.lang.Formatter.format(d.language.id, d.text.toString(), d.engine.indentUnit());
        if (formatted == null) {
            showMessage(f("There is no formatter for '{0}' files installed.", d.language.id));
            return;
        }
        if (formatted.contentEquals(d.text)) return;
        int caretLine = d.text.lineOfOffset(d.engine.sel.caret());
        d.engine.replaceAndSelect(0, d.text.length(), formatted, 0, 0);
        goTo(Math.min(caretLine, d.text.lineCount() - 1), 0);
    }

    /** Insert Snippet: a quick pick of the language's snippets. */
    private void pickSnippet() {
        final Document d = active;
        if (d == null) return;
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        for (final com.disgusty.droid_codology.lang.Snippets.Item s : com.disgusty.droid_codology.lang.Snippets.forLanguage(d.language.id)) {
            items.add(new QuickPick.Item(s.prefix + "  " + s.description, null, new Runnable() {
                @Override
                public void run() {
                    EditEngine e = d.engine;
                    int st = e.sel.start(e.sel.primary()), en = e.sel.end(e.sel.primary());
                    e.insertSnippet(st, en, s.body, snippetVariables(d));
                    editor.requestFocus();
                }
            }));
        }
        quickPick.showItems(t("Insert Snippet"), items);
    }

    /** Values of VS Code's snippet variables for a document. */
    com.disgusty.droid_codology.text.Snippet.Variables snippetVariables(final Document d) {
        return new com.disgusty.droid_codology.text.Snippet.Variables() {
            @Override
            public String get(String n) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                EditEngine e = d.engine;
                int caret = e.sel.caret();
                switch (n) {
                    case "TM_FILENAME": return d.name;
                    case "TM_FILENAME_BASE": { int dot = d.name.lastIndexOf('.'); return dot > 0 ? d.name.substring(0, dot) : d.name; }
                    case "TM_DIRECTORY": return d.file != null ? d.file.getParent() : "";
                    case "TM_FILEPATH": return d.path();
                    case "RELATIVE_FILEPATH": return d.file != null && workspace != null ? workspace.relative(d.file) : d.name;
                    case "WORKSPACE_NAME": return workspace != null ? workspace.name() : "";
                    case "WORKSPACE_FOLDER": return workspace != null ? workspace.root.getAbsolutePath() : "";
                    case "TM_LINE_NUMBER": return String.valueOf(e.text.lineOfOffset(caret) + 1);
                    case "TM_LINE_INDEX": return String.valueOf(e.text.lineOfOffset(caret));
                    case "TM_CURRENT_LINE": return e.text.lineText(e.text.lineOfOffset(caret));
                    case "TM_SELECTED_TEXT": return e.text.substring(e.sel.start(e.sel.primary()), e.sel.end(e.sel.primary()));
                    case "CURRENT_YEAR": return String.valueOf(cal.get(java.util.Calendar.YEAR));
                    case "CURRENT_YEAR_SHORT": return String.format(java.util.Locale.ROOT, "%02d", cal.get(java.util.Calendar.YEAR) % 100);
                    case "CURRENT_MONTH": return String.format(java.util.Locale.ROOT, "%02d", cal.get(java.util.Calendar.MONTH) + 1);
                    case "CURRENT_DATE": return String.format(java.util.Locale.ROOT, "%02d", cal.get(java.util.Calendar.DAY_OF_MONTH));
                    case "CURRENT_HOUR": return String.format(java.util.Locale.ROOT, "%02d", cal.get(java.util.Calendar.HOUR_OF_DAY));
                    case "CURRENT_MINUTE": return String.format(java.util.Locale.ROOT, "%02d", cal.get(java.util.Calendar.MINUTE));
                    case "CURRENT_SECOND": return String.format(java.util.Locale.ROOT, "%02d", cal.get(java.util.Calendar.SECOND));
                    case "CURRENT_SECONDS_UNIX": return String.valueOf(System.currentTimeMillis() / 1000);
                    case "CURRENT_MONTH_NAME": return new java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault()).format(cal.getTime());
                    case "CURRENT_MONTH_NAME_SHORT": return new java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(cal.getTime());
                    case "CURRENT_DAY_NAME": return new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(cal.getTime());
                    case "CURRENT_DAY_NAME_SHORT": return new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(cal.getTime());
                    case "RANDOM": return String.format(java.util.Locale.ROOT, "%06d", new java.util.Random().nextInt(1000000));
                    case "RANDOM_HEX": return String.format(java.util.Locale.ROOT, "%06x", new java.util.Random().nextInt(0x1000000));
                    case "UUID": return java.util.UUID.randomUUID().toString();
                    case "LINE_COMMENT": return d.language.lineComment != null ? d.language.lineComment : "//";
                    case "BLOCK_COMMENT_START": return d.language.blockOpen != null ? d.language.blockOpen : "/*";
                    case "BLOCK_COMMENT_END": return d.language.blockClose != null ? d.language.blockClose : "*/";
                    case "CLIPBOARD": {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return "";
                        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(activity);
                        return cs == null ? "" : cs.toString();
                    }
                    default: return null;
                }
            }
        };
    }

    private boolean editorHasMultiCursor() {
        return active != null && active.engine.sel.count() > 1;
    }

    private void cycle(int dir) {
        if (docs.isEmpty()) return;
        int i = docs.indexOf(active);
        i = (i + dir + docs.size()) % docs.size();
        activate(docs.get(i));
    }

    /** The selected text or word at the caret, used to seed find widgets. */
    String selectedWord() {
        if (active == null) return null;
        EditEngine e = active.engine;
        int p = e.sel.primary();
        if (!e.sel.isEmpty(p)) {
            if (e.sel.end(p) - e.sel.start(p) > 200) return null;
            String s = e.text.substring(e.sel.start(p), e.sel.end(p));
            return s.indexOf('\n') >= 0 ? null : s;
        }
        int[] w = e.wordAt(e.sel.head(p));
        return w[0] < w[1] ? e.text.substring(w[0], w[1]) : null;
    }

    public void hideSoftKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** Implemented by the activity: system file picker (Storage Access Framework). */
    public interface Opener {
        void pickFile();

        /** Pick a .vsix to install. */
        void pickVsix();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Reopens the last folder and editors (hot exit). Call before build(). */
    public void restoreSession() {
        Session.restore(this);
        if (deferred.isEmpty()) return;
        final ArrayList<File> files = new ArrayList<File>(deferred);
        deferred.clear();
        deferredLoading.addAll(files);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                for (final File f : files) {
                    Document opened = null;
                    try {
                        opened = Document.open(activity, f, null);
                    } catch (Exception ignored) {
                    } catch (OutOfMemoryError ignored) {
                    }
                    final Document d = opened;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            deferredLoading.remove(f);
                            if (d == null) return;
                            // The file may have been opened in the meantime (from the explorer or another app).
                            for (Document o : docs) if (f.equals(o.file)) return;
                            applyDocPrefs(d);
                            docs.add(d);
                            refreshTabs();
                        }
                    });
                }
            }
        });
    }

    private final ArrayList<File> deferred = new ArrayList<File>();
    /** Restored tabs still loading in the background: the session keeps them if the app leaves now. */
    final ArrayList<File> deferredLoading = new ArrayList<File>();

    void deferOpen(File f) {
        deferred.add(f);
    }

    private boolean iconStylePending;

    public void onPause() {
        lock.onPause();
        if (lock.enabled() && lock.delayMinutes() == 0) {
            // Locked before Android takes the Recents snapshot.
            lock.lockNow();
            if (root != null) lock.attach(this, root);
        }
        Session.save(this);
        autoSync();
        if (iconStylePending) {
            // Switched while in the background: some launchers close a task whose alias gets disabled.
            int style = prefs.i(Prefs.STYLE, Skin.ORIGINAL);
            LauncherIcons.apply(activity, style);
            prefs.put("state.iconStyle", style);
            iconStylePending = false;
        }
        if ("onFocusChange".equals(prefs.str(Prefs.AUTO_SAVE, "off"))) autoSave.run();
    }

    public void onResume() {
        if (lock.onResume() && root != null) lock.attach(this, root);
        if (extHostStarted) autoSync();
        // Reload files changed on disk (VS Code does this silently when there are no local edits).
        for (final Document d : new ArrayList<Document>(docs)) {
            if (!d.changedOnDisk()) continue;
            if (!d.isDirty()) {
                try {
                    d.reload(activity);
                } catch (Exception ignored) {
                }
            } else if (d == active) {
                Skin.DialogSpec s = new Skin.DialogSpec();
                s.title = t("File changed on disk");
                s.message = t("The file has been changed on disk. Do you want to reload it?");
                s.positive = t("Reload");
                s.negative = t("Keep My Changes");
                s.onPositive = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            d.reload(activity);
                        } catch (Exception ignored) {
                        }
                        refreshTabs();
                    }
                };
                s.onNegative = new Runnable() {
                    @Override
                    public void run() {
                        if (d.file != null) {
                            d.diskStamp = d.file.lastModified();
                            d.diskLength = d.file.length();
                        }
                    }
                };
                skin.dialog(s).show();
            }
        }
        if (explorer != null) explorer.refresh();
        editor.invalidate();
    }

    void setActiveInternal(Document d) {
        active = d;
    }

    ArrayList<Document> docsInternal() {
        return docs;
    }

    int nextUntitled() {
        return untitledCounter;
    }

    void setNextUntitled(int n) {
        untitledCounter = n;
    }

    boolean isZen() {
        return zen;
    }

    QuickPick quickPick() {
        return quickPick;
    }

    ExplorerView explorer() {
        return explorer;
    }

    public void focusEditor() {
        if (active != null) editor.showKeyboard();
    }

    void showDocumentPublic(Document d) {
        showDocument(d);
    }

    static Intent viewIntent(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.fromFile(f));
        return i;
    }

    TextWatcher simpleWatcher(final Runnable r) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                r.run();
            }
        };
    }

    ImageView smallIcon(String name, int color) {
        ImageView iv = new ImageView(activity);
        iv.setImageDrawable(skin.icon(name, color, 16));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        return iv;
    }
}
