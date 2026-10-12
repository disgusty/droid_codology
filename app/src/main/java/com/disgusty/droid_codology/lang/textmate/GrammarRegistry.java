package com.disgusty.droid_codology.lang.textmate;

import org.json.JSONObject;

import java.util.HashMap;

/**
 * Known TextMate grammars by scope name. Grammars are parsed on first use from a source (a bundled
 * asset or an installed extension), and can include each other ("source.css" inside HTML...).
 */
public final class GrammarRegistry {
    public interface Source {
        /** The grammar's JSON text, or null if it can't be read. */
        String load();
    }

    private final HashMap<String, Source> sources = new HashMap<String, Source>();
    private final HashMap<String, Grammar> loaded = new HashMap<String, Grammar>();
    /** The outermost grammar being tokenized ("$base"). */
    Grammar base;

    public static final GrammarRegistry SHARED = new GrammarRegistry();

    public synchronized void register(String scopeName, Source src) {
        sources.put(scopeName, src);
        loaded.remove(scopeName);
    }

    public synchronized void registerJson(String json) {
        try {
            final JSONObject o = new JSONObject(json);
            final String scope = o.getString("scopeName");
            Grammar g = new Grammar(this, o);
            loaded.put(scope, g);
            sources.put(scope, null);
        } catch (Exception ignored) {
        }
    }

    public synchronized boolean has(String scopeName) {
        return loaded.containsKey(scopeName) || sources.get(scopeName) != null;
    }

    public synchronized Grammar grammar(String scopeName) {
        Grammar g = loaded.get(scopeName);
        if (g != null) return g;
        Source src = sources.get(scopeName);
        if (src == null) return null;
        try {
            String text = src.load();
            if (text == null) return null;
            g = new Grammar(this, new JSONObject(text));
            loaded.put(scopeName, g);
            return g;
        } catch (Exception e) {
            return null;
        }
    }
}
