package dev.olatool;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Minimal ntfy.sh client — the rendezvous for Option A. The scooter POSTs its
 * location to a topic over its own internet; the phone reads the latest message
 * from the same topic. Both ends only make outbound HTTPS, so it works through
 * CGNAT and IPv4-only Wi-Fi alike.
 *
 * <p>Read-only here: the app only polls. Treat the topic name as a shared secret
 * — anyone who knows it can read the location.
 */
public final class Ntfy {

    private static final String BASE = "https://ntfy.sh/";

    private Ntfy() {
    }

    /** The latest message body on the topic, or null. Runs on the caller thread. */
    public static String latest(String topic) {
        if (topic == null || topic.trim().isEmpty()) {
            return null;
        }
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(BASE + topic.trim() + "/json?poll=1");
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/x-ndjson");
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            String latest = null;
            InputStream in = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JSONObject o = new JSONObject(line);
                    if ("message".equals(o.optString("event"))) {
                        String m = o.optString("message", null);
                        if (m != null && !m.isEmpty()) {
                            latest = m;   // events arrive oldest-first; keep the last
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            r.close();
            return latest;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
