package dev.olatool;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.olatool.core.Live;
import dev.olatool.core.PanelData;
import dev.olatool.core.Rpc;
import dev.olatool.core.VehicleInfo;

/**
 * Vehicle identity, range by mode, and a compact live dashboard.
 *
 * <p>The details are read once per connection from the scooter profile and
 * cached. The live numbers come from the shared {@link Bus} stream; this tab
 * never opens its own CAN stream.
 */
final class VehicleTab implements Tab, Bus.Listener {

    private static final long INFO_TTL_MS = 60_000L;
    private static final long STALE_MS = 6_000L;

    private final Activity activity;
    private final Conn conn;
    private final Bus bus;
    private final ScrollView page;

    private final LinearLayout banner;
    private final TextView bannerBody;

    private final TextView heroModel;
    private final TextView drivePill;
    private final TextView socBig;
    private final TextView rangeBig;
    private final ProgressBar socBar;
    private final TextView chargeValue;
    private final TextView packTempValue;
    private final TextView healthValue;

    private final Ui.Kv vinRow;
    private final Ui.Kv modelRow;
    private final Ui.Kv generationRow;
    private final Ui.Kv batteryRow;
    private final Ui.Kv moveOsRow;
    private final Ui.Kv uptimeRow;
    private final Ui.Kv registrationRow;
    private final Ui.Kv purchasedRow;
    private final Button refresh;

    private final RangeTile ecoTile;
    private final RangeTile normalTile;
    private final RangeTile sportTile;
    private final RangeTile hyperTile;

    private final Ui.Stat speed;
    private final Ui.Stat odo;
    private final Ui.Stat motorTemp;
    private final Ui.Stat packTemp;
    private final Ui.Stat packVoltage;
    private final Ui.Stat packCurrent;
    private final Ui.Stat batteryHealth;
    private final Ui.Stat cellSpread;

    private VehicleInfo info;
    private long infoAt;
    private boolean loading;
    /** Cached so render() never touches Conn; refreshed on show and each tick. */
    private boolean connected;

    VehicleTab(Activity activity, Conn conn, Bus bus) {
        this.activity = activity;
        this.conn = conn;
        this.bus = bus;
        this.connected = conn != null && conn.ready();

        page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        // 1. Locked / silent banner, hidden until the stream looks asleep.
        banner = Ui.column(activity);
        banner.setPadding(dp(14), dp(12), dp(14), dp(12));
        banner.setBackground(Ui.round(activity,
                Ui.alpha(Ui.WARN, 0x14), 14, Ui.alpha(Ui.WARN, 0x66)));
        banner.setVisibility(View.GONE);
        TextView bannerTitle = Ui.text(activity, "SCOOTER MIGHT BE LOCKED", 12, Ui.WARN);
        bannerTitle.setTypeface(bannerTitle.getTypeface(), Typeface.BOLD);
        banner.addView(bannerTitle);
        bannerBody = Ui.text(activity, "", 12, Ui.TEXT);
        bannerBody.setPadding(0, dp(4), 0, 0);
        banner.addView(bannerBody);
        body.addView(banner);

        // 2. Hero.
        LinearLayout hero = Ui.card(activity);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout top = Ui.row(activity);
        LinearLayout names = Ui.column(activity);
        TextView connectedLabel = Ui.text(activity, "CONNECTED VEHICLE", 10, Ui.DIM);
        connectedLabel.setLetterSpacing(0.08f);
        names.addView(connectedLabel);
        heroModel = Ui.text(activity, "Ola S1", 22, Ui.TEXT);
        heroModel.setTypeface(heroModel.getTypeface(), Typeface.BOLD);
        heroModel.setSingleLine(true);
        heroModel.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(heroModel);
        top.addView(names, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        drivePill = Ui.pill(activity, "PARKED", Ui.ACCENT);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pillLp.leftMargin = dp(8);
        top.addView(drivePill, pillLp);
        hero.addView(top);

        hero.addView(Ui.space(activity, 14));

        LinearLayout socRow = Ui.row(activity);
        LinearLayout socLeft = Ui.row(activity);
        socLeft.setGravity(Gravity.BOTTOM);
        socBig = Ui.text(activity, Ui.DASH, 40, Ui.TEXT);
        socBig.setTypeface(socBig.getTypeface(), Typeface.BOLD);
        socLeft.addView(socBig);
        TextView percent = Ui.text(activity, "%", 18, Ui.DIM);
        percent.setPadding(0, 0, 0, dp(6));
        socLeft.addView(percent);
        socRow.addView(socLeft, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout rangeCol = Ui.column(activity);
        rangeCol.setGravity(Gravity.END);
        rangeBig = Ui.text(activity, Ui.DASH, 22, Ui.TEXT);
        rangeBig.setTypeface(rangeBig.getTypeface(), Typeface.BOLD);
        rangeBig.setGravity(Gravity.END);
        rangeCol.addView(rangeBig);
        TextView rangeLabel = Ui.text(activity, "EST. RANGE", 10, Ui.DIM);
        rangeLabel.setLetterSpacing(0.08f);
        rangeLabel.setGravity(Gravity.END);
        rangeCol.addView(rangeLabel);
        socRow.addView(rangeCol);
        hero.addView(socRow);

        hero.addView(Ui.space(activity, 8));
        socBar = Ui.bar(activity, Ui.ACCENT);
        hero.addView(socBar);
        hero.addView(Ui.space(activity, 6));

        LinearLayout miniRow = Ui.row(activity);
        miniRow.setGravity(Gravity.TOP);
        Mini charging = mini("Charging");
        Mini temp = mini("Pack temp");
        Mini health = mini("Battery health");
        chargeValue = charging.value;
        packTempValue = temp.value;
        healthValue = health.value;
        miniRow.addView(charging.root, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        midLp.leftMargin = dp(8);
        miniRow.addView(temp.root, midLp);
        LinearLayout.LayoutParams lastLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lastLp.leftMargin = dp(8);
        miniRow.addView(health.root, lastLp);
        hero.addView(miniRow);

        body.addView(hero, margin());

        // 3. Vehicle details, read once from the profile database.
        Ui.Section details = Ui.section(activity, "Vehicle details",
                "Read once after connecting");
        vinRow = addKv(details.body, "VIN");
        vinRow.value.setTextSize(12);
        vinRow.value.setSingleLine(true);
        vinRow.value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        vinRow.value.setMaxWidth(dp(200));
        modelRow = addKv(details.body, "Model");
        generationRow = addKv(details.body, "Generation");
        batteryRow = addKv(details.body, "Battery");
        moveOsRow = addKv(details.body, "MoveOS");
        uptimeRow = addKv(details.body, "Uptime");
        registrationRow = addKv(details.body, "Registration");
        registrationRow.value.setTextSize(12);
        registrationRow.value.setSingleLine(true);
        registrationRow.value.setEllipsize(TextUtils.TruncateAt.END);
        registrationRow.value.setMaxWidth(dp(200));
        purchasedRow = addKv(details.body, "Purchased");

        TextView detailsNote = Ui.text(activity,
                "Registration and purchase date are read from the scooter's profile "
                        + "database when it is readable.", 11, Ui.DIM);
        detailsNote.setPadding(0, dp(8), 0, 0);
        details.body.addView(detailsNote);

        refresh = Ui.button(activity, "Refresh", v -> loadInfo(true));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        refreshLp.topMargin = dp(8);
        details.body.addView(refresh, refreshLp);
        body.addView(details.card, margin());

        // 4. Range by mode.
        Ui.Section range = Ui.section(activity, "Range by mode",
                "Dash estimate for each mode");
        ecoTile = rangeTile("ECO");
        normalTile = rangeTile("NORMAL");
        sportTile = rangeTile("SPORT");
        hyperTile = rangeTile("HYPER");
        range.body.addView(Ui.statRow(activity, ecoTile.stat, normalTile.stat));
        range.body.addView(Ui.space(activity, 8));
        range.body.addView(Ui.statRow(activity, sportTile.stat, hyperTile.stat));
        body.addView(range.card, margin());

        // 5. Live stats.
        Ui.Section live = Ui.section(activity, "Live stats",
                "Updated from the CAN bus");
        speed = stat("Speed", "km/h");
        odo = stat("Odometer", "km");
        motorTemp = stat("Motor temperature", "°C");
        packTemp = stat("Pack temperature", "°C");
        packVoltage = stat("Pack voltage", "V");
        packCurrent = stat("Pack current", "A");
        batteryHealth = stat("Battery health", "%");
        cellSpread = stat("Cell spread", "mV");
        live.body.addView(Ui.statRow(activity, speed, odo));
        live.body.addView(Ui.space(activity, 8));
        live.body.addView(Ui.statRow(activity, motorTemp, packTemp));
        live.body.addView(Ui.space(activity, 8));
        live.body.addView(Ui.statRow(activity, packVoltage, packCurrent));
        live.body.addView(Ui.space(activity, 8));
        live.body.addView(Ui.statRow(activity, batteryHealth, cellSpread));
        body.addView(live.card, margin());

        TextView footer = Ui.text(activity,
                "Values come from the scooter's CAN bus. A dash means the vehicle "
                        + "has not reported that signal yet.", 11, Ui.DIM);
        footer.setPadding(0, dp(10), 0, 0);
        body.addView(footer);
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        connected = conn != null && conn.ready();
        if (bus != null) {
            bus.attach(this);
            Live live = bus.live();
            Live.Snapshot snapshot = live == null ? null : live.snapshot();
            render(PanelData.from(snapshot, live));
        } else {
            render(PanelData.from(null, null));
        }
        if (info == null || System.currentTimeMillis() - infoAt > INFO_TTL_MS) {
            loadInfo(false);
        }
    }

    @Override
    public void onHide() {
        if (bus != null) {
            bus.detach(this);
        }
    }

    @Override
    public void onTick(Live.Snapshot s) {
        connected = conn != null && conn.ready();
        render(PanelData.from(s, bus == null ? null : bus.live()));
    }

    void render(PanelData d) {
        if (d == null) {
            d = new PanelData();
        }

        long now = System.currentTimeMillis();
        boolean showBanner;
        String message;
        if (d.frames == 0 && connected) {
            showBanner = true;
            message = "Connected, but no CAN frames yet. Turn the dash on; "
                    + "this clears by itself.";
        } else if (d.vehicleStale(now, STALE_MS)) {
            showBanner = true;
            message = "No vehicle CAN data for more than 6 seconds. The scooter may be "
                    + "asleep or locked. Turn the dash on.";
        } else {
            showBanner = false;
            message = "";
        }
        Ui.set(bannerBody, message);
        banner.setVisibility(showBanner ? View.VISIBLE : View.GONE);

        updateModel();
        String mode = d.driveMode == null ? "" : d.driveMode.trim();
        Ui.pill(drivePill, mode.isEmpty() ? "PARKED" : mode, Ui.ACCENT);

        Ui.set(socBig, num(d.soc, 0));
        Ui.set(rangeBig, Double.isNaN(d.rangeKm) ? Ui.DASH
                : Ui.fmt(d.rangeKm, 0) + " km");
        Ui.bar(socBar, d.soc / 100.0);

        String charging;
        if (d.charging == 1) {
            charging = "CHARGING";
        } else if (d.plugged == 1) {
            charging = "PLUGGED IN";
        } else if (d.charging == 0 || d.plugged == 0) {
            charging = "NOT CHARGING";
        } else {
            charging = Ui.DASH;
        }
        Ui.set(chargeValue, charging);
        Ui.set(packTempValue, withUnit(d.packC, 1, "°C"));
        Ui.set(healthValue, withUnit(d.soh, 1, "%"));

        double eco = d.value("Vehicle_Range_Limp");
        double normal = d.value("Vehicle_Range_Normal");
        double sport = d.value("Vehicle_Range_Sport");
        double hyper = d.value("Vehicle_Range_Hyper");
        double max = Math.max(Math.max(positive(eco), positive(normal)),
                Math.max(positive(sport), positive(hyper)));
        renderRange(ecoTile, eco, max);
        renderRange(normalTile, normal, max);
        renderRange(sportTile, sport, max);
        renderRange(hyperTile, hyper, max);

        speed.set(num(d.speedKph, 1));
        odo.set(num(d.odoKm, 1));
        motorTemp.set(num(d.motorC, 1), tempColor(d.motorC));
        packTemp.set(num(d.packC, 1), tempColor(d.packC));
        packVoltage.set(num(d.packV, 2));
        packCurrent.set(num(d.packA, 1));
        batteryHealth.set(num(d.soh, 1));
        cellSpread.set(num(d.cellSpreadV() * 1000.0, 0));
    }

    void applyInfo(VehicleInfo info) {
        if (info == null) {
            return;
        }
        this.info = info;
        this.infoAt = System.currentTimeMillis();

        Ui.set(vinRow.value, orDash(info.vin));
        Ui.set(modelRow.value, orDash(info.model));
        Ui.set(generationRow.value, orDash(info.generation));
        Ui.set(batteryRow.value, orDash(info.batteryVariant));
        Ui.set(moveOsRow.value, orDash(info.moveOs));
        Ui.set(uptimeRow.value, orDash(info.uptime));
        Ui.set(registrationRow.value, orNotAvailable(info.registration));
        Ui.set(purchasedRow.value, orNotAvailable(info.purchaseDate));
        updateModel();
    }

    private void loadInfo(boolean force) {
        if (loading || conn == null) {
            return;
        }
        Rpc rpc = conn.rpc();
        if (rpc == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && info != null && now - infoAt < INFO_TTL_MS) {
            return;
        }

        loading = true;
        refresh.setEnabled(false);
        Ui.set(refresh, "Reading...");
        final Rpc target = rpc;
        new Thread(new Runnable() {
            @Override
            public void run() {
                VehicleInfo loaded = null;
                try {
                    loaded = VehicleInfo.load(target);
                } catch (Exception ignored) {
                    // Keep the last good values; the user can retry.
                }
                final VehicleInfo result = loaded;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        refresh.setEnabled(true);
                        Ui.set(refresh, "Refresh");
                        if (result != null) {
                            applyInfo(result);
                        }
                    }
                });
            }
        }, "vehicle-info").start();
    }

    private void updateModel() {
        String model = info == null ? null : info.model;
        Ui.set(heroModel, model == null || model.trim().isEmpty()
                ? "Ola S1" : model.trim());
    }

    private Mini mini(String label) {
        LinearLayout root = Ui.column(activity);
        TextView key = Ui.text(activity, label, 10, Ui.DIM);
        key.setLetterSpacing(0.06f);
        root.addView(key);
        TextView value = Ui.text(activity, Ui.DASH, 12, Ui.TEXT);
        value.setPadding(0, dp(2), 0, 0);
        root.addView(value);
        return new Mini(root, value);
    }

    private RangeTile rangeTile(String label) {
        Ui.Stat tile = new Ui.Stat(activity, label);
        tile.unit("km");
        ProgressBar bar = Ui.bar(activity, Ui.ACCENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        lp.topMargin = dp(6);
        bar.setLayoutParams(lp);
        tile.addView(bar);
        return new RangeTile(tile, bar);
    }

    private Ui.Stat stat(String label, String unit) {
        Ui.Stat s = new Ui.Stat(activity, label);
        s.unit(unit);
        return s;
    }

    private Ui.Kv addKv(LinearLayout parent, String label) {
        Ui.Kv kv = new Ui.Kv(activity, label);
        parent.addView(kv);
        return kv;
    }

    private LinearLayout.LayoutParams margin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    private int dp(int value) {
        return Ui.dp(activity, value);
    }

    private static void renderRange(RangeTile tile, double value, double max) {
        tile.stat.set(num(value, 0));
        Ui.bar(tile.bar, max <= 0 ? 0 : value / max);
    }

    private static String num(double value, int decimals) {
        return Double.isNaN(value) ? Ui.DASH : Ui.fmt(value, decimals);
    }

    private static String withUnit(double value, int decimals, String unit) {
        return Double.isNaN(value) ? Ui.DASH : Ui.fmt(value, decimals) + " " + unit;
    }

    private static int tempColor(double celsius) {
        if (Double.isNaN(celsius)) {
            return Ui.TEXT;
        }
        if (celsius > 80) {
            return Ui.BAD;
        }
        if (celsius > 60) {
            return Ui.WARN;
        }
        return Ui.TEXT;
    }

    private static double positive(double value) {
        return Double.isNaN(value) || value <= 0 ? 0 : value;
    }

    private static String orDash(String value) {
        return value == null || value.trim().isEmpty() ? Ui.DASH : value.trim();
    }

    private static String orNotAvailable(String value) {
        return value == null || value.trim().isEmpty()
                ? "Not available" : value.trim();
    }

    private static final class Mini {
        final LinearLayout root;
        final TextView value;

        Mini(LinearLayout root, TextView value) {
            this.root = root;
            this.value = value;
        }
    }

    private static final class RangeTile {
        final Ui.Stat stat;
        final ProgressBar bar;

        RangeTile(Ui.Stat stat, ProgressBar bar) {
            this.stat = stat;
            this.bar = bar;
        }
    }
}
