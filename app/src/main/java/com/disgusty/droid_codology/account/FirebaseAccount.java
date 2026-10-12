package com.disgusty.droid_codology.account;

import android.content.SharedPreferences;
import android.net.Uri;

import com.disgusty.droid_codology.BuildConfig;
import com.disgusty.droid_codology.net.Http;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.util.HashMap;

/**
 * Email and password accounts without a droid_codology server, through Firebase (Google's hosted
 * sign-in): Firebase Auth's REST API for accounts and the Realtime Database's REST API for settings.
 * Works without Google Play services, so also on Android 4.x. Needs a Firebase project whose Web API
 * key and database URL are built into the app (see ACCOUNTS.md).
 */
public final class FirebaseAccount implements Cloud {
    private static String AUTH = "https://identitytoolkit.googleapis.com/v1/accounts:";
    private static String TOKEN = "https://securetoken.googleapis.com/v1/token";
    private static String DB = BuildConfig.FIREBASE_DATABASE_URL.replaceAll("/+$", "");
    private static boolean testEndpoints;

    private final SharedPreferences sp;

    FirebaseAccount(SharedPreferences sp) {
        this.sp = sp;
    }

    /** Debug builds: talk to tools/fake_firebase.py at {@code base}. */
    public static void useTestEndpoints(String base) {
        if (!BuildConfig.DEBUG) return;
        AUTH = base + "/v1/accounts:";
        TOKEN = base + "/v1/token";
        DB = base + "/db";
        testEndpoints = true;
    }

    public static boolean configured() {
        return testEndpoints || (!BuildConfig.FIREBASE_API_KEY.isEmpty() && !BuildConfig.FIREBASE_DATABASE_URL.isEmpty());
    }

    @Override
    public boolean signedIn() {
        return sp.getString("fb.refresh", null) != null;
    }

    @Override
    public String displayName() {
        String n = sp.getString("fb.name", ""), e = sp.getString("fb.email", "");
        return n.isEmpty() ? e : n + " (" + e + ")";
    }

    @Override
    public String storage() {
        return "Firebase";
    }

    // ------------------------------------------------------------------ accounts

    public void signIn(String email, String password) throws IOException {
        signedIn(post(AUTH + "signInWithPassword", obj("email", email.trim(), "password", password, "returnSecureToken", true)));
    }

    public void register(String email, String password, String name) throws IOException {
        JSONObject r = post(AUTH + "signUp", obj("email", email.trim(), "password", password, "returnSecureToken", true));
        signedIn(r);
        if (name != null && !name.trim().isEmpty()) {
            post(AUTH + "update", obj("idToken", r.optString("idToken"), "displayName", name.trim(), "returnSecureToken", false));
            sp.edit().putString("fb.name", name.trim()).apply();
        }
    }

    /** Firebase emails a link to choose a new password. */
    public static void resetPassword(String email) throws IOException {
        post(AUTH + "sendOobCode", obj("requestType", "PASSWORD_RESET", "email", email.trim()));
    }

    private void signedIn(JSONObject r) {
        String uid = r.optString("localId");
        SharedPreferences.Editor e = sp.edit().putString("fb.refresh", r.optString("refreshToken")).putString("fb.id", r.optString("idToken"))
                .putLong("fb.expires", System.currentTimeMillis() + r.optLong("expiresIn", 3600) * 1000 - 60000)
                .putString("fb.email", r.optString("email", "")).putString("fb.name", r.optString("displayName", ""));
        if (!uid.equals(sp.getString("fb.uid", ""))) e.remove("fb.etag").remove("fb.hash");
        e.putString("fb.uid", uid).apply();
    }

    @Override
    public void signOut() {
        // Firebase ID tokens can't be revoked from the client; forgetting them ends the session here.
        sp.edit().remove("fb.refresh").remove("fb.id").remove("fb.expires").remove("fb.email").remove("fb.name")
                .remove("fb.uid").remove("fb.etag").remove("fb.hash").apply();
    }

    /** A valid ID token, refreshed when it expired. */
    private String idToken() throws IOException {
        String refresh = sp.getString("fb.refresh", null);
        if (refresh == null) throw new Account.ServerError(401, "Not signed in");
        if (System.currentTimeMillis() < sp.getLong("fb.expires", 0)) return sp.getString("fb.id", "");
        Http.Response resp = Http.request("POST", TOKEN + "?key=" + Uri.encode(BuildConfig.FIREBASE_API_KEY), null,
                ("grant_type=refresh_token&refresh_token=" + Uri.encode(refresh)).getBytes("UTF-8"), "application/x-www-form-urlencoded");
        JSONObject r;
        try {
            r = json(resp);
        } catch (Account.ServerError e) {
            // TOKEN_EXPIRED / USER_DISABLED / USER_NOT_FOUND: the session is over.
            if (e.code == 400 || e.code == 401 || e.code == 403) signOut();
            throw e;
        }
        String id = r.optString("id_token");
        sp.edit().putString("fb.id", id).putString("fb.refresh", r.optString("refresh_token", refresh))
                .putLong("fb.expires", System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000 - 60000).apply();
        return id;
    }

    // ------------------------------------------------------------------ settings sync (Realtime Database)

    /**
     * Settings live at /droid_codology/users/{uid}/settings as one JSON string (setting names contain
     * dots, which database keys can't). The database's ETag makes writes conditional, so a write
     * never overwrites what another device saved in between (412 → pull first).
     */
    @Override
    public String sync(Account.SyncData data, boolean forceUpload) throws IOException {
        try {
            String url = DB + "/droid_codology/users/" + Uri.encode(sp.getString("fb.uid", "")) + "/settings.json?auth=" + Uri.encode(idToken());
            HashMap<String, String> h = new HashMap<String, String>();
            h.put("X-Firebase-ETag", "true");
            Http.Response got = Http.request("GET", url, h, null, null);
            if (!got.ok()) json(got);
            String etag = got.header("ETag");
            Object value = new JSONTokener(got.text().isEmpty() ? "null" : got.text()).nextValue();
            String remote = value instanceof String ? (String) value : null;
            JSONObject local = data.collect();
            if (!forceUpload && remote != null && etag != null && !etag.equals(sp.getString("fb.etag", ""))) {
                JSONObject rd = new JSONObject(remote);
                data.apply(rd);
                sp.edit().putString("fb.etag", etag).putString("fb.hash", Account.hash(rd)).putLong("lastSync", System.currentTimeMillis()).apply();
                return "pulled";
            }
            String hash = Account.hash(local);
            if (!forceUpload && remote != null && hash.equals(sp.getString("fb.hash", ""))) {
                sp.edit().putLong("lastSync", System.currentTimeMillis()).apply();
                return "unchanged";
            }
            if (etag != null) h.put("if-match", etag);
            Http.Response put = Http.request("PUT", url, h, JSONObject.quote(local.toString()).getBytes("UTF-8"), "application/json");
            if (put.code == 412) throw new Account.Conflict("changed on another device");
            if (!put.ok()) json(put);
            String newTag = put.header("ETag");
            if (newTag == null) {
                // Learn the new version so the next sync doesn't take our own write for someone else's.
                newTag = Http.request("GET", url, h, null, null).header("ETag");
            }
            sp.edit().putString("fb.etag", newTag == null ? "" : newTag).putString("fb.hash", hash)
                    .putLong("lastSync", System.currentTimeMillis()).apply();
            return "pushed";
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }

    // ------------------------------------------------------------------ HTTP

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    private static JSONObject post(String url, JSONObject body) throws IOException {
        return json(Http.request("POST", url + "?key=" + Uri.encode(BuildConfig.FIREBASE_API_KEY), null,
                body.toString().getBytes("UTF-8"), "application/json"));
    }

    private static JSONObject json(Http.Response r) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(r.text());
        } catch (JSONException e) {
            o = new JSONObject();
        }
        if (!r.ok()) {
            Object err = o.opt("error");
            String code = err instanceof JSONObject ? ((JSONObject) err).optString("message", "") : err != null ? String.valueOf(err) : "";
            throw new Account.ServerError(r.code, readable(code, r.code));
        }
        return o;
    }

    /** Firebase's error codes in words. */
    static String readable(String code, int http) {
        if (code.startsWith("Permission denied")) return "The database doesn't allow this (check its rules, see ACCOUNTS.md)";
        String c = code.contains(" ") ? code.substring(0, code.indexOf(' ')) : code;
        if (c.equals("EMAIL_EXISTS")) return "An account with this email already exists";
        if (c.equals("EMAIL_NOT_FOUND") || c.equals("INVALID_PASSWORD") || c.equals("INVALID_LOGIN_CREDENTIALS")) return "Wrong email or password";
        if (c.equals("INVALID_EMAIL")) return "Enter a valid email address";
        if (c.startsWith("WEAK_PASSWORD")) return "The password must be at least 6 characters long";
        if (c.equals("USER_DISABLED")) return "This account has been disabled";
        if (c.equals("TOO_MANY_ATTEMPTS_TRY_LATER")) return "Too many attempts, try again later";
        if (c.equals("OPERATION_NOT_ALLOWED")) return "Email sign-in is turned off in this Firebase project";
        if (c.equals("TOKEN_EXPIRED") || c.equals("INVALID_REFRESH_TOKEN") || c.equals("USER_NOT_FOUND")) return "Please sign in again";
        return c.isEmpty() ? "HTTP " + http : code;
    }
}
