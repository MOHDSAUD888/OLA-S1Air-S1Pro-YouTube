package dev.olatool;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;

import dev.olatool.core.Provision;

/**
 * A root shell over gRPC, plus reversible wireless ADB.
 *
 * <p>{@code ExecuteADBCommand} does not use a shell: it splits the string on
 * whitespace and execs it. So every command here is wrapped in {@code sh -c}
 * with its spaces replaced by {@code ${IFS}}, which the shell expands back.
 * That is what makes pipes, redirection and flags with values work.
 *
 * <p>The ADB buttons do what Axiom does, but without Shizuku: they write a
 * public key to {@code /data/local/tmp}, bind-mount it over {@code /adb_keys},
 * and set the network mode to {@code mfg}. The private key never leaves this
 * phone. Undo removes the mount and puts the mode back.
 */
public final class AdbTab implements Tab {

    private static final String PREFS = "ola";
    private static final String KEY_PRIVATE = "adb_private_pem";
    private static final String KEY_PUBLIC = "adb_public_pem";
    private static final String KEY_PREVIOUS = "adb_previous_mode";

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;
    private EditText input;
    private TextView output;
    private TextView adbResult;

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

        body.addView(provisioning(), topMargin(0));

        body.addView(Ui.heading(activity, "Command"));

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

    // ---------------------------------------------------------- provisioning

    private View provisioning() {
        LinearLayout card = Ui.card(activity);
        card.addView(Ui.heading(activity, "Wireless ADB (reversible)"));

        TextView note = Ui.note(activity,
                "Writes a public key to /data/local/tmp, bind-mounts it over /adb_keys, "
                        + "and sets the network mode to mfg. Nothing is flashed and the "
                        + "mount disappears on reboot. Undo puts the mode back.");
        note.setPadding(0, 0, 0, Ui.dp(activity, 8));
        card.addView(note);

        LinearLayout row = Ui.row(activity);
        row.addView(Ui.accent(activity, "Unlock adb", v -> confirmProvision()), weight());
        LinearLayout.LayoutParams gap = weight();
        gap.leftMargin = Ui.dp(activity, 8);
        row.addView(Ui.button(activity, "Undo", v -> undo()), gap);
        card.addView(row, topMargin(0));

        card.addView(Ui.button(activity, "Status", v -> status()), topMargin(8));

        adbResult = Ui.text(activity, "", 11, Ui.DIM);
        adbResult.setTypeface(android.graphics.Typeface.MONOSPACE);
        adbResult.setTextIsSelectable(true);
        adbResult.setPadding(0, Ui.dp(activity, 8), 0, 0);
        card.addView(adbResult);

        return card;
    }

    private void confirmProvision() {
        new AlertDialog.Builder(activity)
                .setTitle("Unlock wireless ADB?")
                .setMessage("This writes a key you control to the scooter and opens "
                        + "adbd on port 5555.\n\n"
                        + "• the key is stored in this app only\n"
                        + "• the mount is removed by Undo or a reboot\n"
                        + "• the network mode is set to mfg and restored by Undo\n\n"
                        + "Nothing is flashed. Do this on your own scooter.")
                .setPositiveButton("Unlock", (d, w) -> provision())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void provision() {
        if (!conn.ready()) {
            adbResult.setText("Connect to the scooter first.");
            adbResult.setTextColor(Ui.WARN);
            return;
        }
        adbResult.setText("Writing the key ...");
        adbResult.setTextColor(Ui.DIM);
        conn.run(rpc -> {
            String previous = rpc.networkMode();
            KeyPair pair = keyPair();
            String line = Provision.publicKeyLine((RSAPublicKey) pair.getPublic(), "olatool");
            prefs().edit().putString(KEY_PREVIOUS, previous == null ? "" : previous).apply();
            return rpc.runScript("olatool-adb.sh",
                    Provision.provisionScript(line, previous), 60_000);
        }, (value, error) -> {
            if (error != null) {
                adbResult.setText("Failed: " + error.getMessage());
                adbResult.setTextColor(Ui.BAD);
            } else {
                adbResult.setText(value + "\n\nNow try: adb connect <address>:5555");
                adbResult.setTextColor(Ui.ACCENT);
            }
        });
    }

    private void undo() {
        if (!conn.ready()) {
            adbResult.setText("Connect to the scooter first.");
            adbResult.setTextColor(Ui.WARN);
            return;
        }
        adbResult.setText("Undoing ...");
        adbResult.setTextColor(Ui.DIM);
        String previous = prefs().getString(KEY_PREVIOUS, "");
        conn.run(rpc -> rpc.runScript("olatool-adb-undo.sh",
                Provision.undoScript(previous), 30_000), (value, error) -> {
            if (error != null) {
                adbResult.setText("Failed: " + error.getMessage());
                adbResult.setTextColor(Ui.BAD);
            } else {
                adbResult.setText(value);
                adbResult.setTextColor(Ui.ACCENT);
            }
        });
    }

    private void status() {
        if (!conn.ready()) {
            adbResult.setText("Connect to the scooter first.");
            adbResult.setTextColor(Ui.WARN);
            return;
        }
        adbResult.setText("Checking ...");
        adbResult.setTextColor(Ui.DIM);
        conn.run(rpc -> rpc.runScript("olatool-status.sh",
                Provision.statusScript(), 30_000), (value, error) -> {
            if (error != null) {
                adbResult.setText("Failed: " + error.getMessage());
                adbResult.setTextColor(Ui.BAD);
            } else {
                adbResult.setText(value);
                adbResult.setTextColor(Ui.TEXT);
            }
        });
    }

    /**
     * Reuses the key in preferences, or makes one and stores it. The private
     * half is never printed, logged, or sent anywhere.
     */
    private KeyPair keyPair() throws Exception {
        SharedPreferences prefs = prefs();
        String storedPrivate = prefs.getString(KEY_PRIVATE, null);
        String storedPublic = prefs.getString(KEY_PUBLIC, null);
        if (storedPrivate != null && storedPublic != null) {
            try {
                PrivateKey privateKey = Provision.parsePrivateKey(storedPrivate);
                PublicKey publicKey = Provision.parsePublicKey(storedPublic);
                return new KeyPair(publicKey, privateKey);
            } catch (Exception ignored) {
                // A broken saved key is replaced rather than blocking the feature.
            }
        }
        KeyPair pair = Provision.newKeyPair();
        prefs.edit()
                .putString(KEY_PRIVATE, Provision.privateKeyPem(pair.getPrivate()))
                .putString(KEY_PUBLIC, Provision.publicKeyPem(pair.getPublic()))
                .apply();
        return pair;
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ----------------------------------------------------------------- shell

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
