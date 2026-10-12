package com.disgusty.droid_codology.work;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class AppLockTest {
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** PBKDF2-HMAC-SHA256 test vectors (RFC 7914 §11 and the widely used set for SHA-256). */
    @Test
    public void pbkdf2Vectors() {
        byte[] salt = "salt".getBytes(StandardCharsets.UTF_8);
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", hex(AppLock.pbkdf2("password", salt, 1)));
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43", hex(AppLock.pbkdf2("password", salt, 2)));
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", hex(AppLock.pbkdf2("password", salt, 4096)));
    }
}
