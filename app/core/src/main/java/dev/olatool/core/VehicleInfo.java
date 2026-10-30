package dev.olatool.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only facts about the scooter, gathered from calls the daemon already
 * exposes.
 *
 * <p>Every field is best-effort: a failed call leaves its field as the empty
 * string instead of failing the whole read. Nothing here writes to the
 * vehicle, pushes binaries, or prints private data.
 */
public final class VehicleInfo {

    private static final long READ_TIMEOUT_MS = 5_000;

    private static final String VARIANT_XML =
            "/data/data/com.olaelectric.vehiclerangeservice/shared_prefs/"
                    + "com.olaelectric.dataanalyser.xml";

    private static final String VARIANT_COMMAND = "cat " + VARIANT_XML;

    private static final String PROFILE_QUERY =
            "sqlite3 -json /data/data/com.olaelectric.profilemanagerservice/databases/"
                    + "profile-manager-database \"SELECT purchased_on, registration_number "
                    + "FROM vehicle_details\"";

    private static final Pattern VIN_JSON =
            Pattern.compile("\"VIN\"\\s*:\\s*\"([^\"]+)\"");

    private static final Pattern VARIANT_STRING = Pattern.compile(
            "<string\\s+name=\"([^\"]*VEHICLE_VARIANT[^\"]*)\"\\s*>([^<]*)</string>");

    private static final Pattern BATTERY =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*kwh", Pattern.CASE_INSENSITIVE);

    private static final Pattern GENERATION =
            Pattern.compile("gen\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern UPTIME_DAYS = Pattern.compile(
            "\\bup\\s+(\\d+)\\s+days?\\s*,?\\s*(\\d+):(\\d+)");

    private static final Pattern UPTIME_DAYS_ONLY =
            Pattern.compile("\\bup\\s+(\\d+)\\s+days?\\b");

    private static final Pattern UPTIME_CLOCK =
            Pattern.compile("\\bup\\s+(\\d+):(\\d+)");

    private static final Pattern UPTIME_MINUTES =
            Pattern.compile("\\bup\\s+(\\d+)\\s*min(?:ute)?s?\\b");

    private static final Pattern INDIAN_REG =
            Pattern.compile("^([A-Z]{2})(\\d{1,2})([A-Z]{1,3})(\\d{1,4})$");

    private static final Pattern BH_REG =
            Pattern.compile("^(\\d{2})BH(\\d{1,4})([A-Z]{1,2})$");

    public String vin = "";
    public String model = "";
    public String generation = "";
    public String batteryVariant = "";
    public String moveOs = "";
    public String uptime = "";
    public String registration = "";
    public String purchaseDate = "";
    public boolean loaded;

    public VehicleInfo() {
    }

    /**
     * Reads every field it can. A failure in one call leaves just that field
     * empty; the rest are still attempted. Returns an empty object for a null
     * {@code rpc}.
     */
    public static VehicleInfo load(Rpc rpc) {
        VehicleInfo info = new VehicleInfo();
        if (rpc == null) {
            return info;
        }

        try {
            info.vin = vinFromManifest(rpc.vehicleIdentifiers(READ_TIMEOUT_MS));
        } catch (RuntimeException ignored) {
            // leave empty
        }

        try {
            info.moveOs = swLine(rpc.swVersion(READ_TIMEOUT_MS));
        } catch (RuntimeException ignored) {
            // leave empty
        }

        try {
            info.uptime = uptimeHours(rpc.shell("uptime", READ_TIMEOUT_MS));
        } catch (RuntimeException ignored) {
            // leave empty
        }

        try {
            VehicleInfo variant = fromVariantXml(rpc.shell(VARIANT_COMMAND, READ_TIMEOUT_MS));
            info.model = variant.model;
            info.generation = variant.generation;
            info.batteryVariant = variant.batteryVariant;
        } catch (RuntimeException ignored) {
            // leave empty
        }

        try {
            String out = rpc.shell(PROFILE_QUERY, READ_TIMEOUT_MS);
            if (out != null && out.trim().startsWith("[")) {
                List<Object> rows = Json.list(Json.parse(out.trim()));
                if (!rows.isEmpty() && rows.get(0) instanceof Map) {
                    Map<String, Object> row = Json.cast(rows.get(0));
                    info.registration = formatRegistration(
                            Json.string(row, "registration_number", ""));
                    info.purchaseDate = Json.string(row, "purchased_on", "").trim();
                }
            }
        } catch (RuntimeException ignored) {
            // leave empty
        }

        info.loaded = !info.vin.isEmpty()
                || !info.model.isEmpty()
                || !info.generation.isEmpty()
                || !info.batteryVariant.isEmpty()
                || !info.moveOs.isEmpty()
                || !info.uptime.isEmpty()
                || !info.registration.isEmpty()
                || !info.purchaseDate.isEmpty();
        return info;
    }

    /** The VIN inside the manifest JSON, with a regex fallback. */
    public static String vinFromManifest(String manifestJson) {
        if (manifestJson == null || manifestJson.trim().isEmpty()) {
            return "";
        }
        try {
            Object parsed = Json.parse(manifestJson);
            if (parsed instanceof Map) {
                String vin = Json.string(parsed, "VIN", "").trim();
                if (!vin.isEmpty()) {
                    return vin;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the regex
        }
        try {
            Matcher m = VIN_JSON.matcher(manifestJson);
            if (m.find()) {
                return m.group(1).trim();
            }
        } catch (RuntimeException ignored) {
            // leave empty
        }
        return "";
    }

    /** The first non-blank line of a GetSwVersion response, as "MoveOS x.y.z". */
    public static String swLine(Msg.Response response) {
        if (response == null || response.message == null) {
            return "";
        }
        for (String part : response.message.split("\n")) {
            String line = part.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.regionMatches(true, 0, "MoveOS", 0, 6)) {
                return line;
            }
            return "MoveOS " + line;
        }
        return "";
    }

    /** Whole hours from {@code uptime}: "up 3 days, 4:05" becomes "76 hrs". */
    public static String uptimeHours(String uptimeOutput) {
        if (uptimeOutput == null) {
            return "";
        }
        try {
            Matcher days = UPTIME_DAYS.matcher(uptimeOutput);
            if (days.find()) {
                long hours = Long.parseLong(days.group(1)) * 24 + Long.parseLong(days.group(2));
                return hours + " hrs";
            }
            Matcher daysOnly = UPTIME_DAYS_ONLY.matcher(uptimeOutput);
            if (daysOnly.find()) {
                return (Long.parseLong(daysOnly.group(1)) * 24) + " hrs";
            }
            Matcher clock = UPTIME_CLOCK.matcher(uptimeOutput);
            if (clock.find()) {
                return Long.parseLong(clock.group(1)) + " hrs";
            }
            Matcher minutes = UPTIME_MINUTES.matcher(uptimeOutput);
            if (minutes.find()) {
                return "0 hrs";
            }
        } catch (RuntimeException ignored) {
            // leave empty
        }
        return "";
    }

    /**
     * Formats an Indian or Bharat-series registration; anything else comes back
     * trimmed but otherwise untouched.
     */
    public static String formatRegistration(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String compact;
        try {
            compact = trimmed.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return trimmed;
        }
        if (compact.isEmpty() || "N/A".equalsIgnoreCase(compact)) {
            return "";
        }
        try {
            Matcher indian = INDIAN_REG.matcher(compact);
            if (indian.matches()) {
                return indian.group(1) + " " + pad(indian.group(2), 2) + " "
                        + indian.group(3) + " " + pad(indian.group(4), 4);
            }
            Matcher bh = BH_REG.matcher(compact);
            if (bh.matches()) {
                return bh.group(1) + " BH " + pad(bh.group(2), 4) + " " + bh.group(3);
            }
        } catch (RuntimeException ignored) {
            // fall through to the trimmed input
        }
        return trimmed;
    }

    /**
     * Reads model, generation and battery from the dataanalyser shared-prefs
     * XML, with a JSON/key fallback when the variant key is absent.
     */
    public static VehicleInfo fromVariantXml(String xml) {
        VehicleInfo info = new VehicleInfo();
        if (xml == null || xml.trim().isEmpty()) {
            return info;
        }
        try {
            String text = unescape(xml);
            Matcher variant = VARIANT_STRING.matcher(text);
            String raw = variant.find() ? variant.group(2).trim() : "";
            if (!raw.isEmpty()) {
                String cleaned = raw.replace('_', ' ').replaceAll("\\s+", " ").trim();
                info.batteryVariant = batteryOf(cleaned);
                info.generation = generationOf(cleaned);
                info.model = modelOf(cleaned);
            } else {
                info.model = lookup(text, "model");
                info.generation = lookup(text, "generation");
                info.batteryVariant = lookup(text, "batteryVariant");
            }
        } catch (RuntimeException ignored) {
            // return whatever was filled before the failure
        }
        return info;
    }

    // ---------------------------------------------------------------- helpers

    private static String batteryOf(String cleaned) {
        Matcher m = BATTERY.matcher(cleaned);
        return m.find() ? m.group(1) + " kWh" : "";
    }

    private static String generationOf(String cleaned) {
        Matcher m = GENERATION.matcher(cleaned);
        return m.find() ? "Gen " + m.group(1) : "Gen 1";
    }

    private static String modelOf(String cleaned) {
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.contains("s1 pro")) {
            return "S1 Pro";
        }
        if (lower.contains("s1 air")) {
            return "S1 Air";
        }
        if (lower.contains("s1 x")) {
            return "S1 X";
        }
        if (lower.contains("s1")) {
            return "S1";
        }
        return cleaned;
    }

    private static String lookup(String text, String key) {
        Matcher xml = Pattern.compile(
                "<string\\s+name=\"[^\"]*" + Pattern.quote(key) + "[^\"]*\"\\s*>([^<]*)</string>",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (xml.find()) {
            String value = xml.group(1).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        Matcher json = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (json.find()) {
            return json.group(1).trim();
        }
        return "";
    }

    private static String unescape(String xml) {
        return xml.replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String pad(String digits, int width) {
        StringBuilder sb = new StringBuilder(digits);
        while (sb.length() < width) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }
}
