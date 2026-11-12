package dev.olatool;

import android.content.Context;
import android.content.SharedPreferences;

import dev.olatool.core.Live;

/**
 * Widget data bridge: the last good reading, saved so a widget can draw it
 * while the app is closed and the scooter is asleep.
 */
public final class WidgetData {

    private static final String PREFS = "widget_data";
    private static final String KEY_SOC = "soc";
    private static final String KEY_SOH = "soh";
    private static final String KEY_RANGE = "range_km";
    private static final String KEY_SPEED = "speed_kph";
    private static final String KEY_RPM = "rpm";
    private static final String KEY_PACK_C = "pack_c";
    private static final String KEY_MCU_C = "mcu_c";
    private static final String KEY_MOTOR_C = "motor_c";
    private static final String KEY_DRIVE_MODE = "drive_mode";
    private static final String KEY_CHARGING = "charging";
    private static final String KEY_PLUGGED = "plugged";
    private static final String KEY_FLEET_COUNT = "fleet_count";
    private static final String KEY_FLEET_ONLINE = "fleet_online";
    private static final String KEY_UPDATED_AT = "updated_at";

    private WidgetData() {}

    /**
     * Saves a live reading. A snapshot with no SOC is skipped: the bus resets to
     * empty on every reconnect, and that must not wipe the last known values.
     */
    public static boolean update(Context c, Live.Snapshot s) {
        if (s == null || Double.isNaN(s.soc)) {
            return false;
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_SOC, (float) s.soc)
                .putFloat(KEY_SOH, (float) s.soh)
                .putFloat(KEY_RANGE, (float) s.rangeKm)
                .putFloat(KEY_SPEED, (float) s.speedKph)
                .putFloat(KEY_RPM, (float) s.rpm)
                .putFloat(KEY_PACK_C, (float) s.packC)
                .putFloat(KEY_MCU_C, (float) s.mcuC)
                .putFloat(KEY_MOTOR_C, (float) s.motorC)
                .putString(KEY_DRIVE_MODE, s.driveMode == null ? "" : s.driveMode)
                .putInt(KEY_CHARGING, s.charging)
                .putInt(KEY_PLUGGED, s.plugged)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
        return true;
    }

    /** Saves how many scooters are saved, keeping the last known online count. */
    public static void syncFleetCount(Context c, int fleetCount) {
        int online = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_FLEET_ONLINE, 0);
        updateFleet(c, fleetCount, Math.min(online, fleetCount));
    }

    /** Saves the fleet counts from the Fleet tab's last poll. */
    public static void updateFleet(Context c, int fleetCount, int fleetOnline) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_FLEET_COUNT, fleetCount)
                .putInt(KEY_FLEET_ONLINE, fleetOnline)
                .apply();
    }

    public static Snapshot read(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Snapshot(prefs);
    }

    public static void clear(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    public static final class Snapshot {
        public final float soc, soh, rangeKm, speedKph, rpm, packC, mcuC, motorC;
        public final String driveMode;
        public final int charging, plugged, fleetCount, fleetOnline;
        public final long updatedAt;

        Snapshot(SharedPreferences p) {
            this.soc = p.getFloat(KEY_SOC, Float.NaN);
            this.soh = p.getFloat(KEY_SOH, Float.NaN);
            this.rangeKm = p.getFloat(KEY_RANGE, Float.NaN);
            this.speedKph = p.getFloat(KEY_SPEED, Float.NaN);
            this.rpm = p.getFloat(KEY_RPM, Float.NaN);
            this.packC = p.getFloat(KEY_PACK_C, Float.NaN);
            this.mcuC = p.getFloat(KEY_MCU_C, Float.NaN);
            this.motorC = p.getFloat(KEY_MOTOR_C, Float.NaN);
            this.driveMode = p.getString(KEY_DRIVE_MODE, "");
            this.charging = p.getInt(KEY_CHARGING, -1);
            this.plugged = p.getInt(KEY_PLUGGED, -1);
            this.fleetCount = p.getInt(KEY_FLEET_COUNT, 0);
            this.fleetOnline = p.getInt(KEY_FLEET_ONLINE, 0);
            this.updatedAt = p.getLong(KEY_UPDATED_AT, 0);
        }

        public boolean hasData() { return !Float.isNaN(soc) && updatedAt > 0; }
        public boolean isCharging() { return charging == 1; }
        public boolean isPlugged() { return plugged == 1; }
    }
}
