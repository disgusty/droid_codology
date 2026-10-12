package com.disgusty.droid_codology.work;

import android.annotation.SuppressLint;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.disgusty.droid_codology.ai.AiClient;
import com.disgusty.droid_codology.ai.AiProvider;
import com.disgusty.droid_codology.editor.EditorColors;
import com.disgusty.droid_codology.lang.MarkdownHtml;
import com.disgusty.droid_codology.ui.Skin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/**
 * The Chat view (like Copilot / Cursor chat): talk to the selected model about the current file
 * or selection; code blocks in answers can be copied or inserted into the editor.
 */
final class ChatView extends LinearLayout {
    static final String SYSTEM = "You are an expert programming assistant inside droid_codology, a code editor for Android. "
            + "Answer concisely. Use Markdown; put code in fenced blocks with a language tag. "
            + "When you change code from the user's file, show the complete changed part so it can be inserted directly.";

    private final Workbench wb;
    private final Skin skin;
    private final WebView web;
    private final EditText input;
    private final ImageView sendButton;
    private final TextView modelLabel, contextChip;
    private boolean includeContext = true;
    private boolean pageReady;

    private static final class Msg {
        final String role;
        String text;
        /** What was sent to the model (text plus attached context); null = same as text. */
        String sent;
        String attachment;
        boolean error;
        /** Agent tool step (role "step"); live while the agent runs, a snapshot after a restart. */
        Agent.Step step;
        String stepTitle, stepDetail, stepOutput, stepTool;
        int stepState;

        Msg(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    private final ArrayList<Msg> messages = new ArrayList<Msg>();
    private AiClient.Cancel running;
    private Agent agent;
    private boolean agentMode;
    private TextView modeChip;
    private Msg agentText;
    private boolean agentNewTurn;
    private boolean refreshScheduled;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    ChatView(final Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        setOrientation(VERTICAL);
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("add", t("New Chat"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                newChat();
            }
        }));
        acts.add(new Skin.Action("settings", t("AI Settings"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.openAccounts();
            }
        }));
        addView(skin.sideBarHeader(t("Chat"), acts));

        agentMode = "agent".equals(wb.prefs.str("chat.mode", "agent"));
        LinearLayout top = new LinearLayout(wb.activity);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(skin.dp(12), skin.dp(2), skin.dp(12), skin.dp(6));
        modeChip = skin.text("", skin.uiSp() - 1, skin.accent, skin.medium());
        modeChip.setPadding(skin.dp(8), skin.dp(3), skin.dp(8), skin.dp(3));
        modeChip.setBackgroundDrawable(skin.rounded(Skin.withAlpha(skin.accent, 0x26), skin.dp(10)));
        modeChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pickMode();
            }
        });
        top.addView(modeChip);
        modelLabel = skin.text("", skin.uiSp() - 1, skin.descriptionFg, skin.regular());
        modelLabel.setPadding(skin.dp(10), 0, 0, 0);
        modelLabel.setSingleLine(true);
        modelLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        modelLabel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pickModel();
            }
        });
        top.addView(modelLabel, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addView(top);
        updateMode();

        web = new WebView(wb.activity);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.addJavascriptInterface(new Bridge(), "DCChat");
        web.setWebViewClient(new PreviewClient(wb.activity) {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                refresh();
            }
        });
        web.setBackgroundColor(skin.sideBarBg);
        addView(web, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        contextChip = skin.text("", skin.uiSp() - 2, skin.descriptionFg, skin.regular());
        contextChip.setPadding(skin.dp(16), skin.dp(6), skin.dp(16), skin.dp(2));
        contextChip.setSingleLine(true);
        contextChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                includeContext = !includeContext;
                updateContextChip();
            }
        });
        addView(contextChip);

        LinearLayout row = new LinearLayout(wb.activity);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(skin.dp(8), skin.dp(4), skin.dp(4), skin.dp(8));
        input = skin.input(t("Ask about your code..."));
        // Single-line input type so the keyboard shows a Send key; long questions still wrap.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(6);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                send();
                return true;
            }
        });
        input.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                // Hardware keyboards: Enter sends, Shift+Enter starts a new line.
                if (keyCode != KeyEvent.KEYCODE_ENTER || event.getAction() != KeyEvent.ACTION_DOWN) return keyCode == KeyEvent.KEYCODE_ENTER && !event.isShiftPressed();
                if (event.isShiftPressed()) {
                    input.getText().insert(input.getSelectionStart(), "\n");
                    return true;
                }
                send();
                return true;
            }
        });
        row.addView(input, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sendButton = new ImageView(wb.activity);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setContentDescription(t("Send"));
        sendButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running != null || agent != null) stop();
                else send();
            }
        });
        row.addView(sendButton, new LayoutParams(skin.dp(44), skin.dp(44)));
        addView(row);

        load();
        updateButton();
        updateModelLabel();
        updateMode();
        updateContextChip();
        web.loadDataWithBaseURL("file:///android_asset/", shell(), "text/html", "UTF-8", null);
    }

    /** Called when the active editor changes. */
    void onEditorChanged() {
        updateContextChip();
    }

    void focusInput() {
        input.requestFocus();
    }

    /** Opens with a question about the selection (Explain, Fix...). */
    void ask(String question) {
        input.setText(question);
        send();
    }

    // ------------------------------------------------------------------ Ask / Agent

    private void updateMode() {
        modeChip.setText(agentMode ? t("Agent") + " ▾" : t("Ask") + " ▾");
        if (input != null) input.setHint(agentMode ? t("Describe a task for the agent...") : t("Ask about your code..."));
    }

    private void pickMode() {
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        QuickPick.Item ag = new QuickPick.Item(t("Agent"), agentMode ? "✓" : null, new Runnable() {
            @Override
            public void run() {
                setMode(true);
            }
        });
        ag.description = t("Works on the project: reads, edits files and runs commands (asks before changing anything)");
        items.add(ag);
        QuickPick.Item ask = new QuickPick.Item(t("Ask"), !agentMode ? "✓" : null, new Runnable() {
            @Override
            public void run() {
                setMode(false);
            }
        });
        ask.description = t("Answers questions about the open file or selection");
        items.add(ask);
        QuickPick.Item perms = new QuickPick.Item(t("Reset agent permissions"), null, new Runnable() {
            @Override
            public void run() {
                Agent.resetPermissions();
                wb.showMessage(t("The agent will ask again before edits and commands."));
            }
        });
        perms.description = (Agent.allowEdits ? t("edits allowed") : t("edits ask")) + " · "
                + (Agent.allowCommands ? t("commands allowed") : t("commands ask"));
        items.add(perms);
        wb.quickPick().showItems(t("Chat Mode"), items);
    }

    private void setMode(boolean agent) {
        agentMode = agent;
        wb.prefs.put("chat.mode", agent ? "agent" : "ask");
        updateMode();
        updateContextChip();
    }

    // ------------------------------------------------------------------ model & context

    private AiProvider[] currentModel() {
        List<AiProvider> all = wb.ai.all(wb.account.server(), wb.account.signedIn());
        String sel = wb.ai.selected();
        for (AiProvider p : all) {
            for (String m : p.models) {
                if (sel.equals(p.id + "/" + m)) {
                    AiProvider one = new AiProvider(p.id, p.name, p.kind, p.baseUrl, p.apiKey, m);
                    return new AiProvider[]{p, one};
                }
            }
        }
        for (AiProvider p : all) {
            if (!p.models.isEmpty() && (!p.needsKey() || !p.apiKey.isEmpty())) {
                return new AiProvider[]{p, new AiProvider(p.id, p.name, p.kind, p.baseUrl, p.apiKey, p.models.get(0))};
            }
        }
        return null;
    }

    void updateModelLabel() {
        AiProvider[] m = currentModel();
        modelLabel.setText(m == null ? t("No model — set up an AI provider") : "✦ " + m[1].models.get(0) + "  ·  " + m[0].name + "  ▾");
    }

    private void pickModel() {
        List<AiProvider> all = wb.ai.all(wb.account.server(), wb.account.signedIn());
        ArrayList<QuickPick.Item> items = new ArrayList<QuickPick.Item>();
        String sel = wb.ai.selected();
        for (final AiProvider p : all) {
            for (final String m : p.models) {
                QuickPick.Item it = new QuickPick.Item(m, (p.id + "/" + m).equals(sel) ? "✓" : null, new Runnable() {
                    @Override
                    public void run() {
                        wb.ai.select(p.id, m);
                        updateModelLabel();
                    }
                });
                it.description = p.name + (p.needsKey() && p.apiKey.isEmpty() ? " — " + t("no API key") : "");
                items.add(it);
            }
        }
        items.add(new QuickPick.Item(t("Add AI Provider..."), null, new Runnable() {
            @Override
            public void run() {
                wb.openAccounts();
            }
        }));
        wb.quickPick().showItems(t("Select Model"), items);
    }

    private void updateContextChip() {
        Document d = wb.activeDocument();
        if (d == null) {
            contextChip.setVisibility(GONE);
            return;
        }
        contextChip.setVisibility(VISIBLE);
        boolean sel = d.engine.sel.anyNonEmpty();
        String what = sel ? f("{0} (selection)", d.name) : d.name;
        // Plain symbols: Android 4.x fonts have no emoji.
        contextChip.setText(includeContext ? "✓ " + f("Context: {0}", what) : "✗ " + f("Context: {0} (not attached)", what));
        contextChip.setAlpha(includeContext ? 1f : 0.6f);
    }

    /** The user's text plus the attached file / selection. */
    private String withContext(String text, Msg m) {
        Document d = wb.activeDocument();
        if (!includeContext || d == null) return text;
        String lang = d.language.id;
        StringBuilder sb = new StringBuilder();
        String path = d.file != null ? (wb.workspace != null && wb.workspace.contains(d.file)
                ? wb.workspace.root.toURI().relativize(d.file.toURI()).getPath() : d.file.getName()) : d.name;
        if (d.engine.sel.anyNonEmpty()) {
            int s = d.engine.sel.start(d.engine.sel.primary()), e = d.engine.sel.end(d.engine.sel.primary());
            String code = d.text.substring(s, Math.min(e, s + 40000));
            sb.append("Selected code from `").append(path).append("` (lines ").append(d.text.lineOfOffset(s) + 1).append('-')
                    .append(d.text.lineOfOffset(e) + 1).append("):\n```").append(lang).append('\n').append(code).append("\n```\n\n");
            m.attachment = f("{0} (selection)", d.name);
        } else {
            String all = d.text.toString();
            boolean cut = all.length() > 60000;
            if (cut) all = all.substring(0, 60000);
            sb.append("Current file `").append(path).append("`").append(cut ? " (truncated)" : "").append(":\n```").append(lang)
                    .append('\n').append(all).append("\n```\n\n");
            m.attachment = d.name;
        }
        sb.append(text);
        return sb.toString();
    }

    // ------------------------------------------------------------------ sending

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() || running != null || agent != null) return;
        final AiProvider[] model = currentModel();
        if (model == null) {
            wb.showMessage(t("Add an AI provider with an API key first."));
            wb.openAccounts();
            return;
        }
        if (model[1].needsKey() && model[1].apiKey.isEmpty()) {
            wb.showMessage(f("Enter an API key for {0}.", model[0].name));
            wb.openAccounts();
            return;
        }
        if (agentMode) {
            sendAgent(text, model[1]);
            return;
        }
        Msg user = new Msg("user", text);
        user.sent = withContext(text, user);
        messages.add(user);
        final Msg answer = new Msg("assistant", "");
        messages.add(answer);
        input.setText("");
        final ArrayList<AiClient.Message> history = new ArrayList<AiClient.Message>();
        for (Msg m : messages) {
            if (m == answer || m.error) continue;
            history.add(new AiClient.Message(m.role, m.sent != null ? m.sent : m.text));
        }
        final AiClient.Cancel cancel = new AiClient.Cancel();
        running = cancel;
        updateButton();
        refresh();
        final String token = wb.account.token();
        final String system = SYSTEM + (wb.ai.systemPrompt().isEmpty() ? "" : "\n\n" + wb.ai.systemPrompt());
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    AiClient.chat(model[1], model[1].models.get(0), system, history, 0, token, new AiClient.Listener() {
                        @Override
                        public void onDelta(final String piece) {
                            wb.ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    answer.text += piece;
                                    scheduleRefresh();
                                }
                            });
                        }
                    }, cancel);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String error = err;
                wb.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null && !cancel.isCancelled()) {
                            answer.error = true;
                            answer.text = (answer.text.isEmpty() ? "" : answer.text + "\n\n") + error;
                        }
                        if (cancel.isCancelled() && answer.text.isEmpty()) messages.remove(answer);
                        if (running == cancel) running = null;
                        updateButton();
                        refresh();
                        save();
                    }
                });
            }
        });
    }

    /** Agent mode: the task runs as a tool loop; text and steps stream into the chat. */
    private void sendAgent(String text, AiProvider model) {
        Msg user = new Msg("user", text);
        // The agent reads files itself; only a selection is attached directly.
        Document d = wb.activeDocument();
        StringBuilder sent = new StringBuilder();
        if (includeContext && d != null && d.engine.sel.anyNonEmpty()) {
            user.sent = withContext(text, user);
        } else {
            if (includeContext && d != null && d.file != null) {
                sent.append("(I have `").append(Agent.relPath(wb, d.file)).append("` open, caret on line ")
                        .append(d.text.lineOfOffset(d.engine.sel.caret()) + 1).append(".)\n\n");
                user.attachment = d.name;
            }
            sent.append(text);
            user.sent = sent.toString();
        }
        messages.add(user);
        input.setText("");
        final ArrayList<AiClient.Message> history = new ArrayList<AiClient.Message>();
        for (Msg m : messages) {
            if (m.error || m.role.equals("step")) continue;
            history.add(new AiClient.Message(m.role, m.sent != null ? m.sent : m.text));
        }
        agentText = null;
        agentNewTurn = true;
        agent = new Agent(wb, new Agent.Listener() {
            @Override
            public void onText(String t) {
                if (t.isEmpty()) {
                    agentNewTurn = true;
                    return;
                }
                if (agentNewTurn || agentText == null) {
                    agentText = new Msg("assistant", t);
                    messages.add(agentText);
                    agentNewTurn = false;
                } else {
                    agentText.text = t;
                }
                scheduleRefresh();
            }

            @Override
            public void onStep(Agent.Step s) {
                Msg found = null;
                for (Msg m : messages) if (m.step == s) found = m;
                if (found == null) {
                    found = new Msg("step", "");
                    found.step = s;
                    messages.add(found);
                }
                snapshot(found);
                agentNewTurn = true;
                refresh();
            }

            @Override
            public void onFinished(String error) {
                if (error != null) {
                    Msg m = new Msg("assistant", error);
                    m.error = true;
                    messages.add(m);
                }
                agent = null;
                updateButton();
                refresh();
                save();
            }
        });
        final Agent a = agent;
        final AiProvider mdl = model;
        updateButton();
        refresh();
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                a.run(mdl, history);
            }
        });
    }

    private static void snapshot(Msg m) {
        Agent.Step s = m.step;
        if (s == null) return;
        m.stepTitle = s.title;
        m.stepDetail = s.detail;
        m.stepOutput = s.output;
        m.stepState = s.state;
        m.stepTool = s.tool;
    }

    private Agent.Step stepById(int id) {
        for (Msg m : messages) if (m.step != null && m.step.id == id) return m.step;
        return null;
    }

    /** Stops the reply or agent run in progress (also when the window is rebuilt or closed). */
    void stop() {
        if (running != null) running.cancel();
        if (agent != null) agent.stop();
    }

    private void newChat() {
        stop();
        // "Always allow" was given for that conversation, not for every future one.
        Agent.resetPermissions();
        messages.clear();
        refresh();
        save();
        input.requestFocus();
    }

    private void updateButton() {
        boolean busy = running != null || agent != null;
        sendButton.setImageDrawable(skin.icon(busy ? "stop" : "send", busy ? skin.descriptionFg : skin.accent, 22));
        sendButton.setContentDescription(busy ? t("Stop") : t("Send"));
    }

    // ------------------------------------------------------------------ rendering

    private void scheduleRefresh() {
        if (refreshScheduled) return;
        refreshScheduled = true;
        wb.ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshScheduled = false;
                refresh();
            }
        }, Build.VERSION.SDK_INT < 19 ? 400 : 120);
    }

    private void refresh() {
        if (!pageReady) return;
        if (Build.VERSION.SDK_INT >= 19) com.disgusty.droid_codology.compat.Api19.evaluate(web, "refresh()");
        else web.loadUrl("javascript:refresh()");
    }

    /** HTML of the conversation (pulled by the page through the bridge). */
    private String messagesHtml() {
        if (messages.isEmpty()) {
            AiProvider[] m = currentModel();
            return "<div class='empty'><div class='big'>✦</div><p>" + MarkdownHtml.esc(t("Ask about the open file, a selection, or anything else."))
                    + "</p><p class='hint'>" + MarkdownHtml.esc(m == null ? t("Set up an AI provider in Accounts first.")
                    : t("Tip: select code in the editor to ask about just that part.")) + "</p></div>";
        }
        final EditorColors k = wb.editor.colors();
        // Model output is untrusted: no raw HTML in it may reach this page, whose bridge approves agent steps.
        MarkdownHtml md = new MarkdownHtml(new MarkdownHtml.CodeColorizer() {
            @Override
            public String colorize(String hint, String code) {
                return MarkdownPreview.highlight(hint, code, k);
            }
        }).safe(true);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);
            if (m.role.equals("user")) {
                sb.append("<div class='msg user'>");
                if (m.attachment != null) sb.append("<div class='att'>").append(MarkdownHtml.esc(f("Context: {0}", m.attachment))).append("</div>");
                sb.append("<div class='utext'>").append(MarkdownHtml.esc(m.text)).append("</div></div>");
            } else if (m.role.equals("step")) {
                stepHtml(sb, m);
            } else if (m.error) {
                sb.append("<div class='msg err'>").append(MarkdownHtml.esc(m.text)).append("</div>");
            } else {
                boolean live = running != null && i == messages.size() - 1;
                sb.append("<div class='msg ai'>");
                sb.append(m.text.isEmpty() && live ? "<span class='dots'>…</span>" : md.render(m.text));
                sb.append("</div>");
            }
        }
        if (agent != null) {
            Msg last = messages.get(messages.size() - 1);
            boolean waiting = last.step != null && last.step.state == Agent.WAITING;
            if (!waiting && (last.role.equals("user") || last.role.equals("step"))) {
                sb.append("<div class='working'><span class='dots'>").append(MarkdownHtml.esc(t("Agent is working…"))).append("</span></div>");
            }
        }
        return sb.toString();
    }

    private static final String[] STATE_COLORS = {"#888", "#E2C08D", "#3794FF", "#73C991", "#888", "#F14C4C", "#888"};

    private void stepHtml(StringBuilder sb, Msg m) {
        Agent.Step s = m.step;
        int state = s != null ? s.state : m.stepState;
        String title = s != null ? s.title : m.stepTitle;
        String detail = s != null ? s.detail : m.stepDetail;
        String output = s != null ? s.output : m.stepOutput;
        String tool = s != null ? s.tool : m.stepTool;
        String[] labels = {t("pending"), t("waiting for you"), t("running"), t("done"), t("rejected"), t("failed"), t("reverted")};
        sb.append("<div class='step'><div class='sh'><span class='dot' style='background:").append(STATE_COLORS[Math.min(6, state)])
                .append("'></span><b>").append(MarkdownHtml.esc(title == null ? "" : title)).append("</b> <span class='ss'>")
                .append(labels[Math.min(6, state)]).append("</span></div>");
        boolean isEdit = "edit_file".equals(tool) || "write_file".equals(tool) || "delete_file".equals(tool);
        if (detail != null && !detail.isEmpty()) {
            boolean showDetail = state == Agent.WAITING || isEdit || "run_command".equals(tool);
            if (showDetail) {
                sb.append("<pre class='nobar'>");
                if (isEdit) {
                    String[] lines = detail.split("\n");
                    for (int i = 0; i < lines.length && i < 400; i++) {
                        String l = lines[i];
                        String cls = l.startsWith("+++") || l.startsWith("---") ? "dh" : l.startsWith("+") ? "da" : l.startsWith("-") ? "dd" : l.startsWith("@@") ? "dh" : "";
                        sb.append("<span class='").append(cls).append("'>").append(MarkdownHtml.esc(l)).append("</span>\n");
                    }
                } else {
                    sb.append("$ ").append(MarkdownHtml.esc(detail));
                }
                sb.append("</pre>");
            }
        }
        if (output != null && !output.isEmpty() && (state == Agent.FAILED || "run_command".equals(tool))) {
            String o = output.length() > 2500 ? output.substring(0, 2500) + "\n…" : output;
            sb.append("<pre class='nobar out'>").append(MarkdownHtml.esc(o)).append("</pre>");
        }
        if (s != null) {
            int id = s.id;
            if (state == Agent.WAITING) {
                boolean cmd = "run_command".equals(tool);
                sb.append("<div class='acts'>")
                        .append(button(id, "approve", cmd ? t("Run") : t("Apply"), true))
                        .append(button(id, "reject", t("Reject"), false))
                        .append(button(id, "always", cmd ? t("Always allow commands") : t("Always allow edits"), false))
                        .append("</div>");
            } else if (state == Agent.DONE && isEdit && s.file != null) {
                sb.append("<div class='acts'>");
                if (!"delete_file".equals(tool)) sb.append(button(id, "open", t("Open File"), false));
                sb.append(button(id, "revert", t("Revert"), false)).append("</div>");
            }
        }
        sb.append("</div>");
    }

    private static String button(int id, String action, String label, boolean primary) {
        return "<a href='#' class='btn" + (primary ? " pri" : "") + "' onclick=\"DCChat.step(" + id + ",'" + action + "');return false;\">"
                + MarkdownHtml.esc(label) + "</a>";
    }

    private String shell() {
        EditorColors k = wb.editor.colors();
        int bg = skin.sideBarBg, fg = skin.sideBarFg;
        int codeBg = k.background, border = Skin.blend(bg, k.dark ? 0x33FFFFFF : 0x22000000);
        int userBg = Skin.blend(bg, (skin.accent & 0x00FFFFFF) | 0x26000000);
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<style>body{margin:0;background:" + hex(bg) + ";color:" + hex(fg) + ";font-family:Roboto,sans-serif;font-size:14px;line-height:1.5;"
                + "word-wrap:break-word}#log{padding:4px 10px 16px}.msg{margin:10px 0}.user{background:" + hex(userBg) + ";border-radius:8px;padding:8px 10px}"
                + ".utext{white-space:pre-wrap}.att{font-size:12px;opacity:.7;margin-bottom:4px}.err{color:#F14C4C;white-space:pre-wrap}"
                + "pre{background:" + hex(codeBg) + ";padding:10px;border-radius:6px;overflow:auto;margin:6px 0 2px;font-size:12px}"
                + "code{font-family:monospace;font-size:12.5px}p code,li code{background:" + hex(codeBg) + ";padding:1px 4px;border-radius:3px}"
                + "a{color:" + hex(skin.textLink) + "}.bar{text-align:right;margin-bottom:6px}.bar a{font-size:12px;margin-left:14px;text-decoration:none;"
                + "color:" + hex(skin.textLink) + "}table{border-collapse:collapse}th,td{border:1px solid " + hex(border) + ";padding:3px 8px}"
                + ".empty{text-align:center;opacity:.75;padding:40px 16px}.big{font-size:40px;color:" + hex(skin.accent) + "}.hint{font-size:12px;opacity:.8}"
                + ".dots{opacity:.6}h1,h2,h3{font-size:1.1em}"
                + ".step{border:1px solid " + hex(border) + ";border-radius:8px;padding:6px 8px;margin:8px 0;font-size:13px}"
                + ".sh{display:block}.dot{display:inline-block;width:8px;height:8px;border-radius:4px;margin-right:6px}"
                + ".ss{opacity:.65;font-size:12px;margin-left:4px}.step pre{max-height:320px;font-size:11.5px}"
                + ".da{color:#73C991}.dd{color:#F14C4C}.dh{color:#3794FF}.out{opacity:.85}"
                + ".acts{margin-top:6px}.btn{display:inline-block;padding:5px 12px;margin:4px 6px 0 0;border-radius:6px;text-decoration:none;"
                + "border:1px solid " + hex(border) + ";color:" + hex(fg) + "}.pri{background:" + hex(skin.accent) + ";color:#fff;border-color:"
                + hex(skin.accent) + "}.working{margin:8px 0;font-size:13px}"
                + "</style></head><body><div id='log'></div><script>"
                + "function refresh(){var l=document.getElementById('log');"
                + "var atBottom=(window.innerHeight+window.pageYOffset)>=document.body.scrollHeight-40;"
                + "l.innerHTML=DCChat.html();var pres=l.getElementsByTagName('pre');"
                + "for(var i=0;i<pres.length;i++){if(pres[i].className.indexOf('nobar')>=0)continue;(function(p){var b=document.createElement('div');b.className='bar';"
                + "var c=document.createElement('a');c.href='#';c.textContent='" + jsStr(t("Copy")) + "';c.onclick=function(){DCChat.copy(p.textContent);return false;};"
                + "var n=document.createElement('a');n.href='#';n.textContent='" + jsStr(t("Insert")) + "';n.onclick=function(){DCChat.insert(p.textContent);return false;};"
                + "b.appendChild(c);b.appendChild(n);p.parentNode.insertBefore(b,p.nextSibling);})(pres[i]);}"
                + "if(atBottom)window.scrollTo(0,document.body.scrollHeight);}"
                + "</script></body></html>";
    }

    private static String jsStr(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    final class Bridge {
        @JavascriptInterface
        public String html() {
            final String[] out = {""};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            wb.ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        out[0] = messagesHtml();
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return out[0];
        }

        @JavascriptInterface
        public void step(final int id, final String action) {
            wb.ui.post(new Runnable() {
                @Override
                public void run() {
                    Agent.Step s = stepById(id);
                    if (s == null) return;
                    if (action.equals("open") && s.file != null) {
                        wb.openFile(s.file, false, -1, 0, 0);
                        wb.closeOverlaySideBar();
                        return;
                    }
                    if (action.equals("revert")) {
                        Agent a = agent != null ? agent : new Agent(wb, null);
                        a.revert(s);
                        // Without a running agent, refresh once the revert lands.
                        wb.ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                for (Msg m : messages) if (m.step == s) snapshot(m);
                                refresh();
                                save();
                            }
                        }, 600);
                        return;
                    }
                    if (agent == null) return;
                    agent.decide(s, action.equals("approve") ? 1 : action.equals("always") ? 3 : 2);
                }
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            wb.ui.post(new Runnable() {
                @Override
                public void run() {
                    wb.copyText(text);
                    wb.showMessage(t("Copied"));
                }
            });
        }

        @JavascriptInterface
        public void insert(final String text) {
            wb.ui.post(new Runnable() {
                @Override
                public void run() {
                    insertIntoEditor(text);
                }
            });
        }
    }

    /** Replaces the selection (or inserts at the caret) with a code block from an answer. */
    private void insertIntoEditor(String code) {
        Document d = wb.activeDocument();
        if (d == null || d.readOnly) {
            wb.showMessage(t("Open a file to insert code into."));
            return;
        }
        if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
        int p = d.engine.sel.primary();
        int s = d.engine.sel.start(p), e = d.engine.sel.end(p);
        if (s == e && code.contains("\n")) {
            // A block at a bare caret goes on lines of its own, not into the middle of a line.
            int line = d.text.lineOfOffset(s);
            if (!d.text.substring(d.text.lineStart(line), s).trim().isEmpty()) code = "\n" + code;
            if (!d.text.substring(s, d.text.lineEnd(line)).trim().isEmpty()) code = code + "\n";
        }
        d.engine.replaceAndSelect(s, e, code, s + code.length(), s + code.length());
        wb.closeOverlaySideBar();
        wb.editor.ensureCaretVisible();
        wb.editor.invalidate();
        wb.showMessage(t("Inserted into the editor (Undo reverts it)."));
    }

    // ------------------------------------------------------------------ persistence

    private File file() {
        return new File(wb.activity.getFilesDir(), "chat.json");
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Msg m : messages) {
                JSONObject o = new JSONObject().put("role", m.role).put("text", m.text).put("error", m.error);
                if (m.sent != null) o.put("sent", m.sent);
                if (m.attachment != null) o.put("attachment", m.attachment);
                if (m.role.equals("step")) {
                    snapshot(m);
                    // An unfinished step after a restart is shown as rejected (nothing ran).
                    int st = m.stepState == Agent.WAITING || m.stepState == Agent.PENDING || m.stepState == Agent.RUNNING ? Agent.REJECTED : m.stepState;
                    o.put("title", m.stepTitle).put("state", st).put("tool", m.stepTool);
                    if (m.stepDetail != null) o.put("detail", m.stepDetail.length() > 20000 ? m.stepDetail.substring(0, 20000) : m.stepDetail);
                    if (m.stepOutput != null) o.put("output", m.stepOutput);
                }
                a.put(o);
            }
            FileOutputStream os = new FileOutputStream(file());
            try {
                os.write(a.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void load() {
        File f = file();
        if (!f.isFile()) return;
        try {
            JSONArray a = new JSONArray(new String(ExtensionHost.readBytes(f), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Msg m = new Msg(o.getString("role"), o.optString("text"));
                m.error = o.optBoolean("error");
                m.sent = o.has("sent") ? o.getString("sent") : null;
                m.attachment = o.has("attachment") ? o.getString("attachment") : null;
                if (m.role.equals("step")) {
                    m.stepTitle = o.optString("title");
                    m.stepState = o.optInt("state", Agent.DONE);
                    m.stepTool = o.optString("tool");
                    m.stepDetail = o.optString("detail", null);
                    m.stepOutput = o.optString("output", null);
                }
                messages.add(m);
            }
        } catch (Exception ignored) {
        }
    }
}
