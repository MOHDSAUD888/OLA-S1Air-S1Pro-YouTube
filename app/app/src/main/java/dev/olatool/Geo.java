package dev.olatool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Best-effort latitude/longitude extraction from an opaque telemetry payload
 * (JSON or binary/protobuf). The MQTT packet format is not fully known, so this
 * tries several encodings and returns the first plausible pair. The raw packet
 * stream is also shown, so a wrong guess is easy to spot and refine.
 */
public final class Geo {

    private Geo() {
    }

    /** Returns {lat, lon} or null. */
    public static double[] extract(byte[] p) {
        if (p == null || p.length < 8) {
            return null;
        }
        double[] j = fromJson(p);
        if (j != null) {
            return j;
        }
        double[] si = fromScaledInts(p);
        if (si != null) {
            return si;
        }
        double[] d = fromDoubles(p);
        if (d != null) {
            return d;
        }
        return fromFloats(p);
    }

    // ------------------------------------------------------------------ JSON

    private static double[] fromJson(byte[] p) {
        String s;
        try {
            s = new String(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        if (s.indexOf('{') < 0 || (s.toLowerCase().indexOf("lat") < 0)) {
            return null;
        }
        try {
            int start = s.indexOf('{');
            JSONObject o = new JSONObject(s.substring(start));
            double[] out = new double[]{Double.NaN, Double.NaN};
            search(o, out);
            if (plausible(out[0], out[1])) {
                return out;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void search(Object node, double[] out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = o.opt(k);
                String kl = k.toLowerCase();
                if (v instanceof Number) {
                    double d = ((Number) v).doubleValue();
                    if (Double.isNaN(out[0]) && (kl.equals("lat") || kl.equals("latitude")
                            || kl.endsWith("_lat") || kl.endsWith("latitude"))) {
                        out[0] = d;
                    } else if (Double.isNaN(out[1]) && (kl.equals("lon") || kl.equals("lng")
                            || kl.equals("longitude") || kl.endsWith("_lon") || kl.endsWith("longitude"))) {
                        out[1] = d;
                    }
                } else {
                    search(v, out);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                search(a.opt(i), out);
            }
        }
    }

    // ------------------------------------------------- scaled integers (x1e7/1e6)

    private static double[] fromScaledInts(byte[] p) {
        double[] scales = {1e7, 1e6};
        for (double scale : scales) {
            for (int off = 0; off + 8 <= p.length; off++) {
                for (boolean be : new boolean[]{true, false}) {
                    double lat = i32(p, off, be) / scale;
                    double lon = i32(p, off + 4, be) / scale;
                    if (strongPair(lat, lon)) {
                        return new double[]{lat, lon};
                    }
                }
            }
        }
        return null;
    }

    private static double[] fromDoubles(byte[] p) {
        for (int off = 0; off + 16 <= p.length; off++) {
            for (boolean be : new boolean[]{true, false}) {
                double lat = Double.longBitsToDouble(i64(p, off, be));
                double lon = Double.longBitsToDouble(i64(p, off + 8, be));
                if (strongPair(lat, lon)) {
                    return new double[]{lat, lon};
                }
            }
        }
        return null;
    }

    private static double[] fromFloats(byte[] p) {
        for (int off = 0; off + 8 <= p.length; off++) {
            for (boolean be : new boolean[]{true, false}) {
                double lat = Float.intBitsToFloat((int) i32(p, off, be));
                double lon = Float.intBitsToFloat((int) i32(p, off + 4, be));
                if (strongPair(lat, lon)) {
                    return new double[]{lat, lon};
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static long i32(byte[] p, int off, boolean be) {
        if (be) {
            return ((long) (p[off] & 0xFF) << 24) | ((p[off + 1] & 0xFF) << 16)
                    | ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
        }
        return ((long) (p[off + 3] & 0xFF) << 24) | ((p[off + 2] & 0xFF) << 16)
                | ((p[off + 1] & 0xFF) << 8) | (p[off] & 0xFF);
    }

    private static long i64(byte[] p, int off, boolean be) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int b = p[off + (be ? i : 7 - i)] & 0xFF;
            v = (v << 8) | b;
        }
        return v;
    }

    private static boolean plausible(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                && (Math.abs(lat) > 0.01 || Math.abs(lon) > 0.01);
    }

    /** Stricter test for binary scans, to cut false positives. */
    private static boolean strongPair(double lat, double lon) {
        if (!plausible(lat, lon)) {
            return false;
        }
        // Real coordinates have a fractional part and non-trivial magnitude.
        boolean latFrac = Math.abs(lat - Math.rint(lat)) > 1e-6;
        boolean lonFrac = Math.abs(lon - Math.rint(lon)) > 1e-6;
        return latFrac && lonFrac && Math.abs(lat) >= 1.0 && Math.abs(lon) >= 1.0;
    }
}
