package com.disgusty.droid_codology.lang.textmate;

import com.disgusty.droid_codology.lang.Lexer;
import com.disgusty.droid_codology.lang.Tok;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenizes with a TextMate grammar, line by line, the way vscode-textmate does: the leftmost
 * match among the current rule's patterns and its end pattern wins; begin/end regions push and
 * pop a rule stack. Rule stacks are interned so the editor's incremental highlighter can keep
 * them as plain ints (low 16 bits; bracket depth in the high bits, like the built-in lexers).
 * Scopes are mapped to the editor's token styles by the most specific known scope prefix.
 */
public final class TextMateLexer implements Lexer {
    private final Grammar grammar;

    static final class Scopes {
        final String scope;
        final Scopes parent;
        private int style = -2;

        Scopes(String scope, Scopes parent) {
            this.scope = scope;
            this.parent = parent;
        }

        Scopes push(String s) {
            return s == null || s.isEmpty() ? this : new Scopes(s, this);
        }

        int style() {
            if (style == -2) {
                int st = -1;
                for (Scopes x = this; x != null && st < 0; x = x.parent) st = ScopeMap.style(x.scope);
                style = st < 0 ? Tok.PLAIN : st;
            }
            return style;
        }
    }

    static final class Frame {
        final int id;
        final Frame parent;
        final Grammar.BeginRule rule;
        final Pattern end;
        final Scopes nameScopes, contentScopes;

        Frame(int id, Frame parent, Grammar.BeginRule rule, Pattern end, Scopes nameScopes, Scopes contentScopes) {
            this.id = id;
            this.parent = parent;
            this.rule = rule;
            this.end = end;
            this.nameScopes = nameScopes;
            this.contentScopes = contentScopes;
        }
    }

    private final ArrayList<Frame> frames = new ArrayList<Frame>();
    private final HashMap<String, Frame> interned = new HashMap<String, Frame>();
    private final Frame rootFrame;

    public TextMateLexer(Grammar grammar) {
        this.grammar = grammar;
        Scopes root = new Scopes(grammar.scopeName, null);
        rootFrame = new Frame(0, null, (Grammar.BeginRule) grammar.root, null, root, root);
        frames.add(rootFrame);
    }

    public String scopeName() {
        return grammar.scopeName;
    }

    private Frame intern(Frame parent, Grammar.BeginRule rule, Pattern end, String endSrc, Scopes name, Scopes content) {
        String key = parent.id + ":" + rule.id + ":" + endSrc + ":" + scopesKey(content);
        Frame f = interned.get(key);
        if (f == null) {
            if (frames.size() >= 0xFFFF) return parent; // pathological grammar: stop nesting
            f = new Frame(frames.size(), parent, rule, end, name, content);
            frames.add(f);
            interned.put(key, f);
        }
        return f;
    }

    private static String scopesKey(Scopes s) {
        StringBuilder sb = new StringBuilder();
        for (Scopes x = s; x != null; x = x.parent) sb.append(x.scope).append('|');
        return sb.toString();
    }

    // ------------------------------------------------------------------ matcher cache

    private static final class Slot {
        Matcher m;
        int gen = -1;
        /** Last search this line: from where, and its result (null = no match after lastFrom). */
        int lastFrom = -1;
        MatchResult last;
    }

    private final IdentityHashMap<Pattern, Slot> matchers = new IdentityHashMap<Pattern, Slot>();
    private int lineGen;
    private CharSequence line;
    private int total;

    private Matcher matcher(Pattern p) {
        Slot s = matchers.get(p);
        if (s == null) {
            s = new Slot();
            s.m = p.matcher(line);
            s.m.useTransparentBounds(true);
            s.m.useAnchoringBounds(false);
            matchers.put(p, s);
            s.gen = lineGen;
        } else if (s.gen != lineGen) {
            s.lastFrom = -1;
            s.last = null;
            s.m.reset(line);
            s.m.useTransparentBounds(true);
            s.m.useAnchoringBounds(false);
            s.gen = lineGen;
        }
        return s.m;
    }

    public static final java.util.HashMap<String, long[]> PROFILE = new java.util.HashMap<String, long[]>();
    public static boolean profiling;

    private MatchResult find(Pattern p, int from) {
        Matcher m = matcher(p);
        Slot slot = matchers.get(p);
        // A previous search from at or before 'from' that found nothing, or found a match starting at
        // or after 'from', is also the answer now (leftmost-match semantics).
        if (slot.lastFrom >= 0 && slot.lastFrom <= from && (slot.last == null || slot.last.start() >= from)) return slot.last;
        m.region(from, total);
        long t0 = profiling ? System.nanoTime() : 0;
        MatchResult r = m.find() ? m.toMatchResult() : null;
        slot.lastFrom = from;
        slot.last = r;
        if (profiling) {
            long[] a = PROFILE.get(p.pattern());
            if (a == null) PROFILE.put(p.pattern(), a = new long[2]);
            a[0]++;
            a[1] += System.nanoTime() - t0;
        }
        return r;
    }

    // ------------------------------------------------------------------ tokenizing

    private final Tokens raw = new Tokens();
    private int[] paint = new int[256];

    @Override
    public int lex(char[] s, int len, int state, Tokens out) {
        int fid = state & 0xFFFF, depth = (state >>> 16) & 0x7FFF;
        Frame f = fid < frames.size() ? frames.get(fid) : rootFrame;
        GrammarRegistry reg = grammar.registry;
        Grammar savedBase = reg.base;
        reg.base = grammar;
        try {
            char[] buf = new char[len + 1];
            System.arraycopy(s, 0, buf, 0, len);
            buf[len] = '\n';
            line = CharBuffer.wrap(buf);
            total = len + 1;
            lineGen++;
            raw.clear();
            int pos = 0;

            // begin/while regions continue only while their condition matches at the line start.
            ArrayList<Frame> chain = new ArrayList<Frame>();
            for (Frame x = f; x != null; x = x.parent) chain.add(0, x);
            for (Frame x : chain) {
                if (x.parent == null || !x.rule.isWhile) continue;
                Matcher m = x.end == null ? null : matcher(x.end);
                if (m != null) m.region(pos, total);
                if (m != null && m.lookingAt()) {
                    MatchResult mr = m.toMatchResult();
                    emitCaptures(mr, x.rule.endCaptures, x.nameScopes, len);
                    pos = mr.end();
                } else {
                    f = x.parent;
                    break;
                }
            }

            int stuckAt = -1, stuckCount = 0;
            caches.clear();
            List<Grammar.Rule> rules = f.rule.patterns.rules();
            LineCache lc = cacheFor(rules);
            int[] cFrom = lc.from;
            MatchResult[] cRes = lc.res;
            while (pos < total) {
                if (pos == stuckAt) {
                    if (++stuckCount > 8) {
                        // An endless zero-width loop: give up on this line's structure.
                        emit(pos, f.contentScopes.style(), len);
                        break;
                    }
                } else {
                    stuckAt = pos;
                    stuckCount = 0;
                }
                // Earliest match among the patterns and the end pattern.
                MatchResult best = null;
                int bestIdx = -2, bestStart = Integer.MAX_VALUE;
                MatchResult endMatch = null;
                if (f.parent != null && f.end != null && !f.rule.isWhile) {
                    endMatch = find(f.end, pos);
                    if (endMatch != null && !f.rule.applyEndPatternLast) {
                        best = endMatch;
                        bestIdx = -1;
                        bestStart = endMatch.start();
                    }
                }
                for (int i = 0; i < rules.size(); i++) {
                    MatchResult mr;
                    if (cFrom[i] >= 0 && cFrom[i] <= pos && (cRes[i] == null || cRes[i].start() >= pos)) {
                        mr = cRes[i];
                    } else {
                        Grammar.Rule r = rules.get(i);
                        Pattern p = r instanceof Grammar.MatchRule ? ((Grammar.MatchRule) r).regex : ((Grammar.BeginRule) r).begin;
                        mr = p == null ? null : find(p, pos);
                        cFrom[i] = pos;
                        cRes[i] = mr;
                    }
                    if (mr != null && mr.start() < bestStart) {
                        best = mr;
                        bestIdx = i;
                        bestStart = mr.start();
                        if (bestStart == pos) break;
                    }
                }
                if (endMatch != null && f.rule.applyEndPatternLast && (best == null || endMatch.start() <= bestStart)) {
                    if (best == null || endMatch.start() < bestStart || bestIdx < 0) {
                        best = endMatch;
                        bestIdx = -1;
                        bestStart = endMatch.start();
                    }
                }
                if (best == null) {
                    emit(pos, f.contentScopes.style(), len);
                    break;
                }
                if (bestStart > pos) emit(pos, f.contentScopes.style(), len);
                if (bestIdx == -1) {
                    emitCaptures(best, f.rule.endCaptures, f.nameScopes, len);
                    pos = best.end();
                    f = f.parent;
                } else {
                    Grammar.Rule r = rules.get(bestIdx);
                    if (r instanceof Grammar.MatchRule) {
                        Grammar.MatchRule mrule = (Grammar.MatchRule) r;
                        emitCaptures(best, mrule.captures, f.contentScopes.push(resolveName(mrule.name, best)), len);
                        if (best.end() == best.start()) {
                            // A zero-width match must not stall us.
                            emit(pos, f.contentScopes.style(), len);
                            pos = best.end() + 1;
                        } else pos = best.end();
                        continue;
                    }
                    Grammar.BeginRule b = (Grammar.BeginRule) r;
                    Scopes nameScopes = f.contentScopes.push(resolveName(b.name, best));
                    emitCaptures(best, b.beginCaptures, nameScopes, len);
                    Scopes content = nameScopes.push(resolveName(b.contentName, best));
                    String endSrc = b.endHasBackrefs ? substituteBackrefs(b.endSource, best) : b.endSource;
                    Pattern end = b.endHasBackrefs ? OnigRegex.compile(endSrc) : b.end;
                    Frame nf = intern(f, b, end, b.endHasBackrefs ? endSrc : "", nameScopes, content);
                    if (best.end() == best.start() && nf == f) {
                        pos++;
                        continue;
                    }
                    f = nf;
                    pos = best.end();
                }
                // Keep per-rule-list results for this line: after a pop the parent's earlier finds are still valid.
                rules = f.rule.patterns.rules();
                lc = cacheFor(rules);
                cFrom = lc.from;
                cRes = lc.res;
            }
            depth = colorBrackets(s, len, depth, out);
        } catch (RuntimeException e) {
            out.clear();
            out.add(0, Tok.PLAIN);
            return state;
        } finally {
            reg.base = savedBase;
            line = null;
        }
        if (depth > 0x7FFF) depth = 0x7FFF;
        return (depth << 16) | f.id;
    }

    /** Per-line memo of each pattern's next match, per rule list. */
    private static final class LineCache {
        final int[] from;
        final MatchResult[] res;

        LineCache(int n) {
            from = new int[n];
            res = new MatchResult[n];
            java.util.Arrays.fill(from, -1);
        }
    }

    private final IdentityHashMap<List<Grammar.Rule>, LineCache> caches = new IdentityHashMap<List<Grammar.Rule>, LineCache>();

    private LineCache cacheFor(List<Grammar.Rule> rules) {
        LineCache c = caches.get(rules);
        if (c == null) {
            c = new LineCache(rules.size());
            caches.put(rules, c);
        }
        return c;
    }

    private void emit(int start, int style, int len) {
        if (start < len) raw.add(start, style);
    }

    /** Paints a match: the whole range in the base scope (+ capture 0), then each capture group on top. */
    private void emitCaptures(MatchResult m, Grammar.Capture[] caps, Scopes base, int len) {
        int start = m.start(), end = Math.min(m.end(), len);
        if (end <= start) return;
        int n = end - start;
        if (paint.length < n) paint = new int[Math.max(n, paint.length * 2)];
        Scopes whole = caps != null && caps.length > 0 && caps[0] != null ? base.push(caps[0].name) : base;
        int ws = whole.style();
        for (int i = 0; i < n; i++) paint[i] = ws;
        if (caps != null) {
            int groups = m.groupCount();
            for (int g = 1; g < caps.length && g <= groups; g++) {
                Grammar.Capture c = caps[g];
                if (c == null) continue;
                int gs = m.start(g), ge = m.end(g);
                if (gs < 0 || ge <= gs) continue;
                gs = Math.max(gs, start);
                ge = Math.min(ge, end);
                if (ge <= gs) continue;
                Scopes cs = whole.push(resolveName(c.name, m));
                if (c.patterns != null) {
                    paintNested(c, cs, gs, ge, start);
                } else {
                    int st = cs.style();
                    for (int i = gs; i < ge; i++) paint[i - start] = st;
                }
            }
        }
        int prev = -1;
        for (int i = 0; i < n; i++) {
            if (paint[i] != prev) {
                raw.add(start + i, paint[i]);
                prev = paint[i];
            }
        }
    }

    /** Captures with their own "patterns": tokenize the captured text with them (no stack). */
    private void paintNested(Grammar.Capture c, Scopes base, int gs, int ge, int origin) {
        int bs = base.style();
        for (int i = gs; i < ge; i++) paint[i - origin] = bs;
        List<Grammar.Rule> rules = c.patterns.rules();
        int pos = gs;
        int guard = 0;
        while (pos < ge && guard++ < 1000) {
            MatchResult best = null;
            Grammar.Rule bestRule = null;
            for (Grammar.Rule r : rules) {
                Pattern p = r instanceof Grammar.MatchRule ? ((Grammar.MatchRule) r).regex : ((Grammar.BeginRule) r).begin;
                if (p == null) continue;
                Matcher m = matcher(p);
                m.region(pos, ge);
                if (m.find() && (best == null || m.start() < best.start())) {
                    best = m.toMatchResult();
                    bestRule = r;
                }
            }
            if (best == null || best.end() <= best.start()) break;
            String name = bestRule.name;
            int st = base.push(resolveName(name, best)).style();
            for (int i = best.start(); i < best.end(); i++) paint[i - origin] = st;
            Grammar.Capture[] caps = bestRule instanceof Grammar.MatchRule ? ((Grammar.MatchRule) bestRule).captures : ((Grammar.BeginRule) bestRule).beginCaptures;
            if (caps != null) {
                for (int g = 1; g < caps.length && g <= best.groupCount(); g++) {
                    if (caps[g] == null || best.start(g) < 0) continue;
                    int cst = base.push(resolveName(name, best)).push(caps[g].name).style();
                    for (int i = best.start(g); i < best.end(g); i++) paint[i - origin] = cst;
                }
            }
            pos = best.end();
        }
    }

    private static String resolveName(String name, MatchResult m) {
        if (name == null || name.indexOf('$') < 0) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '$' && i + 1 < name.length() && Character.isDigit(name.charAt(i + 1))) {
                int g = name.charAt(i + 1) - '0';
                if (g <= m.groupCount() && m.group(g) != null) sb.append(m.group(g).toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
                i++;
            } else sb.append(c);
        }
        return sb.toString();
    }

    private static String substituteBackrefs(String src, MatchResult m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < src.length()) {
                char d = src.charAt(i + 1);
                if (d >= '1' && d <= '9') {
                    int g = d - '0';
                    String v = g <= m.groupCount() ? m.group(g) : null;
                    sb.append(OnigRegex.quote(v == null ? "" : v));
                    i++;
                    continue;
                }
                sb.append(c).append(d);
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Re-emits raw tokens into {@code out}, giving brackets outside strings/comments depth colors. */
    private int colorBrackets(char[] s, int len, int depth, Tokens out) {
        int n = raw.count();
        for (int t = 0; t < n; t++) {
            int st = raw.start(t), style = raw.style(t);
            int en = t + 1 < n ? raw.start(t + 1) : len;
            boolean code = style != Tok.STRING && style != Tok.COMMENT && style != Tok.DOC_COMMENT && style != Tok.REGEX
                    && style != Tok.CODE && style != Tok.ESCAPE;
            out.add(st, style);
            if (!code) continue;
            for (int i = st; i < en; i++) {
                char c = s[i];
                if (c == '(' || c == '[' || c == '{') {
                    out.add(i, Tok.BRACKET1 + depth % 3);
                    depth++;
                    out.add(i + 1, style);
                } else if (c == ')' || c == ']' || c == '}') {
                    if (depth > 0) depth--;
                    out.add(i, Tok.BRACKET1 + depth % 3);
                    out.add(i + 1, style);
                }
            }
        }
        if (n == 0) out.add(0, Tok.PLAIN);
        return depth;
    }
}
