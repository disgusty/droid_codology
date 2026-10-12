package com.disgusty.droid_codology.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * VS Code snippet syntax: $1, ${1}, ${1:default with ${2:nested}}, ${1|a,b,c|}, $0, variables
 * ($TM_FILENAME, ${CURRENT_YEAR}, ${VAR:default}) and escapes. Expands to plain text plus the
 * tab stop ranges (relative offsets); repeated stops are mirrors of the first one's text.
 */
public final class Snippet {
    private Snippet() {
    }

    public interface Variables {
        /** The value of a variable, or null if unknown (then its default or name is used). */
        String get(String name);
    }

    /** Expansion result: text and tab stops (index → list of {start, end}); index 0 is the final caret. */
    public static final class Result {
        public String text;
        public final TreeMap<Integer, List<int[]>> stops = new TreeMap<Integer, List<int[]>>();
        public final HashMap<Integer, String[]> choices = new HashMap<Integer, String[]>();
    }

    // ---- AST
    private abstract static class Node {
    }

    private static final class Text extends Node {
        final String s;

        Text(String s) {
            this.s = s;
        }
    }

    private static final class Stop extends Node {
        final int index;
        final List<Node> children; // null for $1 without default
        String[] choices;

        Stop(int index, List<Node> children) {
            this.index = index;
            this.children = children;
        }
    }

    private static final class Var extends Node {
        final String name;
        final List<Node> def;

        Var(String name, List<Node> def) {
            this.name = name;
            this.def = def;
        }
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        /** Parses until one of {@code stopChars} (unescaped) or the end. */
        List<Node> parse(String stopChars) {
            ArrayList<Node> out = new ArrayList<Node>();
            StringBuilder text = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char d = s.charAt(i + 1);
                    if (d == '$' || d == '}' || d == '\\' || d == ',' || d == '|') {
                        text.append(d);
                        i += 2;
                        continue;
                    }
                }
                if (stopChars.indexOf(c) >= 0) break;
                if (c == '$') {
                    Node n = dollar();
                    if (n != null) {
                        if (text.length() > 0) {
                            out.add(new Text(text.toString()));
                            text.setLength(0);
                        }
                        out.add(n);
                        continue;
                    }
                }
                text.append(c);
                i++;
            }
            if (text.length() > 0) out.add(new Text(text.toString()));
            return out;
        }

        private Node dollar() {
            int start = i;
            i++; // '$'
            if (i >= s.length()) {
                i = start;
                return null;
            }
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                int n = number();
                return new Stop(n, null);
            }
            if (Character.isLetter(c) || c == '_') {
                return new Var(name(), null);
            }
            if (c != '{') {
                i = start;
                return null;
            }
            i++; // '{'
            if (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                int n = number();
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return new Stop(n, null);
                }
                if (i < s.length() && s.charAt(i) == ':') {
                    i++;
                    List<Node> kids = parse("}");
                    i++; // '}'
                    return new Stop(n, kids);
                }
                if (i < s.length() && s.charAt(i) == '|') {
                    i++;
                    ArrayList<String> opts = new ArrayList<String>();
                    StringBuilder cur = new StringBuilder();
                    while (i < s.length()) {
                        char d = s.charAt(i);
                        if (d == '\\' && i + 1 < s.length()) {
                            cur.append(s.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        if (d == ',') {
                            opts.add(cur.toString());
                            cur.setLength(0);
                            i++;
                            continue;
                        }
                        if (d == '|' && i + 1 < s.length() && s.charAt(i + 1) == '}') {
                            opts.add(cur.toString());
                            i += 2;
                            break;
                        }
                        cur.append(d);
                        i++;
                    }
                    ArrayList<Node> kids = new ArrayList<Node>();
                    kids.add(new Text(opts.isEmpty() ? "" : opts.get(0)));
                    Stop st = new Stop(n, kids);
                    st.choices = opts.toArray(new String[opts.size()]);
                    return st;
                }
                if (i < s.length() && s.charAt(i) == '/') {
                    // Transform: not applied; keep the stop.
                    skipTo('}');
                    return new Stop(n, null);
                }
                i = start;
                return null;
            }
            if (i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '_')) {
                String nm = name();
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return new Var(nm, null);
                }
                if (i < s.length() && s.charAt(i) == ':') {
                    i++;
                    List<Node> def = parse("}");
                    i++;
                    return new Var(nm, def);
                }
                if (i < s.length() && s.charAt(i) == '/') {
                    skipTo('}');
                    return new Var(nm, null);
                }
            }
            i = start;
            return null;
        }

        private void skipTo(char c) {
            int depth = 0;
            while (i < s.length()) {
                char d = s.charAt(i++);
                if (d == '\\') {
                    i++;
                    continue;
                }
                if (d == '{') depth++;
                else if (d == c && depth-- == 0) return;
            }
        }

        private int number() {
            int st = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            // $99999999999 from an extension's snippet must not overflow parseInt.
            return i - st > 6 ? 999999 : Integer.parseInt(s.substring(st, i));
        }

        private String name() {
            int st = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
            return s.substring(st, i);
        }
    }

    /**
     * Expands {@code body}: every "\n" is followed by {@code indent}, every "\t" becomes
     * {@code indentUnit}; variables come from {@code vars}.
     */
    public static Result expand(String body, String indent, String indentUnit, Variables vars) {
        List<Node> nodes = new Parser(body).parse("");
        HashMap<Integer, List<Node>> defaults = new HashMap<Integer, List<Node>>();
        collectDefaults(nodes, defaults);
        Result r = new Result();
        StringBuilder sb = new StringBuilder();
        emit(nodes, sb, r, defaults, vars, indent, indentUnit, 0);
        r.text = sb.toString();
        if (!r.stops.containsKey(0)) {
            ArrayList<int[]> end = new ArrayList<int[]>();
            end.add(new int[]{sb.length(), sb.length()});
            r.stops.put(0, end);
        }
        return r;
    }

    private static void collectDefaults(List<Node> nodes, Map<Integer, List<Node>> defaults) {
        for (Node n : nodes) {
            if (n instanceof Stop) {
                Stop st = (Stop) n;
                if (st.children != null && !st.children.isEmpty() && !defaults.containsKey(st.index)) defaults.put(st.index, st.children);
                if (st.children != null) collectDefaults(st.children, defaults);
            } else if (n instanceof Var && ((Var) n).def != null) {
                collectDefaults(((Var) n).def, defaults);
            }
        }
    }

    private static void emit(List<Node> nodes, StringBuilder sb, Result r, Map<Integer, List<Node>> defaults, Variables vars,
                             String indent, String unit, int depth) {
        if (depth > 20) return;
        for (Node n : nodes) {
            if (n instanceof Text) {
                appendText(sb, ((Text) n).s, indent, unit);
            } else if (n instanceof Stop) {
                Stop st = (Stop) n;
                int start = sb.length();
                List<Node> kids = st.children != null ? st.children : defaults.get(st.index);
                if (kids != null) emit(kids, sb, r, defaults, vars, indent, unit, depth + 1);
                List<int[]> ranges = r.stops.get(st.index);
                if (ranges == null) {
                    ranges = new ArrayList<int[]>();
                    r.stops.put(st.index, ranges);
                }
                ranges.add(new int[]{start, sb.length()});
                if (st.choices != null) r.choices.put(st.index, st.choices);
            } else if (n instanceof Var) {
                Var v = (Var) n;
                String val = vars == null ? null : vars.get(v.name);
                if (val != null) appendText(sb, val, indent, "\t".equals(unit) ? "\t" : unit);
                else if (v.def != null) emit(v.def, sb, r, defaults, vars, indent, unit, depth + 1);
                else if (!KNOWN.contains(v.name)) {
                    // Unknown variable: VS Code inserts its name as a placeholder.
                    int start = sb.length();
                    sb.append(v.name);
                    List<int[]> ranges = r.stops.get(9000 + r.stops.size());
                    if (ranges == null) {
                        ranges = new ArrayList<int[]>();
                        r.stops.put(9000 + r.stops.size(), ranges);
                    }
                    ranges.add(new int[]{start, sb.length()});
                }
            }
        }
    }

    private static void appendText(StringBuilder sb, String s, String indent, String unit) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append('\n').append(indent);
            else if (c == '\t') sb.append(unit);
            else if (c != '\r') sb.append(c);
        }
    }

    private static final java.util.HashSet<String> KNOWN = new java.util.HashSet<String>(java.util.Arrays.asList(
            "TM_SELECTED_TEXT", "TM_CURRENT_LINE", "TM_CURRENT_WORD", "TM_LINE_INDEX", "TM_LINE_NUMBER", "TM_FILENAME",
            "TM_FILENAME_BASE", "TM_DIRECTORY", "TM_FILEPATH", "RELATIVE_FILEPATH", "CLIPBOARD", "WORKSPACE_NAME",
            "WORKSPACE_FOLDER", "CURSOR_INDEX", "CURSOR_NUMBER", "CURRENT_YEAR", "CURRENT_YEAR_SHORT", "CURRENT_MONTH",
            "CURRENT_MONTH_NAME", "CURRENT_MONTH_NAME_SHORT", "CURRENT_DATE", "CURRENT_DAY_NAME", "CURRENT_DAY_NAME_SHORT",
            "CURRENT_HOUR", "CURRENT_MINUTE", "CURRENT_SECOND", "CURRENT_SECONDS_UNIX", "CURRENT_TIMEZONE_OFFSET", "RANDOM",
            "RANDOM_HEX", "UUID", "BLOCK_COMMENT_START", "BLOCK_COMMENT_END", "LINE_COMMENT"));
}
