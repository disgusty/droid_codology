package com.disgusty.droid_codology.term;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The app's own processes as /proc shows them (other apps' are hidden or unreadable). */
public final class Procs {
    private Procs() {
    }

    /** pid → parent pid of every process whose /proc/<pid>/stat is readable. */
    public static Map<Integer, Integer> parents() {
        HashMap<Integer, Integer> m = new HashMap<Integer, Integer>();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return m;
        for (File d : procs) {
            int pid;
            try {
                pid = Integer.parseInt(d.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            int ppid = ppid(d);
            if (ppid >= 0) m.put(pid, ppid);
        }
        return m;
    }

    /** Whether {@code ancestor} is {@code pid}'s parent, grandparent... */
    public static boolean descends(Map<Integer, Integer> parents, int pid, int ancestor) {
        int p = pid;
        for (int hops = 0; p > 1 && hops < 64; hops++) {
            Integer up = parents.get(p);
            p = up == null ? 0 : up;
            if (p == ancestor) return true;
        }
        return false;
    }

    /** Children, grandchildren... of {@code pid}. */
    public static List<Integer> descendants(int pid) {
        Map<Integer, Integer> parents = parents();
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (int p : parents.keySet()) if (descends(parents, p, pid)) out.add(p);
        return out;
    }

    /** SIGKILL to everything below {@code pid}, then to {@code pid}. */
    public static void killTree(int pid) {
        // Stopping the parent first (PRoot, which ignores SIGHUP) would let its programs run on
        // without it, so the descendants go first.
        for (int p : descendants(pid)) android.os.Process.killProcess(p);
        android.os.Process.killProcess(pid);
    }

    /** The parent pid from /proc/<pid>/stat, or -1. */
    static int ppid(File procDir) {
        try {
            InputStream in = new FileInputStream(new File(procDir, "stat"));
            String stat;
            try {
                byte[] b = new byte[1024];
                int n = in.read(b);
                if (n <= 0) return -1;
                stat = new String(b, 0, n, "UTF-8");
            } finally {
                in.close();
            }
            // "pid (comm) state ppid ..."; comm may contain spaces and parentheses
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Integer.parseInt(f[1]);
        } catch (IOException e) {
            return -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
