package dev.olatool;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Flagship remote view of the owner's own scooter, read from Ola's cloud through
 * {@link OlaCloud}. A battery ring, a battery-over-time sparkline, charging and
 * status detail, and a location card with distance from the phone. Read-only: no
 * command endpoint is ever called, no vehicle contact. Works with the scooter
 * asleep or away as long as the owner has imported their access token.
 */
public final class CloudTab implements Tab {

    private static final long POLL_MS = 20_000;

    private final Context ctx;
    private final Conn conn;
    private final ScrollView root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final CloudHistory history;

    private TextView statusLine;
    private Button refreshButton;
    private Button alertsButton;
    private Gauge gauge;
    private Ui.Stat rangeStat;
    private Ui.Stat chargeStat;
    private TextView chargePill;
    private Spark spark;
    private TextView trendLabel;
    private Ui.Kv kvCharging;
    private Ui.Kv kvLock;
    private Ui.Kv kvState;
    private Ui.Kv kvConnected;
    private Ui.Kv kvTimeToFull;
    private Ui.Kv kvUpdated;
    private Ui.Kv kvLat;
    private Ui.Kv kvLon;
    private Ui.Kv kvAcc;
    private Ui.Kv kvDistance;
    private Button mapButton;

    private boolean visible;
    private boolean inFlight;
    private Double lastLat;
    private Double lastLon;
    private long lastOkAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (visible) {
                ui.postDelayed(this, POLL_MS);
            }
        }
    };

    public CloudTab(Context ctx, Conn conn) {
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
        syncCredState();
        renderHistory();
        ui.post(tick);
    }

    @Override
    public void onHide() {
        visible = false;
        ui.removeCallbacks(tick);
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        LinearLayout body = Ui.body(root);

        Ui.Section head = Ui.section(ctx, "Ola cloud",
                "Remote battery and location from Ola's servers. No vehicle contact — "
                        + "works when the scooter is asleep or away.");
        body.addView(head.card);

        LinearLayout buttons = Ui.row(ctx);
        refreshButton = Ui.accent(ctx, "Refresh", v -> refresh());
        buttons.addView(refreshButton);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = Ui.dp(ctx, 8);
        Button setButton = Ui.button(ctx, "Set token", v -> showTokenDialog());
        buttons.addView(setButton, sp);
        Button pinButton = Ui.button(ctx, "App PIN", v -> showPinDialog());
        buttons.addView(pinButton, sp);
        Button patternButton = Ui.button(ctx, "App pattern", v -> showPatternDialog());
        buttons.addView(patternButton, sp);
        head.body.addView(buttons);

        LinearLayout buttons2 = Ui.row(ctx);
        buttons2.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        alertsButton = Ui.button(ctx, alertsLabel(), v -> toggleAlerts());
        buttons2.addView(alertsButton);
        Button clearButton = Ui.button(ctx, "Clear history", v -> {
            history.clear();
            renderHistory();
            setStatus("History cleared.", Ui.DIM);
        });
        buttons2.addView(clearButton, sp);
        Button fetchButton = Ui.button(ctx, "Fetch token", v -> fetchFromScooter());
        buttons2.addView(fetchButton, sp);
        head.body.addView(buttons2);

        statusLine = Ui.note(ctx, "");
        statusLine.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        head.body.addView(statusLine);

        body.addView(Ui.space(ctx, 10));

        // Hero: battery ring.
        LinearLayout hero = Ui.card(ctx);
        gauge = new Gauge(ctx);
        hero.addView(gauge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chargePill = Ui.pill(ctx, "—", Ui.DIM);
        LinearLayout pillRow = Ui.row(ctx);
        pillRow.setGravity(Gravity.CENTER);
        pillRow.addView(chargePill);
        hero.addView(pillRow);
        body.addView(hero);

        body.addView(Ui.space(ctx, 10));

        rangeStat = new Ui.Stat(ctx, "Range");
        rangeStat.unit("km");
        chargeStat = new Ui.Stat(ctx, "Time to full");
        body.addView(Ui.statRow(ctx, rangeStat, chargeStat));

        body.addView(Ui.space(ctx, 10));

        // Battery history.
        Ui.Section hist = Ui.section(ctx, "Battery history",
                "Recorded on this phone from each refresh.");
        spark = new Spark(ctx);
        spark.setRange(0, 100);
        hist.body.addView(spark, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        trendLabel = Ui.note(ctx, "");
        trendLabel.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        hist.body.addView(trendLabel);
        body.addView(hist.card);

        body.addView(Ui.space(ctx, 10));

        Ui.Section status = Ui.section(ctx, "Status", null);
        kvCharging = new Ui.Kv(ctx, "Charging");
        kvLock = new Ui.Kv(ctx, "Lock");
        kvState = new Ui.Kv(ctx, "Vehicle state");
        kvConnected = new Ui.Kv(ctx, "Connected");
        kvTimeToFull = new Ui.Kv(ctx, "Time to full");
        kvUpdated = new Ui.Kv(ctx, "Last updated");
        status.body.addView(kvCharging);
        status.body.addView(kvLock);
        status.body.addView(kvState);
        status.body.addView(kvConnected);
        status.body.addView(kvTimeToFull);
        status.body.addView(kvUpdated);
        body.addView(status.card);

        body.addView(Ui.space(ctx, 10));

        Ui.Section loc = Ui.section(ctx, "Location", null, Ui.INFO);
        kvLat = new Ui.Kv(ctx, "Latitude");
        kvLon = new Ui.Kv(ctx, "Longitude");
        kvAcc = new Ui.Kv(ctx, "Accuracy (m)");
        kvDistance = new Ui.Kv(ctx, "Distance from you");
        loc.body.addView(kvLat);
        loc.body.addView(kvLon);
        loc.body.addView(kvAcc);
        loc.body.addView(kvDistance);
        loc.body.addView(Ui.space(ctx, 8));
        mapButton = Ui.button(ctx, "Open in Maps", v -> openMap());
        mapButton.setEnabled(false);
        loc.body.addView(mapButton);
        body.addView(loc.card);
    }

    // -------------------------------------------------------------- behaviour

    private String alertsLabel() {
        return Notify.enabled(ctx) ? "Alerts: on" : "Alerts: off";
    }

    private void toggleAlerts() {
        boolean on = !Notify.enabled(ctx);
        Notify.setEnabled(ctx, on);
        alertsButton.setText(alertsLabel());
        setStatus(on ? "Alerts on — low battery and charge-complete."
                : "Alerts off.", on ? Ui.ACCENT : Ui.DIM);
    }

    private void syncCredState() {
        if (!OlaCloud.hasToken(ctx)) {
            setStatus("No access token yet. Tap “Set token” and paste your "
                    + "X-ACCESS-TOKEN (and X-UDA-UUID) from your Ola login.", Ui.WARN);
        }
    }

    private void refresh() {
        if (inFlight) {
            return;
        }
        if (!OlaCloud.hasToken(ctx)) {
            syncCredState();
            return;
        }
        inFlight = true;
        refreshButton.setEnabled(false);
        setStatus("Refreshing from Ola cloud …", Ui.DIM);
        io.execute(() -> {
            final OlaCloud.Status s = OlaCloud.pollStatistics(ctx);
            if (s.ok) {
                history.record(s);
                Notify.onStatus(ctx, s);
            }
            ui.post(() -> {
                inFlight = false;
                refreshButton.setEnabled(true);
                render(s);
                renderHistory();
            });
        });
    }

    private void render(OlaCloud.Status s) {
        if (!s.ok) {
            setStatus(s.error == null ? "Could not reach Ola cloud." : s.error, Ui.BAD);
            return;
        }
        lastOkAt = System.currentTimeMillis();
        setStatus("Updated just now from Ola cloud.", Ui.ACCENT);

        boolean charging = isCharging(s);
        Double soc = s.soc == null ? null : (double) (float) s.soc;
        int color = soc == null ? Ui.DIM
                : (charging ? Ui.INFO : (soc <= 15 ? Ui.BAD : (soc <= 30 ? Ui.WARN : Ui.ACCENT)));
        gauge.setValue(soc);
        gauge.setAccent(color);
        gauge.setCaption(charging ? "charging" : (soc == null ? "" : "battery"));

        Ui.pill(chargePill, charging ? "Charging" : "On battery",
                charging ? Ui.INFO : Ui.DIM);

        rangeStat.set(rangeKm(s.range));
        String ttf = pretty(s.timeToFull);
        chargeStat.set(ttf.equals(Ui.DASH) ? (charging ? "…" : "—") : ttf);

        kvCharging.set(pretty(firstNonNull(s.charging, s.chargingEx)),
                charging ? Ui.INFO : Ui.TEXT);
        kvLock.set(pretty(s.lockStatus));
        kvState.set(pretty(firstNonNull(s.vehicleStatus, s.sleepState)));
        kvConnected.set(pretty(s.isConnected));
        kvTimeToFull.set(ttf);
        kvUpdated.set(s.lastUpdated == null ? Ui.DASH : s.lastUpdated);

        lastLat = s.lat;
        lastLon = s.lon;
        kvLat.set(s.lat == null ? Ui.DASH : String.format(Locale.ROOT, "%.6f", s.lat));
        kvLon.set(s.lon == null ? Ui.DASH : String.format(Locale.ROOT, "%.6f", s.lon));
        kvAcc.set(s.hAcc == null ? Ui.DASH : String.format(Locale.ROOT, "%.0f", s.hAcc));
        renderDistance(s.lat, s.lon);
        mapButton.setEnabled(s.lat != null && s.lon != null);
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

    private void renderHistory() {
        if (spark == null) {
            return;
        }
        spark.setData(history.socSeries());
        if (history.size() < 2) {
            trendLabel.setText("Collecting data — refresh a few times to see the curve.");
            trendLabel.setTextColor(Ui.DIM);
            return;
        }
        float delta = history.trendDelta();
        if (Float.isNaN(delta)) {
            trendLabel.setText("");
            return;
        }
        String sign = delta >= 0 ? "+" : "";
        int color = delta >= 0 ? Ui.ACCENT : Ui.WARN;
        trendLabel.setText(String.format(Locale.ROOT,
                "%s%.0f%% over %d samples", sign, delta, history.size()));
        trendLabel.setTextColor(color);
    }

    private static boolean isCharging(OlaCloud.Status s) {
        String c = firstNonNull(s.charging, s.chargingEx);
        return c != null && c.toUpperCase(Locale.ROOT).contains("CHARG")
                && !c.toUpperCase(Locale.ROOT).contains("NOT");
    }

    private void openMap() {
        if (lastLat == null || lastLon == null) {
            return;
        }
        String ll = lastLat + "," + lastLon;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:" + ll + "?q=" + ll + "(Scooter)"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/?api=1&query=" + ll));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {
                setStatus("No app to open the map.", Ui.WARN);
            }
        }
    }

    // -------------------------------------------------------- fetch from scooter

    /**
     * Reads the owner's own Ola access token off the owner's own scooter, over
     * the root gRPC channel the rest of the app already uses, and imports it into
     * the Cloud tab. Gated behind Admin access. This is owner-to-own-credential:
     * it never leaves the device and only ever enables read-only cloud polls.
     */
    private void fetchFromScooter() {
        if (!Access.require(ctx, Access.ADMIN)) {
            return;
        }
        if (conn == null || !conn.ready()) {
            setStatus("Connect to your scooter first (Auto or Direct tab up top), "
                    + "then Fetch token.", Ui.WARN);
            return;
        }
        setStatus("Reading the token from your scooter …", Ui.DIM);
        conn.run(rpc -> rpc.shell(SEARCH_CMD, 40_000), (out, err) -> {
            if (err != null) {
                setStatus("Fetch failed: " + err.getMessage(), Ui.BAD);
                return;
            }
            handleFetched(out);
        });
    }

    private static final String SEARCH_CMD =
            "grep -rhoE '<string name=\"[^\"]*([Tt]oken|[Uu]da|[Uu]uid)[^\"]*\">[^<]+</string>' "
                    + "/data/data/*/shared_prefs/ 2>/dev/null | head -80; "
                    + "grep -rhoE '\"[^\"]*(token|uda|uuid)[^\"]*\"[: ]+\"[^\"]+\"' "
                    + "/data/vendor /data/ola /data/misc 2>/dev/null | head -40";

    private void handleFetched(String out) {
        if (out == null || out.trim().isEmpty()) {
            setStatus("Nothing token-like found on the scooter. The consumer token may live "
                    + "only in your phone's Ola app — use “Set token” instead.", Ui.WARN);
            return;
        }
        String token = extract(out, true);
        String uuid = extract(out, false);
        if (token != null) {
            showImportDialog(token, uuid, out);
        } else {
            showRawDialog(out);
        }
    }

    /** Pulls the first token (wantToken) or uuid value out of the grep output. */
    private static String extract(String out, boolean wantToken) {
        java.util.regex.Pattern[] pats = {
                java.util.regex.Pattern.compile("name=\"([^\"]*)\">([^<]+)</string>"),
                java.util.regex.Pattern.compile("\"([^\"]*)\"\\s*[:=]\\s*\"([^\"]+)\"")
        };
        for (java.util.regex.Pattern p : pats) {
            java.util.regex.Matcher m = p.matcher(out);
            while (m.find()) {
                String key = m.group(1).toLowerCase(Locale.ROOT);
                String val = m.group(2).trim();
                if (val.isEmpty() || val.equalsIgnoreCase("null")) {
                    continue;
                }
                boolean isToken = key.contains("token");
                boolean isUuid = key.contains("uda") || key.contains("uuid");
                if (wantToken && isToken && val.length() >= 20) {
                    return val;
                }
                if (!wantToken && isUuid && val.length() >= 8) {
                    return val;
                }
            }
        }
        return null;
    }

    private void showImportDialog(String token, String uuid, String raw) {
        String msg = "Found an access token (" + token.length() + " chars: " + mask(token) + ")"
                + (uuid != null ? "\nand a UUID (" + mask(uuid) + ")" : "")
                + "\n\nImport it into the Cloud tab? It stays on this device and is used "
                + "only for read-only polls.";
        setStatus("Token found on the scooter.", Ui.ACCENT);
        new AlertDialog.Builder(ctx)
                .setTitle("Token found")
                .setMessage(msg)
                .setPositiveButton("Import", (d, w) -> {
                    OlaCloud.saveCreds(ctx, token, uuid == null ? OlaCloud.uuid(ctx) : uuid);
                    setStatus("Token imported from the scooter. Refreshing …", Ui.ACCENT);
                    refresh();
                })
                .setNeutralButton("Show raw", (d, w) -> showRawDialog(raw))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRawDialog(String raw) {
        setStatus("Could not auto-detect a token — raw matches shown.", Ui.WARN);
        TextView tv = Ui.codeBlock(Ui.text(ctx, trim(raw, 4000), 11, Ui.TEXT));
        tv.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(ctx);
        int pad = Ui.dp(ctx, 12);
        scroll.setPadding(pad, pad, pad, 0);
        scroll.addView(tv);
        new AlertDialog.Builder(ctx)
                .setTitle("Matches on the scooter")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    private static String mask(String s) {
        if (s.length() <= 10) {
            return "…";
        }
        return s.substring(0, 6) + "…" + s.substring(s.length() - 4);
    }

    private static String trim(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private void showTokenDialog() {
        LinearLayout box = Ui.column(ctx);
        int pad = Ui.dp(ctx, 16);
        box.setPadding(pad, Ui.dp(ctx, 8), pad, 0);

        box.addView(Ui.note(ctx, "Paste the two header values from your logged-in Ola "
                + "session (see OLA_CLOUD_API.md). The token is stored only on this device."));

        final EditText token = field("X-ACCESS-TOKEN", OlaCloud.token(ctx));
        box.addView(Ui.space(ctx, 8));
        box.addView(token);
        final EditText uuid = field("X-UDA-UUID (optional)", OlaCloud.uuid(ctx));
        box.addView(Ui.space(ctx, 8));
        box.addView(uuid);

        new AlertDialog.Builder(ctx)
                .setTitle("Set Ola token")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    OlaCloud.saveCreds(ctx,
                            token.getText().toString(), uuid.getText().toString());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPinDialog() {
        final boolean set = Lock.isSet(ctx);
        LinearLayout box = Ui.column(ctx);
        int pad = Ui.dp(ctx, 16);
        box.setPadding(pad, Ui.dp(ctx, 8), pad, 0);

        final EditText current = set ? pinField("Current PIN") : null;
        if (set) {
            box.addView(Ui.note(ctx, "Change the app PIN, or leave the new PIN blank to remove the lock."));
            box.addView(Ui.space(ctx, 8));
            box.addView(current);
        } else {
            box.addView(Ui.note(ctx, "Set a 4–6 digit PIN. It's asked every time the app opens, "
                    + "so your location and token stay private."));
        }
        final EditText p1 = pinField(set ? "New PIN (blank = remove)" : "New PIN");
        final EditText p2 = pinField("Confirm PIN");
        box.addView(Ui.space(ctx, 8));
        box.addView(p1);
        box.addView(Ui.space(ctx, 8));
        box.addView(p2);

        new AlertDialog.Builder(ctx)
                .setTitle(set ? "Change app PIN" : "Set app PIN")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    if (set && !Lock.verify(ctx, current.getText().toString().trim())) {
                        setStatus("Current PIN is wrong.", Ui.BAD);
                        return;
                    }
                    String a = p1.getText().toString().trim();
                    String b = p2.getText().toString().trim();
                    if (set && a.isEmpty()) {
                        Lock.clear(ctx);
                        setStatus("App PIN removed.", Ui.WARN);
                        return;
                    }
                    if (a.length() < 4 || a.length() > 6) {
                        setStatus("PIN must be 4–6 digits.", Ui.BAD);
                        return;
                    }
                    if (!a.equals(b)) {
                        setStatus("The two PINs don't match.", Ui.BAD);
                        return;
                    }
                    Lock.setPin(ctx, a);
                    setStatus("App PIN set. It locks the next time the app opens.", Ui.ACCENT);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPatternDialog() {
        final boolean set = Pattern.isSet(ctx);
        LinearLayout box = Ui.column(ctx);
        int pad = Ui.dp(ctx, 16);
        box.setPadding(pad, Ui.dp(ctx, 8), pad, 0);

        final TextView hint = Ui.note(ctx, set
                ? "Draw your current pattern to change it."
                : "Draw an unlock pattern through at least " + Pattern.MIN_DOTS
                        + " dots, then draw it again to confirm. It's asked on top of "
                        + "the PIN every time the app opens.");
        box.addView(hint);
        box.addView(Ui.space(ctx, 8));

        final PatternView pad2 = new PatternView(ctx, null);
        box.addView(pad2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 260)));

        // Flow state: whether the current pattern is cleared (always true when
        // none is set yet), and the first draw of the new pattern awaiting
        // confirmation.
        final boolean[] currentOk = {!set};
        final int[][] first = {null};

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                .setTitle(set ? "Change app pattern" : "Set app pattern")
                .setView(box)
                .setNegativeButton("Cancel", null);
        if (set) {
            builder.setNeutralButton("Remove", null);   // real handler wired after show()
        }
        final AlertDialog dialog = builder.create();

        pad2.setListener(nodes -> {
            if (!currentOk[0]) {
                if (Pattern.verify(ctx, nodes)) {
                    currentOk[0] = true;
                    hint.setText("Draw a new pattern, then draw it again to confirm.");
                    hint.setTextColor(Ui.DIM);
                    pad2.clearPattern();
                } else {
                    hint.setText("Wrong current pattern.");
                    hint.setTextColor(Ui.BAD);
                    pad2.failAndClear();
                }
                return;
            }
            if (nodes.length < Pattern.MIN_DOTS) {
                hint.setText("Connect at least " + Pattern.MIN_DOTS + " dots.");
                hint.setTextColor(Ui.BAD);
                pad2.failAndClear();
                return;
            }
            if (first[0] == null) {
                first[0] = nodes;
                hint.setText("Good — draw the same pattern again to confirm.");
                hint.setTextColor(Ui.DIM);
                pad2.clearPattern();
                return;
            }
            if (!same(first[0], nodes)) {
                first[0] = null;
                hint.setText("Patterns didn't match — draw a new one.");
                hint.setTextColor(Ui.BAD);
                pad2.failAndClear();
                return;
            }
            Pattern.setPattern(ctx, nodes);
            setStatus("App pattern set. It locks the next time the app opens.", Ui.ACCENT);
            dialog.dismiss();
        });

        dialog.show();
        // Override Remove so a wrong/absent current pattern can't clear the lock,
        // and so the dialog stays open until the user has proven the current one.
        if (set) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (!currentOk[0]) {
                    hint.setText("Draw your current pattern first, then tap Remove.");
                    hint.setTextColor(Ui.WARN);
                } else {
                    Pattern.clear(ctx);
                    setStatus("App pattern removed.", Ui.WARN);
                    dialog.dismiss();
                }
            });
        }
    }

    /** True when two dot sequences are identical in order and length. */
    private static boolean same(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextSize(14);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setSingleLine(true);
        e.setBackground(Ui.round(ctx, Ui.CARD, 10, Ui.LINE));
        e.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        return e;
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

    // ------------------------------------------------------------------ helpers

    private void setStatus(String text, int color) {
        Ui.set(statusLine, text);
        statusLine.setTextColor(color);
    }

    /** Pulls a number out of the range field, which may be "73" or {"value":73}. */
    private static String rangeKm(String range) {
        if (range == null) {
            return Ui.DASH;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-?\\d+(?:\\.\\d+)?").matcher(range);
        return m.find() ? m.group() : Ui.DASH;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** "FAST_CHARGING" -> "Fast charging", for display. */
    private static String pretty(String s) {
        if (s == null || s.isEmpty()) {
            return Ui.DASH;
        }
        String t = s.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
