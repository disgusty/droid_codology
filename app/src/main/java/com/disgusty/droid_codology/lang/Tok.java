package com.disgusty.droid_codology.lang;

/** Token styles. Themes map each to a color (and optionally bold/italic). */
public final class Tok {
    private Tok() {
    }

    public static final int PLAIN = 0, KEYWORD = 1, CONTROL = 2, TYPE = 3, FUNCTION = 4, STRING = 5,
            NUMBER = 6, COMMENT = 7, OPERATOR = 8, PUNCT = 9, CONSTANT = 10, ANNOTATION = 11, TAG = 12,
            ATTR = 13, REGEX = 14, ESCAPE = 15, PREPROC = 16, PROPERTY = 17, HEADING = 18, BOLD = 19,
            ITALIC = 20, LINK = 21, CODE = 22, INVALID = 23, DIFF_ADD = 24, DIFF_DEL = 25, VARIABLE = 26,
            BRACKET1 = 27, BRACKET2 = 28, BRACKET3 = 29, DOC_COMMENT = 30, PARAMETER = 31, NAMESPACE = 32;

    public static final int COUNT = 33;

    /** TextMate-style scope names, so VS Code color themes can be applied. */
    public static final String[] SCOPES = {
            "source", "keyword", "keyword.control", "entity.name.type", "entity.name.function", "string",
            "constant.numeric", "comment", "keyword.operator", "punctuation", "constant.language",
            "meta.decorator", "entity.name.tag", "entity.other.attribute-name", "string.regexp",
            "constant.character.escape", "meta.preprocessor", "variable.other.property", "markup.heading",
            "markup.bold", "markup.italic", "markup.underline.link", "markup.inline.raw", "invalid",
            "markup.inserted", "markup.deleted", "variable", "bracket1", "bracket2", "bracket3",
            "comment.block.documentation", "variable.parameter", "entity.name.namespace"
    };
}
