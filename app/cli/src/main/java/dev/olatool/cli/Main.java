package dev.olatool.cli;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.olatool.core.Addr;
import dev.olatool.core.Can;
import dev.olatool.core.Msg;
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
 *   cli 192.168.1.5 shell "dumpsys activity activities | head -20"
 *   cli 192.168.1.5 mode
 *   cli 192.168.1.5 techpack techpack_on
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        String where = args[0];
        String what = args[1];

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
        System.out.println("  battery [secs]   read CAN and decode the pack (default 8)");
        System.out.println("  can [secs]       count frames per CAN id (default 5)");
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
