package dev.olatool;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * App pattern lock — an optional second factor layered on top of the numeric
 * {@link Lock}. When both are set the app asks for the pattern first and the PIN
 * second, so a shoulder-surfed PIN alone is not enough to open the tool.
 *
 * <p>Like {@link Lock}, the secret is never stored in the clear: only a random
 * 16-byte salt and {@code SHA-256(salt + canonical-sequence)} are kept, and the
 * check is constant-time. The drawn pattern is reduced to a canonical string of
 * dot indices (e.g. {@code "0-1-2-5"}) before hashing, so the stored form never
 * reveals the shape.
 *
 * <p>This class only stores and verifies; it does <em>not</em> own the
 * process-wide locked flag. {@link MainActivity} orchestrates the gate and calls
 * {@link Lock#unlock()} only once every configured factor has passed — that is
 * what keeps the pattern a real added layer rather than a bypassable alternative.
 */
public final class Pattern {

    private static final String PREFS = "ola";
    private static final String KEY_HASH = "pat_hash";
    private static final String KEY_SALT = "pat_salt";
    private static final String KEY_LEN = "pat_len";

    /** Fewest dots a valid pattern may connect. */
    public static final int MIN_DOTS = 4;

    private Pattern() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSet(Context c) {
        return !prefs(c).getString(KEY_HASH, "").isEmpty();
    }

    /** Number of dots in the stored pattern, for a progress hint; 0 when unset. */
    public static int length(Context c) {
        return prefs(c).getInt(KEY_LEN, 0);
    }

    public static void setPattern(Context c, int[] nodes) {
        byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        String salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP);
        prefs(c).edit()
                .putString(KEY_SALT, salt)
                .putString(KEY_HASH, hash(salt, canon(nodes)))
                .putInt(KEY_LEN, nodes == null ? 0 : nodes.length)
                .apply();
    }

    /** True when {@code nodes} matches the stored pattern. No side effects. */
    public static boolean verify(Context c, int[] nodes) {
        String salt = prefs(c).getString(KEY_SALT, "");
        String want = prefs(c).getString(KEY_HASH, "");
        if (salt.isEmpty() || want.isEmpty()) {
            return true;   // no pattern set means nothing to check
        }
        return constEquals(want, hash(salt, canon(nodes)));
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(KEY_HASH).remove(KEY_SALT).remove(KEY_LEN).apply();
    }

    // ------------------------------------------------------------------ crypto

    /** Dot indices joined with '-', the one shape the hash is taken over. */
    private static String canon(int[] nodes) {
        if (nodes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(nodes.length * 2);
        for (int i = 0; i < nodes.length; i++) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(nodes[i]);
        }
        return sb.toString();
    }

    private static String hash(String salt, String seq) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Base64.decode(salt, Base64.NO_WRAP));
            byte[] digest = md.digest(seq.getBytes(StandardCharsets.UTF_8));
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
