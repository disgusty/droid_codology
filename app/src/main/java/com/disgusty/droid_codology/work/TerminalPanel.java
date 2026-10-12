package com.disgusty.droid_codology.work;

import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.disgusty.droid_codology.editor.EditorColors;
import com.disgusty.droid_codology.term.Pty;
import com.disgusty.droid_codology.term.TerminalSession;
import com.disgusty.droid_codology.term.TerminalView;
import com.disgusty.droid_codology.ui.Skin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/** The Terminal panel: shells on pseudo-terminals, several sessions, below the editor. */
final class TerminalPanel extends LinearLayout {
    private final Workbench wb;
    private final Skin skin;
    final TerminalView view;
    private final LinearLayout headerHolder;
    /** Sessions outlive the panel (the window is rebuilt on theme changes). */
    static final ArrayList<TerminalSession> sessions = new ArrayList<TerminalSession>();
    private static int counter = 1;
    private TerminalSession current;

    TerminalPanel(final Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        setOrientation(VERTICAL);
        EditorColors k = wb.editor.colors();
        setBackgroundColor(k.background);
        addView(skin.hline(skin.border));
        headerHolder = new LinearLayout(wb.activity);
        addView(headerHolder);
        view = new TerminalView(wb.activity);
        view.background = k.background;
        view.foreground = k.foreground;
        view.cursorColor = k.foreground;
        if (k.dark) view.darkPalette();
        else view.lightPalette();
        view.setPadding(skin.dp(8), skin.dp(2), skin.dp(4), 0);
        view.setTextSize(Math.max(10, wb.prefs.f(Prefs.FONT_SIZE, 14) - 1));
        Typeface tf = wb.editor.typeface();
        if (tf != null) view.setTypeface(tf);
        view.setHost(new TerminalView.Host() {
            @Override
            public boolean interceptTyped(TerminalView v, String s) {
                return wb.extraKeysConsume(s);
            }

            @Override
            public void onLongPress(TerminalView v, float x, float y) {
                contextMenu(v);
            }
        });
        addView(view, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (sessions.isEmpty()) newSession();
        else show(sessions.get(sessions.size() - 1));
    }

    private void header() {
        headerHolder.removeAllViews();
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("add", t("New Terminal"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                newSession();
            }
        }));
        acts.add(new Skin.Action("trash", t("Kill Terminal"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                kill();
            }
        }));
        acts.add(new Skin.Action("fullscreen", t("Maximize Panel Size"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.toggleTerminalMaximized();
            }
        }));
        acts.add(new Skin.Action("close", t("Hide Panel"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.hideTerminal();
            }
        }));
        String title = current == null ? t("Terminal") : (sessions.size() > 1 ? (sessions.indexOf(current) + 1) + ": " : "") + current.name
                + (current.emulator.title.isEmpty() ? "" : " — " + current.emulator.title);
        View h = skin.sideBarHeader(t("Terminal") + "  ·  " + title, acts);
        h.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSession();
            }
        });
        headerHolder.addView(h, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ sessions

    TerminalSession current() {
        return current;
    }

    /** A terminal in the app's mode: Android's shell (standard) or Alpine Linux (alpine). */
    void newSession() {
        newSession(wb.alpineMode());
    }

    void newSession(boolean linux) {
        if (!Pty.available()) {
            wb.showMessage(t("The terminal isn't available on this device."));
            return;
        }
        File home = new File(wb.activity.getFilesDir(), "home");
        home.mkdirs();
        File cwd = wb.workspace != null ? wb.workspace.root : home;
        com.disgusty.droid_codology.linux.AlpineLinux alpine = wb.alpine();
        final boolean useAlpine = linux && alpine.supported();
        if (useAlpine && !alpine.installed()) {
            // First use: download it, then open the terminal.
            wb.installAlpine(new Runnable() {
                @Override
                public void run() {
                    newSession(useAlpine);
                }
            });
            return;
        }
        TerminalSession s = new TerminalSession((useAlpine ? "alpine" : "sh") + (counter > 1 ? " " + counter : ""), 24, 80);
        counter++;
        try {
            if (useAlpine) {
                com.disgusty.droid_codology.linux.AlpineLinux.Launch l = alpine.launch(cwd.getAbsolutePath(), null);
                s.start(l.program, l.args, l.env, cwd.getAbsolutePath());
            } else {
                s.start("/system/bin/sh", new String[]{"sh"}, environment(home, cwd), cwd.getAbsolutePath());
            }
        } catch (Exception e) {
            wb.showMessage(f("Can't start the shell: {0}", String.valueOf(e.getMessage())));
            return;
        }
        sessions.add(s);
        show(s);
        view.requestFocus();
    }

    private String[] environment(File home, File cwd) {
        ArrayList<String> env = new ArrayList<String>();
        Map<String, String> sys = System.getenv();
        for (String key : new String[]{"PATH", "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ASSETS", "ANDROID_STORAGE",
                "EXTERNAL_STORAGE", "BOOTCLASSPATH", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "ASEC_MOUNTPOINT"}) {
            String v = sys.get(key);
            if (v != null) env.add(key + "=" + v);
        }
        if (sys.get("PATH") == null) env.add("PATH=/system/bin:/system/xbin");
        env.add("HOME=" + home.getAbsolutePath());
        env.add("PWD=" + cwd.getAbsolutePath());
        env.add("TMPDIR=" + wb.activity.getCacheDir().getAbsolutePath());
        env.add("TERM=xterm-256color");
        env.add("COLORTERM=truecolor");
        env.add("LANG=en_US.UTF-8");
        env.add("TERM_PROGRAM=droid_codology");
        env.add("PS1=$ ");
        return env.toArray(new String[env.size()]);
    }

    void show(final TerminalSession s) {
        current = s;
        s.setListener(new TerminalSession.Listener() {
            @Override
            public void onUpdate(TerminalSession x) {
                if (x == current) {
                    view.invalidate();
                    if (!x.emulator.title.equals(lastTitle)) {
                        lastTitle = x.emulator.title;
                        header();
                    }
                }
            }

            @Override
            public void onExit(TerminalSession x, int code) {
                synchronized (x.emulator) {
                    x.emulator.feed("\r\n\033[2m" + f("[Process completed with exit code {0}]", code) + "\033[0m\r\n");
                }
                if (x == current) view.invalidate();
            }
        });
        view.setSession(s);
        header();
    }

    private String lastTitle = "";

    private void pickSession() {
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        for (int i = 0; i < sessions.size(); i++) {
            final TerminalSession s = sessions.get(i);
            QuickPick.Item it = new QuickPick.Item((i + 1) + ": " + s.name, s == current ? "✓" : null, new Runnable() {
                @Override
                public void run() {
                    show(s);
                }
            });
            it.description = s.exited() ? t("exited") : s.emulator.title;
            items.add(it);
        }
        items.add(new QuickPick.Item(t("New Terminal"), null, new Runnable() {
            @Override
            public void run() {
                newSession();
            }
        }));
        wb.quickPick().showItems(t("Select Terminal"), items);
    }

    void kill() {
        if (current == null) return;
        TerminalSession s = current;
        s.finish();
        sessions.remove(s);
        if (sessions.isEmpty()) {
            current = null;
            view.setSession(null);
            wb.hideTerminal();
        } else {
            show(sessions.get(sessions.size() - 1));
        }
    }

    /** Sends text to the shell as if typed (Run Selected Text, Run Active File). */
    void run(String text) {
        if (current == null || current.exited()) newSession();
        if (current != null) current.paste(text.endsWith("\n") ? text : text + "\n");
    }

    private void contextMenu(View anchor) {
        if (current == null) return;
        List<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
        m.add(new Skin.MenuItem(t("Paste"), new Runnable() {
            @Override
            public void run() {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) wb.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(wb.activity);
                    if (s != null) current.paste(s.toString());
                }
            }
        }));
        m.add(new Skin.MenuItem(t("Copy All"), new Runnable() {
            @Override
            public void run() {
                String text;
                synchronized (current.emulator) {
                    text = current.emulator.allText();
                }
                wb.copyText(text);
                wb.showMessage(t("Copied"));
            }
        }));
        m.add(new Skin.MenuItem(t("Clear"), new Runnable() {
            @Override
            public void run() {
                current.write("\u000c");
            }
        }));
        m.add(new Skin.MenuItem(t("Kill Terminal"), new Runnable() {
            @Override
            public void run() {
                kill();
            }
        }));
        skin.popup(anchor, m);
    }
}
