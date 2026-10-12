package com.disgusty.droid_codology.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Unpacks a tar stream (ustar, with PAX and GNU long names) into a folder: files, folders,
 * symlinks and hard links, with their permission bits. Nothing may land outside the folder.
 */
public final class Tar {
    private Tar() {
    }

    /** How links and modes are made; the app uses the native terminal helper (works before Android 5). */
    public interface Fs {
        /** symlink(2); 0 or errno. */
        int symlink(String target, String path);

        /** chmod(2); 0 or errno. */
        int chmod(String path, int mode);
    }

    public interface Progress {
        void onEntry(String name, long bytesRead);
    }

    public static void extract(InputStream in, File dest, Fs fs, Progress progress) throws IOException {
        String root = dest.getCanonicalPath();
        byte[] header = new byte[512];
        byte[] buf = new byte[64 * 1024];
        long read = 0;
        String longName = null, longLink = null;
        List<String[]> hardLinks = new ArrayList<String[]>();
        while (true) {
            if (!readFully(in, header)) break;
            read += 512;
            if (allZero(header)) break;
            String name = longName != null ? longName : entryName(header);
            String link = longLink != null ? longLink : field(header, 157, 100);
            longName = null;
            longLink = null;
            char type = (char) header[156];
            long size = octal(header, 124, 12);
            int mode = (int) octal(header, 100, 8) & 07777;
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                byte[] data = readData(in, size);
                read += padded(size);
                if (type == 'L') longName = cString(data);
                else if (type == 'K') longLink = cString(data);
                else if (type == 'x') {
                    String[] pax = pax(data);
                    if (pax[0] != null) longName = pax[0];
                    if (pax[1] != null) longLink = pax[1];
                }
                continue;
            }
            File out = safe(dest, root, name);
            if (out == null) {
                skip(in, padded(size));
                read += padded(size);
                continue;
            }
            if (progress != null) progress.onEntry(name, read);
            switch (type) {
                case '5':
                    out.mkdirs();
                    fs.chmod(out.getPath(), mode == 0 ? 0755 : mode | 0700);
                    break;
                case '2':
                    mkParent(out);
                    out.delete();
                    int err = fs.symlink(link, out.getPath());
                    if (err != 0) throw new IOException("symlink " + name + " -> " + link + ": errno " + err);
                    break;
                case '1':
                    hardLinks.add(new String[]{name, link});
                    break;
                case '0':
                case '\0':
                case '7': {
                    mkParent(out);
                    OutputStream os = new FileOutputStream(out);
                    try {
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("truncated archive at " + name);
                            os.write(buf, 0, n);
                            left -= n;
                        }
                    } finally {
                        os.close();
                    }
                    skip(in, padded(size) - size);
                    read += padded(size);
                    fs.chmod(out.getPath(), mode == 0 ? 0644 : mode | 0600);
                    continue;
                }
                default:
                    // devices, fifos: a user-space Linux has no use for them here
                    break;
            }
            if (type != '0' && type != '\0' && type != '7') {
                skip(in, padded(size));
                read += padded(size);
            }
        }
        // Hard links last (their targets exist by now): copies, as app storage may not allow links.
        for (String[] h : hardLinks) {
            File to = safe(dest, root, h[0]), from = safe(dest, root, h[1]);
            if (to == null || from == null || !from.isFile()) continue;
            mkParent(to);
            copy(from, to);
            fs.chmod(to.getPath(), from.canExecute() ? 0755 : 0644);
        }
    }

    /** The entry's path under {@code dest}, or null if it would leave it ("../", absolute tricks). */
    static File safe(File dest, String root, String name) throws IOException {
        String n = name;
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        if (n.isEmpty() || n.equals(".")) return null;
        for (String part : n.split("/")) if (part.equals("..")) return null;
        File f = new File(dest, n);
        // Canonical form of the parent only: the entry itself may be a symlink pointing anywhere.
        File parent = f.getParentFile();
        String p = parent == null ? root : parent.getCanonicalPath();
        if (!(p.equals(root) || p.startsWith(root + File.separator))) return null;
        return new File(p, f.getName());
    }

    private static String entryName(byte[] h) {
        String name = field(h, 0, 100);
        String magic = field(h, 257, 6);
        if (magic.startsWith("ustar")) {
            String prefix = field(h, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
        }
        return name;
    }

    /** PAX "path" and "linkpath" records. */
    static String[] pax(byte[] data) throws IOException {
        String[] out = new String[2];
        int i = 0;
        while (i < data.length) {
            int sp = i;
            while (sp < data.length && data[sp] != ' ') sp++;
            if (sp >= data.length) break;
            int len;
            try {
                len = Integer.parseInt(new String(data, i, sp - i, "UTF-8"));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > data.length) break;
            String rec = new String(data, sp + 1, i + len - sp - 2, "UTF-8"); // without the trailing \n
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String k = rec.substring(0, eq), v = rec.substring(eq + 1);
                if (k.equals("path")) out[0] = v;
                else if (k.equals("linkpath")) out[1] = v;
            }
            i += len;
        }
        return out;
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        try {
            return new String(h, off, end - off, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String cString(byte[] d) throws IOException {
        int end = 0;
        while (end < d.length && d[end] != 0) end++;
        return new String(d, 0, end, "UTF-8");
    }

    static long octal(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) {
            // GNU base-256 for big sizes
            long v = 0;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (h[i] & 0xFF);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = h[i];
            if (c == 0 || c == ' ') {
                if (v > 0) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static long padded(long size) {
        return (size + 511) / 512 * 512;
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n < 0) {
                if (got == 0) return false;
                throw new IOException("truncated archive");
            }
            got += n;
        }
        return true;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > 1 << 20) throw new IOException("header record too large");
        byte[] d = new byte[(int) size];
        if (size > 0 && !readFully(in, d)) throw new IOException("truncated archive");
        skip(in, padded(size) - size);
        return d;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] b = new byte[8192];
        while (n > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, n));
            if (r < 0) throw new IOException("truncated archive");
            n -= r;
        }
    }

    private static void mkParent(File f) {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream os = new FileOutputStream(to);
            try {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
    }
}
