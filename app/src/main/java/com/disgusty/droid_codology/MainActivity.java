package com.disgusty.droid_codology;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.disgusty.droid_codology.compat.Api23;
import com.disgusty.droid_codology.ui.Skin;
import com.disgusty.droid_codology.work.Workbench;

import static com.disgusty.droid_codology.work.I18n.t;

/** The only activity: hosts the workbench and bridges system intents, permissions and pickers. */
public final class MainActivity extends Activity implements Workbench.Opener, com.disgusty.droid_codology.work.StorageAccessBridge {
    private static final int REQ_OPEN = 1, REQ_PERM = 2, REQ_VSIX = 3;
    private Workbench wb;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        wb = new Workbench(this);
        wb.restoreSession();
        setContentView(wb.build());
        // A recreated activity (restored state, or reopened from Recents) would replay the intent
        // it was first started with: a second shared document, an old sign-in link...
        boolean replay = state != null || getIntent() != null
                && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (!replay) handleIntent(getIntent());
    }

    @Override
    protected void onDestroy() {
        // This Workbench dies with the activity: its extension host, chat and language servers too.
        if (wb != null) wb.dispose();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        // Test hooks (debug builds only): am start -e openFolder /path -e openFile /path/file
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            String folder = i.getStringExtra("openFolder"), file = i.getStringExtra("openFile");
            if (folder != null) wb.openFolder(new java.io.File(folder));
            if (file != null) wb.openFile(new java.io.File(file), false, i.getIntExtra("line", -1), 0, 0);
            String pb = i.getStringExtra("prefBool");
            if (pb != null) {
                for (String kv : pb.split(",")) {
                    String[] p = kv.split("=");
                    if (p.length == 2) wb.prefs.put(p[0], Boolean.parseBoolean(p[1]));
                }
                wb.applyEditorPrefs();
            }
            if (i.hasExtra("style") || i.hasExtra("theme")) {
                if (i.hasExtra("style")) wb.prefs.put(com.disgusty.droid_codology.work.Prefs.STYLE, i.getIntExtra("style", 0));
                if (i.hasExtra("theme")) wb.prefs.put(com.disgusty.droid_codology.work.Prefs.THEME, i.getStringExtra("theme"));
                wb.rebuild();
            }
            String googleBase = i.getStringExtra("googleTestBase");
            if (googleBase != null) com.disgusty.droid_codology.account.GoogleAccount.useTestEndpoints(googleBase);
            String githubBase = i.getStringExtra("githubTestBase");
            if (githubBase != null) com.disgusty.droid_codology.account.GitHubAccount.useTestEndpoints(githubBase);
            String firebaseBase = i.getStringExtra("firebaseTestBase");
            if (firebaseBase != null) com.disgusty.droid_codology.account.FirebaseAccount.useTestEndpoints(firebaseBase);
            String server = i.getStringExtra("server");
            if (server != null) wb.account.setServer(server);
            String vsix = i.getStringExtra("installVsix");
            if (vsix != null) wb.installVsix(new java.io.File(vsix));
            // -e activityBar explorer,view:publisher.ext/container  -e sideView 6 (index in that list)
            String bar = i.getStringExtra("activityBar");
            if (bar != null) wb.setBarIds(java.util.Arrays.asList(bar.split(",")));
            if (i.hasExtra("sideView")) wb.showBarItem(i.getIntExtra("sideView", 0));
            String cmd = i.getStringExtra("command");
            if (cmd != null) wb.commands.run(cmd);
        }
        String action = i.getAction();
        Uri data = i.getData();
        if (Intent.ACTION_VIEW.equals(action) && data != null && "droid-codology".equals(data.getScheme())) {
            // OAuth sign-in coming back from the browser.
            if ("auth".equals(data.getHost())) wb.onAuthRedirect(data);
            return;
        }
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
            Uri u = i.getData();
            if (u != null) {
                if ("content".equals(u.getScheme()) && Build.VERSION.SDK_INT >= 19) {
                    try {
                        boolean canWrite = (i.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
                        getContentResolver().takePersistableUriPermission(u, canWrite
                                ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {
                        // Not a persistable grant; fine for this session.
                    }
                }
                wb.openUri(u);
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            // Any app can send this intent, with anything in EXTRA_STREAM.
            Object extra;
            try {
                extra = i.getParcelableExtra(Intent.EXTRA_STREAM);
            } catch (RuntimeException e) {
                extra = null;
            }
            Uri stream = extra instanceof Uri ? (Uri) extra : null;
            CharSequence text = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (stream != null) wb.openUri(stream);
            else if (text != null) wb.newUntitled(text.toString());
        }
        setIntent(new Intent());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (wb != null && wb.dispatchKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (wb != null && wb.onBack()) return;
        // Like a desktop app: keep everything open in the background instead of quitting.
        moveTaskToBack(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wb != null) wb.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wb != null) wb.onResume();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        // Screen size (phone ↔ tablet layout), dark mode or a keyboard was attached: rebuild the UI.
        if (wb != null) wb.rebuild();
    }

    // ------------------------------------------------------------------ files

    @Override
    public void pickFile() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 19) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
        }
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (ActivityNotFoundException e) {
            wb.commands.run("workbench.action.files.openFolder");
        }
    }

    @Override
    public void pickVsix() {
        Intent i = new Intent(Build.VERSION.SDK_INT >= 19 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_VSIX);
        } catch (ActivityNotFoundException e) {
            wb.showMessage("No file picker");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VSIX && resultCode == RESULT_OK && data != null && data.getData() != null) {
            // Copy the picked package to the cache (off the UI thread: it may come from the network), then install it.
            final Uri picked = data.getData();
            wb.bg.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final java.io.File tmp = new java.io.File(getCacheDir(), "picked.vsix");
                        java.io.InputStream in = getContentResolver().openInputStream(picked);
                        if (in == null) throw new java.io.IOException("cannot open " + picked);
                        java.io.OutputStream os = new java.io.FileOutputStream(tmp);
                        try {
                            byte[] b = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(b)) > 0) os.write(b, 0, n);
                        } finally {
                            os.close();
                            in.close();
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing()) wb.installVsix(tmp);
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                wb.showMessage(e.getMessage() != null ? e.getMessage() : e.toString());
                            }
                        });
                    }
                }
            });
            return;
        }
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            if (Build.VERSION.SDK_INT >= 19) {
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    try {
                        getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored2) {
                    }
                }
            }
            wb.openUri(u);
        }
    }

    /** Storage access for folders: legacy permission up to Android 10, "All files access" from 11. */
    @Override
    public boolean ensureStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (com.disgusty.droid_codology.compat.Api30.isStorageManager()) return true;
            Skin.DialogSpec s = new Skin.DialogSpec();
            s.title = t("Open Folder");
            s.message = t("Storage permission is needed to open folders on your device.");
            s.positive = t("Grant");
            s.negative = t("Cancel");
            s.onPositive = new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                    } catch (ActivityNotFoundException e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }
            };
            s.neutral = t("App folder");
            s.onNeutral = new Runnable() {
                @Override
                public void run() {
                    wb.openFolder(new java.io.File(getFilesDir(), "projects"));
                }
            };
            wb.skin.dialog(s).show();
            return false;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            if (Api23.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) return true;
            Api23.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            return false;
        }
        return Environment.getExternalStorageState() != null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_PERM && results.length > 0 && results[0] == 0) {
            wb.commands.run("workbench.action.files.openFolder");
        }
    }
}
