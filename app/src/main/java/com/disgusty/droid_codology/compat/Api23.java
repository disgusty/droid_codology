package com.disgusty.droid_codology.compat;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.view.View;

/** Android 6.0+ only calls. */
@TargetApi(23)
public final class Api23 {
    private Api23() {
    }

    @SuppressWarnings("deprecation")
    static void lightBars(View decor, boolean lightStatus, boolean lightNav) {
        int f = decor.getSystemUiVisibility();
        f = lightStatus ? f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            f = lightNav ? f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(f);
    }

    public static boolean hasPermission(Activity a, String perm) {
        return a.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static void requestPermissions(Activity a, String[] perms, int code) {
        a.requestPermissions(perms, code);
    }

    static android.net.Network activeNetwork(android.net.ConnectivityManager cm) {
        return cm.getActiveNetwork();
    }
}
