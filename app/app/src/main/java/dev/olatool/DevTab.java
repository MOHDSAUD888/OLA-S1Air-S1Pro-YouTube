package dev.olatool;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import dev.olatool.core.Faults;
import dev.olatool.core.Live;

/**
 * Developer corner: what the app is connected to, and local-only switches.
 *
 * <p>Nothing on this screen is sent to the scooter. The simulated fault exists
 * only in this app's memory, so the fault screen can be tried without waiting
 * for a real fault to happen.
 */
public final class DevTab implements Tab {

    private static final long RENDER_MS = 1000;

    private final MainActivity activity;
    private final Conn conn;
    private final Bus bus;
    private final ScrollView page;

    private final TextView info;
    private final TextView simulatedView;
    private final EditText faultId;
    private final TextView result;

    private boolean visible;
    private long lastRender;

    private final Bus.Listener listener = snapshot -> {
        if (visible) {
            refresh(snapshot);
        }
    };

    public DevTab(MainActivity activity, Conn conn, Bus bus) {
        this.activity = activity;
        this.conn = conn;
        this.bus = bus;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.text(activity, "Developer", 18, Ui.TEXT));

        TextView note = Ui.note(activity,
                "Everything here is local. No command is sent to the scooter from this "
                        + "screen.");
        note.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 10));
        body.addView(note);

        LinearLayout state = Ui.card(activity);
        state.addView(Ui.heading(activity, "State"));
        info = Ui.text(activity, "-", 12, Ui.TEXT);
        info.setTypeface(android.graphics.Typeface.MONOSPACE);
        info.setTextIsSelectable(true);
        state.addView(info);
        body.addView(state, topMargin(0));

        body.addView(Ui.heading(activity, "Local switches"));

        result = Ui.text(activity, "", 12, Ui.DIM);
        result.setPadding(0, Ui.dp(activity, 0), 0, Ui.dp(activity, 8));
        body.addView(result);

        LinearLayout row1 = Ui.row(activity);
        row1.addView(Ui.button(activity, "Reset live data", v -> {
            bus.reset();
            result.setText("Live data cleared. The next frame starts a new set.");
            result.setTextColor(Ui.ACCENT);
        }), weight());
        LinearLayout.LayoutParams gap = weight();
        gap.leftMargin = Ui.dp(activity, 8);
        row1.addView(Ui.button(activity, "Clear latched", v -> {
            bus.clearLatched();
            result.setText("Latched faults cleared.");
            result.setTextColor(Ui.ACCENT);
        }), gap);
        body.addView(row1, topMargin(8));

        body.addView(Ui.button(activity, "Clear simulated faults", v -> {
            bus.clearSimulated();
            result.setText("Simulated faults cleared.");
            result.setTextColor(Ui.ACCENT);
        }), topMargin(8));

        body.addView(Ui.heading(activity, "Simulate a fault"));

        faultId = new EditText(activity);
        faultId.setHint("fault id, e.g. bmsPrechargeFail");
        faultId.setTextSize(13);
        faultId.setTextColor(Ui.TEXT);
        faultId.setHintTextColor(Ui.DIM);
        faultId.setSingleLine(true);
        faultId.setTypeface(android.graphics.Typeface.MONOSPACE);
        faultId.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        faultId.setBackground(Ui.round(activity, Ui.CARD, 10, Ui.LINE));
        faultId.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8),
                Ui.dp(activity, 12), Ui.dp(activity, 8));
        body.addView(faultId);

        body.addView(Ui.button(activity, "Add simulated fault", v -> simulate()), topMargin(8));

        body.addView(Ui.heading(activity, "Simulated now"));
        simulatedView = Ui.text(activity, "-", 12, Ui.DIM);
        simulatedView.setTextIsSelectable(true);
        body.addView(simulatedView);

        TextView footer = Ui.note(activity,
                "The id is the camelCase form of the CAN signal name, which is also "
                        + "the id used by faults.json. A wrong id is reported instead of "
                        + "being guessed.");
        footer.setPadding(0, Ui.dp(activity, 12), 0, 0);
        body.addView(footer);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
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
        visible = true;
        bus.attach(listener);
        lastRender = 0;
        refresh(bus.snapshot());
    }

    @Override
    public void onHide() {
        visible = false;
        bus.detach(listener);
    }

    private void simulate() {
        String id = faultId.getText().toString().trim();
        if (id.isEmpty()) {
            result.setText("Type a fault id first.");
            result.setTextColor(Ui.WARN);
            return;
        }
        if (bus.live() == null) {
            result.setText("The fault table is still loading.");
            result.setTextColor(Ui.WARN);
            return;
        }
        if (bus.simulate(id)) {
            result.setText("Added " + id + " (local only).");
            result.setTextColor(Ui.ACCENT);
            faultId.setText("");
            refresh(bus.snapshot());
        } else {
            result.setText("No fault with id \"" + id + "\" in the table.");
            result.setTextColor(Ui.BAD);
        }
    }

    private void refresh(Live.Snapshot snapshot) {
        long now = System.currentTimeMillis();
        if (now - lastRender < RENDER_MS) {
            return;
        }
        lastRender = now;

        StringBuilder sb = new StringBuilder(512);
        sb.append("connection  ");
        if (conn.ready()) {
            sb.append(conn.target()).append('\n');
            sb.append("link        ").append(conn.ipv6() ? "IPv6" : "IPv4").append('\n');
            if (!conn.note().isEmpty()) {
                sb.append("note        ").append(conn.note()).append('\n');
            }
        } else {
            sb.append("not connected\n");
        }
        sb.append("bus         ").append(bus.state()).append('\n');

        Live live = bus.live();
        if (live != null) {
            sb.append("table       ").append(live.dbc().size()).append(" messages, ")
                    .append(live.dbc().signalCount()).append(" signals\n");
            sb.append("faults      ").append(live.faults().size()).append(" known\n");
        } else {
            sb.append("table       loading\n");
        }
        if (snapshot != null) {
            sb.append("frames      ").append(snapshot.frames)
                    .append("   pps ").append(snapshot.pps).append('\n');
        }
        info.setText(sb.toString());

        List<Faults.Fault> simulated = bus.simulatedFaults();
        if (simulated.isEmpty()) {
            simulatedView.setText("-");
        } else {
            StringBuilder list = new StringBuilder();
            for (Faults.Fault fault : simulated) {
                list.append(fault.severity).append("  ").append(fault.title).append('\n');
            }
            simulatedView.setText(list.toString().trim());
        }
    }
}
