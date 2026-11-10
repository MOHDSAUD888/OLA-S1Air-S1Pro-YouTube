package dev.olatool;

import android.content.SharedPreferences;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

/**
 * Submit a recovered dash passcode past the flaky Kanzi touch pad. Two ways,
 * both over the root gRPC shell:
 *
 * <ul>
 *   <li><b>Taps</b> — replays {@code input tap} at each digit's screen position.
 *       Needs the pad calibrated once: the centre of key "1" plus the column and
 *       row spacing (read them off the Mirror tab). No extra binary; works now.</li>
 *   <li><b>Frida</b> — injects {@code unlock.js} with {@code frida-inject} and
 *       calls {@code challengeUnlockByPasscode(pin,false)} on the live service.
 *       Needs {@code /data/local/tmp/frida-inject} (arm64 16.0.19) pushed once.</li>
 * </ul>
 *
 * <p>The PIN comes pre-filled from a successful crack in {@link PasscodeTab}.
 * Gated behind {@link Access#ADMIN}.
 */
public final class UnlockTab implements Tab {

    private static final String PROC = "com.olaelectric.profilemanagerservice";
    private static final String INJECT = "/data/local/tmp/frida-inject";
    private static final String SCRIPT = "/data/local/tmp/unlock.js";

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;
    private final SharedPreferences prefs;

    private final EditText pin;
    private final EditText x1, y1, dx, dy;
    private final TextView result;

    public UnlockTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.prefs = activity.getApplicationContext().getSharedPreferences("ola", 0);
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Unlock",
                "Submit the recovered passcode past the Kanzi pad — by replaying "
                        + "taps, or by injecting the real unlock call with Frida."));

        LinearLayout pinCard = Ui.card(activity);
        pinCard.addView(Ui.heading(activity, "Passcode"));
        pin = numberField("recovered PIN");
        pin.setText(prefs.getString("recovered_pin", ""));
        pinCard.addView(pin);
        body.addView(pinCard, topMargin(12));

        // --- tap method -----------------------------------------------------
        LinearLayout tapCard = Ui.card(activity);
        tapCard.addView(Ui.heading(activity, "Pad calibration (pixels)"));
        tapCard.addView(Ui.note(activity,
                "Centre of key “1”, then column spacing (dx) and row spacing "
                        + "(dy). Read the numbers off the Mirror tab. Grid is 1-9 then 0 "
                        + "under the middle column."));
        x1 = numberField("x of key 1");
        y1 = numberField("y of key 1");
        dx = numberField("dx (col spacing)");
        dy = numberField("dy (row spacing)");
        x1.setText(prefs.getString("pad_x1", ""));
        y1.setText(prefs.getString("pad_y1", ""));
        dx.setText(prefs.getString("pad_dx", ""));
        dy.setText(prefs.getString("pad_dy", ""));
        LinearLayout r1 = Ui.row(activity);
        r1.addView(x1, weight()); r1.addView(Ui.space(activity, 0), fixed(8)); r1.addView(y1, weight());
        LinearLayout r2 = Ui.row(activity);
        r2.addView(dx, weight()); r2.addView(Ui.space(activity, 0), fixed(8)); r2.addView(dy, weight());
        tapCard.addView(r1, topMargin(6));
        tapCard.addView(r2, topMargin(6));
        tapCard.addView(Ui.accent(activity, "Unlock by taps", v -> unlockByTaps()), topMargin(8));
        body.addView(tapCard, topMargin(12));

        // --- frida method ---------------------------------------------------
        LinearLayout fridaCard = Ui.card(activity);
        fridaCard.addView(Ui.heading(activity, "Frida inject"));
        fridaCard.addView(Ui.note(activity,
                "Needs " + INJECT + " (frida-inject 16.0.19 arm64) pushed once. "
                        + "Calls challengeUnlockByPasscode on the live service."));
        fridaCard.addView(Ui.button(activity, "Unlock by Frida", v -> unlockByFrida()), topMargin(8));
        body.addView(fridaCard, topMargin(12));

        result = Ui.codeBlock(Ui.text(activity, "", 13, Ui.TEXT));
        body.addView(result, topMargin(12));
    }

    // ------------------------------------------------------------------- taps

    private void unlockByTaps() {
        if (!gate()) return;
        String p = pin.getText().toString().trim();
        if (p.isEmpty()) {
            result.setText("Enter the PIN first.");
            return;
        }
        int gx1, gy1, gdx, gdy;
        try {
            gx1 = Integer.parseInt(x1.getText().toString().trim());
            gy1 = Integer.parseInt(y1.getText().toString().trim());
            gdx = Integer.parseInt(dx.getText().toString().trim());
            gdy = Integer.parseInt(dy.getText().toString().trim());
        } catch (NumberFormatException e) {
            result.setText("Fill all four calibration numbers (from Mirror).");
            return;
        }
        saveCalibration(p, gx1, gy1, gdx, gdy);

        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            int d = p.charAt(i) - '0';
            int col = (d == 0) ? 1 : (d - 1) % 3;
            int row = (d == 0) ? 3 : (d - 1) / 3;
            int x = gx1 + col * gdx;
            int y = gy1 + row * gdy;
            if (i > 0) cmd.append("; sleep 0.2; ");
            cmd.append("input tap ").append(x).append(' ').append(y);
        }
        result.setText("Tapping " + p.length() + " digits...");
        final String shell = cmd.toString();
        conn.run(rpc -> rpc.shell(shell, 20_000), (text, error) ->
                result.setText(error != null
                        ? "Tap failed: " + error.getMessage()
                        : "Taps sent. Watch the dash / Mirror for unlock."));
    }

    // ------------------------------------------------------------------ frida

    private void unlockByFrida() {
        if (!gate()) return;
        final String p = pin.getText().toString().trim();
        if (p.isEmpty()) {
            result.setText("Enter the PIN first.");
            return;
        }
        prefs.edit().putString("recovered_pin", p).apply();
        result.setText("Injecting with frida-inject...");

        conn.run(rpc -> {
            // Write the script with the PIN inlined (small, one chunk).
            String js = readAssetText("unlock.js").replace("__PIN__", p);
            String b64 = Base64.encodeToString(js.getBytes(), Base64.NO_WRAP);
            rpc.shell("echo " + b64 + " | base64 -d | tee " + SCRIPT + " >/dev/null", 15_000);
            rpc.shell("chmod 755 " + INJECT, 10_000);
            // timeout makes frida-inject run the script once and exit; its
            // send() output lands on stdout, which the shell returns to us.
            return rpc.shell("timeout 8 " + INJECT + " -n " + PROC + " -s " + SCRIPT, 20_000);
        }, (text, error) -> {
            if (error != null) {
                result.setText("Frida failed: " + error.getMessage());
            } else if (text == null || text.trim().isEmpty()) {
                result.setText("No output. Is " + INJECT + " present and is "
                        + PROC + " running?");
            } else {
                result.setText(text.trim());
            }
        });
    }

    // --------------------------------------------------------------- plumbing

    private boolean gate() {
        if (!Access.require(activity, Access.ADMIN)) return false;
        if (!conn.ready()) {
            result.setText("Connect to the scooter first.");
            return false;
        }
        return true;
    }

    private void saveCalibration(String p, int gx1, int gy1, int gdx, int gdy) {
        prefs.edit()
                .putString("recovered_pin", p)
                .putString("pad_x1", String.valueOf(gx1))
                .putString("pad_y1", String.valueOf(gy1))
                .putString("pad_dx", String.valueOf(gdx))
                .putString("pad_dy", String.valueOf(gdy))
                .apply();
    }

    private String readAssetText(String name) throws Exception {
        try (java.io.InputStream is = activity.getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    private EditText numberField(String hint) {
        EditText e = new EditText(activity);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setHint(hint);
        e.setTextSize(14f);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams fixed(int dp) {
        return new LinearLayout.LayoutParams(Ui.dp(activity, dp),
                LinearLayout.LayoutParams.WRAP_CONTENT);
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
        pin.setText(prefs.getString("recovered_pin", pin.getText().toString()));
    }

    @Override
    public void onHide() {
    }
}
