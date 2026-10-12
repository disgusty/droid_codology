package com.disgusty.droid_codology.work;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Switches the launcher icon to the current interface style (one activity-alias per style). */
final class LauncherIcons {
    private LauncherIcons() {
    }

    private static final String[] ALIASES = {".LauncherOriginal", ".LauncherHolo", ".LauncherMaterial", ".LauncherMaterial3", ".LauncherMatrix"};

    static void apply(Context ctx, int style) {
        PackageManager pm = ctx.getPackageManager();
        String pkg = ctx.getPackageName();
        for (int i = 0; i < ALIASES.length; i++) {
            ComponentName cn = new ComponentName(pkg, pkg + ALIASES[i]);
            int want = i == style ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            try {
                if (pm.getComponentEnabledSetting(cn) != want) pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP);
            } catch (RuntimeException ignored) {
                // Some launchers/ROMs restrict this; the default icon stays.
            }
        }
    }
}
