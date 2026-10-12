package com.disgusty.droid_codology.io;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Utf8StrictTest {
    private static boolean valid(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) bytes[i];
        return TextFiles.isValidUtf8(b, b.length);
    }

    @Test
    public void wellFormed() throws Exception {
        byte[] b = "aé€😀".getBytes("UTF-8");
        assertTrue(TextFiles.isValidUtf8(b, b.length));
        assertTrue(valid(0xED, 0x9F, 0xBF)); // U+D7FF, just below the surrogates
        assertTrue(valid(0xF4, 0x8F, 0xBF, 0xBF)); // U+10FFFF
    }

    @Test
    public void malformed() {
        assertFalse(valid(0xE0, 0x80, 0xAF, 0x41)); // overlong "/"
        assertFalse(valid(0xED, 0xA0, 0x80, 0x41)); // a UTF-16 surrogate
        assertFalse(valid(0xF0, 0x80, 0x80, 0xAF, 0x41)); // overlong 4-byte
        assertFalse(valid(0xF4, 0x90, 0x80, 0x80, 0x41)); // past U+10FFFF
        assertFalse(valid(0xC0, 0xAF, 0x41));
    }
}
