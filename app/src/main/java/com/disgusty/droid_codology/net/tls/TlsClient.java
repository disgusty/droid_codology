package com.disgusty.droid_codology.net.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.X509TrustManager;

/**
 * A small TLS 1.2 client (RFC 5246) with ECDHE key exchange (X25519 / P-256) and AEAD suites
 * (ChaCha20-Poly1305, AES-GCM). Android before 5.0 can't talk to many current servers (no GCM or
 * ChaCha suites, no X25519), so there HTTPS goes through this instead of the platform stack.
 */
public final class TlsClient {
    private static final int CHACHA_ECDSA = 0xCCA9, CHACHA_RSA = 0xCCA8;
    private static final int AES128_ECDSA = 0xC02B, AES128_RSA = 0xC02F, AES256_ECDSA = 0xC02C, AES256_RSA = 0xC030;
    private static final int[] SUITES = {CHACHA_ECDSA, CHACHA_RSA, AES128_ECDSA, AES128_RSA, AES256_ECDSA, AES256_RSA};
    /** Offered suites, in preference order (tests narrow this to exercise each one). */
    static int[] offered = SUITES;
    private static final int X25519_GROUP = 0x001D, P256_GROUP = 0x0017;
    static int[] offeredGroups = {X25519_GROUP, P256_GROUP};

    private static final int CHANGE_CIPHER_SPEC = 20, ALERT = 21, HANDSHAKE = 22, APPLICATION_DATA = 23;

    private final Socket socket;
    private final DataInputStream sin;
    private final OutputStream sout;
    private final String host;

    private Aead readAead, writeAead;
    private long readSeq, writeSeq;
    private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
    private byte[] hsBuf = new byte[0];
    private int hsPos;
    private byte[] appBuf = new byte[0];
    private int appPos;
    private boolean closed, eof;
    private String suiteName = "";

    private final InputStream in = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (appPos >= appBuf.length) {
                if (eof) return -1;
                fillApp();
            }
            int n = Math.min(len, appBuf.length - appPos);
            System.arraycopy(appBuf, appPos, b, off, n);
            appPos += n;
            return n;
        }

        @Override
        public int available() {
            return appBuf.length - appPos;
        }

        @Override
        public void close() throws IOException {
            TlsClient.this.close();
        }
    };

    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, 16384);
                writeRecord(APPLICATION_DATA, b, off, n);
                off += n;
                len -= n;
            }
        }

        @Override
        public void flush() throws IOException {
            sout.flush();
        }

        @Override
        public void close() throws IOException {
            TlsClient.this.close();
        }
    };

    private TlsClient(Socket socket, String host) throws IOException {
        this.socket = socket;
        this.host = host;
        this.sin = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream(), 16 * 1024 + 512));
        this.sout = new java.io.BufferedOutputStream(socket.getOutputStream(), 16 * 1024 + 512);
    }

    /** Connects and performs the handshake; the server chain is checked with {@code trust} and {@code host}. */
    public static TlsClient connect(String host, int port, X509TrustManager trust, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(Math.max(timeoutMs, 60000));
            s.setTcpNoDelay(true);
            TlsClient c = new TlsClient(s, host);
            c.handshake(trust);
            // After the handshake, waits match the platform stack's streams: a model may think for
            // minutes before its first token.
            s.setSoTimeout(Math.max(timeoutMs, 300000));
            return c;
        } catch (IOException e) {
            s.close();
            throw e;
        } catch (RuntimeException e) {
            s.close();
            throw new SSLException(e.toString());
        }
    }

    public InputStream getInputStream() {
        return in;
    }

    public OutputStream getOutputStream() {
        return out;
    }

    public String cipherSuite() {
        return suiteName;
    }

    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            if (writeAead != null) writeRecord(ALERT, new byte[]{1, 0}, 0, 2);
        } catch (IOException ignored) {
        }
        socket.close();
    }

    // ------------------------------------------------------------------ handshake

    private void handshake(X509TrustManager trust) throws IOException {
        try {
            doHandshake(trust);
        } catch (GeneralSecurityException e) {
            SSLHandshakeException x = new SSLHandshakeException(e.getMessage() == null ? e.toString() : e.getMessage());
            x.initCause(e);
            throw x;
        }
    }

    private void doHandshake(X509TrustManager trust) throws IOException, GeneralSecurityException {
        byte[] clientRandom = random(32);
        sendHandshake(1, clientHello(clientRandom));
        sout.flush();

        // ServerHello
        Reader sh = expect(2);
        if (sh.u16() != 0x0303) throw new SSLHandshakeException("server doesn't speak TLS 1.2");
        byte[] serverRandom = sh.bytes(32);
        sh.bytes(sh.u8());
        int suite = sh.u16();
        boolean known = false;
        for (int s : offered) known |= s == suite;
        if (!known) throw new SSLHandshakeException("server chose unsupported suite " + Integer.toHexString(suite));
        if (sh.u8() != 0) throw new SSLHandshakeException("compression");
        boolean ecdsa = suite == CHACHA_ECDSA || suite == AES128_ECDSA || suite == AES256_ECDSA;
        boolean sha384 = suite == AES256_ECDSA || suite == AES256_RSA;

        // Certificate
        Reader cm = expect(11);
        Reader certs = new Reader(cm.bytes(cm.u24()));
        java.util.ArrayList<X509Certificate> chainList = new java.util.ArrayList<X509Certificate>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        while (certs.left() > 0) {
            byte[] der = certs.bytes(certs.u24());
            chainList.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
        }
        if (chainList.isEmpty()) throw new SSLHandshakeException("no server certificate");
        X509Certificate[] chain = chainList.toArray(new X509Certificate[chainList.size()]);
        trust.checkServerTrusted(chain, ecdsa ? "ECDHE_ECDSA" : "ECDHE_RSA");
        if (!hostMatches(chain[0], host)) throw new SSLHandshakeException("certificate doesn't match " + host);

        // ServerKeyExchange
        byte[] ske = readHandshake(12);
        Reader kx = new Reader(ske);
        if (kx.u8() != 3) throw new SSLHandshakeException("unsupported curve type");
        int group = kx.u16();
        byte[] serverPoint = kx.bytes(kx.u8());
        int paramsLen = kx.pos;
        int sigHash = kx.u8(), sigAlg = kx.u8();
        byte[] sig = kx.bytes(kx.u16());
        Signature verifier = Signature.getInstance(hashName(sigHash) + "with" + (sigAlg == 3 ? "ECDSA" : "RSA"));
        verifier.initVerify(chain[0].getPublicKey());
        verifier.update(clientRandom);
        verifier.update(serverRandom);
        verifier.update(ske, 0, paramsLen);
        if (!verifier.verify(sig)) throw new SSLHandshakeException("bad ServerKeyExchange signature");

        // Optional CertificateRequest, then ServerHelloDone
        byte[] msg = readHandshakeAny();
        boolean certRequested = false;
        if (msg[0] == 13) {
            certRequested = true;
            msg = readHandshakeAny();
        }
        if (msg[0] != 14) throw new SSLHandshakeException("expected ServerHelloDone, got " + msg[0]);

        // Key agreement
        byte[] ourPoint, pms;
        if (group == X25519_GROUP) {
            byte[] priv = random(32);
            ourPoint = X25519.publicKey(priv);
            pms = X25519.scalarMult(priv, serverPoint);
            int acc = 0;
            for (byte b : pms) acc |= b;
            if (acc == 0) throw new SSLHandshakeException("bad X25519 share");
        } else if (group == P256_GROUP) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            KeyPair kp = kpg.generateKeyPair();
            ECPublicKey pub = (ECPublicKey) kp.getPublic();
            if (serverPoint.length != 65 || serverPoint[0] != 4) throw new SSLHandshakeException("bad P-256 share");
            BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(serverPoint, 1, 33));
            BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(serverPoint, 33, 65));
            PublicKey sp = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), pub.getParams()));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(kp.getPrivate());
            ka.doPhase(sp, true);
            pms = ka.generateSecret();
            ourPoint = new byte[65];
            ourPoint[0] = 4;
            fixed(pub.getW().getAffineX(), ourPoint, 1, 32);
            fixed(pub.getW().getAffineY(), ourPoint, 33, 32);
        } else {
            throw new SSLHandshakeException("unsupported group " + group);
        }

        if (certRequested) sendHandshake(11, new byte[]{0, 0, 0});
        byte[] cke = new byte[1 + ourPoint.length];
        cke[0] = (byte) ourPoint.length;
        System.arraycopy(ourPoint, 0, cke, 1, ourPoint.length);
        sendHandshake(16, cke);

        String mac = sha384 ? "HmacSHA384" : "HmacSHA256";
        String digest = sha384 ? "SHA-384" : "SHA-256";
        byte[] master = prf(mac, pms, "master secret", concat(clientRandom, serverRandom), 48);
        boolean chacha = suite == CHACHA_ECDSA || suite == CHACHA_RSA;
        int keyLen = chacha || sha384 ? 32 : 16, ivLen = chacha ? 12 : 4;
        byte[] kb = prf(mac, master, "key expansion", concat(serverRandom, clientRandom), 2 * keyLen + 2 * ivLen);
        byte[] cKey = java.util.Arrays.copyOfRange(kb, 0, keyLen);
        byte[] sKey = java.util.Arrays.copyOfRange(kb, keyLen, 2 * keyLen);
        byte[] cIv = java.util.Arrays.copyOfRange(kb, 2 * keyLen, 2 * keyLen + ivLen);
        byte[] sIv = java.util.Arrays.copyOfRange(kb, 2 * keyLen + ivLen, 2 * keyLen + 2 * ivLen);
        suiteName = (ecdsa ? "ECDHE_ECDSA_" : "ECDHE_RSA_") + (chacha ? "CHACHA20_POLY1305" : sha384 ? "AES_256_GCM" : "AES_128_GCM")
                + (group == X25519_GROUP ? " x25519" : " P-256");

        // ChangeCipherSpec + Finished
        writeRecord(CHANGE_CIPHER_SPEC, new byte[]{1}, 0, 1);
        writeAead = chacha ? new ChaChaAead(cKey, cIv) : new GcmAead(cKey, cIv);
        writeSeq = 0;
        byte[] clientVerify = prf(mac, master, "client finished", hash(digest, transcript.toByteArray()), 12);
        sendHandshake(20, clientVerify);
        sout.flush();

        if (hsPos < hsBuf.length) throw new SSLHandshakeException("unexpected handshake data before ChangeCipherSpec");
        Record r = readRecord();
        if (r.type != CHANGE_CIPHER_SPEC) throw new SSLHandshakeException("expected ChangeCipherSpec, got " + r.type);
        readAead = chacha ? new ChaChaAead(sKey, sIv) : new GcmAead(sKey, sIv);
        readSeq = 0;
        byte[] expected = prf(mac, master, "server finished", hash(digest, transcript.toByteArray()), 12);
        byte[] fin = readHandshake(20);
        if (!MessageDigest.isEqual(expected, fin)) throw new SSLHandshakeException("bad server Finished");
    }

    private byte[] clientHello(byte[] clientRandom) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        u16(b, 0x0303);
        b.write(clientRandom);
        b.write(0); // session id
        u16(b, offered.length * 2 + 2);
        for (int s : offered) u16(b, s);
        u16(b, 0x00FF); // TLS_EMPTY_RENEGOTIATION_INFO_SCSV
        b.write(1);
        b.write(0); // null compression

        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        byte[] name = host.getBytes("US-ASCII");
        if (!isIpLiteral(host)) {
            u16(ext, 0);
            u16(ext, name.length + 5);
            u16(ext, name.length + 3);
            ext.write(0);
            u16(ext, name.length);
            ext.write(name);
        }
        u16(ext, 10); // supported_groups
        u16(ext, offeredGroups.length * 2 + 2);
        u16(ext, offeredGroups.length * 2);
        for (int g : offeredGroups) u16(ext, g);
        u16(ext, 11); // ec_point_formats: uncompressed
        u16(ext, 2);
        ext.write(1);
        ext.write(0);
        int[] sigAlgs = {0x0403, 0x0503, 0x0401, 0x0501, 0x0601, 0x0201};
        u16(ext, 13);
        u16(ext, sigAlgs.length * 2 + 2);
        u16(ext, sigAlgs.length * 2);
        for (int a : sigAlgs) u16(ext, a);
        byte[] alpn = "http/1.1".getBytes("US-ASCII");
        u16(ext, 16);
        u16(ext, alpn.length + 3);
        u16(ext, alpn.length + 1);
        ext.write(alpn.length);
        ext.write(alpn);
        u16(b, ext.size());
        ext.writeTo(b);
        return b.toByteArray();
    }

    private static String hashName(int h) throws SSLHandshakeException {
        switch (h) {
            case 2:
                return "SHA1";
            case 4:
                return "SHA256";
            case 5:
                return "SHA384";
            case 6:
                return "SHA512";
            default:
                throw new SSLHandshakeException("unsupported signature hash " + h);
        }
    }

    // ------------------------------------------------------------------ certificate name check

    static boolean hostMatches(X509Certificate cert, String host) {
        host = host.toLowerCase(Locale.US);
        boolean hadDns = false;
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    int type = (Integer) san.get(0);
                    if (type == 2) {
                        hadDns = true;
                        if (nameMatches(String.valueOf(san.get(1)), host)) return true;
                    } else if (type == 7 && host.equals(String.valueOf(san.get(1)))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (hadDns) return false;
        String dn = cert.getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            part = part.trim();
            if (part.startsWith("CN=") && nameMatches(part.substring(3), host)) return true;
        }
        return false;
    }

    static boolean nameMatches(String pattern, String host) {
        pattern = pattern.toLowerCase(Locale.US);
        if (pattern.endsWith(".")) pattern = pattern.substring(0, pattern.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (!pattern.startsWith("*.")) return pattern.equals(host);
        String suffix = pattern.substring(1);
        if (!host.endsWith(suffix)) return false;
        String label = host.substring(0, host.length() - suffix.length());
        return label.length() > 0 && label.indexOf('.') < 0;
    }

    private static boolean isIpLiteral(String h) {
        return h.indexOf(':') >= 0 || h.matches("[0-9.]+");
    }

    // ------------------------------------------------------------------ handshake messages

    private void sendHandshake(int type, byte[] body) throws IOException {
        byte[] m = new byte[4 + body.length];
        m[0] = (byte) type;
        m[1] = (byte) (body.length >>> 16);
        m[2] = (byte) (body.length >>> 8);
        m[3] = (byte) body.length;
        System.arraycopy(body, 0, m, 4, body.length);
        transcript.write(m);
        writeRecord(HANDSHAKE, m, 0, m.length);
    }

    private Reader expect(int type) throws IOException {
        return new Reader(readHandshake(type));
    }

    /** Body of the next handshake message, which must be of {@code type}. */
    private byte[] readHandshake(int type) throws IOException {
        byte[] m = readHandshakeAny();
        if (m[0] != type) throw new SSLHandshakeException("expected handshake " + type + ", got " + m[0]);
        return java.util.Arrays.copyOfRange(m, 4, m.length);
    }

    /** Next handshake message including its 4-byte header. */
    private byte[] readHandshakeAny() throws IOException {
        while (true) {
            int avail = hsBuf.length - hsPos;
            if (avail >= 4) {
                int len = ((hsBuf[hsPos + 1] & 0xFF) << 16) | ((hsBuf[hsPos + 2] & 0xFF) << 8) | (hsBuf[hsPos + 3] & 0xFF);
                if (avail >= 4 + len) {
                    byte[] m = java.util.Arrays.copyOfRange(hsBuf, hsPos, hsPos + 4 + len);
                    hsPos += 4 + len;
                    transcript.write(m);
                    return m;
                }
            }
            Record r = readRecord();
            if (r.type != HANDSHAKE) throw new SSLHandshakeException("unexpected record " + r.type + " during handshake");
            byte[] nb = new byte[avail + r.data.length];
            System.arraycopy(hsBuf, hsPos, nb, 0, avail);
            System.arraycopy(r.data, 0, nb, avail, r.data.length);
            hsBuf = nb;
            hsPos = 0;
        }
    }

    // ------------------------------------------------------------------ records

    private static final class Record {
        int type;
        byte[] data;
    }

    private Record readRecord() throws IOException {
        Record r = new Record();
        int type;
        try {
            type = sin.readUnsignedByte();
        } catch (EOFException e) {
            throw new EOFException("connection closed by server");
        }
        sin.readUnsignedShort();
        int len = sin.readUnsignedShort();
        if (len > 16384 + 2048) throw new SSLException("record too long");
        byte[] frag = new byte[len];
        sin.readFully(frag);
        if (readAead != null) {
            try {
                frag = readAead.decrypt(readSeq++, type, frag);
            } catch (GeneralSecurityException e) {
                throw new SSLException("record decryption failed: " + e.getMessage());
            }
        }
        if (type == ALERT) {
            if (frag.length >= 2 && frag[1] == 0) {
                r.type = ALERT;
                r.data = frag;
                eof = true;
                if (readAead == null) throw new SSLHandshakeException("server closed the connection");
                return r;
            }
            if (frag.length >= 2 && frag[0] == 2) throw new SSLHandshakeException("server alert " + (frag[1] & 0xFF));
        }
        r.type = type;
        r.data = frag;
        return r;
    }

    private void fillApp() throws IOException {
        Record r = readRecord();
        if (r.type == APPLICATION_DATA) {
            appBuf = r.data;
            appPos = 0;
        } else if (r.type == ALERT) {
            if (r.data.length >= 2 && r.data[1] == 0) eof = true;
        }
        // Post-handshake handshake messages (HelloRequest, tickets) are ignored.
    }

    private synchronized void writeRecord(int type, byte[] b, int off, int len) throws IOException {
        byte[] frag;
        if (writeAead != null) {
            try {
                frag = writeAead.encrypt(writeSeq++, type, b, off, len);
            } catch (GeneralSecurityException e) {
                throw new SSLException(e.toString());
            }
        } else {
            frag = java.util.Arrays.copyOfRange(b, off, off + len);
        }
        byte[] hdr = {(byte) type, 3, 3, (byte) (frag.length >>> 8), (byte) frag.length};
        sout.write(hdr);
        sout.write(frag);
        if (type != HANDSHAKE) sout.flush();
    }

    private static byte[] aad(long seq, int type, int len) {
        byte[] a = new byte[13];
        for (int i = 7; i >= 0; i--) {
            a[i] = (byte) seq;
            seq >>>= 8;
        }
        a[8] = (byte) type;
        a[9] = 3;
        a[10] = 3;
        a[11] = (byte) (len >>> 8);
        a[12] = (byte) len;
        return a;
    }

    private abstract static class Aead {
        abstract byte[] encrypt(long seq, int type, byte[] pt, int off, int len) throws GeneralSecurityException;

        abstract byte[] decrypt(long seq, int type, byte[] frag) throws GeneralSecurityException;
    }

    /** RFC 7905: nonce = IV xor seq, no explicit nonce. */
    private static final class ChaChaAead extends Aead {
        private final byte[] key, iv;

        ChaChaAead(byte[] key, byte[] iv) {
            this.key = key;
            this.iv = iv;
        }

        private byte[] nonce(long seq) {
            byte[] n = iv.clone();
            for (int i = 11; i >= 4; i--) {
                n[i] ^= (byte) seq;
                seq >>>= 8;
            }
            return n;
        }

        @Override
        byte[] encrypt(long seq, int type, byte[] pt, int off, int len) {
            return ChaCha20Poly1305.seal(key, nonce(seq), aad(seq, type, len), pt, off, len);
        }

        @Override
        byte[] decrypt(long seq, int type, byte[] frag) throws GeneralSecurityException {
            if (frag.length < 16) throw new GeneralSecurityException("short record");
            return ChaCha20Poly1305.open(key, nonce(seq), aad(seq, type, frag.length - 16), frag, 0, frag.length);
        }
    }

    /** RFC 5288: nonce = 4-byte implicit salt + 8-byte explicit nonce carried in the record. */
    private static final class GcmAead extends Aead {
        private final Gcm gcm;
        private final byte[] salt;

        GcmAead(byte[] key, byte[] salt) throws GeneralSecurityException {
            this.gcm = new Gcm(key);
            this.salt = salt;
        }

        @Override
        byte[] encrypt(long seq, int type, byte[] pt, int off, int len) throws GeneralSecurityException {
            byte[] nonce = new byte[12];
            System.arraycopy(salt, 0, nonce, 0, 4);
            long s = seq;
            for (int i = 11; i >= 4; i--) {
                nonce[i] = (byte) s;
                s >>>= 8;
            }
            byte[] ct = gcm.seal(nonce, aad(seq, type, len), pt, off, len);
            byte[] frag = new byte[8 + ct.length];
            System.arraycopy(nonce, 4, frag, 0, 8);
            System.arraycopy(ct, 0, frag, 8, ct.length);
            return frag;
        }

        @Override
        byte[] decrypt(long seq, int type, byte[] frag) throws GeneralSecurityException {
            if (frag.length < 24) throw new GeneralSecurityException("short record");
            byte[] nonce = new byte[12];
            System.arraycopy(salt, 0, nonce, 0, 4);
            System.arraycopy(frag, 0, nonce, 4, 8);
            return gcm.open(nonce, aad(seq, type, frag.length - 24), frag, 8, frag.length - 8);
        }
    }

    // ------------------------------------------------------------------ crypto helpers

    static byte[] prf(String macName, byte[] secret, String label, byte[] seed, int n) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(macName);
        mac.init(new SecretKeySpec(secret.length == 0 ? new byte[1] : secret, macName));
        byte[] fullSeed;
        try {
            fullSeed = concat(label.getBytes("US-ASCII"), seed);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        byte[] outBuf = new byte[n];
        byte[] a = fullSeed;
        int pos = 0;
        while (pos < n) {
            a = mac.doFinal(a);
            mac.update(a);
            byte[] chunk = mac.doFinal(fullSeed);
            int c = Math.min(chunk.length, n - pos);
            System.arraycopy(chunk, 0, outBuf, pos, c);
            pos += c;
        }
        return outBuf;
    }

    private static byte[] hash(String alg, byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance(alg).digest(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static void fixed(BigInteger v, byte[] dst, int off, int len) {
        byte[] b = v.toByteArray();
        int copy = Math.min(b.length, len);
        System.arraycopy(b, b.length - copy, dst, off + len - copy, copy);
    }

    private static void u16(ByteArrayOutputStream b, int v) {
        b.write(v >>> 8);
        b.write(v);
    }

    private static SecureRandom secureRandom;

    /**
     * Random bytes from /dev/urandom where present (sidesteps the Android 4.1–4.3 SecureRandom
     * seeding bug), mixed with SecureRandom.
     */
    public static synchronized byte[] random(int n) {
        if (secureRandom == null) secureRandom = new SecureRandom();
        byte[] r = new byte[n];
        secureRandom.nextBytes(r);
        java.io.File dev = new java.io.File("/dev/urandom");
        if (dev.exists()) {
            byte[] u = new byte[n];
            try {
                FileInputStream f = new FileInputStream(dev);
                try {
                    new DataInputStream(f).readFully(u);
                } finally {
                    f.close();
                }
                for (int i = 0; i < n; i++) r[i] ^= u[i];
            } catch (IOException ignored) {
            }
        }
        return r;
    }

    private static final class Reader {
        final byte[] b;
        int pos;

        Reader(byte[] b) {
            this.b = b;
        }

        int left() {
            return b.length - pos;
        }

        private void need(int n) throws SSLHandshakeException {
            if (pos + n > b.length) throw new SSLHandshakeException("truncated handshake message");
        }

        int u8() throws SSLHandshakeException {
            need(1);
            return b[pos++] & 0xFF;
        }

        int u16() throws SSLHandshakeException {
            need(2);
            int v = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        int u24() throws SSLHandshakeException {
            need(3);
            int v = ((b[pos] & 0xFF) << 16) | ((b[pos + 1] & 0xFF) << 8) | (b[pos + 2] & 0xFF);
            pos += 3;
            return v;
        }

        byte[] bytes(int n) throws SSLHandshakeException {
            need(n);
            byte[] r = java.util.Arrays.copyOfRange(b, pos, pos + n);
            pos += n;
            return r;
        }
    }
}
