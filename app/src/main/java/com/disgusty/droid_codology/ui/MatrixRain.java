package com.disgusty.droid_codology.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The film's "digital rain" behind the interface: columns of half-width katakana and digits
 * falling at different speeds, each with a bright leading glyph and a fading tail; glyphs flicker
 * as they fall. It runs only while visible and the app is in front (start/stop), at ~20 fps.
 */
public final class MatrixRain extends View {
    private static final String GLYPHS = "ｦｱｳｴｵｶｷｹｺｻｼｽｾｿﾀﾂﾃﾅﾆﾇﾈﾊﾋﾎﾏﾐﾑﾒﾓﾔﾕﾗﾘﾜ0123456789Z:.=*+-<>¦|";
    private static final long FRAME_MS = 50;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final int color, head, background;
    private float cell;
    private int cols, rows;
    /** Per column: head row (fractional), speed in rows per frame, tail length. */
    private float[] y, speed;
    private int[] length;
    private char[][] grid;
    private boolean running;
    private long lastFrame;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    /**
     * @param color      the glyph color (phosphor green, or the style's accent)
     * @param background what's behind the glyphs (the window color, drawn first)
     */
    public MatrixRain(Context ctx, int color, int background, float textSizePx) {
        super(ctx);
        this.color = color | 0xFF000000;
        this.head = Skin.mix(this.color, 0xFFFFFFFF, 0.75f);
        this.background = background;
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(textSizePx);
        paint.setTextAlign(Paint.Align.CENTER);
        cell = textSizePx * 1.15f;
        setWillNotDraw(false);
        // Decoration only: screen readers and touches go to the views above.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cols = Math.max(1, (int) (w / cell));
        rows = Math.max(1, (int) (h / cell) + 1);
        y = new float[cols];
        speed = new float[cols];
        length = new int[cols];
        grid = new char[cols][rows];
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) grid[c][r] = randomGlyph();
            reset(c, true);
        }
    }

    private void reset(int c, boolean anywhere) {
        y[c] = anywhere ? rnd.nextFloat() * rows * 1.5f - rows * 0.5f : -rnd.nextInt(Math.max(1, rows / 2));
        speed[c] = 0.25f + rnd.nextFloat() * 0.55f;
        length[c] = 6 + rnd.nextInt(Math.max(1, rows / 2));
    }

    private char randomGlyph() {
        return GLYPHS.charAt(rnd.nextInt(GLYPHS.length()));
    }

    private void step() {
        if (grid == null) return;
        long now = SystemClock.uptimeMillis();
        // Catch up evenly when frames were dropped, but never jump far.
        float frames = lastFrame == 0 ? 1 : Math.min(3f, (now - lastFrame) / (float) FRAME_MS);
        lastFrame = now;
        for (int c = 0; c < cols; c++) {
            int before = (int) y[c];
            y[c] += speed[c] * frames;
            int after = (int) y[c];
            // a new glyph appears at the head; a few others flicker
            if (after != before && after >= 0 && after < rows) grid[c][after] = randomGlyph();
            if (rnd.nextInt(8) == 0) grid[c][rnd.nextInt(rows)] = randomGlyph();
            if (y[c] - length[c] > rows) reset(c, false);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(background);
        if (grid == null) return;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = -fm.ascent;
        for (int c = 0; c < cols; c++) {
            int top = (int) y[c];
            float x = c * cell + cell / 2;
            for (int k = 0; k < length[c]; k++) {
                int r = top - k;
                if (r < 0) break;
                if (r >= rows) continue;
                if (k == 0) {
                    paint.setColor(head);
                } else {
                    int a = (int) (230 * (1f - k / (float) length[c]));
                    if (a <= 8) break;
                    paint.setColor((color & 0x00FFFFFF) | (a << 24));
                }
                canvas.drawText(grid[c], r, 1, x, r * cell + baseline, paint);
            }
        }
    }

    public void start() {
        if (running) return;
        running = true;
        lastFrame = 0;
        removeCallbacks(tick);
        post(tick);
    }

    public void stop() {
        running = false;
        removeCallbacks(tick);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }
}
