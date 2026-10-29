package dev.olatool.core;

/**
 * Two tiny encoders. Both are here instead of in the platform libraries so the
 * same code runs on the desktop harness and on Android 7, where
 * {@code java.util.Base64} does not exist yet.
 */
public final class Codec {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Codec() {
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
        }
        return sb.toString();
    }

    public static String hex(String text) {
        return hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static byte[] unhex(String text) {
        if (text.length() % 2 != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Standard base64 with padding, i.e. what {@code base64 -d} expects. */
    public static String base64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            int n = (b0 << 16) | (b1 << 8) | b2;
            sb.append(B64[(n >>> 18) & 0x3F]);
            sb.append(B64[(n >>> 12) & 0x3F]);
            sb.append(i + 1 < data.length ? B64[(n >>> 6) & 0x3F] : '=');
            sb.append(i + 2 < data.length ? B64[n & 0x3F] : '=');
        }
        return sb.toString();
    }
}
