package dev.olatool;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

import dev.olatool.core.Live;

/**
 * BMS "Dashboard": the at-a-glance screen. Vehicle status, battery, GPS and a
 * few quick stats, taken live from the CAN bus and, when the scooter is asleep,
 * filled from Ola's cloud. Read-only.
 */
final class BmsDashboardTab implements Tab, Bus.Listener, CloudState.Listener {

    private static final long STALE_MS = 6_000L;

    private final Activity activity;
    private final Conn conn;
    private final Bus bus;
    private final CloudState cloud;
    private final ScrollView page;

    private final TextView statusDot;
    private final TextView statusText;
    private final TextView updated;
    private final TextView gps;
    private final TextView socBig;
    private final TextView rangeBig;
    private final ProgressBar socBar;
    private final TextView source;

    private final Ui.Stat speed;
    private final Ui.Stat power;
    private final Ui.Stat temp;
    private final Ui.Stat odo;

    private Live.Snapshot last;
    private boolean visible;

    BmsDashboardTab(Activity activity, Conn conn, Bus bus) {
        this.activity = activity;
        this.conn = conn;
        this.bus = bus;
        this.cloud = CloudState.get(activity);
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Dashboard",
                "Live status, battery and location for your scooter.", Ui.ACCENT));

        // Status card.
        LinearLayout status = Ui.card(activity);
        LinearLayout sRow = Ui.row(activity);
        statusDot = Ui.text(activity, "●", 14, Ui.DIM);
        sRow.addView(statusDot);
        statusText = Ui.text(activity, "Unknown", 18, Ui.TEXT);
        statusText.setTypeface(statusText.getTypeface(), Typeface.BOLD);
        statusText.setPadding(Ui.dp(activity, 8), 0, 0, 0);
        sRow.addView(statusText);
        status.addView(sRow);
        updated = Ui.note(activity, "Last update: —");
        updated.setPadding(0, Ui.dp(activity, 8), 0, 0);
        status.addView(updated);
        gps = Ui.note(activity, "GPS: —");
        gps.setPadding(0, Ui.dp(activity, 4), 0, 0);
        status.addView(gps);
        body.addView(status);

        body.addView(Ui.space(activity, 10));

        // Battery hero.
        LinearLayout hero = Ui.card(activity);
        hero.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 16),
                Ui.dp(activity, 16), Ui.dp(activity, 16));
        TextView label = Ui.text(activity, "BATTERY", 10, Ui.DIM);
        label.setLetterSpacing(0.08f);
        hero.addView(label);
        LinearLayout row = Ui.row(activity);
        row.setGravity(Gravity.BOTTOM);
        socBig = Ui.text(activity, Ui.DASH, 40, Ui.TEXT);
        socBig.setTypeface(socBig.getTypeface(), Typeface.BOLD);
        row.addView(socBig);
        TextView pct = Ui.text(activity, "%", 18, Ui.DIM);
        pct.setPadding(Ui.dp(activity, 2), 0, 0, Ui.dp(activity, 6));
        row.addView(pct);
        LinearLayout rangeCol = Ui.column(activity);
        rangeBig = Ui.text(activity, Ui.DASH, 20, Ui.TEXT);
        rangeBig.setTypeface(rangeBig.getTypeface(), Typeface.BOLD);
        rangeBig.setGravity(Gravity.END);
        TextView rLbl = Ui.text(activity, "range", 11, Ui.DIM);
        rLbl.setGravity(Gravity.END);
        rangeCol.addView(rangeBig);
        rangeCol.addView(rLbl);
        row.addView(rangeCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(row);
        socBar = Ui.bar(activity, Ui.ACCENT);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 6));
        barLp.topMargin = Ui.dp(activity, 10);
        hero.addView(socBar, barLp);
        source = Ui.note(activity, "");
        source.setPadding(0, Ui.dp(activity, 6), 0, 0);
        hero.addView(source);
        body.addView(hero);

        body.addView(Ui.space(activity, 10));

        speed = new Ui.Stat(activity, "Speed"); speed.unit("km/h");
        power = new Ui.Stat(activity, "Power"); power.unit("kW");
        temp = new Ui.Stat(activity, "Pack temp"); temp.unit("°C");
        odo = new Ui.Stat(activity, "Odometer"); odo.unit("km");
        body.addView(Ui.statRow(activity, speed, power));
        body.addView(Ui.space(activity, 8));
        body.addView(Ui.statRow(activity, temp, odo));
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        visible = true;
        bus.attach(this);
        cloud.addListener(this);
        cloud.start();
        cloud.pollNow();
        render(bus.snapshot());
    }

    @Override
    public void onHide() {
        visible = false;
        bus.detach(this);
        cloud.removeListener(this);
    }

    @Override
    public void onTick(Live.Snapshot s) {
        if (visible) {
            render(s);
        }
    }

    @Override
    public void onCloud(OlaCloud.Status status) {
        render(last);
    }

    private void render(Live.Snapshot s) {
        last = s;
        long now = System.currentTimeMillis();
        OlaCloud.Status cs = cloud.latest();

        boolean liveData = s != null && !s.empty() && !s.vehicleStale(now, STALE_MS);
        String state;
        int color;
        if (liveData) {
            double sp = s.speedKph;
            if (!Double.isNaN(sp) && sp > 1) {
                state = "Running";
                color = Ui.ACCENT;
            } else {
                state = "Parked (awake)";
                color = Ui.INFO;
            }
        } else if (cs != null && cs.ok) {
            state = "Asleep (cloud)";
            color = Ui.WARN;
        } else {
            state = "Not connected";
            color = Ui.DIM;
        }
        statusText.setText(state);
        statusText.setTextColor(color);
        statusDot.setTextColor(color);

        if (liveData) {
            updated.setText("Last update: live CAN");
        } else if (cs != null && cs.lastUpdated != null) {
            updated.setText("Last update: " + cs.lastUpdated + " (cloud)");
        } else {
            updated.setText("Last update: —");
        }

        Double lat = cs != null ? cs.lat : null;
        Double lon = cs != null ? cs.lon : null;
        gps.setText(lat == null || lon == null ? "GPS: —"
                : String.format(Locale.ROOT, "GPS: %.4f°, %.4f°", lat, lon));

        double soc = s == null ? Double.NaN : s.soc;
        boolean fromCloud = false;
        if (Double.isNaN(soc) && cs != null && cs.soc != null) {
            soc = cs.soc;
            fromCloud = true;
        }
        socBig.setText(Double.isNaN(soc) ? Ui.DASH : Ui.fmt(soc, 0));
        socBig.setTextColor(fromCloud ? Ui.INFO : Ui.TEXT);
        Ui.bar(socBar, Double.isNaN(soc) ? 0 : soc / 100.0);

        double range = s == null ? Double.NaN : s.rangeKm;
        rangeBig.setText(Double.isNaN(range) ? Ui.DASH : Ui.fmt(range, 0) + " km");
        source.setText(fromCloud ? "Battery from Ola cloud (scooter asleep)."
                : liveData ? "Live from the scooter." : "");

        speed.set(num(s == null ? Double.NaN : s.speedKph, 1));
        power.set(num(s == null ? Double.NaN : s.powerW() / 1000.0, 2));
        temp.set(num(s == null ? Double.NaN : s.packC, 1));
        odo.set(num(s == null ? Double.NaN : s.odoKm, 1));
    }

    private static String num(double v, int dec) {
        return Double.isNaN(v) ? Ui.DASH : Ui.fmt(v, dec);
    }
}
