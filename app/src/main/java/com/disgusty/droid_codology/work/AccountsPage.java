package com.disgusty.droid_codology.work;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.disgusty.droid_codology.account.Account;
import com.disgusty.droid_codology.account.Cloud;
import com.disgusty.droid_codology.account.FirebaseAccount;
import com.disgusty.droid_codology.account.GitHubAccount;
import com.disgusty.droid_codology.account.GoogleAccount;
import com.disgusty.droid_codology.account.Loopback;
import com.disgusty.droid_codology.ai.AiProvider;
import com.disgusty.droid_codology.ai.KeyLink;
import com.disgusty.droid_codology.ui.Skin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/** Accounts: the droid_codology server account, settings sync, AI providers and the GitHub token. */
final class AccountsPage {
    private final Workbench wb;
    private final Skin skin;
    private final Account acc;
    private LinearLayout list;
    private View page;

    AccountsPage(Workbench wb) {
        this.wb = wb;
        this.skin = wb.skin;
        this.acc = wb.account;
    }

    View build() {
        LinearLayout p = new LinearLayout(wb.activity);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(skin.windowBg);
        p.setClickable(true);
        Skin.Action back = new Skin.Action("arrow_left", t("Close"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.closeAccounts();
            }
        });
        p.addView(skin.titleBar(back, t("Accounts"), null, null, null));
        ScrollView sv = new ScrollView(wb.activity);
        list = new LinearLayout(wb.activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, skin.dp(24));
        sv.addView(list);
        p.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        page = p;
        fill();
        if (!acc.server().isEmpty()) refreshInfo();
        return p;
    }

    void refill() {
        if (list == null) return;
        list.removeAllViews();
        fill();
    }

    private void header(String s) {
        TextView t = skin.text(s, skin.uiSp() + 2, skin.sideBarFg, skin.medium());
        t.setPadding(skin.dp(16), skin.dp(20), skin.dp(16), skin.dp(6));
        list.addView(t);
    }

    private void note(String s) {
        TextView t = skin.text(s, skin.uiSp() - 1, skin.descriptionFg, skin.regular());
        t.setPadding(skin.dp(16), skin.dp(2), skin.dp(16), skin.dp(6));
        list.addView(t);
    }

    private void row(String icon, String title, String sub, View.OnClickListener l) {
        LinearLayout r = skin.listItem(icon == null ? null : skin.icon(icon, skin.descriptionFg, 24), title, sub);
        if (l != null) r.setOnClickListener(l);
        list.addView(r);
    }

    private JSONObject info() {
        String s = wb.prefs.raw().getString("state.serverInfo", null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            return o.optString("_server").equals(acc.server()) ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void fill() {
        header(t("droid_codology Account"));
        JSONObject info = info();
        String server = acc.server();
        if (acc.signedIn()) {
            row("account", acc.displayName().isEmpty() ? t("Signed in") : acc.displayName(), f("Signed in on {0}", server), null);
            syncRows();
            row("key", t("Create API Key"), t("For scripts or signing in on another device"), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    createKey();
                }
            });
            row("sign_out", t("Sign Out"), null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    wb.bg.execute(new Runnable() {
                        @Override
                        public void run() {
                            acc.logout();
                            wb.ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    wb.onAccountChanged();
                                }
                            });
                        }
                    });
                }
            });
        } else if (acc.cloud() != null) {
            final Cloud cloud = acc.cloud();
            String icon = cloud == acc.google ? "globe" : cloud == acc.github ? "github" : "mail";
            row(icon, cloud.displayName().isEmpty() ? t("Signed in") : cloud.displayName(),
                    f("Settings sync through {0}, no server needed", cloud.storage()), null);
            syncRows();
            row("sign_out", t("Sign Out"), null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    wb.bg.execute(new Runnable() {
                        @Override
                        public void run() {
                            cloud.signOut();
                            wb.ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    wb.onAccountChanged();
                                }
                            });
                        }
                    });
                }
            });
        } else {
            signInRows(info);
        }
        String serverSub = server.isEmpty() ? t("Not set (optional): your own server adds Apple sign-in, AI models and language servers")
                : server + (info != null ? "  ·  " + info.optString("name") + " " + info.optString("version") : "")
                + (acc.usingBuiltInServer() ? "  ·  " + t("built in") : "");
        row("cloud", t("Server"), serverSub, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editServer();
            }
        });
        if (server.isEmpty()) {
            note(t("You can run your own server: see server/README.md in the droid_codology sources. It provides sign-in, settings sync and AI models."));
        }

        header(t("AI Providers"));
        List<AiProvider> providers = wb.ai.providers();
        if (acc.signedIn() && info != null && info.optBoolean("ai")) {
            row("sparkle", t("droid_codology server"), f("Models: {0} — no API key needed", wb.ai.serverModels().size()), null);
        }
        for (final AiProvider p : providers) {
            String sub = p.baseUrl + "  ·  " + (p.needsKey() && p.apiKey.isEmpty() ? t("no API key") : f("Models: {0}", p.models.size()));
            row("sparkle", p.name, sub, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editProvider(p, false);
                }
            });
        }
        for (final KeyLink.Service s : KeyLink.services()) {
            row("sparkle", f("Connect {0}", s.name), t("Opens the browser: create a key there and copy it — it is picked up automatically"),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            connectKey(s, null, true);
                        }
                    });
        }
        row("sparkle", t("Sign in with OpenRouter"), t("Claude, GPT, Gemini and more with one account, no key copying"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRouterSignIn();
            }
        });
        row("add", t("Add AI Provider..."), t("Anthropic, OpenAI, Gemini, OpenRouter, DeepSeek, Ollama..."), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addProvider();
            }
        });
        String sys = wb.ai.systemPrompt();
        row("edit", t("Custom Instructions"), sys.isEmpty() ? t("Added to every chat, e.g. your preferred language or style") : sys, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                multiline(t("Custom Instructions"), wb.ai.systemPrompt(), new Workbench.TextCallback() {
                    @Override
                    public void onText(String s) {
                        wb.ai.setSystemPrompt(s);
                        refill();
                    }
                });
            }
        });
        note(t("API keys are stored only on this device."));

        header("GitHub");
        String gh = wb.account.githubToken();
        String ghSub = gh == null ? t("Used for cloning private repositories and pushing with Git")
                : gh.equals(acc.github.token()) ? f("From GitHub sign-in ({0})", acc.github.login())
                : t("Token saved") + " (" + gh.substring(0, Math.min(8, gh.length())) + "…)";
        row("github", t("Personal Access Token"), ghSub, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                githubTokenDialog();
            }
        });
    }

    // ------------------------------------------------------------------ signing in

    private void syncRows() {
        final boolean sync = acc.syncEnabled();
        list.addView(skin.switchRow(t("Settings Sync"), t("Settings, extensions and AI providers (without API keys) follow you to other devices"),
                sync, new Skin.ToggleListener() {
                    @Override
                    public void onToggle(boolean on) {
                        acc.setSyncEnabled(on);
                        if (on) wb.syncNow(false);
                    }
                }));
        long last = acc.lastSync();
        row("sync", t("Sync Now"), last == 0 ? t("Never synced") : f("Last synced {0}",
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(last))), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.syncNow(true);
            }
        });
    }

    /**
     * Browsers of Android 4.x can't open today's sign-in pages (old TLS and certificates): there,
     * signing in goes by a code entered on another device whenever that's possible.
     */
    private static boolean oldBrowser() {
        return android.os.Build.VERSION.SDK_INT < 21;
    }

    /** Sign-in choices; the ones that need a server say so and ask for one when tapped. */
    private void signInRows(JSONObject info) {
        boolean server = !acc.server().isEmpty();
        final boolean viaServerGoogle = serverOffers(info, "google");
        final boolean serverDevice = info != null && info.optBoolean("deviceSignIn");
        final boolean byCode = oldBrowser() && serverDevice;
        String needServer = t("Needs a droid_codology server");
        String browser = t("Opens the browser"), code = t("Shows a code to enter on your phone or computer");
        final boolean googleByCode = !viaServerGoogle && GoogleAccount.deviceConfigured() && (oldBrowser() || !GoogleAccount.configured());
        row("globe", f("Sign in with {0}", "Google"), viaServerGoogle ? (byCode ? code : browser) : googleByCode ? code
                : GoogleAccount.configured() ? browser : server ? t("Not enabled on this server") : needServer, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (viaServerGoogle) {
                    if (byCode) serverDeviceSignIn();
                    else openBrowser(acc.oauthUrl("google"));
                } else if (googleByCode) {
                    googleDeviceSignIn();
                } else if (GoogleAccount.configured()) {
                    googleSignIn();
                } else {
                    needsServer("Google");
                }
            }
        });
        // GitHub works with or without a server: through the server when it offers GitHub, else by
        // code (with a GitHub OAuth app in the build) or with a personal access token (no setup).
        final boolean githubViaServer = serverOffers(info, "github");
        row("github", f("Sign in with {0}", "GitHub"), githubViaServer ? (byCode ? code : browser)
                : GitHubAccount.deviceConfigured() ? t("By code, settings sync through a private Gist, no server needed")
                : t("With a token from github.com, settings sync through a private Gist, no server needed"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (githubViaServer && byCode) serverDeviceSignIn();
                else if (githubViaServer) openBrowser(acc.oauthUrl("github"));
                else if (GitHubAccount.deviceConfigured()) githubDeviceSignIn();
                else githubTokenSignIn(null, true);
            }
        });
        final boolean appleOffered = serverOffers(info, "apple");
        row("account", f("Sign in with {0}", "Apple"), appleOffered ? (byCode ? code : browser) : server ? t("Not enabled on this server")
                : t("Needs a droid_codology server (Apple requires a secret key kept on a server)"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (appleOffered && byCode) serverDeviceSignIn();
                else if (appleOffered) openBrowser(acc.oauthUrl("apple"));
                else needsServer("Apple");
            }
        });
        if (serverDevice) {
            row("account", t("Sign in on Another Device"), code, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    serverDeviceSignIn();
                }
            });
        }
        row("mail", t("Sign in with Email"), server ? (info == null || info.optBoolean("registration", true)
                ? t("Or create an account") : t("Registration is closed on this server"))
                : FirebaseAccount.configured() ? t("Or create an account, no server needed") : needServer, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!acc.server().isEmpty()) emailDialog(false, null);
                else if (FirebaseAccount.configured()) emailDialog(true, null);
                else needsServer(t("Email"));
            }
        });
        row("key", t("Sign in with API Key"), t("Paste a key created on another device or with the server's create-key command"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (acc.server().isEmpty()) needsServer(t("API Key"));
                else apiKeyDialog();
            }
        });
    }

    private static boolean serverOffers(JSONObject info, String provider) {
        JSONArray oauth = info == null ? null : info.optJSONArray("oauth");
        for (int i = 0; oauth != null && i < oauth.length(); i++) if (oauth.optString(i).equals(provider)) return true;
        return false;
    }

    private void openBrowser(String url) {
        try {
            wb.activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            wb.showMessage(t("No browser found."));
        }
    }

    /** This way of signing in works through a droid_codology server only. */
    private void needsServer(String what) {
        if (!acc.server().isEmpty()) {
            // The server may have been unreachable: ask it again.
            wb.showMessage(f("{0} sign-in is not enabled on this server.", what));
            refreshInfo();
            return;
        }
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = f("Sign in with {0}", what);
        s.message = f("{0} sign-in works through a droid_codology server, which keeps your account and settings. "
                + "Enter the address of your server (see server/README.md to run one).", what);
        s.positive = t("Enter Server Address");
        s.negative = t("Cancel");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                editServer();
            }
        };
        skin.dialog(s).show();
    }

    /** The browser sign-in in progress; starting another one ends it (one waiting thread at a time). */
    private static Loopback activeLoopback;

    private static synchronized Loopback newLoopback(boolean stateRequired) throws java.io.IOException {
        if (activeLoopback != null) activeLoopback.close();
        activeLoopback = new Loopback(stateRequired);
        return activeLoopback;
    }

    /** An exception's text for the user; Android 4.x NPEs have no message at all. */
    /** The error in words; the account classes' own messages are English, translated here when known. */
    static String message(Throwable e) {
        return e.getMessage() != null ? t(e.getMessage()) : e.toString();
    }

    /** Google without a server: browser + loopback redirect, then settings sync through Google Drive. */
    private void googleSignIn() {
        final Loopback lb;
        try {
            lb = newLoopback(true);
        } catch (Exception e) {
            wb.showMessage(message(e));
            return;
        }
        openBrowser(GoogleAccount.authUrl(lb));
        wb.showMessage(t("Continue in the browser..."));
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    Uri r = lb.await(10 * 60 * 1000, t("Signed in"), t("You can go back to droid_codology."));
                    acc.google.finish(lb, r);
                    acc.keepOnlyCloud(acc.google);
                    acc.setSyncEnabled(true);
                } catch (Exception e) {
                    err = message(e);
                } finally {
                    lb.close();
                }
                final String error = err;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            wb.showMessage(f("Sign-in failed: {0}", error));
                            return;
                        }
                        wb.showMessage(f("Signed in as {0}", acc.displayName()));
                        wb.onAccountChanged();
                        wb.syncNow(false);
                    }
                });
            }
        });
    }

    // ---- by code on another device

    private interface Poller {
        /** True once the other device finished; throws when it was refused or the code expired. */
        boolean poll() throws Exception;
    }

    /** Shows a code to enter at {@code url} on another device and waits for that sign-in in the background. */
    private void codeDialog(final String url, final String code, final int intervalSec, final long expiresAt, boolean offerOpen,
                            final Poller poller) {
        LinearLayout b = box();
        if (offerOpen) {
            // This device's browser can open the page itself: copy the code for pasting there.
            b.addView(skin.button(t("Copy Code and Open Page"), true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    wb.copyText(code);
                    openBrowser(url);
                }
            }));
            TextView or = skin.text(t("Or do it on another device:"), skin.uiSp() - 1, skin.descriptionFg, skin.regular());
            or.setPadding(0, skin.dp(10), 0, skin.dp(4));
            b.addView(or);
        }
        TextView l1 = skin.text(t("On your phone or computer, open:"), skin.uiSp(), skin.sideBarFg, skin.regular());
        b.addView(l1);
        TextView u = skin.text(url, skin.uiSp() + 2, skin.textLink, skin.medium());
        u.setTextIsSelectable(true);
        u.setPadding(0, skin.dp(4), 0, skin.dp(12));
        b.addView(u);
        b.addView(skin.text(t("and enter the code:"), skin.uiSp(), skin.sideBarFg, skin.regular()));
        TextView c = skin.text(code, skin.uiSp() + 14, skin.sideBarFg, android.graphics.Typeface.MONOSPACE);
        c.setTextIsSelectable(true);
        c.setPadding(0, skin.dp(6), 0, skin.dp(6));
        b.addView(c);
        b.addView(skin.button(t("Copy Code"), false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.copyText(code);
                wb.showMessage(t("Copied"));
            }
        }));
        final TextView status = skin.text(t("Waiting for you to sign in there..."), skin.uiSp() - 2, skin.descriptionFg, skin.regular());
        status.setPadding(0, skin.dp(10), 0, 0);
        b.addView(status);
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = t("Sign In on Another Device");
        s.content = b;
        s.negative = t("Cancel");
        final Dialog d = skin.dialog(s);
        final boolean[] stop = {false};
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                stop[0] = true;
            }
        });
        d.show();
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                boolean done = false;
                while (!stop[0] && !done && System.currentTimeMillis() < expiresAt) {
                    try {
                        Thread.sleep(intervalSec * 1000L);
                        if (stop[0]) return;
                        done = poller.poll();
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        err = message(e);
                        break;
                    }
                }
                if (stop[0]) return;
                final boolean ok = done;
                final String error = err != null ? err : ok ? null : t("the code expired");
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        stop[0] = true;
                        if (d.isShowing()) d.dismiss();
                        if (error != null) {
                            wb.showMessage(f("Sign-in failed: {0}", error));
                            return;
                        }
                        wb.showMessage(f("Signed in as {0}", acc.displayName()));
                        if (!acc.signedIn() && acc.cloud() != null) acc.setSyncEnabled(true);
                        wb.onAccountChanged();
                        refreshInfo();
                        wb.syncNow(false);
                    }
                });
            }
        });
    }

    /** Through the droid_codology server: its /link page offers every sign-in it supports. */
    private void serverDeviceSignIn() {
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject r = acc.deviceStart();
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            codeDialog(r.optString("verification_url"), r.optString("user_code"), r.optInt("interval", 3),
                                    System.currentTimeMillis() + r.optLong("expires_in", 600) * 1000, !oldBrowser(), new Poller() {
                                        @Override
                                        public boolean poll() throws Exception {
                                            return acc.devicePoll(r.optString("device_code"));
                                        }
                                    });
                        }
                    });
                } catch (final Exception e) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            wb.showMessage(f("Sign-in failed: {0}", message(e)));
                        }
                    });
                }
            }
        });
    }

    /** Google without a server, by code (google.com/device), for devices whose browser can't sign in. */
    private void googleDeviceSignIn() {
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final GoogleAccount.DeviceCode c = GoogleAccount.deviceStart();
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            codeDialog(c.url, c.userCode, c.interval, c.expiresAt, !oldBrowser(), new Poller() {
                                @Override
                                public boolean poll() throws Exception {
                                    if (!acc.google.devicePoll(c)) return false;
                                    acc.keepOnlyCloud(acc.google);
                                    return true;
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            wb.showMessage(f("Sign-in failed: {0}", message(e)));
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------ connecting AI providers

    /**
     * Opens the provider's key page and waits for the key: it is taken from the clipboard as soon
     * as the user copies it and comes back, or can be pasted by hand.
     */
    private void connectKey(final KeyLink.Service svc, String key, boolean openPage) {
        keyDialog(f("Connect {0}", svc.name), t("1. Sign in on the page that opens and create a key.\n2. Copy it.\n3. Come back here: the key is pasted for you."),
                svc.keysPage, t("API Key"), t("Connect"), key, openPage, new KeySink() {
                    @Override
                    public String find(String text) {
                        return svc.findKey(text);
                    }

                    @Override
                    public void use(String k) {
                        AiProvider p = svc.preset();
                        p.apiKey = k;
                        linkProvider(p, svc, k);
                    }

                    @Override
                    public void again(String k) {
                        connectKey(svc, k, false);
                    }
                });
    }

    /** GitHub without a server and without any setup: a personal access token, then sync through a secret Gist. */
    private void githubTokenSignIn(String token, boolean openPage) {
        keyDialog(f("Sign in with {0}", "GitHub"), t("1. Sign in to GitHub on the page that opens and press \"Generate token\" at the bottom.\n"
                        + "2. Copy the token.\n3. Come back here: the token is pasted for you."),
                GitHubAccount.tokenPage(), t("GitHub token"), t("Sign In"), token, openPage, new KeySink() {
                    @Override
                    public String find(String text) {
                        return GitHubAccount.findToken(text);
                    }

                    @Override
                    public void use(final String k) {
                        wb.showMessage(t("Signing in..."));
                        wb.bg.execute(new Runnable() {
                            @Override
                            public void run() {
                                String err = null;
                                try {
                                    acc.github.useToken(k);
                                    acc.keepOnlyCloud(acc.github);
                                    acc.setSyncEnabled(true);
                                } catch (Exception e) {
                                    err = message(e);
                                }
                                final String error = err;
                                wb.ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (error != null) {
                                            Skin.DialogSpec s = new Skin.DialogSpec();
                                            s.title = f("Sign-in failed: {0}", "GitHub");
                                            s.message = error;
                                            s.positive = t("Try Again");
                                            s.negative = t("Cancel");
                                            s.onPositive = new Runnable() {
                                                @Override
                                                public void run() {
                                                    githubTokenSignIn(k, false);
                                                }
                                            };
                                            skin.dialog(s).show();
                                            return;
                                        }
                                        signedInToCloud();
                                    }
                                });
                            }
                        });
                    }

                    @Override
                    public void again(String k) {
                        githubTokenSignIn(k, false);
                    }
                });
    }

    /** GitHub by code at github.com/login/device (needs the client ID of a GitHub OAuth app in the build). */
    private void githubDeviceSignIn() {
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final GitHubAccount.DeviceCode c = GitHubAccount.deviceStart();
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            codeDialog(c.url, c.userCode, c.interval, c.expiresAt, !oldBrowser(), new Poller() {
                                @Override
                                public boolean poll() throws Exception {
                                    if (!acc.github.devicePoll(c)) return false;
                                    acc.keepOnlyCloud(acc.github);
                                    return true;
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            wb.showMessage(f("Sign-in failed: {0}", message(e)));
                        }
                    });
                }
            }
        });
    }

    /** After signing in to an account without a server: turn sync on and fetch the settings. */
    private void signedInToCloud() {
        acc.setSyncEnabled(true);
        wb.showMessage(f("Signed in as {0}", acc.displayName()));
        wb.onAccountChanged();
        refill();
        wb.syncNow(false);
    }

    private interface KeySink {
        /** The key in a clipboard text, or null. */
        String find(String text);

        void use(String key);

        /** Show the dialog again (nothing was entered). */
        void again(String key);
    }

    /**
     * Opens a page where the user creates a key or token and waits for it: it is taken from the
     * clipboard as soon as the user copies it and comes back, or can be typed in.
     */
    private void keyDialog(String title, String stepsText, final String page, String hint, String action, String key, boolean openPage,
                           final KeySink sink) {
        LinearLayout b = box();
        TextView steps = skin.text(stepsText, skin.uiSp() - 1, skin.sideBarFg, skin.regular());
        steps.setPadding(0, 0, 0, skin.dp(6));
        b.addView(steps);
        TextView other = skin.text(oldBrowser() ? t("This device's browser may not open the page: open the link on another device and type the key or token below.")
                : t("Or open the link on another device and type the key or token below."), skin.uiSp() - 2, skin.descriptionFg, skin.regular());
        b.addView(other);
        TextView link = skin.text(page, skin.uiSp() - 1, skin.textLink, skin.regular());
        link.setTextIsSelectable(true);
        link.setPadding(0, skin.dp(2), 0, skin.dp(10));
        b.addView(link);
        final EditText field = field(b, hint, key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final TextView status = skin.text("", skin.uiSp() - 2, skin.descriptionFg, skin.regular());
        b.addView(status);
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = title;
        s.content = b;
        s.positive = action;
        s.negative = t("Cancel");
        s.neutral = t("Open Page");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                String k = field.getText().toString().trim();
                if (k.isEmpty()) {
                    wb.showMessage(t("Paste the key first."));
                    sink.again(null);
                    return;
                }
                sink.use(k);
            }
        };
        s.onNeutral = new Runnable() {
            @Override
            public void run() {
                // The dialog closes with any button: open the page, then show it again for the key.
                openBrowser(page);
                sink.again(field.getText().toString());
            }
        };
        final Dialog d = skin.dialog(s);
        d.show();
        // Watch the clipboard while the dialog is up (readable only while we have focus).
        final android.content.ClipboardManager cm = (android.content.ClipboardManager) wb.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() {
            @Override
            public void run() {
                if (!d.isShowing()) return;
                View decor = d.getWindow() == null ? null : d.getWindow().getDecorView();
                if (decor != null && decor.hasWindowFocus() && field.getText().length() == 0) {
                    try {
                        android.content.ClipData clip = cm.getPrimaryClip();
                        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(wb.activity);
                        String found = sink.find(text == null ? null : text.toString());
                        if (found != null) {
                            field.setText(found);
                            status.setText(t("Pasted from the clipboard. Tap the button below."));
                        }
                    } catch (Exception ignored) {
                        // Clipboard access denied (no focus yet): try again shortly.
                    }
                }
                wb.ui.postDelayed(this, 700);
            }
        };
        wb.ui.postDelayed(poll[0], 700);
        if (openPage) openBrowser(page);
    }

    /** Checks the key by listing the models, then adds (or updates) the provider and selects it. */
    private void linkProvider(final AiProvider p, final KeyLink.Service svc, final String key) {
        wb.showMessage(f("Checking the key for {0}...", p.name));
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                List<String> models = null;
                String err = null;
                try {
                    models = KeyLink.listModels(p);
                } catch (Exception e) {
                    err = message(e);
                }
                final List<String> m = models;
                final String error = err;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            Skin.DialogSpec s = new Skin.DialogSpec();
                            s.title = f("{0}: the key didn't work", p.name);
                            s.message = error;
                            s.positive = t("Try Again");
                            s.negative = t("Cancel");
                            s.onPositive = new Runnable() {
                                @Override
                                public void run() {
                                    if (svc != null) connectKey(svc, key, false);
                                    else openRouterSignIn();
                                }
                            };
                            skin.dialog(s).show();
                            return;
                        }
                        saveLinked(p, key, m);
                    }
                });
            }
        });
    }

    private void saveLinked(AiProvider preset, String key, List<String> models) {
        List<AiProvider> all = wb.ai.providers();
        AiProvider target = null;
        // Reconnecting replaces the key of the provider made from the same preset.
        for (AiProvider x : all) if (x.id.equals(preset.id)) target = x;
        if (target == null) {
            target = new AiProvider(preset.id, preset.name, preset.kind, preset.baseUrl, "");
            all.add(target);
        }
        target.apiKey = key;
        String preferred = preset.models.isEmpty() ? null : preset.models.get(0);
        if (models != null && !models.isEmpty()) {
            target.models.clear();
            // Our suggested default first, when the account has it.
            if (preferred != null && models.contains(preferred)) target.models.add(preferred);
            for (String m : models) if (!target.models.contains(m)) target.models.add(m);
        } else if (target.models.isEmpty()) {
            target.models.addAll(preset.models);
        }
        wb.ai.save(all);
        if (!target.models.isEmpty()) wb.ai.select(target.id, target.models.get(0));
        wb.showMessage(f("{0} connected: {1} models.", target.name, target.models.size()));
        wb.onAccountChanged();
        refill();
    }

    /** OpenRouter's OAuth (PKCE): the browser hands back a code that becomes an API key. */
    private void openRouterSignIn() {
        final Loopback lb;
        try {
            lb = newLoopback(false); // OpenRouter sends no state back
        } catch (Exception e) {
            wb.showMessage(message(e));
            return;
        }
        openBrowser(KeyLink.openRouterAuthUrl(lb));
        wb.showMessage(t("Continue in the browser..."));
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String key = null, err = null;
                try {
                    Uri r = lb.await(10 * 60 * 1000, t("Connected"), t("You can go back to droid_codology."));
                    key = KeyLink.openRouterKey(lb, r);
                } catch (Exception e) {
                    err = message(e);
                } finally {
                    lb.close();
                }
                final String k = key, error = err;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            wb.showMessage(f("OpenRouter: {0}", error));
                            return;
                        }
                        AiProvider p = null;
                        for (AiProvider x : AiProvider.presets()) if (x.id.equals("openrouter")) p = x;
                        if (p == null) return;
                        // OpenRouter lists hundreds of models: keep the suggested ones (editable later).
                        saveLinked(p, k, null);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ server account

    private void editServer() {
        wb.prompt(t("Server Address"), "http://192.168.1.10:8080", acc.server(), new Workbench.TextCallback() {
            @Override
            public void onText(String s) {
                // Clearing the address goes back to the server built into the app, if there is one.
                String v = s.trim();
                acc.setServer(v.isEmpty() || v.equals("https://") ? (com.disgusty.droid_codology.BuildConfig.DEFAULT_SERVER.isEmpty() ? "" : null) : v);
                refill();
                refreshInfo();
            }
        });
    }

    /** Fetches /v1/info (sign-in options, AI models) in the background. */
    void refreshInfo() {
        final String server = acc.server();
        if (server.isEmpty()) {
            refill();
            wb.onAccountChanged();
            return;
        }
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject info = acc.info();
                    info.put("_server", server);
                    wb.prefs.put("state.serverInfo", info.toString());
                    ArrayList<String> models = new ArrayList<String>();
                    JSONArray m = info.optJSONArray("models");
                    if (m != null) for (int i = 0; i < m.length(); i++) models.add(m.getJSONObject(i).getString("id"));
                    wb.ai.setServerModels(models);
                    if (acc.signedIn()) acc.refreshUser();
                } catch (final Exception e) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (server.startsWith("https://") && Account.isLocalHost(server) && !(e instanceof Account.ServerError)) {
                                offerPlainHttp(server, message(e));
                                return;
                            }
                            wb.showMessage(f("Can't reach the server: {0}", message(e)));
                        }
                    });
                }
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        refill();
                        wb.onAccountChanged();
                    }
                });
            }
        });
    }

    /** A home-network server answered badly over HTTPS: it most likely runs plain HTTP. */
    private void offerPlainHttp(String server, String error) {
        final String plain = "http://" + server.substring("https://".length());
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = t("The server doesn't use HTTPS?");
        s.message = f("Connecting to {0} over HTTPS failed ({1}). A server on your home network usually runs without HTTPS: "
                + "its address starts with http://. Use {2}?", server, error, plain);
        s.positive = t("Use http://");
        s.negative = t("Cancel");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                acc.setServer(plain);
                refill();
                refreshInfo();
            }
        };
        skin.dialog(s).show();
    }

    private EditText field(LinearLayout box, String hint, String value, int type) {
        EditText e = skin.input(hint);
        e.setSingleLine(true);
        android.graphics.Typeface face = e.getTypeface();
        e.setInputType(type);
        e.setTypeface(face); // password types switch to monospace otherwise
        if (value != null) e.setText(value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = skin.dp(8);
        box.addView(e, lp);
        return e;
    }

    private LinearLayout box() {
        LinearLayout b = new LinearLayout(wb.activity);
        b.setOrientation(LinearLayout.VERTICAL);
        return b;
    }

    /** Email + password, on the droid_codology server or ({@code firebase}) without one. */
    private void emailDialog(final boolean firebase, String presetEmail) {
        LinearLayout b = box();
        final EditText email = field(b, t("Email"), presetEmail, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText pw = field(b, firebase ? t("Password (at least 6 characters)") : t("Password (at least 8 characters)"), null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText name = field(b, t("Name (for a new account)"), null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = t("Sign in with Email");
        s.content = b;
        s.positive = t("Sign In");
        s.neutral = t("Create Account");
        s.negative = t("Cancel");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                signIn(firebase, email.getText().toString(), pw.getText().toString(), null);
            }
        };
        s.onNeutral = new Runnable() {
            @Override
            public void run() {
                signIn(firebase, email.getText().toString(), pw.getText().toString(), name.getText().toString());
            }
        };
        final Dialog d = skin.dialog(s);
        if (firebase) {
            TextView forgot = skin.textButton(t("Forgot password?"), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String e = email.getText().toString().trim();
                    if (e.isEmpty()) {
                        wb.showMessage(t("Enter your email first."));
                        return;
                    }
                    d.dismiss();
                    resetPassword(e);
                }
            });
            b.addView(forgot);
        }
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void resetPassword(final String email) {
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    FirebaseAccount.resetPassword(email);
                } catch (Exception e) {
                    err = message(e);
                }
                final String error = err;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        wb.showMessage(error != null ? error : f("A link to choose a new password was sent to {0}.", email));
                    }
                });
            }
        });
    }

    /** {@code name} != null registers a new account. */
    private void signIn(final boolean firebase, final String email, final String pw, final String name) {
        wb.showMessage(t("Signing in..."));
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    if (firebase) {
                        if (name != null) acc.firebase.register(email.trim(), pw, name.trim());
                        else acc.firebase.signIn(email.trim(), pw);
                        acc.keepOnlyCloud(acc.firebase);
                        acc.setSyncEnabled(true);
                    } else if (name != null) {
                        acc.register(email.trim(), pw, name.trim());
                    } else {
                        acc.login(email.trim(), pw);
                    }
                } catch (Exception e) {
                    err = message(e);
                }
                final String error = err;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            wb.showMessage(error);
                            // Let the user correct it without typing the email again.
                            emailDialog(firebase, email);
                            return;
                        }
                        wb.showMessage(f("Signed in as {0}", acc.displayName()));
                        wb.onAccountChanged();
                        refill();
                        refreshInfo();
                        if (firebase) wb.syncNow(false);
                    }
                });
            }
        });
    }

    private void apiKeyDialog() {
        wb.prompt(t("Sign in with API Key"), "dck_...", "", new Workbench.TextCallback() {
            @Override
            public void onText(final String s) {
                wb.bg.execute(new Runnable() {
                    @Override
                    public void run() {
                        String err = null;
                        try {
                            acc.useToken(s);
                        } catch (Exception e) {
                            err = message(e);
                        }
                        final String error = err;
                        wb.ui.post(new Runnable() {
                            @Override
                            public void run() {
                                wb.showMessage(error != null ? error : f("Signed in as {0}", acc.displayName()));
                                wb.onAccountChanged();
                                refill();
                                refreshInfo();
                            }
                        });
                    }
                });
            }
        });
    }

    private void createKey() {
        wb.prompt(t("Create API Key"), t("Key name"), "droid_codology", new Workbench.TextCallback() {
            @Override
            public void onText(final String name) {
                wb.bg.execute(new Runnable() {
                    @Override
                    public void run() {
                        String key = null, err = null;
                        try {
                            key = wb.account.createKey(name);
                        } catch (Exception e) {
                            err = message(e);
                        }
                        final String k = key, error = err;
                        wb.ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (error != null) {
                                    wb.showMessage(error);
                                    return;
                                }
                                wb.copyText(k);
                                Skin.DialogSpec s = new Skin.DialogSpec();
                                s.title = t("API Key Created");
                                s.message = k + "\n\n" + t("Copied to the clipboard. It won't be shown again.");
                                s.positive = t("OK");
                                skin.dialog(s).show();
                            }
                        });
                    }
                });
            }
        });
    }

    private void githubTokenDialog() {
        LinearLayout b = box();
        final EditText tok = field(b, "ghp_... / github_pat_...", wb.account.githubToken(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView hint = skin.text(t("Create one at github.com → Settings → Developer settings → Personal access tokens, with access to the repositories you need."),
                skin.uiSp() - 2, skin.descriptionFg, skin.regular());
        b.addView(hint);
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = t("GitHub Personal Access Token");
        s.content = b;
        s.positive = t("Save");
        s.negative = t("Cancel");
        if (wb.account.githubToken() != null) s.neutral = t("Remove");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                String v = tok.getText().toString().trim();
                wb.account.setGithubToken(v.isEmpty() ? null : v);
                refill();
            }
        };
        s.onNeutral = new Runnable() {
            @Override
            public void run() {
                wb.account.setGithubToken(null);
                refill();
            }
        };
        skin.dialog(s).show();
    }

    // ------------------------------------------------------------------ AI providers

    private void addProvider() {
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        for (final AiProvider p : AiProvider.presets()) {
            QuickPick.Item it = new QuickPick.Item(p.name, null, new Runnable() {
                @Override
                public void run() {
                    editProvider(p, true);
                }
            });
            it.description = p.baseUrl;
            items.add(it);
        }
        wb.quickPick().showItems(t("Add AI Provider"), items);
    }

    private void editProvider(final AiProvider p, final boolean isNew) {
        LinearLayout b = box();
        final EditText name = field(b, t("Name"), p.name, InputType.TYPE_CLASS_TEXT);
        final EditText url = field(b, t("Base URL"), p.baseUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText key = field(b, t("API Key"), p.apiKey, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        StringBuilder ms = new StringBuilder();
        for (String m : p.models) {
            if (ms.length() > 0) ms.append(", ");
            ms.append(m);
        }
        final EditText models = field(b, t("Models, comma-separated"), ms.toString(), InputType.TYPE_CLASS_TEXT);
        TextView kind = skin.text(f("API format: {0}", p.kind), skin.uiSp() - 2, skin.descriptionFg, skin.regular());
        b.addView(kind);
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = isNew ? f("Add {0}", p.name) : p.name;
        s.content = b;
        s.positive = t("Save");
        s.negative = t("Cancel");
        if (!isNew) s.neutral = t("Remove");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                List<AiProvider> all = wb.ai.providers();
                AiProvider target = p;
                if (isNew) {
                    String id = p.id;
                    int n = 2;
                    while (has(all, id)) id = p.id + (n++);
                    target = new AiProvider(id, p.name, p.kind, p.baseUrl, "");
                    all.add(target);
                } else {
                    for (AiProvider x : all) if (x.id.equals(p.id)) target = x;
                }
                target.name = name.getText().toString().trim();
                target.baseUrl = url.getText().toString().trim();
                target.apiKey = key.getText().toString().trim();
                target.models.clear();
                for (String m : models.getText().toString().split(",")) if (!m.trim().isEmpty()) target.models.add(m.trim());
                wb.ai.save(all);
                if (isNew && !target.models.isEmpty()) wb.ai.select(target.id, target.models.get(0));
                wb.onAccountChanged();
                refill();
            }
        };
        s.onNeutral = new Runnable() {
            @Override
            public void run() {
                List<AiProvider> all = wb.ai.providers();
                for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(p.id)) all.remove(i);
                wb.ai.save(all);
                wb.onAccountChanged();
                refill();
            }
        };
        Dialog d = skin.dialog(s);
        d.show();
    }

    private static boolean has(List<AiProvider> all, String id) {
        for (AiProvider p : all) if (p.id.equals(id)) return true;
        return false;
    }

    private void multiline(String title, String value, final Workbench.TextCallback cb) {
        final EditText e = skin.input("");
        e.setText(value);
        e.setMinLines(4);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = title;
        s.content = e;
        s.positive = t("Save");
        s.negative = t("Cancel");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                cb.onText(e.getText().toString().trim());
            }
        };
        skin.dialog(s).show();
    }
}
