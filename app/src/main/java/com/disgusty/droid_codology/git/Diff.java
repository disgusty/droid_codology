package com.disgusty.droid_codology.git;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Line diffs (Myers) and three-way merges. */
public final class Diff {
    private Diff() {
    }

    /** base[bs..be) was replaced by other[os..oe). */
    public static final class Hunk {
        public final int bs, be, os, oe;

        Hunk(int bs, int be, int os, int oe) {
            this.bs = bs;
            this.be = be;
            this.os = os;
            this.oe = oe;
        }
    }

    public static String[] lines(String s) {
        if (s.isEmpty()) return new String[0];
        ArrayList<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out.toArray(new String[out.size()]);
    }

    /** Changed regions turning {@code a} into {@code b}. */
    public static List<Hunk> diff(String[] a, String[] b) {
        // Trim common prefix and suffix first: most edits are small.
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) pre++;
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) suf++;
        int n = a.length - pre - suf, m = b.length - pre - suf;
        ArrayList<Hunk> out = new ArrayList<Hunk>();
        if (n == 0 && m == 0) return out;
        if (n == 0 || m == 0) {
            out.add(new Hunk(pre, pre + n, pre, pre + m));
            return out;
        }
        HashMap<String, Integer> ids = new HashMap<String, Integer>();
        int[] x = new int[n], y = new int[m];
        for (int i = 0; i < n; i++) x[i] = intern(ids, a[pre + i]);
        for (int i = 0; i < m; i++) y[i] = intern(ids, b[pre + i]);
        boolean[] keepA = new boolean[n], keepB = new boolean[m];
        if (!myers(x, y, keepA, keepB, 1500)) {
            out.add(new Hunk(pre, pre + n, pre, pre + m));
            return out;
        }
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && keepA[i] && keepB[j]) {
                i++;
                j++;
                continue;
            }
            int si = i, sj = j;
            while (i < n && !keepA[i]) i++;
            while (j < m && !keepB[j]) j++;
            out.add(new Hunk(pre + si, pre + i, pre + sj, pre + j));
        }
        return out;
    }

    private static int intern(HashMap<String, Integer> ids, String s) {
        Integer v = ids.get(s);
        if (v == null) ids.put(s, v = ids.size());
        return v;
    }

    /** Marks lines of the longest common subsequence; false if more than maxD edits are needed. */
    private static boolean myers(int[] a, int[] b, boolean[] keepA, boolean[] keepB, int maxD) {
        int n = a.length, m = b.length, max = n + m;
        int limit = Math.min(max, maxD);
        int off = limit + 1;
        int[] v = new int[2 * limit + 3];
        ArrayList<int[]> trace = new ArrayList<int[]>();
        int dFound = -1;
        outer:
        for (int d = 0; d <= limit; d++) {
            // Only diagonals -d..d matter at step d: keep that slice (memory O(D^2), not O(D*(N+M))).
            trace.add(java.util.Arrays.copyOfRange(v, off - d, off + d + 1));
            for (int k = -d; k <= d; k += 2) {
                int xx;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) xx = v[off + k + 1];
                else xx = v[off + k - 1] + 1;
                int yy = xx - k;
                while (xx < n && yy < m && a[xx] == b[yy]) {
                    xx++;
                    yy++;
                }
                v[off + k] = xx;
                if (xx >= n && yy >= m) {
                    dFound = d;
                    break outer;
                }
            }
        }
        if (dFound < 0) return false;
        int xx = n, yy = m;
        for (int d = dFound; d > 0; d--) {
            int[] pv = trace.get(d); // index i <-> diagonal i - d
            int k = xx - yy;
            int prevK = (k == -d || (k != d && pv[k - 1 + d] < pv[k + 1 + d])) ? k + 1 : k - 1;
            int prevX = pv[prevK + d], prevY = prevX - prevK;
            while (xx > prevX && yy > prevY) {
                keepA[--xx] = true;
                keepB[--yy] = true;
            }
            xx = prevX;
            yy = prevY;
        }
        while (xx > 0 && yy > 0) {
            keepA[--xx] = true;
            keepB[--yy] = true;
        }
        return true;
    }

    // ------------------------------------------------------------------ unified diff text

    /** A unified diff with {@code context} lines around each change. */
    public static String unified(String aName, String bName, String aText, String bText, int context) {
        String[] a = lines(aText), b = lines(bText);
        List<Hunk> hs = diff(a, b);
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(aName).append('\n').append("+++ ").append(bName).append('\n');
        int i = 0;
        while (i < hs.size()) {
            int j = i;
            while (j + 1 < hs.size() && hs.get(j + 1).bs - hs.get(j).be <= 2 * context) j++;
            Hunk first = hs.get(i), last = hs.get(j);
            int bs = Math.max(0, first.bs - context), be = Math.min(a.length, last.be + context);
            int os = Math.max(0, first.os - (first.bs - bs)), oe = Math.min(b.length, last.oe + (be - last.be));
            sb.append("@@ -").append(bs + 1).append(',').append(be - bs).append(" +").append(os + 1).append(',').append(oe - os).append(" @@\n");
            int p = bs;
            for (int h = i; h <= j; h++) {
                Hunk hk = hs.get(h);
                for (; p < hk.bs; p++) line(sb, ' ', a[p]);
                for (int q = hk.bs; q < hk.be; q++) line(sb, '-', a[q]);
                for (int q = hk.os; q < hk.oe; q++) line(sb, '+', b[q]);
                p = hk.be;
            }
            for (; p < be; p++) line(sb, ' ', a[p]);
            i = j + 1;
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, char c, String l) {
        sb.append(c).append(l);
        if (!l.endsWith("\n")) sb.append("\n\\ No newline at end of file\n");
    }

    // ------------------------------------------------------------------ three-way merge

    public static final class Merge3 {
        public String text;
        public int conflicts;
    }

    public static Merge3 merge3(String base, String ours, String theirs) {
        String[] b = lines(base), o = lines(ours), t = lines(theirs);
        List<Hunk> ho = diff(b, o), ht = diff(b, t);
        StringBuilder sb = new StringBuilder();
        Merge3 r = new Merge3();
        int pos = 0, io = 0, it = 0;
        int deltaO = 0, deltaT = 0;
        while (io < ho.size() || it < ht.size()) {
            // Next region: starts at the earliest hunk and grows while hunks overlap or touch.
            int rs = Math.min(io < ho.size() ? ho.get(io).bs : Integer.MAX_VALUE, it < ht.size() ? ht.get(it).bs : Integer.MAX_VALUE);
            int re = rs;
            int fo = io, ft = it;
            boolean grew = true;
            while (grew) {
                grew = false;
                while (io < ho.size() && ho.get(io).bs <= re && (ho.get(io).bs >= rs)) {
                    re = Math.max(re, ho.get(io).be);
                    io++;
                    grew = true;
                }
                while (it < ht.size() && ht.get(it).bs <= re && (ht.get(it).bs >= rs)) {
                    re = Math.max(re, ht.get(it).be);
                    it++;
                    grew = true;
                }
            }
            for (int p = pos; p < rs; p++) sb.append(b[p]);
            int oStart = rs + deltaO, tStart = rs + deltaT;
            for (int h = fo; h < io; h++) deltaO += (ho.get(h).oe - ho.get(h).os) - (ho.get(h).be - ho.get(h).bs);
            for (int h = ft; h < it; h++) deltaT += (ht.get(h).oe - ht.get(h).os) - (ht.get(h).be - ht.get(h).bs);
            int oEnd = re + deltaO, tEnd = re + deltaT;
            String oText = join(o, oStart, oEnd), tText = join(t, tStart, tEnd);
            if (fo == io) sb.append(tText);
            else if (ft == it) sb.append(oText);
            else if (oText.equals(tText)) sb.append(oText);
            else {
                r.conflicts++;
                sb.append("<<<<<<< ours\n").append(endNl(oText)).append("=======\n").append(endNl(tText)).append(">>>>>>> theirs\n");
            }
            pos = re;
        }
        for (int p = pos; p < b.length; p++) sb.append(b[p]);
        r.text = sb.toString();
        return r;
    }

    private static String join(String[] l, int s, int e) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, s); i < Math.min(l.length, e); i++) sb.append(l[i]);
        return sb.toString();
    }

    private static String endNl(String s) {
        return s.isEmpty() || s.endsWith("\n") ? s : s + "\n";
    }
}
