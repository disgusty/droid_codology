package com.disgusty.droid_codology.work;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.disgusty.droid_codology.ext.Extension;
import com.disgusty.droid_codology.ext.OpenVsx;
import com.disgusty.droid_codology.lang.MarkdownHtml;
import com.disgusty.droid_codology.ui.Skin;

import java.io.File;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/** An extension's page: summary, install/uninstall/enable buttons and its README. */
final class ExtensionPage extends LinearLayout {
    private final Workbench wb;
    private final Skin skin;
    private Extension installed;
    private final OpenVsx.Item item;
    private final LinearLayout buttons;
    private final TextView progress;
    /** Says why a desktop-only extension's code won't run here. */
    private final TextView warning;
    /** From Open VSX: published only as desktop builds (null until known). */
    private Boolean desktopOnly;
    private boolean installAnyway;
    /** Download of the Alpine Linux build, when the Node.js host can run it. */
    private String alpineUrl;
    private final WebView readme;

    ExtensionPage(Workbench wb, Extension ext, OpenVsx.Item item) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        this.installed = ext;
        this.item = item;
        setOrientation(VERTICAL);
        setBackgroundColor(skin.windowBg);
        setClickable(true);
        String title = ext != null ? ext.displayName : item.displayName;
        Skin.Action back = new Skin.Action("arrow_left", t("Close"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                ExtensionPage.this.wb.closeExtensionPage();
            }
        });
        addView(skin.titleBar(back, title, ext != null ? ext.id : item.id(), null, null));
        LinearLayout head = new LinearLayout(wb.activity);
        head.setOrientation(VERTICAL);
        head.setPadding(skin.dp(16), skin.dp(12), skin.dp(16), skin.dp(8));
        String desc = ext != null ? ext.description : item.description;
        if (!desc.isEmpty()) {
            TextView d = skin.label(desc);
            head.addView(d);
        }
        String meta = ext != null ? ext.publisher + "  ·  v" + ext.version + (ext.capabilities().isEmpty() ? "" : "  ·  " + ext.capabilities())
                : item.namespace + "  ·  v" + item.version + "  ·  ⤓ " + ExtensionsView.compact(item.downloads);
        TextView m = skin.caption(meta);
        m.setPadding(0, skin.dp(4), 0, skin.dp(8));
        head.addView(m);
        warning = skin.caption("");
        warning.setTextColor(skin.errorFg);
        warning.setPadding(0, 0, 0, skin.dp(8));
        warning.setVisibility(GONE);
        head.addView(warning);
        buttons = new LinearLayout(wb.activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(buttons);
        progress = skin.caption("");
        head.addView(progress);
        addView(head);
        addView(skin.hairline(skin.border));
        readme = new WebView(wb.activity);
        readme.getSettings().setJavaScriptEnabled(false);
        readme.setWebViewClient(new PreviewClient(wb.activity));
        readme.setBackgroundColor(wb.editor.colors().background);
        addView(readme, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        buildButtons();
        loadReadme();
        if (ext != null) showDesktopNote();
        else checkPlatforms();
    }

    private void showDesktopNote() {
        boolean desktop = installed != null ? installed.desktopOnly() : Boolean.TRUE.equals(desktopOnly);
        warning.setVisibility(desktop ? VISIBLE : GONE);
        if (!desktop) return;
        boolean onNode = wb.alpineMode() && (installed != null ? installed.targetPlatform != null && installed.targetPlatform.startsWith("alpine-") : alpineUrl != null);
        warning.setTextColor(onNode ? skin.descriptionFg : skin.errorFg);
        if (onNode) {
            // The Alpine Linux build: its programs run in Alpine, its code on the Node.js host.
            warning.setText(installed != null ? t("Runs on Node.js in Alpine Linux.")
                    : t("Made for desktop computers; its Alpine Linux build runs here on Node.js (Settings → Terminal)."));
            return;
        }
        String arch = com.disgusty.droid_codology.linux.AlpineLinux.arch();
        if (wb.alpineMode() && !"aarch64".equals(arch) && !"x86_64".equals(arch)) {
            // Alpine builds of extensions exist for 64-bit processors only (alpine-arm64, alpine-x64).
            warning.setText(f("Made for desktop computers, with programs for 64-bit processors only; this device runs 32-bit Android ({0}), so its code can't run here.", arch));
            return;
        }
        warning.setText(installed != null
                ? t("Made for desktop computers: its code needs Node.js and desktop programs, which Android doesn't have, so it doesn't run here.")
                + (installed.capabilities().isEmpty() ? "" : " " + t("Its themes, languages and snippets work."))
                : t("Made for desktop computers: it comes with programs for Windows, macOS and Linux and needs Node.js. Android has neither, so its code can't run here; only its themes, languages and snippets would work."));
    }

    /** Asks Open VSX which builds exist before anything is downloaded. */
    private void checkPlatforms() {
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                Boolean desktop = null;
                String alpine = null;
                try {
                    OpenVsx.Item d = OpenVsx.details(item.namespace, item.name);
                    desktop = d.desktopOnly();
                    if (desktop && wb.alpineMode() && d.alpinePlatform() != null) alpine = d.platformUrls.get(d.alpinePlatform());
                } catch (Exception ignored) {
                    // offline: install() asks again
                }
                final Boolean d = desktop;
                final String a = alpine;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (d != null) desktopOnly = d;
                        alpineUrl = a;
                        showDesktopNote();
                    }
                });
            }
        });
    }

    private void confirmDesktopInstall() {
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = t("Made for Desktop Computers");
        s.message = f("'{0}' comes with programs for Windows, macOS and Linux and needs Node.js. Android has neither, so its code can't run in droid_codology; only its themes, languages and snippets would work. Install it anyway?", item.displayName);
        s.positive = t("Install Anyway");
        s.negative = t("Cancel");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                installAnyway = true;
                install();
            }
        };
        skin.dialog(s).show();
    }

    private void buildButtons() {
        buttons.removeAllViews();
        if (installed == null) {
            buttons.addView(skin.button(t("Install"), true, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    install();
                }
            }));
            return;
        }
        final Extension e = installed;
        if (e.enabled && !e.themes().isEmpty()) {
            buttons.addView(skin.button(t("Set Color Theme"), true, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    wb.commands.run("workbench.action.selectTheme");
                }
            }));
            addGap();
        }
        buttons.addView(skin.button(e.enabled ? t("Disable") : t("Enable"), false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.extensions.setEnabled(e, !e.enabled);
                installed = wb.extensions.find(e.id);
                wb.onExtensionsChanged();
                buildButtons();
            }
        }));
        addGap();
        buttons.addView(skin.button(t("Uninstall"), false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.extensions.uninstall(e);
                installed = null;
                wb.onExtensionsChanged();
                if (item == null) wb.closeExtensionPage();
                else buildButtons();
            }
        }));
    }

    private void addGap() {
        View gap = new View(wb.activity);
        buttons.addView(gap, new LayoutParams(skin.dp(8), 1));
    }

    private void install() {
        if (Boolean.TRUE.equals(desktopOnly) && !installAnyway && alpineUrl == null) {
            confirmDesktopInstall();
            return;
        }
        buttons.setEnabled(false);
        progress.setText(t("Downloading..."));
        final File dir = wb.activity.getCacheDir();
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (alpineUrl != null) item.downloadUrl = alpineUrl;
                    if (desktopOnly == null && !installAnyway && OpenVsx.details(item.namespace, item.name).desktopOnly()) {
                        // Not known when the button was pressed: ask before a big download.
                        wb.ui.post(new Runnable() {
                            @Override
                            public void run() {
                                desktopOnly = true;
                                showDesktopNote();
                                progress.setText("");
                                buildButtons();
                                confirmDesktopInstall();
                            }
                        });
                        return;
                    }
                    File vsix = OpenVsx.download(item, dir, new com.disgusty.droid_codology.net.Http.Progress() {
                        long last;

                        @Override
                        public void onProgress(final long done, final long total) {
                            if (System.currentTimeMillis() - last < 200) return;
                            last = System.currentTimeMillis();
                            wb.ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    progress.setText(total > 0 ? f("Downloading... {0}%", done * 100 / total) : t("Downloading..."));
                                }
                            });
                        }
                    });
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            progress.setText(t("Installing..."));
                        }
                    });
                    final boolean linux = alpineUrl != null;
                    final Extension e = wb.extensions.install(vsix, linux);
                    vsix.delete();
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            // The Alpine build runs on the Node.js host: switch to it (installing Node.js if needed).
                            if (linux && wb.alpineMode()) wb.ensureNodeHost();
                            installed = e;
                            showDesktopNote();
                            // (its code counts only once the Node.js host runs it)
                            progress.setText(!linux && e != null && e.capabilities().isEmpty()
                                    ? t("Installed. This extension has no contributions droid_codology can use yet.") : t("Installed."));
                            wb.onExtensionsChanged();
                            buildButtons();
                        }
                    });
                } catch (final Exception ex) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            progress.setText(f("Install failed: {0}", String.valueOf(ex.getMessage())));
                            buildButtons();
                        }
                    });
                }
            }
        });
    }

    private void loadReadme() {
        final com.disgusty.droid_codology.editor.EditorColors k = wb.editor.colors();
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String md = "";
                File base = null;
                try {
                    if (installed != null) {
                        File f = new File(installed.dir, "README.md");
                        if (!f.exists()) f = new File(installed.dir, "readme.md");
                        if (f.exists()) {
                            java.io.FileInputStream in = new java.io.FileInputStream(f);
                            try {
                                md = new String(com.disgusty.droid_codology.io.TextFiles.readAll(in), "UTF-8");
                            } finally {
                                in.close();
                            }
                            base = installed.dir;
                        }
                    } else if (item != null) {
                        OpenVsx.Item d = item.readmeUrl != null ? item : OpenVsx.details(item.namespace, item.name);
                        md = OpenVsx.readme(d);
                    }
                } catch (Exception ignored) {
                }
                final String html = MarkdownPreview.page(new MarkdownHtml(new MarkdownHtml.CodeColorizer() {
                    @Override
                    public String colorize(String hint, String code) {
                        return MarkdownPreview.highlight(hint, code, k);
                    }
                }).render(md), k, skin);
                final String baseUrl = base != null ? "file://" + base.getAbsolutePath() + "/" : null;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        readme.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null);
                    }
                });
            }
        });
    }
}
