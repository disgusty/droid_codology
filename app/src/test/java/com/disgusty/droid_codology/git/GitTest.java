package com.disgusty.droid_codology.git;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GitTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8"));
        os.close();
    }

    private static String read(File f) throws IOException {
        return new String(ObjectDb.readFile(f), "UTF-8");
    }

    @Test
    public void objectIds() {
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitObject.id(GitObject.BLOB, "hello\n".getBytes()));
        assertEquals("4b825dc642cb6eb9a060e54bf8d69288fbee4904", GitObject.id(GitObject.TREE, new byte[0]));
    }

    @Test
    public void commitStatusBranchCheckout() throws Exception {
        File w = tmp.newFolder("w");
        Repo r = Repo.init(w, "main");
        write(new File(w, "a.txt"), "one\n");
        write(new File(w, "dir/b.txt"), "two\n");
        write(new File(w, ".gitignore"), "*.log\nbuild/\n");
        write(new File(w, "x.log"), "ignored");
        write(new File(w, "build/out.bin"), "ignored");
        Repo.Status st = r.status();
        assertEquals(Arrays.asList(".gitignore", "a.txt", "dir/b.txt"), paths(st.unstaged));
        GitIndex ix = r.index();
        for (Repo.Change c : st.unstaged) r.stage(ix, c.path);
        r.saveIndex(ix);
        st = r.status();
        assertEquals(3, st.staged.size());
        assertEquals(0, st.unstaged.size());
        Repo.Identity me = new Repo.Identity("Tester", "t@example.com");
        String c1 = r.commit("first\n", me, null);
        assertTrue(r.status().clean());
        assertEquals(c1, r.head());

        write(new File(w, "a.txt"), "one\nchanged\n");
        st = r.status();
        assertEquals(1, st.unstaged.size());
        assertEquals('M', st.unstaged.get(0).kind);
        ix = r.index();
        r.stage(ix, "a.txt");
        r.saveIndex(ix);
        String c2 = r.commit("second\n", me, null);

        r.createBranch("feature", c1);
        r.checkout(c1, "refs/heads/feature");
        assertEquals("one\n", read(new File(w, "a.txt")));
        assertEquals("feature", r.branch());
        r.checkout(c2, "refs/heads/main");
        assertEquals("one\nchanged\n", read(new File(w, "a.txt")));

        // Discard and unstage.
        write(new File(w, "a.txt"), "garbage\n");
        ix = r.index();
        r.discard(ix, "a.txt");
        r.saveIndex(ix);
        assertEquals("one\nchanged\n", read(new File(w, "a.txt")));
        new File(w, "dir/b.txt").delete();
        ix = r.index();
        r.stage(ix, "dir/b.txt");
        r.saveIndex(ix);
        assertEquals('D', r.status().staged.get(0).kind);
        ix = r.index();
        r.unstage(ix, "dir/b.txt");
        r.saveIndex(ix);
        assertEquals(0, r.status().staged.size());

        List<GitObject.Commit> log = r.log(r.head(), 10);
        assertEquals(Arrays.asList("second", "first"), Arrays.asList(log.get(0).subject(), log.get(1).subject()));
        dulwichCheck(w, c2);
    }

    private static List<String> paths(List<Repo.Change> cs) {
        ArrayList<String> out = new ArrayList<String>();
        for (Repo.Change c : cs) out.add(c.path);
        return out;
    }

    /** Our repository must read back with an independent implementation (dulwich). */
    private static void dulwichCheck(File w, String head) throws Exception {
        String py = "import sys\nfrom dulwich.repo import Repo\nr=Repo(sys.argv[1])\nh=r.head().decode()\n"
                + "assert h==sys.argv[2],(h,sys.argv[2])\n"
                + "from dulwich.object_store import tree_lookup_path\n"
                + "for c in r.get_walker():\n    c.commit.check()\n    r[c.commit.tree].check()\n"
                + "idx=r.open_index()\nprint('ok',len(list(idx)))\nr.close()\n";
        String out = python(py, w.getPath(), head);
        Assume.assumeTrue("dulwich not available", out != null);
        assertTrue(out, out.startsWith("ok"));
    }

    private static String python(String script, String... args) throws Exception {
        try {
            ArrayList<String> cmd = new ArrayList<String>(Arrays.asList("python", "-c", script));
            cmd.addAll(Arrays.asList(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
            if (sb.toString().contains("No module named 'dulwich'")) return null;
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    public void diffAndMerge3() {
        String a = "a\nb\nc\nd\ne\n", b = "a\nB\nc\nd\ne\nf\n";
        List<Diff.Hunk> hs = Diff.diff(Diff.lines(a), Diff.lines(b));
        assertEquals(2, hs.size());
        String u = Diff.unified("a", "b", a, b, 1);
        assertTrue(u, u.contains("-b\n+B\n"));
        Diff.Merge3 m = Diff.merge3("1\n2\n3\n4\n5\n", "1\nX\n3\n4\n5\n", "1\n2\n3\n4\nY\n");
        assertEquals("1\nX\n3\n4\nY\n", m.text);
        assertEquals(0, m.conflicts);
        m = Diff.merge3("1\n2\n3\n", "1\nours\n3\n", "1\ntheirs\n3\n");
        assertEquals(1, m.conflicts);
        assertEquals("1\n<<<<<<< ours\nours\n=======\ntheirs\n>>>>>>> theirs\n3\n", m.text);
        // Large mostly-equal files stay fast.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) big.append("line ").append(i).append('\n');
        String big2 = big.toString().replace("line 10000\n", "changed\n");
        assertEquals(1, Diff.diff(Diff.lines(big.toString()), Diff.lines(big2)).size());
    }

    @Test
    public void ignoreRules() throws Exception {
        File w = tmp.newFolder("ig");
        write(new File(w, ".gitignore"), "*.class\n/out\nlogs/\n!keep.class\ndocs/**/*.tmp\n");
        write(new File(w, "sub/.gitignore"), "local.txt\n");
        Ignore ig = new Ignore(w, new File(w, ".git"));
        assertTrue(ig.ignored("A.class", false));
        assertTrue(ig.ignored("x/y/B.class", false));
        assertFalse(ig.ignored("keep.class", false));
        assertTrue(ig.ignored("out", true));
        assertFalse(ig.ignored("x/out", true));
        assertTrue(ig.ignored("a/logs", true));
        assertFalse(ig.ignored("logs", false));
        assertTrue(ig.ignored("docs/a/b/c.tmp", false));
        assertTrue(ig.ignored("sub/local.txt", false));
        assertFalse(ig.ignored("local.txt", false));
    }

    @Test
    public void mergeBranches() throws Exception {
        File w = tmp.newFolder("m");
        Repo r = Repo.init(w, "main");
        Repo.Identity me = new Repo.Identity("T", "t@e");
        write(new File(w, "f.txt"), "1\n2\n3\n4\n5\n");
        GitIndex ix = r.index();
        r.stage(ix, "f.txt");
        r.saveIndex(ix);
        String base = r.commit("base", me, null);
        write(new File(w, "f.txt"), "1\nmain\n3\n4\n5\n");
        ix = r.index();
        r.stage(ix, "f.txt");
        r.saveIndex(ix);
        r.commit("main change", me, null);
        r.createBranch("other", base);
        r.checkout(base, "refs/heads/other");
        write(new File(w, "f.txt"), "1\n2\n3\n4\nother\n");
        write(new File(w, "new.txt"), "new\n");
        ix = r.index();
        r.stage(ix, "f.txt");
        r.stage(ix, "new.txt");
        r.saveIndex(ix);
        String other = r.commit("other change", me, null);
        r.checkout(r.resolve("main"), "refs/heads/main");
        Repo.MergeResult m = r.merge(other, "Merge other", me);
        assertTrue(m.conflicts.isEmpty());
        assertNotNull(m.commit);
        assertEquals(2, r.commit(r.head()).parents.size());
        assertEquals("1\nmain\n3\n4\nother\n", read(new File(w, "f.txt")));
        assertEquals("new\n", read(new File(w, "new.txt")));
        assertTrue(r.status().clean());
    }

    // ------------------------------------------------------------------ network (dulwich smart-HTTP server)

    private Process server;

    private int startServer(File repoDir) throws Exception {
        int port = 18000 + (int) (System.nanoTime() % 1000);
        File script = new File("../tools/git_test_server.py");
        if (!script.isFile()) script = new File("tools/git_test_server.py");
        Assume.assumeTrue(script.isFile());
        try {
            server = new ProcessBuilder("python", script.getPath(), String.valueOf(port), repoDir.getPath(), "--init")
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            Assume.assumeNoException(e);
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(server.getInputStream()));
        String line = br.readLine();
        if (line == null || !line.startsWith("ready")) {
            server.destroy();
            Assume.assumeTrue("git test server didn't start: " + line, false);
        }
        for (int i = 0; i < 50; i++) {
            try {
                new Socket("127.0.0.1", port).close();
                break;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        return port;
    }

    @Test
    public void cloneCommitPushFetchPull() throws Exception {
        File bare = new File(tmp.getRoot(), "remote.git");
        int port = startServer(bare);
        try {
            String url = "http://127.0.0.1:" + port + "/";
            File w = new File(tmp.getRoot(), "clone");
            Repo r = Remote.cloneInto(url, w, null, null);
            assertEquals("main", r.branch());
            assertTrue(read(new File(w, "README.md")).contains("revision 3"));
            assertTrue(read(new File(w, "big.txt")).endsWith("edit 3\n"));
            assertEquals("print(\"hello\")\n", read(new File(w, "src/hello.py")));
            assertEquals(4, r.log(r.head(), 100).size());
            assertTrue(r.status().clean());

            // Commit and push.
            write(new File(w, "README.md"), "# Test repo\n\npushed from droid_codology\n");
            write(new File(w, "docs/new.md"), "new file\n");
            GitIndex ix = r.index();
            r.stage(ix, "README.md");
            r.stage(ix, "docs/new.md");
            r.saveIndex(ix);
            String mine = r.commit("from droid_codology\n", new Repo.Identity("Droid", "d@example.com"), null);
            String msg = new Remote(url, null).push(r, mine, "main", false, null);
            assertTrue(msg, msg.startsWith("Pushed"));
            String check = python("import sys\nfrom dulwich.repo import Repo\nr=Repo(sys.argv[1])\n"
                    + "h=r.refs[b'refs/heads/main'].decode()\nprint(h)\nc=r[h.encode()]\nprint(c.message.decode().strip())\n"
                    + "from dulwich.object_store import tree_lookup_path\n"
                    + "m,s=tree_lookup_path(r.__getitem__, c.tree, b'docs/new.md')\nprint(r[s].data.decode().strip())\nr.close()\n", bare.getPath());
            assertEquals(mine + "\nfrom droid_codology\nnew file\n", check.replace("\r", ""));

            // A second clone sees it; then it commits, pushes, and the first one pulls.
            File w2 = new File(tmp.getRoot(), "clone2");
            Repo r2 = Remote.cloneInto(url, w2, null, null);
            assertEquals(mine, r2.head());
            write(new File(w2, "src/hello.py"), "print('hello again')\n");
            GitIndex ix2 = r2.index();
            r2.stage(ix2, "src/hello.py");
            r2.saveIndex(ix2);
            String theirs = r2.commit("second clone\n", new Repo.Identity("Other", "o@e"), null);
            new Remote(url, null).push(r2, theirs, "main", false, null);

            // Pushing a stale branch is refused.
            write(new File(w, "README.md"), "diverged\n");
            ix = r.index();
            r.stage(ix, "README.md");
            r.saveIndex(ix);
            String diverged = r.commit("diverged\n", new Repo.Identity("Droid", "d@e"), null);
            try {
                new Remote(url, null).push(r, diverged, "main", false, null);
                fail("push of a diverged branch must be refused");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("Pull first"));
            }
            new Remote(url, null).fetch(r, "origin", null);
            Repo.MergeResult m = r.merge(r.resolve("origin/main"), "Merge origin/main", new Repo.Identity("Droid", "d@e"));
            assertTrue(m.conflicts.isEmpty());
            assertEquals("print('hello again')\n", read(new File(w, "src/hello.py")));
            assertEquals("diverged\n", read(new File(w, "README.md")));
            String merged = r.head();
            assertTrue(new Remote(url, null).push(r, merged, "main", false, null).startsWith("Pushed"));
            assertArrayEquals(new Object[]{true}, new Object[]{r.status().clean()});
        } finally {
            server.destroy();
        }
    }

    @Test
    public void cloneFromGitHub() throws Exception {
        try {
            java.net.InetAddress.getByName("github.com");
        } catch (IOException e) {
            Assume.assumeNoException(e);
        }
        File w = new File(tmp.getRoot(), "hello");
        Repo r = Remote.cloneInto("https://github.com/octocat/Hello-World.git", w, null, null);
        assertTrue(new File(w, "README").isFile());
        assertTrue(r.log(r.head(), 10).size() >= 1);
        assertTrue(r.status().clean());
    }
}
