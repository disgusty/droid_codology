package com.disgusty.droid_codology.account;

import android.net.Uri;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Receives an OAuth redirect from the browser on http://127.0.0.1:port (RFC 8252 loopback
 * redirect), then sends the browser back to the app. Used with PKCE.
 */
public final class Loopback implements java.io.Closeable {
    private final ServerSocket socket;
    public final String state = random(16);
    public final String verifier = random(48);
    /** Google returns our state; OpenRouter returns none, so there an answer without it is accepted. */
    private final boolean stateRequired;

    public Loopback() throws IOException {
        this(true);
    }

    public Loopback(boolean stateRequired) throws IOException {
        this.stateRequired = stateRequired;
        socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    /** S256 code challenge of {@link #verifier}. */
    public String challenge() {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
            return Base64.encodeToString(d, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Waits for the browser's request and returns its query parameters (as a Uri). Requests
     * without our state (favicon, scanners) are answered and skipped.
     */
    public Uri await(long timeoutMs, String doneTitle, String doneText) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new SocketTimeoutException("timed out");
            socket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
            Socket s = socket.accept(); // the overall deadline (or close()) ends the wait
            try {
                s.setSoTimeout(10000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                String line = in.readLine();
                // Drain the headers.
                for (String h = in.readLine(); h != null && !h.isEmpty(); h = in.readLine()) {
                    // ignore
                }
                String target = line == null ? "" : line.split(" ").length > 1 ? line.split(" ")[1] : "";
                Uri u = Uri.parse("http://127.0.0.1" + target);
                String st = u.getQueryParameter("state");
                boolean answer = u.getQueryParameter("code") != null || u.getQueryParameter("error") != null;
                boolean ours = answer && (st == null ? !stateRequired : state.equals(st));
                boolean failed = u.getQueryParameter("error") != null;
                respond(s.getOutputStream(), ours, failed ? "droid_codology" : doneTitle, failed ? u.getQueryParameter("error") : doneText);
                if (ours) return u;
            } catch (IOException e) {
                // One bad connection (a browser's idle preconnect timing out, a reset) isn't the answer: keep waiting.
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void respond(OutputStream os, boolean ours, String title, String text) throws IOException {
        String back = Account.REDIRECT + "?done=1";
        title = android.text.TextUtils.htmlEncode(title);
        text = android.text.TextUtils.htmlEncode(text);
        String body = !ours ? "" : "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<title>" + title + "</title></head><body style=\"font-family:sans-serif;padding:24px\"><h2>" + title + "</h2><p>" + text
                + "</p><p><a href=\"" + back + "\" style=\"font-size:18px\">droid_codology</a></p>"
                + "<script>setTimeout(function(){location.href='" + back + "'},300)</script></body></html>";
        byte[] b = body.getBytes("UTF-8");
        String head = (ours ? "HTTP/1.1 200 OK" : "HTTP/1.1 404 Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        os.write(head.getBytes("US-ASCII"));
        os.write(b);
        os.flush();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /** URL-safe random text; seeded from /dev/urandom too (Android 4.1-4.3 SecureRandom bug). */
    static String random(int bytes) {
        byte[] b = com.disgusty.droid_codology.net.tls.TlsClient.random(bytes);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
