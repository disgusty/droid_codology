package com.disgusty.droid_codology.term;

import java.io.IOException;

/** A process running on a pseudo-terminal (native helper in src/main/cpp/pty.c). */
public final class Pty {
    private static boolean loaded;
    private static Throwable loadError;

    static {
        try {
            System.loadLibrary("dcpty");
            loaded = true;
        } catch (Throwable t) {
            loadError = t;
        }
    }

    public static boolean available() {
        return loaded;
    }

    static native int start(String cmd, String[] args, String[] env, String cwd, int rows, int cols, int[] pidOut) throws IOException;

    static native int read(int fd, byte[] buf, int off, int len);

    static native void write(int fd, byte[] buf, int off, int len);

    static native void resize(int fd, int rows, int cols);

    static native int waitFor(int pid);

    static native void kill(int pid, int sig);

    static native void close(int fd);

    /** symlink(2); 0 or errno. */
    public static native int symlink(String target, String path);

    /** mkfifo(3); 0 or errno. */
    public static native int mkfifo(String path, int mode);

    /** chmod(2); 0 or errno. */
    public static native int chmod(String path, int mode);

    public final int fd, pid;
    private volatile boolean closed;

    private Pty(int fd, int pid) {
        this.fd = fd;
        this.pid = pid;
    }

    public static Pty spawn(String cmd, String[] args, String[] env, String cwd, int rows, int cols) throws IOException {
        if (!loaded) throw new IOException("terminal support is missing: " + loadError);
        int[] pid = new int[1];
        int fd = start(cmd, args, env, cwd, rows, cols, pid);
        return new Pty(fd, pid[0]);
    }

    /** Blocking read; -1 when the process is gone. */
    public int read(byte[] b) {
        return closed ? -1 : read(fd, b, 0, b.length);
    }

    public void write(byte[] b) {
        if (!closed) write(fd, b, 0, b.length);
    }

    public void resize(int rows, int cols) {
        if (!closed) resize(fd, rows, cols);
    }

    public int waitFor() {
        return waitFor(pid);
    }

    /** SIGHUP to the session, like closing a terminal window. */
    public void hangUp() {
        kill(pid, 1);
    }

    /** SIGKILL to the session's process group, for shells that ignore the hang-up. */
    public void kill9() {
        kill(pid, 9);
    }

    public void close() {
        if (closed) return;
        closed = true;
        close(fd);
    }
}
