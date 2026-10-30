package dev.olatool.core;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Wireless ADB, set up over the root gRPC shell instead of Shizuku.
 *
 * <p>The scooter only trusts keys listed in {@code /adb_keys}. This class makes
 * an RSA key, writes the public half in the format adbd reads, bind-mounts the
 * file over {@code /adb_keys}, and flips the network mode to {@code mfg} so
 * adbd accepts a TCP connection. Everything it does is reversible and none of
 * it touches a partition: the key lives in {@code /data/local/tmp} and the
 * mount is a bind mount that disappears on reboot.
 *
 * <p>The private key never leaves the phone. It is stored by the caller in the
 * app's own preferences; this class only formats it.
 */
public final class Provision {

    /** Where the public key is staged on the scooter. */
    public static final String KEY_FILE = "/data/local/tmp/olatool_adb_keys";

    private Provision() {
    }

    // ------------------------------------------------------------------ keys

    /** A fresh 2048-bit RSA key, which is what adbd's legacy auth requires. */
    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("this device cannot make an RSA key", e);
        }
    }

    public static String privateKeyPem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Codec.base64(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    public static String publicKeyPem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Codec.base64(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    public static PrivateKey parsePrivateKey(String pem) throws Exception {
        byte[] der = Codec.unbase64(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static PublicKey parsePublicKey(String pem) throws Exception {
        byte[] der = Codec.unbase64(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * The line adbd wants in {@code /adb_keys}: base64 of a 524-byte
     * {@code RSAPublicKey} struct, then an optional comment.
     *
     * <p>Layout, all little-endian: 32-bit word count (64), 32-bit
     * {@code n0inv}, 256-byte modulus, 256-byte {@code rr = 2^4096 mod n},
     * 32-bit exponent.
     */
    public static String publicKeyLine(RSAPublicKey key, String comment) {
        BigInteger n = key.getModulus();
        BigInteger e = key.getPublicExponent();
        if (n.bitLength() != 2048) {
            throw new IllegalArgumentException("adbd needs a 2048-bit key");
        }

        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = n.and(two32.subtract(BigInteger.ONE));
        BigInteger n0inv = n0.modInverse(two32).negate().mod(two32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);

        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64);
        buffer.putInt(n0inv.intValue());
        buffer.put(fixedLe(n, 256));
        buffer.put(fixedLe(rr, 256));
        buffer.putInt(e.intValue());

        String encoded = Codec.base64(buffer.array());
        return comment == null || comment.isEmpty() ? encoded : encoded + " " + comment;
    }

    private static byte[] fixedLe(BigInteger value, int size) {
        byte[] be = value.toByteArray();
        int start = 0;
        int len = be.length;
        if (len > size && be[0] == 0) {
            start = 1;
            len--;
        }
        byte[] out = new byte[size];
        for (int i = 0; i < Math.min(len, size); i++) {
            out[i] = be[start + len - 1 - i];
        }
        return out;
    }

    // --------------------------------------------------------------- scripts

    /**
     * Writes the key, bind-mounts it over {@code /adb_keys}, and switches the
     * network mode to {@code mfg}.
     *
     * <p>Run it through {@link Rpc#runScript}, not {@link Rpc#exec}: the
     * daemon's whitespace splitter cannot carry quotes or redirection.
     */
    public static String provisionScript(String publicKeyLine, String previousMode) {
        String key = publicKeyLine.replace("'", "");
        return "#!/system/bin/sh\n"
                + "# Written by the Ola S1 tool. Reversible; nothing is flashed.\n"
                + "OLD=" + shellQuote(previousMode == null ? "" : previousMode) + "\n"
                + "umask 022\n"
                + "printf '%s\\n' '" + key + "' > " + KEY_FILE + "\n"
                + "chmod 644 " + KEY_FILE + "\n"
                + "if ! mount | grep -q ' /adb_keys '; then\n"
                + "  mount --bind " + KEY_FILE + " /adb_keys\n"
                + "fi\n"
                + "setprop persist.ola.security.scootermode mfg\n"
                + "echo \"old=$OLD\"\n"
                + "ls -l /adb_keys\n"
                + "getprop persist.ola.security.scootermode\n";
    }

    /** Unmounts the key, deletes it, and puts the network mode back. */
    public static String undoScript(String previousMode) {
        String mode = previousMode == null || previousMode.isEmpty() ? "cust" : previousMode;
        return "#!/system/bin/sh\n"
                + "umount /adb_keys 2>/dev/null\n"
                + "rm -f " + KEY_FILE + "\n"
                + "setprop persist.ola.security.scootermode " + shellQuote(mode) + "\n"
                + "echo \"mode=$(getprop persist.ola.security.scootermode)\"\n"
                + "if mount | grep -q ' /adb_keys '; then echo 'still mounted'; else echo 'unmounted'; fi\n";
    }

    /** Reports the two things that matter: the mount and the network mode. */
    public static String statusScript() {
        return "#!/system/bin/sh\n"
                + "echo \"mode=$(getprop persist.ola.security.scootermode)\"\n"
                + "if mount | grep -q ' /adb_keys '; then echo 'adb_keys=mounted'; else echo 'adb_keys=no'; fi\n"
                + "if [ -f " + KEY_FILE + " ]; then echo \"key=$(wc -c < " + KEY_FILE + ")\"; else echo 'key=none'; fi\n";
    }

    /** Single-quotes a value for {@code sh}. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
