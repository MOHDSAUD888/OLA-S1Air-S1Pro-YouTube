package dev.olatool;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dev.olatool.core.Addr;
import dev.olatool.core.Rpc;

/**
 * Turns the IPv4 address the user typed into a working IPv6 connection.
 *
 * <p>Why bother: the scooter's own init script drops TCP 50005 and 5555 over
 * IPv4 when it is in customer mode (docs/03-adb-and-full-access.md). That rule
 * is IPv4 only, so IPv6 keeps working in every mode. A session that starts on
 * IPv4 therefore dies the moment the user switches to Customer, which is
 * exactly when this app is most useful.
 *
 * <p>There are three ways to learn the scooter's IPv6 address, tried in order:
 *
 * <ol>
 *   <li><b>We are the scooter.</b> The typed IPv4 belongs to one of this
 *       device's own interfaces, so dial this device's own link-local address.
 *       (This is the case when the APK is installed on the dash itself.)</li>
 *   <li><b>We are on the scooter's own access point.</b> The scooter derives
 *       its link-local address from its MAC with EUI-64, and Android reports
 *       that MAC as the BSSID of the network we joined.</li>
 *   <li><b>We are on a normal network the scooter also joined.</b> The BSSID is
 *       then the router's, so instead read the scooter's MAC out of the ARP
 *       table for the IPv4 we typed, and run EUI-64 on that.</li>
 * </ol>
 *
 * <p>If all three fail the app falls back to plain IPv4 and says so. Mode
 * switching is disabled on that path, because switching to Customer would cut
 * the connection the app is using.
 */
public final class Net {

    private Net() {
    }

    public static final class Result {
        public final Rpc rpc;
        public final Addr.Target target;
        public final boolean ipv6;
        public final String note;

        Result(Rpc rpc, Addr.Target target, boolean ipv6, String note) {
            this.rpc = rpc;
            this.target = target;
            this.ipv6 = ipv6;
            this.note = note;
        }
    }

    public static Result connect(Context context, String typed, long timeoutMs) {
        String input = typed == null ? "" : typed.trim();
        if (input.isEmpty()) {
            throw new IllegalArgumentException("Type the scooter's IPv4 address first.");
        }

        // Escape hatch for testing: an IPv6 literal is accepted as typed.
        if (input.contains(":")) {
            Addr.Target target = Addr.parse(input, Rpc.DEFAULT_PORT);
            return new Result(Rpc.open(target, timeoutMs), target, true, "IPv6 as typed");
        }

        if (!Addr.isIpv4(input)) {
            throw new IllegalArgumentException("That is not an IPv4 address: " + input);
        }

        LinkProperties lp = linkProperties(context);
        String ifName = lp == null ? null : lp.getInterfaceName();
        boolean onSubnet = onSameSubnet(lp, input);

        List<String> tried = new ArrayList<>();
        String failure = null;

        // 1. Running on the scooter itself: the typed address is our own, so
        //    there is no network hop at all. ::1 rather than 127.0.0.1, because
        //    customer mode drops port 50005 over IPv4 and IPv6 is never filtered.
        if (isOwnIpv4(input)) {
            Addr.Target target = new Addr.Target("::1", Rpc.DEFAULT_PORT, true);
            try {
                Rpc rpc = Rpc.open(target, timeoutMs);
                return new Result(rpc, target, true,
                        "running on the scooter — dialled its own loopback");
            } catch (RuntimeException e) {
                tried.add("::1");
                failure = e.getMessage();
            }
        }

        // 2. Joined to the scooter's own access point: BSSID is the scooter.
        String bssid = bssid(context);
        String fromBssid = Addr.scopedLinkLocal(bssid, ifName);
        if (fromBssid != null && !tried.contains(fromBssid)) {
            Addr.Target target = new Addr.Target(fromBssid, Rpc.DEFAULT_PORT, true);
            try {
                Rpc rpc = Rpc.open(target, timeoutMs);
                String note = onSubnet ? "derived from the Wi-Fi BSSID"
                        : "derived from the Wi-Fi BSSID (the IPv4 you typed is on another subnet)";
                return new Result(rpc, target, true, note);
            } catch (RuntimeException e) {
                tried.add(fromBssid);
                failure = e.getMessage();
            }
        }

        // 3. Plain network: look the scooter's MAC up in the ARP table.
        String fromArp = Addr.scopedLinkLocal(arpMac(input, ifName), ifName);
        if (fromArp != null && !tried.contains(fromArp)) {
            Addr.Target target = new Addr.Target(fromArp, Rpc.DEFAULT_PORT, true);
            try {
                Rpc rpc = Rpc.open(target, timeoutMs);
                return new Result(rpc, target, true, "derived from the ARP entry for " + input);
            } catch (RuntimeException e) {
                tried.add(fromArp);
                failure = e.getMessage();
            }
        }

        // 4. Fallback: plain IPv4. Works while the scooter is not in customer mode.
        Addr.Target target = new Addr.Target(input, Rpc.DEFAULT_PORT, false);
        try {
            Rpc rpc = Rpc.open(target, timeoutMs);
            return new Result(rpc, target, false,
                    "IPv4 fallback — mode switching is disabled on this path");
        } catch (RuntimeException e) {
            StringBuilder hint = new StringBuilder();
            if (tried.isEmpty()) {
                hint.append("Could not work out an IPv6 address for it: ")
                        .append(bssid == null ? "no BSSID and no ARP entry" : "no ARP entry")
                        .append(".");
            } else {
                hint.append("Tried ");
                for (int i = 0; i < tried.size(); i++) {
                    hint.append(i == 0 ? "" : ", ").append(tried.get(i));
                }
                hint.append('.');
            }
            throw new IllegalStateException(
                    "Cannot reach the scooter.\n" + hint + "\n"
                            + "Is it awake, and are you on its hotspot or the same Wi-Fi?\n"
                            + (failure == null ? "" : failure), e);
        }
    }

    // ------------------------------------------------------------- discovery

    /**
     * True when {@code typed} is one of this device's own IPv4 addresses, which
     * means the scooter we are talking to is the device running this app.
     */
    public static boolean isOwnIpv4(String typed) {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && typed.equals(a.getHostAddress())) {
                        return true;
                    }
                }
            }
        } catch (SocketException ignored) {
            // No interfaces readable: fall through to the other paths.
        }
        return false;
    }

    /** The MAC the kernel has for {@code ipv4}, or null. */
    private static String arpMac(String ipv4, String ifName) {
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 6 || !ipv4.equals(f[0])) {
                    continue;
                }
                // Flags 0x2 means the entry is complete; 0x0 is a placeholder.
                if (!"0x2".equalsIgnoreCase(f[2])) {
                    continue;
                }
                if (ifName != null && !ifName.isEmpty() && !ifName.equals(f[5])) {
                    continue;
                }
                String mac = f[3].toUpperCase(Locale.ROOT);
                if (Addr.isMac(mac) && !"00:00:00:00:00:00".equals(mac)) {
                    return mac;
                }
            }
        } catch (IOException ignored) {
            // Android 10 and later restrict parts of /proc/net on some builds.
        }
        return null;
    }

    // ------------------------------------------------------------- utilities

    private static LinkProperties linkProperties(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        Network network = cm.getActiveNetwork();
        return network == null ? null : cm.getLinkProperties(network);
    }

    /** True when the typed address shares a subnet with one of our own. */
    private static boolean onSameSubnet(LinkProperties lp, String typed) {
        if (lp == null) {
            return false;
        }
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet4Address) {
                if (Addr.sameIpv4Subnet(a.getHostAddress(), typed, la.getPrefixLength())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The MAC of the access point we are joined to, or null. */
    @SuppressWarnings("deprecation")
    private static String bssid(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return null;
            }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                return null;
            }
            String mac = info.getBSSID();
            return Addr.isMac(mac) ? mac : null;
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Our own IPv4 on the current network, for the status line. */
    public static String ourIpv4(Context context) {
        LinkProperties lp = linkProperties(context);
        if (lp == null) {
            return "";
        }
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet4Address) {
                return a.getHostAddress();
            }
        }
        return "";
    }
}
