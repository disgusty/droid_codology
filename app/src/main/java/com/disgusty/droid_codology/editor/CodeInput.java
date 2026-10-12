package com.disgusty.droid_codology.editor;

import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;

import com.disgusty.droid_codology.text.EditEngine;
import com.disgusty.droid_codology.text.Selections;
import com.disgusty.droid_codology.text.TextBuffer;
import com.disgusty.droid_codology.text.UndoStack;

/**
 * Bridge between input methods and the editor. Implemented directly on the text buffer (no
 * Editable copy), with proper composing-region support so predictive keyboards (Gboard, SwiftKey,
 * Samsung) work, while single keystrokes still go through auto-indent and auto-closing.
 */
final class CodeInput extends BaseInputConnection {
    private final CodeView v;
    /** True while an IME call is modifying text (the composing region must survive it). */
    boolean editing;

    CodeInput(CodeView v) {
        super(v, false);
        this.v = v;
    }

    private EditEngine eng() {
        return v.engine();
    }

    private boolean writable() {
        return eng() != null && !v.readOnlyView && !(v.document() != null && v.document().readOnly);
    }

    private static String clean(CharSequence t) {
        String s = t.toString();
        if (s.indexOf('\r') >= 0) s = s.replace("\r\n", "\n").replace('\r', '\n');
        return s;
    }

    @Override
    public boolean beginBatchEdit() {
        v.beginBatch();
        return true;
    }

    @Override
    public boolean endBatchEdit() {
        v.endBatch();
        return true;
    }

    @Override
    public boolean commitText(CharSequence t, int newCursorPosition) {
        if (eng() == null) return false;
        String s = clean(t);
        if (v.composingStart < 0 && v.host() != null && v.host().interceptTyped(v, s)) return true;
        if (!writable()) return false;
        EditEngine e = eng();
        if (v.composingStart >= 0) {
            int cs = v.composingStart, ce = v.composingEnd;
            v.composingStart = v.composingEnd = -1;
            editing = true;
            e.begin(UndoStack.KIND_TYPE);
            Selections sel = e.sel;
            // Other carets get the committed text too (they did not see the composing preview).
            for (int i = sel.count() - 1; i >= 0; i--) {
                if (i == sel.primary()) continue;
                int st = sel.start(i), en = sel.end(i);
                if (st >= cs && en <= ce) continue;
                e.replace(st, en, s);
                sel.setAt(i, st + s.length(), st + s.length());
                if (st < cs) {
                    cs += s.length() - (en - st);
                    ce += s.length() - (en - st);
                }
            }
            e.replace(cs, ce, s);
            int caret = cursorFor(cs, cs + s.length(), newCursorPosition);
            sel.setAt(sel.primary(), caret, caret);
            e.end();
            editing = false;
            v.reportSelectionToIme();
            return true;
        }
        if (s.equals("\n")) e.newline();
        else if (s.equals("\t")) e.tab();
        else if (s.length() > 0) e.type(s);
        else if (e.sel.anyNonEmpty()) e.deleteSelections();
        return true;
    }

    private int cursorFor(int start, int end, int newCursorPosition) {
        int len = eng().text.length();
        int p = newCursorPosition > 0 ? end + newCursorPosition - 1 : start + newCursorPosition;
        return Math.max(0, Math.min(len, p));
    }

    @Override
    public boolean setComposingText(CharSequence t, int newCursorPosition) {
        if (!writable()) return false;
        String s = clean(t);
        EditEngine e = eng();
        Selections sel = e.sel;
        int p = sel.primary();
        int st, en;
        if (v.composingStart >= 0) {
            st = v.composingStart;
            en = v.composingEnd;
        } else {
            st = sel.start(p);
            en = sel.end(p);
        }
        editing = true;
        e.begin(UndoStack.KIND_TYPE);
        e.replace(st, en, s);
        int caret = cursorFor(st, st + s.length(), newCursorPosition);
        sel.setAt(sel.primary(), caret, caret);
        v.composingStart = s.isEmpty() ? -1 : st;
        v.composingEnd = s.isEmpty() ? -1 : st + s.length();
        e.end();
        editing = false;
        v.reportSelectionToIme();
        v.invalidate();
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        if (eng() == null) return false;
        int len = eng().text.length();
        int a = Math.max(0, Math.min(len, Math.min(start, end))), b = Math.max(0, Math.min(len, Math.max(start, end)));
        if (a == b) {
            v.composingStart = v.composingEnd = -1;
        } else {
            v.composingStart = a;
            v.composingEnd = b;
        }
        v.reportSelectionToIme();
        v.invalidate();
        return true;
    }

    @Override
    public boolean finishComposingText() {
        if (v.composingStart >= 0) {
            v.composingStart = v.composingEnd = -1;
            v.reportSelectionToIme();
            v.invalidate();
        }
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int before, int after) {
        if (!writable()) return false;
        EditEngine e = eng();
        Selections sel = e.sel;
        // A plain backspace from the keyboard: use the smart version (auto-pairs, soft tabs, multi-cursor).
        if (before == 1 && after == 0 && v.composingStart < 0 && !sel.anyNonEmpty()) {
            e.backspace();
            return true;
        }
        if (before == 0 && after == 1 && v.composingStart < 0 && !sel.anyNonEmpty()) {
            e.deleteForward();
            return true;
        }
        TextBuffer t = e.text;
        int p = sel.primary();
        int ss = sel.start(p), se = sel.end(p);
        // As in BaseInputConnection: counted from the edges of the composing text, which stays.
        if (v.composingStart >= 0 && v.composingEnd >= v.composingStart) {
            ss = Math.min(ss, v.composingStart);
            se = Math.max(se, Math.min(v.composingEnd, t.length()));
        }
        int a0 = Math.max(0, ss - before), b1 = Math.min(t.length(), se + after);
        // Don't split surrogate pairs.
        if (a0 > 0 && Character.isLowSurrogate(t.charAt(a0)) && Character.isHighSurrogate(t.charAt(a0 - 1))) a0--;
        if (b1 < t.length() && Character.isLowSurrogate(t.charAt(b1)) && b1 > 0 && Character.isHighSurrogate(t.charAt(b1 - 1))) b1++;
        editing = true;
        e.begin(UndoStack.KIND_DELETE);
        if (b1 > se) e.replace(se, b1, "");
        if (a0 < ss) e.replace(a0, ss, "");
        e.end();
        editing = false;
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int before, int after) {
        if (eng() == null) return false;
        TextBuffer t = eng().text;
        Selections sel = eng().sel;
        int p = sel.primary();
        int ss = sel.start(p), se = sel.end(p);
        int b = 0, i = ss;
        while (b < before && i > 0) {
            i--;
            if (i > 0 && Character.isLowSurrogate(t.charAt(i)) && Character.isHighSurrogate(t.charAt(i - 1))) i--;
            b++;
        }
        int a = 0, j = se;
        while (a < after && j < t.length()) {
            if (Character.isHighSurrogate(t.charAt(j)) && j + 1 < t.length()) j++;
            j++;
            a++;
        }
        return deleteSurroundingText(ss - i, j - se);
    }

    @Override
    public CharSequence getTextBeforeCursor(int n, int flags) {
        if (eng() == null) return "";
        Selections sel = eng().sel;
        int s = sel.start(sel.primary());
        return eng().text.substring(Math.max(0, s - n), s);
    }

    @Override
    public CharSequence getTextAfterCursor(int n, int flags) {
        if (eng() == null) return "";
        Selections sel = eng().sel;
        int e = sel.end(sel.primary());
        int len = eng().text.length();
        return eng().text.substring(e, (int) Math.min(len, (long) e + n));
    }

    @Override
    public CharSequence getSelectedText(int flags) {
        if (eng() == null) return null;
        Selections sel = eng().sel;
        int p = sel.primary();
        if (sel.isEmpty(p)) return null;
        return eng().text.substring(sel.start(p), sel.end(p));
    }

    @Override
    public int getCursorCapsMode(int reqModes) {
        return 0;
    }

    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        if (eng() == null) return null;
        TextBuffer t = eng().text;
        Selections sel = eng().sel;
        int p = sel.primary();
        // A window around the caret is enough for keyboards that use it for cursor keys.
        int ss = sel.start(p), se = sel.end(p);
        int from = Math.max(0, ss - 2000), to = Math.min(t.length(), se + 2000);
        ExtractedText et = new ExtractedText();
        et.text = t.substring(from, to);
        et.startOffset = from;
        et.partialStartOffset = -1;
        et.partialEndOffset = -1;
        et.selectionStart = ss - from;
        et.selectionEnd = se - from;
        et.flags = 0;
        return et;
    }

    @Override
    public boolean setSelection(int start, int end) {
        if (eng() == null) return false;
        int len = eng().text.length();
        start = Math.max(0, Math.min(len, start));
        end = Math.max(0, Math.min(len, end));
        eng().setSelection(start, end);
        return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        v.dispatchKeyEvent(event);
        return true;
    }

    @Override
    public boolean performContextMenuAction(int id) {
        if (eng() == null) return false;
        if (id == android.R.id.selectAll) eng().selectAll();
        else if (id == android.R.id.copy) v.copy(false);
        else if (id == android.R.id.cut) v.copy(true);
        else if (id == android.R.id.paste) v.paste();
        else return false;
        return true;
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        // Multi-line editor: Enter arrives as "\n" or a key event; nothing to do here.
        return true;
    }

    @Override
    public boolean commitCompletion(CompletionInfo text) {
        return commitText(text.getText(), 1);
    }

    @Override
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        return true;
    }

    @Override
    public boolean clearMetaKeyStates(int states) {
        return true;
    }

    @Override
    public boolean reportFullscreenMode(boolean enabled) {
        return true;
    }
}
