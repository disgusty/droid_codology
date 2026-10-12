"""A stand-in for Firebase Auth (REST) and the Realtime Database (REST with ETags), for testing
email sign-in without a server and without a real Firebase project.

    python tools/fake_firebase.py 9320
    adb shell am start -n com.disgusty.droid_codology/.MainActivity -e firebaseTestBase http://10.0.2.2:9320

Database rules are emulated: a user can only read and write /droid_codology/users/<own uid>.
GET /state shows users and data; POST /bump/<email> replaces that user's settings (another device).
"""
import hashlib
import json
import re
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

users = {}    # email -> {uid, password, name}
ids = {}      # id token -> uid
refresh = {}  # refresh token -> uid
data = {}     # path -> json text
seq = [0]


def etag(text):
    return hashlib.md5(text.encode()).hexdigest()


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def reply(self, code, obj, headers=None, raw=None):
        body = raw.encode() if raw is not None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def error(self, msg, code=400):
        self.reply(code, {"error": {"code": code, "message": msg}})

    def session(self, uid, email):
        seq[0] += 1
        idt, rt = "id%d" % seq[0], "rt%d" % seq[0]
        ids[idt] = uid
        refresh[rt] = uid
        return {"localId": uid, "idToken": idt, "refreshToken": rt, "expiresIn": "3600", "email": email,
                "displayName": next((u["name"] for u in users.values() if u["uid"] == uid), "")}

    def db(self, method):
        u = urllib.parse.urlparse(self.path)
        q = dict(urllib.parse.parse_qsl(u.query))
        m = re.match(r"^/db/droid_codology/users/([^/]+)/settings\.json$", u.path)
        uid = ids.get(q.get("auth"))
        if not m or uid is None or m.group(1) != uid:
            return self.reply(401, {"error": "Permission denied"})
        path = m.group(1)
        current = data.get(path, "null")
        if method == "GET":
            h = {"ETag": etag(current)} if self.headers.get("X-Firebase-ETag") == "true" else {}
            return self.reply(200, None, h, raw=current)
        new = self.body().decode()
        json.loads(new)
        want = self.headers.get("if-match")
        if want is not None and want != etag(current):
            return self.reply(412, {"error": "ETag mismatch"}, {"ETag": etag(current)})
        data[path] = new
        return self.reply(200, None, {"ETag": etag(new)}, raw=new)

    def do_GET(self):
        if self.path == "/state":
            return self.reply(200, {"users": users, "data": {k: json.loads(v) for k, v in data.items()}})
        if self.path.startswith("/db/"):
            return self.db("GET")
        self.reply(404, {"error": "not found"})

    def do_PUT(self):
        if self.path.startswith("/db/"):
            return self.db("PUT")
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        raw = self.body()
        if u.path.startswith("/bump/"):
            email = urllib.parse.unquote(u.path[6:])
            data[users[email]["uid"]] = json.dumps(raw.decode())
            return self.reply(200, {"ok": True})
        if u.path == "/v1/token":
            f = dict(urllib.parse.parse_qsl(raw.decode()))
            uid = refresh.get(f.get("refresh_token"))
            if uid is None:
                return self.error("INVALID_REFRESH_TOKEN")
            s = self.session(uid, "")
            return self.reply(200, {"id_token": s["idToken"], "refresh_token": f["refresh_token"], "expires_in": "3600", "user_id": uid})
        b = json.loads(raw or b"{}")
        op = u.path.split(":")[-1]
        if op == "signUp":
            if b["email"] in users:
                return self.error("EMAIL_EXISTS")
            if len(b.get("password", "")) < 6:
                return self.error("WEAK_PASSWORD : Password should be at least 6 characters")
            users[b["email"]] = {"uid": "u%d" % (len(users) + 1), "password": b["password"], "name": ""}
            return self.reply(200, self.session(users[b["email"]]["uid"], b["email"]))
        if op == "signInWithPassword":
            u2 = users.get(b.get("email"))
            if not u2 or u2["password"] != b.get("password"):
                return self.error("INVALID_LOGIN_CREDENTIALS")
            return self.reply(200, self.session(u2["uid"], b["email"]))
        if op == "update":
            uid = ids.get(b.get("idToken"))
            for u2 in users.values():
                if u2["uid"] == uid:
                    u2["name"] = b.get("displayName", "")
            return self.reply(200, {"localId": uid})
        if op == "sendOobCode":
            if b.get("email") not in users:
                return self.error("EMAIL_NOT_FOUND")
            return self.reply(200, {"email": b["email"]})
        self.reply(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        sys.stderr.write("fake_firebase: " + (fmt % args) + "\n")


ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 9320), H).serve_forever()
