package dev.olatool;

import android.Manifest;
import android.app.Activity;
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
import android.widget.TextView;

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
    private static final String[] TAB_NAMES = {
            "Battery", "Signals", "Faults", "Log", "Settings",
            "ADB", "Mode", "TechPack", "Dev"
    };

    private final Conn conn = new Conn();
    private final Tab[] tabs = new Tab[TAB_NAMES.length];
    private final Button[] tabButtons = new Button[TAB_NAMES.length];

    private Bus bus;
    private EditText address;
    private Button connectButton;
    private TextView status;
    private FrameLayout content;
    private HorizontalScrollView barScroll;
    private int current = -1;
    private String pendingAddress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bus = new Bus(this, conn);

        tabs[0] = new BatteryTab(this, conn, bus);
        tabs[1] = new SignalsTab(this, conn, bus);
        tabs[2] = new FaultsTab(this, conn, bus);
        tabs[3] = new LogTab(this, conn, bus);
        tabs[4] = new SettingsTab(this, conn);
        tabs[5] = new AdbTab(this, conn);
        tabs[6] = new ModeTab(this, conn);
        tabs[7] = new TechPackTab(this, conn);
        tabs[8] = new DevTab(this, conn, bus);

        setContentView(buildRoot());
        selectTab(0);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_ADDRESS, "");
        if (!saved.isEmpty()) {
            address.setText(saved);
        }
    }

    @Override
    protected void onDestroy() {
        if (bus != null) {
            bus.stop();
        }
        conn.close();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- layout

    private View buildRoot() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = Ui.column(this);
        header.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 6));

        TextView title = Ui.text(this, "Ola S1 tool", 20, Ui.TEXT);
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
            b.setOnClickListener(v -> selectTab(index));
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
            setStatus("Type the scooter's IPv4 address first.", Ui.WARN);
            return;
        }
        if (!Addr.isIpv4(typed) && !typed.contains(":")) {
            setStatus("That does not look like an IPv4 address.", Ui.WARN);
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ADDRESS, typed).apply();

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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
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

    private void doConnect(String typed) {
        connectButton.setEnabled(false);
        setStatus("Connecting to " + typed + " ...", Ui.DIM);

        new Thread(() -> {
            try {
                Net.Result result = Net.connect(getApplicationContext(), typed, 4000);
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

    private void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    /** Used by the tabs to complain in the header instead of silently failing. */
    void report(String text, int color) {
        setStatus(text, color);
    }

    @Override
    public void onBackPressed() {
        // The dash uses HOME to leave; nothing to unwind here.
        moveTaskToBack(true);
    }
}
