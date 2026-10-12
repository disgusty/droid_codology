package com.disgusty.droid_codology.net.tls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Official test vectors: RFC 8439 (ChaCha20-Poly1305), RFC 7748 (X25519), NIST/McGrew-Viega (AES-GCM). */
public class CryptoVectorsTest {
    static byte[] hex(String s) {
        s = s.replaceAll("\\s", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    static String hexOf(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    @Test
    public void poly1305Rfc8439() {
        byte[] key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b");
        byte[] msg = "Cryptographic Forum Research Group".getBytes();
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", hexOf(ChaCha20Poly1305.poly1305(key, msg, 0, msg.length)));
    }

    @Test
    public void aeadRfc8439() throws Exception {
        byte[] key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = hex("070000004041424344454647");
        byte[] aad = hex("50515253c0c1c2c3c4c5c6c7");
        byte[] pt = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.").getBytes();
        byte[] out = ChaCha20Poly1305.seal(key, nonce, aad, pt, 0, pt.length);
        String ct = hexOf(out);
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6", ct.substring(0, 64));
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", ct.substring(ct.length() - 32));
        assertArrayEquals(pt, ChaCha20Poly1305.open(key, nonce, aad, out, 0, out.length));
    }

    @Test
    public void x25519Rfc7748() {
        byte[] alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hexOf(X25519.publicKey(alicePriv)));
        byte[] shared = X25519.scalarMult(alicePriv, X25519.publicKey(bobPriv));
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hexOf(shared));
    }

    @Test
    public void gcmTestCase4() throws Exception {
        // McGrew & Viega GCM spec, test case 4 (AES-128, 60-byte plaintext, 20-byte AAD).
        Gcm g = new Gcm(hex("feffe9928665731c6d6a8f9467308308"));
        byte[] pt = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39");
        byte[] aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2");
        byte[] out = g.seal(hex("cafebabefacedbaddecaf888"), aad, pt, 0, pt.length);
        assertEquals("42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091"
                + "5bc94fbc3221a5db94fae95ae7121a47", hexOf(out));
        assertArrayEquals(pt, g.open(hex("cafebabefacedbaddecaf888"), aad, out, 0, out.length));
    }
}
