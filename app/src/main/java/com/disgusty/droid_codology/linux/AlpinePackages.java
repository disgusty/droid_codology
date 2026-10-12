package com.disgusty.droid_codology.linux;

import com.disgusty.droid_codology.net.Http;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Alpine packages downloaded by the app instead of by apk. On some devices programs inside Alpine
 * can't get through to the network while the app can (it downloaded Alpine itself), so the app
 * fetches the package index and every package a request needs into a local repository in the
 * root file system, and apk installs from there without the network. apk still checks Alpine's
 * signatures of the index and the packages with the keys in /etc/apk/keys.
 */
public final class AlpinePackages {
    private static final String MIRROR = "https://dl-cdn.alpinelinux.org/alpine/v3.24/";
    private static final String[] REPOS = {"main", "community"};
    /** The local repository, as Alpine sees it. */
    public static final String GUEST_REPO = "/var/cache/droid_codology/repo";

    public interface Progress {
        /** {@code what}: "index", "download" (percent of all bytes) or "install". */
        void onProgress(String what, int percent);
    }

    static final class Pkg {
        String name, version, repo;
        long size;
        final ArrayList<String> deps = new ArrayList<String>();
        final ArrayList<String> provides = new ArrayList<String>();
    }

    private AlpinePackages() {
    }

    /**
     * Downloads {@code names} with everything they need that isn't installed yet, then installs
     * them with apk (blocking; run it off the UI thread). Returns apk's output.
     */
    public static String install(AlpineLinux alpine, List<String> names, final Progress p) throws IOException {
        String arch = AlpineLinux.arch();
        if (arch == null) throw new IOException("no Alpine Linux for this processor");
        File repoRoot = new File(alpine.rootfs(), GUEST_REPO.substring(1));
        if (p != null) p.onProgress("index", 0);
        Map<String, Pkg> byName = new LinkedHashMap<String, Pkg>();
        for (String repo : REPOS) {
            File dir = new File(new File(repoRoot, repo), arch);
            dir.mkdirs();
            File index = new File(dir, "APKINDEX.tar.gz");
            // A fresh index each time: package versions change within the branch.
            Http.download(MIRROR + repo + "/" + arch + "/APKINDEX.tar.gz", index, null, null);
            for (Pkg k : parseIndex(readApkIndex(index), repo)) {
                if (!byName.containsKey(k.name)) byName.put(k.name, k);
            }
        }
        Map<String, String> installed = installed(new File(alpine.rootfs(), "lib/apk/db/installed"));
        List<Pkg> need = resolve(byName, installed, names);
        long total = 0;
        for (Pkg k : need) total += Math.max(1, k.size);
        final long all = total;
        long done = 0;
        for (Pkg k : need) {
            File f = new File(new File(new File(repoRoot, k.repo), arch), k.name + "-" + k.version + ".apk");
            if (!(f.isFile() && (k.size <= 0 || f.length() == k.size))) {
                final long before = done;
                Http.download(MIRROR + k.repo + "/" + arch + "/" + f.getName(), f, null, new Http.Progress() {
                    @Override
                    public void onProgress(long d, long t) {
                        if (p != null) p.onProgress("download", (int) Math.min(99, (before + d) * 100 / Math.max(1, all)));
                    }
                });
            }
            done += Math.max(1, k.size);
            if (p != null) p.onProgress("download", (int) Math.min(99, done * 100 / Math.max(1, all)));
        }
        if (p != null) p.onProgress("install", 0);
        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add("/sbin/apk");
        cmd.add("add");
        cmd.add("--no-network");
        cmd.add("--repositories-file");
        cmd.add("/dev/null");
        for (String repo : REPOS) {
            cmd.add("-X");
            cmd.add(GUEST_REPO + "/" + repo);
        }
        cmd.addAll(names);
        StringBuilder out = new StringBuilder();
        int code = alpine.run(cmd, out);
        if (code != 0) {
            String o = out.toString().trim();
            throw new IOException(o.isEmpty() ? "apk failed (" + code + ")" : o.substring(Math.max(0, o.length() - 600)));
        }
        // Installed: the package files would only take space twice.
        for (String repo : REPOS) {
            File[] fs = new File(new File(repoRoot, repo), arch).listFiles();
            if (fs != null) for (File f : fs) if (f.getName().endsWith(".apk")) f.delete();
        }
        return out.toString();
    }

    /** What has to be downloaded for {@code names}: the packages and their dependencies, minus what's installed. */
    static List<Pkg> resolve(Map<String, Pkg> byName, Map<String, String> installed, List<String> names) throws IOException {
        HashMap<String, Pkg> provider = new HashMap<String, Pkg>();
        for (Pkg k : byName.values()) {
            for (String pr : k.provides) {
                String n = bare(pr);
                if (!provider.containsKey(n)) provider.put(n, k);
            }
        }
        ArrayList<Pkg> out = new ArrayList<Pkg>();
        HashSet<String> seen = new HashSet<String>();
        ArrayDeque<String> todo = new ArrayDeque<String>(names);
        int tops = names.size(); // the first ones polled are the requested packages
        while (!todo.isEmpty()) {
            String dep = todo.poll();
            boolean wanted = tops-- > 0;
            if (dep.startsWith("!")) continue; // a conflict, not a dependency
            String n = bare(dep);
            Pkg k = byName.get(n);
            if (k == null) k = provider.get(n);
            if (k == null) {
                if (wanted) throw new IOException("no such package: " + n);
                continue; // apk will say if it really matters
            }
            if (!seen.add(k.name)) continue;
            if (k.version.equals(installed.get(k.name))) continue;
            out.add(k);
            todo.addAll(k.deps);
        }
        return out;
    }

    /** "so:libc.musl-armv7.so.1=1", "nodejs>=20", "cmd:node" → the name part. */
    static String bare(String dep) {
        int end = dep.length();
        for (int i = 0; i < dep.length(); i++) {
            char c = dep.charAt(i);
            if (c == '<' || c == '>' || c == '=' || c == '~') {
                end = i;
                break;
            }
        }
        return dep.substring(0, end);
    }

    /** Records of an APKINDEX text: "P:name", "V:version", "D:deps", "p:provides", "S:size", blank line between. */
    static List<Pkg> parseIndex(String text, String repo) {
        ArrayList<Pkg> out = new ArrayList<Pkg>();
        Pkg k = null;
        for (String line : text.split("\n")) {
            if (line.length() < 2 || line.charAt(1) != ':') {
                if (line.trim().isEmpty() && k != null) {
                    if (k.name != null && k.version != null) out.add(k);
                    k = null;
                }
                continue;
            }
            if (k == null) {
                k = new Pkg();
                k.repo = repo;
            }
            String v = line.substring(2);
            switch (line.charAt(0)) {
                case 'P':
                    k.name = v;
                    break;
                case 'V':
                    k.version = v;
                    break;
                case 'S':
                    try {
                        k.size = Long.parseLong(v.trim());
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                case 'D':
                    for (String d : v.trim().split(" ")) if (!d.isEmpty()) k.deps.add(d);
                    break;
                case 'p':
                    for (String d : v.trim().split(" ")) if (!d.isEmpty()) k.provides.add(d);
                    break;
                default:
                    break;
            }
        }
        if (k != null && k.name != null && k.version != null) out.add(k);
        return out;
    }

    /** name → version of installed packages, from apk's database. */
    static Map<String, String> installed(File db) {
        HashMap<String, String> m = new HashMap<String, String>();
        String text;
        try {
            text = new String(readFile(db), "UTF-8");
        } catch (IOException e) {
            return m;
        }
        String name = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("P:")) name = line.substring(2);
            else if (line.startsWith("V:") && name != null) m.put(name, line.substring(2));
            else if (line.trim().isEmpty()) name = null;
        }
        return m;
    }

    /**
     * The APKINDEX text inside APKINDEX.tar.gz. The file is several gzip streams one after the other
     * (signature, then index), each a piece of one tar archive, which old Android's
     * GZIPInputStream doesn't read past the first of; so they're inflated one by one here.
     */
    static String readApkIndex(File f) throws IOException {
        byte[] all = readFile(f);
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        int off = 0;
        byte[] buf = new byte[64 * 1024];
        while (off + 18 <= all.length && (all[off] & 0xff) == 0x1f && (all[off + 1] & 0xff) == 0x8b) {
            int flg = all[off + 3] & 0xff;
            int pos = off + 10;
            if ((flg & 4) != 0) pos += 2 + ((all[pos] & 0xff) | (all[pos + 1] & 0xff) << 8);
            if ((flg & 8) != 0) while (all[pos++] != 0) ;
            if ((flg & 16) != 0) while (all[pos++] != 0) ;
            if ((flg & 2) != 0) pos += 2;
            Inflater inf = new Inflater(true);
            try {
                inf.setInput(all, pos, all.length - pos);
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n > 0) tar.write(buf, 0, n);
                    else if (inf.needsInput() || inf.needsDictionary()) break;
                }
                off = all.length - inf.getRemaining() + 8; // CRC32 and size follow each stream
            } catch (DataFormatException e) {
                throw new IOException("damaged package index");
            } finally {
                inf.end();
            }
        }
        byte[] t = tar.toByteArray();
        int p = 0;
        while (p + 512 <= t.length) {
            if (t[p] == 0) {
                p += 512; // padding between the pieces
                continue;
            }
            int nameEnd = p;
            while (nameEnd < p + 100 && t[nameEnd] != 0) nameEnd++;
            String name = new String(t, p, nameEnd - p, "UTF-8");
            long size = 0;
            for (int i = p + 124; i < p + 136; i++) {
                int c = t[i];
                if (c >= '0' && c <= '7') size = size * 8 + (c - '0');
            }
            int data = p + 512;
            if (name.equals("APKINDEX")) return new String(t, data, (int) Math.min(size, t.length - data), "UTF-8");
            p = data + (int) ((size + 511) / 512 * 512);
        }
        throw new IOException("no package list in the index");
    }

    private static byte[] readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }
}
