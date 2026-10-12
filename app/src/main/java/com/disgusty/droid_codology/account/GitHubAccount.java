package com.disgusty.droid_codology.account;

import android.content.SharedPreferences;
import android.net.Uri;

import com.disgusty.droid_codology.BuildConfig;
import com.disgusty.droid_codology.net.Http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signing in with GitHub without a droid_codology server. Settings sync to a secret Gist in the
 * user's account, and the token also serves Git over HTTPS.
 * <p>
 * Two ways in: GitHub's device flow (a code entered at github.com/login/device, needs the
 * client ID of a GitHub OAuth app built into the app), or, with no setup at all, a personal
 * access token the user creates on github.com and copies.
 */
public final class GitHubAccount implements Cloud {
    private static String WEB = "https://github.com";
    private static String API = "https://api.github.com";
    private static boolean testEndpoints;
    static final String FILE_NAME = "droid_codology-settings.json";
    private static final String DESCRIPTION = "droid_codology settings (synced by the app; keep it secret)";
    private static final String SCOPES = "gist repo";
    /** github.com page that creates a classic token with the scopes we need. */
    public static String tokenPage() {
        return WEB + "/settings/tokens/new?scopes=gist,repo&description=droid_codology";
    }

    private static final Pattern TOKEN = Pattern.compile("\\b(?:ghp_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,}|gho_[A-Za-z0-9]{36,})\\b");

    private final SharedPreferences sp;
    private final Account account;

    GitHubAccount(SharedPreferences sp, Account account) {
        this.sp = sp;
        this.account = account;
    }

    /** Debug builds: talk to tools/fake_github.py at {@code base} ("...#pat": test the token sign-in instead of device flow). */
    public static void useTestEndpoints(String base) {
        if (!BuildConfig.DEBUG) return;
        testDevice = !base.endsWith("#pat");
        if (!testDevice) base = base.substring(0, base.length() - 4);
        WEB = base;
        API = base + "/api";
        testEndpoints = true;
    }

    private static boolean testDevice = true;

    /** Device flow needs a GitHub OAuth app; without one, sign-in goes by personal access token. */
    public static boolean deviceConfigured() {
        return testEndpoints && testDevice || !BuildConfig.GITHUB_CLIENT_ID.isEmpty();
    }

    /** A GitHub token in {@code text} (ghp_..., github_pat_..., gho_...), or null. */
    public static String findToken(String text) {
        if (text == null) return null;
        Matcher m = TOKEN.matcher(text);
        return m.find() ? m.group() : null;
    }

    @Override
    public boolean signedIn() {
        return sp.getString("gh.token", null) != null;
    }

    public String login() {
        return sp.getString("gh.login", "");
    }

    public String token() {
        return sp.getString("gh.token", null);
    }

    @Override
    public String displayName() {
        String n = sp.getString("gh.name", ""), l = login();
        return n.isEmpty() || n.equals(l) ? l : n + " (" + l + ")";
    }

    @Override
    public String storage() {
        return "GitHub Gist";
    }

    // ------------------------------------------------------------------ sign-in

    /** Checks {@code token}, then keeps it with the user's profile. */
    public void useToken(String token) throws IOException {
        token = token.trim();
        Http.Response r = Http.request("GET", API + "/user", headers(token), null, null);
        JSONObject u = json(r);
        String scopes = r.header("X-OAuth-Scopes");
        // Classic tokens list their scopes; fine-grained ones don't (their permissions are checked on use).
        if (scopes != null && !scopes.contains("gist")) {
            throw new IOException("This token can't create Gists: give it the \"gist\" scope (and \"repo\" for pushing with Git)");
        }
        String old = sp.getString("gh.token", null);
        SharedPreferences.Editor e = sp.edit().putString("gh.token", token).putString("gh.login", u.optString("login", ""))
                .putString("gh.name", u.isNull("name") ? "" : u.optString("name", ""));
        // Another GitHub user: forget the previous user's Gist and sync state.
        if (old == null || !u.optString("login", "").equals(sp.getString("gh.login", ""))) e.remove("gh.gist").remove("gh.version");
        e.apply();
        // The same token serves Git over HTTPS, unless the user saved a token of their own for that.
        if (account.githubToken() == null) account.setGithubToken(token);
    }

    public static final class DeviceCode {
        public final String deviceCode, userCode, url;
        public final int interval;
        public final long expiresAt;

        DeviceCode(JSONObject o) {
            deviceCode = o.optString("device_code");
            userCode = o.optString("user_code");
            url = o.optString("verification_uri", WEB + "/login/device");
            interval = Math.max(2, o.optInt("interval", 5));
            expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 900) * 1000;
        }
    }

    public static DeviceCode deviceStart() throws IOException {
        return new DeviceCode(form(WEB + "/login/device/code", "client_id=" + Uri.encode(BuildConfig.GITHUB_CLIENT_ID)
                + "&scope=" + Uri.encode(SCOPES)));
    }

    /** One check whether the user entered the code: false while waiting. */
    public boolean devicePoll(DeviceCode c) throws IOException {
        JSONObject o = form(WEB + "/login/oauth/access_token", "client_id=" + Uri.encode(BuildConfig.GITHUB_CLIENT_ID)
                + "&device_code=" + Uri.encode(c.deviceCode) + "&grant_type=" + Uri.encode("urn:ietf:params:oauth:grant-type:device_code"));
        // GitHub answers 200 with an "error" field while the user hasn't finished.
        String err = o.optString("error", "");
        if (err.equals("authorization_pending") || err.equals("slow_down")) return false;
        if (!err.isEmpty()) {
            throw new IOException(err.equals("access_denied") ? "cancelled" : err.equals("expired_token") ? "the code expired"
                    : o.optString("error_description", err));
        }
        String token = o.optString("access_token", "");
        if (token.isEmpty()) throw new IOException("GitHub returned no token");
        useToken(token);
        return true;
    }

    @Override
    public void signOut() {
        String t = token();
        // The Git token goes too when it came from this sign-in.
        if (t != null && t.equals(account.githubToken())) account.setGithubToken(null);
        sp.edit().remove("gh.token").remove("gh.login").remove("gh.name").remove("gh.gist").remove("gh.version").apply();
    }

    // ------------------------------------------------------------------ settings sync (a secret Gist)

    @Override
    public String sync(Account.SyncData data, boolean forceUpload) throws IOException {
        String token = token();
        if (token == null) throw new Account.ServerError(401, "Not signed in to GitHub");
        try {
            JSONObject gist = findGist(token);
            JSONObject local = data.collect();
            String remoteVersion = gist == null ? "" : version(gist);
            String known = sp.getString("gh.version", "");
            if (!forceUpload && gist != null && !remoteVersion.equals(known)) {
                String content = content(token, gist);
                if (content != null) {
                    data.apply(new JSONObject(content));
                    sp.edit().putString("gh.version", remoteVersion).putString("gh.hash", Account.hash(new JSONObject(content)))
                            .putLong("lastSync", System.currentTimeMillis()).apply();
                    return "pulled";
                }
            }
            String h = Account.hash(local);
            if (!forceUpload && gist != null && h.equals(sp.getString("gh.hash", ""))) {
                sp.edit().putLong("lastSync", System.currentTimeMillis()).apply();
                return "unchanged";
            }
            JSONObject files = new JSONObject().put(FILE_NAME, new JSONObject().put("content", local.toString(1)));
            JSONObject saved;
            if (gist == null) {
                saved = json(Http.request("POST", API + "/gists", headers(token),
                        new JSONObject().put("description", DESCRIPTION).put("public", false).put("files", files).toString().getBytes("UTF-8"),
                        "application/json"));
            } else {
                saved = json(Http.request("PATCH", API + "/gists/" + gist.getString("id"), headers(token),
                        new JSONObject().put("files", files).toString().getBytes("UTF-8"), "application/json"));
            }
            sp.edit().putString("gh.gist", saved.optString("id")).putString("gh.version", version(saved)).putString("gh.hash", h)
                    .putLong("lastSync", System.currentTimeMillis()).apply();
            return "pushed";
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }

    /** Our Gist: the one remembered, else found by its file name among the user's Gists. */
    private JSONObject findGist(String token) throws IOException, JSONException {
        String id = sp.getString("gh.gist", null);
        if (id != null) {
            Http.Response r = Http.request("GET", API + "/gists/" + id, headers(token), null, null);
            if (r.code != 404) return json(r);
            sp.edit().remove("gh.gist").remove("gh.version").apply();
        }
        for (int page = 1; page <= 10; page++) {
            Http.Response r = Http.request("GET", API + "/gists?per_page=100&page=" + page, headers(token), null, null);
            JSONArray list = new JSONArray(text(r));
            for (int i = 0; i < list.length(); i++) {
                JSONObject g = list.getJSONObject(i);
                JSONObject files = g.optJSONObject("files");
                if (files != null && files.has(FILE_NAME)) {
                    // The list leaves out file contents: fetch the Gist itself.
                    return json(Http.request("GET", API + "/gists/" + g.getString("id"), headers(token), null, null));
                }
            }
            if (list.length() < 100) break;
        }
        return null;
    }

    private static String version(JSONObject gist) {
        JSONArray h = gist.optJSONArray("history");
        JSONObject first = h != null && h.length() > 0 ? h.optJSONObject(0) : null;
        return first != null ? first.optString("version", "") : gist.optString("updated_at", "");
    }

    private static String content(String token, JSONObject gist) throws IOException, JSONException {
        JSONObject files = gist.optJSONObject("files");
        JSONObject f = files == null ? null : files.optJSONObject(FILE_NAME);
        if (f == null) return null;
        // Big files come truncated in the API answer; the raw URL has all of it.
        if (f.optBoolean("truncated") || !f.has("content")) return text(Http.request("GET", f.getString("raw_url"), headers(token), null, null));
        return f.getString("content");
    }

    // ------------------------------------------------------------------ HTTP

    private static HashMap<String, String> headers(String token) {
        HashMap<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        if (token != null) h.put("Authorization", "Bearer " + token);
        return h;
    }

    private static JSONObject form(String url, String body) throws IOException {
        HashMap<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/json");
        return json(Http.request("POST", url, h, body.getBytes("UTF-8"), "application/x-www-form-urlencoded"));
    }

    private static String text(Http.Response r) throws IOException {
        if (!r.ok()) json(r);
        return r.text();
    }

    private static JSONObject json(Http.Response r) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(r.text());
        } catch (JSONException e) {
            o = new JSONObject();
        }
        if (!r.ok()) {
            String msg = o.optString("message", "HTTP " + r.code);
            if (r.code == 401) msg = "GitHub didn't accept the token (" + msg + ")";
            throw new Account.ServerError(r.code, msg);
        }
        return o;
    }
}
