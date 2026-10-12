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

/**
 * Signing in with Google without a droid_codology server: OAuth 2.0 for installed apps
 * (browser + loopback redirect + PKCE) with a "Desktop app" client built into the app, and
 * settings sync to a file in the user's Google Drive app data folder (invisible in Drive).
 * Network methods block; call them off the UI thread.
 */
public final class GoogleAccount implements Cloud {
    static final String SCOPES = "openid email profile https://www.googleapis.com/auth/drive.appdata";
    private static String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static String TOKEN = "https://oauth2.googleapis.com/token";
    private static String USERINFO = "https://openidconnect.googleapis.com/v1/userinfo";
    private static String DRIVE = "https://www.googleapis.com/drive/v3/files";
    private static String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static String REVOKE = "https://oauth2.googleapis.com/revoke";
    private static String DEVICE = "https://oauth2.googleapis.com/device/code";
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static boolean testEndpoints;

    /** Debug builds: talk to tools/fake_google.py at {@code base} instead of Google. */
    public static void useTestEndpoints(String base) {
        if (!BuildConfig.DEBUG) return;
        AUTH = base + "/o/oauth2/v2/auth";
        TOKEN = base + "/token";
        USERINFO = base + "/userinfo";
        DRIVE = base + "/drive/v3/files";
        UPLOAD = base + "/upload/drive/v3/files";
        REVOKE = base + "/revoke";
        DEVICE = base + "/device/code";
        testEndpoints = true;
    }

    private static final String FILE_NAME = "droid_codology-settings.json";

    private final SharedPreferences sp;

    GoogleAccount(SharedPreferences sp) {
        this.sp = sp;
    }

    /** Whether this build has a Google client to sign in with. */
    public static boolean configured() {
        return testEndpoints || !BuildConfig.GOOGLE_CLIENT_ID.isEmpty();
    }

    /** Whether this build can sign in with a code on another device (Android 4.x browsers can't open Google's page). */
    public static boolean deviceConfigured() {
        return testEndpoints || !BuildConfig.GOOGLE_DEVICE_CLIENT_ID.isEmpty();
    }

    public boolean signedIn() {
        return sp.getString("g.refresh", null) != null;
    }

    /** Tokens are refreshed with the client that issued them. */
    private boolean viaDevice() {
        return "device".equals(sp.getString("g.client", ""));
    }

    private String clientId() {
        return viaDevice() ? BuildConfig.GOOGLE_DEVICE_CLIENT_ID : BuildConfig.GOOGLE_CLIENT_ID;
    }

    private String clientSecret() {
        return viaDevice() ? BuildConfig.GOOGLE_DEVICE_CLIENT_SECRET : BuildConfig.GOOGLE_CLIENT_SECRET;
    }

    public String email() {
        return sp.getString("g.email", "");
    }

    public String name() {
        return sp.getString("g.name", "");
    }

    @Override
    public String displayName() {
        String n = name(), e = email();
        return n.isEmpty() ? e : e.isEmpty() ? n : n + " (" + e + ")";
    }

    @Override
    public String storage() {
        return "Google Drive";
    }

    // ------------------------------------------------------------------ sign-in

    /** Browser URL for a sign-in that redirects to {@code lb}. */
    public static String authUrl(Loopback lb) {
        return AUTH + "?client_id=" + Uri.encode(BuildConfig.GOOGLE_CLIENT_ID)
                + "&redirect_uri=" + Uri.encode(lb.redirectUri())
                + "&response_type=code&scope=" + Uri.encode(SCOPES)
                + "&code_challenge=" + lb.challenge() + "&code_challenge_method=S256"
                + "&state=" + lb.state + "&access_type=offline&prompt=consent";
    }

    /** Finishes sign-in with the browser's redirect (from {@link Loopback#await}). */
    public void finish(Loopback lb, Uri redirect) throws IOException {
        String err = redirect.getQueryParameter("error");
        if (err != null) throw new IOException(err.equals("access_denied") ? "cancelled" : err);
        String code = redirect.getQueryParameter("code");
        if (code == null) throw new IOException("no code");
        JSONObject t = form(TOKEN, "code=" + Uri.encode(code)
                + "&client_id=" + Uri.encode(BuildConfig.GOOGLE_CLIENT_ID)
                + "&client_secret=" + Uri.encode(BuildConfig.GOOGLE_CLIENT_SECRET)
                + "&redirect_uri=" + Uri.encode(lb.redirectUri())
                + "&grant_type=authorization_code&code_verifier=" + Uri.encode(lb.verifier));
        signedIn(t, "desktop");
    }

    private void signedIn(JSONObject t, String client) throws IOException {
        String refresh = t.optString("refresh_token", "");
        if (refresh.isEmpty()) throw new IOException("Google did not grant offline access");
        sp.edit().putString("g.refresh", refresh).putString("g.access", t.optString("access_token")).putString("g.client", client)
                .putLong("g.expires", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000 - 60000)
                .remove("g.version").remove("g.hash").apply();
        refreshProfile();
    }

    // ---- by code on another device (OAuth 2.0 for TV and limited-input devices)

    public static final class DeviceCode {
        public final String deviceCode, userCode, url;
        public final int interval;
        public final long expiresAt;

        DeviceCode(JSONObject o) {
            deviceCode = o.optString("device_code");
            userCode = o.optString("user_code");
            url = o.optString("verification_url", o.optString("verification_uri", "https://www.google.com/device"));
            interval = Math.max(2, o.optInt("interval", 5));
            expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 900) * 1000;
        }
    }

    public static DeviceCode deviceStart() throws IOException {
        return new DeviceCode(form(DEVICE, "client_id=" + Uri.encode(BuildConfig.GOOGLE_DEVICE_CLIENT_ID) + "&scope=" + Uri.encode(SCOPES)));
    }

    /** One check whether the user finished on the other device: false while waiting. */
    public boolean devicePoll(DeviceCode c) throws IOException {
        Http.Response r = Http.request("POST", TOKEN, null, ("client_id=" + Uri.encode(BuildConfig.GOOGLE_DEVICE_CLIENT_ID)
                + "&client_secret=" + Uri.encode(BuildConfig.GOOGLE_DEVICE_CLIENT_SECRET)
                + "&device_code=" + Uri.encode(c.deviceCode) + "&grant_type=" + Uri.encode(DEVICE_GRANT)).getBytes("UTF-8"),
                "application/x-www-form-urlencoded");
        if (r.ok()) {
            try {
                signedIn(new JSONObject(r.text()), "device");
            } catch (JSONException e) {
                throw new IOException(e.toString());
            }
            return true;
        }
        String err;
        try {
            err = new JSONObject(r.text()).optString("error", "HTTP " + r.code);
        } catch (JSONException e) {
            err = "HTTP " + r.code;
        }
        if (err.equals("authorization_pending") || err.equals("slow_down")) return false;
        throw new IOException(err.equals("access_denied") ? "cancelled" : err.equals("expired_token") ? "the code expired" : err);
    }

    public void refreshProfile() throws IOException {
        JSONObject u = json(Http.request("GET", USERINFO, auth(), null, null));
        sp.edit().putString("g.email", u.optString("email", "")).putString("g.name", u.optString("name", "")).apply();
    }

    public void signOut() {
        String t = sp.getString("g.refresh", null);
        sp.edit().remove("g.refresh").remove("g.access").remove("g.expires").remove("g.email").remove("g.name").remove("g.client")
                .remove("g.version").remove("g.hash").apply();
        if (t == null) return;
        try {
            Http.request("POST", REVOKE, null, ("token=" + Uri.encode(t)).getBytes("UTF-8"),
                    "application/x-www-form-urlencoded");
        } catch (IOException ignored) {
            // Forgetting the token locally is what matters.
        }
    }

    /** A valid access token, refreshed when it expired. */
    private String accessToken() throws IOException {
        String refresh = sp.getString("g.refresh", null);
        if (refresh == null) throw new Account.ServerError(401, "Not signed in to Google");
        if (System.currentTimeMillis() < sp.getLong("g.expires", 0)) return sp.getString("g.access", "");
        JSONObject t;
        try {
            t = form(TOKEN, "client_id=" + Uri.encode(clientId()) + "&client_secret=" + Uri.encode(clientSecret())
                    + "&refresh_token=" + Uri.encode(refresh) + "&grant_type=refresh_token");
        } catch (Account.ServerError e) {
            // invalid_grant: access was revoked in the Google account settings.
            if (e.code == 400 || e.code == 401) sp.edit().remove("g.refresh").remove("g.access").apply();
            throw e;
        }
        String access = t.optString("access_token");
        sp.edit().putString("g.access", access)
                .putLong("g.expires", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000 - 60000).apply();
        return access;
    }

    private HashMap<String, String> auth() throws IOException {
        HashMap<String, String> h = new HashMap<String, String>();
        h.put("Authorization", "Bearer " + accessToken());
        h.put("Accept", "application/json");
        return h;
    }

    private static JSONObject form(String url, String body) throws IOException {
        return json(Http.request("POST", url, null, body.getBytes("UTF-8"), "application/x-www-form-urlencoded"));
    }

    private static JSONObject json(Http.Response r) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(r.text());
        } catch (JSONException e) {
            o = new JSONObject();
        }
        if (!r.ok()) {
            Object e = o.opt("error");
            String msg = e instanceof JSONObject ? ((JSONObject) e).optString("message", "HTTP " + r.code)
                    : e != null ? o.optString("error_description", String.valueOf(e)) : "HTTP " + r.code;
            throw new Account.ServerError(r.code, msg);
        }
        return o;
    }

    // ------------------------------------------------------------------ settings sync (Drive app data)

    /** Same contract as {@link Account#sync}: newer remote data wins, else local data is uploaded. */
    @Override
    public String sync(Account.SyncData data, boolean forceUpload) throws IOException {
        try {
            JSONObject list = json(Http.request("GET", DRIVE + "?spaces=appDataFolder&fields=files(id%2Cversion)&q="
                    + Uri.encode("name='" + FILE_NAME + "'"), auth(), null, null));
            JSONArray files = list.optJSONArray("files");
            JSONObject file = files != null && files.length() > 0 ? files.getJSONObject(0) : null;
            String remoteVersion = file == null ? "" : file.optString("version", "");
            String known = sp.getString("g.version", "");
            JSONObject local = data.collect();
            if (!forceUpload && file != null && !remoteVersion.equals(known)) {
                Http.Response r = Http.request("GET", DRIVE + "/" + file.getString("id") + "?alt=media", auth(), null, null);
                if (!r.ok()) json(r);
                JSONObject rd = new JSONObject(r.text());
                data.apply(rd);
                sp.edit().putString("g.version", remoteVersion).putString("g.hash", Account.hash(rd))
                        .putLong("lastSync", System.currentTimeMillis()).apply();
                return "pulled";
            }
            String h = Account.hash(local);
            if (!forceUpload && file != null && h.equals(sp.getString("g.hash", ""))) {
                sp.edit().putLong("lastSync", System.currentTimeMillis()).apply();
                return "unchanged";
            }
            byte[] content = local.toString().getBytes("UTF-8");
            JSONObject saved;
            if (file == null) {
                String boundary = "dc" + Loopback.random(9);
                String meta = new JSONObject().put("name", FILE_NAME).put("parents", new JSONArray().put("appDataFolder")).toString();
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                b.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
                        + "\r\n--" + boundary + "\r\nContent-Type: application/json\r\n\r\n").getBytes("UTF-8"));
                b.write(content);
                b.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
                saved = json(Http.request("POST", UPLOAD + "?uploadType=multipart&fields=id%2Cversion", auth(), b.toByteArray(),
                        "multipart/related; boundary=" + boundary));
            } else {
                HashMap<String, String> hd = auth();
                // HttpURLConnection can't send PATCH; Google APIs accept the override header.
                hd.put("X-HTTP-Method-Override", "PATCH");
                saved = json(Http.request("POST", UPLOAD + "/" + file.getString("id") + "?uploadType=media&fields=id%2Cversion", hd,
                        content, "application/json"));
            }
            sp.edit().putString("g.version", saved.optString("version", "")).putString("g.hash", h)
                    .putLong("lastSync", System.currentTimeMillis()).apply();
            return "pushed";
        } catch (JSONException e) {
            throw new IOException(e.toString());
        }
    }
}
