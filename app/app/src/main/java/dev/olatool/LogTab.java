package dev.olatool;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.olatool.core.Live;

/**
 * The raw frame log.
 *
 * <p>Newest frame first, with the id, the message name from the table, and the
 * eight payload bytes. Under it, the busiest ids, which is the quickest way to
 * see whether the bus is quiet or just missing one message.
 */
public final class LogTab implements Tab {

    private static final int MAX_LINES = 200;
    private static final int TOP_IDS = 10;
    private static final long RENDER_MS = 500;

    private final MainActivity activity;
    private final Bus bus;
    private final ScrollView page;
    private final TextView status;
    private final TextView log;
    private final TextView busiest;

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    private boolean visible;
    private long lastRender;

    private final Bus.Listener listener = snapshot -> {
        if (visible) {
            render(snapshot);
        }
    };

    public LogTab(MainActivity activity, Conn conn, Bus bus) {
        this.activity = activity;
        this.bus = bus;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.text(activity, "Frame log", 18, Ui.TEXT));

        TextView note = Ui.note(activity,
                "Every frame the daemon pushes, newest first. This is the same stream "
                        + "the other screens read, so opening this tab does not disturb them.");
        note.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 10));
        body.addView(note);

        status = Ui.text(activity, "Waiting for the first frame ...", 13, Ui.DIM);
        body.addView(status);

        body.addView(Ui.heading(activity, "Busiest ids"));
        busiest = Ui.text(activity, "-", 11, Ui.TEXT);
        busiest.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.addView(busiest);

        body.addView(Ui.heading(activity, "Frames"));

        log = Ui.text(activity, "", 11, Ui.TEXT);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        body.addView(log);

        TextView footer = Ui.note(activity,
                "The log keeps the last 500 frames in memory. It is not written to disk.");
        footer.setPadding(0, Ui.dp(activity, 12), 0, 0);
        body.addView(footer);
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        visible = true;
        bus.attach(listener);
        lastRender = 0;
        render(bus.snapshot());
    }

    @Override
    public void onHide() {
        visible = false;
        bus.detach(listener);
    }

    // --------------------------------------------------------------- render

    private void render(Live.Snapshot snapshot) {
        long now = System.currentTimeMillis();
        if (now - lastRender < RENDER_MS) {
            return;
        }
        lastRender = now;

        Live live = bus.live();
        if (live == null || snapshot == null) {
            status.setText(bus.state());
            return;
        }

        status.setText("frames " + snapshot.frames
                + "   pps " + snapshot.pps
                + "   messages " + snapshot.messagesSeen
                + "   signals " + snapshot.signalsSeen
                + "\n" + bus.state());

        List<Live.Entry> entries = live.log();
        if (entries.isEmpty()) {
            log.setText("No frames yet. The vehicle may be asleep — press the red "
                    + "start button or plug in the charger.");
        } else {
            StringBuilder sb = new StringBuilder(entries.size() * 56);
            int shown = 0;
            for (Live.Entry entry : entries) {
                sb.append(clock.format(new Date(entry.at))).append("  ")
                        .append(String.format(Locale.ROOT, "0x%03X", entry.id)).append("  ")
                        .append(String.format(Locale.ROOT, "%-24s", trim(entry.name, 24)))
                        .append(entry.hex).append('\n');
                if (++shown >= MAX_LINES) {
                    break;
                }
            }
            log.setText(sb.toString());
        }

        busiest.setText(topIds(live));
    }

    private static String topIds(Live live) {
        List<Map.Entry<Integer, Integer>> list = live.busiest();
        if (list.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(list.size() * 24);
        int shown = 0;
        for (Map.Entry<Integer, Integer> entry : list) {
            dev.olatool.core.Dbc.Message message = live.dbc().message(entry.getKey());
            sb.append(String.format(Locale.ROOT, "0x%03X %-22s %d%n",
                    entry.getKey(),
                    trim(message == null ? "Unknown" : message.name, 22),
                    entry.getValue()));
            if (++shown >= TOP_IDS) {
                break;
            }
        }
        return sb.toString();
    }

    private static String trim(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "~";
    }
}
