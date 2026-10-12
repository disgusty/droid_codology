/*
 * droid_codology extension host: runs extension code (CommonJS, ES5 or whatever the WebView
 * supports) and implements a subset of the `vscode` API on top of the Java bridge `DC`.
 *
 * Bridge:  DC.call(method, argsJson) -> '{"ok":value}' | '{"err":"message"}'   (synchronous)
 *          DC.callAsync(reqId, method, argsJson); Java answers with __host.reply(reqId, ok, value)
 * Java -> JS: __host.init(extensions), __host.activate(id), __host.run(reqId, command, args),
 *             __host.fire(event, data), __host.format(reqId, docId, options)
 */
(function (global) {
    'use strict';
    var bridge = global.DC;
    var slice = Array.prototype.slice;
    var hasOwn = Object.prototype.hasOwnProperty;

    // ------------------------------------------------------------------ polyfills (old WebViews)

    function define(obj, name, fn) {
        if (!obj[name]) Object.defineProperty(obj, name, {value: fn, configurable: true, writable: true});
    }

    define(Object, 'assign', function (t) {
        for (var i = 1; i < arguments.length; i++) {
            var s = arguments[i];
            if (s != null) for (var k in s) if (hasOwn.call(s, k)) t[k] = s[k];
        }
        return t;
    });
    define(Object, 'values', function (o) { return Object.keys(o).map(function (k) { return o[k]; }); });
    define(Object, 'entries', function (o) { return Object.keys(o).map(function (k) { return [k, o[k]]; }); });
    define(String.prototype, 'startsWith', function (s, p) { p = p || 0; return this.substr(p, s.length) === s; });
    define(String.prototype, 'endsWith', function (s) { return this.length >= s.length && this.substr(this.length - s.length) === s; });
    define(String.prototype, 'includes', function (s, p) { return this.indexOf(s, p) >= 0; });
    define(String.prototype, 'repeat', function (n) { var r = ''; for (var i = 0; i < n; i++) r += this; return r; });
    define(String.prototype, 'padStart', function (n, c) { var s = String(this); c = c === undefined ? ' ' : String(c); while (s.length < n) s = c + s; return s.slice(s.length - Math.max(n, this.length)); });
    define(String.prototype, 'trimStart', function () { return this.replace(/^\s+/, ''); });
    define(String.prototype, 'trimEnd', function () { return this.replace(/\s+$/, ''); });
    define(Array.prototype, 'find', function (f, t) { for (var i = 0; i < this.length; i++) if (f.call(t, this[i], i, this)) return this[i]; return undefined; });
    define(Array.prototype, 'findIndex', function (f, t) { for (var i = 0; i < this.length; i++) if (f.call(t, this[i], i, this)) return i; return -1; });
    define(Array.prototype, 'includes', function (v) { return this.indexOf(v) >= 0; });
    define(Array.prototype, 'fill', function (v, s, e) { s = s || 0; e = e === undefined ? this.length : e; for (var i = s; i < e; i++) this[i] = v; return this; });
    define(Array, 'from', function (a, f) {
        var r = [];
        if (a && typeof a.forEach === 'function' && typeof a.length !== 'number') a.forEach(function (v) { r.push(v); });
        else for (var i = 0; i < (a ? a.length : 0); i++) r.push(a[i]);
        return f ? r.map(f) : r;
    });
    define(Array, 'of', function () { return slice.call(arguments); });
    define(Number, 'isInteger', function (v) { return typeof v === 'number' && isFinite(v) && Math.floor(v) === v; });
    define(Number, 'isNaN', function (v) { return v !== v; });

    if (!global.Map) {
        var MiniMap = function (init) {
            this._k = [];
            this._v = [];
            var self = this;
            if (init) Array.from(init).forEach(function (e) { self.set(e[0], e[1]); });
        };
        MiniMap.prototype = {
            get size() { return this._k.length; },
            get: function (k) { var i = this._k.indexOf(k); return i < 0 ? undefined : this._v[i]; },
            set: function (k, v) { var i = this._k.indexOf(k); if (i < 0) { this._k.push(k); this._v.push(v); } else this._v[i] = v; return this; },
            has: function (k) { return this._k.indexOf(k) >= 0; },
            'delete': function (k) { var i = this._k.indexOf(k); if (i < 0) return false; this._k.splice(i, 1); this._v.splice(i, 1); return true; },
            clear: function () { this._k = []; this._v = []; },
            forEach: function (f, t) { for (var i = 0; i < this._k.length; i++) f.call(t, this._v[i], this._k[i], this); },
            keys: function () { return this._k.slice(); },
            values: function () { return this._v.slice(); },
            entries: function () { var r = []; for (var i = 0; i < this._k.length; i++) r.push([this._k[i], this._v[i]]); return r; }
        };
        global.Map = MiniMap;
    }
    if (!global.Set) {
        var MiniSet = function (init) {
            this._v = [];
            var self = this;
            if (init) Array.from(init).forEach(function (v) { self.add(v); });
        };
        MiniSet.prototype = {
            get size() { return this._v.length; },
            add: function (v) { if (this._v.indexOf(v) < 0) this._v.push(v); return this; },
            has: function (v) { return this._v.indexOf(v) >= 0; },
            'delete': function (v) { var i = this._v.indexOf(v); if (i < 0) return false; this._v.splice(i, 1); return true; },
            clear: function () { this._v = []; },
            forEach: function (f, t) { for (var i = 0; i < this._v.length; i++) f.call(t, this._v[i], this._v[i], this); },
            values: function () { return this._v.slice(); },
            keys: function () { return this._v.slice(); }
        };
        global.Set = MiniSet;
    }

    // Promise/A+ for WebViews older than Chrome 32.
    var P = global.Promise;
    if (typeof P !== 'function') {
        var queue = [], scheduled = false;
        var drain = function () {
            scheduled = false;
            var q = queue;
            queue = [];
            for (var i = 0; i < q.length; i++) q[i]();
        };
        var asap = function (fn) {
            queue.push(fn);
            if (!scheduled) {
                scheduled = true;
                setTimeout(drain, 0);
            }
        };
        P = function (fn) {
            var self = this;
            self._s = 0;
            self._v = undefined;
            self._q = [];
            try {
                fn(function (v) { settle(self, v); }, function (e) { reject(self, e); });
            } catch (e) {
                reject(self, e);
            }
        };
        var settle = function (p, v) {
            if (p._s) return;
            if (v === p) return reject(p, new TypeError('Promise resolved with itself'));
            if (v && (typeof v === 'object' || typeof v === 'function')) {
                var then;
                try {
                    then = v.then;
                } catch (e) {
                    return reject(p, e);
                }
                if (typeof then === 'function') {
                    var called = false;
                    try {
                        then.call(v, function (x) {
                            if (!called) { called = true; settle(p, x); }
                        }, function (e) {
                            if (!called) { called = true; reject(p, e); }
                        });
                    } catch (e) {
                        if (!called) { called = true; reject(p, e); }
                    }
                    return;
                }
            }
            p._s = 1;
            p._v = v;
            flush(p);
        };
        var reject = function (p, e) {
            if (p._s) return;
            p._s = 2;
            p._v = e;
            flush(p);
        };
        var flush = function (p) {
            var q = p._q;
            p._q = [];
            q.forEach(function (h) { asap(function () { handle(p, h); }); });
        };
        var handle = function (p, h) {
            var cb = p._s === 1 ? h.f : h.r;
            if (typeof cb !== 'function') {
                if (p._s === 1) settle(h.p, p._v); else reject(h.p, p._v);
                return;
            }
            try {
                settle(h.p, cb(p._v));
            } catch (e) {
                reject(h.p, e);
            }
        };
        P.prototype.then = function (f, r) {
            var np = new P(function () {});
            var h = {f: f, r: r, p: np};
            var self = this;
            if (this._s) asap(function () { handle(self, h); }); else this._q.push(h);
            return np;
        };
        P.prototype['catch'] = function (r) { return this.then(null, r); };
        P.prototype['finally'] = function (f) {
            return this.then(function (v) { return P.resolve(f()).then(function () { return v; }); },
                function (e) { return P.resolve(f()).then(function () { throw e; }); });
        };
        P.resolve = function (v) { return v instanceof P ? v : new P(function (res) { res(v); }); };
        P.reject = function (e) { return new P(function (res, rej) { rej(e); }); };
        P.all = function (list) {
            return new P(function (res, rej) {
                var arr = Array.from(list), out = new Array(arr.length), left = arr.length;
                if (!left) return res(out);
                arr.forEach(function (x, i) {
                    P.resolve(x).then(function (v) {
                        out[i] = v;
                        if (--left === 0) res(out);
                    }, rej);
                });
            });
        };
        P.race = function (list) {
            return new P(function (res, rej) { Array.from(list).forEach(function (x) { P.resolve(x).then(res, rej); }); });
        };
        global.Promise = P;
    }
    define(P.prototype, 'finally', function (f) {
        var C = this.constructor || P;
        return this.then(function (v) { return C.resolve(f()).then(function () { return v; }); },
            function (e) { return C.resolve(f()).then(function () { throw e; }); });
    });
    if (!global.setImmediate) global.setImmediate = function (f) { var a = slice.call(arguments, 1); return setTimeout(function () { f.apply(null, a); }, 0); };
    if (!global.clearImmediate) global.clearImmediate = function (t) { clearTimeout(t); };
    if (!global.queueMicrotask) global.queueMicrotask = function (f) { P.resolve().then(f); };

    if (!global.TextEncoder) {
        global.TextEncoder = function () { this.encoding = 'utf-8'; };
        global.TextEncoder.prototype.encode = function (s) {
            var bin = unescape(encodeURIComponent(s || '')), out = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
            return out;
        };
    }
    if (!global.TextDecoder) {
        global.TextDecoder = function () { this.encoding = 'utf-8'; };
        global.TextDecoder.prototype.decode = function (b) {
            if (!b) return '';
            var u = b instanceof Uint8Array ? b : new Uint8Array(b.buffer || b), s = '';
            for (var i = 0; i < u.length; i += 8192) s += String.fromCharCode.apply(null, u.subarray(i, Math.min(u.length, i + 8192)));
            try {
                return decodeURIComponent(escape(s));
            } catch (e) {
                return s;
            }
        };
    }

    // ------------------------------------------------------------------ bridge

    function call(method) {
        var r = JSON.parse(bridge.call(method, JSON.stringify(slice.call(arguments, 1))));
        if (r.err !== undefined) throw new Error(r.err);
        return r.ok;
    }

    var pending = {}, nextReq = 1;

    function callAsync(method) {
        var args = slice.call(arguments, 1);
        return new P(function (res, rej) {
            var id = nextReq++;
            pending[id] = {res: res, rej: rej};
            bridge.callAsync(id, method, JSON.stringify(args));
        });
    }

    function log(extId, level, msg) {
        try {
            bridge.call('log', JSON.stringify([extId || '', level, String(msg)]));
        } catch (e) {
            // nothing more we can do
        }
    }

    function errText(e) {
        if (!e) return String(e);
        return e.stack ? (String(e.stack).indexOf(String(e.message)) >= 0 ? e.stack : e.message + '\n' + e.stack) : String(e.message || e);
    }

    global.onerror = function (msg, src, line) {
        log('', 'error', msg + (src ? ' (' + src + ':' + line + ')' : ''));
    };
    ['log', 'info', 'warn', 'error', 'debug'].forEach(function (k) {
        var orig = global.console && global.console[k];
        global.console[k] = function () {
            var parts = slice.call(arguments).map(function (a) {
                if (a instanceof Error) return errText(a);
                if (typeof a === 'object') {
                    try { return JSON.stringify(a); } catch (e) { return String(a); }
                }
                return String(a);
            });
            log(currentExt, k === 'log' ? 'info' : k, parts.join(' '));
            if (orig) try { orig.apply(global.console, arguments); } catch (e) { /* ignore */ }
        };
    });

    /** Within one JS turn the editor can't change underneath us, so document reads are cached per turn. */
    var turn = 0, turnScheduled = false;

    function touch() {
        if (!turnScheduled) {
            turnScheduled = true;
            setTimeout(function () {
                turnScheduled = false;
                turn++;
            }, 0);
        }
    }

    function invalidate() {
        turn++;
    }

    var currentExt = '';

    // ------------------------------------------------------------------ basic types

    function Disposable(fn) {
        this._fn = fn;
    }

    Disposable.prototype.dispose = function () {
        var f = this._fn;
        this._fn = null;
        if (f) f();
    };
    Disposable.from = function () {
        var list = slice.call(arguments);
        return new Disposable(function () {
            list.forEach(function (d) { if (d && d.dispose) d.dispose(); });
        });
    };

    function EventEmitter() {
        var self = this;
        this._l = [];
        this.event = function (listener, thisArg, disposables) {
            var e = {f: listener, t: thisArg};
            self._l.push(e);
            var d = new Disposable(function () {
                var i = self._l.indexOf(e);
                if (i >= 0) self._l.splice(i, 1);
            });
            if (disposables && disposables.push) disposables.push(d);
            return d;
        };
    }

    EventEmitter.prototype.fire = function (v) {
        this._l.slice().forEach(function (e) {
            try {
                e.f.call(e.t, v);
            } catch (err) {
                log('', 'error', errText(err));
            }
        });
    };
    EventEmitter.prototype.dispose = function () {
        this._l = [];
    };

    function Position(line, character) {
        if (line < 0 || character < 0) throw new Error('Illegal position');
        this.line = line;
        this.character = character;
    }

    Position.prototype = {
        constructor: Position,
        compareTo: function (o) { return this.line - o.line || this.character - o.character; },
        isBefore: function (o) { return this.compareTo(o) < 0; },
        isBeforeOrEqual: function (o) { return this.compareTo(o) <= 0; },
        isAfter: function (o) { return this.compareTo(o) > 0; },
        isAfterOrEqual: function (o) { return this.compareTo(o) >= 0; },
        isEqual: function (o) { return this.compareTo(o) === 0; },
        translate: function (dl, dc) {
            if (dl && typeof dl === 'object') { dc = dl.characterDelta; dl = dl.lineDelta; }
            return new Position(this.line + (dl || 0), this.character + (dc || 0));
        },
        'with': function (l, c) {
            if (l && typeof l === 'object') { c = l.character; l = l.line; }
            return new Position(l === undefined ? this.line : l, c === undefined ? this.character : c);
        },
        toJSON: function () { return {line: this.line, character: this.character}; }
    };

    function Range(a, b, c, d) {
        var s, e;
        if (typeof a === 'number') {
            s = new Position(a, b);
            e = new Position(c, d);
        } else {
            s = a;
            e = b;
        }
        if (s.isAfter(e)) {
            var t = s;
            s = e;
            e = t;
        }
        this.start = s;
        this.end = e;
    }

    Range.prototype = {
        constructor: Range,
        get isEmpty() { return this.start.isEqual(this.end); },
        get isSingleLine() { return this.start.line === this.end.line; },
        contains: function (x) {
            if (x instanceof Range) return this.contains(x.start) && this.contains(x.end);
            return x.isAfterOrEqual(this.start) && x.isBeforeOrEqual(this.end);
        },
        isEqual: function (o) { return this.start.isEqual(o.start) && this.end.isEqual(o.end); },
        intersection: function (o) {
            var s = this.start.isAfter(o.start) ? this.start : o.start;
            var e = this.end.isBefore(o.end) ? this.end : o.end;
            return s.isAfter(e) ? undefined : new Range(s, e);
        },
        union: function (o) {
            return new Range(this.start.isBefore(o.start) ? this.start : o.start, this.end.isAfter(o.end) ? this.end : o.end);
        },
        'with': function (s, e) {
            if (s && !(s instanceof Position)) { e = s.end; s = s.start; }
            return new Range(s || this.start, e || this.end);
        },
        toJSON: function () { return [this.start, this.end]; }
    };

    function Selection(a, b, c, d) {
        var anchor, active;
        if (typeof a === 'number') {
            anchor = new Position(a, b);
            active = new Position(c, d);
        } else {
            anchor = a;
            active = b;
        }
        Range.call(this, anchor, active);
        this.anchor = anchor;
        this.active = active;
    }

    Selection.prototype = Object.create(Range.prototype);
    Selection.prototype.constructor = Selection;
    Object.defineProperty(Selection.prototype, 'isReversed', {get: function () { return this.anchor.isAfter(this.active); }});

    function TextEdit(range, newText) {
        this.range = range;
        this.newText = newText;
    }

    TextEdit.replace = function (r, t) { return new TextEdit(r, t); };
    TextEdit.insert = function (p, t) { return new TextEdit(new Range(p, p), t); };
    TextEdit.del = TextEdit['delete'] = function (r) { return new TextEdit(r, ''); };

    function WorkspaceEdit() {
        this._e = [];
    }

    WorkspaceEdit.prototype = {
        replace: function (uri, range, text) { this._e.push([uri, new TextEdit(range, text)]); },
        insert: function (uri, pos, text) { this._e.push([uri, TextEdit.insert(pos, text)]); },
        'delete': function (uri, range) { this._e.push([uri, TextEdit.del(range)]); },
        set: function (uri, edits) {
            var k = String(uri);
            this._e = this._e.filter(function (e) { return String(e[0]) !== k; });
            var self = this;
            (edits || []).forEach(function (ed) { self._e.push([uri, ed]); });
        },
        get: function (uri) {
            var k = String(uri);
            return this._e.filter(function (e) { return String(e[0]) === k; }).map(function (e) { return e[1]; });
        },
        has: function (uri) { return this.get(uri).length > 0; },
        get size() {
            var seen = {};
            this._e.forEach(function (e) { seen[String(e[0])] = 1; });
            return Object.keys(seen).length;
        },
        entries: function () {
            var map = {}, order = [];
            this._e.forEach(function (e) {
                var k = String(e[0]);
                if (!map[k]) { map[k] = [e[0], []]; order.push(k); }
                map[k][1].push(e[1]);
            });
            return order.map(function (k) { return map[k]; });
        },
        createFile: function () {},
        deleteFile: function () {},
        renameFile: function () {}
    };

    function Uri(scheme, authority, path, query, fragment) {
        this.scheme = scheme || 'file';
        this.authority = authority || '';
        this.path = path || '';
        this.query = query || '';
        this.fragment = fragment || '';
    }

    Uri.prototype = {
        constructor: Uri,
        get fsPath() { return this.path; },
        'with': function (c) {
            return new Uri(c.scheme !== undefined ? c.scheme : this.scheme, c.authority !== undefined ? c.authority : this.authority,
                c.path !== undefined ? c.path : this.path, c.query !== undefined ? c.query : this.query,
                c.fragment !== undefined ? c.fragment : this.fragment);
        },
        toString: function () {
            var s = this.scheme + ':';
            if (this.authority || this.scheme === 'file' || this.scheme === 'http' || this.scheme === 'https') s += '//' + this.authority;
            s += encodeURI(this.path);
            if (this.query) s += '?' + this.query;
            if (this.fragment) s += '#' + this.fragment;
            return s;
        },
        toJSON: function () { return {scheme: this.scheme, authority: this.authority, path: this.path, query: this.query, fragment: this.fragment, fsPath: this.fsPath}; }
    };
    Uri.file = function (p) { return new Uri('file', '', String(p).replace(/\\/g, '/')); };
    Uri.parse = function (s) {
        var m = /^([a-zA-Z][\w+.\-]*):(\/\/([^\/?#]*))?([^?#]*)(\?([^#]*))?(#(.*))?$/.exec(String(s));
        if (!m) return Uri.file(s);
        var path = m[4];
        try { path = decodeURI(path); } catch (e) { /* keep raw */ }
        return new Uri(m[1].toLowerCase(), m[3], path, m[6], m[8]);
    };
    Uri.from = function (c) { return new Uri(c.scheme, c.authority, c.path, c.query, c.fragment); };
    Uri.joinPath = function (base) {
        var p = base.path;
        slice.call(arguments, 1).forEach(function (seg) { p = pathMod.join(p, seg); });
        return base['with']({path: p});
    };
    Uri.isUri = function (x) { return x instanceof Uri; };

    function toUri(x) {
        if (x instanceof Uri) return x;
        if (typeof x === 'string') return x.indexOf(':') > 1 ? Uri.parse(x) : Uri.file(x);
        if (x && x.scheme) return Uri.from(x);
        return x;
    }

    function SnippetString(value) {
        this.value = value || '';
        this._n = 1;
    }

    function escSnippet(s) { return String(s).replace(/[$}\\]/g, '\\$&'); }

    SnippetString.prototype = {
        appendText: function (s) { this.value += escSnippet(s); return this; },
        appendTabstop: function (n) { this.value += '$' + (n === undefined ? this._n++ : n); return this; },
        appendPlaceholder: function (v, n) {
            if (typeof v === 'function') {
                var nested = new SnippetString();
                nested._n = this._n + 1;
                v(nested);
                v = nested.value;
            } else v = escSnippet(v);
            this.value += '${' + (n === undefined ? this._n++ : n) + ':' + v + '}';
            return this;
        },
        appendChoice: function (vals, n) {
            this.value += '${' + (n === undefined ? this._n++ : n) + '|' + vals.map(function (v) { return String(v).replace(/[|,\\$}]/g, '\\$&'); }).join(',') + '|}';
            return this;
        },
        appendVariable: function (name, def) {
            this.value += '${' + name + (def === undefined ? '' : ':' + (typeof def === 'string' ? escSnippet(def) : '')) + '}';
            return this;
        }
    };

    function MarkdownString(value, supportThemeIcons) {
        this.value = value || '';
        this.isTrusted = false;
        this.supportThemeIcons = !!supportThemeIcons;
    }

    MarkdownString.prototype = {
        appendText: function (s) { this.value += String(s).replace(/[\\`*_{}\[\]()#+\-.!]/g, '\\$&'); return this; },
        appendMarkdown: function (s) { this.value += s; return this; },
        appendCodeblock: function (code, lang) { this.value += '\n```' + (lang || '') + '\n' + code + '\n```\n'; return this; }
    };

    function CancellationTokenSource() {
        var em = new EventEmitter();
        this.token = {isCancellationRequested: false, onCancellationRequested: em.event};
        this._em = em;
    }

    CancellationTokenSource.prototype.cancel = function () {
        if (!this.token.isCancellationRequested) {
            this.token.isCancellationRequested = true;
            this._em.fire();
        }
    };
    CancellationTokenSource.prototype.dispose = function () { this._em.dispose(); };

    function simpleClass(fields) {
        return function () {
            for (var i = 0; i < fields.length; i++) this[fields[i]] = arguments[i];
        };
    }

    function enumOf(names, start) {
        var o = {};
        names.forEach(function (n, i) { o[n] = (start || 0) + i; o[(start || 0) + i] = n; });
        return o;
    }

    function CodeActionKind(v) { this.value = v; }

    CodeActionKind.prototype.append = function (p) { return new CodeActionKind(this.value ? this.value + '.' + p : p); };
    CodeActionKind.prototype.contains = function (o) { return this.value === o.value || o.value.indexOf(this.value + '.') === 0; };
    CodeActionKind.prototype.intersects = function (o) { return this.contains(o) || o.contains(this); };
    ['Empty:', 'QuickFix:quickfix', 'Refactor:refactor', 'RefactorExtract:refactor.extract', 'RefactorInline:refactor.inline',
        'RefactorRewrite:refactor.rewrite', 'Source:source', 'SourceOrganizeImports:source.organizeImports', 'SourceFixAll:source.fixAll'
    ].forEach(function (d) {
        var p = d.split(':');
        CodeActionKind[p[0]] = new CodeActionKind(p[1]);
    });

    function ThemeIcon(id, color) { this.id = id; this.color = color; }

    ThemeIcon.File = new ThemeIcon('file');
    ThemeIcon.Folder = new ThemeIcon('folder');

    function FileSystemError(msg) {
        var e = new Error(typeof msg === 'string' ? msg : String(msg));
        e.name = 'FileSystemError';
        return e;
    }

    ['FileNotFound', 'FileExists', 'FileNotADirectory', 'FileIsADirectory', 'NoPermissions', 'Unavailable'].forEach(function (n) {
        FileSystemError[n] = function (m) {
            var e = FileSystemError(m || n);
            e.code = n;
            return e;
        };
    });

    // ------------------------------------------------------------------ path (subset of node's)

    var pathMod = {
        sep: '/',
        delimiter: ':',
        normalize: function (p) {
            var abs = p.charAt(0) === '/', parts = [], segs = p.split('/');
            for (var i = 0; i < segs.length; i++) {
                var s = segs[i];
                if (!s || s === '.') continue;
                if (s === '..') {
                    if (parts.length && parts[parts.length - 1] !== '..') parts.pop();
                    else if (!abs) parts.push('..');
                } else parts.push(s);
            }
            var r = (abs ? '/' : '') + parts.join('/');
            if (p.length > 1 && p.charAt(p.length - 1) === '/' && r !== '/') r += '/';
            return r || (abs ? '/' : '.');
        },
        join: function () {
            var a = slice.call(arguments).filter(function (s) { return s; });
            return a.length ? pathMod.normalize(a.join('/')) : '.';
        },
        resolve: function () {
            var p = '';
            for (var i = arguments.length - 1; i >= 0 && p.charAt(0) !== '/'; i--) if (arguments[i]) p = arguments[i] + (p ? '/' + p : '');
            if (p.charAt(0) !== '/') p = '/' + p;
            var r = pathMod.normalize(p);
            return r.length > 1 && r.charAt(r.length - 1) === '/' ? r.slice(0, -1) : r;
        },
        dirname: function (p) {
            var i = p.replace(/\/+$/, '').lastIndexOf('/');
            return i < 0 ? '.' : i === 0 ? '/' : p.slice(0, i);
        },
        basename: function (p, ext) {
            var b = p.replace(/\/+$/, '');
            b = b.slice(b.lastIndexOf('/') + 1);
            if (ext && b.endsWith(ext) && b !== ext) b = b.slice(0, b.length - ext.length);
            return b;
        },
        extname: function (p) {
            var b = pathMod.basename(p), i = b.lastIndexOf('.');
            return i <= 0 ? '' : b.slice(i);
        },
        isAbsolute: function (p) { return p.charAt(0) === '/'; },
        relative: function (from, to) {
            var f = pathMod.resolve(from).split('/').filter(Boolean), t = pathMod.resolve(to).split('/').filter(Boolean);
            var i = 0;
            while (i < f.length && i < t.length && f[i] === t[i]) i++;
            var up = [];
            for (var j = i; j < f.length; j++) up.push('..');
            return up.concat(t.slice(i)).join('/');
        },
        parse: function (p) {
            var base = pathMod.basename(p), ext = pathMod.extname(p);
            return {root: p.charAt(0) === '/' ? '/' : '', dir: pathMod.dirname(p), base: base, ext: ext, name: ext ? base.slice(0, -ext.length) : base};
        },
        format: function (o) { return (o.dir ? o.dir + '/' : o.root || '') + (o.base || (o.name || '') + (o.ext || '')); }
    };
    pathMod.posix = pathMod;

    function NodeEmitter() { this._ev = {}; }

    NodeEmitter.prototype = {
        on: function (n, f) { (this._ev[n] = this._ev[n] || []).push(f); return this; },
        once: function (n, f) {
            var self = this;
            function w() { self.off(n, w); f.apply(this, arguments); }
            return this.on(n, w);
        },
        off: function (n, f) { var l = this._ev[n]; if (l) { var i = l.indexOf(f); if (i >= 0) l.splice(i, 1); } return this; },
        emit: function (n) {
            var l = (this._ev[n] || []).slice(), a = slice.call(arguments, 1);
            for (var i = 0; i < l.length; i++) l[i].apply(this, a);
            return l.length > 0;
        },
        removeAllListeners: function (n) { if (n) delete this._ev[n]; else this._ev = {}; return this; },
        listenerCount: function (n) { return (this._ev[n] || []).length; }
    };
    NodeEmitter.prototype.addListener = NodeEmitter.prototype.on;
    NodeEmitter.prototype.removeListener = NodeEmitter.prototype.off;
    NodeEmitter.EventEmitter = NodeEmitter;

    var processShim = {
        env: {}, platform: 'android', arch: 'arm', argv: [], execArgv: [], pid: 1,
        version: 'v16.0.0', versions: {node: '16.0.0'},
        cwd: function () { return wsRoot() || '/'; },
        nextTick: function (f) { var a = slice.call(arguments, 1); P.resolve().then(function () { f.apply(null, a); }); },
        on: function () { return processShim; },
        once: function () { return processShim; },
        emitWarning: function (w) { log(currentExt, 'warn', w); },
        hrtime: function (prev) {
            var t = Date.now(), s = Math.floor(t / 1000), ns = (t % 1000) * 1e6;
            return prev ? [s - prev[0], ns - prev[1]] : [s, ns];
        },
        memoryUsage: function () { return {rss: 0, heapTotal: 0, heapUsed: 0}; }
    };

    var builtins = {
        path: pathMod,
        events: NodeEmitter,
        util: {
            inspect: function (o) { try { return JSON.stringify(o); } catch (e) { return String(o); } },
            format: function (f) {
                var a = slice.call(arguments, 1), i = 0;
                return String(f).replace(/%[sdjoO%]/g, function (m) { return m === '%%' ? '%' : i < a.length ? (m === '%j' ? JSON.stringify(a[i++]) : String(a[i++])) : m; });
            },
            promisify: function (fn) {
                return function () {
                    var a = slice.call(arguments), self = this;
                    return new P(function (res, rej) { fn.apply(self, a.concat([function (e, v) { if (e) rej(e); else res(v); }])); });
                };
            },
            inherits: function (c, p) { c.super_ = p; c.prototype = Object.create(p.prototype, {constructor: {value: c}}); },
            TextEncoder: global.TextEncoder,
            TextDecoder: global.TextDecoder
        },
        os: {
            EOL: '\n',
            platform: function () { return 'android'; },
            type: function () { return 'Linux'; },
            homedir: function () { return call('env.info').home; },
            tmpdir: function () { return call('env.info').tmp; },
            cpus: function () { return []; },
            hostname: function () { return 'android'; }
        },
        process: processShim
    };
    var NODE_ONLY = ['fs', 'child_process', 'net', 'http', 'https', 'crypto', 'stream', 'zlib', 'tls', 'dns', 'cluster', 'worker_threads', 'readline', 'vm', 'module', 'assert', 'buffer', 'url', 'querystring'];

    // ------------------------------------------------------------------ module loader

    var exts = {};

    function requireIn(ext, fromDir) {
        var req = function (request) { return loadModule(ext, fromDir, request); };
        req.resolve = function (request) {
            var r = call('fs.resolve', ext.id, fromDir, request);
            if (!r) throw new Error("Cannot find module '" + request + "'");
            return ext.path + '/' + r.path;
        };
        req.cache = ext.modules;
        return req;
    }

    function loadModule(ext, fromDir, request) {
        if (request === 'vscode') return ext.api || (ext.api = makeApi(ext));
        // On Node.js (host-node.js, Alpine Linux): Node's own module loader and built-ins.
        if (global.__dcNode) return global.__dcNode.require(ext, fromDir, request);
        var name = String(request).replace(/^node:/, '');
        if (hasOwn.call(builtins, name)) return builtins[name];
        var r = call('fs.resolve', ext.id, fromDir, request);
        if (!r) {
            var base = name.split('/')[0];
            throw new Error("Cannot find module '" + request + "'" + (NODE_ONLY.indexOf(base) >= 0 ? ' (a Node.js module, not available in droid_codology)' : ''));
        }
        var cached = ext.modules[r.path];
        if (cached) return cached.exports;
        var module = {exports: {}, id: r.path, filename: ext.path + '/' + r.path, loaded: false, children: []};
        ext.modules[r.path] = module;
        if (/\.json$/i.test(r.path)) {
            module.exports = JSON.parse(r.text);
        } else {
            var dir = r.path.lastIndexOf('/') >= 0 ? r.path.slice(0, r.path.lastIndexOf('/')) : '';
            var fn;
            try {
                fn = new Function('exports', 'require', 'module', '__filename', '__dirname', 'process', 'global', 'globalThis',
                    r.text + '\n//# sourceURL=ext/' + ext.id + '/' + r.path);
            } catch (e) {
                delete ext.modules[r.path];
                if (e instanceof SyntaxError) {
                    var engine = (navigator.userAgent.match(/Chrome\/(\d+)/) || [])[1];
                    throw new Error(r.path + ': ' + e.message + ' — this extension needs a newer JavaScript engine than the WebView of this device' + (engine ? ' (Chrome ' + engine + ')' : '') + ': update Android System WebView');
                }
                throw e;
            }
            fn.call(module.exports, module.exports, requireIn(ext, dir), module, module.filename, ext.path + (dir ? '/' + dir : ''), processShim, global, global);
        }
        module.loaded = true;
        return module.exports;
    }

    // ------------------------------------------------------------------ documents & editors

    var docs = {}, editors = {};

    function TextDocument(id) {
        this._id = id;
        this._turn = -1;
        this._info = null;
        this._text = null;
        this._textVer = -1;
        this._starts = null;
    }

    TextDocument.prototype = {
        _sync: function () {
            if (this._turn !== turn || !this._info) {
                this._info = call('doc.info', this._id);
                this._turn = turn;
                touch();
                if (this._info.version !== this._textVer) {
                    this._text = null;
                    this._starts = null;
                }
            }
            return this._info;
        },
        _all: function () {
            var info = this._sync();
            if (this._text === null) {
                this._text = info.closed ? '' : call('doc.text', this._id);
                this._textVer = info.version;
            }
            return this._text;
        },
        _lineStarts: function () {
            var t = this._all();
            if (!this._starts) {
                var s = [0];
                for (var i = 0; i < t.length; i++) if (t.charCodeAt(i) === 10) s.push(i + 1);
                this._starts = s;
            }
            return this._starts;
        },
        get uri() {
            var i = this._sync();
            return i.path ? Uri.file(i.path) : new Uri('untitled', '', i.name);
        },
        get fileName() { var i = this._sync(); return i.path || i.name; },
        get languageId() { return this._sync().languageId; },
        get version() { return this._sync().version; },
        get isDirty() { return this._sync().dirty; },
        get isUntitled() { return this._sync().untitled; },
        get isClosed() { return this._sync().closed; },
        get eol() { return this._sync().eol; },
        get lineCount() { return this._lineStarts().length; },
        get encoding() { return this._sync().encoding; },
        getText: function (range) {
            var t = this._all();
            if (!range) return t;
            range = this.validateRange(range);
            return t.slice(this.offsetAt(range.start), this.offsetAt(range.end));
        },
        offsetAt: function (pos) {
            var s = this._lineStarts(), t = this._all();
            if (pos.line >= s.length) return t.length;
            if (pos.line < 0) return 0;
            var end = pos.line + 1 < s.length ? s[pos.line + 1] - 1 : t.length;
            return Math.min(s[pos.line] + Math.max(0, pos.character), end);
        },
        positionAt: function (off) {
            var s = this._lineStarts(), t = this._all();
            off = Math.max(0, Math.min(off | 0, t.length));
            var lo = 0, hi = s.length - 1;
            while (lo < hi) {
                var mid = (lo + hi + 1) >> 1;
                if (s[mid] <= off) lo = mid; else hi = mid - 1;
            }
            return new Position(lo, off - s[lo]);
        },
        lineAt: function (x) {
            var line = typeof x === 'number' ? x : x.line;
            var s = this._lineStarts(), t = this._all();
            if (line < 0 || line >= s.length) throw new Error('Illegal value for `line`');
            var start = s[line], end = line + 1 < s.length ? s[line + 1] - 1 : t.length;
            var text = t.slice(start, end), fnw = text.search(/\S/);
            return {
                lineNumber: line,
                text: text,
                range: new Range(line, 0, line, text.length),
                rangeIncludingLineBreak: line + 1 < s.length ? new Range(line, 0, line + 1, 0) : new Range(line, 0, line, text.length),
                firstNonWhitespaceCharacterIndex: fnw < 0 ? text.length : fnw,
                isEmptyOrWhitespace: fnw < 0
            };
        },
        validatePosition: function (p) { return this.positionAt(this.offsetAt(p)); },
        validateRange: function (r) { return new Range(this.validatePosition(r.start), this.validatePosition(r.end)); },
        getWordRangeAtPosition: function (pos, regex) {
            var line = this.lineAt(pos.line).text, re = regex || /[\w$]+/g;
            var g = new RegExp(re.source, (re.flags || ((re.ignoreCase ? 'i' : '') + (re.multiline ? 'm' : ''))).replace('g', '') + 'g'), m;
            while ((m = g.exec(line))) {
                if (!m[0].length) { g.lastIndex++; continue; }
                if (m.index <= pos.character && m.index + m[0].length >= pos.character) return new Range(pos.line, m.index, pos.line, m.index + m[0].length);
                if (m.index > pos.character) break;
            }
            return undefined;
        },
        save: function () { return callAsync('doc.save', this._id); }
    };

    function docFor(id) {
        if (id === null || id === undefined) return undefined;
        return docs[id] || (docs[id] = new TextDocument(id));
    }

    function toOffsets(doc, range) {
        return [doc.offsetAt(range.start), doc.offsetAt(range.end)];
    }

    function EditBuilder(doc) {
        this._doc = doc;
        this._edits = [];
        this._eol = 0;
    }

    EditBuilder.prototype = {
        replace: function (loc, text) {
            var r = loc instanceof Position ? new Range(loc, loc) : loc;
            var o = toOffsets(this._doc, r);
            this._edits.push([o[0], o[1], String(text)]);
        },
        insert: function (pos, text) { this.replace(new Range(pos, pos), text); },
        'delete': function (range) { this.replace(range, ''); },
        setEndOfLine: function (eol) { this._eol = eol; }
    };

    function applyEdits(doc, edits, eol) {
        var ok = call('doc.edit', doc._id, edits, eol || 0);
        invalidate();
        return ok;
    }

    function TextEditor(doc) {
        this.document = doc;
        this.viewColumn = 1;
        this.visibleRanges = [];
    }

    TextEditor.prototype = {
        get selections() {
            var doc = this.document;
            return call('doc.selections', doc._id).map(function (s) {
                return new Selection(doc.positionAt(s[0]), doc.positionAt(s[1]));
            });
        },
        set selections(list) {
            var doc = this.document;
            call('doc.select', doc._id, list.map(function (s) {
                var a = s.anchor || s.start, h = s.active || s.end;
                return [doc.offsetAt(a), doc.offsetAt(h)];
            }));
        },
        get selection() { return this.selections[0]; },
        set selection(s) { this.selections = [s]; },
        get options() {
            var i = this.document._sync();
            return {tabSize: i.tabSize, insertSpaces: i.insertSpaces, cursorStyle: 1, lineNumbers: 1};
        },
        set options(o) { call('doc.options', this.document._id, o); },
        edit: function (cb) {
            var b = new EditBuilder(this.document);
            cb(b);
            return P.resolve(applyEdits(this.document, b._edits, b._eol));
        },
        insertSnippet: function (snippet, where) {
            var doc = this.document, value = typeof snippet === 'string' ? snippet : snippet.value;
            var ranges;
            if (!where) ranges = this.selections;
            else if (Array.isArray(where)) ranges = where;
            else ranges = [where];
            var targets = ranges.map(function (r) {
                return r instanceof Position ? [doc.offsetAt(r), doc.offsetAt(r)] : toOffsets(doc, r);
            });
            var ok = call('doc.snippet', doc._id, targets, value);
            invalidate();
            return P.resolve(ok);
        },
        revealRange: function (range) { call('doc.reveal', this.document._id, this.document.offsetAt(range.start)); },
        setDecorations: function () {},
        show: function () { call('doc.show', this.document._id); },
        hide: function () {}
    };

    function editorFor(id) {
        if (id === null || id === undefined) return undefined;
        return editors[id] || (editors[id] = new TextEditor(docFor(id)));
    }

    // ------------------------------------------------------------------ global state shared by all extensions

    var commands = {};
    var ev = {
        activeEditor: new EventEmitter(), selection: new EventEmitter(), save: new EventEmitter(), willSave: new EventEmitter(),
        change: new EventEmitter(), open: new EventEmitter(), close: new EventEmitter(), config: new EventEmitter(),
        visibleEditors: new EventEmitter(), folders: new EventEmitter()
    };
    var formatters = [], completions = [];
    var statusSeq = 0, diagSeq = 0;

    function wsRoot() {
        return call('workspace.root');
    }

    function selectorMatches(sel, doc) {
        if (!sel) return false;
        if (Array.isArray(sel)) return sel.some(function (s) { return selectorMatches(s, doc); });
        if (typeof sel === 'string') return sel === '*' || sel === doc.languageId;
        if (sel.language && sel.language !== '*' && sel.language !== doc.languageId) return false;
        if (sel.scheme && sel.scheme !== '*' && sel.scheme !== doc.uri.scheme) return false;
        if (sel.pattern) {
            var p = typeof sel.pattern === 'string' ? sel.pattern : sel.pattern.pattern;
            if (!globToRegExp(p).test(doc.fileName)) return false;
        }
        return true;
    }

    function globToRegExp(g) {
        var re = '', i = 0, inGroup = false;
        while (i < g.length) {
            var c = g.charAt(i);
            if (c === '*') {
                if (g.charAt(i + 1) === '*') {
                    re += '.*';
                    i += 2;
                    if (g.charAt(i) === '/') i++;
                    continue;
                }
                re += '[^/]*';
            } else if (c === '?') re += '[^/]';
            else if (c === '{') { re += '(?:'; inGroup = true; }
            else if (c === '}' && inGroup) { re += ')'; inGroup = false; }
            else if (c === ',' && inGroup) re += '|';
            else if ('\\^$.|+()[]'.indexOf(c) >= 0) re += '\\' + c;
            else re += c;
            i++;
        }
        return new RegExp('(^|/)' + re + '$');
    }

    function contributedBy(cmd) {
        for (var id in exts) {
            var cs = exts[id].manifest.contributes && exts[id].manifest.contributes.commands;
            if (cs && [].concat(cs).some(function (c) { return c.command === cmd; })) return exts[id];
        }
        return null;
    }

    function executeCommand(id, args) {
        var c = commands[id];
        if (c) {
            return new P(function (res) {
                var prev = currentExt;
                currentExt = c.ext;
                try {
                    res(c.fn.apply(c.thisArg, args));
                } finally {
                    currentExt = prev;
                }
            });
        }
        var owner = contributedBy(id);
        if (owner) {
            // Contributed by an extension: it has to register the command itself (Java would hand it straight back).
            return activateExt(owner.id).then(function () {
                if (!commands[id]) throw new Error("command '" + id + "' not found");
                return executeCommand(id, args);
            });
        }
        return callAsync('commands.execute', id, args);
    }

    // ------------------------------------------------------------------ the vscode API

    function makeMemento(ext, scope) {
        var data = call('state.load', ext.id, scope) || {};
        return {
            get: function (k, d) { return hasOwn.call(data, k) ? data[k] : d; },
            update: function (k, v) {
                if (v === undefined) delete data[k]; else data[k] = v;
                call('state.save', ext.id, scope, data);
                return P.resolve();
            },
            keys: function () { return Object.keys(data); },
            setKeysForSync: function () {}
        };
    }

    function Configuration(section) {
        this._s = section || '';
    }

    Configuration.prototype = {
        _key: function (k) { return this._s ? (k ? this._s + '.' + k : this._s) : k; },
        get: function (k, d) {
            var v = call('config.get', this._key(k));
            return v === null || v === undefined ? d : v;
        },
        has: function (k) { return call('config.get', this._key(k)) !== null; },
        inspect: function (k) {
            var r = call('config.inspect', this._key(k));
            return r ? {key: this._key(k), defaultValue: r.defaultValue, globalValue: r.globalValue} : undefined;
        },
        update: function (k, v) {
            call('config.update', this._key(k), v === undefined ? null : v);
            return P.resolve();
        }
    };

    function normalizePickItems(items) {
        return items.map(function (it) {
            if (typeof it === 'string') return {label: it};
            return {label: String(it.label || ''), description: it.description || '', detail: it.detail || '', kind: it.kind || 0};
        });
    }

    function showMessage(severity) {
        return function (msg) {
            var rest = slice.call(arguments, 1), options = {};
            if (rest.length && rest[0] && typeof rest[0] === 'object' && rest[0].title === undefined) options = rest.shift();
            var titles = rest.map(function (i) { return typeof i === 'string' ? i : i.title; });
            return callAsync('msg.show', severity, String(msg), titles, !!options.modal, options.detail || '').then(function (i) {
                return i >= 0 ? rest[i] : undefined;
            });
        };
    }

    function makeStatusItem(ext, alignment, priority, id) {
        var item = {_id: 'sb' + (++statusSeq), _visible: false, _ext: ext.id, id: id || ext.id};
        var vals = {text: '', tooltip: '', command: undefined, color: undefined, backgroundColor: undefined, name: '', alignment: alignment || 1, priority: priority || 0};
        var scheduled = false;
        function push() {
            if (scheduled) return;
            scheduled = true;
            setTimeout(function () {
                scheduled = false;
                var cmd = vals.command, args = null;
                if (cmd && typeof cmd === 'object') { args = cmd.arguments || null; cmd = cmd.command; }
                call('status.set', item._id, {
                    text: String(vals.text || ''), tooltip: typeof vals.tooltip === 'string' ? vals.tooltip : vals.tooltip && vals.tooltip.value || '',
                    command: cmd || null, args: args, alignment: vals.alignment, priority: vals.priority, visible: item._visible
                });
            }, 0);
        }
        Object.keys(vals).forEach(function (k) {
            Object.defineProperty(item, k, {
                get: function () { return vals[k]; },
                set: function (v) { vals[k] = v; if (item._visible) push(); },
                enumerable: true
            });
        });
        item.show = function () { item._visible = true; push(); };
        item.hide = function () { item._visible = false; push(); };
        item.dispose = function () { item._visible = false; call('status.remove', item._id); };
        return item;
    }

    function makeOutputChannel(name, log) {
        var ch = {
            name: name,
            append: function (s) { call('output.append', name, String(s)); },
            appendLine: function (s) { call('output.append', name, String(s) + '\n'); },
            replace: function (s) { call('output.clear', name); call('output.append', name, String(s)); },
            clear: function () { call('output.clear', name); },
            show: function () { call('output.show', name); },
            hide: function () {},
            dispose: function () {}
        };
        if (log) {
            ['trace', 'debug', 'info', 'warn', 'error'].forEach(function (lvl) {
                ch[lvl] = function (m) {
                    var extra = slice.call(arguments, 1).map(function (a) { return a instanceof Error ? errText(a) : typeof a === 'object' ? JSON.stringify(a) : String(a); });
                    ch.appendLine(new Date().toISOString() + ' [' + lvl + '] ' + (m instanceof Error ? errText(m) : String(m)) + (extra.length ? ' ' + extra.join(' ') : ''));
                };
            });
            ch.logLevel = 2;
            ch.onDidChangeLogLevel = new EventEmitter().event;
        }
        return ch;
    }

    function makeDiagnostics(name) {
        var map = {};
        var coll;

        /** Sends one document's problems to the editor (squiggles, Problems list). */
        function publish(uri, list) {
            var rows = (list || []).map(function (d) {
                var r = d.range || new Range(0, 0, 0, 0);
                return [r.start.line, r.start.character, r.end.line, r.end.character,
                    d.severity === undefined ? 0 : d.severity, String(d.message || ''), d.source || coll.name];
            });
            try {
                call('diag.set', coll.name, String(uri), rows);
            } catch (e) {
                log('', 'warn', 'diagnostics: ' + errText(e));
            }
        }

        coll = {
            name: name || 'diag' + (++diagSeq),
            set: function (uri, list) {
                if (Array.isArray(uri)) {
                    uri.forEach(function (e) { coll.set(e[0], e[1]); });
                    return;
                }
                map[String(uri)] = [uri, list || []];
                publish(uri, list);
            },
            get: function (uri) { var e = map[String(uri)]; return e ? e[1] : undefined; },
            has: function (uri) { return hasOwn.call(map, String(uri)); },
            'delete': function (uri) {
                delete map[String(uri)];
                publish(uri, []);
            },
            clear: function () {
                Object.keys(map).forEach(function (k) { publish(map[k][0], []); });
                map = {};
            },
            forEach: function (f, t) { Object.keys(map).forEach(function (k) { f.call(t, map[k][0], map[k][1], coll); }); },
            dispose: function () { coll.clear(); }
        };
        return coll;
    }

    function noopRegistration() {
        return new Disposable(function () {});
    }

    // ---- tree views (extension views shown in the side bar)

    var trees = {}, treeHandles = {}, treeSeq = 0;

    function registerTree(ext, viewId, provider) {
        if (!provider) return noopRegistration();
        trees[viewId] = {provider: provider, ext: ext.id};
        var sub = null;
        if (provider.onDidChangeTreeData) {
            sub = provider.onDidChangeTreeData(function () {
                try { call('tree.changed', viewId); } catch (e) { /* ignore */ }
            });
        }
        try { call('tree.changed', viewId); } catch (e) { /* ignore */ }
        return new Disposable(function () {
            if (trees[viewId] && trees[viewId].provider === provider) delete trees[viewId];
            if (sub && sub.dispose) sub.dispose();
        });
    }

    function labelOf(item, element) {
        var l = item.label;
        if (l && typeof l === 'object') l = l.label;
        if (!l && item.resourceUri) l = pathMod.basename(item.resourceUri.path || String(item.resourceUri));
        if (!l && typeof element === 'string') l = element;
        return String(l || '');
    }

    function serializeItem(viewId, element, item) {
        var h = 'h' + (++treeSeq);
        treeHandles[h] = {view: viewId, element: element, item: item};
        var icon = item.iconPath instanceof ThemeIcon ? item.iconPath.id : null;
        var tooltip = typeof item.tooltip === 'string' ? item.tooltip : item.tooltip && item.tooltip.value || '';
        return {
            handle: h, label: labelOf(item, element), description: item.description === true ? '' : String(item.description || ''),
            tooltip: tooltip, collapsible: item.collapsibleState || 0, icon: icon,
            hasCommand: !!item.command, contextValue: item.contextValue || ''
        };
    }

    // ---- webviews (panels and side bar views); droid_codology shows them in its own WebViews

    var RESOURCE_ORIGIN = 'https://dc-resource.local';
    var webviews = {}, webviewSeq = 0, webviewViewProviders = {};

    /** The extension that contributes a view, by view id (or null). */
    function ownerOfView(viewId) {
        var owner = null;
        Object.keys(exts).forEach(function (id) {
            var views = exts[id].manifest.contributes && exts[id].manifest.contributes.views;
            if (!views) return;
            Object.keys(views).forEach(function (c) {
                [].concat(views[c]).forEach(function (v) { if (v && v.id === viewId) owner = id; });
            });
        });
        return owner;
    }

    function webviewOptions(o) {
        o = o || {};
        return {
            enableScripts: !!o.enableScripts,
            localResourceRoots: (o.localResourceRoots || []).map(function (u) { return toUri(u).fsPath; })
        };
    }

    /** Creates the webview object shared by panels and views; Java creates its WebView on 'webview.create'. */
    function makeWebview(ext, kind, info, options) {
        var h = 'w' + (++webviewSeq);
        var received = new EventEmitter(), html = '', opts = options || {};
        var wv = {
            get html() { return html; },
            set html(v) {
                html = String(v === undefined || v === null ? '' : v);
                call('webview.html', h, html);
            },
            get options() { return opts; },
            set options(o) {
                opts = o || {};
                call('webview.options', h, webviewOptions(opts));
            },
            cspSource: RESOURCE_ORIGIN,
            asWebviewUri: function (u) {
                u = toUri(u);
                return new Uri('https', 'dc-resource.local', u.scheme === 'file' ? u.fsPath : u.path);
            },
            onDidReceiveMessage: received.event,
            postMessage: function (m) {
                try {
                    call('webview.post', h, JSON.stringify(m === undefined ? null : m));
                    return P.resolve(true);
                } catch (e) {
                    return P.resolve(false);
                }
            }
        };
        info.ext = ext.id;
        info.extensionPath = ext.path;
        info.options = webviewOptions(opts);
        webviews[h] = {wv: wv, received: received, ext: ext.id, disposed: new EventEmitter(), visibility: new EventEmitter(), isDisposed: false};
        call('webview.create', h, kind, info);
        return {handle: h, webview: wv, rec: webviews[h]};
    }

    function createWebviewPanel(ext, viewType, title, showOptions, options) {
        var made = makeWebview(ext, 'panel', {title: String(title || viewType), viewType: viewType}, options);
        var h = made.handle, rec = made.rec, t = String(title || viewType);
        var panel = {
            viewType: viewType,
            get title() { return t; },
            set title(v) { t = String(v); call('webview.title', h, t); },
            webview: made.webview,
            options: options || {},
            viewColumn: 1, active: true, visible: true, iconPath: undefined,
            onDidDispose: rec.disposed.event,
            onDidChangeViewState: rec.visibility.event,
            reveal: function () { if (!rec.isDisposed) call('webview.reveal', h); },
            dispose: function () {
                if (rec.isDisposed) return;
                call('webview.dispose', h);
                webviewGone(h);
            }
        };
        return panel;
    }

    function webviewGone(h) {
        var rec = webviews[h];
        if (!rec || rec.isDisposed) return;
        rec.isDisposed = true;
        delete webviews[h];
        try { rec.disposed.fire(); } catch (e) { log(rec.ext, 'error', errText(e)); }
    }

    function resolveWebviewView(viewId) {
        var p = webviewViewProviders[viewId];
        if (!p) {
            var owner = ownerOfView(viewId);
            if (!owner) return call('webview.unavailable', viewId, 'No extension provides this view');
            return activateExt(owner).then(function () {
                if (!webviewViewProviders[viewId]) return call('webview.unavailable', viewId, 'The extension did not register this view');
                resolveWebviewView(viewId);
            }, function (e) { call('webview.unavailable', viewId, errText(e)); });
        }
        var made = makeWebview(exts[p.ext] || {id: p.ext, path: ''}, 'view', {viewId: viewId}, {});
        var rec = made.rec, t, d;
        var view = {
            viewType: viewId,
            webview: made.webview,
            get title() { return t; }, set title(v) { t = v; call('webview.title', made.handle, String(v || '')); },
            get description() { return d; }, set description(v) { d = v; },
            badge: undefined,
            visible: true,
            onDidDispose: rec.disposed.event,
            onDidChangeVisibility: rec.visibility.event,
            show: function () {}
        };
        invalidate();
        P.resolve().then(function () {
            return p.provider.resolveWebviewView(view, {state: undefined}, new CancellationTokenSource().token);
        })['catch'](function (e) {
            log(p.ext, 'error', 'webview view ' + viewId + ': ' + errText(e));
            call('webview.unavailable', viewId, String(e && e.message || e));
        });
    }

    function makeApi(ext) {
        var api = {
            version: '1.90.0',
            Position: Position, Range: Range, Selection: Selection, Disposable: Disposable, EventEmitter: EventEmitter,
            Uri: Uri, TextEdit: TextEdit, WorkspaceEdit: WorkspaceEdit, SnippetString: SnippetString, MarkdownString: MarkdownString,
            CancellationTokenSource: CancellationTokenSource, CodeActionKind: CodeActionKind, ThemeIcon: ThemeIcon, FileSystemError: FileSystemError,
            ThemeColor: simpleClass(['id']),
            Location: simpleClass(['uri', 'range']),
            Diagnostic: function (range, message, severity) { this.range = range; this.message = message; this.severity = severity === undefined ? 0 : severity; },
            DiagnosticRelatedInformation: simpleClass(['location', 'message']),
            CompletionItem: function (label, kind) { this.label = label; this.kind = kind; },
            CompletionList: function (items, incomplete) { this.items = items || []; this.isIncomplete = !!incomplete; },
            Hover: function (contents, range) { this.contents = Array.isArray(contents) ? contents : [contents]; this.range = range; },
            CodeAction: simpleClass(['title', 'kind']),
            CodeLens: function (range, command) { this.range = range; this.command = command; this.isResolved = !!command; },
            DocumentSymbol: simpleClass(['name', 'detail', 'kind', 'range', 'selectionRange']),
            SymbolInformation: simpleClass(['name', 'kind', 'containerName', 'location']),
            FoldingRange: simpleClass(['start', 'end', 'kind']),
            SelectionRange: simpleClass(['range', 'parent']),
            Color: simpleClass(['red', 'green', 'blue', 'alpha']),
            ColorInformation: simpleClass(['range', 'color']),
            ColorPresentation: simpleClass(['label']),
            DocumentLink: simpleClass(['range', 'target']),
            SignatureHelp: function () { this.signatures = []; this.activeSignature = 0; this.activeParameter = 0; },
            SignatureInformation: function (label, doc) { this.label = label; this.documentation = doc; this.parameters = []; },
            ParameterInformation: simpleClass(['label', 'documentation']),
            InlayHint: simpleClass(['position', 'label', 'kind']),
            TreeItem: function (label, state) {
                if (label instanceof Uri) this.resourceUri = label; else this.label = label;
                this.collapsibleState = state || 0;
            },
            RelativePattern: function (base, pattern) {
                this.baseUri = base instanceof Uri ? base : base && base.uri ? base.uri : Uri.file(String(base));
                this.base = this.baseUri.fsPath;
                this.pattern = pattern;
            },
            TabInputText: simpleClass(['uri']),
            TabInputTextDiff: simpleClass(['original', 'modified']),
            TabInputWebview: simpleClass(['viewType']),
            TabInputNotebook: simpleClass(['uri', 'notebookType']),
            NotebookCellOutputItem: (function () {
                function Item(data, mime) { this.data = data; this.mime = mime; }
                function bytes(s) { return typeof TextEncoder !== 'undefined' ? new TextEncoder().encode(s) : s; }
                Item.text = function (v, mime) { return new Item(bytes(String(v)), mime || 'text/plain'); };
                Item.json = function (v, mime) { return new Item(bytes(JSON.stringify(v)), mime || 'text/x-json'); };
                Item.stdout = function (v) { return new Item(bytes(String(v)), 'application/vnd.code.notebook.stdout'); };
                Item.stderr = function (v) { return new Item(bytes(String(v)), 'application/vnd.code.notebook.stderr'); };
                Item.error = function (e) {
                    return new Item(bytes(JSON.stringify({name: e && e.name, message: e && e.message, stack: e && e.stack})), 'application/vnd.code.notebook.error');
                };
                return Item;
            })(),
            CommentThreadCollapsibleState: {Collapsed: 0, Expanded: 1},
            CommentThreadState: {Unresolved: 0, Resolved: 1},
            CommentMode: {Editing: 0, Preview: 1},
            FileChangeType: {Changed: 1, Created: 2, Deleted: 3},
            StatusBarAlignment: {Left: 1, Right: 2, 1: 'Left', 2: 'Right'},
            ViewColumn: {Active: -1, Beside: -2, One: 1, Two: 2, Three: 3},
            ConfigurationTarget: {Global: 1, Workspace: 2, WorkspaceFolder: 3},
            EndOfLine: {LF: 1, CRLF: 2},
            DiagnosticSeverity: enumOf(['Error', 'Warning', 'Information', 'Hint']),
            DiagnosticTag: {Unnecessary: 1, Deprecated: 2},
            CompletionItemKind: enumOf(['Text', 'Method', 'Function', 'Constructor', 'Field', 'Variable', 'Class', 'Interface', 'Module', 'Property',
                'Unit', 'Value', 'Enum', 'Keyword', 'Snippet', 'Color', 'File', 'Reference', 'Folder', 'EnumMember', 'Constant', 'Struct',
                'Event', 'Operator', 'TypeParameter', 'User', 'Issue']),
            CompletionItemTag: {Deprecated: 1},
            CompletionTriggerKind: {Invoke: 0, TriggerCharacter: 1, TriggerForIncompleteCompletions: 2},
            SymbolKind: enumOf(['File', 'Module', 'Namespace', 'Package', 'Class', 'Method', 'Property', 'Field', 'Constructor', 'Enum',
                'Interface', 'Function', 'Variable', 'Constant', 'String', 'Number', 'Boolean', 'Array', 'Object', 'Key', 'Null',
                'EnumMember', 'Struct', 'Event', 'Operator', 'TypeParameter']),
            SymbolTag: {Deprecated: 1},
            FoldingRangeKind: {Comment: 1, Imports: 2, Region: 3},
            DocumentHighlightKind: {Text: 0, Read: 1, Write: 2},
            TextEditorRevealType: {Default: 0, InCenter: 1, InCenterIfOutsideViewport: 2, AtTop: 3},
            TextEditorCursorStyle: {Line: 1, Block: 2, Underline: 3, LineThin: 4, BlockOutline: 5, UnderlineThin: 6},
            TextEditorLineNumbersStyle: {Off: 0, On: 1, Relative: 2},
            TextEditorSelectionChangeKind: {Keyboard: 1, Mouse: 2, Command: 3},
            TextDocumentSaveReason: {Manual: 1, AfterDelay: 2, FocusOut: 3},
            TextDocumentChangeReason: {Undo: 1, Redo: 2},
            OverviewRulerLane: {Left: 1, Center: 2, Right: 4, Full: 7},
            DecorationRangeBehavior: {OpenOpen: 0, ClosedClosed: 1, OpenClosed: 2, ClosedOpen: 3},
            ProgressLocation: {SourceControl: 1, Window: 10, Notification: 15},
            FileType: {Unknown: 0, File: 1, Directory: 2, SymbolicLink: 64},
            FilePermission: {Readonly: 1},
            TreeItemCollapsibleState: {None: 0, Collapsed: 1, Expanded: 2},
            ExtensionMode: {Production: 1, Development: 2, Test: 3},
            ExtensionKind: {UI: 1, Workspace: 2},
            UIKind: {Desktop: 1, Web: 2},
            ColorThemeKind: {Light: 1, Dark: 2, HighContrast: 3, HighContrastLight: 4},
            QuickPickItemKind: {Separator: -1, Default: 0},
            IndentAction: {None: 0, Indent: 1, IndentOutdent: 2, Outdent: 3},
            LogLevel: {Off: 0, Trace: 1, Debug: 2, Info: 3, Warning: 4, Error: 5},
            InlayHintKind: {Type: 1, Parameter: 2},
            SignatureHelpTriggerKind: {Invoke: 1, TriggerCharacter: 2, ContentChange: 3},
            CodeActionTriggerKind: {Invoke: 1, Automatic: 2},
            EnvironmentVariableMutatorType: {Replace: 1, Append: 2, Prepend: 3}
        };

        api.commands = {
            registerCommand: function (id, fn, thisArg) {
                commands[id] = {fn: fn, thisArg: thisArg, ext: ext.id};
                call('commands.register', ext.id, id);
                return new Disposable(function () { if (commands[id] && commands[id].fn === fn) delete commands[id]; });
            },
            registerTextEditorCommand: function (id, fn, thisArg) {
                return api.commands.registerCommand(id, function () {
                    var ed = api.window.activeTextEditor;
                    if (!ed) return;
                    var b = new EditBuilder(ed.document);
                    var r = fn.apply(thisArg, [ed, b].concat(slice.call(arguments)));
                    if (b._edits.length || b._eol) applyEdits(ed.document, b._edits, b._eol);
                    return r;
                });
            },
            executeCommand: function (id) { return executeCommand(id, slice.call(arguments, 1)); },
            getCommands: function (filterInternal) {
                var ids = call('commands.list').concat(Object.keys(commands));
                if (filterInternal) ids = ids.filter(function (i) { return i.charAt(0) !== '_'; });
                return P.resolve(ids);
            }
        };

        api.window = {
            get activeTextEditor() { return editorFor(call('doc.active')); },
            get activeTerminal() { return undefined; },
            get terminals() { return []; },
            get visibleTextEditors() { var e = api.window.activeTextEditor; return e ? [e] : []; },
            get activeColorTheme() { return {kind: call('env.info').dark ? 2 : 1}; },
            get state() { return {focused: true, active: true}; },
            get tabGroups() { return {all: [], activeTabGroup: {tabs: []}, onDidChangeTabs: new EventEmitter().event, onDidChangeTabGroups: new EventEmitter().event}; },
            onDidChangeActiveTextEditor: ev.activeEditor.event,
            onDidChangeVisibleTextEditors: ev.visibleEditors.event,
            onDidChangeTextEditorSelection: ev.selection.event,
            onDidChangeTextEditorVisibleRanges: new EventEmitter().event,
            onDidChangeTextEditorOptions: new EventEmitter().event,
            onDidChangeActiveColorTheme: new EventEmitter().event,
            onDidChangeWindowState: new EventEmitter().event,
            onDidOpenTerminal: new EventEmitter().event,
            onDidCloseTerminal: new EventEmitter().event,
            showInformationMessage: showMessage(0),
            showWarningMessage: showMessage(1),
            showErrorMessage: showMessage(2),
            showQuickPick: function (items, options) {
                options = options || {};
                return P.resolve(items).then(function (list) {
                    list = list || [];
                    var shown = list.filter(function (i) { return !(i && i.kind === -1); });
                    return callAsync('quickpick', {title: options.title || '', placeHolder: options.placeHolder || '', items: normalizePickItems(shown)})
                        .then(function (i) {
                            if (i < 0) return undefined;
                            return options.canPickMany ? [shown[i]] : shown[i];
                        });
                });
            },
            showInputBox: function (options) {
                options = options || {};
                return callAsync('input', {
                    title: options.title || options.prompt || '', prompt: options.prompt || '', value: options.value || '',
                    placeHolder: options.placeHolder || '', password: !!options.password
                }).then(function (v) {
                    if (v === null || v === undefined) return undefined;
                    if (options.validateInput) {
                        return P.resolve(options.validateInput(v)).then(function (err) {
                            if (err) {
                                api.window.showErrorMessage(typeof err === 'string' ? err : err.message);
                                return undefined;
                            }
                            return v;
                        });
                    }
                    return v;
                });
            },
            showTextDocument: function (d) {
                var id;
                if (d instanceof TextDocument) id = d._id;
                else if (d && d.document) id = d.document._id;
                else id = call('doc.open', toUri(d).fsPath);
                call('doc.show', id);
                invalidate();
                return P.resolve(editorFor(id));
            },
            createStatusBarItem: function (a, b, c) {
                if (typeof a === 'string') return makeStatusItem(ext, b, c, a);
                return makeStatusItem(ext, a, b);
            },
            setStatusBarMessage: function (text, hide) {
                var it = makeStatusItem(ext, 1, -1000);
                it.text = text;
                it.show();
                var d = new Disposable(function () { it.dispose(); });
                if (typeof hide === 'number') setTimeout(function () { d.dispose(); }, hide);
                else if (hide && hide.then) hide.then(function () { d.dispose(); }, function () { d.dispose(); });
                return d;
            },
            createOutputChannel: function (name, opts) { return makeOutputChannel(name, opts && opts.log); },
            withProgress: function (options, task) {
                var it = null;
                if (options && options.title) {
                    it = makeStatusItem(ext, 1, -999);
                    it.text = '$(sync~spin) ' + options.title;
                    it.show();
                }
                var src = new CancellationTokenSource();
                return P.resolve().then(function () {
                    return task({report: function (p) { if (it && p && p.message) it.text = '$(sync~spin) ' + options.title + ': ' + p.message; }}, src.token);
                })['finally'](function () { if (it) it.dispose(); });
            },
            createTextEditorDecorationType: function () { return {key: 'deco' + (++statusSeq), dispose: function () {}}; },
            registerTreeDataProvider: function (viewId, provider) {
                return registerTree(ext, viewId, provider);
            },
            createTreeView: function (viewId, options) {
                var reg = registerTree(ext, viewId, options && options.treeDataProvider);
                var view = {visible: true, title: undefined, message: undefined, description: undefined, badge: undefined,
                    reveal: function () { return P.resolve(); }, dispose: function () { reg.dispose(); },
                    onDidChangeVisibility: new EventEmitter().event, onDidChangeSelection: new EventEmitter().event,
                    onDidExpandElement: new EventEmitter().event, onDidCollapseElement: new EventEmitter().event,
                    onDidChangeCheckboxState: new EventEmitter().event, selection: []};
                return view;
            },
            registerWebviewViewProvider: function (viewId, provider) {
                webviewViewProviders[viewId] = {provider: provider, ext: ext.id};
                try { call('webview.viewRegistered', viewId); } catch (e) { /* ignore */ }
                return new Disposable(function () {
                    if (webviewViewProviders[viewId] && webviewViewProviders[viewId].provider === provider) delete webviewViewProviders[viewId];
                });
            },
            registerWebviewPanelSerializer: noopRegistration,
            registerCustomEditorProvider: noopRegistration,
            registerUriHandler: noopRegistration,
            registerFileDecorationProvider: noopRegistration,
            registerTerminalLinkProvider: noopRegistration,
            createWebviewPanel: function (viewType, title, showOptions, options) {
                return createWebviewPanel(ext, viewType, title, showOptions, options);
            },
            createTerminal: function () { throw new Error('Terminals are not available to extensions yet'); },
            showOpenDialog: function () { return P.resolve(undefined); },
            showSaveDialog: function () { return P.resolve(undefined); },
            showWorkspaceFolderPick: function () { return P.resolve(undefined); },
            createQuickPick: function () { throw new Error('createQuickPick is not supported yet; use showQuickPick'); },
            createInputBox: function () { throw new Error('createInputBox is not supported yet; use showInputBox'); }
        };

        api.workspace = {
            get rootPath() { return wsRoot() || undefined; },
            get workspaceFolders() {
                var r = wsRoot();
                return r ? [{uri: Uri.file(r), name: pathMod.basename(r), index: 0}] : undefined;
            },
            get name() { var r = wsRoot(); return r ? pathMod.basename(r) : undefined; },
            get workspaceFile() { return undefined; },
            get textDocuments() { return call('doc.list').map(docFor); },
            get isTrusted() { return true; },
            onDidChangeTextDocument: ev.change.event,
            onDidOpenTextDocument: ev.open.event,
            onDidCloseTextDocument: ev.close.event,
            onDidSaveTextDocument: ev.save.event,
            onWillSaveTextDocument: ev.willSave.event,
            onDidChangeConfiguration: ev.config.event,
            onDidChangeWorkspaceFolders: ev.folders.event,
            onDidCreateFiles: new EventEmitter().event,
            onDidDeleteFiles: new EventEmitter().event,
            onDidRenameFiles: new EventEmitter().event,
            onWillCreateFiles: new EventEmitter().event,
            onWillDeleteFiles: new EventEmitter().event,
            onWillRenameFiles: new EventEmitter().event,
            onDidGrantWorkspaceTrust: new EventEmitter().event,
            getConfiguration: function (section) { return new Configuration(section); },
            getWorkspaceFolder: function (uri) {
                var r = wsRoot();
                return r && toUri(uri).fsPath.indexOf(r) === 0 ? {uri: Uri.file(r), name: pathMod.basename(r), index: 0} : undefined;
            },
            asRelativePath: function (p) {
                var s = typeof p === 'string' ? p : toUri(p).fsPath, r = wsRoot();
                return r && s.indexOf(r + '/') === 0 ? s.slice(r.length + 1) : s;
            },
            openTextDocument: function (x) {
                var id;
                if (x === undefined || (x && typeof x === 'object' && !(x instanceof Uri) && !x.scheme)) {
                    id = call('doc.new', (x && x.content) || '', (x && x.language) || '');
                } else {
                    id = call('doc.open', toUri(x).fsPath);
                }
                invalidate();
                return P.resolve(docFor(id));
            },
            applyEdit: function (we) {
                var ok = true;
                we.entries().forEach(function (e) {
                    var id = call('doc.open', toUri(e[0]).fsPath);
                    var doc = docFor(id);
                    ok = applyEdits(doc, e[1].map(function (te) { var o = toOffsets(doc, te.range); return [o[0], o[1], te.newText]; }), 0) && ok;
                });
                return P.resolve(ok);
            },
            saveAll: function () { return callAsync('commands.execute', 'workbench.action.files.saveAll', []).then(function () { return true; }); },
            findFiles: function (include, exclude, max) {
                var inc = typeof include === 'string' ? include : include.pattern;
                var base = typeof include === 'string' ? null : include.base;
                var exc = !exclude ? null : typeof exclude === 'string' ? exclude : exclude.pattern;
                return P.resolve(call('fs.find', base, globToRegExp(inc).source, exc ? globToRegExp(exc).source : null, max || 10000).map(Uri.file));
            },
            createFileSystemWatcher: function () {
                return {onDidCreate: new EventEmitter().event, onDidChange: new EventEmitter().event, onDidDelete: new EventEmitter().event,
                    dispose: function () {}, ignoreCreateEvents: false, ignoreChangeEvents: false, ignoreDeleteEvents: false};
            },
            registerTextDocumentContentProvider: noopRegistration,
            registerFileSystemProvider: noopRegistration,
            registerTaskProvider: noopRegistration,
            fs: {
                readFile: function (uri) {
                    return P.resolve().then(function () {
                        var b64 = call('fs.read', toUri(uri).fsPath);
                        if (b64 === null) throw FileSystemError.FileNotFound(String(uri));
                        var bin = atob(b64), out = new Uint8Array(bin.length);
                        for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
                        return out;
                    });
                },
                writeFile: function (uri, bytes) {
                    return P.resolve().then(function () {
                        var s = '';
                        for (var i = 0; i < bytes.length; i += 8192) s += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(bytes.length, i + 8192)));
                        call('fs.write', toUri(uri).fsPath, btoa(s));
                    });
                },
                stat: function (uri) {
                    return P.resolve().then(function () {
                        var s = call('fs.stat', toUri(uri).fsPath);
                        if (!s) throw FileSystemError.FileNotFound(String(uri));
                        return s;
                    });
                },
                readDirectory: function (uri) { return P.resolve().then(function () { return call('fs.list', toUri(uri).fsPath); }); },
                createDirectory: function (uri) { return P.resolve().then(function () { call('fs.mkdir', toUri(uri).fsPath); }); },
                'delete': function (uri, o) { return P.resolve().then(function () { call('fs.delete', toUri(uri).fsPath, !!(o && o.recursive)); }); },
                rename: function (a, b) { return P.resolve().then(function () { call('fs.rename', toUri(a).fsPath, toUri(b).fsPath); }); },
                copy: function (a, b) { return P.resolve().then(function () { call('fs.copy', toUri(a).fsPath, toUri(b).fsPath); }); },
                isWritableFileSystem: function (s) { return s === 'file'; }
            }
        };

        // Comment threads aren't shown in droid_codology yet: a controller that keeps them quietly.
        api.comments = {
            createCommentController: function (id, label) {
                var threads = [];
                return {
                    id: id, label: label, options: {}, commentingRangeProvider: undefined, reactionHandler: undefined,
                    createCommentThread: function (uri, range, comments) {
                        var t = {uri: uri, range: range, comments: comments || [], collapsibleState: 0, canReply: true,
                            contextValue: undefined, label: undefined, state: 0,
                            dispose: function () { var i = threads.indexOf(t); if (i >= 0) threads.splice(i, 1); }};
                        threads.push(t);
                        return t;
                    },
                    dispose: function () { threads.length = 0; }
                };
            }
        };

        api.languages = {            getLanguages: function () { return P.resolve(call('lang.list')); },
            setTextDocumentLanguage: function (doc, lang) { call('doc.setLanguage', doc._id, lang); invalidate(); return P.resolve(doc); },
            match: function (sel, doc) { return selectorMatches(sel, doc) ? 10 : 0; },
            createDiagnosticCollection: makeDiagnostics,
            getDiagnostics: function () { return []; },
            onDidChangeDiagnostics: new EventEmitter().event,
            registerDocumentFormattingEditProvider: function (sel, provider) {
                var e = {sel: sel, p: provider, ext: ext.id};
                formatters.push(e);
                return new Disposable(function () { var i = formatters.indexOf(e); if (i >= 0) formatters.splice(i, 1); });
            },
            registerCompletionItemProvider: function (sel, provider) {
                var e = {sel: sel, p: provider, ext: ext.id, triggers: slice.call(arguments, 2)};
                completions.push(e);
                return new Disposable(function () { var i = completions.indexOf(e); if (i >= 0) completions.splice(i, 1); });
            },
            setLanguageConfiguration: noopRegistration,
            registerHoverProvider: noopRegistration,
            registerDefinitionProvider: noopRegistration,
            registerReferenceProvider: noopRegistration,
            registerDocumentSymbolProvider: noopRegistration,
            registerWorkspaceSymbolProvider: noopRegistration,
            registerCodeActionsProvider: noopRegistration,
            registerCodeLensProvider: noopRegistration,
            registerDocumentRangeFormattingEditProvider: noopRegistration,
            registerOnTypeFormattingEditProvider: noopRegistration,
            registerRenameProvider: noopRegistration,
            registerSignatureHelpProvider: noopRegistration,
            registerDocumentHighlightProvider: noopRegistration,
            registerDocumentLinkProvider: noopRegistration,
            registerColorProvider: noopRegistration,
            registerFoldingRangeProvider: noopRegistration,
            registerSelectionRangeProvider: noopRegistration,
            registerImplementationProvider: noopRegistration,
            registerTypeDefinitionProvider: noopRegistration,
            registerDeclarationProvider: noopRegistration,
            registerInlayHintsProvider: noopRegistration,
            registerDocumentSemanticTokensProvider: noopRegistration,
            registerInlineCompletionItemProvider: noopRegistration,
            registerEvaluatableExpressionProvider: noopRegistration,
            registerCallHierarchyProvider: noopRegistration,
            registerTypeHierarchyProvider: noopRegistration,
            registerLinkedEditingRangeProvider: noopRegistration
        };

        api.env = {
            appName: 'droid_codology', appRoot: '', appHost: 'android', uriScheme: 'droid-codology', uiKind: 1,
            get language() { return call('env.info').language; },
            get machineId() { return call('env.info').machineId; },
            sessionId: String(Date.now()),
            remoteName: undefined, shell: '/system/bin/sh', isNewAppInstall: false, isTelemetryEnabled: false,
            onDidChangeTelemetryEnabled: new EventEmitter().event,
            createTelemetryLogger: function () { return {logUsage: function () {}, logError: function () {}, dispose: function () {}}; },
            clipboard: {
                readText: function () { return P.resolve(call('clipboard.read')); },
                writeText: function (s) { call('clipboard.write', String(s)); return P.resolve(); }
            },
            openExternal: function (uri) { call('env.open', String(uri)); return P.resolve(true); },
            asExternalUri: function (uri) { return P.resolve(uri); }
        };

        api.extensions = {
            getExtension: function (id) {
                var key = Object.keys(exts).find(function (k) { return k.toLowerCase() === String(id).toLowerCase(); });
                return key ? extensionObject(exts[key]) : undefined;
            },
            get all() { return Object.keys(exts).map(function (k) { return extensionObject(exts[k]); }); },
            onDidChange: new EventEmitter().event
        };

        var noop = {};
        ['debug', 'tasks', 'scm', 'tests', 'notebooks', 'comments', 'authentication', 'chat', 'lm', 'l10n'].forEach(function (n) { api[n] = noop; });
        api.debug = {registerDebugConfigurationProvider: noopRegistration, registerDebugAdapterDescriptorFactory: noopRegistration,
            onDidStartDebugSession: new EventEmitter().event, onDidTerminateDebugSession: new EventEmitter().event, breakpoints: []};
        api.tasks = {registerTaskProvider: noopRegistration, fetchTasks: function () { return P.resolve([]); }, onDidStartTask: new EventEmitter().event, onDidEndTask: new EventEmitter().event};
        api.scm = {createSourceControl: function () { throw new Error('Source control providers are not supported yet'); }};
        api.l10n = {
            t: function (msg) {
                var args = slice.call(arguments, 1);
                if (typeof msg === 'object') msg = msg.message;
                if (args.length === 1 && args[0] && typeof args[0] === 'object') {
                    var o = args[0];
                    return String(msg).replace(/\{(\w+)\}/g, function (m, k) { return hasOwn.call(o, k) ? o[k] : m; });
                }
                return String(msg).replace(/\{(\d+)\}/g, function (m, k) { return args[k] !== undefined ? args[k] : m; });
            },
            bundle: undefined, uri: undefined
        };
        api.authentication = {getSession: function () { return P.resolve(undefined); }, onDidChangeSessions: new EventEmitter().event, registerAuthenticationProvider: noopRegistration};
        return api;
    }

    function extensionObject(e) {
        return {
            id: e.id, extensionPath: e.path, extensionUri: Uri.file(e.path), packageJSON: e.manifest, extensionKind: 1,
            get isActive() { return !!e.active; },
            get exports() { return e.exports; },
            activate: function () { return activateExt(e.id); }
        };
    }

    function makeContext(e) {
        var storage = call('state.dir', e.id);
        var secretsChanged = new EventEmitter();
        var secretStore = makeMemento(e, 'secrets');
        return {
            subscriptions: [],
            extensionPath: e.path,
            extensionUri: Uri.file(e.path),
            extension: extensionObject(e),
            extensionMode: 1,
            globalState: makeMemento(e, 'global'),
            workspaceState: makeMemento(e, 'workspace'),
            secrets: {
                get: function (k) { return P.resolve(secretStore.get(k)); },
                store: function (k, v) { secretStore.update(k, v); secretsChanged.fire({key: k}); return P.resolve(); },
                'delete': function (k) { secretStore.update(k, undefined); secretsChanged.fire({key: k}); return P.resolve(); },
                onDidChange: secretsChanged.event
            },
            globalStoragePath: storage, globalStorageUri: Uri.file(storage),
            storagePath: storage, storageUri: Uri.file(storage),
            logPath: storage, logUri: Uri.file(storage),
            asAbsolutePath: function (rel) { return pathMod.join(e.path, rel); },
            environmentVariableCollection: {persistent: false, replace: function () {}, append: function () {}, prepend: function () {}, get: function () {}, forEach: function () {}, 'delete': function () {}, clear: function () {}},
            languageModelAccessInformation: {canSendRequest: function () { return false; }, onDidChange: new EventEmitter().event}
        };
    }

    function activateExt(id) {
        var e = exts[id];
        if (!e) return P.reject(new Error('Unknown extension ' + id));
        if (e.promise) return e.promise;
        e.promise = P.resolve().then(function () {
            var prev = currentExt;
            currentExt = id;
            try {
                var mod = loadModule(e, '', './' + e.main.replace(/^\.\//, ''));
                e.module = mod;
                e.context = makeContext(e);
                return typeof mod.activate === 'function' ? mod.activate(e.context) : undefined;
            } finally {
                currentExt = prev;
            }
        }).then(function (exportsApi) {
            e.exports = exportsApi;
            e.active = true;
            call('ext.activated', id, true, '');
            return exportsApi;
        }, function (err) {
            e.failed = true;
            call('ext.activated', id, false, errText(err));
            throw err;
        });
        return e.promise;
    }

    // ------------------------------------------------------------------ entry points for Java

    /** The `vscode` object of an extension (host-node.js resolves require('vscode') with it). */
    global.__dcExtApi = function (id) {
        var e = exts[id];
        return e ? e.api || (e.api = makeApi(e)) : undefined;
    };

    global.__host = {
        /** Drains the Java outbox: each message is [functionName, args...]. */
        pull: function () {
            for (;;) {
                var s = bridge.take();
                if (!s) break;
                var m = JSON.parse(s);
                try {
                    global.__host[m[0]].apply(null, m.slice(1));
                } catch (e) {
                    log('', 'error', m[0] + ': ' + errText(e));
                }
            }
        },
        init: function (list) {
            list.forEach(function (x) {
                exts[x.id] = {id: x.id, path: x.path, main: x.main, manifest: x.manifest || {}, modules: {}, promise: null, active: false};
            });
            return true;
        },
        activate: function (id) {
            activateExt(id)['catch'](function () { /* reported */ });
        },
        deactivate: function (id) {
            var e = exts[id];
            if (!e || !e.active) return;
            try {
                if (e.module && typeof e.module.deactivate === 'function') e.module.deactivate();
            } catch (err) {
                log(id, 'error', errText(err));
            }
            (e.context && e.context.subscriptions || []).forEach(function (d) {
                try { if (d && d.dispose) d.dispose(); } catch (err) { log(id, 'error', errText(err)); }
            });
            Object.keys(commands).forEach(function (c) { if (commands[c].ext === id) delete commands[c]; });
            formatters = formatters.filter(function (f) { return f.ext !== id; });
            completions = completions.filter(function (f) { return f.ext !== id; });
            Object.keys(webviews).forEach(function (h) {
                if (webviews[h].ext !== id) return;
                try { call('webview.dispose', h); } catch (err) { /* ignore */ }
                webviewGone(h);
            });
            Object.keys(webviewViewProviders).forEach(function (v) { if (webviewViewProviders[v].ext === id) delete webviewViewProviders[v]; });
            delete exts[id];
        },
        run: function (reqId, id, args) {
            invalidate();
            executeCommand(id, args || []).then(function (v) {
                if (reqId) bridge.callAsync(0, 'reply', JSON.stringify([reqId, true, v === undefined ? null : safe(v)]));
            }, function (err) {
                log(currentExt, 'error', "command '" + id + "' failed: " + errText(err));
                callAsync('msg.show', 2, "Command '" + id + "' resulted in an error: " + (err && err.message || err), [], false, '');
                if (reqId) bridge.callAsync(0, 'reply', JSON.stringify([reqId, false, String(err && err.message || err)]));
            });
        },
        reply: function (id, ok, value) {
            var p = pending[id];
            if (!p) return;
            delete pending[id];
            invalidate();
            if (ok) p.res(value); else p.rej(new Error(value));
        },
        fire: function (name, data) {
            invalidate();
            var d = data && data.id !== undefined ? docFor(data.id) : undefined;
            switch (name) {
                case 'activeEditor':
                    ev.activeEditor.fire(editorFor(data && data.id));
                    break;
                case 'selection':
                    var ed = editorFor(data.id);
                    ev.selection.fire({textEditor: ed, selections: ed.selections, kind: data.kind || 1});
                    break;
                case 'change':
                    ev.change.fire({document: d, contentChanges: [], reason: undefined});
                    break;
                case 'save':
                    ev.save.fire(d);
                    break;
                case 'willSave':
                    ev.willSave.fire({document: d, reason: 1, waitUntil: function () {}});
                    break;
                case 'open':
                    ev.open.fire(d);
                    break;
                case 'close':
                    if (d) ev.close.fire(d);
                    delete docs[data.id];
                    delete editors[data.id];
                    break;
                case 'config':
                    var keys = data.keys || [];
                    ev.config.fire({affectsConfiguration: function (s) {
                        return keys.some(function (k) { return k === s || k.indexOf(s + '.') === 0 || s.indexOf(k + '.') === 0; });
                    }});
                    break;
                case 'folders':
                    ev.folders.fire({added: [], removed: []});
                    break;
            }
        },
        /** Children of a tree item (or the roots) of an extension view. */
        treeChildren: function (reqId, viewId, parentHandle) {
            function reply(ok, v) {
                bridge.callAsync(0, 'reply', JSON.stringify([reqId, ok, v]));
            }
            var t = trees[viewId];
            if (!t) {
                // The extension may register the view when activated.
                var owner = null;
                Object.keys(exts).forEach(function (id) {
                    var views = exts[id].manifest.contributes && exts[id].manifest.contributes.views;
                    if (!views) return;
                    Object.keys(views).forEach(function (c) {
                        [].concat(views[c]).forEach(function (v) { if (v && v.id === viewId) owner = id; });
                    });
                });
                if (!owner) return reply(false, 'no provider');
                return activateExt(owner).then(function () {
                    if (!trees[viewId]) return reply(false, 'The extension did not register this view');
                    global.__host.treeChildren(reqId, viewId, parentHandle);
                }, function (e) { reply(false, errText(e)); });
            }
            invalidate();
            var parent = parentHandle ? (treeHandles[parentHandle] || {}).element : undefined;
            P.resolve().then(function () {
                return t.provider.getChildren(parent);
            }).then(function (children) {
                children = children || [];
                return P.all(children.map(function (c) {
                    return P.resolve(t.provider.getTreeItem(c)).then(function (item) {
                        return serializeItem(viewId, c, item || {});
                    });
                }));
            }).then(function (items) {
                reply(true, items);
            }, function (e) {
                log(t.ext, 'error', 'tree view ' + viewId + ': ' + errText(e));
                reply(false, String(e && e.message || e));
            });
        },
        /** A side bar webview view became visible for the first time. */
        resolveWebviewView: function (viewId) {
            resolveWebviewView(viewId);
        },
        /** The page in a webview called vscode.postMessage(...). */
        webviewMessage: function (handle, json) {
            var rec = webviews[handle];
            if (!rec) return;
            invalidate();
            var msg;
            try { msg = JSON.parse(json); } catch (e) { msg = json; }
            try { rec.received.fire(msg); } catch (e) { log(rec.ext, 'error', 'webview message handler: ' + errText(e)); }
        },
        /** The user closed a panel (or its view went away). */
        webviewDisposed: function (handle) {
            invalidate();
            webviewGone(handle);
        },
        webviewVisible: function (handle, visible) {
            var rec = webviews[handle];
            if (!rec) return;
            rec.visibility.fire({webviewPanel: undefined, visible: !!visible});
        },
        /** A tree item was tapped: run its command. */
        treeClick: function (handle) {
            var h = treeHandles[handle];
            if (!h || !h.item.command) return;
            var cmd = h.item.command;
            executeCommand(cmd.command, cmd.arguments || [])['catch'](function (e) {
                log('', 'error', 'tree command failed: ' + errText(e));
            });
        },
        hasFormatter: function (docId) {
            var d = docFor(docId);
            return formatters.some(function (f) { return selectorMatches(f.sel, d); });
        },
        format: function (reqId, docId, options) {
            // Exactly one reply, whatever the formatter does (throws, never settles, bad edits).
            var replied = false;
            function reply(ok, v) {
                if (replied) return;
                replied = true;
                bridge.callAsync(0, 'reply', JSON.stringify([reqId, ok, v]));
            }
            var d, f;
            try {
                invalidate();
                d = docFor(docId);
                f = d && formatters.filter(function (x) { return selectorMatches(x.sel, d); }).pop();
            } catch (e) {
                return reply(false, errText(e));
            }
            if (!f) return reply(false, 'no formatter');
            var src = new CancellationTokenSource();
            setTimeout(function () {
                if (replied) return;
                src.cancel();
                log(f.ext, 'warn', 'formatter timed out');
                reply(false, 'timed out');
            }, 15000);
            P.resolve().then(function () {
                return f.p.provideDocumentFormattingEdits(d, options || {tabSize: 4, insertSpaces: true}, src.token);
            }).then(function (edits) {
                if (replied) return; // too late: the user may have typed since
                var list = (edits || []).map(function (te) { var o = toOffsets(d, te.range); return [o[0], o[1], te.newText]; });
                if (list.length) applyEdits(d, list, 0);
                reply(true, list.length);
            })['catch'](function (err) {
                log(f.ext, 'error', 'formatter failed: ' + errText(err));
                reply(false, String(err && err.message || err));
            });
        },
        complete: function (reqId, docId, offset, trigger) {
            var replied = false;
            function reply(items) {
                if (replied) return;
                replied = true;
                bridge.callAsync(0, 'reply', JSON.stringify([reqId, true, items]));
            }
            var d, pos, ps;
            try {
                invalidate();
                d = docFor(docId);
                if (!d) return reply([]);
                pos = d.positionAt(offset);
                ps = completions.filter(function (x) {
                    return selectorMatches(x.sel, d) && (!trigger || x.triggers.indexOf(trigger) >= 0 || /\w/.test(trigger));
                });
            } catch (e) {
                log('', 'error', 'completion: ' + errText(e));
                return reply([]);
            }
            if (!ps.length) return reply([]);
            var src = new CancellationTokenSource();
            // A slow provider must not hold back the others' items forever.
            setTimeout(function () { src.cancel(); reply([]); }, 5000);
            P.all(ps.map(function (x) {
                return P.resolve().then(function () {
                    return x.p.provideCompletionItems(d, pos, src.token, {triggerKind: trigger ? 1 : 0, triggerCharacter: trigger || undefined});
                })['catch'](function (err) {
                    log(x.ext, 'error', 'completion provider failed: ' + errText(err));
                    return [];
                });
            })).then(function (results) {
                var out = [];
                results.forEach(function (r) {
                    var items = !r ? [] : Array.isArray(r) ? r : r.items || [];
                    items.forEach(function (it) {
                        if (!it) return;
                        try {
                            var label = typeof it.label === 'string' ? it.label : it.label && it.label.label || '';
                            var insert = it.insertText == null ? label : typeof it.insertText === 'string' ? it.insertText : String(it.insertText.value || '');
                            var range = it.range && (it.range.start ? it.range : it.range.replacing);
                            var o = range ? toOffsets(d, range) : null;
                            out.push({
                                label: label, detail: it.detail || '', kind: it.kind || 0,
                                insert: insert, snippet: !!(it.insertText && typeof it.insertText === 'object'),
                                start: o ? o[0] : -1, end: o ? o[1] : -1, filter: it.filterText || label, sort: it.sortText || label
                            });
                        } catch (e) {
                            // one malformed item doesn't drop the rest
                        }
                    });
                });
                reply(out);
            })['catch'](function (e) {
                log('', 'error', 'completion: ' + errText(e));
                reply([]);
            });
        }
    };
    function safe(v) {
        try {
            return JSON.parse(JSON.stringify(v));
        } catch (e) {
            return null;
        }
    }

    bridge.call('ready', '[]');
})(typeof globalThis !== 'undefined' ? globalThis : this); // globalThis on Node.js, where this is module.exports
