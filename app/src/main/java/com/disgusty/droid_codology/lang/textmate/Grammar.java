package com.disgusty.droid_codology.lang.textmate;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A TextMate grammar (the .tmLanguage.json format VS Code uses): rules, repository and includes.
 * Rules are built lazily the first time they are reached, so large grammars load instantly.
 */
public final class Grammar {
    public final String scopeName;
    final GrammarRegistry registry;
    final JSONObject json;
    final Rule root;
    private int nextRuleId;

    // ------------------------------------------------------------------ rules

    abstract static class Rule {
        final int id;
        final String name, contentName;
        final Grammar grammar;

        Rule(Grammar g, String name, String contentName) {
            this.grammar = g;
            this.id = g.nextRuleId++;
            this.name = name;
            this.contentName = contentName;
        }
    }

    /** A capture group's name and optional nested patterns. */
    static final class Capture {
        final String name;
        final Container patterns;

        Capture(String name, Container patterns) {
            this.name = name;
            this.patterns = patterns;
        }
    }

    static final class MatchRule extends Rule {
        final Pattern regex;
        final Capture[] captures;

        MatchRule(Grammar g, String name, Pattern regex, Capture[] captures) {
            super(g, name, null);
            this.regex = regex;
            this.captures = captures;
        }
    }

    /** begin/end (or begin/while) region. */
    static final class BeginRule extends Rule {
        final Pattern begin;
        final String endSource;
        final Pattern end;
        final boolean endHasBackrefs, isWhile, applyEndPatternLast;
        final Capture[] beginCaptures, endCaptures;
        final Container patterns;

        BeginRule(Grammar g, String name, String contentName, Pattern begin, String endSource, boolean isWhile,
                  boolean last, Capture[] bc, Capture[] ec, Container patterns) {
            super(g, name, contentName);
            this.begin = begin;
            this.endSource = endSource;
            this.endHasBackrefs = endSource != null && hasBackrefs(endSource);
            this.end = endSource == null || endHasBackrefs ? null : OnigRegex.compile(endSource);
            this.isWhile = isWhile;
            this.applyEndPatternLast = last;
            this.beginCaptures = bc;
            this.endCaptures = ec;
            this.patterns = patterns;
        }
    }

    /** A list of patterns (from "patterns" arrays); flattened on demand. */
    static final class Container {
        final Grammar grammar;
        final JSONArray raw;
        /** Repository chain in effect where these patterns were written. */
        final List<JSONObject> repos;
        private List<Rule> flat;

        Container(Grammar g, JSONArray raw, List<JSONObject> repos) {
            this.grammar = g;
            this.raw = raw;
            this.repos = repos;
        }

        /** Leaf rules (match / begin) with every include expanded, in order. */
        List<Rule> rules() {
            if (flat == null) {
                ArrayList<Rule> out = new ArrayList<Rule>();
                grammar.expand(this, out, new HashSet<Object>());
                flat = out;
            }
            return flat;
        }
    }

    static boolean hasBackrefs(String s) {
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                char d = s.charAt(i + 1);
                if (d >= '1' && d <= '9') return true;
                i++;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ construction

    Grammar(GrammarRegistry registry, JSONObject json) throws JSONException {
        this.registry = registry;
        this.json = json;
        this.scopeName = json.getString("scopeName");
        ArrayList<JSONObject> repos = new ArrayList<JSONObject>();
        JSONObject repo = json.optJSONObject("repository");
        if (repo != null) repos.add(repo);
        JSONArray patterns = json.optJSONArray("patterns");
        root = new BeginRule(this, scopeName, null, null, null, false, false, null, null,
                new Container(this, patterns != null ? patterns : new JSONArray(), repos));
    }

    /** Built rules, keyed by their JSON object, so each rule exists once. */
    private final HashMap<JSONObject, Rule> built = new HashMap<JSONObject, Rule>();

    Rule build(JSONObject o, List<JSONObject> repos) {
        Rule r = built.get(o);
        if (r != null) return r;
        List<JSONObject> chain = repos;
        JSONObject localRepo = o.optJSONObject("repository");
        if (localRepo != null) {
            chain = new ArrayList<JSONObject>(repos);
            chain.add(0, localRepo);
        }
        String name = o.optString("name", null), contentName = o.optString("contentName", null);
        if (o.has("match")) {
            r = new MatchRule(this, name, OnigRegex.compile(o.optString("match")), captures(o.optJSONObject("captures"), chain));
        } else if (o.has("begin")) {
            boolean isWhile = o.has("while");
            Capture[] bc = captures(o.has("beginCaptures") ? o.optJSONObject("beginCaptures") : o.optJSONObject("captures"), chain);
            Capture[] ec = captures(o.has(isWhile ? "whileCaptures" : "endCaptures") ? o.optJSONObject(isWhile ? "whileCaptures" : "endCaptures") : o.optJSONObject("captures"), chain);
            JSONArray pats = o.optJSONArray("patterns");
            r = new BeginRule(this, name, contentName, OnigRegex.compile(o.optString("begin")),
                    isWhile ? o.optString("while") : o.optString("end", "\\uFFFF"), isWhile,
                    o.optBoolean("applyEndPatternLast", false) || o.optInt("applyEndPatternLast", 0) == 1, bc, ec,
                    new Container(this, pats != null ? pats : new JSONArray(), chain));
        } else {
            // Only "patterns" (and maybe "include"): a container rule.
            JSONArray pats = o.optJSONArray("patterns");
            if (pats == null) pats = new JSONArray();
            r = new BeginRule(this, name, contentName, null, null, false, false, null, null, new Container(this, pats, chain));
        }
        built.put(o, r);
        return r;
    }

    private Capture[] captures(JSONObject o, List<JSONObject> repos) {
        if (o == null) return null;
        int max = -1;
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            try {
                max = Math.max(max, Integer.parseInt(keys.next()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (max < 0) return null;
        Capture[] caps = new Capture[max + 1];
        for (int i = 0; i <= max; i++) {
            JSONObject c = o.optJSONObject(String.valueOf(i));
            if (c == null) continue;
            JSONArray pats = c.optJSONArray("patterns");
            caps[i] = new Capture(c.optString("name", null), pats != null ? new Container(this, pats, repos) : null);
        }
        return caps;
    }

    /** Expands a container's patterns (resolving includes) into leaf rules. */
    void expand(Container c, List<Rule> out, HashSet<Object> seen) {
        for (int i = 0; i < c.raw.length(); i++) {
            JSONObject p = c.raw.optJSONObject(i);
            if (p == null) continue;
            String inc = p.optString("include", null);
            if (inc != null && !p.has("match") && !p.has("begin")) {
                Object key = c.grammar.scopeName + "|" + inc + "|" + System.identityHashCode(c.repos);
                if (!seen.add(key)) continue;
                resolveInclude(c, inc, out, seen);
                continue;
            }
            Rule r = c.grammar.build(p, c.repos);
            if (r instanceof BeginRule && ((BeginRule) r).begin == null) {
                // Pure container: inline its patterns.
                if (seen.add(r)) expand(((BeginRule) r).patterns, out, seen);
            } else if (r instanceof MatchRule && ((MatchRule) r).regex == null) {
                // Untranslatable regex: skip the rule.
            } else if (r instanceof BeginRule && ((BeginRule) r).begin == null) {
                // unreachable
            } else {
                out.add(r);
            }
        }
    }

    private void resolveInclude(Container c, String inc, List<Rule> out, HashSet<Object> seen) {
        Grammar g = c.grammar;
        if (inc.equals("$self")) {
            expand(((BeginRule) g.root).patterns, out, seen);
        } else if (inc.equals("$base")) {
            Grammar base = registry.base != null ? registry.base : g;
            expand(((BeginRule) base.root).patterns, out, seen);
        } else if (inc.startsWith("#")) {
            JSONObject target = lookup(c.repos, inc.substring(1));
            if (target != null) expandObject(g, target, c.repos, out, seen);
        } else {
            // "source.js" or "source.js#expression"
            int hash = inc.indexOf('#');
            String scope = hash < 0 ? inc : inc.substring(0, hash);
            Grammar other = registry.grammar(scope);
            if (other == null) return;
            if (hash < 0) {
                expand(((BeginRule) other.root).patterns, out, seen);
            } else {
                ArrayList<JSONObject> repos = new ArrayList<JSONObject>();
                JSONObject repo = other.json.optJSONObject("repository");
                if (repo != null) repos.add(repo);
                JSONObject target = lookup(repos, inc.substring(hash + 1));
                if (target != null) expandObject(other, target, repos, out, seen);
            }
        }
    }

    private void expandObject(Grammar g, JSONObject target, List<JSONObject> repos, List<Rule> out, HashSet<Object> seen) {
        if (target.has("match") || target.has("begin")) {
            Rule r = g.build(target, repos);
            if (!(r instanceof MatchRule && ((MatchRule) r).regex == null)) out.add(r);
            return;
        }
        Rule r = g.build(target, repos);
        if (seen.add(r)) {
            // An included repository entry that is only "patterns" (or a lone include).
            String inc = target.optString("include", null);
            if (inc != null && !target.has("patterns")) {
                resolveInclude(new Container(g, new JSONArray(), ((BeginRule) r).patterns.repos), inc, out, seen);
            } else {
                expand(((BeginRule) r).patterns, out, seen);
            }
        }
    }

    private static JSONObject lookup(List<JSONObject> repos, String key) {
        for (JSONObject repo : repos) {
            JSONObject o = repo.optJSONObject(key);
            if (o != null) return o;
        }
        return null;
    }
}
