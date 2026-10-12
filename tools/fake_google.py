"""A stand-in for Google sign-in and the Drive app data folder, for testing the app's
"Sign in with Google" without a server or a real OAuth client.

    python tools/fake_google.py 9300
    adb shell am start -n com.disgusty.droid_codology/.MainActivity -e googleTestBase http://10.0.2.2:9300

Consent is automatic. PKCE is checked like Google does. GET /state shows the stored settings
file; POST /bump (JSON body) replaces it as if another device had synced.
"""
import base64
import hashlib
import json
import re
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

codes = {}      # code -> challenge
tokens = set()  # valid access tokens
files = {}      # id -> {"name", "version", "content"}
devices = {}    # device code -> polls so far
seq = [0]


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def reply(self, code, obj=None, raw=None, ctype="application/json"):
        body = raw if raw is not None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authed(self):
        t = (self.headers.get("Authorization") or "")[7:]
        if t not in tokens:
            self.reply(401, {"error": {"code": 401, "message": "Invalid Credentials"}})
            return False
        return True

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = dict(urllib.parse.parse_qsl(u.query))
        if u.path == "/o/oauth2/v2/auth":
            assert q["code_challenge_method"] == "S256" and "drive.appdata" in q["scope"], q
            seq[0] += 1
            code = "code%d" % seq[0]
            codes[code] = q["code_challenge"]
            loc = q["redirect_uri"] + "?" + urllib.parse.urlencode({"code": code, "state": q["state"], "scope": q["scope"]})
            self.send_response(302)
            self.send_header("Location", loc)
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif u.path == "/userinfo":
            if self.authed():
                self.reply(200, {"email": "tester@example.com", "name": "Test User"})
        elif u.path == "/drive/v3/files":
            if self.authed():
                assert q.get("spaces") == "appDataFolder", q
                name = re.search(r"name='([^']*)'", q.get("q", "")).group(1)
                self.reply(200, {"files": [{"id": i, "version": str(f["version"])} for i, f in files.items() if f["name"] == name]})
        elif u.path.startswith("/drive/v3/files/") and q.get("alt") == "media":
            if self.authed():
                f = files.get(u.path.rsplit("/", 1)[1])
                self.reply(200 if f else 404, raw=f["content"] if f else b"{}")
        elif u.path == "/state":
            self.reply(200, {i: {"name": f["name"], "version": f["version"], "content": json.loads(f["content"])} for i, f in files.items()})
        else:
            self.reply(404, {"error": "not found"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        q = dict(urllib.parse.parse_qsl(u.query))
        data = self.body()
        if u.path == "/device/code":
            f = dict(urllib.parse.parse_qsl(data.decode()))
            assert "drive.appdata" in f["scope"], f
            seq[0] += 1
            dc = "dc%d" % seq[0]
            devices[dc] = 0
            return self.reply(200, {"device_code": dc, "user_code": "GQVQ-JKEC", "verification_url": "https://www.google.com/device",
                                    "expires_in": 1800, "interval": 2})
        if u.path == "/token":
            f = dict(urllib.parse.parse_qsl(data.decode()))
            if f.get("grant_type") == "urn:ietf:params:oauth:grant-type:device_code":
                # Approved on the "other device" at the third poll.
                n = devices.get(f.get("device_code"))
                if n is None:
                    return self.reply(400, {"error": "expired_token"})
                devices[f["device_code"]] = n + 1
                if n < 2:
                    return self.reply(428, {"error": "authorization_pending"})
                del devices[f["device_code"]]
                seq[0] += 1
                at = "at%d" % seq[0]
                tokens.add(at)
                return self.reply(200, {"access_token": at, "expires_in": 3599, "refresh_token": "rt1", "token_type": "Bearer"})
            if f.get("grant_type") == "authorization_code":
                ch = codes.pop(f.get("code"), None)
                want = base64.urlsafe_b64encode(hashlib.sha256(f.get("code_verifier", "").encode()).digest()).rstrip(b"=").decode()
                if ch is None or ch != want:
                    return self.reply(400, {"error": "invalid_grant", "error_description": "bad code or verifier"})
                seq[0] += 1
                at = "at%d" % seq[0]
                tokens.add(at)
                return self.reply(200, {"access_token": at, "expires_in": 3599, "refresh_token": "rt1", "token_type": "Bearer"})
            if f.get("grant_type") == "refresh_token" and f.get("refresh_token") == "rt1":
                seq[0] += 1
                at = "at%d" % seq[0]
                tokens.add(at)
                return self.reply(200, {"access_token": at, "expires_in": 3599, "token_type": "Bearer"})
            return self.reply(400, {"error": "unsupported_grant_type"})
        if u.path == "/revoke":
            tokens.clear()
            return self.reply(200, {})
        if u.path == "/bump":
            for f in files.values():
                f["content"] = data
                f["version"] += 1
            return self.reply(200, {"ok": True})
        if u.path == "/upload/drive/v3/files" and q.get("uploadType") == "multipart":
            if not self.authed():
                return
            boundary = re.search(r"boundary=(\S+)", self.headers["Content-Type"]).group(1).encode()
            parts = [p for p in data.split(b"--" + boundary) if p.strip() not in (b"", b"--")]
            meta = json.loads(parts[0].split(b"\r\n\r\n", 1)[1].strip())
            content = parts[1].split(b"\r\n\r\n", 1)[1].rstrip(b"\r\n")
            assert meta["parents"] == ["appDataFolder"], meta
            json.loads(content)
            seq[0] += 1
            fid = "f%d" % seq[0]
            files[fid] = {"name": meta["name"], "version": 1, "content": content}
            return self.reply(200, {"id": fid, "version": "1"})
        m = re.match(r"^/upload/drive/v3/files/(\w+)$", u.path)
        if m and self.headers.get("X-HTTP-Method-Override") == "PATCH" and q.get("uploadType") == "media":
            if not self.authed():
                return
            f = files[m.group(1)]
            json.loads(data)
            f["content"] = data
            f["version"] += 1
            return self.reply(200, {"id": m.group(1), "version": str(f["version"])})
        self.reply(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        sys.stderr.write("fake_google: " + (fmt % args) + "\n")


ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 9300), H).serve_forever()
