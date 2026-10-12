package com.disgusty.droid_codology.lang;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Chat answers are untrusted text: safe mode must not let them run script. */
public class SafeMarkdownTest {
    private final MarkdownHtml md = new MarkdownHtml(null).safe(true);

    @Test
    public void rawHtmlIsText() {
        String h = md.render("<img src=x onerror=alert(1)>\n\n<script>alert(1)</script>\n\nhi <b onclick=x>b</b>");
        assertFalse(h, h.contains("<img src=x"));
        assertFalse(h, h.contains("<script>"));
        assertFalse(h, h.contains("<b onclick"));
        assertTrue(h, h.contains("&lt;script&gt;"));
    }

    @Test
    public void dangerousLinksAreDropped() {
        String h = md.render("[a](javascript:alert(1)) [b](JaVaScRiPt:x) [c](data:text/html,x) ![d](data:image/svg+xml,x) [e](https://ok.example/)");
        assertFalse(h, h.toLowerCase().contains("javascript:"));
        assertFalse(h, h.contains("data:text"));
        assertFalse(h, h.contains("data:image/svg"));
        assertTrue(h, h.contains("href=\"https://ok.example/\""));
    }

    @Test
    public void emmetDoesNotExplode() {
        // a million elements, and a multiplier past int range: no answer instead of a frozen editor
        assertNull(Emmet.expandHtml("(div*100>p*100)*100", "  "));
        assertTrue(Emmet.expandHtml("li*99999999999", "  ") != null);
    }
}
