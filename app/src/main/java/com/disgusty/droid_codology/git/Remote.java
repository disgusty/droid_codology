package com.disgusty.droid_codology.git;

import com.disgusty.droid_codology.net.Http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Git's smart HTTP protocol (v0): clone, fetch and push. */
public final class Remote {
    public interface Progress {
        void onProgress(String message);
    }

    /** Credentials for HTTPS remotes (GitHub: any user name and a personal access token). */
    public static final class Auth {
        final String user, password;

        public Auth(String user, String password) {
            this.user = user;
            this.password = password;
        }
    }

    public static final class AuthRequired extends IOException {
        AuthRequired(String m) {
            super(m);
        }
    }

    private final String url;
    private final Auth auth;

    public Remote(String url, Auth auth) {
        String u = url.trim();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // https://user:token@host/repo (as git accepts): the credentials travel in the header and
        // the URL used for requests and error messages has none.
        int scheme = u.indexOf("://");
        if (scheme > 0) {
            int hostStart = scheme + 3, slash = u.indexOf('/', hostStart);
            int at = u.lastIndexOf('@', slash < 0 ? u.length() : slash);
            if (at >= hostStart) {
                String info = u.substring(hostStart, at);
                int c = info.indexOf(':');
                String user = decode(c < 0 ? info : info.substring(0, c)), pass = c < 0 ? "" : decode(info.substring(c + 1));
                if (!user.isEmpty() || !pass.isEmpty()) auth = new Auth(user, pass);
                u = u.substring(0, hostStart) + u.substring(at + 1);
            }
        }
        this.url = u;
        this.auth = auth;
    }

    /** The URL without credentials, for showing and storing. */
    public static String withoutCredentials(String url) {
        return new Remote(url, null).url;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static final class Advert {
        final LinkedHashMap<String, String> refs = new LinkedHashMap<String, String>();
        final HashSet<String> caps = new HashSet<String>();
        String symHead;
    }

    private Map<String, String> headers(String contentType, String accept) {
        LinkedHashMap<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", "git/2.45.0 (droid_codology)");
        if (accept != null) h.put("Accept", accept);
        if (contentType != null) h.put("Content-Type", contentType);
        if (auth != null) {
            String raw = auth.user + ":" + auth.password;
            h.put("Authorization", "Basic " + base64(raw.getBytes(GitObject.UTF8)));
        }
        return h;
    }

    static String base64(byte[] b) {
        String t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xFF) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xFF) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xFF : 0);
            sb.append(t.charAt(n >> 18 & 63)).append(t.charAt(n >> 12 & 63));
            sb.append(i + 1 < b.length ? t.charAt(n >> 6 & 63) : '=').append(i + 2 < b.length ? t.charAt(n & 63) : '=');
        }
        return sb.toString();
    }

    Advert advertise(String service) throws IOException {
        Http.Stream s = Http.stream("GET", url + "/info/refs?service=" + service, headers(null, "*/*"), null, null);
        try {
            check(s);
            InputStream in = new BufferedInputStream(s.body);
            Advert a = new Advert();
            String first = readPktString(in);
            if (first == null || !first.startsWith("# service=")) {
                throw new IOException("The server doesn't speak the smart Git protocol (is the URL right?)");
            }
            readPkt(in); // flush
            boolean firstRef = true;
            String line;
            while ((line = readPktString(in)) != null) {
                if (firstRef) {
                    int nul = line.indexOf('\0');
                    if (nul >= 0) {
                        for (String c : line.substring(nul + 1).trim().split(" ")) {
                            a.caps.add(c);
                            if (c.startsWith("symref=HEAD:")) a.symHead = c.substring(12);
                        }
                        line = line.substring(0, nul);
                    }
                    firstRef = false;
                }
                line = line.trim();
                if (line.length() < 41) continue;
                String id = line.substring(0, 40), name = line.substring(41);
                if (name.equals("capabilities^{}")) continue;
                a.refs.put(name, id);
            }
            return a;
        } finally {
            s.close();
        }
    }

    private void check(Http.Stream s) throws IOException {
        if (s.code == 401 || s.code == 403) {
            s.close();
            throw new AuthRequired(auth == null ? "Authentication required" : "Access denied — check the token and its permissions");
        }
        if (s.code == 404) throw new IOException("Repository not found: " + url);
        if (!s.ok()) throw new IOException("HTTP " + s.code + " from " + url);
    }

    // ------------------------------------------------------------------ fetch

    public static final class FetchResult {
        /** Remote branch name -> commit id. */
        public final TreeMap<String, String> branches = new TreeMap<String, String>();
        public final TreeMap<String, String> tags = new TreeMap<String, String>();
        public String defaultBranch;
        public int objects;
    }

    /** Downloads what's missing and updates refs/remotes/{remoteName}/*. */
    public FetchResult fetch(Repo repo, String remoteName, Progress p) throws IOException {
        if (p != null) p.onProgress("Connecting...");
        Advert a = advertise("git-upload-pack");
        FetchResult r = new FetchResult();
        for (Map.Entry<String, String> e : a.refs.entrySet()) {
            // Names come from the server and become file paths here: skip anything git wouldn't allow.
            if (!Repo.validRefName(e.getKey()) || !GitObject.isId(e.getValue())) continue;
            if (e.getKey().startsWith("refs/heads/")) r.branches.put(e.getKey().substring(11), e.getValue());
            else if (e.getKey().startsWith("refs/tags/")) r.tags.put(e.getKey().substring(10), e.getValue());
        }
        if (a.symHead != null && a.symHead.startsWith("refs/heads/") && Repo.validRefName(a.symHead)) r.defaultBranch = a.symHead.substring(11);
        else if (r.branches.containsKey("main")) r.defaultBranch = "main";
        else if (r.branches.containsKey("master")) r.defaultBranch = "master";
        else if (!r.branches.isEmpty()) r.defaultBranch = r.branches.firstKey();

        ArrayList<String> wants = new ArrayList<String>();
        for (String id : r.branches.values()) if (!repo.db.has(id) && !wants.contains(id)) wants.add(id);
        for (String id : r.tags.values()) if (!repo.db.has(id) && !wants.contains(id)) wants.add(id);
        if (!wants.isEmpty()) {
            if (p != null) p.onProgress("Downloading objects...");
            r.objects = download(repo, a, wants, p);
        }
        for (Map.Entry<String, String> e : r.branches.entrySet()) repo.updateRef("refs/remotes/" + remoteName + "/" + e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : r.tags.entrySet()) {
            if (repo.resolveRef("refs/tags/" + e.getKey()) == null && repo.db.has(e.getValue())) repo.updateRef("refs/tags/" + e.getKey(), e.getValue());
        }
        // Remote branches deleted on the server.
        for (String ref : repo.refs("refs/remotes/" + remoteName + "/").keySet()) {
            String b = ref.substring(("refs/remotes/" + remoteName + "/").length());
            if (!b.equals("HEAD") && !r.branches.containsKey(b)) repo.deleteRef(ref);
        }
        return r;
    }

    private int download(Repo repo, Advert a, List<String> wants, Progress p) throws IOException {
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        StringBuilder caps = new StringBuilder();
        // thin-pack: bases may be objects we already have; ObjectDb.addPack completes such packs.
        for (String c : new String[]{"side-band-64k", "ofs-delta", "thin-pack", "no-progress", "include-tag"}) {
            if (a.caps.contains(c)) caps.append(' ').append(c);
        }
        caps.append(" agent=git/2.45.0-droid_codology");
        for (int i = 0; i < wants.size(); i++) writePkt(req, "want " + wants.get(i) + (i == 0 ? caps.toString() : "") + "\n");
        writeFlush(req);
        // Tell the server what we have (recent local commits) so it sends less.
        int haves = 0;
        for (String ref : new HashSet<String>(repo.refs("refs/").values())) {
            // Tags point at tag objects: walk the commit they name (log() of a tag id fails).
            String id = repo.peelToCommit(ref);
            if (id == null) continue;
            for (GitObject.Commit c : repo.log(id, 64)) {
                if (haves++ > 512) break;
                writePkt(req, "have " + c.id + "\n");
            }
        }
        writePkt(req, "done\n");
        Http.Stream s = Http.stream("POST", url + "/git-upload-pack", headers("application/x-git-upload-pack-request",
                "application/x-git-upload-pack-result"), req.toByteArray(), "application/x-git-upload-pack-request");
        File tmp = new File(repo.git, "objects/pack/incoming-" + System.nanoTime() + ".pack");
        tmp.getParentFile().mkdirs();
        try {
            check(s);
            InputStream in = new BufferedInputStream(s.body, 65536);
            // ACK/NAK lines, then the pack (side-band framed if negotiated).
            boolean sideband = a.caps.contains("side-band-64k");
            OutputStream os = new FileOutputStream(tmp);
            long total = 0;
            try {
                while (true) {
                    if (!sideband) {
                        in.mark(4);
                        byte[] peek = new byte[4];
                        int n = readFully(in, peek);
                        in.reset();
                        if (n == 4 && peek[0] == 'P' && peek[1] == 'A' && peek[2] == 'C' && peek[3] == 'K') break;
                        // The server hung up without sending a pack (spinning here forever otherwise).
                        if (n < 4) throw new IOException("The server closed the connection without sending the objects");
                    }
                    byte[] pkt = readPkt(in);
                    if (pkt == null) {
                        if (sideband) break;
                        continue;
                    }
                    if (sideband && pkt.length > 0 && (pkt[0] == 1 || pkt[0] == 2 || pkt[0] == 3)) {
                        if (pkt[0] == 1) {
                            os.write(pkt, 1, pkt.length - 1);
                            total += pkt.length - 1;
                            if (p != null && (total & 0xFFFFF) < pkt.length) p.onProgress("Downloading objects... " + (total >> 10) + " KB");
                        } else if (pkt[0] == 3) {
                            throw new IOException("Remote error: " + new String(pkt, 1, pkt.length - 1, GitObject.UTF8).trim());
                        }
                        continue;
                    }
                    String line = new String(pkt, GitObject.UTF8);
                    if (line.startsWith("ERR ")) throw new IOException("Remote error: " + line.substring(4).trim());
                    // "NAK" / "ACK id": nothing to do.
                }
                if (!sideband) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            } finally {
                os.close();
            }
            if (p != null) p.onProgress("Indexing objects...");
            return repo.db.addPack(tmp);
        } finally {
            s.close();
            if (tmp.exists()) tmp.delete();
        }
    }

    // ------------------------------------------------------------------ push

    /** Pushes local commit {@code localId} to remote branch {@code branch}; returns a summary. */
    public String push(Repo repo, String localId, String branch, boolean force, Progress p) throws IOException {
        if (p != null) p.onProgress("Connecting...");
        Advert a = advertise("git-receive-pack");
        String ref = "refs/heads/" + branch;
        String remoteId = a.refs.get(ref);
        String zero = "0000000000000000000000000000000000000000";
        if (localId.equals(remoteId)) return "Everything up-to-date";
        if (remoteId != null && !force) {
            if (!repo.db.has(remoteId) || !repo.isAncestor(remoteId, localId)) {
                throw new IOException("The remote branch has commits you don't have. Pull first.");
            }
        }
        // Objects the remote already has: everything reachable from remote refs we know locally.
        HashSet<String> known = new HashSet<String>();
        for (String id : a.refs.values()) {
            // Tag refs name tag objects: count the commits behind them.
            String c = GitObject.isId(id) && repo.db.has(id) ? repo.peelToCommit(id) : null;
            if (c != null) known.add(c);
        }
        ArrayList<String> objects = objectsToSend(repo, localId, known);
        if (p != null) p.onProgress("Uploading " + objects.size() + " objects...");
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        StringBuilder caps = new StringBuilder("report-status");
        if (a.caps.contains("side-band-64k")) caps.append(" side-band-64k");
        caps.append(" agent=git/2.45.0-droid_codology");
        writePkt(req, (remoteId == null ? zero : remoteId) + " " + localId + " " + ref + "\0" + caps + "\n");
        writeFlush(req);
        repo.db.writePack(objects, req);
        Http.Stream s = Http.stream("POST", url + "/git-receive-pack", headers("application/x-git-receive-pack-request",
                "application/x-git-receive-pack-result"), req.toByteArray(), "application/x-git-receive-pack-request");
        try {
            check(s);
            InputStream in = new BufferedInputStream(s.body);
            ByteArrayOutputStream report = new ByteArrayOutputStream();
            boolean sideband = a.caps.contains("side-band-64k");
            byte[] pkt;
            while ((pkt = readPkt(in)) != null) {
                if (sideband) {
                    if (pkt.length == 0) continue;
                    if (pkt[0] == 1) report.write(pkt, 1, pkt.length - 1);
                    else if (pkt[0] == 3) throw new IOException("Remote error: " + new String(pkt, 1, pkt.length - 1, GitObject.UTF8).trim());
                } else {
                    writePkt(report, new String(pkt, GitObject.UTF8));
                }
            }
            // The report is itself pkt-lines.
            InputStream rin = new java.io.ByteArrayInputStream(report.toByteArray());
            String line;
            String unpack = null;
            StringBuilder errors = new StringBuilder();
            while ((line = readPktString(rin)) != null) {
                line = line.trim();
                if (line.startsWith("unpack ")) unpack = line.substring(7);
                else if (line.startsWith("ng ")) errors.append(line.substring(3)).append('\n');
            }
            if (unpack != null && !unpack.equals("ok")) throw new IOException("Remote couldn't unpack: " + unpack);
            if (errors.length() > 0) throw new IOException("Push rejected: " + errors.toString().trim());
            repo.updateRef("refs/remotes/" + remoteNameFor(repo) + "/" + branch, localId);
            return "Pushed " + objects.size() + " objects to " + branch;
        } finally {
            s.close();
        }
    }

    private String remoteNameFor(Repo repo) throws IOException {
        GitConfig c = repo.config();
        for (String r : c.subsections("remote")) {
            String u = c.get("remote", r, "url");
            if (u != null && u.replaceAll("/+$", "").equals(url)) return r;
        }
        return "origin";
    }

    /** Commits from {@code tip} back to known ones, with their trees and blobs minus what known commits contain. */
    static ArrayList<String> objectsToSend(Repo repo, String tip, HashSet<String> knownCommits) throws IOException {
        HashSet<String> knownAncestors = new HashSet<String>();
        for (String k : knownCommits) knownAncestors.addAll(repo.ancestors(k, 100000));
        ArrayList<String> commits = new ArrayList<String>();
        HashSet<String> boundary = new HashSet<String>();
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<String>();
        HashSet<String> seen = new HashSet<String>();
        q.add(tip);
        seen.add(tip);
        while (!q.isEmpty()) {
            String c = q.poll();
            if (knownAncestors.contains(c)) {
                boundary.add(c);
                continue;
            }
            commits.add(c);
            for (String par : repo.commit(c).parents) if (seen.add(par)) q.add(par);
        }
        HashSet<String> exclude = new HashSet<String>();
        for (String b : boundary) collectTree(repo, repo.commit(b).tree, exclude, null);
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        for (String c : commits) out.put(c, true);
        for (String c : commits) collectTree(repo, repo.commit(c).tree, exclude, out);
        return new ArrayList<String>(out.keySet());
    }

    private static void collectTree(Repo repo, String tree, HashSet<String> exclude, Map<String, Boolean> out) throws IOException {
        if (exclude.contains(tree) || (out != null && out.containsKey(tree))) return;
        if (out == null) exclude.add(tree);
        else out.put(tree, true);
        for (GitObject.TreeEntry e : GitObject.parseTree(repo.db.read(tree).data)) {
            if (e.isGitlink()) continue;
            if (e.isTree()) collectTree(repo, e.id, exclude, out);
            else if (out == null) exclude.add(e.id);
            else if (!exclude.contains(e.id)) out.put(e.id, true);
        }
    }

    // ------------------------------------------------------------------ clone

    /** Clones into {@code dir} (which must be empty or missing). */
    public static Repo cloneInto(String url, File dir, Auth auth, Progress p) throws IOException {
        if (dir.exists()) {
            String[] k = dir.list();
            if (k != null && k.length > 0) throw new IOException("The folder " + dir.getName() + " isn't empty");
        }
        dir.mkdirs();
        Repo repo = Repo.init(dir, "main");
        try {
            GitConfig c = repo.config();
            c.set("remote", "origin", "url", url);
            c.set("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            c.save();
            Remote r = new Remote(url, auth);
            FetchResult f = r.fetch(repo, "origin", p);
            if (f.defaultBranch == null) return repo; // empty repository
            String id = f.branches.get(f.defaultBranch);
            repo.updateRef("refs/heads/" + f.defaultBranch, id);
            c = repo.config();
            c.set("branch", f.defaultBranch, "remote", "origin");
            c.set("branch", f.defaultBranch, "merge", "refs/heads/" + f.defaultBranch);
            c.save();
            repo.setHead("refs/heads/" + f.defaultBranch);
            if (p != null) p.onProgress("Checking out files...");
            repo.switchTree(repo.flatten(repo.commit(id).tree));
            return repo;
        } catch (IOException e) {
            // Leave nothing half-cloned behind.
            deleteTree(dir);
            throw e;
        }
    }

    static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteTree(c);
        f.delete();
    }

    // ------------------------------------------------------------------ pkt-line

    static void writePkt(OutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(GitObject.UTF8);
        o.write(String.format(java.util.Locale.ROOT, "%04x", b.length + 4).getBytes(GitObject.UTF8));
        o.write(b);
    }

    static void writeFlush(OutputStream o) throws IOException {
        o.write("0000".getBytes(GitObject.UTF8));
    }

    /** Payload of the next pkt-line, or null for a flush packet / end of stream. */
    static byte[] readPkt(InputStream in) throws IOException {
        byte[] len = new byte[4];
        if (readFully(in, len) < 4) return null;
        int n = Integer.parseInt(new String(len, GitObject.UTF8), 16);
        if (n == 0 || n == 1 || n == 2) return null;
        byte[] b = new byte[n - 4];
        if (readFully(in, b) < b.length) throw new IOException("truncated pkt-line");
        return b;
    }

    static String readPktString(InputStream in) throws IOException {
        byte[] b = readPkt(in);
        if (b == null) return null;
        String s = new String(b, GitObject.UTF8);
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n <= 0) break;
            got += n;
        }
        return got;
    }
}
