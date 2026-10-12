package com.disgusty.droid_codology.linux;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AlpinePackagesTest {
    private static final String INDEX = "C:abc\nP:nodejs\nV:24.18.1-r0\nS:9000\nD:ca-certificates so:libc.musl-armv7.so.1 so:libicuuc.so.78 !nodejs-lts\np:cmd:node=24.18.1-r0\n\n"
            + "P:ca-certificates\nV:20260909-r0\nS:100\nD:/bin/sh\n\n"
            + "P:icu-libs\nV:78.1-r0\nS:5000\nD:icu-data-en>=78\np:so:libicuuc.so.78=78.1\n\n"
            + "P:icu-data-en\nV:78.1-r0\nS:2000\n\n"
            + "P:musl\nV:1.2.5-r10\nS:400\np:so:libc.musl-armv7.so.1=1\n\n"
            + "P:busybox-binsh\nV:1.37.0-r31\nS:10\np:/bin/sh cmd:sh=1.37.0-r31\n";

    @Test
    public void resolvesDependenciesSkippingInstalled() throws Exception {
        Map<String, AlpinePackages.Pkg> byName = new LinkedHashMap<String, AlpinePackages.Pkg>();
        for (AlpinePackages.Pkg k : AlpinePackages.parseIndex(INDEX, "main")) byName.put(k.name, k);
        assertEquals(6, byName.size());
        assertEquals(9000, byName.get("nodejs").size);
        HashMap<String, String> installed = new HashMap<String, String>();
        installed.put("musl", "1.2.5-r10");
        installed.put("busybox-binsh", "1.37.0-r31");
        List<String> got = new ArrayList<String>();
        for (AlpinePackages.Pkg k : AlpinePackages.resolve(byName, installed, Arrays.asList("nodejs"))) got.add(k.name);
        assertEquals(Arrays.asList("nodejs", "ca-certificates", "icu-libs", "icu-data-en"), got);
    }

    @Test(expected = java.io.IOException.class)
    public void unknownPackageIsAnError() throws Exception {
        Map<String, AlpinePackages.Pkg> byName = new LinkedHashMap<String, AlpinePackages.Pkg>();
        for (AlpinePackages.Pkg k : AlpinePackages.parseIndex(INDEX, "main")) byName.put(k.name, k);
        AlpinePackages.resolve(byName, new HashMap<String, String>(), Arrays.asList("no-such-thing"));
    }

    @Test
    public void bareNames() {
        assertEquals("so:libc.musl-armv7.so.1", AlpinePackages.bare("so:libc.musl-armv7.so.1=1"));
        assertEquals("icu-data-en", AlpinePackages.bare("icu-data-en>=78"));
        assertEquals("cmd:node", AlpinePackages.bare("cmd:node"));
    }

    /** Like Alpine's: a signature tar piece and the index tar piece, each its own gzip stream. */
    @Test
    public void readsConcatenatedGzipIndex() throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(gzip(tarEntry(".SIGN.RSA.alpine-devel.rsa.pub", new byte[300])));
        byte[] idx = INDEX.getBytes("UTF-8");
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        second.write(tarEntry("DESCRIPTION", "v3.24".getBytes("UTF-8")));
        second.write(tarEntry("APKINDEX", idx));
        second.write(new byte[1024]);
        all.write(gzip(second.toByteArray()));
        File f = File.createTempFile("APKINDEX", ".tar.gz");
        try {
            FileOutputStream os = new FileOutputStream(f);
            os.write(all.toByteArray());
            os.close();
            String text = AlpinePackages.readApkIndex(f);
            assertEquals(INDEX, text);
            assertTrue(text.contains("P:nodejs"));
        } finally {
            f.delete();
        }
    }

    private static byte[] gzip(byte[] b) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bo);
        gz.write(b);
        gz.close();
        return bo.toByteArray();
    }

    private static byte[] tarEntry(String name, byte[] data) throws Exception {
        byte[] h = new byte[512];
        byte[] n = name.getBytes("UTF-8");
        System.arraycopy(n, 0, h, 0, n.length);
        byte[] size = String.format("%011o", data.length).getBytes("US-ASCII");
        System.arraycopy(size, 0, h, 124, size.length);
        h[156] = '0';
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(h);
        bo.write(data);
        int pad = (512 - data.length % 512) % 512;
        bo.write(new byte[pad]);
        return bo.toByteArray();
    }
}
