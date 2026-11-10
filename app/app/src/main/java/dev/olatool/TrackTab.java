package dev.olatool;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.app.Dialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.olatool.core.Can;

/**
 * Remote location tracking for the owner's own scooter, from Ola's cloud using
 * the owner's consumer token (set in the Cloud tab). Works anywhere the scooter
 * has signal. Shows a live trail, the latest fix with distance from the phone,
 * and a recorded history. Read-only.
 */
public final class TrackTab implements Tab {

    private static final String PREFS = "ola";
    private static final String K_TOPIC = "ntfy_topic";
    private static final String K_INTERVAL = "ntfy_interval";
    private static final int DEFAULT_INTERVAL = 5;

    private static final Pattern LOC =
            Pattern.compile("Location\\[(?:gps|fused)\\s+(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern HACC = Pattern.compile("hAcc=(\\d+(?:\\.\\d+)?)");
    private static final Pattern SATS = Pattern.compile("satellites=(\\d+)");
    private static final Pattern CANFRAME =
            Pattern.compile("#([0-9A-Fa-f]{1,3})\\s+([0-9A-Fa-f]{2}(?:\\s+[0-9A-Fa-f]{2}){7})");

    private final Context ctx;
    private final Conn conn;
    private final ScrollView root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final CloudHistory history;

    private TextView statusLine;
    private Button refreshButton;
    private WebMapView trail;
    private Dialog fsDialog;
    private WebMapView fsMap;
    private LinearLayout tripsBox;
    private TextView trailInfo;
    private Ui.Kv kvLat;
    private Ui.Kv kvLon;
    private Ui.Kv kvAcc;
    private Ui.Kv kvDistance;
    private Ui.Kv kvSpeed;
    private Ui.Kv kvBattery;
    private Ui.Kv kvUpdated;
    private Ui.Stat rangeStat;
    private Ui.Stat socStat;
    private TextView cloudPill;
    private Ui.Kv kvCharging;
    private Ui.Kv kvLock;
    private Ui.Kv kvState;
    private Ui.Kv kvConnected;
    private Button mapButton;
    private TextView topicLabel;
    private Ui.Kv kvSoh;
    private Ui.Kv kvVolts;
    private Ui.Kv kvAmps;
    private Ui.Kv kvPower;
    private Ui.Kv kvTemp;
    private Ui.Kv kvSpread;
    private Ui.Kv kvRpm;
    private Ui.Kv kvMotorTemp;
    private Ui.Kv kvOdo;
    private Ui.Kv kvTow;

    private boolean visible;
    private boolean inFlight;
    private Double lastLat;
    private Double lastLon;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!topic().isEmpty()) {
                pollNtfy();            // remote path (scooter pushes here)
            } else if (OlaCloud.hasToken(ctx)) {
                refresh();             // consumer-cloud path
            } else {
                syncCred();
            }
            if (visible) {
                ui.postDelayed(this, interval() * 1000L);
            }
        }
    };

    public TrackTab(Context ctx, Conn conn) {
        this.ctx = ctx;
        this.conn = conn;
        this.history = new CloudHistory(ctx);
        this.root = Ui.page(ctx);
        build();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        visible = true;
        trail.setLive(true);
        renderTrail();
        renderMe();
        renderTopic();
        ui.post(tick);
    }

    @Override
    public void onHide() {
        visible = false;
        trail.setLive(false);
        ui.removeCallbacks(tick);
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        LinearLayout body = Ui.body(root);

        body.addView(Ui.pageTitle(ctx, "Track",
                "Two sources: Local GPS (gRPC, no token — works when the scooter is on "
                        + "your network) and Cloud (consumer token — works anywhere).", Ui.INFO));

        LinearLayout trailCard = Ui.card(ctx);
        trail = new WebMapView(ctx);
        LinearLayout mapWrap = Ui.column(ctx);
        mapWrap.setBackground(Ui.round(ctx, Ui.CODE_BG, 10, Ui.LINE));
        mapWrap.setClipToOutline(true);
        mapWrap.addView(trail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 300)));
        trailCard.addView(mapWrap);
        LinearLayout mapActions = Ui.row(ctx);
        mapActions.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        Button fsButton = Ui.button(ctx, "⛶  Fullscreen", v -> openFullscreen());
        mapActions.addView(fsButton);
        trailInfo = Ui.note(ctx, "");
        LinearLayout.LayoutParams tiLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tiLp.leftMargin = Ui.dp(ctx, 10);
        mapActions.addView(trailInfo, tiLp);
        trailCard.addView(mapActions);
        body.addView(trailCard);

        body.addView(Ui.space(ctx, 10));
        Ui.Section trips = Ui.section(ctx, "Trips (last 24h)",
                "Each ride is one colour on the map. Older than 24h auto-clears.", Ui.ACCENT);
        tripsBox = trips.body;
        body.addView(trips.card);

        body.addView(Ui.space(ctx, 10));

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = Ui.dp(ctx, 8);

        // ---- Status: one at-a-glance card (cloud when a token is set, else local) ----
        Ui.Section status = Ui.section(ctx, "Status", null, Ui.ACCENT);
        LinearLayout pillRow = Ui.row(ctx);
        cloudPill = Ui.pill(ctx, "No token", Ui.DIM);
        pillRow.addView(cloudPill);
        status.body.addView(pillRow);
        status.body.addView(Ui.space(ctx, 10));

        socStat = new Ui.Stat(ctx, "Battery");
        socStat.unit("%");
        rangeStat = new Ui.Stat(ctx, "Range");
        rangeStat.unit("km");
        status.body.addView(Ui.statRow(ctx, socStat, rangeStat));

        kvCharging = new Ui.Kv(ctx, "Charging");
        kvLock = new Ui.Kv(ctx, "Lock");
        kvState = new Ui.Kv(ctx, "Vehicle state");
        kvDistance = new Ui.Kv(ctx, "Distance from you");
        kvUpdated = new Ui.Kv(ctx, "Last updated");
        status.body.addView(kvCharging);
        status.body.addView(kvLock);
        status.body.addView(kvState);
        status.body.addView(kvDistance);
        status.body.addView(kvUpdated);

        status.body.addView(Ui.space(ctx, 12));
        LinearLayout actions = Ui.row(ctx);
        refreshButton = Ui.accent(ctx, "Refresh", v -> smartRefresh());
        actions.addView(refreshButton);
        mapButton = Ui.button(ctx, "Open in Maps", v -> openMap());
        mapButton.setEnabled(false);
        actions.addView(mapButton, sp);
        Button more = Ui.button(ctx, "More  ▾", v -> showMore(v));
        actions.addView(more, sp);
        status.body.addView(actions);

        statusLine = Ui.note(ctx, "");
        statusLine.setPadding(0, Ui.dp(ctx, 12), 0, 0);
        status.body.addView(statusLine);
        body.addView(status.card);

        body.addView(Ui.space(ctx, 10));

        // ---- Details: raw coordinates, kept but out of the way ----
        Ui.Section loc = Ui.section(ctx, "Details", null, Ui.INFO);
        kvLat = new Ui.Kv(ctx, "Latitude");
        kvLon = new Ui.Kv(ctx, "Longitude");
        kvAcc = new Ui.Kv(ctx, "Accuracy (m)");
        kvSpeed = new Ui.Kv(ctx, "GPS satellites");
        kvBattery = new Ui.Kv(ctx, "Battery (CAN)");
        kvConnected = new Ui.Kv(ctx, "Connected");
        loc.body.addView(kvLat);
        loc.body.addView(kvLon);
        loc.body.addView(kvAcc);
        loc.body.addView(kvSpeed);
        loc.body.addView(kvBattery);
        loc.body.addView(kvConnected);
        body.addView(loc.card);

        body.addView(Ui.space(ctx, 10));

        Ui.Section veh = Ui.section(ctx, "Vehicle (CAN)",
                "Decoded from the scooter's bus — only while it is awake (riding/charging).");
        kvSoh = new Ui.Kv(ctx, "State of health");
        kvVolts = new Ui.Kv(ctx, "Pack voltage");
        kvAmps = new Ui.Kv(ctx, "Current");
        kvPower = new Ui.Kv(ctx, "Power");
        kvTemp = new Ui.Kv(ctx, "Pack temp");
        kvSpread = new Ui.Kv(ctx, "Cell spread");
        kvRpm = new Ui.Kv(ctx, "Motor speed");
        kvMotorTemp = new Ui.Kv(ctx, "Motor temp");
        kvOdo = new Ui.Kv(ctx, "Odometer");
        kvTow = new Ui.Kv(ctx, "Tow / theft");
        veh.body.addView(kvSoh);
        veh.body.addView(kvVolts);
        veh.body.addView(kvAmps);
        veh.body.addView(kvPower);
        veh.body.addView(kvTemp);
        veh.body.addView(kvSpread);
        veh.body.addView(kvRpm);
        veh.body.addView(kvMotorTemp);
        veh.body.addView(kvOdo);
        veh.body.addView(kvTow);
        body.addView(veh.card);
    }

    // ------------------------------------------------------------------ CAN decode

    private void applyCan(String out) {
        Can.Snapshot s = new Can.Snapshot();
        Matcher m = CANFRAME.matcher(out);
        while (m.find()) {
            try {
                int id = Integer.parseInt(m.group(1), 16);
                String[] bs = m.group(2).trim().split("\\s+");
                byte[] d = new byte[8];
                for (int i = 0; i < 8 && i < bs.length; i++) {
                    d[i] = (byte) Integer.parseInt(bs[i], 16);
                }
                s.apply(new Can.Frame(id, 8, d));
            } catch (Exception ignored) {
            }
        }
        if (s.frames > 0) {
            renderCan(s);
        }
    }

    private void renderCan(Can.Snapshot s) {
        double soc = !Double.isNaN(s.soc) ? s.soc : s.displaySoc;
        if (!Double.isNaN(soc)) {
            kvBattery.set(String.format(Locale.ROOT, "%.0f %%", soc), Ui.ACCENT);
        }
        kvSoh.set(Double.isNaN(s.soh) ? Ui.DASH
                : String.format(Locale.ROOT, "%.0f %%  %s", s.soh, s.sohVerdict()));
        kvVolts.set(fv(s.volts, "V", 1));
        kvAmps.set(fv(s.amps, "A", 1));
        kvPower.set(Double.isNaN(s.powerW()) ? Ui.DASH
                : String.format(Locale.ROOT, "%.2f kW", s.powerW() / 1000.0));
        kvTemp.set(fv(s.packTemp, "°C", 1));
        kvSpread.set(Double.isNaN(s.cellSpreadMv()) ? Ui.DASH
                : String.format(Locale.ROOT, "%.0f mV  %s", s.cellSpreadMv(), s.cellSpreadVerdict()));
        kvRpm.set(fv(s.rpm, "rpm", 0));
        kvMotorTemp.set(fv(s.motorTemp, "°C", 1));
        kvOdo.set(Double.isNaN(s.odoMeters) ? Ui.DASH
                : String.format(Locale.ROOT, "%.1f km", s.odoMeters / 1000.0));
        kvTow.set(s.towTheft < 0 ? Ui.DASH : s.towName());
    }

    private static String fv(double v, String unit, int dec) {
        if (Double.isNaN(v)) {
            return Ui.DASH;
        }
        return String.format(Locale.ROOT, "%." + dec + "f %s", v, unit);
    }

    // -------------------------------------------------------------- behaviour

    private void syncCred() {
        if (!OlaCloud.hasToken(ctx)) {
            if (cloudPill != null) {
                Ui.pill(cloudPill, "No token", Ui.DIM);
            }
            setStatus("No consumer token yet. Tap “Set token” and paste your "
                    + "X-ACCESS-TOKEN + X-UDA-UUID from your phone's Ola app.", Ui.WARN);
        }
    }

    /** The one Refresh button: cloud when a token is set, else remote ntfy. */
    private void smartRefresh() {
        if (OlaCloud.hasToken(ctx)) {
            refresh();
        } else {
            pollNtfy();
        }
    }

    /** The overflow menu — every less-common action lives here, not on the card. */
    private void showMore(View anchor) {
        PopupMenu m = new PopupMenu(ctx, anchor);
        final String setToken = "Set Ola token";
        final String localGps = "Local GPS (on scooter Wi-Fi)";
        final String remote = "Check remote (ntfy)";
        final String startTrk = "Start tracker on scooter";
        final String stopTrk = "Stop tracker";
        final String settings = "Tracker settings";
        final String clear = "Clear trail";
        m.getMenu().add(setToken);
        m.getMenu().add(localGps);
        m.getMenu().add(remote);
        m.getMenu().add(startTrk);
        m.getMenu().add(stopTrk);
        m.getMenu().add(settings);
        m.getMenu().add(clear);
        m.setOnMenuItemClickListener(item -> {
            String t = String.valueOf(item.getTitle());
            if (setToken.equals(t)) {
                showTokenDialog();
            } else if (localGps.equals(t)) {
                readLocalGps();
            } else if (remote.equals(t)) {
                pollNtfy();
            } else if (startTrk.equals(t)) {
                startTracker();
            } else if (stopTrk.equals(t)) {
                stopTracker();
            } else if (settings.equals(t)) {
                editTopic();
            } else if (clear.equals(t)) {
                history.clear();
                renderTrail();
                setStatus("Trail cleared.", Ui.DIM);
            }
            return true;
        });
        m.show();
    }

    private void refresh() {
        if (inFlight) {
            return;
        }
        if (!OlaCloud.hasToken(ctx)) {
            syncCred();
            return;
        }
        inFlight = true;
        refreshButton.setEnabled(false);
        setStatus("Refreshing location …", Ui.DIM);
        io.execute(() -> {
            final OlaCloud.Status s = OlaCloud.pollStatistics(ctx);
            if (s.ok) {
                history.record(s);
            }
            ui.post(() -> {
                inFlight = false;
                refreshButton.setEnabled(true);
                render(s);
                renderTrail();
            });
        });
    }

    private void render(OlaCloud.Status s) {
        if (!s.ok) {
            if (cloudPill != null) {
                boolean rejected = s.httpCode == 401 || s.httpCode == 403;
                Ui.pill(cloudPill, rejected ? "Token rejected" : "Cloud error",
                        rejected ? Ui.BAD : Ui.WARN);
            }
            setStatus(s.error == null ? "Could not reach Ola cloud." : s.error, Ui.BAD);
            return;
        }
        setStatus("Updated just now from Ola cloud.", Ui.ACCENT);
        lastLat = s.lat;
        lastLon = s.lon;
        kvLat.set(s.lat == null ? Ui.DASH : String.format(Locale.ROOT, "%.6f", s.lat));
        kvLon.set(s.lon == null ? Ui.DASH : String.format(Locale.ROOT, "%.6f", s.lon));
        kvAcc.set(s.hAcc == null ? Ui.DASH : String.format(Locale.ROOT, "%.0f", s.hAcc));
        renderDistance(s.lat, s.lon);
        kvSpeed.set(Ui.DASH);
        kvBattery.set(s.soc == null ? Ui.DASH : String.format(Locale.ROOT, "%.0f %%", s.soc));
        kvUpdated.set(s.lastUpdated == null ? Ui.DASH : s.lastUpdated);
        renderCloudSnapshot(s);
        mapButton.setEnabled(s.lat != null && s.lon != null);
        if (s.lat != null && s.lon != null) {
            trail.setScooter(s.lat, s.lon, s.hAcc);
            if (fsMap != null) {
                fsMap.setScooter(s.lat, s.lon, s.hAcc);
            }
        }
        renderMe();
    }

    /** Fills the Cloud snapshot card from one cloud poll. */
    private void renderCloudSnapshot(OlaCloud.Status s) {
        boolean charging = isCharging(s);
        int color = s.soc == null ? Ui.DIM
                : (charging ? Ui.INFO : (s.soc <= 15 ? Ui.BAD : (s.soc <= 30 ? Ui.WARN : Ui.ACCENT)));
        socStat.set(s.soc == null ? Ui.DASH : String.format(Locale.ROOT, "%.0f", s.soc));
        rangeStat.set(rangeKm(s.range));
        Ui.pill(cloudPill, charging ? "Charging" : "On battery", charging ? Ui.INFO : color);
        kvCharging.set(pretty(firstNonNull(s.charging, s.chargingEx)),
                charging ? Ui.INFO : Ui.TEXT);
        kvLock.set(pretty(s.lockStatus));
        kvState.set(pretty(firstNonNull(s.vehicleStatus, s.sleepState)));
        kvConnected.set(pretty(s.isConnected));
    }

    private static boolean isCharging(OlaCloud.Status s) {
        String c = firstNonNull(s.charging, s.chargingEx);
        return c != null && c.toUpperCase(Locale.ROOT).contains("CHARG")
                && !c.toUpperCase(Locale.ROOT).contains("NOT");
    }

    private static String rangeKm(String range) {
        if (range == null) {
            return Ui.DASH;
        }
        Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(range);
        return m.find() ? m.group() : Ui.DASH;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String pretty(String s) {
        if (s == null || s.isEmpty()) {
            return Ui.DASH;
        }
        String t = s.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? Ui.DASH : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Reads the scooter's GPS over the local gRPC root shell — no cloud token. */
    private void readLocalGps() {
        if (conn == null || !conn.ready()) {
            setStatus("Not connected. Up top: Auto → 192.168.0.238 → Connect, then Local GPS.",
                    Ui.WARN);
            return;
        }
        setStatus("Reading GPS from the scooter …", Ui.DIM);
        conn.run(rpc -> rpc.shell("dumpsys location | grep -F Location[", 12_000),
                (out, err) -> {
                    if (err != null) {
                        setStatus("Local GPS failed: " + err.getMessage(), Ui.BAD);
                        return;
                    }
                    if (!parseLocal(out, "local GPS")) {
                        setStatus("No GPS fix from the scooter yet (it may be indoors / no sky).",
                                Ui.WARN);
                    }
                });
    }

    private boolean parseLocal(String out, String label) {
        if (out == null) {
            return false;
        }
        for (String line : out.split("\\r?\\n")) {
            Matcher m = LOC.matcher(line);
            if (!m.find()) {
                continue;
            }
            double lat, lon;
            try {
                lat = Double.parseDouble(m.group(1));
                lon = Double.parseDouble(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            Double acc = null;
            int sats = -1;
            Matcher a = HACC.matcher(line);
            if (a.find()) {
                try {
                    acc = Double.parseDouble(a.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            Matcher sm = SATS.matcher(line);
            if (sm.find()) {
                try {
                    sats = Integer.parseInt(sm.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            lastLat = lat;
            lastLon = lon;
            kvLat.set(String.format(Locale.ROOT, "%.6f", lat));
            kvLon.set(String.format(Locale.ROOT, "%.6f", lon));
            kvAcc.set(acc == null ? Ui.DASH : String.format(Locale.ROOT, "%.0f", acc));
            renderDistance(lat, lon);
            kvSpeed.set(sats < 0 ? Ui.DASH : sats + " sats");
            kvUpdated.set(String.format(Locale.ROOT, "%tT (%s)", System.currentTimeMillis(), label));
            mapButton.setEnabled(true);
            applyCan(out);                 // battery + full vehicle data, if present
            history.recordLocation(lat, lon);
            trail.setScooter(lat, lon, acc);
            if (fsMap != null) {
                fsMap.setScooter(lat, lon, acc);
            }
            renderMe();
            renderTrail();
            setStatus("Location fix via " + label + ".", Ui.ACCENT);
            return true;
        }
        return false;
    }

    private void renderDistance(Double lat, Double lon) {
        if (lat == null || lon == null) {
            kvDistance.set(Ui.DASH);
            return;
        }
        Location me = lastKnownLocation();
        if (me == null) {
            kvDistance.set("—");
            return;
        }
        double m = Ui.distanceM(me.getLatitude(), me.getLongitude(), lat, lon);
        kvDistance.set(Ui.distanceLabel(m), Ui.INFO);
    }

    private Location lastKnownLocation() {
        try {
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return null;
            }
            Location best = null;
            for (String p : lm.getAllProviders()) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private void renderMe() {
        Location me = lastKnownLocation();
        trail.setMe(me == null ? null : me.getLatitude(), me == null ? null : me.getLongitude());
    }

    private void renderTrail() {
        if (trail == null) {
            return;
        }
        double[][] pts = history.locationPoints();
        String routes = history.routesJson();
        String stops = history.stopsJson();
        trail.setRoutesJson(routes);
        trail.setStopsJson(stops);
        if (fsMap != null) {
            fsMap.setRoutesJson(routes);
            fsMap.setStopsJson(stops);
        }
        trailInfo.setText(pts.length == 0
                ? "No fixes yet — refresh to start the trail."
                : pts.length + " fix(es) · live, auto-updates every " + interval() + "s");
        renderTrips();
    }

    /** Fills the Trips panel: one row per ride, newest first. */
    private void renderTrips() {
        if (tripsBox == null) {
            return;
        }
        tripsBox.removeAllViews();
        java.util.List<CloudHistory.Trip> trips = history.trips();
        if (trips.isEmpty()) {
            tripsBox.addView(Ui.note(ctx, "No rides recorded yet."));
            return;
        }
        for (CloudHistory.Trip t : trips) {
            LinearLayout row = Ui.row(ctx);
            row.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6));
            View dot = new View(ctx);
            dot.setBackground(Ui.round(ctx, android.graphics.Color.parseColor(t.color), 6, 0));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(ctx, 12), Ui.dp(ctx, 12));
            dp.rightMargin = Ui.dp(ctx, 10);
            row.addView(dot, dp);
            LinearLayout col = Ui.column(ctx);
            TextView title = Ui.text(ctx, String.format(Locale.ROOT, "%.1f km", t.km), 14, Ui.TEXT);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            col.addView(title);
            long durMin = Math.max(0, (t.end - t.start) / 60000);
            String span = String.format(Locale.ROOT, "%tR–%tR · %s · %d fixes",
                    t.start, t.end, durMin < 60 ? durMin + "m" : (durMin / 60) + "h " + (durMin % 60) + "m",
                    t.fixes);
            col.addView(Ui.text(ctx, span, 11, Ui.DIM));
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tripsBox.addView(row);
        }
    }

    /** Opens the live map full-screen in its own WebView, seeded with the trail. */
    private void openFullscreen() {
        final WebMapView big = new WebMapView(ctx);
        big.setLive(true);
        big.setRoutesJson(history.routesJson());
        big.setStopsJson(history.stopsJson());
        if (lastLat != null && lastLon != null) {
            big.setScooter(lastLat, lastLon, null);
        }
        Location me = lastKnownLocation();
        big.setMe(me == null ? null : me.getLatitude(), me == null ? null : me.getLongitude());
        fsMap = big;

        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setBackgroundColor(Ui.BG);
        wrap.addView(big, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Button close = Ui.accent(ctx, "✕  Close", v -> {
            if (fsDialog != null) {
                fsDialog.dismiss();
            }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        clp.topMargin = Ui.dp(ctx, 16);
        clp.rightMargin = Ui.dp(ctx, 16);
        wrap.addView(close, clp);

        Dialog d = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.setContentView(wrap);
        d.setOnDismissListener(dd -> fsMap = null);
        fsDialog = d;
        d.show();
    }

    private void openMap() {
        if (lastLat == null || lastLon == null) {
            return;
        }
        String ll = lastLat + "," + lastLon;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + ll + "?q=" + ll + "(Scooter)"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/?api=1&query=" + ll));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {
                setStatus("No map app.", Ui.WARN);
            }
        }
    }

    // ------------------------------------------------------------------ ntfy / A

    private SharedPreferences prefs() {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String topic() {
        SharedPreferences p = prefs();
        String t = p.getString(K_TOPIC, "");
        if (t.isEmpty()) {
            t = "ola-trk-" + Long.toHexString(System.nanoTime())
                    + Integer.toHexString((int) (Math.random() * 0xFFFF));
            p.edit().putString(K_TOPIC, t).apply();
        }
        return t;
    }

    /** Update interval in seconds, clamped to a sane 2..600. */
    private int interval() {
        int v = prefs().getInt(K_INTERVAL, DEFAULT_INTERVAL);
        return v < 2 ? 2 : (v > 600 ? 600 : v);
    }

    private void renderTopic() {
        if (topicLabel != null) {
            topicLabel.setText("ntfy topic: " + topic() + "   ·   every " + interval()
                    + "s   (tap to edit)");
        }
    }

    private void editTopic() {
        LinearLayout box = Ui.column(ctx);
        int pad = Ui.dp(ctx, 16);
        box.setPadding(pad, Ui.dp(ctx, 8), pad, 0);
        box.addView(Ui.note(ctx, "Topic is a shared secret between the scooter and this app — "
                + "keep it long and private. Interval is how often the scooter pushes and the app "
                + "polls. 2s is live but can hit ntfy rate limits and drains the scooter; 5–10s "
                + "is safer."));
        final EditText t = field("ntfy topic", topic());
        box.addView(Ui.space(ctx, 8));
        box.addView(t);
        final EditText iv = field("Interval seconds (min 2)", String.valueOf(interval()));
        box.addView(Ui.space(ctx, 8));
        box.addView(iv);
        new AlertDialog.Builder(ctx)
                .setTitle("Tracker settings")
                .setView(wrap(box))
                .setPositiveButton("Save", (d, w) -> {
                    SharedPreferences.Editor e = prefs().edit();
                    String v = t.getText().toString().trim();
                    if (!v.isEmpty()) {
                        e.putString(K_TOPIC, v);
                    }
                    try {
                        int n = Integer.parseInt(iv.getText().toString().trim());
                        e.putInt(K_INTERVAL, n < 2 ? 2 : (n > 600 ? 600 : n));
                    } catch (Exception ignored) {
                    }
                    e.apply();
                    renderTopic();
                    setStatus("Saved. Restart the tracker (Start tracker) to apply the new interval.",
                            Ui.DIM);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pollNtfy() {
        final String t = topic();
        io.execute(() -> {
            final String msg = Ntfy.latest(t);
            ui.post(() -> {
                if (msg == null) {
                    setStatus("No ntfy message yet on “" + t + "” — start the tracker "
                            + "on the scooter.", Ui.WARN);
                    return;
                }
                if (!parseLocal(msg, "ntfy")) {
                    setStatus("ntfy message had no GPS line yet.", Ui.WARN);
                }
            });
        });
    }

    /** Pushes a tiny GPS→ntfy loop onto the scooter and launches it detached. */
    private void startTracker() {
        if (!Access.require(ctx, Access.ADMIN)) {
            return;
        }
        if (conn == null || !conn.ready()) {
            setStatus("Connect to the scooter first (Auto/Direct), then Start tracker.", Ui.WARN);
            return;
        }
        final String t = topic();
        setStatus("Starting tracker on the scooter …", Ui.DIM);
        final String launcher = "cat > /data/local/tmp/ola-track.sh <<'EOF'\n"
                + "#!/system/bin/sh\n"
                + "while true; do\n"
                + "  L=$(dumpsys location | grep -F 'Location[' | grep gps | head -1)\n"
                + "  /vendor/bin/candump vcan0 > /data/local/tmp/.cd 2>/dev/null &\n"
                + "  CPID=$!\n"
                + "  sleep 2\n"
                + "  kill $CPID 2>/dev/null\n"
                + "  CAN=$(awk 'BEGIN{split(\"120 140 150 160 170 1A4 1A5 1A6 1A7 1A8 1A9 1AA 313 352 356 683\",a,\" \");for(i in a)w[a[i]]=1}"
                + "$3==\"[8]\"{id=toupper($2);if(id in w)L[id]=id\" \"$4\" \"$5\" \"$6\" \"$7\" \"$8\" \"$9\" \"$10\" \"$11}"
                + "END{for(k in L)printf \"#%s \",L[k]}' /data/local/tmp/.cd 2>/dev/null)\n"
                + "  MSG=\"$L $CAN\"\n"
                + "  [ -n \"$L\" ] && curl -s -d \"$MSG\" \"https://ntfy.sh/" + t + "\" >/dev/null 2>&1\n"
                + "  sleep " + interval() + "\n"
                + "done\n"
                + "EOF\n"
                + "chmod 755 /data/local/tmp/ola-track.sh\n"
                + "pkill -f ola-track.sh 2>/dev/null\n"
                + "setsid sh /data/local/tmp/ola-track.sh >/dev/null 2>&1 &\n"
                + "echo started\n";
        conn.run(rpc -> rpc.runScript("ola-track-launch.sh", launcher, 30_000), (out, err) -> {
            if (err != null) {
                setStatus("Start failed: " + err.getMessage(), Ui.BAD);
                return;
            }
            setStatus("Tracker started on the scooter — pushing to ntfy every " + interval()
                    + "s.", Ui.ACCENT);
        });
    }

    private void stopTracker() {
        if (!Access.require(ctx, Access.ADMIN)) {
            return;
        }
        if (conn == null || !conn.ready()) {
            setStatus("Connect to the scooter first.", Ui.WARN);
            return;
        }
        conn.run(rpc -> rpc.shell("pkill -f ola-track.sh", 10_000), (out, err) ->
                setStatus(err == null ? "Tracker stopped on the scooter."
                        : "Stop failed: " + err.getMessage(), err == null ? Ui.DIM : Ui.BAD));
    }

    private void showTokenDialog() {
        LinearLayout box = Ui.column(ctx);
        int pad = Ui.dp(ctx, 16);
        box.setPadding(pad, Ui.dp(ctx, 8), pad, 0);
        box.addView(Ui.note(ctx, "Paste the header values from your phone's logged-in Ola app "
                + "(capture with HTTP Toolkit). Token + UUID are required; Device-APP helps if "
                + "the server asks for it. Stored only on this device."));
        final EditText token = field("X-ACCESS-TOKEN", OlaCloud.token(ctx));
        box.addView(Ui.space(ctx, 8));
        box.addView(token);
        final EditText uuid = field("X-UDA-UUID", OlaCloud.uuid(ctx));
        box.addView(Ui.space(ctx, 8));
        box.addView(uuid);
        final EditText devApp = field("X-Device-APP (optional)", OlaCloud.deviceApp(ctx));
        box.addView(Ui.space(ctx, 8));
        box.addView(devApp);
        final EditText devId = field("X-Device-ID (optional)", OlaCloud.deviceId(ctx));
        box.addView(Ui.space(ctx, 8));
        box.addView(devId);
        new AlertDialog.Builder(ctx)
                .setTitle("Set Ola token")
                .setView(wrap(box))
                .setPositiveButton("Save", (d, w) -> {
                    OlaCloud.saveCreds(ctx, token.getText().toString(), uuid.getText().toString());
                    OlaCloud.saveExtra(ctx, devApp.getText().toString(), devId.getText().toString());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private ScrollView wrap(View v) {
        ScrollView s = new ScrollView(ctx);
        s.addView(v);
        return s;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(13);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setSingleLine(true);
        e.setBackground(Ui.round(ctx, Ui.CARD, 10, Ui.LINE));
        e.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        return e;
    }

    private void setStatus(String text, int color) {
        Ui.set(statusLine, text);
        statusLine.setTextColor(color);
    }
}
