package com.disgusty.droid_codology.lang.textmate;

import com.disgusty.droid_codology.lang.Tok;

import java.util.HashMap;

/**
 * Maps TextMate scope names to the editor's token styles, using the same scope families VS Code's
 * Dark+/Light+ themes color. Longer (more specific) prefixes are listed first.
 */
final class ScopeMap {
    private ScopeMap() {
    }

    private static final Object[] RULES = {
            "comment.block.documentation", Tok.DOC_COMMENT,
            "comment", Tok.COMMENT,
            "punctuation.definition.comment", Tok.COMMENT,
            "string.regexp", Tok.REGEX,
            "string", Tok.STRING,
            "punctuation.definition.string", Tok.STRING,
            "constant.character.escape", Tok.ESCAPE,
            "constant.numeric", Tok.NUMBER,
            "constant.language", Tok.CONSTANT,
            "constant.other.color", Tok.NUMBER,
            "constant", Tok.CONSTANT,
            "support.constant", Tok.CONSTANT,
            "meta.decorator", Tok.ANNOTATION,
            "meta.annotation", Tok.ANNOTATION,
            "storage.type.annotation", Tok.ANNOTATION,
            "punctuation.decorator", Tok.ANNOTATION,
            "keyword.control.directive", Tok.PREPROC,
            "meta.preprocessor", Tok.PREPROC,
            "keyword.control", Tok.CONTROL,
            "keyword.operator.new", Tok.KEYWORD,
            "keyword.operator.expression", Tok.KEYWORD,
            "keyword.operator.wordlike", Tok.KEYWORD,
            "keyword.operator", Tok.OPERATOR,
            "keyword", Tok.KEYWORD,
            "storage", Tok.KEYWORD,
            "variable.language", Tok.KEYWORD,
            "entity.name.function", Tok.FUNCTION,
            "support.function", Tok.FUNCTION,
            "entity.name.namespace", Tok.NAMESPACE,
            "entity.name.type", Tok.TYPE,
            "entity.name.class", Tok.TYPE,
            "entity.other.inherited-class", Tok.TYPE,
            "support.type.property-name", Tok.PROPERTY,
            "support.type", Tok.TYPE,
            "support.class", Tok.TYPE,
            "entity.name.section", Tok.HEADING,
            "entity.name.tag", Tok.TAG,
            "entity.other.attribute-name", Tok.ATTR,
            "entity.name", Tok.FUNCTION,
            "variable.parameter", Tok.PARAMETER,
            "variable.other.constant", Tok.CONSTANT,
            "variable.other.enummember", Tok.CONSTANT,
            "variable.other.property", Tok.PROPERTY,
            "variable.other.object.property", Tok.PROPERTY,
            "support.variable.property", Tok.PROPERTY,
            "meta.object-literal.key", Tok.PROPERTY,
            "variable", Tok.VARIABLE,
            "support.variable", Tok.VARIABLE,
            "markup.heading", Tok.HEADING,
            "markup.bold", Tok.BOLD,
            "markup.italic", Tok.ITALIC,
            "markup.inline.raw", Tok.CODE,
            "markup.raw", Tok.CODE,
            "markup.fenced_code", Tok.CODE,
            "markup.underline.link", Tok.LINK,
            "markup.inserted", Tok.DIFF_ADD,
            "markup.deleted", Tok.DIFF_DEL,
            "markup.quote", Tok.COMMENT,
            "markup.list", Tok.KEYWORD,
            "invalid", Tok.INVALID,
            "punctuation", Tok.PUNCT,
    };

    private static final HashMap<String, Integer> CACHE = new HashMap<String, Integer>();

    /** Style for a scope name ("a.b c.d" lists several: the last wins), or -1 if none applies. */
    static synchronized int style(String scope) {
        Integer cached = CACHE.get(scope);
        if (cached != null) return cached;
        int r = -1;
        String[] parts = scope.split(" ");
        for (int k = parts.length - 1; k >= 0 && r < 0; k--) r = one(parts[k]);
        CACHE.put(scope, r);
        return r;
    }

    private static int one(String s) {
        for (int i = 0; i < RULES.length; i += 2) {
            String p = (String) RULES[i];
            if (s.startsWith(p) && (s.length() == p.length() || s.charAt(p.length()) == '.')) return (Integer) RULES[i + 1];
        }
        return -1;
    }
}
