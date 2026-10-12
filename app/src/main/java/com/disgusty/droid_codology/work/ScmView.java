package com.disgusty.droid_codology.work;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.disgusty.droid_codology.ai.AiClient;
import com.disgusty.droid_codology.ai.AiProvider;
import com.disgusty.droid_codology.git.Diff;
import com.disgusty.droid_codology.git.GitConfig;
import com.disgusty.droid_codology.git.GitIndex;
import com.disgusty.droid_codology.git.GitObject;
import com.disgusty.droid_codology.git.Remote;
import com.disgusty.droid_codology.git.Repo;
import com.disgusty.droid_codology.ui.Skin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/** Source Control: changes, staging, commit, branches, pull / push / sync, history and diffs. */
final class ScmView extends LinearLayout {
    /** Git work (operations, status refreshes, the agent's git tools) runs one at a time. */
    static final Object GIT_LOCK = new Object();
    private final Workbench wb;
    private final Skin skin;
    private final LinearLayout body;
    private final EditText message;
    private final TextView branchRow, busyLabel;
    private Repo repo;
    private Repo.Status status;
    private boolean busy;
    private boolean stagedOpen = true, changesOpen = true;
    private View msgRow;

    ScmView(final Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        setOrientation(VERTICAL);
        setBackgroundColor(skin.sideBarBg);
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("check", t("Commit"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                commit();
            }
        }));
        acts.add(new Skin.Action("refresh", t("Refresh"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }));
        acts.add(new Skin.Action("more", t("More Actions..."), new OnClickListener() {
            @Override
            public void onClick(View v) {
                moreMenu(v);
            }
        }));
        addView(skin.sideBarHeader(t("Source Control"), acts));

        branchRow = skin.text("", skin.uiSp() - 1, skin.descriptionFg, skin.regular());
        branchRow.setPadding(skin.dp(16), skin.dp(2), skin.dp(16), skin.dp(4));
        branchRow.setSingleLine(true);
        branchRow.setCompoundDrawablesWithIntrinsicBounds(skin.icon("branch", skin.descriptionFg, 16), null, null, null);
        branchRow.setCompoundDrawablePadding(skin.dp(6));
        branchRow.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBranch();
            }
        });
        addView(branchRow);

        LinearLayout msgRow = new LinearLayout(wb.activity);
        msgRow.setOrientation(HORIZONTAL);
        msgRow.setGravity(Gravity.CENTER_VERTICAL);
        msgRow.setPadding(skin.dp(12), 0, skin.dp(6), skin.dp(4));
        message = skin.input(t("Message (Ctrl+Enter to commit)"));
        message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        message.setMaxLines(5);
        message.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && e.isCtrlPressed()) {
                    if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) commit();
                    return true;
                }
                return false;
            }
        });
        msgRow.addView(message, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView ai = skin.text("✦", skin.uiSp() + 4, skin.accent, skin.regular());
        ai.setGravity(Gravity.CENTER);
        ai.setContentDescription(t("Generate Commit Message"));
        ai.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                generateMessage();
            }
        });
        msgRow.addView(ai, new LayoutParams(skin.dp(40), skin.dp(40)));
        addView(msgRow);
        this.msgRow = msgRow;

        busyLabel = skin.caption("");
        busyLabel.setPadding(skin.dp(16), 0, skin.dp(16), skin.dp(4));
        busyLabel.setVisibility(GONE);
        addView(busyLabel);

        ScrollView sv = new ScrollView(wb.activity);
        body = new LinearLayout(wb.activity);
        body.setOrientation(VERTICAL);
        sv.addView(body);
        addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        refresh();
    }

    // ------------------------------------------------------------------ state

    private File root() {
        return wb.workspace == null ? null : wb.workspace.root;
    }

    /** Re-reads the repository status in the background. */
    void refresh() {
        final File root = root();
        if (root == null) {
            repo = null;
            status = null;
            render();
            return;
        }
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                Repo r = Repo.find(root);
                Repo.Status s = null;
                String err = null;
                if (r != null) {
                    // status() rewrites the index: never at the same time as a checkout or pull.
                    synchronized (GIT_LOCK) {
                        try {
                            s = r.status();
                        } catch (Exception e) {
                            err = e.getMessage() == null ? e.toString() : e.getMessage();
                        } catch (OutOfMemoryError e) {
                            err = "out of memory";
                        }
                    }
                }
                final Repo fr = r;
                final Repo.Status fs = s;
                final String error = err;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        repo = fr;
                        status = fs;
                        render();
                        if (error != null) wb.showMessage(f("Git: {0}", error));
                        wb.updateStatus();
                    }
                });
            }
        });
    }

    Repo.Status lastStatus() {
        return status;
    }

    Repo repo() {
        return repo;
    }

    private void render() {
        body.removeAllViews();
        msgRow.setVisibility(repo != null ? VISIBLE : GONE);
        if (root() == null) {
            branchRow.setVisibility(GONE);
            note(t("Open a folder to use Git, or clone a repository."));
            button(t("Clone Repository"), new Runnable() {
                @Override
                public void run() {
                    cloneRepo();
                }
            });
            return;
        }
        if (repo == null) {
            branchRow.setVisibility(GONE);
            note(t("The folder currently open doesn't have a Git repository."));
            button(t("Initialize Repository"), new Runnable() {
                @Override
                public void run() {
                    initRepo();
                }
            });
            button(t("Clone Repository"), new Runnable() {
                @Override
                public void run() {
                    cloneRepo();
                }
            });
            return;
        }
        branchRow.setVisibility(VISIBLE);
        if (status == null) return;
        String b = status.branch != null ? status.branch : (status.head != null ? status.head.substring(0, 8) : "?");
        String sync = status.upstream == null ? "" : "   ↓" + status.behind + " ↑" + status.ahead;
        branchRow.setText(b + sync);
        String mergeHead = null;
        try {
            mergeHead = repo.mergeHead();
        } catch (IOException ignored) {
        }
        if (mergeHead != null) note(t("Merging: resolve the conflict markers, stage the files and commit."));
        if (!status.staged.isEmpty()) {
            section(f("Staged Changes ({0})", status.staged.size()), stagedOpen, new Runnable() {
                @Override
                public void run() {
                    stagedOpen = !stagedOpen;
                    render();
                }
            }, "−", t("Unstage All Changes"), new Runnable() {
                @Override
                public void run() {
                    unstageAll();
                }
            });
            if (stagedOpen) for (Repo.Change c : status.staged) changeRow(c, true);
        }
        section(f("Changes ({0})", status.unstaged.size()), changesOpen, new Runnable() {
            @Override
            public void run() {
                changesOpen = !changesOpen;
                render();
            }
        }, "+", t("Stage All Changes"), new Runnable() {
            @Override
            public void run() {
                stageAll();
            }
        });
        if (changesOpen) for (Repo.Change c : status.unstaged) changeRow(c, false);
        if (status.staged.isEmpty() && status.unstaged.isEmpty()) note(t("No changes. Everything is committed."));
    }

    private void note(String s) {
        TextView t = skin.text(s, skin.uiSp() - 1, skin.descriptionFg, skin.regular());
        t.setPadding(skin.dp(16), skin.dp(8), skin.dp(16), skin.dp(8));
        body.addView(t);
    }

    private void button(String label, final Runnable r) {
        TextView b = skin.button(label, true, new OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(skin.dp(16), skin.dp(6), skin.dp(16), skin.dp(6));
        body.addView(b, lp);
    }

    private void section(String title, boolean open, final Runnable toggle, String actionLabel, String actionDesc, final Runnable action) {
        LinearLayout row = new LinearLayout(wb.activity);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View h = skin.sectionHeader(title, open, new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle.run();
            }
        });
        row.addView(h, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView a = skin.text(actionLabel, skin.uiSp() + 4, skin.sideBarFg, skin.regular());
        a.setGravity(Gravity.CENTER);
        a.setContentDescription(actionDesc);
        a.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        row.addView(a, new LayoutParams(skin.dp(40), skin.dp(32)));
        body.addView(row);
    }

    private static int kindColor(char k) {
        switch (k) {
            case 'A':
            case '?':
                return 0xFF73C991;
            case 'D':
                return 0xFFF14C4C;
            default:
                return 0xFFE2C08D;
        }
    }

    private void changeRow(final Repo.Change c, final boolean staged) {
        String name = c.path.substring(c.path.lastIndexOf('/') + 1);
        String dir = c.path.contains("/") ? c.path.substring(0, c.path.lastIndexOf('/')) : "";
        TextView letter = skin.text(c.kind == '?' ? "U" : String.valueOf(c.kind), skin.uiSp() - 1, kindColor(c.kind), skin.medium());
        letter.setPadding(skin.dp(8), 0, skin.dp(12), 0);
        LinearLayout row = skin.treeRow(1, null, FileIcons.file(skin, name, 16), name, dir, letter);
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiff(c, staged);
            }
        });
        row.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                changeMenu(v, c, staged);
                return true;
            }
        });
        body.addView(row);
    }

    private void changeMenu(View anchor, final Repo.Change c, final boolean staged) {
        ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
        m.add(new Skin.MenuItem(t("Open Changes"), new Runnable() {
            @Override
            public void run() {
                showDiff(c, staged);
            }
        }));
        if (c.kind != 'D') m.add(new Skin.MenuItem(t("Open File"), new Runnable() {
            @Override
            public void run() {
                wb.openFile(repo.file(c.path), false, -1, 0, 0);
                wb.closeOverlaySideBar();
            }
        }));
        if (staged) {
            m.add(new Skin.MenuItem(t("Unstage Changes"), new Runnable() {
                @Override
                public void run() {
                    indexOp(c.path, 1);
                }
            }));
        } else {
            m.add(new Skin.MenuItem(t("Stage Changes"), new Runnable() {
                @Override
                public void run() {
                    indexOp(c.path, 0);
                }
            }));
            m.add(new Skin.MenuItem(c.kind == '?' ? t("Delete File") : t("Discard Changes"), new Runnable() {
                @Override
                public void run() {
                    confirmDiscard(c);
                }
            }));
        }
        skin.popup(anchor, m);
    }

    // ------------------------------------------------------------------ background operations

    private interface Op {
        String run() throws Exception;
    }

    /** Runs a Git operation off the UI thread, then refreshes; the returned text is shown. */
    private void op(final String label, final Op op) {
        if (busy) {
            wb.showMessage(t("A Git operation is already running."));
            return;
        }
        busy = true;
        busyLabel.setText("⟳ " + label);
        busyLabel.setVisibility(VISIBLE);
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String result = null, error = null;
                boolean auth = false;
                synchronized (GIT_LOCK) {
                    try {
                        result = op.run();
                    } catch (Remote.AuthRequired e) {
                        auth = true;
                        error = e.getMessage();
                    } catch (Exception e) {
                        error = e.getMessage() == null ? e.toString() : e.getMessage();
                    } catch (OutOfMemoryError e) {
                        // A big pack or file on a small heap: report it instead of crashing the app.
                        error = t("Not enough memory for this Git operation.");
                    }
                }
                final String r = result, err = error;
                final boolean needAuth = auth;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        busyLabel.setVisibility(GONE);
                        if (needAuth) askCredentials(label, op);
                        else if (err != null) wb.showMessage(f("Git: {0}", err));
                        else if (r != null) wb.showMessage(r);
                        refresh();
                        wb.reloadOpenDocumentsFromDisk();
                    }
                });
            }
        });
    }

    private void progress(final String s) {
        wb.ui.post(new Runnable() {
            @Override
            public void run() {
                if (busy) busyLabel.setText("⟳ " + s);
            }
        });
    }

    private final Remote.Progress progress = new Remote.Progress() {
        @Override
        public void onProgress(String message) {
            progress(message);
        }
    };

    private void indexOp(final String path, final int kind) {
        op(t("Updating index..."), new Op() {
            @Override
            public String run() throws Exception {
                GitIndex ix = repo.index();
                if (kind == 0) repo.stage(ix, path);
                else if (kind == 1) repo.unstage(ix, path);
                else repo.discard(ix, path);
                repo.saveIndex(ix);
                return null;
            }
        });
    }

    private void stageAll() {
        if (status == null) return;
        final List<Repo.Change> cs = new ArrayList<Repo.Change>(status.unstaged);
        op(t("Staging..."), new Op() {
            @Override
            public String run() throws Exception {
                GitIndex ix = repo.index();
                for (Repo.Change c : cs) repo.stage(ix, c.path);
                repo.saveIndex(ix);
                return null;
            }
        });
    }

    private void unstageAll() {
        if (status == null) return;
        final List<Repo.Change> cs = new ArrayList<Repo.Change>(status.staged);
        op(t("Unstaging..."), new Op() {
            @Override
            public String run() throws Exception {
                GitIndex ix = repo.index();
                for (Repo.Change c : cs) repo.unstage(ix, c.path);
                repo.saveIndex(ix);
                return null;
            }
        });
    }

    private void confirmDiscard(final Repo.Change c) {
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = c.kind == '?' ? f("Are you sure you want to DELETE {0}?", c.path) : f("Are you sure you want to discard changes in {0}?", c.path);
        s.message = t("This is IRREVERSIBLE!");
        s.positive = c.kind == '?' ? t("Delete File") : t("Discard File");
        s.negative = t("Cancel");
        s.destructive = true;
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                indexOp(c.path, 2);
            }
        };
        skin.dialog(s).show();
    }

    // ------------------------------------------------------------------ commit

    private Repo.Identity identity() {
        String n = wb.prefs.str("git.user.name", null), e = wb.prefs.str("git.user.email", null);
        if (n == null || n.isEmpty()) {
            org.json.JSONObject u = wb.account.user();
            if (u != null) {
                n = u.optString("name", null);
                if (e == null && !u.isNull("email")) e = u.optString("email", "");
            }
        }
        return n == null || n.isEmpty() ? null : new Repo.Identity(n, e == null ? "" : e);
    }

    private void askIdentity(final Runnable then) {
        LinearLayout b = new LinearLayout(wb.activity);
        b.setOrientation(VERTICAL);
        final EditText name = skin.input(t("Your name"));
        final EditText email = skin.input(t("Your email"));
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        name.setSingleLine(true);
        email.setSingleLine(true);
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = skin.dp(8);
        b.addView(name, lp);
        b.addView(email, lp);
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = t("Who is committing?");
        s.message = t("Git records a name and email with every commit.");
        s.content = b;
        s.positive = t("Save");
        s.negative = t("Cancel");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) return;
                wb.prefs.put("git.user.name", n);
                wb.prefs.put("git.user.email", email.getText().toString().trim());
                then.run();
            }
        };
        skin.dialog(s).show();
    }

    void commit() {
        if (repo == null || status == null) return;
        final String msg = message.getText().toString().trim();
        if (msg.isEmpty()) {
            wb.showMessage(t("Enter a commit message first."));
            message.requestFocus();
            return;
        }
        final Repo.Identity who = identity();
        if (who == null) {
            askIdentity(new Runnable() {
                @Override
                public void run() {
                    commit();
                }
            });
            return;
        }
        final boolean stageEverything = status.staged.isEmpty();
        if (stageEverything && status.unstaged.isEmpty()) {
            wb.showMessage(t("There are no changes to commit."));
            return;
        }
        final List<Repo.Change> toStage = new ArrayList<Repo.Change>(status.unstaged);
        Runnable doCommit = new Runnable() {
            @Override
            public void run() {
                op(t("Committing..."), new Op() {
                    @Override
                    public String run() throws Exception {
                        if (stageEverything) {
                            GitIndex ix = repo.index();
                            for (Repo.Change c : toStage) repo.stage(ix, c.path);
                            repo.saveIndex(ix);
                        }
                        String id = repo.commit(msg + "\n", who, repo.mergeHead() != null ? listOf(repo.mergeHead()) : null);
                        wb.ui.post(new Runnable() {
                            @Override
                            public void run() {
                                message.setText("");
                            }
                        });
                        return f("Committed {0}", id.substring(0, 8));
                    }
                });
            }
        };
        if (stageEverything) {
            // VS Code's "smart commit": nothing staged means commit all changes.
            Skin.DialogSpec s = new Skin.DialogSpec();
            s.title = t("There are no staged changes to commit.");
            s.message = t("Would you like to stage all your changes and commit them directly?");
            s.positive = t("Yes");
            s.negative = t("Cancel");
            s.onPositive = doCommit;
            skin.dialog(s).show();
        } else {
            doCommit.run();
        }
    }

    private static List<String> listOf(String s) {
        ArrayList<String> l = new ArrayList<String>();
        l.add(s);
        return l;
    }

    private void generateMessage() {
        if (repo == null || status == null) return;
        final AiProvider model = InlineAi.currentModel(wb);
        if (model == null) {
            wb.showMessage(t("Add an AI provider with an API key first."));
            wb.openAccounts();
            return;
        }
        final boolean staged = !status.staged.isEmpty();
        final List<Repo.Change> cs = new ArrayList<Repo.Change>(staged ? status.staged : status.unstaged);
        if (cs.isEmpty()) {
            wb.showMessage(t("There are no changes to commit."));
            return;
        }
        message.setText(t("Generating..."));
        final String token = wb.account.token();
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String text, err = null;
                try {
                    StringBuilder diff = new StringBuilder();
                    for (Repo.Change c : cs) {
                        diff.append(diffText(c, staged));
                        if (diff.length() > 14000) {
                            diff.setLength(14000);
                            diff.append("\n[diff truncated]\n");
                            break;
                        }
                    }
                    ArrayList<AiClient.Message> msgs = new ArrayList<AiClient.Message>();
                    msgs.add(new AiClient.Message("user", "Write a git commit message for this diff:\n\n" + diff));
                    text = AiClient.chat(model, model.models.get(0), "You write concise git commit messages: a summary line under 72 "
                            + "characters in the imperative mood, optionally a blank line and a few bullet points. Reply with the "
                            + "message only, no quotes or code fences.", msgs, 400, token, null, null).trim();
                } catch (Exception e) {
                    text = "";
                    err = e.getMessage();
                }
                final String result = text, error = err;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        message.setText(result);
                        if (error != null) wb.showMessage(f("AI: {0}", error));
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ diffs & history

    private String diffText(Repo.Change c, boolean staged) throws IOException {
        String before = "", after = "";
        GitIndex ix = repo.index();
        if (staged) {
            GitObject.TreeEntry h = repo.headFiles().get(c.path);
            if (h != null) before = text(repo.blob(h.id));
            GitIndex.Entry e = ix.entries.get(c.path);
            if (e != null) after = text(repo.blob(e.id));
        } else {
            GitIndex.Entry e = ix.entries.get(c.path);
            if (e != null) before = text(repo.blob(e.id));
            File f = repo.file(c.path);
            if (f.isFile()) after = text(com.disgusty.droid_codology.work.ExtensionHost.readBytes(f));
        }
        return Diff.unified("a/" + c.path, "b/" + c.path, before, after, 3);
    }

    private static String text(byte[] b) {
        for (int i = 0; i < Math.min(b.length, 8000); i++) if (b[i] == 0) return "(binary file)\n";
        return new String(b, GitObject.UTF8_PUBLIC);
    }

    private void showDiff(final Repo.Change c, final boolean staged) {
        op(t("Computing diff..."), new Op() {
            @Override
            public String run() throws Exception {
                final String d = diffText(c, staged);
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        wb.showReadOnlyText(c.path.substring(c.path.lastIndexOf('/') + 1) + (staged ? " (Index)" : " (Working Tree)"), d, "diff");
                        wb.closeOverlaySideBar();
                    }
                });
                return null;
            }
        });
    }

    void showHistory() {
        if (repo == null) return;
        op(t("Reading history..."), new Op() {
            @Override
            public String run() throws Exception {
                final List<GitObject.Commit> log = repo.log(repo.head(), 300);
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
                        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
                        for (final GitObject.Commit c : log) {
                            QuickPick.Item it = new QuickPick.Item(c.subject(), null, new Runnable() {
                                @Override
                                public void run() {
                                    showCommit(c);
                                }
                            });
                            it.description = c.authorName() + " · " + df.format(new Date(c.time())) + " · " + c.id.substring(0, 7);
                            items.add(it);
                        }
                        wb.quickPick().showItems(t("Git History"), items);
                    }
                });
                return null;
            }
        });
    }

    private void showCommit(final GitObject.Commit c) {
        op(t("Computing diff..."), new Op() {
            @Override
            public String run() throws Exception {
                Map<String, GitObject.TreeEntry> after = repo.flatten(c.tree);
                Map<String, GitObject.TreeEntry> before = c.parents.isEmpty() ? new java.util.TreeMap<String, GitObject.TreeEntry>()
                        : repo.flatten(repo.commit(c.parents.get(0)).tree);
                StringBuilder sb = new StringBuilder();
                sb.append("commit ").append(c.id).append('\n').append("Author: ").append(c.author.replaceAll(" \\d+ [+-]\\d{4}$", "")).append('\n')
                        .append("Date:   ").append(new Date(c.time())).append("\n\n");
                for (String l : c.message.split("\n")) sb.append("    ").append(l).append('\n');
                sb.append('\n');
                java.util.TreeSet<String> paths = new java.util.TreeSet<String>(before.keySet());
                paths.addAll(after.keySet());
                for (String p : paths) {
                    GitObject.TreeEntry a = before.get(p), b = after.get(p);
                    if (a != null && b != null && a.id.equals(b.id)) continue;
                    String x = a == null ? "" : text(repo.blob(a.id)), y = b == null ? "" : text(repo.blob(b.id));
                    sb.append("diff --git a/").append(p).append(" b/").append(p).append('\n');
                    sb.append(Diff.unified(a == null ? "/dev/null" : "a/" + p, b == null ? "/dev/null" : "b/" + p, x, y, 3));
                    if (sb.length() > 2000000) {
                        sb.append("\n[truncated]\n");
                        break;
                    }
                }
                final String text = sb.toString();
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        wb.showReadOnlyText(c.id.substring(0, 7) + " " + c.subject(), text, "diff");
                        wb.closeOverlaySideBar();
                    }
                });
                return null;
            }
        });
    }

    // ------------------------------------------------------------------ branches

    void pickBranch() {
        if (repo == null) return;
        op(t("Reading branches..."), new Op() {
            @Override
            public String run() throws Exception {
                final List<String> local = repo.branches();
                final List<String> remote = new ArrayList<String>();
                for (String r : repo.refs("refs/remotes/").keySet()) if (!r.endsWith("/HEAD")) remote.add(r.substring(13));
                final String cur = repo.branch();
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showBranchPicker(local, remote, cur);
                    }
                });
                return null;
            }
        });
    }

    private void showBranchPicker(List<String> local, List<String> remote, String cur) {
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        items.add(new QuickPick.Item("+ " + t("Create new branch..."), null, new Runnable() {
            @Override
            public void run() {
                wb.prompt(t("Branch name"), "feature/my-change", "", new Workbench.TextCallback() {
                    @Override
                    public void onText(final String name) {
                        op(t("Creating branch..."), new Op() {
                            @Override
                            public String run() throws Exception {
                                repo.createBranch(name, repo.head());
                                repo.setHead("refs/heads/" + name);
                                return f("Switched to a new branch {0}", name);
                            }
                        });
                    }
                });
            }
        }));
        for (final String b : local) {
            QuickPick.Item it = new QuickPick.Item(b, b.equals(cur) ? "✓" : null, new Runnable() {
                @Override
                public void run() {
                    if (b.equals(repo == null ? null : currentBranch())) return;
                    op(f("Switching to {0}...", b), new Op() {
                        @Override
                        public String run() throws Exception {
                            repo.checkout(repo.resolve("refs/heads/" + b), "refs/heads/" + b);
                            return f("Switched to {0}", b);
                        }
                    });
                }
            });
            it.description = t("local branch");
            items.add(it);
        }
        for (final String rb : remote) {
            final String name = rb.substring(rb.indexOf('/') + 1);
            if (local.contains(name)) continue;
            QuickPick.Item it = new QuickPick.Item(rb, null, new Runnable() {
                @Override
                public void run() {
                    op(f("Switching to {0}...", name), new Op() {
                        @Override
                        public String run() throws Exception {
                            String id = repo.resolve("refs/remotes/" + rb);
                            repo.createBranch(name, id);
                            GitConfig c = repo.config();
                            c.set("branch", name, "remote", rb.substring(0, rb.indexOf('/')));
                            c.set("branch", name, "merge", "refs/heads/" + name);
                            c.save();
                            repo.checkout(id, "refs/heads/" + name);
                            return f("Switched to {0}", name);
                        }
                    });
                }
            });
            it.description = t("remote branch");
            items.add(it);
        }
        if (local.size() > 1) items.add(new QuickPick.Item(t("Delete Branch..."), null, new Runnable() {
            @Override
            public void run() {
                deleteBranchPicker();
            }
        }));
        wb.quickPick().showItems(t("Select a branch to checkout"), items);
    }

    private String currentBranch() {
        return status == null ? null : status.branch;
    }

    private void deleteBranchPicker() {
        try {
            ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
            for (final String b : repo.branches()) {
                if (b.equals(currentBranch())) continue;
                items.add(new QuickPick.Item(b, null, new Runnable() {
                    @Override
                    public void run() {
                        op(t("Deleting branch..."), new Op() {
                            @Override
                            public String run() throws Exception {
                                repo.deleteBranch(b);
                                return f("Deleted branch {0}", b);
                            }
                        });
                    }
                }));
            }
            wb.quickPick().showItems(t("Delete Branch"), items);
        } catch (IOException e) {
            wb.showMessage(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ remotes

    private void moreMenu(View anchor) {
        ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
        boolean has = repo != null;
        m.add(new Skin.MenuItem(t("Pull"), new Runnable() {
            @Override
            public void run() {
                pull(false);
            }
        }).enabled(has));
        m.add(new Skin.MenuItem(t("Push"), new Runnable() {
            @Override
            public void run() {
                push();
            }
        }).enabled(has));
        m.add(new Skin.MenuItem(t("Sync"), new Runnable() {
            @Override
            public void run() {
                pull(true);
            }
        }).enabled(has));
        m.add(new Skin.MenuItem(t("Fetch"), new Runnable() {
            @Override
            public void run() {
                fetch();
            }
        }).enabled(has).separator());
        m.add(new Skin.MenuItem(t("Branches..."), new Runnable() {
            @Override
            public void run() {
                pickBranch();
            }
        }).enabled(has));
        m.add(new Skin.MenuItem(t("View History"), new Runnable() {
            @Override
            public void run() {
                showHistory();
            }
        }).enabled(has));
        m.add(new Skin.MenuItem(t("Add Remote..."), new Runnable() {
            @Override
            public void run() {
                addRemote();
            }
        }).enabled(has).separator());
        m.add(new Skin.MenuItem(t("Clone Repository"), new Runnable() {
            @Override
            public void run() {
                cloneRepo();
            }
        }));
        skin.popup(anchor, m);
    }

    private String remoteUrl(String name) throws IOException {
        String u = repo.config().get("remote", name, "url");
        if (u == null) throw new IOException(f("No remote named {0}. Add one with More Actions → Add Remote.", name));
        if (u.startsWith("git@") || u.startsWith("ssh://")) throw new IOException(t("SSH remotes aren't supported; use an HTTPS URL."));
        return u;
    }

    private String upstreamRemote() throws IOException {
        String b = repo.branch();
        String r = b == null ? null : repo.config().get("branch", b, "remote");
        if (r != null) return r;
        List<String> remotes = repo.config().subsections("remote");
        if (remotes.isEmpty()) throw new IOException(t("This repository has no remotes. Add one with More Actions → Add Remote."));
        return remotes.contains("origin") ? "origin" : remotes.get(0);
    }

    /** Credentials for a remote URL: the GitHub token for github.com, otherwise saved ones. */
    /** The URL of the last remote operation (for asking credentials). */
    private volatile String lastUrl;

    Remote.Auth authFor(String url) {
        String host, scheme;
        try {
            URI u = new URI(url);
            host = u.getHost();
            scheme = u.getScheme();
        } catch (Exception e) {
            host = null;
            scheme = null;
        }
        lastUrl = url;
        // Saved passwords and tokens only travel encrypted.
        if (host == null || !"https".equalsIgnoreCase(scheme)) return null;
        host = host.toLowerCase(java.util.Locale.ROOT);
        String saved = wb.prefs.raw().getString("state.gitcred." + host, null);
        if (saved != null) {
            int nl = saved.indexOf('\n');
            return new Remote.Auth(saved.substring(0, nl), saved.substring(nl + 1));
        }
        String gh = wb.account.githubToken();
        // github.com itself, not any host whose name merely ends that way (evilgithub.com).
        if (gh != null && (host.equals("github.com") || host.endsWith(".github.com"))) return new Remote.Auth("x-access-token", gh);
        return null;
    }

    private void askCredentials(final String label, final Op retry) {
        LinearLayout b = new LinearLayout(wb.activity);
        b.setOrientation(VERTICAL);
        final EditText user = skin.input(t("User name"));
        final EditText pass = skin.input(t("Password or access token"));
        user.setSingleLine(true);
        pass.setSingleLine(true);
        android.graphics.Typeface face = pass.getTypeface();
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setTypeface(face);
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = skin.dp(8);
        b.addView(user, lp);
        b.addView(pass, lp);
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = t("Git: authentication required");
        s.message = t("For GitHub use your user name and a personal access token (or save the token in Accounts).");
        s.content = b;
        s.positive = t("Sign In");
        s.negative = t("Cancel");
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                try {
                    String host = new URI(lastUrl).getHost();
                    wb.prefs.put("state.gitcred." + host, user.getText().toString().trim() + "\n" + pass.getText().toString());
                    op(label, retry);
                } catch (Exception e) {
                    wb.showMessage(e.getMessage());
                }
            }
        };
        skin.dialog(s).show();
    }

    void fetch() {
        op(t("Fetching..."), new Op() {
            @Override
            public String run() throws Exception {
                String r = upstreamRemote();
                String url = remoteUrl(r);
                Remote.FetchResult res = new Remote(url, authFor(url)).fetch(repo, r, progress);
                return res.objects == 0 ? t("Fetched: already up to date.") : f("Fetched {0} objects.", res.objects);
            }
        });
    }

    void pull(final boolean thenPush) {
        op(thenPush ? t("Syncing...") : t("Pulling..."), new Op() {
            @Override
            public String run() throws Exception {
                String b = repo.branch();
                if (b == null) throw new IOException(t("Check out a branch first."));
                String r = upstreamRemote();
                String url = remoteUrl(r);
                Remote remote = new Remote(url, authFor(url));
                remote.fetch(repo, r, progress);
                String merge = repo.config().get("branch", b, "merge");
                String rb = merge != null && merge.startsWith("refs/heads/") ? merge.substring(11) : b;
                String theirs = repo.resolveRef("refs/remotes/" + r + "/" + rb);
                String result;
                if (theirs == null) result = f("The remote has no branch {0} yet.", rb);
                else {
                    Repo.Identity who = identity();
                    Repo.MergeResult m = repo.merge(theirs, "Merge branch '" + rb + "' of " + url, who != null ? who : new Repo.Identity("droid_codology user", ""));
                    if (!m.conflicts.isEmpty()) {
                        return f("Merge conflicts in {0} file(s): {1}. Fix them, stage and commit.", m.conflicts.size(), join(m.conflicts));
                    }
                    result = m.upToDate ? t("Already up to date.") : m.fastForward ? t("Pulled (fast-forward).") : t("Pulled and merged.");
                }
                if (thenPush) {
                    String head = repo.head();
                    if (head != null) result += " " + remote.push(repo, head, b, false, progress);
                }
                return result;
            }
        });
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    void push() {
        op(t("Pushing..."), new Op() {
            @Override
            public String run() throws Exception {
                String b = repo.branch();
                if (b == null) throw new IOException(t("Check out a branch first."));
                String head = repo.head();
                if (head == null) throw new IOException(t("Nothing to push: make a commit first."));
                String r = upstreamRemote();
                String url = remoteUrl(r);
                GitConfig c = repo.config();
                if (c.get("branch", b, "remote") == null) {
                    // First push of this branch: track it (git push -u).
                    c.set("branch", b, "remote", r);
                    c.set("branch", b, "merge", "refs/heads/" + b);
                    c.save();
                }
                return new Remote(url, authFor(url)).push(repo, head, b, false, progress);
            }
        });
    }

    private void addRemote() {
        wb.prompt(t("Remote URL"), "https://github.com/user/repo.git", "https://", new Workbench.TextCallback() {
            @Override
            public void onText(String typed) {
                final String url = rememberCredentials(normalizeUrl(typed));
                op(t("Adding remote..."), new Op() {
                    @Override
                    public String run() throws Exception {
                        GitConfig c = repo.config();
                        String name = c.subsections("remote").contains("origin") ? "upstream" : "origin";
                        c.set("remote", name, "url", url);
                        c.set("remote", name, "fetch", "+refs/heads/*:refs/remotes/" + name + "/*");
                        c.save();
                        return f("Added remote {0}", name);
                    }
                });
            }
        });
    }

    void initRepo() {
        final File root = root();
        if (root == null) return;
        op(t("Initializing..."), new Op() {
            @Override
            public String run() throws Exception {
                Repo.init(root, "main");
                return t("Initialized an empty Git repository.");
            }
        });
    }

    /**
     * https://user:token@host/repo → the credentials are saved for the host (like a git credential
     * helper) and the URL without them is returned, so the token isn't kept in .git/config or shown.
     */
    String rememberCredentials(String url) {
        String clean = Remote.withoutCredentials(url);
        if (clean.equals(url.trim().replaceAll("/+$", ""))) return url;
        try {
            URI u = new URI(url.trim());
            String info = u.getRawUserInfo();
            if (info != null && "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null) {
                int c = info.indexOf(':');
                String user = java.net.URLDecoder.decode((c < 0 ? info : info.substring(0, c)).replace("+", "%2B"), "UTF-8");
                String pass = c < 0 ? "" : java.net.URLDecoder.decode(info.substring(c + 1).replace("+", "%2B"), "UTF-8");
                wb.prefs.put("state.gitcred." + u.getHost().toLowerCase(java.util.Locale.ROOT), user + "\n" + pass);
                return clean;
            }
        } catch (Exception ignored) {
        }
        return url; // http: keep them in the URL as typed; they're used from there
    }

    /** "github.com/user/repo" -> "https://github.com/user/repo". */
    static String normalizeUrl(String u) {
        u = u.trim();
        if (!u.contains("://") && !u.startsWith("git@")) u = "https://" + u;
        return u;
    }

    void cloneRepo() {
        wb.prompt(t("Clone Repository"), "https://github.com/user/repo.git", "https://", new Workbench.TextCallback() {
            @Override
            public void onText(String typed) {
                final String url = rememberCredentials(normalizeUrl(typed));
                File start = root() != null ? root().getParentFile() : android.os.Environment.getExternalStorageDirectory();
                FolderPicker.pick(wb, t("Choose a folder to clone into"), start, null, new FolderPicker.Callback() {
                    @Override
                    public void picked(File dir, String name) {
                        String repoName = url.trim().replaceAll("/+$", "").replaceAll("\\.git$", "");
                        repoName = repoName.substring(repoName.lastIndexOf('/') + 1);
                        if (repoName.isEmpty() || repoName.equals(".") || repoName.equals("..")) repoName = "repository";
                        final File target = new File(dir, repoName);
                        op(f("Cloning into {0}...", repoName), new Op() {
                            @Override
                            public String run() throws Exception {
                                Remote.cloneInto(url.trim(), target, authFor(url.trim()), progress);
                                wb.ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        wb.openFolder(target);
                                    }
                                });
                                return f("Cloned {0}", target.getName());
                            }
                        });
                    }
                });
            }
        });
    }

}
