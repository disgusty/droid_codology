package com.disgusty.droid_codology.net.tls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.disgusty.droid_codology.net.MiniHttp;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Handshakes with real servers; skipped when offline. */
public class LiveTlsTest {
    private static X509TrustManager trust() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        throw new IllegalStateException();
    }

    private static void online() {
        try {
            InetAddress.getByName("open-vsx.org");
        } catch (IOException e) {
            Assume.assumeNoException(e);
        }
    }

    @After
    public void reset() {
        TlsClient.offered = new int[]{0xCCA9, 0xCCA8, 0xC02B, 0xC02F, 0xC02C, 0xC030};
        TlsClient.offeredGroups = new int[]{0x001D, 0x0017};
    }

    @Test
    public void p256() throws Exception {
        online();
        TlsClient.offeredGroups = new int[]{0x0017};
        for (String host : new String[]{"github.com", "www.cloudflare.com"}) {
            TlsClient t = TlsClient.connect(host, 443, trust(), 20000);
            System.out.println(host + " -> " + t.cipherSuite());
            assertTrue(t.cipherSuite().endsWith("P-256"));
            t.close();
        }
    }

    private static String get(String url) throws Exception {
        MiniHttp.Reply r = MiniHttp.request("GET", url, null, null, null, "droid_codology-test", trust());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream in = r.body;
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        r.close();
        assertEquals(url, 200, r.code);
        return new String(bo.toByteArray(), "UTF-8");
    }

    @Test
    public void openVsxSearch() throws Exception {
        online();
        String json = get("https://open-vsx.org/api/-/search?query=theme&size=2");
        assertTrue(json, json.contains("\"extensions\""));
    }

    @Test
    public void eachSuite() throws Exception {
        online();
        String[][] cases = {
                {"cca8", "www.cloudflare.com"}, {"c02f", "github.com"}, {"c030", "github.com"},
                {"cca9", "www.cloudflare.com"}, {"c02b", "www.cloudflare.com"}, {"c02c", "www.cloudflare.com"},
        };
        for (String[] c : cases) {
            TlsClient.offered = new int[]{Integer.parseInt(c[0], 16)};
            TlsClient t = TlsClient.connect(c[1], 443, trust(), 20000);
            t.getOutputStream().write(("HEAD / HTTP/1.1\r\nHost: " + c[1] + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            byte[] b = new byte[12];
            int n = t.getInputStream().read(b);
            t.close();
            System.out.println(c[0] + " " + c[1] + " -> " + t.cipherSuite());
            assertTrue(c[1] + " " + c[0], n > 0 && new String(b, 0, n, "US-ASCII").startsWith("HTTP/1."));
        }
    }

    @Test
    public void chunkedAndRedirect() throws Exception {
        online();
        String html = get("https://github.com/microsoft/vscode/blob/main/LICENSE.txt");
        assertTrue(html.length() > 1000);
        String api = get("http://api.github.com/repos/microsoft/vscode");
        assertTrue(api.contains("\"full_name\""));
    }

    @Test
    public void rejectsWrongHost() throws Exception {
        online();
        try {
            TlsClient.connect("wrong.host.badssl.com", 443, trust(), 20000).close();
            throw new AssertionError("accepted wrong host");
        } catch (javax.net.ssl.SSLHandshakeException expected) {
            // expected
        }
    }

    @Test
    public void wildcardMatching() {
        assertTrue(TlsClient.nameMatches("*.github.com", "api.github.com"));
        assertFalse(TlsClient.nameMatches("*.github.com", "github.com"));
        assertFalse(TlsClient.nameMatches("*.github.com", "a.b.github.com"));
        assertTrue(TlsClient.nameMatches("Open-VSX.org", "open-vsx.org"));
    }
}
