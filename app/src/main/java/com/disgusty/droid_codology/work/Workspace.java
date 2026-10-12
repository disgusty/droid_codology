package com.disgusty.droid_codology.work;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** The open folder: listing with exclusions, a file index for Quick Open, and file operations. */
public final class Workspace {
    public final File root;
    public boolean showHidden;
    /** Folder names never descended into by search / quick open (VS Code's search.exclude defaults). */
    public final HashSet<String> excludedDirs = new HashSet<String>(Arrays.asList(
            ".git", ".svn", ".hg", "node_modules", "bower_components", ".gradle", "build", ".idea", "__pycache__",
            ".venv", "venv", "target", "dist", ".next", ".dart_tool", "Pods", ".DS_Store"));

    private volatile List<File> index;
    private volatile boolean indexing;

    public Workspace(File root) {
        this.root = root;
    }

    public String name() {
        return root.getName().isEmpty() ? root.getPath() : root.getName();
    }

    /** Sorted children: folders first, then files, case-insensitive (VS Code's default order). */
    public File[] list(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return new File[0];
        ArrayList<File> out = new ArrayList<File>(fs.length);
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".dcsave")) continue;
            if (!showHidden && n.startsWith(".") && !n.equals(".github") && !n.equals(".vscode") && !n.equals(".gitignore")
                    && !n.equals(".editorconfig") && !n.equals(".env")) continue;
            out.add(f);
        }
        File[] arr = out.toArray(new File[out.size()]);
        Arrays.sort(arr, ORDER);
        return arr;
    }

    public static final Comparator<File> ORDER = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            boolean da = a.isDirectory(), db = b.isDirectory();
            if (da != db) return da ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };

    public String relative(File f) {
        String r = root.getAbsolutePath(), p = f.getAbsolutePath();
        if (p.startsWith(r)) {
            String s = p.substring(r.length());
            while (s.startsWith("/")) s = s.substring(1);
            return s.isEmpty() ? f.getName() : s;
        }
        return p;
    }

    public boolean contains(File f) {
        String r = root.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.equals(r) || p.startsWith(r.endsWith("/") ? r : r + "/");
    }

    // ------------------------------------------------------------------ index for Quick Open / search

    public interface FileVisitor {
        /** Return false to stop. */
        boolean visit(File f);
    }

    /** Walks every non-excluded file (iteratively, so deep trees can't overflow the stack). */
    public void walk(FileVisitor v, int maxFiles) {
        ArrayList<File> stack = new ArrayList<File>();
        stack.add(root);
        int n = 0;
        while (!stack.isEmpty()) {
            File dir = stack.remove(stack.size() - 1);
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            Arrays.sort(fs, ORDER);
            for (int i = fs.length - 1; i >= 0; i--) {
                File f = fs[i];
                String name = f.getName();
                if (f.isDirectory()) {
                    if (excludedDirs.contains(name)) continue;
                    if (name.startsWith(".") && !name.equals(".github") && !name.equals(".vscode")) continue;
                    stack.add(f);
                }
            }
            for (File f : fs) {
                if (f.isDirectory()) continue;
                if (f.getName().endsWith(".dcsave")) continue;
                if (!v.visit(f)) return;
                if (++n >= maxFiles) return;
            }
            if (Thread.currentThread().isInterrupted()) return;
        }
    }

    public List<File> index() {
        return index;
    }

    public void invalidateIndex() {
        index = null;
    }

    /** Builds the file index on the calling (background) thread. */
    public List<File> buildIndex() {
        if (index != null) return index;
        if (indexing) return null;
        indexing = true;
        final ArrayList<File> out = new ArrayList<File>();
        walk(new FileVisitor() {
            @Override
            public boolean visit(File f) {
                out.add(f);
                return true;
            }
        }, 100000);
        index = out;
        indexing = false;
        return out;
    }

    // ------------------------------------------------------------------ fuzzy matching (Quick Open)

    /**
     * Scores {@code text} against a fuzzy {@code query} (all query chars in order). Higher is
     * better; -1 = no match. Rewards consecutive runs, word/segment starts and file-name hits.
     */
    public static int fuzzyScore(String query, String text, int nameStart) {
        if (query.isEmpty()) return 0;
        String q = query.toLowerCase(Locale.ROOT), t = text.toLowerCase(Locale.ROOT);
        int score = 0, ti = 0, run = 0;
        for (int qi = 0; qi < q.length(); qi++) {
            char c = q.charAt(qi);
            if (c == ' ') continue;
            int found = t.indexOf(c, ti);
            if (found < 0) return -1;
            boolean consecutive = found == ti && qi > 0;
            run = consecutive ? run + 1 : 0;
            score += 1 + run * 5;
            if (found >= nameStart) score += 3;
            if (found == 0 || "/\\_-. ".indexOf(t.charAt(found - 1)) >= 0) score += 8;
            else if (Character.isUpperCase(text.charAt(found)) && !Character.isUpperCase(text.charAt(found - 1))) score += 6;
            ti = found + 1;
        }
        // Prefer shorter paths and exact name prefixes.
        score -= text.length() / 8;
        if (t.startsWith(q, nameStart)) score += 20;
        return score;
    }

    // ------------------------------------------------------------------ file operations

    /** Deletes a file or folder tree; a symlink is removed itself, never the folder it points to. */
    public static boolean deleteRecursive(File f) {
        if (f.isDirectory() && !isSymlink(f)) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) if (!deleteRecursive(c)) return false;
        }
        return f.delete() || !f.exists();
    }

    /** True for a symbolic link (judged by where it resolves; no java.nio.file before Android 8). */
    public static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            File inCanonicalParent = parent == null ? f.getAbsoluteFile() : new File(parent.getCanonicalFile(), f.getName());
            return !inCanonicalParent.getCanonicalFile().equals(inCanonicalParent.getAbsoluteFile());
        } catch (IOException e) {
            return true; // unsure: don't descend
        }
    }

    /** "name.ext" → "name copy.ext", "name copy 2.ext"... (VS Code duplicate naming). */
    public static File uniqueSibling(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + (i == 1 ? " copy" : " copy " + i) + ext);
            if (!f.exists()) return f;
        }
    }
}
