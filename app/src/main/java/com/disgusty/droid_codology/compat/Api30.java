package com.disgusty.droid_codology.compat;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.view.View;
import android.view.WindowInsets;

/** Android 11+ only calls. */
@TargetApi(30)
public final class Api30 {
    private Api30() {
    }

    public static boolean isStorageManager() {
        return Environment.isExternalStorageManager();
    }

    /**
     * Android 15 draws every window edge to edge and ignores bar colors: pad the content by the
     * system bars, cutout and keyboard (replacing adjustResize), and paint the padding in the
     * skin's status / navigation bar colors.
     */
    public static void edgeToEdge(View content, int statusColor, int navColor) {
        content.setBackground(new Bars(content, statusColor, navColor));
        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                v.invalidate();
                return WindowInsets.CONSUMED;
            }
        });
        content.requestApplyInsets();
    }

    private static final class Bars extends Drawable {
        private final View owner;
        private final Paint status = new Paint(), nav = new Paint();

        Bars(View owner, int statusColor, int navColor) {
            this.owner = owner;
            status.setColor(statusColor);
            nav.setColor(navColor);
        }

        @Override
        public void draw(Canvas c) {
            int w = getBounds().width(), h = getBounds().height();
            int top = owner.getPaddingTop(), bottom = owner.getPaddingBottom();
            int left = owner.getPaddingLeft(), right = owner.getPaddingRight();
            if (top > 0) c.drawRect(0, 0, w, top, status);
            if (bottom > 0) c.drawRect(0, h - bottom, w, h, nav);
            if (left > 0) c.drawRect(0, top, left, h - bottom, nav);
            if (right > 0) c.drawRect(w - right, top, w, h - bottom, nav);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
