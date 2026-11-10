package dev.olatool;

import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A tiny, dependency-free MQTT 3.1.1 client over TLS — just enough to subscribe
 * to the owner's own scooter telemetry topics on Ola's broker and read the
 * packets. Hand-rolled to keep the app's no-new-deps rule.
 *
 * <p><b>Read-only by construction:</b> it only ever sends CONNECT, SUBSCRIBE,
 * PINGREQ, PUBACK and DISCONNECT. There is no publish method, so it can never
 * send a command to the vehicle. It connects with a unique client id, so it
 * should not evict the scooter's own broker session.
 */
public final class OlaMqtt {

    private static final String TAG = "OlaMqtt";

    public interface Listener {
        void onState(String state, boolean ok);
        void onConnack(int returnCode);
        void onMessage(String topic, byte[] payload);
    }

    private final String host;
    private final int port;
    private final String clientId;
    private final String user;
    private final String pass;
    private final Listener listener;

    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private volatile boolean running;
    private Thread reader;
    private Thread pinger;
    private final Object writeLock = new Object();
    private final AtomicInteger packetId = new AtomicInteger(1);
    private String subTopic;
    private int subQos;

    public OlaMqtt(String host, int port, String clientId, String user, String pass,
                   Listener listener) {
        this.host = host;
        this.port = port;
        this.clientId = clientId;
        this.user = user;
        this.pass = pass;
        this.listener = listener;
    }

    /**
     * Registers a topic to subscribe to automatically once connected, from the
     * background connect thread (so the subscribe write never touches the UI
     * thread). Call before {@link #connect}.
     */
    public void setSubscription(String topic, int qos) {
        this.subTopic = topic;
        this.subQos = qos;
    }

    /** Opens the TLS socket and sends CONNECT. Blocks until CONNACK or failure. */
    public void connect(int timeoutMs) {
        try {
            listener.onState("Connecting to " + host + ":" + port + " …", true);
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket s = (SSLSocket) f.createSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(0);
            s.startHandshake();
            this.socket = s;
            this.out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());

            sendConnect();
            int type = in.readUnsignedByte();
            int len = readRemaining(in);
            if ((type & 0xF0) != 0x20 || len < 2) {
                fail("Unexpected CONNACK");
                return;
            }
            in.readUnsignedByte();                 // session present
            int rc = in.readUnsignedByte();
            for (int i = 2; i < len; i++) {
                in.readUnsignedByte();
            }
            Log.i(TAG, "CONNACK rc=" + rc);
            listener.onConnack(rc);
            if (rc != 0) {
                listener.onState("Broker refused (code " + rc + ": "
                        + connackReason(rc) + ").", false);
                closeQuietly();
                return;
            }
            running = true;
            listener.onState("Connected.", true);
            startReader(in);
            startPinger();
            if (subTopic != null) {
                subscribe(subTopic, subQos);     // on this background connect thread
            }
        } catch (Exception e) {
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    public void subscribe(String topic, int qos) {
        try {
            int id = nextId();
            byte[] t = topic.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + 2 + t.length + 1];
            int p = 0;
            payload[p++] = (byte) (id >> 8);
            payload[p++] = (byte) id;
            payload[p++] = (byte) (t.length >> 8);
            payload[p++] = (byte) t.length;
            System.arraycopy(t, 0, payload, p, t.length);
            p += t.length;
            payload[p] = (byte) (qos & 3);
            Log.i(TAG, "sending SUBSCRIBE topic=" + topic + " out=" + (out != null));
            sendPacket(0x82, payload);           // SUBSCRIBE (0x80|0x02)
            Log.i(TAG, "SUBSCRIBE sent");
        } catch (Throwable e) {
            Log.w(TAG, "subscribe failed", e);
            listener.onState("Subscribe failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), false);
        }
    }

    public void close() {
        running = false;
        try {
            if (out != null) {
                sendPacket(0xE0, new byte[0]);   // DISCONNECT
            }
        } catch (Exception ignored) {
        }
        closeQuietly();
    }

    // ------------------------------------------------------------------ internals

    private void sendConnect() throws IOException {
        byte[] cid = clientId.getBytes(StandardCharsets.UTF_8);
        byte[] u = user == null ? new byte[0] : user.getBytes(StandardCharsets.UTF_8);
        byte[] pw = pass == null ? new byte[0] : pass.getBytes(StandardCharsets.UTF_8);

        java.io.ByteArrayOutputStream vh = new java.io.ByteArrayOutputStream();
        writeString(vh, "MQTT");
        vh.write(4);                              // protocol level 3.1.1
        int flags = 0x02;                         // clean session
        if (u.length > 0) flags |= 0x80;
        if (pw.length > 0) flags |= 0x40;
        vh.write(flags);
        vh.write(0);
        vh.write(30);                             // keep-alive 30s
        writeBytes(vh, cid);
        if (u.length > 0) writeBytes(vh, u);
        if (pw.length > 0) writeBytes(vh, pw);
        sendPacket(0x10, vh.toByteArray());       // CONNECT
    }

    private void startReader(DataInputStream in) {
        reader = new Thread(() -> {
            try {
                while (running) {
                    int b1 = in.read();
                    if (b1 < 0) {
                        break;
                    }
                    int len = readRemaining(in);
                    byte[] body = new byte[len];
                    in.readFully(body);
                    handle(b1 & 0xF0, b1 & 0x0F, body);
                }
            } catch (Exception e) {
                Log.w(TAG, "reader ended", e);
                if (running) {
                    listener.onState("Disconnected: "
                            + (e.getMessage() == null ? e.toString() : e.getMessage()), false);
                }
            } finally {
                Log.i(TAG, "reader loop exit (running=" + running + ")");
                running = false;
                closeQuietly();
            }
        }, "mqtt-read");
        reader.setDaemon(true);
        reader.start();
    }

    private void startPinger() {
        pinger = new Thread(() -> {
            try {
                while (running) {
                    Thread.sleep(20_000);
                    if (running) {
                        sendPacket(0xC0, new byte[0]);   // PINGREQ
                    }
                }
            } catch (Exception ignored) {
            }
        }, "mqtt-ping");
        pinger.setDaemon(true);
        pinger.start();
    }

    private void handle(int type, int flags, byte[] body) {
        if (type == 0x30) {                        // PUBLISH
            int p = 0;
            int tlen = ((body[p] & 0xFF) << 8) | (body[p + 1] & 0xFF);
            p += 2;
            String topic = new String(body, p, tlen, StandardCharsets.UTF_8);
            p += tlen;
            int qos = (flags >> 1) & 3;
            int pid = -1;
            if (qos > 0) {
                pid = ((body[p] & 0xFF) << 8) | (body[p + 1] & 0xFF);
                p += 2;
            }
            byte[] payload = new byte[body.length - p];
            System.arraycopy(body, p, payload, 0, payload.length);
            Log.i(TAG, "PUBLISH " + topic + " len=" + payload.length);
            listener.onMessage(topic, payload);
            if (qos == 1 && pid >= 0) {
                try {
                    sendPacket(0x40, new byte[]{(byte) (pid >> 8), (byte) pid});  // PUBACK
                } catch (Exception ignored) {
                }
            }
        } else if (type == 0x90) {                 // SUBACK
            boolean denied = false;
            StringBuilder codes = new StringBuilder();
            for (int i = 2; i < body.length; i++) {
                int c = body[i] & 0xFF;
                codes.append(i == 2 ? "" : ",").append(String.format("0x%02X", c));
                if (c == 0x80) {
                    denied = true;
                }
            }
            Log.i(TAG, "SUBACK codes=" + codes);
            if (denied) {
                listener.onState("Subscribe DENIED by broker (0x80). The device token "
                        + "likely cannot subscribe to these telemetry topics.", false);
            } else {
                listener.onState("Subscribed OK (granted qos " + codes + "). Waiting for packets …",
                        true);
            }
        }
        // PINGRESP etc. ignored.
    }

    private static String connackReason(int rc) {
        switch (rc) {
            case 1: return "unacceptable protocol version";
            case 2: return "client id rejected";
            case 3: return "server unavailable";
            case 4: return "bad username/token";
            case 5: return "not authorized";
            default: return "unknown";
        }
    }

    private void sendPacket(int header, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (out == null) {
                return;
            }
            out.write(header);
            writeRemaining(out, payload.length);
            if (payload.length > 0) {
                out.write(payload);
            }
            out.flush();
        }
    }

    private static void writeString(java.io.ByteArrayOutputStream o, String s) {
        writeBytes(o, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(java.io.ByteArrayOutputStream o, byte[] b) {
        o.write(b.length >> 8);
        o.write(b.length & 0xFF);
        o.write(b, 0, b.length);
    }

    private static void writeRemaining(OutputStream o, int len) throws IOException {
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) {
                d |= 0x80;
            }
            o.write(d);
        } while (len > 0);
    }

    private static int readRemaining(DataInputStream in) throws IOException {
        int multiplier = 1;
        int value = 0;
        int d;
        do {
            d = in.readUnsignedByte();
            value += (d & 0x7F) * multiplier;
            multiplier *= 128;
        } while ((d & 0x80) != 0);
        return value;
    }

    private int nextId() {
        int id = packetId.getAndIncrement() & 0xFFFF;
        return id == 0 ? 1 : id;
    }

    private void fail(String msg) {
        Log.w(TAG, "fail: " + msg);
        listener.onState("Failed: " + msg, false);
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        socket = null;
        out = null;
    }
}
