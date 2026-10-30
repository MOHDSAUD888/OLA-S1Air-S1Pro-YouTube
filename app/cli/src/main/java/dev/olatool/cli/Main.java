package dev.olatool.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.olatool.core.Addr;
import dev.olatool.core.Can;
import dev.olatool.core.Dbc;
import dev.olatool.core.Faults;
import dev.olatool.core.Live;
import dev.olatool.core.Msg;
import dev.olatool.core.Provision;
import dev.olatool.core.Rpc;

/**
 * Desktop harness. Same core classes the Android app uses, so anything that
 * works here works there — the only difference is that on a PC the address is
 * typed directly instead of being derived from the Wi-Fi BSSID.
 *
 * <pre>
 *   cli 192.168.1.5 probe
 *   cli "fe80::382b:7cff:fe06:5da8%6" info
 *   cli 192.168.1.5 battery 10
 *   cli 192.168.1.5 stream 10
 *   cli 192.168.1.5 signals 10
 *   cli 192.168.1.5 faults 10
 *   cli 192.168.1.5 shell "dumpsys activity activities | head -20"
 *   cli 192.168.1.5 mode
 *   cli 192.168.1.5 ident
 *   cli 192.168.1.5 provision
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length >= 1 && "selftest".equals(args[0])) {
            try {
                SelfTest.main(new String[0]);
            } catch (Exception e) {
                System.err.println("selftest error: " + e);
                System.exit(1);
            }
            return;
        }
        if (args.length >= 1 && "db".equals(args[0])) {
            db();
            return;
        }
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        String where = args[0];
        String what = args[1];

        // Loading the tables needs no scooter, so do it before opening a channel.
        if ("db".equals(what)) {
            db();
            return;
        }

        Addr.Target target = Addr.parse(where, Rpc.DEFAULT_PORT);
        System.out.println("target  " + target);

        try (Rpc rpc = Rpc.open(target, 8000)) {
            System.out.println("uid     " + rpc.uid());
            System.out.println();

            switch (what) {
                case "probe":
                    return;
                case "info":
                    info(rpc);
                    return;
                case "battery":
                    battery(rpc, args.length > 2 ? Integer.parseInt(args[2]) : 8);
                    return;
                case "can":
                    can(rpc, args.length > 2 ? Integer.parseInt(args[2]) : 5);
                    return;
                case "stream":
                    stream(rpc, seconds(args, 2, 10), false);
                    return;
                case "signals":
                    stream(rpc, seconds(args, 2, 10), true);
                    return;
                case "faults":
                    faults(rpc, seconds(args, 2, 10));
                    return;
                case "log":
                    log(rpc, seconds(args, 2, 10));
                    return;
                case "db":
                    db();
                    return;
                case "ident":
                    ident(rpc);
                    return;
                case "provision":
                    provision(rpc);
                    return;
                case "unprovision":
                    unprovision(rpc, args.length > 2 ? args[2] : null);
                    return;
                case "provision-status":
                    System.out.println(rpc.runScript("olatool-status.sh",
                            Provision.statusScript(), 30_000));
                    return;
                case "settings":
                    System.out.println(rpc.exec("am start -a android.settings.SETTINGS"));
                    System.out.println(rpc.shell("dumpsys activity activities | grep ResumedActivity"));
                    return;
                case "home":
                    System.out.println(rpc.exec("input keyevent 3"));
                    return;
                case "shell":
                    if (args.length < 3) {
                        throw new IllegalArgumentException("shell needs a command");
                    }
                    System.out.println(rpc.shell(join(args, 2), 60_000));
                    return;
                case "exec":
                    if (args.length < 3) {
                        throw new IllegalArgumentException("exec needs a command");
                    }
                    System.out.println(rpc.exec(join(args, 2)));
                    return;
                case "mode":
                    mode(rpc);
                    return;
                case "mode-set":
                    if (args.length < 3) {
                        throw new IllegalArgumentException("mode-set needs cust or serv");
                    }
                    System.out.println(rpc.setNetworkMode(args[2], 20_000));
                    System.out.println("now: " + rpc.networkMode());
                    return;
                case "techpack":
                    if (args.length < 3) {
                        throw new IllegalArgumentException("techpack needs a type string");
                    }
                    System.out.println(rpc.techPackUpdate(args[2]));
                    return;
                default:
                    usage();
                    System.exit(2);
            }
        } catch (Rpc.RpcException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("usage: cli <address> <command> [args]");
        System.out.println();
        System.out.println("  address   192.168.1.5  or  fe80::1%wlan0  or  [fe80::1%6]:50005");
        System.out.println();
        System.out.println("  probe            connect and show uid");
        System.out.println("  info             build, patch level, network mode");
        System.out.println("  battery [secs]   read CAN and decode the proven battery signals (default 8)");
        System.out.println("  can [secs]       count frames per CAN id (default 5)");
        System.out.println("  stream [secs]    live stream through the signal table (default 10)");
        System.out.println("  signals [secs]   same, then print every decoded signal");
        System.out.println("  faults [secs]    same, then print active and latched faults");
        System.out.println("  log [secs]       same, then print the frame log");
        System.out.println("  db               load the signal table, no scooter needed");
        System.out.println("  ident            vehicle manifest JSON");
        System.out.println("  provision        write the ADB key and mount it (reversible)");
        System.out.println("  unprovision [m]  undo it, restoring mode m (default cust)");
        System.out.println("  provision-status show the mount and the network mode");
        System.out.println("  settings         open Android Settings");
        System.out.println("  home             send HOME (back to the dash)");
        System.out.println("  shell <cmd>      run through sh -c, pipes allowed");
        System.out.println("  exec <cmd>       run exactly as typed, no shell");
        System.out.println("  mode             network mode and vehicle operation mode");
        System.out.println("  mode-set c|s     set persist.ola.security.scootermode");
        System.out.println("  techpack <type>  call TechPackUpdate with a type string");
    }

    private static void info(Rpc rpc) {
        String[][] checks = {
                {"android", "ro.build.version.release"},
                {"build", "ro.build.display.id"},
                {"patch", "ro.build.version.security_patch"},
                {"model", "ro.product.model"},
                {"mode", "persist.ola.security.scootermode"},
                {"selinux", "ro.boot.selinux"},
        };
        for (String[] c : checks) {
            System.out.printf("%-10s %s%n", c[0], orDash(rpc.getprop(c[1])));
        }
        System.out.printf("%-10s %s%n", "uptime", orDash(rpc.exec("uptime").message));
        try {
            System.out.printf("%-10s %s%n", "op mode", rpc.vehicleOperationMode());
        } catch (Rpc.RpcException e) {
            System.out.printf("%-10s %s%n", "op mode", "(" + e.getMessage() + ")");
        }
    }

    private static void mode(Rpc rpc) {
        System.out.println("network mode  " + orDash(rpc.networkMode()));
        System.out.println("vehicle mode  " + rpc.vehicleOperationMode());
    }

    private static void ident(Rpc rpc) {
        String json = rpc.vehicleIdentifiers();
        if (json == null || json.trim().isEmpty()) {
            System.out.println("the scooter returned an empty manifest");
            return;
        }
        System.out.println(json);
    }

    // ------------------------------------------------------------------ live

    private static void stream(Rpc rpc, int seconds, boolean printSignals) {
        Dbc dbc = loadDbc();
        Faults faults = loadFaults();
        Live live = new Live(dbc, faults);
        System.out.printf("table   %d messages, %d signals, %d faults%n",
                dbc.size(), dbc.signalCount(), faults.size());
        System.out.println("stream  ReadCanStream, " + seconds + " s, no deadline");
        System.out.println();

        Rpc.Cancel cancel = rpc.streamCanFrames(new Rpc.FrameSink() {
            @Override
            public void frame(Can.Frame frame) {
                live.onFrame(frame);
            }

            @Override
            public void ended(String reason) {
                System.out.println("stream ended: " + reason);
            }
        });

        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cancel.close();

        Live.Snapshot snap = live.snapshot();
        if (snap.empty()) {
            System.out.println("no frames. the vehicle is probably asleep —");
            System.out.println("press the red start button or plug in the charger.");
            return;
        }
        printSnapshot(snap);
        if (printSignals) {
            System.out.println();
            System.out.println("signals with a value:");
            Map<String, Double> values = live.values();
            int shown = 0;
            for (Map.Entry<String, Double> e : values.entrySet()) {
                if (Double.isNaN(e.getValue())) {
                    continue;
                }
                System.out.printf("  %-38s %s%n", e.getKey(), num(e.getValue(), 3));
                if (++shown >= 80) {
                    System.out.println("  ... " + (values.size() - shown) + " more");
                    break;
                }
            }
        }
    }

    private static void faults(Rpc rpc, int seconds) {
        Dbc dbc = loadDbc();
        Faults table = loadFaults();
        Live live = new Live(dbc, table);

        Rpc.Cancel cancel = rpc.streamCanFrames(new Rpc.FrameSink() {
            @Override
            public void frame(Can.Frame frame) {
                live.onFrame(frame);
            }

            @Override
            public void ended(String reason) {
                // quiet; the summary below says whether anything arrived
            }
        });
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cancel.close();

        if (!live.hasData()) {
            System.out.println("no frames, so no fault state");
            return;
        }
        List<Faults.Fault> active = live.activeFaults();
        List<Faults.Fault> latched = live.latchedFaults();
        System.out.println("active   " + active.size());
        for (Faults.Fault f : active) {
            System.out.printf("  %-9s %-8s %s%n", f.severity, f.component, f.title);
        }
        System.out.println("latched  " + latched.size());
        for (Faults.Fault f : latched) {
            if (active.contains(f)) {
                continue;
            }
            System.out.printf("  %-9s %-8s %s%n", f.severity, f.component, f.title);
        }
        System.out.println("table    " + table.size() + " known, "
                + table.fixable().size() + " marked fixable");
    }

    private static void log(Rpc rpc, int seconds) {
        Dbc dbc = loadDbc();
        Live live = new Live(dbc, loadFaults());
        Rpc.Cancel cancel = rpc.streamCanFrames(new Rpc.FrameSink() {
            @Override
            public void frame(Can.Frame frame) {
                live.onFrame(frame);
            }

            @Override
            public void ended(String reason) {
                // the log below is the point
            }
        });
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cancel.close();

        List<Live.Entry> entries = live.log();
        System.out.println("frames   " + entries.size());
        for (int i = 0; i < Math.min(entries.size(), 60); i++) {
            Live.Entry e = entries.get(i);
            System.out.printf("%8d  %s  %-34s %s%n", e.at % 100000, e.name, e.hex);
        }
    }

    private static void printSnapshot(Live.Snapshot s) {
        System.out.println("frames " + s.frames + "  pps " + s.pps
                + "  messages " + s.messagesSeen + "  signals " + s.signalsSeen);
        System.out.println();
        row("state of charge", pct(s.soc));
        row("state of health", pct(s.soh));
        row("pack voltage", num(s.packV, 2) + " V");
        row("pack current", num(s.packA, 2) + " A");
        row("power", num(s.powerW() / 1000.0, 2) + " kW");
        row("cell min/max", num(s.cellMinV, 3) + " / " + num(s.cellMaxV, 3) + " V");
        row("cell spread", num(s.cellSpreadV() * 1000.0, 0) + " mV");
        row("cells", s.cellCount() + " reported");
        row("pack temperature", num(s.packC, 1) + " C");
        row("MCU temperature", num(s.mcuC, 1) + " C");
        row("motor temperature", num(s.motorC, 1) + " C");
        row("speed", num(s.speedKph, 1) + " km/h");
        row("motor speed", num(s.rpm, 0) + " rpm");
        row("range", num(s.rangeKm, 0) + " km");
        row("odometer", num(s.odoKm, 0));
        row("drive mode", s.driveMode.isEmpty() ? "-" : s.driveMode);
        row("charging", tri(s.charging));
        row("charger plugged", tri(s.plugged));
        row("side stand up", tri(s.sideStandUp));
        row("seat unlocked", tri(s.seatUnlocked));
        row("unlocked", tri(s.unlocked));
        row("active faults", Integer.toString(s.activeFaults.size()));
    }

    private static void db() {
        Dbc dbc = loadDbc();
        Faults faults = loadFaults();
        System.out.println("messages " + dbc.size());
        System.out.println("signals  " + dbc.signalCount());
        System.out.println("faults   " + faults.size() + " (" + faults.fixable().size() + " fixable)");
        System.out.println();
        int shown = 0;
        for (Dbc.Message m : dbc.messages()) {
            System.out.printf("%-6s %-34s %d signals%n", m.hexId(), m.name, m.signals.length);
            if (++shown >= 25) {
                System.out.println("...");
                break;
            }
        }
    }

    // ------------------------------------------------------------- provision

    private static void provision(Rpc rpc) {
        String previous = rpc.networkMode();
        KeyPair pair = Provision.newKeyPair();
        String line = Provision.publicKeyLine((RSAPublicKey) pair.getPublic(), "olatool");
        System.out.println("previous mode  " + orDash(previous));
        System.out.println("key            " + line.substring(0, 24) + "... ("
                + line.length() + " chars)");
        System.out.println();
        System.out.println("private key, keep it if you want adb from this machine:");
        System.out.println(Provision.privateKeyPem(pair.getPrivate()));
        String out = rpc.runScript("olatool-adb.sh",
                Provision.provisionScript(line, previous), 60_000);
        System.out.println(out);
        System.out.println();
        System.out.println("now try:  adb connect <address>:5555");
    }

    private static void unprovision(Rpc rpc, String mode) {
        String out = rpc.runScript("olatool-adb-undo.sh",
                Provision.undoScript(mode), 30_000);
        System.out.println(out);
    }

    // ---------------------------------------------------------------- assets

    private static Dbc loadDbc() {
        try {
            return Dbc.parse(read(asset("can_database.json")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot find can_database.json: " + e.getMessage(), e);
        }
    }

    private static Faults loadFaults() {
        try {
            return Faults.parse(read(asset("faults.json")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot find faults.json: " + e.getMessage(), e);
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** Looks in the places the harness is usually run from. */
    private static Path asset(String name) throws IOException {
        Path[] candidates = {
                Paths.get("app", "src", "main", "assets", name),
                Paths.get("..", "app", "src", "main", "assets", name),
                Paths.get("ola-s1-rea", "app", "app", "src", "main", "assets", name),
                Paths.get("..", "..", "ola-s1-rea", "app", "app", "src", "main", "assets", name),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IOException(name + " not found from " + Paths.get(".").toAbsolutePath());
    }

    // ---------------------------------------------------------------- output

    private static int seconds(String[] args, int index, int fallback) {
        return args.length > index ? Integer.parseInt(args[index]) : fallback;
    }

    private static String cells(Can.Snapshot snap) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snap.cells.length; i++) {
            if (Double.isNaN(snap.cells[i]) || snap.cells[i] <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(i + 1).append(':').append((int) snap.cells[i]);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static void battery(Rpc rpc, int seconds) {
        System.out.println(rpc.initCan(Can.BATTERY_IDS));
        Can.Snapshot snap = new Can.Snapshot();
        int frames = 0;
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            byte[] raw = rpc.readCanFrame(1500);
            if (raw == null) {
                continue;
            }
            Can.Frame f = Can.Frame.parse(raw);
            if (f != null) {
                frames++;
                snap.apply(f);
            }
        }
        System.out.println();
        if (frames == 0) {
            System.out.println("no frames. the vehicle is probably asleep —");
            System.out.println("press the red start button or plug in the charger.");
            return;
        }
        System.out.println("frames " + frames + "  cells " + snap.cellCount());
        System.out.println();
        row("state of charge", pct(snap.soc));
        row("display SOC", pct(snap.displaySoc));
        row("state of health", pct(snap.soh) + "  " + snap.sohVerdict());
        row("pack voltage", num(snap.volts, 2) + " V");
        row("pack current", num(snap.amps, 2) + " A");
        row("power", num(snap.powerW() / 1000.0, 2) + " kW");
        row("pack temperature", num(snap.packTemp, 1) + " C");
        row("temperature min/max", num(snap.tempMin, 1) + " / " + num(snap.tempMax, 1) + " C");
        row("cell min/max", num(snap.cellMinMv, 0) + " / " + num(snap.cellMaxMv, 0) + " mV");
        row("cell spread", num(snap.cellSpreadMv(), 0) + " mV  " + snap.cellSpreadVerdict());
        row("MCU temperature", num(snap.mcuTemp, 1) + " C");
        row("motor temperature", num(snap.motorTemp, 1) + " C");
        row("motor speed", num(snap.rpm, 0) + " rpm");
        row("odometer", num(snap.odoMeters / 1000.0, 1) + " km");
        row("fall detection", snap.fallName());
        row("tow / theft", snap.towName());
        row("regen", snap.regenName());
        row("hill hold", snap.hillHoldName());
        row("cells", cells(snap));
    }

    private static void can(Rpc rpc, int seconds) {
        // Init takes a filter list; there is no "subscribe to everything".
        // The documented battery set is enough to see whether the bus is alive.
        System.out.println(rpc.initCan(Can.BATTERY_IDS));

        Map<Integer, Integer> counts = new LinkedHashMap<>();
        Map<Integer, String> sample = new LinkedHashMap<>();
        long end = System.currentTimeMillis() + seconds * 1000L;
        int total = 0;
        while (System.currentTimeMillis() < end) {
            byte[] raw = rpc.readCanFrame(1500);
            if (raw == null) {
                continue;
            }
            Can.Frame f = Can.Frame.parse(raw);
            if (f == null) {
                continue;
            }
            total++;
            Integer n = counts.get(f.id);
            counts.put(f.id, n == null ? 1 : n + 1);
            if (!sample.containsKey(f.id)) {
                sample.put(f.id, f.dataHex());
            }
        }
        System.out.println();
        System.out.println("frames=" + total + "  distinct_ids=" + counts.size());
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            System.out.printf("0x%03X  n=%-5d %s%n", e.getKey(), e.getValue(),
                    sample.get(e.getKey()));
        }
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static void row(String label, String value) {
        System.out.printf("%-20s %s%n", label, value);
    }

    private static String orDash(String s) {
        return s == null || s.trim().isEmpty() ? "-" : s.trim();
    }

    private static String tri(int value) {
        if (value < 0) {
            return "-";
        }
        return value == 1 ? "yes" : "no";
    }

    private static String num(double v, int decimals) {
        if (Double.isNaN(v)) {
            return "-";
        }
        return String.format("%." + decimals + "f", v);
    }

    private static String pct(double v) {
        if (Double.isNaN(v)) {
            return "-";
        }
        return String.format("%.1f %%", v);
    }
}
