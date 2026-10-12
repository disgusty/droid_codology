package com.disgusty.droid_codology.editor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import com.disgusty.droid_codology.lang.Highlighter;
import com.disgusty.droid_codology.lang.Tok;
import com.disgusty.droid_codology.text.EditEngine;
import com.disgusty.droid_codology.text.Selections;
import com.disgusty.droid_codology.text.TextBuffer;
import com.disgusty.droid_codology.work.Document;

import java.util.Arrays;

/**
 * The code editing surface. Draws only the visible rows straight from the text buffer (no
 * android.text layouts), so files of any size open instantly and scroll smoothly. Supports
 * multi-cursor, word wrap, touch selection handles, pinch zoom, hardware keyboards, mice and
 * input methods (see {@link CodeInput}).
 */
public final class CodeView extends View implements EditEngine.Listener, TextBuffer.Listener, RowLayout.Measure {

    /** Callbacks to the workbench. */
    public interface Host {
        /** Global key bindings (Ctrl+S...). Return true if consumed. */
        boolean onEditorKey(CodeView v, int keyCode, KeyEvent e);

        void onCaretMoved(CodeView v);

        void onTextChanged(CodeView v);

        /** Show the cut/copy/paste bar near (x, y) in view coordinates; hide when show is false. */
        void onSelectionMenu(CodeView v, boolean show, float x, float y);

        void onZoom(CodeView v, float sizeSp);

        /** Text from the on-screen keyboard; return true to consume it (sticky Ctrl/Alt keys). */
        boolean interceptTyped(CodeView v, String s);
    }

    Host host() {
        return host;
    }

    private Host host;
    private Document doc;
    private EditEngine eng;
    private TextBuffer text;
    private Highlighter hl;
    private RowLayout layout;
    private EditorColors colors = EditorColors.darkModern();

    // ---- settings
    private float fontSp = 14;
    private Typeface typeface = Typeface.MONOSPACE;
    public boolean lineNumbers = true, relativeNumbers, wordWrap, indentGuides = true, highlightLine = true,
            matchBrackets = true, renderWhitespace, smoothCaret = true, keyboardSuggestions, readOnlyView;
    public int[] rulers = new int[0];
    private float lineSpacing = 1.35f;

    // ---- metrics
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private float lineH, baseline, charW, density;
    private final float[] widths = new float[65536];
    private float gutterW, textLeft;
    private int tabSize = 4;
    private Typeface bold, italic, boldItalic;

    // ---- scrolling
    private int scrollX, scrollY;
    private final OverScroller scroller;
    private int maxLineChars = 1;
    private boolean draggingThumbV, draggingThumbH;
    private long lastScrollTime;

    // ---- caret
    private boolean caretOn = true;
    private long lastInput;
    private float[] prefX = new float[4];
    private boolean prefValid;

    // ---- touch selection
    private static final int H_NONE = 0, H_INSERT = 1, H_ANCHOR = 2, H_HEAD = 3;
    private boolean showHandles, showInsertHandle;
    private int dragHandle = H_NONE;
    private float handleR;
    private float dragDx, dragDy;
    private boolean selectingByDrag;
    private int dragAnchor;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scale;
    private boolean scaling;
    private float scaleStartSp;
    private float lastTouchX, lastTouchY;

    // ---- IME
    int composingStart = -1, composingEnd = -1;
    CodeInput input;
    private InputMethodManager imm;
    private int batchDepth;

    // ---- decorations
    private int[] findStarts = new int[0], findEnds = new int[0];
    private int findCurrent = -1;
    private int[] bracketPair;
    private int[] wordHits = new int[0];
    private int wordHitLen;
    private String lastWord;

    private char[] lineBuf = new char[512];
    private final RectF rect = new RectF();
    private final Path path = new Path();

    public CodeView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setHapticFeedbackEnabled(true);
        scroller = new OverScroller(ctx);
        imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        handleR = 11 * density;
        gestures = new GestureDetector(ctx, new Gestures());
        gestures.setIsLongpressEnabled(true);
        scale = new ScaleGestureDetector(ctx, new Scale());
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        setFontSize(fontSp);
        setWillNotDraw(false);
    }

    public void setHost(Host h) {
        host = h;
    }

    // ------------------------------------------------------------------ document

    public Document document() {
        return doc;
    }

    public EditEngine engine() {
        return eng;
    }

    public void setDocument(Document d) {
        if (doc != null) {
            doc.scrollX = scrollX;
            doc.scrollY = scrollY;
            if (folding != null) folding.setListener(null);
            eng.setListener(null);
            text.removeListener(this);
            layout.detach();
        }
        doc = d;
        composingStart = composingEnd = -1;
        findStarts = new int[0];
        findEnds = new int[0];
        findCurrent = -1;
        showHandles = showInsertHandle = false;
        if (d == null) {
            eng = null;
            text = null;
            hl = null;
            layout = null;
            invalidate();
            return;
        }
        eng = d.engine;
        text = d.text;
        hl = d.highlighter;
        tabSize = eng.tabSize;
        eng.setListener(this);
        text.addListener(this);
        layout = new RowLayout(text, this);
        folding = d.folding;
        folding.tabSize = tabSize;
        layout.setFolding(folding);
        folding.setListener(foldListener);
        recomputeMaxLine();
        scroller.forceFinished(true);
        updateGutter();
        configureWrap();
        layout.refold();
        scrollX = d.scrollX;
        scrollY = d.scrollY;
        clampScroll();
        prefValid = false;
        updateBracket();
        if (imm != null) imm.restartInput(this);
        invalidate();
    }

    public void setColors(EditorColors c) {
        colors = c;
        invalidate();
    }

    public EditorColors colors() {
        return colors;
    }

    public void setTypeface(Typeface tf) {
        typeface = tf == null ? Typeface.MONOSPACE : tf;
        setFontSize(fontSp);
    }

    public Typeface typeface() {
        return typeface;
    }

    public float fontSize() {
        return fontSp;
    }

    public void setFontSize(float sp) {
        fontSp = Math.max(6, Math.min(48, sp));
        float px = fontSp * getResources().getDisplayMetrics().scaledDensity;
        textPaint.setTypeface(typeface);
        textPaint.setTextSize(px);
        numPaint.setTypeface(typeface);
        numPaint.setTextSize(px);
        numPaint.setTextAlign(Paint.Align.RIGHT);
        bold = Typeface.create(typeface, Typeface.BOLD);
        italic = Typeface.create(typeface, Typeface.ITALIC);
        boldItalic = Typeface.create(typeface, Typeface.BOLD_ITALIC);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float h = fm.descent - fm.ascent;
        lineH = (float) Math.ceil(h * lineSpacing);
        baseline = (lineH - h) / 2 - fm.ascent;
        Arrays.fill(widths, -1f);
        charW = textPaint.measureText(" ");
        for (char c = 32; c < 127; c++) widths[c] = textPaint.measureText(String.valueOf(c));
        updateGutter();
        if (layout != null) {
            configureWrap();
            layout.invalidateMeasure();
        }
        clampScroll();
        invalidate();
    }

    public void setLineSpacing(float f) {
        lineSpacing = Math.max(1f, Math.min(2.5f, f));
        setFontSize(fontSp);
    }

    public void setTabSize(int n) {
        tabSize = Math.max(1, Math.min(16, n));
        if (eng != null) eng.tabSize = tabSize;
        if (folding != null) folding.tabSize = tabSize;
        if (layout != null) layout.invalidateMeasure();
        invalidate();
    }

    public void setWordWrap(boolean on) {
        // Very big files stay unwrapped: wrapping must measure every line.
        wordWrap = on;
        configureWrap();
        clampScroll();
        invalidate();
    }

    private void configureWrap() {
        if (layout == null) return;
        boolean w = wordWrap && text.length() < 8 * 1024 * 1024;
        float width = textRight() - textLeft - 8 * density;
        if (w && width > 0) {
            int keepLine = layout.lineOfRow((int) (scrollY / lineH));
            layout.configure(true, width);
            scrollX = 0;
            scrollY = (int) (layout.rowOfLine(keepLine) * lineH);
        } else if (!w) {
            int keepLine = layout.lineOfRow((int) (scrollY / lineH));
            layout.configure(false, 0);
            scrollY = (int) (keepLine * lineH);
        }
    }

    // ------------------------------------------------------------------ metrics

    @Override
    public float advance(char c, float x) {
        if (c == '\t') {
            float tw = charW * tabSize;
            return tw - (x % tw);
        }
        float w = widths[c];
        if (w < 0) {
            w = textPaint.measureText(String.valueOf(c));
            // Surrogates are measured as pairs at draw time; give halves a full cell.
            if (c >= '\uD800' && c <= '\uDFFF') w = charW;
            widths[c] = w;
        }
        return w;
    }

    private void updateGutter() {
        foldColW = foldingEnabled ? 18 * density : 0;
        if (!lineNumbers) {
            gutterW = 6 * density + foldColW;
        } else {
            int lines = text == null ? 1 : text.lineCount();
            int digits = Math.max(3, String.valueOf(lines).length());
            gutterW = numPaint.measureText("0") * digits + (foldingEnabled ? 14 : 22) * density + foldColW;
        }
        textLeft = gutterW + 4 * density;
    }

    // ------------------------------------------------------------------ folding & sticky scroll

    public boolean foldingEnabled = true, stickyScroll = true;
    private com.disgusty.droid_codology.text.Folding folding;
    private float foldColW;
    private int stickyCount;
    private final int[] stickyLines = new int[5];

    private final com.disgusty.droid_codology.text.Folding.Listener foldListener = new com.disgusty.droid_codology.text.Folding.Listener() {
        @Override
        public void onFoldsChanged() {
            if (layout == null) return;
            layout.refold();
            clampScroll();
            invalidate();
        }
    };

    private final Runnable recomputeFolds = new Runnable() {
        @Override
        public void run() {
            if (folding == null) return;
            folding.ensureRanges();
            invalidate();
        }
    };

    public com.disgusty.droid_codology.text.Folding folding() {
        return folding;
    }

    /** Lines of the ranges enclosing the first visible line, outermost first (for sticky scroll). */
    private void computeSticky(int firstLine) {
        stickyCount = 0;
        if (!stickyScroll || folding == null || !folding.hasRanges()) return;
        int n = folding.rangeCountStale();
        int lc = text.lineCount();
        for (int i = 0; i < n && stickyCount < stickyLines.length; i++) {
            int s = folding.rangeStart(i), e = folding.rangeEnd(i);
            if (s >= firstLine + stickyCount) break;
            if (s < lc && e >= firstLine + stickyCount && e < lc && s < firstLine + stickyCount) {
                stickyLines[stickyCount++] = s;
            }
        }
    }

    private void recomputeMaxLine() {
        int max = 1, n = text.lineCount();
        for (int l = 0; l < n; l++) {
            int len = text.lineStart(l + 1 < n ? l + 1 : l);
            int ll = (l + 1 < n ? len - 1 : text.length()) - text.lineStart(l);
            if (ll > max) max = ll;
        }
        maxLineChars = max;
    }

    public float lineHeight() {
        return lineH;
    }

    // ------------------------------------------------------------------ text listener

    @Override
    public void onReplaced(int start, int removedLen, int insertedLen, int firstLine, int removedLines, int insertedLines) {
        // Keep the composing region and find highlights roughly in place.
        if (composingStart >= 0) {
            int end = start + removedLen;
            if (end <= composingStart) {
                int d = insertedLen - removedLen;
                composingStart += d;
                composingEnd += d;
            } else if (start >= composingEnd) {
                // after: unchanged
            } else if (input == null || !input.editing) {
                composingStart = composingEnd = -1;
            }
        }
        for (int l = firstLine; l <= firstLine + insertedLines && l < text.lineCount(); l++) {
            int len = text.lineLength(l);
            if (len > maxLineChars) maxLineChars = len;
        }
        if (removedLines != insertedLines) {
            float oldGutter = gutterW;
            updateGutter();
            if (oldGutter != gutterW && wordWrap) configureWrap();
        }
        if (findStarts.length > 0) {
            findStarts = new int[0];
            findEnds = new int[0];
            findCurrent = -1;
        }
    }

    @Override
    public void onSelectionChanged(boolean textChanged) {
        if (!(input != null && input.editing)) {
            if (textChanged && composingStart >= 0 && batchDepth == 0) composingStart = composingEnd = -1;
        }
        caretOn = true;
        lastInput = SystemClock.uptimeMillis();
        if (textChanged) {
            showInsertHandle = false;
            if (host != null) host.onTextChanged(this);
        }
        if (!eng.sel.anyNonEmpty()) showHandles = false;
        // Leaving the snippet's tab stops ends the snippet session.
        if (eng.snippet.isActive() && !eng.snippet.isSelecting() && !eng.snippet.contains(eng.sel.head(eng.sel.primary()))) eng.snippet.end();
        // The caret never sits in a folded region: open it (as VS Code does on navigation/search).
        if (folding != null && folding.hasCollapsed()) {
            int hl2 = text.lineOfOffset(eng.sel.head(eng.sel.primary()));
            if (folding.isHidden(hl2)) folding.reveal(hl2);
        }
        updateBracket();
        updateWordHighlight();
        if (batchDepth == 0) {
            reportSelectionToIme();
            ensureCaretVisible();
        }
        if (host != null) host.onCaretMoved(this);
        if (showHandles && eng.sel.anyNonEmpty() && host != null && dragHandle == H_NONE) showSelectionMenu();
        invalidate();
    }

    void beginBatch() {
        batchDepth++;
    }

    void endBatch() {
        if (--batchDepth == 0) {
            reportSelectionToIme();
            ensureCaretVisible();
            invalidate();
        }
    }

    void reportSelectionToIme() {
        if (imm == null || eng == null) return;
        Selections s = eng.sel;
        int p = s.primary();
        imm.updateSelection(this, s.start(p), s.end(p), composingStart, composingEnd);
    }

    private void updateBracket() {
        bracketPair = null;
        if (!matchBrackets || eng == null || eng.sel.count() != 1 || !eng.sel.isEmpty(0)) return;
        bracketPair = eng.matchBracket(eng.sel.caret(), 200000);
    }

    /** Highlights other occurrences of the word under the caret (VS Code "word highlight"). */
    private void updateWordHighlight() {
        wordHits = new int[0];
        lastWord = null;
        if (eng == null || eng.sel.count() != 1) return;
        int s, e;
        if (eng.sel.isEmpty(0)) {
            int[] w = eng.wordAt(eng.sel.caret());
            s = w[0];
            e = w[1];
        } else {
            s = eng.sel.start(0);
            e = eng.sel.end(0);
            if (e - s > 100 || text.lineOfOffset(s) != text.lineOfOffset(e)) return;
            for (int i = s; i < e; i++) if (!EditEngine.isWordChar(text.charAt(i))) return;
        }
        if (e <= s) return;
        lastWord = text.substring(s, e);
        wordHitLen = e - s;
        computeWordHits();
    }

    private void computeWordHits() {
        if (lastWord == null) return;
        int firstRow = (int) (scrollY / lineH), lastRow = firstRow + (int) (getHeight() / lineH) + 2;
        int l0 = layout.lineOfRow(firstRow), l1 = layout.lineOfRow(lastRow);
        int from = text.lineStart(l0), to = text.lineEnd(l1);
        int[] hits = new int[16];
        int n = 0;
        String w = lastWord;
        char f = w.charAt(0);
        for (int i = from; i <= to - w.length(); i++) {
            if (text.charAt(i) != f) continue;
            if (i > 0 && EditEngine.isWordChar(text.charAt(i - 1))) continue;
            int k = 1;
            while (k < w.length() && text.charAt(i + k) == w.charAt(k)) k++;
            if (k < w.length()) continue;
            if (i + k < text.length() && EditEngine.isWordChar(text.charAt(i + k))) continue;
            if (n == hits.length) hits = Arrays.copyOf(hits, n * 2);
            hits[n++] = i;
        }
        wordHits = n > 1 ? Arrays.copyOf(hits, n) : new int[0];
    }

    // ------------------------------------------------------------------ find decorations

    public void setFindMatches(int[] starts, int[] ends, int current) {
        findStarts = starts;
        findEnds = ends;
        findCurrent = current;
        invalidate();
    }

    // ------------------------------------------------------------------ geometry

    private int rowOfOffset(int off) {
        int line = text.lineOfOffset(off);
        int row = layout.rowOfLine(line);
        if (layout.wraps()) {
            int[] b = layout.breaks(line);
            for (int i = 0; i < b.length && b[i] <= off; i++) {
                if (b[i] == off && off == text.lineEnd(line)) break;
                row++;
            }
        }
        return row;
    }

    /** [start, end) offsets of a visual row. */
    private int rowStart(int row, int line) {
        int sub = row - layout.rowOfLine(line);
        if (sub == 0) return text.lineStart(line);
        int[] b = layout.breaks(line);
        return b[Math.min(sub, b.length) - 1];
    }

    private int rowEnd(int row, int line) {
        int sub = row - layout.rowOfLine(line);
        int[] b = layout.breaks(line);
        if (sub < b.length) return b[sub];
        return text.lineEnd(line);
    }

    private float rowIndent(int row, int line) {
        return row == layout.rowOfLine(line) ? 0 : layout.continuationIndent(line);
    }

    /** X of {@code off} inside its row, relative to the text origin (no scroll). */
    private float xOf(int off) {
        int row = rowOfOffset(off);
        int line = text.lineOfOffset(off);
        int rs = rowStart(row, line);
        float x = rowIndent(row, line);
        for (int i = rs; i < off; i++) x += advance(text.charAt(i), x);
        return x;
    }

    /** Offset in visual row {@code row} closest to x (text origin coordinates). */
    private int offsetInRow(int row, float x) {
        int rows = layout.rowCount();
        if (row < 0) return 0;
        if (row >= rows) return text.length();
        int line = layout.lineOfRow(row);
        int rs = rowStart(row, line), re = rowEnd(row, line);
        boolean lastRowOfLine = row - layout.rowOfLine(line) == layout.rowsOfLine(line) - 1;
        float cx = rowIndent(row, line);
        for (int i = rs; i < re; i++) {
            char c = text.charAt(i);
            float w = advance(c, cx);
            // A surrogate pair is one character: the caret never lands between its halves.
            boolean pair = Character.isHighSurrogate(c) && i + 1 < re && Character.isLowSurrogate(text.charAt(i + 1));
            if (pair) w += advance(text.charAt(i + 1), cx + w);
            if (x < cx + w / 2) return i;
            cx += w;
            if (pair) i++;
        }
        // On a wrapped row the end offset belongs to the next row: stay before it.
        if (!lastRowOfLine && re > rs) return re - 1 >= rs ? re : rs;
        return re;
    }

    /** Offset under the view point (x, y). */
    public int offsetAt(float vx, float vy) {
        int row = (int) Math.floor((vy + scrollY) / lineH);
        return offsetInRow(row, vx + scrollX - textLeft);
    }

    /** View coordinates of the caret at {@code off}: {x, top, bottom}. */
    public float[] caretPos(int off) {
        int row = rowOfOffset(off);
        float x = xOf(off) + textLeft - scrollX;
        float top = row * lineH - scrollY;
        return new float[]{x, top, top + lineH};
    }

    // ------------------------------------------------------------------ scrolling

    private int maxScrollY() {
        return (int) Math.max(0, layout.rowCount() * lineH - Math.max(lineH, getHeight() / 3f));
    }

    private int maxScrollX() {
        if (layout.wraps()) return 0;
        float content = maxLineChars * charW * 1.05f + textLeft + 48 * density;
        return (int) Math.max(0, content - textRight());
    }

    private void clampScroll() {
        if (layout == null) return;
        scrollY = Math.max(0, Math.min(scrollY, maxScrollY()));
        scrollX = Math.max(0, Math.min(scrollX, maxScrollX()));
    }

    public void scrollToRow(int row) {
        scrollY = (int) (row * lineH);
        clampScroll();
        invalidate();
    }

    public int firstVisibleLine() {
        return layout == null ? 0 : layout.lineOfRow((int) (scrollY / lineH));
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.getCurrX();
            scrollY = scroller.getCurrY();
            clampScroll();
            postInvalidateOnAnimation();
        }
    }

    public void ensureCaretVisible() {
        if (eng == null || getHeight() == 0) return;
        int off = eng.sel.head(eng.sel.primary());
        int row = rowOfOffset(off);
        float top = row * lineH, bottom = top + lineH;
        float margin = lineH * 1.5f;
        int h = getHeight();
        if (top - margin < scrollY) scrollY = (int) Math.max(0, top - margin);
        else if (bottom + margin > scrollY + h) scrollY = (int) (bottom + margin - h);
        if (!layout.wraps()) {
            float x = xOf(off) + textLeft;
            int tw = textRight();
            float visL = scrollX + textLeft + 2 * charW, visR = scrollX + tw - 3 * charW;
            if (x < visL) scrollX = (int) Math.max(0, x - textLeft - tw / 3f);
            else if (x > visR) scrollX = (int) (x - tw + tw / 3f);
        }
        scroller.forceFinished(true);
        clampScroll();
    }

    /** Scrolls so the line is near the top third (go-to-line, search results). */
    public void revealLine(int line) {
        int row = layout.rowOfLine(Math.min(line, text.lineCount() - 1));
        scrollY = (int) Math.max(0, row * lineH - getHeight() / 3f);
        clampScroll();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        updateMinimapWidth();
        if (layout != null) {
            configureWrap();
            clampScroll();
            if (h < oh) ensureCaretVisible(); // keyboard opened: keep the caret in view
        }
    }

    // ------------------------------------------------------------------ drawing

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            if (!hasFocus() || eng == null) return;
            long since = SystemClock.uptimeMillis() - lastInput;
            caretOn = since < 500 || !caretOn;
            invalidateCaret();
            postDelayed(this, 500);
        }
    };

    private final Runnable continueHighlight = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    private void invalidateCaret() {
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(focused, direction, prev);
        removeCallbacks(blink);
        if (focused) {
            caretOn = true;
            postDelayed(blink, 500);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        EditorColors k = colors;
        c.drawColor(k.background);
        if (eng == null) return;
        int w = getWidth(), h = getHeight();
        int rows = layout.rowCount();
        int firstRow = Math.max(0, (int) (scrollY / lineH));
        int lastRow = Math.min(rows - 1, (int) ((scrollY + h) / lineH));
        int firstLine = layout.lineOfRow(firstRow), lastLine = layout.lineOfRow(lastRow);
        // Lex up to the visible end within a frame budget; finish later if needed.
        if (!hl.ensure(lastLine + 1, 8000000L)) postDelayed(continueHighlight, 16);
        if (lastWord != null && (wordHits.length == 0 || SystemClock.uptimeMillis() - lastScrollTime < 50)) computeWordHits();
        if (folding != null && (foldingEnabled || stickyScroll) && folding.isStale()) {
            // Fold ranges are recomputed shortly after typing pauses (O(lines)), not on every key.
            if (!folding.hasRanges() && text.lineCount() < 20000) folding.ensureRanges();
            else {
                removeCallbacks(recomputeFolds);
                postDelayed(recomputeFolds, 400);
            }
        }

        Selections sel = eng.sel;
        int primaryLine = text.lineOfOffset(sel.head(sel.primary()));
        boolean focused = hasFocus();

        c.save();
        c.clipRect(gutterW, 0, textRight(), h);
        float ox = textLeft - scrollX;

        // Rulers.
        for (int r : rulers) {
            fill.setColor(k.rulers);
            float x = ox + r * charW;
            c.drawRect(x, 0, x + Math.max(1, density), h, fill);
        }

        for (int row = firstRow; row <= lastRow; row++) {
            int line = layout.lineOfRow(row);
            int rs = rowStart(row, line), re = rowEnd(row, line);
            float top = row * lineH - scrollY, bottom = top + lineH;
            float indentX = rowIndent(row, line);

            // Current line highlight.
            if (highlightLine && line == primaryLine && !sel.anyNonEmpty()) {
                if ((k.lineHighlight >>> 24) != 0) {
                    fill.setColor(k.lineHighlight);
                    c.drawRect(gutterW, top, w, bottom, fill);
                }
                if ((k.lineHighlightBorder >>> 24) != 0) {
                    stroke.setColor(k.lineHighlightBorder);
                    stroke.setStrokeWidth(Math.max(1, density * 1.5f));
                    float sw = stroke.getStrokeWidth() / 2;
                    c.drawRect(gutterW + sw, top + sw, w - sw, bottom - sw, stroke);
                }
            }

            // Selections.
            int selColor = focused ? k.selection : k.inactiveSelection;
            for (int i = 0; i < sel.count(); i++) {
                int ss = sel.start(i), se = sel.end(i);
                if (ss == se || se < rs || ss > re) continue;
                if (ss == re && re < text.length() && rs != re) continue;
                float x0 = ss <= rs ? indentX : xIn(rs, ss, indentX);
                float x1 = se >= re ? xIn(rs, re, indentX) + (se > re && re == text.lineEnd(line) ? charW * 0.6f : 0) : xIn(rs, se, indentX);
                fill.setColor(selColor);
                c.drawRect(ox + x0, top, ox + Math.max(x1, x0 + 2), bottom, fill);
            }

            // Find matches.
            if (findStarts.length > 0) drawRanges(c, findStarts, findEnds, rs, re, top, bottom, ox, indentX, k.findMatch, findCurrent, k.findMatchCurrent);
            // Word highlights.
            if (wordHits.length > 0) {
                fill.setColor(k.wordHighlight);
                for (int hp : wordHits) {
                    if (hp + wordHitLen < rs || hp > re) continue;
                    float x0 = xIn(rs, Math.max(rs, hp), indentX), x1 = xIn(rs, Math.min(re, hp + wordHitLen), indentX);
                    c.drawRect(ox + x0, top, ox + x1, bottom, fill);
                }
            }
            // Matching brackets.
            if (bracketPair != null) {
                for (int b : bracketPair) {
                    if (b < rs || b >= re) continue;
                    float x0 = xIn(rs, b, indentX), x1 = x0 + advance(text.charAt(b), x0);
                    fill.setColor(k.bracketMatch);
                    c.drawRect(ox + x0, top, ox + x1, bottom, fill);
                    stroke.setColor(k.bracketMatchBorder);
                    stroke.setStrokeWidth(Math.max(1, density));
                    c.drawRect(ox + x0 + 0.5f, top + 0.5f, ox + x1 - 0.5f, bottom - 0.5f, stroke);
                }
            }

            // Indent guides (first row of each line only).
            if (indentGuides && rs == text.lineStart(line)) drawIndentGuides(c, line, top, bottom, ox);

            // Text.
            drawRowText(c, line, rs, re, top, ox, indentX, textRight());

            // Folded region placeholder "⋯" after the header line.
            if (folding != null && re == text.lineEnd(line) && folding.isCollapsed(line)) {
                float x0 = ox + xIn(rs, re, indentX) + charW * 0.6f, bw = charW * 2.6f;
                fill.setColor(k.foldBg);
                rect.set(x0, top + lineH * 0.18f, x0 + bw, bottom - lineH * 0.18f);
                c.drawRoundRect(rect, 3 * density, 3 * density, fill);
                textPaint.setColor(k.lineNumberActive);
                c.drawText("⋯", x0 + bw / 2 - textPaint.measureText("⋯") / 2, top + baseline, textPaint);
            }

            // Diagnostics: wavy underlines.
            if (diagnostics != null && !diagnostics.isEmpty()) drawDiagnostics(c, line, rs, re, bottom, ox, indentX);

            // IME composing underline.
            if (composingStart >= 0 && composingEnd > composingStart && composingEnd >= rs && composingStart <= re) {
                float x0 = xIn(rs, Math.max(rs, composingStart), indentX), x1 = xIn(rs, Math.min(re, composingEnd), indentX);
                fill.setColor(k.composingUnderline);
                c.drawRect(ox + x0, bottom - 2 * density, ox + x1, bottom - density, fill);
            }
        }

        // Carets.
        if (focused && caretOn && !readOnlyView) {
            fill.setColor(k.cursor);
            float cw = Math.max(2, 2 * density);
            for (int i = 0; i < sel.count(); i++) {
                int hpos = sel.head(i);
                int row = rowOfOffset(hpos);
                if (row < firstRow || row > lastRow) continue;
                float x = ox + xOf(hpos);
                float top = row * lineH - scrollY;
                c.drawRect(x - cw / 2, top, x + cw / 2, top + lineH, fill);
            }
        }
        c.restore();

        drawGutter(c, firstRow, lastRow, primaryLine);
        drawSticky(c, firstRow, firstLine, primaryLine);
        if (minimapW > 0) drawMinimap(c, rows);
        drawHandles(c);
        if (minimapW == 0) drawScrollbars(c, rows);
    }

    /** VS Code's sticky scroll: headers of the scopes the first visible line is in, pinned on top. */
    private void drawSticky(Canvas c, int firstRow, int firstLine, int primaryLine) {
        computeSticky(firstLine);
        // Don't pin a header that is itself fully visible at the top.
        while (stickyCount > 0 && layout.rowOfLine(stickyLines[stickyCount - 1]) * lineH >= scrollY) stickyCount--;
        if (stickyCount == 0) return;
        EditorColors k = colors;
        int w = getWidth();
        float ox = textLeft - scrollX;
        for (int i = 0; i < stickyCount; i++) {
            int line = stickyLines[i];
            float top = i * lineH;
            fill.setColor(k.background);
            c.drawRect(0, top, w, top + lineH, fill);
            c.save();
            c.clipRect(gutterW, top, w, top + lineH);
            int rs = text.lineStart(line);
            int re = layout.wraps() && layout.breaks(line).length > 0 ? layout.breaks(line)[0] : text.lineEnd(line);
            drawRowText(c, line, rs, re, top, ox, 0, w);
            c.restore();
            fill.setColor(k.gutterBg);
            c.drawRect(0, top, gutterW, top + lineH, fill);
            if (lineNumbers) {
                numPaint.setColor(line == primaryLine ? k.lineNumberActive : k.lineNumber);
                c.drawText(Integer.toString(line + 1), gutterW - foldColW - 6 * density, top + baseline, numPaint);
            }
        }
        // Shadow under the pinned lines.
        float y = stickyCount * lineH;
        fill.setColor(k.dark ? 0x80000000 : 0x26000000);
        c.drawRect(0, y, w, y + Math.max(1, density), fill);
        fill.setColor(k.dark ? 0x30000000 : 0x10000000);
        c.drawRect(0, y + density, w, y + 3 * density, fill);
    }

    // ------------------------------------------------------------------ minimap

    private int minimapW;
    private boolean minimapOn, draggingMinimap;
    private android.graphics.Bitmap mmBitmap;
    private int mmTopDrawn = -1, mmVersionDrawn = -1, mmColorsDrawn, mmRowsDrawn = -1;
    private boolean mmIncomplete;

    public void setMinimap(boolean on) {
        minimapOn = on;
        updateMinimapWidth();
        configureWrap();
        clampScroll();
        invalidate();
    }

    private void updateMinimapWidth() {
        int w = getWidth();
        minimapW = minimapOn && w > 0 ? (int) Math.min(80 * density, w * 0.16f) : 0;
    }

    /** Right edge of the text area (left of the minimap). */
    private int textRight() {
        return getWidth() - minimapW;
    }

    private float mmLineH() {
        return Math.max(2, Math.round(2 * density));
    }

    /** First row shown in the minimap: 1:1 when everything fits, else proportional to the scroll. */
    private int minimapTop(int rows) {
        int fit = (int) (getHeight() / mmLineH());
        if (rows <= fit) return 0;
        float frac = maxScrollY() == 0 ? 0 : scrollY / (float) maxScrollY();
        return (int) (frac * (rows - fit));
    }

    private void drawMinimap(Canvas c, int rows) {
        int w = getWidth(), h = getHeight();
        int left = w - minimapW;
        int top = minimapTop(rows);
        int version = text.version();
        int lastMm = layout.lineOfRow(Math.min(rows - 1, top + (int) (h / mmLineH()) + 1));
        if (!hl.ensure(lastMm, 4000000L)) postDelayed(continueHighlight, 32);
        if (mmBitmap == null || mmBitmap.getWidth() != minimapW || mmBitmap.getHeight() != h) {
            if (mmBitmap != null) mmBitmap.recycle();
            mmBitmap = android.graphics.Bitmap.createBitmap(Math.max(1, minimapW), Math.max(1, h), android.graphics.Bitmap.Config.ARGB_8888);
            mmTopDrawn = -1;
        }
        if (top != mmTopDrawn || version != mmVersionDrawn || colors.background != mmColorsDrawn || rows != mmRowsDrawn || mmIncomplete) {
            renderMinimap(new Canvas(mmBitmap), top, rows);
            mmTopDrawn = top;
            mmVersionDrawn = version;
            mmColorsDrawn = colors.background;
            mmRowsDrawn = rows;
        }
        c.drawBitmap(mmBitmap, left, 0, null);
        // Viewport slider.
        float lh = mmLineH();
        float sTop = (scrollY / lineH - top) * lh, sH = Math.max(lh * 2, (h / lineH) * lh);
        fill.setColor(draggingMinimap ? colors.scrollThumbActive : colors.minimapSlider);
        c.drawRect(left, sTop, w, sTop + sH, fill);
        // Left edge shadow like VS Code.
        fill.setColor(colors.dark ? 0x40000000 : 0x14000000);
        c.drawRect(left - Math.max(1, density), 0, left, h, fill);
    }

    private void renderMinimap(Canvas mc, int top, int rows) {
        mc.drawColor(colors.background);
        float lh = mmLineH(), cw = Math.max(1f, density * 0.75f);
        int visible = (int) (getHeight() / lh) + 1;
        mmIncomplete = false;
        Paint p = fill;
        int lastLine = -1;
        for (int i = 0; i < visible && top + i < rows; i++) {
            int row = top + i;
            int line = layout.lineOfRow(row);
            if (line == lastLine && !layout.wraps()) continue;
            lastLine = line;
            int rs = rowStart(row, line), re = rowEnd(row, line);
            if (re - rs > 300) re = rs + 300;
            int ls = text.lineStart(line);
            int[] tok = line <= hl.validLines() ? hl.tokens(line) : null;
            if (tok == null) mmIncomplete = line < text.lineCount() && i < visible;
            float y = i * lh;
            int ti = 0, nt = tok == null ? 0 : tok.length / 2;
            float x = 0;
            int col = 0;
            for (int o = rs; o < re; o++) {
                char ch = text.charAt(o);
                if (ch == '\t') {
                    col += tabSize - col % tabSize;
                    continue;
                }
                if (ch == ' ') {
                    col++;
                    continue;
                }
                while (ti + 1 < nt && tok[(ti + 1) * 2] + ls <= o) ti++;
                int style = nt == 0 ? com.disgusty.droid_codology.lang.Tok.PLAIN : tok[ti * 2 + 1];
                x = col * cw;
                if (x > minimapW) break;
                p.setColor((colors.tokens[style] & 0x00FFFFFF) | 0xB0000000);
                // Merge a run of same-styled non-space chars into one rect.
                int runStart = col;
                int o2 = o;
                while (o2 + 1 < re) {
                    char n2 = text.charAt(o2 + 1);
                    if (n2 == ' ' || n2 == '\t') break;
                    int ti2 = ti;
                    while (ti2 + 1 < nt && tok[(ti2 + 1) * 2] + ls <= o2 + 1) ti2++;
                    if (nt != 0 && tok[ti2 * 2 + 1] != style) break;
                    o2++;
                    col++;
                }
                col++;
                mc.drawRect(runStart * cw, y, col * cw, y + lh - Math.max(0.5f, lh / 4), p);
                o = o2;
            }
        }
    }

    private void dragMinimapTo(float y) {
        int rows = layout.rowCount();
        float lh = mmLineH();
        int fit = (int) (getHeight() / lh);
        float target;
        if (rows <= fit) target = (y / lh) * lineH - getHeight() / 2f;
        else target = (y / getHeight()) * maxScrollY();
        scrollY = (int) target;
        lastScrollTime = SystemClock.uptimeMillis();
        clampScroll();
        invalidate();
    }

    /** Height of the sticky header area (taps there jump to the scope start). */
    private int stickyHit(float y) {
        if (stickyCount == 0 || y >= stickyCount * lineH) return -1;
        return stickyLines[(int) (y / lineH)];
    }

    private java.util.List<Diagnostic> diagnostics;
    private final android.graphics.Path squiggle = new android.graphics.Path();

    /** Problems of the shown document (drawn as squiggles); null or empty for none. */
    public void setDiagnostics(java.util.List<Diagnostic> d) {
        diagnostics = d;
        invalidate();
    }

    private void drawDiagnostics(Canvas c, int line, int rs, int re, float bottom, float ox, float indentX) {
        int lineStart = text.lineStart(line), lineEnd = text.lineEnd(line);
        int lines = text.lineCount();
        for (Diagnostic d : diagnostics) {
            if (d.line > line || d.endLine < line || d.line >= lines) continue;
            int s = d.line == line ? Math.min(lineEnd, lineStart + d.col) : lineStart;
            int e = d.endLine == line ? Math.min(lineEnd, lineStart + d.endCol) : lineEnd;
            if (e <= s) {
                // Zero-width ranges (e.g. "missing ;") still get a short mark.
                if (s < lineEnd) e = s + 1;
                else if (s > lineStart) s = e - 1;
                else continue;
            }
            s = Math.max(s, rs);
            e = Math.min(e, re);
            if (e <= s) continue;
            float x0 = ox + xIn(rs, s, indentX), x1 = ox + xIn(rs, e, indentX);
            float amp = 1.6f * density, step = 2.5f * density, y = bottom - 2.2f * density;
            squiggle.reset();
            squiggle.moveTo(x0, y);
            boolean up = true;
            for (float x = x0 + step; x < x1 + step; x += step) {
                squiggle.lineTo(Math.min(x, x1), up ? y - amp : y);
                up = !up;
            }
            stroke.setColor(d.color());
            stroke.setStrokeWidth(Math.max(1, density));
            c.drawPath(squiggle, stroke);
        }
    }

    /** The diagnostic under {@code offset}, or null. */
    public Diagnostic diagnosticAt(int offset) {
        if (diagnostics == null || text == null) return null;
        int line = text.lineOfOffset(offset), col = offset - text.lineStart(line);
        Diagnostic best = null;
        for (Diagnostic d : diagnostics) {
            boolean in = (line > d.line || line == d.line && col >= d.col) && (line < d.endLine || line == d.endLine && col <= d.endCol);
            if (in && (best == null || d.severity < best.severity)) best = d;
        }
        return best;
    }

    /** X (text origin) of {@code off} inside a row that starts at {@code rs}. */
    private float xIn(int rs, int off, float indentX) {
        float x = indentX;
        for (int i = rs; i < off; i++) x += advance(text.charAt(i), x);
        return x;
    }

    private void drawRanges(Canvas c, int[] starts, int[] ends, int rs, int re, float top, float bottom, float ox, float indentX,
                            int color, int current, int currentColor) {
        // Binary search for the first range ending after the row start.
        int lo = 0, hi = starts.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ends[mid] < rs) lo = mid + 1;
            else hi = mid;
        }
        for (int i = lo; i < starts.length && starts[i] <= re; i++) {
            int s = Math.max(rs, starts[i]), e = Math.min(re, ends[i]);
            if (e < s) continue;
            float x0 = xIn(rs, s, indentX), x1 = xIn(rs, e, indentX);
            if (x1 - x0 < 2) x1 = x0 + 2;
            fill.setColor(i == current ? currentColor : color);
            c.drawRect(ox + x0, top, ox + x1, bottom, fill);
        }
    }

    private void drawIndentGuides(Canvas c, int line, float top, float bottom, float ox) {
        int level = indentColumns(line);
        if (level < 0) {
            // Blank line: continue the guides of the next non-blank line.
            int n = text.lineCount();
            for (int l = line + 1; l < n && l < line + 200; l++) {
                level = indentColumns(l);
                if (level >= 0) break;
            }
            if (level < 0) return;
        }
        fill.setColor(colors.indentGuide);
        float gw = Math.max(1, density);
        // One guide per indent level, at columns 0, tab, 2*tab... inside the indentation.
        for (int col = 0; col < level; col += tabSize) {
            float x = ox + col * charW;
            c.drawRect(x, top, x + gw, bottom, fill);
        }
    }

    /** Indentation width in columns, or -1 for a blank line. */
    private int indentColumns(int line) {
        int s = text.lineStart(line), e = text.lineEnd(line), col = 0;
        for (int i = s; i < e; i++) {
            char ch = text.charAt(i);
            if (ch == ' ') col++;
            else if (ch == '\t') col += tabSize - col % tabSize;
            else return col;
        }
        return -1;
    }

    private void drawRowText(Canvas c, int line, int rs, int re, float top, float ox, float indentX, int viewW) {
        int len = re - rs;
        if (len <= 0) return;
        if (lineBuf.length < len) lineBuf = new char[Math.max(len, lineBuf.length * 2)];
        text.getChars(rs, re, lineBuf, 0);
        int ls = text.lineStart(line);
        int[] tok = hl.tokens(line);
        float y = top + baseline;
        float x = indentX;
        float leftLimit = scrollX - textLeft - 64 * density, rightLimit = scrollX + viewW;
        int ti = 0;
        int nt = tok == null ? 0 : tok.length / 2;
        // Find the token covering rs.
        while (ti + 1 < nt && tok[(ti + 1) * 2] + ls <= rs) ti++;
        int i = 0;
        EditorColors k = colors;
        while (i < len) {
            int style = tok == null ? Tok.PLAIN : tok[ti * 2 + 1];
            int tokEnd = (ti + 1 < nt) ? tok[(ti + 1) * 2] + ls - rs : len;
            if (tokEnd > len) tokEnd = len;
            if (tokEnd <= i) {
                ti++;
                continue;
            }
            // Skip whole runs left of the viewport; split at tabs and stop past the right edge.
            int runStart = i;
            float runX = x;
            for (; i < tokEnd; i++) {
                char ch = lineBuf[i];
                if (ch == '\t') {
                    drawRun(c, runStart, i, runX, y, style, ox, leftLimit, x);
                    if (renderWhitespace) drawTabMark(c, ox + x, top, advance('\t', x));
                    x += advance('\t', x);
                    runStart = i + 1;
                    runX = x;
                    continue;
                }
                if (ch == ' ' && renderWhitespace) {
                    fill.setColor(k.whitespace);
                    float r = Math.max(1, density);
                    c.drawCircle(ox + x + charW / 2, top + lineH / 2, r, fill);
                }
                x += advance(ch, x);
                if (x > rightLimit) {
                    i++;
                    break;
                }
            }
            drawRun(c, runStart, i, runX, y, style, ox, leftLimit, x);
            if (x > rightLimit) return;
            ti++;
        }
    }

    private void drawRun(Canvas c, int from, int to, float x, float y, int style, float ox, float leftLimit, float endX) {
        if (to <= from || endX < leftLimit) return;
        EditorColors k = colors;
        textPaint.setColor(k.tokens[style]);
        int fs = k.fontStyle[style];
        textPaint.setTypeface(fs == 0 ? typeface : fs == 1 ? bold : fs == 2 ? italic : boldItalic);
        // Control chars would render as boxes of unknown width; draw a placeholder instead.
        c.drawText(lineBuf, from, to - from, ox + x, y, textPaint);
        if (fs != 0) textPaint.setTypeface(typeface);
    }

    private void drawTabMark(Canvas c, float x, float top, float w) {
        stroke.setColor(colors.whitespace);
        stroke.setStrokeWidth(Math.max(1, density));
        float y = top + lineH / 2;
        c.drawLine(x + 2 * density, y, x + w - 2 * density, y, stroke);
    }

    private void drawGutter(Canvas c, int firstRow, int lastRow, int primaryLine) {
        EditorColors k = colors;
        fill.setColor(k.gutterBg);
        c.drawRect(0, 0, gutterW, getHeight(), fill);
        float right = gutterW - (foldingEnabled ? foldColW + 4 * density : 12 * density);
        for (int row = firstRow; row <= lastRow; row++) {
            int line = layout.lineOfRow(row);
            if (layout.rowOfLine(line) != row) continue;
            float top = row * lineH - scrollY;
            boolean active = line == primaryLine;
            if (lineNumbers) {
                numPaint.setColor(active ? k.lineNumberActive : k.lineNumber);
                int shown = relativeNumbers && !active ? Math.abs(line - primaryLine) : line + 1;
                c.drawText(Integer.toString(shown), right, top + baseline, numPaint);
            }
            if (foldingEnabled && folding != null && folding.rangeEndAtStale(line) > line) {
                boolean closed = folding.isCollapsed(line);
                drawChevron(c, gutterW - foldColW / 2 - 2 * density, top + lineH / 2, closed,
                        closed ? k.lineNumberActive : Skin_withAlpha(k.lineNumber, 0xB0));
            }
        }
    }

    private static int Skin_withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    /** A small ›/⌄ chevron (codicon-like) for fold controls. */
    private void drawChevron(Canvas c, float cx, float cy, boolean right, int color) {
        float s = 3.5f * density;
        stroke.setColor(color);
        stroke.setStrokeWidth(Math.max(1.5f, 1.4f * density));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        if (right) {
            path.moveTo(cx - s * 0.5f, cy - s);
            path.lineTo(cx + s * 0.5f, cy);
            path.lineTo(cx - s * 0.5f, cy + s);
        } else {
            path.moveTo(cx - s, cy - s * 0.5f);
            path.lineTo(cx, cy + s * 0.5f);
            path.lineTo(cx + s, cy - s * 0.5f);
        }
        c.drawPath(path, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawHandles(Canvas c) {
        if (eng == null) return;
        fill.setColor(colors.handle);
        int p = eng.sel.primary();
        if (showHandles && eng.sel.anyNonEmpty()) {
            int s = eng.sel.start(p), e = eng.sel.end(p);
            drawHandle(c, s, -1);
            drawHandle(c, e, 1);
        } else if (showInsertHandle && hasFocus()) {
            drawHandle(c, eng.sel.head(p), 0);
        }
    }

    /** Teardrop handle under the caret; side -1 = left (selection start), 1 = right, 0 = centered. */
    private void drawHandle(Canvas c, int off, int side) {
        float[] pos = caretPos(off);
        if (pos[0] < gutterW - handleR || pos[0] > getWidth() + handleR) return;
        float x = pos[0], y = pos[2];
        float r = handleR;
        float cx = side == 0 ? x : side < 0 ? x - r : x + r;
        float cy = y + r;
        c.drawCircle(cx, cy, r, fill);
        // The pointed corner touching the caret.
        if (side == 0) {
            path.reset();
            path.moveTo(x, y);
            path.lineTo(x - r * 0.72f, y + r * 0.3f);
            path.lineTo(x + r * 0.72f, y + r * 0.3f);
            path.close();
            c.drawPath(path, fill);
        } else {
            float l = side < 0 ? cx : x, rr = side < 0 ? x : cx;
            c.drawRect(l, y, rr, cy, fill);
        }
    }

    private void drawScrollbars(Canvas c, int rows) {
        int h = getHeight(), w = getWidth();
        float content = rows * lineH + h - Math.max(lineH, h / 3f);
        if (content > h * 1.05f) {
            float thumbH = Math.max(36 * density, h * (h / content));
            float top = (h - thumbH) * (scrollY / Math.max(1f, maxScrollY()));
            boolean recent = draggingThumbV || SystemClock.uptimeMillis() - lastScrollTime < 1200;
            fill.setColor(draggingThumbV ? colors.scrollThumbActive : colors.scrollThumb);
            float tw = (recent ? 10 : 6) * density;
            c.drawRect(w - tw, top, w, top + thumbH, fill);
            if (recent && !draggingThumbV) postInvalidateDelayed(1300);
        }
        int mx = maxScrollX();
        if (mx > 0) {
            float viewW = w - gutterW;
            float contentW = viewW + mx;
            float thumbW = Math.max(36 * density, viewW * (viewW / contentW));
            float left = gutterW + (viewW - thumbW) * (scrollX / (float) mx);
            fill.setColor(draggingThumbH ? colors.scrollThumbActive : colors.scrollThumb);
            c.drawRect(left, h - 6 * density, left + thumbW, h, fill);
        }
    }

    // ------------------------------------------------------------------ touch

    private boolean nearHandle(float x, float y, int off, int side) {
        float[] pos = caretPos(off);
        float cx = side == 0 ? pos[0] : side < 0 ? pos[0] - handleR : pos[0] + handleR;
        float cy = pos[2] + handleR;
        float slop = handleR * 2.2f;
        return Math.abs(x - cx) < slop && Math.abs(y - cy) < slop;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (eng == null) return false;
        int action = e.getActionMasked();
        lastTouchX = e.getX();
        lastTouchY = e.getY();
        boolean mouse = isMouse(e);

        if (action == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true);
            dragHandle = H_NONE;
            draggingThumbV = draggingThumbH = false;
            float x = e.getX(), y = e.getY();
            int p = eng.sel.primary();
            if (showHandles && eng.sel.anyNonEmpty()) {
                if (nearHandle(x, y, eng.sel.end(p), 1)) dragHandle = H_HEAD;
                else if (nearHandle(x, y, eng.sel.start(p), -1)) dragHandle = H_ANCHOR;
            } else if (showInsertHandle && nearHandle(x, y, eng.sel.head(p), 0)) {
                dragHandle = H_INSERT;
            }
            if (dragHandle != H_NONE) {
                float[] pos = caretPos(dragHandle == H_ANCHOR ? eng.sel.start(p) : dragHandle == H_HEAD ? eng.sel.end(p) : eng.sel.head(p));
                dragDx = pos[0] - x;
                dragDy = (pos[1] + lineH / 2) - y;
                if (dragHandle == H_ANCHOR) eng.sel.setAt(p, eng.sel.end(p), eng.sel.start(p));
                else if (dragHandle == H_HEAD) eng.sel.setAt(p, eng.sel.start(p), eng.sel.end(p));
                if (host != null) host.onSelectionMenu(this, false, 0, 0);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (minimapW > 0 && x >= getWidth() - minimapW) {
                draggingMinimap = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                dragMinimapTo(y);
                return true;
            }
            // Grab the vertical scrollbar along the right edge (fast scroll through big files).
            if (x > getWidth() - 24 * density && layout.rowCount() * lineH > getHeight() * 2) {
                draggingThumbV = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                dragThumbTo(y);
                return true;
            }
            if (mouse && (e.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0) {
                requestFocus();
                int off = offsetAt(x, y);
                if ((e.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) eng.setSelection(eng.sel.anchor(p), off);
                else if ((e.getMetaState() & KeyEvent.META_ALT_ON) != 0) {
                    eng.sel.add(off, off);
                    eng.selectionChangedExternally();
                } else eng.setSelection(off, off);
                selectingByDrag = true;
                dragAnchor = eng.sel.anchor(eng.sel.primary());
                return true;
            }
        }

        if (dragHandle != H_NONE) {
            if (action == MotionEvent.ACTION_MOVE) {
                int off = offsetAt(e.getX() + dragDx, e.getY() + dragDy - lineH * 0.2f);
                int p = eng.sel.primary();
                if (dragHandle == H_INSERT) eng.setSelection(off, off);
                else {
                    int a = eng.sel.anchor(p);
                    if (off == a) off = dragHandle == H_HEAD ? a + 1 : a - 1;
                    off = Math.max(0, Math.min(text.length(), off));
                    eng.sel.setAt(p, a, off);
                    eng.selectionChangedExternally();
                }
                autoScrollNear(e.getX(), e.getY());
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragHandle = H_NONE;
                if (eng.sel.anyNonEmpty()) showSelectionMenu();
                invalidate();
            }
            return true;
        }
        if (draggingMinimap) {
            if (action == MotionEvent.ACTION_MOVE) dragMinimapTo(e.getY());
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) draggingMinimap = false;
            return true;
        }
        if (draggingThumbV) {
            if (action == MotionEvent.ACTION_MOVE) dragThumbTo(e.getY());
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                draggingThumbV = false;
                invalidate();
            }
            return true;
        }
        if (selectingByDrag) {
            if (action == MotionEvent.ACTION_MOVE) {
                int off = offsetAt(e.getX(), e.getY());
                eng.sel.setAt(eng.sel.primary(), dragAnchor, off);
                eng.selectionChangedExternally();
                autoScrollNear(e.getX(), e.getY());
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                selectingByDrag = false;
            }
            return true;
        }

        scale.onTouchEvent(e);
        if (scaling) return true;
        boolean handled = gestures.onTouchEvent(e);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return handled || true;
    }

    private static boolean isMouse(MotionEvent e) {
        return (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
    }

    private void dragThumbTo(float y) {
        int h = getHeight();
        float content = layout.rowCount() * lineH;
        float thumbH = Math.max(36 * density, h * (h / Math.max(1f, content)));
        float frac = (y - thumbH / 2) / Math.max(1f, h - thumbH);
        scrollY = (int) (Math.max(0, Math.min(1, frac)) * maxScrollY());
        lastScrollTime = SystemClock.uptimeMillis();
        clampScroll();
        invalidate();
    }

    private void autoScrollNear(float x, float y) {
        float edge = lineH * 1.5f;
        boolean moved = false;
        if (y < edge) {
            scrollY -= (int) lineH;
            moved = true;
        } else if (y > getHeight() - edge) {
            scrollY += (int) lineH;
            moved = true;
        }
        if (!layout.wraps()) {
            if (x < gutterW + edge) {
                scrollX -= (int) (charW * 2);
                moved = true;
            } else if (x > getWidth() - edge) {
                scrollX += (int) (charW * 2);
                moved = true;
            }
        }
        if (moved) {
            clampScroll();
            invalidate();
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0 && e.getAction() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL), hz = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if ((e.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
                zoomBy(v > 0 ? 1 : -1);
                return true;
            }
            if ((e.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) {
                hz = v;
                v = 0;
            }
            scrollY -= (int) (v * lineH * 3);
            scrollX -= (int) (hz * charW * 6);
            lastScrollTime = SystemClock.uptimeMillis();
            clampScroll();
            invalidate();
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    private void zoomBy(int steps) {
        setFontSize(fontSp + steps);
        if (host != null) host.onZoom(this, fontSp);
    }

    public void showSelectionMenu() {
        if (host == null || eng == null) return;
        int p = eng.sel.primary();
        float[] a = caretPos(eng.sel.start(p));
        host.onSelectionMenu(this, true, a[0], a[1]);
    }

    /** After programmatic replacements of the word being typed, make the IME forget its composing text. */
    public void restartInputIfComposing() {
        if (composingStart >= 0) {
            composingStart = composingEnd = -1;
            if (imm != null) imm.restartInput(this);
        }
    }

    public void showKeyboard() {
        requestFocus();
        if (imm != null && !readOnlyView) imm.showSoftInput(this, 0);
    }

    public void hideKeyboard() {
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            int sticky = stickyHit(e.getY());
            if (sticky >= 0) {
                revealLine(sticky);
                scrollY = (int) (layout.rowOfLine(sticky) * lineH);
                clampScroll();
                invalidate();
                return true;
            }
            if (e.getX() < gutterW) {
                int row = (int) Math.floor((e.getY() + scrollY) / lineH);
                int line = layout.lineOfRow(Math.max(0, Math.min(row, layout.rowCount() - 1)));
                if (foldingEnabled && folding != null && e.getX() >= gutterW - foldColW - 6 * density) {
                    folding.ensureRanges();
                    if (folding.rangeEndAt(line) > line || folding.isCollapsed(line)) {
                        folding.toggle(line);
                        return true;
                    }
                }
                // Tapping a line number selects the line (like clicking the gutter in VS Code).
                int s = text.lineStart(line);
                int en = line + 1 < text.lineCount() ? text.lineStart(line + 1) : text.length();
                showHandles = true;
                eng.setSelection(s, en);
                showSelectionMenu();
                return true;
            }
            int off = offsetAt(e.getX(), e.getY());
            int p = eng.sel.primary();
            boolean insideSel = eng.sel.anyNonEmpty() && off >= eng.sel.start(p) && off <= eng.sel.end(p);
            if (insideSel && showHandles) {
                showSelectionMenu();
                return true;
            }
            showHandles = false;
            if (host != null) host.onSelectionMenu(CodeView.this, false, 0, 0);
            boolean sameSpot = eng.sel.count() == 1 && eng.sel.isEmpty(0) && eng.sel.caret() == off && showInsertHandle;
            eng.setSelection(off, off);
            showInsertHandle = true;
            prefValid = false;
            showKeyboard();
            if (sameSpot && host != null) {
                // Tapping the caret again offers Paste / Select all.
                float[] pos = caretPos(off);
                host.onSelectionMenu(CodeView.this, true, pos[0], pos[1]);
            }
            invalidate();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            int off = offsetAt(e.getX(), e.getY());
            int[] wd = eng.wordAt(off);
            if (wd[0] == wd[1]) {
                wd[0] = off;
                wd[1] = Math.min(text.length(), off + 1);
            }
            showHandles = true;
            eng.setSelection(wd[0], wd[1]);
            showSelectionMenu();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (scaling) return;
            int off = offsetAt(e.getX(), e.getY());
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int[] wd = eng.wordAt(off);
            showHandles = true;
            if (wd[0] == wd[1]) {
                eng.setSelection(off, off);
                showHandles = false;
                showInsertHandle = true;
                float[] pos = caretPos(off);
                if (host != null) host.onSelectionMenu(CodeView.this, true, pos[0], pos[1]);
            } else {
                eng.setSelection(wd[0], wd[1]);
                showSelectionMenu();
            }
            invalidate();
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (layout.wraps()) dx = 0;
            else if (Math.abs(dy) > Math.abs(dx) * 2) dx = 0; // lock to vertical for straight swipes
            else if (Math.abs(dx) > Math.abs(dy) * 2) dy = 0;
            scrollX += (int) dx;
            scrollY += (int) dy;
            lastScrollTime = SystemClock.uptimeMillis();
            clampScroll();
            if (host != null && (showHandles || showInsertHandle)) host.onSelectionMenu(CodeView.this, false, 0, 0);
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (layout.wraps()) vx = 0;
            scroller.fling(scrollX, scrollY, (int) -vx, (int) -vy, 0, maxScrollX(), 0, maxScrollY());
            lastScrollTime = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
            return true;
        }
    }

    private final class Scale extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private int anchorLine;

        @Override
        public boolean onScaleBegin(ScaleGestureDetector d) {
            scaling = true;
            scaleStartSp = fontSp;
            anchorLine = layout.lineOfRow((int) ((scrollY + d.getFocusY()) / lineH));
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector d) {
            float target = scaleStartSp * d.getScaleFactor();
            // getScaleFactor is relative to the previous event; accumulate.
            scaleStartSp = target;
            float rounded = Math.round(target * 2) / 2f;
            if (Math.abs(rounded - fontSp) >= 0.5f) {
                setFontSize(rounded);
                scrollY = (int) (layout.rowOfLine(anchorLine) * lineH - d.getFocusY());
                clampScroll();
            }
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector d) {
            scaling = false;
            if (host != null) host.onZoom(CodeView.this, fontSp);
        }
    }

    // ------------------------------------------------------------------ keyboard

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | (keyboardSuggestions ? 0 : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        if (eng != null) {
            int p = eng.sel.primary();
            out.initialSelStart = eng.sel.start(p);
            out.initialSelEnd = eng.sel.end(p);
        }
        input = new CodeInput(this);
        return input;
    }

    /** Moves carets one visual row up or down (or a page), keeping the preferred x. */
    public void moveVertical(int rowsDelta, boolean extend) {
        Selections s = eng.sel;
        int n = s.count();
        if (!prefValid || prefX.length < n) {
            prefX = new float[Math.max(4, n)];
            for (int i = 0; i < n; i++) prefX[i] = xOf(s.head(i));
            prefValid = true;
        }
        int rows = layout.rowCount();
        for (int i = 0; i < n; i++) {
            int hd = s.head(i);
            int row = rowOfOffset(hd) + rowsDelta;
            int off;
            if (row < 0) off = 0;
            else if (row >= rows) off = text.length();
            else off = offsetInRow(row, prefX[i]);
            s.setAt(i, extend ? s.anchor(i) : off, off);
        }
        boolean keep = true;
        eng.selectionChangedExternally();
        prefValid = keep;
    }

    /** Adds a caret one row above/below the top/bottom caret (Ctrl+Alt+Up/Down). */
    public void addCaretVertical(int dir) {
        Selections s = eng.sel;
        int edge = dir < 0 ? s.head(0) : s.head(s.count() - 1);
        int row = rowOfOffset(edge) + dir;
        if (row < 0 || row >= layout.rowCount()) return;
        int off = offsetInRow(row, xOf(edge));
        s.add(off, off);
        eng.selectionChangedExternally();
    }

    public int rowsPerPage() {
        return Math.max(1, (int) (getHeight() / lineH) - 1);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (eng == null) return super.onKeyDown(keyCode, e);
        lastInput = SystemClock.uptimeMillis();
        caretOn = true;
        if (host != null && host.onEditorKey(this, keyCode, e)) return true;
        boolean shift = e.isShiftPressed(), ctrl = e.isCtrlPressed(), alt = e.isAltPressed();
        boolean vertical = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN;
        if (!vertical) prefValid = false;
        showInsertHandle = false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                eng.move(ctrl ? EditEngine.MOVE_WORD_LEFT : EditEngine.MOVE_LEFT, shift);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                eng.move(ctrl ? EditEngine.MOVE_WORD_RIGHT : EditEngine.MOVE_RIGHT, shift);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (ctrl && !alt) {
                    scrollY -= (int) lineH;
                    clampScroll();
                    invalidate();
                } else moveVertical(-1, shift);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (ctrl && !alt) {
                    scrollY += (int) lineH;
                    clampScroll();
                    invalidate();
                } else moveVertical(1, shift);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                moveVertical(-rowsPerPage(), shift);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                moveVertical(rowsPerPage(), shift);
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                eng.move(ctrl ? EditEngine.MOVE_DOC_START : EditEngine.MOVE_HOME, shift);
                return true;
            case KeyEvent.KEYCODE_MOVE_END:
                eng.move(ctrl ? EditEngine.MOVE_DOC_END : EditEngine.MOVE_END, shift);
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                if (eng.snippet.isActive()) {
                    eng.snippet.end();
                    return true;
                }
                if (eng.sel.count() > 1) {
                    eng.sel.collapseToPrimary();
                    eng.selectionChangedExternally();
                    return true;
                }
                if (eng.sel.anyNonEmpty()) {
                    int c = eng.sel.caret();
                    eng.setSelection(c, c);
                    return true;
                }
                return super.onKeyDown(keyCode, e);
        }
        if (readOnlyView || (doc != null && doc.readOnly)) return super.onKeyDown(keyCode, e);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                if (ctrl) eng.deleteWord(false);
                else eng.backspace();
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (shift && !ctrl) eng.deleteLines();
                else if (ctrl) eng.deleteWord(true);
                else eng.deleteForward();
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (ctrl) eng.insertLine(shift);
                else eng.newline();
                return true;
            case KeyEvent.KEYCODE_TAB:
                if (shift && eng.snippet.isActive()) eng.snippet.prev();
                else if (shift) eng.indentLines(false);
                else eng.tab();
                return true;
        }
        boolean altGr = (e.getMetaState() & KeyEvent.META_ALT_RIGHT_ON) != 0;
        if (!ctrl && (!alt || altGr)) {
            int uc = e.getUnicodeChar(e.getMetaState() & ~(KeyEvent.META_CTRL_MASK));
            if (uc != 0 && (uc & KeyCharacterMapCompat.COMBINING_ACCENT) == 0 && uc >= 32) {
                eng.type(new String(Character.toChars(uc)));
                return true;
            }
        }
        return super.onKeyDown(keyCode, e);
    }

    /** Small shim: AltGr state and the dead-key flag. */
    static final class KeyCharacterMapCompat {
        static final int COMBINING_ACCENT = android.view.KeyCharacterMap.COMBINING_ACCENT;
    }

    // ------------------------------------------------------------------ clipboard

    private String lineCopy;

    public void copy(boolean cut) {
        if (eng == null) return;
        Selections s = eng.sel;
        StringBuilder sb = new StringBuilder();
        boolean wholeLine = !s.anyNonEmpty();
        if (wholeLine) {
            // Like VS Code: copying with no selection copies the current line(s).
            for (int i = 0; i < s.count(); i++) {
                int line = text.lineOfOffset(s.head(i));
                if (i > 0) sb.append('\n');
                sb.append(text.lineText(line)).append('\n');
            }
            if (s.count() > 1) sb.setLength(sb.length() - 1);
        } else {
            for (int i = 0; i < s.count(); i++) {
                if (s.isEmpty(i)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(text.substring(s.start(i), s.end(i)));
            }
        }
        String str = sb.toString();
        lineCopy = wholeLine ? str : null;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            try {
                cm.setPrimaryClip(ClipData.newPlainText("code", str));
            } catch (RuntimeException ignored) {
                // Huge clips can exceed the binder limit.
            }
        }
        if (cut && !(doc != null && doc.readOnly)) {
            if (wholeLine) eng.deleteLines();
            else eng.deleteSelections();
        }
    }

    public void paste() {
        if (eng == null || (doc != null && doc.readOnly)) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence cs = clip.getItemAt(0).coerceToText(getContext());
        if (cs == null) return;
        String s = cs.toString().replace("\r\n", "\n").replace('\r', '\n');
        pasteText(s);
    }

    public void pasteText(String s) {
        Selections sel = eng.sel;
        if (s.equals(lineCopy) && !sel.anyNonEmpty() && sel.count() == 1) {
            // A whole-line copy is pasted above the current line.
            int ls = text.lineStart(text.lineOfOffset(sel.caret()));
            int caret = sel.caret() + s.length();
            eng.replaceAndSelect(ls, ls, s, caret, caret);
            return;
        }
        String[] parts = s.split("\n", -1);
        if (sel.count() > 1 && parts.length == sel.count()) {
            // One line per caret.
            eng.begin(com.disgusty.droid_codology.text.UndoStack.KIND_OTHER);
            for (int i = sel.count() - 1; i >= 0; i--) {
                int st = sel.start(i), en = sel.end(i);
                eng.replace(st, en, parts[i]);
                // replace() shifted carets; place this one after its text.
                sel.setAt(i, st + parts[i].length(), st + parts[i].length());
            }
            eng.end();
            return;
        }
        // Plain insertion without auto-indent/auto-close.
        eng.begin(com.disgusty.droid_codology.text.UndoStack.KIND_OTHER);
        for (int i = sel.count() - 1; i >= 0; i--) {
            int st = sel.start(i), en = sel.end(i);
            eng.replace(st, en, s);
            sel.setAt(i, st + s.length(), st + s.length());
        }
        eng.end();
    }

    public void selectAllTouch() {
        showHandles = true;
        eng.selectAll();
        showSelectionMenu();
    }

    public boolean isTouchSelecting() {
        return showHandles;
    }

    public void hideHandles() {
        showHandles = false;
        showInsertHandle = false;
        invalidate();
    }
}
