package dev.olatool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One shared cloud snapshot for the whole app.
 *
 * <p>The local CAN bus only carries data while the scooter is awake. When it is
 * asleep or off Wi-Fi, {@link OlaCloud} can still answer with battery, lock,
 * range and location from Ola's servers using the owner's token. This class
 * polls that endpoint in the background whenever a token is set, keeps the most
 * recent {@link OlaCloud.Status}, and lets any screen read it or listen for
 * updates — so every data tab can fill its empty fields from the cloud without
 * each one opening its own connection.
 *
 * <p>Read-only: it only ever calls the statistics poll, never a command.
 */
public final class CloudState {

    /** Notified on the UI thread after each successful or failed poll. */
    public interface Listener {
        void onCloud(OlaCloud.Status status);
    }

    private static final long POLL_MS = 60_000L;
    /** Skip a fresh network poll if the last one is younger than this. */
    private static final long FRESH_MS = 20_000L;

    private static CloudState instance;

    public static synchronized CloudState get(Context c) {
        if (instance == null) {
            instance = new CloudState(c.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ola-cloud");
        t.setDaemon(true);
        return t;
    });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile OlaCloud.Status latest;
    private volatile long latestAt;
    private volatile boolean polling;
    private volatile boolean active;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }
            pollNow();
            ui.postDelayed(this, POLL_MS);
        }
    };

    private CloudState(Context app) {
        this.app = app;
    }

    /** The most recent snapshot, or null if none succeeded yet. */
    public OlaCloud.Status latest() {
        return latest;
    }

    public long latestAt() {
        return latestAt;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Begins background polling if a token is set. Safe to call repeatedly. */
    public void start() {
        if (active || !OlaCloud.hasToken(app)) {
            return;
        }
        active = true;
        ui.removeCallbacks(loop);
        ui.post(loop);
    }

    public void stop() {
        active = false;
        ui.removeCallbacks(loop);
    }

    /** Forces one poll now unless a very recent snapshot already exists. */
    public void pollNow() {
        if (polling || !OlaCloud.hasToken(app)) {
            return;
        }
        if (latest != null && System.currentTimeMillis() - latestAt < FRESH_MS) {
            return;
        }
        polling = true;
        io.execute(() -> {
            final OlaCloud.Status s = OlaCloud.pollStatistics(app);
            polling = false;
            if (s != null && s.ok) {
                latest = s;
                latestAt = System.currentTimeMillis();
            }
            ui.post(() -> {
                for (Listener l : listeners) {
                    l.onCloud(s);
                }
            });
        });
    }
}
