package com.disgusty.droid_codology.lang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.disgusty.droid_codology.text.Snippet;

import org.junit.Test;

public class EmmetTest {
    private static String html(String abbr) {
        String body = Emmet.expandHtml(abbr, "  ");
        return body == null ? null : Snippet.expand(body, "", "  ", null).text;
    }

    @Test
    public void nestingSiblingsAndNumbers() {
        assertEquals("<ul id=\"nav\">\n  <li class=\"item1\"></li>\n  <li class=\"item2\"></li>\n</ul>", html("ul#nav>li.item$*2"));
        assertEquals("<div></div>\n<p>Hi</p>", html("div+p{Hi}"));
        assertEquals("<div>\n  <header></header>\n</div>\n<footer></footer>", html("div>header^footer"));
        assertEquals("<a href=\"\"></a>", html("a"));
        assertEquals("<p><span>x</span></p>", html("p>span{x}"));
        assertEquals("<input type=\"email\" required=\"\">", html("input[type=email required]"));
        assertEquals("<div>\n  <p>1</p>\n  <p>2</p>\n</div>", html("div>(p{$})*2"));
    }

    @Test
    public void css() {
        assertEquals("margin: 10px 20px;", Emmet.cssExpand("m10-20"));
        assertEquals("width: 100%;", Emmet.cssExpand("w100p"));
        assertEquals("display: flex;", Emmet.cssExpand("df"));
        assertEquals("margin: 0 auto;", Emmet.cssExpand("m0-a"));
        assertEquals("z-index: 10;", Emmet.cssExpand("z10"));
        assertNull(Emmet.cssExpand("xyz"));
    }

    @Test
    public void detection() {
        assertTrue(Emmet.looksLikeAbbreviation("ul>li*3", false));
        assertTrue(Emmet.looksLikeAbbreviation("!", false));
        assertTrue(!Emmet.looksLikeAbbreviation("foobar", false));
        assertTrue(html("!").startsWith("<!DOCTYPE html>"));
    }
}
