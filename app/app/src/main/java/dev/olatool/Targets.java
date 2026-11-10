package dev.olatool;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Saved connection targets for Direct mode: a nickname paired with an address
 * (public IP, global IPv6, or host:port). Stored on-device only, as JSON in the
 * shared prefs.
 */
public final class Targets {

    private static final String PREFS = "ola";
    private static final String KEY = "saved_targets";

    public static final class Target {
        public final String name;
        public final String address;

        public Target(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    private Targets() {
    }

    public static List<Target> load(Context c) {
        List<Target> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String a = o.optString("address", "").trim();
                if (a.isEmpty()) {
                    continue;
                }
                String n = o.optString("name", a).trim();
                out.add(new Target(n.isEmpty() ? a : n, a));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void add(Context c, String name, String address) {
        String a = address == null ? "" : address.trim();
        if (a.isEmpty()) {
            return;
        }
        String n = name == null ? "" : name.trim();
        List<Target> list = load(c);
        // Replace an existing entry with the same address, else append.
        List<Target> next = new ArrayList<>();
        boolean replaced = false;
        for (Target t : list) {
            if (t.address.equalsIgnoreCase(a)) {
                next.add(new Target(n.isEmpty() ? a : n, a));
                replaced = true;
            } else {
                next.add(t);
            }
        }
        if (!replaced) {
            next.add(new Target(n.isEmpty() ? a : n, a));
        }
        save(c, next);
    }

    public static void remove(Context c, String address) {
        List<Target> list = load(c);
        List<Target> next = new ArrayList<>();
        for (Target t : list) {
            if (!t.address.equalsIgnoreCase(address)) {
                next.add(t);
            }
        }
        save(c, next);
    }

    private static void save(Context c, List<Target> list) {
        JSONArray arr = new JSONArray();
        for (Target t : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", t.name);
                o.put("address", t.address);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
