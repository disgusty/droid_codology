package com.disgusty.droid_codology.compat;

import android.annotation.TargetApi;
import android.view.View;

/** Android 8.0+ only calls. */
@TargetApi(26)
public final class Api26 {
    private Api26() {
    }

    /** The editor draws its own caret; the system's keyboard-focus overlay would tint all of it. */
    public static void noFocusHighlight(View v) {
        v.setDefaultFocusHighlightEnabled(false);
    }
}
