#!/usr/bin/env python3
"""Create all Pegasus widget files."""

import os
from pathlib import Path

BASE = Path(r"D:\claude-work\ola-s1-rea\app\app\src\main")
JAVA_DIR = BASE / "java" / "dev" / "olatool"
RES_DIR = BASE / "res"
LAYOUT_DIR = RES_DIR / "layout"
DRAWABLE_DIR = RES_DIR / "drawable"
XML_DIR = RES_DIR / "xml"

for d in [JAVA_DIR, LAYOUT_DIR, DRAWABLE_DIR, XML_DIR]:
    d.mkdir(parents=True, exist_ok=True)

WIDGET_DATA = '''package dev.olatool;

import android.content.Context;
import android.content.preferencesSharedPreferences;

/**
 * Widget data bridge — writes the latest bus/cloud snapshot to SharedPreferences
 * so widgets can read it without needing the app process to be alive.
 *
 * <p>Widgets run in the launcher process, not the app process. They cannot
 * access the live {@link Bus} or {@link Conn}. This class serialises the
 * minimum set of signals a widget needs into a single preference file.
 */
public final class WidgetData {

    private static final String PREFS = "widget_data";
    private static final String KEY_SOC = "soc";
    private static final String KEY_SOH = "soh";
    private static final String KEY_RANGE = "range_km";
    private static final String KEY_SPEED = "speed_kph";
    private static final String KEY_RPM = "rpm";
    private static final String KEY_PACK_V = "pack_v";
    private static final String KEY_PACK_A = "pack_a";
    private static final String KEY_PACK_C = "pack_c";
    private static final String KEY_MCU_C = "mcu_c";
    private static final String KEY_MOTOR_C = "motor_c";
    private static final String KEY_DRIVE_MODE = "drive_mode";
    private static final String KEY_CHARGING = "charging";
    private static final String KEY_PLUGGED = "plugged";
    private static final String KEY_UPDATED_AT = "updated_at";
    private static final String KEY_FLEET_COUNT = "fleet_count";
    private static final String KEY_FLEET_ONLINE = "fleet_online";

    private WidgetData() {
    }

    /**
     * Writes the latest snapshot into preferences. Call this from the Bus tick
     * or a dedicated widget updater — do not call it from the widget itself.
     */
    public static void update(Context c, Live.Snapshot s, String driveMode,
                              int fleetCount, int fleetOnline) {
        SharedPreferences prefs = cSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = prefs.edit();

        if (s != null) {
            e.putFloat(KEY_SOC, (float) s.soc);
            e.putFloat(KEY_SOH, (float) s.soh);
            e.putFloat(KEY_RANGE, (float) s.rangeKm);
            e.putFloat(KEY_SPEED, (float) s.speedKph);
            e.putFloat(KEY_RPM, (float) s.rpm);
            e.putFloat(KEY_PACK_V, (float) s.packV);
            e.putFloat(KEY_PACK_A, (float) s.packA);
            e.putFloat(KEY_PACK_C, (float) s.packC);
            e.putFloat(KEY_MCU_C, (float) s.mcuC);
            e.putFloat(KEY_MOTOR_C, (float) s.motorC);
            e.putString(KEY_DRIVE_MODE, driveMode == null ? "" : driveMode);
            e.putInt(KEY_CHARGING, s.charging);
            e.putInt(KEY_PLUGGED, s.plugged);
        } else {
            e.putFloat(KEY_SOC, Float.NaN);
            e.putFloat(KEY_SOH, Float.NaN);
            e.putFloat(KEY_RANGE, Float.NaN);
            e.putFloat(KEY_SPEED, Float.NaN);
            e.putFloat(KEY_RPM, Float.NaN);
            e.putFloat(KEY_PACK_V, Float.NaN);
            e.putFloat(KEY_PACK_A, Float.NaN);
            e.putFloat(KEY_PACK_C, Float.NaN);
            e.putFloat(KEY_MCU_C, Float.NaN);
            e.putFloat(KEY_MOTOR_C, Float.NaN);
            e.putString(KEY_DRIVE_MODE, "");
            e.putInt(KEY_CHARGING, -1);
            e.putInt(KEY_PLUGGED, -1);
        }

        e.putInt(KEY_FLEET_COUNT, fleetCount);
        e.putInt(KEY_FLEET_ONLINE, fleetOnline);
        e.putLong(KEY_UPDATED_AT, System.currentTimeMillis());
        e.apply();
    }

    /** Reads the last written values. Returns a fresh instance every time. */
    public static Snapshot read(Context c) {
        SharedPreferences prefs = cSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Snapshot(prefs);
    }

    /** Clears all widget data, e.g. on logout. */
    public static void clear(Context c) {
        SharedPreferences prefs = cSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    /** A read-only snapshot of the widget preferences. */
    public static final class Snapshot {
        public final float soc;
        public final float soh;
        public final float rangeKm;
        public final float speedKph;
        public final float rpm;
        public final float packV;
        public final float packA;
        public final float packC;
        public final float mcuC;
        public final float motorC;
        public final String driveMode;
        public final int charging;
        public final int plugged;
        public final int fleetCount;
        public final int fleetOnline;
        public final long updatedAt;

        Snapshot(SharedPreferences p) {
            this.soc = p.getFloat(KEY_SOC, Float.NaN);
            this.soh = p.getFloat(KEY_SOH, Float.NaN);
            this.rangeKm = p.getFloat(KEY_RANGE, Float.NaN);
            this.speedKph = p.getFloat(KEY_SPEED, Float.NaN);
            this.rpm = p.getFloat(KEY_RPM, Float.NaN);
            this.packV = p.getFloat(KEY_PACK_V, Float.NaN);
            this.packA = p.getFloat(KEY_PACK_A, Float.NaN);
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

        public boolean hasData() {
            return !Float.isNaN(soc) && updatedAt > 0;
        }

        public boolean isCharging() {
            return charging == 1;
        }

        public boolean isPlugged() {
            return plugged == 1;
        }
    }
}
'''
