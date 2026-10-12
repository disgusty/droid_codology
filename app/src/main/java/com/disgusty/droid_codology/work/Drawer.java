package com.disgusty.droid_codology.work;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Slide-in side panel for narrow screens: content underneath, a scrim and a panel that follows
 * the finger when dragged from the left edge (or opened with the menu button).
 */
public final class Drawer extends FrameLayout {
    private final View scrim;
    private View panel;
    private float offset; // 0 closed .. 1 open
    private boolean dragging, fromEdge;
    private float downX, downY, startOffset;
    private final int touchSlop;
    private final float edge;
    private boolean enabled = true;
    private Runnable onClosed, onOpened;

    public Drawer(Context ctx, View content, int scrimColor) {
        super(ctx);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        scrim = new View(ctx);
        scrim.setBackgroundColor(scrimColor);
        scrim.setVisibility(GONE);
        scrim.setClickable(true);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        edge = 16 * ctx.getResources().getDisplayMetrics().density;
    }

    public void setPanel(View p, int widthPx) {
        if (panel != null) removeView(panel);
        panel = p;
        if (p != null) {
            addView(p, new LayoutParams(widthPx, LayoutParams.MATCH_PARENT));
            apply();
        }
    }

    public void setDrawerEnabled(boolean e) {
        enabled = e;
        if (!e) {
            offset = 0;
            apply();
        }
    }

    public void setListeners(Runnable opened, Runnable closed) {
        onOpened = opened;
        onClosed = closed;
    }

    public boolean isOpen() {
        return offset > 0.5f;
    }

    public void open() {
        animateTo(1);
    }

    public void close() {
        animateTo(0);
    }

    private void animateTo(final float target) {
        if (panel == null) return;
        final float from = offset;
        final long start = System.currentTimeMillis();
        final long dur = 180;
        post(new Runnable() {
            @Override
            public void run() {
                float t = Math.min(1f, (System.currentTimeMillis() - start) / (float) dur);
                float e = 1 - (1 - t) * (1 - t);
                offset = from + (target - from) * e;
                apply();
                if (t < 1) post(this);
                else if (target == 0 && onClosed != null) onClosed.run();
                else if (target == 1 && onOpened != null) onOpened.run();
            }
        });
    }

    private void apply() {
        if (panel == null) return;
        int w = panel.getLayoutParams().width;
        panel.setTranslationX(-w * (1 - offset));
        panel.setVisibility(offset > 0 ? VISIBLE : INVISIBLE);
        scrim.setVisibility(offset > 0 ? VISIBLE : GONE);
        scrim.setAlpha(offset);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!enabled || panel == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                startOffset = offset;
                fromEdge = offset == 0 && downX < edge;
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if ((fromEdge && dx > touchSlop) || (offset == 1 && dx < -touchSlop && Math.abs(dx) > Math.abs(dy))) {
                    dragging = true;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        int w = panel.getLayoutParams().width;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                offset = Math.max(0, Math.min(1, startOffset + (e.getX() - downX) / w));
                apply();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                animateTo(offset > 0.4f ? 1 : 0);
                return true;
        }
        return true;
    }
}
