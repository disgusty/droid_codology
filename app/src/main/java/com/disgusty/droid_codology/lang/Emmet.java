package com.disgusty.droid_codology.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Emmet abbreviations (VS Code has Emmet built in): HTML like "ul#nav>li.item$*3>a{Link $}" and
 * CSS like "m10-20", "df", "w100p". Expansions are VS Code snippets with tab stops on empty
 * attributes and element contents.
 */
public final class Emmet {
    private Emmet() {
    }

    // ------------------------------------------------------------------ HTML

    private static final HashSet<String> VOID = new HashSet<String>(Arrays.asList(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr", "param"));
    private static final HashSet<String> INLINE = new HashSet<String>(Arrays.asList(
            "a", "abbr", "b", "bdi", "bdo", "br", "cite", "code", "data", "dfn", "em", "i", "kbd", "mark", "q", "s", "samp",
            "small", "span", "strong", "sub", "sup", "time", "u", "var", "img", "input", "label", "button", "select", "textarea"));
    private static final HashMap<String, String> DEFAULT_ATTRS = new HashMap<String, String>();

    static {
        DEFAULT_ATTRS.put("a", "href");
        DEFAULT_ATTRS.put("img", "src|alt");
        DEFAULT_ATTRS.put("input", "type=text");
        DEFAULT_ATTRS.put("link", "rel=stylesheet|href");
        DEFAULT_ATTRS.put("script", "");
        DEFAULT_ATTRS.put("form", "action");
        DEFAULT_ATTRS.put("label", "for");
        DEFAULT_ATTRS.put("iframe", "src|frameborder=0");
        DEFAULT_ATTRS.put("area", "shape|coords|href|alt");
        DEFAULT_ATTRS.put("source", "src|type");
        DEFAULT_ATTRS.put("select", "name|id");
        DEFAULT_ATTRS.put("textarea", "name|id|cols=30|rows=10");
        DEFAULT_ATTRS.put("button", "");
    }

    private static final class El {
        String tag;
        String id;
        final ArrayList<String> classes = new ArrayList<String>();
        final ArrayList<String[]> attrs = new ArrayList<String[]>();
        String text;
        int count = 1;
        final ArrayList<El> children = new ArrayList<El>();
        boolean group;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        /** expr := term (op term)*; returns the list of siblings at this level. */
        List<El> expr(El parent) {
            ArrayList<El> siblings = new ArrayList<El>();
            El last = term();
            if (last == null) return null;
            siblings.add(last);
            ArrayList<El> stack = new ArrayList<El>();
            List<El> current = siblings;
            ArrayList<List<El>> levels = new ArrayList<List<El>>();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ')') break;
                if (c == '>') {
                    i++;
                    El child = term();
                    if (child == null) return null;
                    levels.add(current);
                    stack.add(last);
                    current = last.children;
                    current.add(child);
                    last = child;
                } else if (c == '+') {
                    i++;
                    El sib = term();
                    if (sib == null) return null;
                    current.add(sib);
                    last = sib;
                } else if (c == '^') {
                    while (i < s.length() && s.charAt(i) == '^') {
                        i++;
                        if (!levels.isEmpty()) {
                            current = levels.remove(levels.size() - 1);
                            stack.remove(stack.size() - 1);
                        }
                    }
                    El sib = term();
                    if (sib == null) return null;
                    current.add(sib);
                    last = sib;
                } else return null;
            }
            return siblings;
        }

        El term() {
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                El g = new El();
                g.group = true;
                List<El> inner = expr(g);
                if (inner == null || i >= s.length() || s.charAt(i) != ')') return null;
                i++;
                g.children.addAll(inner);
                multiplier(g);
                return g;
            }
            El e = new El();
            int st = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '-' || s.charAt(i) == ':' || s.charAt(i) == '!')) i++;
            if (i > st) e.tag = s.substring(st, i);
            boolean any = e.tag != null;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '#') {
                    i++;
                    e.id = ident();
                    any = true;
                } else if (c == '.') {
                    i++;
                    e.classes.add(ident());
                    any = true;
                } else if (c == '[') {
                    i++;
                    if (!attrs(e)) return null;
                    any = true;
                } else if (c == '{') {
                    i++;
                    int depth = 1, ts = i;
                    while (i < s.length() && depth > 0) {
                        if (s.charAt(i) == '{') depth++;
                        else if (s.charAt(i) == '}') depth--;
                        i++;
                    }
                    if (depth != 0) return null;
                    e.text = s.substring(ts, i - 1);
                    any = true;
                } else break;
            }
            if (!any) return null;
            multiplier(e);
            return e;
        }

        private void multiplier(El e) {
            if (i < s.length() && s.charAt(i) == '*') {
                i++;
                int st = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                // li*99999999999 must not overflow parseInt.
                if (i > st) e.count = i - st > 3 ? 100 : Math.max(1, Math.min(100, Integer.parseInt(s.substring(st, i))));
            }
        }

        private String ident() {
            int st = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '-' || s.charAt(i) == '_' || s.charAt(i) == '$' || s.charAt(i) == '@')) i++;
            return s.substring(st, i);
        }

        private boolean attrs(El e) {
            while (i < s.length() && s.charAt(i) != ']') {
                while (i < s.length() && s.charAt(i) == ' ') i++;
                int st = i;
                while (i < s.length() && s.charAt(i) != '=' && s.charAt(i) != ' ' && s.charAt(i) != ']') i++;
                String name = s.substring(st, i);
                String val = null;
                if (i < s.length() && s.charAt(i) == '=') {
                    i++;
                    if (i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                        char q = s.charAt(i++);
                        int vs = i;
                        while (i < s.length() && s.charAt(i) != q) i++;
                        val = s.substring(vs, Math.min(i, s.length()));
                        i++;
                    } else {
                        int vs = i;
                        while (i < s.length() && s.charAt(i) != ' ' && s.charAt(i) != ']') i++;
                        val = s.substring(vs, i);
                    }
                }
                if (!name.isEmpty()) e.attrs.add(new String[]{name, val});
            }
            if (i >= s.length()) return false;
            i++;
            return true;
        }
    }

    /** True if {@code abbr} looks like an Emmet abbreviation worth offering (not just a word). */
    public static boolean looksLikeAbbreviation(String abbr, boolean css) {
        if (abbr.isEmpty() || abbr.length() > 200) return false;
        if (css) return cssExpand(abbr) != null;
        if (abbr.equals("!")) return true;
        for (int k = 0; k < abbr.length(); k++) if (">+^*.#[{(".indexOf(abbr.charAt(k)) >= 0) return expandHtml(abbr, "\t") != null;
        // A bare known tag name.
        return KNOWN_TAGS.contains(abbr.toLowerCase(Locale.ROOT)) || abbr.startsWith("lorem");
    }

    private static final HashSet<String> KNOWN_TAGS = new HashSet<String>(Arrays.asList(
            "html", "head", "body", "div", "span", "p", "a", "ul", "ol", "li", "nav", "header", "footer", "main", "section",
            "article", "aside", "h1", "h2", "h3", "h4", "h5", "h6", "img", "input", "button", "form", "label", "select", "option",
            "table", "thead", "tbody", "tr", "td", "th", "textarea", "script", "style", "link", "meta", "title", "br", "hr",
            "strong", "em", "code", "pre", "blockquote", "figure", "figcaption", "video", "audio", "source", "canvas", "svg",
            "iframe", "dl", "dt", "dd", "small", "template", "slot", "details", "summary", "dialog", "picture"));

    /** Expands an HTML abbreviation to a snippet body (tab stops included), or null if it doesn't parse. */
    public static String expandHtml(String abbr, String indentUnit) {
        if (abbr.equals("!") || abbr.equals("html:5")) {
            return "<!DOCTYPE html>\n<html lang=\"${1:en}\">\n<head>\n" + indentUnit + "<meta charset=\"UTF-8\">\n" + indentUnit
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" + indentUnit
                    + "<title>${2:Document}</title>\n</head>\n<body>\n" + indentUnit + "$0\n</body>\n</html>";
        }
        if (abbr.startsWith("lorem")) {
            int n = 30;
            try {
                if (abbr.length() > 5) n = Integer.parseInt(abbr.substring(5));
            } catch (NumberFormatException e) {
                return null;
            }
            return lorem(Math.max(1, Math.min(n, 500)));
        }
        Parser p = new Parser(abbr);
        List<El> roots = p.expr(null);
        if (roots == null || p.i != abbr.length()) return null;
        StringBuilder sb = new StringBuilder();
        int[] stop = {1};
        try {
            for (El e : roots) render(e, null, sb, "", indentUnit, stop, 1, 1);
        } catch (TooBig e) {
            return null; // (div*100>p*100)*100: a million elements isn't an abbreviation anyone means
        }
        return sb.toString().replaceAll("\\n+$", "");
    }

    private static final class TooBig extends RuntimeException {
    }

    private static final int MAX_OUTPUT = 256 * 1024;

    private static void render(El e, String parentTag, StringBuilder sb, String indent, String unit, int[] stop, int index, int total) {
        for (int k = 1; k <= e.count; k++) {
            if (sb.length() > MAX_OUTPUT) throw new TooBig();
            int idx = e.count > 1 ? k : index, tot = e.count > 1 ? e.count : total;
            if (e.group) {
                for (El c : e.children) render(c, parentTag, sb, indent, unit, stop, idx, tot);
                continue;
            }
            String tag = e.tag != null ? e.tag : implicitTag(parentTag);
            boolean voidEl = VOID.contains(tag);
            sb.append(indent).append('<').append(tag);
            if (e.id != null) sb.append(" id=\"").append(number(e.id, idx, tot)).append('"');
            if (!e.classes.isEmpty()) {
                sb.append(" class=\"");
                for (int c = 0; c < e.classes.size(); c++) {
                    if (c > 0) sb.append(' ');
                    sb.append(number(e.classes.get(c), idx, tot));
                }
                sb.append('"');
            }
            ArrayList<String[]> attrs = new ArrayList<String[]>(e.attrs);
            if (e.attrs.isEmpty() && e.tag != null && DEFAULT_ATTRS.containsKey(tag)) {
                for (String a : DEFAULT_ATTRS.get(tag).split("\\|")) {
                    if (a.isEmpty()) continue;
                    int eq = a.indexOf('=');
                    attrs.add(eq > 0 ? new String[]{a.substring(0, eq), a.substring(eq + 1)} : new String[]{a, null});
                }
            }
            for (String[] a : attrs) {
                sb.append(' ').append(a[0]).append("=\"");
                if (a[1] == null || a[1].isEmpty()) sb.append("${").append(stop[0]++).append('}');
                else sb.append(escape(number(a[1], idx, tot)));
                sb.append('"');
            }
            if (voidEl) {
                sb.append(">\n");
                continue;
            }
            sb.append('>');
            boolean blockChildren = false;
            for (El c : e.children) if (c.group || !INLINE.contains(c.tag != null ? c.tag : implicitTag(tag)) || c.count > 1) blockChildren = true;
            if (e.text != null) sb.append(escape(number(e.text, idx, tot)));
            if (!e.children.isEmpty()) {
                if (blockChildren) {
                    sb.append('\n');
                    for (El c : e.children) render(c, tag, sb, indent + unit, unit, stop, idx, tot);
                    sb.append(indent);
                } else {
                    StringBuilder inner = new StringBuilder();
                    for (El c : e.children) render(c, tag, inner, "", unit, stop, idx, tot);
                    sb.append(inner.toString().replace("\n", ""));
                }
            } else if (e.text == null) {
                sb.append("${").append(stop[0]++).append('}');
            }
            sb.append("</").append(tag).append(">\n");
        }
    }

    private static String implicitTag(String parent) {
        if (parent == null) return "div";
        switch (parent) {
            case "ul":
            case "ol":
                return "li";
            case "table":
            case "tbody":
            case "thead":
            case "tfoot":
                return "tr";
            case "tr":
                return "td";
            case "select":
            case "optgroup":
                return "option";
            case "dl":
                return "dt";
            default:
                return INLINE.contains(parent) || parent.equals("p") ? "span" : "div";
        }
    }

    /** "$" → index, "$$" → zero-padded, "$@-" → reversed. */
    private static String number(String s, int idx, int total) {
        if (s.indexOf('$') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '$') {
                sb.append(s.charAt(i));
                continue;
            }
            int n = 0;
            while (i < s.length() && s.charAt(i) == '$') {
                n++;
                i++;
            }
            int value = idx;
            if (i < s.length() && s.charAt(i) == '@') {
                i++;
                if (i < s.length() && s.charAt(i) == '-') {
                    value = total - idx + 1;
                    i++;
                }
            }
            String v = String.valueOf(value);
            while (v.length() < n) v = "0" + v;
            sb.append(v);
            i--;
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("$", "\\$").replace("}", "\\}");
    }

    private static final String[] LOREM = ("lorem ipsum dolor sit amet consectetur adipisicing elit sed do eiusmod tempor incididunt ut labore et "
            + "dolore magna aliqua enim ad minim veniam quis nostrud exercitation ullamco laboris nisi aliquip ex ea commodo consequat "
            + "duis aute irure in reprehenderit voluptate velit esse cillum fugiat nulla pariatur excepteur sint occaecat cupidatat "
            + "non proident sunt culpa qui officia deserunt mollit anim id est laborum").split(" ");

    private static String lorem(int n) {
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random(n);
        boolean cap = true;
        for (int i = 0; i < n; i++) {
            String w = i < 5 ? LOREM[i] : LOREM[r.nextInt(LOREM.length)];
            if (cap) w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            sb.append(w);
            cap = false;
            if (i == n - 1) sb.append('.');
            else if (r.nextInt(10) == 0) {
                sb.append(". ");
                cap = true;
            } else if (r.nextInt(12) == 0) sb.append(", ");
            else sb.append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ CSS

    private static final HashMap<String, String> CSS = new HashMap<String, String>();

    private static void css(String abbr, String prop) {
        CSS.put(abbr, prop);
    }

    static {
        css("m", "margin");
        css("mt", "margin-top");
        css("mr", "margin-right");
        css("mb", "margin-bottom");
        css("ml", "margin-left");
        css("p", "padding");
        css("pt", "padding-top");
        css("pr", "padding-right");
        css("pb", "padding-bottom");
        css("pl", "padding-left");
        css("w", "width");
        css("h", "height");
        css("maw", "max-width");
        css("mah", "max-height");
        css("miw", "min-width");
        css("mih", "min-height");
        css("t", "top");
        css("r", "right");
        css("b", "bottom");
        css("l", "left");
        css("z", "z-index");
        css("fz", "font-size");
        css("fw", "font-weight");
        css("fs", "font-style");
        css("ff", "font-family");
        css("lh", "line-height");
        css("ls", "letter-spacing");
        css("c", "color");
        css("bg", "background");
        css("bgc", "background-color");
        css("bgi", "background-image");
        css("bd", "border");
        css("bdr", "border-radius");
        css("br", "border-radius");
        css("bdc", "border-color");
        css("o", "opacity");
        css("op", "opacity");
        css("d", "display");
        css("pos", "position");
        css("fl", "float");
        css("cl", "clear");
        css("ta", "text-align");
        css("td", "text-decoration");
        css("tt", "text-transform");
        css("va", "vertical-align");
        css("ov", "overflow");
        css("cur", "cursor");
        css("jc", "justify-content");
        css("ai", "align-items");
        css("ac", "align-content");
        css("as", "align-self");
        css("fd", "flex-direction");
        css("fxw", "flex-wrap");
        css("fx", "flex");
        css("fg", "flex-grow");
        css("gap", "gap");
        css("gtc", "grid-template-columns");
        css("gtr", "grid-template-rows");
        css("trs", "transition");
        css("trf", "transform");
        css("bxsh", "box-shadow");
        css("bxz", "box-sizing");
        css("ws", "white-space");
        css("wb", "word-break");
        css("cnt", "content");
        css("v", "visibility");
        css("ol", "outline");
    }

    private static final String[][] CSS_KEYWORDS = {
            {"df", "display: flex;"}, {"dg", "display: grid;"}, {"db", "display: block;"}, {"dib", "display: inline-block;"},
            {"di", "display: inline;"}, {"dn", "display: none;"}, {"dif", "display: inline-flex;"},
            {"posa", "position: absolute;"}, {"posr", "position: relative;"}, {"posf", "position: fixed;"}, {"poss", "position: sticky;"},
            {"tac", "text-align: center;"}, {"tal", "text-align: left;"}, {"tar", "text-align: right;"}, {"taj", "text-align: justify;"},
            {"fwb", "font-weight: bold;"}, {"fwn", "font-weight: normal;"}, {"fsi", "font-style: italic;"},
            {"tdn", "text-decoration: none;"}, {"tdu", "text-decoration: underline;"}, {"ttu", "text-transform: uppercase;"},
            {"ovh", "overflow: hidden;"}, {"ova", "overflow: auto;"}, {"ovs", "overflow: scroll;"},
            {"jcc", "justify-content: center;"}, {"jcsb", "justify-content: space-between;"}, {"jcsa", "justify-content: space-around;"},
            {"jcfs", "justify-content: flex-start;"}, {"jcfe", "justify-content: flex-end;"},
            {"aic", "align-items: center;"}, {"aifs", "align-items: flex-start;"}, {"aife", "align-items: flex-end;"},
            {"fdc", "flex-direction: column;"}, {"fdr", "flex-direction: row;"}, {"fxww", "flex-wrap: wrap;"},
            {"curp", "cursor: pointer;"}, {"bxzbb", "box-sizing: border-box;"}, {"wsnw", "white-space: nowrap;"},
            {"fll", "float: left;"}, {"flr", "float: right;"}, {"clb", "clear: both;"}, {"vh", "visibility: hidden;"},
    };

    /** Expands a CSS abbreviation ("m10-auto", "w100p", "df", "bgc") or returns null. */
    public static String cssExpand(String abbr) {
        String a = abbr.replace(":", "");
        for (String[] kw : CSS_KEYWORDS) if (kw[0].equals(a)) return kw[1];
        // Longest property prefix, then numeric values.
        String prop = null, rest = null;
        for (int len = Math.min(a.length(), 4); len >= 1; len--) {
            String p = CSS.get(a.substring(0, len));
            if (p != null) {
                prop = p;
                rest = a.substring(len);
                break;
            }
        }
        if (prop == null) return null;
        if (rest.isEmpty()) {
            if (prop.equals("color") || prop.endsWith("-color")) return prop + ": #${1:000};";
            return prop + ": ${1};";
        }
        StringBuilder value = new StringBuilder();
        for (String part : rest.split("-(?=\\d|a)|(?<=\\d)-")) {
            if (part.isEmpty()) continue;
            if (value.length() > 0) value.append(' ');
            if (part.equals("a") || part.equals("auto")) {
                value.append("auto");
                continue;
            }
            String num = part.replaceAll("[a-z%]+$", ""), unit = part.substring(num.length());
            if (num.isEmpty() || !num.matches("-?\\d*\\.?\\d+")) return null;
            if (unit.isEmpty()) unit = prop.equals("z-index") || prop.equals("opacity") || prop.equals("line-height") || prop.equals("flex-grow")
                    || prop.equals("font-weight") || num.equals("0") ? "" : "px";
            else if (unit.equals("p")) unit = "%";
            else if (unit.equals("e")) unit = "em";
            else if (unit.equals("r")) unit = "rem";
            else if (unit.equals("x")) unit = "ex";
            value.append(num).append(unit);
        }
        return prop + ": " + value + ";";
    }
}
