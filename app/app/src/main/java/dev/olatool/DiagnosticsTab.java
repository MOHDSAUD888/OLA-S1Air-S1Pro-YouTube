package dev.olatool;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;

import dev.olatool.core.Live;
import dev.olatool.core.PanelData;

/**
 * A read-only diagnostics board: every input the scooter reports, laid out in
 * one place. It shares the single {@link Bus} stream and never writes anything
 * back to the vehicle.
 */
final class DiagnosticsTab implements Tab, Bus.Listener {

    private static final long STALE_MS = 6000L;
    private static final long PRESS_HOLD_MS = 1200L;

    private static final String[] SWITCHES = {
            "High_Low_beam_short_press",
            "Horn",
            "Left_Indicator",
            "Right_Indicator",
            "Drive_Mode",
            "Cruise_mode",
            "Accept_Conf_short_press",
            "Reject_Cancel_short_press",
            "Play_Pause_short_press",
    };

    private static final String[] SWITCH_LABELS = {
            "High beam",
            "Horn",
            "Left indicator",
            "Right indicator",
            "Mode button",
            "Cruise",
            "Accept",
            "Reject",
            "Play/Pause",
    };

    private static final String[] MOTOR_LABELS = {
            "MCU temperature",
            "Motor temperature",
            "Motor speed",
            "MCU voltage",
            "Motor current",
            "Motor torque",
            "Motor frequency",
            "Position counter",
            "Rotor angle",
            "Throttle",
    };

    private static final String[] MOTOR_UNITS = {
            "\u00B0C",
            "\u00B0C",
            "RPM",
            "V",
            "A",
            "Nm",
            "Hz",
            "",
            "\u00B0",
            "V",
    };

    private static final String[] CHARGE_SIGNALS = {
            null,
            "Total_Charge_Time",
            "Total_Kwhr_Charged_in_the_Mode",
            "Factored_Charge_Amphr",
            "Time_to_chargeSC_80",
            "Time_to_Full_chargeSC_00",
            "Time_to_chargeFC_80",
            "Time_to_chargeFC_100",
    };

    private static final int[] CHARGE_DECIMALS = {1, 0, 2, 2, 0, 0, 0, 0};

    private static final String[] CHARGE_UNITS = {
            "%/h", "min", "kWh", "Ah", "min", "min", "min", "min",
    };

    private final Activity activity;
    private final Conn conn;
    private final Bus bus;
    private final ScrollView page;

    private final LinearLayout banner;
    private final TextView bannerBody;
    private final TextView statusTitle;
    private final TextView statusSub;
    private final TextView statusPill;

    private final Ui.Stat lockStat;
    private final Ui.Stat seatStat;
    private final Ui.Stat standStat;
    private final Ui.Stat frontBrakeStat;
    private final Ui.Stat rearBrakeStat;

    private final Ui.Stat[] switchStats;
    private final HashMap<String, Long> pressedAt = new HashMap<>();

    private final Ui.Stat[] motorStats;

    private final TextView chargePill;
    private final Ui.Stat[] chargeStats;

    private final TextView deltaValue;
    private final TextView minValue;
    private final TextView maxValue;
    private final Ui.Stat[] cellStats;
    private final TextView[] cellPills;
    private final String[] balanceSignals;

    /** Mirrors {@code conn.ready()} without letting render touch the connection. */
    private boolean connected;

    DiagnosticsTab(Activity activity, Conn conn, Bus bus) {
        this.activity = activity;
        this.conn = conn;
        this.bus = bus;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        // 1 -------------------------------------------------------- locked banner
        banner = Ui.column(activity);
        banner.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 12),
                Ui.dp(activity, 14), Ui.dp(activity, 12));
        banner.setBackground(Ui.round(activity, Ui.alpha(Ui.WARN, 0x16), 12,
                Ui.alpha(Ui.WARN, 0x66)));
        TextView bannerTitle = Ui.text(activity, "SCOOTER MIGHT BE LOCKED", 13, Ui.WARN);
        bannerTitle.setTypeface(bannerTitle.getTypeface(), Typeface.BOLD);
        banner.addView(bannerTitle);
        bannerBody = Ui.text(activity, "", 11, Ui.TEXT);
        bannerBody.setPadding(0, Ui.dp(activity, 4), 0, 0);
        banner.addView(bannerBody);
        banner.setVisibility(View.GONE);
        body.addView(banner);

        // 2 --------------------------------------------------------- status card
        LinearLayout statusCard = Ui.card(activity);
        LinearLayout statusRow = Ui.row(activity);
        LinearLayout statusLeft = Ui.column(activity);
        statusTitle = Ui.text(activity, "Systems operational", 20, Ui.ACCENT);
        statusTitle.setTypeface(statusTitle.getTypeface(), Typeface.BOLD);
        statusTitle.setSingleLine(true);
        statusTitle.setEllipsize(TextUtils.TruncateAt.END);
        statusLeft.addView(statusTitle);
        statusSub = Ui.text(activity, "", 11, Ui.DIM);
        statusSub.setSingleLine(true);
        statusSub.setEllipsize(TextUtils.TruncateAt.END);
        statusSub.setPadding(0, Ui.dp(activity, 3), 0, 0);
        statusLeft.addView(statusSub);
        statusRow.addView(statusLeft, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusPill = Ui.pill(activity, "WAITING", Ui.DIM);
        statusPill.setSingleLine(true);
        LinearLayout.LayoutParams statusPillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusPillLp.leftMargin = Ui.dp(activity, 8);
        statusRow.addView(statusPill, statusPillLp);
        statusCard.addView(statusRow);
        body.addView(statusCard, margin());

        // 3 --------------------------------------------------- controls & sensors
        Ui.Section controls = Ui.section(activity, "Controls & sensors",
                "Lock, stand, seat and brakes");
        lockStat = new Ui.Stat(activity, "Vehicle lock");
        seatStat = new Ui.Stat(activity, "Seat");
        standStat = new Ui.Stat(activity, "Side stand");
        frontBrakeStat = new Ui.Stat(activity, "Front brake");
        rearBrakeStat = new Ui.Stat(activity, "Rear brake");
        addGrid(controls.body, new View[]{
                lockStat, seatStat, standStat, frontBrakeStat, rearBrakeStat,
        });
        body.addView(controls.card, margin());

        // 4 --------------------------------------------------- handlebar switches
        Ui.Section switches = Ui.section(activity, "Handlebar switches",
                "Momentary presses stay lit for a moment");
        switchStats = new Ui.Stat[SWITCHES.length];
        View[] switchViews = new View[SWITCHES.length];
        for (int i = 0; i < SWITCHES.length; i++) {
            switchStats[i] = new Ui.Stat(activity, SWITCH_LABELS[i]);
            switchViews[i] = switchStats[i];
        }
        addGrid(switches.body, switchViews);
        body.addView(switches.card, margin());

        // 5 -------------------------------------------------------- motor & MCU
        Ui.Section motor = Ui.section(activity, "Motor & MCU", "Drive unit telemetry", Ui.INFO);
        motorStats = new Ui.Stat[MOTOR_LABELS.length];
        View[] motorViews = new View[MOTOR_LABELS.length];
        for (int i = 0; i < MOTOR_LABELS.length; i++) {
            motorStats[i] = new Ui.Stat(activity, MOTOR_LABELS[i]);
            motorStats[i].unit(MOTOR_UNITS[i]);
            motorViews[i] = motorStats[i];
        }
        addGrid(motor.body, motorViews);
        body.addView(motor.card, margin());

        // 6 ------------------------------------------------------------- charger
        Ui.Section charger = Ui.section(activity, "Charger",
                "Charge session and time estimates");
        LinearLayout chargeHead = Ui.row(activity);
        TextView chargeKey = Ui.text(activity, "STATUS", 10, Ui.DIM);
        chargeKey.setLetterSpacing(0.06f);
        chargeHead.addView(chargeKey, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        chargePill = Ui.pill(activity, "WAITING", Ui.DIM);
        chargePill.setSingleLine(true);
        chargeHead.addView(chargePill);
        charger.body.addView(chargeHead);
        charger.body.addView(Ui.space(activity, 8));

        String[] chargeLabels = {
                "Charge rate", "Total charge time", "Energy this session", "Charge added",
                "SC to 80%", "SC to 100%", "FC to 80%", "FC to 100%",
        };
        chargeStats = new Ui.Stat[chargeLabels.length];
        View[] chargeViews = new View[chargeLabels.length];
        for (int i = 0; i < chargeLabels.length; i++) {
            chargeStats[i] = new Ui.Stat(activity, chargeLabels[i]);
            chargeStats[i].unit(CHARGE_UNITS[i]);
            chargeViews[i] = chargeStats[i];
        }
        addGrid(charger.body, chargeViews);
        body.addView(charger.card, margin());

        // 7 ------------------------------------------------------ cell balancing
        Ui.Section cells = Ui.section(activity, "Cell balancing",
                "14 cells, live balance state");
        LinearLayout summary = Ui.row(activity);
        deltaValue = summaryCell(summary, "DELTA", false);
        minValue = summaryCell(summary, "MIN", true);
        maxValue = summaryCell(summary, "MAX", true);
        cells.body.addView(summary);
        cells.body.addView(Ui.space(activity, 10));

        cellStats = new Ui.Stat[14];
        cellPills = new TextView[14];
        balanceSignals = new String[14];
        View[] cellViews = new View[14];
        for (int i = 0; i < 14; i++) {
            balanceSignals[i] = "Balancing_Status_Battery_Cell_" + (i + 1);
            Ui.Stat tile = new Ui.Stat(activity,
                    String.format(Locale.ROOT, "CELL %02d", i + 1));
            tile.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 8),
                    Ui.dp(activity, 10), Ui.dp(activity, 8));
            TextView pill = Ui.pill(activity, Ui.DASH, Ui.DIM);
            pill.setSingleLine(true);
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            pillLp.topMargin = Ui.dp(activity, 6);
            tile.addView(pill, pillLp);
            cellStats[i] = tile;
            cellPills[i] = pill;
            cellViews[i] = tile;
        }
        addGrid(cells.body, cellViews);
        body.addView(cells.card, margin());

        // 8 -------------------------------------------------------------- footer
        TextView footer = Ui.text(activity,
                "Read-only. Nothing on this screen writes to the bus.", 11, Ui.DIM);
        footer.setPadding(0, Ui.dp(activity, 12), 0, 0);
        body.addView(footer);
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        if (bus != null) {
            bus.attach(this);
        }
        connected = conn != null && conn.ready();
        if (bus == null) {
            render(PanelData.from(null, null));
        } else {
            render(PanelData.from(bus.snapshot(), bus.live()));
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

    // --------------------------------------------------------------- render

    void render(PanelData d) {
        if (d == null) {
            d = new PanelData();
        }
        long now = System.currentTimeMillis();

        boolean stale = d.stale(now, STALE_MS);
        boolean noFramesYet = d.frames == 0 && connected;
        if (stale || noFramesYet) {
            banner.setVisibility(View.VISIBLE);
            String why = stale
                    ? "No CAN frames for more than 6 seconds. The scooter may be "
                            + "asleep or locked. Turn the dash on."
                    : "Connected, but no CAN frames yet. Turn the dash on.";
            Ui.set(bannerBody, why + "\nStream: "
                    + (bus == null ? "no bus" : bus.state()));
        } else {
            banner.setVisibility(View.GONE);
        }

        int faults = d.activeFaults;
        if (d.frames == 0) {
            Ui.set(statusTitle, "Waiting for CAN data");
            statusTitle.setTextColor(Ui.DIM);
        } else if (faults <= 0) {
            Ui.set(statusTitle, "Systems operational");
            statusTitle.setTextColor(Ui.ACCENT);
        } else {
            Ui.set(statusTitle, faults == 1 ? "1 active fault" : faults + " active faults");
            statusTitle.setTextColor(Ui.BAD);
        }
        Ui.set(statusSub, d.frames + " frames  \u00B7  " + d.pps
                + " pkt/s  \u00B7  " + d.signals.size() + " signals");
        if (d.frames == 0) {
            Ui.pill(statusPill, "WAITING", Ui.DIM);
        } else if (stale) {
            Ui.pill(statusPill, "STALE", Ui.WARN);
        } else {
            Ui.pill(statusPill, "LIVE", Ui.ACCENT);
        }

        binary(lockStat, d.unlocked, "UNLOCKED", "LOCKED", Ui.ACCENT, Ui.TEXT);
        binary(seatStat, d.seatUnlocked, "UNLOCKED", "LOCKED", Ui.ACCENT, Ui.TEXT);
        binary(standStat, d.sideStandUp, "UP", "DOWN", Ui.ACCENT, Ui.WARN);
        binary(frontBrakeStat, d.flag("Front_Brake_Press"),
                "PRESSED", "RELEASED", Ui.ACCENT, Ui.DIM);
        binary(rearBrakeStat, d.flag("Rear_Brake_Press"),
                "PRESSED", "RELEASED", Ui.ACCENT, Ui.DIM);

        for (int i = 0; i < SWITCHES.length; i++) {
            int flag = d.flag(SWITCHES[i]);
            if (flag < 0) {
                switchStats[i].set(Ui.DASH, Ui.DIM);
                continue;
            }
            if (flag >= 1) {
                pressedAt.put(SWITCHES[i], now);
            }
            Long at = pressedAt.get(SWITCHES[i]);
            boolean held = at != null && now - at < PRESS_HOLD_MS;
            switchStats[i].set(held ? "PRESSED" : "RELEASED",
                    held ? Ui.ACCENT : Ui.DIM);
        }

        motorStats[0].set(num(d.mcuC, 1), tempColor(d.mcuC));
        motorStats[1].set(num(d.motorC, 1), tempColor(d.motorC));
        motorStats[2].set(num(d.rpm, 0));
        motorStats[3].set(num(d.mcuV, 1));
        motorStats[4].set(num(d.value("Motor_Current"), 1));
        motorStats[5].set(num(d.value("Motor_Torque"), 1));
        motorStats[6].set(num(d.value("Motor_Frequency"), 1));
        motorStats[7].set(num(d.value("Position_Counter"), 0));
        motorStats[8].set(num(d.value("Rotor_Angle"), 1));
        motorStats[9].set(num(d.throttleV, 2));

        if (d.charging == 1) {
            Ui.pill(chargePill, "CHARGING", Ui.ACCENT);
        } else if (d.plugged == 1) {
            Ui.pill(chargePill, "PLUGGED IN", Ui.INFO);
        } else if (d.charging < 0 && d.plugged < 0) {
            Ui.pill(chargePill, "WAITING", Ui.DIM);
        } else {
            Ui.pill(chargePill, "NOT CONNECTED", Ui.DIM);
        }
        chargeStats[0].set(num(d.chargeRate, CHARGE_DECIMALS[0]));
        for (int i = 1; i < chargeStats.length; i++) {
            chargeStats[i].set(num(d.value(CHARGE_SIGNALS[i]), CHARGE_DECIMALS[i]));
        }

        Ui.set(deltaValue, withUnit(d.imbalanceV * 1000.0, 0, "mV"));
        Ui.set(minValue, withUnit(d.cellMinV, 3, "V"));
        Ui.set(maxValue, withUnit(d.cellMaxV, 3, "V"));
        for (int i = 0; i < cellStats.length; i++) {
            cellStats[i].set(num(d.cells[i], 3));
            int flag = d.flag(balanceSignals[i]);
            if (flag < 0) {
                Ui.pill(cellPills[i], Ui.DASH, Ui.DIM);
            } else if (flag == 1) {
                Ui.pill(cellPills[i], "BALANCING", Ui.ACCENT);
            } else {
                Ui.pill(cellPills[i], "IDLE", Ui.DIM);
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private void addGrid(LinearLayout parent, View[] tiles) {
        for (int i = 0; i < tiles.length; i += 2) {
            View right = i + 1 < tiles.length ? tiles[i + 1] : null;
            parent.addView(Ui.statRow(activity, tiles[i], right));
            if (i + 2 < tiles.length) {
                parent.addView(Ui.space(activity, 8));
            }
        }
    }

    private LinearLayout.LayoutParams margin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, 10);
        return lp;
    }

    private TextView summaryCell(LinearLayout parent, String label, boolean spaced) {
        LinearLayout col = Ui.column(activity);
        TextView key = Ui.text(activity, label, 12, Ui.DIM);
        key.setLetterSpacing(0.04f);
        TextView value = Ui.text(activity, Ui.DASH, 14, Ui.TEXT);
        value.setTypeface(value.getTypeface(), Typeface.BOLD);
        value.setSingleLine(true);
        value.setPadding(0, Ui.dp(activity, 2), 0, 0);
        col.addView(key);
        col.addView(value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (spaced) {
            lp.leftMargin = Ui.dp(activity, 8);
        }
        parent.addView(col, lp);
        return value;
    }

    private static void binary(Ui.Stat stat, int value, String on, String off,
                               int onColor, int offColor) {
        if (value < 0) {
            stat.set(Ui.DASH, Ui.DIM);
        } else if (value == 1) {
            stat.set(on, onColor);
        } else {
            stat.set(off, offColor);
        }
    }

    private static String num(double value, int decimals) {
        return Double.isNaN(value) ? Ui.DASH : Ui.fmt(value, decimals);
    }

    private static String withUnit(double value, int decimals, String unit) {
        return Double.isNaN(value) ? Ui.DASH : Ui.fmt(value, decimals) + " " + unit;
    }

    private static int tempColor(double celsius) {
        if (Double.isNaN(celsius)) {
            return Ui.DIM;
        }
        if (celsius > 80) {
            return Ui.BAD;
        }
        if (celsius > 60) {
            return Ui.WARN;
        }
        return Ui.TEXT;
    }
}
