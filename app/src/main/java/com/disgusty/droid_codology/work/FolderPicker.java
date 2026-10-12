package com.disgusty.droid_codology.work;

import android.app.Dialog;
import android.content.Context;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.disgusty.droid_codology.ui.Skin;

import java.io.File;
import java.util.ArrayList;

import static com.disgusty.droid_codology.work.I18n.t;

/**
 * Built-in folder browser (works on every Android version, unlike the system pickers): storage
 * roots, folder navigation, "new folder", and an optional file name for Save As.
 */
final class FolderPicker {
    interface Callback {
        void picked(File dir, String fileName);
    }

    private final Workbench wb;
    private final Skin skin;
    private File current;
    private LinearLayout list;
    private TextView pathView;
    private EditText nameInput;

    private FolderPicker(Workbench wb) {
        this.wb = wb;
        this.skin = wb.skin;
    }

    static void pick(Workbench wb, String title, File start, String fileName, final Callback cb) {
        if (wb.activity instanceof StorageAccessBridge && !((StorageAccessBridge) wb.activity).ensureStorage()) return;
        new FolderPicker(wb).show(title, start, fileName, cb);
    }

    static File[] roots(Context ctx) {
        ArrayList<File> r = new ArrayList<File>();
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) r.add(ext);
        File projects = new File(ctx.getFilesDir(), "projects");
        if (!projects.exists()) projects.mkdirs();
        r.add(projects);
        // Removable cards: getExternalFilesDirs()[1..] are on them; walk up to the card root.
        File[] dirs = android.os.Build.VERSION.SDK_INT >= 19 ? ctx.getExternalFilesDirs(null) : new File[0];
        for (int i = 1; i < dirs.length; i++) {
            File d = dirs[i];
            if (d == null) continue;
            String p = d.getAbsolutePath();
            int a = p.indexOf("/Android/");
            if (a > 0) r.add(new File(p.substring(0, a)));
        }
        r.add(new File("/"));
        return r.toArray(new File[r.size()]);
    }

    private void show(String title, File start, final String fileName, final Callback cb) {
        LinearLayout content = new LinearLayout(wb.activity);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rootsRow = new LinearLayout(wb.activity);
        File[] roots = roots(wb.activity);
        String[] names = {t("Internal storage"), t("App folder")};
        for (int i = 0; i < roots.length; i++) {
            final File r = roots[i];
            String label = i < 2 ? names[i] : r.getPath().equals("/") ? t("Root") : t("SD card");
            TextView b = skin.textButton(label, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    navigate(r);
                }
            });
            rootsRow.addView(b);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(wb.activity);
        hs.addView(rootsRow);
        content.addView(hs);
        pathView = skin.caption("");
        pathView.setPadding(0, skin.dp(4), 0, skin.dp(4));
        content.addView(pathView);
        ScrollView sv = new ScrollView(wb.activity);
        list = new LinearLayout(wb.activity);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        int h = (int) (wb.activity.getResources().getDisplayMetrics().heightPixels * 0.45f);
        content.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        content.addView(skin.textButton("+ " + t("New Folder..."), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.prompt(t("New Folder"), t("Type the name of a folder"), null, new Workbench.TextCallback() {
                    @Override
                    public void onText(String s) {
                        File f = new File(current, s);
                        if (f.mkdirs()) navigate(f);
                    }
                });
            }
        }));
        if (fileName != null) {
            nameInput = skin.input(t("File name"));
            nameInput.setText(fileName);
            content.addView(nameInput);
        }
        Skin.DialogSpec s = new Skin.DialogSpec();
        s.title = title;
        s.content = content;
        s.positive = fileName != null ? t("Save") : t("Select Folder");
        s.negative = t("Cancel");
        s.fullWidth = true;
        s.onPositive = new Runnable() {
            @Override
            public void run() {
                String n = nameInput == null ? null : nameInput.getText().toString().trim();
                if (nameInput != null && (n == null || n.isEmpty())) return;
                cb.picked(current, n);
            }
        };
        Dialog d = skin.dialog(s);
        File first = start != null && start.isDirectory() ? start : wb.prefs.str("state.lastPickerDir", null) != null
                ? new File(wb.prefs.str("state.lastPickerDir", "/")) : roots[0];
        if (!first.isDirectory()) first = roots[0];
        navigate(first);
        d.show();
    }

    private void navigate(File dir) {
        current = dir;
        wb.prefs.put("state.lastPickerDir", dir.getAbsolutePath());
        pathView.setText(dir.getAbsolutePath());
        list.removeAllViews();
        final File parent = dir.getParentFile();
        if (parent != null) {
            LinearLayout up = skin.treeRow(0, null, skin.icon("arrow_up", skin.descriptionFg, 16), "..", null, null);
            up.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    navigate(parent);
                }
            });
            list.addView(up);
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            list.addView(skin.caption("  —"));
            return;
        }
        java.util.Arrays.sort(fs, Workspace.ORDER);
        for (final File f : fs) {
            if (f.getName().startsWith(".") && !wb.prefs.b(Prefs.SHOW_HIDDEN, false)) continue;
            LinearLayout row = skin.treeRow(0, null, FileIcons.of(skin, f, false, 16), f.getName(), null, null);
            if (f.isDirectory()) {
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        navigate(f);
                    }
                });
            } else {
                row.setAlpha(0.55f);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (nameInput != null) nameInput.setText(f.getName());
                    }
                });
            }
            list.addView(row);
        }
    }
}
