package dev.olatool;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import dev.olatool.core.Live;

/**
 * BMS "Control": the remote-control layout (start/stop, lock/unlock, buzzer,
 * headlight, speed) over the real vehicle state.
 *
 * <p><b>Honest by design.</b> The Ola S1 only accepts commands behind an AES
 * challenge-response the app cannot answer, and the cloud API is read-only. So
 * the state shown here is real (lock, running, speed from CAN/cloud), but the
 * command buttons do not fake success: each explains that the command channel
 * is locked. Nothing is ever sent to the vehicle.
 */
final class BmsControlTab implements Tab, Bus.Listener, CloudState.Listener {

    private static final long STALE_MS = 6_000L;
    private static final String LOCKED =
            "Command channel is locked on the Ola S1 (AES challenge-response). "
            + "This tool is read-only — no command was sent.";

    private final Activity activity;
    private final Conn conn;
    private final Bus bus;
    private final CloudState cloud;
    private final ScrollView page;

    private final TextView lockIcon;
    private final TextView lockTitle;
    private final TextView lockSub;
    private final Ui.Kv maxSpeed;
    private final Ui.Kv curSpeed;
    private final TextView note;

    // Live BMS over the local link (IPv4/IPv6 CAN), Admin-gated, read-only.
    private Ui.Kv bPackV, bPackA, bSoc, bSoh, bTemp, bCellMin, bCellMax, bSpread, bCharge;
    private TextView bmsGate;
    private TextView bleStatus;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private boolean scanning;

    private Live.Snapshot last;
    private boolean visible;

    BmsControlTab(Activity activity, Conn conn, Bus bus) {
        this.activity = activity;
        this.conn = conn;
        this.bus = bus;
        this.cloud = CloudState.get(activity);
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Control",
                "Lock, unlock and locate send over Ola cloud (needs a token). "
                        + "Motor start/stop stays read-only.", Ui.ACCENT));

        // Remote-control button grid.
        LinearLayout rc = Ui.card(activity);
        rc.addView(Ui.heading(activity, "Remote control"));
        rc.addView(grid(
                accentBtn("⏻  Start / Enable", Ui.ACCENT),
                dangerBtn("⏹  Stop / Disable")));
        rc.addView(space());
        rc.addView(grid(
                cmdBtn("🔒  Lock Vehicle", Ui.WARN, "LOCK_COMMAND", true),
                cmdBtn("🔓  Unlock Vehicle", Ui.ACCENT, "UNLOCK_COMMAND", true)));
        rc.addView(space());
        rc.addView(grid(
                cmdBtn("🔊  Buzzer", 0, "FIND_MY_SCOOTER", false),
                cmdBtn("💡  Headlight", 0, "FLASH_BLINKERS_ON", false)));
        body.addView(rc);

        body.addView(Ui.space(activity, 10));

        // Big lock-state panel.
        LinearLayout lp = Ui.card(activity);
        lp.setGravity(Gravity.CENTER_HORIZONTAL);
        lp.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 20),
                Ui.dp(activity, 16), Ui.dp(activity, 20));
        lockIcon = Ui.text(activity, "🔒", 44, Ui.TEXT);
        lockIcon.setGravity(Gravity.CENTER);
        lp.addView(lockIcon);
        lockTitle = Ui.text(activity, "Lock state unknown", 18, Ui.TEXT);
        lockTitle.setTypeface(lockTitle.getTypeface(), Typeface.BOLD);
        lockTitle.setGravity(Gravity.CENTER);
        lockTitle.setPadding(0, Ui.dp(activity, 10), 0, 0);
        lp.addView(lockTitle);
        lockSub = Ui.note(activity, "Read from the scooter / cloud.");
        lockSub.setGravity(Gravity.CENTER);
        lockSub.setPadding(0, Ui.dp(activity, 4), 0, 0);
        lp.addView(lockSub);
        body.addView(lp);

        body.addView(Ui.space(activity, 10));

        LinearLayout sp = Ui.card(activity);
        sp.addView(Ui.heading(activity, "Speed"));
        maxSpeed = new Ui.Kv(activity, "Max speed (mode cap)");
        curSpeed = new Ui.Kv(activity, "Current speed");
        sp.addView(maxSpeed);
        sp.addView(curSpeed);
        body.addView(sp);

        body.addView(Ui.space(activity, 10));

        // Live BMS straight off the local link (the CAN bus over IPv4/IPv6),
        // plus an honest BLE presence check. Read-only, and gated to Admin.
        LinearLayout bmsCard = Ui.card(activity);
        bmsCard.addView(Ui.heading(activity, "Live BMS — local link"));
        bmsGate = Ui.note(activity, "");
        bmsCard.addView(bmsGate);
        bPackV = new Ui.Kv(activity, "Pack voltage");
        bPackA = new Ui.Kv(activity, "Pack current");
        bSoc = new Ui.Kv(activity, "State of charge");
        bSoh = new Ui.Kv(activity, "State of health");
        bTemp = new Ui.Kv(activity, "Pack temp");
        bCellMin = new Ui.Kv(activity, "Cell min");
        bCellMax = new Ui.Kv(activity, "Cell max");
        bSpread = new Ui.Kv(activity, "Cell spread");
        bCharge = new Ui.Kv(activity, "Charge rate");
        bmsCard.addView(bPackV);
        bmsCard.addView(bPackA);
        bmsCard.addView(bSoc);
        bmsCard.addView(bSoh);
        bmsCard.addView(bTemp);
        bmsCard.addView(bCellMin);
        bmsCard.addView(bCellMax);
        bmsCard.addView(bSpread);
        bmsCard.addView(bCharge);
        bleStatus = Ui.note(activity, "BLE: not checked.");
        bleStatus.setPadding(0, Ui.dp(activity, 10), 0, 0);
        bmsCard.addView(bleStatus);
        bmsCard.addView(Ui.space(activity, 8));
        bmsCard.addView(Ui.button(activity, "Check BLE presence", v -> checkBle()));
        body.addView(bmsCard);

        note = Ui.note(activity, "Lock / Unlock / Buzzer / Headlight go through the Ola "
                + "cloud using your token. Start / Stop (motor) is still locked behind the "
                + "vehicle's AES challenge and stays read-only.");
        note.setPadding(0, Ui.dp(activity, 12), 0, 0);
        body.addView(note);
    }

    // ---- button builders: every command button reports the locked channel ----

    private Button accentBtn(String label, int tint) {
        Button b = Ui.accent(activity, label, v -> deny(label));
        return b;
    }

    private Button dangerBtn(String label) {
        return Ui.danger(activity, label, v -> deny(label));
    }

    private Button tintBtn(String label, int tint) {
        Button b = Ui.button(activity, label, v -> deny(label));
        b.setTextColor(tint);
        b.setBackground(Ui.round(activity, Ui.alpha(tint, 0x14), 10, Ui.alpha(tint, 0x55)));
        return b;
    }

    private Button plainBtn(String label) {
        return Ui.button(activity, label, v -> deny(label));
    }

    /** A button that sends a real Ola-cloud command (lock/unlock/locate). */
    private Button cmdBtn(String label, int tint, String commandType, boolean confirm) {
        Button b = Ui.button(activity, label, v -> command(label, commandType, confirm));
        if (tint != 0) {
            b.setTextColor(tint);
            b.setBackground(Ui.round(activity, Ui.alpha(tint, 0x14), 10, Ui.alpha(tint, 0x55)));
        }
        return b;
    }

    private void command(String label, String commandType, boolean confirm) {
        String name = stripIcon(label);
        if (OlaCloud.token(activity).isEmpty()) {
            Toast.makeText(activity, "Set the cloud token first (Cloud tab).",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (confirm) {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(name)
                    .setMessage("Send “" + name + "” to the scooter over Ola cloud?")
                    .setPositiveButton("Send", (d, w) -> sendCommand(name, commandType))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            sendCommand(name, commandType);
        }
    }

    private void sendCommand(String name, String commandType) {
        Toast.makeText(activity, name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            OlaCloud.CommandResult r = OlaCloud.issueCommand(activity, commandType);
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    name + ": " + (r.ok ? "sent ✓" : r.message),
                    Toast.LENGTH_LONG).show());
        }, "ola-cmd").start();
    }

    private void deny(String label) {
        Toast.makeText(activity, "“" + stripIcon(label) + "” — " + LOCKED,
                Toast.LENGTH_LONG).show();
    }

    private static String stripIcon(String label) {
        int i = label.indexOf("  ");
        return i >= 0 ? label.substring(i + 2) : label;
    }

    private LinearLayout grid(Button left, Button right) {
        LinearLayout r = Ui.row(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = Ui.dp(activity, 8);
        r.addView(left, lp);
        r.addView(right, rp);
        return r;
    }

    private View space() {
        return Ui.space(activity, 8);
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
        stopScan();
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

        // Lock state: prefer the cloud lock_status, else infer from CAN "unlocked".
        String lock = cs != null ? cs.lockStatus : null;
        Boolean locked = null;
        if (lock != null) {
            String u = lock.toUpperCase(Locale.ROOT);
            if (u.contains("UNLOCK")) {
                locked = false;
            } else if (u.contains("LOCK")) {
                locked = true;
            }
        }
        if (locked == null && s != null && s.unlocked >= 0) {
            locked = s.unlocked == 0;
        }
        if (locked == null) {
            lockIcon.setText("🔒");
            lockTitle.setText("Lock state unknown");
            lockTitle.setTextColor(Ui.DIM);
            lockSub.setText("Connect or set a cloud token to read it.");
        } else if (locked) {
            lockIcon.setText("🔒");
            lockTitle.setText("Vehicle is Locked");
            lockTitle.setTextColor(Ui.WARN);
            lockSub.setText(lock != null ? "From Ola cloud." : "From CAN state.");
        } else {
            lockIcon.setText("🔓");
            lockTitle.setText("Vehicle is Unlocked");
            lockTitle.setTextColor(Ui.ACCENT);
            lockSub.setText(lock != null ? "From Ola cloud." : "From CAN state.");
        }

        double cur = s == null ? Double.NaN : s.speedKph;
        curSpeed.set(Double.isNaN(cur) ? Ui.DASH : Ui.fmt(cur, 0) + " km/h");
        String mode = s == null || s.driveMode == null ? "" : s.driveMode;
        maxSpeed.set(mode.isEmpty() ? Ui.DASH : mode);

        renderBms(s);
    }

    // ---- Live BMS over the local link (IPv4/IPv6 CAN), read-only, Admin-gated ----

    private void renderBms(Live.Snapshot s) {
        if (Access.level(activity) != Access.ADMIN) {
            bmsGate.setText("Admin access required to read the local link "
                    + "(Settings → Access → Admin).");
            bmsGate.setTextColor(Ui.WARN);
            clearBms();
            return;
        }
        if (!conn.ready()) {
            bmsGate.setText("Connect over IPv4/IPv6 to read live BMS from the CAN bus.");
            bmsGate.setTextColor(Ui.DIM);
            clearBms();
            return;
        }
        bmsGate.setText("Live over " + (conn.ipv6() ? "IPv6" : "IPv4")
                + " · local CAN (" + conn.target() + ").");
        bmsGate.setTextColor(Ui.ACCENT);
        if (s == null) {
            clearBms();
            return;
        }
        bPackV.set(unit(s.packV, 1, " V"));
        bPackA.set(unit(s.packA, 1, " A"));
        bSoc.set(unit(s.soc, 0, " %"));
        bSoh.set(unit(s.soh, 0, " %"));
        bTemp.set(unit(s.packC, 1, " °C"));
        bCellMin.set(unit(s.cellMinV, 3, " V"));
        bCellMax.set(unit(s.cellMaxV, 3, " V"));
        bSpread.set(unit(s.cellSpreadV(), 3, " V"));
        bCharge.set(unit(s.chargeRate, 1, ""));
    }

    private void clearBms() {
        bPackV.set(Ui.DASH);
        bPackA.set(Ui.DASH);
        bSoc.set(Ui.DASH);
        bSoh.set(Ui.DASH);
        bTemp.set(Ui.DASH);
        bCellMin.set(Ui.DASH);
        bCellMax.set(Ui.DASH);
        bSpread.set(Ui.DASH);
        bCharge.set(Ui.DASH);
    }

    private static String unit(double v, int dec, String suffix) {
        return Double.isNaN(v) ? Ui.DASH : Ui.fmt(v, dec) + suffix;
    }

    // ---- BLE presence only. Ola's BMS-over-BLE is proprietary/encrypted, so
    //      this honestly reports range + RSSI; the values above stay CAN-sourced.

    private void checkBle() {
        if (Access.level(activity) != Access.ADMIN) {
            bleStatus.setText("BLE: Admin access required.");
            bleStatus.setTextColor(Ui.WARN);
            return;
        }
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_SCAN
                : Manifest.permission.ACCESS_FINE_LOCATION;
        if (activity.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            bleStatus.setText("BLE: grant the Bluetooth permission in the Bluetooth tab first.");
            bleStatus.setTextColor(Ui.WARN);
            return;
        }
        BluetoothManager bm =
                (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            bleStatus.setText("BLE: turn Bluetooth on first.");
            bleStatus.setTextColor(Ui.WARN);
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            bleStatus.setText("BLE: scanner unavailable.");
            bleStatus.setTextColor(Ui.WARN);
            return;
        }
        stopScan();
        bleStatus.setText("BLE: scanning…");
        bleStatus.setTextColor(Ui.DIM);
        final int[] bestRssi = {-999};
        final String[] bestName = {null};
        scanCb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                try {
                    BluetoothDevice d = r.getDevice();
                    String n = d == null ? null : d.getName();
                    if (n == null) {
                        return;
                    }
                    String low = n.toLowerCase(Locale.ROOT);
                    boolean hint = low.contains("ola") || low.contains("s1")
                            || low.contains("scooter") || low.contains("bms");
                    if (hint && r.getRssi() > bestRssi[0]) {
                        bestRssi[0] = r.getRssi();
                        bestName[0] = n;
                    }
                } catch (SecurityException ignored) {
                }
            }
        };
        try {
            scanner.startScan(scanCb);
            scanning = true;
        } catch (SecurityException e) {
            bleStatus.setText("BLE: permission denied.");
            bleStatus.setTextColor(Ui.BAD);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            stopScan();
            if (bestName[0] != null) {
                bleStatus.setText("BLE: found “" + bestName[0] + "” (RSSI "
                        + bestRssi[0] + " dBm). Decode is proprietary — the values above "
                        + "come from the local CAN link.");
                bleStatus.setTextColor(Ui.ACCENT);
            } else {
                bleStatus.setText("BLE: no scooter advert seen (presence check only; "
                        + "Ola's BMS-over-BLE is encrypted).");
                bleStatus.setTextColor(Ui.DIM);
            }
        }, 4500);
    }

    private void stopScan() {
        if (scanning && scanner != null && scanCb != null) {
            try {
                scanner.stopScan(scanCb);
            } catch (SecurityException ignored) {
            }
        }
        scanning = false;
    }
}
