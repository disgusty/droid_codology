package com.disgusty.droid_codology.work;

import android.app.Dialog;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.disgusty.droid_codology.ai.AiClient;
import com.disgusty.droid_codology.ai.AiProvider;
import com.disgusty.droid_codology.ui.Skin;

import java.util.ArrayList;
import java.util.List;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/**
 * Inline edit (Cursor's Ctrl+K / Copilot's Ctrl+I): rewrite the selection, or generate code at the
 * caret, from an instruction. The answer streams into a preview and is applied on Accept as one
 * undo step.
 */
final class InlineAi {
    private static final String SYSTEM = "You are a code editing engine. You receive part of a file and an instruction. "
            + "Reply with ONLY the code that replaces the marked selection (or that is inserted at the marked position): "
            + "no explanations, no Markdown fences. Keep the indentation and style of the surrounding code.";

    private InlineAi() {
    }

    static void start(final Workbench wb) {
        final Document d = wb.activeDocument();
        if (d == null || d.readOnly) return;
        final AiProvider model = currentModel(wb);
        if (model == null) {
            wb.showMessage(t("Add an AI provider with an API key first."));
            wb.openAccounts();
            return;
        }
        int p = d.engine.sel.primary();
        final int start = d.engine.sel.start(p), end = d.engine.sel.end(p);
        boolean hasSel = end > start;
        wb.prompt(hasSel ? t("Edit Selection with AI") : t("Generate Code with AI"),
                hasSel ? t("e.g. add error handling") : t("e.g. a function that parses dates"), null, new Workbench.TextCallback() {
                    @Override
                    public void onText(String instruction) {
                        run(wb, d, model, start, end, instruction);
                    }
                });
    }

    static AiProvider currentModel(Workbench wb) {
        List<AiProvider> all = wb.ai.all(wb.account.server(), wb.account.signedIn());
        String sel = wb.ai.selected();
        AiProvider fallback = null;
        for (AiProvider p : all) {
            for (String m : p.models) {
                boolean usable = !p.needsKey() || !p.apiKey.isEmpty();
                if (!usable) continue;
                AiProvider one = new AiProvider(p.id, p.name, p.kind, p.baseUrl, p.apiKey, m);
                if (sel.equals(p.id + "/" + m)) return one;
                if (fallback == null) fallback = one;
            }
        }
        return fallback;
    }

    static String prompt(Document d, int start, int end, String instruction) {
        int firstLine = d.text.lineOfOffset(start), lastLine = d.text.lineOfOffset(end);
        int ctxStart = d.text.lineStart(Math.max(0, firstLine - 80));
        int ctxEnd = d.text.lineEnd(Math.min(d.text.lineCount() - 1, lastLine + 40));
        String before = d.text.substring(ctxStart, start), sel = d.text.substring(start, end), after = d.text.substring(end, ctxEnd);
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(d.name).append(" (").append(d.language.id).append(")\n\n");
        sb.append("```\n").append(before);
        if (start == end) sb.append("<<<INSERT HERE>>>");
        else sb.append("<<<SELECTION>>>").append(sel).append("<<<END SELECTION>>>");
        sb.append(after).append("\n```\n\n");
        sb.append("Instruction: ").append(instruction);
        return sb.toString();
    }

    /** Removes a Markdown fence the model may add despite the instructions. */
    static String clean(String s) {
        String t = s;
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? "" : t.substring(nl + 1);
            int close = t.lastIndexOf("```");
            if (close >= 0) t = t.substring(0, close);
            if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
        }
        return t.replace("<<<SELECTION>>>", "").replace("<<<END SELECTION>>>", "").replace("<<<INSERT HERE>>>", "");
    }

    private static void run(final Workbench wb, final Document d, final AiProvider model, final int start, final int end, String instruction) {
        final Skin skin = wb.skin;
        final TextView preview = skin.text("", skin.uiSp() - 1, skin.sideBarFg, Typeface.MONOSPACE);
        preview.setPadding(0, skin.dp(4), 0, skin.dp(4));
        preview.setText(t("Thinking..."));
        ScrollView sv = new ScrollView(wb.activity);
        sv.addView(preview);
        if (android.os.Build.VERSION.SDK_INT >= 26) com.disgusty.droid_codology.compat.Api26.noFocusHighlight(sv);
        sv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, skin.dp(260)));
        final AiClient.Cancel cancel = new AiClient.Cancel();
        final StringBuilder out = new StringBuilder();
        final boolean[] done = {false};
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = "✦ " + model.models.get(0);
        s.content = sv;
        s.positive = t("Accept");
        s.negative = t("Discard");
        s.fullWidth = true;
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                if (!done[0]) {
                    cancel.cancel();
                    wb.showMessage(t("Stopped; applying what was generated."));
                }
                String code = clean(out.toString());
                if (d.text.length() < end || code.isEmpty()) return;
                d.engine.replaceAndSelect(start, end, code, start, start + code.length());
                if (d == wb.activeDocument()) {
                    wb.editor.ensureCaretVisible();
                    wb.editor.invalidate();
                }
            }
        };
        s.onNegative = new Runnable() {
            @Override
            public void run() {
                cancel.cancel();
            }
        };
        final Dialog dialog = skin.dialog(s);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(android.content.DialogInterface di) {
                cancel.cancel();
            }
        });
        dialog.show();
        final List<AiClient.Message> msgs = new ArrayList<AiClient.Message>();
        msgs.add(new AiClient.Message("user", prompt(d, start, end, instruction)));
        final String token = wb.account.token();
        wb.bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    AiClient.chat(model, model.models.get(0), SYSTEM, msgs, 0, token, new AiClient.Listener() {
                        @Override
                        public void onDelta(final String piece) {
                            wb.ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    out.append(piece);
                                    preview.setText(clean(out.toString()));
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
                        done[0] = true;
                        if (error != null && !cancel.isCancelled()) {
                            preview.setText(f("Error: {0}", error));
                            out.setLength(0);
                        } else if (out.length() == 0 && !cancel.isCancelled()) {
                            preview.setText(t("The model returned nothing."));
                        }
                    }
                });
            }
        });
    }
}
