package com.disgusty.droid_codology.net.tls;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM (NIST SP 800-38D) built on the platform's AES block cipher ("AES/ECB/NoPadding", present
 * on every Android) with GHASH in Java (4-bit tables), for Android versions without GCM suites.
 */
public final class Gcm {
    private final Cipher aes;
    /** H·i for i in 0..15 as (hi, lo) longs, for 4-bit table multiplication. */
    private final long[] tHi = new long[16], tLo = new long[16];

    public Gcm(byte[] key) throws GeneralSecurityException {
        aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] h = aes.doFinal(new byte[16]);
        long hHi = be64(h, 0), hLo = be64(h, 8);
        // Table of multiples of H (Shoup's method): t[i] = i·H in GF(2^128), bit-reflected order.
        tHi[8] = hHi;
        tLo[8] = hLo;
        for (int i = 4; i > 0; i >>= 1) {
            long hi = tHi[i * 2], lo = tLo[i * 2];
            boolean lsb = (lo & 1) != 0;
            lo = (lo >>> 1) | (hi << 63);
            hi >>>= 1;
            if (lsb) hi ^= 0xE100000000000000L;
            tHi[i] = hi;
            tLo[i] = lo;
        }
        for (int i = 2; i < 16; i <<= 1) {
            for (int j = 1; j < i; j++) {
                tHi[i + j] = tHi[i] ^ tHi[j];
                tLo[i + j] = tLo[i] ^ tLo[j];
            }
        }
    }

    private static long be64(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xFF);
        return v;
    }

    private static void put64(byte[] b, int o, long v) {
        for (int i = 7; i >= 0; i--) {
            b[o + i] = (byte) v;
            v >>>= 8;
        }
    }

    private static final long[] R = {
            0x0000L, 0x1C20L, 0x3840L, 0x2460L, 0x7080L, 0x6CA0L, 0x48C0L, 0x54E0L,
            0xE100L, 0xFD20L, 0xD940L, 0xC560L, 0x9180L, 0x8DA0L, 0xA9C0L, 0xB5E0L};

    /** x = x·H. */
    private void mulH(long[] x) {
        long zHi = 0, zLo = 0;
        long xHi = x[0], xLo = x[1];
        for (int i = 15; i >= 0; i--) {
            long b = i >= 8 ? (xLo >>> ((15 - i) * 8)) & 0xFF : (xHi >>> ((7 - i) * 8)) & 0xFF;
            for (int half = 0; half < 2; half++) {
                int nib = half == 0 ? (int) (b & 0xF) : (int) ((b >>> 4) & 0xF);
                if (!(i == 15 && half == 0)) {
                    int rem = (int) (zLo & 0xF);
                    zLo = (zLo >>> 4) | (zHi << 60);
                    zHi = (zHi >>> 4) ^ (R[rem] << 48);
                }
                zHi ^= tHi[nib];
                zLo ^= tLo[nib];
            }
        }
        x[0] = zHi;
        x[1] = zLo;
    }

    private void ghashUpdate(long[] y, byte[] data, int off, int len) {
        byte[] blk = new byte[16];
        for (int i = 0; i < len; i += 16) {
            int n = Math.min(16, len - i);
            java.util.Arrays.fill(blk, (byte) 0);
            System.arraycopy(data, off + i, blk, 0, n);
            y[0] ^= be64(blk, 0);
            y[1] ^= be64(blk, 8);
            mulH(y);
        }
    }

    private byte[] tag(byte[] j0, byte[] aad, byte[] ct, int ctOff, int ctLen) throws GeneralSecurityException {
        long[] y = new long[2];
        ghashUpdate(y, aad, 0, aad.length);
        ghashUpdate(y, ct, ctOff, ctLen);
        y[0] ^= (long) aad.length * 8;
        y[1] ^= (long) ctLen * 8;
        mulH(y);
        byte[] s = new byte[16];
        put64(s, 0, y[0]);
        put64(s, 8, y[1]);
        byte[] ek = aes.doFinal(j0);
        for (int i = 0; i < 16; i++) s[i] ^= ek[i];
        return s;
    }

    private void ctr(byte[] j0, byte[] in, int off, int len, byte[] out, int outOff) throws GeneralSecurityException {
        byte[] counter = j0.clone();
        byte[] ks = new byte[16];
        for (int i = 0; i < len; i += 16) {
            // inc32
            for (int k = 15; k >= 12; k--) if (++counter[k] != 0) break;
            aes.doFinal(counter, 0, 16, ks, 0);
            int n = Math.min(16, len - i);
            for (int j = 0; j < n; j++) out[outOff + i + j] = (byte) (in[off + i + j] ^ ks[j]);
        }
    }

    private static byte[] j0(byte[] nonce12) {
        byte[] j = new byte[16];
        System.arraycopy(nonce12, 0, j, 0, 12);
        j[15] = 1;
        return j;
    }

    /** Encrypts with a 12-byte nonce; returns ciphertext || tag. */
    public byte[] seal(byte[] nonce12, byte[] aad, byte[] pt, int off, int len) throws GeneralSecurityException {
        byte[] j0 = j0(nonce12);
        byte[] out = new byte[len + 16];
        ctr(j0, pt, off, len, out, 0);
        System.arraycopy(tag(j0, aad, out, 0, len), 0, out, len, 16);
        return out;
    }

    public byte[] open(byte[] nonce12, byte[] aad, byte[] ct, int off, int len) throws GeneralSecurityException {
        if (len < 16) throw new GeneralSecurityException("short record");
        int n = len - 16;
        byte[] j0 = j0(nonce12);
        byte[] t = tag(j0, aad, ct, off, n);
        int diff = 0;
        for (int i = 0; i < 16; i++) diff |= t[i] ^ ct[off + n + i];
        if (diff != 0) throw new GeneralSecurityException("bad record MAC");
        byte[] out = new byte[n];
        ctr(j0, ct, off, n, out, 0);
        return out;
    }
}
