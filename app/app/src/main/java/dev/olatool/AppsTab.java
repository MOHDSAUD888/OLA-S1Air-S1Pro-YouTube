package dev.olatool;

import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * App &amp; Module manager — self-service control over installed apps and their
 * components, so routine fixes (disabling a stuck overlay service, force-stopping
 * a mod app, clearing a cache, uninstalling) can be done from the dash without a
 * root shell.
 *
 * <p>Two levels:
 * <ul>
 *   <li><b>App</b> — info, force-stop, enable/disable, clear cache, uninstall.
 *   <li><b>Component</b> — enable/disable one service/receiver/activity by name
 *       (e.g. {@code dev.olatube/.OverlayService} to kill a sticky 7-tap overlay
 *       whose touch zone keeps blocking other apps). This reaches non-exported
 *       components that no app chooser exposes.
 * </ul>
 *
 * <p>Everything runs as root over the live connection; nothing is destructive
 * without the ADMIN gate, and uninstall uses the danger button.
 */
public final class AppsTab implements Tab {

    // Known on-dash mod apps, as quick-picks.
    private static final String[][] PRESETS = {
            {"S1 Nav (ball)", "com.saud.ball"},
            {"Android Auto", "com.saud.androidauto"},
            {"CarPlay", "com.saud.openplay"},
            {"YouTube overlay", "dev.olatube"},
            {"SmartTube", "org.smarttube.stable"},
    };

    // Friendly "module" names for known packages, so the app list reads by name
    // instead of a package you have to remember. Built from PRESETS plus Pegasus.
    private static final Map<String, String> NAMES = new HashMap<>();
    static {
        for (String[] p : PRESETS) {
            NAMES.put(p[1], p[0]);
        }
        NAMES.put("dev.olatool", "Pegasus (this app)");
    }

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;
    private final TextView status;
    private final EditText pkgBox;
    private final EditText compBox;
    private final LinearLayout listBox;

    public AppsTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "App & Module manager",
                "Control installed apps and their individual services/receivers "
                        + "from the dash — no shell needed."));

        // --- Pick an app --------------------------------------------------
        pkgBox = input("package name, e.g. dev.olatube");
        body.addView(pkgBox, topMargin(12));

        LinearLayout picks = Ui.row(activity);
        for (int i = 0; i < PRESETS.length; i++) {
            if (i == 3) {
                body.addView(picks, topMargin(8));
                picks = Ui.row(activity);
            }
            final String p = PRESETS[i][1];
            picks.addView(Ui.button(activity, PRESETS[i][0], v -> pkgBox.setText(p)), weight());
            picks.addView(Ui.space(activity, 0), fixed(6));
        }
        body.addView(picks, topMargin(8));

        // Or load the real installed list from the scooter and pick by name.
        body.addView(Ui.accent(activity, "📋 Installed apps (from scooter)", v -> loadApps()), topMargin(8));
        listBox = Ui.column(activity);
        body.addView(listBox, topMargin(4));

        // --- App-level actions --------------------------------------------
        body.addView(Ui.heading(activity, "App"), topMargin(12));
        LinearLayout a1 = Ui.row(activity);
        a1.addView(Ui.button(activity, "Info", v -> info()), weight());
        a1.addView(Ui.space(activity, 0), fixed(8));
        a1.addView(Ui.button(activity, "Force-stop", v -> appAct("am force-stop ", "force-stopped")), weight());
        a1.addView(Ui.space(activity, 0), fixed(8));
        a1.addView(Ui.button(activity, "Clear cache", v -> clearCache()), weight());
        body.addView(a1, topMargin(8));

        LinearLayout a2 = Ui.row(activity);
        a2.addView(Ui.button(activity, "Enable", v -> appAct("pm enable ", "enabled")), weight());
        a2.addView(Ui.space(activity, 0), fixed(8));
        a2.addView(Ui.button(activity, "Disable", v -> appAct("pm disable-user --user 0 ", "disabled")), weight());
        a2.addView(Ui.space(activity, 0), fixed(8));
        a2.addView(Ui.danger(activity, "Uninstall", v -> appAct("pm uninstall ", "uninstalled")), weight());
        body.addView(a2, topMargin(8));

        // --- Component-level actions --------------------------------------
        body.addView(Ui.heading(activity, "Component (service / receiver / activity)"), topMargin(12));
        body.addView(Ui.note(activity,
                "Enter a component as package/class (e.g. dev.olatube/.OverlayService), or "
                        + "just .ClassName to use the app above. \"List\" shows the app's "
                        + "components so you can copy one."), topMargin(0));
        compBox = input("dev.olatube/.OverlayService");
        body.addView(compBox, topMargin(8));

        LinearLayout c1 = Ui.row(activity);
        c1.addView(Ui.button(activity, "Enable comp", v -> compAct(true)), weight());
        c1.addView(Ui.space(activity, 0), fixed(8));
        c1.addView(Ui.button(activity, "Disable comp", v -> compAct(false)), weight());
        c1.addView(Ui.space(activity, 0), fixed(8));
        c1.addView(Ui.button(activity, "List", v -> listComponents()), weight());
        body.addView(c1, topMargin(8));

        status = Ui.codeBlock(Ui.text(activity, "Pick an app, then tap Info.", 12, Ui.TEXT));
        status.setTextIsSelectable(true);
        body.addView(status, topMargin(12));
    }

    private EditText input(String hint) {
        EditText e = new EditText(activity);
        e.setHint(hint);
        e.setTextSize(13);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackground(Ui.round(activity, Ui.CARD, 10, Ui.LINE));
        e.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8), Ui.dp(activity, 12), Ui.dp(activity, 8));
        return e;
    }

    private String pkg() {
        return pkgBox.getText().toString().trim();
    }

    /** Resolve the component field to a pm-ready package/class token. */
    private String component() {
        String c = compBox.getText().toString().trim();
        if (c.isEmpty()) {
            return "";
        }
        if (c.contains("/")) {
            return c;
        }
        // bare class (".Foo" or "a.b.Foo") -> attach to the app above
        return pkg() + "/" + c;
    }

    private boolean ready() {
        if (!Access.require(activity, Access.ADMIN)) {
            return false;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return false;
        }
        if (pkg().isEmpty()) {
            status.setText("Enter or pick a package first.");
            return false;
        }
        return true;
    }

    private void info() {
        if (!ready()) {
            return;
        }
        String p = pkg();
        status.setText("Reading " + p + "…");
        String cmd = "echo PKG=" + p + ";"
                + "echo PRESENT=$(pm list packages " + p + " | grep -c " + p + ");"
                + "echo ENABLED=$(pm list packages -e " + p + " | grep -c " + p + ");"
                + "echo VER=$(dumpsys package " + p + " | grep -m1 versionName | cut -d= -f2);"
                + "echo PROC=$(pidof " + p + " | grep -c .)";
        conn.run(rpc -> rpc.shell(cmd, 15_000), (out, err) -> {
            if (err != null) {
                status.setText("Failed: " + err.getMessage());
                return;
            }
            String o = out == null ? "" : out;
            boolean present = "1".equals(val(o, "PRESENT"));
            if (!present) {
                status.setText(p + " is not installed.");
                return;
            }
            boolean enabled = "1".equals(val(o, "ENABLED"));
            boolean running = intVal(o, "PROC") > 0;
            status.setText(p + "\n"
                    + "Version: " + nz(val(o, "VER")) + "\n"
                    + "State:   " + (enabled ? "enabled" : "disabled") + "\n"
                    + "Running: " + (running ? "yes" : "no"));
        });
    }

    /** One app-level pm/am action. */
    private void appAct(String cmdPrefix, String doneWord) {
        if (!ready()) {
            return;
        }
        String p = pkg();
        status.setText(doneWord.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + doneWord.substring(1) + " " + p + "…");
        conn.run(rpc -> rpc.shell(cmdPrefix + p, 20_000), (out, err) ->
                status.setText(err != null
                        ? "Failed: " + err.getMessage()
                        : p + " " + doneWord + ".\n" + (out == null ? "" : out.trim())));
    }

    private void clearCache() {
        if (!ready()) {
            return;
        }
        String p = pkg();
        String cmd = "rm -rf /data/data/" + p + "/cache /data/data/" + p + "/code_cache";
        conn.run(rpc -> rpc.shell(cmd, 15_000), (out, err) ->
                status.setText(err != null ? "Failed: " + err.getMessage()
                        : "Cache cleared for " + p + "."));
    }

    private void compAct(boolean enable) {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        String c = component();
        if (c.isEmpty()) {
            status.setText("Enter a component (package/class) first.");
            return;
        }
        String cmd = "pm " + (enable ? "enable " : "disable ") + c;
        status.setText((enable ? "Enabling " : "Disabling ") + c + "…");
        conn.run(rpc -> rpc.shell(cmd, 15_000), (out, err) -> {
            if (err != null) {
                status.setText("Failed: " + err.getMessage());
                return;
            }
            String o = out == null ? "" : out.trim();
            status.setText((o.toLowerCase(java.util.Locale.ROOT).contains("new state")
                    ? o : (enable ? "Enabled " : "Disabled ") + c + "\n" + o));
        });
    }

    private void listComponents() {
        if (!ready()) {
            return;
        }
        String p = pkg();
        status.setText("Listing components of " + p + "… (can be slow)");
        // Declared services/receivers/activities for this package, de-duplicated.
        String cmd = "dumpsys package " + p
                + " | grep -oE '" + p + "/[A-Za-z0-9_.$]+' | sort -u";
        conn.run(rpc -> rpc.shell(cmd, 25_000), (out, err) -> {
            if (err != null) {
                status.setText("Failed: " + err.getMessage());
                return;
            }
            String o = out == null ? "" : out.trim();
            status.setText(o.isEmpty()
                    ? "No components found (or none exported). Type the class name manually."
                    : "Components of " + p + ":\n" + o);
        });
    }

    /** List third-party apps from the scooter as tappable rows (by module name). */
    private void loadApps() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        status.setText("Loading installed apps from the scooter…");
        conn.run(rpc -> rpc.shell("pm list packages -3", 20_000), (out, err) -> {
            listBox.removeAllViews();
            if (err != null) {
                status.setText("Failed: " + err.getMessage());
                return;
            }
            List<String> pkgs = new ArrayList<>();
            for (String line : (out == null ? "" : out).split("\\r?\\n")) {
                String t = line.trim();
                if (t.startsWith("package:")) {
                    pkgs.add(t.substring("package:".length()).trim());
                }
            }
            Collections.sort(pkgs, (a, b) -> friendlyName(a).compareToIgnoreCase(friendlyName(b)));
            for (String p : pkgs) {
                final String pk = p;
                String name = friendlyName(pk);
                String label = name.equals(pk) ? pk : name + "   ·   " + pk;
                Button row = Ui.button(activity, label, v -> {
                    pkgBox.setText(pk);
                    info();
                });
                listBox.addView(row, topMargin(6));
            }
            status.setText(pkgs.size() + " apps installed. Tap one to select it.");
        });
    }

    private static String friendlyName(String pkg) {
        String n = NAMES.get(pkg);
        return n != null ? n : pkg;
    }

    private static String val(String out, String key) {
        for (String line : out.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith(key + "=")) {
                return t.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    private static int intVal(String out, String key) {
        try {
            String v = val(out, key).replaceAll("[^0-9-]", "");
            return v.isEmpty() ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "?" : s;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams fixed(int dp) {
        return new LinearLayout.LayoutParams(Ui.dp(activity, dp), LinearLayout.LayoutParams.WRAP_CONTENT);
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
    }

    @Override
    public void onHide() {
    }
}
