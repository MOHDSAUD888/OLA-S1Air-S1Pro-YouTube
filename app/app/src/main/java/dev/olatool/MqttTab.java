package dev.olatool;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Live location tracking over Ola's MQTT broker, using the owner's own scooter
 * device token, so it works even when the scooter is away (not on the LAN).
 *
 * <p>Read-only: it only subscribes to the scooter's own telemetry topics and
 * reads the packets. It never publishes, so it cannot command the vehicle. It
 * connects with a unique client id to avoid evicting the scooter's own session —
 * but that is not guaranteed by every broker, hence the warning on screen.
 */
public final class MqttTab implements Tab {

    private static final String PREFS = "ola";
    private static final String K_HOST = "mqtt_host";
    private static final String K_PORT = "mqtt_port";
    private static final String K_USER = "mqtt_user";
    private static final String K_TOKEN = "mqtt_token";
    private static final String K_TOPIC = "mqtt_topic";

    private static final int MAX_LOG = 40;

    private final Context ctx;
    private final Conn conn;
    private final ScrollView root;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusLine;
    private Button connectButton;
    private Ui.Kv kvHost;
    private Ui.Kv kvUser;
    private Ui.Kv kvTopic;
    private Ui.Kv kvToken;
    private Ui.Kv kvLat;
    private Ui.Kv kvLon;
    private Ui.Kv kvWhen;
    private Button mapButton;
    private TextView log;

    private OlaMqtt client;
    private boolean connected;
    private Double lastLat;
    private Double lastLon;
    private final StringBuilder logBuf = new StringBuilder();
    private int logLines;

    public MqttTab(Context ctx, Conn conn) {
        this.ctx = ctx;
        this.conn = conn;
        this.root = Ui.page(ctx);
        build();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        renderCreds();
    }

    @Override
    public void onHide() {
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        LinearLayout body = Ui.body(root);

        body.addView(Ui.pageTitle(ctx, "Track",
                "Live location over Ola's MQTT, using your scooter's own device token — "
                        + "works even when the scooter is away. Read-only.", Ui.INFO));

        // Risk warning.
        LinearLayout warn = Ui.card(ctx);
        warn.setBackground(Ui.round(ctx, Ui.alpha(Ui.WARN, 0x1A), 12, Ui.alpha(Ui.WARN, 0x55)));
        TextView w = Ui.text(ctx, "⚠  This connects to Ola's broker with the scooter's own "
                + "device token. It only reads (never commands). It uses a unique client id, but "
                + "some brokers allow one session per device — if so, your scooter's own cloud "
                + "link could briefly drop while this is connected. Disconnect when done.", 12, Ui.WARN);
        warn.addView(w);
        body.addView(warn);
        body.addView(Ui.space(ctx, 10));

        Ui.Section head = Ui.section(ctx, "Connection", null);
        LinearLayout buttons = Ui.row(ctx);
        connectButton = Ui.accent(ctx, "Connect", v -> toggle());
        buttons.addView(connectButton);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = Ui.dp(ctx, 8);
        Button fetch = Ui.button(ctx, "Fetch from scooter", v -> fetchCreds());
        buttons.addView(fetch, sp);
        Button edit = Ui.button(ctx, "Edit", v -> editCreds());
        buttons.addView(edit, sp);
        head.body.addView(buttons);
        statusLine = Ui.note(ctx, "");
        statusLine.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        head.body.addView(statusLine);
        body.addView(head.card);

        body.addView(Ui.space(ctx, 10));

        Ui.Section creds = Ui.section(ctx, "Broker", null);
        kvHost = new Ui.Kv(ctx, "Host");
        kvUser = new Ui.Kv(ctx, "Username");
        kvTopic = new Ui.Kv(ctx, "Topic");
        kvToken = new Ui.Kv(ctx, "Token");
        creds.body.addView(kvHost);
        creds.body.addView(kvUser);
        creds.body.addView(kvTopic);
        creds.body.addView(kvToken);
        body.addView(creds.card);

        body.addView(Ui.space(ctx, 10));

        Ui.Section loc = Ui.section(ctx, "Location", null, Ui.INFO);
        kvLat = new Ui.Kv(ctx, "Latitude");
        kvLon = new Ui.Kv(ctx, "Longitude");
        kvWhen = new Ui.Kv(ctx, "Last fix");
        loc.body.addView(kvLat);
        loc.body.addView(kvLon);
        loc.body.addView(kvWhen);
        loc.body.addView(Ui.space(ctx, 8));
        mapButton = Ui.button(ctx, "Open in Maps", v -> openMap());
        mapButton.setEnabled(false);
        loc.body.addView(mapButton);
        body.addView(loc.card);

        body.addView(Ui.space(ctx, 10));

        Ui.Section stream = Ui.section(ctx, "Packet stream", "Live topics (read-only).");
        log = Ui.codeBlock(Ui.text(ctx, "Not connected.", 10, Ui.TEXT));
        log.setTextIsSelectable(true);
        stream.body.addView(log);
        body.addView(stream.card);
    }

    // -------------------------------------------------------------- behaviour

    private void toggle() {
        if (connected) {
            disconnect();
            return;
        }
        if (!Access.require(ctx, Access.ADMIN)) {
            return;
        }
        SharedPreferences p = prefs();
        String host = p.getString(K_HOST, "");
        String user = p.getString(K_USER, "");
        String token = p.getString(K_TOKEN, "");
        if (host.isEmpty() || token.isEmpty()) {
            setStatus("No broker creds. Tap “Fetch from scooter” (connected) or Edit.", Ui.WARN);
            return;
        }
        int port = p.getInt(K_PORT, 443);
        final String topic = p.getString(K_TOPIC, defaultTopic(user));
        String cid = "olatool-ro-" + Long.toHexString(System.nanoTime());

        logBuf.setLength(0);
        logLines = 0;
        Ui.set(log, "Connecting …");
        client = new OlaMqtt(host, port, cid, user, token, new OlaMqtt.Listener() {
            @Override
            public void onState(String state, boolean ok) {
                ui.post(() -> {
                    setStatus(state, ok ? Ui.ACCENT : Ui.BAD);
                    if (!ok) {
                        connected = false;
                        connectButton.setText("Connect");
                    }
                });
            }

            @Override
            public void onConnack(int rc) {
                ui.post(() -> {
                    if (rc == 0) {
                        connected = true;
                        connectButton.setText("Disconnect");
                        appendLog("connected — subscribing to " + topic);
                    }
                });
            }

            @Override
            public void onMessage(String t, byte[] payload) {
                final double[] geo = Geo.extract(payload);
                ui.post(() -> onPacket(t, payload.length, geo));
            }
        });
        client.setSubscription(topic, 1);
        new Thread(() -> client.connect(10_000), "mqtt-connect").start();
        connectButton.setText("Disconnect");
    }

    private void disconnect() {
        connected = false;
        connectButton.setText("Connect");
        if (client != null) {
            final OlaMqtt c = client;
            client = null;
            new Thread(c::close, "mqtt-close").start();
        }
        setStatus("Disconnected.", Ui.DIM);
    }

    private void onPacket(String topic, int len, double[] geo) {
        String name = topic.substring(topic.lastIndexOf('/') + 1);
        String line = String.format(Locale.ROOT, "%-24s %d B", name, len);
        if (geo != null) {
            line += String.format(Locale.ROOT, "  → %.5f,%.5f", geo[0], geo[1]);
            lastLat = geo[0];
            lastLon = geo[1];
            kvLat.set(String.format(Locale.ROOT, "%.6f", geo[0]), Ui.INFO);
            kvLon.set(String.format(Locale.ROOT, "%.6f", geo[1]), Ui.INFO);
            kvWhen.set(timeNow());
            mapButton.setEnabled(true);
        }
        appendLog(line);
    }

    private void appendLog(String line) {
        logBuf.insert(0, line + "\n");
        if (++logLines > MAX_LOG) {
            int cut = logBuf.lastIndexOf("\n", logBuf.length() - 2);
            if (cut > 0) {
                logBuf.setLength(cut + 1);
            }
            logLines = MAX_LOG;
        }
        Ui.set(log, logBuf.toString());
    }

    private void openMap() {
        if (lastLat == null || lastLon == null) {
            return;
        }
        String ll = lastLat + "," + lastLon;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + ll + "?q=" + ll + "(Scooter)"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            setStatus("No map app.", Ui.WARN);
        }
    }

    // -------------------------------------------------------------- credentials

    private void fetchCreds() {
        if (!Access.require(ctx, Access.ADMIN)) {
            return;
        }
        if (conn == null || !conn.ready()) {
            setStatus("Connect to your scooter first (Auto/Direct), then Fetch.", Ui.WARN);
            return;
        }
        setStatus("Reading broker creds from your scooter …", Ui.DIM);
        conn.run(rpc -> rpc.shell(
                "cat /data/data/com.olaelectric.cloudconnectivitymanager/shared_prefs/com.ola.xml",
                30_000), (xml, err) -> {
            if (err != null) {
                setStatus("Fetch failed: " + err.getMessage(), Ui.BAD);
                return;
            }
            if (!parseCreds(xml)) {
                setStatus("Could not parse creds from the scooter.", Ui.BAD);
                return;
            }
            renderCreds();
            setStatus("Broker creds loaded from the scooter.", Ui.ACCENT);
        });
    }

    /** Pulls token, mqtt host/port, username/VIN out of the scooter's com.ola.xml. */
    private boolean parseCreds(String xml) {
        if (xml == null) {
            return false;
        }
        String s = xml.replace("&quot;", "\"");
        String token = group(s, "\"access_token\"\\s*:\\s*\"([^\"]+)\"");
        String host = group(s, "\"mqtt_host\"\\s*:\\s*\"(?:ssl://)?([^\":/]+)");
        String portS = group(s, "\"mqtt_port\"\\s*:\\s*(\\d+)");
        String user = group(s, "\"username\"\\s*:\\s*\"([^\"]+)\"");
        String vin = group(s, "\"tenant_id\"\\s*:\\s*\"([^\"]+)\"");
        if (token == null || host == null) {
            return false;
        }
        SharedPreferences.Editor e = prefs().edit();
        e.putString(K_TOKEN, token);
        e.putString(K_HOST, host);
        e.putInt(K_PORT, portS == null ? 443 : Integer.parseInt(portS));
        if (user != null) {
            e.putString(K_USER, user);
        } else if (vin != null) {
            e.putString(K_USER, "DEVICE:" + vin);
        }
        if (vin != null) {
            e.putString(K_TOPIC, "oem/HMI/Quectel/" + vin + "/#");
        }
        e.apply();
        return true;
    }

    private static String group(String s, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private void editCreds() {
        SharedPreferences p = prefs();
        LinearLayout box = Ui.column(ctx);
        int pad = Ui.dp(ctx, 16);
        box.setPadding(pad, Ui.dp(ctx, 8), pad, 0);
        final EditText host = field("Host", p.getString(K_HOST, ""));
        final EditText port = field("Port", String.valueOf(p.getInt(K_PORT, 443)));
        final EditText user = field("Username (DEVICE:VIN)", p.getString(K_USER, ""));
        final EditText topic = field("Topic", p.getString(K_TOPIC, ""));
        final EditText token = field("Token", p.getString(K_TOKEN, ""));
        box.addView(host);
        box.addView(port);
        box.addView(user);
        box.addView(topic);
        box.addView(token);
        new AlertDialog.Builder(ctx)
                .setTitle("Broker credentials")
                .setView(wrapScroll(box))
                .setPositiveButton("Save", (d, w) -> {
                    SharedPreferences.Editor e = p.edit();
                    e.putString(K_HOST, host.getText().toString().trim());
                    try {
                        e.putInt(K_PORT, Integer.parseInt(port.getText().toString().trim()));
                    } catch (Exception ignored) {
                        e.putInt(K_PORT, 443);
                    }
                    e.putString(K_USER, user.getText().toString().trim());
                    e.putString(K_TOPIC, topic.getText().toString().trim());
                    e.putString(K_TOKEN, token.getText().toString().trim());
                    e.apply();
                    renderCreds();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private ScrollView wrapScroll(View v) {
        ScrollView s = new ScrollView(ctx);
        s.addView(v);
        return s;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(12);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackground(Ui.round(ctx, Ui.CARD, 10, Ui.LINE));
        e.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        return e;
    }

    private void renderCreds() {
        SharedPreferences p = prefs();
        String host = p.getString(K_HOST, "");
        kvHost.set(host.isEmpty() ? Ui.DASH : host + ":" + p.getInt(K_PORT, 443));
        kvUser.set(orDash(p.getString(K_USER, "")));
        kvTopic.set(orDash(p.getString(K_TOPIC, "")));
        String tok = p.getString(K_TOKEN, "");
        kvToken.set(tok.isEmpty() ? Ui.DASH : "set (" + tok.length() + " chars)",
                tok.isEmpty() ? Ui.DIM : Ui.ACCENT);
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? Ui.DASH : s;
    }

    private static String defaultTopic(String user) {
        String vin = user != null && user.contains(":") ? user.substring(user.indexOf(':') + 1) : "";
        return "oem/HMI/Quectel/" + vin + "/#";
    }

    private void setStatus(String text, int color) {
        Ui.set(statusLine, text);
        statusLine.setTextColor(color);
    }

    private static String timeNow() {
        return String.format(Locale.ROOT, "%tT", System.currentTimeMillis());
    }

    private SharedPreferences prefs() {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
