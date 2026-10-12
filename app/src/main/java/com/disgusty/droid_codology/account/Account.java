package com.disgusty.droid_codology.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import com.disgusty.droid_codology.net.Http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The signed-in account on a droid_codology server (see server/README.md): sign-in by email,
 * OAuth through the browser (GitHub, Google, Apple) or an API key, and settings sync.
 * Network methods block; call them off the UI thread.
 */
public final class Account {
    public static final String REDIRECT = "droid-codology://auth";

    /** Settings that never leave the device (paths, window state, secrets). */
    private static final String[] LOCAL_PREFIXES = {"state.", "editor.fontFamily", "workbench.sideBar.visible"};

    private final Context ctx;
    private final SharedPreferences sp;

    public static final class ServerError extends IOException {
        public final int code;

        ServerError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    /** Accounts without a server, each syncing to the user's own cloud storage. */
    public final GoogleAccount google;
    public final GitHubAccount github;
    public final FirebaseAccount firebase;

    public Account(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sp = ctx.getSharedPreferences("account", Context.MODE_PRIVATE);
        google = new GoogleAccount(sp);
        github = new GitHubAccount(sp, this);
        firebase = new FirebaseAccount(sp);
    }

    /** The signed-in account without a server, or null. */
    public Cloud cloud() {
        if (google.signedIn()) return google;
        if (github.signedIn()) return github;
        if (firebase.signedIn()) return firebase;
        return null;
    }

    /** One cloud account at a time: signing in to {@code keep} signs out of the others (call off the UI thread). */
    public void keepOnlyCloud(Cloud keep) {
        for (Cloud c : new Cloud[]{google, github, firebase}) if (c != keep && c.signedIn()) c.signOut();
    }

    // ------------------------------------------------------------------ state

    /** The server address the user set, else the one built into the app (may be empty). */
    public String server() {
        return sp.contains("server") ? sp.getString("server", "") : com.disgusty.droid_codology.BuildConfig.DEFAULT_SERVER;
    }

    public boolean usingBuiltInServer() {
        return !sp.contains("server") && !server().isEmpty();
    }

    /** Signed in somewhere settings can sync to: a server, or Google Drive. */
    public boolean syncSignedIn() {
        return signedIn() || cloud() != null;
    }

    /** {@code null} goes back to the server built into the app. */
    public void setServer(String url) {
        if (url == null) {
            if (sp.contains("server")) sp.edit().remove("server").remove("token").remove("user").remove("syncVersion").apply();
            return;
        }
        url = url.trim().replaceAll("/+$", "");
        if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) url = (isLocalHost(url) ? "http://" : "https://") + url;
        if (!url.equals(server())) sp.edit().putString("server", url).remove("token").remove("user").remove("syncVersion").apply();
        else if (!sp.contains("server")) sp.edit().putString("server", url).apply();
    }

    /**
     * A server on the home network or this device (192.168.x.x, 10.x, 172.16-31.x, localhost, *.local):
     * such servers usually run without HTTPS, so an address typed without a scheme means http there.
     */
    public static boolean isLocalHost(String address) {
        String host = address.replaceFirst("^[a-zA-Z]+://", "");
        int end = host.length();
        for (char c : new char[]{'/', ':', '?'}) {
            int i = host.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        host = host.substring(0, end).toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".lan") || host.startsWith("127.")) return true;
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            String[] p = host.split("\\.");
            try {
                int second = p.length > 1 ? Integer.parseInt(p[1]) : -1;
                return second >= 16 && second <= 31;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    public String token() {
        return sp.getString("token", null);
    }

    public boolean signedIn() {
        return token() != null && !server().isEmpty();
    }

    /** Cached profile: {id, email, name, identities...}, or null. */
    public JSONObject user() {
        String s = sp.getString("user", null);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    public String displayName() {
        JSONObject u = user();
        if (u == null && !signedIn() && cloud() != null) return cloud().displayName();
        if (u == null) return "";
        String n = u.optString("name", "");
        String e = u.isNull("email") ? "" : u.optString("email", "");
        return n.isEmpty() ? e : e.isEmpty() ? n : n + " (" + e + ")";
    }

    private void signedIn(String token, JSONObject user) {
        sp.edit().putString("token", token).putString("user", user == null ? null : user.toString()).remove("syncVersion").apply();
    }

    public void signOutLocally() {
        sp.edit().remove("token").remove("user").remove("syncVersion").apply();
    }

    public boolean syncEnabled() {
        return sp.getBoolean("syncEnabled", false);
    }

    public void setSyncEnabled(boolean on) {
        sp.edit().putBoolean("syncEnabled", on).apply();
    }

    public long lastSync() {
        return sp.getLong("lastSync", 0);
    }

    // ------------------------------------------------------------------ requests

    private Object call(String method, String path, JSONObject body, boolean auth) throws IOException {
        if (server().isEmpty()) throw new IOException("No server set");
        HashMap<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/json");
        if (auth) {
            String t = token();
            if (t == null) throw new ServerError(401, "Not signed in");
            h.put("Authorization", "Bearer " + t);
        }
        Http.Response r = Http.request(method, server() + path, h, body == null ? null : body.toString().getBytes("UTF-8"),
                body == null ? null : "application/json");
        String text = r.text();
        Object v = null;
        try {
            v = text.isEmpty() ? null : new org.json.JSONTokener(text).nextValue();
        } catch (JSONException ignored) {
        }
        if (!r.ok()) {
            String msg = v instanceof JSONObject ? ((JSONObject) v).optString("error", "HTTP " + r.code) : "HTTP " + r.code;
            if (r.code == 401 && auth) signOutLocally();
            throw new ServerError(r.code, msg);
        }
        return v;
    }

    private JSONObject obj(String method, String path, JSONObject body, boolean auth) throws IOException {
        Object v = call(method, path, body, auth);
        if (!(v instanceof JSONObject)) throw new IOException("Unexpected response from server");
        return (JSONObject) v;
    }

    /** Server description: {name, version, registration, oauth[], ai, models[]}. */
    public JSONObject info() throws IOException {
        return obj("GET", "/v1/info", null, false);
    }

    private static String device() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    public void login(String email, String password) throws IOException {
        try {
            JSONObject r = obj("POST", "/v1/auth/login", new JSONObject().put("email", email).put("password", password).put("device", device()), false);
            signedIn(r.getString("token"), r.optJSONObject("user"));
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }

    public void register(String email, String password, String name) throws IOException {
        try {
            JSONObject r = obj("POST", "/v1/auth/register", new JSONObject().put("email", email).put("password", password)
                    .put("name", name).put("device", device()), false);
            signedIn(r.getString("token"), r.optJSONObject("user"));
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }

    /** Starts signing in by code on another device: {device_code, user_code, verification_url, interval, expires_in}. */
    public JSONObject deviceStart() throws IOException {
        try {
            return obj("POST", "/v1/auth/device/start", new JSONObject().put("device", device()), false);
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }

    /** One check whether the code was used on the other device: false while waiting. */
    public boolean devicePoll(String deviceCode) throws IOException {
        try {
            JSONObject r = obj("POST", "/v1/auth/device/poll", new JSONObject().put("device_code", deviceCode), false);
            if (r.optBoolean("pending")) return false;
            signedIn(r.getString("token"), r.optJSONObject("user"));
            return true;
        } catch (ServerError e) {
            if (e.code == 410) throw new IOException("the code expired");
            throw e;
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }

    /** Signs in with an API key (dck_...) or a session token received from the browser. */
    public void useToken(String token) throws IOException {
        // Everything a failed attempt could disturb (a 401 signs out locally), to put back.
        String oldToken = token(), oldUser = sp.getString("user", null), oldHash = sp.getString("lastHash", null);
        boolean hadVersion = sp.contains("syncVersion");
        int oldVersion = sp.getInt("syncVersion", -1);
        sp.edit().putString("token", token.trim()).apply();
        try {
            JSONObject before = oldUser == null ? null : new JSONObject(oldUser);
            JSONObject u = refreshUser();
            // Another account: its sync history starts fresh (the next sync pulls its settings).
            if (before == null || before.optLong("id", -1) != u.optLong("id", -2)) {
                sp.edit().remove("syncVersion").remove("lastHash").apply();
            }
        } catch (IOException | JSONException e) {
            SharedPreferences.Editor ed = sp.edit();
            if (oldToken != null) ed.putString("token", oldToken);
            else ed.remove("token");
            if (oldUser != null) ed.putString("user", oldUser);
            if (hadVersion) ed.putInt("syncVersion", oldVersion);
            if (oldHash != null) ed.putString("lastHash", oldHash);
            ed.apply();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.toString());
        }
    }

    public JSONObject refreshUser() throws IOException {
        JSONObject u = obj("GET", "/v1/me", null, true);
        sp.edit().putString("user", u.toString()).apply();
        return u;
    }

    public void logout() {
        try {
            call("POST", "/v1/auth/logout", new JSONObject(), true);
        } catch (IOException ignored) {
            // Signing out locally is what matters.
        }
        signOutLocally();
    }

    /** Creates an API key on the server; the key is returned only this once. */
    public String createKey(String name) throws IOException {
        try {
            return obj("POST", "/v1/keys", new JSONObject().put("name", name), true).getString("key");
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }

    /** GitHub personal access token for Git over HTTPS (kept on this device only). */
    public String githubToken() {
        return sp.getString("githubToken", null);
    }

    public void setGithubToken(String t) {
        if (t == null) sp.edit().remove("githubToken").apply();
        else sp.edit().putString("githubToken", t).apply();
    }

    /**
     * Browser URL that starts OAuth sign-in; the server sends the browser back to {@link #REDIRECT}
     * with the token. The redirect carries a one-time value, so a droid-codology://auth link that
     * this app didn't ask for (a web page, another app) can't sign it in to someone else's account.
     */
    public String oauthUrl(String provider) {
        String nonce = Loopback.random(18);
        sp.edit().putString("pendingAuth", nonce).putLong("pendingAuthAt", System.currentTimeMillis()).apply();
        return server() + "/v1/auth/oauth/" + provider + "/start?redirect=" + Uri.encode(REDIRECT + "?s=" + nonce);
    }

    /** True once for the redirect of a sign-in started here within the last 15 minutes. */
    public boolean takePendingAuth(String nonce) {
        String want = sp.getString("pendingAuth", null);
        long at = sp.getLong("pendingAuthAt", 0);
        boolean ok = want != null && want.equals(nonce) && System.currentTimeMillis() - at < 15 * 60 * 1000;
        if (ok) sp.edit().remove("pendingAuth").remove("pendingAuthAt").apply();
        return ok;
    }

    // ------------------------------------------------------------------ settings sync

    public static final class Conflict extends IOException {
        Conflict(String msg) {
            super(msg);
        }
    }

    public interface SyncData {
        /** What to upload. */
        JSONObject collect() throws JSONException;

        /** Applies downloaded data (on any thread; implementations post to the UI themselves). */
        void apply(JSONObject data) throws JSONException;
    }

    /**
     * Two-way sync: if the server has a newer version than we last saw, it wins and is applied;
     * otherwise local settings are uploaded. {@code forceUpload} overwrites the server.
     * Returns "pulled", "pushed" or "unchanged".
     */
    public String sync(SyncData data, boolean forceUpload) throws IOException {
        // A server account syncs through the server; otherwise Google Drive, when signed in with Google.
        if (!signedIn() && cloud() != null) return cloud().sync(data, forceUpload);
        try {
            JSONObject remote = obj("GET", "/v1/sync", null, true);
            int remoteVersion = remote.optInt("version", 0);
            int known = sp.getInt("syncVersion", -1);
            JSONObject local = data.collect();
            if (!forceUpload && remoteVersion > 0 && remoteVersion != known) {
                JSONObject rd = remote.optJSONObject("data");
                if (rd != null) data.apply(rd);
                sp.edit().putInt("syncVersion", remoteVersion).putLong("lastSync", System.currentTimeMillis())
                        .putString("lastHash", hash(rd == null ? local : rd)).apply();
                return "pulled";
            }
            String h = hash(local);
            if (!forceUpload && remoteVersion == known && h.equals(sp.getString("lastHash", ""))) {
                sp.edit().putLong("lastSync", System.currentTimeMillis()).apply();
                return "unchanged";
            }
            JSONObject r;
            try {
                r = obj("PUT", "/v1/sync", new JSONObject().put("baseVersion", remoteVersion).put("data", local), true);
            } catch (ServerError e) {
                if (e.code == 409) throw new Conflict(e.getMessage());
                throw e;
            }
            sp.edit().putInt("syncVersion", r.optInt("version")).putLong("lastSync", System.currentTimeMillis()).putString("lastHash", h).apply();
            return "pushed";
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }

    /**
     * Fingerprint of synced data that doesn't depend on key order: preferences come out of a
     * HashMap whose order differs between Android versions and devices, and an order-dependent
     * hash made two devices take each other's identical settings for changes, pulling and pushing
     * forever (and overwriting fresh local edits each time).
     */
    static String hash(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        canonical(o, sb);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", d[i] & 0xFF));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    static void canonical(Object v, StringBuilder sb) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            java.util.ArrayList<String> keys = new java.util.ArrayList<String>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            java.util.Collections.sort(keys);
            sb.append('{');
            for (String k : keys) {
                sb.append(JSONObject.quote(k)).append(':');
                canonical(o.opt(k), sb);
                sb.append(',');
            }
            sb.append('}');
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            sb.append('[');
            for (int i = 0; i < a.length(); i++) {
                canonical(a.opt(i), sb);
                sb.append(',');
            }
            sb.append(']');
        } else if (v instanceof String) {
            sb.append(JSONObject.quote((String) v));
        } else if (v instanceof Number) {
            // 1 and 1.0 are the same setting whichever way it was stored.
            double d = ((Number) v).doubleValue();
            sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d));
        } else {
            sb.append(String.valueOf(v));
        }
    }

    // ------------------------------------------------------------------ helpers for SyncData

    static boolean isLocal(String key) {
        for (String p : LOCAL_PREFIXES) if (key.startsWith(p)) return true;
        return false;
    }

    /** SharedPreferences -> {key: [type, value]} without device-local keys. */
    public static JSONObject dumpPrefs(SharedPreferences sp) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (isLocal(e.getKey())) continue;
            Object v = e.getValue();
            String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Float ? "f" : v instanceof String ? "s" : null;
            if (t == null) continue;
            o.put(e.getKey(), new JSONArray().put(t).put(v instanceof Float ? ((Float) v).doubleValue() : v));
        }
        return o;
    }

    /** Replaces synced keys in {@code sp} with {@code o}; device-local keys are kept. */
    public static void loadPrefs(SharedPreferences sp, JSONObject o) throws JSONException {
        SharedPreferences.Editor ed = sp.edit();
        for (String k : sp.getAll().keySet()) if (!isLocal(k) && !o.has(k)) ed.remove(k);
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (isLocal(k)) continue;
            JSONArray tv = o.optJSONArray(k);
            if (tv == null || tv.length() < 2) continue;
            String t = tv.getString(0);
            if (t.equals("b")) ed.putBoolean(k, tv.getBoolean(1));
            else if (t.equals("i")) ed.putInt(k, tv.getInt(1));
            else if (t.equals("l")) ed.putLong(k, tv.getLong(1));
            else if (t.equals("f")) ed.putFloat(k, (float) tv.getDouble(1));
            else ed.putString(k, tv.getString(1));
        }
        ed.apply();
    }
}
