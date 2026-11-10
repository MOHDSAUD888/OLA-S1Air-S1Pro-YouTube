package dev.olatool;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A local ring-buffer of cloud poll snapshots, saved to {@code cloud_history.json}
 * in the app's files dir. Feeds the battery sparkline and the last-seen trail.
 *
 * <p>Read-only: it only records what {@link OlaCloud} already fetched. Capped so
 * the file never grows without bound.
 */
public final class CloudHistory {

    private static final String FILE = "cloud_history.json";
    private static final int CAP = 720;          // ~4h at 20s, or days at coarser gaps
    private static final long MIN_GAP_MS = 60_000; // one sample a minute, at most

    /** Route tracking only keeps the last 24 h; older points are dropped. */
    private static final long WINDOW_MS = 24L * 3600_000L;
    /** A time gap longer than this starts a new ride (a new coloured route). */
    private static final long RIDE_GAP_MS = 10L * 60_000L;
    /** Points staying inside this radius count as "stopped" at one place. */
    private static final double STOP_RADIUS_M = 35.0;
    /** A cluster must last at least this long to be shown as a stop. */
    private static final long STOP_MIN_MS = 3L * 60_000L;

    /** One colour per ride, cycled. */
    private static final String[] RIDE_COLORS = {
            "#7AA2F7", "#9ECE6A", "#E0AF68", "#F7768E", "#BB9AF7", "#7DCFFF", "#FF9E64"
    };

    /** One recorded point. */
    public static final class Point {
        public long t;          // epoch millis (device clock)
        public float soc;       // battery percent, NaN if unknown
        public double lat = Double.NaN;
        public double lon = Double.NaN;
        public boolean charging;
    }

    private final File file;
    private JSONArray arr;

    public CloudHistory(Context c) {
        file = new File(c.getApplicationContext().getFilesDir(), FILE);
        load();
    }

    private void load() {
        try {
            if (file.exists()) {
                byte[] b = new byte[(int) file.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    int off = 0, n;
                    while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) {
                        off += n;
                    }
                }
                arr = new JSONArray(new String(b, StandardCharsets.UTF_8));
                purgeOld();
                return;
            }
        } catch (Exception ignored) {
        }
        arr = new JSONArray();
    }

    /** Records a poll if it carries a battery value and enough time passed. */
    public void record(OlaCloud.Status s) {
        if (s == null || !s.ok || s.soc == null) {
            return;
        }
        purgeOld();
        long now = System.currentTimeMillis();
        JSONObject last = arr.length() == 0 ? null : arr.optJSONObject(arr.length() - 1);
        if (last != null && now - last.optLong("t") < MIN_GAP_MS) {
            // Overwrite the most recent point instead of piling up.
            arr.remove(arr.length() - 1);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("t", now);
            o.put("soc", (double) s.soc);
            o.put("chg", isCharging(s));
            if (s.lat != null) o.put("lat", s.lat);
            if (s.lon != null) o.put("lon", s.lon);
            arr.put(o);
            while (arr.length() > CAP) {
                arr.remove(0);
            }
            save();
        } catch (Exception ignored) {
        }
    }

    /** Records a GPS-only point (e.g. from the local gRPC dumpsys read). */
    public void recordLocation(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return;
        }
        purgeOld();
        long now = System.currentTimeMillis();
        JSONObject last = arr.length() == 0 ? null : arr.optJSONObject(arr.length() - 1);
        if (last != null && now - last.optLong("t") < MIN_GAP_MS) {
            arr.remove(arr.length() - 1);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("t", now);
            o.put("lat", lat);
            o.put("lon", lon);
            arr.put(o);
            while (arr.length() > CAP) {
                arr.remove(0);
            }
            save();
        } catch (Exception ignored) {
        }
    }

    private static boolean isCharging(OlaCloud.Status s) {
        String c = s.charging != null ? s.charging : s.chargingEx;
        return c != null && c.toUpperCase().contains("CHARG")
                && !c.toUpperCase().contains("NOT");
    }

    private void save() {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Battery series, oldest first — for {@link Spark}. */
    public float[] socSeries() {
        float[] out = new float[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            out[i] = o == null ? Float.NaN : (float) o.optDouble("soc", Float.NaN);
        }
        return out;
    }

    public int size() {
        return arr.length();
    }

    /** Recorded GPS points (oldest first) as {lat, lon}, skipping blanks. */
    public double[][] locationPoints() {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            double lat = o.optDouble("lat", Double.NaN);
            double lon = o.optDouble("lon", Double.NaN);
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                out.add(new double[]{lat, lon});
            }
        }
        return out.toArray(new double[0][]);
    }

    /** Count of recorded points that carry a GPS fix. */
    public int locationCount() {
        return locationPoints().length;
    }

    public Point first() {
        return at(0);
    }

    public Point last() {
        return at(arr.length() - 1);
    }

    private Point at(int i) {
        JSONObject o = (i < 0 || i >= arr.length()) ? null : arr.optJSONObject(i);
        if (o == null) {
            return null;
        }
        Point p = new Point();
        p.t = o.optLong("t");
        p.soc = (float) o.optDouble("soc", Float.NaN);
        p.lat = o.optDouble("lat", Double.NaN);
        p.lon = o.optDouble("lon", Double.NaN);
        p.charging = o.optBoolean("chg", false);
        return p;
    }

    /** Net battery change over the recorded window, or NaN if too little data. */
    public float trendDelta() {
        if (arr.length() < 2) {
            return Float.NaN;
        }
        Point a = first();
        Point b = last();
        if (a == null || b == null || Float.isNaN(a.soc) || Float.isNaN(b.soc)) {
            return Float.NaN;
        }
        return b.soc - a.soc;
    }

    public void clear() {
        arr = new JSONArray();
        if (file.exists()) {
            file.delete();
        }
    }

    // ---------------------------------------------------------- pro tracking

    /** Drops points older than the 24 h window. Returns true if anything went. */
    public boolean purgeOld() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        boolean changed = false;
        while (arr.length() > 0) {
            JSONObject o = arr.optJSONObject(0);
            if (o != null && o.optLong("t") < cutoff) {
                arr.remove(0);
                changed = true;
            } else {
                break;
            }
        }
        if (changed) {
            save();
        }
        return changed;
    }

    /** GPS points inside the 24 h window, oldest first, each {t, lat, lon}. */
    private java.util.List<double[]> windowGps() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            double lat = o.optDouble("lat", Double.NaN);
            double lon = o.optDouble("lon", Double.NaN);
            long t = o.optLong("t");
            if (!Double.isNaN(lat) && !Double.isNaN(lon) && t >= cutoff) {
                out.add(new double[]{t, lat, lon});
            }
        }
        return out;
    }

    /** Splits the window's points into rides on a long time gap. */
    private java.util.List<java.util.List<double[]>> rides() {
        java.util.List<java.util.List<double[]>> rides = new java.util.ArrayList<>();
        java.util.List<double[]> cur = null;
        double[] prev = null;
        for (double[] p : windowGps()) {
            if (prev == null || p[0] - prev[0] > RIDE_GAP_MS) {
                cur = new java.util.ArrayList<>();
                rides.add(cur);
            }
            cur.add(p);
            prev = p;
        }
        return rides;
    }

    /** One route per ride, each with its own colour: {@code [{color,pts}]}. */
    public String routesJson() {
        JSONArray outer = new JSONArray();
        java.util.List<java.util.List<double[]>> rides = rides();
        for (int r = 0; r < rides.size(); r++) {
            java.util.List<double[]> ride = rides.get(r);
            if (ride.size() < 1) {
                continue;
            }
            try {
                JSONArray pts = new JSONArray();
                for (double[] p : ride) {
                    JSONArray ll = new JSONArray();
                    ll.put(p[1]);
                    ll.put(p[2]);
                    pts.put(ll);
                }
                JSONObject seg = new JSONObject();
                seg.put("color", RIDE_COLORS[r % RIDE_COLORS.length]);
                seg.put("pts", pts);
                outer.put(seg);
            } catch (Exception ignored) {
            }
        }
        return outer.toString();
    }

    /** Stops where the scooter stayed put: {@code [{lat,lon,ms,open}]}. */
    public String stopsJson() {
        JSONArray out = new JSONArray();
        java.util.List<double[]> pts = windowGps();
        int i = 0;
        while (i < pts.size()) {
            double[] anchor = pts.get(i);
            int j = i + 1;
            while (j < pts.size()
                    && haversine(anchor[1], anchor[2], pts.get(j)[1], pts.get(j)[2]) <= STOP_RADIUS_M) {
                j++;
            }
            long dwell = pts.get(j - 1)[0] >= anchor[0] ? (long) (pts.get(j - 1)[0] - anchor[0]) : 0;
            boolean open = j >= pts.size();     // still the latest position = ongoing stop
            if (dwell >= STOP_MIN_MS || (open && dwell > 0)) {
                try {
                    JSONObject s = new JSONObject();
                    s.put("lat", anchor[1]);
                    s.put("lon", anchor[2]);
                    s.put("ms", open ? System.currentTimeMillis() - anchor[0] : dwell);
                    s.put("open", open);
                    out.put(s);
                } catch (Exception ignored) {
                }
            }
            i = Math.max(j, i + 1);
        }
        return out.toString();
    }

    /** One ride summary row for the trips list. */
    public static final class Trip {
        public long start;
        public long end;
        public double km;
        public int fixes;
        public String color;
    }

    /** Per-ride stats, newest ride first, for the Trips panel. */
    public java.util.List<Trip> trips() {
        java.util.List<Trip> out = new java.util.ArrayList<>();
        java.util.List<java.util.List<double[]>> rides = rides();
        for (int r = 0; r < rides.size(); r++) {
            java.util.List<double[]> ride = rides.get(r);
            if (ride.isEmpty()) {
                continue;
            }
            Trip t = new Trip();
            t.start = (long) ride.get(0)[0];
            t.end = (long) ride.get(ride.size() - 1)[0];
            t.fixes = ride.size();
            t.color = RIDE_COLORS[r % RIDE_COLORS.length];
            double km = 0;
            for (int k = 1; k < ride.size(); k++) {
                km += haversine(ride.get(k - 1)[1], ride.get(k - 1)[2],
                        ride.get(k)[1], ride.get(k)[2]) / 1000.0;
            }
            t.km = km;
            out.add(t);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
