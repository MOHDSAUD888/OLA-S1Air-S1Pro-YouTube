package dev.olatool;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

import dev.olatool.core.Live;

/**
 * Battery health, from the shared stream.
 *
 * <p>This used to read frames itself with unary {@code ReadCanFrame}. It now
 * listens to {@link Bus}, so the pack, the motor and the fault screen all see
 * the same frames instead of competing for them.
 */
public final class BatteryTab implements Tab {

    private final MainActivity activity;
    private final Bus bus;
    private final ScrollView page;

    private final TextView verdict;
    private final TextView hint;
    private final BmsCellPanel cellPanel;
    private final Ui.Kv imbalance;
    private final Ui.Kv mcuVolt;
    private final Button liveButton;

    private final Ui.Kv soc;
    private final Ui.Kv soh;
    private final Ui.Kv volts;
    private final Ui.Kv amps;
    private final Ui.Kv power;
    private final Ui.Kv temp;
    private final Ui.Kv cellMin;
    private final Ui.Kv cellMax;
    private final Ui.Kv spread;

    private final Ui.Kv speed;
    private final Ui.Kv range;
    private final Ui.Kv mode;
    private final Ui.Kv rpm;
    private final Ui.Kv odo;

    private final Ui.Kv charging;
    private final Ui.Kv plugged;
    private final Ui.Kv chargeRate;
    private final Ui.Kv cycles;

    private final Ui.Kv fall;
    private final Ui.Kv tow;
    private final Ui.Kv regen;
    private final Ui.Kv hill;

    /** Older than this and the last reading is no longer treated as current. */
    private static final long STALE_MS = 6000;

    private boolean live;
    private boolean visible;

    private final Bus.Listener listener = snapshot -> {
        if (visible && live) {
            render(snapshot);
        }
    };

    public BatteryTab(MainActivity activity, Conn conn, Bus bus) {
        this.activity = activity;
        this.bus = bus;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        verdict = Ui.text(activity, "Not read yet", 18, Ui.DIM);
        body.addView(verdict);

        hint = Ui.note(activity, "");
        hint.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 10));
        body.addView(hint);

        liveButton = Ui.accent(activity, "Stop live", v -> toggle());
        body.addView(liveButton);

        LinearLayout pack = Ui.card(activity);
        pack.addView(Ui.heading(activity, "Pack"));
        soc = add(pack, "State of charge");
        soh = add(pack, "State of health");
        volts = add(pack, "Voltage");
        amps = add(pack, "Current");
        power = add(pack, "Power");
        temp = add(pack, "Pack temperature");
        cellMin = add(pack, "Cell minimum");
        cellMax = add(pack, "Cell maximum");
        spread = add(pack, "Cell spread");
        imbalance = add(pack, "Imbalance (Δ)");
        mcuVolt = add(pack, "MCU voltage");
        body.addView(pack, margin());

        // Per-cell grid: one bar per cell, scaled across the live pack min–max,
        // the weakest cell amber, the strongest accent, a balancing cell blue.
        LinearLayout cells = Ui.card(activity);
        cells.addView(Ui.heading(activity, "Cells (14S)"));
        cellPanel = new BmsCellPanel(activity);
        cells.addView(cellPanel);
        body.addView(cells, margin());

        LinearLayout ride = Ui.card(activity);
        ride.addView(Ui.heading(activity, "Ride"));
        speed = add(ride, "Speed");
        range = add(ride, "Range");
        mode = add(ride, "Drive mode");
        rpm = add(ride, "Motor speed");
        odo = add(ride, "Odometer");
        body.addView(ride, margin());

        LinearLayout charge = Ui.card(activity);
        charge.addView(Ui.heading(activity, "Charging"));
        charging = add(charge, "Charging");
        plugged = add(charge, "Charger plugged in");
        chargeRate = add(charge, "Charging rate");
        cycles = add(charge, "Cycle count");
        body.addView(charge, margin());

        LinearLayout safety = Ui.card(activity);
        safety.addView(Ui.heading(activity, "Safety state (0x683)"));
        fall = add(safety, "Fall detection");
        tow = add(safety, "Tow / theft");
        regen = add(safety, "Regen");
        hill = add(safety, "Hill hold");
        body.addView(safety, margin());

        TextView footer = Ui.note(activity,
                "SOH is the raw byte in 0x150. Cell spread under 30 mV is healthy; "
                        + "a large spread under load points at a weak cell. "
                        + "If nothing arrives, the vehicle is asleep: press the red "
                        + "start button or plug in the charger.");
        footer.setPadding(0, Ui.dp(activity, 10), 0, 0);
        body.addView(footer);
    }

    /** One cell: number, a bar scaled across the pack range, its mV, a balance dot. */
    private static final class CellRow extends LinearLayout {
        final TextView num;
        final ProgressBar bar;
        final TextView mv;
        final TextView balance;

        CellRow(MainActivity c, int index) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));

            num = Ui.text(c, "C" + index, 12, Ui.DIM);
            addView(num, new LayoutParams(Ui.dp(c, 30), LayoutParams.WRAP_CONTENT));

            bar = Ui.bar(c, Ui.ACCENT);
            LayoutParams bp = new LayoutParams(0, Ui.dp(c, 8), 1f);
            bp.leftMargin = Ui.dp(c, 6);
            bp.rightMargin = Ui.dp(c, 8);
            addView(bar, bp);

            mv = Ui.text(c, Ui.DASH, 12, Ui.TEXT);
            mv.setGravity(Gravity.END);
            mv.setTypeface(Typeface.MONOSPACE);
            addView(mv, new LayoutParams(Ui.dp(c, 58), LayoutParams.WRAP_CONTENT));

            balance = Ui.text(c, "", 10, Ui.INFO);
            balance.setGravity(Gravity.END);
            addView(balance, new LayoutParams(Ui.dp(c, 18), LayoutParams.WRAP_CONTENT));
        }

        void set(double volt, double lo, double hi, boolean balancing, boolean isMin, boolean isMax) {
            if (Double.isNaN(volt) || volt <= 0) {
                mv.setText(Ui.DASH);
                Ui.bar(bar, 0);
                balance.setText("");
                return;
            }
            mv.setText(String.format(Locale.ROOT, "%d mV", (int) (volt * 1000.0)));
            double span = hi - lo;
            double frac = span < 0.001 ? 1.0 : (volt - lo) / span;
            Ui.bar(bar, Math.max(0.04, Math.min(1, frac)));
            int color = balancing ? Ui.INFO : isMin ? Ui.WARN : isMax ? Ui.ACCENT : Ui.CELL_NORMAL;
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
            mv.setTextColor(isMin ? Ui.WARN : isMax ? Ui.ACCENT : Ui.TEXT);
            balance.setText(balancing ? "BAL" : "");
        }
    }

    private Ui.Kv add(LinearLayout parent, String label) {
        Ui.Kv kv = new Ui.Kv(activity, label);
        parent.addView(kv);
        return kv;
    }

    private LinearLayout.LayoutParams margin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, 10);
        return lp;
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        visible = true;
        bus.attach(listener);
        live = true;
        liveButton.setText("Stop");
        if (bus.snapshot() != null) {
            render(bus.snapshot());
        } else {
            hint.setText(bus.state());
        }
    }

    @Override
    public void onHide() {
        visible = false;
        bus.detach(listener);
    }

    private void toggle() {
        live = !live;
        liveButton.setText(live ? "Stop" : "Start live");
        if (live) {
            bus.attach(listener);
            if (bus.snapshot() != null) {
                render(bus.snapshot());
            }
        } else {
            bus.detach(listener);
        }
    }

    // --------------------------------------------------------------- render

    private void render(Live.Snapshot s) {
        if (s == null) {
            hint.setText(bus.state());
            return;
        }
        long now = System.currentTimeMillis();
        boolean stale = s.sohStale(now, STALE_MS);
        boolean noSoh = Double.isNaN(s.soh);
        if (s.empty()) {
            verdict.setText("No frames yet");
            verdict.setTextColor(Ui.DIM);
            hint.setText("The bus is silent. The vehicle is probably asleep — press the "
                    + "red start button or plug in the charger.");
        } else if (stale || noSoh) {
            // The values below are the last ones seen, not a live reading. Do not
            // turn them into a health verdict while the bus is silent.
            verdict.setText("Pack: no live data");
            verdict.setTextColor(Ui.DIM);
            hint.setText(stale
                    ? "No pack SOH for " + ((now - s.sohAt) / 1000)
                            + " s. The scooter is asleep or the battery data stopped."
                    : "Frames are arriving but no pack SOH yet. Waiting for the battery.");
        } else {
            String health = sohVerdict(s.soh);
            verdict.setText("Pack: " + health);
            verdict.setTextColor(healthColor(health));
            hint.setText("Frames " + s.frames + "   " + s.pps + "/s   "
                    + s.messagesSeen + " messages   " + s.signalsSeen + " signals");
        }

        soc.set(Ui.pct(s.soc));
        soh.set(stale || noSoh ? Ui.DASH : Ui.pct(s.soh) + "  " + sohVerdict(s.soh));
        volts.set(Ui.fmt(s.packV, 2) + " V");
        amps.set(Ui.fmt(s.packA, 2) + " A");
        power.set(Ui.fmt(s.powerW() / 1000.0, 2) + " kW");
        temp.set(Ui.fmt(s.packC, 1) + " °C");
        cellMin.set(Ui.fmt(s.cellMinV * 1000.0, 0) + " mV");
        cellMax.set(Ui.fmt(s.cellMaxV * 1000.0, 0) + " mV");
        spread.set(Ui.fmt(s.cellSpreadV() * 1000.0, 0) + " mV  "
                + spreadVerdict(s.cellSpreadV() * 1000.0),
                healthColor(spreadVerdict(s.cellSpreadV() * 1000.0)));
        imbalance.set(Double.isNaN(s.imbalanceV) ? Ui.DASH
                : Ui.fmt(s.imbalanceV * 1000.0, 0) + " mV");
        mcuVolt.set(Double.isNaN(s.mcuV) ? Ui.DASH : Ui.fmt(s.mcuV, 2) + " V");

        speed.set(Ui.fmt(s.speedKph, 1) + " km/h");
        range.set(Ui.fmt(s.rangeKm, 0) + " km");
        mode.set(s.driveMode.isEmpty() ? "-" : s.driveMode);
        rpm.set(Ui.fmt(s.rpm, 0) + " rpm");
        odo.set(Ui.fmt(s.odoKm, 1) + " km");

        charging.set(tri(s.charging));
        plugged.set(tri(s.plugged));
        chargeRate.set(Ui.fmt(s.chargeRate, 0) + " %");
        cycles.set(Ui.fmt(s.cycles, 0));

        fall.set(s.fallName());
        tow.set(s.towName());
        regen.set(s.regenName());
        hill.set(s.hillName());

        cellPanel.update(s, bus == null ? null : bus.live());
    }

    private static String tri(int value) {
        if (value < 0) {
            return "-";
        }
        return value == 1 ? "yes" : "no";
    }

    /** Same thresholds the old snapshot used. */
    private static String sohVerdict(double soh) {
        if (Double.isNaN(soh)) {
            return "no reading";
        }
        if (soh >= 90) {
            return "healthy";
        }
        if (soh >= 80) {
            return "watch";
        }
        return "degraded";
    }

    private static String spreadVerdict(double millivolts) {
        if (Double.isNaN(millivolts)) {
            return "no reading";
        }
        if (millivolts < 30) {
            return "healthy";
        }
        if (millivolts < 60) {
            return "watch";
        }
        return "large";
    }

    private static int healthColor(String verdict) {
        if (verdict.startsWith("healthy")) {
            return Ui.ACCENT;
        }
        if (verdict.startsWith("watch")) {
            return Ui.WARN;
        }
        if (verdict.startsWith("no ")) {
            return Ui.DIM;
        }
        return Ui.BAD;
    }
}
