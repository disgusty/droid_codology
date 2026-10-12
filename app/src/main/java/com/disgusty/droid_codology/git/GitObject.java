package com.disgusty.droid_codology.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Git object types, ids and the commit / tree formats. */
public final class GitObject {
    private GitObject() {
    }

    public static final int COMMIT = 1, TREE = 2, BLOB = 3, TAG = 4;
    static final Charset UTF8 = Charset.forName("UTF-8");
    public static final Charset UTF8_PUBLIC = UTF8;

    public final static class Raw {
        public final int type;
        public final byte[] data;

        public Raw(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    public static String typeName(int t) {
        switch (t) {
            case COMMIT:
                return "commit";
            case TREE:
                return "tree";
            case BLOB:
                return "blob";
            case TAG:
                return "tag";
            default:
                return "unknown";
        }
    }

    public static int typeOf(String name) {
        if (name.equals("commit")) return COMMIT;
        if (name.equals("tree")) return TREE;
        if (name.equals("blob")) return BLOB;
        if (name.equals("tag")) return TAG;
        return 0;
    }

    static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Id of an object: sha1("type len\0" + data). */
    public static String id(int type, byte[] data) {
        MessageDigest md = sha1();
        md.update((typeName(type) + " " + data.length + "\0").getBytes(UTF8));
        md.update(data);
        return hex(md.digest());
    }

    public static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b, int off, int len) {
        char[] c = new char[len * 2];
        for (int i = 0; i < len; i++) {
            c[i * 2] = HEX[(b[off + i] >> 4) & 15];
            c[i * 2 + 1] = HEX[b[off + i] & 15];
        }
        return new String(c);
    }

    public static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    public static boolean isId(String s) {
        return s != null && s.length() == 40 && s.matches("[0-9a-f]{40}");
    }

    // ------------------------------------------------------------------ trees

    public static final class TreeEntry implements Comparable<TreeEntry> {
        public final String mode, name, id;

        public TreeEntry(String mode, String name, String id) {
            this.mode = mode;
            this.name = name;
            this.id = id;
        }

        public boolean isTree() {
            return mode.equals("40000") || mode.equals("040000");
        }

        public boolean isGitlink() {
            return mode.equals("160000");
        }

        /** Git sorts trees as if their names ended with '/'. */
        String sortKey() {
            return isTree() ? name + "/" : name;
        }

        @Override
        public int compareTo(TreeEntry o) {
            return compareBytes(sortKey(), o.sortKey());
        }
    }

    static int compareBytes(String a, String b) {
        byte[] x = a.getBytes(UTF8), y = b.getBytes(UTF8);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int d = (x[i] & 0xFF) - (y[i] & 0xFF);
            if (d != 0) return d;
        }
        return x.length - y.length;
    }

    public static List<TreeEntry> parseTree(byte[] d) {
        ArrayList<TreeEntry> out = new ArrayList<TreeEntry>();
        int i = 0;
        while (i < d.length) {
            int sp = i;
            while (d[sp] != ' ') sp++;
            int nul = sp + 1;
            while (d[nul] != 0) nul++;
            String mode = new String(d, i, sp - i, UTF8);
            String name = new String(d, sp + 1, nul - sp - 1, UTF8);
            out.add(new TreeEntry(mode, name, hex(d, nul + 1, 20)));
            i = nul + 21;
        }
        return out;
    }

    public static byte[] formatTree(List<TreeEntry> entries) {
        ArrayList<TreeEntry> sorted = new ArrayList<TreeEntry>(entries);
        java.util.Collections.sort(sorted);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            for (TreeEntry e : sorted) {
                String mode = e.mode.equals("040000") ? "40000" : e.mode;
                bo.write((mode + " " + e.name).getBytes(UTF8));
                bo.write(0);
                bo.write(unhex(e.id));
            }
        } catch (IOException ignored) {
        }
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ commits

    public static final class Commit {
        public String id, tree;
        public final ArrayList<String> parents = new ArrayList<String>();
        public String author = "", committer = "", message = "";

        /** "Name <email>" of the author. */
        public String authorName() {
            int lt = author.lastIndexOf(" <");
            return lt < 0 ? author : author.substring(0, lt);
        }

        /** Author time in milliseconds. */
        public long time() {
            String[] p = committer.isEmpty() ? author.split(" ") : committer.split(" ");
            try {
                return Long.parseLong(p[p.length - 2]) * 1000L;
            } catch (Exception e) {
                return 0;
            }
        }

        public String subject() {
            int nl = message.indexOf('\n');
            return nl < 0 ? message.trim() : message.substring(0, nl).trim();
        }
    }

    public static Commit parseCommit(String id, byte[] d) {
        Commit c = new Commit();
        c.id = id;
        String s = new String(d, UTF8);
        int body = s.indexOf("\n\n");
        String head = body < 0 ? s : s.substring(0, body);
        c.message = body < 0 ? "" : s.substring(body + 2);
        for (String line : head.split("\n")) {
            if (line.startsWith("tree ")) c.tree = line.substring(5).trim();
            else if (line.startsWith("parent ")) c.parents.add(line.substring(7).trim());
            else if (line.startsWith("author ")) c.author = line.substring(7);
            else if (line.startsWith("committer ")) c.committer = line.substring(10);
        }
        return c;
    }

    public static byte[] formatCommit(String tree, List<String> parents, String author, String committer, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("tree ").append(tree).append('\n');
        for (String p : parents) sb.append("parent ").append(p).append('\n');
        sb.append("author ").append(author).append('\n');
        sb.append("committer ").append(committer).append('\n');
        sb.append('\n').append(message);
        if (!message.endsWith("\n")) sb.append('\n');
        return sb.toString().getBytes(UTF8);
    }

    /** "Name <email> 1700000000 +0300" for now. */
    public static String signature(String name, String email, long millis, java.util.TimeZone tz) {
        int off = tz.getOffset(millis) / 60000;
        String sign = off < 0 ? "-" : "+";
        off = Math.abs(off);
        return name + " <" + email + "> " + (millis / 1000) + " " + sign + String.format(java.util.Locale.ROOT, "%02d%02d", off / 60, off % 60);
    }

    // ------------------------------------------------------------------ deltas

    /** Applies a git delta to {@code base}. */
    public static byte[] applyDelta(byte[] base, byte[] delta) throws IOException {
        int[] pos = {0};
        long baseLen = varint(delta, pos);
        long resLen = varint(delta, pos);
        if (baseLen != base.length) throw new IOException("delta base size mismatch");
        byte[] out = new byte[(int) resLen];
        int o = 0;
        int i = pos[0];
        while (i < delta.length) {
            int cmd = delta[i++] & 0xFF;
            if ((cmd & 0x80) != 0) {
                int off = 0, size = 0;
                if ((cmd & 0x01) != 0) off |= delta[i++] & 0xFF;
                if ((cmd & 0x02) != 0) off |= (delta[i++] & 0xFF) << 8;
                if ((cmd & 0x04) != 0) off |= (delta[i++] & 0xFF) << 16;
                if ((cmd & 0x08) != 0) off |= (delta[i++] & 0xFF) << 24;
                if ((cmd & 0x10) != 0) size |= delta[i++] & 0xFF;
                if ((cmd & 0x20) != 0) size |= (delta[i++] & 0xFF) << 8;
                if ((cmd & 0x40) != 0) size |= (delta[i++] & 0xFF) << 16;
                if (size == 0) size = 0x10000;
                System.arraycopy(base, off, out, o, size);
                o += size;
            } else if (cmd != 0) {
                System.arraycopy(delta, i, out, o, cmd);
                i += cmd;
                o += cmd;
            } else {
                throw new IOException("bad delta opcode");
            }
        }
        if (o != out.length) throw new IOException("delta result size mismatch");
        return out;
    }

    private static long varint(byte[] d, int[] pos) {
        long v = 0;
        int shift = 0;
        int b;
        do {
            b = d[pos[0]++] & 0xFF;
            v |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return v;
    }
}
