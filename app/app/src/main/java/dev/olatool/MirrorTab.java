package dev.olatool;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.PixelCopy;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.olatool.core.Addr;

/**
 * Live view and touch control of the dash, the scrcpy way but self-contained.
 *
 * <p>On Start the tab pushes the bundled {@code mirror.apk} (the {@link Agent}
 * dex) to the dash over the root gRPC shell, launches it with {@code
 * app_process}, then opens a plain TCP socket straight to the dash on Wi-Fi.
 * The socket carries an H.264 stream which a {@link MediaCodec} decoder renders
 * into the {@link SurfaceView}; touches on that view are scaled to dash pixels
 * and sent back on the same socket, where the agent injects them.
 *
 * <p>Why our own agent and not scrcpy-server: scrcpy reaches the device over an
 * adb-forwarded abstract socket; we have no adb here, but we do have a root
 * shell and a routable Wi-Fi address, so the agent just binds a TCP port. Gated
 * behind {@link Access#ADMIN}.
 */
public final class MirrorTab implements Tab {

    private static final int PORT = 8099;
    private static final String REMOTE_APK = "/data/local/tmp/mirror.apk";
    private static final int DASH_W = 800;     // panel is 800x480; agent args
    private static final int DASH_H = 480;
    private static final int BITRATE = 6_000_000;
    private static final String NAV = "com.olaelectric.olaevnavigation/"
            + "com.olaelectric.navigation.screens.main.presentation.view.MainActivity";
    private static final String HMI_COMP = "com.olaelectric.hmiclient/.MainActivity";

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;

    private final SurfaceView surfaceView;
    private final TextView status;
    private final View startButton;
    private final View stopButton;

    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private volatile boolean surfaceReady;
    private volatile boolean running;
    private volatile Socket socket;
    private volatile DataOutputStream control;
    private Thread netThread;

    private int dashW = DASH_W;
    private int dashH = DASH_H;
    private long downTime;

    // Typed-text box, screenshot and MP4-record state. The muxer is touched
    // from both the UI thread (start/stop) and the net thread (per frame), so
    // every access is guarded by recLock.
    private EditText typeBox;
    private Button recButton;
    private final Object recLock = new Object();
    private volatile boolean recording;
    private MediaMuxer muxer;
    private int recTrack = -1;
    private boolean muxerStarted;
    private boolean awaitingKey;
    private long recStartNs;
    private volatile byte[] lastCsd;
    private File recFile;

    public MirrorTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Screen mirror",
                "Live dash view with touch control. Pushes a tiny capture agent "
                        + "to the dash over the root shell and streams H.264 back."));

        LinearLayout buttons = Ui.row(activity);
        startButton = Ui.accent(activity, "Start", v -> start());
        stopButton = Ui.danger(activity, "Stop", v -> stop("Stopped."));
        stopButton.setVisibility(View.GONE);
        View wakeButton = Ui.button(activity, "Wake", v -> wakeDash());
        buttons.addView(startButton, weight());
        buttons.addView(Ui.space(activity, 0), fixed(8));
        buttons.addView(wakeButton, weight());
        buttons.addView(Ui.space(activity, 0), fixed(8));
        buttons.addView(stopButton, weight());
        body.addView(buttons, topMargin(12));

        status = Ui.note(activity, "Idle.");
        body.addView(status, topMargin(8));

        surfaceView = new SurfaceView(activity);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 260));
        sp.topMargin = Ui.dp(activity, 12);
        surfaceView.setLayoutParams(sp);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }
        });
        surfaceView.setOnTouchListener(this::onTouch);
        body.addView(surfaceView);

        // --- YouTube / dash remote, right under the live screen -------------
        LinearLayout openRow = Ui.row(activity);
        openRow.addView(Ui.accent(activity, "Open YouTube", v -> ytOpen()), weight());
        openRow.addView(Ui.space(activity, 0), fixed(8));
        openRow.addView(Ui.danger(activity, "Close + clean", v -> ytForceStop()), weight());
        body.addView(openRow, topMargin(12));

        body.addView(centerRow(Ui.button(activity, "▲", v -> key(KEY_UP))), topMargin(8));
        LinearLayout mid = Ui.row(activity);
        mid.addView(Ui.button(activity, "◀", v -> key(KEY_LEFT)), weight());
        mid.addView(Ui.space(activity, 0), fixed(8));
        mid.addView(Ui.accent(activity, "OK / Play", v -> key(KEY_OK)), weight());
        mid.addView(Ui.space(activity, 0), fixed(8));
        mid.addView(Ui.button(activity, "▶", v -> key(KEY_RIGHT)), weight());
        body.addView(mid, topMargin(8));
        body.addView(centerRow(Ui.button(activity, "▼", v -> key(KEY_DOWN))), topMargin(8));

        LinearLayout trRow = Ui.row(activity);
        trRow.addView(Ui.button(activity, "Back", v -> key(KEY_BACK)), weight());
        trRow.addView(Ui.space(activity, 0), fixed(8));
        trRow.addView(Ui.button(activity, "⏯", v -> key(KEY_PLAY_PAUSE)), weight());
        trRow.addView(Ui.space(activity, 0), fixed(8));
        trRow.addView(Ui.button(activity, "Home", v -> key(KEY_HOME)), weight());
        body.addView(trRow, topMargin(8));

        // Volume.
        LinearLayout volRow = Ui.row(activity);
        volRow.addView(Ui.button(activity, "Vol −", v -> key(KEY_VOL_DOWN)), weight());
        volRow.addView(Ui.space(activity, 0), fixed(8));
        volRow.addView(Ui.button(activity, "🔇 Mute", v -> key(KEY_MUTE)), weight());
        volRow.addView(Ui.space(activity, 0), fixed(8));
        volRow.addView(Ui.button(activity, "Vol +", v -> key(KEY_VOL_UP)), weight());
        body.addView(volRow, topMargin(8));

        // --- Capture: screenshot + MP4 record ------------------------------
        LinearLayout capRow = Ui.row(activity);
        capRow.addView(Ui.button(activity, "📷 Screenshot", v -> screenshot()), weight());
        capRow.addView(Ui.space(activity, 0), fixed(8));
        recButton = Ui.button(activity, "⏺ Record", v -> toggleRecord());
        capRow.addView(recButton, weight());
        body.addView(capRow, topMargin(12));

        // --- Type text into the dash (e.g. a YouTube search) ---------------
        typeBox = new EditText(activity);
        typeBox.setHint("type text to send to the dash…");
        typeBox.setTextSize(13);
        typeBox.setTextColor(Ui.TEXT);
        typeBox.setHintTextColor(Ui.DIM);
        typeBox.setSingleLine(true);
        typeBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        typeBox.setBackground(Ui.round(activity, Ui.CARD, 10, Ui.LINE));
        typeBox.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8),
                Ui.dp(activity, 12), Ui.dp(activity, 8));
        LinearLayout typeRow = Ui.row(activity);
        typeRow.addView(typeBox, weight());
        typeRow.addView(Ui.space(activity, 0), fixed(8));
        typeRow.addView(Ui.accent(activity, "Send", v -> sendText()), fixed(72));
        body.addView(typeRow, topMargin(8));

        // --- Quick launch on the dash --------------------------------------
        LinearLayout launchRow = Ui.row(activity);
        launchRow.addView(Ui.button(activity, "Nav",
                v -> remote("am start -n " + NAV, "Opened Navigation.")), weight());
        launchRow.addView(Ui.space(activity, 0), fixed(8));
        launchRow.addView(Ui.button(activity, "Settings",
                v -> remote("am start -a android.settings.SETTINGS", "Opened Settings.")), weight());
        launchRow.addView(Ui.space(activity, 0), fixed(8));
        launchRow.addView(Ui.button(activity, "Restart dash", v -> {
            if (Access.require(activity, Access.ADMIN)) {
                remote("am force-stop com.olaelectric.hmiclient; am start -n " + HMI_COMP,
                        "Dash restarting…");
            }
        }), weight());
        body.addView(launchRow, topMargin(8));
    }

    // Android key codes for the remote.
    private static final int KEY_UP = 19, KEY_DOWN = 20, KEY_LEFT = 21, KEY_RIGHT = 22,
            KEY_OK = 23, KEY_BACK = 4, KEY_HOME = 3, KEY_PLAY_PAUSE = 85,
            KEY_VOL_UP = 24, KEY_VOL_DOWN = 25, KEY_MUTE = 164;
    private static final String YT_PKG = "org.smarttube.stable";
    private static final String YT_SPLASH =
            "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity";

    private void ytOpen() {
        remote("am start -n " + YT_PKG + "/" + YT_SPLASH, "YouTube opened.");
    }

    private void ytForceStop() {
        // Fully close YouTube so its ~90 MB of RAM is released (a plain exit
        // leaves the process cached), then wipe its caches so nothing builds up
        // across sessions. Only cache/ and code_cache/ are removed; files/,
        // databases/ and shared_prefs/ stay, so the sign-in and settings
        // survive. Android recreates the cache dirs with the right SELinux
        // context on next launch — the same thing the OS "Clear cache" does.
        String cmd = "am force-stop " + YT_PKG
                + "; rm -rf /data/data/" + YT_PKG + "/cache /data/data/" + YT_PKG + "/code_cache";
        remote(cmd, "YouTube closed, cache cleared.");
    }

    private void key(int keycode) {
        remote("input keyevent " + keycode, null);
    }

    /** Fire a control command over the root shell; optional status message. */
    private void remote(String cmd, String okMsg) {
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        conn.run(rpc -> rpc.shell(cmd, 10_000), (text, error) -> {
            if (error != null) {
                status.setText("Failed: " + error.getMessage());
            } else if (okMsg != null) {
                status.setText(okMsg);
            }
        });
    }

    private LinearLayout centerRow(View child) {
        LinearLayout row = Ui.row(activity);
        row.setGravity(android.view.Gravity.CENTER);
        row.addView(child, new LinearLayout.LayoutParams(
                Ui.dp(activity, 120), LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    // ------------------------------------------------------------------ start

    private void start() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (running) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        if (!surfaceReady) {
            status.setText("Surface not ready yet — try again in a moment.");
            return;
        }
        running = true;
        startButton.setEnabled(false);
        stopButton.setVisibility(View.VISIBLE);
        status.setText("Pushing agent to dash...");

        // Push + launch the agent on the IO thread (uses the shell), then the
        // decode/stream happens on its own net thread.
        conn.run(rpc -> {
            pushAgent(rpc);
            launchAgent(rpc);
            return conn.target();
        }, (target, error) -> {
            if (error != null || target == null) {
                stop("Agent launch failed: " + (error == null ? "no target" : error.getMessage()));
                return;
            }
            status.setText("Connecting to dash...");
            netThread = new Thread(() -> streamLoop(target), "mirror-net");
            netThread.start();
        });
    }

    /** Streams the bundled agent APK to the dash in base64 chunks (tee -a, since
     *  the daemon's shell splitter mangles >> and 2>&1). */
    private void pushAgent(dev.olatool.core.Rpc rpc) throws Exception {
        byte[] apk = readAsset("mirror.apk");
        // Skip the push if the correct agent is already on the dash: faster, and
        // it avoids any chance of a half-written APK from a dropped chunk.
        String size = rpc.shell("wc -c < " + REMOTE_APK, 10_000);
        if (size != null && size.trim().equals(String.valueOf(apk.length))) {
            return;
        }
        String b64 = Base64.encodeToString(apk, Base64.NO_WRAP);
        rpc.shell("rm -f " + REMOTE_APK, 10_000);
        int chunk = 4000;
        for (int i = 0; i < b64.length(); i += chunk) {
            String part = b64.substring(i, Math.min(b64.length(), i + chunk));
            // No spaces in the pipeline args; tee -a appends without using >>.
            rpc.shell("echo " + part + " | base64 -d | tee -a " + REMOTE_APK + " >/dev/null", 20_000);
        }
    }

    private void launchAgent(dev.olatool.core.Rpc rpc) {
        // Kill any stale agent, then start detached. Separate >/2> redirects:
        // the daemon's splitter breaks the combined 2>&1 form.
        safeShell(rpc, "pkill -f dev.olatool.mirror.Agent", 8_000);
        // 'env VAR=val cmd' sets CLASSPATH then execs app_process. A bare
        // 'CLASSPATH=... app_process' prefix only works as a shell command word;
        // after setsid it would be treated as the program name and fail.
        String cmd = "setsid env CLASSPATH=" + REMOTE_APK
                + " app_process / dev.olatool.mirror.Agent "
                + PORT + " " + DASH_W + " " + DASH_H + " " + BITRATE
                + " >/data/local/tmp/mirror.out 2>/data/local/tmp/mirror.err &";
        // Fire-and-forget: a backgrounded command never closes its stream, so the
        // daemon lets the RPC hit its deadline even though the agent did start.
        // The connect-retry in streamLoop is the real "did it come up?" check.
        safeShell(rpc, cmd, 4_000);
    }

    private static void safeShell(dev.olatool.core.Rpc rpc, String cmd, long timeout) {
        try {
            rpc.shell(cmd, timeout);
        } catch (Exception ignored) {
        }
    }

    /** Wake the dash display (KEYCODE_WAKEUP) and keep it on, so the mirror is
     *  not black because the panel went to sleep. */
    private void wakeDash() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        conn.run(rpc -> {
            rpc.shell("input keyevent 224", 8_000);      // KEYCODE_WAKEUP
            rpc.shell("svc power stayon true", 8_000);    // don't re-sleep while plugged
            return null;
        }, (v, error) -> status.setText(error != null
                ? "Wake failed: " + error.getMessage()
                : "Dash woken (stay-on). Now press Start."));
    }

    // --------------------------------------------------------------- stream

    private void streamLoop(Addr.Target target) {
        MediaCodec decoder = null;
        try {
            Socket s = new Socket();
            // The agent may need a beat to bind; retry a few times.
            InetSocketAddress dest = new InetSocketAddress(
                    InetAddress.getByName(stripScope(target.host)), PORT);
            Exception last = null;
            for (int attempt = 0; attempt < 10 && running; attempt++) {
                try {
                    s = new Socket();
                    s.connect(dest, 1500);
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    Thread.sleep(400);
                }
            }
            if (last != null) {
                throw last;
            }
            s.setTcpNoDelay(true);
            socket = s;
            control = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());

            dashW = in.readInt();
            dashH = in.readInt();
            conn.post(() -> status.setText("Live " + dashW + "x" + dashH + " — touch to control."));

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, dashW, dashH);
            decoder.configure(format, surfaceView.getHolder().getSurface(), null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                int len = in.readInt();
                int flags = in.readUnsignedByte();
                byte[] data = new byte[len];
                in.readFully(data);

                if ((flags & 1) != 0) {
                    lastCsd = data;   // SPS/PPS, kept so a mid-stream record can start
                }
                recordFrame(data, flags);

                int ii = decoder.dequeueInputBuffer(15_000);
                if (ii >= 0) {
                    ByteBuffer b = decoder.getInputBuffer(ii);
                    b.clear();
                    b.put(data);
                    int bf = (flags & 1) != 0 ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                    decoder.queueInputBuffer(ii, 0, len, 0, bf);
                }
                int oi;
                while ((oi = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                    decoder.releaseOutputBuffer(oi, true);   // render to surface
                }
            }
        } catch (Exception e) {
            if (running) {
                conn.post(() -> stop("Stream error: " + e.getMessage()));
            }
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) { }
                decoder.release();
            }
        }
    }

    // ---------------------------------------------------------------- control

    private boolean onTouch(View v, MotionEvent e) {
        if (!running || control == null) {
            return false;
        }
        int vw = v.getWidth(), vh = v.getHeight();
        if (vw == 0 || vh == 0) {
            return false;
        }
        final int action;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: action = 0; break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: action = 1; break;
            case MotionEvent.ACTION_MOVE: action = 2; break;
            default: return true;
        }
        // Keep the gesture inside the mirror: while a finger is down, stop the
        // parent ScrollView from stealing vertical drags, so a swipe on the dash
        // scrolls the dash instead of this tab.
        android.view.ViewParent parent = v.getParent();
        if (parent != null) {
            if (action == 0) {
                parent.requestDisallowInterceptTouchEvent(true);
            } else if (action == 1) {
                parent.requestDisallowInterceptTouchEvent(false);
            }
        }
        final int x = clamp((int) (e.getX() * dashW / vw), dashW);
        final int y = clamp((int) (e.getY() * dashH / vh), dashH);
        sender.execute(() -> {
            try {
                DataOutputStream c = control;
                if (c != null) {
                    synchronized (c) {
                        c.writeByte(action);
                        c.writeInt(x);
                        c.writeInt(y);
                        c.flush();
                    }
                }
            } catch (Exception ignored) {
            }
        });
        return true;
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v >= max ? max - 1 : v);
    }

    // ------------------------------------------------------------ extra tools

    /** Send typed text to the dash — e.g. into a YouTube search box. */
    private void sendText() {
        String t = typeBox.getText().toString();
        if (t.isEmpty()) {
            status.setText("Type something to send first.");
            return;
        }
        remote("input text " + escapeForInput(t), "Sent: " + t);
        typeBox.setText("");
    }

    /** input(1) uses %s for a space; backslash-escape the shell metacharacters
     *  so the text survives the daemon's {@code sh -c} wrapping unchanged. */
    private static String escapeForInput(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                sb.append("%s");
                continue;
            }
            if ("&|;<>()$`\"'\\*?[]{}~#!".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Grab the current decoded frame straight off the Surface with PixelCopy.
     *  This works even though the dash's own {@code screencap} returns black. */
    private void screenshot() {
        if (!running) {
            status.setText("Start the mirror first.");
            return;
        }
        int w = surfaceView.getWidth(), h = surfaceView.getHeight();
        if (w == 0 || h == 0) {
            status.setText("Surface not ready yet.");
            return;
        }
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(surfaceView, bmp, result -> {
                if (result == PixelCopy.SUCCESS) {
                    sender.execute(() -> savePng(bmp));
                } else {
                    status.setText("Screenshot failed (code " + result + ").");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException e) {
            status.setText("Screenshot not available: " + e.getMessage());
        }
    }

    private void savePng(Bitmap bmp) {
        try {
            File f = new File(activity.getExternalFilesDir(null),
                    "dash-" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            conn.post(() -> status.setText("Screenshot saved: " + f.getAbsolutePath()));
        } catch (Exception e) {
            conn.post(() -> status.setText("Save failed: " + e.getMessage()));
        }
    }

    // --------------------------------------------------------------- record

    private void toggleRecord() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (recording) {
            stopRecording();
            return;
        }
        if (!running) {
            status.setText("Start the mirror first.");
            return;
        }
        recFile = new File(activity.getExternalFilesDir(null),
                "dash-" + System.currentTimeMillis() + ".mp4");
        synchronized (recLock) {
            muxerStarted = false;
            recTrack = -1;
            awaitingKey = true;
            recording = true;
        }
        recButton.setText("⏹ Stop rec");
        status.setText("Recording to " + recFile.getName() + " …");
    }

    private void stopRecording() {
        synchronized (recLock) {
            recording = false;
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                } catch (Exception ignored) {
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
            muxer = null;
            muxerStarted = false;
        }
        recButton.setText("⏺ Record");
        if (recFile != null && recFile.exists() && recFile.length() > 0) {
            status.setText("Saved: " + recFile.getAbsolutePath());
        } else {
            status.setText("Recording stopped (no frames captured).");
        }
    }

    /** Mux one H.264 access unit into the MP4 while recording. Called on the net
     *  thread; the lock keeps it off a muxer that stopRecording() is releasing. */
    private void recordFrame(byte[] data, int flags) {
        synchronized (recLock) {
            if (!recording) {
                return;
            }
            try {
                if (!muxerStarted) {
                    byte[] csd = lastCsd;
                    if (csd == null) {
                        return;   // no SPS/PPS seen yet — wait for the next one
                    }
                    MediaFormat fmt = MediaFormat.createVideoFormat(
                            MediaFormat.MIMETYPE_VIDEO_AVC, dashW, dashH);
                    int sc2 = findNextStart(csd, 4);
                    if (sc2 > 0) {
                        fmt.setByteBuffer("csd-0",
                                ByteBuffer.wrap(Arrays.copyOfRange(csd, 0, sc2)));
                        fmt.setByteBuffer("csd-1",
                                ByteBuffer.wrap(Arrays.copyOfRange(csd, sc2, csd.length)));
                    } else {
                        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                    }
                    muxer = new MediaMuxer(recFile.getAbsolutePath(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    recTrack = muxer.addTrack(fmt);
                    muxer.start();
                    muxerStarted = true;
                    recStartNs = System.nanoTime();
                }
                if ((flags & 1) != 0) {
                    return;   // config NALs live in the format, not as samples
                }
                boolean key = isKeyframe(data);
                if (awaitingKey) {
                    if (!key) {
                        return;   // an MP4 has to open on a keyframe
                    }
                    awaitingKey = false;
                }
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                long pts = (System.nanoTime() - recStartNs) / 1000;
                bi.set(0, data.length, pts,
                        key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(recTrack, ByteBuffer.wrap(data), bi);
            } catch (Exception e) {
                conn.post(() -> status.setText("Record error: " + e.getMessage()));
                recording = false;
                if (muxer != null) {
                    try {
                        muxer.release();
                    } catch (Exception ignored) {
                    }
                    muxer = null;
                }
                conn.post(() -> recButton.setText("⏺ Record"));
            }
        }
    }

    /** Index of the next Annex-B start code at or after {@code from}, else -1. */
    private static int findNextStart(byte[] d, int from) {
        for (int i = from; i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) {
                return i;
            }
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }

    /** True if any NAL in the access unit is an IDR (type 5). */
    private static boolean isKeyframe(byte[] d) {
        for (int i = 0; i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                int type = d[i + 3] & 0x1F;
                if (type == 5) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- stop

    private void stop(String msg) {
        if (recording) {
            stopRecording();
        }
        running = false;
        Socket s = socket;
        socket = null;
        control = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) { }
        }
        startButton.setEnabled(true);
        stopButton.setVisibility(View.GONE);
        status.setText(msg);
    }

    // --------------------------------------------------------------- plumbing

    private byte[] readAsset(String name) throws Exception {
        try (InputStream is = activity.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static String stripScope(String host) {
        int pct = host.indexOf('%');
        return pct < 0 ? host : host.substring(0, pct);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams fixed(int dp) {
        return new LinearLayout.LayoutParams(Ui.dp(activity, dp),
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
    }

    @Override
    public void onHide() {
        if (running) {
            stop("Idle.");
        }
    }
}
