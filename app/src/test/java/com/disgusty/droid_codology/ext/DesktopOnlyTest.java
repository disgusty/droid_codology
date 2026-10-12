package com.disgusty.droid_codology.ext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DesktopOnlyTest {
    private static boolean nativeBytes(String name, int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) bytes[i];
        return ExtensionManager.isNative(name, b, b.length);
    }

    @Test
    public void targetPlatformFromManifest() {
        assertEquals("linux-arm64", ExtensionManager.targetPlatform(
                "<PackageManifest><Metadata><Identity Language=\"en-US\" Id=\"claude-code\" Version=\"2.1.291\" Publisher=\"Anthropic\" TargetPlatform=\"linux-arm64\"/>"));
        assertNull(ExtensionManager.targetPlatform("<Identity Id=\"x\" Version=\"1\" Publisher=\"p\"/>"));
        assertNull(ExtensionManager.targetPlatform("<Identity TargetPlatform=\"universal\"/>"));
        assertNull(ExtensionManager.targetPlatform("<Identity TargetPlatform=\"web\"/>"));
    }

    @Test
    public void desktopPrograms() {
        assertTrue(nativeBytes("resources/native-binary/claude", 0x7F, 'E', 'L', 'F'));
        assertTrue(nativeBytes("bin/rg", 0xCF, 0xFA, 0xED, 0xFE));
        assertTrue(nativeBytes("bin/tool", 'M', 'Z', 0x90, 0));
        assertTrue(nativeBytes("build/keytar.node", '{', '}', ' ', ' '));
        assertTrue(nativeBytes("lib/libfoo.so.1", 0, 0, 0, 0));
        assertTrue(nativeBytes("x.dll", 0, 0, 0, 0));
        assertFalse(nativeBytes("out/extension.js", 'v', 'a', 'r', ' '));
        assertFalse(nativeBytes("README.md", 'M', 'Z', 'x', 'y'));
        assertFalse(nativeBytes("lib/Main.class", 0xCA, 0xFE, 0xBA, 0xBE));
        assertFalse(nativeBytes("media/icon.png", 0x89, 'P', 'N', 'G'));
    }
}
