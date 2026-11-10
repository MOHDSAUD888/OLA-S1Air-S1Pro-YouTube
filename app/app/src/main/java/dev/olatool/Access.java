package dev.olatool;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

/**
 * App-wide access level, chosen by the owner, that gates what the tool is
 * allowed to do:
 *
 * <ul>
 *   <li><b>READ</b> — read-only telemetry. No command, no write, no mode change.</li>
 *   <li><b>EDIT</b> — read plus harmless, non-destructive actions (open Android
 *       screens, local-only dev switches).</li>
 *   <li><b>ADMIN</b> — full access: shell, ADB provisioning, mode switch,
 *       TechPack, everything.</li>
 * </ul>
 *
 * <p>This is a self-imposed guard so a quick glance can't fat-finger a write;
 * it defaults to READ. Not a security boundary against someone who has the app.
 */
public final class Access {

    public static final int READ = 0;
    public static final int EDIT = 1;
    public static final int ADMIN = 2;

    private static final String PREFS = "ola";
    private static final String KEY = "access_level";

    private Access() {
    }

    public static int level(Context c) {
        return prefs(c).getInt(KEY, READ);
    }

    public static void setLevel(Context c, int level) {
        int v = level < READ ? READ : (level > ADMIN ? ADMIN : level);
        prefs(c).edit().putInt(KEY, v).apply();
    }

    public static String name(int level) {
        switch (level) {
            case ADMIN:
                return "Admin (full access)";
            case EDIT:
                return "Edit";
            default:
                return "Read-only";
        }
    }

    public static boolean allows(Context c, int needed) {
        return level(c) >= needed;
    }

    /**
     * Convenience gate: returns true when the current level is enough, else
     * shows a toast naming what is required and returns false.
     */
    public static boolean require(Context c, int needed) {
        if (allows(c, needed)) {
            return true;
        }
        Toast.makeText(c, "Needs " + name(needed)
                + " access. Change it in Settings → Access level.",
                Toast.LENGTH_LONG).show();
        return false;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
