package com.disgusty.droid_codology.work;

import com.disgusty.droid_codology.linux.AlpineLinux;
import com.disgusty.droid_codology.term.Pty;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The extension host running on Node.js inside Alpine Linux (assets/jshost/host-node.js): the
 * same messages as the WebView host, over pipes instead of a JavaScript interface.
 */
final class NodeHost {
    interface Bridge {
        String call(String method, String argsJson);

        void callAsync(int reqId, String method, String argsJson);

        void log(String level, String text);

        void exited(String reason);
    }

    private final Workbench wb;
    private final Bridge bridge;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Process process;
    private OutputStream stdin;
    private volatile boolean stopped;

    NodeHost(Workbench wb, Bridge bridge) {
        this.wb = wb;
        this.bridge = bridge;
    }

    /** Node.js is installed in Alpine Linux (apk add nodejs). */
    static boolean available(AlpineLinux alpine) {
        return alpine.supported() && alpine.installed() && new File(alpine.rootfs(), "usr/bin/node").exists();
    }

    void start() throws IOException {
        File dir = new File(wb.activity.getFilesDir(), "nodehost");
        dir.mkdirs();
        // The host scripts, from the APK each time (they change with the app).
        copyAsset("jshost/host.js", new File(dir, "host.js"));
        copyAsset("jshost/host-node.js", new File(dir, "host-node.js"));
        final File req = new File(dir, "req.fifo"), reply = new File(dir, "reply.fifo");
        if (Pty.mkfifo(req.getPath(), 0600) != 0 || Pty.mkfifo(reply.getPath(), 0600) != 0) {
            throw new IOException("cannot create the host's channels");
        }
        AlpineLinux alpine = wb.alpine();
        AlpineLinux.Launch l = alpine.launch(dir.getAbsolutePath(),
                Arrays.asList("/usr/bin/node", new File(dir, "host-node.js").getAbsolutePath()),
                Arrays.asList(dir.getAbsolutePath(), new File(wb.activity.getFilesDir(), "extensions").getAbsolutePath()),
                Arrays.asList("DC_REQ_FIFO=" + req.getAbsolutePath(), "DC_REPLY_FIFO=" + reply.getAbsolutePath()));
        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add(l.program);
        cmd.addAll(Arrays.asList(l.args).subList(1, l.args.length));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.clear();
        for (String kv : l.env) {
            int eq = kv.indexOf('=');
            if (eq > 0) env.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        pb.directory(dir);
        process = pb.start();
        stdin = process.getOutputStream();
        final Process p = process;
        // Output of extensions (console.log...) to the log.
        thread("node-stdout", new Runnable() {
            @Override
            public void run() {
                pipeToLog(p.getInputStream(), "info");
            }
        });
        thread("node-stderr", new Runnable() {
            @Override
            public void run() {
                pipeToLog(p.getErrorStream(), "warn");
            }
        });
        // Requests: opened in the same order as host-node.js opens them (req, then reply).
        thread("node-requests", new Runnable() {
            @Override
            public void run() {
                serve(req, reply);
            }
        });
        thread("node-exit", new Runnable() {
            @Override
            public void run() {
                int code;
                try {
                    code = p.waitFor();
                } catch (InterruptedException e) {
                    return;
                }
                if (!stopped) bridge.exited("Node.js exited with code " + code);
            }
        });
    }

    private void serve(File req, File reply) {
        BufferedReader in = null;
        OutputStream out = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(req), "UTF-8"));
            out = new FileOutputStream(reply);
            String line;
            while ((line = in.readLine()) != null) {
                JSONArray m;
                try {
                    m = new JSONArray(line);
                } catch (Exception e) {
                    continue;
                }
                String kind = m.optString(0);
                if (kind.equals("call")) {
                    int id = m.optInt(1);
                    String result = bridge.call(m.optString(2), m.optString(3, "[]"));
                    JSONArray answer = new JSONArray();
                    answer.put(id);
                    answer.put(result);
                    out.write((answer.toString() + "\n").getBytes("UTF-8"));
                    out.flush();
                } else if (kind.equals("async")) {
                    bridge.callAsync(m.optInt(1), m.optString(2), m.optString(3, "[]"));
                }
            }
        } catch (IOException e) {
            if (!stopped) bridge.log("error", "extension host channel: " + e.getMessage());
        } finally {
            close(in);
            close(out);
        }
    }

    /** Java → JS: one queue entry per line on Node's stdin. */
    void post(final String message) {
        if (stopped) return;
        try {
            writer.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        stdin.write((message.replace("\n", " ") + "\n").getBytes("UTF-8"));
                        stdin.flush();
                    } catch (IOException e) {
                        // the host is gone; the exit thread reports it
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    void stop() {
        stopped = true;
        writer.shutdownNow();
        if (process == null) return;
        // destroy() kills only PRoot, which would leave Node.js and its child processes running
        // untraced; the whole tree goes. Our PRoot is the child of this app running host-node.js.
        int me = android.os.Process.myPid();
        java.util.Map<Integer, Integer> parents = com.disgusty.droid_codology.term.Procs.parents();
        for (java.util.Map.Entry<Integer, Integer> e : parents.entrySet()) {
            if (e.getValue() == me && cmdline(e.getKey()).contains("host-node.js")) {
                com.disgusty.droid_codology.term.Procs.killTree(e.getKey());
            }
        }
        process.destroy();
    }

    private static String cmdline(int pid) {
        try {
            InputStream in = new java.io.FileInputStream("/proc/" + pid + "/cmdline");
            try {
                byte[] b = new byte[8192];
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, "UTF-8") : "";
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    private void pipeToLog(InputStream s, String level) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) bridge.log(level, line);
        } catch (IOException ignored) {
        }
    }

    private void copyAsset(String name, File to) throws IOException {
        InputStream in = wb.activity.getAssets().open(name);
        try {
            OutputStream os = new FileOutputStream(to);
            try {
                byte[] b = new byte[32 * 1024];
                int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
