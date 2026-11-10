package dev.olatool;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Bluetooth pairing, scanning and GATT inspection for the owner's own scooter.
 *
 * <p>Merges the S1 Pro Companion app: an optional scan filter on the S1 Pro
 * advertised service UUID, service UUIDs and manufacturer data per device, a
 * GATT client with connection states and auto-reconnect, per-characteristic
 * Read and Subscribe (notify/indicate), and three log files
 * (BLE_SCAN_LOG.md, BLE_GATT_LOG.md, ProtocolObservation.txt) in the app's
 * external files dir, with an export of the protocol log.
 *
 * <p>Read-only by design: it never writes a characteristic value or sends a
 * command to the vehicle. The only write is the standard CCCD descriptor
 * (0x2902) that turns notifications on or off.
 */
public final class BtTab implements Tab {

    private static final int REQ_BT = 71;
    static final int REQ_EXPORT = 72;
    private static final long SCAN_MS = 12_000;
    private static final long RESORT_MS = 800;
    private static final long RECONNECT_MS = 2_000;
    private static final int MAX_RECONNECTS = 5;

    /** Service UUID the S1 Pro advertises (scan filter). */
    static final UUID S1_ADV_UUID = UUID.fromString("6e401812-b5a3-f393-e0a9-84978b5f7c21");
    /** Client Characteristic Configuration Descriptor. */
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String SCAN_LOG = "BLE_SCAN_LOG.md";
    private static final String GATT_LOG = "BLE_GATT_LOG.md";
    private static final String PROTO_LOG = "ProtocolObservation.txt";

    /** Name fragments that mark a device as probably the scooter. */
    private static final String[] SCOOTER_HINTS = {"ola", "s1", "scooter", "ev", "bms"};

    private enum State { DISCONNECTED, CONNECTING, CONNECTED, DISCOVERING_SERVICES, READY }

    private final Context ctx;
    private final ScrollView root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Row> rows = new LinkedHashMap<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private BluetoothGatt gatt;
    private BluetoothDevice gattDevice;
    private State state = State.DISCONNECTED;
    private boolean userDisconnect;
    private int reconnects;

    /** Serialised GATT operations: Android allows one outstanding op at a time. */
    private final ArrayDeque<Runnable> ops = new ArrayDeque<>();
    private boolean opBusy;

    private final Map<BluetoothGattCharacteristic, TextView> valueLines = new IdentityHashMap<>();
    private final Map<BluetoothGattCharacteristic, TextView> streamLines = new IdentityHashMap<>();
    private final Map<BluetoothGattCharacteristic, Button> subButtons = new IdentityHashMap<>();
    private final Map<BluetoothGattCharacteristic, Boolean> subscribed = new IdentityHashMap<>();

    private TextView statusLine;
    private TextView btLine;
    private Button scanButton;
    private Button filterButton;
    private LinearLayout list;
    private TextView gattLine;
    private TextView statePill;
    private LinearLayout gattBox;
    private TextView logLine;

    private boolean s1Only;
    private boolean scanning;
    private boolean visible;
    private boolean resortPending;
    private boolean autoScanned;

    /** A device entry: its UI card plus the live metadata. */
    private static final class Row {
        BluetoothDevice device;
        int rssi = 0;
        boolean likely;
        boolean s1Match;
        String services = "";
        String mfr = "";
        LinearLayout card;
        TextView title;
        TextView sub;
        TextView detail;
        TextView bondPill;
    }

    public BtTab(Context ctx) {
        this.ctx = ctx;
        this.root = Ui.page(ctx);
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        this.s1Only = ctx.getSharedPreferences("bt", Context.MODE_PRIVATE).getBoolean("s1Only", false);
        build();
        registerStateReceiver();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        visible = true;
        refreshBtLine();
        showBonded();
        // Like the Companion scanner screen: scan starts by itself on first open.
        if (!autoScanned && !scanning && adapter != null && adapter.isEnabled() && hasPermissions()) {
            autoScanned = true;
            startScan();
        }
    }

    @Override
    public void onHide() {
        visible = false;
        stopScan();
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        LinearLayout body = Ui.body(root);

        body.addView(Ui.pageTitle(ctx, "Bluetooth",
                "Scan, pair, and inspect nearby Bluetooth devices. S1 Pro filter, live "
                        + "notifications and protocol logs. Read-only: no commands are sent."));

        Ui.Section head = Ui.section(ctx, "Controls", null);
        btLine = Ui.note(ctx, "");
        btLine.setPadding(0, 0, 0, Ui.dp(ctx, 8));
        head.body.addView(btLine);

        LinearLayout buttons = Ui.row(ctx);
        scanButton = Ui.accent(ctx, "Scan", v -> onScanClicked());
        buttons.addView(scanButton);
        filterButton = Ui.button(ctx, "", v -> toggleFilter());
        buttons.addView(filterButton, gap());
        buttons.addView(Ui.button(ctx, "Paired", v -> showBonded()), gap());
        head.body.addView(buttons);

        LinearLayout buttons2 = Ui.row(ctx);
        buttons2.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        buttons2.addView(Ui.button(ctx, "Enable BT", v -> enableBt()));
        buttons2.addView(Ui.button(ctx, "BT settings", v -> openBtSettings()), gap());
        buttons2.addView(Ui.button(ctx, "App perms", v -> openAppSettings()), gap());
        head.body.addView(buttons2);
        bindFilterButton();

        statusLine = Ui.note(ctx, "");
        statusLine.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        head.body.addView(statusLine);
        body.addView(head.card);

        body.addView(Ui.space(ctx, 10));

        Ui.Section devices = Ui.section(ctx, "Devices",
                "Sorted: S1 Pro match, then likely scooter, then strongest signal.");
        list = devices.body;
        body.addView(devices.card);

        body.addView(Ui.space(ctx, 10));

        Ui.Section g = Ui.section(ctx, "GATT diagnostics",
                "Services, properties, Read and Subscribe after a Connect.", Ui.INFO);
        LinearLayout stateRow = Ui.row(ctx);
        gattLine = Ui.note(ctx, "Connect to a device to list its services.");
        stateRow.addView(gattLine, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statePill = Ui.pill(ctx, State.DISCONNECTED.name(), Ui.DIM);
        stateRow.addView(statePill);
        g.body.addView(stateRow);

        LinearLayout gb = Ui.row(ctx);
        gb.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        gb.addView(Ui.danger(ctx, "Disconnect", v -> userDisconnectGatt()));
        gb.addView(Ui.button(ctx, "Export log", v -> exportLog()), gap());
        gb.addView(Ui.button(ctx, "Clear logs", v -> clearLogs()), gap());
        g.body.addView(gb);
        logLine = Ui.note(ctx, "");
        logLine.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        g.body.addView(logLine);
        refreshLogLine();

        gattBox = Ui.column(ctx);
        gattBox.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        g.body.addView(gattBox);
        body.addView(g.card);
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = Ui.dp(ctx, 8);
        return sp;
    }

    private void bindFilterButton() {
        filterButton.setText(s1Only ? "S1 Pro only: ON" : "S1 Pro only: OFF");
        filterButton.setTextColor(s1Only ? Ui.ACCENT : Ui.TEXT);
    }

    private void toggleFilter() {
        s1Only = !s1Only;
        ctx.getSharedPreferences("bt", Context.MODE_PRIVATE).edit().putBoolean("s1Only", s1Only).apply();
        bindFilterButton();
        if (scanning) {
            stopScan();
            startScan();
        }
    }

    // ------------------------------------------------------- adapter / perms

    private void registerStateReceiver() {
        try {
            IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    refreshBtLine();
                    int s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (s == BluetoothAdapter.STATE_OFF) {
                        stopScan();
                    }
                }
            };
            ctx.getApplicationContext().registerReceiver(r, f);
        } catch (Exception ignored) {
        }
    }

    private void refreshBtLine() {
        if (btLine == null) {
            return;
        }
        String bt;
        int color;
        if (adapter == null) {
            bt = "Bluetooth unavailable on this device";
            color = Ui.BAD;
        } else if (!hasPermissions()) {
            bt = "Bluetooth permissions required";
            color = Ui.WARN;
        } else if (!adapter.isEnabled()) {
            bt = "Bluetooth is off";
            color = Ui.WARN;
        } else {
            bt = "Bluetooth ready";
            color = Ui.ACCENT;
        }
        String sc = (gattDevice != null && state != State.DISCONNECTED)
                ? "Scooter: " + nameOr(gattDevice) + " (" + state.name() + ")"
                : "Scooter: not connected";
        btLine.setText(bt + "  ·  " + sc);
        btLine.setTextColor(color);
    }

    private void enableBt() {
        if (adapter == null) {
            setStatus("This device has no Bluetooth adapter.", Ui.BAD);
            return;
        }
        if (adapter.isEnabled()) {
            setStatus("Bluetooth is already on.", Ui.ACCENT);
            return;
        }
        if (!ensurePermissions()) {
            setStatus("Allow Bluetooth permission, then Enable again.", Ui.DIM);
            return;
        }
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            openBtSettings();
        }
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", ctx.getPackageName(), null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            setStatus("Could not open app settings.", Ui.WARN);
        }
    }

    /** Called by MainActivity after the permission dialog closes. */
    void onPermissionsResult(int requestCode) {
        if (requestCode != REQ_BT) {
            return;
        }
        refreshBtLine();
        if (hasPermissions()) {
            setStatus("Bluetooth permission granted. Tap Scan.", Ui.ACCENT);
        } else {
            setStatus("Permission denied. Use App perms to allow Nearby devices.", Ui.WARN);
        }
    }

    private void onScanClicked() {
        if (adapter == null) {
            setStatus("This device has no Bluetooth adapter.", Ui.BAD);
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Bluetooth is off. Tap Enable BT and scan again.", Ui.WARN);
            return;
        }
        if (!ensurePermissions()) {
            setStatus("Granting Bluetooth permission … tap Scan again after allowing.", Ui.DIM);
            return;
        }
        if (scanning) {
            stopScan();
            return;
        }
        startScan();
    }

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String p : neededPermissions()) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean ensurePermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : neededPermissions()) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return true;
        }
        if (ctx instanceof Activity) {
            ((Activity) ctx).requestPermissions(missing.toArray(new String[0]), REQ_BT);
        }
        return false;
    }

    // ------------------------------------------------------------------ scan

    private void startScan() {
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                setStatus("BLE scanner unavailable.", Ui.BAD);
                return;
            }
            rows.clear();
            list.removeAllViews();
            if (!s1Only) {
                showBonded();
            }
            scanCb = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    onDevice(result);
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    for (ScanResult r : results) {
                        onDevice(r);
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    log(SCAN_LOG, "Scan failed, code " + errorCode);
                    ui.post(() -> setStatus("Scan failed (code " + errorCode + ").", Ui.BAD));
                }
            };
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            List<ScanFilter> filters = null;
            if (s1Only) {
                filters = new ArrayList<>();
                filters.add(new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(S1_ADV_UUID)).build());
            }
            scanner.startScan(filters, settings, scanCb);
            scanning = true;
            scanButton.setText("Stop");
            log(SCAN_LOG, "Scan started (" + (s1Only ? "filter " + S1_ADV_UUID : "no filter") + ")");
            setStatus(s1Only ? "Scanning for S1 Pro …" : "Scanning for nearby devices …", Ui.ACCENT);
            ui.postDelayed(this::stopScan, SCAN_MS);
        } catch (SecurityException e) {
            setStatus("Bluetooth permission denied.", Ui.BAD);
        } catch (Exception e) {
            setStatus("Could not start scan: " + e.getMessage(), Ui.BAD);
        }
    }

    private void stopScan() {
        if (!scanning) {
            return;
        }
        scanning = false;
        try {
            if (scanner != null && scanCb != null) {
                scanner.stopScan(scanCb);
            }
        } catch (Exception ignored) {
        }
        if (scanButton != null) {
            scanButton.setText("Scan");
        }
        int likely = 0;
        int s1 = 0;
        for (Row r : rows.values()) {
            if (r.likely) {
                likely++;
            }
            if (r.s1Match) {
                s1++;
            }
        }
        log(SCAN_LOG, "Scan stopped. " + rows.size() + " device(s), " + s1 + " S1 Pro match(es)");
        if (visible) {
            setStatus(rows.isEmpty() ? "No devices found. Move closer and scan again."
                    : "Scan done. " + rows.size() + " device(s), found " + s1 + " S1 Pro match(es)"
                    + (likely > 0 ? ", " + likely + " likely scooter." : "."), Ui.DIM);
        }
    }

    private void onDevice(ScanResult result) {
        final BluetoothDevice device = result.getDevice();
        if (device == null) {
            return;
        }
        final int rssi = result.getRssi();
        final ScanRecord rec = result.getScanRecord();
        String services = "";
        String mfr = "";
        boolean s1 = false;
        if (rec != null) {
            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null && !uuids.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ParcelUuid u : uuids) {
                    if (S1_ADV_UUID.equals(u.getUuid())) {
                        s1 = true;
                    }
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(shortUuid(u.toString()));
                }
                services = sb.toString();
            }
            SparseArray<byte[]> m = rec.getManufacturerSpecificData();
            if (m != null && m.size() > 0) {
                mfr = String.format(Locale.ROOT, "0x%04X: %s", m.keyAt(0), hex(m.valueAt(0)));
            }
        }
        final String fServices = services;
        final String fMfr = mfr;
        final boolean fS1 = s1;
        final String addr = device.getAddress();
        ui.post(() -> {
            Row r = rows.get(addr);
            if (r == null) {
                r = new Row();
                r.device = device;
                rows.put(addr, r);
                addCard(r);
                log(SCAN_LOG, "Found " + nameOr(device) + " " + addr + " RSSI " + rssi
                        + (fServices.isEmpty() ? "" : " services [" + fServices + "]")
                        + (fMfr.isEmpty() ? "" : " mfr " + fMfr));
            }
            r.rssi = rssi;
            r.s1Match = r.s1Match || fS1;
            if (!fServices.isEmpty()) {
                r.services = fServices;
            }
            if (!fMfr.isEmpty()) {
                r.mfr = fMfr;
            }
            r.likely = r.s1Match || isLikelyScooter(safeName(device));
            bindRow(r);
            scheduleResort();
        });
    }

    private static boolean isLikelyScooter(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (String hint : SCOOTER_HINTS) {
            if (n.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** Re-orders the device cards (S1 match, likely, then strongest RSSI), throttled. */
    private void scheduleResort() {
        if (resortPending) {
            return;
        }
        resortPending = true;
        ui.postDelayed(() -> {
            resortPending = false;
            resort();
        }, RESORT_MS);
    }

    private void resort() {
        List<Row> ordered = new ArrayList<>(rows.values());
        Collections.sort(ordered, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                if (a.s1Match != b.s1Match) {
                    return a.s1Match ? -1 : 1;
                }
                if (a.likely != b.likely) {
                    return a.likely ? -1 : 1;
                }
                int ra = a.rssi == 0 ? -999 : a.rssi;
                int rb = b.rssi == 0 ? -999 : b.rssi;
                return Integer.compare(rb, ra);
            }
        });
        list.removeAllViews();
        for (Row r : ordered) {
            list.addView(r.card);
        }
    }

    private void showBonded() {
        try {
            if (adapter == null || !adapter.isEnabled()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 31
                    && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (!rows.containsKey(d.getAddress())) {
                    Row r = new Row();
                    r.device = d;
                    r.likely = isLikelyScooter(safeName(d));
                    rows.put(d.getAddress(), r);
                    addCard(r);
                    bindRow(r);
                }
            }
            scheduleResort();
        } catch (SecurityException ignored) {
        }
    }

    private void addCard(Row r) {
        LinearLayout card = Ui.card(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 8);
        card.setLayoutParams(lp);

        LinearLayout top = Ui.row(ctx);
        LinearLayout titles = Ui.column(ctx);
        r.title = Ui.text(ctx, "", 15, Ui.TEXT);
        r.title.setTypeface(r.title.getTypeface(), android.graphics.Typeface.BOLD);
        r.sub = Ui.note(ctx, "");
        r.detail = Ui.text(ctx, "", 11, Ui.DIM);
        titles.addView(r.title);
        titles.addView(r.sub);
        titles.addView(r.detail);
        top.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.bondPill = Ui.pill(ctx, "", Ui.DIM);
        top.addView(r.bondPill);
        card.addView(top);

        LinearLayout actions = Ui.row(ctx);
        actions.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        actions.addView(Ui.button(ctx, "Pair", v -> pair(r)));
        actions.addView(Ui.button(ctx, "Connect", v -> connect(r.device)), gap());
        card.addView(actions);

        r.card = card;
        list.addView(card);
    }

    private void bindRow(Row r) {
        String name = safeName(r.device);
        String shown = (name == null || name.isEmpty()) ? "(no name)" : name;
        r.title.setText(r.likely ? shown + "  ★" : shown);
        r.title.setTextColor(r.likely ? Ui.ACCENT : Ui.TEXT);
        String rssi = r.rssi == 0 ? "" : "  ·  " + r.rssi + " dBm" + signalBars(r.rssi);
        String tag = r.s1Match ? "  ·  S1 Pro match" : r.likely ? "  ·  likely scooter" : "";
        r.sub.setText(r.device.getAddress() + rssi + tag);
        StringBuilder d = new StringBuilder();
        if (!r.services.isEmpty()) {
            d.append("Services: ").append(r.services);
        }
        if (!r.mfr.isEmpty()) {
            if (d.length() > 0) {
                d.append('\n');
            }
            d.append("Mfr data: ").append(r.mfr);
        }
        r.detail.setText(d.toString());
        r.detail.setVisibility(d.length() == 0 ? View.GONE : View.VISIBLE);
        int bond = safeBondState(r.device);
        if (bond == BluetoothDevice.BOND_BONDED) {
            Ui.pill(r.bondPill, "Paired", Ui.ACCENT);
        } else if (bond == BluetoothDevice.BOND_BONDING) {
            Ui.pill(r.bondPill, "Pairing…", Ui.WARN);
        } else {
            Ui.pill(r.bondPill, "Not paired", Ui.DIM);
        }
    }

    private void pair(Row r) {
        try {
            if (!ensurePermissions()) {
                setStatus("Allow Bluetooth permission, then Pair again.", Ui.DIM);
                return;
            }
            int bond = safeBondState(r.device);
            if (bond == BluetoothDevice.BOND_BONDED) {
                setStatus(safeName(r.device) + " is already paired.", Ui.ACCENT);
                return;
            }
            boolean started = r.device.createBond();
            setStatus(started ? "Pairing with " + safeName(r.device)
                    + " — confirm on the system dialog."
                    : "Could not start pairing.", started ? Ui.ACCENT : Ui.BAD);
            ui.postDelayed(() -> bindRow(r), 1500);
        } catch (SecurityException e) {
            setStatus("Bluetooth permission denied.", Ui.BAD);
        } catch (Exception e) {
            setStatus("Pair failed: " + e.getMessage(), Ui.BAD);
        }
    }

    // ------------------------------------------------------------------ GATT

    private void connect(BluetoothDevice device) {
        try {
            if (!ensurePermissions()) {
                setStatus("Allow Bluetooth permission, then Connect again.", Ui.DIM);
                return;
            }
            stopScan();
            userDisconnect = true;
            closeGatt();
            userDisconnect = false;
            reconnects = 0;
            gattDevice = device;
            openGatt();
        } catch (SecurityException e) {
            setStatus("Bluetooth permission denied.", Ui.BAD);
        } catch (Exception e) {
            setStatus("Connect failed: " + e.getMessage(), Ui.BAD);
        }
    }

    private void openGatt() {
        gattBox.removeAllViews();
        clearCharViews();
        ops.clear();
        opBusy = false;
        setState(State.CONNECTING, "Connecting to " + nameOr(gattDevice) + " …");
        log(GATT_LOG, "Connecting to " + nameOr(gattDevice) + " " + gattDevice.getAddress());
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = gattDevice.connectGatt(ctx, false, gattCallback(), BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = gattDevice.connectGatt(ctx, false, gattCallback());
            }
        } catch (SecurityException e) {
            setState(State.DISCONNECTED, "Bluetooth permission denied.");
        }
    }

    private void userDisconnectGatt() {
        if (gatt == null) {
            setStatus("Not connected.", Ui.DIM);
            return;
        }
        userDisconnect = true;
        log(GATT_LOG, "User disconnect");
        closeGatt();
        setState(State.DISCONNECTED, "Disconnected.");
    }

    private void setState(State s, String text) {
        state = s;
        if (text != null) {
            gattLine.setText(text);
        }
        int c = s == State.READY ? Ui.ACCENT : s == State.DISCONNECTED ? Ui.DIM : Ui.WARN;
        gattLine.setTextColor(c);
        Ui.pill(statePill, s.name(), c);
        refreshBtLine();
    }

    private BluetoothGattCallback gattCallback() {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (g != gatt) {
                    return;
                }
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    proto("EVENT", "-", "-", "CONNECT", null, status);
                    log(GATT_LOG, "Connected (status " + status + ")");
                    ui.post(() -> {
                        reconnects = 0;
                        setState(State.CONNECTED, "Connected.");
                        setState(State.DISCOVERING_SERVICES, "Connected — discovering services …");
                    });
                    try {
                        g.discoverServices();
                    } catch (SecurityException ignored) {
                    }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    proto("EVENT", "-", "-", "DISCONNECT", null, status);
                    log(GATT_LOG, "Disconnected (status " + status + ")");
                    ui.post(() -> onDisconnected(g));
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                final List<BluetoothGattService> services;
                try {
                    services = g.getServices();
                } catch (SecurityException e) {
                    return;
                }
                dumpServices(services);
                ui.post(() -> {
                    renderServices(services);
                    setState(State.READY, (services == null ? 0 : services.size())
                            + " service(s) ready. Read / Subscribe below.");
                    readAll(g);
                });
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch,
                    int status) {
                onRead(ch, ch.getValue(), status);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch,
                    byte[] value, int status) {
                onRead(ch, value, status);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
                onNotify(ch, ch.getValue());
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch,
                    byte[] value) {
                onNotify(ch, value);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                BluetoothGattCharacteristic ch = d.getCharacteristic();
                proto("TX", ch.getService().getUuid().toString(), ch.getUuid().toString(),
                        "CCCD_WRITE", d.getValue(), status);
                ui.post(() -> {
                    boolean ok = status == BluetoothGatt.GATT_SUCCESS;
                    Boolean want = subscribed.get(ch);
                    if (!ok) {
                        subscribed.put(ch, false);
                        setStatus("Subscribe failed (status " + status + ").", Ui.WARN);
                    }
                    bindSubButton(ch);
                    TextView s = streamLines.get(ch);
                    if (s != null && ok) {
                        s.setText(Boolean.TRUE.equals(want) ? "     Stream: waiting …" : "     Stream: off");
                    }
                    opDone();
                });
            }
        };
    }

    private void onDisconnected(BluetoothGatt g) {
        try {
            g.close();
        } catch (Exception ignored) {
        }
        if (g == gatt) {
            gatt = null;
        }
        opBusy = false;
        ops.clear();
        if (!userDisconnect && gattDevice != null && reconnects < MAX_RECONNECTS) {
            reconnects++;
            setState(State.DISCONNECTED, "Connection lost. Reconnecting (" + reconnects + "/"
                    + MAX_RECONNECTS + ") …");
            log(GATT_LOG, "Auto-reconnect attempt " + reconnects);
            ui.postDelayed(() -> {
                if (!userDisconnect && gatt == null && gattDevice != null) {
                    openGatt();
                }
            }, RECONNECT_MS);
        } else {
            setState(State.DISCONNECTED, userDisconnect ? "Disconnected." : "Disconnected. Reconnect gave up.");
        }
    }

    private void onRead(BluetoothGattCharacteristic ch, byte[] value, int status) {
        proto("RX", ch.getService().getUuid().toString(), ch.getUuid().toString(), "READ", value, status);
        final boolean ok = status == BluetoothGatt.GATT_SUCCESS;
        final String hex = ok ? hex(value) : "read failed (status " + status + ")";
        ui.post(() -> {
            TextView line = valueLines.get(ch);
            if (line != null) {
                line.setText("     = " + hex);
                line.setTextColor(ok ? Ui.ACCENT : Ui.WARN);
            }
            opDone();
        });
    }

    private void onNotify(BluetoothGattCharacteristic ch, byte[] value) {
        proto("RX", ch.getService().getUuid().toString(), ch.getUuid().toString(), "NOTIFY", value, 0);
        final String hex = hex(value);
        ui.post(() -> {
            TextView s = streamLines.get(ch);
            if (s != null) {
                s.setText("     Stream: " + hex);
                s.setTextColor(Ui.WARN);
            }
        });
    }

    private void clearCharViews() {
        valueLines.clear();
        streamLines.clear();
        subButtons.clear();
        subscribed.clear();
    }

    private void renderServices(List<BluetoothGattService> services) {
        gattBox.removeAllViews();
        clearCharViews();
        if (services == null || services.isEmpty()) {
            gattLine.setText("No services reported.");
            gattLine.setTextColor(Ui.WARN);
            return;
        }
        for (BluetoothGattService s : services) {
            LinearLayout card = Ui.card(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(ctx, 6);
            card.setLayoutParams(lp);
            TextView t = Ui.text(ctx, "Service " + shortUuid(s.getUuid().toString()), 12, Ui.TEXT);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            card.addView(t);
            List<BluetoothGattCharacteristic> chars = s.getCharacteristics();
            if (chars != null) {
                for (BluetoothGattCharacteristic ch : chars) {
                    addCharRow(card, ch);
                }
            }
            gattBox.addView(card);
        }
    }

    private void addCharRow(LinearLayout card, BluetoothGattCharacteristic ch) {
        int p = ch.getProperties();
        String ola = olaLabel(ch.getUuid().toString());
        TextView c = Ui.text(ctx, "  " + shortUuid(ch.getUuid().toString()) + "\n  " + propsLong(p), 11, Ui.DIM);
        c.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        card.addView(c);
        if (ola != null) {
            TextView tag = Ui.text(ctx, "  ★ " + ola, 11, Ui.ACCENT);
            tag.setTypeface(tag.getTypeface(), android.graphics.Typeface.BOLD);
            card.addView(tag);
        }
        boolean canRead = (p & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
        boolean canSub = (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
        if (canRead) {
            TextView val = Ui.text(ctx, "     = …", 11, Ui.DIM);
            card.addView(val);
            valueLines.put(ch, val);
        }
        if (canSub) {
            TextView st = Ui.text(ctx, "     Stream: off", 11, Ui.DIM);
            card.addView(st);
            streamLines.put(ch, st);
        }
        if (canRead || canSub) {
            LinearLayout a = Ui.row(ctx);
            a.setPadding(0, Ui.dp(ctx, 4), 0, 0);
            if (canRead) {
                a.addView(Ui.button(ctx, "Read", v -> queueRead(ch)));
            }
            if (canSub) {
                Button b = Ui.button(ctx, "Subscribe", v -> toggleSubscribe(ch));
                subButtons.put(ch, b);
                a.addView(b, canRead ? gap() : new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            card.addView(a);
        }
    }

    private void bindSubButton(BluetoothGattCharacteristic ch) {
        Button b = subButtons.get(ch);
        if (b != null) {
            b.setText(Boolean.TRUE.equals(subscribed.get(ch)) ? "Unsubscribe" : "Subscribe");
        }
    }

    // ------------------------------------------------------ GATT op queue

    private void enqueue(Runnable op) {
        ops.add(op);
        if (!opBusy) {
            runNext();
        }
    }

    private void opDone() {
        opBusy = false;
        runNext();
    }

    private void runNext() {
        Runnable next = ops.poll();
        if (next == null) {
            return;
        }
        opBusy = true;
        next.run();
    }

    /** Auto-reads every readable characteristic once after discovery. */
    private void readAll(BluetoothGatt g) {
        for (BluetoothGattCharacteristic ch : new ArrayList<>(valueLines.keySet())) {
            queueRead(ch);
        }
    }

    private void queueRead(BluetoothGattCharacteristic ch) {
        enqueue(() -> {
            BluetoothGatt g = gatt;
            TextView line = valueLines.get(ch);
            try {
                if (g == null || !g.readCharacteristic(ch)) {
                    if (line != null) {
                        line.setText("     = read refused");
                        line.setTextColor(Ui.WARN);
                    }
                    opDone();
                }
            } catch (SecurityException e) {
                // Android blocks app access to some services (HID) outright.
                proto("RX", ch.getService().getUuid().toString(), ch.getUuid().toString(),
                        "READ", null, -1);
                if (line != null) {
                    line.setText("     = BLOCKED BY OS");
                    line.setTextColor(Ui.BAD);
                }
                opDone();
            }
        });
    }

    private void toggleSubscribe(BluetoothGattCharacteristic ch) {
        final boolean enable = !Boolean.TRUE.equals(subscribed.get(ch));
        subscribed.put(ch, enable);
        enqueue(() -> {
            BluetoothGatt g = gatt;
            try {
                if (g == null || !g.setCharacteristicNotification(ch, enable)) {
                    failSub(ch, "setCharacteristicNotification refused");
                    return;
                }
                BluetoothGattDescriptor d = ch.getDescriptor(CCCD);
                if (d == null) {
                    // No CCCD: local enable is all there is; mark done.
                    bindSubButton(ch);
                    opDone();
                    return;
                }
                boolean indicate = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
                byte[] v = !enable ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        : indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                boolean started;
                if (Build.VERSION.SDK_INT >= 33) {
                    started = g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS;
                } else {
                    d.setValue(v);
                    started = g.writeDescriptor(d);
                }
                if (!started) {
                    failSub(ch, "CCCD write refused");
                }
            } catch (SecurityException e) {
                failSub(ch, "BLOCKED BY OS");
            }
        });
    }

    private void failSub(BluetoothGattCharacteristic ch, String why) {
        subscribed.put(ch, false);
        bindSubButton(ch);
        TextView s = streamLines.get(ch);
        if (s != null) {
            s.setText("     Stream: " + why);
            s.setTextColor(Ui.BAD);
        }
        opDone();
    }

    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        ops.clear();
        opBusy = false;
    }

    // ------------------------------------------------------------------ logs

    private File logFile(String name) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        return new File(dir, name);
    }

    private synchronized void append(String file, String line) {
        try (FileWriter w = new FileWriter(logFile(file), true)) {
            w.write(line);
            w.write('\n');
        } catch (Exception ignored) {
        }
    }

    private void log(String file, String msg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        append(file, "- `" + ts + "` " + msg);
        ui.post(this::refreshLogLine);
    }

    /** One line per event: timestamp ms | direction | Srv | Char | Op | Len | Status | Payload hex. */
    private void proto(String dir, String srv, String chr, String op, byte[] payload, int status) {
        int len = payload == null ? 0 : payload.length;
        append(PROTO_LOG, System.currentTimeMillis() + " | " + dir + " | " + srv + " | " + chr
                + " | " + op + " | " + len + " | " + status + " | "
                + (payload == null ? "" : hex(payload)));
    }

    private void dumpServices(List<BluetoothGattService> services) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## GATT dump ").append(gattDevice == null ? "" : gattDevice.getAddress())
                .append(" @ ").append(new Date()).append('\n');
        if (services != null) {
            for (BluetoothGattService s : services) {
                sb.append("### Service ").append(s.getUuid()).append('\n');
                for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
                    sb.append("- ").append(ch.getUuid()).append("  [")
                            .append(propsLong(ch.getProperties())).append("]\n");
                    for (BluetoothGattDescriptor d : ch.getDescriptors()) {
                        sb.append("  - descriptor ").append(d.getUuid()).append('\n');
                    }
                }
            }
        }
        append(GATT_LOG, sb.toString());
    }

    private void refreshLogLine() {
        if (logLine == null) {
            return;
        }
        File p = logFile(PROTO_LOG);
        logLine.setText("Logs: " + p.getParent() + "\n" + SCAN_LOG + ", " + GATT_LOG + ", "
                + PROTO_LOG + " (" + (p.exists() ? p.length() : 0) + " B)");
    }

    private void clearLogs() {
        logFile(SCAN_LOG).delete();
        logFile(GATT_LOG).delete();
        logFile(PROTO_LOG).delete();
        refreshLogLine();
        setStatus("Logs cleared.", Ui.DIM);
    }

    private void exportLog() {
        if (!logFile(PROTO_LOG).exists()) {
            setStatus("No protocol log yet. Connect and Read/Subscribe first.", Ui.WARN);
            return;
        }
        if (!(ctx instanceof Activity)) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, PROTO_LOG);
        try {
            ((Activity) ctx).startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            setStatus("No document picker available.", Ui.BAD);
        }
    }

    /** Called by MainActivity with the document picker result. */
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            setStatus("Export cancelled.", Ui.DIM);
            return;
        }
        try (InputStream in = new FileInputStream(logFile(PROTO_LOG));
             OutputStream out = ctx.getContentResolver().openOutputStream(data.getData())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            setStatus("Protocol log exported.", Ui.ACCENT);
        } catch (Exception e) {
            setStatus("Export failed: " + e.getMessage(), Ui.BAD);
        }
    }

    private void openBtSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            setStatus("Could not open Bluetooth settings.", Ui.WARN);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String nameOr(BluetoothDevice d) {
        String n = safeName(d);
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    private String safeName(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    private int safeBondState(BluetoothDevice d) {
        try {
            return d.getBondState();
        } catch (SecurityException e) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    private static String propsLong(int p) {
        StringBuilder b = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) b.append("READ ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) b.append("WRITE ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) b.append("WRITE_NR ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) b.append("NOTIFY ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) b.append("INDICATE ");
        return b.length() == 0 ? "-" : b.toString().trim();
    }

    /**
     * Names the three Ola characteristic UUIDs that are hardcoded in the
     * companion app (from RESEARCH.md §10.9). The primary service / TX / RX
     * UUIDs are NOT here: those come from Ola's remote config at connect time,
     * so they are never literals in the APK. The location characteristic is the
     * interesting one to probe — its payload may or may not be AES-wrapped.
     */
    private static String olaLabel(String uuid) {
        if (uuid == null) {
            return null;
        }
        switch (uuid.toLowerCase(Locale.ROOT)) {
            case "45616c3f-b5a3-f393-e0a9-e50e24dcca9e":
                return "Ola TX-Location (probe this)";
            case "45616c4f-b5a3-f393-e0a9-e50e24dcca9e":
                return "Ola read (aux)";
            case "46616c3f-b5a3-f393-e0a9-e50e24dcca9e":
                return "Ola char-w";
            default:
                return null;
        }
    }

    /** Short 16-bit UUID when it is a standard assigned one, else the full UUID. */
    private static String shortUuid(String uuid) {
        if (uuid.length() == 36 && uuid.startsWith("0000")
                && uuid.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return "0x" + uuid.substring(4, 8).toUpperCase(Locale.ROOT);
        }
        return uuid;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02X ", b));
        }
        return sb.toString().trim();
    }

    private static String signalBars(int rssi) {
        int n = rssi >= -60 ? 4 : rssi >= -70 ? 3 : rssi >= -80 ? 2 : rssi >= -90 ? 1 : 0;
        StringBuilder b = new StringBuilder("  ");
        for (int i = 0; i < 4; i++) {
            b.append(i < n ? '█' : '▁');
        }
        return b.toString();
    }

    private void setStatus(String text, int color) {
        if (statusLine == null) {
            return;
        }
        Ui.set(statusLine, text);
        statusLine.setTextColor(color);
    }
}
