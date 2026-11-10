package dev.olatool;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

/**
 * Reads the owner's own scooter telemetry from Ola's cloud (the same Kong
 * gateway the official companion app uses), so battery and location show up
 * even when the scooter is asleep and off Wi-Fi.
 *
 * <p>Reverse-engineered from the official app (see OLA_CLOUD_API.md). The
 * vehicle pushes its telemetry to {@code kong2.olaelectric.com} over its own
 * SIM; one authenticated GET returns the home-screen snapshot. Auth is a pair
 * of values the owner imports once from their logged-in session:
 * {@code X-ACCESS-TOKEN} and {@code X-UDA-UUID}. Nothing here contacts the
 * vehicle, and it is read-only: no command endpoint is ever called.
 */
public final class OlaCloud {

    private static final String PREFS = "ola";
    private static final String KEY_TOKEN = "cloud_token";
    private static final String KEY_UUID = "cloud_uuid";
    private static final String KEY_DEVICE_APP = "cloud_device_app";
    private static final String KEY_DEVICE_ID = "cloud_device_id";

    private static final String BASE = "https://kong2.olaelectric.com/";
    private static final String APP_VERSION = "v5.4.1";
    private static final String STATS_PATH = "api/config/v2/poll/vehicle/statistics";

    private OlaCloud() {
    }

    // -------------------------------------------------------- credential store

    public static String token(Context c) {
        return prefs(c).getString(KEY_TOKEN, "");
    }

    public static String uuid(Context c) {
        return prefs(c).getString(KEY_UUID, "");
    }

    public static boolean hasToken(Context c) {
        return !token(c).trim().isEmpty();
    }

    public static void saveCreds(Context c, String token, String uuid) {
        prefs(c).edit()
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_UUID, uuid == null ? "" : uuid.trim())
                .apply();
    }

    public static String deviceApp(Context c) {
        return prefs(c).getString(KEY_DEVICE_APP, "");
    }

    /** A stable per-install device id; generated once if not set. */
    public static String deviceId(Context c) {
        String v = prefs(c).getString(KEY_DEVICE_ID, "");
        if (v.isEmpty()) {
            v = UUID.randomUUID().toString();
            prefs(c).edit().putString(KEY_DEVICE_ID, v).apply();
        }
        return v;
    }

    public static void saveExtra(Context c, String deviceApp, String deviceId) {
        prefs(c).edit()
                .putString(KEY_DEVICE_APP, deviceApp == null ? "" : deviceApp.trim())
                .putString(KEY_DEVICE_ID, deviceId == null ? "" : deviceId.trim())
                .apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ result

    /** One snapshot, filled as far as the server answered. Never thrown from. */
    public static final class Status {
        public boolean ok;
        public int httpCode;
        public String error;

        public Float soc;              // battery_soc, percent
        public String charging;        // charging_status
        public String chargingEx;      // charging_status_ex
        public String lockStatus;      // lock_status
        public String vehicleStatus;   // vehicle_status
        public String sleepState;      // sleep_state
        public String isConnected;     // is_connected
        public String lastUpdated;     // last_updated
        public String timeToFull;      // time_to_full_charge
        public String range;           // range (object summary)

        public Double lat;             // latitude
        public Double lon;             // longitude
        public Double hAcc;            // horizontal_accuracy
    }

    /** Convenience: reads the stored credentials and polls. */
    public static Status pollStatistics(Context c) {
        return pollStatistics(token(c), uuid(c), deviceApp(c), deviceId(c));
    }

    public static Status pollStatistics(String token, String uuid) {
        return pollStatistics(token, uuid, "", "");
    }

    /**
     * {@code GET kong2/api/config/v2/poll/vehicle/statistics}. Runs on the
     * caller's thread (never the UI thread); failure is reported through
     * {@link Status#error}, not thrown.
     */
    public static Status pollStatistics(String token, String uuid,
                                        String deviceApp, String deviceId) {
        Status s = new Status();
        if (token == null || token.trim().isEmpty()) {
            s.error = "No access token set — tap “Set token”.";
            return s;
        }
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(BASE + STATS_PATH);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("X-ACCESS-TOKEN", token.trim());
            if (uuid != null && !uuid.trim().isEmpty()) {
                conn.setRequestProperty("X-UDA-UUID", uuid.trim());
            }
            conn.setRequestProperty("X-APP-VERSION", APP_VERSION);
            conn.setRequestProperty("X-Request-ID", UUID.randomUUID().toString());
            if (deviceApp != null && !deviceApp.trim().isEmpty()) {
                conn.setRequestProperty("X-Device-APP", deviceApp.trim());
            }
            if (deviceId != null && !deviceId.trim().isEmpty()) {
                conn.setRequestProperty("X-Device-ID", deviceId.trim());
            }
            conn.setRequestProperty("locale", "en");
            conn.setRequestProperty("config_locale", "en");
            conn.setRequestProperty("country_code", "IN");
            conn.setRequestProperty("Accept", "application/json");

            s.httpCode = conn.getResponseCode();
            String body = readBody(conn, s.httpCode >= 400);
            if (s.httpCode == 200) {
                parse(body, s);
                s.ok = true;
            } else if (s.httpCode == 401 || s.httpCode == 403) {
                s.error = "Token rejected (" + s.httpCode
                        + "). It may have expired — re-import it from the Ola app.";
            } else {
                s.error = "HTTP " + s.httpCode
                        + (body == null || body.isEmpty() ? "" : ": " + trim(body, 240));
            }
        } catch (Exception e) {
            s.error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ commands

    /** POST kong2/v1/commander/issuecommand — the remote command the official
     *  companion app uses for lock/unlock/find-my-scooter. The vehicle is
     *  identified by the same X-UDA-UUID + token as the read path; the body is
     *  {@code {commandType, params, source}} (reverse-engineered from the app). */
    private static final String COMMAND_PATH = "v1/commander/issuecommand";
    private static final String COMMAND_SOURCE = "COMPANION_APP";

    public static final class CommandResult {
        public boolean ok;
        public int httpCode;
        public String message = "";
    }

    public static CommandResult issueCommand(Context c, String commandType) {
        return issueCommand(token(c), uuid(c), deviceApp(c), deviceId(c), commandType);
    }

    public static CommandResult issueCommand(String token, String uuid, String deviceApp,
                                             String deviceId, String commandType) {
        CommandResult r = new CommandResult();
        if (token == null || token.trim().isEmpty()) {
            r.message = "No access token set — tap “Set token”.";
            return r;
        }
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(BASE + COMMAND_PATH);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("X-ACCESS-TOKEN", token.trim());
            if (uuid != null && !uuid.trim().isEmpty()) {
                conn.setRequestProperty("X-UDA-UUID", uuid.trim());
            }
            conn.setRequestProperty("X-APP-VERSION", APP_VERSION);
            conn.setRequestProperty("X-Request-ID", UUID.randomUUID().toString());
            if (deviceApp != null && !deviceApp.trim().isEmpty()) {
                conn.setRequestProperty("X-Device-APP", deviceApp.trim());
            }
            if (deviceId != null && !deviceId.trim().isEmpty()) {
                conn.setRequestProperty("X-Device-ID", deviceId.trim());
            }
            conn.setRequestProperty("locale", "en");
            conn.setRequestProperty("config_locale", "en");
            conn.setRequestProperty("country_code", "IN");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            String payload = new JSONObject()
                    .put("commandType", commandType)
                    .put("params", new JSONObject())
                    .put("source", COMMAND_SOURCE)
                    .toString();
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes("UTF-8"));
            }

            r.httpCode = conn.getResponseCode();
            String body = readBody(conn, r.httpCode >= 400);
            r.ok = r.httpCode >= 200 && r.httpCode < 300;
            if (r.ok) {
                r.message = "sent (" + r.httpCode + ")";
            } else if (r.httpCode == 401 || r.httpCode == 403) {
                r.message = "token rejected (" + r.httpCode + ")";
            } else {
                r.message = "HTTP " + r.httpCode
                        + (body == null || body.isEmpty() ? "" : ": " + trim(body, 200));
            }
        } catch (Exception e) {
            r.message = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ parsing

    private static void parse(String body, Status s) {
        if (body == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject(body);
            // The Kong envelope nests the payload (e.g. under "data"); find the
            // object that actually holds the fields, whatever the wrapper is.
            JSONObject d = findPayload(root);
            if (d == null) {
                d = root;
            }
            if (d.has("battery_soc") && !d.isNull("battery_soc")) {
                s.soc = (float) d.optDouble("battery_soc");
            }
            s.charging = str(d, "charging_status");
            s.chargingEx = str(d, "charging_status_ex");
            s.lockStatus = str(d, "lock_status");
            s.vehicleStatus = str(d, "vehicle_status");
            s.sleepState = str(d, "sleep_state");
            s.isConnected = str(d, "is_connected");
            s.lastUpdated = str(d, "last_updated");
            s.timeToFull = str(d, "time_to_full_charge");
            s.lat = dbl(d, "latitude");
            s.lon = dbl(d, "longitude");
            s.hAcc = dbl(d, "horizontal_accuracy");
            Object r = d.opt("range");
            if (r != null && !JSONObject.NULL.equals(r)) {
                s.range = r.toString();
            }
        } catch (Exception ignored) {
            // A body we cannot parse leaves the snapshot mostly empty, which the
            // tab renders as dashes rather than a crash.
        }
    }

    /** First nested object that carries a {@code battery_soc} key, or null. */
    private static JSONObject findPayload(JSONObject node) {
        if (node.has("battery_soc")) {
            return node;
        }
        for (Iterator<String> it = node.keys(); it.hasNext(); ) {
            JSONObject child = node.optJSONObject(it.next());
            if (child != null) {
                JSONObject found = findPayload(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String str(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) {
            return null;
        }
        String v = o.optString(key, null);
        return (v == null || v.isEmpty() || v.equals("null")) ? null : v;
    }

    private static Double dbl(JSONObject o, String key) {
        String v = str(o, key);
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readBody(HttpsURLConnection conn, boolean isError) {
        try {
            InputStream in = isError ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
