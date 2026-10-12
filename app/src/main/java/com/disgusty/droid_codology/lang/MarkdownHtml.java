package com.disgusty.droid_codology.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown (CommonMark + GitHub tables, task lists, strikethrough) to HTML for the preview.
 * Fenced code blocks are highlighted with the editor's own lexers, in the current theme colors.
 */
public final class MarkdownHtml {
    public interface CodeColorizer {
        /** HTML for a code block (escaped, with spans), or null to show it plain. */
        String colorize(String languageHint, String code);
    }

    private final CodeColorizer colorizer;
    /** Untrusted text (AI answers): no raw HTML at all, links only to http(s) and mailto. */
    private boolean safe;

    public MarkdownHtml(CodeColorizer colorizer) {
        this.colorizer = colorizer;
    }

    public MarkdownHtml safe(boolean on) {
        safe = on;
        return this;
    }

    /**
     * A link or image target that can't run script: javascript:, vbscript:, data: (except images
     * in trusted documents) and anything else unusual become "#". In safe mode only http(s), mailto
     * and in-page anchors pass.
     */
    String url(String u, boolean image) {
        String t = u.trim();
        String lower = t.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\u0000-\\u001f]", "");
        int colon = lower.indexOf(':');
        int slash = lower.indexOf('/');
        boolean hasScheme = colon > 0 && (slash < 0 || colon < slash) && lower.substring(0, colon).matches("[a-z][a-z0-9+.-]*");
        if (!hasScheme) return safe && !t.startsWith("#") ? "#" : t;
        String scheme = lower.substring(0, colon);
        if (scheme.equals("http") || scheme.equals("https")) return t;
        if (scheme.equals("mailto") && !image) return t;
        if (!safe && image && lower.startsWith("data:image/") && !lower.startsWith("data:image/svg")) return t;
        return "#";
    }

    public String render(String md) {
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        blocks(lines, 0, lines.length, out);
        return out.toString();
    }

    private static final Pattern ATX = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern HR = Pattern.compile("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$");
    private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})\\s*([^`\\s]*)");
    private static final Pattern UL = Pattern.compile("^( *)([-*+])[ \\t]+(.*)$");
    private static final Pattern OL = Pattern.compile("^( *)(\\d{1,9})[.)][ \\t]+(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");

    private void blocks(String[] l, int from, int to, StringBuilder out) {
        int i = from;
        while (i < to) {
            String line = l[i];
            if (line.trim().isEmpty()) {
                i++;
                continue;
            }
            Matcher m;
            if ((m = FENCE.matcher(line)).find()) {
                String fence = m.group(2), lang = m.group(3);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < to && !l[i].trim().startsWith(fence.substring(0, 3)) ) {
                    code.append(l[i]).append('\n');
                    i++;
                }
                i++;
                String c = code.length() > 0 ? code.substring(0, code.length() - 1) : "";
                String html = colorizer == null ? null : colorizer.colorize(lang, c);
                out.append("<pre><code").append(lang.isEmpty() ? "" : " class=\"language-" + esc(lang) + "\"").append('>')
                        .append(html != null ? html : esc(c)).append("</code></pre>\n");
                continue;
            }
            if ((m = ATX.matcher(line)).find()) {
                int level = m.group(1).length();
                String text = m.group(2) == null ? "" : m.group(2);
                out.append("<h").append(level).append(" id=\"").append(slug(text)).append("\">").append(inline(text)).append("</h").append(level).append(">\n");
                i++;
                continue;
            }
            if (HR.matcher(line).matches()) {
                out.append("<hr>\n");
                i++;
                continue;
            }
            if (line.trim().startsWith(">")) {
                ArrayList<String> inner = new ArrayList<String>();
                while (i < to && !l[i].trim().isEmpty() && (l[i].trim().startsWith(">") || !inner.isEmpty())) {
                    String t = l[i].trim();
                    if (t.startsWith(">")) t = t.substring(1).startsWith(" ") ? t.substring(2) : t.substring(1);
                    inner.add(t);
                    i++;
                }
                out.append("<blockquote>\n");
                blocks(inner.toArray(new String[inner.size()]), 0, inner.size(), out);
                out.append("</blockquote>\n");
                continue;
            }
            if (UL.matcher(line).find() || OL.matcher(line).find()) {
                i = list(l, i, to, out);
                continue;
            }
            if (line.contains("|") && i + 1 < to && TABLE_SEP.matcher(l[i + 1]).matches()) {
                i = table(l, i, to, out);
                continue;
            }
            if (!safe && (line.trim().startsWith("<") && line.trim().length() > 1 && Character.isLetter(line.trim().charAt(1)) || line.trim().startsWith("<!--"))) {
                // Raw HTML block until a blank line.
                while (i < to && !l[i].trim().isEmpty()) out.append(l[i++]).append('\n');
                continue;
            }
            // Paragraph (setext headings end it).
            StringBuilder p = new StringBuilder();
            while (i < to && !l[i].trim().isEmpty()) {
                String t = l[i];
                if (p.length() > 0 && (t.matches("^ {0,3}=+\\s*$") || t.matches("^ {0,3}-+\\s*$"))) {
                    int level = t.trim().startsWith("=") ? 1 : 2;
                    out.append("<h").append(level).append(">").append(inline(p.toString().trim())).append("</h").append(level).append(">\n");
                    p.setLength(0);
                    i++;
                    break;
                }
                if (p.length() > 0 && (ATX.matcher(t).find() || FENCE.matcher(t).find() || t.trim().startsWith(">") || HR.matcher(t).matches())) break;
                if (p.length() > 0) {
                    // Hard break: two trailing spaces or a backslash.
                    if (p.toString().endsWith("  ") || p.toString().endsWith("\\")) {
                        p.setLength(p.length() - (p.toString().endsWith("\\") ? 1 : 0));
                        p.append("<br>");
                    }
                    p.append('\n');
                }
                p.append(t.trim());
                i++;
            }
            if (p.length() > 0) out.append("<p>").append(inline(p.toString())).append("</p>\n");
        }
    }

    private int list(String[] l, int i, int to, StringBuilder out) {
        Matcher first = UL.matcher(l[i]);
        boolean ordered = !first.find();
        Matcher m0 = ordered ? OL.matcher(l[i]) : UL.matcher(l[i]);
        m0.find();
        int baseIndent = m0.group(1).length();
        out.append(ordered ? (m0.group(2).equals("1") ? "<ol>\n" : "<ol start=\"" + m0.group(2) + "\">\n") : "<ul>\n");
        while (i < to) {
            Matcher m = ordered ? OL.matcher(l[i]) : UL.matcher(l[i]);
            if (!m.find() || m.group(1).length() != baseIndent) break;
            String content = m.group(3);
            ArrayList<String> sub = new ArrayList<String>();
            i++;
            // Continuation and nested lines are indented deeper than the marker.
            while (i < to) {
                String t = l[i];
                if (t.trim().isEmpty()) {
                    if (i + 1 < to && indentOf(l[i + 1]) > baseIndent) {
                        sub.add("");
                        i++;
                        continue;
                    }
                    break;
                }
                if (indentOf(t) <= baseIndent) break;
                sub.add(t.substring(Math.min(t.length(), baseIndent + 2)));
                i++;
            }
            String li = content;
            String checkbox = "";
            if (li.startsWith("[ ] ") || li.startsWith("[x] ") || li.startsWith("[X] ")) {
                checkbox = "<input type=\"checkbox\" disabled" + (li.charAt(1) != ' ' ? " checked" : "") + "> ";
                li = li.substring(4);
            }
            out.append("<li").append(checkbox.isEmpty() ? "" : " class=\"task\"").append('>').append(checkbox).append(inline(li));
            if (!sub.isEmpty()) {
                out.append('\n');
                blocks(sub.toArray(new String[sub.size()]), 0, sub.size(), out);
            }
            out.append("</li>\n");
            while (i < to && l[i].trim().isEmpty() && i + 1 < to && (ordered ? OL : UL).matcher(l[i + 1]).find()) i++;
        }
        out.append(ordered ? "</ol>\n" : "</ul>\n");
        return i;
    }

    private static int indentOf(String s) {
        int n = 0;
        while (n < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == '\t')) n += s.charAt(n) == '\t' ? 4 : 1;
        return n;
    }

    private int table(String[] l, int i, int to, StringBuilder out) {
        List<String> head = cells(l[i]);
        List<String> sep = cells(l[i + 1]);
        String[] align = new String[sep.size()];
        for (int k = 0; k < sep.size(); k++) {
            String s = sep.get(k).trim();
            align[k] = s.startsWith(":") && s.endsWith(":") ? "center" : s.endsWith(":") ? "right" : s.startsWith(":") ? "left" : null;
        }
        out.append("<table>\n<thead><tr>");
        for (int k = 0; k < head.size(); k++) out.append(cell("th", head.get(k), k < align.length ? align[k] : null));
        out.append("</tr></thead>\n<tbody>\n");
        i += 2;
        while (i < to && l[i].contains("|") && !l[i].trim().isEmpty()) {
            List<String> row = cells(l[i]);
            out.append("<tr>");
            for (int k = 0; k < head.size(); k++) out.append(cell("td", k < row.size() ? row.get(k) : "", k < align.length ? align[k] : null));
            out.append("</tr>\n");
            i++;
        }
        out.append("</tbody></table>\n");
        return i;
    }

    private String cell(String tag, String content, String align) {
        return "<" + tag + (align != null ? " style=\"text-align:" + align + "\"" : "") + ">" + inline(content.trim()) + "</" + tag + ">";
    }

    private static List<String> cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.substring(0, r.length() - 1);
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean code = false;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '`') code = !code;
            if (c == '\\' && i + 1 < r.length() && r.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
                continue;
            }
            if (c == '|' && !code) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    // ------------------------------------------------------------------ inline

    private static final Pattern CODE_SPAN = Pattern.compile("(`+)(.+?)\\1");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]*)>?(?:\\s+\"([^\"]*)\")?\\s*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(\\s*<?([^)\\s>]*)>?(?:\\s+\"([^\"]*)\")?\\s*\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<(https?://[^>\\s]+)>|(?<![\"'=(>])\\b(https?://[^\\s<)]+[^\\s<).,;:!?])");

    String inline(String s) {
        // Protect code spans first; everything else is escaped then formatted.
        ArrayList<String> codes = new ArrayList<String>();
        Matcher cm = CODE_SPAN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (cm.find()) {
            codes.add("<code>" + esc(cm.group(2).trim()) + "</code>");
            cm.appendReplacement(sb, "\u0001" + (codes.size() - 1) + "\u0002");
        }
        cm.appendTail(sb);
        String t = sb.toString();
        // Keep simple inline HTML tags (not for untrusted text), escape the rest.
        t = safe ? esc(t) : escKeepTags(t);
        t = t.replaceAll("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|~])", "\u0003$1");
        t = replaceAll(IMAGE, t, new Repl() {
            @Override
            public String apply(Matcher m) {
                return "<img src=\"" + attr(url(m.group(2), true)) + "\" alt=\"" + attr(m.group(1)) + "\"" + (m.group(3) != null ? " title=\"" + attr(m.group(3)) + "\"" : "") + ">";
            }
        });
        t = replaceAll(LINK, t, new Repl() {
            @Override
            public String apply(Matcher m) {
                return "<a href=\"" + attr(url(m.group(2), false)) + "\"" + (m.group(3) != null ? " title=\"" + attr(m.group(3)) + "\"" : "") + ">" + m.group(1) + "</a>";
            }
        });
        t = replaceAll(AUTOLINK, t, new Repl() {
            @Override
            public String apply(Matcher m) {
                String u = m.group(1) != null ? m.group(1) : m.group(2);
                return "<a href=\"" + attr(url(u, false)) + "\">" + u + "</a>";
            }
        });
        t = t.replaceAll("(\\*\\*|__)(?=\\S)(.+?[*_]*)(?<=\\S)\\1", "<strong>$2</strong>");
        t = t.replaceAll("(?<![\\w*])\\*(?=\\S)(.+?)(?<=\\S)\\*(?!\\*)", "<em>$1</em>");
        t = t.replaceAll("(?<![\\w_])_(?=\\S)(.+?)(?<=\\S)_(?![\\w_])", "<em>$1</em>");
        t = t.replaceAll("~~(?=\\S)(.+?)(?<=\\S)~~", "<del>$1</del>");
        t = t.replace('\u0003', '\u0000').replace("\u0000", "");
        for (int k = 0; k < codes.size(); k++) t = t.replace("\u0001" + k + "\u0002", codes.get(k));
        return t;
    }

    private interface Repl {
        String apply(Matcher m);
    }

    private static String replaceAll(Pattern p, String s, Repl r) {
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(r.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }

    private static final Pattern INLINE_TAG = Pattern.compile("</?(?:b|i|em|strong|code|kbd|sub|sup|br|span|u|s|del|ins|mark|small|img|a)(?:\\s[^<>]*)?/?>", Pattern.CASE_INSENSITIVE);

    private static String escKeepTags(String s) {
        Matcher m = INLINE_TAG.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String tag = m.group();
            // A tag with an event handler or a script URL is shown as text, not run.
            String squeezed = tag.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\u0000-\\u001f]", "");
            boolean active = tag.matches("(?is).*\\son[a-z]+\\s*=.*") || squeezed.contains("javascript:") || squeezed.contains("vbscript:")
                    || squeezed.contains("data:text") || squeezed.contains("&#");
            out.append(esc(s.substring(last, m.start()))).append(active ? esc(tag) : tag);
            last = m.end();
        }
        out.append(esc(s.substring(last)));
        return out.toString();
    }

    public static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&':
                    // Keep existing entities.
                    if (s.substring(i).matches("^&(#\\d+|#x[0-9a-fA-F]+|\\w+);[\\s\\S]*")) sb.append('&');
                    else sb.append("&amp;");
                    break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** For text that inline() already escaped; only quotes need care inside an attribute. */
    private static String attr(String s) {
        return s.replace("\"", "&quot;");
    }

    private static String slug(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("<[^>]*>", "").replaceAll("[^\\p{L}\\p{N}\\s-]", "").trim().replaceAll("\\s+", "-");
    }
}
