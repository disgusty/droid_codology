package com.disgusty.droid_codology.work;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Moves the borders between the side bar, the editors and the bottom panel. A transparent layer
 * over the window: a single tap goes through to what is under it, so scrolling and placing the
 * caret near a border work as before. Double-tap a border and keep the finger down to drag it;
 * a plain double-tap highlights it for a few seconds, and then an ordinary drag moves it.
 */
final class BorderDrag extends View {
    static final int SIDEBAR = 1, PANEL = 2, SPLIT = 3;
    private static final int[] BORDERS = {SIDEBAR, PANEL, SPLIT};
    private static final long ARMED_MS = 4000;

    private final Workbench wb;
    private final Rect line = new Rect();
    private final Paint paint = new Paint();
    private final int slop, reach, doubleTapMs;

    private long lastDown;
    private float lastX, lastY;
    private int lastBorder;
    /** Highlighted after a double-tap: the next drag moves it without another double-tap. */
    private int armed;
    private long armedUntil;
    private int dragging;
    private float startX, startY;
    private boolean moved;

    private final Runnable disarm = new Runnable() {
        @Override
        public void run() {
            armed = 0;
            invalidate();
        }
    };

    BorderDrag(Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        ViewConfiguration vc = ViewConfiguration.get(wb.activity);
        slop = vc.getScaledTouchSlop();
        doubleTapMs = Math.max(300, ViewConfiguration.getDoubleTapTimeout() + 100);
        // Borders are 1px lines: a finger is anywhere within this distance.
        reach = wb.skin.dp(18);
        paint.setColor(wb.skin.focusBorder);
        setWillNotDraw(false);
    }

    private int borderAt(float x, float y) {
        for (int b : BORDERS) {
            if (!wb.borderLine(b, line)) continue;
            boolean vertical = line.width() <= line.height();
            if (vertical ? Math.abs(x - line.centerX()) <= reach && y >= line.top && y <= line.bottom
                    : Math.abs(y - line.centerY()) <= reach && x >= line.left && x <= line.right) return b;
        }
        return 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int b = borderAt(x, y);
                long now = e.getEventTime();
                boolean second = b != 0 && b == lastBorder && now - lastDown <= doubleTapMs
                        && Math.abs(x - lastX) < reach * 2 && Math.abs(y - lastY) < reach * 2;
                lastDown = now;
                lastX = x;
                lastY = y;
                lastBorder = b;
                if (b == 0 || !(second || b == armed && now < armedUntil)) return false; // goes to the views below
                dragging = b;
                startX = x;
                startY = y;
                moved = false;
                lastBorder = 0;
                removeCallbacks(disarm);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging == 0) return false;
                if (!moved && Math.abs(x - startX) < slop && Math.abs(y - startY) < slop) return true;
                moved = true;
                wb.moveBorder(dragging, x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging == 0) return false;
                if (moved) {
                    wb.saveBorders();
                    armed = 0;
                } else {
                    armed = dragging;
                    armedUntil = e.getEventTime() + ARMED_MS;
                    postDelayed(disarm, ARMED_MS);
                    wb.showMessage(I18n.t("Drag the highlighted border to move it."));
                }
                dragging = 0;
                invalidate();
                return true;
            default:
                return dragging != 0;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int b = dragging != 0 ? dragging : armed;
        if (b == 0 || !wb.borderLine(b, line)) return;
        int half = Math.max(1, wb.skin.dp(2));
        if (line.width() <= line.height()) c.drawRect(line.centerX() - half, line.top, line.centerX() + half, line.bottom, paint);
        else c.drawRect(line.left, line.centerY() - half, line.right, line.centerY() + half, paint);
    }
}
