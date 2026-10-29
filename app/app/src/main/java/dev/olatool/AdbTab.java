package dev.olatool;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A root shell, over gRPC.
 *
 * <p>{@code ExecuteADBCommand} does not use a shell: it splits the string on
 * whitespace and execs it. So every command here is wrapped in {@code sh -c}
 * with its spaces replaced by {@code ${IFS}}, which the shell expands back.
 * That is what makes pipes, redirection and flags with values work.
 */
public final class AdbTab implements Tab {

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;
    private EditText input;
    private TextView output;

    public AdbTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.text(activity, "Root shell", 18, Ui.TEXT));

        TextView note = Ui.note(activity,
                "Runs as uid 0 through the scooter's own daemon. Pipes and flags work. "
                        + "This is the same channel scripts/ola.py uses.");
        note.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 12));
        body.addView(note);

        input = new EditText(activity);
        input.setText("getprop ro.build.version.release");
        input.setTextSize(13);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackground(Ui.round(activity, Ui.CARD, 10, Ui.LINE));
        input.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10),
                Ui.dp(activity, 12), Ui.dp(activity, 10));
        body.addView(input);

        LinearLayout buttons = Ui.row(activity);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gap.leftMargin = Ui.dp(activity, 8);
        buttons.addView(Ui.accent(activity, "Run", v -> run()), weight());
        buttons.addView(Ui.button(activity, "Clear", v -> {
            input.setText("");
            output.setText("");
        }), gap);
        body.addView(buttons, topMargin(8));

        body.addView(Ui.heading(activity, "Output"));
        output = Ui.text(activity, "", 12, Ui.TEXT);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setBackground(Ui.round(activity, Ui.CARD, 10, Ui.LINE));
        output.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10),
                Ui.dp(activity, 12), Ui.dp(activity, 10));
        body.addView(output);

        TextView footer = Ui.note(activity,
                "The daemon has no shell of its own. If a command still misbehaves, "
                        + "push it as a .sh file and run that instead.");
        footer.setPadding(0, Ui.dp(activity, 10), 0, 0);
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

    private void run() {
        String command = input.getText().toString().trim();
        if (command.isEmpty()) {
            output.setText("Nothing to run.");
            return;
        }
        if (!conn.ready()) {
            output.setText("Connect to the scooter first.");
            return;
        }
        output.setText("...");
        conn.run(rpc -> rpc.shell(command, 60_000), (text, error) -> {
            if (error != null) {
                output.setText("Failed: " + error.getMessage());
            } else if (text == null || text.trim().isEmpty()) {
                output.setText("(no output)");
            } else {
                output.setText(text);
            }
        });
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
