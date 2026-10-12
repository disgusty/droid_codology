package com.disgusty.droid_codology.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Checks the port against the published Material 3 baseline scheme (seed #6750A4). */
public class MaterialColorTest {
    private static void near(String role, int expected, int actual) {
        int dr = Math.abs(((expected >> 16) & 0xFF) - ((actual >> 16) & 0xFF));
        int dg = Math.abs(((expected >> 8) & 0xFF) - ((actual >> 8) & 0xFF));
        int db = Math.abs((expected & 0xFF) - (actual & 0xFF));
        assertTrue(role + ": expected " + Integer.toHexString(expected) + " got " + Integer.toHexString(actual), dr <= 3 && dg <= 3 && db <= 3);
    }

    @Test
    public void baselineLight() {
        MaterialColor.Scheme s = MaterialColor.scheme(0xFF6750A4, false);
        near("primary", 0xFF6750A4, s.primary);
        near("onPrimary", 0xFFFFFFFF, s.onPrimary);
        near("primaryContainer", 0xFFEADDFF, s.primaryContainer);
        near("onPrimaryContainer", 0xFF21005D, s.onPrimaryContainer);
        near("secondary", 0xFF625B71, s.secondary);
        near("secondaryContainer", 0xFFE8DEF8, s.secondaryContainer);
        near("tertiary", 0xFF7D5260, s.tertiary);
        near("outline", 0xFF79747E, s.outline);
        near("onSurfaceVariant", 0xFF49454F, s.onSurfaceVariant);
    }

    @Test
    public void baselineDark() {
        MaterialColor.Scheme s = MaterialColor.scheme(0xFF6750A4, true);
        near("primary", 0xFFD0BCFF, s.primary);
        near("onPrimary", 0xFF381E72, s.onPrimary);
        near("primaryContainer", 0xFF4F378B, s.primaryContainer);
        near("secondaryContainer", 0xFF4A4458, s.secondaryContainer);
        near("onSurface", 0xFFE6E1E5, s.onSurface);
    }

    @Test
    public void toneIsLstar() {
        for (int t = 5; t <= 95; t += 15) {
            int c = MaterialColor.hct(200, 30, t);
            assertTrue("tone " + t, Math.abs(MaterialColor.lstarFromArgb(c) - t) < 1.0);
        }
    }
}
