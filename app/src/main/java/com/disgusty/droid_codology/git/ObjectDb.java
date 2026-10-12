package com.disgusty.droid_codology.git;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/** .git/objects: loose objects and packfiles (with deltas). */
public final class ObjectDb {
    private final File dir;
    private final ArrayList<Pack> packs = new ArrayList<Pack>();
    private long packsStamp = -1;

    public ObjectDb(File objectsDir) {
        this.dir = objectsDir;
    }

    public File dir() {
        return dir;
    }

    // ------------------------------------------------------------------ reading

    public boolean has(String id) {
        if (looseFile(id).isFile()) return true;
        for (Pack p : packs()) if (p.find(id) >= 0) return true;
        return false;
    }

    public GitObject.Raw read(String id) throws IOException {
        File f = looseFile(id);
        if (f.isFile()) return readLoose(f);
        for (Pack p : packs()) {
            long off = p.find(id);
            if (off >= 0) return p.read(off, this);
        }
        throw new IOException("missing object " + id);
    }

    public GitObject.Raw readOrNull(String id) {
        try {
            return read(id);
        } catch (IOException e) {
            return null;
        }
    }

    private File looseFile(String id) {
        return new File(new File(dir, id.substring(0, 2)), id.substring(2));
    }

    private static GitObject.Raw readLoose(File f) throws IOException {
        byte[] all = inflateAll(readFile(f));
        int sp = 0;
        while (all[sp] != ' ') sp++;
        int nul = sp;
        while (all[nul] != 0) nul++;
        int type = GitObject.typeOf(new String(all, 0, sp, GitObject.UTF8));
        return new GitObject.Raw(type, Arrays.copyOfRange(all, nul + 1, all.length));
    }

    synchronized List<Pack> packs() {
        File pd = new File(dir, "pack");
        long stamp = pd.lastModified();
        if (stamp != packsStamp) {
            for (Pack p : packs) p.close();
            packs.clear();
            File[] idx = pd.listFiles();
            if (idx != null) {
                for (File f : idx) {
                    if (!f.getName().endsWith(".idx")) continue;
                    File pack = new File(pd, f.getName().replace(".idx", ".pack"));
                    if (!pack.isFile()) continue;
                    try {
                        packs.add(new Pack(f, pack));
                    } catch (IOException ignored) {
                    }
                }
            }
            packsStamp = stamp;
        }
        return packs;
    }

    // ------------------------------------------------------------------ writing

    public String write(int type, byte[] data) throws IOException {
        String id = GitObject.id(type, data);
        File f = looseFile(id);
        if (f.isFile() || has(id)) return id;
        f.getParentFile().mkdirs();
        File tmp = new File(f.getParentFile(), "tmp_" + id.substring(2) + "_" + System.nanoTime());
        OutputStream os = new DeflaterOutputStream(new FileOutputStream(tmp), new Deflater(Deflater.BEST_SPEED));
        try {
            os.write((GitObject.typeName(type) + " " + data.length + "\0").getBytes(GitObject.UTF8));
            os.write(data);
        } finally {
            os.close();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            if (!f.isFile()) throw new IOException("can't write object " + id);
        }
        return id;
    }

    // ------------------------------------------------------------------ packs

    static final class Pack {
        final File idxFile, packFile;
        private final int[] fanout = new int[256];
        private final byte[] ids;
        private final int[] offsets32;
        private final long[] offsets64;
        private final int count;
        private RandomAccessFile raf;
        /** Small cache of resolved delta bases by offset. */
        private final LinkedHashMap<Long, GitObject.Raw> cache = new LinkedHashMap<Long, GitObject.Raw>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, GitObject.Raw> e) {
                return size() > 64;
            }
        };

        Pack(File idx, File pack) throws IOException {
            idxFile = idx;
            packFile = pack;
            byte[] d = readFile(idx);
            if (!(d[0] == (byte) 0xFF && d[1] == 't' && d[2] == 'O' && d[3] == 'c') || be32(d, 4) != 2) {
                throw new IOException("unsupported pack index " + idx.getName());
            }
            for (int i = 0; i < 256; i++) fanout[i] = be32(d, 8 + i * 4);
            count = fanout[255];
            int shaStart = 8 + 1024;
            ids = Arrays.copyOfRange(d, shaStart, shaStart + count * 20);
            int offStart = shaStart + count * 20 + count * 4;
            offsets32 = new int[count];
            int big = 0;
            for (int i = 0; i < count; i++) {
                offsets32[i] = be32(d, offStart + i * 4);
                if (offsets32[i] < 0) big++;
            }
            int bigStart = offStart + count * 4;
            offsets64 = new long[big];
            for (int i = 0; i < big; i++) offsets64[i] = ((long) be32(d, bigStart + i * 8) << 32) | (be32(d, bigStart + i * 8 + 4) & 0xFFFFFFFFL);
        }

        long find(String id) {
            byte[] key = GitObject.unhex(id);
            int first = key[0] & 0xFF;
            int lo = first == 0 ? 0 : fanout[first - 1], hi = fanout[first] - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = cmp(ids, mid * 20, key);
                if (c == 0) {
                    int o = offsets32[mid];
                    return o >= 0 ? o : offsets64[o & 0x7FFFFFFF];
                }
                if (c < 0) lo = mid + 1;
                else hi = mid - 1;
            }
            return -1;
        }

        synchronized GitObject.Raw read(long off, ObjectDb db) throws IOException {
            GitObject.Raw cached = cache.get(off);
            if (cached != null) return cached;
            if (raf == null) raf = new RandomAccessFile(packFile, "r");
            PackEntry e = readEntry(raf, off);
            GitObject.Raw r;
            if (e.type == 6) {
                GitObject.Raw base = read(off - e.baseOffset, db);
                r = new GitObject.Raw(base.type, GitObject.applyDelta(base.data, e.data));
            } else if (e.type == 7) {
                GitObject.Raw base = db.read(e.baseId);
                r = new GitObject.Raw(base.type, GitObject.applyDelta(base.data, e.data));
            } else {
                r = new GitObject.Raw(e.type, e.data);
            }
            if (r.data.length < 1 << 20) cache.put(off, r);
            return r;
        }

        synchronized void close() {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
                raf = null;
            }
        }
    }

    private static int cmp(byte[] a, int off, byte[] b) {
        for (int i = 0; i < 20; i++) {
            int d = (a[off + i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) return d;
        }
        return 0;
    }

    /** One pack entry as stored: type 1-4, 6 (ofs delta) or 7 (ref delta), with inflated data. */
    static final class PackEntry {
        int type;
        long baseOffset;
        String baseId;
        byte[] data;
        /** Offset just past the entry. */
        long end;
    }

    static PackEntry readEntry(RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        byte[] hdr = new byte[32];
        int n = raf.read(hdr);
        int i = 0;
        int c = hdr[i++] & 0xFF;
        PackEntry e = new PackEntry();
        e.type = (c >> 4) & 7;
        if (n < 2) throw new IOException("truncated pack entry");
        long size = c & 15;
        int shift = 4;
        while ((c & 0x80) != 0) {
            c = hdr[i++] & 0xFF;
            size |= (long) (c & 0x7F) << shift;
            shift += 7;
        }
        if (e.type == 6) {
            c = hdr[i++] & 0xFF;
            long base = c & 0x7F;
            while ((c & 0x80) != 0) {
                c = hdr[i++] & 0xFF;
                base = ((base + 1) << 7) | (c & 0x7F);
            }
            e.baseOffset = base;
        } else if (e.type == 7) {
            e.baseId = GitObject.hex(hdr, i, 20);
            i += 20;
        }
        if (n < i) throw new IOException("truncated pack entry");
        long dataStart = off + i;
        Inflater inf = new Inflater();
        try {
            byte[] out = new byte[(int) size];
            int got = 0;
            byte[] buf = new byte[8192];
            raf.seek(dataStart);
            while (!inf.finished()) {
                if (inf.needsInput()) {
                    int r = raf.read(buf);
                    if (r <= 0) throw new IOException("truncated pack data");
                    inf.setInput(buf, 0, r);
                }
                try {
                    int k = inf.inflate(out, got, out.length - got);
                    got += k;
                    if (k == 0 && inf.needsDictionary()) throw new IOException("bad zlib stream");
                    if (k == 0 && got == out.length && !inf.finished() && !inf.needsInput()) {
                        // zero-length output may still need a final read to see the stream end
                        byte[] one = new byte[1];
                        if (inf.inflate(one) != 0) throw new IOException("pack object larger than declared");
                    }
                } catch (DataFormatException ex) {
                    throw new IOException("corrupt pack data: " + ex.getMessage());
                }
            }
            if (got != out.length) throw new IOException("pack object size mismatch");
            e.data = out;
            e.end = dataStart + inf.getTotalIn();
        } finally {
            inf.end();
        }
        return e;
    }

    // ------------------------------------------------------------------ receiving packs

    /**
     * Stores a received pack: verifies it, resolves deltas to compute every object id and writes
     * the .idx (v2) next to it. Returns the number of objects.
     */
    public int addPack(File received) throws IOException {
        java.util.HashSet<String> external = new java.util.HashSet<String>();
        File idx = indexPack(received, external);
        if (!external.isEmpty()) {
            // A thin pack: deltas against objects we already have. Append those bases so the pack
            // stands on its own (like git index-pack --fix-thin), then index it again.
            idx.delete();
            appendObjects(received, new ArrayList<String>(external));
            external.clear();
            idx = indexPack(received, external);
        }
        File pd = new File(dir, "pack");
        File dest = new File(pd, idx.getName().replace(".idx", ".pack"));
        if (!received.renameTo(dest)) {
            copy(received, dest);
            received.delete();
        }
        pd.setLastModified(System.currentTimeMillis());
        packsStamp = -1;
        return countOf(dest);
    }

    private static int countOf(File pack) throws IOException {
        RandomAccessFile r = new RandomAccessFile(pack, "r");
        try {
            byte[] h = new byte[12];
            r.readFully(h);
            return be32(h, 8);
        } finally {
            r.close();
        }
    }

    /** Adds whole objects to the end of a pack, fixing its object count and checksum. */
    private void appendObjects(File pack, List<String> ids) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(pack, "rw");
        try {
            byte[] h = new byte[12];
            raf.readFully(h);
            int count = be32(h, 8) + ids.size();
            raf.seek(8);
            raf.writeInt(count);
            long end = raf.length() - 20;
            raf.seek(end);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            for (String id : ids) writeEntry(read(id), bo);
            raf.write(bo.toByteArray());
            long len = raf.getFilePointer();
            MessageDigest md = GitObject.sha1();
            raf.seek(0);
            byte[] buf = new byte[65536];
            long left = len;
            while (left > 0) {
                int r = raf.read(buf, 0, (int) Math.min(buf.length, left));
                md.update(buf, 0, r);
                left -= r;
            }
            raf.seek(len);
            raf.write(md.digest());
            raf.setLength(len + 20);
        } finally {
            raf.close();
        }
    }

    /** Checks and indexes a pack in place; ref-delta bases that live outside it go to {@code external}. */
    private File indexPack(File received, java.util.Set<String> external) throws IOException {
        File idx;
        RandomAccessFile raf = new RandomAccessFile(received, "r");
        int count;
        long[] offs;
        String[] idsOut;
        int[] crcs;
        try {
            byte[] h = new byte[12];
            raf.readFully(h);
            if (h[0] != 'P' || h[1] != 'A' || h[2] != 'C' || h[3] != 'K') throw new IOException("not a pack");
            count = be32(h, 8);
            offs = new long[count];
            idsOut = new String[count];
            crcs = new int[count];
            int[] types = new int[count];
            long[] baseOff = new long[count];
            String[] baseId = new String[count];
            HashMap<Long, Integer> byOffset = new HashMap<Long, Integer>();
            HashMap<String, Integer> byId = new HashMap<String, Integer>();
            long off = 12;
            for (int i = 0; i < count; i++) {
                PackEntry e = readEntry(raf, off);
                offs[i] = off;
                byOffset.put(off, i);
                types[i] = e.type;
                CRC32 crc = new CRC32();
                byte[] raw = new byte[(int) (e.end - off)];
                raf.seek(off);
                raf.readFully(raw);
                crc.update(raw);
                crcs[i] = (int) crc.getValue();
                if (e.type == 6) baseOff[i] = off - e.baseOffset;
                else if (e.type == 7) baseId[i] = e.baseId;
                else {
                    idsOut[i] = GitObject.id(e.type, e.data);
                    byId.put(idsOut[i], i);
                }
                off = e.end;
            }
            // Trailer: SHA-1 of everything before it.
            byte[] trailer = new byte[20];
            raf.seek(off);
            raf.readFully(trailer);
            MessageDigest md = GitObject.sha1();
            raf.seek(0);
            byte[] buf = new byte[65536];
            long left = off;
            while (left > 0) {
                int r = raf.read(buf, 0, (int) Math.min(buf.length, left));
                md.update(buf, 0, r);
                left -= r;
            }
            if (!Arrays.equals(md.digest(), trailer)) throw new IOException("pack checksum mismatch");

            // Resolve deltas until nothing changes (bases may come later in the pack).
            LinkedHashMap<Long, GitObject.Raw> cache = new LinkedHashMap<Long, GitObject.Raw>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, GitObject.Raw> e) {
                    return size() > 48;
                }
            };
            boolean progress = true;
            while (progress) {
                progress = false;
                for (int i = 0; i < count; i++) {
                    if (idsOut[i] != null) continue;
                    boolean ready;
                    if (types[i] == 6) ready = idsOut[byOffset.get(baseOff[i])] != null;
                    else if (byId.containsKey(baseId[i])) ready = true;
                    else {
                        ready = has(baseId[i]);
                        if (ready) external.add(baseId[i]);
                    }
                    if (!ready) continue;
                    GitObject.Raw r = resolveAt(raf, offs[i], byId, offs, cache);
                    idsOut[i] = GitObject.id(r.type, r.data);
                    byId.put(idsOut[i], i);
                    progress = true;
                }
            }
            for (int i = 0; i < count; i++) if (idsOut[i] == null) throw new IOException("unresolvable delta in pack");
            String packName = GitObject.hex(trailer);
            File pd = new File(dir, "pack");
            pd.mkdirs();
            idx = new File(pd, "pack-" + packName + ".idx");
            writeIndex(idx, idsOut, offs, crcs, trailer);
        } finally {
            raf.close();
        }
        return idx;
    }

    /** Full object at {@code off} of a pack being indexed (bases from the same pack or this database). */
    private GitObject.Raw resolveAt(RandomAccessFile raf, long off, HashMap<String, Integer> byId, long[] offs,
                                    Map<Long, GitObject.Raw> cache) throws IOException {
        GitObject.Raw c = cache.get(off);
        if (c != null) return c;
        PackEntry e = readEntry(raf, off);
        GitObject.Raw r;
        if (e.type == 6 || e.type == 7) {
            GitObject.Raw base;
            if (e.type == 6) base = resolveAt(raf, off - e.baseOffset, byId, offs, cache);
            else {
                Integer bi = byId.get(e.baseId);
                base = bi != null ? resolveAt(raf, offs[bi], byId, offs, cache) : read(e.baseId);
            }
            r = new GitObject.Raw(base.type, GitObject.applyDelta(base.data, e.data));
        } else {
            r = new GitObject.Raw(e.type, e.data);
        }
        if (r.data.length < 1 << 20) cache.put(off, r);
        return r;
    }

    private static byte[] readTrailer(File f) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            byte[] t = new byte[20];
            r.seek(r.length() - 20);
            r.readFully(t);
            return t;
        } finally {
            r.close();
        }
    }

    private static void writeIndex(File idx, String[] idsIn, long[] offs, int[] crcs, byte[] packSha) throws IOException {
        int n = idsIn.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final String[] ids = idsIn;
        Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return ids[a].compareTo(ids[b]);
            }
        });
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1100 + n * 28);
        bo.write(new byte[]{(byte) 0xFF, 't', 'O', 'c', 0, 0, 0, 2});
        int[] fan = new int[256];
        for (String id : ids) fan[Integer.parseInt(id.substring(0, 2), 16)]++;
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += fan[i];
            put32(bo, acc);
        }
        for (Integer i : order) bo.write(GitObject.unhex(ids[i]), 0, 20);
        for (Integer i : order) put32(bo, crcs[i]);
        ArrayList<Long> big = new ArrayList<Long>();
        for (Integer i : order) {
            if (offs[i] < 0x80000000L) put32(bo, (int) offs[i]);
            else {
                put32(bo, 0x80000000 | big.size());
                big.add(offs[i]);
            }
        }
        for (Long b : big) {
            put32(bo, (int) (b >>> 32));
            put32(bo, (int) (long) b);
        }
        bo.write(packSha, 0, 20);
        MessageDigest md = GitObject.sha1();
        md.update(bo.toByteArray());
        bo.write(md.digest(), 0, 20);
        FileOutputStream os = new FileOutputStream(idx);
        try {
            bo.writeTo(os);
        } finally {
            os.close();
        }
    }

    /** Writes a pack (no deltas) with the given objects to {@code out}; returns nothing, ends with the checksum. */
    public void writePack(List<String> objectIds, OutputStream out) throws IOException {
        MessageDigest md = GitObject.sha1();
        ByteArrayOutputStream hdr = new ByteArrayOutputStream();
        hdr.write(new byte[]{'P', 'A', 'C', 'K', 0, 0, 0, 2});
        put32(hdr, objectIds.size());
        byte[] h = hdr.toByteArray();
        md.update(h);
        out.write(h);
        for (String id : objectIds) {
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            writeEntry(read(id), e);
            byte[] b = e.toByteArray();
            md.update(b);
            out.write(b);
        }
        out.write(md.digest());
    }

    /** One undeltified pack entry: type/size header and the deflated data. */
    private static void writeEntry(GitObject.Raw r, ByteArrayOutputStream e) throws IOException {
        long size = r.data.length;
        int c = (r.type << 4) | (int) (size & 15);
        size >>>= 4;
        while (size != 0) {
            e.write(c | 0x80);
            c = (int) (size & 0x7F);
            size >>>= 7;
        }
        e.write(c);
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
        DeflaterOutputStream dos = new DeflaterOutputStream(e, d);
        dos.write(r.data);
        dos.finish();
        d.end();
    }

    // ------------------------------------------------------------------ helpers

    static int be32(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    static void put32(ByteArrayOutputStream b, int v) {
        b.write(v >>> 24);
        b.write(v >>> 16);
        b.write(v >>> 8);
        b.write(v);
    }

    static byte[] readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(32, Math.min(f.length(), 1 << 26)));
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    static void copy(File a, File b) throws IOException {
        InputStream in = new FileInputStream(a);
        OutputStream os = new FileOutputStream(b);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            in.close();
            os.close();
        }
    }

    static byte[] inflateAll(byte[] z) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(z);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(z.length * 3);
            byte[] buf = new byte[16384];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("corrupt object: " + e.getMessage());
        } finally {
            inf.end();
        }
    }
}
