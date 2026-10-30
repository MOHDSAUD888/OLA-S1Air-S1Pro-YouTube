package dev.olatool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.olatool.core.Can;
import dev.olatool.core.Dbc;
import dev.olatool.core.Faults;
import dev.olatool.core.Live;
import dev.olatool.core.Rpc;

/**
 * One CAN stream for the whole app.
 *
 * <p>The daemon can be told to push frames with {@code ReadCanStream}, and that
 * stream has to be opened once. If every tab opened its own, they would steal
 * frames from each other and the bus would look half dead. So this class owns
 * the one stream, decodes into a single {@link Live}, and hands the same
 * {@link Live.Snapshot} to every screen at 10 Hz.
 *
 * <p>It does not do any connection work: it reads {@link Conn#rpc()} and
 * re-opens the stream when that changes. The address discovery stays in
 * {@link Net}, untouched.
 *
 * <p>Tables load on a background thread the first time the bus starts, because
 * the signal file is a few hundred kilobytes of JSON.
 */
public final class Bus {

    /** Called on the UI thread, about ten times a second. */
    public interface Listener {
        void onTick(Live.Snapshot snapshot);
    }

    private static final long TICK_MS = 100;

    private final Context context;
    private final Conn conn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Live live;
    private volatile Rpc.Cancel cancel;
    private volatile Rpc streamedOn;
    private volatile String state = "idle";
    private volatile boolean running;
    private volatile boolean loading;

    private final Set<String> simulated = new LinkedHashSet<>();

    public Bus(Context context, Conn conn) {
        this.context = context.getApplicationContext();
        this.conn = conn;
    }

    // --------------------------------------------------------------- control

    /** Loads the tables if needed, then starts streaming and ticking. */
    public void start() {
        running = true;
        if (live == null) {
            if (!loading) {
                loading = true;
                new Thread(this::loadTables, "ola-bus-load").start();
            }
        } else {
            open();
        }
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    /** Stops the stream and the ticker. The tables stay loaded. */
    public void stop() {
        running = false;
        ui.removeCallbacks(tick);
        closeStream();
        state = "stopped";
    }

    public void attach(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void detach(Listener listener) {
        listeners.remove(listener);
    }

    // ----------------------------------------------------------------- state

    /** Null until the tables finish loading. */
    public Live live() {
        return live;
    }

    public boolean ready() {
        return live != null;
    }

    /** A short line for a status label: loading, streaming, or why it stopped. */
    public String state() {
        return state;
    }

    /** Latest snapshot, or an empty one before the first frame. */
    public Live.Snapshot snapshot() {
        Live l = live;
        return l == null ? null : l.snapshot();
    }

    public void clearLatched() {
        Live l = live;
        if (l != null) {
            l.clearLatched();
        }
    }

    public void reset() {
        Live l = live;
        if (l != null) {
            l.reset();
        }
    }

    // ------------------------------------------------------------ simulation

    /**
     * Adds a fault row to a local "simulated" set so the fault screen can be
     * tried without a real fault. Nothing is sent to the vehicle.
     */
    public synchronized boolean simulate(String id) {
        Live l = live;
        if (l == null || id == null) {
            return false;
        }
        Faults.Fault fault = l.faults().get(id.trim());
        if (fault == null) {
            return false;
        }
        simulated.add(fault.id);
        return true;
    }

    public synchronized void clearSimulated() {
        simulated.clear();
    }

    public synchronized List<Faults.Fault> simulatedFaults() {
        List<Faults.Fault> out = new ArrayList<>();
        Live l = live;
        if (l == null) {
            return out;
        }
        for (String id : simulated) {
            Faults.Fault fault = l.faults().get(id);
            if (fault != null) {
                out.add(fault);
            }
        }
        return out;
    }

    // --------------------------------------------------------------- stream

    private void loadTables() {
        try {
            Dbc dbc = Dbc.parse(readAsset("can_database.json"));
            Faults faults = Faults.parse(readAsset("faults.json"));
            live = new Live(dbc, faults);
            state = "table " + dbc.size() + " messages, " + dbc.signalCount()
                    + " signals, " + faults.size() + " faults";
            open();
        } catch (Exception e) {
            state = "cannot load tables: " + e.getMessage();
        } finally {
            loading = false;
        }
    }

    private synchronized void open() {
        Rpc rpc = conn.rpc();
        Live l = live;
        if (rpc == null) {
            state = "not connected";
            return;
        }
        if (l == null) {
            return;
        }
        closeStream();
        l.reset();
        streamedOn = rpc;
        state = "streaming";
        cancel = rpc.streamCanFrames(new Rpc.FrameSink() {
            @Override
            public void frame(Can.Frame frame) {
                Live target = live;
                if (target != null) {
                    target.onFrame(frame);
                }
            }

            @Override
            public void ended(String reason) {
                state = "stream ended: " + reason;
            }
        });
    }

    private synchronized void closeStream() {
        Rpc.Cancel old = cancel;
        cancel = null;
        streamedOn = null;
        if (old != null) {
            old.close();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (!conn.ready()) {
                if (cancel != null) {
                    closeStream();
                    state = "not connected";
                }
            } else if (conn.rpc() != streamedOn) {
                open();
            }

            Live l = live;
            if (l != null) {
                Live.Snapshot snapshot = l.snapshot();
                for (Listener listener : listeners) {
                    listener.onTick(snapshot);
                }
            }
            ui.postDelayed(this, TICK_MS);
        }
    };

    // --------------------------------------------------------------- assets

    private String readAsset(String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
