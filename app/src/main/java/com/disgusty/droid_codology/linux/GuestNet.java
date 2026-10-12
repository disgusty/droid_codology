package com.disgusty.droid_codology.linux;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Alpine Linux's way out to the network through the app. Programs in Alpine resolve names and pick
 * addresses on their own (musl, /etc/resolv.conf), which on some networks ends up somewhere other
 * than where Android goes: a server that never answers, a broken IPv6 route, a DNS answer the
 * network doesn't serve. apk then hangs at "fetch https://dl-cdn.alpinelinux.org/..." although the
 * app itself downloaded Alpine from the very same host.
 *
 * So the guest gets an HTTP proxy in the app (http_proxy / https_proxy): HTTPS goes through it as
 * a CONNECT tunnel (the app only passes bytes; TLS stays end to end between the program and the
 * server), plain HTTP is forwarded. Names are resolved and connections made by Android, exactly as
 * for the app's own downloads. The proxy listens on 127.0.0.1 only and wants a random password, so
 * other apps on the device can't use it.
 */
public final class GuestNet {
    private static GuestNet instance;

    private final ServerSocket server;
    private final String user = "dc", password;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private GuestNet() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64);
        byte[] r = new byte[18];
        new SecureRandom().nextBytes(r);
        password = Base64.encodeToString(r, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                accept();
            }
        }, "guest-proxy");
        t.setDaemon(true);
        t.start();
    }

    /** The running proxy, started on first use; null when it can't listen. */
    public static synchronized GuestNet get() {
        if (instance == null) {
            try {
                instance = new GuestNet();
            } catch (IOException e) {
                return null;
            }
        }
        return instance;
    }

    /** Variables that point programs in Alpine at the proxy. */
    public List<String> env() {
        String url = "http://" + user + ":" + password + "@127.0.0.1:" + server.getLocalPort();
        ArrayList<String> e = new ArrayList<String>();
        for (String k : new String[]{"http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"}) e.add(k + "=" + url);
        e.add("no_proxy=localhost,127.0.0.1,::1");
        e.add("NO_PROXY=localhost,127.0.0.1,::1");
        // Node.js (22.21+/24.5+) honors the variables above only when asked to.
        e.add("NODE_USE_ENV_PROXY=1");
        return e;
    }

    private void accept() {
        while (true) {
            final Socket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                return;
            }
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        serve(c);
                    }
                });
            } catch (RuntimeException e) {
                closeQuietly(c);
            }
        }
    }

    private void serve(Socket c) {
        Socket up = null;
        try {
            c.setSoTimeout(30000);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            byte[] head = readHead(in);
            if (head == null) return;
            String text = new String(head, "ISO-8859-1");
            String[] lines = text.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 3) {
                reply(out, "400 Bad Request");
                return;
            }
            if (!authorized(lines)) {
                out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"droid_codology\"\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
                out.flush();
                return;
            }
            String method = req[0].toUpperCase(Locale.ROOT);
            if (method.equals("CONNECT")) {
                String target = req[1];
                int colon = target.lastIndexOf(':');
                if (colon <= 0) {
                    reply(out, "400 Bad Request");
                    return;
                }
                String host = target.substring(0, colon);
                if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
                int port = Integer.parseInt(target.substring(colon + 1));
                up = connect(host, port);
                if (up == null) {
                    reply(out, "502 Bad Gateway");
                    return;
                }
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("ISO-8859-1"));
                out.flush();
                pipe(c, up);
                up = null; // pipe closes both
                return;
            }
            // Plain HTTP: "GET http://host[:port]/path HTTP/1.1" goes to the host as "GET /path".
            String url = req[1];
            if (!url.regionMatches(true, 0, "http://", 0, 7)) {
                reply(out, "400 Bad Request");
                return;
            }
            String rest = url.substring(7);
            int slash = rest.indexOf('/');
            String authority = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);
            String host = authority;
            int port = 80;
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && authority.indexOf(']') < colon) {
                host = authority.substring(0, colon);
                port = Integer.parseInt(authority.substring(colon + 1));
            }
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            up = connect(host, port);
            if (up == null) {
                reply(out, "502 Bad Gateway");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(req[0]).append(' ').append(path).append(' ').append(req[2]).append("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i];
                if (l.isEmpty()) continue;
                String lower = l.toLowerCase(Locale.ROOT);
                // One request per connection keeps the forwarding a plain byte pipe.
                if (lower.startsWith("proxy-") || lower.startsWith("connection:") || lower.startsWith("keep-alive:")) continue;
                sb.append(l).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            up.getOutputStream().write(sb.toString().getBytes("ISO-8859-1"));
            up.getOutputStream().flush();
            pipe(c, up);
            up = null;
        } catch (Exception e) {
            // the client sees the connection close
        } finally {
            if (up != null) closeQuietly(up);
        }
    }

    private boolean authorized(String[] lines) {
        String want = "Basic " + Base64.encodeToString((user + ":" + password).getBytes(), Base64.NO_WRAP);
        for (String l : lines) {
            int colon = l.indexOf(':');
            if (colon > 0 && l.substring(0, colon).trim().equalsIgnoreCase("Proxy-Authorization")
                    && l.substring(colon + 1).trim().equals(want)) {
                return true;
            }
        }
        return false;
    }

    /** Connects like the app does: Android's resolver, each address in its order until one answers. */
    private static Socket connect(String host, int port) {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (IOException e) {
            return null;
        }
        for (InetAddress a : addrs) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(a, port), 15000);
                s.setTcpNoDelay(true);
                return s;
            } catch (IOException e) {
                closeQuietly(s);
            }
        }
        return null;
    }

    /** Copies both ways until either side closes, then closes both. */
    private void pipe(final Socket a, final Socket b) throws IOException {
        a.setSoTimeout(0);
        b.setSoTimeout(0);
        final InputStream bin = b.getInputStream();
        final OutputStream aout = a.getOutputStream();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                copy(bin, aout, a, b);
            }
        });
        copy(a.getInputStream(), b.getOutputStream(), a, b);
    }

    private static void copy(InputStream in, OutputStream out, Socket a, Socket b) {
        byte[] buf = new byte[32 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(a);
            closeQuietly(b);
        }
    }

    /** The request head up to the blank line, or null; at most 64 KB. */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 64 * 1024) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            // \r\n\r\n
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toByteArray();
        }
        return null;
    }

    private static void reply(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ DNS

    /**
     * The DNS servers Android uses on the current network, for /etc/resolv.conf (programs that
     * resolve names themselves, e.g. ping or ssh). Empty when unknown.
     */
    @android.annotation.SuppressLint("MissingPermission")
    public static List<String> dnsServers(Context ctx) {
        ArrayList<String> out = new ArrayList<String>();
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                out.addAll(com.disgusty.droid_codology.compat.Api21.dnsServers(ctx));
            } else {
                // Android 4.x publishes them as system properties.
                java.lang.reflect.Method get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
                String d1 = (String) get.invoke(null, "net.dns1");
                String d2 = (String) get.invoke(null, "net.dns2");
                if (d1 != null && !d1.isEmpty()) out.add(d1);
                if (d2 != null && !d2.isEmpty()) out.add(d2);
            }
        } catch (Exception ignored) {
        }
        // musl reads at most three; link-local IPv6 servers need an interface name it can't use.
        ArrayList<String> usable = new ArrayList<String>();
        for (String s : out) {
            int pct = s.indexOf('%');
            if (pct >= 0) continue;
            if (s.toLowerCase(Locale.ROOT).startsWith("fe80")) continue;
            if (!usable.contains(s) && usable.size() < 3) usable.add(s);
        }
        return usable;
    }
}
