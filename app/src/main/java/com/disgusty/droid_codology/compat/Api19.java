package com.disgusty.droid_codology.compat;

import android.annotation.TargetApi;
import android.webkit.WebView;

/** Android 4.4+ only calls, kept apart so older VMs never verify them. */
@TargetApi(19)
public final class Api19 {
    private Api19() {
    }

    public static void evaluate(WebView w, String js) {
        w.evaluateJavascript(js, null);
    }
}
