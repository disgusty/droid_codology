package com.disgusty.droid_codology.work;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.disgusty.droid_codology.ui.Skin;

import java.util.ArrayList;

import static com.disgusty.droid_codology.work.I18n.t;

/** The Settings editor: grouped settings with VS Code ids as descriptions. */
final class SettingsPage {
    private final Workbench wb;
    private final Skin skin;
    private final Prefs p;
    private LinearLayout list;

    SettingsPage(Workbench wb) {
        this.wb = wb;
        this.skin = wb.skin;
        this.p = wb.prefs;
    }

    /** Items Settings can be opened at (Workbench.openSettings(focus)). */
    static final String FOCUS_ALPINE = "alpine";
    private String focus;
    private View focusView;

    View build() {
        return build(null);
    }

    View build(String focus) {
        this.focus = focus;
        LinearLayout page = new LinearLayout(wb.activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(skin.windowBg);
        page.setClickable(true);
        Skin.Action back = new Skin.Action("arrow_left", t("Close"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.closeSettings();
            }
        });
        page.addView(skin.titleBar(back, t("Settings"), null, null, null));
        ScrollView sv = new ScrollView(wb.activity);
        list = new LinearLayout(wb.activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, skin.dp(24));
        sv.addView(list);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        fill();
        if (focusView != null) showFocus(sv, focusView);
        return page;
    }

    /** Scrolls to {@code v} once laid out and flashes it in the accent color. */
    private void showFocus(final ScrollView sv, final View v) {
        sv.post(new Runnable() {
            @Override
            public void run() {
                int y = 0;
                for (View x = v; x != null && x != sv; x = (View) x.getParent()) y += x.getTop();
                sv.scrollTo(0, Math.max(0, y - skin.dp(96)));
                final android.graphics.drawable.Drawable old = v.getBackground();
                v.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Skin.withAlpha(skin.accent, 0x40)));
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setBackgroundDrawable(old);
                    }
                }, 2500);
            }
        });
    }

    private void header(String s) {
        TextView t = skin.text(s, skin.uiSp() + 2, skin.sideBarFg, skin.medium());
        t.setPadding(skin.dp(16), skin.dp(20), skin.dp(16), skin.dp(6));
        list.addView(t);
    }

    private void toggle(String title, final String key, boolean def, String desc, final boolean rebuild) {
        list.addView(skin.switchRow(title, desc != null ? desc : key, p.b(key, def), new Skin.ToggleListener() {
            @Override
            public void onToggle(boolean on) {
                p.put(key, on);
                if (rebuild) wb.rebuild();
                else wb.applyEditorPrefs();
            }
        }));
    }

    /** A setting with a fixed set of values, chosen in the quick pick. */
    private void choice(final String title, final String key, final String def, final String[] values, final String[] labels, final boolean rebuild) {
        String cur = p.str(key, def);
        String curLabel = cur;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) curLabel = labels[i];
        LinearLayout row = skin.listItem(null, title, curLabel + "   —   " + key);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
                for (int i = 0; i < values.length; i++) {
                    final String val = values[i];
                    items.add(new QuickPick.Item(labels[i], val.equals(p.str(key, def)) ? "✓" : null, new Runnable() {
                        @Override
                        public void run() {
                            p.put(key, val);
                            if (rebuild) {
                                wb.closeSettings();
                                wb.rebuild();
                                wb.openSettings();
                            } else {
                                wb.applyEditorPrefs();
                                refill();
                            }
                        }
                    }));
                }
                wb.quickPick().showItems(title, items);
            }
        });
        list.addView(row);
    }

    private void number(final String title, final String key, final float def, final float[] values, final boolean isInt) {
        String[] vals = new String[values.length], labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            vals[i] = isInt ? String.valueOf((int) values[i]) : String.valueOf(values[i]);
            labels[i] = vals[i];
        }
        float cur = isInt ? p.i(key, (int) def) : p.f(key, def);
        LinearLayout row = skin.listItem(null, title, (isInt ? String.valueOf((int) cur) : String.valueOf(cur)) + "   —   " + key);
        final String[] fv = vals;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
                for (final String val : fv) {
                    items.add(new QuickPick.Item(val, null, new Runnable() {
                        @Override
                        public void run() {
                            if (isInt) p.put(key, Integer.parseInt(val));
                            else p.put(key, Float.parseFloat(val));
                            wb.applyEditorPrefs();
                            refill();
                        }
                    }));
                }
                wb.quickPick().showItems(title, items);
            }
        });
        list.addView(row);
    }

    static String[] styleNames() {
        return new String[]{t("Original (VS Code)"), t("Holo (Android 3.0–4.4)"), t("Material Design (Android 5–11)"), t("Material You (Android 12+)"), t("Matrix")};
    }

    /** Rebuilds the window in the new look and reopens this page. */
    private void restyle() {
        wb.closeSettings();
        wb.rebuild();
        wb.openSettings();
    }

    private void styleRow() {
        final String[] names = styleNames();
        final int cur = p.i(Prefs.STYLE, Skin.ORIGINAL);
        LinearLayout row = skin.listItem(skin.icon("brush", skin.descriptionFg, 24), t("Interface Style"), names[cur]);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
                for (int i = 0; i < names.length; i++) {
                    final int st = i;
                    items.add(new QuickPick.Item(names[i], i == cur ? "✓" : null, new Runnable() {
                        @Override
                        public void run() {
                            p.put(Prefs.STYLE, st);
                            restyle();
                        }
                    }));
                }
                wb.quickPick().showItems(t("Interface Style"), items);
            }
        });
        list.addView(row);
    }

    private void accentRow() {
        final int style = p.i(Prefs.STYLE, Skin.ORIGINAL);
        int stored = Accents.stored(p, style);
        String desc = stored == 0 ? t("Default") : stored == Accents.DYNAMIC ? t("Wallpaper") : Accents.hex(skin.accent);
        LinearLayout row = skin.listItem(skin.icon("palette", skin.accent, 24), t("Accent Color"), desc);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AccentDialog(wb, style, new Runnable() {
                    @Override
                    public void run() {
                        restyle();
                    }
                }).show();
            }
        });
        list.addView(row);
    }

    // ------------------------------------------------------------------ activity bar editor

    private String barLabel(String id, java.util.Map<String, ExtViewContainer.Spec> containers) {
        int k = java.util.Arrays.asList(Workbench.BUILTIN_VIEWS).indexOf(id);
        if (k >= 0) return wb.builtinLabel(k);
        ExtViewContainer.Spec s = containers.get(id);
        return s != null ? s.title : id;
    }

    private String barIcon(String id, java.util.Map<String, ExtViewContainer.Spec> containers) {
        int k = java.util.Arrays.asList(Workbench.BUILTIN_VIEWS).indexOf(id);
        if (k >= 0) return Workbench.BUILTIN_ICONS[k];
        ExtViewContainer.Spec s = containers.get(id);
        return s != null ? s.iconName : "extensions";
    }

    /** Add, remove and reorder the views of the activity bar (also extension views). */
    void editActivityBar() {
        final LinearLayout box = new LinearLayout(wb.activity);
        box.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<String> ids = new ArrayList<String>(wb.barIds());
        final Runnable[] render = new Runnable[1];
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        render[0] = new Runnable() {
            @Override
            public void run() {
                box.removeAllViews();
                final java.util.LinkedHashMap<String, ExtViewContainer.Spec> containers = wb.extContainers();
                for (int i = 0; i < ids.size(); i++) {
                    final int at = i;
                    final String id = ids.get(i);
                    LinearLayout row = new LinearLayout(wb.activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    android.widget.ImageView icon = new android.widget.ImageView(wb.activity);
                    icon.setImageDrawable(skin.icon(barIcon(id, containers), skin.sideBarFg, 20));
                    icon.setScaleType(android.widget.ImageView.ScaleType.CENTER);
                    row.addView(icon, new LinearLayout.LayoutParams(skin.dp(36), skin.dp(40)));
                    TextView label = skin.text(barLabel(id, containers), skin.uiSp(), skin.sideBarFg, skin.regular());
                    label.setSingleLine(true);
                    row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    row.addView(small("arrow_up", at > 0, new Runnable() {
                        @Override
                        public void run() {
                            ids.add(at - 1, ids.remove(at));
                            wb.setBarIds(ids);
                            render[0].run();
                        }
                    }));
                    row.addView(small("arrow_down", at < ids.size() - 1, new Runnable() {
                        @Override
                        public void run() {
                            ids.add(at + 1, ids.remove(at));
                            wb.setBarIds(ids);
                            render[0].run();
                        }
                    }));
                    row.addView(small("close", ids.size() > 1, new Runnable() {
                        @Override
                        public void run() {
                            ids.remove(at);
                            wb.setBarIds(ids);
                            render[0].run();
                        }
                    }));
                    box.addView(row);
                }
                TextView add = skin.button("+ " + t("Add a View..."), false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
                        for (String b : Workbench.BUILTIN_VIEWS) {
                            if (ids.contains(b)) continue;
                            final String id = b;
                            items.add(new QuickPick.Item(barLabel(id, containers), null, new Runnable() {
                                @Override
                                public void run() {
                                    ids.add(id);
                                    wb.setBarIds(ids);
                                    render[0].run();
                                }
                            }));
                        }
                        for (final ExtViewContainer.Spec s : containers.values()) {
                            if (ids.contains(s.key)) continue;
                            QuickPick.Item it = new QuickPick.Item(s.title, null, new Runnable() {
                                @Override
                                public void run() {
                                    ids.add(s.key);
                                    wb.setBarIds(ids);
                                    render[0].run();
                                }
                            });
                            it.description = s.extId;
                            items.add(it);
                        }
                        if (items.isEmpty()) {
                            wb.showMessage(t("Everything is already on the bar. Install extensions with views to add more."));
                            return;
                        }
                        // The picker lives in the window under the dialog: step out while picking, then come back.
                        if (dlg[0] != null) dlg[0].dismiss();
                        QuickPick qp = wb.quickPick();
                        qp.showItems(t("Add a View to the Activity Bar"), items);
                        qp.onDismiss = new Runnable() {
                            @Override
                            public void run() {
                                // Posted so a picked item's action has run first.
                                wb.activity.getWindow().getDecorView().post(new Runnable() {
                                    @Override
                                    public void run() {
                                        editActivityBar();
                                    }
                                });
                            }
                        };
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = skin.dp(8);
                box.addView(add, lp);
                TextView hint = skin.text(t("Extension views come from installed extensions that add their own icon to the activity bar."),
                        skin.uiSp() - 2, skin.descriptionFg, skin.regular());
                hint.setPadding(0, skin.dp(8), 0, 0);
                box.addView(hint);
            }
        };
        render[0].run();
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = t("Customize Activity Bar");
        s.content = box; // the dialog scrolls its body itself
        s.positive = t("Done");
        s.neutral = t("Reset");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                refill();
            }
        };
        s.onNeutral = new Runnable() {
            @Override
            public void run() {
                wb.prefs.put("workbench.activityBar.items", null);
                wb.setBarIds(wb.barIds());
                refill();
            }
        };
        dlg[0] = skin.dialog(s);
        dlg[0].show();
    }

    /** Returns an error to show (the dialog stays), or null when done. */
    private interface PasswordAction {
        String run(String[] values, boolean neutral);
    }

    private void newPassword(final String current) {
        passwordDialog(current == null ? t("Set App Password") : t("Change App Password"),
                new String[]{t("New password (at least 4 characters)"), t("Repeat the password")}, t("Save"), null, new PasswordAction() {
                    @Override
                    public String run(String[] v, boolean neutral) {
                        if (v[0].length() < 4) return t("At least 4 characters.");
                        if (!v[0].equals(v[1])) return t("The passwords don't match.");
                        wb.lock.setPassword(v[0]);
                        wb.showMessage(t("The app password is on."));
                        refill();
                        return null;
                    }
                });
    }

    private void passwordDialog(String title, String[] hints, String positive, String neutral, final PasswordAction action) {
        LinearLayout box = new LinearLayout(wb.activity);
        box.setOrientation(LinearLayout.VERTICAL);
        final android.widget.EditText[] fields = new android.widget.EditText[hints.length];
        for (int i = 0; i < hints.length; i++) {
            fields[i] = skin.input(hints[i]);
            fields[i].setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            // (setSingleLine after this would undo the masking)
            fields[i].setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = i == 0 ? 0 : skin.dp(8);
            box.addView(fields[i], lp);
        }
        final TextView error = skin.text("", skin.uiSp() - 1, skin.errorFg, skin.regular());
        error.setPadding(0, skin.dp(8), 0, 0);
        box.addView(error);
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = title;
        s.content = box;
        s.positive = positive;
        s.negative = t("Cancel");
        s.neutral = neutral;
        s.keepOpen = true;
        final Runnable submit = new Runnable() {
            @Override
            public void run() {
                String[] v = new String[fields.length];
                for (int i = 0; i < v.length; i++) v[i] = fields[i].getText().toString();
                String err = action.run(v, false);
                if (err == null) dlg[0].dismiss();
                else error.setText(err);
            }
        };
        s.onPositive = submit;
        if (neutral != null) {
            s.onNeutral = new Runnable() {
                @Override
                public void run() {
                    String[] v = new String[fields.length];
                    for (int i = 0; i < v.length; i++) v[i] = fields[i].getText().toString();
                    String err = action.run(v, true);
                    if (err == null) dlg[0].dismiss();
                    else error.setText(err);
                }
            };
        }
        dlg[0] = skin.dialog(s);
        dlg[0].show();
        fields[0].requestFocus();
    }

    private View small(String icon, boolean enabled, final Runnable r) {
        android.widget.ImageView b = new android.widget.ImageView(wb.activity);
        b.setImageDrawable(skin.icon(icon, enabled ? skin.sideBarFg : skin.descriptionFg, 18));
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        b.setAlpha(enabled ? 1f : 0.3f);
        b.setEnabled(enabled);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(skin.dp(40), skin.dp(40)));
        return b;
    }

    private void refill() {
        if (list == null) return; // opened from the command palette, not the page
        list.removeAllViews();
        fill();
    }

    private void fill() {
        header(t("Appearance"));
        styleRow();
        accentRow();
        toggle(t("Falling Code"), Prefs.MATRIX_RAIN, false, t("The digital rain of The Matrix behind the interface (uses a little more battery)"), true);
        choice(t("Color Theme"), Prefs.THEME, "system", new String[]{"system", "dark", "light"},
                new String[]{t("System"), t("Dark"), t("Light")}, true);
        choice(t("Language"), "workbench.language", "system", new String[]{"system", "en", "ru"},
                new String[]{t("System"), "English", "Русский"}, true);
        toggle(t("Command Center"), Prefs.COMMAND_CENTER, true, null, true);
        toggle(t("Breadcrumbs"), Prefs.BREADCRUMBS, true, null, true);
        toggle(t("Extra Keys Row"), Prefs.EXTRA_KEYS, true, null, true);

        header(t("Editor"));
        number(t("Font Size"), Prefs.FONT_SIZE, 14, new float[]{8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32}, false);
        number(t("Line Height"), Prefs.LINE_HEIGHT, 1.35f, new float[]{1.0f, 1.15f, 1.25f, 1.35f, 1.5f, 1.75f, 2.0f}, false);
        number(t("Tab Size"), Prefs.TAB_SIZE, 4, new float[]{1, 2, 3, 4, 6, 8}, true);
        toggle(t("Insert Spaces"), Prefs.INSERT_SPACES, true, null, false);
        toggle(t("Detect Indentation"), Prefs.DETECT_INDENT, true, null, false);
        toggle(t("Word Wrap"), Prefs.WORD_WRAP, false, null, false);
        choice(t("Line Numbers"), Prefs.LINE_NUMBERS, "on", new String[]{"on", "off", "relative"},
                new String[]{t("on"), t("off"), t("Relative Line Numbers")}, false);
        toggle(t("Render Whitespace"), Prefs.WHITESPACE, false, null, false);
        toggle(t("Indentation Guides"), Prefs.INDENT_GUIDES, true, null, false);
        toggle(t("Highlight Current Line"), Prefs.HIGHLIGHT_LINE, true, null, false);
        toggle(t("Bracket Pair Colorization"), Prefs.BRACKET_COLORS, true, null, true);
        toggle(t("Match Brackets"), Prefs.MATCH_BRACKETS, true, null, false);
        toggle(t("Folding"), Prefs.FOLDING, true, null, true);
        toggle(t("Sticky Scroll"), Prefs.STICKY_SCROLL, true, t("Pin the headers of enclosing blocks while scrolling."), false);
        toggle(t("Minimap"), Prefs.MINIMAP, wb.wideScreen(), null, false);
        toggle(t("Quick Suggestions"), Prefs.SUGGEST, true, t("Show completions while typing."), false);
        toggle(t("Auto Closing Brackets"), Prefs.AUTO_CLOSE, true, null, false);
        toggle(t("Auto Indent"), Prefs.AUTO_INDENT, true, null, false);
        toggle(t("Keyboard Suggestions"), Prefs.SUGGESTIONS, false, t("Let the on-screen keyboard predict and correct words."), true);

        header(t("Files"));
        choice(t("Auto Save"), Prefs.AUTO_SAVE, "off", new String[]{"off", "afterDelay", "onFocusChange"},
                new String[]{t("off"), t("afterDelay"), t("onFocusChange")}, false);
        toggle(t("Trim Trailing Whitespace on Save"), Prefs.TRIM_WS, false, null, false);
        toggle(t("Insert Final Newline"), Prefs.FINAL_NEWLINE, false, null, false);
        toggle(t("Hot Exit"), Prefs.HOT_EXIT, true, t("Remember unsaved files between sessions."), false);
        toggle(t("Show Hidden Files"), Prefs.SHOW_HIDDEN, false, null, false);

        header(t("Security"));
        final AppLock lock = wb.lock;
        LinearLayout pw = skin.listItem(skin.icon("lock", skin.descriptionFg, 24), t("App Password"),
                lock.enabled() ? t("On: asked when the app opens") : t("Off"));
        pw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!lock.enabled()) {
                    newPassword(null);
                    return;
                }
                // Changing or removing it needs the current one.
                passwordDialog(t("App Password"), new String[]{t("Current password")}, t("Change"), t("Remove"), new PasswordAction() {
                    @Override
                    public String run(String[] v, boolean neutral) {
                        if (!lock.check(v[0])) return t("Wrong password.");
                        if (neutral) {
                            lock.clear();
                            wb.showMessage(t("The app password is off."));
                            refill();
                        } else {
                            newPassword(v[0]);
                        }
                        return null;
                    }
                });
            }
        });
        list.addView(pw);
        if (lock.enabled()) {
            final int[] minutes = {0, 1, 5, 15, 60};
            String[] labels = {t("Right away"), t("After 1 minute"), t("After 5 minutes"), t("After 15 minutes"), t("After 1 hour")};
            int cur = 1;
            for (int i = 0; i < minutes.length; i++) if (minutes[i] == lock.delayMinutes()) cur = i;
            final String[] fl = labels;
            LinearLayout when = skin.listItem(null, t("Lock in the Background"), labels[cur]);
            when.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    java.util.ArrayList<QuickPick.Item> items = new java.util.ArrayList<QuickPick.Item>();
                    for (int i = 0; i < minutes.length; i++) {
                        final int m = minutes[i];
                        items.add(new QuickPick.Item(fl[i], m == lock.delayMinutes() ? "✓" : null, new Runnable() {
                            @Override
                            public void run() {
                                lock.setDelayMinutes(m);
                                refill();
                            }
                        }));
                    }
                    wb.quickPick.showItems(t("Lock in the Background"), items);
                }
            });
            list.addView(when);
        }

        header(t("Terminal"));
        final com.disgusty.droid_codology.linux.AlpineLinux alpine = wb.alpine();
        if (alpine.supported() && !alpine.installed()) {
            LinearLayout get = skin.listItem(skin.icon("cloud", skin.accent, 24), t("Download Alpine Linux"),
                    I18n.f("Alpine Linux {0} for the alpine mode (no root needed): apk, git, Node.js, Python and more. About 4 MB to download.",
                            com.disgusty.droid_codology.linux.AlpineLinux.VERSION));
            get.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    wb.installAlpine(new Runnable() {
                        @Override
                        public void run() {
                            refill();
                        }
                    });
                }
            });
            list.addView(get);
            if (FOCUS_ALPINE.equals(focus)) focusView = get;
        } else if (alpine.supported()) {
            View mode = skin.switchRow(t("Alpine Mode"),
                    t("New terminals run in Alpine Linux. Also switched with standard / alpine at the top of the Manage and app menus."),
                    wb.alpineMode(), new Skin.ToggleListener() {
                        @Override
                        public void onToggle(boolean on) {
                            wb.setAlpineMode(on);
                        }
                    });
            list.addView(mode);
            if (FOCUS_ALPINE.equals(focus)) focusView = mode;
            list.addView(skin.switchRow(t("Run Extensions on Node.js"),
                    t("In alpine mode, extension code runs on Node.js in Alpine Linux, like in desktop VS Code: files, processes, network and npm packages work."),
                    p.b(Prefs.NODE_EXT_HOST, false), new Skin.ToggleListener() {
                        @Override
                        public void onToggle(boolean on) {
                            if (on) {
                                wb.ensureNodeHost();
                                return;
                            }
                            p.put(Prefs.NODE_EXT_HOST, false);
                            wb.extHost.restart();
                        }
                    }));
            LinearLayout rm = skin.listItem(skin.icon("trash", skin.descriptionFg, 24), t("Remove Alpine Linux"),
                    I18n.f("Alpine Linux {0}; your projects stay", com.disgusty.droid_codology.linux.AlpineLinux.VERSION));
            rm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    wb.commands.run("alpine.remove");
                }
            });
            list.addView(rm);
        } else {
            list.addView(skin.listItem(null, t("Alpine Linux"), t("Not available in this build or on this processor.")));
        }

        header(t("Activity Bar"));
        StringBuilder names = new StringBuilder();
        java.util.LinkedHashMap<String, ExtViewContainer.Spec> containers = wb.extContainers();
        for (String id : wb.barIds()) {
            if (names.length() > 0) names.append(", ");
            names.append(barLabel(id, containers));
        }
        LinearLayout bar = skin.listItem(skin.icon("sidebar", skin.descriptionFg, 24), t("Customize Activity Bar"), names.toString());
        bar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editActivityBar();
            }
        });
        list.addView(bar);

        header(t("Languages & Git"));
        list.addView(skin.switchRow(t("Language Servers"), t("Diagnostics, completion, go to definition and rename from the language servers of your droid_codology server"),
                p.b("lsp.enabled", true), new Skin.ToggleListener() {
                    @Override
                    public void onToggle(boolean on) {
                        p.put("lsp.enabled", on);
                        wb.lsp.reset();
                    }
                }));
        String gitName = p.str("git.user.name", "");
        LinearLayout who = skin.listItem(skin.icon("person", skin.descriptionFg, 24), t("Git Author"),
                gitName.isEmpty() ? t("Asked on the first commit") : gitName + " <" + p.str("git.user.email", "") + ">");
        who.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.prompt(t("Your name"), "", p.str("git.user.name", ""), new Workbench.TextCallback() {
                    @Override
                    public void onText(final String name) {
                        wb.prompt(t("Your email"), "you@example.com", p.str("git.user.email", ""), new Workbench.TextCallback() {
                            @Override
                            public void onText(String email) {
                                p.put("git.user.name", name);
                                p.put("git.user.email", email);
                                refill();
                            }
                        });
                    }
                });
            }
        });
        list.addView(who);

        header(t("About"));
        String version = "";
        try {
            version = wb.activity.getPackageManager().getPackageInfo(wb.activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        list.addView(skin.listItem(null, "droid_codology " + version, "com.disgusty.droid_codology — Android " + android.os.Build.VERSION.RELEASE));
        // Credits, in small print (the Horus note stays as written, untranslated).
        credit("Alpine Linux — https://alpinelinux.org", "https://alpinelinux.org", skin.dp(4));
        credit("Horus — https://f-droid.org/packages/com.scariflabs.horus/ — вдохновитель :3",
                "https://f-droid.org/packages/com.scariflabs.horus/", 0);
    }

    /** A small line of the About section that opens {@code url} in the browser. */
    private void credit(String text, final String url, int top) {
        TextView t = skin.text(text, skin.uiSp() - 3, skin.textLink, skin.regular());
        t.setPadding(skin.dp(16), top, skin.dp(16), skin.dp(6));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    wb.activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                } catch (RuntimeException ignored) {
                    // no browser
                }
            }
        });
        list.addView(t);
    }
}
