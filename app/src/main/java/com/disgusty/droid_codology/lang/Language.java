package com.disgusty.droid_codology.lang;

/** A language: how to recognize its files, how to lex it and its editing conventions. */
public final class Language {
    public interface Factory {
        Lexer create();
    }

    public final String id, name;
    public final String[] extensions, fileNames;
    /** Shebang interpreters ("python", "bash"...) that select this language. */
    public String[] interpreters = {};
    public String lineComment, blockOpen, blockClose;
    public String pairOpen = "([{\"'", pairClose = ")]}\"'";
    public String indentAfter = "";
    /** Whether indentation is significant and usually spaces (Python, YAML). */
    public boolean preferSpaces = true;
    public int defaultTabSize = 4;
    /** Off-side rule (Python, YAML): blocks are defined by indentation alone. */
    public boolean offSide;
    private Factory factory;
    private Lexer lexer;

    public Language(String id, String name, String exts, String names, Factory f) {
        this.id = id;
        this.name = name;
        this.extensions = exts.isEmpty() ? new String[0] : exts.split(" ");
        this.fileNames = names.isEmpty() ? new String[0] : names.split(" ");
        this.factory = f;
    }

    /** Words the language's lexer knows (keywords, types, constants), for completion. */
    public java.util.Set<String> keywords() {
        Lexer l = lexer();
        if (l instanceof CodeLexer) return ((CodeLexer) l).words();
        return java.util.Collections.emptySet();
    }

    public synchronized Lexer lexer() {
        if (lexer == null) lexer = factory == null ? Lexer.PLAIN : factory.create();
        return lexer;
    }

    /** Replaces the lexer (e.g. with a TextMate grammar from an extension). */
    public synchronized void setLexer(Lexer l) {
        lexer = l;
        factory = null;
    }

    public Language comments(String line, String open, String close) {
        lineComment = line;
        blockOpen = open;
        blockClose = close;
        return this;
    }

    public Language pairs(String open, String close) {
        pairOpen = open;
        pairClose = close;
        return this;
    }

    public Language indentAfter(String s) {
        indentAfter = s;
        return this;
    }

    public Language tabs(int size, boolean spaces) {
        defaultTabSize = size;
        preferSpaces = spaces;
        return this;
    }

    public Language offSide() {
        offSide = true;
        return this;
    }

    public Language shebang(String... names) {
        interpreters = names;
        return this;
    }

    @Override
    public String toString() {
        return name;
    }
}
