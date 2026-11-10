package dev.olatool;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dev.olatool.core.Addr;
import dev.olatool.core.BatteryHealth;
import dev.olatool.core.Rpc;

/**
 * The fleet overview: every saved unit of the owner's own, with its charge and
 * where it is, read side by side.
 *
 * <p>Units are added by hand or confirmed from a subnet scan — never added on
 * their own — so this stays a view of the owner's vehicles, which is the only
 * use this tool is for. Each unit is polled over its own short-lived link, so
 * the overview never disturbs whichever unit the other tabs are on.
 */
public final class FleetTab implements Tab {

    private final MainActivity activity;
    private final ScrollView page;
    private final LinearLayout list;
    private final TextView summary;

    private final ExecutorService pollPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "fleet-poll");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Row> rows = new HashMap<>();
    /** Last battery-health verdict per unit, for the fleet flagged-count summary. */
    private final Map<String, BatteryHealth.Result> lastHealth = new HashMap<>();
    private volatile boolean visible;
    private boolean everRefreshed;

    /** The live views of one card, so a poll result can update it in place. */
    private static final class Row {
        final String id;
        final TextView status;
        final TextView soc;
        final TextView detail;
        final TextView health;
        final TextView loc;
        final TextView updated;

        Row(String id, TextView status, TextView soc, TextView detail,
            TextView health, TextView loc, TextView updated) {
            this.id = id;
            this.status = status;
            this.soc = soc;
            this.detail = detail;
            this.health = health;
            this.loc = loc;
            this.updated = updated;
        }
    }

    public FleetTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Fleet",
                "Your own scooters, side by side. Add one or scan the network."));

        summary = Ui.note(activity, "");
        summary.setPadding(0, 0, 0, Ui.dp(activity, 10));
        body.addView(summary);

        LinearLayout buttons = Ui.row(activity);
        buttons.addView(Ui.accent(activity, "Refresh all", v -> refreshAll()));
        buttons.addView(spacer());
        buttons.addView(Ui.button(activity, "Add", v -> addDialog()));
        buttons.addView(spacer());
        buttons.addView(Ui.button(activity, "Discover", v -> discoverDialog()));
        buttons.addView(spacer());
        buttons.addView(Ui.button(activity, "From cloud", v -> addFromCloud()));
        body.addView(buttons);

        list = Ui.column(activity);
        list.setPadding(0, Ui.dp(activity, 12), 0, 0);
        body.addView(list);
    }

    private View spacer() {
        View v = new View(activity);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(activity, 8), 1));
        return v;
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        visible = true;
        render();
        if (!everRefreshed && !Fleet.load(activity).isEmpty()) {
            everRefreshed = true;
            refreshAll();
        }
    }

    @Override
    public void onHide() {
        visible = false;
    }

    // ------------------------------------------------------------------ list

    private void render() {
        list.removeAllViews();
        rows.clear();
        lastHealth.clear();
        List<Scooter> fleet = Fleet.load(activity);
        summary.setText(fleet.isEmpty()
                ? "No scooters yet. Add one by IP, or Discover to scan this network."
                : fleet.size() + (fleet.size() == 1 ? " scooter saved." : " scooters saved."));
        for (Scooter s : fleet) {
            list.addView(card(s), cardParams());
        }
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(activity, 10);
        return lp;
    }

    private View card(Scooter s) {
        LinearLayout card = Ui.card(activity);

        LinearLayout head = Ui.row(activity);
        LinearLayout titles = Ui.column(activity);
        TextView name = Ui.text(activity, s.name.isEmpty() ? s.address : s.name, 16, Ui.TEXT);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        titles.addView(name);
        titles.addView(Ui.note(activity, s.isCloud() ? "Ola cloud" : s.address));
        head.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = Ui.pill(activity, "idle", Ui.DIM);
        head.addView(status);
        card.addView(head);

        TextView soc = Ui.text(activity, Ui.DASH, 30, Ui.TEXT);
        soc.setTypeface(soc.getTypeface(), android.graphics.Typeface.BOLD);
        soc.setPadding(0, Ui.dp(activity, 8), 0, 0);
        card.addView(soc);

        TextView detail = Ui.note(activity, "Tap Refresh to read this unit.");
        detail.setPadding(0, Ui.dp(activity, 2), 0, 0);
        card.addView(detail);

        TextView health = Ui.text(activity, "Battery health —", 13, Ui.DIM);
        health.setPadding(0, Ui.dp(activity, 6), 0, 0);
        card.addView(health);

        TextView loc = Ui.text(activity, "Location —", 13, Ui.DIM);
        loc.setPadding(0, Ui.dp(activity, 8), 0, 0);
        card.addView(loc);

        TextView updated = Ui.note(activity, "");
        updated.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 4));
        card.addView(updated);

        LinearLayout actions = Ui.row(activity);
        Button connect = Ui.button(activity, "Connect", v -> activity.connectTo(s.address));
        connect.setEnabled(!s.isCloud());   // cloud units have no local link to open
        actions.addView(connect);
        actions.addView(spacer());
        Button map = Ui.button(activity, "Map", v -> openMap(s));
        map.setEnabled(false);
        map.setTag("map");
        actions.addView(map);
        actions.addView(spacer());
        actions.addView(Ui.button(activity, "Remove", v -> removeDialog(s)));
        card.addView(actions, topGap());

        Row row = new Row(s.id, status, soc, detail, health, loc, updated);
        // Stash the map button + last reading on the card so openMap can find them.
        card.setTag(new Object[]{row, map, null});
        rows.put(s.id, row);
        return card;
    }

    private LinearLayout.LayoutParams topGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, 10);
        return lp;
    }

    // --------------------------------------------------------------- polling

    private void refreshAll() {
        List<Scooter> fleet = Fleet.load(activity);
        if (fleet.isEmpty()) {
            Toast.makeText(activity, "Add a scooter first.", Toast.LENGTH_SHORT).show();
            return;
        }
        for (Scooter s : fleet) {
            Row row = rows.get(s.id);
            if (row != null) {
                Ui.pill(row.status, "checking", Ui.INFO);
                Ui.set(row.detail, "Connecting and reading …");
            }
            final Scooter unit = s;
            pollPool.execute(() -> {
                Fleet.Reading r = Fleet.read(activity, unit, 4000);
                if (visible) {
                    activity.runOnUiThread(() -> applyReading(unit.id, r));
                }
            });
        }
    }

    private void applyReading(String id, Fleet.Reading r) {
        Row row = rows.get(id);
        if (row == null) {
            return;   // card was removed while the poll ran
        }
        if (!r.online) {
            Ui.pill(row.status, "offline", Ui.BAD);
            Ui.set(row.soc, Ui.DASH);
            Ui.set(row.detail, r.error == null ? "No answer." : shorten(r.error));
            applyHealth(row, null);
            Ui.set(row.loc, "Location —");
            Ui.set(row.updated, "checked " + clock(r.at));
            return;
        }

        Ui.pill(row.status, "online " + r.link, Ui.ACCENT);
        if (r.hasPack) {
            Ui.set(row.soc, Ui.pct(r.soc));
            StringBuilder d = new StringBuilder();
            d.append(Ui.fmt(r.packV, 1)).append(" V");
            if (r.charging == 1) {
                d.append("   · charging");
            }
            if (!Double.isNaN(r.speedKph) && r.speedKph > 0) {
                d.append("   · ").append(Ui.fmt(r.speedKph, 0)).append(" km/h");
            }
            if (!Double.isNaN(r.soh)) {
                d.append("   · SOH ").append(Ui.fmt(r.soh, 0)).append("%");
            }
            Ui.set(row.detail, d.toString());
        } else {
            Ui.set(row.soc, Ui.DASH);
            Ui.set(row.detail, "Online, but no live pack data — the vehicle is asleep "
                    + "(press its red button to read the battery).");
        }

        if ("cloud".equals(r.link) && r.health == null) {
            lastHealth.remove(row.id);
            row.health.setTextColor(Ui.DIM);
            Ui.set(row.health, "Battery health — cloud has no temperature "
                    + "(connect on Wi-Fi for a thermal check)");
            row.health.setOnClickListener(null);
            row.health.setClickable(false);
            updateFlaggedSummary();
        } else {
            applyHealth(row, r.health);
        }

        if (r.hasLoc) {
            StringBuilder l = new StringBuilder();
            l.append(String.format(Locale.US, "%.5f, %.5f", r.lat, r.lon));
            if (!Double.isNaN(r.accM)) {
                l.append("   ±").append(Ui.fmt(r.accM, 0)).append(" m");
            }
            if (r.sats >= 0) {
                l.append("   · ").append(r.sats).append(" sats");
            }
            Ui.set(row.loc, l.toString());
            enableMap(id, r.lat, r.lon);
        } else {
            Ui.set(row.loc, "Location — no GPS fix");
        }
        Ui.set(row.updated, "updated " + clock(r.at));
    }

    private void enableMap(String id, double lat, double lon) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof Object[])) {
                continue;
            }
            Object[] holder = (Object[]) tag;
            if (holder[0] instanceof Row && ((Row) holder[0]).id.equals(id)) {
                holder[2] = new double[]{lat, lon};
                if (holder[1] instanceof Button) {
                    ((Button) holder[1]).setEnabled(true);
                }
                return;
            }
        }
    }

    private void openMap(Scooter s) {
        double[] ll = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            Object tag = list.getChildAt(i).getTag();
            if (tag instanceof Object[]) {
                Object[] holder = (Object[]) tag;
                if (holder[0] instanceof Row && ((Row) holder[0]).id.equals(s.id)
                        && holder[2] instanceof double[]) {
                    ll = (double[]) holder[2];
                    break;
                }
            }
        }
        if (ll == null) {
            Toast.makeText(activity, "No fix yet — refresh first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String label = Uri.encode(s.name.isEmpty() ? s.address : s.name);
        Uri geo = Uri.parse(String.format(Locale.US,
                "geo:%f,%f?q=%f,%f(%s)", ll[0], ll[1], ll[0], ll[1], label));
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, geo));
        } catch (RuntimeException e) {
            Toast.makeText(activity, "No map app to show " + ll[0] + ", " + ll[1],
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------- add

    private void addDialog() {
        LinearLayout box = Ui.column(activity);
        int pad = Ui.dp(activity, 16);
        box.setPadding(pad, pad, pad, 0);

        final EditText name = new EditText(activity);
        name.setHint("Name (e.g. My S1)");
        name.setSingleLine(true);
        box.addView(name);

        final EditText addr = new EditText(activity);
        addr.setHint("IPv4, e.g. 192.168.0.238");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addr.setSingleLine(true);
        box.addView(addr);

        new AlertDialog.Builder(activity)
                .setTitle("Add your scooter")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String a = addr.getText().toString().trim();
                    if (!Addr.isIpv4(a) && !a.contains(":")) {
                        Toast.makeText(activity, "That is not an IPv4 or IPv6 address.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String n = name.getText().toString().trim();
                    Fleet.add(activity, Scooter.create(n.isEmpty() ? a : n, a));
                    render();
                    refreshAll();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Adds the account's own vehicle from Ola's cloud, using the token already
     * stored in the app. Read-only: it polls the cloud once and saves a
     * cloud-backed fleet entry. One consumer token sees one account's vehicle;
     * a full company fleet needs a fleet backend, not this.
     */
    private void addFromCloud() {
        if (!OlaCloud.hasToken(activity)) {
            Toast.makeText(activity, "Set your cloud token in the Cloud tab first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Checking Ola cloud …", Toast.LENGTH_SHORT).show();
        pollPool.execute(() -> {
            final OlaCloud.Status s = OlaCloud.pollStatistics(activity);
            if (!visible) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (!s.ok) {
                    Toast.makeText(activity, s.error == null
                            ? "Cloud did not answer." : s.error, Toast.LENGTH_LONG).show();
                    return;
                }
                String uuid = OlaCloud.uuid(activity);
                if (uuid == null || uuid.trim().isEmpty()) {
                    uuid = "cloud-vehicle";
                }
                String name = "My S1 (cloud)";
                if (Fleet.has(activity, uuid)) {
                    Toast.makeText(activity, "Already in the fleet.", Toast.LENGTH_SHORT).show();
                } else {
                    Fleet.add(activity, Scooter.createCloud(name, uuid));
                    Toast.makeText(activity, "Added from cloud.", Toast.LENGTH_SHORT).show();
                }
                render();
                refreshAll();
            });
        });
    }

    private void removeDialog(Scooter s) {
        new AlertDialog.Builder(activity)
                .setTitle("Remove this scooter?")
                .setMessage((s.name.isEmpty() ? s.address : s.name)
                        + "\n\nThis only forgets it here. The scooter is not touched.")
                .setPositiveButton("Remove", (d, w) -> {
                    Fleet.remove(activity, s.id);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -------------------------------------------------------------- discover

    private void discoverDialog() {
        String own = Net.ourIpv4(activity);
        final String prefix = Addr.ipv4Prefix24(own);
        if (prefix == null) {
            Toast.makeText(activity,
                    "Join the scooter's Wi-Fi first — no IPv4 network to scan.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Scanning " + prefix + "0/24 on port "
                + Rpc.DEFAULT_PORT + " …", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            final List<String> found = scanSubnet(prefix, own);
            if (visible) {
                activity.runOnUiThread(() -> showDiscovered(found));
            }
        }, "fleet-scan").start();
    }

    /** TCP-connects to every host on the /24 and keeps those with 50005 open. */
    private List<String> scanSubnet(String prefix, String own) {
        ExecutorService scan = Executors.newFixedThreadPool(32, r -> {
            Thread t = new Thread(r, "fleet-scanone");
            t.setDaemon(true);
            return t;
        });
        final List<String> hits = new ArrayList<>();
        final AtomicInteger pending = new AtomicInteger(0);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            if (ip.equals(own)) {
                continue;
            }
            pending.incrementAndGet();
            scan.execute(() -> {
                if (portOpen(ip, Rpc.DEFAULT_PORT, 500)) {
                    synchronized (hits) {
                        hits.add(ip);
                    }
                }
                synchronized (pending) {
                    pending.decrementAndGet();
                    pending.notifyAll();
                }
            });
        }
        synchronized (pending) {
            long deadline = System.currentTimeMillis() + 20_000;
            while (pending.get() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    pending.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        scan.shutdownNow();
        hits.sort((a, b) -> Long.compare(Addr.ipv4ToLong(a), Addr.ipv4ToLong(b)));
        return hits;
    }

    private static boolean portOpen(String ip, int port, int timeoutMs) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showDiscovered(List<String> found) {
        if (found.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Nothing found")
                    .setMessage("No host had port " + Rpc.DEFAULT_PORT + " open on this network.\n\n"
                            + "Wake the scooter (red button) and make sure you are on its Wi-Fi.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = Ui.column(activity);
        int pad = Ui.dp(activity, 16);
        box.setPadding(pad, pad, pad, 0);
        scroll.addView(box);

        TextView hint = Ui.note(activity, "Tick only the scooters that are yours, then Add. "
                + "Found on port " + Rpc.DEFAULT_PORT + ":");
        hint.setPadding(0, 0, 0, Ui.dp(activity, 8));
        box.addView(hint);

        final List<CheckBox> checks = new ArrayList<>();
        final List<EditText> names = new ArrayList<>();
        final List<String> ips = new ArrayList<>();
        for (String ip : found) {
            boolean already = Fleet.has(activity, ip);
            LinearLayout rowView = Ui.row(activity);
            CheckBox cb = new CheckBox(activity);
            cb.setChecked(!already);
            cb.setEnabled(!already);
            rowView.addView(cb);

            LinearLayout col = Ui.column(activity);
            EditText nm = new EditText(activity);
            String octet = ip.substring(ip.lastIndexOf('.') + 1);
            nm.setText(already ? "(already saved)" : "Scooter " + octet);
            nm.setEnabled(!already);
            nm.setSingleLine(true);
            col.addView(nm);
            col.addView(Ui.note(activity, ip));
            rowView.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            rowView.setGravity(Gravity.CENTER_VERTICAL);
            box.addView(rowView, listRowGap());

            checks.add(cb);
            names.add(nm);
            ips.add(ip);
        }

        new AlertDialog.Builder(activity)
                .setTitle(found.size() + " device(s) found")
                .setView(scroll)
                .setPositiveButton("Add selected", (d, w) -> {
                    int added = 0;
                    for (int i = 0; i < ips.size(); i++) {
                        if (checks.get(i).isChecked() && checks.get(i).isEnabled()) {
                            String n = names.get(i).getText().toString().trim();
                            String ip = ips.get(i);
                            Fleet.add(activity, Scooter.create(n.isEmpty() ? ip : n, ip));
                            added++;
                        }
                    }
                    Toast.makeText(activity, "Added " + added + ".", Toast.LENGTH_SHORT).show();
                    render();
                    refreshAll();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout.LayoutParams listRowGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, 6);
        return lp;
    }

    // ---------------------------------------------------------- battery health

    /** Shows the read-only battery verdict on a card; null clears it. */
    private void applyHealth(Row row, BatteryHealth.Result res) {
        if (res == null || res.risk == BatteryHealth.Risk.UNKNOWN) {
            lastHealth.remove(row.id);
            row.health.setTextColor(Ui.DIM);
            Ui.set(row.health, "Battery health —");
            row.health.setOnClickListener(null);
            row.health.setClickable(false);
            updateFlaggedSummary();
            return;
        }
        lastHealth.put(row.id, res);
        String first = res.reasons.isEmpty() ? res.headline : res.reasons.get(0);
        row.health.setTextColor(riskColor(res.risk));
        Ui.set(row.health, "Battery: " + riskShort(res.risk) + " — " + first
                + (res.reasons.size() > 1 ? "  (tap for " + res.reasons.size() + ")" : ""));
        row.health.setClickable(true);
        row.health.setOnClickListener(v -> healthDialog(row.id, res));
        updateFlaggedSummary();
    }

    private void healthDialog(String id, BatteryHealth.Result res) {
        StringBuilder m = new StringBuilder();
        m.append(res.headline).append("\n");
        for (String reason : res.reasons) {
            m.append("\n•  ").append(reason);
        }
        m.append("\n\nAdvisory only. This reads the pack and the BMS's own flags — "
                + "it does not and cannot disable the vehicle. For a flagged unit, contact the "
                + "owner and route it through service / the BMS team's recall process.");
        new AlertDialog.Builder(activity)
                .setTitle("Battery health — " + riskShort(res.risk))
                .setMessage(m.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void updateFlaggedSummary() {
        int flagged = 0;
        for (BatteryHealth.Result r : lastHealth.values()) {
            if (r != null && r.flagged()) {
                flagged++;
            }
        }
        int fleetSize = Fleet.load(activity).size();
        String base = fleetSize == 0
                ? "No scooters yet. Add one by IP, or Discover to scan this network."
                : fleetSize + (fleetSize == 1 ? " scooter saved." : " scooters saved.");
        if (flagged > 0) {
            base += "   ⚠ " + flagged + " flagged — check battery health.";
        }
        summary.setText(base);
    }

    private static int riskColor(BatteryHealth.Risk risk) {
        switch (risk) {
            case CRITICAL: return Ui.BAD;
            case AT_RISK:  return Ui.WARN;
            case WATCH:    return Ui.INFO;
            case OK:       return Ui.ACCENT;
            default:       return Ui.DIM;
        }
    }

    private static String riskShort(BatteryHealth.Risk risk) {
        switch (risk) {
            case CRITICAL: return "CRITICAL";
            case AT_RISK:  return "AT RISK";
            case WATCH:    return "WATCH";
            case OK:       return "OK";
            default:       return "no data";
        }
    }

    // ----------------------------------------------------------------- utils

    private static String clock(long at) {
        return String.format(Locale.US, "%tT", at);
    }

    private static String shorten(String s) {
        String one = s.replace('\n', ' ').trim();
        return one.length() > 120 ? one.substring(0, 117) + "…" : one;
    }
}
