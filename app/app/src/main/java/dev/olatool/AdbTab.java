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

    // Release assets the dash can pull on its own (see the "Dash self-install"
    // card). The dash downloads straight from GitHub over whatever Wi-Fi it is
    // on, so the APK bytes never pass through the phone.
    private static final String REL =
            "https://github.com/MOHDSAUD888/OLA-S1Air-S1Pro-YouTube/releases/download/";
    private static final String NAV_URL = REL + "s1mods-apks/Pegasus-S1Nav.apk";
    private static final String AA_URL = REL + "s1mods-apks/Pegasus-AndroidAuto.apk";
    private static final String CP_URL = REL + "s1mods-apks/Pegasus-CarPlay.apk";

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;
    private EditText input;
    private TextView output;
    private TextView adbResult;
    private EditText urlInput;
    private TextView urlResult;

    public AdbTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Root shell",
                "Runs as uid 0 through the scooter's own daemon. Pipes and flags work. "
                        + "This is the same channel scripts/ola.py uses."));

        body.addView(provisioning(), topMargin(0));

        body.addView(installCard(), topMargin(10));

        body.addView(urlInstallCard(), topMargin(10));

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
        output = Ui.codeBlock(Ui.text(activity, "", 12, Ui.TEXT));
        output.setTextIsSelectable(true);
        body.addView(output);

        TextView footer = Ui.note(activity,
                "The daemon has no shell of its own. If a command still misbehaves, "
                        + "push it as a .sh file and run that instead.");
        footer.setPadding(0, Ui.dp(activity, 10), 0, 0);
        body.addView(footer);
    }

    // ------------------------------------------------------------- apk install

    private TextView installResult;

    private View installCard() {
        LinearLayout card = Ui.card(activity);
        card.addView(Ui.heading(activity, "Install an app (.apk)"));
        TextView note = Ui.note(activity,
                "Pick any APK from this phone. It is staged on the scooter over the "
                        + "root channel and installed with pm. Overlay and write-settings "
                        + "app-ops are granted automatically for floating-nav style mods.");
        note.setPadding(0, 0, 0, Ui.dp(activity, 8));
        card.addView(note);

        card.addView(Ui.accent(activity, "Pick & install APK", v -> pickAndInstall()));

        installResult = Ui.text(activity, "", 11, Ui.DIM);
        installResult.setTypeface(android.graphics.Typeface.MONOSPACE);
        installResult.setTextIsSelectable(true);
        installResult.setPadding(0, Ui.dp(activity, 8), 0, 0);
        card.addView(installResult);
        return card;
    }

    private void pickAndInstall() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            installResult.setText("Connect to the scooter first.");
            installResult.setTextColor(Ui.WARN);
            return;
        }
        activity.pickApk(this::installApk);
    }

    private void installApk(android.net.Uri uri) {
        installResult.setTextColor(Ui.DIM);
        installResult.setText("Reading file ...");

        final java.io.File staged;
        final byte[] bytes;
        try {
            staged = stage(uri);
            bytes = readAll(new java.io.FileInputStream(staged));
        } catch (Exception e) {
            installResult.setText("Could not read the file: " + e.getMessage());
            installResult.setTextColor(Ui.BAD);
            return;
        }
        final String pkg = archivePackage(staged.getAbsolutePath());
        final String remote = "/data/local/tmp/pegasus_install.apk";

        conn.run(rpc -> {
            rpc.pushFile(bytes, remote, f -> activity.runOnUiThread(() ->
                    installResult.setText("Pushing APK ... " + Math.round(f * 100) + "%")));
            activity.runOnUiThread(() -> installResult.setText("Installing with pm ..."));
            String out = rpc.shell("pm install -r -g " + remote, 180_000);
            rpc.exec("rm -f " + remote, 15_000);
            if (pkg != null) {
                rpc.grantAppPermissions(pkg);
            }
            return out;
        }, (out, error) -> {
            staged.delete();
            if (error != null) {
                installResult.setText("Install failed: " + error.getMessage());
                installResult.setTextColor(Ui.BAD);
                return;
            }
            boolean ok = out != null
                    && out.toLowerCase(java.util.Locale.ROOT).contains("success");
            StringBuilder sb = new StringBuilder();
            sb.append(ok ? "Installed successfully."
                    : "pm: " + (out == null ? "(no output)" : out.trim()));
            if (pkg != null) {
                sb.append("\nPackage: ").append(pkg);
                if (ok) {
                    sb.append("\nGranted: overlay + write-settings.");
                }
            }
            installResult.setText(sb.toString());
            installResult.setTextColor(ok ? Ui.ACCENT : Ui.WARN);
        });
    }

    /** Copies the picked Uri into the app cache so pm and PackageManager can read it. */
    private java.io.File stage(android.net.Uri uri) throws Exception {
        java.io.File out = new java.io.File(activity.getCacheDir(), "pegasus_pick.apk");
        try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri);
             java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            if (in == null) {
                throw new java.io.IOException("cannot open the selected file");
            }
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        return out;
    }

    private byte[] readAll(java.io.InputStream in) throws Exception {
        try (java.io.InputStream src = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = src.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Reads the package name straight from the APK, for the permission grants. */
    private String archivePackage(String path) {
        try {
            android.content.pm.PackageInfo pi =
                    activity.getPackageManager().getPackageArchiveInfo(path, 0);
            return pi == null ? null : pi.packageName;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------- dash self-install (URL)

    private View urlInstallCard() {
        LinearLayout card = Ui.card(activity);
        card.addView(Ui.heading(activity, "Dash self-install (from URL)"));
        TextView note = Ui.note(activity,
                "The dash downloads the APK itself and installs it — the phone never "
                        + "handles the file. Put the dash on a Wi-Fi with internet (e.g. "
                        + "this phone's hotspot) first. Needs curl or wget on the dash.");
        note.setPadding(0, 0, 0, Ui.dp(activity, 8));
        card.addView(note);

        LinearLayout row = Ui.row(activity);
        row.addView(Ui.button(activity, "S1 Nav", v -> installUrl(NAV_URL, "com.saud.ball")), weight());
        row.addView(Ui.button(activity, "Android Auto",
                v -> installUrl(AA_URL, "com.saud.androidauto")), weightGap());
        card.addView(row, topMargin(4));
        card.addView(Ui.button(activity, "CarPlay",
                v -> installUrl(CP_URL, "com.saud.openplay")), topMargin(8));

        urlInput = new EditText(activity);
        urlInput.setHint("https://…/app.apk  (custom URL)");
        urlInput.setSingleLine(true);
        urlInput.setTextSize(12);
        urlInput.setTextColor(Ui.TEXT);
        urlInput.setHintTextColor(Ui.DIM);
        urlInput.setTypeface(android.graphics.Typeface.MONOSPACE);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlInput.setBackground(Ui.round(activity, Ui.CARD, 10, Ui.LINE));
        urlInput.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10),
                Ui.dp(activity, 12), Ui.dp(activity, 10));
        card.addView(urlInput, topMargin(8));

        card.addView(Ui.accent(activity, "Download on dash & install",
                v -> installUrl(urlInput.getText().toString().trim(), null)), topMargin(8));

        urlResult = Ui.text(activity, "", 11, Ui.DIM);
        urlResult.setTypeface(android.graphics.Typeface.MONOSPACE);
        urlResult.setTextIsSelectable(true);
        urlResult.setPadding(0, Ui.dp(activity, 8), 0, 0);
        card.addView(urlResult);
        return card;
    }

    private void installUrl(String url, String pkg) {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            urlResult.setText("Enter a valid http(s) URL first.");
            urlResult.setTextColor(Ui.WARN);
            return;
        }
        if (!conn.ready()) {
            urlResult.setText("Connect to the scooter first.");
            urlResult.setTextColor(Ui.WARN);
            return;
        }
        urlResult.setTextColor(Ui.DIM);
        urlResult.setText("Dash is downloading + installing…");
        conn.run(rpc -> rpc.installFromUrl(url, pkg, 300_000), (out, error) -> {
            if (error != null) {
                urlResult.setText("Failed: " + error.getMessage());
                urlResult.setTextColor(Ui.BAD);
                return;
            }
            boolean ok = out != null
                    && out.toLowerCase(java.util.Locale.ROOT).contains("success");
            urlResult.setText(out == null || out.trim().isEmpty()
                    ? "(no output — is curl/wget on the dash, and is it online?)"
                    : out.trim());
            urlResult.setTextColor(ok ? Ui.ACCENT : Ui.WARN);
        });
    }

    /** A weighted cell with a left gap, for the second button in a row. */
    private LinearLayout.LayoutParams weightGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(activity, 8);
        return lp;
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
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
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
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
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
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) {
            output.setText("Nothing to run.");
            return;
        }
        // The daemon splits the whole command on every run of whitespace, so a
        // raw newline would break sh -c and silently run only the first line.
        // Join lines into one ';'-separated command instead.
        String command = raw.replaceAll("[\\r\\n]+", " ; ");
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
