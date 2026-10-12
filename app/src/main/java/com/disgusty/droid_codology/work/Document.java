package com.disgusty.droid_codology.work;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.disgusty.droid_codology.io.TextFiles;
import com.disgusty.droid_codology.lang.Highlighter;
import com.disgusty.droid_codology.lang.Language;
import com.disgusty.droid_codology.lang.Languages;
import com.disgusty.droid_codology.text.EditEngine;
import com.disgusty.droid_codology.text.TextBuffer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** An open file: its text, edit history, highlighting, encoding and where it is stored. */
public final class Document {
    /** Local file, or null for a content Uri / untitled buffer. */
    public File file;
    public Uri uri;
    public String name;
    public final EditEngine engine;
    public final TextBuffer text;
    public Highlighter highlighter;
    public final com.disgusty.droid_codology.text.Folding folding;
    public Language language;
    public String charset = "UTF-8";
    public boolean bom;
    public String eol = TextFiles.LF;
    public boolean mixedEol, binary, readOnly;
    /** Undo state id when last saved; differing means unsaved changes. */
    private int savedState;
    /** Last-modified time of the file when loaded/saved, to detect outside changes. */
    public long diskStamp;
    public long diskLength;
    // Per-document view state.
    public int scrollX, scrollY;
    public boolean pinned, preview;
    public int untitledIndex;
    /** Problems by source ("lsp", "ext:<collection>"). */
    public final java.util.LinkedHashMap<String, java.util.List<com.disgusty.droid_codology.editor.Diagnostic>> diagnostics =
            new java.util.LinkedHashMap<String, java.util.List<com.disgusty.droid_codology.editor.Diagnostic>>();

    public java.util.List<com.disgusty.droid_codology.editor.Diagnostic> allDiagnostics() {
        java.util.ArrayList<com.disgusty.droid_codology.editor.Diagnostic> all = new java.util.ArrayList<com.disgusty.droid_codology.editor.Diagnostic>();
        for (java.util.List<com.disgusty.droid_codology.editor.Diagnostic> l : diagnostics.values()) all.addAll(l);
        return all;
    }

    public Document(TextBuffer text, String name) {
        this.text = text;
        this.name = name;
        this.engine = new EditEngine(text);
        // Registered before any view layout, so hidden lines are updated first on edits.
        this.folding = new com.disgusty.droid_codology.text.Folding(text);
        setLanguage(Languages.forFile(name, firstLine(text)));
    }

    private static String firstLine(TextBuffer t) {
        int e = t.lineEnd(0);
        return t.substring(0, Math.min(e, 200));
    }

    public void setLanguage(Language l) {
        language = l;
        if (highlighter == null) highlighter = new Highlighter(text, l.lexer());
        else highlighter.setLexer(l.lexer());
        engine.lineComment = l.lineComment;
        engine.blockOpen = l.blockOpen;
        engine.blockClose = l.blockOpen != null ? l.blockClose : null;
        engine.pairOpen = l.pairOpen;
        engine.pairClose = l.pairClose;
        engine.indentAfter = l.indentAfter;
        folding.offSide = l.offSide;
        engine.autoCloseTags = l.id.equals("html") || l.id.equals("xml") || l.id.equals("php");
    }

    /** Encoding / line-ending changed: needs saving even though the text is the same. */
    public boolean metaDirty;

    public boolean isDirty() {
        return metaDirty || engine.undo.stateId() != savedState;
    }

    public void markSaved() {
        savedState = engine.undo.stateId();
        engine.undo.seal();
        metaDirty = false;
    }

    /** For hot exit: restores "unsaved" state after reloading a backup. */
    public void markUnsaved() {
        savedState = -1;
    }

    public boolean isUntitled() {
        return file == null && uri == null;
    }

    public String path() {
        if (file != null) return file.getAbsolutePath();
        if (uri != null) return uri.toString();
        return name;
    }

    /** Applies file metadata and the text's own indentation style. */
    void applyLoaded(TextFiles.Loaded l) {
        charset = l.charset;
        bom = l.bom;
        eol = l.eol;
        mixedEol = l.mixedEol;
        binary = l.binary;
        if (binary) readOnly = true;
        detectIndentation();
    }

    /** Guesses tabs vs spaces and the indent width from the first lines, like VS Code. */
    public void detectIndentation() {
        int tabs = 0, spaces = 0;
        int[] widths = new int[9];
        int prevSpaces = 0;
        int n = Math.min(text.lineCount(), 2000);
        for (int l = 0; l < n; l++) {
            int s = text.lineStart(l), e = text.lineEnd(l);
            if (s == e) continue;
            char c = text.charAt(s);
            if (c == '\t') {
                tabs++;
                prevSpaces = 0;
                continue;
            }
            int k = s;
            while (k < e && text.charAt(k) == ' ') k++;
            if (k == e) continue;
            int sp = k - s;
            if (sp > 0) spaces++;
            int d = Math.abs(sp - prevSpaces);
            if (d >= 2 && d <= 8) widths[d]++;
            prevSpaces = sp;
        }
        if (tabs == 0 && spaces == 0) {
            engine.insertSpaces = language.preferSpaces;
            engine.tabSize = language.defaultTabSize;
            return;
        }
        engine.insertSpaces = spaces >= tabs;
        if (engine.insertSpaces) {
            int best = language.defaultTabSize, bestN = 0;
            for (int w = 2; w <= 8; w++) {
                if (widths[w] > bestN) {
                    best = w;
                    bestN = widths[w];
                }
            }
            engine.tabSize = best;
        }
    }

    // ------------------------------------------------------------------ loading / saving

    public static Document open(Context ctx, File f, String charset) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            TextFiles.Loaded l = TextFiles.read(in, charset);
            Document d = new Document(new TextBuffer(l.chars, l.length), f.getName());
            d.file = f;
            d.applyLoaded(l);
            d.readOnly = d.readOnly || !f.canWrite();
            d.diskStamp = f.lastModified();
            d.diskLength = f.length();
            return d;
        } finally {
            in.close();
        }
    }

    public static Document open(Context ctx, Uri u, String charset) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        InputStream in = cr.openInputStream(u);
        if (in == null) throw new IOException("Cannot open " + u);
        try {
            TextFiles.Loaded l = TextFiles.read(in, charset);
            Document d = new Document(new TextBuffer(l.chars, l.length), displayName(ctx, u));
            d.uri = u;
            d.applyLoaded(l);
            return d;
        } finally {
            in.close();
        }
    }

    public static String displayName(Context ctx, Uri u) {
        if ("file".equals(u.getScheme())) return new File(u.getPath()).getName();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = u.getLastPathSegment();
        if (last == null) return "file";
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    /** Reloads the text from disk, keeping it undoable. */
    public void reload(Context ctx) throws IOException {
        TextFiles.Loaded l;
        if (file != null) {
            InputStream in = new FileInputStream(file);
            try {
                l = TextFiles.read(in, charset);
            } finally {
                in.close();
            }
            diskStamp = file.lastModified();
            diskLength = file.length();
        } else if (uri != null) {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("Cannot open " + uri);
            try {
                l = TextFiles.read(in, charset);
            } finally {
                in.close();
            }
        } else return;
        String s = new String(l.chars, 0, l.length);
        int caret = Math.min(engine.sel.caret(), s.length());
        engine.replaceAndSelect(0, text.length(), s, caret, caret);
        bom = l.bom;
        eol = l.eol;
        markSaved();
    }

    /** The text has characters the file's encoding can't store (they would turn into '?'). */
    public static final class Unencodable extends IOException {
        Unencodable(String charset) {
            super(charset);
        }
    }

    public void save(Context ctx) throws IOException {
        char[] chars = text.toCharArray();
        // Unicode encodings store everything (a lone surrogate becomes U+FFFD, as it should).
        if (!charset.toUpperCase(java.util.Locale.ROOT).startsWith("UTF") && !TextFiles.canEncode(chars, chars.length, charset)) {
            throw new Unencodable(charset);
        }
        if (file != null) {
            // Through a symlink, the link stays and its target is written.
            File target = file;
            try {
                target = file.getCanonicalFile();
            } catch (IOException ignored) {
            }
            // Write a sibling temp file, then rename over the original: a crash never leaves half a file.
            File dir = target.getParentFile();
            File tmp = new File(dir, "." + target.getName() + ".dcsave");
            boolean viaTemp = dir != null && dir.canWrite();
            OutputStream os = new FileOutputStream(viaTemp ? tmp : target);
            boolean written = false;
            try {
                TextFiles.write(os, chars, chars.length, charset, bom, eol);
                if (os instanceof FileOutputStream) ((FileOutputStream) os).getFD().sync();
                written = true;
            } finally {
                os.close();
                if (!written && viaTemp) tmp.delete();
            }
            if (viaTemp) {
                // A script stays runnable.
                if (target.canExecute()) tmp.setExecutable(true, false);
                if (!tmp.renameTo(target)) {
                    // Some filesystems (FAT sdcards) refuse rename-over.
                    target.delete();
                    if (!tmp.renameTo(target)) throw new IOException("Cannot replace " + target + "; your text is in " + tmp);
                }
            }
            diskStamp = file.lastModified();
            diskLength = file.length();
        } else if (uri != null) {
            OutputStream os;
            try {
                os = ctx.getContentResolver().openOutputStream(uri, "wt");
            } catch (RuntimeException e) {
                os = ctx.getContentResolver().openOutputStream(uri);
            }
            if (os == null) throw new IOException("Cannot write " + uri);
            try {
                TextFiles.write(os, chars, chars.length, charset, bom, eol);
            } finally {
                os.close();
            }
        } else {
            throw new IOException("No file");
        }
        markSaved();
    }

    /** True if the file changed on disk since we loaded or saved it. */
    public boolean changedOnDisk() {
        if (file == null || !file.exists()) return false;
        return file.lastModified() != diskStamp || file.length() != diskLength;
    }
}
