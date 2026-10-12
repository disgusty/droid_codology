package com.disgusty.droid_codology.term;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A shell running on a pty, with its screen. Output is read on a background thread. */
public final class TerminalSession {
    public interface Listener {
        /** The screen changed (called on the UI thread, coalesced). */
        void onUpdate(TerminalSession s);

        void onExit(TerminalSession s, int code);
    }

    public final TerminalEmulator emulator;
    public final String name;
    private Pty pty;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Listener listener;
    private volatile boolean updatePosted, exited;
    public int exitCode;

    public TerminalSession(String name, int rows, int cols) {
        this.name = name;
        this.emulator = new TerminalEmulator(rows, cols, 3000, new TerminalEmulator.Output() {
            @Override
            public void write(String s) {
                TerminalSession.this.write(s);
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean exited() {
        return exited;
    }

    public void start(String shell, String[] args, String[] env, String cwd) throws IOException {
        pty = Pty.spawn(shell, args, env, cwd, emulator.rows(), emulator.cols());
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[8192];
                while (true) {
                    int n = pty.read(buf);
                    if (n < 0) break;
                    synchronized (emulator) {
                        emulator.feed(buf, 0, n);
                    }
                    postUpdate();
                }
                final int code = pty.waitFor();
                pty.close();
                exited = true;
                exitCode = code;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onExit(TerminalSession.this, code);
                    }
                });
            }
        }, "terminal-" + name);
        reader.setDaemon(true);
        reader.start();
    }

    private void postUpdate() {
        if (updatePosted) return;
        updatePosted = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                updatePosted = false;
                if (listener != null) listener.onUpdate(TerminalSession.this);
            }
        });
    }

    public void write(final String s) {
        if (exited || pty == null) return;
        final byte[] b;
        try {
            b = s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        try {
            writer.execute(new Runnable() {
                @Override
                public void run() {
                    pty.write(b);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The session was closed meanwhile (e.g. a program's answer to a terminal query).
        }
    }

    /** Text from a paste: wrapped in bracketed-paste markers when the program asked for them. */
    public void paste(String s) {
        s = s.replace("\r\n", "\r").replace('\n', '\r');
        if (emulator.bracketedPaste) s = "\033[200~" + s + "\033[201~";
        write(s);
    }

    public void resize(int rows, int cols) {
        synchronized (emulator) {
            emulator.resize(rows, cols);
        }
        if (pty != null && !exited) pty.resize(rows, cols);
    }

    public void finish() {
        if (pty != null && !exited) {
            pty.hangUp();
            // A shell (or job) that ignores the hang-up is killed shortly after, so closed
            // terminals don't leave processes, the reader thread and the pty behind.
            final Pty p = pty;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }
                    if (!exited) {
                        // the pty's own jobs and whatever PRoot runs, even in other sessions
                        Procs.killTree(p.pid);
                        p.kill9();
                    }
                }
            }, "pty-reaper");
            t.setDaemon(true);
            t.start();
        }
        writer.shutdown();
    }
}
