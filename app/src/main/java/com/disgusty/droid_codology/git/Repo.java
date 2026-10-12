package com.disgusty.droid_codology.git;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TimeZone;
import java.util.TreeMap;

/** A Git repository with a work tree: refs, status, staging, commits, branches, checkout, merge. */
public final class Repo {
    public final File work, git;
    public final ObjectDb db;

    private Repo(File work, File git) {
        this.work = work;
        this.git = git;
        this.db = new ObjectDb(new File(git, "objects"));
    }

    /** The repository containing {@code dir} (searching parents), or null. */
    public static Repo find(File dir) {
        for (File d = dir; d != null; d = d.getParentFile()) {
            File g = new File(d, ".git");
            if (new File(g, "HEAD").isFile()) return new Repo(d, g);
        }
        return null;
    }

    public static Repo init(File work, String branch) throws IOException {
        File g = new File(work, ".git");
        for (String d : new String[]{"objects/pack", "objects/info", "refs/heads", "refs/tags", "info"}) new File(g, d).mkdirs();
        write(new File(g, "HEAD"), "ref: refs/heads/" + branch + "\n");
        if (!new File(g, "config").isFile()) {
            write(new File(g, "config"), "[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = false\n\tlogallrefupdates = true\n");
        }
        write(new File(g, "description"), "Unnamed repository; edit this file 'description' to name the repository.\n");
        return new Repo(work, g);
    }

    public GitConfig config() throws IOException {
        return new GitConfig(new File(git, "config"));
    }

    // ------------------------------------------------------------------ refs

    /** Raw content of a ref ("ref: ..." or an id), or null. */
    public String readRefRaw(String name) throws IOException {
        File f = new File(git, name);
        if (f.isFile()) return new String(ObjectDb.readFile(f), GitObject.UTF8).trim();
        return packedRefs().get(name);
    }

    private Map<String, String> packedRefs() throws IOException {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        File f = new File(git, "packed-refs");
        if (!f.isFile()) return m;
        for (String l : new String(ObjectDb.readFile(f), GitObject.UTF8).split("\n")) {
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("^")) continue;
            int sp = l.indexOf(' ');
            if (sp == 40) m.put(l.substring(41).trim(), l.substring(0, 40));
        }
        return m;
    }

    /** Commit id a ref points to (following symbolic refs), or null. */
    public String resolveRef(String name) throws IOException {
        for (int depth = 0; depth < 10 && name != null; depth++) {
            String raw = readRefRaw(name);
            if (raw == null) return null;
            if (raw.startsWith("ref:")) name = raw.substring(4).trim();
            else return raw;
        }
        return null;
    }

    /** "refs/heads/main" when HEAD is on a branch, null when detached. */
    public String headRef() throws IOException {
        String raw = readRefRaw("HEAD");
        return raw != null && raw.startsWith("ref:") ? raw.substring(4).trim() : null;
    }

    public String branch() throws IOException {
        String r = headRef();
        return r == null ? null : r.startsWith("refs/heads/") ? r.substring(11) : r;
    }

    public String head() throws IOException {
        return resolveRef("HEAD");
    }

    /** Branch names, remote-tracking refs ("origin/main") or commit ids. */
    public String resolve(String rev) throws IOException {
        if (GitObject.isId(rev)) return rev;
        for (String n : new String[]{rev, "refs/" + rev, "refs/heads/" + rev, "refs/tags/" + rev, "refs/remotes/" + rev}) {
            String id = resolveRef(n);
            if (id != null) return peel(id);
        }
        return null;
    }

    /** The commit behind {@code id} (peeling annotated tags), or null if it isn't one we have. */
    public String peelToCommit(String id) throws IOException {
        String c = peel(id);
        GitObject.Raw r = db.readOrNull(c);
        return r != null && r.type == GitObject.COMMIT ? c : null;
    }

    /** Annotated tag -> the commit it points at. */
    private String peel(String id) throws IOException {
        for (int i = 0; i < 5; i++) {
            GitObject.Raw r = db.readOrNull(id);
            if (r == null || r.type != GitObject.TAG) return id;
            String s = new String(r.data, GitObject.UTF8);
            id = s.substring(7, 47);
        }
        return id;
    }

    public void updateRef(String name, String id) throws IOException {
        if (!GitObject.isId(id)) throw new IOException("not an object id: " + id);
        File f = refFile(name);
        f.getParentFile().mkdirs();
        File lock = new File(f.getPath() + ".lock");
        write(lock, id + "\n");
        // rename() replaces the old ref in one step on Android; elsewhere (Windows) delete first.
        if (!lock.renameTo(f) && (!f.delete() || !lock.renameTo(f))) throw new IOException("can't update " + name);
    }

    /**
     * Ref names come from remote servers too: "refs/heads/../../x" would point anywhere. The
     * rules of git check-ref-format, and the file must stay inside .git.
     */
    static boolean validRefName(String name) {
        if (name == null || !name.startsWith("refs/") || name.endsWith("/") || name.endsWith(".") || name.endsWith(".lock")
                || name.contains("..") || name.contains("//") || name.contains("@{") || name.contains("/.")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == ' ' || c == '~' || c == '^' || c == ':' || c == '?' || c == '*' || c == '[' || c == '\\') return false;
        }
        return true;
    }

    private File refFile(String name) throws IOException {
        if (!validRefName(name)) throw new IOException("Invalid ref name: " + name);
        File f = new File(git, name).getCanonicalFile();
        if (!f.getPath().startsWith(git.getCanonicalPath() + File.separator)) throw new IOException("Invalid ref name: " + name);
        return f;
    }

    public void deleteRef(String name) throws IOException {
        refFile(name).delete();
        Map<String, String> packed = packedRefs();
        if (packed.remove(name) != null) {
            StringBuilder sb = new StringBuilder("# pack-refs with: peeled fully-peeled sorted \n");
            for (Map.Entry<String, String> e : packed.entrySet()) sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
            write(new File(git, "packed-refs"), sb.toString());
        }
    }

    public void setHead(String target) throws IOException {
        write(new File(git, "HEAD"), GitObject.isId(target) ? target + "\n" : "ref: " + target + "\n");
    }

    /** refs under {@code prefix} ("refs/heads/") -> id. */
    public TreeMap<String, String> refs(String prefix) throws IOException {
        TreeMap<String, String> out = new TreeMap<String, String>();
        for (Map.Entry<String, String> e : packedRefs().entrySet()) if (e.getKey().startsWith(prefix)) out.put(e.getKey(), e.getValue());
        collectLoose(new File(git, prefix), prefix, out);
        return out;
    }

    private void collectLoose(File dir, String prefix, Map<String, String> out) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectLoose(f, prefix + f.getName() + "/", out);
            else if (!f.getName().endsWith(".lock")) {
                String v = new String(ObjectDb.readFile(f), GitObject.UTF8).trim();
                if (GitObject.isId(v)) out.put(prefix + f.getName(), v);
            }
        }
    }

    public List<String> branches() throws IOException {
        ArrayList<String> out = new ArrayList<String>();
        for (String r : refs("refs/heads/").keySet()) out.add(r.substring(11));
        return out;
    }

    // ------------------------------------------------------------------ objects

    public GitObject.Commit commit(String id) throws IOException {
        GitObject.Raw r = db.read(id);
        if (r.type != GitObject.COMMIT) throw new IOException(id + " is not a commit");
        return GitObject.parseCommit(id, r.data);
    }

    public byte[] blob(String id) throws IOException {
        return db.read(id).data;
    }

    /** All files of a tree: path -> entry (blobs, symlinks and submodules). */
    public TreeMap<String, GitObject.TreeEntry> flatten(String treeId) throws IOException {
        TreeMap<String, GitObject.TreeEntry> out = new TreeMap<String, GitObject.TreeEntry>();
        if (treeId != null) flatten(treeId, "", out);
        return out;
    }

    private void flatten(String treeId, String prefix, Map<String, GitObject.TreeEntry> out) throws IOException {
        for (GitObject.TreeEntry e : GitObject.parseTree(db.read(treeId).data)) {
            // A crafted repository could name an entry ".." or ".git" to write outside the work tree
            // (or into its own config) on checkout: refuse such trees, as git does.
            String n = e.name;
            if (n.isEmpty() || n.equals(".") || n.equals("..") || n.indexOf('/') >= 0 || n.indexOf('\\') >= 0
                    || n.indexOf('\0') >= 0 || n.equalsIgnoreCase(".git")) {
                throw new IOException("The repository contains an unsafe path: " + prefix + n);
            }
            String p = prefix + n;
            if (e.isTree()) flatten(e.id, p + "/", out);
            else out.put(p, e);
        }
    }

    public TreeMap<String, GitObject.TreeEntry> headFiles() throws IOException {
        String h = head();
        return h == null ? new TreeMap<String, GitObject.TreeEntry>() : flatten(commit(h).tree);
    }

    // ------------------------------------------------------------------ index & status

    public GitIndex index() throws IOException {
        return GitIndex.read(new File(git, "index"));
    }

    public void saveIndex(GitIndex ix) throws IOException {
        ix.write(new File(git, "index"));
    }

    public static final class Change implements Comparable<Change> {
        public final String path;
        /** 'A' added, 'M' modified, 'D' deleted, '?' untracked. */
        public final char kind;

        Change(String path, char kind) {
            this.path = path;
            this.kind = kind;
        }

        @Override
        public int compareTo(Change o) {
            return path.compareTo(o.path);
        }
    }

    public static final class Status {
        public final ArrayList<Change> staged = new ArrayList<Change>();
        public final ArrayList<Change> unstaged = new ArrayList<Change>();
        public String branch, head, upstream;
        public int ahead, behind;

        public boolean clean() {
            for (Change c : unstaged) if (c.kind != '?') return false;
            return staged.isEmpty();
        }
    }

    public Status status() throws IOException {
        Status st = new Status();
        st.branch = branch();
        st.head = head();
        TreeMap<String, GitObject.TreeEntry> headFiles = headFiles();
        GitIndex ix = index();
        // Staged: index vs HEAD.
        for (GitIndex.Entry e : ix.entries.values()) {
            GitObject.TreeEntry h = headFiles.get(e.path);
            if (h == null) st.staged.add(new Change(e.path, 'A'));
            else if (!h.id.equals(e.id)) st.staged.add(new Change(e.path, 'M'));
        }
        for (String p : headFiles.keySet()) if (!ix.entries.containsKey(p)) st.staged.add(new Change(p, 'D'));
        // Unstaged: work tree vs index.
        boolean indexDirty = false;
        for (GitIndex.Entry e : ix.entries.values()) {
            if (e.mode == 0160000) continue;
            File f = file(e.path);
            if (!f.isFile()) {
                st.unstaged.add(new Change(e.path, 'D'));
                continue;
            }
            if (!ix.maybeChanged(e, f)) continue;
            String id = hashFile(f);
            if (!id.equals(e.id)) st.unstaged.add(new Change(e.path, 'M'));
            else {
                // Same content: refresh the stat data so the next status is fast.
                e.mtime = f.lastModified();
                e.size = f.length();
                indexDirty = true;
            }
        }
        Ignore ig = new Ignore(work, git);
        ArrayList<String> untracked = new ArrayList<String>();
        walk(work, "", ix, ig, untracked);
        for (String p : untracked) st.unstaged.add(new Change(p, '?'));
        if (indexDirty && new File(git, "index").isFile()) {
            try {
                saveIndex(ix);
            } catch (IOException ignored) {
            }
        }
        Collections.sort(st.staged);
        Collections.sort(st.unstaged);
        aheadBehind(st);
        return st;
    }

    private void walk(File dir, String prefix, GitIndex ix, Ignore ig, List<String> out) {
        File[] fs = dir.listFiles();
        if (fs == null || out.size() > 5000) return;
        for (File f : fs) {
            String p = prefix + f.getName();
            if (f.getName().equals(".git")) continue;
            boolean isDir = f.isDirectory();
            if (isDir) {
                if (new File(f, ".git").exists()) continue; // nested repository
                if (ig.ignored(p, true)) continue;
                walk(f, p + "/", ix, ig, out);
            } else if (!ix.entries.containsKey(p) && !ig.ignored(p, false)) {
                out.add(p);
            }
        }
    }

    private void aheadBehind(Status st) throws IOException {
        if (st.branch == null || st.head == null) return;
        GitConfig c = config();
        String remote = c.get("branch", st.branch, "remote"), merge = c.get("branch", st.branch, "merge");
        if (remote == null || merge == null || !merge.startsWith("refs/heads/")) return;
        String up = "refs/remotes/" + remote + "/" + merge.substring(11);
        String upId = resolveRef(up);
        st.upstream = remote + "/" + merge.substring(11);
        if (upId == null) return;
        HashSet<String> mine = ancestors(st.head, 5000), theirs = ancestors(upId, 5000);
        for (String id : mine) if (!theirs.contains(id)) st.ahead++;
        for (String id : theirs) if (!mine.contains(id)) st.behind++;
    }

    public File file(String path) {
        return new File(work, path.replace('/', File.separatorChar));
    }

    /** {@link #file} for writing or deleting: must stay inside the work tree and out of .git. */
    File safeFile(String path) throws IOException {
        File f = file(path).getCanonicalFile();
        String root = work.getCanonicalPath();
        if (!f.getPath().startsWith(root + File.separator) || f.getPath().startsWith(git.getCanonicalPath() + File.separator)) {
            throw new IOException("Refusing to touch a path outside the work tree: " + path);
        }
        return f;
    }

    public String hashFile(File f) throws IOException {
        return GitObject.id(GitObject.BLOB, ObjectDb.readFile(f));
    }

    /** git add: stages the file's current content (or its deletion). */
    public void stage(GitIndex ix, String path) throws IOException {
        File f = file(path);
        if (!f.isFile()) {
            ix.entries.remove(path);
            return;
        }
        byte[] data = ObjectDb.readFile(f);
        String id = db.write(GitObject.BLOB, data);
        GitIndex.Entry old = ix.entries.get(path);
        GitIndex.Entry e = ix.entryFor(path, id, f);
        if (old != null && (old.mode == 0100755)) e.mode = old.mode;
        ix.entries.put(path, e);
    }

    /** git restore --staged: the index entry goes back to HEAD's version. */
    public void unstage(GitIndex ix, String path) throws IOException {
        GitObject.TreeEntry h = headFiles().get(path);
        if (h == null) {
            ix.entries.remove(path);
            return;
        }
        GitIndex.Entry e = ix.entryFor(path, h.id, null);
        e.mode = Integer.parseInt(h.mode, 8);
        File f = file(path);
        // Keep stat data only if the file still matches HEAD, so status re-hashes otherwise.
        if (f.isFile() && hashFile(f).equals(h.id)) {
            e.mtime = f.lastModified();
            e.size = f.length();
        }
        ix.entries.put(path, e);
    }

    /** git restore: the work tree file goes back to the index version (untracked files are deleted). */
    public void discard(GitIndex ix, String path) throws IOException {
        GitIndex.Entry e = ix.entries.get(path);
        File f = file(path);
        if (e == null) {
            if (f.isFile() && !f.delete()) throw new IOException("can't delete " + path);
            return;
        }
        writeFile(f, blob(e.id), e.mode);
        e.mtime = f.lastModified();
        e.size = f.length();
    }

    // ------------------------------------------------------------------ commit

    public String writeTree(GitIndex ix) throws IOException {
        TreeMap<String, Object> root = new TreeMap<String, Object>();
        for (GitIndex.Entry e : ix.entries.values()) {
            String[] parts = e.path.split("/");
            TreeMap<String, Object> node = root;
            for (int i = 0; i < parts.length - 1; i++) {
                @SuppressWarnings("unchecked")
                TreeMap<String, Object> child = (TreeMap<String, Object>) node.get(parts[i] + "/");
                if (child == null) node.put(parts[i] + "/", child = new TreeMap<String, Object>());
                node = child;
            }
            node.put(parts[parts.length - 1], e);
        }
        return writeTreeNode(root);
    }

    private String writeTreeNode(TreeMap<String, Object> node) throws IOException {
        ArrayList<GitObject.TreeEntry> entries = new ArrayList<GitObject.TreeEntry>();
        for (Map.Entry<String, Object> m : node.entrySet()) {
            if (m.getKey().endsWith("/")) {
                @SuppressWarnings("unchecked")
                String id = writeTreeNode((TreeMap<String, Object>) m.getValue());
                entries.add(new GitObject.TreeEntry("40000", m.getKey().substring(0, m.getKey().length() - 1), id));
            } else {
                GitIndex.Entry e = (GitIndex.Entry) m.getValue();
                entries.add(new GitObject.TreeEntry(Integer.toOctalString(e.mode), m.getKey(), e.id));
            }
        }
        return db.write(GitObject.TREE, GitObject.formatTree(entries));
    }

    public static final class Identity {
        public final String name, email;

        public Identity(String name, String email) {
            this.name = name;
            this.email = email;
        }
    }

    public Identity identity(Identity fallback) throws IOException {
        GitConfig c = config();
        String n = c.get("user", null, "name"), e = c.get("user", null, "email");
        if (n == null && fallback != null) n = fallback.name;
        if (e == null && fallback != null) e = fallback.email;
        return new Identity(n == null || n.isEmpty() ? "droid_codology user" : n, e == null ? "" : e);
    }

    /** Commits the index; returns the new commit id. */
    public String commit(String message, Identity who, List<String> extraParents) throws IOException {
        GitIndex ix = index();
        // During a merge, files still holding conflict markers must not be committed (and pushed).
        if (new File(git, "MERGE_HEAD").exists()) {
            for (GitIndex.Entry e : ix.entries.values()) {
                if (e.mode == 0160000 || !db.has(e.id)) continue;
                String s = new String(blob(e.id), GitObject.UTF8);
                if (s.startsWith("<<<<<<< ") || s.contains("\n<<<<<<< ") && s.contains("\n>>>>>>> ")) {
                    throw new IOException("Resolve the conflicts in " + e.path + " (remove the <<<<<<< ======= >>>>>>> markers) and stage it again");
                }
            }
        }
        String tree = writeTree(ix);
        ArrayList<String> parents = new ArrayList<String>();
        String h = head();
        if (h != null) parents.add(h);
        if (extraParents != null) parents.addAll(extraParents);
        if (h != null && extraParents == null && commit(h).tree.equals(tree)) throw new IOException("Nothing to commit");
        String sig = GitObject.signature(who.name, who.email, System.currentTimeMillis(), TimeZone.getDefault());
        String id = db.write(GitObject.COMMIT, GitObject.formatCommit(tree, parents, sig, sig, message));
        String ref = headRef();
        if (ref != null) updateRef(ref, id);
        else setHead(id);
        File mergeHead = new File(git, "MERGE_HEAD");
        if (mergeHead.exists()) mergeHead.delete();
        return id;
    }

    // ------------------------------------------------------------------ history

    public List<GitObject.Commit> log(String start, int max) throws IOException {
        ArrayList<GitObject.Commit> out = new ArrayList<GitObject.Commit>();
        if (start == null) return out;
        PriorityQueue<GitObject.Commit> q = new PriorityQueue<GitObject.Commit>(16, new Comparator<GitObject.Commit>() {
            @Override
            public int compare(GitObject.Commit a, GitObject.Commit b) {
                long x = b.time(), y = a.time();
                return x < y ? -1 : x == y ? 0 : 1;
            }
        });
        HashSet<String> seen = new HashSet<String>();
        q.add(commit(start));
        seen.add(start);
        while (!q.isEmpty() && out.size() < max) {
            GitObject.Commit c = q.poll();
            out.add(c);
            for (String p : c.parents) {
                if (seen.add(p) && db.has(p)) q.add(commit(p));
            }
        }
        return out;
    }

    public HashSet<String> ancestors(String id, int limit) throws IOException {
        HashSet<String> seen = new HashSet<String>();
        ArrayDeque<String> q = new ArrayDeque<String>();
        if (id == null) return seen;
        q.add(id);
        seen.add(id);
        while (!q.isEmpty() && seen.size() < limit) {
            String c = q.poll();
            if (!db.has(c)) continue;
            for (String p : commit(c).parents) if (seen.add(p)) q.add(p);
        }
        return seen;
    }

    public boolean isAncestor(String ancestor, String of) throws IOException {
        return ancestor == null || ancestors(of, 200000).contains(ancestor);
    }

    /** Nearest common ancestor (by walking both histories). */
    public String mergeBase(String a, String b) throws IOException {
        HashSet<String> as = ancestors(a, 200000);
        ArrayDeque<String> q = new ArrayDeque<String>();
        HashSet<String> seen = new HashSet<String>();
        q.add(b);
        seen.add(b);
        while (!q.isEmpty()) {
            String c = q.poll();
            if (as.contains(c)) return c;
            if (!db.has(c)) continue;
            for (String p : commit(c).parents) if (seen.add(p)) q.add(p);
        }
        return null;
    }

    // ------------------------------------------------------------------ branches & checkout

    public void createBranch(String name, String startId) throws IOException {
        if (!name.matches("[^\\s~^:?*\\[\\\\]+") || name.contains("..") || name.endsWith("/") || name.endsWith(".lock")) {
            throw new IOException("Invalid branch name: " + name);
        }
        if (resolveRef("refs/heads/" + name) != null) throw new IOException("Branch " + name + " already exists");
        if (!validRefName("refs/heads/" + name)) throw new IOException("Invalid branch name: " + name);
        // Before the first commit there is nothing to point at: the branch is just where HEAD goes.
        if (startId == null) return;
        updateRef("refs/heads/" + name, startId);
    }

    public void deleteBranch(String name) throws IOException {
        if (name.equals(branch())) throw new IOException("Can't delete the current branch");
        deleteRef("refs/heads/" + name);
        GitConfig c = config();
        c.removeSection("branch", name);
        c.save();
    }

    /**
     * Switches the work tree and index to {@code commitId}; refuses when tracked files have
     * uncommitted changes. {@code ref} is the branch ref to put HEAD on, or null to detach.
     */
    public void checkout(String commitId, String ref) throws IOException {
        Status st = status();
        if (!st.clean()) throw new IOException("Commit or discard your changes before switching branches");
        TreeMap<String, GitObject.TreeEntry> target = flatten(commit(commitId).tree);
        switchTree(target);
        if (ref != null) setHead(ref);
        else setHead(commitId);
    }

    /** Makes index and work tree match {@code target} (tracked files only). */
    void switchTree(Map<String, GitObject.TreeEntry> target) throws IOException {
        GitIndex ix = index();
        // Untracked files the switch would overwrite with different content: stop before touching
        // anything (git: "untracked working tree files would be overwritten").
        ArrayList<String> clobbered = new ArrayList<String>();
        for (Map.Entry<String, GitObject.TreeEntry> t : target.entrySet()) {
            if (ix.entries.containsKey(t.getKey()) || t.getValue().isGitlink()) continue;
            File f = safeFile(t.getKey());
            if (f.isFile() && !java.util.Arrays.equals(ObjectDb.readFile(f), blob(t.getValue().id))) clobbered.add(t.getKey());
            if (clobbered.size() >= 20) break;
        }
        if (!clobbered.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String p : clobbered) sb.append("\n  ").append(p);
            throw new IOException("These untracked files would be overwritten; move or commit them first:" + sb);
        }
        for (GitIndex.Entry e : new ArrayList<GitIndex.Entry>(ix.entries.values())) {
            if (!target.containsKey(e.path)) {
                File f = safeFile(e.path);
                f.delete();
                pruneEmptyDirs(f.getParentFile());
                ix.entries.remove(e.path);
            }
        }
        for (Map.Entry<String, GitObject.TreeEntry> t : target.entrySet()) {
            GitObject.TreeEntry te = t.getValue();
            if (te.isGitlink()) {
                GitIndex.Entry e = ix.entryFor(t.getKey(), te.id, null);
                e.mode = 0160000;
                ix.entries.put(t.getKey(), e);
                continue;
            }
            GitIndex.Entry cur = ix.entries.get(t.getKey());
            File f = safeFile(t.getKey());
            if (cur != null && cur.id.equals(te.id) && f.isFile() && !ix.maybeChanged(cur, f)) continue;
            int mode = Integer.parseInt(te.mode, 8);
            writeFile(f, blob(te.id), mode);
            GitIndex.Entry e = ix.entryFor(t.getKey(), te.id, f);
            e.mode = mode;
            ix.entries.put(t.getKey(), e);
        }
        saveIndex(ix);
    }

    private void pruneEmptyDirs(File d) {
        while (d != null && !d.equals(work)) {
            String[] k = d.list();
            if (k == null || k.length > 0 || !d.delete()) return;
            d = d.getParentFile();
        }
    }

    static void writeFile(File f, byte[] data, int mode) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("can't create " + parent);
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
        if (mode == 0100755) f.setExecutable(true, false);
    }

    // ------------------------------------------------------------------ merge

    public static final class MergeResult {
        public String commit;
        public boolean fastForward, upToDate;
        public final ArrayList<String> conflicts = new ArrayList<String>();
    }

    /**
     * Merges {@code theirs} into HEAD: fast-forward when possible, otherwise a three-way merge by
     * file (and by line inside files changed on both sides). Conflicting files get conflict
     * markers and the merge is left for the user to commit.
     */
    public MergeResult merge(String theirs, String message, Identity who) throws IOException {
        MergeResult r = new MergeResult();
        String ours = head();
        if (theirs == null || theirs.equals(ours) || (ours != null && isAncestor(theirs, ours))) {
            r.upToDate = true;
            return r;
        }
        if (!status().clean()) throw new IOException("Commit or discard your changes before pulling");
        if (ours == null || isAncestor(ours, theirs)) {
            switchTree(flatten(commit(theirs).tree));
            String ref = headRef();
            if (ref != null) updateRef(ref, theirs);
            else setHead(theirs);
            r.fastForward = true;
            r.commit = theirs;
            return r;
        }
        String base = mergeBase(ours, theirs);
        Map<String, GitObject.TreeEntry> b = base == null ? new TreeMap<String, GitObject.TreeEntry>() : flatten(commit(base).tree);
        Map<String, GitObject.TreeEntry> o = flatten(commit(ours).tree), t = flatten(commit(theirs).tree);
        TreeMap<String, GitObject.TreeEntry> result = new TreeMap<String, GitObject.TreeEntry>();
        HashMap<String, byte[]> conflicted = new HashMap<String, byte[]>();
        java.util.TreeSet<String> all = new java.util.TreeSet<String>();
        all.addAll(b.keySet());
        all.addAll(o.keySet());
        all.addAll(t.keySet());
        for (String p : all) {
            GitObject.TreeEntry be = b.get(p), oe = o.get(p), te = t.get(p);
            String bi = be == null ? null : be.id, oi = oe == null ? null : oe.id, ti = te == null ? null : te.id;
            if (eq(oi, ti)) {
                if (oe != null) result.put(p, oe);
            } else if (eq(bi, oi)) {
                if (te != null) result.put(p, te);
            } else if (eq(bi, ti)) {
                if (oe != null) result.put(p, oe);
            } else if (oe != null && te != null && !oe.isGitlink() && !te.isGitlink()
                    && (be == null || isText(be.id)) && isText(oe.id) && isText(te.id)) {
                Diff.Merge3 m = Diff.merge3(be == null ? "" : text(be.id), text(oe.id), text(te.id));
                byte[] data = m.text.getBytes(GitObject.UTF8);
                String id = db.write(GitObject.BLOB, data);
                result.put(p, new GitObject.TreeEntry(oe.mode, p, id));
                if (m.conflicts > 0) {
                    r.conflicts.add(p);
                    conflicted.put(p, data);
                }
            } else {
                // Deleted on one side and changed on the other, or changed on both sides but not
                // UTF-8 text (binary, another encoding: a line merge would corrupt it): keep ours
                // and report the conflict.
                result.put(p, oe != null ? oe : te);
                r.conflicts.add(p);
            }
        }
        switchTree(result);
        if (!r.conflicts.isEmpty()) {
            write(new File(git, "MERGE_HEAD"), theirs + "\n");
            write(new File(git, "MERGE_MSG"), message + "\n");
            return r;
        }
        ArrayList<String> extra = new ArrayList<String>();
        extra.add(theirs);
        r.commit = commit(message, who, extra);
        return r;
    }

    /** The other parent of an unfinished merge (conflicts being resolved), or null. */
    public String mergeHead() throws IOException {
        File f = new File(git, "MERGE_HEAD");
        return f.isFile() ? new String(ObjectDb.readFile(f), GitObject.UTF8).trim() : null;
    }

    private String text(String id) throws IOException {
        return new String(blob(id), GitObject.UTF8);
    }

    /** Valid UTF-8 without NUL bytes: safe to merge line by line and write back unchanged. */
    private boolean isText(String id) throws IOException {
        byte[] b = blob(id);
        for (int i = 0; i < Math.min(b.length, 8000); i++) if (b[i] == 0) return false;
        try {
            java.nio.charset.Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(s.getBytes(GitObject.UTF8));
        } finally {
            os.close();
        }
    }
}
