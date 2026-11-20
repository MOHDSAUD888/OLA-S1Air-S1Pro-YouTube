package dev.olatool;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Android Auto / Apple CarPlay projection helper and readiness check.
 *
 * <p>The projection apps themselves are third-party and run on the dash:
 * <ul>
 *   <li><b>Android Auto</b> — {@code com.saud.androidauto} (an Open Headunit
 *       build), a wireless AA receiver.</li>
 *   <li><b>CarPlay</b> — {@code com.saud.openplay} (a DiPlay / xcertplay
 *       fork), software CarPlay over an extracted Carlinkit MFi identity.</li>
 * </ul>
 * Install them with the "Pick &amp; install APK" card on the Root-shell tab, then
 * use the buttons here to launch and to see whether a connection can work.
 *
 * <p><b>Why the readiness check matters.</b> Decompiling OpenPlay shows wireless
 * CarPlay needs iAP2 over a Bluetooth RFCOMM socket. When the head unit's
 * Bluetooth has no socket manager the app gives up with
 * {@code BLUETOOTH_UNUSABLE} ("Wireless CarPlay cannot work here. Use a USB data
 * cable."). The MoveOS Bluetooth stack trips exactly this, which is why iPhone
 * pairing fails. Wireless also wants Wi-Fi Direct, which cannot run while the
 * dash is joined to a Wi-Fi hotspot ({@code WIFI_DIRECT_BESIDE_HOTSPOT}). The
 * dwc3 controller does support USB host, so a USB-cable (wired) connection is the
 * path that actually works. This tab reports those three facts so the user knows
 * which path to take instead of fighting a pairing that cannot succeed.
 */
public final class ProjectionTab implements Tab {

    private static final String AA_PKG = "com.saud.androidauto";
    private static final String AA_ACT = AA_PKG + "/com.andrerinas.openheadunit.main.MainActivity";
    private static final String CP_PKG = "com.saud.openplay";
    private static final String CP_ACT = CP_PKG + "/com.shilapi.xcertplay.DiPlayActivity";

    // Floating navigation ("Balls"): an accessibility-service overlay giving
    // Back / Home / shortcuts on top of the dash, useful while a projection app
    // is full-screen. It ships a SET_SCOOTER_LOCKED broadcast that its own
    // service listens to — locked=true tears the floating button down, false
    // brings it back — which is exactly a lock/unlock toggle.
    private static final String BALL_PKG = "com.saud.ball";
    private static final String BALL_RECV = BALL_PKG + "/.core.QuickBallCommandReceiver";
    private static final String BALL_LOCK_ACTION = BALL_PKG + ".action.SET_SCOOTER_LOCKED";
    private static final String BALL_ACT = BALL_PKG + "/.ui.MainActivity";
    // The ball is drawn by an accessibility service. The lock/unlock broadcast
    // only has something to toggle while this service is enabled and alive —
    // MoveOS/Android-10 silently drops accessibility services across reboots and
    // "optimize" sweeps, which is the usual reason lock/unlock quietly stops
    // doing anything. enabled_accessibility_services uses the fully-qualified
    // class name: package/package.Class.
    private static final String BALL_SVC =
            BALL_PKG + "/" + BALL_PKG + ".core.QuickBallService";

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;
    private final TextView status;

    public ProjectionTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Phone projection",
                "Launch Android Auto / CarPlay on the dash and check whether a "
                        + "connection can actually work. Install the apps first from "
                        + "the Root-shell tab's \"Pick & install APK\" card."));

        // --- Launch (one projection app at a time) --------------------------
        LinearLayout launch = Ui.row(activity);
        launch.addView(Ui.accent(activity, "Android Auto", v -> launch(AA_PKG, AA_ACT, "Android Auto")), weight());
        launch.addView(Ui.space(activity, 0), fixed(8));
        launch.addView(Ui.accent(activity, "CarPlay", v -> launch(CP_PKG, CP_ACT, "CarPlay")), weight());
        body.addView(launch, topMargin(12));

        LinearLayout tools = Ui.row(activity);
        tools.addView(Ui.button(activity, "Check readiness", v -> checkReadiness()), weight());
        tools.addView(Ui.space(activity, 0), fixed(8));
        tools.addView(Ui.button(activity, "BT discoverable", v -> discoverable()), weight());
        body.addView(tools, topMargin(8));

        status = Ui.codeBlock(Ui.text(activity, "Tap \"Check readiness\" to test this dash.", 12, Ui.TEXT));
        status.setTextIsSelectable(true);
        body.addView(status, topMargin(8));

        // --- Floating nav lock/unlock ---------------------------------------
        body.addView(Ui.heading(activity, "Floating nav (dash)"), topMargin(12));
        body.addView(Ui.note(activity,
                "Back / Home / shortcut buttons that float on top of the dash — handy "
                        + "while CarPlay or Android Auto is full-screen. Lock hides the "
                        + "floating button; Unlock brings it back.\n\n"
                        + "If lock/unlock does nothing, the ball's accessibility service was "
                        + "switched off (common after a dash reboot). Tap \"Fix nav\" to turn "
                        + "it back on, or \"Check nav\" to see its state."), topMargin(0));
        LinearLayout navRow = Ui.row(activity);
        navRow.addView(Ui.button(activity, "🔒 Lock (hide)", v -> navLock(true)), weight());
        navRow.addView(Ui.space(activity, 0), fixed(8));
        navRow.addView(Ui.button(activity, "🔓 Unlock (show)", v -> navLock(false)), weight());
        body.addView(navRow, topMargin(8));
        LinearLayout navFixRow = Ui.row(activity);
        navFixRow.addView(Ui.accent(activity, "🛠 Fix nav", v -> navFix()), weight());
        navFixRow.addView(Ui.space(activity, 0), fixed(8));
        navFixRow.addView(Ui.button(activity, "Check nav", v -> navCheck()), weight());
        body.addView(navFixRow, topMargin(8));
        body.addView(Ui.button(activity, "Open nav settings", v -> openNav()), topMargin(8));

        // --- What the readiness check is telling you ------------------------
        body.addView(Ui.heading(activity, "How projection connects"), topMargin(12));
        body.addView(Ui.note(activity,
                "Wireless CarPlay needs iAP2 over a Bluetooth RFCOMM socket. MoveOS "
                        + "Bluetooth usually has no socket manager, so the app reports "
                        + "BLUETOOTH_UNUSABLE and iPhone pairing fails — wireless CarPlay "
                        + "cannot work here.\n\n"
                        + "Wireless also wants Wi-Fi Direct, which will not start while the "
                        + "dash is joined to a Wi-Fi hotspot. Turn the hotspot off or use "
                        + "the app's \"Existing Wi-Fi (same LAN)\" mode.\n\n"
                        + "The dash does have USB host (dwc3), so a USB data cable from the "
                        + "iPhone to the scooter's USB port — wired CarPlay — is the path "
                        + "that bypasses Bluetooth and actually works.\n\n"
                        + "Android Auto is separate: Google disabled wireless projection to "
                        + "Android-10 head units in Android Auto 16.4+, so AA here needs a "
                        + "USB cable too."), topMargin(0));
    }

    /** Launch one projection app; stop the other first (only one can run). */
    private void launch(String pkg, String activityName, String label) {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        String other = pkg.equals(AA_PKG) ? CP_PKG : AA_PKG;
        status.setText("Launching " + label + "…");
        conn.run(rpc -> {
            // Confirm it is installed, else say so rather than failing opaquely.
            String installed = rpc.shell("pm list packages " + pkg, 10_000);
            if (installed == null || !installed.contains(pkg)) {
                return "NOT_INSTALLED";
            }
            rpc.shell("am force-stop " + other, 8_000);
            rpc.shell("am start -n " + activityName, 10_000);
            return "OK";
        }, (out, error) -> {
            if (error != null) {
                status.setText(label + " launch failed: " + error.getMessage());
            } else if ("NOT_INSTALLED".equals(out)) {
                status.setText(label + " is not installed yet.\nInstall it from Root shell → "
                        + "\"Pick & install APK\" first.");
            } else {
                status.setText(label + " launched on the dash.\nOpen Mirror to see and drive it.");
            }
        });
    }

    /** Make the dash Bluetooth discoverable for 5 minutes, for a pairing attempt. */
    private void discoverable() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        conn.run(rpc -> rpc.shell(
                "am start -a android.bluetooth.adapter.action.REQUEST_DISCOVERABLE "
                        + "--ei android.bluetooth.adapter.extra.DISCOVERABLE_DURATION 300", 10_000),
                (out, error) -> status.setText(error != null
                        ? "Failed: " + error.getMessage()
                        : "Dash is discoverable for 5 min (accept the prompt on the dash via "
                                + "Mirror). Note: wireless CarPlay may still fail if Bluetooth "
                                + "has no socket manager — see Check readiness."));
    }

    /**
     * Hide (lock=true) or show (lock=false) the dash's floating nav button.
     *
     * <p>Probes the two preconditions in the same round-trip as the broadcast,
     * so a no-op gets a real diagnosis instead of a blind "if nothing changed".
     * The ball cannot exist unless its app is installed and its accessibility
     * service is enabled; when either is missing the broadcast lands on nothing.
     */
    private void navLock(boolean lock) {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        // Read install + accessibility state, then fire the broadcast. The
        // command receiver re-broadcasts to the service, which adds/removes the
        // overlay window — but only while that service is alive.
        String cmd = navProbe()
                + "am broadcast -a " + BALL_LOCK_ACTION + " -n " + BALL_RECV
                + " --ez locked " + lock + " >/dev/null 2>&1";
        conn.run(rpc -> rpc.shell(cmd, 10_000), (out, error) -> {
            if (error != null) {
                status.setText("Failed: " + error.getMessage());
                return;
            }
            String problem = navProblem(out == null ? "" : out);
            if (problem != null) {
                status.setText((lock ? "Lock" : "Unlock") + " sent, but it will do nothing:\n"
                        + problem);
            } else {
                status.setText(lock ? "Floating nav hidden (locked)."
                        : "Floating nav shown (unlocked).");
            }
        });
    }

    /** Read-only state check for the floating nav, with a plain verdict. */
    private void navCheck() {
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        status.setText("Checking floating nav…");
        conn.run(rpc -> rpc.shell(navProbe() + "true", 10_000), (out, error) -> {
            if (error != null) {
                status.setText("Check failed: " + error.getMessage());
                return;
            }
            String probe = out == null ? "" : out;
            String problem = navProblem(probe);
            if (problem != null) {
                status.setText("Floating nav is NOT ready:\n" + problem);
            } else {
                status.setText("Floating nav is ready: app installed and its accessibility "
                        + "service is enabled. Lock/Unlock will work.");
            }
        });
    }

    /**
     * Re-enable the ball's accessibility service from root, the fix for the most
     * common failure (service dropped after a dash reboot). Appends the service
     * to enabled_accessibility_services only if absent, flips the master
     * accessibility switch on, then opens the app so the overlay redraws.
     */
    private void navFix() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        status.setText("Re-enabling floating nav…");
        // One shell line: bail if not installed, else add the service when
        // missing and turn accessibility on, then relaunch the app.
        String cmd =
                "if [ \"$(pm list packages " + BALL_PKG + " | grep -c " + BALL_PKG + ")\" = 0 ]; "
                        + "then echo RESULT=NOT_INSTALLED; else "
                        + "C=$(settings get secure enabled_accessibility_services); "
                        + "if echo \"$C\" | grep -q " + BALL_SVC + "; then echo RESULT=ALREADY; "
                        + "else if [ \"$C\" = null ] || [ -z \"$C\" ]; then N=" + BALL_SVC + "; "
                        + "else N=$C:" + BALL_SVC + "; fi; "
                        + "settings put secure enabled_accessibility_services \"$N\"; "
                        + "echo RESULT=ENABLED; fi; "
                        + "settings put secure accessibility_enabled 1; "
                        + "am start -n " + BALL_ACT + " >/dev/null 2>&1; fi";
        conn.run(rpc -> rpc.shell(cmd, 12_000), (out, error) -> {
            if (error != null) {
                status.setText("Fix failed: " + error.getMessage());
                return;
            }
            String r = val(out == null ? "" : out, "RESULT");
            if ("NOT_INSTALLED".equals(r)) {
                status.setText("S1 Nav app is not installed.\nInstall Pegasus-S1Nav.apk from "
                        + "Root shell → \"Pick & install APK\" first.");
            } else if ("ALREADY".equals(r)) {
                status.setText("Accessibility service was already on. Reopened S1 Nav so the "
                        + "ball redraws — if it still won't show, tap Unlock.");
            } else if ("ENABLED".equals(r)) {
                status.setText("Fixed: turned the ball's accessibility service back on and "
                        + "reopened S1 Nav. Lock/Unlock should work now.");
            } else {
                status.setText("Fix ran but returned no status.\n" + (out == null ? "" : out.trim()));
            }
        });
    }

    /** Shell prelude that emits INST= and ACC= lines for {@link #navProblem}. */
    private static String navProbe() {
        return "echo INST=$(pm list packages " + BALL_PKG + " | grep -c " + BALL_PKG + "); "
                + "echo ACC=$(settings get secure enabled_accessibility_services); ";
    }

    /**
     * Turn an {@link #navProbe} result into a one-line problem, or null when the
     * nav is ready to toggle.
     */
    private static String navProblem(String out) {
        if (!"1".equals(val(out, "INST"))) {
            return "S1 Nav app is not installed — install Pegasus-S1Nav.apk from "
                    + "Root shell → \"Pick & install APK\".";
        }
        String acc = val(out, "ACC");
        if (!acc.contains(BALL_SVC)) {
            return "S1 Nav is installed, but its accessibility service is OFF, so there is no "
                    + "overlay to show or hide. Tap \"🛠 Fix nav\" to turn it back on.";
        }
        return null;
    }

    /** Open the floating-nav app's own settings on the dash, to pick shortcuts. */
    private void openNav() {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        conn.run(rpc -> rpc.shell("am start -n " + BALL_ACT, 10_000),
                (out, error) -> status.setText(error != null
                        ? "Failed: " + error.getMessage()
                        : "Opened floating-nav settings on the dash — configure shortcuts there."));
    }

    /**
     * Probe the three facts that decide which projection path can work, and turn
     * them into a plain verdict. The Bluetooth RFCOMM socket-manager state cannot
     * be proven from the shell without an app that opens a socket, so this reports
     * the facts it can read and names the known MoveOS limitation.
     */
    private void checkReadiness() {
        if (!conn.ready()) {
            status.setText("Connect to the scooter first.");
            return;
        }
        status.setText("Checking…");
        String probe = "echo HOST=$(pm list features | grep -c android.hardware.usb.host);"
                + "echo UDC=$(ls /sys/class/udc 2>/dev/null | head -1);"
                + "echo BT=$(settings get global bluetooth_on);"
                + "echo WIFI=$(ip addr show wlan0 2>/dev/null | grep -c inet);"
                + "echo AA=$(pm list packages | grep -c " + AA_PKG + ");"
                + "echo CP=$(pm list packages | grep -c " + CP_PKG + ")";
        conn.run(rpc -> rpc.shell(probe, 15_000), (out, error) -> {
            if (error != null) {
                status.setText("Check failed: " + error.getMessage());
                return;
            }
            status.setText(verdict(out == null ? "" : out));
        });
    }

    private static String verdict(String out) {
        boolean usbHost = val(out, "HOST").equals("1") || !val(out, "UDC").isEmpty();
        boolean btOn = val(out, "BT").equals("1");
        boolean onWifi = intVal(out, "WIFI") > 0;
        boolean aaInstalled = intVal(out, "AA") > 0;
        boolean cpInstalled = intVal(out, "CP") > 0;

        StringBuilder sb = new StringBuilder();
        sb.append("Apps:  Android Auto ").append(aaInstalled ? "✓" : "—")
                .append("   CarPlay ").append(cpInstalled ? "✓" : "—").append('\n');
        sb.append("USB host: ").append(usbHost ? "yes (wired works)" : "no").append('\n');
        sb.append("Bluetooth: ").append(btOn ? "on" : "off").append('\n');
        sb.append("On a Wi-Fi network: ").append(onWifi ? "yes" : "no").append("\n\n");

        sb.append("Verdict\n");
        sb.append("• Wireless CarPlay: likely BLOCKED. MoveOS Bluetooth has no RFCOMM "
                + "socket manager, so iAP2 cannot start and the iPhone will not pair.\n");
        if (onWifi) {
            sb.append("• Wi-Fi Direct will not start while the dash is on this Wi-Fi/hotspot. "
                    + "Turn it off or use \"Existing Wi-Fi (same LAN)\" in the app.\n");
        }
        if (usbHost) {
            sb.append("• USB host is available → use a USB data cable from the iPhone to the "
                    + "scooter's USB port (wired CarPlay). This is the path that works.\n");
        } else {
            sb.append("• No USB host detected — wired CarPlay may not be possible on this port.\n");
        }
        sb.append("• Android Auto wireless is blocked by Google for Android-10 head units; "
                + "use USB for AA too.");
        return sb.toString();
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
            String v = val(out, key);
            return v.isEmpty() ? 0 : Integer.parseInt(v.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
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
    }

    @Override
    public void onHide() {
    }
}
