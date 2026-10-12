package com.disgusty.droid_codology.work;

import android.net.Uri;

import com.disgusty.droid_codology.io.TextFiles;
import com.disgusty.droid_codology.lang.Languages;
import com.disgusty.droid_codology.text.TextBuffer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Hot exit (VS Code's files.hotExit): open editors, their carets and unsaved text survive the app
 * being closed or killed. Unsaved text is backed up as UTF-8 in the app's private storage.
 */
final class Session {
    private Session() {
    }

    private static File dir(Workbench wb) {
        File d = new File(wb.activity.getFilesDir(), "session");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Light save (no backups) used when rebuilding the UI. */
    static void saveState(Workbench wb) {
        // Document objects survive a rebuild in memory; nothing to do.
    }

    static void save(Workbench wb) {
        if (!wb.prefs.b(Prefs.HOT_EXIT, true)) return;
        File dir = dir(wb);
        try {
            JSONObject root = new JSONObject();
            root.put("untitled", wb.nextUntitled());
            JSONArray arr = new JSONArray();
            ArrayList<String> keep = new ArrayList<String>();
            Document active = wb.activeDocument();
            // New names on every save: the previous session.json keeps pointing at the previous
            // backups until the new one is in place, so a kill mid-save never mixes up texts.
            String gen = Long.toString(System.currentTimeMillis(), 36);
            int i = 0;
            for (Document d : wb.documents()) {
                JSONObject o = new JSONObject();
                if (d.file != null) o.put("file", d.file.getAbsolutePath());
                else if (d.uri != null) o.put("uri", d.uri.toString());
                o.put("name", d.name);
                o.put("lang", d.language.id);
                o.put("charset", d.charset);
                o.put("bom", d.bom);
                o.put("eol", d.eol);
                o.put("anchor", d.engine.sel.anchor(d.engine.sel.primary()));
                o.put("head", d.engine.sel.head(d.engine.sel.primary()));
                o.put("scrollY", d == active ? -1 : d.scrollY);
                o.put("tabSize", d.engine.tabSize);
                o.put("spaces", d.engine.insertSpaces);
                o.put("untitledIndex", d.untitledIndex);
                if (d.isDirty()) {
                    String backup = "backup-" + gen + "-" + i + ".txt";
                    char[] c = d.text.toCharArray();
                    OutputStream os = new FileOutputStream(new File(dir, backup));
                    try {
                        TextFiles.write(os, c, c.length, "UTF-8", false, TextFiles.LF);
                    } finally {
                        os.close();
                    }
                    o.put("backup", backup);
                    keep.add(backup);
                }
                if (d == active) root.put("active", i);
                arr.put(o);
                i++;
            }
            for (File f : wb.deferredLoading) {
                JSONObject o = new JSONObject();
                o.put("file", f.getAbsolutePath());
                o.put("name", f.getName());
                arr.put(o);
            }
            root.put("docs", arr);
            if (wb.workspace != null) root.put("folder", wb.workspace.root.getAbsolutePath());
            File tmp = new File(dir, "session.json.tmp");
            OutputStream os = new FileOutputStream(tmp);
            try {
                os.write(root.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
            if (!tmp.renameTo(new File(dir, "session.json"))) return; // keep the old backups it names
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (f.getName().startsWith("backup-") && !keep.contains(f.getName())) f.delete();
        } catch (Exception ignored) {
            // Best effort: never block leaving the app.
        }
    }

    static void restore(Workbench wb) {
        File dir = dir(wb);
        File f = new File(dir, "session.json");
        String folder = wb.prefs.str(Prefs.FOLDER, null);
        if (folder != null && new File(folder).isDirectory()) wb.openFolderQuiet(new File(folder));
        if (!f.exists() || !wb.prefs.b(Prefs.HOT_EXIT, true)) return;
        try {
            InputStream in = new FileInputStream(f);
            String json;
            try {
                json = new String(TextFiles.readAll(in), "UTF-8");
            } finally {
                in.close();
            }
            JSONObject root = new JSONObject(json);
            wb.setNextUntitled(root.optInt("untitled", 1));
            JSONArray arr = root.getJSONArray("docs");
            int activeIdx = root.optInt("active", -1);
            Document activeDoc = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Document d = null;
                String backup = o.optString("backup", null);
                try {
                    if (backup != null && new File(dir, backup).exists()) {
                        InputStream bin = new FileInputStream(new File(dir, backup));
                        TextFiles.Loaded l;
                        try {
                            l = TextFiles.read(bin, "UTF-8");
                        } finally {
                            bin.close();
                        }
                        d = new Document(new TextBuffer(l.chars, l.length), o.optString("name", "Untitled"));
                        if (o.has("file")) d.file = new File(o.getString("file"));
                        else if (o.has("uri")) d.uri = Uri.parse(o.getString("uri"));
                        d.charset = o.optString("charset", "UTF-8");
                        d.bom = o.optBoolean("bom", false);
                        d.eol = o.optString("eol", TextFiles.LF);
                        if (d.file != null) {
                            d.diskStamp = d.file.lastModified();
                            d.diskLength = d.file.length();
                        }
                        d.markUnsaved();
                    } else if (o.has("file")) {
                        File df = new File(o.getString("file"));
                        if (!df.exists()) continue;
                        // Big background tabs load after the window is up, so startup stays instant.
                        if (df.length() > 512 * 1024 && i != activeIdx) {
                            wb.deferOpen(df);
                            continue;
                        }
                        d = Document.open(wb.activity, df, o.optString("charset", null));
                    } else if (o.has("uri")) {
                        d = Document.open(wb.activity, Uri.parse(o.getString("uri")), o.optString("charset", null));
                    }
                } catch (IOException | SecurityException e) {
                    continue;
                }
                if (d == null) continue;
                d.setLanguage(Languages.find(o.optString("lang", d.language.id)));
                d.engine.tabSize = o.optInt("tabSize", d.engine.tabSize);
                d.engine.insertSpaces = o.optBoolean("spaces", d.engine.insertSpaces);
                d.untitledIndex = o.optInt("untitledIndex", 0);
                int len = d.text.length();
                d.engine.setSelection(Math.min(len, o.optInt("anchor", 0)), Math.min(len, o.optInt("head", 0)));
                d.scrollY = Math.max(0, o.optInt("scrollY", 0));
                wb.docsInternal().add(d);
                if (i == activeIdx) activeDoc = d;
            }
            if (activeDoc == null && !wb.documents().isEmpty()) activeDoc = wb.documents().get(0);
            wb.setActiveInternal(activeDoc);
            if (activeDoc != null) activeDoc.scrollY = -1;
        } catch (Exception ignored) {
        }
    }
}
