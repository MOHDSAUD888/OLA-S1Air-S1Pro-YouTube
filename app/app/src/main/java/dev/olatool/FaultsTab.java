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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.olatool.core.Faults;
import dev.olatool.core.Live;

/**
 * Faults, from the same stream.
 *
 * <p>Three lists and one table:
 *
 * <p>• active — the signal is 1 or more right now.
 * <br>• latched — the signal has been 1 at some point, even if it cleared.
 * <br>• simulated — added by the Dev tab, never sent to the vehicle.
 * <br>• table — all 194 known rows, searchable.
 *
 * <p>A fault is matched by turning the CAN signal name into camelCase and
 * looking that up, which is how the table is keyed.
 */
public final class FaultsTab implements Tab {

    private static final long RENDER_MS = 500;

    private final MainActivity activity;
    private final Bus bus;
    private final ScrollView page;

    private final TextView status;
    private final TextView activeView;
    private final TextView latchedView;
    private final TextView simulatedView;
    private final TextView table;
    private final EditText search;

    private boolean visible;
    private long lastRender;
    private String builtQuery;

    private final Bus.Listener listener = snapshot -> {
        if (visible) {
            render(snapshot);
        }
    };

    public FaultsTab(MainActivity activity, Conn conn, Bus bus) {
        this.activity = activity;
        this.bus = bus;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.text(activity, "Faults", 18, Ui.TEXT));

        TextView note = Ui.note(activity,
                "Read straight from the fault bits in the CAN stream. Nothing here is "
                        + "written to the scooter.");
        note.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 10));
        body.addView(note);

        status = Ui.text(activity, "Waiting for the first frame ...", 13, Ui.DIM);
        body.addView(status);

        activeView = section(body, "Active now", Ui.BAD);
        latchedView = section(body, "Latched (seen at least once)", Ui.WARN);
        simulatedView = section(body, "Simulated (local only)", Ui.DIM);

        body.addView(Ui.heading(activity, "Full table"));

        search = new EditText(activity);
        search.setHint("filter by title, id, component or text");
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
                builtQuery = null;
                render(bus.snapshot());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        body.addView(search);

        table = Ui.text(activity, "", 11, Ui.TEXT);
        table.setTypeface(android.graphics.Typeface.MONOSPACE);
        table.setTextIsSelectable(true);
        body.addView(table);

        TextView footer = Ui.note(activity,
                "A fault clearing does not remove it from the latched list; that is "
                        + "deliberate, so an intermittent fault is not missed. "
                        + "Clear it from the Dev tab.");
        footer.setPadding(0, Ui.dp(activity, 12), 0, 0);
        body.addView(footer);
    }

    private TextView section(LinearLayout parent, String heading, int color) {
        parent.addView(Ui.heading(activity, heading));
        TextView view = Ui.text(activity, "-", 12, color);
        view.setTextIsSelectable(true);
        view.setPadding(0, 0, 0, Ui.dp(activity, 8));
        parent.addView(view);
        return view;
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
        builtQuery = null;
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
        if (live == null) {
            status.setText(bus.state());
            return;
        }

        List<Faults.Fault> active = snapshot == null
                ? new ArrayList<>() : snapshot.activeFaults;
        List<Faults.Fault> latched = live.latchedFaults();
        List<Faults.Fault> simulated = bus.simulatedFaults();

        status.setText("active " + active.size()
                + "   latched " + latched.size()
                + "   table " + live.faults().size()
                + "   simulated " + simulated.size());
        status.setTextColor(active.isEmpty() ? Ui.ACCENT : Ui.BAD);

        activeView.setText(lines(active, "No active fault right now."));
        activeView.setTextColor(active.isEmpty() ? Ui.DIM : Ui.BAD);

        List<Faults.Fault> onlyLatched = latchedExcept(latched, active);
        latchedView.setText(lines(onlyLatched, "Nothing latched."));
        latchedView.setTextColor(onlyLatched.isEmpty() ? Ui.DIM : Ui.WARN);

        simulatedView.setText(lines(simulated, "No simulated fault."));

        rebuildTable(live);
    }

    private void rebuildTable(Live live) {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (query.equals(builtQuery)) {
            return;
        }
        builtQuery = query;

        StringBuilder sb = new StringBuilder(8192);
        int shown = 0;
        for (Faults.Fault fault : live.faults().all()) {
            if (!query.isEmpty() && !matches(fault, query)) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "%-8s %-10s %s%n",
                    fault.severity, trim(fault.component, 10), fault.title));
            shown++;
        }
        if (shown == 0) {
            sb.append("No row matches \"").append(query).append("\".");
        }
        table.setText(sb.toString());
    }

    private static boolean matches(Faults.Fault fault, String query) {
        return fault.id.toLowerCase(Locale.ROOT).contains(query)
                || fault.title.toLowerCase(Locale.ROOT).contains(query)
                || fault.component.toLowerCase(Locale.ROOT).contains(query)
                || fault.severity.toLowerCase(Locale.ROOT).contains(query)
                || fault.description.toLowerCase(Locale.ROOT).contains(query);
    }

    private static List<Faults.Fault> latchedExcept(List<Faults.Fault> latched,
                                                    List<Faults.Fault> active) {
        Set<String> ids = new HashSet<>();
        for (Faults.Fault fault : active) {
            ids.add(fault.id);
        }
        List<Faults.Fault> out = new ArrayList<>();
        for (Faults.Fault fault : latched) {
            if (!ids.contains(fault.id)) {
                out.add(fault);
            }
        }
        return out;
    }

    private static String lines(List<Faults.Fault> faults, String empty) {
        if (faults.isEmpty()) {
            return empty;
        }
        StringBuilder sb = new StringBuilder(faults.size() * 64);
        for (Faults.Fault fault : faults) {
            sb.append(fault.severity).append("  ")
                    .append(trim(fault.component, 10)).append("  ")
                    .append(fault.title).append('\n');
        }
        return sb.toString().trim();
    }

    private static String trim(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "~";
    }
}
