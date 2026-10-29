package dev.olatool.core;

/**
 * Address handling.
 *
 * <p>The app only ever asks for the scooter's IPv4 address. The scooter's own
 * firewall blocks TCP 50005 and 5555 over IPv4 in customer mode (see
 * docs/03-adb-and-full-access.md), so the real connection has to be IPv6. The
 * IPv6 address is derived from the access point's MAC with EUI-64: a Wi-Fi AP
 * built by Android puts its own MAC into the low 64 bits of its link-local
 * address, so {@code fe80::} + EUI-64 of the BSSID is the address to dial.
 *
 * <p>That relation is exact, not a guess. Measured on the scooter:
 * {@code 3a:2b:7c:06:5d:a8} is {@code fe80::382b:7cff:fe06:5da8}.
 */
public final class Addr {

    private Addr() {
    }

    /** A place to connect to. {@code host} may carry a {@code %scope} suffix. */
    public static final class Target {
        public final String host;
        public final int port;
        public final boolean ipv6;

        public Target(String host, int port, boolean ipv6) {
            this.host = host;
            this.port = port;
            this.ipv6 = ipv6;
        }

        public boolean isLinkLocal() {
            return ipv6 && host.toLowerCase().startsWith("fe80");
        }

        @Override
        public String toString() {
            return ipv6 ? "[" + host + "]:" + port : host + ":" + port;
        }
    }

    /**
     * Normalises whatever the user typed into a host and a port. Accepts
     * {@code 192.168.1.5}, {@code fe80::1}, {@code fe80::1%wlan0},
     * {@code [fe80::1]:50005} and {@code ipv6:fe80::1}.
     */
    public static Target parse(String input, int defaultPort) {
        if (input == null) {
            throw new IllegalArgumentException("no address");
        }
        String s = input.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("no address");
        }
        if (s.regionMatches(true, 0, "ipv6:", 0, 5)) {
            s = s.substring(5).trim();
        }

        int port = defaultPort;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("unclosed [");
            }
            String rest = s.substring(end + 1).trim();
            s = s.substring(1, end);
            if (rest.startsWith(":")) {
                port = parsePort(rest.substring(1));
            }
        } else if (s.chars().filter(c -> c == ':').count() == 1 && s.indexOf(':') == s.lastIndexOf(':')) {
            int colon = s.indexOf(':');
            port = parsePort(s.substring(colon + 1));
            s = s.substring(0, colon);
        }

        boolean ipv6 = s.chars().filter(c -> c == ':').count() >= 2;
        return new Target(s, port, ipv6);
    }

    private static int parsePort(String text) {
        try {
            int p = Integer.parseInt(text.trim());
            if (p < 1 || p > 65535) {
                throw new IllegalArgumentException("port out of range: " + text);
            }
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad port: " + text);
        }
    }

    /** True for a dotted-quad IPv4 literal. */
    public static boolean isIpv4(String text) {
        if (text == null) {
            return false;
        }
        String s = text.trim();
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    /** True for a MAC in colon, dash or bare form. */
    public static boolean isMac(String text) {
        return macBytes(text) != null;
    }

    private static byte[] macBytes(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim().replace("-", ":").toLowerCase();
        if (s.equals("02:00:00:00:00:00") || s.isEmpty()) {
            return null;   // Android's placeholder for "redacted"
        }
        String[] parts = s.split(":", -1);
        if (parts.length != 6) {
            return null;
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            if (parts[i].length() != 2) {
                return null;
            }
            try {
                out[i] = (byte) Integer.parseInt(parts[i], 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /**
     * EUI-64 of a MAC, as a bare IPv6 literal: flip the universal/local bit of
     * the first byte, splice {@code ff:fe} into the middle.
     *
     * @return {@code fe80::382b:7cff:fe06:5da8}, or null if the MAC is not a
     *         usable unicast address
     */
    public static String linkLocalFromMac(String mac) {
        byte[] b = macBytes(mac);
        if (b == null) {
            return null;
        }
        if ((b[0] & 0x01) != 0) {
            return null;   // multicast / broadcast group address
        }
        int[] o = new int[8];
        o[0] = (b[0] ^ 0x02) & 0xFF;
        o[1] = b[1] & 0xFF;
        o[2] = b[2] & 0xFF;
        o[3] = 0xFF;
        o[4] = 0xFE;
        o[5] = b[3] & 0xFF;
        o[6] = b[4] & 0xFF;
        o[7] = b[5] & 0xFF;

        StringBuilder sb = new StringBuilder("fe80::");
        for (int i = 0; i < 8; i += 2) {
            int group = (o[i] << 8) | o[i + 1];
            if (i > 0) {
                sb.append(':');
            }
            sb.append(Integer.toHexString(group));
        }
        return sb.toString();
    }

    /**
     * The address to dial for an access point whose MAC is {@code bssid}, seen
     * through interface {@code ifName}. The scope id is required: a link-local
     * address is only meaningful on one interface.
     */
    public static String scopedLinkLocal(String bssid, String ifName) {
        String ll = linkLocalFromMac(bssid);
        if (ll == null) {
            return null;
        }
        if (ifName == null || ifName.isEmpty()) {
            return ll;
        }
        return ll + "%" + ifName;
    }

    /** Compares the first {@code prefixBits} bits of two IPv4 literals. */
    public static boolean sameIpv4Subnet(String a, String b, int prefixBits) {
        if (!isIpv4(a) || !isIpv4(b)) {
            return false;
        }
        long na = ipv4ToLong(a);
        long nb = ipv4ToLong(b);
        int shift = 32 - Math.max(0, Math.min(32, prefixBits));
        return (na >>> shift) == (nb >>> shift);
    }

    public static long ipv4ToLong(String text) {
        String[] parts = text.trim().split("\\.");
        long v = 0;
        for (String p : parts) {
            v = (v << 8) | (Integer.parseInt(p) & 0xFF);
        }
        return v;
    }

    /** "192.168.1.5" -> "192.168.85." ; null when it is not IPv4. */
    public static String ipv4Prefix24(String text) {
        if (!isIpv4(text)) {
            return null;
        }
        return text.trim().substring(0, text.trim().lastIndexOf('.') + 1);
    }
}
