"""A stand-in for GitHub's device flow, /user and the Gist API, for testing "Sign in with GitHub"
without a real account.

    python tools/fake_github.py 9310
    adb shell am start -n com.disgusty.droid_codology/.MainActivity -e githubTestBase http://10.0.2.2:9310

Personal access tokens that work: ghp_ followed by 36 "a" (gist + repo scopes) and ghp_ followed by
36 "b" (repo only, which the app must refuse). The device flow is approved at the third poll.
GET /state shows the Gists; POST /bump replaces the settings file as if another device had synced.
"""
import json
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

GOOD = "ghp_" + "a" * 36
NO_GIST = "ghp_" + "b" * 36
tokens = {GOOD: "gist, repo", NO_GIST: "repo"}
devices = {}
gists = {}
seq = [0]


def version():
    seq[0] += 1
    return "%040x" % seq[0]


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def reply(self, code, obj, extra=None):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def token(self):
        t = (self.headers.get("Authorization") or "")[7:]
        if t not in tokens:
            self.reply(401, {"message": "Bad credentials"})
            return None
        return t

    def gist_json(self, gid, with_content=True):
        g = gists[gid]
        files = {}
        for name, content in g["files"].items():
            f = {"filename": name, "raw_url": "http://unused/raw", "size": len(content)}
            if with_content:
                f["content"] = content
                f["truncated"] = False
            files[name] = f
        return {"id": gid, "description": g["description"], "public": g["public"], "files": files,
                "history": [{"version": v} for v in reversed(g["versions"])], "updated_at": "now"}

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = dict(urllib.parse.parse_qsl(u.query))
        if u.path == "/state":
            return self.reply(200, {gid: {"versions": g["versions"], "files": g["files"]} for gid, g in gists.items()})
        if u.path == "/login/device":
            return self.reply(200, {"page": "enter the code here"})
        t = self.token()
        if t is None:
            return
        if u.path == "/api/user":
            return self.reply(200, {"login": "octotest", "name": "Octo Tester", "id": 1}, {"X-OAuth-Scopes": tokens[t]})
        if u.path == "/api/gists":
            page = int(q.get("page", "1"))
            items = [self.gist_json(gid, False) for gid in sorted(gists)] if page == 1 else []
            return self.reply(200, items)
        if u.path.startswith("/api/gists/"):
            gid = u.path.rsplit("/", 1)[1]
            if gid not in gists:
                return self.reply(404, {"message": "Not Found"})
            return self.reply(200, self.gist_json(gid))
        self.reply(404, {"message": "Not Found"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        data = self.body()
        if u.path == "/login/device/code":
            f = dict(urllib.parse.parse_qsl(data.decode()))
            assert "gist" in f.get("scope", ""), f
            dc = "dev%d" % len(devices)
            devices[dc] = 0
            return self.reply(200, {"device_code": dc, "user_code": "WDJB-MJHT", "verification_uri": "http://10.0.2.2:%d/login/device" % PORT,
                                    "expires_in": 900, "interval": 2})
        if u.path == "/login/oauth/access_token":
            f = dict(urllib.parse.parse_qsl(data.decode()))
            n = devices.get(f.get("device_code"))
            if n is None:
                return self.reply(200, {"error": "expired_token"})
            devices[f["device_code"]] = n + 1
            if n < 2:
                return self.reply(200, {"error": "authorization_pending"})
            del devices[f["device_code"]]
            tok = "gho_" + ("%036d" % len(tokens))
            tokens[tok] = "gist, repo"
            return self.reply(200, {"access_token": tok, "token_type": "bearer", "scope": "gist,repo"})
        if u.path == "/bump":
            for g in gists.values():
                for name in g["files"]:
                    g["files"][name] = data.decode()
                g["versions"].append(version())
            return self.reply(200, {"ok": True})
        if self.token() is None:
            return
        if u.path == "/api/gists":
            b = json.loads(data)
            assert b["public"] is False, b
            seq[0] += 1
            gid = "g%d" % seq[0]
            gists[gid] = {"description": b["description"], "public": False,
                          "files": {n: f["content"] for n, f in b["files"].items()}, "versions": [version()]}
            return self.reply(201, self.gist_json(gid))
        self.reply(404, {"message": "Not Found"})

    def do_PATCH(self):
        u = urllib.parse.urlparse(self.path)
        data = self.body()
        if self.token() is None:
            return
        gid = u.path.rsplit("/", 1)[1]
        if not u.path.startswith("/api/gists/") or gid not in gists:
            return self.reply(404, {"message": "Not Found"})
        b = json.loads(data)
        for n, f in b["files"].items():
            json.loads(f["content"])
            gists[gid]["files"][n] = f["content"]
        gists[gid]["versions"].append(version())
        self.reply(200, self.gist_json(gid))

    def log_message(self, fmt, *args):
        sys.stderr.write("fake_github: " + (fmt % args) + "\n")


PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9310
ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
