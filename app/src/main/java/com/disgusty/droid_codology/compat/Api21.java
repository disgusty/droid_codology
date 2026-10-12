package com.disgusty.droid_codology.compat;

import android.annotation.TargetApi;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** Android 5.0+ only calls, kept apart so older VMs never verify them. */
@TargetApi(21)
public final class Api21 {
    private Api21() {
    }

    public static void barColors(Window w, int status, int nav, boolean lightStatus, boolean lightNav) {
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        // Light status bar icons exist from 6.0, light nav bar icons from 8.0; otherwise darken the bar.
        if (Build.VERSION.SDK_INT < 23 && lightStatus) status = darken(status);
        if (Build.VERSION.SDK_INT < 27 && lightNav) nav = darken(nav);
        w.setStatusBarColor(status);
        w.setNavigationBarColor(nav);
        if (Build.VERSION.SDK_INT >= 23) Api23.lightBars(w.getDecorView(), lightStatus, lightNav);
    }

    private static int darken(int c) {
        return 0xFF000000 | (((c >> 16) & 0xFF) * 6 / 10 << 16) | (((c >> 8) & 0xFF) * 6 / 10 << 8) | ((c & 0xFF) * 6 / 10);
    }

    public static Drawable ripple(int color, Drawable content, Drawable pressed) {
        // The mask bounds the ripple: the visible content if it has any, else the pressed shape;
        // with neither the ripple is borderless (toolbar icon buttons).
        boolean emptyContent = content == null || (content instanceof ColorDrawable && ((ColorDrawable) content).getColor() == 0);
        Drawable mask = emptyContent ? pressed : content;
        if (mask instanceof ColorDrawable && ((ColorDrawable) mask).getColor() == 0) mask = new ColorDrawable(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(color), content, mask);
    }

    public static void elevation(View v, float px) {
        v.setElevation(px);
    }

    /** The device's preferred ABI (Build.SUPPORTED_ABIS is 5.0+). */
    public static String primaryAbi() {
        String[] a = android.os.Build.SUPPORTED_ABIS;
        return a != null && a.length > 0 ? a[0] : android.os.Build.CPU_ABI;
    }

    /** DNS servers of the default network (the active one on 6.0+, the first connected before). */
    @SuppressWarnings("deprecation")
    public static java.util.List<String> dnsServers(android.content.Context ctx) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (cm == null) return out;
        android.net.Network net = Build.VERSION.SDK_INT >= 23 ? Api23.activeNetwork(cm) : null;
        if (net == null) {
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkInfo ni = cm.getNetworkInfo(n);
                if (ni != null && ni.isConnected()) {
                    net = n;
                    break;
                }
            }
        }
        android.net.LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
        if (lp == null) return out;
        for (java.net.InetAddress a : lp.getDnsServers()) out.add(a.getHostAddress());
        return out;
    }
}
