package dev.olatool;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dev.olatool.core.Addr;

/**
 * Network scanner: sweeps the current /24, identifies each live host by its open
 * ports (scooter, TV, router, phone, computer …), and shows a type logo with the
 * IP, MAC, and open ports. Read-only — it only probes and reads the ARP table.
 */
public final class ScanTab implements Tab {

    /** Ports probed per host, with the service they hint at. */
    private static final int[] PORTS = {50005, 5555, 8009, 8008, 8060, 7000, 22, 80, 443, 9000, 62078};

    private final Context ctx;
    private final ScrollView root;

    private TextView statusLine;
    private Button scanButton;
    private LinearLayout list;

    private volatile boolean scanning;
    private boolean scannedOnce;
    private ExecutorService pool;

    public ScanTab(Context ctx) {
        this.ctx = ctx;
        this.root = Ui.page(ctx);
        build();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        // Scan the moment the user opens this screen, so devices appear without
        // a tap. Once only: re-scan is manual, to avoid sweeping on every switch.
        if (!scannedOnce) {
            scannedOnce = true;
            onScan();
        }
    }

    @Override
    public void onHide() {
        scanning = false;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void build() {
        LinearLayout body = Ui.body(root);

        body.addView(Ui.pageTitle(ctx, "Scan",
                "Find devices on this network and identify each one — scooter, TV, "
                        + "router, phone, computer — with its IP, MAC and open ports."));

        Ui.Section head = Ui.section(ctx, "Network scan", null);
        LinearLayout buttons = Ui.row(ctx);
        scanButton = Ui.accent(ctx, "Scan network", v -> onScan());
        buttons.addView(scanButton);
        head.body.addView(buttons);
        statusLine = Ui.note(ctx, "Scans the /24 you are on. Join the scooter's Wi-Fi to find it.");
        statusLine.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        head.body.addView(statusLine);
        body.addView(head.card);

        body.addView(Ui.space(ctx, 10));

        Ui.Section devices = Ui.section(ctx, "Devices", null, Ui.INFO);
        list = devices.body;
        TextView empty = Ui.note(ctx, "No scan yet.");
        list.addView(empty);
        body.addView(devices.card);
    }

    // -------------------------------------------------------------- behaviour

    private void onScan() {
        if (scanning) {
            scanning = false;
            if (pool != null) {
                pool.shutdownNow();
            }
            scanButton.setText("Scan network");
            setStatus("Scan stopped.", Ui.DIM);
            return;
        }
        String own = Net.ourIpv4(ctx);
        final String prefix = Addr.ipv4Prefix24(own);
        if (prefix == null) {
            setStatus("No IPv4 network to scan. Join a Wi-Fi first.", Ui.WARN);
            return;
        }
        list.removeAllViews();
        scanning = true;
        scanButton.setText("Stop");
        setStatus("Scanning " + prefix + "0/24 …", Ui.ACCENT);

        final String gateway = prefix + "1";
        pool = Executors.newFixedThreadPool(48, r -> {
            Thread t = new Thread(r, "scan");
            t.setDaemon(true);
            return t;
        });
        final AtomicInteger pending = new AtomicInteger(0);
        final AtomicInteger found = new AtomicInteger(0);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            pending.incrementAndGet();
            pool.execute(() -> {
                try {
                    if (!scanning) {
                        return;
                    }
                    List<Integer> open = probe(ip);
                    boolean reachable = open.isEmpty() && reachable(ip);
                    if (!open.isEmpty() || reachable) {
                        final String mac = arpMac(ip);
                        final boolean self = ip.equals(own);
                        final boolean gw = ip.equals(gateway);
                        found.incrementAndGet();
                        post(() -> addDevice(ip, mac, open, self, gw));
                    }
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        post(() -> finish(found.get()));
                    }
                }
            });
        }
    }

    private void finish(int found) {
        scanning = false;
        // Release the 48 worker threads; a fixed pool keeps them alive until
        // shutdown, so without this each completed scan would leak a poolful.
        if (pool != null) {
            pool.shutdown();
        }
        if (scanButton != null) {
            scanButton.setText("Scan network");
        }
        if (found == 0) {
            setStatus("No devices answered. Wake the scooter and check you are on its Wi-Fi.", Ui.WARN);
            TextView none = Ui.note(ctx, "Nothing found.");
            list.addView(none);
        } else {
            setStatus("Scan done. " + found + " device(s) found.", Ui.DIM);
        }
    }

    private void addDevice(String ip, String mac, List<Integer> open, boolean self, boolean gateway) {
        String type = classify(open, self, gateway);
        String emoji = emojiFor(type);
        int color = colorFor(type);

        LinearLayout card = Ui.card(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 8);
        card.setLayoutParams(lp);

        LinearLayout row = Ui.row(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(ctx);
        badge.setText(emoji);
        badge.setTextSize(22);
        badge.setGravity(Gravity.CENTER);
        int sz = Ui.dp(ctx, 46);
        badge.setWidth(sz);
        badge.setHeight(sz);
        badge.setBackground(Ui.round(ctx, Ui.alpha(color, 0x22), 23, Ui.alpha(color, 0x66)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(sz, sz);
        bp.rightMargin = Ui.dp(ctx, 12);
        row.addView(badge, bp);

        LinearLayout col = Ui.column(ctx);
        TextView title = Ui.text(ctx, type, 15, color);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(title);
        TextView ipLine = Ui.text(ctx, "IP   " + ip, 12, Ui.TEXT);
        col.addView(ipLine);
        TextView macLine = Ui.text(ctx, "MAC  " + (mac == null ? Ui.DASH : mac), 12, Ui.DIM);
        col.addView(macLine);
        TextView portLine = Ui.text(ctx, "Ports  " + portsLabel(open), 12, Ui.DIM);
        col.addView(portLine);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row);

        // Tell the user, per device, whether the app can connect to it. Only the
        // scooter (port 50005) is connectable; show a Connect button for it.
        boolean connectable = "Scooter".equals(type);
        TextView can = Ui.text(ctx,
                connectable ? "✓ Ready to connect" : "Can’t connect — not a scooter",
                12, connectable ? Ui.ACCENT : Ui.DIM);
        can.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        card.addView(can);

        if (connectable && ctx instanceof MainActivity) {
            final String target = ip;
            Button connect = Ui.accent(ctx, "Connect", v -> ((MainActivity) ctx).connectTo(target));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = Ui.dp(ctx, 8);
            card.addView(connect, cp);
        }

        list.addView(card);
    }

    // ------------------------------------------------------------- probing

    private static List<Integer> probe(String ip) {
        List<Integer> open = new ArrayList<>();
        for (int port : PORTS) {
            if (portOpen(ip, port, 350)) {
                open.add(port);
            }
        }
        return open;
    }

    private static boolean portOpen(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean reachable(String ip) {
        try {
            return InetAddress.getByName(ip).isReachable(350);
        } catch (Exception e) {
            return false;
        }
    }

    /** The MAC the kernel has for an IPv4 in the ARP table, or null. */
    private static String arpMac(String ip) {
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 4 || !ip.equals(f[0])) {
                    continue;
                }
                String mac = f[3].toUpperCase(Locale.ROOT);
                if (Addr.isMac(mac) && !"00:00:00:00:00:00".equals(mac)) {
                    return mac;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------- classify

    private static String classify(List<Integer> open, boolean self, boolean gateway) {
        if (self) {
            return "This phone";
        }
        if (open.contains(50005)) {
            return "Scooter";
        }
        if (gateway) {
            return "Router";
        }
        if (open.contains(8009) || open.contains(8008) || open.contains(8060) || open.contains(7000)) {
            return "TV / cast";
        }
        if (open.contains(62078)) {
            return "iPhone";
        }
        if (open.contains(5555)) {
            return "Android device";
        }
        if (open.contains(22)) {
            return "Computer";
        }
        if (open.contains(80) || open.contains(443) || open.contains(9000)) {
            return "Web device";
        }
        return "Unknown device";
    }

    private static String emojiFor(String type) {
        switch (type) {
            case "This phone":
            case "Android device":
                return "📱";      // 📱
            case "iPhone":
                return "📱";      // 📱
            case "Scooter":
                return "🛵";      // 🛵
            case "Router":
                return "📶";      // 📶
            case "TV / cast":
                return "📺";      // 📺
            case "Computer":
                return "💻";      // 💻
            case "Web device":
                return "🌐";      // 🌐
            default:
                return "❓";            // ❓
        }
    }

    private static int colorFor(String type) {
        switch (type) {
            case "Scooter":
                return Ui.ACCENT;
            case "Router":
                return Ui.WARN;
            case "This phone":
            case "Android device":
            case "iPhone":
                return Ui.INFO;
            case "TV / cast":
                return Ui.LILAC;
            default:
                return Ui.DIM;
        }
    }

    private static String portsLabel(List<Integer> open) {
        if (open.isEmpty()) {
            return "(none open)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < open.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(open.get(i));
        }
        return sb.toString();
    }

    private void post(Runnable r) {
        root.post(r);
    }

    private void setStatus(String text, int color) {
        if (statusLine == null) {
            return;
        }
        Ui.set(statusLine, text);
        statusLine.setTextColor(color);
    }
}
