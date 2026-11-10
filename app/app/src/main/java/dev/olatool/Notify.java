package dev.olatool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Local alerts for the owner's own scooter: low battery and charge-complete.
 * Opt-in (a prefs toggle) and de-duplicated so a repeating poll does not spam.
 * Nothing leaves the device.
 */
public final class Notify {

    private static final String PREFS = "ola";
    private static final String KEY_ON = "notify_on";
    private static final String KEY_LAST_LOW = "notify_last_low";   // soc bucket already warned
    private static final String KEY_WAS_FULL = "notify_was_full";
    private static final String CHANNEL = "ola_cloud";
    private static final int ID_LOW = 101;
    private static final int ID_FULL = 102;
    private static final int LOW_THRESHOLD = 15;

    private Notify() {
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(KEY_ON, false);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
        if (on) {
            ensureChannel(c);
        }
    }

    /** Checks a fresh snapshot and fires an alert when a threshold is crossed. */
    public static void onStatus(Context c, OlaCloud.Status s) {
        if (!enabled(c) || s == null || !s.ok || s.soc == null) {
            return;
        }
        int soc = Math.round(s.soc);
        boolean charging = charging(s);
        SharedPreferences p = prefs(c);

        // Low battery — warn once per time it drops below threshold.
        if (!charging && soc <= LOW_THRESHOLD) {
            if (!p.getBoolean(KEY_LAST_LOW, false)) {
                show(c, ID_LOW, "Scooter battery low",
                        soc + "% left — plug it in soon.");
                p.edit().putBoolean(KEY_LAST_LOW, true).apply();
            }
        } else if (soc > LOW_THRESHOLD + 5) {
            p.edit().putBoolean(KEY_LAST_LOW, false).apply();
        }

        // Charge complete — fire once on the full edge.
        boolean full = charging && soc >= 99;
        if (full && !p.getBoolean(KEY_WAS_FULL, false)) {
            show(c, ID_FULL, "Scooter fully charged", soc + "% — charging done.");
            p.edit().putBoolean(KEY_WAS_FULL, true).apply();
        } else if (!charging || soc < 95) {
            p.edit().putBoolean(KEY_WAS_FULL, false).apply();
        }
    }

    private static boolean charging(OlaCloud.Status s) {
        String x = s.charging != null ? s.charging : s.chargingEx;
        return x != null && x.toUpperCase().contains("CHARG") && !x.toUpperCase().contains("NOT");
    }

    private static void show(Context c, int id, String title, String text) {
        try {
            ensureChannel(c);
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent open = new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            }
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(c, id, open, flags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CHANNEL)
                    : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(id, b.build());
        } catch (Exception ignored) {
        }
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "Scooter alerts", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Low battery and charge-complete alerts");
            nm.createNotificationChannel(ch);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
