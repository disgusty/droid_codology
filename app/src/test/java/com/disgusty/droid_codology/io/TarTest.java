package com.disgusty.droid_codology.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class TarTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Records links and modes instead of making them (the JVM on Windows can't create symlinks). */
    private static final class FakeFs implements Tar.Fs {
        final Map<String, String> links = new LinkedHashMap<String, String>();
        final Map<String, Integer> modes = new LinkedHashMap<String, Integer>();

        @Override
        public int symlink(String target, String path) {
            links.put(path, target);
            return 0;
        }

        @Override
        public int chmod(String path, int mode) {
            modes.put(path, mode);
            return 0;
        }
    }

    private static byte[] header(String name, char type, int mode, long size, String link) {
        byte[] h = new byte[512];
        put(h, 0, name);
        put(h, 100, String.format("%07o", mode));
        put(h, 108, "0000000");
        put(h, 116, "0000000");
        put(h, 124, String.format("%011o", size));
        put(h, 136, "00000000000");
        h[156] = (byte) type;
        if (link != null) put(h, 157, link);
        put(h, 257, "ustar");
        put(h, 263, "00");
        for (int i = 148; i < 156; i++) h[i] = ' ';
        int sum = 0;
        for (byte b : h) sum += b & 0xFF;
        put(h, 148, String.format("%06o", sum));
        h[154] = 0;
        return h;
    }

    private static void put(byte[] h, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, b.length);
    }

    private static void entry(ByteArrayOutputStream out, String name, char type, int mode, byte[] data, String link) throws Exception {
        out.write(header(name, type, mode, data.length, link));
        out.write(data);
        int pad = (512 - data.length % 512) % 512;
        out.write(new byte[pad]);
    }

    private static byte[] paxRecord(String key, String value) {
        String body = " " + key + "=" + value + "\n";
        int len = body.length();
        int total = len + String.valueOf(len).length();
        if (String.valueOf(total).length() != String.valueOf(len).length()) total++;
        return (total + body).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void filesFoldersLinksAndLongNames() throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, "./bin/", '5', 0755, new byte[0], null);
        entry(tar, "./bin/busybox", '0', 0755, "ELF...".getBytes(StandardCharsets.UTF_8), null);
        entry(tar, "./bin/sh", '2', 0777, new byte[0], "/bin/busybox");
        StringBuilder longName = new StringBuilder("usr/share/");
        for (int i = 0; i < 12; i++) longName.append("very-long-folder-name-").append(i).append('/');
        longName.append("file.txt");
        entry(tar, "././@PaxHeader", 'x', 0644, paxRecord("path", longName.toString()), null);
        entry(tar, "truncated-name", '0', 0644, "hello".getBytes(StandardCharsets.UTF_8), null);
        entry(tar, "etc/motd", '0', 0644, "welcome".getBytes(StandardCharsets.UTF_8), null);
        entry(tar, "etc/motd-copy", '1', 0644, new byte[0], "etc/motd");
        tar.write(new byte[1024]);

        File dest = tmp.newFolder("root");
        FakeFs fs = new FakeFs();
        Tar.extract(new ByteArrayInputStream(tar.toByteArray()), dest, fs, null);

        assertEquals("ELF...", new String(Files.readAllBytes(new File(dest, "bin/busybox").toPath()), StandardCharsets.UTF_8));
        assertEquals("/bin/busybox", fs.links.get(new File(dest.getCanonicalFile(), "bin/sh").getPath()));
        assertEquals("hello", new String(Files.readAllBytes(new File(dest, longName.toString()).toPath()), StandardCharsets.UTF_8));
        assertEquals("welcome", new String(Files.readAllBytes(new File(dest, "etc/motd-copy").toPath()), StandardCharsets.UTF_8));
        assertEquals(Integer.valueOf(0755), fs.modes.get(new File(dest.getCanonicalFile(), "bin/busybox").getPath()));
    }

    @Test
    public void nothingLandsOutside() throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, "../evil.txt", '0', 0644, "x".getBytes(StandardCharsets.UTF_8), null);
        entry(tar, "a/../../evil2.txt", '0', 0644, "x".getBytes(StandardCharsets.UTF_8), null);
        entry(tar, "/etc/passwd", '0', 0644, "root".getBytes(StandardCharsets.UTF_8), null);
        tar.write(new byte[1024]);
        File parent = tmp.newFolder("parent");
        File dest = new File(parent, "root");
        dest.mkdirs();
        Tar.extract(new ByteArrayInputStream(tar.toByteArray()), dest, new FakeFs(), null);
        assertFalse(new File(parent, "evil.txt").exists());
        assertFalse(new File(parent, "evil2.txt").exists());
        // an absolute name is taken as relative to the folder
        assertTrue(new File(dest, "etc/passwd").isFile());
    }

    @Test
    public void octalAndBase256Sizes() {
        byte[] h = new byte[512];
        put(h, 124, "00000001750");
        assertEquals(1000, Tar.octal(h, 124, 12));
        byte[] big = new byte[512];
        big[124] = (byte) 0x80;
        big[135] = 1;
        big[134] = 2;
        assertEquals(0x0201, Tar.octal(big, 124, 12));
    }
}
