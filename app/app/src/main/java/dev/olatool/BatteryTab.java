package dev.olatool;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.olatool.core.Can;
import dev.olatool.core.Rpc;

/**
 * Battery health, decoded from the CAN bus.
 *
 * <p>Reads with {@code Init(Register)} followed by repeated unary
 * {@code ReadCanFrame}. A timeout means "the bus is quiet for a moment", which
 * is normal and harmless; the streams are avoided because a deadline on one of
 * those wedges the daemon.
 */
public final class BatteryTab implements Tab {

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;

    private final TextView verdict;
    private final TextView hint;
    private final TextView cellsLine;
    private final Button liveButton;

    private final Ui.Kv soc;
    private final Ui.Kv displaySoc;
    private final Ui.Kv soh;
    private final Ui.Kv volts;
    private final Ui.Kv amps;
    private final Ui.Kv power;
    private final Ui.Kv temp;
    private final Ui.Kv tempRange;
    private final Ui.Kv cellMin;
    private final Ui.Kv cellMax;
    private final Ui.Kv spread;
    private final Ui.Kv mcu;
    private final Ui.Kv motor;
    private final Ui.Kv rpm;
    private final Ui.Kv odo;
    private final Ui.Kv fall;
    private final Ui.Kv tow;
    private final Ui.Kv regen;
    private final Ui.Kv hill;

    private volatile boolean wanted;
    private volatile Thread reader;

    public BatteryTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        verdict = Ui.text(activity, "Not read yet", 18, Ui.DIM);
        body.addView(verdict);

        hint = Ui.note(activity, "");
        hint.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 10));
        body.addView(hint);

        liveButton = Ui.accent(activity, "Start live", v -> toggle());
        body.addView(liveButton);

        LinearLayout pack = Ui.card(activity);
        pack.addView(Ui.heading(activity, "Pack"));
        soc = add(pack, "State of charge");
        displaySoc = add(pack, "Display SOC");
        soh = add(pack, "State of health");
        volts = add(pack, "Voltage");
        amps = add(pack, "Current");
        power = add(pack, "Power");
        temp = add(pack, "Pack temperature");
        tempRange = add(pack, "Temperature min / max");
        cellMin = add(pack, "Cell minimum");
        cellMax = add(pack, "Cell maximum");
        spread = add(pack, "Cell spread");
        body.addView(pack, margin());

        LinearLayout cells = Ui.card(activity);
        cells.addView(Ui.heading(activity, "Cells (14S)"));
        cellsLine = Ui.text(activity, "-", 12, Ui.TEXT);
        cells.addView(cellsLine);
        body.addView(cells, margin());

        LinearLayout drive = Ui.card(activity);
        drive.addView(Ui.heading(activity, "Motor and body"));
        mcu = add(drive, "MCU temperature");
        motor = add(drive, "Motor temperature");
        rpm = add(drive, "Motor speed");
        odo = add(drive, "Odometer");
        body.addView(drive, margin());

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
        if (conn.ready()) {
            startLive();
        } else {
            hint.setText("Connect to the scooter first.");
        }
    }

    @Override
    public void onHide() {
        stopLive();
    }

    private void toggle() {
        if (wanted) {
            stopLive();
        } else if (conn.ready()) {
            startLive();
        } else {
            hint.setText("Connect to the scooter first.");
        }
    }

    private void startLive() {
        if (!conn.ready()) {
            return;
        }
        wanted = true;
        liveButton.setText("Stop");
        hint.setText("Reading CAN ...");
        ensureReader();
    }

    /**
     * Starts the reader unless one is still winding down. A reader can sit in a
     * 1.2 s ReadCanFrame call, and two readers would steal frames from each
     * other, so this waits instead of spawning a second one.
     */
    private void ensureReader() {
        if (!wanted || !conn.ready()) {
            return;
        }
        if (reader != null) {
            page.postDelayed(this::ensureReader, 300);
            return;
        }
        final Rpc rpc = conn.rpc();
        reader = new Thread(() -> {
            Can.Snapshot snap = new Can.Snapshot();
            try {
                rpc.initCan(Can.BATTERY_IDS);
                long lastPost = 0;
                while (wanted && conn.rpc() == rpc) {
                    byte[] raw = rpc.readCanFrame(1200);
                    if (raw != null) {
                        Can.Frame frame = Can.Frame.parse(raw);
                        if (frame != null) {
                            synchronized (snap) {
                                snap.apply(frame);
                            }
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastPost > 800) {
                        lastPost = now;
                        final Can.Snapshot shown = snap;
                        conn.post(() -> {
                            synchronized (shown) {
                                render(shown);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                conn.post(() -> hint.setText("Read stopped: " + e.getMessage()));
            } finally {
                reader = null;
            }
        }, "can-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void stopLive() {
        wanted = false;
        liveButton.setText("Start live");
    }

    // --------------------------------------------------------------- render

    private void render(Can.Snapshot s) {
        if (s.frames == 0) {
            verdict.setText("No frames yet");
            verdict.setTextColor(Ui.DIM);
            hint.setText("The bus is silent. The vehicle is probably asleep — press the "
                    + "red start button or plug in the charger.");
        } else {
            String health = s.sohVerdict();
            verdict.setText("Pack: " + health);
            verdict.setTextColor(healthColor(health));
            hint.setText("Frames read: " + s.frames);
        }

        soc.set(Ui.pct(s.soc));
        displaySoc.set(Ui.pct(s.displaySoc));
        soh.set(Ui.pct(s.soh) + "  " + s.sohVerdict());
        volts.set(Ui.fmt(s.volts, 2) + " V");
        amps.set(Ui.fmt(s.amps, 2) + " A");
        power.set(Ui.fmt(s.powerW() / 1000.0, 2) + " kW");
        temp.set(Ui.fmt(s.packTemp, 1) + " °C");
        tempRange.set(Ui.fmt(s.tempMin, 1) + " / " + Ui.fmt(s.tempMax, 1) + " °C");
        cellMin.set(Ui.fmt(s.cellMinMv, 0) + " mV");
        cellMax.set(Ui.fmt(s.cellMaxMv, 0) + " mV");
        spread.set(Ui.fmt(s.cellSpreadMv(), 0) + " mV  " + s.cellSpreadVerdict(),
                healthColor(s.cellSpreadVerdict()));

        mcu.set(Ui.fmt(s.mcuTemp, 1) + " °C");
        motor.set(Ui.fmt(s.motorTemp, 1) + " °C");
        rpm.set(Ui.fmt(s.rpm, 0) + " rpm");
        odo.set(Ui.fmt(s.odoMeters / 1000.0, 1) + " km");

        fall.set(s.fallName());
        tow.set(s.towName());
        regen.set(s.regenName());
        hill.set(s.hillHoldName());

        cellsLine.setText(cellList(s));
    }

    private static String cellList(Can.Snapshot s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.cells.length; i++) {
            if (Double.isNaN(s.cells[i]) || s.cells[i] <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("   ");
            }
            sb.append(i + 1).append(": ").append((int) s.cells[i]);
        }
        return sb.length() == 0 ? "-" : sb.toString();
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
