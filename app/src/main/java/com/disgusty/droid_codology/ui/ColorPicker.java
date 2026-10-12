package com.disgusty.droid_codology.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * An HSV color picker like in drawing apps: a saturation/value square with a hue strip beneath.
 * Drag either part; {@link Listener} gets every change.
 */
public final class ColorPicker extends View {
    public interface Listener {
        void onColor(int argb);
    }

    private final float[] hsv = {0, 1, 1};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sv = new RectF(), hue = new RectF();
    private final float density;
    private Listener listener;
    private int dragging; // 0 none, 1 square, 2 hue
    private Shader svShader;
    private float shaderHue = -1;
    private Shader hueShader;

    public ColorPicker(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        // ComposeShader with a hardware canvas needs API 16+ and a software layer for mixed modes on some GPUs.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setColor(int argb) {
        Color.colorToHSV(argb, hsv);
        invalidate();
    }

    public int color() {
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (w * 0.62f + 48 * density));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = 12 * density;
        sv.set(pad, pad, w - pad, h - 40 * density);
        hue.set(pad, h - 28 * density, w - pad, h - 8 * density);
        int[] colors = new int[7];
        for (int i = 0; i < 7; i++) colors[i] = Color.HSVToColor(new float[]{i * 60f % 360, 1, 1});
        colors[6] = 0xFFFF0000;
        hueShader = new LinearGradient(hue.left, 0, hue.right, 0, colors, null, Shader.TileMode.CLAMP);
        shaderHue = -1;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (shaderHue != hsv[0] || svShader == null) {
            Shader sat = new LinearGradient(sv.left, 0, sv.right, 0, 0xFFFFFFFF, Color.HSVToColor(new float[]{hsv[0], 1, 1}), Shader.TileMode.CLAMP);
            Shader val = new LinearGradient(0, sv.top, 0, sv.bottom, 0xFFFFFFFF, 0xFF000000, Shader.TileMode.CLAMP);
            svShader = new ComposeShader(val, sat, PorterDuff.Mode.MULTIPLY);
            shaderHue = hsv[0];
        }
        float r = 6 * density;
        paint.setShader(svShader);
        c.drawRoundRect(sv, r, r, paint);
        paint.setShader(hueShader);
        c.drawRoundRect(hue, hue.height() / 2, hue.height() / 2, paint);
        paint.setShader(null);
        // Thumbs: white rings with a dark outline, filled with the current color.
        float x = sv.left + hsv[1] * sv.width(), y = sv.top + (1 - hsv[2]) * sv.height();
        drawThumb(c, x, y, Color.HSVToColor(hsv));
        float hx = hue.left + hsv[0] / 360f * hue.width();
        drawThumb(c, hx, hue.centerY(), Color.HSVToColor(new float[]{hsv[0], 1, 1}));
    }

    private void drawThumb(Canvas c, float x, float y, int fill) {
        float r = 11 * density;
        paint.setColor(fill);
        c.drawCircle(x, y, r, paint);
        ring.setStrokeWidth(3 * density);
        ring.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, r, ring);
        ring.setStrokeWidth(density);
        ring.setColor(0x66000000);
        c.drawCircle(x, y, r + 1.5f * density, ring);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = y > sv.bottom + 4 * density ? 2 : 1;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (dragging == 1) {
                    hsv[1] = clamp((x - sv.left) / sv.width());
                    hsv[2] = 1 - clamp((y - sv.top) / sv.height());
                } else if (dragging == 2) {
                    hsv[0] = clamp((x - hue.left) / hue.width()) * 359.9f;
                }
                invalidate();
                if (listener != null) listener.onColor(color());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = 0;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
