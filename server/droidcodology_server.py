#!/usr/bin/env python3
''''command -v python3 >/dev/null 2>&1 && exec python3 "$0" "$@"; command -v python >/dev/null 2>&1 && exec python "$0" "$@"; echo "This is a Python program: install Python 3.9 or newer (Termux: pkg install python), then run: python3 $0" >&2; exit 1 #'''
# (The line above lets "sh droidcodology_server.py" work too: a shell runs Python with this file.)
"""
droid_codology server: accounts, settings sync, an AI proxy and a language-server bridge you can
host yourself.

Only the Python standard library is used (3.9+). Configuration comes from environment
variables (see README.md); data lives in a single SQLite file under DC_DATA_DIR.

    python3 droidcodology_server.py                 # serve
    python3 droidcodology_server.py create-user EMAIL [--admin]
    python3 droidcodology_server.py create-key EMAIL NAME
"""
import sys

if sys.version_info < (3, 9):
    sys.exit("droid_codology server needs Python 3.9 or newer; this is Python %d.%d. Install a newer Python and run it "
             "with that one (for example: python3 droidcodology_server.py)." % sys.version_info[:2])

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import socket
import sqlite3
import ssl
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.1.0"


def allowed_redirect(r):
    """Where a finished OAuth sign-in may send its token: the app (droid-codology://) or a loopback
    address on the user's own machine. Parsed, not prefix-matched: "http://localhost.evil.com" and
    "http://127.0.0.1@evil.com" are other hosts."""
    if not r or not r.isascii() or any(ord(ch) < 0x21 or ord(ch) == 0x7f for ch in r):
        return False
    try:
        u = urllib.parse.urlsplit(r)
    except ValueError:
        return False
    if u.scheme == "droid-codology":
        return True
    try:
        host = u.hostname
        u.port  # raises on a malformed port
    except ValueError:
        return False
    return u.scheme == "http" and "@" not in u.netloc and host in ("127.0.0.1", "localhost")


# ---------------------------------------------------------------------------- configuration

def env(name, default=None):
    v = os.environ.get(name)
    return v if v not in (None, "") else default


def env_bool(name, default):
    v = env(name)
    if v is None:
        return default
    return v.strip().lower() in ("1", "true", "yes", "on")


class Config:
    def __init__(self):
        self.host = env("DC_HOST", "0.0.0.0")
        self.port = int(env("DC_PORT", "8080"))
        self.data_dir = env("DC_DATA_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))
        self.public_url = (env("DC_PUBLIC_URL", "http://localhost:%d" % self.port)).rstrip("/")
        self.name = env("DC_NAME", "droid_codology server")
        self.allow_registration = env_bool("DC_ALLOW_REGISTRATION", True)
        self.tls_cert = env("DC_TLS_CERT")
        self.tls_key = env("DC_TLS_KEY")
        self.session_days = int(env("DC_SESSION_DAYS", "90"))
        self.max_sync_bytes = int(env("DC_MAX_SYNC_BYTES", str(2 * 1024 * 1024)))
        self.github = (env("DC_GITHUB_CLIENT_ID"), env("DC_GITHUB_CLIENT_SECRET"))
        self.google = (env("DC_GOOGLE_CLIENT_ID"), env("DC_GOOGLE_CLIENT_SECRET"))
        # Sign in with Apple: Services ID, team id, key id and the .p8 private key file.
        self.apple = (env("DC_APPLE_SERVICES_ID"), env("DC_APPLE_TEAM_ID"), env("DC_APPLE_KEY_ID"), env("DC_APPLE_KEY_FILE"))
        self.ai_config = env("DC_AI_CONFIG")  # path to a JSON file, see README
        self.lsp = load_language_servers(env("DC_LSP_CONFIG"))
        # Language servers run code from the project they open (build scripts, settings), so by
        # default only admins may start them: "admin" (default), "all" (any signed-in user) or "false".
        lsp = (env("DC_LSP", "admin") or "admin").strip().lower()
        self.lsp_access = "all" if lsp in ("all", "true", "1", "yes", "on") else "off" if lsp in ("false", "0", "no", "off") else "admin"
        self.lsp_enabled = self.lsp_access != "off"
        self.lsp_max = int(env("DC_LSP_MAX", "16"))  # running language servers, all users together
        self.ai_daily_tokens = int(env("DC_AI_DAILY_TOKENS", "200000"))
        self.ai_max_tokens = int(env("DC_AI_MAX_TOKENS", "8192"))  # longest answer a request may ask for
        # Reverse proxies whose X-Forwarded-For header is believed (comma-separated IPs).
        self.trusted_proxies = set(p.strip() for p in (env("DC_TRUSTED_PROXY", "") or "").split(",") if p.strip())
        os.makedirs(self.data_dir, exist_ok=True)
        secret_file = os.path.join(self.data_dir, "secret.key")
        s = env("DC_SECRET")
        if not s:
            if not os.path.exists(secret_file):
                # Created private from the start (not chmod-ed afterwards).
                fd = os.open(secret_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, "w") as f:
                    f.write(secrets.token_hex(32))
            with open(secret_file) as f:
                s = f.read().strip()
        self.secret = s.encode()
        self.providers = load_ai_providers(self.ai_config)

    def oauth_providers(self):
        out = []
        if all(self.github):
            out.append("github")
        if all(self.google):
            out.append("google")
        if all(self.apple):
            out.append("apple")
        return out


def load_ai_providers(path):
    """[{id, kind: openai|anthropic|gemini, baseUrl, apiKey, models: [..]}]; $ENV references allowed in apiKey."""
    if not path:
        # Convenience: a single provider from the environment.
        out = []
        if env("OPENAI_API_KEY"):
            out.append({"id": "openai", "kind": "openai", "baseUrl": env("OPENAI_BASE_URL", "https://api.openai.com/v1"),
                        "apiKey": env("OPENAI_API_KEY"), "models": (env("OPENAI_MODELS", "gpt-4o-mini")).split(",")})
        if env("ANTHROPIC_API_KEY"):
            out.append({"id": "anthropic", "kind": "anthropic", "baseUrl": "https://api.anthropic.com",
                        "apiKey": env("ANTHROPIC_API_KEY"), "models": (env("ANTHROPIC_MODELS", "claude-sonnet-5-5")).split(",")})
        return out
    with open(path, encoding="utf-8") as f:
        items = json.load(f)
    for p in items:
        k = p.get("apiKey", "")
        if isinstance(k, str) and k.startswith("$"):
            p["apiKey"] = os.environ.get(k[1:], "")
    return items


# Language servers found on PATH when DC_LSP_CONFIG isn't set: language id -> command candidates.
KNOWN_LANGUAGE_SERVERS = [
    ("python", [["pyright-langserver", "--stdio"], ["pylsp"], ["jedi-language-server"]]),
    ("typescript", [["typescript-language-server", "--stdio"]]),
    ("javascript", [["typescript-language-server", "--stdio"]]),
    ("c", [["clangd"]]),
    ("cpp", [["clangd"]]),
    ("go", [["gopls"]]),
    ("rust", [["rust-analyzer"]]),
    ("shellscript", [["bash-language-server", "start"]]),
    ("lua", [["lua-language-server"]]),
    ("json", [["vscode-json-language-server", "--stdio"]]),
    ("css", [["vscode-css-language-server", "--stdio"]]),
    ("html", [["vscode-html-language-server", "--stdio"]]),
    ("yaml", [["yaml-language-server", "--stdio"]]),
    ("php", [["intelephense", "--stdio"]]),
    ("kotlin", [["kotlin-language-server"]]),
    ("java", [["jdtls"]]),
    ("dart", [["dart", "language-server"]]),
    ("ruby", [["solargraph", "stdio"]]),
]


def load_language_servers(path):
    """language id -> command list. From a JSON file ({"python": ["pylsp"], ...}) or found on PATH."""
    if path:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        return {k: (v["command"] if isinstance(v, dict) else v) for k, v in data.items()}
    found = {}
    for lang, candidates in KNOWN_LANGUAGE_SERVERS:
        for cmd in candidates:
            exe = shutil.which(cmd[0])
            if exe:
                found[lang] = [exe] + cmd[1:]
                break
    return found


class LspSession:
    """One language server process; messages go out over an SSE stream, come in by POST."""

    def __init__(self, sid, user_id, language, command, root):
        self.id = sid
        self.user_id = user_id
        self.language = language
        self.root = root
        self.queue = []
        self.cond = threading.Condition()
        self.listeners = 0
        self.last_active = time.time()
        self.exit_code = None
        self.files = 0
        self.bytes = 0
        # Own process group, so stopping it also stops what it started (node behind a .cmd shim,
        # build tools...); and none of the server's secrets in its environment.
        kw = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        self.proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     cwd=root, bufsize=0, env=lsp_environment(), **kw)
        self.write_lock = threading.Lock()
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _read(self):
        out = self.proc.stdout
        try:
            while True:
                length = None
                while True:
                    line = out.readline()
                    if not line:
                        raise EOFError
                    line = line.strip()
                    if not line:
                        break
                    if line.lower().startswith(b"content-length:"):
                        length = int(line.split(b":", 1)[1])
                if length is None:
                    continue
                body = b""
                while len(body) < length:
                    chunk = out.read(length - len(body))
                    if not chunk:
                        raise EOFError
                    body += chunk
                self.push(body.decode("utf-8", "replace"))
        except (EOFError, OSError, ValueError):
            pass
        self.exit_code = self.proc.wait()
        self.push(json.dumps({"droidcodology": "exit", "code": self.exit_code}))

    def _drain_stderr(self):
        for line in self.proc.stderr:
            sys.stderr.write("[lsp %s] %s" % (self.language, line.decode("utf-8", "replace")))

    def push(self, message):
        with self.cond:
            self.queue.append(message)
            if len(self.queue) > 5000:
                del self.queue[:1000]
            self.cond.notify_all()

    def send(self, raw):
        self.last_active = time.time()
        data = raw if isinstance(raw, bytes) else raw.encode("utf-8")
        with self.write_lock:
            self.proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(data) + data)
            self.proc.stdin.flush()

    def take(self, timeout):
        with self.cond:
            if not self.queue:
                self.cond.wait(timeout)
            items, self.queue = self.queue, []
            return items

    def alive(self):
        return self.proc.poll() is None

    def kill(self):
        """Stops the language server with everything it started, then deletes its files."""
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/T", "/F", "/PID", str(self.proc.pid)], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=15)
            else:
                import signal
                os.killpg(self.proc.pid, signal.SIGKILL)
        except (OSError, subprocess.SubprocessError):
            pass
        try:
            self.proc.kill()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
        shutil.rmtree(self.root, ignore_errors=True)
        if os.path.exists(self.root):
            sys.stderr.write("could not delete language server folder %s\n" % self.root)


# Environment variables a language server needs; everything else (API keys, DC_* secrets) stays out.
LSP_ENV_KEEP = ("PATH", "PATHEXT", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR", "HOME",
                "USERPROFILE", "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "LANG", "LC_ALL",
                "LC_CTYPE", "JAVA_HOME", "GOPATH", "GOROOT", "CARGO_HOME", "RUSTUP_HOME", "NODE_PATH", "PYTHONPATH",
                "VIRTUAL_ENV", "PREFIX", "ANDROID_DATA", "ANDROID_ROOT", "LD_LIBRARY_PATH", "SHELL", "USER", "LOGNAME")


def lsp_environment():
    return {k: v for k, v in os.environ.items() if k.upper() in LSP_ENV_KEEP}


# ---------------------------------------------------------------------------- storage

SCHEMA = """
create table if not exists users (
    id integer primary key autoincrement,
    email text unique,
    name text not null default '',
    password text,
    admin integer not null default 0,
    created integer not null
);
create table if not exists identities (
    provider text not null,
    subject text not null,
    user_id integer not null references users(id) on delete cascade,
    primary key (provider, subject)
);
create table if not exists tokens (
    hash text primary key,
    user_id integer not null references users(id) on delete cascade,
    kind text not null,            -- 'session' or 'key'
    name text not null default '',
    created integer not null,
    expires integer,               -- null: never
    last_used integer
);
create table if not exists sync (
    user_id integer primary key references users(id) on delete cascade,
    version integer not null,
    data text not null,
    updated integer not null
);
create table if not exists ai_usage (
    user_id integer not null,
    day text not null,
    tokens integer not null default 0,
    primary key (user_id, day)
);
"""


class Store:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        c = self.conn()
        try:
            c.executescript(SCHEMA)
        finally:
            c.close()

    def conn(self):
        c = sqlite3.connect(self.path, timeout=30)
        c.row_factory = sqlite3.Row
        c.execute("pragma foreign_keys = on")
        c.execute("pragma journal_mode = wal")
        return c

    def q(self, sql, args=(), one=False):
        with self.lock:
            c = self.conn()
            try:
                rows = c.execute(sql, args).fetchall()
            finally:
                c.close()
        return (rows[0] if rows else None) if one else rows

    def x(self, sql, args=()):
        with self.lock:
            c = self.conn()
            try:
                with c:
                    return c.execute(sql, args).lastrowid
            finally:
                c.close()

    def xr(self, sql, args=()):
        """Like x(), but returns the number of changed rows (for conditional updates)."""
        with self.lock:
            c = self.conn()
            try:
                with c:
                    return c.execute(sql, args).rowcount
            finally:
                c.close()

    class _Tx:
        def __init__(self, store):
            self.store = store

        def __enter__(self):
            self.store.lock.acquire()
            self.c = self.store.conn()
            self.c.execute("begin immediate")
            return self.c

        def __exit__(self, kind, value, tb):
            try:
                if kind is None:
                    self.c.commit()
                else:
                    self.c.rollback()
            finally:
                self.c.close()
                self.store.lock.release()
            return False

    def tx(self):
        """A transaction: with store.tx() as c: c.execute(...)."""
        return Store._Tx(self)


# ---------------------------------------------------------------------------- crypto helpers

class Html(str):
    """A route result sent as an HTML page instead of JSON."""


def html_escape(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&#39;"))


def hash_password(pw):
    salt = secrets.token_bytes(16)
    h = hashlib.scrypt(pw.encode(), salt=salt, n=2 ** 14, r=8, p=1, dklen=32)
    return "scrypt$%s$%s" % (base64.b64encode(salt).decode(), base64.b64encode(h).decode())


def check_password(pw, stored):
    try:
        alg, salt, h = stored.split("$")
        if alg != "scrypt":
            return False
        got = hashlib.scrypt(pw.encode(), salt=base64.b64decode(salt), n=2 ** 14, r=8, p=1, dklen=32)
        return hmac.compare_digest(got, base64.b64decode(h))
    except Exception:
        return False


def token_hash(t):
    return hashlib.sha256(t.encode()).hexdigest()


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def b64url_decode(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def sign_state(secret, payload):
    raw = json.dumps(payload, separators=(",", ":")).encode()
    mac = hmac.new(secret, raw, hashlib.sha256).digest()
    return b64url(raw) + "." + b64url(mac)


def verify_state(secret, state, max_age=900):
    try:
        raw_s, mac_s = state.split(".")
        raw = b64url_decode(raw_s)
        if not hmac.compare_digest(hmac.new(secret, raw, hashlib.sha256).digest(), b64url_decode(mac_s)):
            return None
        p = json.loads(raw)
        if time.time() - p.get("t", 0) > max_age:
            return None
        return p
    except Exception:
        return None


def jwt_payload(token):
    """Payload of a JWT received directly from the issuer over TLS (signature not needed then)."""
    return json.loads(b64url_decode(token.split(".")[1]))


# P-256 ECDSA signing for the Sign in with Apple client secret (ES256), standard library only.
P256_P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff
P256_N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551
P256_A = P256_P - 3
P256_G = (0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296,
          0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5)


def _ec_add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    if p1[0] == p2[0] and (p1[1] + p2[1]) % P256_P == 0:
        return None
    if p1 == p2:
        lam = (3 * p1[0] * p1[0] + P256_A) * pow(2 * p1[1], -1, P256_P) % P256_P
    else:
        lam = (p2[1] - p1[1]) * pow(p2[0] - p1[0], -1, P256_P) % P256_P
    x = (lam * lam - p1[0] - p2[0]) % P256_P
    return x, (lam * (p1[0] - x) - p1[1]) % P256_P


def _ec_mul(k, pt):
    r = None
    while k:
        if k & 1:
            r = _ec_add(r, pt)
        pt = _ec_add(pt, pt)
        k >>= 1
    return r


def _der_read(b, i):
    tag = b[i]
    ln = b[i + 1]
    i += 2
    if ln & 0x80:
        n = ln & 0x7f
        ln = int.from_bytes(b[i:i + n], "big")
        i += n
    return tag, b[i:i + ln], i + ln


def p8_private_key(pem):
    """The private scalar from a PKCS#8 EC key (Apple .p8 file)."""
    der = base64.b64decode("".join(l for l in pem.strip().splitlines() if not l.startswith("-----")))
    _, seq, _ = _der_read(der, 0)
    i = 0
    _, _, i = _der_read(seq, i)  # version
    _, _, i = _der_read(seq, i)  # algorithm
    _, octets, _ = _der_read(seq, i)  # privateKey OCTET STRING -> ECPrivateKey
    _, ec, _ = _der_read(octets, 0)
    j = 0
    _, _, j = _der_read(ec, j)  # version
    _, d, _ = _der_read(ec, j)
    return int.from_bytes(d, "big")


def es256_sign(d, msg):
    z = int.from_bytes(hashlib.sha256(msg).digest(), "big")
    while True:
        k = secrets.randbelow(P256_N - 1) + 1
        r = _ec_mul(k, P256_G)[0] % P256_N
        if r == 0:
            continue
        s = pow(k, -1, P256_N) * (z + r * d) % P256_N
        if s:
            return r.to_bytes(32, "big") + s.to_bytes(32, "big")


def es256_verify(pub, msg, sig):
    z = int.from_bytes(hashlib.sha256(msg).digest(), "big")
    r, s = int.from_bytes(sig[:32], "big"), int.from_bytes(sig[32:], "big")
    w = pow(s, -1, P256_N)
    pt = _ec_add(_ec_mul(z * w % P256_N, P256_G), _ec_mul(r * w % P256_N, pub))
    return pt is not None and pt[0] % P256_N == r


_apple_secret = [None, 0]


def apple_client_secret(cfg):
    """The JWT Apple wants as client secret. Signed once a day and reused (Apple accepts up to six
    months), so the private key is used rarely and timing can't be sampled over many sign-ins."""
    now = int(time.time())
    if _apple_secret[0] and now < _apple_secret[1] - 3600:
        return _apple_secret[0]
    services_id, team_id, key_id, key_file = cfg.apple
    with open(key_file) as f:
        d = p8_private_key(f.read())
    header = b64url(json.dumps({"alg": "ES256", "kid": key_id}).encode())
    exp = now + 86400
    payload = b64url(json.dumps({"iss": team_id, "iat": now, "exp": exp, "aud": "https://appleid.apple.com",
                                 "sub": services_id}).encode())
    signing_input = (header + "." + payload).encode()
    _apple_secret[0] = header + "." + payload + "." + b64url(es256_sign(d, signing_input))
    _apple_secret[1] = exp
    return _apple_secret[0]


# ---------------------------------------------------------------------------- outgoing HTTP

def http_json(url, data=None, headers=None, form=False, method=None, timeout=30):
    h = {"Accept": "application/json", "User-Agent": "droid_codology-server/" + VERSION}
    if headers:
        h.update(headers)
    body = None
    if data is not None:
        if form:
            body = urllib.parse.urlencode(data).encode()
            h["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            body = json.dumps(data).encode()
            h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or "null")


# ---------------------------------------------------------------------------- AI providers

def ai_request(provider, model, messages, system, max_tokens, temperature, stream):
    """Builds (url, headers, body) for a provider; messages are [{role: user|assistant, content: str}]."""
    kind = provider.get("kind", "openai")
    base = provider.get("baseUrl", "").rstrip("/")
    key = provider.get("apiKey", "")
    if kind == "anthropic":
        body = {"model": model, "messages": messages, "max_tokens": max_tokens or 4096, "stream": stream}
        if system:
            body["system"] = system
        if temperature is not None:
            body["temperature"] = temperature
        return base + "/v1/messages", {"x-api-key": key, "anthropic-version": "2023-06-01"}, body
    if kind == "gemini":
        contents = [{"role": "model" if m["role"] == "assistant" else "user", "parts": [{"text": m["content"]}]} for m in messages]
        body = {"contents": contents, "generationConfig": {}}
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        if max_tokens:
            body["generationConfig"]["maxOutputTokens"] = max_tokens
        if temperature is not None:
            body["generationConfig"]["temperature"] = temperature
        action = "streamGenerateContent?alt=sse" if stream else "generateContent"
        return "%s/v1beta/models/%s:%s" % (base or "https://generativelanguage.googleapis.com", model, action), {"x-goog-api-key": key}, body
    msgs = ([{"role": "system", "content": system}] if system else []) + messages
    body = {"model": model, "messages": msgs, "stream": stream}
    if max_tokens:
        body["max_tokens"] = max_tokens
    if temperature is not None:
        body["temperature"] = temperature
    if stream:
        body["stream_options"] = {"include_usage": True}
    headers = {"Authorization": "Bearer " + key} if key else {}
    return base + "/chat/completions", headers, body


def ai_parse_event(kind, data):
    """One SSE data payload from a provider -> (text delta, usage tokens or None, finished)."""
    try:
        j = json.loads(data)
    except ValueError:
        return "", None, data.strip() == "[DONE]"
    if kind == "anthropic":
        t = j.get("type")
        if t == "content_block_delta":
            return j.get("delta", {}).get("text", ""), None, False
        if t == "message_delta":
            u = j.get("usage", {})
            return "", u.get("output_tokens"), False
        if t == "message_start":
            u = j.get("message", {}).get("usage", {})
            return "", u.get("input_tokens"), False
        if t == "message_stop":
            return "", None, True
        if t == "error":
            raise RuntimeError(j.get("error", {}).get("message", "provider error"))
        return "", None, False
    if kind == "gemini":
        text = ""
        for c in j.get("candidates", []):
            for p in c.get("content", {}).get("parts", []):
                text += p.get("text", "")
        u = j.get("usageMetadata", {}).get("totalTokenCount")
        return text, u, False
    text = ""
    for c in j.get("choices", []) or []:
        text += (c.get("delta") or {}).get("content") or ""
    u = (j.get("usage") or {}).get("total_tokens")
    return text, u, False


def ai_parse_full(kind, j):
    if kind == "anthropic":
        text = "".join(b.get("text", "") for b in j.get("content", []) if b.get("type") == "text")
        u = j.get("usage", {})
        return text, (u.get("input_tokens", 0) + u.get("output_tokens", 0))
    if kind == "gemini":
        text = "".join(p.get("text", "") for c in j.get("candidates", []) for p in c.get("content", {}).get("parts", []))
        return text, j.get("usageMetadata", {}).get("totalTokenCount", 0)
    text = "".join((c.get("message") or {}).get("content") or "" for c in j.get("choices", []))
    return text, (j.get("usage") or {}).get("total_tokens", 0)


# ---------------------------------------------------------------------------- the server

class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def text_field(b, name, default=""):
    """A string field of a JSON body; anything else is the client's mistake (400), not a crash."""
    v = b.get(name, default)
    if v is None:
        return default
    if not isinstance(v, str):
        raise ApiError(400, "%s must be text" % name)
    return v


class RateLimiter:
    def __init__(self, per_minute):
        self.per_minute = per_minute
        self.hits = {}
        self.lock = threading.Lock()
        self.pruned = time.time()

    def check(self, key):
        now = time.time()
        with self.lock:
            if now - self.pruned > 60:
                # Forget clients that have been quiet for a minute, so the table can't grow forever.
                self.hits = {k: v for k, v in self.hits.items() if v and now - v[-1] < 60}
                self.pruned = now
            lst = [t for t in self.hits.get(key, []) if now - t < 60]
            if len(lst) >= self.per_minute:
                self.hits[key] = lst
                return False
            lst.append(now)
            self.hits[key] = lst
            return True


class App:
    def __init__(self, cfg):
        self.cfg = cfg
        self.db = Store(os.path.join(cfg.data_dir, "droidcodology.db"))
        self.auth_limiter = RateLimiter(10)
        self.lsp_sessions = {}
        self.lsp_lock = threading.Lock()
        self.devices = {}
        self.device_lock = threading.Lock()
        self.used_nonces = {}
        self.lookup_limiter = RateLimiter(30)  # device-code lookups per IP and minute
        threading.Thread(target=self._reap_lsp, daemon=True).start()

    def https_on_http(self, ip):
        now = time.time()
        if now - getattr(self, "_https_warned", 0) < 30:
            return
        self._https_warned = now
        sys.stderr.write(
            "\n!!! %s tried to connect with HTTPS, but this server uses plain HTTP.\n"
            "!!! In the app, enter the address with http:// (not https://), for example: http://<this computer's IP>:%d\n\n"
            % (ip, self.cfg.port))

    def _reap_lsp(self):
        """Stops language servers nobody listens to any more (10 minutes) or that exited."""
        while True:
            time.sleep(30)
            now = time.time()
            with self.lsp_lock:
                for sid, s in list(self.lsp_sessions.items()):
                    if s is None:
                        continue  # being started
                    if not s.alive() or (s.listeners == 0 and now - s.last_active > 600):
                        s.kill()
                        del self.lsp_sessions[sid]

    # ---- users & tokens

    def user_json(self, u):
        ids = [r["provider"] for r in self.db.q("select provider from identities where user_id=?", (u["id"],))]
        return {"id": u["id"], "email": u["email"], "name": u["name"], "admin": bool(u["admin"]),
                "hasPassword": bool(u["password"]), "identities": ids, "created": u["created"]}

    def _insert_user(self, c, email, name, password_hash, admin):
        """Inside a transaction. The admin is DC_ADMIN_EMAIL's account if that is set, else the first account."""
        admin_email = (env("DC_ADMIN_EMAIL", "") or "").strip().lower()
        if admin_email:
            admin = admin or (email or "") == admin_email
        else:
            admin = admin or c.execute("select count(*) n from users").fetchone()["n"] == 0
        return c.execute("insert into users(email, name, password, admin, created) values (?,?,?,?,?)",
                         (email, name or (email.split("@")[0] if email else ""), password_hash, 1 if admin else 0,
                          int(time.time()))).lastrowid

    def create_user(self, email, name="", password=None, admin=False):
        email = email.strip().lower() if email else None
        pw = hash_password(password) if password else None  # slow: outside the transaction
        with self.db.tx() as c:
            if email and c.execute("select id from users where email=?", (email,)).fetchone():
                raise ApiError(409, "An account with this email already exists")
            uid = self._insert_user(c, email, name, pw, admin)
        return self.db.q("select * from users where id=?", (uid,), one=True)

    def identity_user(self, provider, subject, email, name):
        """The account of an OAuth identity, created at its first sign-in. An email-and-password
        account with the same address is not taken over: that address was never verified, so
        someone else may have registered it to wait for the owner's sign-in."""
        with self.db.tx() as c:
            row = c.execute("select user_id from identities where provider=? and subject=?", (provider, subject)).fetchone()
            if row:
                return row["user_id"]
            uid = None
            if email:
                existing = c.execute("select id, password from users where email=?", (email,)).fetchone()
                if existing and existing["password"]:
                    raise ApiError(409, "An account with %s already exists: sign in with its email and password." % email)
                if existing:
                    uid = existing["id"]  # made by another verified OAuth sign-in with this address
            if uid is None:
                uid = self._insert_user(c, email, name, None, False)
            c.execute("insert into identities(provider, subject, user_id) values (?,?,?)", (provider, subject, uid))
            return uid

    def use_nonce(self, nonce):
        """OAuth states work once."""
        now = time.time()
        with self.device_lock:
            for k in [k for k, t in self.used_nonces.items() if now - t > 900]:
                del self.used_nonces[k]
            if not nonce or nonce in self.used_nonces:
                return False
            self.used_nonces[nonce] = now
            return True

    def issue_token(self, uid, kind="session", name=""):
        prefix = "dcs_" if kind == "session" else "dck_"
        t = prefix + secrets.token_urlsafe(32)
        expires = int(time.time()) + self.cfg.session_days * 86400 if kind == "session" else None
        self.db.x("insert into tokens(hash, user_id, kind, name, created, expires) values (?,?,?,?,?,?)",
                  (token_hash(t), uid, kind, name, int(time.time()), expires))
        return t

    def auth(self, handler):
        h = handler.headers.get("Authorization", "")
        if not h.startswith("Bearer "):
            raise ApiError(401, "Sign in required")
        t = h[7:].strip()
        row = self.db.q("select t.*, u.id uid from tokens t join users u on u.id=t.user_id where t.hash=?", (token_hash(t),), one=True)
        if not row or (row["expires"] and row["expires"] < time.time()):
            raise ApiError(401, "Invalid or expired token")
        now = int(time.time())
        if not row["last_used"] or now - row["last_used"] > 300:
            self.db.x("update tokens set last_used=? where hash=?", (now, row["hash"]))
        return self.db.q("select * from users where id=?", (row["uid"],), one=True), row

    # ---- routing

    def route(self, h, method, path, query):
        if path in ("/", "/healthz"):
            return 200, {"ok": True, "name": self.cfg.name, "version": VERSION}
        if path == "/v1/info" and method == "GET":
            return 200, {"name": self.cfg.name, "version": VERSION, "registration": self.cfg.allow_registration,
                         "oauth": self.cfg.oauth_providers(), "deviceSignIn": True, "ai": bool(self.cfg.providers),
                         "models": self.models(), "maxSyncBytes": self.cfg.max_sync_bytes,
                         "lsp": sorted(self.cfg.lsp.keys()) if self.cfg.lsp_enabled else []}
        if path == "/v1/auth/register" and method == "POST":
            return self.register(h)
        if path == "/v1/auth/login" and method == "POST":
            return self.login(h)
        if path == "/v1/auth/device/start" and method == "POST":
            return self.device_start(h)
        if path == "/v1/auth/device/poll" and method == "POST":
            return self.device_poll(h)
        if path == "/link" and method == "GET":
            return self.link_page(query.get("code", [""])[0], h=h)
        if path == "/link/email" and method == "POST":
            return self.link_email(h)
        m = re.match(r"^/v1/auth/oauth/(github|google|apple)/(start|callback)$", path)
        if m:
            return self.oauth(h, m.group(1), m.group(2), method, query)
        if path == "/v1/auth/logout" and method == "POST":
            u, tok = self.auth(h)
            self.db.x("delete from tokens where hash=?", (tok["hash"],))
            return 200, {"ok": True}
        if path == "/v1/me":
            u, tok = self.auth(h)
            if method == "GET":
                return 200, self.user_json(u)
            if method == "PATCH":
                return self.update_me(h, u, tok)
            if method == "DELETE":
                self.db.x("delete from users where id=?", (u["id"],))
                return 200, {"ok": True}
        if path == "/v1/keys":
            u, _ = self.auth(h)
            if method == "GET":
                rows = self.db.q("select rowid, name, created, last_used from tokens where user_id=? and kind='key' order by created",
                                 (u["id"],))
                return 200, [{"id": r["rowid"], "name": r["name"], "created": r["created"], "lastUsed": r["last_used"]} for r in rows]
            if method == "POST":
                name = (h.json().get("name") or "API key")[:100]
                key = self.issue_token(u["id"], "key", name)
                return 201, {"key": key, "name": name}
        m = re.match(r"^/v1/keys/(\d+)$", path)
        if m and method == "DELETE":
            u, _ = self.auth(h)
            self.db.x("delete from tokens where rowid=? and user_id=? and kind='key'", (int(m.group(1)), u["id"]))
            return 200, {"ok": True}
        if path == "/v1/sessions" and method == "DELETE":
            u, tok = self.auth(h)
            self.db.x("delete from tokens where user_id=? and kind='session' and hash<>?", (u["id"], tok["hash"]))
            return 200, {"ok": True}
        if path == "/v1/sync":
            u, _ = self.auth(h)
            if method == "GET":
                r = self.db.q("select * from sync where user_id=?", (u["id"],), one=True)
                if not r:
                    return 200, {"version": 0, "data": None, "updated": None}
                return 200, {"version": r["version"], "data": json.loads(r["data"]), "updated": r["updated"]}
            if method == "PUT":
                return self.put_sync(h, u)
            if method == "DELETE":
                self.db.x("delete from sync where user_id=?", (u["id"],))
                return 200, {"ok": True}
        if path == "/v1/ai/models" and method == "GET":
            self.auth(h)
            return 200, self.models()
        if path == "/v1/ai/chat" and method == "POST":
            u, _ = self.auth(h)
            return self.ai_chat(h, u)
        if path.startswith("/v1/lsp"):
            u, _ = self.auth(h)
            return self.lsp_route(h, u, method, path)
        if path == "/v1/ai/usage" and method == "GET":
            u, _ = self.auth(h)
            r = self.db.q("select tokens from ai_usage where user_id=? and day=?", (u["id"], time.strftime("%Y-%m-%d")), one=True)
            return 200, {"today": r["tokens"] if r else 0, "dailyLimit": self.cfg.ai_daily_tokens}
        raise ApiError(404, "Not found")

    # ---- email / password

    def register(self, h):
        if not self.auth_limiter.check(h.client_ip()):
            raise ApiError(429, "Too many attempts, try again in a minute")
        if not self.cfg.allow_registration:
            raise ApiError(403, "Registration is closed on this server")
        b = h.json()
        email = text_field(b, "email").strip().lower()
        pw = text_field(b, "password")
        if not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email):
            raise ApiError(400, "Enter a valid email address")
        if len(pw) < 8:
            raise ApiError(400, "The password must be at least 8 characters long")
        u = self.create_user(email, text_field(b, "name")[:100], pw)
        return 201, {"token": self.issue_token(u["id"], name=text_field(b, "device")[:100]), "user": self.user_json(u)}

    def login(self, h):
        if not self.auth_limiter.check(h.client_ip()):
            raise ApiError(429, "Too many attempts, try again in a minute")
        b = h.json()
        u = self.db.q("select * from users where email=?", (text_field(b, "email").strip().lower(),), one=True)
        if not u or not u["password"] or not check_password(text_field(b, "password"), u["password"]):
            raise ApiError(401, "Wrong email or password")
        return 200, {"token": self.issue_token(u["id"], name=text_field(b, "device")[:100]), "user": self.user_json(u)}

    def update_me(self, h, u, tok):
        b = h.json()
        if "name" in b:
            self.db.x("update users set name=? where id=?", (str(b["name"])[:100], u["id"]))
        if "password" in b:
            if not isinstance(b["password"], str) or not isinstance(b.get("currentPassword") or "", str):
                raise ApiError(400, "The password must be text")
            if u["password"] and not check_password(b.get("currentPassword") or "", u["password"]):
                raise ApiError(403, "The current password is wrong")
            if len(b["password"]) < 8:
                raise ApiError(400, "The password must be at least 8 characters long")
            self.db.x("update users set password=? where id=?", (hash_password(b["password"]), u["id"]))
            # A new password ends every other session (API keys are revoked separately, in /v1/keys).
            self.db.x("delete from tokens where user_id=? and kind='session' and hash<>?", (u["id"], tok["hash"]))
        return 200, self.user_json(self.db.q("select * from users where id=?", (u["id"],), one=True))

    # ---- OAuth (GitHub, Google, Apple): the app opens .../start in a browser and gets its token back
    # via a redirect to droid-codology://auth?token=...

    def oauth(self, h, provider, step, method, query):
        if provider not in self.cfg.oauth_providers():
            raise ApiError(404, "%s sign-in is not configured on this server" % provider)
        callback = "%s/v1/auth/oauth/%s/callback" % (self.cfg.public_url, provider)
        if step == "start":
            redirect = query.get("redirect", ["droid-codology://auth"])[0]
            link = query.get("link", [""])[0]
            if link:
                # Sign-in for another device (see device_start): the token goes to that device.
                if not self.lookup_limiter.check(h.client_ip()):
                    raise ApiError(429, "Too many attempts, try again in a minute")
                if not self.device_by_user_code(link):
                    return 200, Html(self.page("Code expired", "<p>This code has expired. Start again on your device.</p>"))
                redirect = ""
            elif not allowed_redirect(redirect):
                raise ApiError(400, "Redirect not allowed")
            nonce = secrets.token_hex(16)
            state = sign_state(self.cfg.secret, {"p": provider, "r": redirect, "t": int(time.time()), "n": nonce, "d": link})
            if provider == "github":
                url = "https://github.com/login/oauth/authorize?" + urllib.parse.urlencode({
                    "client_id": self.cfg.github[0], "redirect_uri": callback, "scope": "read:user user:email", "state": state})
            elif provider == "google":
                url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
                    "client_id": self.cfg.google[0], "redirect_uri": callback, "response_type": "code",
                    "scope": "openid email profile", "state": state, "prompt": "select_account"})
            else:
                url = "https://appleid.apple.com/auth/authorize?" + urllib.parse.urlencode({
                    "client_id": self.cfg.apple[0], "redirect_uri": callback, "response_type": "code",
                    "scope": "name email", "response_mode": "form_post", "state": state})
            # The state is valid only in the browser that started the sign-in (against login CSRF).
            # Apple comes back with a cross-site form POST, which only sends SameSite=None cookies.
            https = self.cfg.public_url.startswith("https://")
            cookie = "dc_oauth=%s; Path=/v1/auth/oauth/; Max-Age=900; HttpOnly; %s" % (
                nonce, "SameSite=None; Secure" if https else "SameSite=Lax")
            return 302, (url, {"Set-Cookie": cookie})
        # callback: Apple posts a form, the others use the query string
        params = query
        if method == "POST":
            params = urllib.parse.parse_qs(h.body().decode())
        state = verify_state(self.cfg.secret, params.get("state", [""])[0])
        if not state or state.get("p") != provider:
            raise ApiError(400, "Sign-in expired, please try again")
        if h.cookie("dc_oauth") != state.get("n"):
            raise ApiError(400, "Finish the sign-in in the browser where it started")
        if not self.use_nonce(state.get("n", "")):
            raise ApiError(400, "This sign-in was already used, please start again")
        redirect = state["r"]
        if not state.get("d") and not allowed_redirect(redirect):
            raise ApiError(400, "Redirect not allowed")
        link = state.get("d") or ""
        if "error" in params:
            if link:
                return 200, Html(self.page("Not signed in", "<p>Sign-in was cancelled (%s).</p><p><a href=\"/link?code=%s\">Try again</a></p>"
                                           % (html_escape(params["error"][0]), html_escape(link))))
            return 302, redirect + ("&" if "?" in redirect else "?") + urllib.parse.urlencode({"error": params["error"][0]})
        code = params.get("code", [""])[0]
        try:
            subject, email, name = self.oauth_identity(provider, code, callback, params)
        except (urllib.error.URLError, ValueError, KeyError) as e:
            raise ApiError(502, "%s sign-in failed: %s" % (provider, e))
        try:
            uid = self.identity_user(provider, subject, email, name)
        except ApiError as e:
            if link:
                return 200, Html(self.page("Not signed in", "<p>%s</p>" % html_escape(e.message)))
            return 302, redirect + ("&" if "?" in redirect else "?") + urllib.parse.urlencode({"error": e.message})
        if link:
            return self.link_done(link, uid, provider)
        token = self.issue_token(uid, name=provider)
        return 302, redirect + ("&" if "?" in redirect else "?") + urllib.parse.urlencode({"token": token})

    # ---- signing in a device that can't open the sign-in pages itself (old Android browsers):
    # the app shows a code, the user enters it at /link on any device and signs in there.

    DEVICE_TTL = 600
    CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ"  # no vowels: codes never spell words

    def device_start(self, h):
        if not self.auth_limiter.check(h.client_ip()):
            raise ApiError(429, "Too many attempts, try again in a minute")
        b = h.json()
        code = "".join(secrets.choice(self.CODE_ALPHABET) for _ in range(8))
        device_code = secrets.token_urlsafe(32)
        now = time.time()
        with self.device_lock:
            for k in [k for k, v in self.devices.items() if now - v["created"] > self.DEVICE_TTL]:
                del self.devices[k]
            if len(self.devices) > 10000:
                raise ApiError(503, "Too many sign-ins in progress, try again in a few minutes")
            self.devices[device_code] = {"code": code, "created": now, "user": None, "device": text_field(b, "device")[:100],
                                         "ip": h.client_ip()}
        shown = code[:4] + "-" + code[4:]
        return 200, {"device_code": device_code, "user_code": shown, "verification_url": self.cfg.public_url + "/link",
                     "verification_url_complete": self.cfg.public_url + "/link?code=" + shown,
                     "expires_in": self.DEVICE_TTL, "interval": 3}

    def device_poll(self, h):
        dc = h.json().get("device_code") or ""
        with self.device_lock:
            e = self.devices.get(dc)
            if not e or time.time() - e["created"] > self.DEVICE_TTL:
                self.devices.pop(dc, None)
                raise ApiError(410, "The code has expired")
            if e["user"] is None:
                return 200, {"pending": True}
            del self.devices[dc]
        u = self.db.q("select * from users where id=?", (e["user"],), one=True)
        return 200, {"token": self.issue_token(u["id"], name=e["device"] or "device"), "user": self.user_json(u)}

    def device_by_user_code(self, code):
        code = re.sub(r"[^A-Z]", "", (code or "").upper())
        now = time.time()
        with self.device_lock:
            for v in self.devices.values():
                if v["code"] == code and v["user"] is None and now - v["created"] <= self.DEVICE_TTL:
                    return v
        return None

    def link_done(self, code, uid, how):
        wanted = re.sub(r"[^A-Z]", "", (code or "").upper())
        now = time.time()
        with self.device_lock:
            # Check and claim together: a code signs in exactly one account.
            e = next((v for v in self.devices.values() if v["code"] == wanted and v["user"] is None
                      and now - v["created"] <= self.DEVICE_TTL), None)
            if e:
                e["user"] = uid
        if not e:
            return 200, Html(self.page("Code expired", "<p>This code has expired or was already used. Start again on your device.</p>"))
        u = self.db.q("select * from users where id=?", (uid,), one=True)
        who = html_escape(u["email"] or u["name"] or "")
        return 200, Html(self.page("Signed in", "<p>Your device is now signed in%s (with %s). You can close this page.</p>"
                                   % (" as <b>%s</b>" % who if who else "", html_escape(how))))

    def page(self, title, body):
        return ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                "<title>%s</title><style>body{font-family:sans-serif;max-width:28em;margin:2em auto;padding:0 1em;line-height:1.5}"
                "input,button,a.b{display:block;width:100%%;box-sizing:border-box;margin:.4em 0;padding:.6em;font-size:1em}"
                "a.b{text-align:center;background:#0066b8;color:#fff;text-decoration:none;border-radius:3px}"
                "button{background:#0066b8;color:#fff;border:0;border-radius:3px}</style></head><body><h2>%s</h2>%s"
                "<p style=\"color:#888;font-size:.85em\">%s</p></body></html>") % (
            html_escape(title), html_escape(title), body, html_escape(self.cfg.name))

    def link_page(self, code, error="", h=None):
        err = "<p style=\"color:#c00\">%s</p>" % html_escape(error) if error else ""
        if code and h is not None and not self.lookup_limiter.check(h.client_ip()):
            return 429, Html(self.page("Sign in a device", "<p style=\"color:#c00\">Too many attempts, try again in a minute.</p>"))
        entry = self.device_by_user_code(code) if code else None
        if not entry:
            msg = "<p style=\"color:#c00\">Unknown or expired code.</p>" if code else ""
            return 200, Html(self.page("Sign in a device", msg + "<p>Enter the code shown on your device:</p>"
                                       "<form method=\"get\" action=\"/link\"><input name=\"code\" autocapitalize=\"characters\" "
                                       "placeholder=\"XXXX-XXXX\" value=\"%s\"><button>Continue</button></form>" % html_escape(code)))
        c = html_escape(code.strip().upper())
        names = {"github": "GitHub", "google": "Google", "apple": "Apple"}
        buttons = "".join("<a class=\"b\" href=\"/v1/auth/oauth/%s/start?link=%s\">Sign in with %s</a>" % (p, c, names[p])
                          for p in self.cfg.oauth_providers())
        email = ("<p>%s with your email and password:</p><form method=\"post\" action=\"/link/email\">" % ("Or" if buttons else "Sign in")) + (
                 "<input type=\"hidden\" name=\"code\" value=\"%s\"><input name=\"email\" type=\"email\" placeholder=\"Email\">"
                 "<input name=\"password\" type=\"password\" placeholder=\"Password\"><button>Sign In</button></form>" % c)
        # Whoever signs in here gives that device their account: say which device it is, so that a
        # code someone else sent can't be used to take over the account (RFC 8628, section 5.4).
        who = html_escape(entry.get("device") or "an unnamed device")
        when = time.strftime("%H:%M", time.localtime(entry["created"]))
        warning = ("<p>Code <b>%s</b> was made by <b>%s</b> at %s. <b>Continue only if you started signing in on that "
                   "device yourself just now.</b> Never enter a code that someone sent you.</p>" % (c, who, when))
        return 200, Html(self.page("Sign in a device", err + warning + buttons + email))

    def link_email(self, h):
        f = urllib.parse.parse_qs(h.body().decode("utf-8", "replace"))
        code = f.get("code", [""])[0]
        if not self.auth_limiter.check(h.client_ip()):
            return self.link_page(code, "Too many attempts, try again in a minute")
        email = f.get("email", [""])[0].strip().lower()
        u = self.db.q("select * from users where email=?", (email,), one=True)
        if not u or not u["password"] or not check_password(f.get("password", [""])[0], u["password"]):
            return self.link_page(code, "Wrong email or password")
        return self.link_done(code, u["id"], "email")

    def oauth_identity(self, provider, code, callback, params):
        if provider == "github":
            t = http_json("https://github.com/login/oauth/access_token", {
                "client_id": self.cfg.github[0], "client_secret": self.cfg.github[1], "code": code, "redirect_uri": callback}, form=True)
            if "access_token" not in t:
                raise ValueError(t.get("error_description", "no access token"))
            auth = {"Authorization": "Bearer " + t["access_token"]}
            u = http_json("https://api.github.com/user", headers=auth)
            email = u.get("email")
            if not email:
                for e in http_json("https://api.github.com/user/emails", headers=auth):
                    if e.get("primary") and e.get("verified"):
                        email = e["email"]
            return str(u["id"]), (email or "").lower() or None, u.get("name") or u.get("login") or ""
        if provider == "google":
            t = http_json("https://oauth2.googleapis.com/token", {
                "client_id": self.cfg.google[0], "client_secret": self.cfg.google[1], "code": code,
                "redirect_uri": callback, "grant_type": "authorization_code"}, form=True)
            p = jwt_payload(t["id_token"])
            email = p.get("email") if p.get("email_verified") else None
            return p["sub"], (email or "").lower() or None, p.get("name", "")
        t = http_json("https://appleid.apple.com/auth/token", {
            "client_id": self.cfg.apple[0], "client_secret": apple_client_secret(self.cfg), "code": code,
            "redirect_uri": callback, "grant_type": "authorization_code"}, form=True)
        p = jwt_payload(t["id_token"])
        name = ""
        if "user" in params:  # sent only on the first sign-in
            try:
                n = json.loads(params["user"][0]).get("name", {})
                name = (n.get("firstName", "") + " " + n.get("lastName", "")).strip()
            except ValueError:
                pass
        return p["sub"], (p.get("email") or "").lower() or None, name

    # ---- settings sync

    def put_sync(self, h, u):
        raw = h.body()
        if len(raw) > self.cfg.max_sync_bytes:
            raise ApiError(413, "Settings are too large to sync")
        b = h.json()
        try:
            base = int(b.get("baseVersion", 0))
        except (TypeError, ValueError):
            raise ApiError(400, "baseVersion must be a number")
        now = int(time.time())
        data = json.dumps(b.get("data"), separators=(",", ":"))
        # Check and write in one statement: of two devices writing on the same version, one gets 409.
        if base == 0:
            changed = self.db.xr("insert into sync(user_id, version, data, updated) values (?,?,?,?) on conflict(user_id) do nothing",
                                 (u["id"], 1, data, now))
        else:
            changed = self.db.xr("update sync set version=version+1, data=?, updated=? where user_id=? and version=?",
                                 (data, now, u["id"], base))
        if changed != 1:
            cur = self.db.q("select version from sync where user_id=?", (u["id"],), one=True)
            raise ApiError(409, "Settings changed on another device (server version %d)" % (cur["version"] if cur else 0))
        return 200, {"version": base + 1, "updated": now}

    # ---- language servers

    def lsp_route(self, h, u, method, path):
        if not self.cfg.lsp_enabled:
            raise ApiError(404, "Language servers are disabled on this server")
        # Language servers execute code from the projects they open: admins only, unless DC_LSP=all.
        if self.cfg.lsp_access == "admin" and not u["admin"]:
            raise ApiError(403, "Language servers are for this server's admins only (DC_LSP=all allows everyone)")
        if path == "/v1/lsp" and method == "GET":
            return 200, {"languages": sorted(self.cfg.lsp.keys())}
        if path == "/v1/lsp/sessions" and method == "POST":
            lang = text_field(h.json(), "language")
            cmd = self.cfg.lsp.get(lang)
            if not cmd:
                raise ApiError(404, "No language server for %s on this server" % lang)
            sid = secrets.token_urlsafe(12)
            old = []
            with self.lsp_lock:
                # Count and reserve the slot together, so parallel requests can't exceed the limits.
                mine = [s for s in self.lsp_sessions.values() if s is not None and s.user_id == u["id"]]
                if len(mine) >= 8:
                    oldest = min(mine, key=lambda s: s.last_active)
                    old.append(oldest)
                    del self.lsp_sessions[oldest.id]
                if len(self.lsp_sessions) >= self.cfg.lsp_max:
                    raise ApiError(503, "The server runs as many language servers as it can; try again later")
                self.lsp_sessions[sid] = None  # reserved
            for s in old:
                s.kill()
            root = os.path.join(self.cfg.data_dir, "lsp", str(u["id"]), sid)
            os.makedirs(root, exist_ok=True)
            try:
                s = LspSession(sid, u["id"], lang, cmd, root)
            except OSError as e:
                with self.lsp_lock:
                    self.lsp_sessions.pop(sid, None)
                shutil.rmtree(root, ignore_errors=True)
                raise ApiError(500, "Can't start the %s language server: %s" % (lang, e))
            with self.lsp_lock:
                self.lsp_sessions[sid] = s
            import pathlib
            return 201, {"id": sid, "root": root, "rootUri": pathlib.Path(root).resolve().as_uri()}
        m = re.match(r"^/v1/lsp/sessions/([\w-]+)(/\w+)?$", path)
        if not m:
            raise ApiError(404, "Not found")
        with self.lsp_lock:
            s = self.lsp_sessions.get(m.group(1))
        if not s or s.user_id != u["id"]:
            raise ApiError(404, "No such language server session (it may have stopped)")
        action = m.group(2) or ""
        if action == "/send" and method == "POST":
            if not s.alive():
                raise ApiError(410, "The language server has exited")
            s.send(h.body())
            return 200, {"ok": True}
        if action == "/files" and method == "PUT":
            b = h.json()
            rel = text_field(b, "path").replace("\\", "/").lstrip("/")
            text = text_field(b, "text")
            target = os.path.realpath(os.path.join(s.root, rel))
            if not target.startswith(os.path.realpath(s.root) + os.sep):
                raise ApiError(400, "Bad path")
            # A session's private folder is a copy of the files the app opens, not storage.
            if s.files >= 2000 or s.bytes + len(text) > 64 * 1024 * 1024:
                raise ApiError(413, "Too many files for this language server session")
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="") as f:
                f.write(text)
            s.files += 1
            s.bytes += len(text)
            return 200, {"ok": True}
        if action == "/events" and method == "GET":
            if s.listeners >= 4:
                raise ApiError(429, "Too many connections to this language server")
            h.start_sse()
            s.listeners += 1
            try:
                while True:
                    items = s.take(15)
                    s.last_active = time.time()
                    if not items:
                        h.wfile.write(b": ping\n\n")
                        h.wfile.flush()
                        if not s.alive() and s.exit_code is not None:
                            break
                        continue
                    for msg in items:
                        h.wfile.write(("data: " + msg.replace("\n", "\ndata: ") + "\n\n").encode("utf-8"))
                    h.wfile.flush()
                    if any('"droidcodology": "exit"' in i for i in items):
                        break
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                s.listeners -= 1
            return None, None
        if action == "" and method == "DELETE":
            with self.lsp_lock:
                self.lsp_sessions.pop(s.id, None)
            s.kill()
            return 200, {"ok": True}
        raise ApiError(404, "Not found")

    # ---- AI proxy

    def models(self):
        out = []
        for p in self.cfg.providers:
            for m in p.get("models", []):
                out.append({"id": "%s/%s" % (p["id"], m), "provider": p["id"], "model": m})
        return out

    def ai_chat(self, h, u):
        b = h.json()
        model_id = b.get("model") or (self.models()[0]["id"] if self.models() else "")
        if "/" not in model_id:
            raise ApiError(400, "Unknown model")
        pid, model = model_id.split("/", 1)
        provider = next((p for p in self.cfg.providers if p["id"] == pid and model in p.get("models", [])), None)
        if not provider:
            raise ApiError(400, "Unknown model %s" % model_id)
        day = time.strftime("%Y-%m-%d")
        used = self.db.q("select tokens from ai_usage where user_id=? and day=?", (u["id"], day), one=True)
        if self.cfg.ai_daily_tokens > 0 and used and used["tokens"] >= self.cfg.ai_daily_tokens and not u["admin"]:
            raise ApiError(429, "Daily AI limit reached")
        raw_messages = b.get("messages", [])
        if not isinstance(raw_messages, list) or not all(isinstance(m, dict) for m in raw_messages):
            raise ApiError(400, "messages must be a list of objects")
        messages = [{"role": "assistant" if m.get("role") == "assistant" else "user", "content": str(m.get("content", ""))}
                    for m in raw_messages if m.get("content")]
        if not messages:
            raise ApiError(400, "No messages")
        stream = bool(b.get("stream", True))
        # The answer length is the server's call: a client can't ask for more than DC_AI_MAX_TOKENS.
        try:
            max_tokens = min(int(b.get("maxTokens") or self.cfg.ai_max_tokens), self.cfg.ai_max_tokens)
        except (TypeError, ValueError):
            raise ApiError(400, "maxTokens must be a number")
        system = b.get("system")
        if system is not None and not isinstance(system, str):
            raise ApiError(400, "system must be text")
        # Without a usage report (a stream cut short, a provider that doesn't send one) the request
        # is still counted: roughly 4 characters per token, prompt and answer.
        prompt_estimate = (sum(len(m["content"]) for m in messages) + len(system or "")) // 4
        url, headers, body = ai_request(provider, model, messages, system, max_tokens, b.get("temperature"), stream)
        headers = dict(headers, **{"Content-Type": "application/json", "User-Agent": "droid_codology-server/" + VERSION})
        req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="POST")
        try:
            resp = urllib.request.urlopen(req, timeout=300)
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:500]
            raise ApiError(502, "Provider error %d: %s" % (e.code, detail))
        except urllib.error.URLError as e:
            raise ApiError(502, "Provider unreachable: %s" % e.reason)
        kind = provider.get("kind", "openai")
        if not stream:
            with resp:
                text, tokens = ai_parse_full(kind, json.loads(resp.read().decode()))
            self.add_usage(u["id"], day, tokens or prompt_estimate + len(text or "") // 4)
            return 200, {"text": text, "usage": tokens}
        # Stream as server-sent events: data: {"delta": "..."} ... data: {"done": true, "usage": n}
        h.start_sse()
        tokens = 0
        streamed = 0
        try:
            with resp:
                for line in resp:
                    line = line.decode("utf-8", "replace").strip()
                    if not line.startswith("data:"):
                        continue
                    text, usage, finished = ai_parse_event(kind, line[5:].strip())
                    if usage:
                        tokens = max(tokens, usage) if kind != "anthropic" else tokens + usage
                    if text:
                        streamed += len(text)
                        h.sse({"delta": text})
                    if finished:
                        break
            h.sse({"done": True, "usage": tokens})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            try:
                h.sse({"error": str(e)})
            except OSError:
                pass
        self.add_usage(u["id"], day, max(tokens, prompt_estimate + streamed // 4))
        return None, None

    def add_usage(self, uid, day, n):
        self.db.x("insert into ai_usage(user_id, day, tokens) values (?,?,?) "
                  "on conflict(user_id, day) do update set tokens = tokens + excluded.tokens", (uid, day, int(n)))


def make_handler(app):
    class Handler(BaseHTTPRequestHandler):
        server_version = "droid_codology/" + VERSION
        protocol_version = "HTTP/1.1"
        # A client that stops sending gives its thread back after this long (event streams send a ping every 15 s).
        timeout = 120
        _body = None

        def setup(self):
            super().setup()
            # With built-in TLS the handshake happens here, in this connection's thread and under its
            # timeout, not in accept() where one silent client would block everybody.
            if isinstance(self.request, ssl.SSLSocket):
                self.request.do_handshake()

        def log_message(self, fmt, *args):
            sys.stderr.write("%s %s\n" % (self.client_ip(), fmt % args))

        def client_ip(self):
            peer = self.client_address[0]
            # X-Forwarded-For is believed only from proxies listed in DC_TRUSTED_PROXY; from anyone
            # else it would let a client pick its own rate-limit key. The right-most address that
            # isn't a trusted proxy is the client.
            headers = getattr(self, "headers", None)  # a request that couldn't be parsed has none
            if peer in app.cfg.trusted_proxies and headers is not None and headers.get("X-Forwarded-For"):
                hops = [x.strip() for x in headers.get("X-Forwarded-For").split(",") if x.strip()]
                for ip in reversed(hops):
                    if ip not in app.cfg.trusted_proxies:
                        return ip
            return peer

        def cookie(self, name):
            import http.cookies
            jar = http.cookies.SimpleCookie()
            try:
                jar.load(self.headers.get("Cookie") or "")
            except http.cookies.CookieError:
                return None
            return jar[name].value if name in jar else None

        def handle_one_request(self):
            # An HTTPS client talking to this plain-HTTP port sends a TLS handshake (first byte 0x16):
            # tell the person running the server how to fix the address instead of logging binary junk.
            if not (app.cfg.tls_cert and app.cfg.tls_key):
                try:
                    first = self.rfile.peek(1)[:1]
                except (OSError, ValueError):
                    self.close_connection = True
                    return
                if first == b"\x16":
                    app.https_on_http(self.client_address[0])
                    self.close_connection = True
                    return
            super().handle_one_request()

        def body(self):
            if self._body is None:
                limit = app.cfg.max_sync_bytes + 65536
                if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
                    # Android's HttpURLConnection may send request bodies chunked.
                    parts, total = [], 0
                    while True:
                        size_line = self.rfile.readline(1024)
                        if not size_line:
                            break
                        hex_size = size_line.split(b";")[0].strip() or b"0"
                        if not re.match(rb"^[0-9A-Fa-f]{1,8}$", hex_size):
                            self.close_connection = True
                            raise ApiError(400, "Bad chunked body")
                        size = int(hex_size, 16)
                        if size == 0:
                            while self.rfile.readline(1024) not in (b"\r\n", b"\n", b""):
                                pass  # trailers
                            break
                        total += size
                        if total > limit:
                            self.close_connection = True
                            raise ApiError(413, "Request too large")
                        parts.append(self.rfile.read(size))
                        self.rfile.readline(1024)  # CRLF after the chunk
                    self._body = b"".join(parts)
                else:
                    cl = (self.headers.get("Content-Length") or "0").strip()
                    if not cl.isdigit():
                        # Negative or malformed: read(-1) would read until the client hangs up.
                        self.close_connection = True
                        raise ApiError(400, "Bad Content-Length")
                    n = int(cl)
                    if n > limit:
                        self.close_connection = True
                        raise ApiError(413, "Request too large")
                    self._body = self.rfile.read(n) if n else b""
            return self._body

        def finish_body(self):
            """Consumes a body the route didn't read, so it can't be mistaken for the next request."""
            if self._body is None and (self.headers.get("Content-Length") or self.headers.get("Transfer-Encoding")):
                try:
                    self.body()
                except Exception:
                    self.close_connection = True

        def json(self):
            try:
                v = json.loads(self.body().decode() or "{}")
            except ValueError:
                raise ApiError(400, "Invalid JSON")
            if not isinstance(v, dict):
                raise ApiError(400, "Expected a JSON object")
            return v

        def send_json(self, status, obj):
            data = json.dumps(obj).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def start_sse(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.send_header("X-Accel-Buffering", "no")
            self.end_headers()
            self.close_connection = True

        def sse(self, obj):
            self.wfile.write(("data: " + json.dumps(obj) + "\n\n").encode())
            self.wfile.flush()

        def handle_any(self, method):
            u = urllib.parse.urlsplit(self.path)
            self._body = None
            try:
                self.respond(method, u)
            finally:
                self.finish_body()

        def respond(self, method, u):
            try:
                status, payload = app.route(self, method, u.path.rstrip("/") or "/", urllib.parse.parse_qs(u.query))
                if status is None:
                    return
                if status == 302:
                    url, extra = payload if isinstance(payload, tuple) else (payload, {})
                    self.send_response(302)
                    self.send_header("Location", url)
                    for k, v in extra.items():
                        self.send_header(k, v)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if isinstance(payload, Html):
                    data = payload.encode()
                    self.send_response(status)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.send_header("Content-Length", str(len(data)))
                    self.send_header("Cache-Control", "no-store")
                    # Sign-in pages must not be framed by other sites (clickjacking).
                    self.send_header("X-Frame-Options", "DENY")
                    self.send_header("Content-Security-Policy", "frame-ancestors 'none'")
                    self.end_headers()
                    self.wfile.write(data)
                    return
                self.send_json(status, payload)
            except ApiError as e:
                self.send_json(e.status, {"error": e.message})
            except Exception as e:  # pragma: no cover - last resort
                sys.stderr.write("error: %r\n" % e)
                # The request stream may be out of step now: don't reuse the connection.
                self.close_connection = True
                try:
                    self.send_json(500, {"error": "Internal server error"})
                except OSError:
                    pass

        def do_GET(self):
            self.handle_any("GET")

        def do_POST(self):
            self.handle_any("POST")

        def do_PUT(self):
            self.handle_any("PUT")

        def do_PATCH(self):
            self.handle_any("PATCH")

        def do_DELETE(self):
            self.handle_any("DELETE")

    return Handler


class Server(ThreadingHTTPServer):
    # On Windows SO_REUSEADDR lets a second server bind the same port silently and steal requests.
    allow_reuse_address = os.name != "nt"

    def handle_error(self, request, client_address):
        # Clients that hang up, time out or fail the TLS handshake are normal on the internet.
        e = sys.exc_info()[1]
        if isinstance(e, (ssl.SSLError, ConnectionError, TimeoutError, socket.timeout)):
            return
        super().handle_error(request, client_address)


def local_addresses():
    """This machine's LAN addresses, for telling the user what to type in the app."""
    found = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("10.255.255.255", 1))  # no packet is sent; this just picks the outgoing interface
            found.append(s.getsockname()[0])
        finally:
            s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith(("127.", "169.254.")) and ip not in found:
                found.append(ip)
    except OSError:
        pass
    return [ip for ip in found if not ip.startswith("127.")]


class Tee:
    """Writes to the console and to the log file, so errors stay readable after the window closes."""

    def __init__(self, stream, log):
        self.stream, self.log = stream, log

    def write(self, s):
        try:
            self.stream.write(s)
            self.stream.flush()
        except UnicodeEncodeError:
            self.stream.write(s.encode("ascii", "replace").decode("ascii"))
        except (OSError, ValueError, AttributeError):
            pass  # no console (pythonw)
        try:
            self.log.write(s)
            self.log.flush()
        except (OSError, ValueError):
            pass
        return len(s)

    def flush(self):
        try:
            self.stream.flush()
        except (OSError, ValueError, AttributeError):
            pass

    def isatty(self):
        return False


def serve(cfg):
    os.makedirs(cfg.data_dir, exist_ok=True)
    log = open(os.path.join(cfg.data_dir, "server.log"), "a", encoding="utf-8")
    log.write("\n==== %s ====\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
    sys.stdout, sys.stderr = Tee(sys.stdout, log), Tee(sys.stderr, log)
    app = App(cfg)
    try:
        httpd = Server((cfg.host, cfg.port), make_handler(app))
    except OSError as e:
        if e.errno in (98, 48, 10048) or getattr(e, "winerror", None) == 10048:  # address in use (Linux, macOS, Windows)
            sys.exit("Port %d is already in use. Probably the server is already running (open http://127.0.0.1:%d/ to "
                     "check) or another program uses this port. Close it, or start on another port, for example:\n"
                     "  Windows:  set DC_PORT=8081  and start again\n"
                     "  Linux/Termux:  DC_PORT=8081 sh start-server.sh" % (cfg.port, cfg.port))
        if e.errno in (13, 10013) or getattr(e, "winerror", None) == 10013:
            sys.exit("No permission to use port %d. Use a port above 1024, for example: DC_PORT=8081" % cfg.port)
        raise
    httpd.daemon_threads = True
    scheme = "http"
    if cfg.tls_cert and cfg.tls_key:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(cfg.tls_cert, cfg.tls_key)
        # The handshake runs later, in each connection's own thread (Handler.setup).
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True, do_handshake_on_connect=False)
        scheme = "https"
    print("%s %s listening on %s://%s:%d (public URL %s)" % (cfg.name, VERSION, scheme, cfg.host, cfg.port, cfg.public_url))
    print("sign-in providers: %s; AI models: %s" % (", ".join(["email"] + cfg.oauth_providers()),
                                                    ", ".join(m["id"] for m in app.models()) or "none"))
    ips = local_addresses()
    if ips and cfg.host in ("0.0.0.0", "::", ""):
        print("In the app, set the server address to one of: %s"
              % ", ".join("%s://%s:%d" % (scheme, ip, cfg.port) for ip in ips))
    print("Keep this window open while you use the server. Log: %s" % os.path.join(cfg.data_dir, "server.log"))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    return httpd


def main(argv):
    cfg = Config()
    if len(argv) > 1 and argv[1] == "create-user":
        import getpass
        email = argv[2]
        pw = getpass.getpass("Password for %s: " % email)
        u = App(cfg).create_user(email, password=pw, admin="--admin" in argv)
        print("created user %d (%s)%s" % (u["id"], u["email"], " [admin]" if u["admin"] else ""))
        return
    if len(argv) > 1 and argv[1] == "create-key":
        app = App(cfg)
        u = app.db.q("select * from users where email=?", (argv[2].lower(),), one=True)
        if not u:
            sys.exit("no such user")
        print(app.issue_token(u["id"], "key", argv[3] if len(argv) > 3 else "cli"))
        return
    serve(cfg)


def pause_if_window_closes():
    """Windows: when the server stops with an error, keep the window open so the message can be read
    (a double-clicked .py closes its window at once). start-server.bat pauses by itself."""
    if os.name != "nt" or os.environ.get("DC_NO_PAUSE"):
        return
    try:
        input("\nThe server has stopped. Press Enter to close this window.")
    except (EOFError, OSError, RuntimeError):
        pass


if __name__ == "__main__":
    try:
        main(sys.argv)
    except KeyboardInterrupt:
        pass
    except SystemExit as e:
        if e.code not in (None, 0):
            sys.stderr.write("%s\n" % e.code if isinstance(e.code, str) else "")
            pause_if_window_closes()
            sys.exit(1)
    except BaseException:
        import traceback
        traceback.print_exc()
        pause_if_window_closes()
        sys.exit(1)
