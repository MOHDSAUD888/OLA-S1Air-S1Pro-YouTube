package dev.olatool;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.olatool.core.BatteryHealth;
import dev.olatool.core.Can;
import dev.olatool.core.Dbc;
import dev.olatool.core.Faults;
import dev.olatool.core.Live;
import dev.olatool.core.Rpc;

/**
 * The fleet model behind {@link FleetTab}: where the saved units live, and how
 * one unit is read without disturbing the main live connection.
 *
 * <p>A reading opens its own short-lived {@link Rpc} to the unit, samples the
 * CAN bus just long enough to catch a pack frame, reads the GPS fix out of
 * {@code dumpsys location}, and closes. It never touches {@link Conn} or
 * {@link Bus}, so polling the fleet does not interrupt whichever unit the other
 * tabs are watching.
 *
 * <p>Everything here is read-only. No vehicle state is written, no mode is
 * changed, and only units the owner saved are ever contacted.
 */
public final class Fleet {

    private static final String PREFS = "ola";
    private static final String KEY_FLEET = "fleet";

    /** How long to listen to the bus for a pack frame before giving up. */
    private static final long SAMPLE_MS = 2500;

    private Fleet() {
    }

    // ------------------------------------------------------------------ store

    public static List<Scooter> load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return Scooter.fromJson(p.getString(KEY_FLEET, ""));
    }

    public static void save(Context ctx, List<Scooter> list) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_FLEET, Scooter.toJson(list)).apply();
    }

    public static void add(Context ctx, Scooter s) {
        List<Scooter> list = load(ctx);
        for (Scooter existing : list) {
            if (existing.address.equalsIgnoreCase(s.address)) {
                existing.name = s.name;        // same address: just relabel
                save(ctx, list);
                return;
            }
        }
        list.add(s);
        save(ctx, list);
    }

    public static void remove(Context ctx, String id) {
        List<Scooter> list = load(ctx);
        List<Scooter> kept = new ArrayList<>();
        for (Scooter s : list) {
            if (!s.id.equals(id)) {
                kept.add(s);
            }
        }
        save(ctx, kept);
    }

    public static boolean has(Context ctx, String address) {
        for (Scooter s : load(ctx)) {
            if (s.address.equalsIgnoreCase(address)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ CAN tables

    private static volatile Dbc dbc;
    private static volatile Faults faults;

    /** Loads the shared signal and fault tables once, on the calling thread. */
    private static synchronized void ensureTables(Context ctx) throws Exception {
        if (dbc != null && faults != null) {
            return;
        }
        dbc = Dbc.parse(readAsset(ctx, "can_database.json"));
        faults = Faults.parse(readAsset(ctx, "faults.json"));
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getApplicationContext().getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // --------------------------------------------------------------- reading

    /** One snapshot of a single unit, filled as far as the unit answered. */
    public static final class Reading {
        public long at = System.currentTimeMillis();
        public boolean online;
        public String error;
        public String link = "";          // "IPv6" / "IPv4", for the status line

        public boolean hasPack;
        public double soc = Double.NaN;
        public double soh = Double.NaN;
        public double packV = Double.NaN;
        public double speedKph = Double.NaN;
        public int charging = -1;         // -1 unknown, 0 no, 1 yes

        public boolean hasLoc;
        public double lat;
        public double lon;
        public double accM = Double.NaN;
        public int sats = -1;

        /** Hottest reported pack/cell temperature (°C), NaN until a frame carries it. */
        public double maxTempC = Double.NaN;
        /** Read-only battery thermal/health verdict for this unit; null until read. */
        public BatteryHealth.Result health;
    }

    /**
     * Connects to {@code s}, reads location and pack health, and closes. Never
     * throws: failure is reported through {@link Reading#online} and
     * {@link Reading#error}.
     */
    public static Reading read(Context ctx, Scooter s, long connectTimeoutMs) {
        if (s.isCloud()) {
            return readCloud(ctx);
        }
        Reading r = new Reading();
        Rpc rpc = null;
        try {
            Net.Result res = Net.connect(ctx.getApplicationContext(), s.address, connectTimeoutMs);
            rpc = res.rpc;
            r.online = true;
            r.link = res.ipv6 ? "IPv6" : "IPv4";

            try {
                readLocation(rpc, r);
            } catch (RuntimeException ignored) {
                // Location is best-effort; a unit with no fix is still online.
            }
            try {
                readPack(ctx, rpc, r);
            } catch (Exception ignored) {
                // Pack needs the vehicle awake; absence is not an error.
            }
        } catch (Exception e) {
            r.online = false;
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            if (rpc != null) {
                rpc.close();
            }
        }
        return r;
    }

    /**
     * Reads a cloud-backed unit from Ola's servers using the app's stored token.
     * No vehicle contact. Cloud telemetry carries charge and location but not
     * pack temperature, so {@link Reading#health} is left null — a real thermal
     * verdict needs the local CAN path.
     */
    private static Reading readCloud(Context ctx) {
        Reading r = new Reading();
        r.link = "cloud";
        if (!OlaCloud.hasToken(ctx)) {
            r.online = false;
            r.error = "No cloud token set — import it in the Cloud tab first.";
            return r;
        }
        OlaCloud.Status s = OlaCloud.pollStatistics(ctx);
        if (!s.ok) {
            r.online = false;
            r.error = s.error == null ? ("HTTP " + s.httpCode) : s.error;
            return r;
        }
        r.online = true;
        if (s.soc != null) {
            r.hasPack = true;
            r.soc = s.soc;
            String c = s.charging == null ? "" : s.charging.toUpperCase(Locale.US);
            r.charging = c.contains("CHARG") ? 1 : 0;
        }
        if (s.lat != null && s.lon != null) {
            r.hasLoc = true;
            r.lat = s.lat;
            r.lon = s.lon;
            if (s.hAcc != null) {
                r.accM = s.hAcc;
            }
        }
        return r;
    }

    private static void readPack(Context ctx, Rpc rpc, Reading r) throws Exception {
        ensureTables(ctx);
        final Live live = new Live(dbc, faults);
        Rpc.Cancel cancel = rpc.dumpCanFrames(new Rpc.FrameSink() {
            @Override
            public void frame(Can.Frame frame) {
                try {
                    live.onFrame(frame);
                } catch (RuntimeException ignored) {
                    // one undecodable frame must not stop the sample
                }
            }

            @Override
            public void ended(String reason) {
                // nothing: the sample ends on its own timer
            }
        });
        try {
            Thread.sleep(SAMPLE_MS);
        } finally {
            cancel.close();
        }
        Live.Snapshot snap = live.snapshot();
        if (!Double.isNaN(snap.soc)) {
            r.hasPack = true;
            r.soc = snap.soc;
            r.soh = snap.soh;
            r.packV = snap.packV;
            r.speedKph = snap.speedKph;
            r.charging = snap.charging;
        }
        // Read-only battery thermal/health verdict from the same sample. Computed
        // whenever any battery data arrived (temperature/flags can be present even
        // when SOC is not). Advisory only — never acts on the vehicle.
        r.maxTempC = BatteryHealth.maxPresent(
                live.value("AFE_Max_Temp"),
                live.value("Temperature_Sensing_Max"),
                live.value("Effective_Battery_Temperature_00"));
        if (live.hasData()) {
            r.health = BatteryHealth.from(live);
        }
    }

    // dumpsys prints each provider's last fix as:
    //   gps: Location[gps 26.860166,80.901895 hAcc=3 ... {Bundle[{satellites=12, ...}]}]
    private static final Pattern LOC =
            Pattern.compile("Location\\[(?:gps|fused)\\s+(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern HACC = Pattern.compile("hAcc=(\\d+(?:\\.\\d+)?)");
    private static final Pattern SATS = Pattern.compile("satellites=(\\d+)");

    private static void readLocation(Rpc rpc, Reading r) {
        // grep -F keeps the response tiny and avoids a regex bracket, which the
        // whitespace splitter would mangle. "Location[" has no closing ']', so
        // the shell leaves the token alone.
        String out = rpc.shell("dumpsys location | grep -F Location[", 12_000);
        if (out == null) {
            return;
        }
        for (String line : out.split("\\r?\\n")) {
            Matcher m = LOC.matcher(line);
            if (!m.find()) {
                continue;
            }
            try {
                r.lat = Double.parseDouble(m.group(1));
                r.lon = Double.parseDouble(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            r.hasLoc = true;
            Matcher a = HACC.matcher(line);
            if (a.find()) {
                try {
                    r.accM = Double.parseDouble(a.group(1));
                } catch (NumberFormatException ignored) {
                    // leave accuracy unknown
                }
            }
            Matcher sm = SATS.matcher(line);
            if (sm.find()) {
                try {
                    r.sats = Integer.parseInt(sm.group(1));
                } catch (NumberFormatException ignored) {
                    // leave satellite count unknown
                }
            }
            return;   // first usable fix wins
        }
    }
}
