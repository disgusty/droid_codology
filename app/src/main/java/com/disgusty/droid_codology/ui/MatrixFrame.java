package com.disgusty.droid_codology.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The frame of the Matrix style, after the menus of the Matrix games: a thin phosphor line with
 * heavier corner brackets, over a fill (often plain black).
 */
public final class MatrixFrame extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int fill, line, lineW, cornerW, cornerLen;
    private final boolean brackets;

    public MatrixFrame(int fill, int line, int lineW, int cornerW, int cornerLen, boolean brackets) {
        this.fill = fill;
        this.line = line;
        this.lineW = lineW;
        this.cornerW = cornerW;
        this.cornerLen = cornerLen;
        this.brackets = brackets;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        paint.setStyle(Paint.Style.FILL);
        if ((fill >>> 24) != 0) {
            paint.setColor(fill);
            c.drawRect(b, paint);
        }
        if ((line >>> 24) == 0) return;
        paint.setColor(line);
        // the thin line
        if (lineW > 0) {
            float h = lineW / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(lineW);
            c.drawRect(b.left + h, b.top + h, b.right - h, b.bottom - h, paint);
        }
        if (!brackets) return;
        paint.setStyle(Paint.Style.FILL);
        int l = Math.min(cornerLen, Math.min(b.width(), b.height()) / 3);
        int w = cornerW;
        // top left, top right, bottom left, bottom right: an "L" each
        c.drawRect(b.left, b.top, b.left + l, b.top + w, paint);
        c.drawRect(b.left, b.top, b.left + w, b.top + l, paint);
        c.drawRect(b.right - l, b.top, b.right, b.top + w, paint);
        c.drawRect(b.right - w, b.top, b.right, b.top + l, paint);
        c.drawRect(b.left, b.bottom - w, b.left + l, b.bottom, paint);
        c.drawRect(b.left, b.bottom - l, b.left + w, b.bottom, paint);
        c.drawRect(b.right - l, b.bottom - w, b.right, b.bottom, paint);
        c.drawRect(b.right - w, b.bottom - l, b.right, b.bottom, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return (fill >>> 24) == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }
}
