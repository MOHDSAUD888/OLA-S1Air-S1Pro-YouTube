package dev.olatool;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The TechPack demo.
 *
 * <p>The daemon has a {@code TechPackUpdate(ConfigType)} call and the Ola
 * manufacturing app uses it. What the {@code type} string must contain is not
 * in the daemon's strings and not a literal in the app's dex, so this screen
 * sends whatever the user picks and reports exactly what came back. Treat the
 * result as an observation, not a feature.
 */
public final class TechPackTab implements Tab {

    private static final String[] CANDIDATES = {
            "on", "off", "ON", "OFF", "enable", "disable",
            "techpack_on", "techpack_off", "true", "false", "1", "0"
    };

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;
    private EditText type;
    private TextView result;

    public TechPackTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.text(activity, "TechPack", 18, Ui.TEXT));

        TextView note = Ui.note(activity,
                "This calls TechPackUpdate on the scooter's daemon. The call exists and "
                        + "the Ola manufacturing app uses it, but the value the type field "
                        + "expects was not recoverable from the device. In this demo it may "
                        + "simply do nothing.");
        note.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 12));
        body.addView(note);

        LinearLayout card = Ui.card(activity);
        card.addView(Ui.heading(activity, "ConfigType.type"));

        type = new EditText(activity);
        type.setText("techpack_on");
        type.setTextSize(14);
        type.setTextColor(Ui.TEXT);
        type.setHintTextColor(Ui.DIM);
        type.setTypeface(android.graphics.Typeface.MONOSPACE);
        type.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        type.setSingleLine(true);
        type.setBackground(Ui.round(activity, Ui.BG, 8, Ui.LINE));
        type.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8),
                Ui.dp(activity, 12), Ui.dp(activity, 8));
        card.addView(type);

        TextView pick = Ui.note(activity, "Try one of these:");
        pick.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 6));
        card.addView(pick);

        LinearLayout chips = Ui.row(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < CANDIDATES.length; i++) {
            String value = CANDIDATES[i];
            android.widget.Button chip = Ui.button(activity, value, v -> type.setText(value));
            chip.setTextSize(11);
            chip.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 4),
                    Ui.dp(activity, 6), Ui.dp(activity, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.leftMargin = Ui.dp(activity, 4);
            }
            chips.addView(chip, lp);
            if (i % 4 == 3 && i + 1 < CANDIDATES.length) {
                card.addView(chips);
                chips = Ui.row(activity);
            }
        }
        card.addView(chips);
        body.addView(card, topMargin(0));

        body.addView(Ui.accent(activity, "Send TechPackUpdate", v -> send()), topMargin(10));

        result = Ui.text(activity, "", 13, Ui.TEXT);
        result.setTypeface(android.graphics.Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setBackground(Ui.round(activity, Ui.CARD, 10, Ui.LINE));
        result.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10),
                Ui.dp(activity, 12), Ui.dp(activity, 10));
        body.addView(result, topMargin(10));

        TextView footer = Ui.note(activity,
                "This changes vehicle configuration, not a display setting. Keep the "
                        + "scooter parked and stationary, and note the exact reply before "
                        + "you try a different value.");
        footer.setPadding(0, Ui.dp(activity, 10), 0, 0);
        body.addView(footer);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }

    private void send() {
        String value = type.getText().toString();
        if (!conn.ready()) {
            show("Connect to the scooter first.", Ui.WARN);
            return;
        }
        show("sending type=\"" + value + "\" ...", Ui.DIM);
        conn.run(rpc -> rpc.techPackUpdate(value), (update, error) -> {
            if (error != null) {
                show("Call failed:\n" + error.getMessage(), Ui.BAD);
                return;
            }
            show("ConfigType.type = \"" + value + "\"\n"
                            + "status  = " + update.statusName() + " (" + update.status + ")\n"
                            + "message = " + (update.message.isEmpty() ? "(empty)" : update.message),
                    update.ok() ? Ui.ACCENT : Ui.WARN);
        });
    }

    private void show(String text, int color) {
        result.setText(text);
        result.setTextColor(color);
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
    }
}
