package com.disgusty.droid_codology.net;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS that works on Android 4.x: TLS 1.2 switched on (it exists since 4.1 but is off by default
 * before 5.0) and current root certificates bundled (assets/certs/roots.pem) next to the system
 * store, which on old devices lacks Let's Encrypt, Google Trust Services and other modern roots.
 */
public final class Http {
    private Http() {
    }

    private static SSLSocketFactory factory;
    private static X509TrustManager trust;
    private static String userAgent = "droid_codology";

    /**
     * Before 5.0 the platform TLS has no AEAD suites and no X25519, which many servers (Fastly,
     * Cloudflare…) now require, so requests go through our own TLS 1.2 client instead.
     */
    private static boolean ownStack() {
        return Build.VERSION.SDK_INT < 21 && trust != null;
    }

    public static synchronized void init(Context ctx) {
        if (factory != null) return;
        try {
            String version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            userAgent = "droid_codology/" + version + " (Android " + Build.VERSION.RELEASE + ")";
        } catch (Exception ignored) {
        }
        try {
            X509TrustManager system = defaultTrustManager(null);
            X509TrustManager bundled = defaultTrustManager(bundledStore(ctx));
            trust = new Composite(system, bundled);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{trust}, null);
            factory = new ModernTls(tls.getSocketFactory());
            HttpsURLConnection.setDefaultSSLSocketFactory(factory);
        } catch (Exception e) {
            android.util.Log.w("DCHttp", "TLS init failed", e);
            factory = null;
        }
    }

    private static KeyStore bundledStore(Context ctx) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        InputStream in = ctx.getAssets().open("certs/roots.pem");
        String pem;
        try {
            pem = new String(readAll(in), "US-ASCII");
        } finally {
            in.close();
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int i = 0, n = 0;
        while ((i = pem.indexOf("-----BEGIN CERTIFICATE-----", i)) >= 0) {
            int e = pem.indexOf("-----END CERTIFICATE-----", i);
            if (e < 0) break;
            e += "-----END CERTIFICATE-----".length();
            byte[] der = pem.substring(i, e).getBytes("US-ASCII");
            try {
                X509Certificate c = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
                ks.setCertificateEntry("root" + (n++), c);
            } catch (Exception ignored) {
                // A root this platform can't parse (e.g. an ECC curve): skip it.
            }
            i = e;
        }
        return ks;
    }

    private static X509TrustManager defaultTrustManager(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        throw new IllegalStateException("no X509TrustManager");
    }

    /** Trusts a chain if either the system store or the bundled roots accept it. */
    private static final class Composite implements X509TrustManager {
        private final X509TrustManager a, b;

        Composite(X509TrustManager a, X509TrustManager b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            a.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                a.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                b.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            ArrayList<X509Certificate> l = new ArrayList<X509Certificate>(Arrays.asList(a.getAcceptedIssuers()));
            l.addAll(Arrays.asList(b.getAcceptedIssuers()));
            return l.toArray(new X509Certificate[l.size()]);
        }
    }

    /** Enables TLS 1.2 (and 1.1) on every socket. */
    private static final class ModernTls extends SSLSocketFactory {
        private final SSLSocketFactory d;

        ModernTls(SSLSocketFactory d) {
            this.d = d;
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                ArrayList<String> want = new ArrayList<String>();
                for (String p : ss.getSupportedProtocols()) {
                    if (p.equals("TLSv1.3") || p.equals("TLSv1.2") || p.equals("TLSv1.1")) want.add(p);
                }
                if (!want.isEmpty()) ss.setEnabledProtocols(want.toArray(new String[want.size()]));
                // Android 4.x supports AES-GCM / ECDHE suites but doesn't enable them; servers that
                // require them would otherwise fail the handshake (and the client falls back to SSLv3).
                ArrayList<String> suites = new ArrayList<String>();
                for (String c : ss.getSupportedCipherSuites()) if (isModern(c)) suites.add(c);
                for (String c : ss.getEnabledCipherSuites()) if (!suites.contains(c) && isAcceptable(c)) suites.add(c);
                if (!suites.isEmpty()) ss.setEnabledCipherSuites(suites.toArray(new String[suites.size()]));
            }
            return s;
        }

        private static boolean isModern(String c) {
            return (c.contains("_ECDHE_") || c.contains("_DHE_") || c.startsWith("TLS_AES_") || c.startsWith("TLS_CHACHA20"))
                    && (c.contains("_GCM_") || c.contains("CHACHA20")) && isAcceptable(c);
        }

        private static boolean isAcceptable(String c) {
            return !c.contains("_NULL_") && !c.contains("_anon_") && !c.contains("_EXPORT_") && !c.contains("_RC4_")
                    && !c.contains("_DES_") && !c.contains("_MD5") && !c.contains("EMPTY_RENEGOTIATION");
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return d.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(d.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket() throws IOException {
            return patch(d.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(d.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(d.createSocket(address, port, localAddress, localPort));
        }
    }

    // ------------------------------------------------------------------ requests

    public static final class Response {
        public int code;
        public byte[] body;
        public Map<String, List<String>> headers;

        public String text() {
            try {
                return new String(body, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }

        /** A response header by name, ignoring case (both stacks spell names differently). */
        public String header(String name) {
            if (headers == null) return null;
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    public interface Progress {
        /** total is -1 when unknown. */
        void onProgress(long done, long total);
    }

    public static HttpURLConnection open(String url, String method, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && factory != null) ((HttpsURLConnection) c).setSSLSocketFactory(factory);
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", userAgent);
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        return c;
    }

    public static Response request(String method, String url, Map<String, String> headers, byte[] body, String contentType) throws IOException {
        if (ownStack()) {
            MiniHttp.Reply m = MiniHttp.request(method, url, headers, body, contentType, userAgent, trust);
            try {
                Response r = new Response();
                r.code = m.code;
                r.headers = m.headers;
                r.body = readAll(m.body);
                return r;
            } finally {
                m.close();
            }
        }
        HttpURLConnection c = open(url, method, headers);
        try {
            if (body != null) {
                c.setDoOutput(true);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body);
                } finally {
                    os.close();
                }
            }
            Response r = new Response();
            r.code = c.getResponseCode();
            r.headers = c.getHeaderFields();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? new byte[0] : readAll(in);
            if (in != null) in.close();
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** A response whose body is read as it arrives (server-sent events, big downloads). */
    public static final class Stream implements java.io.Closeable {
        public int code;
        public InputStream body;
        private HttpURLConnection conn;
        private MiniHttp.Reply mini;

        public boolean ok() {
            return code >= 200 && code < 300;
        }

        /** The whole body as text (for error responses). */
        public String text() throws IOException {
            return body == null ? "" : new String(readAll(body), "UTF-8");
        }

        @Override
        /**
         * Safe from any thread: closing a chunked body drains it from the socket, which on the UI
         * thread is a NetworkOnMainThreadException (Stop in chat, Discard, restarting language
         * servers), so there it happens on a thread of its own.
         */
        public void close() {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        closeNow();
                    }
                }, "http-close");
                t.setDaemon(true);
                t.start();
                return;
            }
            closeNow();
        }

        private void closeNow() {
            try {
                if (mini != null) mini.close();
                if (body != null) body.close();
            } catch (IOException | RuntimeException ignored) {
                // closing anyway
            }
            try {
                if (conn != null) conn.disconnect();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static Stream stream(String method, String url, Map<String, String> headers, byte[] body, String contentType) throws IOException {
        Stream s = new Stream();
        if (ownStack()) {
            s.mini = MiniHttp.request(method, url, headers, body, contentType, userAgent, trust);
            s.code = s.mini.code;
            s.body = s.mini.body;
            return s;
        }
        HttpURLConnection c = open(url, method, headers);
        s.conn = c;
        c.setReadTimeout(300000);
        try {
            if (body != null) {
                c.setDoOutput(true);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body);
                } finally {
                    os.close();
                }
            }
            s.code = c.getResponseCode();
            s.body = s.code >= 400 ? c.getErrorStream() : c.getInputStream();
            return s;
        } catch (IOException e) {
            c.disconnect();
            throw e;
        }
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, headers, null, null);
    }

    public static Response postJson(String url, Map<String, String> headers, String json) throws IOException {
        return request("POST", url, headers, json.getBytes("UTF-8"), "application/json; charset=utf-8");
    }

    /** Downloads to {@code dest} (via a temp file), reporting progress. */
    public static void download(String url, File dest, Map<String, String> headers, Progress p) throws IOException {
        HttpURLConnection c = null;
        MiniHttp.Reply m = null;
        File tmp = new File(dest.getPath() + ".part");
        try {
            int code;
            long total;
            InputStream in;
            if (ownStack()) {
                m = MiniHttp.request("GET", url, headers, null, null, userAgent, trust);
                code = m.code;
                total = m.contentLength;
                in = m.body;
            } else {
                c = open(url, "GET", headers);
                code = c.getResponseCode();
                total = c.getContentLength();
                in = code >= 200 && code < 300 ? c.getInputStream() : null;
            }
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            OutputStream os = new FileOutputStream(tmp);
            try {
                byte[] b = new byte[32 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(b)) > 0) {
                    os.write(b, 0, n);
                    done += n;
                    if (p != null) p.onProgress(done, total);
                    if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                }
            } finally {
                os.close();
                in.close();
            }
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) throw new IOException("rename failed");
        } finally {
            if (c != null) c.disconnect();
            if (m != null) m.close();
            if (tmp.exists()) tmp.delete();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16 * 1024];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    public static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
