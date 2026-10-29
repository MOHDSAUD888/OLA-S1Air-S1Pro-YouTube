package dev.olatool;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.olatool.core.Addr;
import dev.olatool.core.Rpc;

/**
 * Holds the one live connection and keeps every RPC off the UI thread.
 *
 * <p>There is a single {@code Conn} for the lifetime of the activity, so a
 * reconnect swaps the {@link Rpc} underneath without the screens having to be
 * rebuilt.
 */
public final class Conn {

    /** Work that needs the connection. Runs on the IO thread. */
    public interface Task<T> {
        T run(Rpc rpc) throws Exception;
    }

    /** Result of a {@link Task}, delivered on the UI thread. */
    public interface Done<T> {
        void done(T value, Exception error);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ola-io");
        t.setDaemon(true);
        return t;
    });
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile Rpc rpc;
    private volatile Addr.Target target;
    private volatile boolean ipv6;
    private volatile String note = "";

    public Rpc rpc() {
        return rpc;
    }

    public boolean ready() {
        return rpc != null;
    }

    /** True when the live link is IPv6. Mode switching is only safe then. */
    public boolean ipv6() {
        return ipv6;
    }

    public Addr.Target target() {
        return target;
    }

    public String note() {
        return note;
    }

    public void install(Rpc fresh, Addr.Target where, boolean overIpv6, String why) {
        Rpc old = this.rpc;
        this.rpc = fresh;
        this.target = where;
        this.ipv6 = overIpv6;
        this.note = why == null ? "" : why;
        if (old != null && old != fresh) {
            old.close();
        }
    }

    public void clear() {
        Rpc old = this.rpc;
        this.rpc = null;
        this.target = null;
        this.note = "";
        if (old != null) {
            old.close();
        }
    }

    public <T> void run(Task<T> task, Done<T> done) {
        io.execute(() -> {
            T value = null;
            Exception error = null;
            try {
                Rpc live = rpc;
                if (live == null) {
                    throw new IllegalStateException("Not connected yet.");
                }
                value = task.run(live);
            } catch (Exception e) {
                error = e;
            }
            final T v = value;
            final Exception e = error;
            if (done != null) {
                ui.post(() -> done.done(v, e));
            }
        });
    }

    public void post(Runnable r) {
        ui.post(r);
    }

    public void close() {
        io.shutdownNow();
        clear();
    }
}
