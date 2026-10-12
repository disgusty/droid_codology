package com.disgusty.droid_codology.work;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ExtWebviewTest {
    private static final String H = "'sha256-abc'";

    @Test
    public void addsHashToScriptSrc() {
        assertEquals("default-src 'none'; script-src 'nonce-x' " + H,
                ExtWebview.allowScript("default-src 'none'; script-src 'nonce-x';", H));
    }

    @Test
    public void fallsBackToDefaultSrc() {
        assertEquals("default-src https://dc-resource.local " + H + "; img-src *",
                ExtWebview.allowScript("default-src https://dc-resource.local; img-src *", H));
    }

    @Test
    public void noneIsReplacedByTheHash() {
        assertEquals("script-src " + H, ExtWebview.allowScript("script-src 'none'", H));
    }

    @Test
    public void unsafeInlineNeedsNothing() {
        String p = "script-src 'unsafe-inline' https:";
        assertEquals(p, ExtWebview.allowScript(p, H));
    }

    @Test
    public void noScriptDirectiveMeansNoLimit() {
        String p = "img-src *; style-src 'unsafe-inline'";
        assertEquals(p, ExtWebview.allowScript(p, H));
    }
}
