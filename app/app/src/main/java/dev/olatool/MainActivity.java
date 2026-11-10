package dev.olatool;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import dev.olatool.core.Addr;

/**
 * The shell: an address field, a Connect button, and the tabs.
 *
 * <p>Everything the user types is an IPv4 address. {@link Net} turns that into
 * an IPv6 connection, which is the only link that survives a switch to customer
 * mode. That discovery is deliberately unchanged.
 */
public final class MainActivity extends Activity {

    private static final int REQ_LOCATION = 41;
    private static final String PREFS = "ola";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_MODE = "conn_mode";
    private static final String MODE_AUTO = "auto";
    private static final String MODE_DIRECT = "direct";
    // Top-level groups. Each hosts several related screens behind a segmented
    // control (see GroupTab), so the main bar is short instead of listing all
    // sixteen screens at once.
    private static final String[] TAB_NAMES = {
            "BMS", "Vehicle", "Diagnostics", "Cloud", "Bluetooth", "Tools", "Settings"
    };

    private final Conn conn = new Conn();
    private final Tab[] tabs = new Tab[TAB_NAMES.length];
    private final Button[] tabButtons = new Button[TAB_NAMES.length];
    private BtTab btTab;

    private Bus bus;
    private EditText address;
    private Button connectButton;
    private TextView status;
    private FrameLayout content;
    private FrameLayout host;
    private View lockOverlay;
    private HorizontalScrollView barScroll;
    private int current = -1;
    private String pendingAddress;
    private String connMode = MODE_AUTO;
    private Button modeAuto;
    private Button modeDirect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bus = new Bus(this, conn);

        btTab = new BtTab(this);

        // Build the child screens once, then group related ones under one bar
        // entry each. Screens themselves are unchanged.
        tabs[0] = new GroupTab(this,
                new String[]{"Dashboard", "Cells", "Control", "Alerts"},
                new Tab[]{new BmsDashboardTab(this, conn, bus),
                        new BmsCellsTab(this, bus),
                        new BmsControlTab(this, conn, bus),
                        new FaultsTab(this, conn, bus)});
        tabs[1] = new GroupTab(this,
                new String[]{"Vehicle", "Battery", "Signals"},
                new Tab[]{new VehicleTab(this, conn, bus),
                        new BatteryTab(this, conn, bus),
                        new SignalsTab(this, conn, bus)});
        tabs[2] = new GroupTab(this,
                new String[]{"Diagnostics", "Faults", "Log"},
                new Tab[]{new DiagnosticsTab(this, conn, bus),
                        new FaultsTab(this, conn, bus),
                        new LogTab(this, conn, bus)});
        tabs[3] = new GroupTab(this,
                new String[]{"Cloud", "Fleet", "Track"},
                new Tab[]{new CloudTab(this, conn),
                        new FleetTab(this, conn),
                        new TrackTab(this, conn)});
        tabs[4] = new GroupTab(this,
                new String[]{"Bluetooth", "Scan"},
                new Tab[]{btTab, new ScanTab(this)});
        tabs[5] = new GroupTab(this,
                new String[]{"ADB", "Dev", "TechPack", "Mode", "Passcode", "Unlock", "Mirror"},
                new Tab[]{new AdbTab(this, conn),
                        new DevTab(this, conn, bus),
                        new TechPackTab(this, conn),
                        new ModeTab(this, conn),
                        new PasscodeTab(this, conn),
                        new UnlockTab(this, conn),
                        new MirrorTab(this, conn)});
        tabs[6] = new SettingsTab(this, conn);

        host = new FrameLayout(this);
        host.addView(buildRoot(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(host);
        selectTab(0);

        if (Lock.anySet(this)) {
            Lock.lockNow();
            showLock();
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_ADDRESS, "");
        if (!saved.isEmpty()) {
            address.setText(saved);
        }
        applyMode(prefs.getString(KEY_MODE, MODE_AUTO), false);
    }

    @Override
    protected void onDestroy() {
        if (bus != null) {
            bus.stop();
        }
        conn.close();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Lock.anySet(this) && Lock.isLocked()) {
            showLock();
        }
        // Keep the shared cloud snapshot warm whenever a token is set, so data
        // tabs can fill empty fields even while the scooter is asleep.
        CloudState.get(this).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CloudState.get(this).stop();
        // Re-lock when the app leaves the foreground, so the gate is needed again.
        if (Lock.anySet(this)) {
            Lock.lockNow();
        }
    }

    /**
     * Shows the full-screen unlock gate over everything until every configured
     * factor is cleared. When both are set the order is pattern first, PIN
     * second; the app is only marked unlocked once the last factor passes.
     */
    private void showLock() {
        if (lockOverlay != null) {
            return;
        }
        java.util.ArrayDeque<String> steps = new java.util.ArrayDeque<>();
        if (Pattern.isSet(this)) {
            steps.add("pattern");
        }
        if (Lock.isSet(this)) {
            steps.add("pin");
        }
        if (steps.isEmpty()) {
            Lock.unlock();
            return;
        }
        showStep(steps);
    }

    /** Mounts the gate for the step at the head of {@code steps}. */
    private void showStep(java.util.ArrayDeque<String> steps) {
        String step = steps.peekFirst();
        boolean twoFactor = Pattern.isSet(this) && Lock.isSet(this);
        View gate = "pattern".equals(step)
                ? patternGate(twoFactor, () -> advance(steps))
                : pinGate(twoFactor, () -> advance(steps));
        setOverlay(gate);
    }

    /**
     * Clears the current step and either moves to the next or unlocks. The
     * view swap is posted so it runs after the touch that triggered it has
     * finished dispatching — detaching a view mid-dispatch is unsafe.
     */
    private void advance(java.util.ArrayDeque<String> steps) {
        steps.pollFirst();
        if (steps.isEmpty()) {
            Lock.unlock();
            host.post(this::clearOverlay);
        } else {
            host.post(() -> showStep(steps));   // setOverlay swaps the view in place
        }
    }

    private void setOverlay(View gate) {
        if (lockOverlay != null) {
            host.removeView(lockOverlay);
        }
        lockOverlay = gate;
        host.addView(gate, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void clearOverlay() {
        if (lockOverlay != null) {
            host.removeView(lockOverlay);
            lockOverlay = null;
        }
    }

    private View pinGate(boolean twoFactor, Runnable onPass) {
        final PinView[] ref = new PinView[1];
        PinView pin = new PinView(this, "Enter PIN",
                twoFactor ? "Step 2 of 2 — enter your PIN" : "Unlock to continue",
                Lock.length(this), entered -> {
                    if (Lock.verify(this, entered)) {
                        onPass.run();
                    } else {
                        ref[0].fail("Wrong PIN, try again");
                    }
                });
        ref[0] = pin;
        return pin;
    }

    private View patternGate(boolean twoFactor, Runnable onPass) {
        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.BG);
        col.setClickable(true);
        col.setFocusable(true);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 24);
        col.setPadding(pad, Ui.dp(this, 64), pad, pad);

        TextView title = Ui.text(this, "Draw pattern", 20, Ui.TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        col.addView(title);

        final TextView sub = Ui.text(this,
                twoFactor ? "Step 1 of 2 — draw your unlock pattern" : "Draw your unlock pattern",
                13, Ui.DIM);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 24));
        col.addView(sub);

        final PatternView[] ref = new PatternView[1];
        PatternView pad2 = new PatternView(this, nodes -> {
            if (Pattern.verify(this, nodes)) {
                onPass.run();
            } else {
                sub.setText("Wrong pattern, try again");
                sub.setTextColor(Ui.BAD);
                ref[0].failAndClear();
            }
        });
        ref[0] = pad2;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        int gpad = Ui.dp(this, 12);
        lp.setMargins(gpad, gpad, gpad, Ui.dp(this, 48));
        col.addView(pad2, lp);
        return col;
    }

    // ---------------------------------------------------------------- layout

    private View buildRoot() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = Ui.column(this);
        header.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 6));

        TextView title = Ui.text(this, "Pegasus", 20, Ui.TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView subtitle = Ui.note(this,
                "Talks to the scooter over its root gRPC daemon on TCP 50005.");
        header.addView(subtitle);

        LinearLayout connectRow = Ui.row(this);
        connectRow.setPadding(0, Ui.dp(this, 10), 0, 0);

        address = new EditText(this);
        address.setHint("scooter IPv4, e.g. 192.168.1.5");
        address.setTextSize(14);
        address.setTextColor(Ui.TEXT);
        address.setHintTextColor(Ui.DIM);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        address.setBackground(Ui.round(this, Ui.CARD, 10, Ui.LINE));
        address.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        connectRow.addView(address, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        connectButton = Ui.accent(this, "Connect", v -> onConnectClicked());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.leftMargin = Ui.dp(this, 8);
        connectRow.addView(connectButton, buttonParams);
        header.addView(connectRow);

        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeScroll.setPadding(0, Ui.dp(this, 10), 0, 0);
        LinearLayout modeRow = Ui.row(this);
        TextView modeLabel = Ui.note(this, "Connect via");
        LinearLayout.LayoutParams lblParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblParams.rightMargin = Ui.dp(this, 8);
        modeRow.addView(modeLabel, lblParams);
        modeAuto = modeChip("Auto", () -> applyMode(MODE_AUTO, true));
        modeDirect = modeChip("Direct IP / IPv6", () -> applyMode(MODE_DIRECT, true));
        LinearLayout.LayoutParams chipGap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipGap.rightMargin = Ui.dp(this, 8);
        modeRow.addView(modeAuto, chipGap);
        modeRow.addView(modeDirect, chipGap);
        Button savedButton = modeChip("★ Saved", this::showSavedDialog);
        styleModeChip(savedButton, false);
        modeRow.addView(savedButton);
        modeScroll.addView(modeRow);
        header.addView(modeScroll);

        status = Ui.note(this, "Not connected. Join the scooter's Wi-Fi, then press Connect.");
        status.setPadding(0, Ui.dp(this, 8), 0, 0);
        header.addView(status);

        root.addView(header);

        barScroll = new HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 6));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            Button b = new Button(this);
            b.setText(TAB_NAMES[i]);
            b.setAllCaps(false);
            b.setTextSize(12);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            b.setBackgroundColor(Color.TRANSPARENT);
            Ui.wire(b, v -> selectTab(index));
            tabButtons[i] = b;
            bar.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        barScroll.addView(bar, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(barScroll);

        View divider = new View(this);
        divider.setBackgroundColor(Ui.LINE);
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private void selectTab(int index) {
        if (index == current) {
            return;
        }
        if (current >= 0) {
            tabs[current].onHide();
        }
        current = index;
        content.removeAllViews();
        content.addView(tabs[index].view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < tabButtons.length; i++) {
            tabButtons[i].setTextColor(i == index ? Ui.ACCENT : Ui.DIM);
            tabButtons[i].setTypeface(null,
                    i == index ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            if (i == index) {
                tabButtons[i].setBackground(Ui.round(this,
                        Ui.alpha(Ui.ACCENT, 0x1A), 20, Ui.alpha(Ui.ACCENT, 0x55)));
            } else {
                tabButtons[i].setBackgroundColor(Color.TRANSPARENT);
            }
        }
        final Button chosen = tabButtons[index];
        barScroll.post(() -> barScroll.smoothScrollTo(
                Math.max(0, chosen.getLeft() - Ui.dp(this, 24)), 0));
        tabs[index].onShow();
    }

    // ------------------------------------------------------------ connection

    private void onConnectClicked() {
        String typed = address.getText().toString().trim();
        if (typed.isEmpty()) {
            setStatus(MODE_DIRECT.equals(connMode)
                    ? "Type an IPv4, IPv6 or host:port first."
                    : "Type the scooter's IPv4 address first.", Ui.WARN);
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ADDRESS, typed).apply();

        // Direct mode: connect to exactly what was typed (public IP, global IPv6,
        // or host:port) with no BSSID/ARP derivation and no location permission.
        if (MODE_DIRECT.equals(connMode)) {
            doConnectDirect(typed);
            return;
        }

        if (!Addr.isIpv4(typed) && !typed.contains(":")) {
            setStatus("Auto mode needs the scooter's IPv4. For a public IP, a global "
                    + "IPv6 or host:port, switch “Connect via” to Direct.", Ui.WARN);
            return;
        }

        if (!Addr.isIpv4(typed) || Net.isOwnIpv4(typed)) {
            // No BSSID is needed when the address is our own: that is the
            // on-device case, and it talks to its own loopback.
            doConnect(typed);
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAddress = typed;
            setStatus("Asking for the location permission — it is needed to read the "
                    + "Wi-Fi BSSID and derive the scooter's IPv6 address.", Ui.DIM);
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }

        doConnect(typed);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (btTab != null) {
            btTab.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (btTab != null) {
            btTab.onPermissionsResult(requestCode);
        }
        if (requestCode != REQ_LOCATION) {
            return;
        }
        // Denied is not fatal: the app falls back to IPv4 and says so.
        String typed = pendingAddress;
        pendingAddress = null;
        if (typed != null) {
            doConnect(typed);
        }
    }

    private interface ResultSupplier {
        Net.Result get() throws Exception;
    }

    private void doConnect(String typed) {
        runConnect(typed, () -> Net.connect(getApplicationContext(), typed, 4000));
    }

    /** Direct mode: a longer timeout, since a remote/ISP host can be slower. */
    private void doConnectDirect(String typed) {
        runConnect(typed, () -> Net.connectDirect(typed, 8000));
    }

    private void runConnect(String typed, ResultSupplier supplier) {
        connectButton.setEnabled(false);
        setStatus("Connecting to " + typed + " ...", Ui.DIM);

        new Thread(() -> {
            try {
                Net.Result result = supplier.get();
                conn.install(result.rpc, result.target, result.ipv6, result.note);
                bus.start();
                runOnUiThread(() -> {
                    connectButton.setEnabled(true);
                    connectButton.setText("Reconnect");
                    setStatus((result.ipv6 ? "IPv6  " : "IPv4  ") + result.target
                            + "\n" + result.note, result.ipv6 ? Ui.ACCENT : Ui.WARN);
                    if (current >= 0) {
                        tabs[current].onHide();
                        tabs[current].onShow();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    connectButton.setEnabled(true);
                    setStatus(e.getMessage(), Ui.BAD);
                });
            }
        }, "connect").start();
    }

    // -------------------------------------------------------- connection mode

    private Button modeChip(String label, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        Ui.wire(b, v -> onClick.run());
        return b;
    }

    private void applyMode(String mode, boolean persist) {
        connMode = MODE_DIRECT.equals(mode) ? MODE_DIRECT : MODE_AUTO;
        if (persist) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_MODE, connMode).apply();
        }
        boolean direct = MODE_DIRECT.equals(connMode);
        styleModeChip(modeAuto, !direct);
        styleModeChip(modeDirect, direct);
        if (address != null) {
            address.setHint(direct
                    ? "IPv4 / IPv6 / host:port (public or [fe80::1%wlan0])"
                    : "scooter IPv4, e.g. 192.168.1.5");
        }
        if (persist) {
            setStatus(direct
                    ? "Direct mode: connects to exactly what you type — a public IP, a "
                            + "global IPv6, or host:port. No Wi-Fi derivation."
                    : "Auto mode: type the scooter's IPv4; the app derives its IPv6 over "
                            + "Wi-Fi.", Ui.DIM);
        }
    }

    private void styleModeChip(Button b, boolean selected) {
        if (b == null) {
            return;
        }
        if (selected) {
            b.setTextColor(Ui.ACCENT);
            b.setTypeface(null, android.graphics.Typeface.BOLD);
            b.setBackground(Ui.round(this, Ui.alpha(Ui.ACCENT, 0x1A), 16, Ui.alpha(Ui.ACCENT, 0x55)));
        } else {
            b.setTextColor(Ui.DIM);
            b.setTypeface(null, android.graphics.Typeface.NORMAL);
            b.setBackground(Ui.round(this, Ui.CARD, 16, Ui.LINE));
        }
    }

    // ----------------------------------------------------------- saved targets

    private void showSavedDialog() {
        final AlertDialog[] ref = new AlertDialog[1];
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = Ui.column(this);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, 0);
        scroll.addView(box);

        List<Targets.Target> saved = Targets.load(this);
        if (saved.isEmpty()) {
            box.addView(Ui.note(this,
                    "No saved targets yet. Type an address above, then Save current."));
        } else {
            for (Targets.Target t : saved) {
                LinearLayout card = Ui.card(this);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.topMargin = Ui.dp(this, 8);

                LinearLayout line = Ui.row(this);
                LinearLayout col = Ui.column(this);
                TextView nm = Ui.text(this, t.name, 15, Ui.TEXT);
                nm.setTypeface(nm.getTypeface(), android.graphics.Typeface.BOLD);
                col.addView(nm);
                col.addView(Ui.note(this, t.address));
                line.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                Button use = Ui.accent(this, "Connect", v -> {
                    if (ref[0] != null) {
                        ref[0].dismiss();
                    }
                    useTarget(t.address);
                });
                line.addView(use);
                card.addView(line);

                Button del = Ui.danger(this, "Delete", v -> {
                    Targets.remove(this, t.address);
                    if (ref[0] != null) {
                        ref[0].dismiss();
                    }
                    showSavedDialog();
                });
                card.addView(del, cp);
                box.addView(card, cp);
            }
        }

        box.addView(Ui.space(this, 12));
        final EditText nameField = new EditText(this);
        nameField.setHint("Name for the current address");
        nameField.setTextColor(Ui.TEXT);
        nameField.setHintTextColor(Ui.DIM);
        nameField.setSingleLine(true);
        box.addView(nameField);

        ref[0] = new AlertDialog.Builder(this)
                .setTitle("Saved targets")
                .setView(scroll)
                .setPositiveButton("Save current", (d, w) -> {
                    String addr = address.getText().toString().trim();
                    if (addr.isEmpty()) {
                        Toast.makeText(this, "Type an address above first.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = nameField.getText().toString().trim();
                    Targets.add(this, name.isEmpty() ? addr : name, addr);
                    Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .create();
        ref[0].show();
    }

    /** Fill the address, switch to Direct mode, and connect — for a saved target. */
    private void useTarget(String addr) {
        address.setText(addr);
        applyMode(MODE_DIRECT, true);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ADDRESS, addr).apply();
        onConnectClicked();
    }

    private void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    /** Used by the tabs to complain in the header instead of silently failing. */
    void report(String text, int color) {
        setStatus(text, color);
    }

    /**
     * Called from the Fleet tab: point the main live session at one unit. Fills
     * the address field, jumps to the Vehicle screen, and connects the same way
     * the Connect button does.
     */
    public void connectTo(String addr) {
        if (addr == null || addr.trim().isEmpty()) {
            return;
        }
        address.setText(addr.trim());
        selectTab(1);
        onConnectClicked();
    }

    @Override
    public void onBackPressed() {
        // The dash uses HOME to leave; nothing to unwind here.
        moveTaskToBack(true);
    }
}
