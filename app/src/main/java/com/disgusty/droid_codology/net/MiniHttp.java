package com.disgusty.droid_codology.net;

import com.disgusty.droid_codology.net.tls.TlsClient;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.X509TrustManager;

/** HTTP/1.1 over plain sockets or {@link TlsClient}: used instead of HttpURLConnection on Android &lt; 5.0. */
public final class MiniHttp {
    private MiniHttp() {
    }

    public static final class Reply {
        public int code;
        /** Header names in lower case. */
        public final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        public long contentLength = -1;
        public InputStream body;

        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.US));
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Read timeout once connected (5 minutes, like Http.stream on the platform stack). */
    private static final int READ_TIMEOUT = 300000;

    public static Reply request(String method, String url, Map<String, String> headers, byte[] body, String contentType,
                                String userAgent, X509TrustManager trust) throws IOException {
        for (int redirects = 0; ; redirects++) {
            Reply r = once(method, new URL(url), headers, body, contentType, userAgent, trust);
            if (r.code >= 300 && r.code < 400 && r.code != 304 && r.header("location") != null && redirects < 10) {
                String loc = r.header("location");
                r.close();
                URL from = new URL(url), to = new URL(from, loc);
                // Like the platform stack: never from https down to http.
                if ("https".equalsIgnoreCase(from.getProtocol()) && !"https".equalsIgnoreCase(to.getProtocol())) {
                    throw new IOException("Refusing a redirect from HTTPS to " + to.getProtocol() + ": " + to);
                }
                // Credentials were meant for the first host only.
                if (!from.getHost().equalsIgnoreCase(to.getHost()) || from.getPort() != to.getPort()) {
                    java.util.LinkedHashMap<String, String> kept = new java.util.LinkedHashMap<String, String>();
                    if (headers != null) {
                        for (Map.Entry<String, String> h : headers.entrySet()) {
                            String k = h.getKey().toLowerCase(java.util.Locale.ROOT);
                            if (k.equals("authorization") || k.equals("x-api-key") || k.equals("x-goog-api-key") || k.equals("cookie")) continue;
                            kept.put(h.getKey(), h.getValue());
                        }
                    }
                    headers = kept;
                }
                url = to.toString();
                if (r.code == 303 || ((r.code == 301 || r.code == 302) && !"GET".equals(method) && !"HEAD".equals(method))) {
                    method = "GET";
                    body = null;
                }
                continue;
            }
            return r;
        }
    }

    private static Reply once(String method, URL u, Map<String, String> headers, byte[] body, String contentType,
                              String userAgent, X509TrustManager trust) throws IOException {
        boolean https = "https".equalsIgnoreCase(u.getProtocol());
        if (!https && !"http".equalsIgnoreCase(u.getProtocol())) throw new IOException("unsupported URL " + u);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : https ? 443 : 80;
        final InputStream rawIn;
        final OutputStream rawOut;
        final Object conn;
        if (https) {
            TlsClient t = TlsClient.connect(host, port, trust, 20000);
            rawIn = t.getInputStream();
            rawOut = t.getOutputStream();
            conn = t;
        } else {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 20000);
                s.setSoTimeout(READ_TIMEOUT);
                rawIn = s.getInputStream();
                rawOut = s.getOutputStream();
            } catch (IOException e) {
                s.close(); // no file descriptor left behind per failed attempt
                throw e;
            }
            conn = s;
        }
        try {
            StringBuilder req = new StringBuilder();
            String path = u.getFile();
            if (path.length() == 0) path = "/";
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host);
            if (u.getPort() > 0) req.append(':').append(port);
            req.append("\r\n");
            boolean hasAccept = false, hasAgent = false;
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if (h.getKey().equalsIgnoreCase("accept")) hasAccept = true;
                    if (h.getKey().equalsIgnoreCase("user-agent")) hasAgent = true;
                    req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
                }
            }
            if (!hasAgent) req.append("User-Agent: ").append(userAgent).append("\r\n");
            req.append("Accept-Encoding: gzip\r\n");
            req.append("Connection: close\r\n");
            if (!hasAccept) req.append("Accept: */*\r\n");
            if (body != null) {
                if (contentType != null) req.append("Content-Type: ").append(contentType).append("\r\n");
                req.append("Content-Length: ").append(body.length).append("\r\n");
            }
            req.append("\r\n");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bo.write(req.toString().getBytes("UTF-8"));
            if (body != null) bo.write(body);
            rawOut.write(bo.toByteArray());
            rawOut.flush();

            final InputStream in = new BufferedInputStream(rawIn, 16 * 1024);
            Reply r = new Reply();
            String status;
            do {
                status = line(in);
                if (status == null) throw new IOException("empty response");
                String[] parts = status.split(" ", 3);
                if (parts.length < 2) throw new IOException("bad status line: " + status);
                r.code = Integer.parseInt(parts[1]);
                r.headers.clear();
                String l;
                while ((l = line(in)) != null && l.length() > 0) {
                    int c = l.indexOf(':');
                    if (c <= 0) continue;
                    String k = l.substring(0, c).trim().toLowerCase(Locale.US);
                    List<String> vs = r.headers.get(k);
                    if (vs == null) r.headers.put(k, vs = new ArrayList<String>());
                    vs.add(l.substring(c + 1).trim());
                }
            } while (r.code == 100);

            InputStream b;
            String te = r.header("transfer-encoding");
            String cl = r.header("content-length");
            if ("HEAD".equals(method) || r.code == 204 || r.code == 304) {
                b = new Limited(in, 0);
            } else if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
                b = new Chunked(in);
            } else if (cl != null) {
                r.contentLength = Long.parseLong(cl.trim());
                b = new Limited(in, r.contentLength);
            } else {
                b = in;
            }
            String ce = r.header("content-encoding");
            if (ce != null && ce.toLowerCase(Locale.US).contains("gzip")) {
                b = new GZIPInputStream(b);
                r.contentLength = -1;
            }
            r.body = new FilterInputStream(b) {
                @Override
                public void close() throws IOException {
                    closeConn(conn);
                }
            };
            return r;
        } catch (IOException e) {
            closeConn(conn);
            throw e;
        } catch (RuntimeException e) {
            closeConn(conn);
            throw new IOException(e.toString());
        }
    }

    private static void closeConn(Object c) {
        try {
            if (c instanceof TlsClient) ((TlsClient) c).close();
            else ((Socket) c).close();
        } catch (IOException ignored) {
        }
    }

    /** An ISO-8859-1 line without CR LF, or null at end of stream. */
    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 65536) throw new IOException("header line too long");
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long n) {
            super(in);
            left = n;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int c = in.read();
            if (c >= 0) left--;
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            else if (n < 0) throw new IOException("connection closed early");
            return n;
        }
    }

    private static final class Chunked extends FilterInputStream {
        private long left;
        private boolean done;

        Chunked(InputStream in) {
            super(in);
        }

        private boolean next() throws IOException {
            if (done) return false;
            if (left == 0) {
                String l = line(in);
                if (l != null && l.length() == 0) l = line(in); // CRLF after the previous chunk
                if (l == null) throw new IOException("truncated chunked body");
                int semi = l.indexOf(';');
                left = Long.parseLong((semi >= 0 ? l.substring(0, semi) : l).trim(), 16);
                if (left == 0) {
                    String t;
                    while ((t = line(in)) != null && t.length() > 0) {
                        // trailers
                    }
                    done = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) <= 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!next()) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new IOException("truncated chunked body");
            left -= n;
            return n;
        }
    }
}
