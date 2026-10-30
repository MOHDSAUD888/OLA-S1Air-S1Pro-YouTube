package dev.olatool;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.olatool.core.Dbc;
import dev.olatool.core.Live;

/**
 * The signal table, live.
 *
 * <p>With an empty search box this lists the messages actually seen on the bus,
 * newest state first, with a few of their signals. Type a message or signal
 * name and it shows every matching signal with its current value.
 *
 * <p>The whole list is one {@link TextView}: a hundred small views would be
 * rebuilt four times a second, which the dash does not like.
 */
public final class SignalsTab implements Tab {

    private static final int MAX_LINES = 300;
    private static final long RENDER_MS = 250;

    private final Bus bus;
    private final ScrollView page;
    private final TextView status;
    private final TextView table;
    private final EditText search;

    private boolean visible;
    private long lastRender;

    private final Bus.Listener listener = snapshot -> {
        if (visible) {
            render(snapshot);
        }
    };

    public SignalsTab(MainActivity activity, Conn conn, Bus bus) {
        this.bus = bus;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.text(activity, "Signals", 18, Ui.TEXT));

        TextView note = Ui.note(activity,
                "Decoded from can_database.json. Leave the box empty to list the "
                        + "messages seen so far; type part of a message or signal name "
                        + "to see its value.");
        note.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 10));
        body.addView(note);

        search = new EditText(activity);
        search.setHint("message or signal name");
        search.setTextSize(13);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackground(Ui.round(activity, Ui.CARD, 10, Ui.LINE));
        search.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8),
                Ui.dp(activity, 12), Ui.dp(activity, 8));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                lastRender = 0;
                render(bus.snapshot());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        body.addView(search);

        status = Ui.note(activity, "Waiting for the first frame ...");
        status.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
        body.addView(status);

        table = Ui.text(activity, "", 11, Ui.TEXT);
        table.setTypeface(android.graphics.Typeface.MONOSPACE);
        table.setTextIsSelectable(true);
        body.addView(table);

        TextView footer = Ui.note(activity,
                "Values are raw * scaling + offset, exactly as the table says. "
                        + "A dash means the vehicle has not reported that signal yet.");
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
            table.setText("Loading the signal table ...");
            return;
        }

        status.setText("frames " + snapshot.frames
                + "   pps " + snapshot.pps
                + "   messages " + snapshot.messagesSeen
                + "   signals " + snapshot.signalsSeen);

        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String text = query.isEmpty() ? seenMessages(live) : matchingSignals(live, query);
        table.setText(text);
    }

    /** One line per message seen, with up to three non-zero signals. */
    private String seenMessages(Live live) {
        Dbc dbc = live.dbc();
        Map<Integer, Integer> counts = live.counts();
        Map<String, Double> values = live.values();

        List<Integer> ids = new ArrayList<>(counts.keySet());
        Collections.sort(ids);

        StringBuilder sb = new StringBuilder(4096);
        if (ids.isEmpty()) {
            return "No frames yet. The vehicle may be asleep — press the red "
                    + "start button or plug in the charger.";
        }
        int shown = 0;
        for (int id : ids) {
            Dbc.Message message = dbc.message(id);
            String name = message == null ? "Unknown" : message.name;
            Integer count = counts.get(id);
            sb.append(String.format(Locale.ROOT, "0x%03X %-26s n=%-5d",
                    id, trim(name, 26), count == null ? 0 : count));

            int printed = 0;
            if (message != null) {
                for (Dbc.Signal signal : message.signals) {
                    Double value = values.get(signal.name);
                    if (value == null || value.isNaN() || value == 0.0) {
                        continue;
                    }
                    sb.append(' ').append(trim(signal.name, 18)).append('=')
                            .append(Ui.fmt(value, 2));
                    if (++printed >= 3) {
                        break;
                    }
                }
            }
            sb.append('\n');
            if (++shown >= MAX_LINES) {
                sb.append("... more messages\n");
                break;
            }
        }
        return sb.toString();
    }

    /** Every signal whose name or message name contains the query. */
    private String matchingSignals(Live live, String query) {
        Dbc dbc = live.dbc();
        Map<String, Double> values = live.values();

        StringBuilder sb = new StringBuilder(4096);
        int shown = 0;
        int total = 0;
        for (Dbc.Message message : dbc.messages()) {
            boolean messageHit = message.name.toLowerCase(Locale.ROOT).contains(query);
            for (Dbc.Signal signal : message.signals) {
                if (!messageHit && !signal.name.toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                total++;
                if (shown >= MAX_LINES) {
                    continue;
                }
                Double value = values.get(signal.name);
                sb.append(String.format(Locale.ROOT, "%-32s %10s %-5s %s%n",
                        trim(signal.name, 32),
                        value == null ? "-" : Ui.fmt(value, 3),
                        signal.units,
                        trim(message.name, 24)));
                shown++;
            }
        }
        if (shown == 0) {
            return "Nothing matches \"" + query + "\".";
        }
        if (total > shown) {
            sb.append("... ").append(total - shown).append(" more matches\n");
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
