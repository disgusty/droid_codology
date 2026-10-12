package com.disgusty.droid_codology.linux;

import android.content.Context;
import android.os.Build;

import com.disgusty.droid_codology.io.Tar;
import com.disgusty.droid_codology.net.Http;
import com.disgusty.droid_codology.term.Pty;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Alpine Linux in user space: PRoot (shipped as libproot.so, the only place Android 10+ lets an
 * app execute files from) runs a minimal Alpine root file system downloaded on first use. No root
 * needed. The app sandbox is the only boundary: PRoot is not a security container.
 */
public final class AlpineLinux {
    public static final String VERSION = "3.24.2";

    /** Pinned downloads: Alpine arch, file, size, SHA-256 (from Alpine's latest-releases.yaml). */
    private static final String[][] ROOTFS = {
            {"aarch64", "alpine-minirootfs-3.24.2-aarch64.tar.gz", "4028030", "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"},
            {"armv7", "alpine-minirootfs-3.24.2-armv7.tar.gz", "3180242", "5552f1ef2398cee46ef2cf733f8f1258f39a77ad065dfd47282fc0df9fe1976c"},
            {"x86_64", "alpine-minirootfs-3.24.2-x86_64.tar.gz", "3701382", "c5ca053cfe1d85c5b96dff8b9bc57045f7f184a30ffb6b65776409ca90388677"},
            {"x86", "alpine-minirootfs-3.24.2-x86.tar.gz", "3543444", "757f07c5a3476ba3947cfbea2323b7f02fb6fb2fba8a3a3c2b9d17f0a54c9226"},
    };
    private static final String MIRROR = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/";

    public static final String GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    private final Context ctx;
    private boolean checked;

    public AlpineLinux(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public interface Progress {
        void onProgress(String what, int percent);
    }

    // ------------------------------------------------------------------ where things are

    public File base() {
        return new File(ctx.getFilesDir(), "alpine");
    }

    public File rootfs() {
        return new File(base(), "rootfs");
    }

    private File nativeDir() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    public File proot() {
        return new File(nativeDir(), "libproot.so");
    }

    /** Alpine's name for this device's main CPU architecture, or null when there's no build for it. */
    public static String arch() {
        String abi = Build.VERSION.SDK_INT >= 21 ? com.disgusty.droid_codology.compat.Api21.primaryAbi() : Build.CPU_ABI;
        if (abi == null) return null;
        if (abi.equals("arm64-v8a")) return "aarch64";
        if (abi.startsWith("armeabi-v7a")) return "armv7";
        if (abi.equals("x86_64")) return "x86_64";
        if (abi.equals("x86")) return "x86";
        return null;
    }

    private static String[] rootfsFor(String arch) {
        for (String[] r : ROOTFS) if (r[0].equals(arch)) return r;
        return null;
    }

    /** PRoot is in this build for this device (the native build step was run) and the CPU is supported. */
    public boolean supported() {
        return arch() != null && proot().isFile() && Pty.available();
    }

    public boolean installed() {
        return new File(rootfs(), "bin/busybox").exists() && new File(base(), "installed").isFile();
    }

    // ------------------------------------------------------------------ install / remove

    /** Downloads, checks and unpacks the root file system (blocking; run it off the UI thread). */
    public void install(Progress p) throws IOException {
        String arch = arch();
        String[] spec = arch == null ? null : rootfsFor(arch);
        if (spec == null) throw new IOException("No Alpine Linux for this processor (" + Build.CPU_ABI + ")");
        File base = base();
        base.mkdirs();
        File tgz = new File(ctx.getCacheDir(), spec[1]);
        final Progress pr = p;
        final long size = Long.parseLong(spec[2]);
        if (!tgz.isFile() || tgz.length() != size || !spec[3].equals(sha256(tgz))) {
            tgz.delete();
            Http.download(MIRROR + spec[0] + "/" + spec[1], tgz, null, new Http.Progress() {
                @Override
                public void onProgress(long done, long total) {
                    if (pr != null) pr.onProgress("download", (int) (done * 100 / Math.max(1, total > 0 ? total : size)));
                }
            });
        }
        if (tgz.length() != size || !spec[3].equals(sha256(tgz))) {
            tgz.delete();
            throw new IOException("The downloaded Alpine Linux doesn't match its checksum; try again");
        }
        File tmp = new File(base, "rootfs.tmp");
        deleteTree(tmp);
        tmp.mkdirs();
        InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(tgz), 64 * 1024));
        try {
            Tar.extract(in, tmp, new Tar.Fs() {
                @Override
                public int symlink(String target, String path) {
                    return Pty.symlink(target, path);
                }

                @Override
                public int chmod(String path, int mode) {
                    return Pty.chmod(path, mode);
                }
            }, new Tar.Progress() {
                @Override
                public void onEntry(String name, long bytesRead) {
                    // the archive is about 2.5 times its download size
                    if (pr != null) pr.onProgress("unpack", (int) Math.min(99, bytesRead * 100 / (size * 5 / 2)));
                }
            });
        } finally {
            in.close();
        }
        configure(tmp);
        File root = rootfs();
        deleteTree(root);
        if (!tmp.renameTo(root)) throw new IOException("Cannot move Alpine Linux into place");
        new File(base, "proot-tmp").mkdirs();
        write(new File(base, "installed"), VERSION + "\n");
        tgz.delete();
        if (p != null) p.onProgress("done", 100);
    }

    /** Files the minimal root file system lacks for working on a phone. */
    /**
     * Stand-ins for the /proc files Android 8+ doesn't let apps read (as proot-distro does it):
     * "host file:guest path" binds, only for files that really can't be read here.
     */
    private List<String> fakeProc() {
        ArrayList<String> out = new ArrayList<String>();
        File dir = new File(base(), "proc-fake");
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        double up = android.os.SystemClock.elapsedRealtime() / 1000.0;
        StringBuilder stat = new StringBuilder("cpu  1957 0 2877 93280 262 342 254 87 0 0\n");
        for (int i = 0; i < cpus; i++) stat.append("cpu").append(i).append(" 245 0 360 11660 33 43 32 11 0 0\n");
        stat.append("intr 0\nctxt 140223\nbtime ").append(System.currentTimeMillis() / 1000 - (long) up)
                .append("\nprocesses 772\nprocs_running 2\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n");
        String[][] files = {
                {"/proc/loadavg", "0.12 0.07 0.02 2/165 765\n"},
                {"/proc/stat", stat.toString()},
                {"/proc/uptime", String.format(Locale.ROOT, "%.2f %.2f\n", up, up * cpus * 0.8)},
                {"/proc/version", "Linux version " + System.getProperty("os.version", "4.14") + " (droid_codology) #1 SMP PREEMPT\n"},
                {"/proc/vmstat", "nr_free_pages 136448\nnr_inactive_anon 2\nnr_active_anon 3\nnr_inactive_file 4\nnr_active_file 5\n"},
                {"/proc/sys/kernel/cap_last_cap", "40\n"},
                {"/proc/sys/fs/inotify/max_user_watches", "4096\n"},
                {"/proc/sys/kernel/overflowuid", "65534\n"},
                {"/proc/sys/kernel/overflowgid", "65534\n"},
        };
        for (String[] f : files) {
            if (readable(f[0])) continue;
            File fake = new File(dir, f[0].substring(1).replace('/', '_'));
            try {
                write(fake, f[1]);
                out.add(fake.getAbsolutePath() + ":" + f[0]);
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private static boolean readable(String path) {
        try {
            InputStream in = new FileInputStream(path);
            try {
                return in.read() >= 0;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Runs {@code command} in Alpine without a terminal and waits for it (off the UI thread). Its
     * output, both streams, goes to {@code out}. Returns the exit code.
     */
    public int run(List<String> command, StringBuilder out) throws IOException {
        Launch l = launch(new File(ctx.getFilesDir(), "home").getAbsolutePath(), command);
        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add(l.program);
        cmd.addAll(java.util.Arrays.asList(l.args).subList(1, l.args.length));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        java.util.Map<String, String> env = pb.environment();
        env.clear();
        for (String kv : l.env) {
            int eq = kv.indexOf('=');
            if (eq > 0) env.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        InputStream in = p.getInputStream();
        byte[] b = new byte[8192];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        try {
            int code = p.waitFor();
            out.append(new String(bo.toByteArray(), "UTF-8"));
            return code;
        } catch (InterruptedException e) {
            p.destroy();
            throw new IOException("interrupted");
        }
    }

    /** The DNS servers Android uses right now; public ones only when they can't be found. */
    private void writeResolvConf(File root) {
        StringBuilder sb = new StringBuilder();
        List<String> dns = GuestNet.dnsServers(ctx);
        if (dns.isEmpty()) dns = java.util.Arrays.asList("1.1.1.1", "8.8.8.8");
        for (String s : dns) sb.append("nameserver ").append(s).append('\n');
        try {
            write(new File(root, "etc/resolv.conf"), sb.toString());
        } catch (IOException ignored) {
        }
    }

    private void configure(File root) throws IOException {
        writeResolvConf(root);
        write(new File(root, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost\n");
        write(new File(root, "etc/motd"), "Alpine Linux " + VERSION + " in droid_codology.\n"
                + "Install tools with apk, e.g.:  apk add git nodejs npm python3 build-base\n"
                + "Your projects: the same paths as in the app (/sdcard, /storage/...).\n\n");
        write(new File(root, "etc/profile.d/droid_codology.sh"),
                "export PS1='\\[\\e[32m\\]\\u@alpine\\[\\e[0m\\]:\\[\\e[34m\\]\\w\\[\\e[0m\\]\\$ '\n"
                        + "export LANG=C.UTF-8\n"
                        + "[ -d \"$DC_START_DIR\" ] && cd \"$DC_START_DIR\"\n");
        new File(root, "tmp").mkdirs();
        Pty.chmod(new File(root, "tmp").getPath(), 01777);
        new File(root, "root").mkdirs();
        apkWaits(root);
    }

    /**
     * apk takes an exclusive lock on its database and by default fails at once ("Unable to lock
     * database") while another apk runs, e.g. the install the app started in another terminal. A
     * "wait" line in /etc/apk/config (apk-tools 3) makes it wait its turn instead. A "timeout"
     * line ends a download that makes no progress for a minute with an error instead of a hang.
     */
    static void apkWaits(File root) {
        File cfg = new File(root, "etc/apk/config");
        String old = "";
        if (cfg.isFile()) {
            try {
                old = readText(cfg);
            } catch (IOException e) {
                return;
            }
        }
        StringBuilder add = new StringBuilder();
        for (String opt : new String[]{"wait 600", "timeout 60"}) {
            String name = opt.substring(0, opt.indexOf(' '));
            boolean has = false;
            for (String line : old.split("\n")) if (line.trim().startsWith(name)) has = true;
            if (!has) add.append(opt).append('\n');
        }
        if (add.length() == 0) return;
        try {
            write(cfg, old + (old.isEmpty() || old.endsWith("\n") ? "" : "\n") + add);
        } catch (IOException ignored) {
        }
    }

    private static boolean reaped;

    /**
     * Kills Linux processes left over from an earlier run of the app: PRoot and everything it runs
     * (their executables are our libproot*.so) that isn't a descendant of this app process. They
     * can survive the app on old Android versions, or when PRoot itself was killed, and would keep
     * holding files such as apk's database lock.
     */
    static void reapOrphans(Context ctx) {
        if (reaped) return;
        reaped = true;
        int me = android.os.Process.myPid();
        String pkg = ctx.getPackageName();
        java.util.Map<Integer, Integer> parents = com.disgusty.droid_codology.term.Procs.parents();
        for (int pid : parents.keySet()) {
            String exe;
            try {
                exe = new File("/proc/" + pid + "/exe").getCanonicalPath();
            } catch (IOException e) {
                continue; // another app's or the system's process
            }
            String name = exe.substring(exe.lastIndexOf('/') + 1);
            if (name.startsWith("libproot") && exe.contains(pkg) && !com.disgusty.droid_codology.term.Procs.descends(parents, pid, me)) {
                android.os.Process.killProcess(pid);
            }
        }
    }

    public void remove() {
        deleteTree(rootfs());
        deleteTree(new File(base(), "rootfs.tmp"));
        new File(base(), "installed").delete();
    }

    // ------------------------------------------------------------------ running

    /** A command line for the pty: program, argv and environment. */
    public static final class Launch {
        public final String program;
        public final String[] args, env;

        Launch(String program, String[] args, String[] env) {
            this.program = program;
            this.args = args;
            this.env = env;
        }
    }

    /**
     * Runs {@code command} (or a login shell) inside Alpine with the Android storage, the app's home
     * and {@code cwd} visible at the same paths as in the app, so file paths work both ways.
     */
    @android.annotation.SuppressLint("SdCardPath") // the same /sdcard path inside Linux as users see in Android
    public Launch launch(String cwd, List<String> command) {
        return launch(cwd, command, null, null);
    }

    /** With more folders visible at their own paths, and more variables for the guest. */
    @android.annotation.SuppressLint("SdCardPath")
    public Launch launch(String cwd, List<String> command, List<String> extraBinds, List<String> extraEnv) {
        String root = rootfs().getAbsolutePath();
        if (!checked) {
            checked = true;
            apkWaits(rootfs()); // installs from 0.7.0-rc7 lack it
            reapOrphans(ctx);
        }
        writeResolvConf(rootfs()); // the network may have changed since the last launch
        GuestNet net = GuestNet.get();
        ArrayList<String> a = new ArrayList<String>();
        a.add("proot");
        a.add("--kill-on-exit");
        a.add("--link2symlink");
        a.add("--sysvipc");
        a.add("-0");
        a.add("-r");
        a.add(root);
        for (String b : new String[]{"/dev", "/proc", "/sys", "/proc/self/fd:/dev/fd", "/proc/self/fd/0:/dev/stdin",
                "/proc/self/fd/1:/dev/stdout", "/proc/self/fd/2:/dev/stderr",
                // OpenSSL (apk, curl, git, Node.js) waits on old kernels until /dev/random is ready,
                // which on phones may be never: the first HTTPS download then hangs without a word.
                "/dev/urandom:/dev/random"}) {
            a.add("-b");
            a.add(b);
        }
        // Android has no /dev/shm for apps; shared memory (OpenSSL, Python, Node.js) needs one.
        File shm = new File(base(), "shm");
        shm.mkdirs();
        Pty.chmod(shm.getPath(), 01777);
        a.add("-b");
        a.add(shm.getAbsolutePath() + ":/dev/shm");
        // Files of /proc that Android 8+ hides from apps, as stand-ins (CPU count, load, uptime...).
        for (String b : fakeProc()) {
            a.add("-b");
            a.add(b);
        }
        for (String path : new String[]{"/sdcard", "/storage", new File(ctx.getFilesDir(), "home").getAbsolutePath()}) {
            if (new File(path).exists()) {
                a.add("-b");
                a.add(path);
            }
        }
        if (extraBinds != null) {
            for (String b : extraBinds) {
                if (new File(b).exists()) {
                    a.add("-b");
                    a.add(b);
                }
            }
        }
        if (cwd != null && new File(cwd).isDirectory() && !cwd.startsWith("/sdcard") && !cwd.startsWith("/storage")) {
            a.add("-b");
            a.add(cwd);
        }
        a.add("-w");
        a.add(cwd != null && new File(cwd).isDirectory() ? cwd : "/root");
        a.add("/usr/bin/env");
        a.add("-i");
        a.add("HOME=/root");
        a.add("USER=root");
        a.add("LOGNAME=root");
        a.add("PATH=" + GUEST_PATH);
        a.add("TERM=xterm-256color");
        a.add("COLORTERM=truecolor");
        a.add("LANG=C.UTF-8");
        a.add("TMPDIR=/tmp");
        a.add("SHELL=/bin/sh");
        a.add("TERM_PROGRAM=droid_codology");
        if (cwd != null) a.add("DC_START_DIR=" + cwd);
        // Downloads (apk, npm, git, curl, Node.js...) go out the way the app's own do.
        if (net != null) a.addAll(net.env());
        if (extraEnv != null) a.addAll(extraEnv);
        if (command == null || command.isEmpty()) {
            a.add("/bin/sh");
            a.add("-l");
        } else {
            a.addAll(command);
        }
        ArrayList<String> env = new ArrayList<String>();
        File loader = new File(nativeDir(), "libproot-loader.so");
        File loader32 = new File(nativeDir(), "libproot-loader32.so");
        env.add("PROOT_LOADER=" + loader.getAbsolutePath());
        if (loader32.isFile()) env.add("PROOT_LOADER_32=" + loader32.getAbsolutePath());
        File tmp = new File(base(), "proot-tmp");
        tmp.mkdirs();
        env.add("PROOT_TMP_DIR=" + tmp.getAbsolutePath());
        String arch = arch();
        if ("x86_64".equals(arch) || "x86".equals(arch)) {
            // musl on x86 calls fork(2), which Android's app seccomp policy rejects before
            // PRoot's own seccomp-accelerated tracing would see it. Without the acceleration PRoot
            // stops at every syscall entry, before seccomp, and turns fork into clone (our patch).
            env.add("PROOT_NO_SECCOMP=1");
        }
        env.add("PATH=/system/bin:/system/xbin");
        env.add("ANDROID_ROOT=/system");
        env.add("ANDROID_DATA=/data");
        return new Launch(proot().getAbsolutePath(), a.toArray(new String[a.size()]), env.toArray(new String[env.size()]));
    }

    // ------------------------------------------------------------------ helpers

    static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) md.update(b, 0, n);
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e.toString());
        }
    }

    /** Whole file as UTF-8, read as a stream (files in /proc report a size of 0). */
    private static String readText(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return new String(com.disgusty.droid_codology.io.TextFiles.readAll(in), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void write(File f, String text) throws IOException {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        // a symlink in the image (e.g. resolv.conf) is replaced, not followed
        f.delete();
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(text.getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }

    /** Deletes a tree without following symlinks (a link to /sdcard must not take the user's files with it). */
    static void deleteTree(File f) {
        if (com.disgusty.droid_codology.work.Workspace.isSymlink(f)) {
            f.delete();
            return;
        }
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }
}
