"""Tests for droidcodology_server.py: python3 -m unittest test_server (standard library only)."""
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import droidcodology_server as dc


class FakeProvider(BaseHTTPRequestHandler):
    """Speaks just enough OpenAI and Anthropic streaming to test the proxy."""
    protocol_version = "HTTP/1.1"
    last_body = None

    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n))
        FakeProvider.last_body = body
        FakeProvider.last_headers = dict(self.headers)
        if self.path.endswith("/v1/messages"):
            events = [
                {"type": "message_start", "message": {"usage": {"input_tokens": 5}}},
                {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Hel"}},
                {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "lo"}},
                {"type": "message_delta", "usage": {"output_tokens": 2}},
                {"type": "message_stop"},
            ]
            lines = ["event: x\ndata: %s\n\n" % json.dumps(e) for e in events]
        else:
            if not body.get("stream"):
                data = json.dumps({"choices": [{"message": {"content": "full answer"}}], "usage": {"total_tokens": 9}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            chunks = [{"choices": [{"delta": {"content": "Hi "}}]}, {"choices": [{"delta": {"content": "there"}}]},
                      {"choices": [], "usage": {"total_tokens": 12}}]
            lines = ["data: %s\n\n" % json.dumps(c) for c in chunks] + ["data: [DONE]\n\n"]
        data = "".join(lines).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.fake = ThreadingHTTPServer(("127.0.0.1", 0), FakeProvider)
        threading.Thread(target=cls.fake.serve_forever, daemon=True).start()
        fake_url = "http://127.0.0.1:%d" % cls.fake.server_address[1]
        ai = os.path.join(cls.tmp, "ai.json")
        with open(ai, "w") as f:
            json.dump([{"id": "oa", "kind": "openai", "baseUrl": fake_url + "/v1", "apiKey": "$FAKE_KEY", "models": ["m1"]},
                       {"id": "an", "kind": "anthropic", "baseUrl": fake_url, "apiKey": "k2", "models": ["c1"]}], f)
        lsp = os.path.join(cls.tmp, "lsp.json")
        with open(lsp, "w") as f:
            json.dump({"python": [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "fake_lsp.py")]}, f)
        os.environ.update({"DC_DATA_DIR": os.path.join(cls.tmp, "data"), "DC_AI_CONFIG": ai, "FAKE_KEY": "secret-key",
                           "DC_LSP_CONFIG": lsp, "DC_LSP": "all",
                           "DC_GITHUB_CLIENT_ID": "gh-id", "DC_GITHUB_CLIENT_SECRET": "gh-secret", "DC_PORT": "0",
                           "DC_PUBLIC_URL": "https://dc.example.com"})
        cls.cfg = dc.Config()
        cls.app = dc.App(cls.cfg)
        cls.app.auth_limiter = dc.RateLimiter(1000)  # the suite signs up more often than the real limit allows
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), dc.make_handler(cls.app))
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        cls.base = "http://127.0.0.1:%d" % cls.httpd.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.fake.shutdown()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def call(self, method, path, body=None, token=None, raw=False):
        h = {"Content-Type": "application/json"}
        if token:
            h["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode() if body is not None else None,
                                     headers=h, method=method)
        try:
            with urllib.request.urlopen(req) as r:
                data = r.read()
                return r.status, data if raw else json.loads(data or b"null")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"null")

    def register(self, email):
        s, r = self.call("POST", "/v1/auth/register", {"email": email, "password": "correct horse", "device": "test"})
        self.assertEqual(201, s, r)
        return r["token"]

    def test_info(self):
        s, r = self.call("GET", "/v1/info")
        self.assertEqual(200, s)
        self.assertEqual(["github"], r["oauth"])
        self.assertEqual(["oa/m1", "an/c1"], [m["id"] for m in r["models"]])

    def test_register_login_me(self):
        t = self.register("Alice@Example.com")
        s, me = self.call("GET", "/v1/me", token=t)
        self.assertEqual("alice@example.com", me["email"])
        s, r = self.call("POST", "/v1/auth/register", {"email": "alice@example.com", "password": "another one"})
        self.assertEqual(409, s)
        s, r = self.call("POST", "/v1/auth/login", {"email": "alice@example.com", "password": "wrong password"})
        self.assertEqual(401, s)
        s, r = self.call("POST", "/v1/auth/login", {"email": "ALICE@example.com", "password": "correct horse"})
        self.assertEqual(200, s)
        s, _ = self.call("POST", "/v1/auth/logout", token=r["token"])
        self.assertEqual(200, s)
        s, _ = self.call("GET", "/v1/me", token=r["token"])
        self.assertEqual(401, s)
        s, _ = self.call("GET", "/v1/me", token="dcs_bogus")
        self.assertEqual(401, s)

    def test_device_sign_in(self):
        self.register("device@example.com")
        s, d = self.call("POST", "/v1/auth/device/start", {"device": "Old phone"})
        self.assertEqual(200, s, d)
        self.assertRegex(d["user_code"], r"^[A-Z]{4}-[A-Z]{4}$")
        self.assertEqual("https://dc.example.com/link", d["verification_url"])
        s, r = self.call("POST", "/v1/auth/device/poll", {"device_code": d["device_code"]})
        self.assertEqual((200, {"pending": True}), (s, r))
        # The page for the code offers the server's OAuth providers and email.
        with urllib.request.urlopen(self.base + "/link?code=" + d["user_code"].lower()) as resp:
            page = resp.read().decode()
        self.assertIn("/v1/auth/oauth/github/start?link=", page)
        self.assertIn("/link/email", page)
        with urllib.request.urlopen(self.base + "/link?code=ZZZZ-ZZZZ") as resp:
            self.assertIn("Unknown or expired code", resp.read().decode())

        def post_form(fields):
            req = urllib.request.Request(self.base + "/link/email", data=urllib.parse.urlencode(fields).encode(), method="POST")
            with urllib.request.urlopen(req) as resp:
                return resp.read().decode()

        self.assertIn("Wrong email or password", post_form({"code": d["user_code"], "email": "device@example.com", "password": "nope"}))
        self.assertIn("now signed in", post_form({"code": d["user_code"], "email": "device@example.com", "password": "correct horse"}))
        s, r = self.call("POST", "/v1/auth/device/poll", {"device_code": d["device_code"]})
        self.assertEqual(200, s, r)
        self.assertEqual("device@example.com", r["user"]["email"])
        s, me = self.call("GET", "/v1/me", token=r["token"])
        self.assertEqual("device@example.com", me["email"])
        # A code works once.
        s, r = self.call("POST", "/v1/auth/device/poll", {"device_code": d["device_code"]})
        self.assertEqual(410, s)
        # OAuth started for a code carries it in the signed state.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        s2, d2 = self.call("POST", "/v1/auth/device/start", {})
        try:
            urllib.request.build_opener(NoRedirect).open(self.base + "/v1/auth/oauth/github/start?link=" + d2["user_code"])
            self.fail("expected a redirect")
        except urllib.error.HTTPError as e:
            loc = e.headers["Location"]
        state = urllib.parse.parse_qs(urllib.parse.urlsplit(loc).query)["state"][0]
        self.assertEqual(d2["user_code"], dc.verify_state(self.cfg.secret, state)["d"])

    def test_redirect_check(self):
        ok = ["droid-codology://auth", "http://127.0.0.1:5000/cb", "http://localhost:8080/x?y=1", "http://localhost"]
        bad = ["http://localhost.evil.com/x", "http://127.0.0.1.evil.com", "http://127.0.0.1@evil.com/",
               "http://localhost:80@evil.com", "https://evil.com", "http://localhost/\r\nSet-Cookie:x=1",
               "http://localhost/é", "javascript:alert(1)", "", "http://localhost:abc/"]
        for r in ok:
            self.assertTrue(dc.allowed_redirect(r), r)
        for r in bad:
            self.assertFalse(dc.allowed_redirect(r), r)

    def raw_request(self, data):
        import socket
        s = socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=5)
        try:
            s.sendall(data)
            return s.recv(4096).decode("latin-1")
        finally:
            s.close()

    def test_bad_lengths(self):
        r = self.raw_request(b"POST /v1/auth/login HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n")
        self.assertIn(" 400 ", r.split("\r\n")[0])
        r = self.raw_request(b"POST /v1/auth/login HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n-5\r\nabc\r\n0\r\n\r\n")
        self.assertIn(" 400 ", r.split("\r\n")[0])

    def test_sync_race(self):
        t = self.register("race@example.com")
        s, r = self.call("PUT", "/v1/sync", {"baseVersion": 0, "data": {"a": 1}}, token=t)
        self.assertEqual(200, s)
        s, r = self.call("PUT", "/v1/sync", {"baseVersion": 0, "data": {"a": 2}}, token=t)
        self.assertEqual(409, s)  # the first write exists: a second "first" write must not win
        s, r = self.call("PUT", "/v1/sync", {"baseVersion": "x", "data": {}}, token=t)
        self.assertEqual(400, s)

    def test_oauth_needs_starting_browser(self):
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        try:
            urllib.request.build_opener(NoRedirect).open(self.base + "/v1/auth/oauth/github/start")
            self.fail("expected a redirect")
        except urllib.error.HTTPError as e:
            loc, cookie = e.headers["Location"], e.headers["Set-Cookie"]
        self.assertIn("dc_oauth=", cookie)
        state = urllib.parse.parse_qs(urllib.parse.urlsplit(loc).query)["state"][0]
        # The callback from another browser (no cookie) is refused before GitHub is even asked.
        s, r = self.call("GET", "/v1/auth/oauth/github/callback?state=%s&code=x" % urllib.parse.quote(state))
        self.assertEqual(400, s)
        self.assertIn("browser", r["error"])
        s, r = self.call("GET", "/v1/auth/oauth/github/start?redirect=" + urllib.parse.quote("http://localhost.evil.com/"))
        self.assertEqual(400, s)

    def test_oauth_does_not_take_over_password_accounts(self):
        self.register("owner@example.com")
        with self.assertRaises(dc.ApiError):
            self.app.identity_user("google", "g-123", "owner@example.com", "Owner")
        uid = self.app.identity_user("google", "g-456", "fresh@example.com", "Fresh")
        self.assertEqual(uid, self.app.identity_user("google", "g-456", "fresh@example.com", "Fresh"))
        # A second verified provider with the same address joins the same account.
        self.assertEqual(uid, self.app.identity_user("github", "77", "fresh@example.com", "Fresh"))

    def test_lsp_admin_only_by_default(self):
        t = self.register("notadmin@example.com")
        self.cfg.lsp_access = "admin"
        try:
            s, r = self.call("GET", "/v1/lsp", token=t)
            self.assertEqual(403, s)
        finally:
            self.cfg.lsp_access = "all"
        self.assertNotIn("DC_SECRET", dc.lsp_environment())
        self.assertNotIn("FAKE_KEY", dc.lsp_environment())

    def test_validation(self):
        s, r = self.call("POST", "/v1/auth/register", {"email": "not-an-email", "password": "longenough"})
        self.assertEqual(400, s)
        s, r = self.call("POST", "/v1/auth/register", {"email": "short@example.com", "password": "short"})
        self.assertEqual(400, s)

    def test_api_keys(self):
        t = self.register("keys@example.com")
        s, k = self.call("POST", "/v1/keys", {"name": "laptop"}, token=t)
        self.assertEqual(201, s)
        self.assertTrue(k["key"].startswith("dck_"))
        s, me = self.call("GET", "/v1/me", token=k["key"])
        self.assertEqual("keys@example.com", me["email"])
        s, keys = self.call("GET", "/v1/keys", token=t)
        self.assertEqual(["laptop"], [x["name"] for x in keys])
        self.call("DELETE", "/v1/keys/%d" % keys[0]["id"], token=t)
        s, _ = self.call("GET", "/v1/me", token=k["key"])
        self.assertEqual(401, s)

    def test_sync_versions(self):
        t = self.register("sync@example.com")
        s, r = self.call("GET", "/v1/sync", token=t)
        self.assertEqual(0, r["version"])
        s, r = self.call("PUT", "/v1/sync", {"baseVersion": 0, "data": {"editor.fontSize": 14}}, token=t)
        self.assertEqual((200, 1), (s, r["version"]))
        s, r = self.call("PUT", "/v1/sync", {"baseVersion": 0, "data": {"editor.fontSize": 99}}, token=t)
        self.assertEqual(409, s)
        s, r = self.call("PUT", "/v1/sync", {"baseVersion": 1, "data": {"editor.fontSize": 16}}, token=t)
        self.assertEqual(2, r["version"])
        s, r = self.call("GET", "/v1/sync", token=t)
        self.assertEqual({"editor.fontSize": 16}, r["data"])

    def test_chunked_body_and_keep_alive(self):
        import http.client
        t = self.register("chunked@example.com")
        port = self.httpd.server_address[1]
        c = http.client.HTTPConnection("127.0.0.1", port)
        body = json.dumps({"baseVersion": 0, "data": {"k": [1, 2, 3]}}).encode()

        def chunks():
            yield body[:10]
            yield body[10:]
        c.request("PUT", "/v1/sync", body=chunks(), headers={"Authorization": "Bearer " + t,
                                                              "Content-Type": "application/json"}, encode_chunked=True)
        r = c.getresponse()
        self.assertEqual(200, r.status, r.read())
        r.read()
        # A body the route ignores (logout of a bogus token -> 401) must not leak into the next request.
        c.request("POST", "/v1/auth/logout", body=b'{"ignored": true}', headers={"Authorization": "Bearer dcs_nope"})
        r = c.getresponse()
        self.assertEqual(401, r.status)
        r.read()
        c.request("GET", "/v1/sync", headers={"Authorization": "Bearer " + t})
        r = c.getresponse()
        self.assertEqual({"k": [1, 2, 3]}, json.loads(r.read())["data"])
        c.close()

    def test_ai_openai_stream(self):
        t = self.register("ai@example.com")
        s, raw = self.call("POST", "/v1/ai/chat", {"model": "oa/m1", "messages": [{"role": "user", "content": "hey"}],
                                                   "system": "be brief"}, token=t, raw=True)
        events = [json.loads(l[6:]) for l in raw.decode().split("\n") if l.startswith("data: ")]
        self.assertEqual("Hi there", "".join(e.get("delta", "") for e in events))
        self.assertEqual({"done": True, "usage": 12}, events[-1])
        self.assertEqual("Bearer secret-key", FakeProvider.last_headers.get("Authorization"))
        self.assertEqual({"role": "system", "content": "be brief"}, FakeProvider.last_body["messages"][0])
        s, u = self.call("GET", "/v1/ai/usage", token=t)
        self.assertEqual(12, u["today"])

    def test_ai_anthropic_stream_and_full(self):
        t = self.register("ai2@example.com")
        s, raw = self.call("POST", "/v1/ai/chat", {"model": "an/c1", "messages": [{"role": "user", "content": "hey"}],
                                                   "system": "sys"}, token=t, raw=True)
        events = [json.loads(l[6:]) for l in raw.decode().split("\n") if l.startswith("data: ")]
        self.assertEqual("Hello", "".join(e.get("delta", "") for e in events))
        self.assertEqual(7, events[-1]["usage"])
        self.assertEqual("sys", FakeProvider.last_body["system"])
        s, r = self.call("POST", "/v1/ai/chat", {"model": "oa/m1", "stream": False,
                                                 "messages": [{"role": "user", "content": "q"}]}, token=t)
        self.assertEqual({"text": "full answer", "usage": 9}, r)
        s, r = self.call("POST", "/v1/ai/chat", {"model": "zz/none", "messages": [{"role": "user", "content": "q"}]}, token=t)
        self.assertEqual(400, s)

    def test_ai_requires_auth(self):
        s, r = self.call("POST", "/v1/ai/chat", {"model": "oa/m1", "messages": []})
        self.assertEqual(401, s)

    def test_oauth_start_and_state(self):
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a):
                return None
        opener = urllib.request.build_opener(NoRedirect)
        try:
            opener.open(self.base + "/v1/auth/oauth/github/start?redirect=" + urllib.parse.quote("droid-codology://auth"))
            self.fail("expected redirect")
        except urllib.error.HTTPError as e:
            self.assertEqual(302, e.code)
            loc = e.headers["Location"]
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(loc).query)
        self.assertTrue(loc.startswith("https://github.com/login/oauth/authorize"))
        self.assertEqual("https://dc.example.com/v1/auth/oauth/github/callback", q["redirect_uri"][0])
        st = dc.verify_state(self.cfg.secret, q["state"][0])
        self.assertEqual("droid-codology://auth", st["r"])
        self.assertIsNone(dc.verify_state(self.cfg.secret, q["state"][0][:-2] + "AA"))
        s, r = self.call("GET", "/v1/auth/oauth/github/start?redirect=" + urllib.parse.quote("https://evil.example"))
        self.assertEqual(400, s)
        s, r = self.call("GET", "/v1/auth/oauth/google/start")
        self.assertEqual(404, s)

    def test_lsp_bridge(self):
        import http.client
        t = self.register("lsp@example.com")
        s, info = self.call("GET", "/v1/lsp", token=t)
        self.assertEqual(["python"], info["languages"])
        s, sess = self.call("POST", "/v1/lsp/sessions", {"language": "python"}, token=t)
        self.assertEqual(201, s, sess)
        sid, root_uri = sess["id"], sess["rootUri"]
        s, _ = self.call("PUT", "/v1/lsp/sessions/%s/files" % sid, {"path": "pkg/a.py", "text": "x = 1\n"}, token=t)
        self.assertEqual(200, s)
        self.assertTrue(os.path.isfile(os.path.join(sess["root"], "pkg", "a.py")))
        s, r = self.call("PUT", "/v1/lsp/sessions/%s/files" % sid, {"path": "../escape.py", "text": ""}, token=t)
        self.assertEqual(400, s)
        # Event stream on its own connection.
        port = self.httpd.server_address[1]
        ev = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        ev.request("GET", "/v1/lsp/sessions/%s/events" % sid, headers={"Authorization": "Bearer " + t})
        resp = ev.getresponse()
        self.assertEqual(200, resp.status)

        def read_event():
            data = []
            while True:
                line = resp.fp.readline().decode()
                if line.startswith("data: "):
                    data.append(line[6:].rstrip("\n"))
                elif line == "\n" and data:
                    return json.loads("\n".join(data))

        def send(obj):
            st, _ = self.call("POST", "/v1/lsp/sessions/%s/send" % sid, obj, token=t)
            self.assertEqual(200, st)

        send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"rootUri": root_uri, "capabilities": {}}})
        self.assertTrue(read_event()["result"]["capabilities"]["hoverProvider"])
        uri = root_uri + "/pkg/a.py"
        send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument": {
            "uri": uri, "languageId": "python", "version": 1, "text": "x = 1\n# TODO: more\n"}}})
        d = read_event()
        self.assertEqual("textDocument/publishDiagnostics", d["method"])
        self.assertEqual(1, d["params"]["diagnostics"][0]["range"]["start"]["line"])
        send({"jsonrpc": "2.0", "id": 2, "method": "textDocument/completion", "params": {
            "textDocument": {"uri": uri}, "position": {"line": 0, "character": 1}}})
        self.assertEqual("fake_function", read_event()["result"]["items"][0]["label"])
        s, _ = self.call("DELETE", "/v1/lsp/sessions/%s" % sid, token=t)
        self.assertEqual(200, s)
        ev.close()
        s, _ = self.call("POST", "/v1/lsp/sessions/%s/send" % sid, {"x": 1}, token=t)
        self.assertEqual(404, s)
        s, _ = self.call("POST", "/v1/lsp/sessions", {"language": "cobol"}, token=t)
        self.assertEqual(404, s)

    def test_password_hash(self):
        h = dc.hash_password("pässword")
        self.assertTrue(dc.check_password("pässword", h))
        self.assertFalse(dc.check_password("password", h))

    def test_es256(self):
        d = 0xc9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721  # RFC 6979 A.2.5 key
        pub = dc._ec_mul(d, dc.P256_G)
        self.assertEqual(0x60fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6, pub[0])
        sig = dc.es256_sign(d, b"sample")
        self.assertTrue(dc.es256_verify(pub, b"sample", sig))
        self.assertFalse(dc.es256_verify(pub, b"other", sig))

    def test_p8_parse(self):
        # PKCS#8 wrapping of the RFC 6979 key above, laid out as in an Apple .p8 file (public key omitted).
        import base64
        d = 0xc9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721
        ec = bytes([0x30, 0x25, 0x02, 0x01, 0x01, 0x04, 0x20]) + d.to_bytes(32, "big")
        alg = bytes.fromhex("301306072a8648ce3d020106082a8648ce3d030107")
        body = bytes([0x02, 0x01, 0x00]) + alg + bytes([0x04, len(ec)]) + ec
        der = bytes([0x30, len(body)]) + body
        pem = "-----BEGIN PRIVATE KEY-----\n" + base64.b64encode(der).decode() + "\n-----END PRIVATE KEY-----\n"
        self.assertEqual(d, dc.p8_private_key(pem))


if __name__ == "__main__":
    unittest.main()
