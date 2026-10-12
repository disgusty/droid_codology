package com.disgusty.droid_codology.io;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public class TextFilesTest {
    private static TextFiles.Loaded load(byte[] b) throws Exception {
        return TextFiles.decode(b, b.length, null);
    }

    private static String text(TextFiles.Loaded l) {
        return new String(l.chars, 0, l.length);
    }

    @Test
    public void utf8WithBomAndCrlf() throws Exception {
        byte[] b = "﻿a\r\nб\r\n".getBytes("UTF-8");
        TextFiles.Loaded l = load(b);
        assertEquals("UTF-8", l.charset);
        assertTrue(l.bom);
        assertEquals(TextFiles.CRLF, l.eol);
        assertEquals("a\nб\n", text(l));
        // Round trip keeps the exact bytes.
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        TextFiles.write(os, l.chars, l.length, l.charset, l.bom, l.eol);
        assertArrayEquals(b, os.toByteArray());
    }

    @Test
    public void cyrillicLegacyEncodings() throws Exception {
        String s = "Привет, мир! Это проверка кодировки.\r\n";
        assertEquals("windows-1251", load(s.getBytes("windows-1251")).charset);
        assertEquals("KOI8-R", load(s.getBytes("KOI8-R")).charset);
        assertEquals(s.replace("\r\n", "\n"), text(load(s.getBytes("windows-1251"))));
    }

    @Test
    public void westernAccentsAreNotCyrillic() throws Exception {
        // The last-resort fallback follows the device language; pin it so only the heuristic is tested.
        java.util.Locale saved = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.US);
        try {
            byte[] b = "Café crème brûlée, déjà vu — naïve façade.".getBytes("windows-1252");
            assertEquals("windows-1252", load(b).charset);
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }

    @Test
    public void utf16NoBom() throws Exception {
        TextFiles.Loaded l = load("hello world\nline two".getBytes("UTF-16LE"));
        assertEquals("UTF-16LE", l.charset);
        assertEquals("hello world\nline two", text(l));
        assertFalse(l.binary);
    }

    @Test
    public void mixedEolAndSurrogatesRoundTrip() throws Exception {
        String s = "a\nb\r\nc 😀\n";
        TextFiles.Loaded l = load(s.getBytes("UTF-8"));
        assertTrue(l.mixedEol);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) big.append("😀x");
        char[] c = big.toString().toCharArray();
        TextFiles.write(os, c, c.length, "UTF-8", false, TextFiles.LF);
        assertEquals(big.toString(), new String(os.toByteArray(), "UTF-8"));
    }
}
