package dev.olatool;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * App PIN lock. Guards the whole app (and the stored Ola token behind it) so a
 * phone left unlocked can't be used to see the scooter's location or battery.
 *
 * <p>The PIN is never stored in the clear: only a random 16-byte salt and the
 * SHA-256 of salt+PIN are kept. Verification is constant-time. The lock state
 * is process-wide and starts locked, so the gate shows on every cold start and
 * again whenever the app returns from the background.
 */
public final class Lock {

    private static final String PREFS = "ola";
    private static final String KEY_HASH = "pin_hash";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_LEN = "pin_len";

    /** Process-wide: true until the correct PIN is entered this session. */
    private static boolean locked = true;

    private Lock() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSet(Context c) {
        return !prefs(c).getString(KEY_HASH, "").isEmpty();
    }

    /**
     * True when any app-open gate is configured — the numeric PIN, the
     * {@link Pattern} lock, or both. The gate and the background re-lock key off
     * this, so a pattern-only setup still guards the app.
     */
    public static boolean anySet(Context c) {
        return isSet(c) || Pattern.isSet(c);
    }

    /** The stored PIN length, for the unlock pad; 0 when no PIN is set. */
    public static int length(Context c) {
        return prefs(c).getInt(KEY_LEN, 0);
    }

    public static void setPin(Context c, String pin) {
        byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        String salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP);
        prefs(c).edit()
                .putString(KEY_SALT, salt)
                .putString(KEY_HASH, hash(salt, pin))
                .putInt(KEY_LEN, pin.length())
                .apply();
        locked = false;
    }

    /**
     * True when {@code pin} matches the stored PIN. Pure: it does <em>not</em>
     * unlock the app on its own, so when a {@link Pattern} is also set the PIN
     * cannot bypass it. The gate calls {@link #unlock()} only after every
     * configured factor has passed.
     */
    public static boolean verify(Context c, String pin) {
        String salt = prefs(c).getString(KEY_SALT, "");
        String want = prefs(c).getString(KEY_HASH, "");
        if (salt.isEmpty() || want.isEmpty()) {
            return true;   // no PIN set means nothing to check
        }
        return constEquals(want, hash(salt, pin));
    }

    /** Marks the app unlocked for this session. Called once all factors pass. */
    public static void unlock() {
        locked = false;
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(KEY_HASH).remove(KEY_SALT).remove(KEY_LEN).apply();
        locked = false;
    }

    /** Called when the app goes to the background, so it re-locks on return. */
    public static void lockNow() {
        locked = true;
    }

    public static boolean isLocked() {
        return locked;
    }

    // ------------------------------------------------------------------ crypto

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Base64.decode(salt, Base64.NO_WRAP));
            byte[] digest = md.digest(pin.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean constEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
