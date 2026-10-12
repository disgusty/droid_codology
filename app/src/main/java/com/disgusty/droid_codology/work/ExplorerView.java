package com.disgusty.droid_codology.work;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.disgusty.droid_codology.ui.Skin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/**
 * The Explorer view: the open folder as a lazily expanded tree (a flat ListView of visible rows,
 * so huge folders scroll smoothly), with VS Code's file operations in a long-press menu.
 */
final class ExplorerView extends LinearLayout {
    private final Workbench wb;
    private final Skin skin;
    private Workspace ws;
    private final HashSet<String> expanded = new HashSet<String>();
    private final ArrayList<Row> rows = new ArrayList<Row>();
    private final ListView list;
    private final Adapter adapter = new Adapter();
    private final LinearLayout emptyView;
    private String selectedPath;

    private static final class Row {
        final File file;
        final int depth;
        final boolean dir;

        Row(File f, int depth, boolean dir) {
            file = f;
            this.depth = depth;
            this.dir = dir;
        }
    }

    ExplorerView(Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        setOrientation(VERTICAL);
        setBackgroundColor(skin.sideBarBg);
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("new_file", t("New File..."), new OnClickListener() {
            @Override
            public void onClick(View v) {
                newEntry(targetDir(), false);
            }
        }));
        acts.add(new Skin.Action("new_folder", t("New Folder..."), new OnClickListener() {
            @Override
            public void onClick(View v) {
                newEntry(targetDir(), true);
            }
        }));
        acts.add(new Skin.Action("refresh", t("Refresh Explorer"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }));
        acts.add(new Skin.Action("collapse_all", t("Collapse Folders in Explorer"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded.clear();
                rebuild();
            }
        }));
        addView(skin.sideBarHeader(t("Explorer"), acts));
        list = new ListView(wb.activity);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setFastScrollEnabled(true);
        list.setAdapter(adapter);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        emptyView = new LinearLayout(wb.activity);
        emptyView.setOrientation(VERTICAL);
        emptyView.setPadding(skin.dp(18), skin.dp(8), skin.dp(18), skin.dp(8));
        TextView msg = skin.label(t("You have not yet opened a folder."));
        emptyView.addView(msg);
        View gap = new View(wb.activity);
        emptyView.addView(gap, new LayoutParams(1, skin.dp(12)));
        emptyView.addView(skin.button(t("Open Folder"), true, new OnClickListener() {
            @Override
            public void onClick(View v) {
                wb.commands.run("workbench.action.files.openFolder");
            }
        }), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(emptyView, 1);
    }

    void setWorkspace(Workspace w) {
        ws = w;
        expanded.clear();
        if (w != null) expanded.add(w.root.getAbsolutePath());
        rebuild();
    }

    void refresh() {
        rebuild();
    }

    private File targetDir() {
        if (ws == null) return null;
        if (selectedPath != null) {
            File f = new File(selectedPath);
            if (f.isDirectory()) return f;
            if (f.getParentFile() != null) return f.getParentFile();
        }
        return ws.root;
    }

    /** Flattens the expanded tree into rows. */
    private void rebuild() {
        rows.clear();
        emptyView.setVisibility(ws == null ? VISIBLE : GONE);
        list.setVisibility(ws == null ? GONE : VISIBLE);
        if (ws != null) {
            rows.add(new Row(ws.root, 0, true));
            if (expanded.contains(ws.root.getAbsolutePath())) addChildren(ws.root, 1);
        }
        adapter.notifyDataSetChanged();
    }

    private void addChildren(File dir, int depth) {
        for (File f : ws.list(dir)) {
            boolean d = f.isDirectory();
            rows.add(new Row(f, depth, d));
            if (d && expanded.contains(f.getAbsolutePath())) addChildren(f, depth + 1);
        }
    }

    /** Expands the parents of {@code f}, selects and scrolls to it. */
    void reveal(File f) {
        if (ws == null || !ws.contains(f)) return;
        File p = f.getParentFile();
        while (p != null && ws.contains(p)) {
            expanded.add(p.getAbsolutePath());
            p = p.getParentFile();
        }
        if (f.isDirectory()) expanded.add(f.getAbsolutePath());
        selectedPath = f.getAbsolutePath();
        rebuild();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).file.getAbsolutePath().equals(selectedPath)) {
                final int pos = i;
                list.post(new Runnable() {
                    @Override
                    public void run() {
                        list.setSelectionFromTop(pos, list.getHeight() / 3);
                    }
                });
                break;
            }
        }
    }

    private void onRowClick(Row r) {
        selectedPath = r.file.getAbsolutePath();
        if (r.dir) {
            String p = r.file.getAbsolutePath();
            if (!expanded.remove(p)) expanded.add(p);
            rebuild();
        } else {
            wb.openFile(r.file, true, -1, 0, 0);
            adapter.notifyDataSetChanged();
        }
    }

    private void onRowLongClick(View anchor, final Row r) {
        selectedPath = r.file.getAbsolutePath();
        adapter.notifyDataSetChanged();
        final File dir = r.dir ? r.file : r.file.getParentFile();
        ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
        if (!r.dir) {
            m.add(new Skin.MenuItem(t("Keep Open"), new Runnable() {
                @Override
                public void run() {
                    wb.openFile(r.file, false, -1, 0, 0);
                }
            }));
        }
        m.add(new Skin.MenuItem(t("New File..."), new Runnable() {
            @Override
            public void run() {
                newEntry(dir, false);
            }
        }).separator());
        m.add(new Skin.MenuItem(t("New Folder..."), new Runnable() {
            @Override
            public void run() {
                newEntry(dir, true);
            }
        }));
        if (r.dir) {
            m.add(new Skin.MenuItem(t("Find in Folder..."), new Runnable() {
                @Override
                public void run() {
                    wb.commands.run("workbench.view.search");
                }
            }));
        }
        m.add(new Skin.MenuItem(t("Copy Path"), new Runnable() {
            @Override
            public void run() {
                wb.copyText(r.file.getAbsolutePath());
            }
        }).separator());
        m.add(new Skin.MenuItem(t("Copy Relative Path"), new Runnable() {
            @Override
            public void run() {
                wb.copyText(ws.relative(r.file));
            }
        }));
        boolean isRoot = ws != null && r.file.equals(ws.root);
        if (!isRoot) {
            if (!r.dir) {
                m.add(new Skin.MenuItem(t("Duplicate"), new Runnable() {
                    @Override
                    public void run() {
                        duplicate(r.file);
                    }
                }).separator());
            }
            m.add(new Skin.MenuItem(t("Rename..."), new Runnable() {
                @Override
                public void run() {
                    rename(r.file);
                }
            }).key("F2"));
            m.add(new Skin.MenuItem(t("Delete"), new Runnable() {
                @Override
                public void run() {
                    delete(r.file);
                }
            }).key("Delete"));
        } else {
            m.add(new Skin.MenuItem(t("Close Folder"), new Runnable() {
                @Override
                public void run() {
                    wb.closeFolder();
                }
            }).separator());
        }
        skin.popup(anchor, m);
    }

    private void newEntry(final File dir, final boolean folder) {
        if (dir == null) {
            if (!folder) wb.newUntitled(null);
            return;
        }
        wb.prompt(folder ? t("New Folder") : t("New File"), folder ? t("Type the name of a folder") : t("Type the name of a file"), null, new Workbench.TextCallback() {
            @Override
            public void onText(String name) {
                // "a/b/c.txt" creates the intermediate folders, like VS Code.
                File f = new File(dir, name);
                if (f.exists()) {
                    wb.showMessage(f("A file or folder {0} already exists at this location.", name));
                    return;
                }
                try {
                    if (folder) {
                        if (!f.mkdirs()) throw new IOException("mkdir failed");
                    } else {
                        File parent = f.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed");
                        if (!f.createNewFile()) throw new IOException("create failed");
                    }
                } catch (IOException e) {
                    wb.showMessage(f("Unable to open '{0}': {1}", name, e.getMessage()));
                    return;
                }
                expanded.add(dir.getAbsolutePath());
                ws.invalidateIndex();
                reveal(f);
                if (!folder) wb.openFile(f, false, -1, 0, 0);
            }
        });
    }

    private void rename(final File f) {
        wb.prompt(t("Rename"), null, f.getName(), new Workbench.TextCallback() {
            @Override
            public void onText(String name) {
                if (name.equals(f.getName())) return;
                if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) {
                    wb.showMessage(f("'{0}' is not a valid file name.", name));
                    return;
                }
                File target = new File(f.getParentFile(), name);
                boolean caseOnly = name.equalsIgnoreCase(f.getName());
                // A case-only rename on a case-sensitive disk may still hit a different file with that name.
                boolean taken = false;
                String[] siblings = f.getParentFile().list();
                if (siblings != null) for (String s : siblings) if (s.equals(name)) taken = true;
                if (taken || target.exists() && !caseOnly) {
                    wb.showMessage(f("A file or folder {0} already exists at this location.", name));
                    return;
                }
                boolean renamed;
                if (caseOnly) {
                    // Through a temporary name: case-insensitive storage won't rename Foo to foo directly.
                    File tmp = new File(f.getParentFile(), "." + f.getName() + ".dcrename");
                    renamed = !tmp.exists() && f.renameTo(tmp);
                    if (renamed && !tmp.renameTo(target)) {
                        tmp.renameTo(f);
                        renamed = false;
                    }
                } else renamed = f.renameTo(target);
                if (!renamed) {
                    wb.showMessage(f("Unable to open '{0}': {1}", name, "rename failed"));
                    return;
                }
                // Open editors follow the rename.
                String old = f.getAbsolutePath();
                for (Document d : wb.documents()) {
                    if (d.file == null) continue;
                    String p = d.file.getAbsolutePath();
                    if (p.equals(old) || p.startsWith(old + "/")) {
                        d.file = new File(target.getAbsolutePath() + p.substring(old.length()));
                        d.name = d.file.getName();
                    }
                }
                if (expanded.remove(old)) expanded.add(target.getAbsolutePath());
                ws.invalidateIndex();
                wb.refreshTabs();
                reveal(target);
            }
        });
    }

    private void duplicate(File f) {
        File copy = Workspace.uniqueSibling(f.getParentFile(), f.getName());
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.FileOutputStream out = new java.io.FileOutputStream(copy);
            try {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally {
                in.close();
                out.close();
            }
            ws.invalidateIndex();
            reveal(copy);
        } catch (IOException e) {
            wb.showMessage(String.valueOf(e.getMessage()));
        }
    }

    private void delete(final File f) {
        String msg = f.isDirectory() ? f("Are you sure you want to delete '{0}' and its contents?", f.getName())
                : f("Are you sure you want to delete '{0}'?", f.getName());
        wb.confirm(msg, t("This action is irreversible!"), t("Delete"), new Runnable() {
            @Override
            public void run() {
                if (!Workspace.deleteRecursive(f)) wb.showMessage(f("Unable to open '{0}': {1}", f.getName(), "delete failed"));
                // Close editors of deleted files (they keep their text if dirty).
                String p = f.getAbsolutePath();
                List<Document> toClose = new ArrayList<Document>();
                for (Document d : wb.documents()) {
                    if (d.file != null && (d.file.getAbsolutePath().equals(p) || d.file.getAbsolutePath().startsWith(p + "/")) && !d.isDirty()) toClose.add(d);
                }
                for (Document d : toClose) wb.close(d, null);
                ws.invalidateIndex();
                rebuild();
            }
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            final Row r = rows.get(i);
            boolean isRoot = ws != null && r.file.equals(ws.root);
            View v;
            if (isRoot) {
                v = skin.sectionHeader(ws.name(), expanded.contains(r.file.getAbsolutePath()), null);
            } else {
                Boolean exp = r.dir ? expanded.contains(r.file.getAbsolutePath()) : null;
                LinearLayout row = skin.treeRow(r.depth - 1, exp, FileIcons.of(skin, r.file, Boolean.TRUE.equals(exp), 16), r.file.getName(), null, null);
                Document a = wb.activeDocument();
                boolean active = r.file.getAbsolutePath().equals(selectedPath) || (a != null && r.file.equals(a.file));
                if (active) row.setBackgroundDrawable(skin.listSelector(true));
                v = row;
            }
            v.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onRowClick(r);
                }
            });
            v.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    onRowLongClick(v, r);
                    return true;
                }
            });
            v.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
    }
}
