package com.disgusty.droid_codology.git;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeMap;

/** .git/index (versions 2 and 3; extensions are dropped on write). */
public final class GitIndex {
    public static final class Entry {
        public String path;
        public String id;
        public int mode;
        /** Last-modified time in milliseconds and size, to detect changes without hashing. */
        public long mtime;
        public long size;
        public int stage;
        int ctimeS, ctimeN, dev, ino, uid, gid;

        public String modeString() {
            return Integer.toOctalString(mode);
        }
    }

    /** Entries by path, sorted like git (byte order). */
    public final TreeMap<String, Entry> entries = new TreeMap<String, Entry>(new java.util.Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            return GitObject.compareBytes(a, b);
        }
    });
    /** mtime of the index file when read (for racy-clean detection). */
    public long fileTime;

    public static GitIndex read(File f) throws IOException {
        GitIndex ix = new GitIndex();
        if (!f.isFile()) return ix;
        ix.fileTime = f.lastModified();
        byte[] d = ObjectDb.readFile(f);
        if (d.length < 12 || d[0] != 'D' || d[1] != 'I' || d[2] != 'R' || d[3] != 'C') throw new IOException("bad index file");
        int version = ObjectDb.be32(d, 4);
        if (version != 2 && version != 3) throw new IOException("unsupported index version " + version);
        int n = ObjectDb.be32(d, 8);
        int p = 12;
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            int start = p;
            e.ctimeS = ObjectDb.be32(d, p);
            e.ctimeN = ObjectDb.be32(d, p + 4);
            long ms = ObjectDb.be32(d, p + 8) & 0xFFFFFFFFL;
            long ns = ObjectDb.be32(d, p + 12) & 0xFFFFFFFFL;
            e.mtime = ms * 1000 + ns / 1000000;
            e.dev = ObjectDb.be32(d, p + 16);
            e.ino = ObjectDb.be32(d, p + 20);
            e.mode = ObjectDb.be32(d, p + 24);
            e.uid = ObjectDb.be32(d, p + 28);
            e.gid = ObjectDb.be32(d, p + 32);
            e.size = ObjectDb.be32(d, p + 36) & 0xFFFFFFFFL;
            e.id = GitObject.hex(d, p + 40, 20);
            int flags = ((d[p + 60] & 0xFF) << 8) | (d[p + 61] & 0xFF);
            e.stage = (flags >> 12) & 3;
            p += 62;
            if ((flags & 0x4000) != 0) p += 2; // v3 extended flags
            int nul = p;
            while (d[nul] != 0) nul++;
            e.path = new String(d, p, nul - p, GitObject.UTF8);
            p = nul;
            int len = p - start;
            p = start + ((len + 8) & ~7);
            if (e.stage == 0 || !ix.entries.containsKey(e.path)) ix.entries.put(e.path, e);
        }
        return ix;
    }

    public void write(File f) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 + entries.size() * 80);
        bo.write(new byte[]{'D', 'I', 'R', 'C', 0, 0, 0, 2});
        ObjectDb.put32(bo, entries.size());
        for (Entry e : entries.values()) {
            byte[] name = e.path.getBytes(GitObject.UTF8);
            ObjectDb.put32(bo, e.ctimeS != 0 ? e.ctimeS : (int) (e.mtime / 1000));
            ObjectDb.put32(bo, e.ctimeN);
            ObjectDb.put32(bo, (int) (e.mtime / 1000));
            ObjectDb.put32(bo, (int) (e.mtime % 1000) * 1000000);
            ObjectDb.put32(bo, e.dev);
            ObjectDb.put32(bo, e.ino);
            ObjectDb.put32(bo, e.mode);
            ObjectDb.put32(bo, e.uid);
            ObjectDb.put32(bo, e.gid);
            ObjectDb.put32(bo, (int) e.size);
            bo.write(GitObject.unhex(e.id));
            int flags = Math.min(name.length, 0xFFF);
            bo.write(flags >> 8);
            bo.write(flags);
            bo.write(name);
            int len = 62 + name.length;
            int pad = ((len + 8) & ~7) - len;
            for (int i = 0; i < pad; i++) bo.write(0);
        }
        MessageDigest md = GitObject.sha1();
        md.update(bo.toByteArray());
        bo.write(md.digest());
        File tmp = new File(f.getPath() + ".lock");
        FileOutputStream os = new FileOutputStream(tmp);
        try {
            bo.writeTo(os);
        } finally {
            os.close();
        }
        if (f.exists() && !f.delete()) throw new IOException("can't replace index");
        if (!tmp.renameTo(f)) throw new IOException("can't write index");
        fileTime = f.lastModified();
    }

    public Entry entryFor(String path, String id, File file) {
        Entry e = new Entry();
        e.path = path;
        e.id = id;
        e.mode = file != null && file.canExecute() && !isWindows() ? 0100755 : 0100644;
        if (file != null) {
            e.mtime = file.lastModified();
            e.size = file.length();
        }
        return e;
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }

    /** Whether the file may differ from the entry (size/mtime changed, or racily clean). */
    public boolean maybeChanged(Entry e, File f) {
        if (f.length() != e.size) return true;
        long m = f.lastModified();
        if (m / 1000 != e.mtime / 1000) return true;
        return m / 1000 >= fileTime / 1000;
    }

    public ArrayList<String> paths() {
        return new ArrayList<String>(entries.keySet());
    }

    static ArrayList<String> sorted(java.util.Collection<String> c) {
        ArrayList<String> l = new ArrayList<String>(c);
        Collections.sort(l);
        return l;
    }

    static boolean equalIds(String a, String b) {
        return a != null && a.equals(b);
    }

    @SuppressWarnings("unused")
    private static String debug(byte[] b) {
        return Arrays.toString(b);
    }
}
