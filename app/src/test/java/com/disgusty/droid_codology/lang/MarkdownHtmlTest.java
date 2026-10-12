package com.disgusty.droid_codology.lang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MarkdownHtmlTest {
    private final MarkdownHtml md = new MarkdownHtml(null);

    @Test
    public void blocks() {
        String h = md.render("# Title\n\nSome **bold** and *it* and `code <x>`.\n\n- a\n- [x] done\n  - nested\n\n1. one\n2. two\n\n> quote\n\n```js\nlet a = 1 < 2;\n```\n\n| A | B |\n|:--|--:|\n| 1 | 2 |\n");
        assertTrue(h, h.contains("<h1 id=\"title\">Title</h1>"));
        assertTrue(h, h.contains("<strong>bold</strong>"));
        assertTrue(h, h.contains("<em>it</em>"));
        assertTrue(h, h.contains("<code>code &lt;x&gt;</code>"));
        assertTrue(h, h.contains("<li class=\"task\"><input type=\"checkbox\" disabled checked> done"));
        assertTrue(h, h.contains("<ul>\n<li>nested</li>"));
        assertTrue(h, h.contains("<ol>\n<li>one</li>\n<li>two</li>"));
        assertTrue(h, h.contains("<blockquote>\n<p>quote</p>"));
        assertTrue(h, h.contains("<pre><code class=\"language-js\">let a = 1 &lt; 2;</code></pre>"));
        assertTrue(h, h.contains("<th style=\"text-align:left\">A</th>"));
        assertTrue(h, h.contains("<td style=\"text-align:right\">2</td>"));
    }

    @Test
    public void links() {
        assertEquals("<p><a href=\"https://x.y\">site</a> and <img src=\"a.png\" alt=\"pic\"> <a href=\"https://q.r/s\">https://q.r/s</a></p>\n",
                md.render("[site](https://x.y) and ![pic](a.png) https://q.r/s"));
    }
}
