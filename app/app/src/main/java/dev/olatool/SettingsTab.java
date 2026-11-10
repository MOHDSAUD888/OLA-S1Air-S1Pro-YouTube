package dev.olatool;

import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Opens real Android screens on the scooter.
 *
 * <p>{@code am start} is used, never a tap: the dash is a Kanzi scene graph and
 * it ignores injected touches (docs/07-hmi-ui.md). An intent goes through the
 * activity manager and works regardless of what is drawing the screen.
 */
public final class SettingsTab implements Tab {

    private static final String[][] INTENTS = {
            {"Settings", "android.settings.SETTINGS"},
            {"Wi-Fi", "android.settings.WIFI_SETTINGS"},
            {"Bluetooth", "android.settings.BLUETOOTH_SETTINGS"},
            {"Apps", "android.settings.APPLICATION_SETTINGS"},
            {"Display", "android.settings.DISPLAY_SETTINGS"},
            {"Sound", "android.settings.SOUND_SETTINGS"},
            {"Battery", "android.settings.BATTERY_SAVER_SETTINGS"},
            {"Storage", "android.settings.INTERNAL_STORAGE_SETTINGS"},
            {"About", "android.settings.DEVICE_INFO_SETTINGS"},
            {"Notifications", "android.settings.NOTIFICATION_SETTINGS"},
            {"Location", "android.settings.LOCATION_SOURCE_SETTINGS"},
            {"Date & time", "android.settings.DATE_SETTINGS"},
    };

    private static final String HMI = "com.olaelectric.hmiclient/.MainActivity";
    private static final String DIAG = "com.ola.mfgapp/.MainActivity";

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;
    private final TextView result;

    private Button accRead;
    private Button accEdit;
    private Button accAdmin;
    private TextView accNote;

    public SettingsTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Settings",
                "Access level and the stock Android screens on the scooter's own display."));

        body.addView(accessSection());
        body.addView(Ui.space(activity, 12));

        body.addView(Ui.heading(activity, "Android screens"));
        TextView screensNote = Ui.note(activity,
                "These launch the stock Android activities on the scooter's own display. "
                        + "Nothing is written to the vehicle.");
        screensNote.setPadding(0, 0, 0, Ui.dp(activity, 10));
        body.addView(screensNote);

        LinearLayout grid = Ui.column(activity);
        for (int i = 0; i < INTENTS.length; i += 2) {
            LinearLayout row = Ui.row(activity);
            row.addView(intentButton(INTENTS[i][0], INTENTS[i][1]), weight());
            if (i + 1 < INTENTS.length) {
                LinearLayout.LayoutParams gap = weight();
                gap.leftMargin = Ui.dp(activity, 8);
                row.addView(intentButton(INTENTS[i + 1][0], INTENTS[i + 1][1]), gap);
            } else {
                row.addView(new View(activity), weight());
            }
            body.addView(row, rowMargin());
        }

        body.addView(Ui.heading(activity, "The dash itself"));

        LinearLayout dashRow = Ui.row(activity);
        dashRow.addView(Ui.button(activity, "Back to dash", v -> run(
                "input keyevent 3", "HOME sent. The dash is the home activity.")), weight());
        LinearLayout.LayoutParams gap = weight();
        gap.leftMargin = Ui.dp(activity, 8);
        dashRow.addView(Ui.button(activity, "Restart dash", v -> confirmRestart()), gap);
        body.addView(dashRow, rowMargin());

        LinearLayout diagRow = Ui.row(activity);
        diagRow.addView(Ui.button(activity, "Diagnostic app", v -> run(
                "am start -n " + DIAG, "Opened the Ola service app.")), weight());
        body.addView(diagRow, rowMargin());

        result = Ui.text(activity, "", 12, Ui.DIM);
        result.setPadding(0, Ui.dp(activity, 12), 0, 0);
        body.addView(result);
    }

    // ----------------------------------------------------------- access level

    private View accessSection() {
        Ui.Section s = Ui.section(activity, "Access level",
                "Controls what this tool is allowed to do. Defaults to Read-only.");
        LinearLayout chips = Ui.row(activity);
        accRead = Ui.button(activity, "Read-only", v -> setAccess(Access.READ));
        accEdit = Ui.button(activity, "Edit", v -> setAccess(Access.EDIT));
        accAdmin = Ui.button(activity, "Admin", v -> setAccess(Access.ADMIN));
        chips.addView(accRead, accWeight(0));
        chips.addView(accEdit, accWeight(8));
        chips.addView(accAdmin, accWeight(8));
        s.body.addView(chips);
        accNote = Ui.note(activity, "");
        accNote.setPadding(0, Ui.dp(activity, 10), 0, 0);
        s.body.addView(accNote);
        refreshAccess();
        return s.card;
    }

    private LinearLayout.LayoutParams accWeight(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(activity, leftDp);
        return lp;
    }

    private void setAccess(int level) {
        Access.setLevel(activity, level);
        refreshAccess();
    }

    private void refreshAccess() {
        int level = Access.level(activity);
        styleAccChip(accRead, level == Access.READ);
        styleAccChip(accEdit, level == Access.EDIT);
        styleAccChip(accAdmin, level == Access.ADMIN);
        String desc;
        if (level == Access.ADMIN) {
            desc = "Admin (full access): shell, ADB provisioning, mode switch, TechPack, "
                    + "token fetch — everything.";
        } else if (level == Access.EDIT) {
            desc = "Edit: read plus harmless actions (open Android screens). Mode switch, "
                    + "ADB, TechPack and token fetch stay blocked.";
        } else {
            desc = "Read-only: telemetry only. No command, no write, no mode change.";
        }
        accNote.setText(desc);
        accNote.setTextColor(level == Access.ADMIN ? Ui.WARN : Ui.DIM);
    }

    private void styleAccChip(Button b, boolean selected) {
        b.setAllCaps(false);
        b.setTextSize(13);
        if (selected) {
            b.setTextColor(Ui.ACCENT);
            b.setTypeface(null, android.graphics.Typeface.BOLD);
            b.setBackground(Ui.round(activity, Ui.alpha(Ui.ACCENT, 0x1A), 10, Ui.alpha(Ui.ACCENT, 0x55)));
        } else {
            b.setTextColor(Ui.DIM);
            b.setTypeface(null, android.graphics.Typeface.NORMAL);
            b.setBackground(Ui.round(activity, Ui.CARD, 10, Ui.LINE));
        }
    }

    private Button intentButton(String label, String action) {
        return Ui.button(activity, label, v -> run("am start -a " + action,
                "Asked Android to open " + label + "."));
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams rowMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, 8);
        return lp;
    }

    private void confirmRestart() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Restart the dash?")
                .setMessage("This force-stops com.olaelectric.hmiclient and starts it again. "
                        + "The screen goes blank for a few seconds. Do not do it while riding.")
                .setPositiveButton("Restart", (d, w) -> restartDash())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restartDash() {
        result.setText("Restarting the dash ...");
        result.setTextColor(Ui.DIM);
        conn.run(rpc -> {
            rpc.exec("am force-stop com.olaelectric.hmiclient", 20_000);
            Thread.sleep(1200);
            rpc.exec("am start -n " + HMI, 20_000);
            Thread.sleep(2000);
            rpc.exec("input keyevent 224", 10_000);
            rpc.exec("input keyevent 82", 10_000);
            return "Dash restarted.";
        }, (value, error) -> {
            result.setText(error == null ? value : "Failed: " + error.getMessage());
            result.setTextColor(error == null ? Ui.ACCENT : Ui.BAD);
        });
    }

    private void run(String command, String okText) {
        if (!Access.require(activity, Access.EDIT)) {
            return;
        }
        if (!conn.ready()) {
            result.setText("Connect to the scooter first.");
            result.setTextColor(Ui.WARN);
            return;
        }
        result.setText("Sending ...");
        result.setTextColor(Ui.DIM);
        conn.run(rpc -> rpc.exec(command, 30_000), (value, error) -> {
            if (error != null) {
                result.setText("Failed: " + error.getMessage());
                result.setTextColor(Ui.BAD);
            } else if (value.ok()) {
                result.setText(okText);
                result.setTextColor(Ui.ACCENT);
            } else {
                result.setText("The scooter said: " + value);
                result.setTextColor(Ui.WARN);
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
