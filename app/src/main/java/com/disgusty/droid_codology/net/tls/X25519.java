package com.disgusty.droid_codology.net.tls;

import java.math.BigInteger;

/** X25519 Diffie-Hellman (RFC 7748), for TLS key exchange on Android versions without it. */
public final class X25519 {
    private X25519() {
    }

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final byte[] BASE = new byte[32];

    static {
        BASE[0] = 9;
    }

    private static BigInteger le(byte[] b) {
        byte[] be = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) be[b.length - i] = b[i];
        return new BigInteger(be);
    }

    private static byte[] toLe(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    /** scalar · u (both 32 bytes, little endian). */
    public static byte[] scalarMult(byte[] scalar, byte[] u) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= 127;
        k[31] |= 64;
        BigInteger kk = le(k);
        byte[] uu = u.clone();
        uu[31] &= 127;
        BigInteger x1 = le(uu).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = kk.testBit(t) ? 1 : 0;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tmp = x2;
                x2 = x3;
                x3 = tmp;
                tmp = z2;
                z2 = z3;
                z3 = tmp;
            }
            swap = kt;
            BigInteger a = x2.add(z2).mod(P), aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P), bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P), d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P), cb = c.multiply(b).mod(P);
            BigInteger s = da.add(cb).mod(P);
            x3 = s.multiply(s).mod(P);
            BigInteger df = da.subtract(cb).mod(P);
            z3 = x1.multiply(df.multiply(df).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap == 1) {
            x2 = x3;
            z2 = z3;
        }
        BigInteger r = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);
        return toLe(r);
    }

    public static byte[] publicKey(byte[] privateKey) {
        return scalarMult(privateKey, BASE);
    }
}
