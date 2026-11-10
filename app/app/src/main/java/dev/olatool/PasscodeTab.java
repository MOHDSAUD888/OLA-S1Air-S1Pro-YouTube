package dev.olatool;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.generators.SCrypt;
import android.util.Base64;

/**
 * Recover a forgotten dash passcode from its stored scrypt hash (owner
 * self-recovery). The dash keeps each profile's PIN as a lambdaworks scrypt
 * hash {@code $s0$<paramsHex>$<salt_b64>$<dk_b64>} built with
 * {@code scrypt(pin, N=1024, r=8, p=1)} (see RESEARCH.md §11). The PIN is six
 * digits, so the whole keyspace brute-forces on-device in well under a minute
 * across the phone's cores.
 *
 * <p>Two ways to get a hash in:
 * <ul>
 *   <li><b>Pull from dash</b> — over the root gRPC shell, scan the profile
 *       service's Room DB (+ -wal) and prefs for every {@code $s0$} string.
 *       The DB is uid-owned, so this needs the root channel; adb (uid shell)
 *       cannot read it.</li>
 *   <li>Paste a hash by hand.</li>
 * </ul>
 *
 * <p>Nothing here contacts the vehicle beyond the one read; the crack is pure
 * local compute. Gated behind {@link Access#ADMIN}.
 */
public final class PasscodeTab implements Tab {

    /** The profile service's on-device paths holding the hash. */
    private static final String DB =
            "/data/data/com.olaelectric.profilemanagerservice/databases/profile-manager-database";
    private static final String PREFS =
            "/data/data/com.olaelectric.profilemanagerservice/shared_prefs/"
                    + "com.olaelectric.profilemanagerservice.xml";

    private static final Pattern HASH = Pattern.compile(
            "\\$s0\\$[0-9a-fA-F]+\\$[A-Za-z0-9+/]+=*\\$[A-Za-z0-9+/]+=*");

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;

    private final EditText hashInput;
    private final TextView status;
    private final TextView result;
    private final ProgressBar progress;
    private final View crackButton;
    private final View stopButton;

    private volatile Thread worker;
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    public PasscodeTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Passcode recovery",
                "Recover a forgotten dash PIN from its stored scrypt hash. "
                        + "Pull it from the profile service over the root channel, "
                        + "or paste one, then brute-force the 6-digit space locally."));

        LinearLayout hashCard = Ui.card(activity);
        hashCard.addView(Ui.heading(activity, "Hash ($s0$...)"));
        hashInput = new EditText(activity);
        hashInput.setTextColor(Ui.TEXT);
        hashInput.setHint("$s0$a0801$salt$dk");
        hashInput.setHintTextColor(Ui.DIM);
        hashInput.setTextSize(12f);
        hashCard.addView(hashInput);
        body.addView(hashCard, topMargin(12));

        LinearLayout buttons = Ui.row(activity);
        View pull = Ui.button(activity, "Pull from dash", v -> pull());
        crackButton = Ui.accent(activity, "Crack", v -> startCrack());
        stopButton = Ui.danger(activity, "Stop", v -> stop());
        stopButton.setVisibility(View.GONE);
        buttons.addView(pull, weight());
        buttons.addView(Ui.space(activity, 0), fixed(8));
        buttons.addView(crackButton, weight());
        buttons.addView(Ui.space(activity, 0), fixed(8));
        buttons.addView(stopButton, weight());
        body.addView(buttons, topMargin(12));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1_000_000);
        progress.setVisibility(View.GONE);
        body.addView(progress, topMargin(12));

        status = Ui.note(activity, "");
        body.addView(status, topMargin(8));

        result = Ui.codeBlock(Ui.text(activity, "", 16, Ui.TEXT));
        body.addView(result, topMargin(8));
    }

    // ------------------------------------------------------------------- pull

    private void pull() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        status.setText("Reading profile store over root shell...");
        // grep -a treats the binary DB as text; -oE prints only the matches.
        // The regex has no spaces (rpc.shell maps spaces to ${IFS}); single
        // quotes keep sh from globbing the '=*' once it re-parses the script.
        String regex = "\\$s0\\$[0-9a-fA-F]+\\$[A-Za-z0-9+/]+=*\\$[A-Za-z0-9+/]+=*";
        String cmd = "grep -a -oE '" + regex + "' " + DB + " " + DB + "-wal " + PREFS;
        conn.run(rpc -> rpc.shell(cmd, 30_000), (text, error) -> {
            if (error != null) {
                status.setText("Pull failed: " + error.getMessage());
                return;
            }
            List<String> found = extract(text == null ? "" : text);
            if (found.isEmpty()) {
                status.setText("No $s0$ hash found. Paste one by hand.");
                return;
            }
            hashInput.setText(found.get(0));
            StringBuilder sb = new StringBuilder();
            for (String h : found) {
                sb.append(h).append('\n');
            }
            result.setText(sb.toString().trim());
            status.setText(found.size() + " hash(es) found. First one loaded — press Crack.");
        });
    }

    private static List<String> extract(String blob) {
        List<String> out = new ArrayList<>();
        Matcher m = HASH.matcher(blob);
        while (m.find()) {
            String h = m.group();
            if (!out.contains(h)) {
                out.add(h);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ crack

    private void startCrack() {
        if (worker != null) {
            return;
        }
        final String stored = hashInput.getText().toString().trim();
        final Parsed p;
        try {
            p = parse(stored);
        } catch (Exception e) {
            status.setText("Bad hash format. Expected $s0$params$salt$dk.");
            return;
        }

        cancel.set(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        stopButton.setVisibility(View.VISIBLE);
        crackButton.setEnabled(false);
        status.setText("Cracking N=" + p.n + " r=" + p.r + " p=" + p.p + " ...");

        worker = new Thread(() -> runCrack(p), "pin-crack");
        worker.start();
    }

    private void stop() {
        cancel.set(true);
    }

    /**
     * Brute-forces 000000-999999 across all cores. The candidates are tried in a
     * smart order (dates and common PINs first, see {@link #buildOrder}), so a
     * human-chosen PIN usually falls out in seconds instead of grinding the whole
     * space. Workers steal the next index from a shared counter.
     */
    private void runCrack(Parsed p) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long t0 = System.currentTimeMillis();
        AtomicReference<String> hit = new AtomicReference<>(null);
        AtomicLong done = new AtomicLong(0);
        final int[] order = buildOrder();
        final java.util.concurrent.atomic.AtomicInteger next =
                new java.util.concurrent.atomic.AtomicInteger(0);

        Thread[] pool = new Thread[cores];
        for (int c = 0; c < cores; c++) {
            pool[c] = new Thread(() -> {
                int k;
                while ((k = next.getAndIncrement()) < order.length) {
                    if (cancel.get() || hit.get() != null) {
                        return;
                    }
                    String cand = sixDigits(order[k]);
                    byte[] dk = SCrypt.generate(cand.getBytes(), p.salt, p.n, p.r, p.p, p.dk.length);
                    if (java.util.Arrays.equals(dk, p.dk)) {
                        hit.set(cand);
                        return;
                    }
                    long n = done.incrementAndGet();
                    if ((n & 0x3FF) == 0) {              // throttle UI posts
                        conn.post(() -> progress.setProgress((int) done.get()));
                    }
                }
            }, "pin-crack-" + c);
            pool[c].start();
        }
        for (Thread t : pool) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }

        final String pin = hit.get();
        final double secs = (System.currentTimeMillis() - t0) / 1000.0;
        worker = null;
        conn.post(() -> {
            progress.setVisibility(View.GONE);
            stopButton.setVisibility(View.GONE);
            crackButton.setEnabled(true);
            if (pin != null) {
                result.setText("PIN = " + pin);
                // Hand it to the Unlock tab (prefill).
                activity.getApplicationContext().getSharedPreferences("ola", 0)
                        .edit().putString("recovered_pin", pin).apply();
                status.setText("Recovered in " + String.format("%.1f", secs) + "s.");
            } else if (cancel.get()) {
                status.setText("Stopped.");
            } else {
                status.setText("No 6-digit match in " + String.format("%.1f", secs)
                        + "s. The hash may not be a 6-digit PIN.");
            }
        });
    }

    /**
     * A permutation of 0..999999 that front-loads the PINs people actually pick:
     * calendar dates in several digit orders, then repeats/sequences, then every
     * remaining value in numeric order. Covers the whole space, just reordered.
     */
    private static int[] buildOrder() {
        boolean[] seen = new boolean[1_000_000];
        int[] order = new int[1_000_000];
        int[] pos = {0};
        Adder add = v -> {
            if (v >= 0 && v < 1_000_000 && !seen[v]) {
                seen[v] = true;
                order[pos[0]++] = v;
            }
        };
        // Repeats and simple sequences.
        for (int d = 0; d <= 9; d++) add.add(d * 111111);
        add.add(123456); add.add(654321); add.add(121212); add.add(123123);
        add.add(112233); add.add(102030); add.add(123321); add.add(159753);
        // Dates in the three common six-digit layouts.
        for (int a = 1; a <= 31; a++) {
            for (int b = 1; b <= 12; b++) {
                for (int y = 0; y <= 99; y++) {
                    add.add(a * 10000 + b * 100 + y);   // DDMMYY
                    add.add(b * 10000 + a * 100 + y);   // MMDDYY
                    add.add(y * 10000 + b * 100 + a);   // YYMMDD
                }
            }
        }
        // Everything else, numeric order.
        for (int i = 0; i < 1_000_000; i++) add.add(i);
        return order;
    }

    private interface Adder {
        void add(int v);
    }

    private static String sixDigits(int i) {
        char[] c = new char[6];
        for (int k = 5; k >= 0; k--) {
            c[k] = (char) ('0' + (i % 10));
            i /= 10;
        }
        return new String(c);
    }

    private static Parsed parse(String stored) {
        String[] parts = stored.split("\\$");
        // parts[0] is empty (leading $): "", "s0", paramsHex, saltB64, dkB64
        if (parts.length != 5 || !parts[1].equals("s0")) {
            throw new IllegalArgumentException("not an $s0$ hash");
        }
        long params = Long.parseLong(parts[2], 16);
        Parsed p = new Parsed();
        p.n = 1 << ((int) ((params >> 16) & 0xFFFF));
        p.r = (int) ((params >> 8) & 0xFF);
        p.p = (int) (params & 0xFF);
        p.salt = Base64.decode(parts[3], Base64.DEFAULT);
        p.dk = Base64.decode(parts[4], Base64.DEFAULT);
        return p;
    }

    private static final class Parsed {
        int n, r, p;
        byte[] salt, dk;
    }

    // --------------------------------------------------------------- plumbing

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams fixed(int dp) {
        return new LinearLayout.LayoutParams(Ui.dp(activity, dp),
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
    }

    @Override
    public void onHide() {
        stop();
    }
}
