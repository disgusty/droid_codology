package com.disgusty.droid_codology.work;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.disgusty.droid_codology.ui.Skin;

import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/**
 * An optional password for opening the app. Only a salted PBKDF2-HMAC-SHA256 hash is kept, in a
 * preferences file of its own that isn't synced or backed up. The app locks when it starts and
 * after a chosen time in the background. It guards against someone picking up the phone; it does
 * not encrypt the files (Android's own storage encryption does that).
 */
final class AppLock {
    static final String FILE = "lock";
    private static final int ITERATIONS = 20000;
    private static final int MAX_TRIES = 5;
    private static final long WAIT_MS = 30000;

    private final SharedPreferences sp;
    private boolean locked;
    private long backgroundSince = -1;
    private int failures;
    private long waitUntil;
    private View overlay;

    AppLock(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        locked = enabled(); // a cold start always asks
    }

    boolean enabled() {
        return sp.getString("hash", null) != null;
    }

    boolean isLocked() {
        return locked && enabled();
    }

    /** Minutes in the background before locking; 0 = right away. */
    int delayMinutes() {
        return sp.getInt("delay", 1);
    }

    void setDelayMinutes(int m) {
        sp.edit().putInt("delay", m).apply();
    }

    void setPassword(String pw) {
        // urandom-backed (Android 4.1-4.3 seeded SecureRandom badly)
        byte[] salt = com.disgusty.droid_codology.net.tls.TlsClient.random(16);
        sp.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("hash", Base64.encodeToString(pbkdf2(pw, salt, ITERATIONS), Base64.NO_WRAP))
                .putInt("iterations", ITERATIONS).apply();
    }

    void clear() {
        sp.edit().remove("hash").remove("salt").remove("iterations").apply();
        locked = false;
    }

    boolean check(String pw) {
        String h = sp.getString("hash", null), s = sp.getString("salt", null);
        if (h == null || s == null) return true;
        byte[] want = Base64.decode(h, Base64.NO_WRAP);
        byte[] got = pbkdf2(pw, Base64.decode(s, Base64.NO_WRAP), sp.getInt("iterations", ITERATIONS));
        return constantTimeEquals(want, got);
    }

    // ------------------------------------------------------------------ lifecycle

    void onPause() {
        backgroundSince = SystemClock.elapsedRealtime();
    }

    /** True when the app must be locked now (after the chosen time in the background). */
    boolean onResume() {
        if (!enabled()) return false;
        if (backgroundSince >= 0 && SystemClock.elapsedRealtime() - backgroundSince >= delayMinutes() * 60000L) locked = true;
        backgroundSince = -1;
        return locked;
    }

    /** "Lock Now" from the palette. */
    void lockNow() {
        if (enabled()) locked = true;
    }

    // ------------------------------------------------------------------ the lock screen

    /** Puts the lock screen over {@code root} when locked (also after the UI is rebuilt). */
    void attach(final Workbench wb, FrameLayout root) {
        if (overlay != null && overlay.getParent() != null) ((ViewGroup) overlay.getParent()).removeView(overlay);
        overlay = null;
        if (!isLocked()) return;
        final Skin skin = wb.skin;
        FrameLayout scrim = new FrameLayout(wb.activity);
        scrim.setBackgroundColor(skin.windowBg | 0xFF000000);
        scrim.setClickable(true); // nothing behind it can be touched
        scrim.setFocusable(true);
        LinearLayout card = new LinearLayout(wb.activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(skin.dp(24), skin.dp(24), skin.dp(24), skin.dp(24));
        ImageView icon = new ImageView(wb.activity);
        icon.setImageDrawable(skin.icon("lock", skin.accent, 48));
        card.addView(icon, new LinearLayout.LayoutParams(skin.dp(64), skin.dp(64)));
        TextView title = skin.text("droid_codology", skin.uiSp() + 6, skin.sideBarFg, skin.medium());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, skin.dp(12), 0, skin.dp(4));
        card.addView(title);
        TextView sub = skin.text(t("Enter the app password"), skin.uiSp(), skin.descriptionFg, skin.regular());
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, skin.dp(16));
        card.addView(sub);
        final EditText field = skin.input(t("Password"));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        card.addView(field, new LinearLayout.LayoutParams(skin.dp(280), ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView error = skin.text("", skin.uiSp() - 1, skin.errorFg, skin.regular());
        error.setGravity(Gravity.CENTER);
        error.setPadding(0, skin.dp(8), 0, skin.dp(8));
        card.addView(error);
        final Runnable tryUnlock = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                if (now < waitUntil) {
                    error.setText(f("Too many attempts. Try again in {0} s.", (waitUntil - now + 999) / 1000));
                    return;
                }
                if (check(field.getText().toString())) {
                    failures = 0;
                    locked = false;
                    field.setText("");
                    wb.hideKeyboardFrom(field);
                    if (overlay != null && overlay.getParent() != null) ((ViewGroup) overlay.getParent()).removeView(overlay);
                    overlay = null;
                    wb.onUnlocked();
                } else {
                    failures++;
                    field.setText("");
                    if (failures >= MAX_TRIES) {
                        failures = 0;
                        waitUntil = now + WAIT_MS;
                        error.setText(f("Too many attempts. Try again in {0} s.", WAIT_MS / 1000));
                    } else {
                        error.setText(t("Wrong password."));
                    }
                }
            }
        };
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                tryUnlock.run();
                return true;
            }
        });
        card.addView(skin.button(t("Unlock"), true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryUnlock.run();
            }
        }));
        TextView forgot = skin.text(t("Forgot it? Clearing the app's data in Android's settings removes the password, together with the app's settings and unsaved files."),
                skin.uiSp() - 3, skin.descriptionFg, skin.regular());
        forgot.setGravity(Gravity.CENTER);
        forgot.setPadding(0, skin.dp(24), 0, 0);
        card.addView(forgot, new LinearLayout.LayoutParams(skin.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT));
        scrim.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = scrim;
        field.requestFocus();
    }

    // ------------------------------------------------------------------ crypto

    /** PBKDF2 with HMAC-SHA256 (RFC 8018), 32 bytes; done by hand: Android 4.x's PBKDF2 mishandled non-ASCII. */
    static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] pw = password.getBytes("UTF-8");
            // SecretKeySpec refuses an empty key; HMAC pads keys with zeros, so {0} is the same key.
            mac.init(new SecretKeySpec(pw.length == 0 ? new byte[1] : pw, "HmacSHA256"));
            byte[] block = new byte[salt.length + 4];
            System.arraycopy(salt, 0, block, 0, salt.length);
            block[salt.length + 3] = 1; // block index 1 (32 bytes are one SHA-256 block)
            byte[] u = mac.doFinal(block);
            byte[] out = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int k = 0; k < out.length; k++) out[k] ^= u[k];
            }
            Arrays.fill(pw, (byte) 0);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int d = 0;
        for (int i = 0; i < a.length; i++) d |= a[i] ^ b[i];
        return d == 0;
    }
}
