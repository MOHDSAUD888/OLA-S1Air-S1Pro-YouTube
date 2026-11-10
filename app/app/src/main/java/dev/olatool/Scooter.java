package dev.olatool;

import java.util.ArrayList;
import java.util.List;

import dev.olatool.core.Json;

/**
 * One saved scooter in the fleet: a user label and the address to reach it.
 *
 * <p>The address is whatever the owner would type on the single-unit screen —
 * an IPv4 like {@code 192.168.0.238} or an IPv6 literal. {@link Net#connect}
 * turns it into a working link exactly as it does for the main Connect button,
 * so a fleet entry and a manual connect behave the same.
 *
 * <p>This list only ever holds units the owner added by hand or confirmed from
 * a scan. Nothing is added silently, which keeps the tool to its own-vehicle
 * scope.
 */
public final class Scooter {

    /** A unit reached over the local network (IPv4/IPv6 in {@link #address}). */
    public static final String SOURCE_LOCAL = "local";
    /** A unit read from Ola's cloud with the owner's token; {@link #address} holds its uuid. */
    public static final String SOURCE_CLOUD = "cloud";

    public final String id;
    public String name;
    public String address;
    /** Where this unit is read from: {@link #SOURCE_LOCAL} or {@link #SOURCE_CLOUD}. */
    public final String source;

    public Scooter(String id, String name, String address) {
        this(id, name, address, SOURCE_LOCAL);
    }

    public Scooter(String id, String name, String address, String source) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.address = address == null ? "" : address.trim();
        this.source = SOURCE_CLOUD.equals(source) ? SOURCE_CLOUD : SOURCE_LOCAL;
    }

    public boolean isCloud() {
        return SOURCE_CLOUD.equals(source);
    }

    static Scooter create(String name, String address) {
        return new Scooter(newId(), name, address, SOURCE_LOCAL);
    }

    /** A cloud-backed unit identified by its account/vehicle {@code uuid}. */
    static Scooter createCloud(String name, String uuid) {
        return new Scooter(newId(), name, uuid, SOURCE_CLOUD);
    }

    private static String newId() {
        return Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString((int) (Math.random() * 1296), 36);
    }

    // ------------------------------------------------------------------ json

    /** Serialises the whole list to a JSON array string for SharedPreferences. */
    static String toJson(List<Scooter> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Scooter s = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"id\":\"").append(esc(s.id)).append("\",")
                    .append("\"name\":\"").append(esc(s.name)).append("\",")
                    .append("\"address\":\"").append(esc(s.address)).append("\",")
                    .append("\"source\":\"").append(esc(s.source)).append("\"}");
        }
        return sb.append(']').toString();
    }

    /** Parses what {@link #toJson} wrote. Returns an empty list on any problem. */
    static List<Scooter> fromJson(String text) {
        List<Scooter> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        try {
            for (Object o : Json.list(Json.parse(text))) {
                if (!(o instanceof java.util.Map)) {
                    continue;
                }
                String id = Json.string(o, "id", "");
                String name = Json.string(o, "name", "");
                String address = Json.string(o, "address", "");
                String source = Json.string(o, "source", SOURCE_LOCAL);
                if (!address.isEmpty()) {
                    if (id.isEmpty()) {
                        id = newId();
                    }
                    out.add(new Scooter(id, name, address, source));
                }
            }
        } catch (RuntimeException ignored) {
            // A corrupt preference should not crash the app; start empty.
        }
        return out;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
