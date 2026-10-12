package com.disgusty.droid_codology.lang;

import com.disgusty.droid_codology.text.TextBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Document symbols (Outline, "Go to Symbol in Editor", breadcrumbs) found by per-language patterns.
 * Precise symbols come from a language server when one is connected; this works offline for
 * every built-in language.
 */
public final class Symbols {
    private Symbols() {
    }

    public static final int CLASS = 0, FUNCTION = 1, METHOD = 2, FIELD = 3, VARIABLE = 4, MODULE = 5, HEADING = 6, KEY = 7,
            INTERFACE = 8, ENUM = 9, STRUCT = 10, CONSTANT = 11;

    public static final class Symbol {
        public final String name;
        public final int kind, line, column;
        /** Nesting depth (by indentation for code, by level for headings). */
        public int depth;
        /** Last line of the symbol's body when known (from folding), else = line. */
        public int endLine;

        Symbol(String name, int kind, int line, int column) {
            this.name = name;
            this.kind = kind;
            this.line = line;
            this.column = column;
            this.endLine = line;
        }
    }

    private static final class Rule {
        final Pattern p;
        final int group, kind;

        Rule(String regex, int group, int kind) {
            p = Pattern.compile(regex);
            this.group = group;
            this.kind = kind;
        }
    }

    private static Rule[] rules(String lang) {
        switch (lang) {
            case "java":
            case "csharp":
            case "kotlin":
            case "scala":
            case "groovy":
            case "dart":
            case "swift":
                return new Rule[]{
                        new Rule("\\b(?:class|object|record)\\s+([A-Za-z_]\\w*)", 1, CLASS),
                        new Rule("\\binterface\\s+([A-Za-z_]\\w*)", 1, INTERFACE),
                        new Rule("\\benum(?:\\s+class)?\\s+([A-Za-z_]\\w*)", 1, ENUM),
                        new Rule("\\b(?:struct|protocol|extension|mixin|trait)\\s+([A-Za-z_]\\w*)", 1, STRUCT),
                        new Rule("\\b(?:fun|func|def)\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z_][\\w.]*\\.)?([A-Za-z_]\\w*)\\s*[(<]", 1, FUNCTION),
                        // Java/C#/Dart methods: modifiers + return type + name(…) not followed by ';'
                        new Rule("^\\s*(?:(?:public|private|protected|internal|static|final|abstract|override|virtual|async|synchronized|native|open|sealed|default)\\s+)*"
                                + "(?:[A-Za-z_][\\w.<>\\[\\],?\\s]*?\\s+)([A-Za-z_]\\w*)\\s*\\([^;=]*$", 1, METHOD),
                };
            case "c":
            case "cpp":
            case "objective-c":
            case "glsl":
                return new Rule[]{
                        new Rule("\\b(?:class|struct|union|namespace)\\s+([A-Za-z_]\\w*)\\s*(?::[^{;]*)?\\{?\\s*$", 1, STRUCT),
                        new Rule("\\benum\\s+(?:class\\s+)?([A-Za-z_]\\w*)", 1, ENUM),
                        new Rule("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)", 1, CONSTANT),
                        new Rule("^[A-Za-z_][\\w\\s*&:<>,]*?[\\s*&]([A-Za-z_][\\w:~]*)\\s*\\([^;]*$", 1, FUNCTION),
                };
            case "javascript":
            case "typescript":
                return new Rule[]{
                        new Rule("\\bclass\\s+([A-Za-z_$][\\w$]*)", 1, CLASS),
                        new Rule("\\binterface\\s+([A-Za-z_$][\\w$]*)", 1, INTERFACE),
                        new Rule("\\benum\\s+([A-Za-z_$][\\w$]*)", 1, ENUM),
                        new Rule("\\btype\\s+([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*=", 1, STRUCT),
                        new Rule("\\bfunction\\s*\\*?\\s*([A-Za-z_$][\\w$]*)", 1, FUNCTION),
                        new Rule("\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=\\s*(?:async\\s*)?(?:function\\b|\\([^)]*\\)\\s*(?::[^=]+)?=>|[A-Za-z_$][\\w$]*\\s*=>)", 1, FUNCTION),
                        new Rule("^\\s+(?:(?:public|private|protected|static|async|get|set|readonly|override)\\s+)*([A-Za-z_$#][\\w$]*)\\s*\\([^)]*\\)\\s*(?::[^{]+)?\\{\\s*$", 1, METHOD),
                };
            case "python":
                return new Rule[]{
                        new Rule("^\\s*class\\s+([A-Za-z_]\\w*)", 1, CLASS),
                        new Rule("^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)", 1, FUNCTION),
                };
            case "ruby":
                return new Rule[]{
                        new Rule("^\\s*(?:class|module)\\s+([A-Z][\\w:]*)", 1, CLASS),
                        new Rule("^\\s*def\\s+(?:self\\.)?([A-Za-z_]\\w*[?!=]?)", 1, METHOD),
                };
            case "go":
                return new Rule[]{
                        new Rule("^type\\s+([A-Za-z_]\\w*)\\s+struct", 1, STRUCT),
                        new Rule("^type\\s+([A-Za-z_]\\w*)\\s+interface", 1, INTERFACE),
                        new Rule("^func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)", 1, FUNCTION),
                };
            case "rust":
                return new Rule[]{
                        new Rule("\\b(?:struct|union)\\s+([A-Za-z_]\\w*)", 1, STRUCT),
                        new Rule("\\benum\\s+([A-Za-z_]\\w*)", 1, ENUM),
                        new Rule("\\btrait\\s+([A-Za-z_]\\w*)", 1, INTERFACE),
                        new Rule("\\bimpl(?:<[^>]*>)?\\s+(?:[\\w:<>]+\\s+for\\s+)?([A-Za-z_][\\w:<>]*)", 1, CLASS),
                        new Rule("\\bmod\\s+([A-Za-z_]\\w*)", 1, MODULE),
                        new Rule("\\bfn\\s+([A-Za-z_]\\w*)", 1, FUNCTION),
                };
            case "php":
                return new Rule[]{
                        new Rule("\\b(?:class|trait|interface|enum)\\s+([A-Za-z_]\\w*)", 1, CLASS),
                        new Rule("\\bfunction\\s+&?([A-Za-z_]\\w*)", 1, FUNCTION),
                };
            case "lua":
                return new Rule[]{new Rule("\\bfunction\\s+([A-Za-z_][\\w.:]*)", 1, FUNCTION),
                        new Rule("\\blocal\\s+([A-Za-z_]\\w*)\\s*=\\s*function", 1, FUNCTION)};
            case "shellscript":
            case "powershell":
                return new Rule[]{new Rule("^\\s*(?:function\\s+)?([A-Za-z_][\\w-]*)\\s*\\(\\s*\\)\\s*\\{?", 1, FUNCTION),
                        new Rule("^\\s*function\\s+([A-Za-z_][\\w-]*)", 1, FUNCTION)};
            case "sql":
                return new Rule[]{new Rule("(?i)\\bcreate\\s+(?:or\\s+replace\\s+)?(?:temp(?:orary)?\\s+)?(table|view|function|procedure|index|trigger)\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\"`]+)", 2, STRUCT)};
            case "css":
            case "scss":
            case "less":
                return new Rule[]{new Rule("^\\s*([^{}/@][^{};]*?)\\s*\\{\\s*$", 1, CLASS), new Rule("^\\s*(@media[^{]*)\\{", 1, MODULE)};
            case "yaml":
            case "toml":
            case "ini":
                return new Rule[]{new Rule("^\\s*\\[([^\\]]+)\\]", 1, MODULE), new Rule("^( {0,4})([A-Za-z_][\\w.-]*)\\s*[:=]", 2, KEY)};
            case "makefile":
                return new Rule[]{new Rule("^([A-Za-z_./%-][\\w./%-]*)\\s*:(?!=)", 1, FUNCTION)};
            case "dockerfile":
                return new Rule[]{new Rule("(?i)^FROM\\s+\\S+(?:\\s+AS\\s+(\\w+))", 1, MODULE)};
            default:
                return new Rule[0];
        }
    }

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern JSON_KEY = Pattern.compile("^(\\s*)\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:");

    /** Symbols of the document (scans at most {@code maxLines} lines). */
    public static List<Symbol> find(TextBuffer text, String languageId, int tabSize, int maxLines) {
        ArrayList<Symbol> out = new ArrayList<Symbol>();
        int n = Math.min(text.lineCount(), maxLines);
        if (languageId.equals("markdown")) {
            boolean fence = false;
            for (int l = 0; l < n; l++) {
                String s = text.lineText(l);
                if (s.startsWith("```") || s.startsWith("~~~")) fence = !fence;
                if (fence) continue;
                Matcher m = MD_HEADING.matcher(s);
                if (m.find()) {
                    Symbol sym = new Symbol(m.group(2), HEADING, l, 0);
                    sym.depth = m.group(1).length() - 1;
                    out.add(sym);
                }
            }
            return out;
        }
        if (languageId.equals("json")) {
            for (int l = 0; l < n; l++) {
                String s = text.lineText(l);
                Matcher m = JSON_KEY.matcher(s);
                if (m.find() && m.group(1).length() <= tabSize * 3) {
                    Symbol sym = new Symbol(m.group(2), KEY, l, m.group(1).length());
                    sym.depth = m.group(1).length() / Math.max(1, tabSize) - 1;
                    out.add(sym);
                }
            }
            return out;
        }
        Rule[] rules = rules(languageId);
        if (rules.length == 0) return out;
        for (int l = 0; l < n; l++) {
            int ls = text.lineStart(l), le = text.lineEnd(l);
            if (le - ls > 400 || le == ls) continue;
            String s = text.substring(ls, le);
            String trimmed = s.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("#") && !languageId.equals("c") && !languageId.equals("cpp")
                    || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            for (Rule r : rules) {
                Matcher m = r.p.matcher(s);
                if (m.find()) {
                    String name = m.group(r.group);
                    if (name == null || isKeyword(name)) continue;
                    int indent = 0;
                    for (int i = 0; i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t'); i++) indent += s.charAt(i) == '\t' ? tabSize : 1;
                    Symbol sym = new Symbol(name.trim(), r.kind, l, m.start(r.group));
                    sym.depth = indent / Math.max(1, tabSize);
                    out.add(sym);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean isKeyword(String s) {
        switch (s) {
            case "if":
            case "for":
            case "while":
            case "switch":
            case "catch":
            case "return":
            case "new":
            case "else":
            case "do":
            case "try":
            case "sizeof":
            case "synchronized":
            case "using":
            case "foreach":
            case "lock":
            case "when":
            case "super":
            case "this":
                return true;
            default:
                return false;
        }
    }

    /** The innermost symbol whose range contains {@code line} (needs endLine set), or null. */
    public static Symbol at(List<Symbol> symbols, int line) {
        Symbol best = null;
        for (Symbol s : symbols) {
            if (s.line <= line && line <= s.endLine) {
                if (best == null || s.line >= best.line) best = s;
            }
        }
        return best;
    }

    public static String icon(int kind) {
        switch (kind) {
            case CLASS:
            case STRUCT:
            case ENUM:
            case INTERFACE:
                return "symbol_class";
            case FUNCTION:
            case METHOD:
                return "symbol_method";
            case FIELD:
            case KEY:
                return "symbol_field";
            case HEADING:
                return "symbol_text";
            case MODULE:
                return "symbol_keyword";
            default:
                return "symbol_variable";
        }
    }

    /** VS Code's symbol icon colors. */
    public static int color(int kind) {
        switch (kind) {
            case CLASS:
            case STRUCT:
            case ENUM:
                return 0xFFEE9D28;
            case INTERFACE:
                return 0xFF75BEFF;
            case FUNCTION:
            case METHOD:
                return 0xFFB180D7;
            case FIELD:
            case KEY:
            case VARIABLE:
                return 0xFF75BEFF;
            default:
                return 0xFF9D9D9D;
        }
    }
}
