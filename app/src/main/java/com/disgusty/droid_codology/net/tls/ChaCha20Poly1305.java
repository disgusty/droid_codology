package com.disgusty.droid_codology.net.tls;

import java.security.GeneralSecurityException;

/** ChaCha20-Poly1305 AEAD (RFC 8439) in plain Java, for TLS on Android versions without it. */
public final class ChaCha20Poly1305 {
    private ChaCha20Poly1305() {
    }

    // ------------------------------------------------------------------ ChaCha20

    private static int rotl(int v, int c) {
        return (v << c) | (v >>> (32 - c));
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    /** One 64-byte keystream block for (key, counter, nonce). */
    static void block(int[] key, int counter, int[] nonce, byte[] out) {
        int x0 = 0x61707865, x1 = 0x3320646e, x2 = 0x79622d32, x3 = 0x6b206574;
        int x4 = key[0], x5 = key[1], x6 = key[2], x7 = key[3], x8 = key[4], x9 = key[5], x10 = key[6], x11 = key[7];
        int x12 = counter, x13 = nonce[0], x14 = nonce[1], x15 = nonce[2];
        int j0 = x0, j1 = x1, j2 = x2, j3 = x3, j4 = x4, j5 = x5, j6 = x6, j7 = x7, j8 = x8, j9 = x9, j10 = x10, j11 = x11,
                j12 = x12, j13 = x13, j14 = x14, j15 = x15;
        for (int i = 0; i < 10; i++) {
            x0 += x4; x12 = rotl(x12 ^ x0, 16); x8 += x12; x4 = rotl(x4 ^ x8, 12);
            x0 += x4; x12 = rotl(x12 ^ x0, 8); x8 += x12; x4 = rotl(x4 ^ x8, 7);
            x1 += x5; x13 = rotl(x13 ^ x1, 16); x9 += x13; x5 = rotl(x5 ^ x9, 12);
            x1 += x5; x13 = rotl(x13 ^ x1, 8); x9 += x13; x5 = rotl(x5 ^ x9, 7);
            x2 += x6; x14 = rotl(x14 ^ x2, 16); x10 += x14; x6 = rotl(x6 ^ x10, 12);
            x2 += x6; x14 = rotl(x14 ^ x2, 8); x10 += x14; x6 = rotl(x6 ^ x10, 7);
            x3 += x7; x15 = rotl(x15 ^ x3, 16); x11 += x15; x7 = rotl(x7 ^ x11, 12);
            x3 += x7; x15 = rotl(x15 ^ x3, 8); x11 += x15; x7 = rotl(x7 ^ x11, 7);
            x0 += x5; x15 = rotl(x15 ^ x0, 16); x10 += x15; x5 = rotl(x5 ^ x10, 12);
            x0 += x5; x15 = rotl(x15 ^ x0, 8); x10 += x15; x5 = rotl(x5 ^ x10, 7);
            x1 += x6; x12 = rotl(x12 ^ x1, 16); x11 += x12; x6 = rotl(x6 ^ x11, 12);
            x1 += x6; x12 = rotl(x12 ^ x1, 8); x11 += x12; x6 = rotl(x6 ^ x11, 7);
            x2 += x7; x13 = rotl(x13 ^ x2, 16); x8 += x13; x7 = rotl(x7 ^ x8, 12);
            x2 += x7; x13 = rotl(x13 ^ x2, 8); x8 += x13; x7 = rotl(x7 ^ x8, 7);
            x3 += x4; x14 = rotl(x14 ^ x3, 16); x9 += x14; x4 = rotl(x4 ^ x9, 12);
            x3 += x4; x14 = rotl(x14 ^ x3, 8); x9 += x14; x4 = rotl(x4 ^ x9, 7);
        }
        int[] s = {x0 + j0, x1 + j1, x2 + j2, x3 + j3, x4 + j4, x5 + j5, x6 + j6, x7 + j7, x8 + j8, x9 + j9, x10 + j10,
                x11 + j11, x12 + j12, x13 + j13, x14 + j14, x15 + j15};
        for (int i = 0; i < 16; i++) {
            out[i * 4] = (byte) s[i];
            out[i * 4 + 1] = (byte) (s[i] >>> 8);
            out[i * 4 + 2] = (byte) (s[i] >>> 16);
            out[i * 4 + 3] = (byte) (s[i] >>> 24);
        }
    }

    private static int[] words(byte[] b, int off, int n) {
        int[] w = new int[n];
        for (int i = 0; i < n; i++) w[i] = le32(b, off + i * 4);
        return w;
    }

    /** XORs {@code in[off, off+len)} with the ChaCha20 keystream starting at block {@code counter}. */
    static void xor(byte[] key, int counter, byte[] nonce, byte[] in, int off, int len, byte[] out, int outOff) {
        int[] k = words(key, 0, 8), n = words(nonce, 0, 3);
        byte[] ks = new byte[64];
        for (int i = 0; i < len; i += 64) {
            block(k, counter++, n, ks);
            int m = Math.min(64, len - i);
            for (int j = 0; j < m; j++) out[outOff + i + j] = (byte) (in[off + i + j] ^ ks[j]);
        }
    }

    // ------------------------------------------------------------------ Poly1305 (26-bit limbs)

    static byte[] poly1305(byte[] key, byte[] msg, int off, int len) {
        long r0 = le32(key, 0) & 0x3ffffff, r1 = (le32(key, 3) >>> 2) & 0x3ffff03, r2 = (le32(key, 6) >>> 4) & 0x3ffc0ff,
                r3 = (le32(key, 9) >>> 6) & 0x3f03fff, r4 = (le32(key, 12) >>> 8) & 0x00fffff;
        long s1 = r1 * 5, s2 = r2 * 5, s3 = r3 * 5, s4 = r4 * 5;
        long h0 = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0;
        byte[] blk = new byte[17];
        for (int i = 0; i < len; i += 16) {
            int n = Math.min(16, len - i);
            java.util.Arrays.fill(blk, (byte) 0);
            System.arraycopy(msg, off + i, blk, 0, n);
            blk[n] = 1;
            long t0 = le32(blk, 0) & 0xffffffffL, t1 = le32(blk, 4) & 0xffffffffL, t2 = le32(blk, 8) & 0xffffffffL,
                    t3 = le32(blk, 12) & 0xffffffffL, hibit = blk[16] & 0xFF;
            h0 += t0 & 0x3ffffff;
            h1 += ((t1 << 32 | t0) >>> 26) & 0x3ffffff;
            h2 += ((t2 << 32 | t1) >>> 20) & 0x3ffffff;
            h3 += ((t3 << 32 | t2) >>> 14) & 0x3ffffff;
            h4 += (t3 >>> 8) | (hibit << 24);
            long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
            long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
            long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
            long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
            long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;
            long c = d0 >>> 26;
            h0 = d0 & 0x3ffffff;
            d1 += c;
            c = d1 >>> 26;
            h1 = d1 & 0x3ffffff;
            d2 += c;
            c = d2 >>> 26;
            h2 = d2 & 0x3ffffff;
            d3 += c;
            c = d3 >>> 26;
            h3 = d3 & 0x3ffffff;
            d4 += c;
            c = d4 >>> 26;
            h4 = d4 & 0x3ffffff;
            h0 += c * 5;
            c = h0 >>> 26;
            h0 &= 0x3ffffff;
            h1 += c;
        }
        long c = h1 >>> 26;
        h1 &= 0x3ffffff;
        h2 += c;
        c = h2 >>> 26;
        h2 &= 0x3ffffff;
        h3 += c;
        c = h3 >>> 26;
        h3 &= 0x3ffffff;
        h4 += c;
        c = h4 >>> 26;
        h4 &= 0x3ffffff;
        h0 += c * 5;
        c = h0 >>> 26;
        h0 &= 0x3ffffff;
        h1 += c;
        // h - p
        long g0 = h0 + 5;
        c = g0 >>> 26;
        g0 &= 0x3ffffff;
        long g1 = h1 + c;
        c = g1 >>> 26;
        g1 &= 0x3ffffff;
        long g2 = h2 + c;
        c = g2 >>> 26;
        g2 &= 0x3ffffff;
        long g3 = h3 + c;
        c = g3 >>> 26;
        g3 &= 0x3ffffff;
        long g4 = h4 + c - (1L << 26);
        boolean useG = (g4 >>> 63) == 0;
        if (useG) {
            h0 = g0;
            h1 = g1;
            h2 = g2;
            h3 = g3;
            h4 = g4;
        }
        long f0 = ((h0) | (h1 << 26)) & 0xffffffffL;
        long f1 = ((h1 >>> 6) | (h2 << 20)) & 0xffffffffL;
        long f2 = ((h2 >>> 12) | (h3 << 14)) & 0xffffffffL;
        long f3 = ((h3 >>> 18) | (h4 << 8)) & 0xffffffffL;
        f0 += le32(key, 16) & 0xffffffffL;
        f1 += (le32(key, 20) & 0xffffffffL) + (f0 >>> 32);
        f2 += (le32(key, 24) & 0xffffffffL) + (f1 >>> 32);
        f3 += (le32(key, 28) & 0xffffffffL) + (f2 >>> 32);
        byte[] tag = new byte[16];
        long[] f = {f0, f1, f2, f3};
        for (int i = 0; i < 4; i++) {
            tag[i * 4] = (byte) f[i];
            tag[i * 4 + 1] = (byte) (f[i] >>> 8);
            tag[i * 4 + 2] = (byte) (f[i] >>> 16);
            tag[i * 4 + 3] = (byte) (f[i] >>> 24);
        }
        return tag;
    }

    // ------------------------------------------------------------------ AEAD

    private static byte[] macData(byte[] aad, byte[] ct, int ctOff, int ctLen) {
        int aadPad = (16 - aad.length % 16) % 16, ctPad = (16 - ctLen % 16) % 16;
        byte[] m = new byte[aad.length + aadPad + ctLen + ctPad + 16];
        System.arraycopy(aad, 0, m, 0, aad.length);
        System.arraycopy(ct, ctOff, m, aad.length + aadPad, ctLen);
        int p = aad.length + aadPad + ctLen + ctPad;
        long al = aad.length, cl = ctLen;
        for (int i = 0; i < 8; i++) {
            m[p + i] = (byte) (al >>> (8 * i));
            m[p + 8 + i] = (byte) (cl >>> (8 * i));
        }
        return m;
    }

    private static byte[] polyKey(byte[] key, byte[] nonce) {
        byte[] block = new byte[64];
        block(words(key, 0, 8), 0, words(nonce, 0, 3), block);
        byte[] pk = new byte[32];
        System.arraycopy(block, 0, pk, 0, 32);
        return pk;
    }

    /** Encrypts; returns ciphertext || 16-byte tag. */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] pt, int off, int len) {
        byte[] out = new byte[len + 16];
        xor(key, 1, nonce, pt, off, len, out, 0);
        byte[] mac = macData(aad, out, 0, len);
        byte[] tag = poly1305(polyKey(key, nonce), mac, 0, mac.length);
        System.arraycopy(tag, 0, out, len, 16);
        return out;
    }

    /** Decrypts ciphertext||tag; throws if the tag doesn't verify. */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ct, int off, int len) throws GeneralSecurityException {
        if (len < 16) throw new GeneralSecurityException("short record");
        int n = len - 16;
        byte[] mac = macData(aad, ct, off, n);
        byte[] tag = poly1305(polyKey(key, nonce), mac, 0, mac.length);
        int diff = 0;
        for (int i = 0; i < 16; i++) diff |= tag[i] ^ ct[off + n + i];
        if (diff != 0) throw new GeneralSecurityException("bad record MAC");
        byte[] out = new byte[n];
        xor(key, 1, nonce, ct, off, n, out, 0);
        return out;
    }
}
